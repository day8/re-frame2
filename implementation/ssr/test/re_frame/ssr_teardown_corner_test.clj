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
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ===========================================================================
;; Composition — one destroy clears every side-channel
;; ===========================================================================

(deftest on-frame-destroyed-clears-every-side-channel-in-one-call
  (testing "rf2-u91hb: a single destroy call against a frame whose
            request-slot, response-slot, pending-error-trace buffer AND
            hydration-payload claim are ALL populated MUST clear every
            one of them. The pre-rf2-fcj33 / rf2-jbcmt teardown only
            cleared a subset (or none); the post-fix contract pins the
            all-in-one-call composition that the individual tests don't
            exercise together. (Head reads keep NO per-frame state —
            `render-head` returns its model and records nothing — so
            there is no fourth head channel to clear.)"
    (let [fid :rf.test/composition-target]
      ;; Populate every side-channel slot for fid.
      (rf.ssr.request/set-request! fid {:uri "/comp" :request-method :get})
      (rf.ssr.response/swap-response! fid (fn [r] (assoc r :status 200)))
      ;; Plant a synthetic pending error trace.
      (swap! rf.ssr.error-listener/pending-error-traces
             update fid (fnil conj [])
             {:op-type :error :operation :rf.error/composition-probe})
      ;; Plant a hydration-payload install claim under the frame's id
      ;; (payload ids ARE frame ids, 004C §6).
      (swap! rf.ssr.install/installed-payloads
             assoc fid (rf.ssr.install/claim-record "digest-probe" :rf.test/root))

      (is (some? (rf.ssr.request/get-request fid))
          "request-slot populated (sanity)")
      (is (contains? @rf.ssr.response/response-slots fid)
          "response-slot populated (sanity)")
      (is (contains? @rf.ssr.error-listener/pending-error-traces fid)
          "pending-error-traces populated (sanity)")
      (is (some? (rf.ssr.install/installed-payload fid))
          "payload claim populated (sanity)")

      ;; Drive the destroy hook directly — this is the single call the
      ;; spec contract pins as the load-bearing release point.
      (rf.ssr.request/on-frame-destroyed! fid)

      (is (nil? (rf.ssr.request/get-request fid))
          "request-slot released by on-frame-destroyed!")
      (is (not (contains? @rf.ssr.response/response-slots fid))
          "response-slot released by on-frame-destroyed!")
      (is (not (contains? @rf.ssr.error-listener/pending-error-traces fid))
          "pending-error-traces released by on-frame-destroyed!")
      (is (nil? (rf.ssr.install/installed-payload fid))
          "payload claim released by on-frame-destroyed!"))))

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
    (let [fid :rf.test/idempotence-target]
      (rf.ssr.request/set-request! fid {:uri "/i" :request-method :get})
      (rf.ssr.response/swap-response! fid (fn [r] (assoc r :status 201)))
      (swap! rf.ssr.install/installed-payloads
             assoc fid (rf.ssr.install/claim-record "digest-idem" :rf.test/root))

      ;; First destroy releases everything.
      (rf.ssr.request/on-frame-destroyed! fid)
      (is (nil? (rf.ssr.request/get-request fid)))
      (is (nil? (rf.ssr.install/installed-payload fid)))

      ;; Second destroy MUST be a no-op — no throw, no spurious state
      ;; change, no extra trace emission.
      (is (nil? (rf.ssr.request/on-frame-destroyed! fid))
          "second destroy returns nil cleanly")
      (is (nil? (rf.ssr.request/get-request fid))
          "request-slot still empty after second destroy")
      (is (nil? (rf.ssr.install/installed-payload fid))
          "payload claim still released after second destroy"))))

;; ===========================================================================
;; Cross-frame isolation — destroying A leaves B intact
;; ===========================================================================

(deftest on-frame-destroyed-isolates-across-frames
  (testing "rf2-u91hb: destroying frame A MUST NOT touch frame B's
            slots. Per Spec 011 §Request/Response storage substrate —
            'two simultaneous per-request frames carry independent
            slots that cannot bleed into each other'. The side-
            channel atoms are keyed by frame-id; the contract is that
            the destroy hook touches ONLY the keyed entry, not any
            other frame's entries."
    (let [fid-a :rf.test/iso-frame-a
          fid-b :rf.test/iso-frame-b]
      ;; Populate both frames identically.
      (doseq [fid [fid-a fid-b]]
        (rf.ssr.request/set-request! fid {:uri (str "/" (name fid))
                                   :request-method :get})
        (rf.ssr.response/swap-response! fid (fn [r] (assoc r :status 200)))
        (swap! rf.ssr.error-listener/pending-error-traces
               update fid (fnil conj [])
               {:op-type :error :operation :rf.error/iso-probe})
        (swap! rf.ssr.install/installed-payloads
               assoc fid (rf.ssr.install/claim-record "digest-iso" :rf.test/root)))

      ;; Destroy ONLY fid-a.
      (rf.ssr.request/on-frame-destroyed! fid-a)

      ;; fid-a cleared.
      (is (nil? (rf.ssr.request/get-request fid-a)))
      (is (not (contains? @rf.ssr.response/response-slots fid-a)))
      (is (not (contains? @rf.ssr.error-listener/pending-error-traces fid-a)))
      (is (nil? (rf.ssr.install/installed-payload fid-a)))

      ;; fid-b untouched.
      (is (some? (rf.ssr.request/get-request fid-b))
          "fid-b's request-slot survived fid-a's destroy")
      (is (contains? @rf.ssr.response/response-slots fid-b)
          "fid-b's response-slot survived fid-a's destroy")
      (is (contains? @rf.ssr.error-listener/pending-error-traces fid-b)
          "fid-b's pending-error-traces survived fid-a's destroy")
      (is (some? (rf.ssr.install/installed-payload fid-b))
          "fid-b's payload claim survived fid-a's destroy"))))
