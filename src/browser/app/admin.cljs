(ns app.admin
  "Site entry point for the site-wide translation dashboard (served at
   /<locale>/admin). The dashboard itself lives in the shared psite-translate
   module; this shim only pulls in the site's re-frame helpers and computes the
   target-language candidates from this site's config before handing off."
  (:require
   [app.reframe]
   [config.env :as env]
   [psite-translate.admin :as pta]))

(defn- candidate-langs []
  (->> (env/setting :locale-fallback)
       (map name)
       (remove #{"de"})))

;; Called by the page onload (app.admin.main). `data` carries :locale and
;; :csrf-token from the server; we add this site's :candidate-langs.
(defn ^:export main [data]
  (pta/main (assoc data :candidate-langs (vec (candidate-langs)))))
