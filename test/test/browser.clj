(ns test.browser
  (:require [cuic.chrome :as chrome]
            [cuic.core :as c]))

(defn fixture
  ([] (fixture {}))
  ([{:keys [headless options]
     :or   {headless (not= "false" (System/getProperty "cuic.headless"))
            options  {}}}]
   {:pre [(boolean? headless)
          (map? options)]}
   (fn browser-test-fixture* [t]
     (with-open [chrome (chrome/launch
                         (assoc options :headless headless)
                         (System/getenv "CHROME_BINARY_PATH"))]
       (c/set-typing-speed! :very-fast)
       (binding [c/*browser* chrome]
         (t))))))
