(ns api.presse
  (:require [comp.localization :as loc]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-datetime.core :as td]
            [psite-routing.core :as routing]))

(defhandler detail-handler [req]
  (p/let [id   (routing/path-param req :artikel-id)
          rows (db/query
                {:select    [s/presse-id s/presse-medium s/presse-autor
                             s/presse-datum s/presse-ueberschrift
                             s/presse-volltext s/presse-link s/presse-bild
                             s/presse-nurbild s/presse-sprache
                             [:directus_files.width  :bild-width]
                             [:directus_files.height :bild-height]]
                 :from      [:presse]
                 :left-join [:directus_files [:= :directus_files.id s/presse-bild]]
                 :where     [:= s/presse-id id]
                 :limit     1})
          row  (first rows)]
    (if row
      {:status  200
       :headers {"Cache-Control" "no-store"}
       :body    (-> row
                    (assoc :bild-url (when (:bild row)
                                       (d/image-by-preset "w1600" (:bild row))))
                    (update :datum (fn [d]
                                     (when d
                                       (->> (td/format-dates-wordy d 0 true)
                                            (loc/by-locale (:locale req))
                                            (tree-seq coll? seq)
                                            (filter string?)
                                            (apply str))))))}
      {:status  404
       :headers {"Cache-Control" "no-store"}
       :body    {:error "not-found"}})))
