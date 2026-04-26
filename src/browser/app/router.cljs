(ns app.router
  (:require [api.routes :as api]
            [re-frame.core :as rf]
            [reitit.core :as reitit]
            [seiten.routes :as seiten]))

(def router
  (reitit/router
   [["/:locale"
     seiten/routes
     api/routes]]))

(defn reverse-match
  "Reverse-route by name. Auto-pulls :locale from re-frame db;
   callers may override by passing :locale in params."
  ([name] (reverse-match name {}))
  ([name params]
   (let [locale (or (:locale params)
                    @(rf/subscribe [:get-in [:locale]]))]
     (reitit/match->path
      (reitit/match-by-name router name (assoc params :locale locale))))))
