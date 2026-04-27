(ns seiten.permanent
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(def param-spec
  [:string {:min 1}])

(defhandler handler [req]
  (p/let [rendered (templates/head-and-foot-dynamic
                    req
                    {:titel "Permanent"}
                    [:section.section
                     [:div.container
                      [:h1.title "Permanent — TODO"]]])]
    (ph/html->response rendered)))
