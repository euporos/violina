(ns api.directus-proxy
  "Reverse-proxies /directus/* to a local Directus upstream so that the dev
   environment looks like prod, where edge nginx fronts Directus on the same
   path. Enabled by setting :directus-proxy-upstream.

   Dispatched directly from serving.core/app before the macchiato middleware
   stack runs, so the raw Node request stream is still readable here. That
   matters for non-GET methods (login POST, schema PATCH, asset PUT) whose
   bodies wrap-params/wrap-restful-format would otherwise consume."
  {:clj-kondo/config '{:lint-as {macchiato-async.core/defhandler clojure.core/defn}}}
  (:require [macchiato-async.core :refer-macros [defhandler]]
            [clojure.string :as str]
            [config.env :as env]
            [taoensso.timbre :as log]))

(def ^:private hop-by-hop
  #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
    "te" "trailers" "transfer-encoding" "upgrade" "content-length"})

(defn- forward-req-headers [headers]
  (clj->js
   (into {} (remove (fn [[k _]] (hop-by-hop (str/lower-case (name k)))))
         headers)))

(defn- response-headers [resp-headers]
  (let [out (transient {})]
    (.forEach resp-headers
              (fn [v k]
                (when-not (hop-by-hop (str/lower-case k))
                  (assoc! out k v))))
    (let [m (persistent! out)
          set-cookies (when (.-getSetCookie resp-headers)
                        (vec (.getSetCookie resp-headers)))]
      (cond-> m
        (seq set-cookies) (assoc "set-cookie" set-cookies)))))

(defn- read-body
  "Buffer the Node IncomingMessage stream into a single Buffer."
  [stream]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []]
       (.on stream "data" (fn [chunk] (.push chunks chunk)))
       (.on stream "end"  (fn [] (resolve (.concat js/Buffer chunks))))
       (.on stream "error" reject)))))

(defhandler handler [req]
  ;; Strip the /directus prefix before forwarding: Directus's PUBLIC_URL
  ;; affects emitted URLs (admin SPA <base href>, emails) but NOT where its
  ;; routes are mounted — those always live at /. Prod nginx does the same.
  (let [upstream (str/replace (env/setting :directus-proxy-upstream) #"/+$" "")
        uri      (or (:uri req) "/")
        path     (str/replace-first uri #"^/directus" "")
        path     (if (str/blank? path) "/" path)
        qs       (:query-string req)
        url      (str upstream path (when (seq qs) (str "?" qs)))
        method   (str/upper-case (name (:request-method req)))
        body-method? (not (#{"GET" "HEAD"} method))]
    (-> (if body-method?
          (read-body (:body req))
          (js/Promise.resolve nil))
        (.then (fn [body-buf]
                 (let [opts #js {:method   method
                                 :headers  (forward-req-headers (:headers req))
                                 :redirect "manual"}]
                   (when body-buf
                     (set! (.-body opts) body-buf)
                     (set! (.-duplex opts) "half"))
                   (js/fetch url opts))))
        (.then (fn [resp]
                 (.then (.arrayBuffer resp)
                        (fn [buf]
                          {:status  (.-status resp)
                           :headers (response-headers (.-headers resp))
                           :body    (js/Buffer.from buf)}))))
        (.catch (fn [e]
                  (log/warnf "directus proxy %s %s failed: %s"
                             method url (.-message e))
                  {:status  502
                   :headers {"content-type" "text/plain"}
                   :body    "directus upstream unreachable"})))))
