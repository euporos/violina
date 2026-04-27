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
                {:select [s/presse-id s/presse-medium s/presse-autor
                          s/presse-datum s/presse-ueberschrift
                          s/presse-volltext s/presse-link s/presse-bild
                          s/presse-nurbild s/presse-sprache]
                 :from   [:presse]
                 :where  [:= s/presse-id id]
                 :limit  1})
          row  (first rows)]
    (if row
      {:status  200
       :headers {"Cache-Control" "no-store"}
       :body    (-> row
                    (assoc :bild-url (when (:bild row)
                                       (d/image-by-preset "w1600" (:bild row))))
                    (update :datum (fn [d]
                                     (when d
                                       (apply str (loc/by-locale (:locale req)
                                                                 (td/format-dates-wordy d 0 true)))))))}
      {:status  404
       :headers {"Cache-Control" "no-store"}
       :body    {:error "not-found"}})))
