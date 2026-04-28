(ns seiten.kuenstler
  (:require [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [clojure.string :as str]
            [comp.localization :as loc]
            [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-datetime.core :as td]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [psite-utils.core :as putils]
            [seiten.templates :as templates]))

(defn- format-besetzung [besetzung]
  [:div.besetzung
   [:table.besetzung__table
    (for [line (str/split besetzung "\n")
          :let [[player instrument] (str/split line #" *:: *")]
          :when player]
      [:tr.besetzung__item
       [:th.besetzung__instrumentalist player]
       [:th " – "]
       [:th.besetzung__instrument instrument]])]])

(defn- uebersicht-item [req {:keys [id name besetzung bild slug]}]
  [:a {:href (routing/reverse-match req :kuenstler-single
                                    {:kuenstler-id   id
                                     :kuenstler-slug (putils/slugify (or slug (str id)))})}
   [:div.smallsheet.smallsheet--kprev
    [:div.smallsheet__header
     [:span.smallsheet__name name]]
    [:div.smallsheet__body.dummytable
     (when bild
       [:img.smallsheet__bild {:src (d/image-by-preset "w600" bild)}])
     [:div.dummyrow
      [:div.dummycell.dummycell--besetzung
       (when (seq besetzung)
         (format-besetzung besetzung))]]]]])

(defhandler handler [req]
  (p/let [locale    (:locale req)
          kuenstler (db/query
                     {:select   [s/kuenstler-id
                                 s/kuenstler-slug
                                 s/kuenstler-bild
                                 (db/localized s/kuenstler-name locale)
                                 (db/localized s/kuenstler-besetzung locale)]
                      :from     [[s/kuenstler_t s/kuenstler]]
                      :order-by [s/kuenstler-id]})
          rendered  (templates/head-and-foot-dynamic
                     req {:titel        (snip/kuenstler locale)
                          :beschreibung (snip/meta-kuenstler locale)}
                     [:div.mainframe
                      (map (partial uebersicht-item req) kuenstler)])]
    (ph/html->response rendered)))

;; ##### Einzelansicht #####

(defn- format-termin [req {:keys [id datum uhrzeit abgesagt stadt]}]
  (let [locale (:locale req)]
    [:a {:href (str "/" (name locale) "/termine#termin-" id)}
     [:div.programm__termin
      {:style (when abgesagt "text-decoration: line-through;")}
      [:div.programm__termindatum
       (loc/by-locale locale (td/format-dates-wordy datum 1 nil))
       (when uhrzeit
         (let [datzeit (td/unify-date-and-time
                        (t.format/unparse (t.format/formatters :date) datum)
                        uhrzeit)]
           (str ", " (loc/by-locale locale (td/format-times datzeit)))))]
      [:div.programm__terminstadt stadt]]]))

(defn- format-termine [req termine ankuendigung]
  (if (seq termine)
    (list
     [:div.programm__terminankuendigung ankuendigung]
     (map (partial format-termin req) (take 3 termine)))
    "Derzeit keine Konzerte"))

(defn- format-voll [req {:keys [bild bild_width bild_height name beschreibung ankuendigung]} termine]
  [:div.sheet
   [:div.sheet__header name]
   [:div.sheet__body
    (when bild
      [:img {:class (str "sheet__bild "
                         (if (and bild_width bild_height (< bild_width bild_height))
                           "sheet__bild--v"
                           "sheet__bild--h"))
             :src   (d/image-by-preset "w1200" bild)}])
    [:div.sheet__fliesstext
     (ph/dangerous-html beschreibung)]
    [:div.programm__termine
     (format-termine req termine (str ankuendigung ":"))]]])

(defhandler single-handler [req]
  (p/let [locale       (:locale req)
          kuenstler-id (routing/path-param req :kuenstler-id)
          slug         (routing/path-param req :kuenstler-slug)
          kuenstler-rows (db/query
                         {:select    [s/kuenstler-id
                                      s/kuenstler-slug
                                      s/kuenstler-bild
                                      s/kuenstler-og_image
                                      (db/localized s/kuenstler-name locale)
                                      (db/localized s/kuenstler-beschreibung locale)
                                      (db/localized s/kuenstler-ankuendigung locale)
                                      (db/localized s/kuenstler-meta_description locale)
                                      [:directus_files.width :bild_width]
                                      [:directus_files.height :bild_height]]
                          :from      [[s/kuenstler_t s/kuenstler]]
                          :left-join [:directus_files [:= :directus_files.id s/kuenstler-bild]]
                          :where     [:= s/kuenstler-id kuenstler-id]})
          kuenstler      (first kuenstler-rows)
          true-slug      (putils/slugify (or (:slug kuenstler) (str kuenstler-id)))]
    (if-not (= slug true-slug)
      (r/see-other
       (routing/reverse-match req :kuenstler-single
                              {:kuenstler-id   kuenstler-id
                               :kuenstler-slug true-slug}))
      (p/let [today   (t.format/unparse (t.format/formatters :date) (t/now))
              termine (db/query
                       {:select    [s/termine-id s/termine-datum s/termine-uhrzeit
                                    s/termine-abgesagt [s/orte-stadt :stadt]]
                        :from      [:termine]
                        :left-join [:orte [:= s/termine-ort s/orte-id]]
                        :where     [:and
                                    [:= s/termine-kuenstler kuenstler-id]
                                    [:>= s/termine-datum today]]
                        :order-by  [s/termine-datum s/termine-uhrzeit]
                        :limit     3})
              rendered (templates/head-and-foot-dynamic
                        req {:titel        (:name kuenstler)
                             :og-image     (d/image-by-preset "og-image"
                                                              (or (:og_image kuenstler)
                                                                  (:bild kuenstler)))
                             :beschreibung (:meta_description kuenstler)
                             :breadcrumbs  [[(snip/kuenstler (:locale req)) [:kuenstler {}]]
                                            [(:name kuenstler) (:url req)]]}
                        [:div.mainframe
                         (format-voll req kuenstler termine)])]
        (ph/html->response rendered)))))
