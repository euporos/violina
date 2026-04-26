(ns seiten.termine
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Termine"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Termine — TODO"]]])))
