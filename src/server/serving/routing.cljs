(ns serving.routing
  (:require
   [psite-routing.core :as routing]))

(defn path-fixed
  [name request]
  (routing/reverse-match request name {}))

(defn switch-locale
  [new-locale {:keys [path-params query-params] :as req}]
  (let [route-name (get-in req [:reitit.core/match :data :name])]
    (routing/reverse-match req route-name
                           (assoc path-params :locale new-locale)
                           query-params)))

(defn switch-locale-and-prepend-domain
  [new-locale req]
  (routing/make-path-absolute req (switch-locale new-locale req)))
