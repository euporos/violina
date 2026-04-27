(ns seiten.galerie
  (:require [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(defn- thumbnail [{:keys [bild width height beschreibung]}]
  [:a.galerie__thumb
   {:href             (d/image-by-preset "w1200" bild)
    :data-pswp-width  width
    :data-pswp-height height
    :target           "_blank"}
   [:img.galerie__thumb-img
    {:src     (d/image-by-preset "w600" bild)
     :loading "lazy"
     :alt     (or beschreibung "")}]])

(defn- kuenstler-section [{:keys [name images]}]
  [:section.galerie__section
   [:h2.galerie__heading name]
   [:div.galerie__grid.pswp-gallery
    (map thumbnail images)]])

(defhandler handler [req]
  (p/let [locale   (:locale req)
          rows     (db/query
                    {:select    [s/kuenstler-id
                                 (db/localized s/kuenstler-name locale)
                                 [s/bilder-id          :bilder-id]
                                 [s/bilder-bild        :bild]
                                 [s/bilder-beschreibung :beschreibung]
                                 [:directus_files.width  :width]
                                 [:directus_files.height :height]]
                     :from      [[s/kuenstler_t :kuenstler]]
                     :join      [:bilder         [:= s/bilder-kuenstler s/kuenstler-id]
                                 :directus_files [:= :directus_files.id s/bilder-bild]]
                     :where     [:= s/bilder-status "published"]
                     :order-by  [s/kuenstler-id s/bilder-id]})
          sections (->> rows
                        (group-by (juxt :id :name))
                        (sort-by (comp first key))
                        (map (fn [[[_ name] imgs]]
                               {:name name :images imgs})))
          rendered (templates/head-and-foot-dynamic
                    req {:titel (snip/galerie locale)}
                    [:div.mainframe
                     [:div.galerie
                      (map kuenstler-section sections)]])]
    (ph/html->response rendered)))
