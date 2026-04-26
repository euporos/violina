(ns config.env
  (:require [psite-config.env :as env]
            #?(:node [psite-config.server]
               :cljs [psite-config.browser])))

;; (defn derive-value [from target func]
;;   (fn [env]
;;     (assoc-in env target
;;               (func (get-in env from)))))

(defn derive-value
  ([env from target func]
   (derive-value env from target func false))
  ([env from target func frontend?]
   (let [newval (func (get-in env from))]
     (cond-> env
       true (assoc-in target newval)
       frontend? (update :share-with-frontend #(conj % target))))))

(defn derive-values [env]
  (-> env
      (derive-value [:contact-email]
                    [:frontend :contact-email]
                    js/btoa
                    true)
      (derive-value [:contact-phone]
                    [:frontend :contact-phone]
                    js/btoa
                    true)))

#?(:node (def env (derive-values psite-config.server/env)))

(def setting
  #?(:node (partial env/setting env)
     :cljs env/setting))
