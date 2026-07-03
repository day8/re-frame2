(ns re-frame.http-backoff-cancellation-test
  "Per rf2-wj8vv — the retry backoff WINDOW must be cancellable.

  Defect (closed by this test): when `maybe-retry!` schedules a retry it
  cleared the prior attempt's handle from BOTH in-flight indexes BEFORE
  arming the backoff timer, leaving the request invisible to every
  cancellation path for the whole backoff window. A
  `:rf.http/managed-abort`, an `abort-on-actor-destroy` cascade, or a
  same-`:request-id` supersede issued DURING the backoff all resolved to
  `nil` and silently no-op'd — the retry timer kept running and a fresh
  HTTP attempt fired after the backoff elapsed, against a request the
  caller had already cancelled.

  Fix: the request stays registered for the whole backoff window under a
  handle whose `:abort-fn` cancels the pending retry timer and clears the
  registry. The three cancellation paths converge on the one `:abort-fn`
  dispatch, so a sleeping request is cancellable through each with no
  path-specific code.

  Spec references:
   - Spec 014 §Retry and backoff
   - Spec 014 §Aborts (`:request-id` (internal), supersede)
   - Spec 014 §Abort on actor destroy (rf2-wvkn)

  Test strategy: a tiny in-process server that always returns 500 and
  COUNTS its hits. A `:retry {:on #{:rf.http/http-5xx} :max-attempts 5
  :backoff {:base-ms 2000 :factor 1 :max-ms 2000}}` config gives a
  deterministic 2000ms backoff between attempts. After hit #1 lands the
  request is sleeping in the backoff window (confirmed: it is present in
  the in-flight registry and the hit count is exactly 1). The test fires
  the cancel inside that window and asserts — past the backoff deadline —
  that no second hit ever fired and the registry is clean.

  Coverage matrix (each its own deftest):
   1. :rf.http/managed-abort during backoff   → no retry, registry clear
   2. abort-on-actor-destroy during backoff   → no retry, registry clear
   3. supersede (same request-id) during backoff → old retry suppressed
   3b. supersede during backoff (rf2-hbus90)  → the stale-suppressed trace
       carries the SLEEPING retry attempt's work-id (issuance/attempt
       preserved), not a default `1 1`
   4. GUARD: an uncancelled backoff still retries (no regression)"
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
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
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

;; ---- hit-counting always-500 server ---------------------------------------

(defn- start-counting-500-server!
  "Start an HttpServer that always returns 500 and increments `hits` on
  every request it serves. Returns `{:server :port :hits}`."
  []
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (.incrementAndGet hits)
                        (let [^HttpExchange ex ex
                              bs (.getBytes "boom" "UTF-8")]
                          (try
                            (.sendResponseHeaders ex 500 (long (count bs)))
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

;; ---- helpers --------------------------------------------------------------

;; The backoff window. Long enough that the test can deterministically
;; observe the sleeping state and fire a cancel well inside it; short
;; enough to keep the test snappy.
(def ^:private backoff-ms 2000)

(def ^:private retry-config
  {:on           #{:rf.http/http-5xx}
   :max-attempts 5
   :backoff      {:base-ms backoff-ms :factor 1 :max-ms backoff-ms}})

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-backoff-cancellation condition"})
   true))

(defn- await-backoff-sleeping!
  "Wait until attempt #1 has hit the server, then settle briefly so
  `maybe-retry!` has armed the backoff timer. Returns with the request
  squarely inside the (2000ms) backoff window.

  Deliberately gates ONLY on the server hit count — NOT on registry
  presence — because registry presence during backoff is the very
  invariant under test (the defect emptied the registry here). Gating
  on it would let a buggy build hang this helper instead of failing the
  downstream assertions."
  [^AtomicInteger hits]
  (await-condition! #(>= (.get hits) 1))
  ;; Settle: let the 500 response classify + arm the backoff timer. Well
  ;; inside the 2000ms window; the cancel fires immediately after.
  (Thread/sleep 150))

(defn- assert-no-retry-fired!
  "After a cancel inside the backoff window, wait past the backoff
  deadline and assert the retry never fired (hit count frozen at 1)."
  [^AtomicInteger hits]
  ;; Sleep past the full backoff window plus a margin — proving the
  ;; ABSENCE of a retry, which has no positive signal to poll on
  ;; (rf2-fun38 timer-semantics sleep).
  (Thread/sleep (long (+ backoff-ms 600)))
  (is (= 1 (.get hits))
      "the retry MUST NOT fire after a cancel issued during the backoff window — exactly one hit ever reached the server"))

;; ---- (1) :rf.http/managed-abort during backoff ----------------------------

(deftest abort-during-backoff-cancels-pending-retry
  (testing "rf2-wj8vv — :rf.http/managed-abort issued during the retry backoff window cancels the pending retry and clears the registry"
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    :retry      retry-config
                    :request-id :race
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
        ;; Attempt #1 fails 500 → request sleeps in the backoff window.
        (await-backoff-sleeping! hits)
        (is (contains? (registry/in-flight-snapshot) :race)
            "the sleeping request is visible in the in-flight registry during backoff (the defect made it invisible here)")
        ;; Abort squarely inside the 2000ms window.
        (rf/dispatch-sync [:do/abort])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind]))
              "abort during backoff dispatches the canonical :rf.http/aborted reply")
          (is (= :user (get-in reply [:error :reason]))))
        (is (empty? (registry/in-flight-snapshot))
            "the registry is cleared the instant the backoff is cancelled")
        (assert-no-retry-fired! hits)
        (is (= 1 (count @replies))
            "exactly one reply — the cancelled retry never produced a second outcome")
        (finally
          (stop-server! srv))))))

