(ns test.db
  (:require
   [clojure.java.shell :refer [sh]]
   [next.jdbc :as jdbc]
   [test.config :as cfg]))

(def meta-db-config {:dbtype "mysql"
                     :dbname "test"
                     :user "root"
                     :password ""
                     :serverTimezone "UTC"})

(def db-name (get-in cfg/config [:db-config :db-name]))
(def db-user (get-in cfg/config [:db-config :user]))
(def db-password (get-in cfg/config [:db-config :password]))

(def  db-config {:dbtype "mysql"
                 :dbname db-name
                 :user db-user
                 :password db-password
                 :serverTimezone "UTC"})

(defn execute! [ds sql & [params]]
  (jdbc/execute! ds [sql] params))

(defn sh-throw-on-error
  [command & args]
  (let [result (apply sh command args)]
    (if (= 0 (:exit result))
      result
      (throw (ex-info "Command failed" result)))))

(defn init-data  []
  (let [tempfile "dump_to_test.sql"
        ds (jdbc/get-datasource db-config)]
    (println "Filling test DB")
    (sh-throw-on-error "bash" "-c" (format "mysqldump --protocol=tcp -h 127.0.0.1 -u %s festival_directus > %s" (:user meta-db-config) tempfile))
    (sh-throw-on-error "bash" "-c" (format "mysql --protocol=tcp -h 127.0.0.1 -u %s %s < %s" (:user meta-db-config) db-name tempfile))
    (sh-throw-on-error "rm" tempfile)
    (execute! ds (format "DELETE FROM reservations"))))

(defn setup []
  (let [root-ds (jdbc/get-datasource meta-db-config)]
    (println "setting up test DB")
    (execute! root-ds (format "CREATE DATABASE IF NOT EXISTS %s" db-name))
    (execute! root-ds (format "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s'" db-user db-password))
    (execute! root-ds (format "GRANT ALL PRIVILEGES ON %s.* TO '%s'@'%%'" db-name db-user))
    (execute! root-ds "FLUSH PRIVILEGES")
    (init-data)))

(defn teardown []
  (let [root-ds (jdbc/get-datasource meta-db-config)]
    (println "tearing down test DB")
    (execute! root-ds (format "DROP DATABASE IF EXISTS %s" db-name))
    (execute! root-ds (format "DROP USER IF EXISTS '%s'@'%%'" db-user))))

(defn fixture [f]
  (setup)
  (f)
  (teardown))

(comment
  (setup)
  (teardown))
