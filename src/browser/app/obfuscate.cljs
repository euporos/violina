(ns app.obfuscate
  (:require
   ["react-obfuscate" :as Obfuscate]
   [clojure.edn :as edn]
   [config.env :as env]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

(defn component [key value]
  [(r/adapt-react-class (.-default Obfuscate))
   {key value}])

(defn inject [class key]
  (doseq [node (js/document.querySelectorAll class)
          :let [env-path  (edn/read-string
                           (js/decodeURIComponent
                            (.getAttribute node "env-path")))
                value (js/atob (env/setting env-path))]]
    #_(println env-path)
    #_(println value)
    (rdom/render
     (component key value #_(reduce str (reverse as-b64)))
     node)))

(defn email []
  (inject ".psite-oml" :email))

(defn phone []
  (inject ".psite-oph" :tel))
