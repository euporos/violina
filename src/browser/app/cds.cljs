(ns app.cds
  (:require
   [app.reframe]
   [cljstache.core :refer [render]]
   [comp.snippets :as snip]
   [config.env :as env]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

(rf/reg-event-db ::init
                 (fn [db [_ data]]
                   (merge db data)))

(rf/reg-sub ::cds         (fn [db _] (:cds db)))
(rf/reg-sub ::sales-region (fn [db _] (:sales-region db)))
(rf/reg-sub ::email-text  (fn [db _] (:email-text db)))
(rf/reg-sub ::locale      (fn [db _] (:locale db)))
(rf/reg-sub ::widmung     (fn [db [_ id]] (get-in db [:widmungen id])))

(rf/reg-event-db ::set-region
                 (fn [db [_ region]] (assoc db :sales-region region)))

(rf/reg-event-db ::set-widmung
                 (fn [db [_ id v]] (assoc-in db [:widmungen id] v)))

(rf/reg-sub ::paypal-button-id
            (fn [db [_ cd]]
              (let [region (:sales-region db)]
                (or (get-in cd [:edn :paypal region :button-id])
                    (get-in cd [:edn :paypal :world :button-id])
                    (:paypal_value cd)))))

(rf/reg-sub ::shipment-cost
            (fn [db [_ cd]]
              (let [region (:sales-region db)]
                (or (get-in cd [:edn :paypal region :shipment])
                    (get-in cd [:edn :paypal :world :shipment])
                    0))))

(rf/reg-sub ::total-cost
            (fn [[_ cd]]
              [(rf/subscribe [::shipment-cost cd])])
            (fn [[ship] [_ cd]]
              (+ ship (or (get-in cd [:edn :base-price]) 0))))

(defn- region-options [db-cds]
  (->> db-cds
       (mapcat #(keys (get-in % [:edn :paypal])))
       distinct
       (filter #{:eu :world :ua})
       sort
       (#(or (seq %) [:eu :world]))))

(defn- region-label [region locale]
  (case region
    :eu    (snip/european-union locale)
    :world (snip/weltweit locale)
    :ua    (snip/ukraine locale)
    (name region)))

(defn region-selector []
  (let [locale  @(rf/subscribe [::locale])
        region  @(rf/subscribe [::sales-region])
        cds     @(rf/subscribe [::cds])]
    [:select
     {:value     (name region)
      :on-change #(rf/dispatch [::set-region (keyword (-> % .-target .-value))])}
     (for [opt (region-options cds)]
       ^{:key opt} [:option {:value (name opt)} (region-label opt locale)])]))

(defn paypal-form [cd]
  [:form {:action "https://www.paypal.com/cgi-bin/webscr"
          :method "post" :target "_top"}
   [:input {:type "hidden" :name "cmd" :value "_s-xclick"}]
   [:input {:type "hidden" :name "hosted_button_id"
            :value @(rf/subscribe [::paypal-button-id cd])}]
   [:input {:type "hidden" :name "on0" :value "Optionale persönliche Widmung:"}]
   [:input {:type "hidden" :name "os0" :maxLength 200
            :value (or @(rf/subscribe [::widmung (:id cd)]) "")}]
   [:input.kaufbutton
    {:type "submit" :name "submit"
     :value "PayPal"
     :alt "Jetzt einfach, schnell und sicher online bezahlen – mit PayPal."}]])

(defn sepa-button [cd]
  (let [tpl     @(rf/subscribe [::email-text])
        widmung (or @(rf/subscribe [::widmung (:id cd)]) "")
        preis   @(rf/subscribe [::total-cost cd])
        body    (when tpl (render tpl {:preis preis :titel (:titel cd) :widmung widmung}))
        subject (str "CD-Bestellung: " (:titel cd))
        b64     (env/setting :email-to-b64)]
    (when b64
      [:button.kaufbutton
       {:type "button"
        :on-click (fn [_]
                    (let [email (js/atob b64)
                          url   (str "mailto:" email
                                     "?subject=" (js/encodeURIComponent subject)
                                     "&body="    (js/encodeURIComponent (or body "")))]
                      (set! js/window.location.href url)))}
       "SEPA"])))

(defn order-widget [cd]
  (let [locale @(rf/subscribe [::locale])
        id     (:id cd)]
    [:div.cd-order
     [:div (snip/jetzt-kaufen locale) " "
      @(rf/subscribe [::total-cost cd]) " €"]
     [:div {:style {:display "flex" :justify-content "center" :margin-top "0.5rem"}}
      [:div {:style {:margin-right "0.5rem"}} [paypal-form cd]]
      [sepa-button cd]]
     [:div {:style {:margin-top "0.5rem"}}
      (snip/versand locale) ": " [region-selector]]
     [:div {:style {:margin-top "0.5rem"}}
      (snip/widmung-bestellung locale)]
     [:input.widmungsinput
      {:type "text" :maxLength 200
       :value (or @(rf/subscribe [::widmung id]) "")
       :on-change #(rf/dispatch [::set-widmung id (-> % .-target .-value)])}]]))

(defn ^:dev/after-load mount []
  (doseq [cd @(rf/subscribe [::cds])
          :let [el (js/document.getElementById (str "cd-order-" (:id cd)))]
          :when el]
    (rdom/render [order-widget cd] el)))

(defn ^:export init [data]
  (let [locale (or (some-> (.-pathname js/location)
                           (.split "/")
                           (aget 1)
                           keyword)
                   :de)
        data   (-> data
                   (update :sales-region #(or % :eu))
                   (assoc :locale locale))]
    (rf/dispatch-sync [::init data])
    (mount)))
