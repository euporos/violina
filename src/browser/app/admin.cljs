(ns app.admin
  (:require
   [app.reframe]
   [reagent.dom :as rdom]))

(defn admin []
  [:div
   [:p "Admin dashboard placeholder."]])

(defn ^:dev/after-load mount []
  (rdom/render [admin] (js/document.getElementById "admin")))

(defn ^:export main [_]
  (println "Admin ready")
  (mount))
