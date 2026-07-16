(ns seiten.translate
  "Site-wide translation tool backend for the /admin dashboard.

   Fills EMPTY translation fields from the German reference row by calling the
   Ollama/Babashka gateway on netcup-vps-3. The browser (app.admin) drives the
   work field-by-field so runs stay short and interruptible; these handlers are
   stateless.

   Two endpoints, both mounted under /admin (so wrap-directus-user has attached
   :directus-user) and re-gated here:
     GET  /admin/api/translation/gaps?langs=en,uk
     POST /admin/api/translation/field   {collection field pk langs}

   NOTE: `translatables` is the single source of truth for what gets translated.
   The Directus schema snapshot is not shipped to prod, so this cannot be
   derived at runtime — keep it in sync by hand when the translation schema
   changes (add/remove a translated field, or a new translated collection)."
  (:require [clojure.string :as str]
            [config.env :as env]
            [db.setup :as db]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]))

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

(def ^:private source-lang "de")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- strip-html [s]
  (when s (-> s (str/replace #"<[^>]+>" " ") (str/replace #"\s+" " ") str/trim)))

(defn- preview [s]
  (let [t (or (strip-html s) "")]
    (if (> (count t) 90) (str (subs t 0 90) "…") t)))

(defn- json-response
  "Return Clojure data as the response body; wrap-restful-format serializes it
   to JSON based on the request's Accept header (browser sends application/json)."
  [status data]
  {:status status :body data})

(defn- unauthorized? [req] (not (:directus-user req)))

;; ---------------------------------------------------------------------------
;; Gateway call
;; ---------------------------------------------------------------------------

(def ^:private site-context
  {:site_name        "Violina Petrychenko"
   :site_description (str "Website of the classical concert pianist Violina Petrychenko — "
                          "concert programmes, CDs, press reviews and piano pedagogy.")
   :domain           "violina-petrychenko.de"
   :audience         "concert organisers, classical-music audiences and press"
   :tone             "formal and professional, third person"})

;; The gateway does the slow work off-request: POST /jobs starts an async job and
;; returns an id; GET /jobs/:id (no key) reports its state. Neither call is held
;; open for the translation, so no proxy (nginx in front of this app, or undici
;; here) can time it out. The browser drives start→poll via these two handlers.

(defn- gateway-base []
  (str/replace (env/setting [:llm-gateway :url]) #"/+$" ""))

(defn- gateway-start-job
  "POST /jobs — start an async translation. Resolves to the job-id string."
  [text langs fmt]
  (let [key  (env/setting [:llm-gateway :api-key])
        body (js/JSON.stringify
              (clj->js {:text             text
                        :target_languages langs
                        :source_language  source-lang
                        :format           (if (= fmt :html) "html" "plain text")
                        :context          site-context}))]
    (p/let [resp (js/fetch (str (gateway-base) "/jobs")
                           #js {:method  "POST"
                                :headers #js {"Content-Type"  "application/json"
                                              "Authorization" (str "Bearer " key)}
                                :body    body})]
      (if-not (.-ok resp)
        (p/let [t (.text resp)]
          (throw (js/Error. (str "gateway " (.-status resp) ": " (subs (or t "") 0 200)))))
        (p/let [json (.json resp)]
          (.-jobId json))))))

(defn- gateway-poll-job
  "GET /jobs/:id (no key needed). Resolves to {:status :translations :error}."
  [job-id]
  (p/let [resp (js/fetch (str (gateway-base) "/jobs/" (js/encodeURIComponent job-id)))]
    (if-not (.-ok resp)
      (p/let [t (.text resp)]
        (throw (js/Error. (str "gateway job " (.-status resp) ": " (subs (or t "") 0 200)))))
      (p/let [json (.json resp)]
        {:status       (.-status json)
         :translations (js->clj (.-translations json))
         :error        (.-error json)}))))

;; ---------------------------------------------------------------------------
;; Target languages (present in the languages table, minus German)
;; ---------------------------------------------------------------------------

(defn- available-target-langs []
  (p/let [rows (db/query {:select [:code] :from [:languages]})]
    (->> rows (map :code) (remove #(= % source-lang)) set)))

;; ---------------------------------------------------------------------------
;; GET gaps
;; ---------------------------------------------------------------------------

(defn- collection-gaps
  "Work units for one collection given the requested target langs."
  [coll {:keys [table fk label-field fields]} target-langs]
  (p/let [cols (into [fk :languages_code] (keys fields))
          rows (db/query {:select cols :from [table]})]
    (let [by-parent (group-by #(get % fk) rows)]
      (for [[pk group] by-parent
            :let  [de (some #(when (= (:languages_code %) source-lang) %) group)]
            :when de
            [field _fmt] fields
            :when (not (blank? (get de field)))
            :let  [missing (for [lang target-langs
                                 :let [trow (some #(when (= (:languages_code %) lang) %) group)]
                                 :when (blank? (get trow field))]
                             lang)]
            :when (seq missing)]
        {:collection (name coll)
         :field      (name field)
         :format     (name (get fields field))
         :pk         pk
         :langs      (vec missing)
         :label      (or (get de label-field) (str pk))
         :preview    (preview (get de field))}))))

(defhandler gaps-handler [req]
  (if (unauthorized? req)
    (json-response 401 {:error "unauthorized"})
    (p/let [requested (some-> (get-in req [:query-params "langs"])
                              (str/split #",")
                              (->> (map str/trim) (remove str/blank?) set))
            available (available-target-langs)
            targets   (if (seq requested)
                        (filter available requested)
                        available)]
      (if (empty? targets)
        (json-response 200 {:units [] :targets []})
        (p/let [per-coll (p/all (for [[coll cfg] translatables]
                                  (collection-gaps coll cfg targets)))]
          (json-response 200 {:targets (vec targets)
                              :units   (vec (apply concat per-coll))}))))))

;; ---------------------------------------------------------------------------
;; Async translate: start a job, poll it, save on completion
;; ---------------------------------------------------------------------------

;; Skip fields whose German source is too long for the gateway's per-call time
;; budget (kept in sync with the extension's MAX_CHARS_PER_UNIT and the gateway's
;; 30-min Ollama/nginx ceiling — ~30k chars ≈ ~17 min expected, ~21 min worst).
(def ^:private max-chars 30000)

;; jobId -> the upsert context needed to write the result when the job finishes.
;; In-memory (single app-server instance); lost on restart, in which case the
;; browser's poll 404s and it re-runs the unit.
(defonce ^:private jobs (atom {}))

(defn- upsert-translation!
  "Write `value` into `field` of the (pk,lang) translation row: UPDATE if the
   row exists, else INSERT (copying status from the German row when present)."
  [{:keys [table fk status?]} pk field lang value de-status]
  (p/let [existing (db/query {:select [:id] :from [table]
                              :where [:and [:= fk pk] [:= :languages_code lang]]
                              :limit 1})]
    (if-let [id (:id (first existing))]
      (db/query {:update table :set {field value} :where [:= :id id]})
      (db/query {:insert-into table
                 :values [(cond-> {fk pk :languages_code lang field value}
                            status? (assoc :status (or de-status "published")))]}))))

;; POST /start {collection field pk langs} — read the German source, guard the
;; length, kick off a gateway job, and remember what to write when it finishes.
(defhandler start-handler [req]
  (if (unauthorized? req)
    (json-response 401 {:error "unauthorized"})
    (let [{:keys [collection field pk langs]} (:body req)
          cfg      (get translatables (keyword collection))
          field-kw (keyword field)
          fmt      (get-in cfg [:fields field-kw])]
      (cond
        (or (nil? cfg) (nil? fmt))
        (json-response 400 {:error (str "unknown collection/field: " collection "/" field)})

        (empty? langs)
        (json-response 400 {:error "no target languages"})

        :else
        (p/let [de-rows (db/query {:select (cond-> [field-kw]
                                             (:status? cfg) (conj :status))
                                   :from  [(:table cfg)]
                                   :where [:and [:= (:fk cfg) pk] [:= :languages_code source-lang]]
                                   :limit 1})
                de      (first de-rows)
                text    (get de field-kw)]
          (cond
            (blank? text)
            (json-response 400 {:error "no German source content"})

            (> (count text) max-chars)
            (json-response 400 {:error (str "Quelltext zu lang (" (count text) " Zeichen, max. "
                                            max-chars ") — bitte manuell übersetzen")})

            :else
            (p/let [job-id (gateway-start-job text (vec langs) fmt)]
              (swap! jobs assoc job-id {:cfg cfg :pk pk :field field-kw
                                        :langs (vec langs) :de-status (:status de)})
              (json-response 202 {:jobId job-id}))))))))

;; GET /job?id=<jobId> — poll the gateway; on completion, write the rows and
;; drop the job. Returns {:status running|done|error}.
(defhandler job-handler [req]
  (if (unauthorized? req)
    (json-response 401 {:error "unauthorized"})
    (let [job-id (get-in req [:query-params "id"])
          ctx    (get @jobs job-id)]
      (if (nil? ctx)
        (json-response 404 {:error "unknown or expired job"})
        (p/let [{:keys [status translations error]} (gateway-poll-job job-id)]
          (cond
            (= status "done")
            (p/let [{:keys [cfg pk field langs de-status]} ctx
                    _ (p/all (for [lang langs
                                   :let [val (get translations lang)]
                                   :when (not (blank? val))]
                               (upsert-translation! cfg pk field lang val de-status)))]
              (swap! jobs dissoc job-id)
              (json-response 200 {:status "done"}))

            (= status "error")
            (do (swap! jobs dissoc job-id)
                (json-response 200 {:status "error" :error error}))

            :else
            (json-response 200 {:status "running"})))))))
