(ns comp.localization
  (:require [psite-i18n.core :as i18n]))

(def fallback [:de :en :uk])

(def by-locale (partial i18n/by-locale fallback))
