(ns re-frame.http-synthetic-4xx-test
  "Per rf2-wi8g9r — the `:else` synthetic-4xx arm of `handle-response!`
  (transport.cljc §handle-response!) end-to-end.

  THE ARM: a non-2xx status that is NOT 4xx/5xx — a 1xx, or a 3xx the
  runtime did not follow — falls through the 4xx / 5xx / 2xx cascade to the
  `:else` branch, which classifies it as `:rf.http/http-4xx` carrying the raw
  body-text and (per rf2-ee38b.7) routes through `maybe-retry!` (NOT
  `finalise-failure!`) so a caller with `:retry {:on #{:rf.http/http-4xx}}`
  retries it consistently with a real 4xx.

  REACHABILITY: `:redirect :error` (or `:manual`) selects the JDK
  `HttpClient$Redirect/NEVER` client (rf2-ee38b.7 — `transport-jvm/redirect->
  policy`), so a 302 response surfaces UNFOLLOWED at status 302 through the
  classification cascade rather than being auto-followed. The redirect-policy
  MAPPING is unit-tested in `http_transport_security_test`, but no test drove a
  real 3xx response through the cascade — so a refactor reverting the `:else`
  arm to `finalise-failure!`, or changing the synthesised kind, would pass every
  existing test. These end-to-end tests pin it: a real 302-returning server, a
  `:redirect :error` request, and assertions on (a) the synthesised
  `:rf.http/http-4xx` kind, (b) the raw 302 body at `:body`, and (c) the
  `maybe-retry!` routing (hit count > 1 under a `:rf.http/http-4xx` retry).

  Strategy mirrors `http_backoff_cancellation_test`: a tiny in-process
  `com.sun.net.httpserver.HttpServer` that always returns 302 and COUNTS its
  hits.

  Spec references:
   - Spec 014 §Classification order (status classification before decode)
   - Spec 014 §Failure categories (`:rf.http/http-4xx`)
   - Spec 014 §Retry and backoff / §Request envelope (`:redirect`)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as registry]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

;; ---- per-test reset (mirrors http_backoff_cancellation_test) ---------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  (machines/reset-timers!)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- hit-counting always-302 server ----------------------------------------

(def ^:private redirect-body
  "moved permanently-ish — body the JDK NEVER-policy client surfaces raw")

(defn- start-counting-302-server!
  "Start an HttpServer that always returns a 302 (with a Location header the
  NEVER-policy client will NOT follow) carrying `redirect-body`, incrementing
  `hits` on every request. Returns `{:server :port :hits}`."
  []
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (.incrementAndGet hits)
                        (let [^HttpExchange ex ex
                              bs (.getBytes redirect-body "UTF-8")]
                          (try
                            ;; A Location header a following client WOULD chase;
                            ;; the :redirect :error client (NEVER) does not, so
                            ;; the 302 surfaces unfollowed through the cascade.
                            (.add (.getResponseHeaders ex) "Location" "/elsewhere")
                            (.sendResponseHeaders ex 302 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))
     :hits   hits}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-synthetic-4xx condition"})
   true))

;; ---- (1) synthetic-4xx classification + raw body + maybe-retry! routing -----

(deftest unfollowed-3xx-classifies-synthetic-4xx-and-retries
  (testing "rf2-wi8g9r — an UNFOLLOWED 302 (:redirect :error → JDK NEVER) hits
  the `:else` arm: it classifies as :rf.http/http-4xx carrying the raw 302
  body, and — being a :rf.http/http-4xx — is RETRIED under a
  `:retry {:on #{:rf.http/http-4xx}}` config (routed through maybe-retry!,
  NOT finalise-failure!). The server is hit TWICE (attempt + one retry)."
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-302-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url      (str "http://127.0.0.1:" (:port srv) "/")
                                 ;; NEVER-policy client — the 302 is NOT followed.
                                 :redirect :error}
                    :decode     :json
                    ;; max-attempts 2 → exactly one retry, then exhaust.
                    :retry      {:on           #{:rf.http/http-4xx}
                                 :max-attempts 2
                                 :backoff      {:base-ms 100 :factor 1 :max-ms 100}}
                    :request-id :synth
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue])
        ;; The synthetic-4xx retried once → server hit exactly twice.
        (await-condition! #(= 2 (.get hits)))
        (is (= 2 (.get hits))
            "the synthetic http-4xx routed through maybe-retry! and retried once (the :else arm is NOT a direct finalise-failure!)")
        (await-condition! #(seq @replies))
        (is (= 1 (count @replies))
            "exactly one final reply after the retry exhausts")
        (let [reply (first @replies)]
          (is (= :error (:status reply))
              "the exhausted synthetic-4xx lowers to a :status :error reply")
          (is (= :rf.http/http-4xx (get-in reply [:error :kind]))
              "the unfollowed 3xx classifies as the synthetic :rf.http/http-4xx kind")
          (is (= 302 (get-in reply [:error :status]))
              "the RAW 3xx status rides on the failure map")
          (is (= redirect-body (get-in reply [:error :body]))
              "the RAW 302 body-text rides at :body (decode never runs on a non-2xx)"))
        (is (empty? (registry/in-flight-snapshot))
            "the registry is clean after the synthetic-4xx exhausts its retries")
        (finally
          (stop-server! srv))))))

;; ---- (2) GUARD: a 5xx-only retry does NOT catch the synthetic-4xx ----------

(deftest synthetic-4xx-not-retried-under-5xx-only-retry
  (testing "rf2-wi8g9r guard — the synthesised kind is genuinely
  :rf.http/http-4xx (NOT :rf.http/http-5xx): a `:retry {:on #{:rf.http/http-5xx}}`
  config does NOT match it, so the unfollowed 302 fails on the FIRST attempt
  (hit count exactly 1) and finalises with the synthetic-4xx failure. This pins
  the kind from the opposite direction — a mislabel as 5xx would spuriously
  retry here."
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-302-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url      (str "http://127.0.0.1:" (:port srv) "/")
                                 :redirect :error}
                    :decode     :json
                    :retry      {:on           #{:rf.http/http-5xx}
                                 :max-attempts 3
                                 :backoff      {:base-ms 100 :factor 1 :max-ms 100}}
                    :request-id :synth-guard
                    :on-failure [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :rf.http/http-4xx (get-in reply [:error :kind]))
              "classified :rf.http/http-4xx — a 5xx-only retry does not match it")
          (is (= 302 (get-in reply [:error :status]))))
        (is (= 1 (.get hits))
            "the synthetic-4xx is NOT in the 5xx retry set → exactly one attempt, no retry")
        (is (= 1 (count @replies)))
        (is (empty? (registry/in-flight-snapshot)))
        (finally
          (stop-server! srv))))))
