(ns seiten.permanent
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(def param-spec
  [:string {:min 1}])

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Permanent"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Permanent — TODO"]]])))
