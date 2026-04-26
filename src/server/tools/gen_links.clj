(ns tools.gen-links
  (:require [clojure.edn :as edn]
            [directus-schema.links :as links]
            [seiten.routes :as seiten]))

(defn run [_]
  (let [settings (edn/read-string {:readers {'psite/secret (constantly nil)}}
                                  (slurp "settings.edn"))]
    (links/generate-links
     {:routes        (into ["/{locale}"] seiten/routes)
      :domain        (:canonical-domain settings)
      :snapshot-path "schema/snapshot.json"})))
