(ns re-frame.ssr-teardown-corner-test
  "Corner-matrix coverage for per-request frame teardown — composition,
  idempotence, cross-frame isolation, missing-hook tolerance. Per
  rf2-u91hb (audit follow-on from the rare-corner-cases sweep).

  ## Why this lives next to `ssr_teardown_load_test.clj`

  `ssr_teardown_load_test` proves the teardown contract HOLDS UNDER
  LOAD (2000 requests, every side-channel returns to baseline). It
  drives the documented per-request flow N times and asserts the
  aggregate invariant. What it does NOT do is exercise each named
  invariant on its own (`re-frame.ssr.request/on-frame-destroyed!`
  docstring claims four properties: drops pending-error-traces, drops
  request-slots, drops response-slots, invokes head-cleanup hook;
  idempotent; tolerates missing head hook). The load test will catch
  a regression in the aggregate, but the per-invariant tests are the
  triage hooks — they name the failing dimension at first sight, not
  via a heap-delta detective story.

  ## Scope

  Composition: a single destroy of a fully-populated frame releases
  all four side-channels in one call (test 1).
  Idempotence: a second destroy of the same frame-id is a no-op (test 2).
  Cross-frame isolation: destroying frame A leaves frame B's slots
  intact (test 3).
  Missing-hook tolerance: the head-cleanup hook can be absent (e.g. a
  bundle that doesn't pull in re-frame.ssr.head); destroy still completes
  (test 4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.head :as head]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; Composition — one destroy clears all four side-channels
;; ===========================================================================

(deftest on-frame-destroyed-clears-all-four-side-channels-in-one-call
  (testing "rf2-u91hb: a single destroy call against a frame whose
            request-slot, response-slot, pending-error-trace buffer,
            AND head-snapshot are ALL populated MUST clear every one
            of them. The pre-rf2-fcj33 / rf2-jbcmt teardown only
            cleared a subset (or none); the post-fix contract pins
            the all-four-in-one-call composition that the four
            individual tests don't exercise together."
    (rf/reg-head :head/composition-test (fn [_ _] {:title "composition"}))
    (let [fid :rf.test/composition-target]
      ;; Populate all four side-channel slots for fid.
      (request/set-request! fid {:uri "/comp" :request-method :get})
      (response/swap-response! fid (fn [r] (assoc r :status 200)))
      ;; Plant a synthetic pending error trace.
      (swap! error-listener/pending-error-traces
             update fid (fnil conj [])
             {:op-type :error :operation :rf.error/composition-probe})
      ;; Make-frame + render-head to populate head-snapshot.
      (rf/reg-frame fid
        {:doc       "composition test"
         :platform  :server
         :on-create [:rf.test.composition/noop]})
      (rf/reg-event-db :rf.test.composition/noop (fn [db _] db))
      (rf/render-head :head/composition-test {:frame fid})

      ;; All four populated — sanity.
      (is (some? (request/get-request fid))
          "request-slot populated (sanity)")
      (is (contains? @response/response-slots fid)
          "response-slot populated (sanity)")
      (is (contains? @error-listener/pending-error-traces fid)
          "pending-error-traces populated (sanity)")
      (is (seq (head/head-snapshot fid))
          "head-snapshot populated (sanity)")

      ;; Drive the destroy hook directly — this is the single call the
      ;; spec contract pins as the load-bearing release point.
      (request/on-frame-destroyed! fid)

      (is (nil? (request/get-request fid))
          "request-slot released by on-frame-destroyed!")
      (is (not (contains? @response/response-slots fid))
          "response-slot released by on-frame-destroyed!")
      (is (not (contains? @error-listener/pending-error-traces fid))
          "pending-error-traces released by on-frame-destroyed!")
      (is (= {} (head/head-snapshot fid))
          "head-snapshot released by on-frame-destroyed! (via the
           :ssr/head-on-frame-destroyed late-bind hook chain)"))))

;; ===========================================================================
;; Idempotence — second destroy is a no-op
;; ===========================================================================

(deftest on-frame-destroyed-is-idempotent
  (testing "rf2-u91hb: the on-frame-destroyed! docstring promises
            idempotence ('a second call against the same frame-id sees
            the atoms already cleared and does nothing'). Pin that
            promise — a host adapter that mistakenly invokes the hook
            twice (e.g. via a defensive try/destroy AND a finally
            destroy) MUST NOT throw or corrupt state."
    (rf/reg-head :head/idempotence (fn [_ _] {:title "idem"}))
    (let [fid :rf.test/idempotence-target]
      (request/set-request! fid {:uri "/i" :request-method :get})
      (response/swap-response! fid (fn [r] (assoc r :status 201)))
      (rf/reg-frame fid
        {:doc       "idempotence test"
         :platform  :server
         :on-create [:rf.test.composition/noop]})
      (rf/reg-event-db :rf.test.composition/noop (fn [db _] db))
      (rf/render-head :head/idempotence {:frame fid})

      ;; First destroy releases everything.
      (request/on-frame-destroyed! fid)
      (is (nil? (request/get-request fid)))
      (is (= {} (head/head-snapshot fid)))

      ;; Second destroy MUST be a no-op — no throw, no spurious state
      ;; change, no extra trace emission.
      (is (nil? (request/on-frame-destroyed! fid))
          "second destroy returns nil cleanly")
      (is (nil? (request/get-request fid))
          "request-slot still empty after second destroy")
      (is (= {} (head/head-snapshot fid))
          "head-snapshot still empty after second destroy"))))

;; ===========================================================================
;; Cross-frame isolation — destroying A leaves B intact
;; ===========================================================================

(deftest on-frame-destroyed-isolates-across-frames
  (testing "rf2-u91hb: destroying frame A MUST NOT touch frame B's
            slots. Per Spec 011 §Request/Response storage substrate —
            'two simultaneous per-request frames carry independent
            slots that cannot bleed into each other'. The four side-
            channel atoms are keyed by frame-id; the contract is that
            the destroy hook touches ONLY the keyed entry, not any
            other frame's entries."
    (rf/reg-head :head/iso (fn [_ _] {:title "iso"}))
    (let [fid-a :rf.test/iso-frame-a
          fid-b :rf.test/iso-frame-b]
      ;; Populate both frames identically.
      (doseq [fid [fid-a fid-b]]
        (request/set-request! fid {:uri (str "/" (name fid))
                                   :request-method :get})
        (response/swap-response! fid (fn [r] (assoc r :status 200)))
        (swap! error-listener/pending-error-traces
               update fid (fnil conj [])
               {:op-type :error :operation :rf.error/iso-probe})
        (rf/reg-frame fid
          {:doc       (str "iso " (name fid))
           :platform  :server
           :on-create [:rf.test.composition/noop]})
        (rf/reg-event-db :rf.test.composition/noop (fn [db _] db))
        (rf/render-head :head/iso {:frame fid}))

      ;; Destroy ONLY fid-a.
      (request/on-frame-destroyed! fid-a)

      ;; fid-a cleared.
      (is (nil? (request/get-request fid-a)))
      (is (not (contains? @response/response-slots fid-a)))
      (is (not (contains? @error-listener/pending-error-traces fid-a)))
      (is (= {} (head/head-snapshot fid-a)))

      ;; fid-b untouched.
      (is (some? (request/get-request fid-b))
          "fid-b's request-slot survived fid-a's destroy")
      (is (contains? @response/response-slots fid-b)
          "fid-b's response-slot survived fid-a's destroy")
      (is (contains? @error-listener/pending-error-traces fid-b)
          "fid-b's pending-error-traces survived fid-a's destroy")
      (is (seq (head/head-snapshot fid-b))
          "fid-b's head-snapshot survived fid-a's destroy"))))

;; ===========================================================================
;; Missing-hook tolerance — the head-cleanup hook can be absent
;; ===========================================================================

(deftest on-frame-destroyed-tolerates-missing-head-hook
  (testing "rf2-u91hb: the :ssr/head-on-frame-destroyed late-bind hook
            is OPTIONAL — re-frame.ssr.head publishes it at ns-load,
            but a deployment that wires its own head substitute (or
            wires NO head at all) leaves the hook unregistered.
            on-frame-destroyed! MUST tolerate the absence — late-bind
            lookup returns nil and the destroy completes."
    (let [fid :rf.test/no-head-hook
          prior-hook (late-bind/get-fn :ssr/head-on-frame-destroyed)]
      ;; Populate the OTHER three slots (no head — that's the point).
      (request/set-request! fid {:uri "/no-head" :request-method :get})
      (response/swap-response! fid (fn [r] (assoc r :status 200)))
      (swap! error-listener/pending-error-traces
             update fid (fnil conj [])
             {:op-type :error :operation :rf.error/no-head-probe})

      (try
        ;; Remove the head-cleanup hook by dropping it from the
        ;; late-bind table — same shape as a deployment that never
        ;; loaded re-frame.ssr.head.
        (swap! late-bind/hooks dissoc :ssr/head-on-frame-destroyed)
        (is (nil? (late-bind/get-fn :ssr/head-on-frame-destroyed))
            "the hook is now absent (sanity)")

        ;; Drive destroy. MUST NOT throw on the missing-hook path.
        (is (nil? (request/on-frame-destroyed! fid))
            "on-frame-destroyed! completes without throwing — the
             when-let on the absent hook short-circuits to nil")

        (is (nil? (request/get-request fid))
            "the other three side-channels were still cleared
             (request-slot)")
        (is (not (contains? @response/response-slots fid))
            "response-slot cleared")
        (is (not (contains? @error-listener/pending-error-traces fid))
            "pending-error-traces cleared")

        (finally
          ;; Restore the prior hook so subsequent tests see normal
          ;; behaviour.
          (when prior-hook
            (late-bind/set-fn! :ssr/head-on-frame-destroyed prior-hook)))))))
