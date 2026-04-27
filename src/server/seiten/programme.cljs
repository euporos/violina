(ns seiten.programme
  (:require [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [psite-utils.core :as putils]
            [seiten.components.common :as cmn]
            [seiten.templates :as templates]))

;; ##############
;; ### Übersicht
;; ##############

(defn- uebersichts-query [locale]
  {:select   [s/programme-id s/programme-slug s/programme-aktualitaet
              s/programme-bild s/programme-vertikal
              (db/localized s/programme-titel locale)
              (db/localized s/programme-beschreibung locale)
              [:kuenstler.instrumente :instrumente]]
   :from     [[s/programme_t :programme]]
   :left-join [:kuenstler [:= s/programme-kuenstler :kuenstler.id]]
   :order-by [[s/programme-id :desc]]})

(defn- format-preview [req {:keys [id aktualitaet titel beschreibung slug instrumente]
                            :as _row}]
  [:div.smallsheet.smallsheet--progprev
   [:a {:href (routing/reverse-match req :programm
                                     {:programm-id   id
                                      :programm-slug (or (some-> slug putils/slugify)
                                                         (str id))})}
    [:div.smallsheet__header.smallsheet__header--progprev
     [:div.progprev__title titel]]
    [:div.progprev__body
     (cmn/generate-vorschautext beschreibung 45)
     (case aktualitaet
       "vergangen" "Nicht mehr aktuell"
       "geplant"   "In Planung"
       "")]
    [:div.progprev__footer
     (for [src (cmn/instrumente instrumente)]
       [:img.instrument {:src src}])]]])

(defhandler handler [req]
  (p/let [locale    (:locale req)
          programme (db/query (uebersichts-query locale))
          rendered  (templates/head-and-foot-dynamic
                     req {:titel (snip/programme locale)}
                     [:div.mainframe
                      [:div.progprevs
                       (map (partial format-preview req) programme)]])]
    (ph/html->response rendered)))

;; ##################
;; ### Einzelansicht
;; ##################

(defn- qualified-coalesce
  "Build a COALESCE over `<alias>.<field>_<lang>` columns; aliased as `out-key`."
  [alias field locale fallback-order out-key]
  (let [chain (into [locale] (remove #{locale} fallback-order))]
    [(into [:coalesce]
           (map #(keyword (str (name alias) "." field "_" (name %))) chain))
     out-key]))

(def fallback [:de :en :uk])

(defn- single-query [locale id]
  {:select    [s/programme-id s/programme-slug s/programme-bild s/programme-vertikal
               (qualified-coalesce :programme "titel"        locale fallback :titel)
               (qualified-coalesce :programme "beschreibung" locale fallback :beschreibung)
               (qualified-coalesce :programme "stuecke"      locale fallback :stuecke)
               [:kuenstler.id   :kuenstler_id]
               [:kuenstler.slug :kuenstler_slug]
               (qualified-coalesce :kuenstler "name" locale fallback :kuenstler_name)
               [:directus_files.width  :bild_width]
               [:directus_files.height :bild_height]]
   :from      [[s/programme_t :programme]]
   :left-join [[s/kuenstler_t :kuenstler]
               [:= s/programme-kuenstler :kuenstler.id]
               :directus_files
               [:= :directus_files.id s/programme-bild]]
   :where     [:= s/programme-id id]})

(defn- termine-for-programm-query [locale programm-id]
  (let [today (t.format/unparse (t.format/formatters :date) (t/now))]
    {:select    [s/termine-id s/termine-datum s/termine-uhrzeit s/termine-abgesagt
                 [s/orte-stadt :stadt]]
     :from      [:termine]
     :left-join [:orte [:= s/termine-ort s/orte-id]]
     :where     [:and
                 [:= s/termine-programm programm-id]
                 [:>= s/termine-datum today]]
     :order-by  [s/termine-datum s/termine-uhrzeit]
     :limit     3}))

(defn- format-full [req programm termine]
  (let [{:keys [titel beschreibung stuecke bild bild_width bild_height
                kuenstler_id kuenstler_slug kuenstler_name]} programm
        locale     (:locale req)
        vertikal?  (and bild_width bild_height (< bild_width bild_height))]
    [:div.sheet.programm-h
     [:div.sheet__header
      (when kuenstler_name
        [:div.programm__performer
         [:a {:href (routing/reverse-match req :kuenstler-single
                                           {:kuenstler-id   kuenstler_id
                                            :kuenstler-slug (or (some-> kuenstler_slug putils/slugify)
                                                                (str kuenstler_id))})}
          kuenstler_name]])
      [:div titel]]
     (when bild
       [:img {:class (if vertikal? "sheet__bild--v" "sheet__bild--h")
              :src   (d/image-by-preset "w1600" bild)
              :alt   "featured"}])
     [:div.sheet__fliesstext
      (when stuecke
        [:ol {:class (str "programm__setlist "
                          (if vertikal?
                            "programm__setlist--v"
                            "programm__setlist--h"))}
         (ph/dangerous-html stuecke)])
      (when beschreibung (ph/dangerous-html beschreibung))]
     [:div.programm__termine
      (cmn/format-termine-bigsheet
       req termine
       (snip/termine-programm locale)
       [:span
        (snip/beiinteresse locale)
        [:a.standardlink
         {:href (str (routing/reverse-match req :home {}) "#contact")}
         (snip/kontaktierensiemich locale)] "."])]]))

(defhandler single-handler [req]
  (p/let [locale       (:locale req)
          id           (routing/path-param req :programm-id)
          slug         (routing/path-param req :programm-slug)
          rows         (db/query (single-query locale id))
          programm     (first rows)
          true-slug    (or (some-> (:slug programm) putils/slugify) (str id))]
    (cond
      (nil? programm)
      (r/not-found (str "Programm " id " not found"))

      (not= slug true-slug)
      (r/see-other
       (routing/reverse-match req :programm
                              {:programm-id   id
                               :programm-slug true-slug}))

      :else
      (p/let [termine  (db/query (termine-for-programm-query locale id))
              rendered (templates/head-and-foot-dynamic
                        req {:titel       (:titel programm)
                             :og-image    (when (:bild programm)
                                            (d/image-by-preset "w1600" (:bild programm)))
                             :breadcrumbs [(snip/programme locale) [:programme {}]
                                           (:titel programm) (:url req)]}
                        [:div.mainframe
                         (format-full req programm termine)])]
        (ph/html->response rendered)))))
