(ns re-frame.ui.reactive-incarnation-close-race-jvm-test
  "rf2-vxgfnd.94 — the reciprocal of rf2-vxgfnd.88's Failure-2: an OLD incarnation
  A's stale close marker must NOT tear down a LIVE same-id REPLACEMENT incarnation
  B's cell.

  THE WINDOW (JVM-only; on CLJS destroy runs to completion single-threaded before
  any recreate, so B can never be live while A's marker is set — the window is
  unreachable). `frame/destroy-frame!` populates `frame/destroying-frames` at its
  TOP and clears it only in its terminal `finally` — AFTER step-6 `dissoc-frame!`
  frees the id. So there is a JVM window, BETWEEN `dissoc-frame!` and the
  `finally`, where A's marker is still set while the id is already free for a fresh
  incarnation B. A ViewCell commit that ACQUIRES B in that window used to be torn
  down: the .61/.88 revalidation consulted the BARE-id `frame/frame-closing?`,
  which reads true for the reused id regardless of WHICH incarnation is closing
  (`destroying-frames` was a set of bare ids). The reactive artefact alone could
  not tell A's marker from B being genuinely in-flight.

  THE FIX (rf2-vxgfnd.94): core keys `destroying-frames` by the DESTROYING
  incarnation's token `{id -> token}`, and the ViewCell commit consults the
  incarnation-scoped `frame/frame-incarnation-closing?` against the token it
  ACQUIRED — so A's stale marker (A's token) cannot reap a cell that owns B (B's
  token). AC (rf2-vxgfnd.88): 'A's close authority cannot tear down a cell that
  owns B.'

  The window is opened deterministically with a `CountDownLatch` handoff (NO
  sleeps): a wrapped `:epoch/on-frame-destroyed` hook — which `destroy-frame!`
  fires at step 7, AFTER `dissoc-frame!` and BEFORE the `finally` — pauses A's
  destroy exactly in the window; the main thread then constructs B under the reused
  id and commits a cell against it; the destroy resumes and clears the marker.
  `.clj` (JVM-only) — the window is a genuine two-thread interleaving; the barrier
  handoff makes it a controlled linearization probe, not a stress test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.late-bind             :as late-bind]
            [re-frame.live-frame            :as live-frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            ;; Load-bearing: publishes the :ui/on-frame-destroyed! hook, so A's
            ;; real destroy runs the ViewCell sweep (a no-op here — A owns no cells).
            [re-frame.ui.frames]
            [re-frame.ui.reactive           :as reactive])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- tk [fid q] [:sub fid q])

;; ===========================================================================
;; Failure-2: A's stale close marker must NOT reap a live replacement B's cell
;; ===========================================================================

(deftest stale-close-marker-of-A-does-not-tear-down-a-live-replacement-B
  (rf/reg-sub :ic/a (fn [db _] (:a db)))
  (let [fid       :ic/frame
        _         (make-frame! fid {:a 1})
        token-a   (frame/frame-incarnation-token fid)
        b-cell    (reactive/make-cell ::b)
        dissocd   (CountDownLatch. 1)   ;; A dissoc'd; marker still set; id free
        b-done    (CountDownLatch. 1)   ;; B constructed + committed
        orig-hook (late-bind/get-fn :epoch/on-frame-destroyed)
        token-b   (atom nil)
        b-error   (atom nil)]
    (try
      ;; Pause A's destroy in the post-`dissoc-frame!` / pre-`finally` window: the
      ;; :epoch/on-frame-destroyed hook fires at destroy step 7, after the id is
      ;; freed but before A's marker is cleared.
      (late-bind/set-fn! :epoch/on-frame-destroyed
                         (fn [id & _args]
                           (when (= id fid)
                             (.countDown dissocd)
                             (.await b-done 5 TimeUnit/SECONDS))))
      (let [destroy (future (frame/destroy-frame! fid))] ;; A — pauses in the hook
        (.await dissocd 5 TimeUnit/SECONDS)
        ;; In the window: the id is free (A dissoc'd) but A's marker is still set.
        (is (nil? (frame/frame fid)) "precondition: A is dissoc'd — the id is free")
        ;; Construct B under the reused id — a DISTINCT incarnation, fresh state.
        (make-frame! fid {:a 99})
        (reset! token-b (frame/frame-incarnation-token fid))
        (is (not (identical? token-a @token-b))
            "precondition: B is a fresh incarnation under the reused id")
        (is (true? (frame/frame-closing? fid))
            "precondition: BARE-id frame-closing? reads true — A's stale marker is
             still set for the reused id (the pre-fix hole reactive.cljc consulted)")
        (is (false? (frame/frame-incarnation-closing? fid @token-b))
            "the INCARNATION-scoped predicate reads false for B — A's marker carries
             A's token, not B's (rf2-vxgfnd.94)")
        ;; Commit B's cell IN the window. The incarnation-scoped revalidation must
        ;; resolve against B's acquired token, NOT A's stale bare-id marker.
        (try
          (rf/with-frame fid
            (reactive/with-capture b-cell (fn [] (reactive/sub-read [:ic/a]))))
          (reactive/commit! b-cell)
          (catch Throwable e (reset! b-error e)))
        (.countDown b-done)
        (is (nil? (deref destroy 5000 ::timeout)) "A's destroy completes cleanly"))

      (is (nil? @b-error) "B's commit did not throw")
      (testing "A's stale close marker did NOT tear down the live replacement B's cell"
        (is (= :connected (reactive/lifecycle b-cell))
            "the cell that ACQUIRED B stays connected — A's token ≠ B's token, so
             frame-incarnation-closing? is false for B; the pre-fix bare-id
             frame-closing? would have reaped it (rf2-vxgfnd.94)")
        (is (= #{(tk fid [:ic/a])} (reactive/committed-target-keys b-cell))
            "B's cell owns B's node")
        (is (= 99 (get (reactive/committed-values b-cell) (tk fid [:ic/a])))
            "B's cell reads B's own state — never joined A's teardown"))
      (testing "B is live and not closing after A's destroy fully completes"
        (is (some? (frame/frame fid)) "B survives A's teardown")
        (is (false? (frame/frame-closing? fid))
            "A's marker was cleared in its finally; B is live and not closing"))
      (finally
        (late-bind/set-fn! :epoch/on-frame-destroyed orig-hook)))))
