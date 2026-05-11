(ns api.routes
  {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}}}}
  #?(:node (:require
             [api.ical :as ical]
             [api.presse :as presse]
             [api.werbe-email :as werbe-email]
             [config.env :as env]
             [psite-middleware.core :as middleware]
             [psite-rate-limit.core :as rate-limit]
             [setup.directus-auth :as directus-auth]))
  #?(:clj  (:require      [psite-routing.macros :as prm])
     :cljs (:require-macros [psite-routing.macros :as prm])))

(def routes
  (#?(:node identity :default prm/routes-reduced-for-matching)
   ["/api" {:middleware [(when (env/setting :hide-errors?)
                           (partial middleware/wrap-error-response-for-user
                                    (middleware/json-converter)))
                         middleware/wrap-edn-params]}
    ["/presse/{artikel-id}" {:name       :api-presse-detail
                             :handler    presse/detail-handler
                             :parameters {:path [[:artikel-id :int]]}}]
    ["/ical" {:name       :api-ical
              :handler    ical/handler
              :middleware [(rate-limit/wrap-rate-limit :ical)]
              :parameters {:query [[:kuenstler-id {:optional true} :int]
                                   [:termin-id    {:optional true} :int]]}}]
    ["/werbe-email.eml" {:name       :api-werbe-email
                         :handler    werbe-email/handler
                         :middleware [directus-auth/wrap-directus-user]}]]))
