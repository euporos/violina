(ns app.modal
  (:require
   [directus.core :as d]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

(defonce content (r/atom nil))

(rf/reg-event-db ::close
                 (fn [db _]
                   (dissoc db :modal-content)))

(rf/reg-sub ::content
            (fn [db _]
              (get db :modal-content)))

(rf/reg-event-db ::display
                 (fn [db [_ component]]
                   (assoc db :modal-content component)))

(defn set-content  [comp]
  (reset! content comp))

(defn notes []
  [:div.notes-container
   [:div.notes
    [:div.note-1 "♫ ♩"]
    [:div.note-2 "♩"]
    [:div.note-3 "♯ ♪"]
    [:div.note-4 "♪"]]])

(defn modal []
  [:div.modal
   {:class (when
            @content
             "is-active")}
   [:div.modal-background
    {:on-click #(reset! content nil)}]
   [:div.modal-content
    @content]
   [:button.modal-close.is-large
    {:on-click #(reset! content nil)
     :aria-label "close"}]])

(defn ^:export open-image [image]
  (set-content
   [:p.image
    [:img {:src (d/image-by-preset "1200" image)
           :width "auto"}]]))

(defn  ^:dev/after-load mount []
  (rf/clear-subscription-cache!)
  (rdom/render [modal] (js/document.getElementById "modal")))

(defn ^:export init []
  (.addEventListener js/document "keyup"
                     (fn [e]
                       (let [e (or e (.-event js/window))]
                         (when  (#{"Escape" "Enter"} (.-key e))
                           (reset! content nil)))))
  (mount)
  (println "Modal ready"))

