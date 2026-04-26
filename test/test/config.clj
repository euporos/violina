(ns test.config
  (:require [clojure.edn :as edn]))

(def config (edn/read-string (slurp "settings_test.edn")))
