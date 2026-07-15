(ns seiten.admin
  (:require [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]
            [setup.directus-auth :as directus-auth]))

(defhandler handler [req]
  (if-not (:directus-user req)
    (directus-auth/directus-login-redirect req)
    (p/let [locale (or (some-> (get-in req [:parameters :path :locale]) name)
                       (get-in req [:path-params :locale])
                       "de")
            rendered (templates/head-and-foot-dynamic
                      req {:titel    "Übersetzungen"
                           :noindex  true
                           :notrack? true
                           :cljs     {:js-data {:locale locale}
                                      :onload  "app.admin.main"}}
                      [:div.mainframe
                       [:div.sheet
                        [:div.sheet__header "Übersetzungen"]
                        [:div.sheet__body
                         [:div#admin]]]])]
      (ph/html->response rendered))))
