(ns seiten.components.common
  (:require ["he" :as he]
            [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [clojure.string :as str]
            [comp.localization :as loc]
            [comp.snippets :as snip]
            [directus.core :as d]
            [psite-datetime.core :as td]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [psite-utils.core :as putils]))

(defn generate-vorschautext
  ([instring] (generate-vorschautext instring 35))
  ([instring wordnum]
   (when instring
     (let [plain     (-> instring
                         (str/replace #"<[^>]*>" " ")
                         he/decode
                         (str/replace #"\s+" " ")
                         str/trim)
           words     (str/split plain #" +")
           wordcount (count words)]
       (if (> wordcount wordnum)
         (str (str/join " " (take wordnum words)) "…")
         plain)))))

(defn instrumente
  [instrumente-csv]
  (when instrumente-csv
    (keep
     #((keyword %)
       {:klarinette "/from_reservoir/klarinette.svg"
        :klavier    "/from_reservoir/klavier.svg"
        :violine    "/from_reservoir/violine.svg"})
     (str/split instrumente-csv #" *, *"))))

(defn mainpanel
  [& comps]
  [:section
   (into [:div.panel.is-primary.mainpanel] comps)])

(defn youtube-embed
  [link _controls? width height]
  (when link
    (let [id        (second (re-find #"watch\?v=(.+$)" link))
          embedlink (str "https://www.youtube.com/embed/" (or id link))]
      [:iframe
       {:width       (str width)
        :height      (str height)
        :src         embedlink
        :frameborder "0"
        :allow       "accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture"}])))

(defn format-sheet-image
  [layout dbild]
  (when dbild
    [:img
     {:class (str "sheet__bild "
                  (case layout
                    "rechts" "sheet__bild--v"
                    "links"  "sheet__bild--vl"
                    "sheet__bild--h"))
      :src   (d/image-by-preset "w1200" dbild)}]))

(defn format-termin-bigsheet
  [req {:keys [id datum uhrzeit abgesagt stadt]}]
  (let [locale (:locale req)
        datzeit (when uhrzeit
                  (td/unify-date-and-time
                   (t.format/unparse (t.format/formatters :date) datum)
                   uhrzeit))]
    [:a {:href (str (routing/reverse-match req :termine {}) "#termin-" id)}
     [:div.programm__termin
      {:style (when abgesagt "text-decoration: line-through;")}
      [:div.programm__termindatum
       (loc/by-locale locale (td/format-dates-wordy datum 1 nil))
       (when datzeit
         (str ", " (loc/by-locale locale (td/format-times datzeit))))]
      [:div.programm__terminstadt stadt]]]))

(defn format-termine-bigsheet
  [req termine ankuendigung ersatztext]
  (if (seq termine)
    (list
     [:div.programm__terminankuendigung ankuendigung]
     (map (partial format-termin-bigsheet req) (take 3 termine)))
    ersatztext))
