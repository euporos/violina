(ns test.db
  (:require
   [clojure.java.shell :refer [sh]]
   [next.jdbc :as jdbc]
   [test.config :as cfg]))

(def pg-port (or (System/getenv "PGPORT") "5435"))

(def meta-db-config {:dbtype "postgresql"
                     :host "127.0.0.1"
                     :port pg-port
                     :dbname "postgres"
                     :user "postgres"})

(def db-name (get-in cfg/config [:db-config :db-name]))
(def db-user (get-in cfg/config [:db-config :user]))
(def db-password (get-in cfg/config [:db-config :password]))

(def db-config {:dbtype "postgresql"
                :host "127.0.0.1"
                :port pg-port
                :dbname db-name
                :user db-user
                :password db-password})

(defn execute! [ds sql & [params]]
  (jdbc/execute! ds [sql] params))

(defn sh-throw-on-error
  [command & args]
  (let [result (apply sh command args)]
    (if (= 0 (:exit result))
      result
      (throw (ex-info "Command failed" result)))))

(defn init-data []
  (let [tempfile "dump_to_test.sql"
        ds (jdbc/get-datasource db-config)]
    (println "Filling test DB")
    (sh-throw-on-error "bash" "-c"
                       (format "pg_dump --no-owner --no-privileges -h 127.0.0.1 -p %s -U postgres -d directus -f %s"
                               pg-port tempfile))
    (sh-throw-on-error "bash" "-c"
                       (format "psql -h 127.0.0.1 -p %s -U postgres -d %s -v ON_ERROR_STOP=1 -f %s"
                               pg-port db-name tempfile))
    (sh-throw-on-error "rm" tempfile)
    (execute! ds "DELETE FROM reservations")))

(defn setup []
  (let [root-ds (jdbc/get-datasource meta-db-config)]
    (println "setting up test DB")
    (execute! root-ds (format "DROP DATABASE IF EXISTS %s" db-name))
    (execute! root-ds
              (format "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%s') THEN CREATE ROLE %s LOGIN PASSWORD '%s'; END IF; END $$"
                      db-user db-user db-password))
    (execute! root-ds (format "CREATE DATABASE %s OWNER %s" db-name db-user))
    (init-data)))

(defn teardown []
  (let [root-ds (jdbc/get-datasource meta-db-config)]
    (println "tearing down test DB")
    (execute! root-ds (format "DROP DATABASE IF EXISTS %s" db-name))
    (execute! root-ds (format "DROP ROLE IF EXISTS %s" db-user))))

(defn fixture [f]
  (setup)
  (f)
  (teardown))

(comment
  (setup)
  (teardown))
