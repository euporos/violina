(ns seiten.templates
  (:require
   [cljs-time.core :as t]
   [cljstache.core :as tache]
   [clojure.string :as str]
   [comp.snippets :as snip]

   [garden.core :as garden]
   [goog.string :as gstring]
   [goog.string.format]
   [psite-menu.core :as pmenu]
   [psite-routing.core :as routing]
   [psite-seo.core :as seo]
   [psite-transit.core :as transit]
   [psite-transit.cljs-time :as transit.time]
   [psite-hiccup.core :as ph]
   [serving.routing :as rt]
   [shadow.resource :as rc])
  (:require-macros [hiccups.core :as hiccups :refer [html html5]]
                   [psite-utils.macros :as m]))

;; #############
;; ### Blank ###
;; #############

(def writer
  (transit/make-writer {:handlers transit.time/write-handlers}))

(def full-locales
  {:de "de-DE"
   :en "en-US"})

(def font-awesome
  (list [:script {:src "/compiled/fontawesome-free-6.4.0-web/js/brands.min.js"
                  :defer true}]
        [:script {:src "/compiled/fontawesome-free-6.4.0-web/js/solid.min.js"
                  :defer true}]
        [:script {:src "/compiled/fontawesome-free-6.4.0-web/js/fontawesome.min.js"
                  :defer true}]))

(defn blank_hiccup
  "Empty page… "
  [req head-data & comps]
  (let [{:keys [titel beschreibung into-head
                og-image breadcrumbs cljs raw-title
                noindex? notrack?]
         :or   {head-data []}} head-data
        locale                 (keyword (-> req :path-params :locale))
        ;; titlesupp (by-locale locale (:title dpage))
        localestring           (get full-locales locale)
        title                  (or raw-title
                                   (str (when titel (str titel " | "))
                                        (get-in req [:config :site-name])))]

    (list (into [:head
                 (seo/charset)
                 (seo/viewport)

                 (seo/robots
                  (when (or (get-in req [:parameters :query :debug])
                            noindex?
                            (get-in req [:config :noindex]))
                    :noindex))

                 #_(seo/ld
                    (seo/breadcrumb-list
                     (or breadcrumbs [{:name titel :url (:url req)}])))

                 [:script {:src (m/cache-bust "/js/pedestrian.js")}]

                 (when (:noindex (get-in req [:config :noindex]))
                   (seo/http-equiv "Cache-Control" "no-store"))

                 (seo/google-site-verification
                  (get-in req [:config :google-site-verfication])) ;TODO: make setting

                 (seo/title title)

                 (seo/open-graph
                  {:title       title
                   :description beschreibung
                   :image       og-image
                   :url         (routing/make-path-absolute req (:url req))
                   :type        "website"})

                 (seo/description beschreibung)

                 (seo/favicon-set {:base-path "/imgs/favicon"})

                 (when (:reitit.core/router req)
                   (seo/alternates
                    (for [locale (get-in req [:config :locale-fallback])]
                      {:hreflang (name locale)
                       :url      (rt/switch-locale-and-prepend-domain locale req)})))

                 [:link
                  {:rel  "stylesheet"
                   :href (m/cache-bust "/compiled/bundle/main.css") :type "text/css" :media "screen"}]

                 [:script
                  {:src   (m/cache-bust "/compiled/bundle/main.js")
                   :defer true}]

                 (when (and (get-in req [:config :posthog])
                            (not notrack?))
                   [:script
                    (ph/dangerous-html (tache/render (rc/inline "posthog.js") (-> (get-in req [:config :posthog])
                                                                                 (assoc :posthog-identity
                                                                                        (get-in req
                                                                                                [:cookies "macchiato-session.sig" :value])
                                                                                        :tracking-id (get-in req
                                                                                                             [:session :tracking :id])))))])]

                into-head)

          [:body {:lang locale}

           comps

           (:browser-env-script req)

           (let [{:keys [onload js-data]} cljs
                 js-data (assoc js-data :csrf-token (:af-token req))
                 argument                 (when js-data (str "'" (js/escape (transit/serialize writer js-data)) "'"))
                 onload-string                   (str
                                                  onload
                                                  "("
                                                  "app.core.readarg" "(" argument ")"
                                                  ")")]
             [:script {:src (m/cache-bust "/compiled/js/app.js") :onload onload-string}])]

          #_[:script
             "console.log('" settings/mode " build " settings/build-id " ')"])))

(defn blank
  [req head-data & comps]
  (html5 {:lang (:locale req)}
         (apply (partial blank_hiccup req head-data) comps)))

;; ###########################
;; ###### Head & Footer ######
;; ###########################

(defn locale-switcher [locale string req]
  [:a.navbar-item {:href (rt/switch-locale-and-prepend-domain locale req)}
   string])

(let [switchers [[:de (partial locale-switcher :de "Deutsch")]
                 [:uk (partial locale-switcher :uk "Українска")]
                 [:en (partial locale-switcher :en "English")]]]
  (defn locale-switchers [req]
    (keep
     (fn [[locale func]]
       (when-not (= locale (:locale req)) (func req)))
     switchers)))

(defn alternate-locales  [current-locale])

