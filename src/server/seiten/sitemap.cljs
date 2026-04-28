(ns seiten.sitemap
  "Runtime sitemap generator. Walks static routes for each locale and
  joins the per-collection dynamic IDs from the database; emits one
  <url> per route with <xhtml:link rel=\"alternate\" hreflang> entries
  so search engines can resolve the localized variants."
  (:require [clojure.string :as str]
            [config.env :as env]
            [db.schema :as s]
            [db.setup :as db]
            [kitchen-async.promise :as p]
            [macchiato.util.response :as r]
            [psite-routing.core :as routing]
            [psite-utils.core :as putils]))

(def ^:private locales-fallback [:de :en :uk :it])

(defn- locales [] (or (env/setting :locale-fallback) locales-fallback))

(defn- canonical-domain [] (env/setting :canonical-domain))

(defn- abs-url
  "Build https://canonical-domain/{locale}/<path-after-reverse-match>.
  Reitit's reverse-match returns paths starting with /:locale; we
  swap that prefix in for whatever the request supplied."
  [req route-name locale params]
  (let [path (routing/reverse-match req route-name (assoc params :locale locale))]
    (str "https://" (canonical-domain) path)))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&apos;")))

(defn- url-element
  "One <url> entry with hreflang alternates for every locale."
  [req route-name params]
  (let [locs    (locales)
        primary (first locs)
        loc-url (abs-url req route-name primary params)
        alts    (for [l locs]
                  (str "<xhtml:link rel=\"alternate\" hreflang=\""
                       (name l)
                       "\" href=\""
                       (xml-escape (abs-url req route-name l params))
                       "\"/>"))
        x-default (str "<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\""
                       (xml-escape loc-url) "\"/>")]
    (str "<url><loc>" (xml-escape loc-url) "</loc>"
         (apply str alts) x-default
         "</url>")))

;; ---------------------------------------------------------------------------
;; Dynamic ID queries — one per collection that exposes single-item routes.
;; Slugged collections also return the slug so the URL matches the canonical
;; form the single-handler would redirect to.
;; ---------------------------------------------------------------------------

(defn- programme-rows []
  (db/query {:select [s/programme-id s/programme-slug] :from [:programme]}))

(defn- kuenstler-rows []
  (db/query {:select [s/kuenstler-id s/kuenstler-slug] :from [:kuenstler]}))

(defn- cds-rows []
  (db/query {:select [s/cds-id] :from [:cds]}))

(defn- presse-rows []
  (db/query {:select [s/presse-id] :from [:presse]}))

(defn- termine-rows []
  ;; Past termine stay in the sitemap so any backlinks remain resolvable;
  ;; only excluded if they hold no information at all (no datum).
  (db/query {:select [s/termine-id]
             :from   [:termine]
             :where  [:not= s/termine-datum nil]}))

(defn- sonderseiten-rows []
  ;; Skip page-id 1 — that's the legacy paedagogik URL, now permanently
  ;; redirected to /klavierunterricht-koeln. Including it in the sitemap
  ;; would dilute the canonical signal.
  (db/query {:select [s/sonderseiten-id s/sonderseiten-slug]
             :from   [:sonderseiten]
             :where  [:not= s/sonderseiten-id 1]}))

(defn- slug-or-id [{:keys [id slug]}]
  (or (some-> slug putils/slugify) (str id)))

(defn- dynamic-entries [req]
  (p/let [[programmes kuenstler cds presse termine pages]
          (p/all [(programme-rows)
                  (kuenstler-rows)
                  (cds-rows)
                  (presse-rows)
                  (termine-rows)
                  (sonderseiten-rows)])]
    (concat
     (for [{:keys [id] :as row} programmes]
       [:programm {:programm-id id :programm-slug (slug-or-id row)}])
     (for [{:keys [id] :as row} kuenstler]
       [:kuenstler-single {:kuenstler-id id :kuenstler-slug (slug-or-id row)}])
     (for [{:keys [id]} cds]
       [:cd {:cd-id id}])
     (for [{:keys [id]} presse]
       [:presse-artikel {:artikel-id id}])
     (for [{:keys [id]} termine]
       [:termin-einzelansicht {:termin-id id}])
     (for [{:keys [id slug]} pages]
       [:single-page {:page-id id :page-slug (or slug (str id))}]))))

(def ^:private static-routes
  ;; Top-level pages that exist for every locale.
  [[:home {}]
   [:termine {}]
   [:kuenstler {}]
   [:programme {}]
   [:cds {}]
   [:galerie {}]
   [:presse {}]
   [:paedagogik {}]])

(defn- build-body [req entries]
  (let [urls (->> entries
                  (map (fn [[route-name params]]
                         (url-element req route-name params)))
                  (apply str))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
         "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""
         " xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">"
         urls
         "</urlset>")))

(defn handler
  "Plain 3-arg handler; bypasses macchiato-async/defhandler so the
  promise from the DB query is awaited directly via .then. This avoids
  the recursive handle-response chain that some middleware combinations
  break on for non-HTML responses."
  [req res _raise]
  (-> (dynamic-entries req)
      (.then (fn [dynamic]
               (let [body (build-body req (concat static-routes dynamic))]
                 (res (-> (r/ok body)
                          (r/content-type "application/xml; charset=utf-8")
                          (r/header "Cache-Control" "public, max-age=3600"))))))))
