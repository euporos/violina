(ns api.routes
  {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}}}}
  #?(:node (:require
             [api.presse :as presse]
             [config.env :as env]
             [psite-middleware.core :as middleware]))
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
                             :parameters {:path [[:artikel-id :int]]}}]]))
