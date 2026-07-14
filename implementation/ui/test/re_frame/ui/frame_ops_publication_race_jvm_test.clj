(ns re-frame.ui.frame-ops-publication-race-jvm-test
  "JVM lifecycle race coverage for the compiled `(frame)` operation-bundle CACHE
  publication (rf2-vxgfnd.229).

  `re-frame.ui.frames/frame-ops` mints a per-incarnation operation bundle and
  caches it keyed by the frame's incarnation token. A reader captures token A,
  passes its liveness check, and later publishes into the cache. If A is
  destroyed and a same-id replacement incarnation B is created + read BETWEEN
  that check and the publication, a pre-fix check-then-independent-publish let
  the paused A reader overwrite B's cache entry and hand back A's already-dead
  bundle — B then lost its promised stable identity and the read returned a
  bundle no longer live at return time.

  The fix routes the publish through the frame's drain serialization and
  REVALIDATES the captured incarnation under the lock (the same seam
  `publish-plan-for-live-incarnation!` uses), so a stale reader fails loud
  through the canonical `:rf.error/frame-destroyed` path and never displaces
  the live incarnation's entry.

  The reader is paused at the deterministic `*frame-ops-publish-barrier*` seam
  with a CountDownLatch handoff — no sleeps, no scheduler guesses. CLJS cannot
  interleave a second publisher while synchronous destruction is paused, so this
  fixture is JVM-only.

  This fixture is also the guard for the acceptance criterion 'a mutation
  restoring check-then-independent-publish fails deterministically': reverting
  `publish-frame-ops!` to the pre-fix cache-atom check-then-swap turns both the
  `:rf.error/frame-destroyed` assertion and the B1/B2 identity assertion red."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.frames            :as frames])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [test-fn]
    (frames/reset-frame-ops-cache!)
    (try (test-fn) (finally (frames/reset-frame-ops-cache!)))))

(defn- read-ops [fid]
  (binding [frame/*current-frame* fid]
    (frames/frame-ops)))

(deftest stale-reader-neither-displaces-a-reincarnation-nor-returns-a-dead-bundle
  (let [fid        :ops-race/frame
        _          (rf/make-frame {:id fid :doc "incarnation A"})
        token-a    (frame/frame-incarnation-token fid)
        at-barrier (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        result-a   (atom ::unset)
        ;; Reader A: capture token A, clear the top-of-`frame-ops` liveness
        ;; check while A is still live, then PARK at the publish barrier before
        ;; publishing. The barrier is bound ONLY on this thread — the main
        ;; thread's reads of B must run without the barrier.
        reader-a
        (future
          (binding [frame/*current-frame* fid
                    frames/*frame-ops-publish-barrier*
                    (fn [_fid _tok]
                      (.countDown at-barrier)
                      (.await release 10 TimeUnit/SECONDS))]
            (reset! result-a
                    (try {:ok (frames/frame-ops)}
                         (catch clojure.lang.ExceptionInfo e
                           {:err (:rf.error/id (ex-data e))})))))]
    (try
      (is (.await at-barrier 10 TimeUnit/SECONDS)
          "reader A reached the publish barrier after clearing its liveness check")
      (is (identical? token-a (frame/frame-incarnation-token fid))
          "precondition: incarnation A is still the live token while A is parked")

      ;; Destroy A (the :ui/on-frame-destroyed! hook prunes A's cache entry and
      ;; the destroy flips liveness), then stand up a fresh same-id incarnation
      ;; B and read it — B mints + publishes its OWN bundle while A is still
      ;; parked mid-publish.
      (frame/destroy-frame! fid)
      (rf/make-frame {:id fid :doc "incarnation B"})
      (let [token-b (frame/frame-incarnation-token fid)
            b1      (read-ops fid)]
        (is (not (identical? token-a token-b))
            "B is a distinct incarnation of the reused id")
        (is (= fid (:frame b1)) "B's freshly published bundle is locked to the id")

        ;; Resume A. The fix fails it loud through the destroyed path; the pre-fix
        ;; independent publish would instead overwrite B's entry and return A's
        ;; dead bundle.
        (.countDown release)
        (is (not= ::timeout (deref reader-a 10000 ::timeout))
            "reader A resumed and completed")
        (is (= {:err :rf.error/frame-destroyed} @result-a)
            "stale reader A fails loud; it never returns a dead-incarnation bundle")

        ;; B's cached identity survived the stale reader entirely.
        (let [b2 (read-ops fid)]
          (is (identical? b1 b2)
              "B1 and B2 are identical — the stale A reader did not displace B's entry")
          (is (identical? token-b (frame/frame-incarnation-token fid))
              "B is still the live incarnation after A resumed")))
      (finally
        (.countDown release)
        (deref reader-a 10000 ::timeout)))))

(deftest disjoint-frames-publish-independently-and-same-incarnation-reads-are-stable
  ;; Companion positive: the drain-serialized publish keeps the ordinary
  ;; guarantees — disjoint ids make independent progress and repeated
  ;; same-incarnation reads share one bundle identity.
  (rf/make-frame {:id :ops-race/x})
  (rf/make-frame {:id :ops-race/y})
  (let [x1 (read-ops :ops-race/x)
        y1 (read-ops :ops-race/y)
        x2 (read-ops :ops-race/x)]
    (is (identical? x1 x2) "same-incarnation reads keep stable identity")
    (is (not (identical? x1 y1)) "disjoint frames get independent bundles")
    (is (= :ops-race/x (:frame x1)))
    (is (= :ops-race/y (:frame y1)))))
