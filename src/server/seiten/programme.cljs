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
                     req {:titel        (snip/programme locale)
                          :beschreibung (snip/meta-programme locale)}
                     [:div.mainframe
                      [:div.progprevs
                       (map (partial format-preview req) programme)]])]
    (ph/html->response rendered)))

;; ##################
;; ### Einzelansicht
;; ##################

;; NOTE: programm and kuenstler are queried separately rather than joined,
;; because joining `programme_t` and `kuenstler_t` makes their overlapping
;; translation columns (`beschreibung_de`, `meta_description_de`) ambiguous
;; in the COALESCEs that `db/localized` emits. See psite/directus-schema.

(defn- programm-query [locale id]
  {:select    [s/programme-id s/programme-slug s/programme-kuenstler
               s/programme-bild s/programme-vertikal
               (db/localized s/programme-titel locale)
               (db/localized s/programme-beschreibung locale)
               (db/localized s/programme-stuecke locale)
               [:directus_files.width  :bild_width]
               [:directus_files.height :bild_height]]
   :from      [[s/programme_t :programme]]
   :left-join [:directus_files [:= :directus_files.id s/programme-bild]]
   :where     [:= s/programme-id id]})

(defn- kuenstler-mini-query [locale kuenstler-id]
  {:select [s/kuenstler-id s/kuenstler-slug
            (db/localized s/kuenstler-name locale)]
   :from   [[s/kuenstler_t s/kuenstler]]
   :where  [:= s/kuenstler-id kuenstler-id]})

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
  (p/let [locale     (:locale req)
          id         (routing/path-param req :programm-id)
          slug       (routing/path-param req :programm-slug)
          progr-rows (db/query (programm-query locale id))
          programm   (first progr-rows)
          true-slug  (or (some-> (:slug programm) putils/slugify) (str id))]
    (cond
      (nil? programm)
      (r/not-found (str "Programm " id " not found"))

      (not= slug true-slug)
      (r/see-other
       (routing/reverse-match req :programm
                              {:programm-id   id
                               :programm-slug true-slug}))

      :else
      (p/let [[kuenstler-rows termine]
              (p/all
               [(if-let [kid (:kuenstler programm)]
                  (db/query (kuenstler-mini-query locale kid))
                  [])
                (db/query (termine-for-programm-query locale id))])
              kuenstler (first kuenstler-rows)
              programm  (assoc programm
                               :kuenstler_id   (:id kuenstler)
                               :kuenstler_slug (:slug kuenstler)
                               :kuenstler_name (:name kuenstler))
              rendered  (templates/head-and-foot-dynamic
                         req {:titel       (:titel programm)
                              :og-image    (when (:bild programm)
                                             (d/image-by-preset "og-image" (:bild programm)))
                              :breadcrumbs [[(snip/programme locale) [:programme {}]]
                                            [(:titel programm) (:url req)]]}
                         [:div.mainframe
                          (format-full req programm termine)])]
        (ph/html->response rendered)))))
