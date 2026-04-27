(ns db.setup
  (:require
   [psite-pg.core :as pg]
   [directus-schema.runtime :as dsr]
   [cljs-time.coerce :as t.coerce]
   [cljs-time.core :as t]
   [config.env :as env]
   [db.schema :as s]
   [mount.core :refer [defstate]]
   [taoensso.timbre :refer [infof]]))

(defn- pg-date->cljs-time
  "node-postgres returns DATE columns as JS Date at LOCAL midnight; converting
   that via from-date shifts the calendar day in non-UTC timezones (e.g. CET
   `2026-01-01` becomes `2025-12-31T23:00Z`). Build a UTC DateTime from the
   JS Date's *local* y/m/d components so the calendar day is preserved."
  [js-date]
  (when js-date
    (t/date-time (.getFullYear js-date)
                 (inc (.getMonth js-date))
                 (.getDate js-date))))

(declare pool)

(defstate pool
  :start (let [db-config (env/setting [:db-config])
               config {:host     (:host db-config)
                       :port     (:port db-config)
                       :user     (:user db-config)
                       :password (:password db-config)
                       :database (:db-name db-config)
                       ;; pg type OIDs: 1082=date, 1114=timestamp, 1184=timestamptz
                       :type-parsers {1082 pg-date->cljs-time
                                      1114 dsr/ts-no-tz->cljs-time
                                      1184 t.coerce/from-date}}
               pool (pg/create-pool config)]
           (dsr/ensure-views! pool s/view-defs)
           pool)
  :stop (.then (pg/end-pool @pool)
               (fn [_] (infof "Database pool closed"))))

(defn query
  "Executes a HoneySQL query against the pool. Returns a native js/Promise."
  ([honeysql] (query {} honeysql))
  ([options honeysql]
   (let [opts (cond-> options
                (env/setting :print-sql)
                (assoc :log-fn (fn [sql params]
                                 (println "\n" sql "\n" params))))]
     (pg/query @pool opts honeysql))))

(def ^:private fallback-order [:de :en :uk])

(defn localized
  "Selects a translated field with locale fallback via COALESCE.
   (localized :concerts.title :uk) => [[:coalesce :title_uk :title_de :title_en] :title]"
  [field-kw locale]
  (dsr/localized field-kw locale fallback-order))
