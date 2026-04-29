(ns app.presse
  (:require
   [clojure.string :as str]
   [psite-transit.core :as transit]
   [psite-transit.cljs-time :as transit.time]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

(defonce article (r/atom nil))

(def reader
  (transit/make-reader {:handlers transit.time/read-handlers}))

(defn- locale-from-path []
  (or (some-> (.-pathname js/location)
              (.split "/")
              (aget 1))
      "de"))

(defn- query-param [name]
  (some-> (js/URLSearchParams. (.-search js/location))
          (.get name)))

(defn fetch-article [locale id]
  (-> (js/fetch (str "/" locale "/api/presse/" id)
                #js {:cache   "no-store"
                     :headers #js {"Accept" "application/transit+json"}})
      (.then (fn [r] (.text r)))
      (.then (fn [s]
               (let [data (transit/deserialize reader s)]
                 (reset! article data))))
      (.catch (fn [e] (js/console.error "presse detail fetch failed" e)))))

(defn close [] (reset! article nil))

(defn- vollansicht-standard [{:keys [medium autor datum ueberschrift volltext link]}]
  [:<>
   [:div.popup__header
    [:div.popup__autor medium]
    [:div.popup__ueberschrift ueberschrift]]
   [:div.popup__haupttext
    [:div {:dangerouslySetInnerHTML
           {:__html (str/replace (or volltext "") "\n" " ")}}]
    [:div.popup__datum
     (str/join ", " (filter seq [autor datum]))]]
   (when link
     [:div.popup__footer
      [:a.popup__originallink
       {:href   link
        :target "_blank"
        :rel    "noopener noreferrer"}
       "Zum Original"]])])

(defn- vollansicht-nurbild [{:keys [medium bild-url bild-width bild-height link]}]
  [:<>
   [:div.popup__header
    [:div.popup__autor medium]]
   [:div.popup__scan
    (when bild-url
      [:img {:src    bild-url
             :width  bild-width
             :height bild-height
             :alt    medium}])]
   (when link
     [:div.popup__footer
      [:a.popup__originallink
       {:href link :target "_blank" :rel "noopener noreferrer"}
       "Zum Original"]])])

(defn popup []
  (let [a @article]
    [:div#pressepopup.popup
     {:class    (when a "popup--revealed")
      :on-click (fn [e]
                  (when (= (.-target e) (.-currentTarget e))
                    (close)))}
     [:div.popup__window
      [:div.popup__schliesskreuz {:on-click close} "×"]
      [:div#popup__artikelvollansicht
       (when a
         (if (:nurbild a)
           [vollansicht-nurbild a]
           [vollansicht-standard a]))]]]))

(defn- on-zeitung-click [locale e]
  (when-let [el (.closest (.-target e) ".zeitung__artikel")]
    (when-let [id (.getAttribute el "data-artikel-id")]
      (.preventDefault e)
      (fetch-article locale id))))

(defn ^:dev/after-load mount []
  (when-let [el (js/document.getElementById "pressepopup-mount")]
    (rdom/render [popup] el)))

(defn ^:export init [_data]
  (let [locale (locale-from-path)]
    (.addEventListener js/document "keyup"
                       (fn [e]
                         (when (= "Escape" (.-key e)) (close))))
    (when-let [zeitung (js/document.querySelector ".zeitung__main")]
      (.addEventListener zeitung "click" (partial on-zeitung-click locale)))
    (mount)
    (when-let [id (query-param "artikel-id")]
      (fetch-article locale id))))
