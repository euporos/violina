(ns seiten.home
  (:require [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [comp.localization :as loc]
            [comp.snippets :as snip]
            [config.env :as env]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-datetime.core :as td]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.components.common :as cmn]
            [seiten.templates :as templates]))

;; ##################
;; #### Queries #####
;; ##################

(defn- begruessung-query [locale]
  {:select [s/begruessung-id
            s/begruessung-video
            (db/localized s/begruessung-haupttext locale)
            (db/localized s/begruessung-untertitel locale)
            (db/localized s/begruessung-meta_description locale)]
   :from   [[s/begruessung_t :begruessung]]
   :limit  1})

(defn- termine-upcoming-query [locale]
  (let [today (t.format/unparse (t.format/formatters :date) (t/now))]
    {:select   [s/termine-id s/termine-datum s/termine-uhrzeit s/termine-abgesagt
                s/termine-sonderkuenstler
                [s/orte-stadt :stadt]
                [s/kuenstler-instrumentbild :instrumentbild]
                (db/localized s/kuenstler-name locale)]
     :from     [:termine]
     :left-join [:orte [:= s/termine-ort s/orte-id]
                 [s/kuenstler_t :kuenstler] [:= s/termine-kuenstler s/kuenstler-id]]
     :where    [:>= s/termine-datum today]
     :order-by [s/termine-datum s/termine-uhrzeit]
     :limit    3}))

(defn- presse-query [locale-or-nil]
  (cond-> {:select   [s/presse-id s/presse-medium s/presse-ueberschrift
                      s/presse-datum s/presse-sprache]
           :from     [:presse]
           :where    [:and
                      [:not= s/presse-ueberschrift nil]]
           :order-by [[s/presse-datum :desc]]
           :limit    3}
    locale-or-nil
    (assoc :where [:and
                   [:= s/presse-sprache (name locale-or-nil)]
                   [:not= s/presse-ueberschrift nil]])))

(defn- featured-cd-query []
  {:select [s/cds-id s/cds-recto s/cds-titel]
   :from   [:cds]
   :where  [:= s/cds-id 7]
   :limit  1})

;; ##################
;; #### Sections ####
;; ##################

(defn- begruessung-section [req begruessung]
  (let [locale (:locale req)]
    (list
     [:div#homesection--begruessung.homesection
      [:div.begruessung
       [:div.begruessung__violina
        [:div.begruessung__name
         (snip/violina locale) [:br] (snip/petrychenko locale)]
        [:div.begruessung__untertitel
         [:div.begruessung__untertitelelement
          (:untertitel begruessung)]]]]]
     [:div.homesection.homesection--begruessungstext
      (ph/dangerous-html (:haupttext begruessung))
      [:div.homesection__link-to-bio
       [:a {:href (routing/reverse-match req :kuenstler-single
                                         {:kuenstler-id   1
                                          :kuenstler-slug "solo"})}
        "→ " (snip/mehr locale)]]])))

(defn- video-section [begruessung]
  (when-let [link (:video begruessung)]
    [:div.video-container
     (cmn/youtube-embed link nil 650 315)]))

(defn- einzeltermin [{:keys [locale] :as req}
                     {:keys [id datum uhrzeit sonderkuenstler abgesagt
                             stadt instrumentbild]
                      kuenstler_name :name}]
  (let [datzeit (when uhrzeit
                  (td/unify-date-and-time
                   (t.format/unparse (t.format/formatters :date) datum) uhrzeit))
        zeitstring (loc/by-locale locale (td/format-dates-wordy (or datzeit datum) 0 nil))
        kuenstlername (or sonderkuenstler kuenstler_name)
        bild (when instrumentbild (d/image-by-preset "h600" instrumentbild))]
    [:a.homeelem.homeelem--hebeeffekt.terminvor
     {:href  (str (routing/reverse-match req :termine {}) "#termin-" id)
      :style (when bild
               (str "background-image: url(" bild ");"
                    "background-position: center;"))}
     [:div.dummytable
      [:div.dummyrow
       [:div.terminvor__text.terminvor__text--zeit zeitstring]]
      [:div.dummyrow
       [:div.terminvor__text.terminvor__text--ort stadt]]
      [:div.dummyrow
       [:div.terminvor__text.terminvor__text--kuenstler
        (if abgesagt
          (str (snip/abgesagt locale) "!")
          kuenstlername)]]]]))

