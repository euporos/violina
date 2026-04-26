(ns seiten.cds
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Discs"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Discs — TODO"]]])))

(defhandler single-handler [req]
  (let [cd-id (routing/path-param req :cd-id)]
    (ph/html->response
     (templates/head-and-foot-blank
      req
      {:titel (str "Disc " cd-id)}
      {}
      [:section.section
       [:div.container
        [:h1.title (str "Disc " cd-id " — TODO")]]]))))
