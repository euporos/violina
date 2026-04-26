(ns seiten.presse
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Presse"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Presse — TODO (redesign pending)"]]])))

(defhandler single-handler [req]
  (let [artikel-id (routing/path-param req :artikel-id)]
    (ph/html->response
     (templates/head-and-foot-blank
      req
      {:titel (str "Presse-Artikel " artikel-id)}
      {}
      [:section.section
       [:div.container
        [:h1.title (str "Presse-Artikel " artikel-id " — TODO")]]]))))
