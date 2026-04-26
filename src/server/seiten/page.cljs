(ns seiten.page
  (:require [cljstache.core :as tache]
            [db.setup :as db]
            [db.schema :as s]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates])
  (:require-macros
   [hiccups.core :refer [html]]))

(def tache-data
  {:email (html (ph/obfuscate-email [:frontend :contact-email]))
   :phone (html (ph/obfuscate-phone [:frontend :contact-phone]))})

(defhandler handler [req]
  (p/let [locale  (:locale req)
          page-id (routing/path-param req :page-id)
          pages   (db/query {:select [s/sonderseiten-id
                                      (db/localized s/sonderseiten-titel locale)
                                      (db/localized s/sonderseiten-inhalt locale)]
                             :from   [[s/sonderseiten_t s/sonderseiten]]
                             :where  [:= :id page-id]})
          {:keys [titel inhalt]} (first pages)
          tache-data (select-keys tache-data (tache/tags inhalt))]
    (p/let [rendered (templates/head-and-foot-dynamic
                      req {:titel titel}
                      [:div.section
                       [:h1.title.is-1.has-text-centered titel]
                       [:div.container
                        [:div.content
                         (ph/dangerous-html (tache/render inhalt tache-data))]]])]
      (ph/html->response rendered))))
