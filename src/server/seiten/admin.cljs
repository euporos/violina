(ns seiten.admin
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]
            [setup.directus-auth :as directus-auth]))

(defhandler handler [req]
  (if-not (:directus-user req)
    (directus-auth/directus-login-redirect req)
    (p/let [rendered (templates/head-and-foot-dynamic
                      req {:titel    "Verwaltung"
                           :noindex  true
                           :notrack? true
                           :cljs     {:js-data {}
                                      :onload  "app.admin.main"}}
                      [:div.section
                       [:div.container
                        [:div.content
                         [:h1.title "Admin"]
                         [:div#admin]]]])]
      (ph/html->response rendered))))