(defn navbar [{:keys [locale session] :as req}]
  [:nav.navbar
   [:div.navbar-brand
    [:a.navbar-item
     {:href (routing/reverse-match req :home {})
      :style (garden/style {:position "relative"})}
     [:div
      {:style (garden/style {:width "55px"
                             :height "28px"
                             :background-size "cover"
                             :background (str "url('" (m/cache-bust "/imgs/logo.svg") "') no-repeat left")})}]
     (locale-switchers req)]
    [:div.navbar-burger.burger
     {:data-target "navbarExampleTransparentExample"}
     [:span]
     [:span]
     [:span]]]
   [:div#navbarExampleTransparentExample.navbar-menu
    [:div.navbar-start
     #_[:a.navbar-item
        {:href (routing/reverse-match req :home {})} "Home"]

     #_[:div.navbar-item.has-dropdown.is-hoverable
        [:a.navbar-link
         {:href "/documentation/overview/start/"} "More"]
        [:div.navbar-dropdown.is-boxed
         [:a.navbar-item
          {:href "/documentation/overview/start/"} "Mechandise"]
         [:a.navbar-item
          {:href "https://bulma.io/documentation/modifiers/syntax/"} "Extras"]
         [:a.navbar-item
          {:href "https://bulma.io/documentation/columns/basics/"} "Media"]]]]
    [:div.navbar-end.mr-3
     [:a.navbar-item
      {:href (str (routing/reverse-match req :home {}) "#contact")} (snip/kontakt locale)]]]])
(defn footer [{:keys [locale] :as req}]
  [:footer.footer
   [:div.container
    [:div.columns
     [:div.column.is-2
      [:div.content.has-text-centered
       [:p
        [:a {:href (routing/reverse-match req :single-page {:page-id 1 :page-slug "impressum"})} (snip/impressum locale)]]
       [:p
        [:a {:href (routing/reverse-match req :single-page {:page-id 2 :page-slug "datenschutz"})} (snip/datenschutz locale)]]]]
     [:div.column.is-10
      [:div.columns
       [:div.column.has-text-centered
        [:div "Unterstützt von:"]
        [:div.columns.is-vcentered
         {:style "flex-direction: column;"}
         #_[:div.column.is-half
            [:a
             {:href (if (= locale :uk)
                      "https://duesseldorf.mfa.gov.ua/"
                      "https://duesseldorf.mfa.gov.ua/de")
              :target "blank"
              :style
              (garden/style {:width "180px"
                             :height "60px"
                             :display "inline-block"
                             :background "url('/compiled/from_reservoir/konsulat.svg') no-repeat left"
                             :background-size "contain"})}]]

         [:div.column.is-half
          [:a
           {:href "https://www.bechstein.com/centren/duesseldorf/startseite/"
            :target "blank"
            :style
            (garden/style {:width "180px"
                           :height "70px"
                           :display "inline-block"
                           :background "url('/compiled/from_reservoir/Bechstein_Logo.svg') no-repeat left"
                           :background-size "contain"})}]]
         [:div.column.is-half
          [:a
           {:href "https://www.klavierhaus-klavins.de/"
            :target "blank"
            :style
            (garden/style {:width "180px"
                           :height "70px"
                           :display "inline-block"
                           :background "url('/compiled/from_reservoir/logo_klavins_breit.svg') no-repeat left"
                           :background-size "contain"})}]]
         #_[:div.column.is-half
            [:a
             {:href "https://www.zamus.de/"
              :target "blank"
              :style
              (garden/style {:width "180px"
                             :height "70px"
                             :display "inline-block"
                             :background "url('/compiled/from_reservoir/nrw.svg') no-repeat left"
                             :background-size "contain"})}]]]]

       [:div.column.has-text-centered
        [:div "Gefördert durch:"]
        [:columns
         {:style "flex-direction: column;"}
         [:div.column
          [:a
           {:href (str "https://gvl.de/"
                       (when-not (= locale :de) "en"))
            :target "blank"
            :style
            (garden/style {:width "100px"
                           :height "70px"
                           :display "inline-block"
                           :background "url('/compiled/from_reservoir/logo_gvl.svg') no-repeat left"
                           :background-size "contain"})}]]
         [:div.column
          [:a
           {:href "https://www.mkw.nrw/"
            :target "blank"
            :style
            (garden/style {:width "250px"
                           :height "80px"
                           :display "inline-block"
                           :background "url('/compiled/from_reservoir/nrw.svg') no-repeat left"
                           :background-size "contain"})}]]
         [:div.column
          [:a
           {:href (str "https://philharmonia.lviv.ua/"
                       (if (= locale :uk) "ua" "en")
                       "/events/")
            :target "blank"
            :style
            (garden/style {:width "100px"
                           :height "80px"
                           :display "inline-block"
                           :background (str "url('"
                                            (if (= locale :uk)
                                              "/compiled/from_reservoir/logo-phil-ua.svg"
                                              "/compiled/from_reservoir/logo-phil-en.svg")
                                            "') no-repeat left")
                           :background-size "contain"})}]]]]]]]]])

(defn head-and-foot-blank
  [req head-data dynamic-menus & comps]
  (blank req head-data
         (navbar req)
         [:div#modal]
         comps
         (footer req)))

(defn router-free
  [req head-data & comps]
  (blank req head-data
         [:div#modal]
         comps))

(defn head-and-foot-dynamic
  [req head-data & comps]
  (head-and-foot-blank req head-data {} comps))
