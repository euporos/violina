(ns ^:dev/always serving.core
  (:require [clojure.string :as str]
            [config.env :as env]
            [db.setup]
            [geo]
            [macchiato.middleware.defaults :as defaults]
            [macchiato.middleware.session.memory :as session.memory]
            [macchiato.server :as http]
            [macchiato.util.response :as r]
            [mount.core :as mount]
            [macchiato-async.errors :as err]
            [psite-config.runtime :as config]
            [psite-config.server]
            [psite-routing.core :as routing]
            [psite-middleware.core :as middleware]
            [reitit.coercion.malli]
            [reitit.ring :as ring]
            [seiten.generics :as generics]
            [psite-rate-limit.core :as rate-limit]
            [serving.routes :refer [router]]
            [setup.config-check]
            [setup.session]
            [taoensso.timbre :refer [info]]))

(def browser-env-script
  (psite-config.server/set-browser-env-string env/env))

(def app-config-schema
  (config/compose
   routing/config-schema
   middleware/config-schema
   rate-limit/config-schema))

(def lib-config
  (let [cfg {:psite-routing/devmode?         (boolean (env/setting :devmode?))
             :psite-routing/locale-fallback  (env/setting :locale-fallback)
             :psite-routing/country->locale  geo/country->locale
             :psite-middleware/show-errors?  (boolean (env/setting :show-errors?))
             :psite-middleware/geolocation   (merge {:enabled?               false
                                                     :provider-url           "https://ipapi.co/%s/json/"
                                                     :api-key                nil
                                                     :trust-x-forwarded-for? false
                                                     :timeout-ms             3000}
                                                    (env/setting :geolocation))
             :rate-limit/trust-proxy?       (boolean (env/setting :rate-limit-trust-proxy?))
             :rate-limit/buckets            (merge {:book {:capacity 3  :refill-per-sec 0.003333333}
                                                   :ical {:capacity 30 :refill-per-sec 0.5}
                                                   :qr   {:capacity 30 :refill-per-sec 0.5}}
                                                  (env/setting :rate-limit-buckets))}]
    (config/validate! app-config-schema cfg)
    cfg))

(defn wrap-config [handler]
  (fn [req res raise]
    (handler (-> req
                 (assoc :config (merge env/env lib-config))
                 (assoc :browser-env-script browser-env-script))
             res raise)))

(def reitit-handler
  (ring/ring-handler
   router
   (cond->> (fn [req res raise]
              (let [locale-in-uri
                    ((set (env/setting :locale-fallback))
                     (keyword (second (re-find #"^/([a-z]+)/"
                                               (or (get req :uri) "")))))
                    response-404 {:status 404
                                  ;; request needed here for language extraction
                                  :body   {:request (err/make-circular-safe
                                                     (dissoc req :config))}}
                    response (if
                              locale-in-uri response-404
                              (if-let [coerced-locale (routing/coerce-locale req)]
                                (r/found
                                 (str "/" (name coerced-locale) (:url req)))
                                response-404))]

                (res response))))))

(def wrap-in-generic-converter (partial middleware/wrap-error-response-for-user (partial generics/error-converter router)))

;; Inline scripts (browser-env-script, posthog template, onload attr on app.js)
;; and inline style= attrs (via garden/style) require 'unsafe-inline' for now.
;; Remove 'unsafe-inline' from script-src once templates inject (:csp-nonce req).
(def directus-origin
  ;; When :directus-url is absolute (e.g. http://localhost:8055/ or
  ;; https://directus.example.com/), extract origin so Directus-served
  ;; images pass img-src. Relative paths are covered by 'self'.
  (when-let [[_ origin] (re-find #"^(https?://[^/]+)"
                                 (or (env/setting :directus-url) ""))]
    origin))

(def posthog-origin
  ;; Origin of PostHog (e.g. https://eu.posthog.com), derived from :posthog :host
  ;; (api_host of the embed bundle) with fallback to :dashboard-embed-url.
  (when-let [[_ origin] (re-find #"^(https?://[^/]+)"
                                 (or (get-in env/env [:posthog :host])
                                     (get-in env/env [:posthog :dashboard-embed-url])
                                     ""))]
    origin))

(def csp-directives
  {:default-src     ["'self'"]
   :script-src      (cond-> ["'self'" "'unsafe-inline'"]
                      (= :dev (env/setting :mode)) (conj "'unsafe-eval'")
                      posthog-origin (conj posthog-origin))
   :style-src       ["'self'" "'unsafe-inline'"]
   :img-src         (cond-> ["'self'" "data:" "https://sounds-of-ukraine.de"]
                      directus-origin (conj directus-origin)
                      posthog-origin (conj posthog-origin))
   :connect-src     (cond-> ["'self'"]
                      posthog-origin (conj posthog-origin))
   :frame-src       (cond-> ["https://www.youtube.com"]
                      posthog-origin (conj posthog-origin))
   :object-src      ["'none'"]
   :base-uri        ["'self'"]
   :form-action     ["'self'" "https://www.paypal.com"]
   :frame-ancestors ["'self'"]})

(def macchiato-app
  (cond-> reitit-handler
    true middleware/wrap-geolocation
    (not (env/setting :show-errors?)) wrap-in-generic-converter
    true (defaults/wrap-defaults
          (-> defaults/site-defaults
              #_(assoc-in [:session :cookie-attrs] {:max-age (* 2 psite.track/rotation-interval)})
              (assoc-in [:session :store] (session.memory/memory-store setup.session/session-map))
              #_(assoc-in [:security :anti-forgery] false)))
    true (middleware/wrap-cache {:default "private, max-age=1800" ;; "private, max-age=2592000"
                                "text/html" "private, no-cache"
                                "application/transit" "no-store"})
    true (middleware/wrap-csp csp-directives)
    true wrap-config))

(defn app [{:keys [hostname uri] :as req} res raise]
  (try
    (if (str/starts-with? hostname "www.")
      (let [canonical-url (str "https://" (env/setting :canonical-domain) uri)]
        (res (r/moved-permanently canonical-url)))
      (macchiato-app req res raise))
    (catch js/Error e
      (println "Caught exception: %s" (.-stack e))
      (res (err/error->response e req)))))

(defn ^:dev/after-load load-state []
  (mount/start))

(defn ^:export serve []
  (setup.config-check/check!)
  (let [host (env/setting :host)
        port (env/setting :port)]
    (mount/start)
    (http/start
     {:handler    #'app
      :host       host
      :port       port
      :cookies    {:signed? false}
      :on-success #(info "Server started on" host ":" port "\n"
                         #_#_"running build" settings/build-id)})))



