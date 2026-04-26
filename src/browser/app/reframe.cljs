(ns app.reframe
  (:require [re-frame.core :as rf]))

(rf/reg-sub :get-in
            (fn [db [_ path]]
              (get-in db path)))

(rf/reg-event-db :set
                 (fn [db [_ path val]]
                   (assoc-in db path val)))

(rf/reg-event-db :set-db
                 (fn [db [_ val]]
                   val))
