(ns test.routes
  (:require
   [api.routes :as api]
   [reitit.core :as r]
   [reitit.impl :as impl]
   [seiten.routes :as seiten]
   [test.config :as cfg]))

(def router
  (r/router
   ["/" ["" {:name :blank-home}]
    [":locale" seiten/routes api/routes]]))

(defn path [route-name path-params]
  (:path (r/match-by-name! router route-name path-params)))

(defn url [route-name path-params & {:keys [fragment query]}]
  (str "http://localhost:" (:port cfg/config)
       (path route-name path-params)
       (when (seq query) (str "?" (impl/query-string query)))
       (when fragment (str "#" fragment))))
