(ns test.server
  (:require [clj-http.client :as client]
            [test.routes :as routes]))

(defn- start-server []
  (let [command  ["node" "server/server.js"]
        pb       (doto (ProcessBuilder. (into-array String command))
                   (.inheritIO))]
    (.put (.environment pb) "MERGE_CONFIG" "settings_test.edn")
    (println "starting server" command)
    (.start pb)))

(defn- app-ready? [url]
  (try
    (let [status (:status (client/get url {:timeout 1000}))]
      (println "status is " status)
      (= status 200))
    (catch Exception _ false)))

(defn- wait-for-app [url max-attempts]
  (loop [attempt 1]
    (println "waiting-for-app…" attempt)
    (if (or (> attempt max-attempts) (app-ready? url))
      (app-ready? url)
      (do
        (Thread/sleep 1000)             ; wait for 1 second
        (recur (inc attempt))))))

(defn fixture [t]
  (let [proc (start-server)
        url  (routes/url :home {:locale :de})]
    (try
      (when-not (wait-for-app url 10)
        (throw (ex-info (str "server never became ready at " url) {:url url})))
      (t)
      (finally (.destroy proc)))))
