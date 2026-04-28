(ns seiten.page
  (:require [cljstache.core :as tache]
            [db.setup :as db]
            [db.schema :as s]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.components.common :as cmn]
            [seiten.templates :as templates])
  (:require-macros
   [hiccups.core :refer [html]]))

(def tache-data
  {:email (html (ph/obfuscate-email [:frontend :contact-email]))
   :phone (html (ph/obfuscate-phone [:frontend :contact-phone]))})

;; Legacy /pg/1-pädagogik URL is the old home of the teaching page.
;; Permanently redirect it to the dedicated, keyword-bearing route so
;; existing inbound links and search results route to the new hub.
(def ^:private legacy-paedagogik-page-id 1)

(defhandler handler [req]
  (p/let [locale  (:locale req)
          page-id (routing/path-param req :page-id)]
    (if (= page-id legacy-paedagogik-page-id)
      (r/moved-permanently
       (routing/reverse-match req :paedagogik {}))
      (p/let [pages    (db/query {:select [s/sonderseiten-id
                                           s/sonderseiten-bild
                                           s/sonderseiten-bildlayout
                                           (db/localized s/sonderseiten-titel locale)
                                           (db/localized s/sonderseiten-inhalt locale)
                                           (db/localized s/sonderseiten-meta_description locale)]
                                  :from   [[s/sonderseiten_t s/sonderseiten]]
                                  :where  [:= :id page-id]})
              {:keys [titel inhalt bild bildlayout meta_description]} (first pages)
              tache-data (select-keys tache-data (tache/tags (or inhalt "")))
              rendered (templates/head-and-foot-dynamic
                        req {:titel        titel
                             :beschreibung meta_description}
                        [:div.mainframe
                         [:div.sheet
                          [:div.sheet__header titel]
                          [:div.sheet__body
                           (cmn/format-sheet-image bildlayout bild)
                           [:div.sheet__fliesstext
                            (ph/dangerous-html (tache/render (or inhalt "") tache-data))]]]])]
        (ph/html->response rendered)))))
