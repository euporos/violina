(ns app.core
  (:require
   [app.modal :as modal]
   [app.obfuscate :as obfuscate]
   [psite-config.browser]
   [psite-transit.core :as transit]
   [psite-transit.cljs-time :as transit.time]))

(def reader
  (transit/make-reader {:handlers transit.time/read-handlers}))

(defn ^:export readarg  [arg]
  (transit/deserialize reader (js/unescape arg)))

(defn ^:export main []
  (println "CLJS ready")
  (psite-config.browser/load js/frontend_env_string)
  (obfuscate/email)
  (obfuscate/phone)
  (modal/init))


