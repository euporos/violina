(ns seiten.paedagogik
  (:require [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [psite-seo.json-ld :as ld]
            [seiten.templates :as templates]))

(def ^:private violina-same-as
  ["https://www.facebook.com/Violina-Petrychenko-265583113951153"
   "https://www.youtube.com/channel/UCzwTs30aFoC93xPBdEQL6jQ/videos"])

(def cta-text
  {:de "Bei Interesse an Klavierunterricht freue ich mich auf Ihre Nachricht."
   :en "If you are interested in piano lessons, I look forward to your message."
   :uk "Якщо вас цікавлять уроки гри на фортепіано, буду рада вашому повідомленню."
   :it "Se siete interessati a lezioni di pianoforte, attendo con piacere il vostro messaggio."})

(defn- paedagogik-query [locale]
  {:select    [s/paedagogik-id
               s/paedagogik-hauptbild
               s/paedagogik-og_bild
               s/paedagogik-adresse
               [s/orte-strasse :strasse]
               [s/orte-postleitzahl :postleitzahl]
               [s/orte-stadt :stadt]
               (db/localized s/paedagogik-titel locale)
               (db/localized s/paedagogik-untertitel locale)
               (db/localized s/paedagogik-meta_title locale)
               (db/localized s/paedagogik-meta_description locale)
               (db/localized s/paedagogik-og_image_alt locale)
               (db/localized s/paedagogik-Beschreibung locale)]
   :from      [[s/paedagogik_t :paedagogik]]
   :left-join [:orte [:= s/paedagogik-adresse s/orte-id]]
   :limit     1})

(defn- adresse-section [{:keys [strasse postleitzahl stadt]}]
  (when (or strasse postleitzahl stadt)
    [:address.paedagogik__adresse
     {:itemscope true :itemtype "https://schema.org/PostalAddress"}
     (when strasse
       [:span {:itemprop "streetAddress"} strasse])
     (when (or postleitzahl stadt)
       (list
        (when strasse [:br])
        (when postleitzahl
          [:span {:itemprop "postalCode"} postleitzahl])
        (when (and postleitzahl stadt) " ")
        (when stadt
          [:span {:itemprop "addressLocality"} stadt])))]))

(defn- postal-address [{:keys [strasse postleitzahl stadt]}]
  (when (or strasse postleitzahl stadt)
    (ld/entity :PostalAddress
               (cond-> {:addressCountry "DE"}
                 strasse      (assoc :streetAddress strasse)
                 postleitzahl (assoc :postalCode postleitzahl)
                 stadt        (assoc :addressLocality stadt)))))

(defn- ld-graph
  "Schema.org @graph: MusicSchool (the offer + venue), Person (Violina, the
  teacher), Service (the lessons). IDs cross-reference each other. The
  Service offers come from the page's stated price points; if they ever go
  out of sync with body copy the editor should update both."
  [{:keys [titel meta_description] :as row} canonical-url image-url]
  (let [school-id   (str canonical-url "#paedagogik")
        person-id   (str canonical-url "#violina")
        service-id  (str canonical-url "#klavierunterricht")
        address     (postal-address row)
        violina     (ld/entity :Person
                               (cond-> {"@id"         person-id
                                        :name         "Violina Petrychenko"
                                        :jobTitle     "Konzertpianistin und Klavierpädagogin"
                                        :sameAs       violina-same-as}))
        school      (ld/entity :MusicSchool
                               (cond-> {"@id"        school-id
                                        :name        (or titel "Klavierunterricht in Köln")
                                        :description meta_description
                                        :url         canonical-url
                                        :areaServed  ["Köln" "Rheinland" "Nordrhein-Westfalen"]
                                        :founder     {"@id" person-id}}
                                 image-url (assoc :image image-url)
                                 address   (assoc :address address)))
        service     (ld/entity :Service
                               {"@id"          service-id
                                :name          "Klavierunterricht"
                                :serviceType   "Klavierunterricht"
                                :provider      {"@id" school-id}
                                :areaServed    ["Köln" "Rheinland"]
                                :offers        [(ld/entity :Offer
                                                           {:name           "45 Minuten Klavierunterricht"
                                                            :price          "45"
                                                            :priceCurrency  "EUR"})
                                                (ld/entity :Offer
                                                           {:name           "60 Minuten Klavierunterricht"
                                                            :price          "50"
                                                            :priceCurrency  "EUR"})]})]
    [school violina service]))

(defn- contact-cta [req]
  (let [locale (:locale req)]
    [:p.paedagogik__cta
     [:button.standardlink.js-piano-email-link
      {:type "button"}
      (get cta-text locale (:de cta-text))]]))

(defhandler handler [req]
  (p/let [locale  (:locale req)
          rows    (db/query (paedagogik-query locale))
          {:keys [titel untertitel meta_title meta_description hauptbild og_bild
                  og_image_alt Beschreibung]
           :as   row} (first rows)
          og-src        (or og_bild hauptbild)
          og-image-url  (when og-src (d/image-by-preset "og-image" og-src))
          canonical-url (routing/make-path-absolute req (:url req))
          rendered (templates/head-and-foot-dynamic
                    req {:titel        (or meta_title titel (snip/paedagogik locale))
                         :beschreibung meta_description
                         :og-image     og-image-url
                         :og-image-alt og_image_alt
                         :breadcrumbs  [[(or titel (snip/paedagogik locale))
                                         (:url req)]]
                         :extra-ld     (ld-graph row canonical-url og-image-url)}
                    [:div.mainframe
                     [:article.sheet
                      [:div.sheet__header
                       [:div.sheet__titel (or titel (snip/paedagogik locale))]
                       (when untertitel
                         [:div.sheet__untertitel untertitel])]
                      [:div.sheet__body
                       (when hauptbild
                         [:img.sheet__bild.sheet__bild--h
                          {:src (d/image-by-preset "w1200" hauptbild)
                           :alt og_image_alt}])
                       [:div.sheet__fliesstext
                        (when Beschreibung
                          (ph/dangerous-html Beschreibung))
                        (adresse-section row)
                        (contact-cta req)]]]])]
    (ph/html->response rendered)))
