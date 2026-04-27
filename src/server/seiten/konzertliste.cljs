(ns seiten.konzertliste
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (p/let [rendered (templates/head-and-foot-dynamic
                    req
                    {:titel "Konzertliste"}
                    [:section.section
                     [:div.container
                      [:h1.title "Konzertliste — TODO"]]])]
    (ph/html->response rendered)))
