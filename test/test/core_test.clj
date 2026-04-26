(ns test.core-test
  (:require
   [clojure.test :refer [use-fixtures run-tests]]
   [cuic.chrome :as chrome]
   [cuic.core :as c]
   [cuic.test :refer [deftest* is*]]
   [test.browser :as browser]
   [test.db :as db]
   [test.routes :as routes]
   [test.server :as server]))

(use-fixtures
  :once
  db/fixture
  server/fixture
  (browser/fixture {:headless false}))

(defn book [wait-before-click]
  (c/goto (routes/url :concert {:locale :de :concert-id 16} :fragment "book"))

  (c/sleep 100)

  (let [input (c/find "input[data-test-id=name-input]")]
    (c/fill input "Oliver"))

  (c/sleep 100)

  (let [input (c/find "input[data-test-id=email-input]")]
    (c/fill input "technical@olivermotz.com"))

  (let [input (c/find "select[data-test-id=concert-input]")]
    (c/select input "5"))

  (let [input (c/find "input[data-test-id=privacy-input]")]
    (c/click input))

  (c/sleep wait-before-click)

  (let [input (c/find "#booking-button")]
    (c/click input)))

#_(deftest* booking-success
    (book 4000)
    (is*
     (=
      (:data-test-booking-status
       (c/attributes (c/find "div[data-test-id=booking-message]")))
      (str :success))))

(deftest* booking-trigger-spam-time
  (book 1000)
  (print (:data-test-booking-status
          (c/attributes (c/find "div[data-test-id=booking-message]")))
         (is*
          (=
           (:data-test-booking-status
            (c/attributes (c/find "div[data-test-id=booking-message]")))
           (str :too-fast)))))

(deftest* booking-success
  (book 4000)
  (is*
   (= (str :success)
      (:data-test-booking-status
       (c/attributes (c/find {:by "div[data-test-id=booking-message]"
                              :timeout 8000}))))))

(comment

  (c/set-browser! (chrome/launch
                   {:headless false}
                   (System/getenv "CHROME_BINARY_PATH")))

  (is*
   (= (str :success)
      (:data-test-booking-status
       (c/attributes (c/find "div[data-test-id=booking-message]")))))

  (run-tests)

  (book 4000)

  "hh")
