(ns seiten.translate
  "Site-wide translation tool backend for the /admin dashboard.

   Thin site shim over psite-translate.core: this namespace holds only what is
   specific to Violina — the `translatables` schema map and the `cfg` that wires
   in this site's config (gateway url/key, db query fn, LLM_SITE_* context) — and
   delegates the actual gaps/start/job logic to the shared module.

   Two endpoints, both mounted under /admin (so wrap-directus-user has attached
   :directus-user):
     GET  /admin/api/translation/gaps?langs=en,uk
     POST /admin/api/translation/start  {collection field pk langs}
     GET  /admin/api/translation/job?id=<jobId>

   NOTE: `translatables` is the single source of truth for what gets translated.
   The Directus schema snapshot is not shipped to prod, so this cannot be derived
   at runtime — keep it in sync by hand when the translation schema changes
   (add/remove a translated field, or a new translated collection)."
  {:clj-kondo/config '{:lint-as {macchiato-async.core/defhandler clojure.core/defn}}}
  (:require [config.env :as env]
            [db.setup :as db]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-translate.core :as pt]))

;; ---------------------------------------------------------------------------
;; Schema: what to translate
;; ---------------------------------------------------------------------------
;;
;; Per collection:
;;   :table       - the *_translations table
;;   :fk          - foreign-key column back to the parent (mostly <coll>_id,
;;                  but cds uses cd_id)
;;   :label-field - a text field used to label the item in the UI list
;;   :status?     - whether the *_translations table has a `status` column
;;                  (copied onto newly-inserted rows)
;;   :fields      - {<field> :plain|:html}; :html fields are sent to the gateway
;;                  with format "html" so markup is preserved.

(def translatables
  {:begruessung  {:table :begruessung_translations :fk :begruessung_id
                  :label-field :untertitel :status? true
                  :fields {:untertitel :plain :haupttext :html :meta_description :plain}}
   :cds          {:table :cds_translations :fk :cd_id
                  :label-field :titel :status? true
                  :fields {:titel :plain :beschreibung :html :zitat :plain
                           :zitatquelle :plain :kaufbutton :plain :meta_description :plain}}
   :kuenstler    {:table :kuenstler_translations :fk :kuenstler_id
                  :label-field :name :status? true
                  :fields {:name :plain :ankuendigung :plain :beschreibung :html
                           :besetzung :plain :meta_description :plain}}
   :laender      {:table :laender_translations :fk :laender_id
                  :label-field :name :status? false
                  :fields {:name :plain}}
   :metadaten    {:table :metadaten_translations :fk :metadaten_id
                  :label-field :description :status? false
                  :fields {:description :plain}}
   :paedagogik   {:table :paedagogik_translations :fk :paedagogik_id
                  :label-field :titel :status? false
                  :fields {:titel :plain :untertitel :plain :Beschreibung :html
                           :meta_description :plain :meta_title :plain :og_image_alt :plain}}
   :programme    {:table :programme_translations :fk :programme_id
                  :label-field :titel :status? true
                  :fields {:titel :plain :beschreibung :html :stuecke :html}}
   :sonderseiten {:table :sonderseiten_translations :fk :sonderseiten_id
                  :label-field :titel :status? true
                  :fields {:titel :plain :inhalt :html :meta_description :plain}}
   :texts        {:table :texts_translations :fk :texts_id
                  :label-field :text :status? false
                  :fields {:text :plain}}})

;; ---------------------------------------------------------------------------
;; Site config handed to the shared module
;; ---------------------------------------------------------------------------

(def ^:private cfg
  {:translatables translatables
   :source-lang   "de"
   :max-chars     30000
   :site-context  (pt/site-context-from-settings env/setting)
   :gateway-url   (env/setting [:llm-gateway :url])
   :gateway-key   (env/setting [:llm-gateway :api-key])
   :query         db/query})

;; ---------------------------------------------------------------------------
;; Handlers — delegate to the shared module
;; ---------------------------------------------------------------------------

(defhandler gaps-handler  [req] (pt/gaps cfg req))
(defhandler start-handler [req] (pt/start cfg req))
(defhandler job-handler   [req] (pt/job cfg req))
