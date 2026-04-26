(ns processing.core
  (:require [psite-tooling.reservoir :as reservoir]))

(def basecolor "hsl(211, 100%, 36%)")
(def basecolor-lighter "#ffe08a")

(defn ^:export reservoir [{:keys [from-scratch?]}]
  (reservoir/thumbnail-with-imagemagick
   {:source-dir "reservoir"
    :target-dir "public/compiled/from_reservoir"
    :only-newer? (not from-scratch?)})
  (reservoir/svgs-with-replacements
   {:source "reservoir"
    :target "public/compiled/from_reservoir/"
    :replacements
    {#"#000000" basecolor
     #"#999999" basecolor-lighter
     #"#ffffff" basecolor-lighter
     #"#FFFFFF" basecolor-lighter
     #"#000" basecolor
     #"#999" basecolor-lighter
     #"#fff" basecolor-lighter
     #"#FFF" basecolor-lighter}}))
