(ns seiten.presse
  (:require [comp.snippets :as snip]
            [config.env :as env]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [seiten.components.common :as cmn]
            [seiten.templates :as templates]))

(defn- language-rank
  "Index in fallback order, with the current locale forced to 0."
  [locale fallback sprache]
  (let [k (some-> sprache keyword)]
    (cond
      (= k locale)             0
      (nil? k)                 99
      :else
      (let [idx (.indexOf (vec (remove #{locale} fallback)) k)]
        (if (neg? idx) 99 (inc idx))))))

(defn- sort-articles [locale fallback rows]
  (sort (fn [a b]
          (let [la (language-rank locale fallback (:sprache a))
                lb (language-rank locale fallback (:sprache b))]
            (if (= la lb)
              ;; datum desc — YYYY-MM-DD strings compare lexicographically
              (compare (or (some-> (:datum b) str) "")
                       (or (some-> (:datum a) str) ""))
              (compare la lb))))
        rows))

(defn- artikelvorschau [{:keys [id medium autor ueberschrift vorschautext volltext
                                bild nurbild sprache]}]
  [:div.zeitung__artikel
   {:id              (str "artikel-" id)
    :data-artikel-id id
    :data-nurbild    (if nurbild "true" "false")
    :style           "cursor: pointer;"}
   [:div.zeitung__autor (if (seq medium) medium autor)]
   (when-not nurbild
     [:div.zeitung__artikelueberschrift ueberschrift])
   (when bild
     [:img.zeitung__vorschaubild {:src (d/image-by-preset "w600" bild)}])
   [:div.zeitung__artikelvorschau {:lang sprache}
    (cond
      (seq vorschautext) (ph/dangerous-html vorschautext)
      (seq volltext)     (cmn/generate-vorschautext volltext)
      :else              "")
    (when (seq volltext)
      [:span.zeitung__weiterlesen.standardlink
       "→ " (snip/weiterlesen (some-> sprache keyword))])]])

(defn- zeitung [locale articles]
  [:div.zeitung
   [:div.zeitung__background]
   [:div.zeitung__header (snip/pressespiegel locale)]
   (into [:div.zeitung__main]
         (map artikelvorschau articles))])

(defhandler handler [req]
  (p/let [locale   (:locale req)
          fallback (env/setting :locale-fallback)
          rows     (db/query
                    {:select   [s/presse-id s/presse-medium s/presse-autor
                                s/presse-ueberschrift s/presse-vorschautext
                                s/presse-volltext s/presse-bild s/presse-nurbild
                                s/presse-sprache s/presse-datum]
                     :from     [:presse]
                     :where    [:not= s/presse-ueberschrift nil]})
          articles (sort-articles locale fallback rows)
          rendered (templates/head-and-foot-dynamic
                    req {:titel        (snip/presse locale)
                         :beschreibung (snip/meta-presse locale)
                         :cljs  {:onload "app.presse.init"}}
                    [:div.mainframe
                     (zeitung locale articles)
                     [:div#pressepopup-mount]])]
    (ph/html->response rendered)))

(defhandler single-handler [req]
  (let [locale     (:locale req)
        artikel-id (routing/path-param req :artikel-id)]
    (r/found (str "/" (name locale) "/presse?artikel-id=" artikel-id))))
