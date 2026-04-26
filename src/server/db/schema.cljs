(ns db.schema
  (:require-macros [directus-schema.core :refer [defschema]]))

(defschema "schema/snapshot.json"
  {:locales              ["de" "en" "uk"]
   :translations-suffix  "_translations"
   :language-key         "languages_code"})