(defn- termin-section [req termine]
  [:div.hmleiste
   (map (partial einzeltermin req) termine)])

(defn- einzelartikel [req {:keys [id medium ueberschrift]}]
  [:a.homeelem.homeelem--hebeeffekt.hmartikel
   {:href (routing/reverse-match req :presse-artikel {:artikel-id id})}
   [:div.dummytable
    [:div.dummyrow
     [:div.hmartikel__medium medium]]
    [:div.dummyrow
     [:div.hmartikel__ueberschrift ueberschrift]]]])

(defn- presse-section [req artikel]
  [:div.hmleiste
   (map (partial einzelartikel req) artikel)])

(defn- audio-player [asset-name]
  [:audio {:style    "width: 40rem; max-width: 100%;"
           :controls true
           :src      (str (env/setting :directus-url) "assets/" asset-name)}])

(defn- wdr []
  [:div
   [:a {:href "https://www1.wdr.de/mediathek/audio/wdr3/wdr3-tonart/audio-ukrainische-klaviermusik---violina-petrychenko-im-gespraech-100.html"
        :target "blank"
        :style  "mix-blend-mode: darken; max-width: 60rem;"}
    [:img {:width "100%" :style "max-width: 60rem;" :src "/imgs/wdr.png"}]]
   [:div (audio-player "f83a72ed-3e34-4502-9c3e-cb963910b8e9.mp3")]])

(defn- rbb []
  [:div
   [:a {:href "https://www.rbb-online.de/rbbkultur/themen/musik/album_der_woche/violina-petrychenko-mrii-ukrainian-hope.html"
        :target "blank"
        :style  "mix-blend-mode: darken; max-width: 60rem;"}
    [:img {:width "100%" :style "max-width: 40rem; margin-top: 1rem;" :src "/imgs/rbb.jpg"}]]
   [:div {:style "margin-top: .5rem;"}
    (audio-player "af37187a-aa1f-4d6d-a6f4-31c863f67fd3.mp3")]])

#_(defn- zdf []
    [:a {:href "https://www.zdf.de/nachrichten/zdf-morgenmagazin/violina-petrychenko-ukrainische-suite-vesnyanka-100.html"
         :target "blank"}
     [:img {:width "100%" :style "max-width: 60rem; margin-top: 1rem;" :src "/imgs/moma.jpg"}]])

(def img-style
  "max-width: 40rem; margin-top: 1rem; width: 100%; height: 40rem; object-fit: cover; object-position: 25% 15%;")

(defn- wdr2 []
  [:div
   [:img {:src   (str (env/setting :directus-url)
                      "assets/02bb5308-bdaa-4bed-a46e-ccc045af164f.jpg?key=w1200")
          :width "100%" :style img-style}]
   [:br]
   (audio-player "b4ccd609-3586-4a64-801d-f8681dfaf992.mp3")])

(defn- radio-islandia []
  [:div
   [:img {:src   (str (env/setting :directus-url)
                      "assets/02bb5308-bdaa-4bed-a46e-ccc045af164f.jpg?key=w1200")
          :width "100%" :style img-style}]
   [:br]
   (audio-player "c2ba5d5d-a92b-4c5e-ab41-306dc09e1ab7.mp3")])

(defn- cd [req featured-cd]
  (when featured-cd
    [:div
     [:a {:href (routing/reverse-match req :cds {})}
      [:img {:width "70%" :style "max-width: 60rem; margin-top: 1rem;"
             :src   (d/image-by-preset "w1200" (:recto featured-cd))}]]
     [:br] [:br]
     [:a {:href "https://o-ton.online/medien/o-ton-cd-mrii-petrychenko-zerban-230116/"}
      [:div.homesection__ueberschrift "Audio-Rezension bei O-Ton:"]
      [:img {:width "70%" :style "max-width: 60rem; margin-top: 1rem;"
             :src   "/imgs/zerban_mrii.png"}]]]))

