(ns ^:dev/always serving.routes
  (:require
   [api.routes :as api]
   [config.env :as env]
   [db.setup]
   [macchiato.middleware.params :as params]
   [macchiato.middleware.restful-format :as rf]
   [psite-middleware.core :as middleware]
   [psite-routing.core :as routing]
   [reitit.coercion.malli]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [seiten.home :as home]
   [seiten.routes :as seiten]))

(def language-keys-spec
  (into [:enum]
        (env/setting :locale-fallback)))

(def routes
  [["/" {:coercion   reitit.coercion.malli/coercion
         :parameters {:query [:map
                              [:debug {:optional true} :boolean]]}}
    ["" home/blankhome]

    [":locale" {:middleware []
                :parameters {:path [:map
                                    [:locale [:and keyword?
                                              language-keys-spec]]]}}

     seiten/routes

     api/routes]]])

(def router
  (ring/router
   [routes]
   {:data {:middleware [params/wrap-params
                        routing/wrap-locale
                        middleware/wrap-synchronous-exceptions
                        #(rf/wrap-restful-format % {:keywordize? true})
                          ;; middleware/wrap-body-to-params
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware]}}))
