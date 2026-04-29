(ns app.paedagogik
  (:require [config.env :as env]))

(defn- decode [obf]
  (some-> obf js/atob (.split "") .reverse (.join "")))

(defn- open-mail [_ev]
  (when-let [email (decode (env/setting :piano-email))]
    (set! js/window.location.href (str "mailto:" email))))

(defn init []
  (doseq [el (array-seq (js/document.querySelectorAll ".js-piano-email-link"))]
    (.addEventListener el "click"
                       (fn [ev]
                         (.preventDefault ev)
                         (open-mail ev)))))
