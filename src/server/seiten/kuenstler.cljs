(ns seiten.kuenstler
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:titel "Künstler"}
    {}
    [:section.section
     [:div.container
      [:h1.title "Künstler — TODO"]]])))

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
