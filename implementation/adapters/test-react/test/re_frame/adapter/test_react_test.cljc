(ns re-frame.adapter.test-react-test
  "Demonstration tests for the Test-React adapter (rf2-gqyqv).

  Scenarios drawn from the rf2-4l7t2 bug class:

    1. Happy-path lifecycle ordering — constructor → render → did-mount
       → did-update → will-unmount.
    2. Sync unmount during render — the rf2-4l7t2 production manifestation;
       the adapter throws `:rf.error/sync-unmount-during-render` at
       unit-test speed.
    3. Adapter-disposal teardown — `dispose-adapter!` drains stranded
       mounts and the test surface can see the `:forced-teardown`
       breadcrumb.
    4. mount! identity — the record threaded back from the internal mount
       seam is the one created, even with many mounts live (rf2-ee38b.16,
       replacing the old :seq scan-recovery tests).
    5. render-to-string survives a dispose/reinstall cycle — dispose does
       not clear the chained hiccup emitter (rf2-ee38b.16).

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
    (let [mount (test-react/mount! [:div "v1"])]
      (test-react/trigger-update! mount [:div "v2"])
      (test-react/unmount! mount)
      (is (= [:constructor :render :did-mount :render :did-update :will-unmount]
             (mapv :phase (test-react/lifecycle-log mount)))
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

;; ---- scenario 4: mount! returns the exact record it created ---------------

(deftest mount-returns-its-own-record-under-many-live-mounts
  (testing "mount! returns the record it created — and its unmount thunk tears
            down THAT mount — even with many other mounts live at once. The
            record is threaded directly through the internal mount seam, so
            there is no scan/ordering heuristic to alias the wrong mount."
    ;; Mount a dozen components without unmounting any (they stay live), then
    ;; mount one more with a unique sentinel render-tree.
    (let [earlier (doall (for [i (range 12)]
                           (test-react/mount! [:div (str "v" i)])))
          latest  (test-react/mount! [:div "SENTINEL-LATEST"])]
      (is (= [:div "SENTINEL-LATEST"] (test-react/current-render-tree latest))
          "mount! returned the record for the mount it just created")
      (is (= 13 (count (test-react/mounted-roots)))
          "all thirteen mounts are live")
      ;; The unmount thunk on the returned record tears down the SENTINEL
      ;; mount specifically — not a neighbour.
      (test-react/unmount! latest)
      (is (nil? (test-react/current-render-tree latest))
          "unmounting the returned record tore down the correct (latest) mount")
      (is (= 12 (count (test-react/mounted-roots)))
          "exactly one mount torn down; the other twelve remain live")
      ;; Drain the 12 still-live earlier mounts so the fixture's
      ;; dispose-adapter! has nothing surprising to forcibly tear down.
      (doseq [m earlier] (test-react/unmount! m)))))

;; ---- scenario 5: render-to-string survives a dispose/reinstall cycle ------

(deftest render-to-string-survives-dispose-reinstall
  (testing "dispose-adapter! does NOT clear the chained hiccup emitter, so
            render-to-string keeps working across a dispose/reinstall cycle"
    (test-react/set-hiccup-emitter! (fn [tree _opts] (str "HTML:" (pr-str tree))))
    (try
      (is (= "HTML:[:div \"a\"]"
             (substrate-adapter/render-to-string [:div "a"] nil))
          "emitter is bound before the dispose cycle")
      ;; Dispose + reinstall (the standard fixture shape). The emitter must
      ;; survive — it is re-derivable infrastructure, not a host resource.
      (substrate-adapter/dispose-adapter!)
      (substrate-adapter/install-adapter! test-react/adapter)
      (is (= "HTML:[:div \"b\"]"
             (substrate-adapter/render-to-string [:div "b"] nil))
          "emitter still bound after dispose + reinstall")
      (finally
        (test-react/set-hiccup-emitter! nil)))))
