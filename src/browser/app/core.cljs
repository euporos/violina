(ns app.core
  (:require
   [app.cds :as cds]
   [app.modal :as modal]
   [app.obfuscate :as obfuscate]
   [app.presse :as presse]
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
  (modal/init)
  ;; Touch app.presse so shadow-cljs includes it in the bundle's require graph;
  ;; the actual init is triggered via the script onload (see seiten/templates).
  (set! js/window.-_app_presse_init presse/init)
  (set! js/window.-_app_cds_init cds/init))


