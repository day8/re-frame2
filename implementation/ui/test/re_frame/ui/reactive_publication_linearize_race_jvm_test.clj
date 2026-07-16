(ns re-frame.ui.reactive-publication-linearize-race-jvm-test
  "rf2-77pb08 — the final ViewCell body-authority validation and the state
  publication are ONE linearized compare-and-set! transition.

  THE RACE (JVM-only; CLJS is single-threaded). PR #5938 fenced the final
  publication against an HMR body-authority change, but the fence SAMPLED the
  cell generation + registered-view revision and then performed an INDEPENDENT
  `swap!` to publish. A concurrent `advance-generation!` (cell-local axis) or
  same-view re-registration (registry axis) landing AFTER that sample but BEFORE
  the swap published a generation-0 capture that connected and owned leases while
  current authority was already generation/revision 1 — a check-to-use race.

  These fixtures reproduce the window with a deterministic `CountDownLatch`
  handoff (NO sleeps): the commit PARKS at the `:pre-publish` seam — fired inside
  `publish-commit!` between an attempt's authority sample and its publish CAS — a
  racing thread advances the authority, then the commit resumes. With the
  linearization the publish CAS over the now-stale snapshot FAILS (cell-local
  axis) or the registered-slot identity token has moved (registry axis), so the
  loop re-validates and returns `:stale` having published/connected NOTHING.
  Pre-fix (sample-then-independent-swap) the racing commit publishes and connects.

  `.clj` (JVM-only) — a genuine two-thread interleaving; the barrier handoff means
  only one thread mutates the substrate at a time, so this is a controlled
  linearization probe, not a stress test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.fingerprint        :as fp]
            [re-frame.ui.reactive           :as reactive])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)
(def ^:private hs0 (fp/hook-signature-hash {:locals [] :effects []}))

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- capture-of [cell sites]
  (second
    (rf/with-frame fid
      (reactive/with-capture
        cell (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))))

(defn- park-at-pre-publish
  "A `*commit-barrier*` that, at `:pre-publish` (once), signals `parked` — the
  authority sample is done — then blocks until `released` so a racing thread can
  advance the authority in EXACTLY the sample→publish window."
  [^CountDownLatch parked ^CountDownLatch released]
  (fn [phase _cell]
    (when (= phase :pre-publish)
      (.countDown parked)
      (.await released 5 TimeUnit/SECONDS))))

;; ===========================================================================
;; Cell-local axis: a concurrent advance-generation! fails the publish CAS
;; ===========================================================================

(deftest concurrent-generation-advance-at-pre-publish-fences-cell-local
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell     (reactive/make-cell ::race-cell-local 0)  ;; unregistered: CAS is sole catch
        cap      (capture-of cell [[::site [:r/a]]])
        parked   (CountDownLatch. 1)
        released (CountDownLatch. 1)]
    (is (= 0 (:generation cap)) "capture formed at cell body generation 0")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [committer (future
                      (binding [reactive/*commit-barrier*
                                (park-at-pre-publish parked released)]
                        (reactive/commit! cell cap)))]
      (.await parked 5 TimeUnit/SECONDS)
      ;; racing thread — advance the cell body generation while the commit is
      ;; parked between its authority sample and the publish CAS.
      (reactive/advance-generation! cell 1)
      (.countDown released)
      (is (= :stale (deref committer 5000 ::timeout))
          "the publish CAS over the stale snapshot failed → :stale, nothing published")
      (testing "the stale generation-0 capture never published or connected"
        (is (= :fresh (reactive/lifecycle cell)) "never connected")
        (is (empty? (reactive/committed-target-keys cell)) "no dependency set published")
        (is (nil? (entry [:r/a])) "the staged lease was released — zero-owner node")
        (is (= 1 (reactive/generation cell)) "the cell carries the advanced generation")
        (is (= 0 (reactive/revision cell)) "no revision advance")))))

;; ===========================================================================
;; Registry axis: a concurrent re-registration moves the slot-identity token
;; ===========================================================================

(deftest concurrent-registration-at-pre-publish-fences-registry
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid      ::race-registry-view
        _        (reactive/register-view-generation! vid hs0)
        cell     (reactive/make-cell vid 0)
        cap      (capture-of cell [[::site [:r/a]]])
        parked   (CountDownLatch. 1)
        released (CountDownLatch. 1)]
    (is (= 0 (:generation cap)) "capture formed at body revision 0")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [committer (future
                      (binding [reactive/*commit-barrier*
                                (park-at-pre-publish parked released)]
                        (reactive/commit! cell cap)))]
      (.await parked 5 TimeUnit/SECONDS)
      ;; racing thread — re-register the same view (advance the registered body
      ;; revision) while the commit is parked in the sample→publish window.
      (reactive/register-view-generation! vid hs0)
      (.countDown released)
      (is (= :stale (deref committer 5000 ::timeout))
          "the moved slot-identity token failed the publish gate → :stale")
      (testing "the stale revision-0 capture never published or connected"
        (is (= :fresh (reactive/lifecycle cell)) "never connected")
        (is (empty? (reactive/committed-target-keys cell)) "no dependency set published")
        (is (nil? (entry [:r/a])) "the staged lease was released — zero-owner node")
        (is (= 0 (reactive/revision cell)) "no revision advance")))))

;; ===========================================================================
;; A disjoint commit under an UNRELATED view registration still publishes —
;; the slot-identity token is per-view, so no global serialization.
;; ===========================================================================

(deftest concurrent-unrelated-registration-does-not-fence-a-fresh-commit
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid       ::race-owner-view
        other-vid ::race-other-view
        _         (reactive/register-view-generation! vid hs0)
        _         (reactive/register-view-generation! other-vid hs0)
        cell      (reactive/make-cell vid 0)
        cap       (capture-of cell [[::site [:r/a]]])
        parked    (CountDownLatch. 1)
        released  (CountDownLatch. 1)]
    (let [committer (future
                      (binding [reactive/*commit-barrier*
                                (park-at-pre-publish parked released)]
                        (reactive/commit! cell cap)))]
      (.await parked 5 TimeUnit/SECONDS)
      ;; A DIFFERENT view re-registers in the window — our view's slot identity
      ;; is untouched, so the token gate holds and the commit publishes.
      (reactive/register-view-generation! other-vid hs0)
      (.countDown released)
      (is (identical? cell (deref committer 5000 ::timeout))
          "an unrelated view's registration does not fence this commit")
      (testing "the fresh capture published and connected normally"
        (is (= :connected (reactive/lifecycle cell)))
        (is (= 1 (:value (reactive/committed-site cell ::site))))
        (is (= 1 (:ref-count (entry [:r/a]))) "exactly one owner acquired")))))
