(ns seiten.kuenstler
  (:require [clojure.string :as str]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [comp.snippets :as snip]
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
                     req {:titel (snip/kuenstler locale)}
                     [:div.mainframe
                      (map (partial uebersicht-item req) kuenstler)])]
    (ph/html->response rendered)))

(defhandler single-handler [req]
  (let [kuenstler-id (routing/path-param req :kuenstler-id)
        slug         (routing/path-param req :kuenstler-slug)]
    (ph/html->response
     (templates/head-and-foot-blank
      req
      {:titel (str "Künstler " kuenstler-id)}
      {}
      [:section.section
       [:div.container
        [:h1.title (str "Künstler " kuenstler-id " (" slug ") — TODO")]]]))))
