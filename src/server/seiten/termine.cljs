(ns seiten.termine
  (:require [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [clojure.string :as str]
            [comp.localization :as loc]
            [comp.snippets :as snip]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [psite-datetime.core :as td]
            [psite-hiccup.core :as ph]
            [psite-routing.core :as routing]
            [psite-utils.core :as putils]
            [seiten.components.common :as cmn]
            [seiten.templates :as templates]))

;; ##################
;; #### Helpers #####
;; ##################

(defn- archiv? [req]
  (let [v (or (get-in req [:parameters :query :archive])
              (get-in req [:query-params "archive"]))]
    (boolean (and v (not (#{"" "0" "false"} v))))))

(defn- date->str [d]
  (when d (t.format/unparse (t.format/formatters :date) d)))

(defn- termine-list-query [locale starting-year]
  {:select   [s/termine-id s/termine-datum s/termine-uhrzeit s/termine-abgesagt
              s/termine-eintrittspreis s/termine-waehrung s/termine-veranstalterlink
              s/termine-sonderkuenstler s/termine-sonderprogramm
              [s/orte-stadt :stadt] [s/orte-name :ort_name]
              [s/orte-strasse :strasse] [s/orte-postleitzahl :postleitzahl]
              [s/kuenstler-id :kuenstler_id]
              [s/kuenstler-slug :kuenstler_slug]
              [s/kuenstler-instrumente :instrumente]
              (db/localized s/kuenstler-name locale)
              [s/programme-id :programm_id]
              [s/programme-slug :programm_slug]
              (db/localized s/programme-titel locale)]
   :from     [:termine]
   :left-join [:orte                [:= s/termine-ort s/orte-id]
               [s/kuenstler_t :kuenstler] [:= s/termine-kuenstler s/kuenstler-id]
               [s/programme_t :programme] [:= s/termine-programm s/programme-id]]
   :where    [:>= s/termine-datum (str starting-year "-01-01")]
   :order-by [s/termine-datum s/termine-uhrzeit]
   :limit    1000})

(defn- single-termin-query [locale termin-id]
  {:select    [s/termine-id s/termine-datum s/termine-uhrzeit
               s/termine-abgesagt s/termine-sonderkuenstler s/termine-sonderprogramm
               s/termine-eintrittspreis s/termine-waehrung s/termine-veranstalterlink
               [s/orte-stadt :stadt] [s/orte-name :ort_name]
               [s/orte-strasse :strasse] [s/orte-postleitzahl :postleitzahl]
               [s/kuenstler-id :kuenstler_id]
               [s/kuenstler-slug :kuenstler_slug]
               [s/kuenstler-bild :kuenstler_bild]
               (db/localized s/kuenstler-name locale)
               [s/programme-id :programm_id]
               [s/programme-slug :programm_slug]
               [s/programme-bild :programm_bild]
               (db/localized s/programme-titel locale)]
   :from      [:termine]
   :left-join [:orte                [:= s/termine-ort s/orte-id]
               [s/kuenstler_t :kuenstler] [:= s/termine-kuenstler s/kuenstler-id]
               [s/programme_t :programme] [:= s/termine-programm s/programme-id]]
   :where     [:= s/termine-id termin-id]})

(defn- massage-termin [locale row]
  (let [{:keys [id datum uhrzeit programm_id programm_slug
                kuenstler_id kuenstler_slug
                stadt ort_name strasse postleitzahl
                instrumente sonderkuenstler sonderprogramm
                eintrittspreis waehrung veranstalterlink abgesagt]} row
        kuenstler_name (:name row)
        programm_titel (:titel row)
        datzeit (when uhrzeit
                  (td/unify-date-and-time (date->str datum) uhrzeit))]
    (merge row
           {:datzeit          (or datzeit datum)
            :jahr             (some-> (date->str datum) (str/split "-") first)
            :detail-id        (str "tdt" id)
            :weekday          (loc/by-locale locale (td/weekday-strings datum 1))
            :datum-formatted  (loc/by-locale locale (td/format-dates-wordy datum 1))
            :zeit             (when datzeit
                                (loc/by-locale locale (td/format-times datzeit)))
            :stadt            stadt
            :ort-name         ort_name
            :strasse          strasse
            :postleitzahl     postleitzahl
            :programmtitel    (or programm_titel
                                  (when (string? sonderprogramm)
                                    (first (str/split sonderprogramm #"\n"))))
            :programm-id      programm_id
            :programm-slug    (when programm_slug (putils/slugify programm_slug))
            :kuenstler-id     kuenstler_id
            :kuenstler-name   (or sonderkuenstler kuenstler_name)
            :kuenstler-slug   (when kuenstler_slug (putils/slugify kuenstler_slug))
            :instrumente-csv  instrumente
            :eintrittspreis   eintrittspreis
            :waehrung         waehrung
            :veranstalterlink veranstalterlink
            :abgesagt         abgesagt})))

;; ##################
;; #### Rendering ###
;; ##################

(defn- google-calendar-link [{:keys [locale] :as req}
                             {:keys [datzeit kuenstler-name strasse postleitzahl stadt
                                     ort-name programmtitel id sonderprogramm]}]
  (let [event (td/ptermin
               datzeit 120
               (str/join " " (remove str/blank?
                                     [(snip/konzert locale) "–" kuenstler-name]))
               {:location    (str/join ", " (remove str/blank?
                                                    [ort-name strasse
                                                     (str/join " " (remove str/blank?
                                                                           [postleitzahl stadt]))]))
                :description (str/join "\n"
                                       (remove str/blank?
                                               [(or programmtitel sonderprogramm)
                                                (str (routing/make-path-absolute
                                                      req (routing/reverse-match req :termine {}))
                                                     "#termin-" id)]))})]
    [:a.standardlink.termin__gcal-link
     {:target "_blank"
      :rel    "noopener noreferrer"
      :title  "Google Calendar"
      :href   (td/google-calendar event)}
     "+Google Cal"]))

(defn- google-maps-url [& parts]
  (->> parts
       (remove str/blank?)
       (str/join " ")
       js/encodeURIComponent
       (str "https://www.google.com/maps/search/?api=1&query=")))

(defn- google-maps-link [{:keys [strasse stadt postleitzahl]}]
  [:a.detailzelle__mapslink
   {:target "_blank"
    :rel    "noopener noreferrer"
    :title  "Google Maps"
    :href   (google-maps-url strasse postleitzahl stadt)}
   [:img.mapicon {:src "/from_reservoir/mapicon.svg" :height "32" :alt "Map"}]])

(defn- ical-link [req termin-id]
  [:a.standardlink.termin__ical-link
   {:target "_blank"
    :rel    "noopener noreferrer"
    :title  "iCal"
    :href   (routing/reverse-match req :api-ical {} {:termin-id termin-id})}
   [:img {:src    "/from_reservoir/ical.svg"
          :height "32"
          :alt    "iCal"}]])

(defn- abonnement [req]
  (let [locale  (:locale req)
        ics-url (routing/make-path-absolute
                 req (routing/reverse-match req :api-ical {}))
        href    (str "https://calendar.google.com/calendar/u/0/r?cid=" ics-url)]
    [:div.abonnement
     [:div.abonnement__text
      (snip/kein-konzert-mehr-verpassen locale) " "
      [:a.standardlink {:href href :target "_blank" :rel "noopener noreferrer"}
       (snip/subscribe-to-ical locale)]]
     [:a.abonnement__icon {:href href :target "_blank" :rel "noopener noreferrer"}
      [:img {:src "/from_reservoir/ical.svg" :height "40"}]]]))

(defn- termin-row [{:keys [locale] :as req} t-row]
  (let [{:keys [id uhrzeit datzeit datum sonderkuenstler abgesagt
                detail-id weekday datum-formatted kuenstler-name
                kuenstler-slug kuenstler-id instrumente-csv
                ort-name stadt strasse postleitzahl
                programmtitel programm-id programm-slug zeit
                eintrittspreis waehrung veranstalterlink
                sonderprogramm]} t-row
        past?     (try (t/after? (t/now) (or datzeit datum))
                       (catch :default _ false))
        toggle-js (str "var e=document.getElementById('" detail-id
                       "'); e.classList.toggle('termin__detailzeile--revealed');")]
    [:div.termin
     {:class (str "termin"
                  (cond past?    " termin--vergangen"
                        abgesagt " termin--faelltaus"
                        :else    ""))
      :id    (str "termin-" id)}
     [:div.termin__hauptzeile
      {:onClick toggle-js}
      [:div.hauptzelle.hauptzelle--links
       [:a.standardlink
        {:href (routing/reverse-match req :termin-einzelansicht {:termin-id id})}
        weekday ", " datum-formatted
        (when uhrzeit (list [:br] zeit))]]
      [:div.hauptzelle.hauptzelle--mitte
       (str/join ", " (remove str/blank? [stadt ort-name]))
       (when abgesagt
         [:span.ausfallhinweis
          (str " " (snip/abgesagt locale) "!")])]
      [:div.hauptzelle.hauptzelle--rechts
       (cond
         sonderkuenstler sonderkuenstler
         kuenstler-id    [:a.standardlink
                          {:href (routing/reverse-match req :kuenstler-single
                                                        {:kuenstler-id   kuenstler-id
                                                         :kuenstler-slug (or kuenstler-slug
                                                                             (str kuenstler-id))})}
                          kuenstler-name])]]
     [:div.termin__detailzeile
      {:id detail-id}
      [:div.hauptzelle.hauptzelle--links
       (when-not past?
         (list (ical-link req id) [:br]
               (google-calendar-link req t-row)))]
      [:div.detailzelle.hauptzelle--mitte
       (when strasse
         [:div.detailzelle__adresse
          [:div.detailzelle__adressfeld
           strasse [:br] postleitzahl " " stadt]
          (google-maps-link t-row)])
       (cond
         (not (str/blank? sonderprogramm))
         [:div sonderprogramm]
         programm-id
         [:div (snip/programm locale) ":" [:br]
          [:a.standardlink
           {:href (routing/reverse-match req :programm
                                         {:programm-id   programm-id
                                          :programm-slug (or programm-slug
                                                             (str programm-id))})}
           programmtitel]])]
      [:div.detailzelle.detailzelle--rechts
       (for [src (cmn/instrumente instrumente-csv)]
         [:img.instrument.instrument--termine {:src src}])
       [:br] [:br]
       (when (and eintrittspreis (not= eintrittspreis "0"))
         (list (snip/eintritt locale) ": " eintrittspreis " " waehrung))
       (when veranstalterlink
         (list [:br]
               [:a.standardlink {:href veranstalterlink}
                (snip/veranstalterlink locale)]))]]]))

(defn- terminabschnitt [req termine]
  (let [jahr (:jahr (first termine))]
    (into [:div.terminliste__terminabschnitt
           [:div.jahreszeile jahr]]
          (map (partial termin-row req) termine))))

(defn- liste-full [req termine archiv-flag?]
  (let [aktueller (first
                   (filter
                    (fn [tt]
                      (try (t/after? (or (:datzeit tt) (:datum tt)) (t/now))
                           (catch :default _ false)))
                    termine))]
    (list
     [:div.terminliste
      (when-not archiv-flag?
        [:a.kaufbutton
         {:href (str (routing/reverse-match req :termine {}) "?archive=1")}
         [:div.termin__hauptzeile (snip/aeltere-termine (:locale req))]])
      (map (partial terminabschnitt req)
           (partition-by :jahr termine))
      (when-not archiv-flag? (abonnement req))]
     (when (and (not archiv-flag?) aktueller)
       [:script {:type "text/javascript"}
        (str "location.href = '#termin-" (:id aktueller) "'")]))))

;; ##################
;; #### Handler #####
;; ##################

(defhandler handler [req]
  (p/let [locale        (:locale req)
          archiv-flag?  (archiv? req)
          starting-year (if archiv-flag? 2019 (td/currentyear))
          rows          (db/query (termine-list-query locale starting-year))
          rendered      (templates/head-and-foot-dynamic
                         req {:titel        (snip/termine locale)
                              :beschreibung (snip/meta-termine locale)}
                         (cmn/mainpanel
                          (liste-full req
                                      (map (partial massage-termin locale) rows)
                                      archiv-flag?)))]
    (ph/html->response rendered)))

;; #########################
;; ###### Einzelansicht ####
;; #########################

(defn- format-einzel [req row]
  (let [{:keys [programmtitel sonderprogramm stadt strasse postleitzahl
                ort-name uhrzeit zeit datum-formatted jahr
                kuenstler-id kuenstler-name kuenstler-slug
                programm-id programm-slug
                programm_bild kuenstler_bild]} row
        bild (or programm_bild kuenstler_bild)]
    [:div.sheet
     [:div.sheet__header (or programmtitel sonderprogramm)]
     [:div.sheet__body
      [:div.termin-ez__columns
       [:div.termin-ez__columns__left
        [:div.termin-ez__kuenstler kuenstler-name]
        [:div.termin-ez__datum datum-formatted " " jahr [:br]
         (when uhrzeit zeit)]
        [:div.termin-ez__adresse ort-name [:br] [:br]
         strasse [:br] postleitzahl " " stadt
         (when strasse
           (list " " (google-maps-link row)))]]
       (when bild
         [:div.termin-ez__columns__right
          [:img {:width "100%"
                 :src   (d/image-by-preset "w1200" bild)}]])]
      (when programm-id
        [:div.termin-ez__mehr-info
         [:a.standardlink
          {:href (routing/reverse-match req :programm
                                        {:programm-id   programm-id
                                         :programm-slug (or programm-slug
                                                            (str programm-id))})}
          "→ "
          (case (:locale req)
            :en "More on program "
            :uk "Більше про програму "
            "Mehr zum Programm ")
          programmtitel]])
      (when kuenstler-id
        [:div.termin-ez__mehr-info
         [:a.standardlink
          {:href (routing/reverse-match req :kuenstler-single
                                        {:kuenstler-id   kuenstler-id
                                         :kuenstler-slug (or kuenstler-slug
                                                             (str kuenstler-id))})}
          "→ "
          (case (:locale req)
            :en "More on " :uk "Більше про "
            "Mehr zu ")
          kuenstler-name]])]]))

(defhandler handler-einzel [req]
  (p/let [locale    (:locale req)
          termin-id (routing/path-param req :termin-id)
          rows      (db/query (single-termin-query locale termin-id))
          row       (first rows)
          massaged  (when row (massage-termin locale row))
          rendered  (templates/head-and-foot-dynamic
                     req {:titel       (snip/termine locale)
                          :breadcrumbs [(snip/termine locale) [:termine {}]
                                        (str termin-id) (:url req)]}
                     [:div.mainframe (when massaged (format-einzel req massaged))])]
    (ph/html->response rendered)))
