(ns seiten.paedagogik
  (:require [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [seiten.templates :as templates]))

(defn- paedagogik-query [locale]
  {:select [s/paedagogik-id
            s/paedagogik-hauptbild
            s/paedagogik-og_bild
            (db/localized s/paedagogik-titel locale)
            (db/localized s/paedagogik-meta_title locale)
            (db/localized s/paedagogik-meta_description locale)
            (db/localized s/paedagogik-og_image_alt locale)
            (db/localized s/paedagogik-Beschreibung locale)]
   :from   [[s/paedagogik_t :paedagogik]]
   :limit  1})

(defhandler handler [req]
  (p/let [locale  (:locale req)
          rows    (db/query (paedagogik-query locale))
          {:keys [titel meta_title meta_description hauptbild og_bild
                  og_image_alt Beschreibung]} (first rows)
          og-src  (or og_bild hauptbild)
          rendered (templates/head-and-foot-dynamic
                    req {:titel        (or meta_title titel (snip/paedagogik locale))
                         :beschreibung meta_description
                         :og-image     (when og-src
                                         (d/image-by-preset "og-image" og-src))
                         :breadcrumbs  [[(or titel (snip/paedagogik locale))
                                         (:url req)]]}
                    [:div.mainframe
                     [:div.sheet
                      [:div.sheet__header (or titel (snip/paedagogik locale))]
                      [:div.sheet__body
                       (when hauptbild
                         [:img.sheet__bild.sheet__bild--h
                          {:src (d/image-by-preset "w1200" hauptbild)
                           :alt og_image_alt}])
                       [:div.sheet__fliesstext
                        (when Beschreibung
                          (ph/dangerous-html Beschreibung))]]]])]
    (ph/html->response rendered)))
