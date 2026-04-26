(ns setup.config-check
  (:require [clojure.string :as str]
            [config.env :as env]
            [psite-config.read :as config-read]))

(defn check! []
  (let [missing (config-read/find-unresolved-secrets env/env)]
    (when (seq missing)
      (.write js/process.stderr
              (str "ERROR: Missing required secrets. Unresolved #psite/secret tags:\n"
                   (str/join "\n" (map (fn [[path tag-name]]
                                         (str "  " path " (#psite/secret \"" tag-name "\")"))
                                       missing))
                   "\nProvide these values in ../settings.edn.\n"))
      (.exit js/process 1))))
