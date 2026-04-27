(ns seiten.presse
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (p/let [rendered (templates/head-and-foot-dynamic
                    req
                    {:titel "Presse"}
                    [:section.section
                     [:div.container
                      [:h1.title "Presse — TODO (redesign pending)"]]])]
    (ph/html->response rendered)))

(defhandler single-handler [req]
  (p/let [artikel-id (routing/path-param req :artikel-id)
          rendered   (templates/head-and-foot-dynamic
                      req
                      {:titel (str "Presse-Artikel " artikel-id)}
                      [:section.section
                       [:div.container
                        [:h1.title (str "Presse-Artikel " artikel-id " — TODO")]]])]
    (ph/html->response rendered)))
