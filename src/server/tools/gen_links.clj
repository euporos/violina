(ns tools.gen-links
  (:require [clojure.edn :as edn]
            [directus-schema.links :as links]
            [seiten.routes :as seiten]))

(defn run [_]
  ;; gen-links only needs :canonical-domain, so swallow any reader tag
  ;; (#psite/secret, #psite-config.read/obfuscate, future ones) as identity.
  (let [settings (edn/read-string {:default (fn [_tag v] v)}
                                  (slurp "settings.edn"))]
    (links/generate-links
     {:routes        (into ["/{locale}"] seiten/routes)
      :domain        (:canonical-domain settings)
      :snapshot-path "schema/snapshot.json"})))
