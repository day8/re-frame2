(ns re-frame.ui.reactive-incarnation-close-cljs-test
  "rf2-vxgfnd.88 — the ViewCell frame-close revalidation is INCARNATION-safe
  (extending rf2-vxgfnd.61's linearization).

  #5764 (.61) made `commit!` consult `frame/frame-closing?` after the enrolment
  publish, so a cell enrolling mid-teardown JOINS the teardown instead of
  stranding `:connected`. But that check keyed on the BARE frame-id: a same-id
  DESTROY + RECREATE in the render→commit gap left the reused id NOT closing
  (`frame-closing?` false — the replacement is live and the old marker cleared),
  so a commit holding the DESTROYED incarnation's lease would publish `:connected`
  against the REPLACEMENT. The fix compares the incarnation TOKEN
  (`frame/frame-incarnation-token`) captured at ACQUIRE against the live token, so
  a commit resolves against exactly the incarnation it targeted.

  Staging is deterministic and host-agnostic via the `reactive/*commit-barrier*`
  test seam: it fires synchronously INSIDE `commit!` at `:post-acquire` — after
  the lease is acquired + read but BEFORE the publish — exactly the window
  rf2-vxgfnd.88 names. The fixture reincarnates the frame there, so no threads
  are needed; `.cljc` ending `-cljs-test` graft-checks it on node (`test:cljs`)
  AND JVM (`clojure -M:test`) against the REAL observation port + sub-cache
  (plain-atom adapter).

  RECIPROCAL Failure-2 (CLOSED by rf2-vxgfnd.94): an old incarnation A's stale
  close marker (present in the JVM window between `dissoc-frame!` and
  `destroy-frame!`'s terminal `finally`) tearing down a live REPLACEMENT
  incarnation B's cell. That could not be closed from the ui artefact alone —
  `frame/destroying-frames` was a set of bare ids, so reactive.cljc could not tell
  an A-marker from a B-marker. rf2-vxgfnd.94 keyed `destroying-frames` by the
  destroying incarnation's token and had the commit consult the incarnation-scoped
  `frame/frame-incarnation-closing?`; its JVM window fixture lives in
  `reactive-incarnation-close-race-jvm-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            ;; Load-bearing: publishes the :ui/on-frame-destroyed! hook, so a real
            ;; destroy runs the ViewCell sweep — proving the mid-commit cell is
            ;; NOT caught by it (it enrols only at the post-publish connect!).
            [re-frame.ui.frames]
            [re-frame.ui.reactive          :as reactive]))

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
;; Failure-1: an old-incarnation commit must NOT survive on the replacement id
;; ===========================================================================

(deftest acquire-before-incarnation-snapshot-cannot-pair-a-lease-from-a-with-token-b
  (rf/reg-sub :ic/a (fn [db _] (:a db)))
  (let [fid     :ic/acquire-snapshot
        _       (make-frame! fid {:a 1})
        token-a (frame/frame-incarnation-token fid)
        cell    (reactive/make-cell ::acquire-snapshot)
        [_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (reactive/sub-read ::site [:ic/a]))))]
    (binding [reactive/*commit-barrier*
              (fn [phase _]
                (when (= :post-stage-acquire phase)
                  ;; The lease now belongs to A, but the subsequent frame-id
                  ;; lookup would see B. `current?` must reject that pairing.
                  (frame/destroy-frame! fid)
                  (make-frame! fid {:a 99})))]
      (reactive/commit! cell capture))
    (is (not (identical? token-a (frame/frame-incarnation-token fid))))
    (is (= :fresh (reactive/lifecycle cell))
        "a rejected candidate never publishes/connects")
    (is (empty? (reactive/committed-sites cell)))
    (is (= 1 (reactive/revision cell))
        "the host is synchronously invalidated to probe B before paint")
    (is (nil? (get @(:sub-cache (frame/frame fid)) [:ic/a]))
        "rolling back A cannot materialize or decrement B's cache")
    (reactive/reset-scheduler!)
    (let [[_ capture-b] (rf/with-frame fid
                          (reactive/with-capture
                           cell (fn [] (reactive/sub-read ::site [:ic/a]))))]
      (reactive/commit! cell capture-b))
    (is (= :connected (reactive/lifecycle cell)))
    (is (= 99 (:value (reactive/committed-site cell ::site))))))

(deftest commit-of-a-superseded-incarnation-joins-its-teardown-not-the-replacement
  (rf/reg-sub :ic/a (fn [db _] (:a db)))
  (let [fid     :ic/frame
        _       (make-frame! fid {:a 1})
        token-a (frame/frame-incarnation-token fid)
        cell    (reactive/make-cell ::v)]
    ;; render probes incarnation A (ownership-free)
    (let [[_ capture] (rf/with-frame fid
                        (reactive/with-capture
                         cell (fn [] (reactive/sub-read ::site [:ic/a]))))]
    ;; commit: step 4 acquires A's lease + step 5 reads it (A still live), THEN
    ;; the :post-acquire seam fully destroys A and recreates B under the same id
    ;; BEFORE the publish. The incarnation-safe revalidation must join THIS commit
    ;; (which holds A's lease) to A's teardown, never publish :connected on B.
    (binding [reactive/*commit-barrier*
              (fn [phase _]
                (when (= :post-acquire phase)
                  (frame/destroy-frame! fid)     ;; A fully destroyed (marker cleared)
                  (make-frame! fid {:a 99})))]    ;; B — a DISTINCT incarnation, new state
      (reactive/commit! cell capture)))
    (testing "precondition — B is a distinct incarnation under the reused id"
      (is (not (identical? token-a (frame/frame-incarnation-token fid)))
          "destroy + recreate minted a fresh incarnation token")
      (is (false? (frame/frame-closing? fid))
          "the reused id is NOT closing — bare-id frame-closing? MISSES this (the pre-fix hole)"))
    (testing "the commit resolved against the incarnation it ACQUIRED (A), not the id"
      (is (= :dead (reactive/lifecycle cell))
          "the cell holding A's lease joined A's teardown — not left :connected on B")
      (is (empty? (reactive/committed-target-keys cell))
          "A's stale lease is released — no old ownership survives on the replacement id"))
    (testing "the replacement incarnation B is wholly untouched and commits cleanly"
      ;; Reset the slice PROBE MEMO for clean isolation. A cold probe caches its
      ;; computed value under a `(frame, frame-epoch, registry-epoch)` tag whose
      ;; epochs tie across this reincarnation by construction — but the tag ALSO
      ;; carries the exact frame-incarnation token (rf2-vxgfnd.160), so B's probe
      ;; would already miss A's stale entry by identity. The reset simply pins B's
      ;; OWN read unambiguously. The dead cell already left every registry.
      (reactive/reset-scheduler!)
      (let [cell-b (reactive/make-cell ::b)]
        (let [[_ capture] (rf/with-frame fid
                            (reactive/with-capture
                             cell-b (fn [] (reactive/sub-read ::site [:ic/a]))))]
          (reactive/commit! cell-b capture))
        (is (= :connected (reactive/lifecycle cell-b)) "B's own cell connects")
        (is (= #{(tk fid [:ic/a])} (reactive/committed-target-keys cell-b))
            "B's cell owns B's node")
        (is (= 1 (:ref-count (get @(:sub-cache (frame/frame fid)) [:ic/a])))
            "B's node has exactly ONE owner — the stale A release never decremented
             B's cache entry (identity-guarded release, rf2-vxgfnd.88 AC4)")
        (is (= 99 (get (reactive/committed-values cell-b) (tk fid [:ic/a])))
            "B reads its OWN state — the stale commit never clobbered B's cache")))))

;; ===========================================================================
;; The ordinary (non-reincarnation) commit is unaffected — a live, unchanged
;; incarnation is neither superseded nor closing, so the commit publishes
;; :connected (the constant false check).
;; ===========================================================================

(deftest live-unchanged-incarnation-commit-stays-connected
  (rf/reg-sub :ic/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :ic/frame2 {:a 1})
        cell (reactive/make-cell ::v)]
    (let [[_ capture] (rf/with-frame fid
                        (reactive/with-capture
                         cell (fn [] (reactive/sub-read ::site [:ic/a]))))]
      (reactive/commit! cell capture))
    (is (= :connected (reactive/lifecycle cell))
        "no supersede, no close ⇒ ordinary connected commit (incarnation check is false)")
    (is (= #{(tk fid [:ic/a])} (reactive/committed-target-keys cell)))))
