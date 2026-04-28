(ns seiten.paedagogik
  (:require [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

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

(defn- contact-cta [req]
  (let [locale (:locale req)]
    [:p.paedagogik__cta
     [:a.standardlink
      {:href (str (routing/reverse-match req :home {}) "#contact")}
      (get cta-text locale (:de cta-text))]]))

(defhandler handler [req]
  (p/let [locale  (:locale req)
          rows    (db/query (paedagogik-query locale))
          {:keys [titel meta_title meta_description hauptbild og_bild
                  og_image_alt Beschreibung]
           :as   row} (first rows)
          og-src  (or og_bild hauptbild)
          rendered (templates/head-and-foot-dynamic
                    req {:titel        (or meta_title titel (snip/paedagogik locale))
                         :beschreibung meta_description
                         :og-image     (when og-src
                                         (d/image-by-preset "og-image" og-src))
                         :og-image-alt og_image_alt
                         :breadcrumbs  [[(or titel (snip/paedagogik locale))
                                         (:url req)]]}
                    [:div.mainframe
                     [:article.sheet
                      [:div.sheet__header (or titel (snip/paedagogik locale))]
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
