(ns seiten.generics
  (:require [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [comp.localization :as loc]
            [seiten.templates :as templates]
            [setup.mail :as mail]
            [taoensso.timbre :refer-macros [errorf]])
  (:require-macros
   [hiccups.core :as hiccups :refer [html5]]))

(defn error-page [message status]
  [:section.section
   [:div.container.has-text-centered
    [:h1.title.is-1.has-text-centered message " (" status ")"]
    [:a
     {:href "/"}
     "Zurück zur Startseite"]]])

(defn error-converter
  [router response]
  (let [injected-request (:original-request response)
        locale           (routing/coerce-locale injected-request)
        request          (assoc injected-request
                                :locale locale
                                :reitit.core/router router)
        status           (:status response)
        message          (loc/by-locale locale
                                      (get {404 {:en "Not found"
                                                 :de "Nicht gefunden"
                                                 :it "Non trovato"}
                                            403 {:en "Forbidden"
                                                 :de "Keine Zugriffserlaubnis"
                                                 :it "Non permesso"}}
                                           status
                                           {:en "Something went wrong"
                                            :de "Etwas ist schief gelaufen"}))]
    (when (and (get-in response [:original-request :config :mail-on-errors?])
               (not (= status 404)))
      (-> (mail/mail-to-admin!
           (str status " on VP " (get-in response [:original-request :config :settings :mode])
                " : " (:url request))
           (str "URL: " (:url request) "\n"
                (ph/pprint-to-string (update response :original-request
                                             #(dissoc % :reitit.core/match :config)))))
          (.catch (fn [e] (errorf "Error notification mail failed: %s" (.-message e))))))
    (-> {:body   (if-not true #_(:reitit.core/router request)
                         (templates/router-free request {:beschreibung ""
                                                         :titel        (str status " – " message)}
                                                (error-page message status))
                         (templates/head-and-foot-blank
                          request {:beschreibung ""
                                   :titel        (str status " – " message)}
                          []
                          (error-page message status)))
         :status status}
        (r/content-type "text/html"))))




