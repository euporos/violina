(ns seiten.kontakt
  (:require [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(def ^:private bild "397ae0dc-438e-4c4d-8b28-aedfa32c5873")

(def ^:private phone "+49 176 615 25 868")

(def ^:private strings
  {:de {:titel  "Kontakt"
        :mobil  "Mobil"
        :anfragen "Buchungs- und Presseanfragen:"
        :meta "Sie wollen ein Konzert buchen oder interessieren sich für Klavierunterricht in Köln und Umgebung? Kontaktieren Sie mich!"}
   :en {:titel  "Contact"
        :mobil  "Mobile"
        :anfragen "Booking and press inquiries:"
        :meta "Looking to book a concert or interested in piano lessons in Cologne and the surrounding area? Get in touch!"}
   :uk {:titel  "Контакти"
        :mobil  "Мобільний"
        :anfragen "Запити на бронювання та запити преси:"
        :meta "Бажаєте замовити концерт або цікавитесь уроками гри на фортепіано в Кельні та околицях? Напишіть мені!"}
   :it {:titel  "Contatti"
        :mobil  "Cellulare"
        :anfragen "Richieste di prenotazione e stampa:"
        :meta "Vuoi prenotare un concerto o sei interessato a lezioni di pianoforte a Colonia e dintorni? Contattami!"}})

(defhandler handler [req]
  (p/let [locale (:locale req)
          s      (get strings locale (:de strings))
          rendered (templates/head-and-foot-dynamic
                  req {:titel        (:titel s)
                       :beschreibung (:meta s)
                       :breadcrumbs  [[(:titel s) (:url req)]]}
                  [:div.mainframe
                   [:div.sheet
                    [:div.sheet__header (:titel s)]
                    [:div.sheet__body
                     [:img.sheet__bild.sheet__bild--v
                      {:src (d/image-by-preset "w1200" bild)}]
                     [:div.sheet__fliesstext
                      [:p.ql-align-center [:strong "Violina Petrychenko"]]
                      [:p.ql-align-center (:mobil s) ": " phone]
                      [:p.ql-align-center (:anfragen s)]
                      [:p.ql-align-center
                       [:button.standardlink.js-piano-email-link
                        {:type "button"} "Email"]]]]]])]
    (ph/html->response rendered)))
