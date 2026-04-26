(ns seiten.programme
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Programme"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Programme — TODO"]]])))

(defhandler single-handler [req]
  (let [programm-id (routing/path-param req :programm-id)]
    (ph/html->response
     (templates/head-and-foot-blank
      req
      {:titel (str "Programm " programm-id)}
      {}
      [:section.section
       [:div.container
        [:h1.title (str "Programm " programm-id " — TODO")]]]))))
