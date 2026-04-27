(ns seiten.cds
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (p/let [rendered (templates/head-and-foot-dynamic
                    req
                    {:titel "Discs"}
                    [:section.section
                     [:div.container
                      [:h1.title "Discs — TODO"]]])]
    (ph/html->response rendered)))

(defhandler single-handler [req]
  (p/let [cd-id    (routing/path-param req :cd-id)
          rendered (templates/head-and-foot-dynamic
                    req
                    {:titel (str "Disc " cd-id)}
                    [:section.section
                     [:div.container
                      [:h1.title (str "Disc " cd-id " — TODO")]]])]
    (ph/html->response rendered)))