(defn- festival [req]
  [:div
   [:a {:href (str "https://sounds-of-ukraine.de/" (name (:locale req)) "/")}
    [:img {:width "70%" :style "max-width: 60rem; margin-top: 1rem;"
           :src   "https://sounds-of-ukraine.de/imgs/sou_2024_optimized.jpg"}]]])

(defn- homesection [title content]
  [:div.homesection
   (when title [:div.homesection__ueberschrift title])
   content])

(def ^:private paedagogik-home-text
  {:de {:title "Klavierunterricht in Köln"
        :body  "Ich gebe Klavierunterricht in Köln für ambitionierte und fortgeschrittene Pianistinnen und Pianisten."
        :link  "→ mehr erfahren"}
   :en {:title "Piano lessons in Cologne"
        :body  "I teach piano in Cologne for ambitious and advanced pianists."
        :link  "→ learn more"}
   :uk {:title "Уроки фортепіано в Кельні"
        :body  "Я викладаю фортепіано в Кельні для амбітних піаністів і піаністок просунутого рівня."
        :link  "→ дізнатися більше"}
   :it {:title "Lezioni di pianoforte a Colonia"
        :body  "Insegno pianoforte a Colonia a pianisti ambiziosi di livello avanzato."
        :link  "→ per saperne di più"}})

(defn- paedagogik-section [req]
  (let [{:keys [title body link]} (get paedagogik-home-text (:locale req)
                                       (:de paedagogik-home-text))]
    (homesection title
                 [:div.homesection__paedagogik
                  [:p body]
                  [:a {:href (routing/reverse-match req :paedagogik {})} link]])))

;; ##################
;; #### Handler #####
;; ##################

(defhandler blankhome [req]
  (let [coerced-locale (:locale req)
        query-string   (:query-string req)
        target         (str (routing/reverse-match req :home
                                                   {:locale (or (#{:de :en :uk} coerced-locale) :de)})
                            (when query-string (str "?" query-string)))]
    (r/found target)))

(defhandler handler [req]
  (p/let [locale         (:locale req)
          [bgs termine artikel-locale artikel-any cds]
          (p/all
           [(db/query (begruessung-query locale))
            (db/query (termine-upcoming-query locale))
            (db/query (presse-query locale))
            (db/query (presse-query nil))
            (db/query (featured-cd-query))])
          begruessung    (first bgs)
          featured-cd    (first cds)
          artikel        (->> (concat artikel-locale artikel-any)
                              (distinct)
                              (take 3))
          rendered (templates/head-and-foot-dynamic
                    req {:og-image     (str (or (get-in req [:config :site-url]) "")
                                            "/imgs/og_home.jpg")
                         :beschreibung (:meta_description begruessung)}
                    (cmn/mainpanel
                     [:div.homesections
                      (begruessung-section req begruessung)
                      (homesection "Festival: Sounds of Ukraine" (festival req))
                      (video-section begruessung)
                      (homesection [:a {:href (routing/reverse-match req :cds {})}
                                    (snip/meine-neue-cd locale)]
                                   (cd req featured-cd))
                      (homesection (snip/home-konzerte locale)
                                   (termin-section req termine))
                      (when (= :de locale)
                        (homesection "»Mrii« als Album der Woche beim RBB" (rbb)))
                      (when (= :de locale)
                        (homesection "Interview bei WDR TonArt" (wdr2)))
                      (when (= :uk locale)
                        (homesection "Інтерв'ю про Мрії на Радіо Ісландія" (radio-islandia)))
                      (homesection (snip/home-presse locale)
                                   (presse-section req artikel))
                      (paedagogik-section req)
                      (when (= :de locale)
                        (homesection nil (wdr)))]))]
    (ph/html->response rendered)))
