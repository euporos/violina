(ns seiten.cds
  (:require [clojure.edn :as edn]
            [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.templates :as templates]))

;; cds_translations uses singular FK column `cd_id`, breaking the standard
;; `<collection>_id` convention that directus-schema's view generator assumes.
;; So we cannot use cds_t — instead JOIN cds_translations directly per locale.
(def ^:private locales [:de :en :uk :it])

(defn- locale-alias [l] (keyword (str "t_" (name l))))

;; Some fields (titel) also live on the parent cds table as a fallback —
;; CMS editors may leave the per-language translation blank.
(def ^:private base-fallback-fields #{:titel})

(defn- localized-cds [field locale]
  (let [order  (cons locale (remove #{locale} locales))
        cols   (map #(keyword (str (name (locale-alias %)) "." (name field))) order)
        cols   (cond-> (vec cols)
                 (base-fallback-fields field)
                 (conj (keyword (str "c." (name field)))))]
    [(into [:coalesce] cols) field]))

(defn- locale-joins []
  (->> locales
       (mapcat (fn [l]
                 [[:cds_translations (locale-alias l)]
                  [:and
                   [:= (keyword (str (name (locale-alias l)) ".cd_id")) :c.id]
                   [:= (keyword (str (name (locale-alias l)) ".languages_code")) (name l)]]]))))

(defn- query-cds [locale]
  (db/query
   {:select   (into [:c.id :c.recto :c.paypal_value :c.edn]
                    (map #(localized-cds % locale)
                         [:titel :zitat :zitatquelle :kaufbutton]))
    :from     [[:cds :c]]
    :left-join (locale-joins)
    :order-by [[:c.erscheinungsjahr :desc] [:c.id :desc]]}))

(defn- query-cd [locale id]
  (p/let [rows (db/query
                {:select   (into [:c.id :c.recto :c.paypal_value :c.edn]
                                 (map #(localized-cds % locale)
                                      [:titel :zitat :zitatquelle :beschreibung
                                       :meta_description :kaufbutton]))
                 :from     [[:cds :c]]
                 :left-join (locale-joins)
                 :where    [:= :c.id id]})]
    (first rows)))

(defn- query-email-template [locale]
  (p/let [rows (db/query
                {:select [(db/localized s/texts-text locale)]
                 :from   [[s/texts_t s/texts]]
                 :where  [:= s/texts-keyword "cd-bestell-email"]})]
    (:text (first rows))))

(defn- parse-edn-field [cd]
  (update cd :edn #(when (seq %) (edn/read-string %))))

(defn- zitat-block [locale {:keys [zitat zitatquelle]}]
  (when (seq zitat)
    [:div.zitat
     [:div.zitat__text
      [:span.quotemark.quotemark--l (snip/quoteleft locale)]
      [:span.zitatinnen zitat]
      [:span.quotemark.quotemark--r (snip/quoteright locale)]]
     (when (seq zitatquelle)
       [:div.zitat__quelle "— " zitatquelle " —"])]))

(defn- listenansicht-item [req {:keys [id titel recto] :as cd}]
  (let [locale (:locale req)]
    [:div.sheet
     [:div.sheet__header.sheet__header--cd titel]
     [:div.sheet__body
      [:a {:href (routing/reverse-match req :cd {:cd-id id})}
       [:img.sheet__bild--cd {:src (d/image-by-preset "w600" recto)}]]
      [:div.cdinfo
       (zitat-block locale cd)
       [:div {:id (str "cd-order-" id)}]]]]))

(defhandler handler [req]
  (p/let [locale         (:locale req)
          rows           (query-cds locale)
          cds            (mapv parse-edn-field rows)
          email-template (query-email-template locale)
          rendered       (templates/head-and-foot-dynamic
                          req {:titel (snip/cds locale)
                               :cljs  {:onload "app.cds.init"
                                       :js-data {:cds cds
                                                 :email-text email-template
                                                 :sales-region :eu}}}
                          [:div.mainframe
                           (map (partial listenansicht-item req) cds)])]
    (ph/html->response rendered)))

(defn- detail-sheet [req {:keys [id titel recto beschreibung] :as cd}]
  (let [locale (:locale req)]
    [:div.sheet
     [:div.sheet__header.sheet__header--cd titel]
     [:div.sheet__body
      [:img.sheet__bild--cd {:src (d/image-by-preset "w1200" recto)}]
      [:div.cdinfo
       (zitat-block locale cd)
       (when (seq beschreibung)
         [:div.sheet__fliesstext (ph/dangerous-html beschreibung)])
       [:div {:id (str "cd-order-" id)}]]]]))

(defhandler single-handler [req]
  (p/let [locale         (:locale req)
          cd-id          (routing/path-param req :cd-id)
          cd-row         (query-cd locale cd-id)]
    (if-not cd-row
      (r/not-found "CD not found")
      (p/let [cd             (parse-edn-field cd-row)
              email-template (query-email-template locale)
              rendered       (templates/head-and-foot-dynamic
                              req {:titel        (:titel cd)
                                   :beschreibung (:meta_description cd)
                                   :og-image     (d/image-by-preset "og-image" (:recto cd))
                                   :cljs         {:onload "app.cds.init"
                                                  :js-data {:cds [cd]
                                                            :email-text email-template
                                                            :sales-region :eu}}}
                              [:div.mainframe
                               (detail-sheet req cd)])]
        (ph/html->response rendered)))))
