(ns geo
  "App-specific mappings from ISO 3166-1 alpha-2 country codes to user-facing
   defaults: UI locale (consulted only as a tiebreak after Accept-Language)
   and CD shipping region (drives PayPal button + shipping cost display).")

(def country->locale
  "Country code → preferred site locale. Consulted by psite-routing/coerce-locale
   only when no earlier signal (URL, cookie, Accept-Language) yielded a
   supported locale. Anything not listed falls through to :locale-fallback."
  {"UA" :uk
   "IT" :it
   "DE" :de
   "AT" :de
   "CH" :de
   "LI" :de})

(def ^:private eu-eea
  ;; 27 EU + EEA non-EU (IS, LI, NO).
  #{"AT" "BE" "BG" "HR" "CY" "CZ" "DK" "EE" "FI" "FR" "DE" "GR" "HU" "IE"
    "IT" "LV" "LT" "LU" "MT" "NL" "PL" "PT" "RO" "SK" "SI" "ES" "SE"
    "IS" "LI" "NO"})

(defn country->sales-region
  "Country code → CD shipping region (:ua / :eu / :world). Defaults to :eu
   when the code is missing or unmapped; the browser-side fallback in
   app.cds keeps the same default for the no-geo case."
  [cc]
  (cond
    (= cc "UA") :ua
    (eu-eea cc) :eu
    :else       (if cc :world :eu)))
