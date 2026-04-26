(ns seiten.home
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

(defhandler blankhome [req]
  (let [coerced-locale (:locale req)
        query-string   (:query-string req)
        target         (str (routing/reverse-match req :home {:locale
                                                              (or (#{:de :uk} coerced-locale)
                                                                  :de)})
                            (when query-string (str "?" query-string)))]
    (r/found target)))

(defhandler handler [req]
  (ph/html->response
   (templates/head-and-foot-blank
    req
    {:raw-title "new Macchiato project"}
    {}
    [:section.section
     [:div.container.has-text-centered
      [:h1.title "new Macchiato project"]]])))