;; ---- (2) abort-on-actor-destroy during backoff -----------------------------

(deftest actor-destroy-during-backoff-cancels-pending-retry
  (testing "rf2-wj8vv — destroying the issuing actor during the retry backoff window cancels the pending retry and clears the actor index"
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        ;; Child actor issues the retrying request on entry.
        (rf/reg-machine :worker/race
          {:initial :idle
           :data    {:port (:port srv)}
           :actions {:fire (fn [{data :data}]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     :json
                                     :retry      retry-config
                                     :request-id :race
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        (rf/reg-machine :sup/race
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/race
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/race [:start]])
        ;; Attempt #1 fails 500 → request sleeps in the backoff window,
        ;; registered under BOTH the request-id and actor-in-flight index.
        (await-backoff-sleeping! hits)
        (is (= 1 (count (registry/actor-in-flight-snapshot)))
            "the sleeping retry is indexed under its issuing actor during backoff (the defect emptied this slot)")
        ;; Destroy the actor squarely inside the backoff window.
        (rf/dispatch-sync [:sup/race [:cancel]])
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :cancelled (:status reply)))
          (is (= :rf.http/aborted (get-in reply [:error :kind])))
          (is (= :actor-destroyed (get-in reply [:error :reason]))
              "actor-destroy during backoff surfaces :reason :actor-destroyed"))
        (is (empty? (registry/actor-in-flight-snapshot))
            "the actor index is clean — no stale entry left for a destroyed actor")
        (is (empty? (registry/in-flight-snapshot)))
        (assert-no-retry-fired! hits)
        (is (= 1 (count @replies)))
        (finally
          (stop-server! srv))))))

;; ---- (3) supersede (same request-id) during backoff ------------------------

(deftest supersede-during-backoff-cancels-old-pending-retry
  (testing "rf2-wj8vv — a fresh request with the same :request-id issued during the old request's backoff supersedes the sleeping retry (the old retry never fires)"
    (let [{:keys [^AtomicInteger hits] :as srv}     (start-counting-500-server!)
          ;; The superseding request targets a DIFFERENT, blocking endpoint
          ;; so it stays in-flight (and is the sole registry occupant) while
          ;; we prove the OLD request's retry was suppressed.
          new-srv (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue-old
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    :retry      retry-config
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        ;; The superseding request: same :request-id, no retry, points at a
        ;; second always-500 server. It runs ONE attempt then finalises.
        (rf/reg-event :issue-new
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port new-srv) "/")}
                    :decode     :json
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue-old])
        ;; Old request's attempt #1 fails 500 → sleeps in the backoff window.
        (await-backoff-sleeping! hits)
        (is (contains? (registry/in-flight-snapshot) :shared))
        ;; Supersede with a fresh same-id request, squarely inside the
        ;; old request's backoff window.
        (rf/dispatch-sync [:issue-new])
        ;; The superseding request fires its own attempt (against new-srv);
        ;; await its failure reply (the old request's reply is suppressed
        ;; per rf2-lxd3 supersede semantics).
        (await-condition! #(seq @replies))
        ;; The OLD server must NEVER receive a second hit — its sleeping
        ;; retry was cancelled by the supersede.
        (assert-no-retry-fired! hits)
        (is (= 1 (.get hits))
            "the OLD request's backoff retry MUST NOT fire after being superseded")
        ;; The superseding request is the only one that touched new-srv.
        (is (= 1 (.get ^AtomicInteger (:hits new-srv)))
            "the superseding request fired exactly one attempt against its own endpoint")
        ;; The suppressed-old reply never reaches the recorder; only the
        ;; new request's failure (a real :rf.http/http-5xx) does.
        (is (every? #(not= :request-id-superseded (get-in % [:error :reason])) @replies)
            "the superseded request's reply is suppressed (rf2-lxd3); no :request-id-superseded reply is dispatched to the user")
        (finally
          (stop-server! srv)
          (stop-server! new-srv))))))

;; ---- (3b) supersede during backoff carries the sleeping attempt's work-id --

