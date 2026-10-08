(ns re-frame2-pair-mcp.shadow-discovery-test
  "The shadow-cljs HTTP probe, with no socket: `extract-project-home`
  parses a transit-json body, `fetch-project-info` is driven through an
  injected fake `http.request`, and `discover-project-home*` composes the
  two with the fetch stubbed."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.shadow-discovery :as sd]))

;; ===========================================================================
;; extract-project-home
;; ===========================================================================

(deftest extract-project-home-pulls-canonical-payload
  ;; The live `/api/project-info` shape.
  (let [body (str "[\"^ \","
                  "\"~:project-config\",\"C:\\\\Users\\\\me\\\\proj\\\\shadow-cljs.edn\","
                  "\"~:project-home\",\"C:\\\\Users\\\\me\\\\proj\","
                  "\"~:version\",\"3.4.10\"]")]
    (is (= "C:\\Users\\me\\proj" (sd/extract-project-home body)))))

(deftest extract-project-home-returns-nil-for-every-other-body
  ;; Every other shape collapses to nil, never a throw, so the discovery
  ;; cascade falls through.
  (doseq [[body note]
          [[(str "[\"^ \","
                 "\"~:project-config\",\"/x/y/shadow-cljs.edn\","
                 "\"~:version\",\"3.4.10\"]")
            "a payload without :project-home"]
           ["[\"^ \",\"~:project-home\",42]" "a non-string at :project-home"]
           ["{\"project-home\":\"/x\"}"
            "a JSON object isn't the transit-map-as-array shape"]
           ["[1,2,3]" "an array without the \"^ \" sentinel isn't a transit map"]
           ["not-json-at-all" "JSON parse failure"]]]
    (is (nil? (sd/extract-project-home body)) note)))

;; ===========================================================================
;; fetch-project-info, through a fake ClientRequest.
;;
;; Drive the fake req/res settlement FIRST and attach the `done`-calling
;; `.then` last: attaching it before a synchronous settlement trips
;; cljs.test's run-block into "done called more than one time".
;; ===========================================================================

(defn- fake-res
  "A stand-in IncomingMessage: fixed `.statusCode`, `.on` records handlers
  into `handlers`."
  [status handlers]
  #js {:statusCode  status
       :setEncoding (fn [_enc] nil)
       :resume      (fn [] nil)
       :on          (fn [event cb] (swap! handlers assoc event cb) nil)})

(defn- make-fake-transport
  "Returns `[request-fn state]`: `request-fn` records its opts and response
  callback, and returns a fake ClientRequest whose `on` / `setTimeout` /
  `destroy` / `end` calls land in `state`."
  []
  (let [state (atom {:req-handlers {} :res-cb nil :timeout-cb nil
                     :destroyed nil :ended false :opts nil})
        req   #js {:on         (fn [event cb]
                                 (swap! state assoc-in [:req-handlers event] cb) nil)
                   :setTimeout (fn [_ms cb] (swap! state assoc :timeout-cb cb) nil)
                   :destroy    (fn [err] (swap! state assoc :destroyed err) nil)
                   :end        (fn [] (swap! state assoc :ended true) nil)}
        request-fn (fn [opts cb]
                     (swap! state assoc :opts opts :res-cb cb)
                     req)]
    [request-fn state]))

(deftest fetch-project-info-200-multi-chunk-assembles-body
  ;; The request goes to host:port/api/project-info and is sent; a chunked
  ;; 200 body is joined in arrival order.
  (async done
    (let [[request-fn state] (make-fake-transport)
          res-handlers (atom {})
          p (sd/fetch-project-info "10.0.0.5" 9700 request-fn)]
      ((:res-cb @state) (fake-res 200 res-handlers))
      ((get @res-handlers "data") "ab")
      ((get @res-handlers "data") "cd")
      ((get @res-handlers "data") "ef")
      ((get @res-handlers "end"))
      (-> p
          (.then (fn [body]
                   (let [opts (:opts @state)]
                     (is (= ["abcdef" "10.0.0.5" 9700 "/api/project-info" true]
                            [body (.-host opts) (.-port opts) (.-path opts) (:ended @state)])))
                   (done)))))))

(deftest fetch-project-info-rejects-on-every-failure
  ;; Each row drives one failure; the probe must reject with its message.
  (async done
    (let [rows [["a non-200 status" #"HTTP 404"
                 (fn [state] ((:res-cb @state) (fake-res 404 (atom {}))))]
                ["a request error" #"^ECONNREFUSED$"
                 (fn [state] ((get-in @state [:req-handlers "error"]) (js/Error. "ECONNREFUSED")))]
                ["the probe timeout" #"timed out"
                 (fn [state] ((:timeout-cb @state)))]
                ["a mid-body response error" #"^socket hang up$"
                 (fn [state]
                   (let [h (atom {})]
                     ((:res-cb @state) (fake-res 200 h))
                     ((get @h "data") "partial")
                     ((get @h "error") (js/Error. "socket hang up"))))]]
          settled (for [[what re drive!] rows]
                    (let [[request-fn state] (make-fake-transport)
                          p (sd/fetch-project-info "127.0.0.1" 9630 request-fn)]
                      (drive! state)
                      (.then p
                             (fn [_] (is false (str what " must reject")))
                             (fn [err]
                               (is (re-find re (.-message err)) what)
                               (when (= "the probe timeout" what)
                                 (is (some? (:destroyed @state))
                                     "the timeout destroys the request before rejecting"))))))]
      (-> (js/Promise.all (into-array settled))
          (.then (fn [_] (done)))))))

;; ===========================================================================
;; discover-project-home* — fetch + parse, with the fetch stubbed.
;; ===========================================================================

(deftest discover-project-home-fetch-rejection-yields-nil
  ;; It never rejects: the cascade wants a value or nil.
  (async done
    (-> (sd/discover-project-home* "127.0.0.1" 9630
                                   (fn [_host _port] (js/Promise.reject (js/Error. "ECONNREFUSED"))))
        (.then (fn [v]
                 (is (nil? v))
                 (done))))))

(deftest discover-project-home-threads-args-and-resolves-the-path
  (async done
    (let [seen (atom nil)]
      (-> (sd/discover-project-home* "10.0.0.5" 9700
                                     (fn [host port]
                                       (reset! seen [host port])
                                       (js/Promise.resolve "[\"^ \",\"~:project-home\",\"/x\"]")))
          (.then (fn [v]
                   (is (= ["/x" ["10.0.0.5" 9700]] [v @seen]))
                   (done)))))))
