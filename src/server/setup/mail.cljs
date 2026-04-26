(ns setup.mail
  (:require
   [psite-mail.core :as mail]
   [config.env :as env]
   [taoensso.timbre :refer-macros [errorf]]))

(def transporter
  (mail/create-transporter
   (env/setting :email-transporter)))

(defn send-from-info [message]
  (mail/send! transporter
              (merge
               {:from {:name (env/setting :site-name)
                       :address (env/setting [:email-transporter :auth :user])}}
               message)))

(defn mail-to-admin!
  [subject message]
  (mail/send! transporter
              {:from    (env/setting :admin-email)
               :to      (env/setting :admin-email)
               :subject (or subject (str "Problem mit " (env/setting :site-name)))
               :text    message}))
