(ns directus.core
  (:require [config.env :as env]))

(defn image-by-preset [preset image-id]
  (str (env/setting :directus-url) "assets/" image-id "?key=" preset))

