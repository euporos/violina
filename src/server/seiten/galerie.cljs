(ns seiten.galerie
  (:require [comp.snippets :as snip]
            [config.env :as env]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(defn- thumbnail [labels {:keys [bild width height beschreibung fotograf-name fotograf-link]}]
  (let [{:keys [foto-label vollaufloesung-label]} labels]
    [:a.galerie__vorschaubild
     {:href                      (d/image-by-preset "w1200" bild)
      :data-pswp-width           width
      :data-pswp-height          height
      :data-fotograf-name        (or fotograf-name "")
      :data-fotograf-link        (or fotograf-link "")
      :data-full-url             (str (env/setting :directus-url) "assets/" bild)
      :data-foto-label           foto-label
      :data-vollaufloesung-label vollaufloesung-label
      :target                    "_blank"}
     [:img {:src     (d/image-by-preset "400crop" bild)
            :width   "100%"
            :loading "lazy"
            :alt     (or beschreibung "")}]]))

(defn- kuenstler-section [labels {:keys [name images]}]
  [:div.sheet
   [:div.sheet__header name]
   [:div.sheet__body
    [:div.galerie.pswp-gallery
     (map (partial thumbnail labels) images)]]])

(defhandler handler [req]
  (p/let [locale   (:locale req)
          labels   {:foto-label           (snip/foto locale)
                    :vollaufloesung-label (snip/vollaufloesung locale)}
          rows     (db/query
                    {:select    [s/kuenstler-id
                                 (db/localized s/kuenstler-name locale)
                                 [s/bilder-id          :bilder-id]
                                 [s/bilder-bild        :bild]
                                 [s/bilder-beschreibung :beschreibung]
                                 [:directus_files.width  :width]
                                 [:directus_files.height :height]
                                 [s/fotografen-name :fotograf-name]
                                 [s/fotografen-link :fotograf-link]]
                     :from      [[s/kuenstler_t :kuenstler]]
                     :join      [:bilder         [:= s/bilder-kuenstler s/kuenstler-id]
                                 :directus_files [:= :directus_files.id s/bilder-bild]]
                     :left-join [:fotografen     [:= s/bilder-fotograf s/fotografen-id]]
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
                     (map (partial kuenstler-section labels) sections)])]
    (ph/html->response rendered)))
