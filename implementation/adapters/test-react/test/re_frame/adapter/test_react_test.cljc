(ns re-frame.adapter.test-react-test
  "Demonstration tests for the Test-React adapter (rf2-gqyqv).

  Three scenarios drawn from the rf2-4l7t2 bug class:

    1. Happy-path lifecycle ordering — constructor → render → did-mount
       → did-update → will-unmount.
    2. Sync unmount during render — the rf2-4l7t2 production manifestation;
       the adapter throws `:rf.error/sync-unmount-during-render` at
       unit-test speed.
    3. Adapter-disposal teardown — `dispose-adapter!` drains stranded
       mounts and the test surface can see the `:forced-teardown`
       breadcrumb.

  These are minimal demonstration tests, not exhaustive coverage. The
  bead is P4 / placeholder; broader test corpora ship in follow-on
  beads if React-lifecycle bugs prove recurrent."
  (:require [re-frame.adapter.test-react :as test-react]
            [re-frame.substrate.adapter :as substrate-adapter]
            #?(:clj  [clojure.test :as ctest :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :as ctest :refer-macros [deftest is testing use-fixtures]])))

;; ---- fixture ---------------------------------------------------------------

(defn- install-test-react! [t]
  ;; Clean install/dispose around each test. `install-adapter!` throws
  ;; if an adapter is still installed; the test-only seam wipes the
  ;; lifecycle state so each case starts cold.
  (substrate-adapter/reset-lifecycle-state-for-tests!)
  (substrate-adapter/install-adapter! test-react/adapter)
  (try
    (t)
    (finally
      (substrate-adapter/dispose-adapter!))))

(use-fixtures :each install-test-react!)

;; ---- scenario 1: happy-path lifecycle ordering ----------------------------

(deftest happy-path-lifecycle-ordering
  (testing "constructor → render → did-mount → did-update → will-unmount"
    (let [mount (test-react/mount! [:div "v1"])
          _     (test-react/trigger-update! mount [:div "v2"])
          _     (test-react/unmount! mount)
          phases (mapv :phase (test-react/lifecycle-log mount))]
      (is (= [:constructor :render :did-mount :render :did-update :will-unmount]
             phases)
          "the simulated lifecycle records constructor, mount-render+did-mount, update-render+did-update, will-unmount"))))

(deftest mounted-roots-and-current-render-tree
  (testing "mounted-roots tracks live mounts; current-render-tree returns the latest hiccup"
    (let [mount (test-react/mount! [:div "initial"])]
      (is (= 1 (count (test-react/mounted-roots))))
      (is (= [:div "initial"] (test-react/current-render-tree mount)))
      (test-react/trigger-update! mount [:div "updated"])
      (is (= [:div "updated"] (test-react/current-render-tree mount)))
      (test-react/unmount! mount)
      (is (zero? (count (test-react/mounted-roots))))
      (is (nil? (test-react/current-render-tree mount))))))

;; ---- scenario 2: the rf2-4l7t2 class --------------------------------------

(deftest sync-unmount-during-render-throws
  (testing "synchronous unmount during render raises :rf.error/sync-unmount-during-render"
    (let [mount (test-react/mount! [:div])]
      ;; Force the currently-rendering? flag on (simulating a render in
      ;; flight — production code that calls `(.unmount root)` from a
      ;; child's render body hits this state in real React).
      (reset! (:currently-rendering? mount) true)
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/sync-unmount-during-render"
            (test-react/unmount! mount))
          "the adapter mirrors React 18+'s sync-unmount-during-render guard")
      ;; Clear the flag so the fixture's dispose-adapter! can drain
      ;; cleanly.
      (reset! (:currently-rendering? mount) false))))

;; ---- scenario 3: adapter-disposal drains stranded mounts ------------------

(deftest dispose-adapter-drains-stranded-mounts
  (testing "dispose-adapter! drains mounts the test forgot to unmount; log carries :forced-teardown breadcrumb"
    (let [mount (test-react/mount! [:div "leaked"])]
      ;; The :each fixture's `dispose-adapter!` will fire on the way
      ;; out; we invoke it explicitly here so the assertions land in
      ;; the test body rather than the fixture.
      (substrate-adapter/dispose-adapter!)
      (is (not @(:mounted? mount))
          ":mounted? flips to false on forced teardown")
      (is (some #{:forced-teardown} (mapv :phase (test-react/lifecycle-log mount)))
          ":forced-teardown phase records the drain so tests can spot leaked mounts")
      ;; Re-install so the fixture's outer dispose call below is a no-op.
      (substrate-adapter/install-adapter! test-react/adapter))))

;; ---- scenario 4: monotonic mount ordering (rf2-ovpl3) ---------------------

(deftest mount-returns-the-freshest-record-past-the-tenth-mint
  (testing "mount! returns the LATEST mount even after >=10 mounts in one
            process — rf2-ovpl3. The pre-fix code sorted lexicographically
            on the gensym :id, so '…mount-10' < '…mount-9' returned a STALE
            record. The numeric :seq ordering is monotonic for any count."
    ;; Mount 11 components without unmounting any (they stay live), then
    ;; mount one more whose render-tree is a unique sentinel. The
    ;; freshest mount! must return THAT sentinel's record — not a stale
    ;; mount-9-vs-mount-10 lexicographic loser.
    (let [_earlier (doall (for [i (range 11)]
                            (test-react/mount! [:div (str "v" i)])))
          latest   (test-react/mount! [:div "SENTINEL-LATEST"])]
      (is (= [:div "SENTINEL-LATEST"] (test-react/current-render-tree latest))
          "mount! returned the record for the most-recent mount, not a
           lexicographically-stale one")
      ;; And the returned record's :seq is the maximum across all live
      ;; mounts — proves we picked the freshest, not just any live one.
      (let [max-seq (apply max (map :seq (test-react/mounted-roots)))]
        (is (= max-seq (:seq latest))
            "the returned record carries the maximum monotonic :seq"))
      ;; The unmount thunk on the returned record tears down the SENTINEL
      ;; mount specifically (not a stale neighbour).
      (test-react/unmount! latest)
      (is (nil? (test-react/current-render-tree latest))
          "unmounting the returned record tore down the correct (latest) mount")
      ;; Drain the 11 still-live earlier mounts so the fixture's
      ;; dispose-adapter! has nothing surprising to forcibly tear down.
      (doseq [m _earlier] (test-react/unmount! m)))))

(deftest mount-seq-is-strictly-increasing
  (testing "each mount! mints a strictly-greater :seq than the previous —
            the monotonic ordering invariant rf2-ovpl3 relies on"
    (let [a (test-react/mount! [:div "a"])
          b (test-react/mount! [:div "b"])
          c (test-react/mount! [:div "c"])]
      (is (< (:seq a) (:seq b) (:seq c))
          ":seq is strictly increasing across successive mounts")
      (test-react/unmount! a)
      (test-react/unmount! b)
      (test-react/unmount! c))))