(deftest supersede-during-backoff-stale-trace-carries-sleeping-attempt-work-id
  (testing "rf2-hbus90 — superseding a request that is SLEEPING in its retry backoff window records a :rf.http/stale-suppressed trace whose carried work-id is the sleeping retry attempt's work-id (issuance/attempt preserved), distinct from the superseding attempt's"
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          ;; The superseding request targets a different blocking endpoint so
          ;; it stays in-flight while we inspect the emitted stale trace.
          new-srv (start-counting-500-server!)
          replies (atom [])
          traces  (atom [])
          lid     ::supersede-backoff-stale]
      (try
        (trace/register-listener! lid (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue-old
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    :retry      retry-config
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        ;; The superseding request: same :request-id, no retry, fresh endpoint.
        ;; It allocates issuance 2 under :shared, so its work-id is
        ;; [:rf.work/http :shared 2 1].
        (rf/reg-event :issue-new
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port new-srv) "/")}
                    :decode     :json
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue-old])
        ;; Drive the old request THROUGH attempt #1 (500 → backoff) and into
        ;; attempt #2's backoff window: wait for 2 server hits (attempt #1 and
        ;; the retried attempt #2), then settle so attempt #2's 500 has
        ;; classified and `maybe-retry!` has re-armed the backoff timer. The
        ;; sleeping handle now represents attempt #2 (issuance 1, attempt 2).
        (await-condition! #(>= (.get hits) 2))
        (Thread/sleep 150)
        (is (contains? (registry/in-flight-snapshot) :shared)
            "the old request is still registered, sleeping in attempt #2's backoff window")
        ;; Supersede with a fresh same-id request squarely inside that window.
        (rf/dispatch-sync [:issue-new])
        (test-support/poll-until
          #(some (fn [ev] (= :rf.http/stale-suppressed (:operation ev))) @traces)
          {:timeout-ms 5000 :label "supersede-backoff-stale"})
        (let [stale (filter #(= :rf.http/stale-suppressed (:operation %)) @traces)]
          (is (= 1 (count stale)) "exactly one stale-suppression row for the superseded sleeping attempt")
          (let [tags (:tags (first stale))]
            (is (= :stale (:rf.reply/status tags)))
            (is (= :suppressed (:rf.reply/work-status tags)))
            (is (= :http (:work/kind tags)))
            ;; THE BUG (rf2-hbus90): the carried work-id must be the SLEEPING
            ;; retry attempt's work-id — issuance 1, attempt 2. The pre-fix
            ;; backoff handle dropped :issuance / :attempt, so the carried id
            ;; defaulted to [:rf.work/http :shared 1 1] (a phantom attempt #1).
            (is (= [:rf.work/http :shared 1 2] (:work/id (:rf.reply/carried tags)))
                "carried work-id reflects the superseded SLEEPING retry attempt (attempt 2), not a default attempt 1")
            ;; current = the superseding attempt's work-id (issuance 2, attempt 1).
            (is (= [:rf.work/http :shared 2 1] (:work/id (:rf.reply/current tags)))
                "current work-id is the superseding fresh issuance")
            (is (not= (:work/id (:rf.reply/carried tags))
                      (:work/id (:rf.reply/current tags)))
                "carried (superseded) and current (superseding) work-ids are =-distinct")
            ;; The canonical join key reads the carried (superseded) work-id.
            (is (= [:rf.work/http :shared 1 2] (:rf.reply/work-id tags)))))
        ;; The OLD server must NEVER receive a third hit — its sleeping retry
        ;; was cancelled by the supersede (existing rf2-wj8vv behaviour kept).
        (Thread/sleep (long (+ backoff-ms 600)))
        (is (= 2 (.get hits))
            "the OLD request's backoff retry MUST NOT fire after being superseded (exactly attempts #1 + #2 hit the server)")
        ;; The superseded request's app reply is suppressed (rf2-lxd3).
        (is (every? #(not= :request-id-superseded (get-in % [:error :reason])) @replies)
            "no :request-id-superseded reply is dispatched to the user")
        (finally
          (trace/unregister-listener! lid)
          (stop-server! srv)
          (stop-server! new-srv))))))

;; ---- (4) GUARD: an uncancelled backoff still retries -----------------------

(deftest uncancelled-backoff-still-retries
  (testing "rf2-wj8vv guard — a normal (uncancelled) backoff still retries exactly as before; the cancellable-window change does not regress the happy path"
    (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    ;; max-attempts 2 → exactly one retry, then exhaust.
                    :retry      {:on           #{:rf.http/http-5xx}
                                 :max-attempts 2
                                 :backoff      {:base-ms 200 :factor 1 :max-ms 200}}
                    :request-id :happy
                    :on-failure [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue])
        ;; The retry fires after the backoff — the server is hit twice.
        (await-condition! #(= 2 (.get hits)) 5000)
        (is (= 2 (.get hits))
            "the uncancelled backoff retried — the second attempt reached the server")
        ;; Retries exhausted → a single final :rf.http/http-5xx failure.
        (await-condition! #(seq @replies))
        (let [reply (first @replies)]
          (is (= :error (:status reply)))
          (is (= :rf.http/http-5xx (get-in reply [:error :kind]))
              "after the retry exhausts, the final reply carries the real failure category, not :rf.http/aborted"))
        (is (empty? (registry/in-flight-snapshot))
            "the registry is clean after the retry exhausts")
        (is (= 1 (count @replies))
            "exactly one final reply across the retry sequence")
        (finally
          (stop-server! srv))))))
