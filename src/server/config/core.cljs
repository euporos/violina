(ns config.core
  (:require
   [config.env :as config]))

(def locale-set (set (config/setting :locale-fallback)))
