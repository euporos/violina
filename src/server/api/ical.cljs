(ns api.ical
  (:require ["ical-generator" :as icalg :refer [ICalCalendar]]
            [cljs-time.coerce :as t.coerce]
            [cljs-time.core :as t]
            [cljs-time.format :as t.format]
            [clojure.string :as str]
            [db.schema :as s]
            [db.setup :as db]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-datetime.core :as td]
            [psite-utils.core :as putils]))

(defn- date->str [d]
  (when d (t.format/unparse (t.format/formatters :date) d)))

(defn- ical-query
  "Upcoming concerts, optionally filtered to a single concert or musician.
   Mirrors the SELECT shape of seiten.termine/termine-list-query plus the
   stuecke description."
  [locale {:keys [termin-id kuenstler-id]}]
  (let [today (t.format/unparse (t.format/formatters :date) (t/now))
        base-where [:>= s/termine-datum today]]
    {:select    [s/termine-id s/termine-datum s/termine-uhrzeit s/termine-abgesagt
                 s/termine-sonderkuenstler s/termine-sonderprogramm
                 [s/orte-stadt :stadt] [s/orte-name :ort_name]
                 [s/orte-strasse :strasse] [s/orte-postleitzahl :postleitzahl]
                 [s/kuenstler-id :kuenstler_id]
                 [s/kuenstler-slug :kuenstler_slug]
                 (db/localized s/kuenstler-name locale)
                 (db/localized s/programme-titel locale)
                 (db/localized s/programme-stuecke locale)]
     :from      [:termine]
     :left-join [:orte                      [:= s/termine-ort s/orte-id]
                 [s/kuenstler_t :kuenstler] [:= s/termine-kuenstler s/kuenstler-id]
                 [s/programme_t :programme] [:= s/termine-programm s/programme-id]]
     :where     (cond
                  termin-id    [:and base-where [:= s/termine-id termin-id]]
                  kuenstler-id [:and base-where [:= s/termine-kuenstler kuenstler-id]]
                  :else        base-where)
     :order-by  [s/termine-datum s/termine-uhrzeit]
     :limit     1000}))

(defn- strip-html [s]
  (when s
    (-> s
        (str/replace #"<[^>]+>" "")
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&ndash;" "–")
        (str/replace #"&mdash;" "—")
        (str/replace #"&eacute;" "é")
        (str/replace #"&[a-zA-Z]+;" "")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

(defn- format-ort [{:keys [stadt ort_name strasse postleitzahl]}]
  (str/join "\n"
            (remove str/blank?
                    [ort_name
                     strasse
                     (str postleitzahl " · " stadt)])))

(defn- termin->event [{:keys [datum uhrzeit abgesagt
                              sonderkuenstler sonderprogramm
                              titel stuecke] :as row}]
  (let [kuenstler-name (:name row)
        start (some-> (td/unify-date-and-time (date->str datum) uhrzeit)
                      t.coerce/to-local-date-time)
        end   (when start (t/plus start (t/hours 2)))]
    (when (and start (not abgesagt))
      {:start       (.-date start)
       :end         (.-date end)
       :summary     (str "Konzert"
                         (when-let [who (or sonderkuenstler kuenstler-name)]
                           (str " – " who)))
       :description (or (strip-html sonderprogramm)
                        (str/join "\n\n" (remove str/blank?
                                                 [titel (strip-html stuecke)])))
       :location    (format-ort row)})))

(defn- calendar-name [rows {:keys [termin-id kuenstler-id]}]
  (let [scoped? (or termin-id kuenstler-id)
        slug    (some :kuenstler_slug rows)
        prefix  (if (and scoped? slug) slug "Violina Petrychenko")
        suffix  (if termin-id " Konzert" " Konzerte")]
    (str prefix suffix)))

(defhandler handler [req]
  (p/let [{:keys [kuenstler-id termin-id]} (get-in req [:parameters :query])
          rows (db/query (ical-query (:locale req)
                                     {:termin-id    termin-id
                                      :kuenstler-id kuenstler-id}))]
    (if (empty? rows)
      (r/not-found)
      (let [name     (calendar-name rows {:termin-id    termin-id
                                          :kuenstler-id kuenstler-id})
            filename (str (putils/make-string-url-compatible name "_") ".ics")
            calendar (new ICalCalendar (clj->js {:name     name
                                                 :timezone "Europe/Berlin"}))]
        (doseq [row rows
                :let [event (termin->event row)]
                :when event]
          (.createEvent calendar (clj->js event)))
        (-> (str calendar)
            r/ok
            (r/header "Content-Disposition" (str "attachment; filename=" filename))
            (r/content-type "text/calendar; charset=utf-8"))))))
