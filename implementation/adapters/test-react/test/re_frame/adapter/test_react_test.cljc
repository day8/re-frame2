(ns re-frame.adapter.test-react-test
  "Tests for the Test-React adapter.

  Two layers:

  A. Demonstration scenarios — the original skeleton coverage (rf2-gqyqv):
     happy-path lifecycle ordering, render-tree tracking, adapter-disposal
     drain, mount! record identity, render-to-string dispose/reinstall.

  B. Ported lifecycle regressions (rf2-n2cuo) — each guards a REAL bug class
     the adapter claims to catch, and asserts that bug's *symptom* so a
     future regression in the class fails this unit test:

       1. Organic sync-unmount-during-render (the rf2-4l7t2 class). Modelled
          on the real Story senbl panel-host shape: a panel-host parent holds
          the current panel's child root; on a chip-row 'switch panel'
          re-render, the parent's render body synchronously unmounts the
          PREVIOUS panel's root. The guard fires ORGANICALLY — no
          hand-fabricated :currently-rendering? — because the unmount happens
          while React (the global render depth) is rendering somewhere.

       2. Unbalanced subscribe/dispose (mount/unmount ref-count). A faulty
          teardown disposes only some of the resources it acquired on mount;
          the symptom is a non-zero live-mount / ref count after teardown.

       3. Double-render. A redundant re-render fires where exactly one was
          expected; the symptom is an extra :render (and :did-update) entry in
          the lifecycle log — the render counter is higher than the contract."
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

;; ---- lifecycle-log query helpers -------------------------------------------
;; The assertions below repeatedly interrogate a mount's lifecycle log by
;; phase — counting how many times a phase fired (render-count contracts) and
;; reading the first `:at` timestamp for a phase (teardown-ordering checks).
;; These two helpers name those queries so each assertion reads as the
;; property under test rather than a `->>`/filter thread.

(defn- phase-count
  "How many `phase` entries the mount's lifecycle log holds."
  [mount phase]
  (->> (test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       count))

(defn- phase-first-at
  "The `:at` timestamp of the first `phase` entry in the mount's lifecycle
  log, or nil if the phase never fired."
  [mount phase]
  (->> (test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       first
       :at))

;; ----------------------------------------------------------------------------
;; A. Demonstration scenarios
;; ----------------------------------------------------------------------------

;; ---- happy-path lifecycle ordering ----------------------------------------

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

;; ---- adapter-disposal drains stranded mounts ------------------------------

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

;; ---- mount! returns the exact record it created ---------------------------

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

;; ---- render-to-string survives a dispose/reinstall cycle ------------------

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

;; ----------------------------------------------------------------------------
;; A'. Recursive child mounting — the structural seam the regressions ride on
;; ----------------------------------------------------------------------------

(deftest child-mounts-recurse-through-their-own-lifecycle
  (testing "a parent's render body mounts a child via mount-child!; the child
            runs its own constructor → render → did-mount, is tracked as a
            child of the parent, and is torn down children-first when the
            parent unmounts"
    (let [child-ref (atom nil)
          parent    (test-react/mount!
                      {:rf/component
                       (fn [_parent]
                         (reset! child-ref
                                 (test-react/mount-child! [:span "child"])))})]
      ;; The child ran its full mount lifecycle.
      (is (= [:constructor :render :did-mount]
             (mapv :phase (test-react/lifecycle-log @child-ref)))
          "child recursed through its own class-3 mount lifecycle")
      ;; The forest holds both mounts; the parent records the child.
      (is (= 2 (count (test-react/mounted-roots)))
          "parent + child are both live in the forest")
      (is (= [@child-ref] (test-react/children parent))
          "the child is recorded under the parent")
      ;; Unmounting the parent cascades to the child, children-first.
      (test-react/unmount! parent)
      (is (zero? (count (test-react/mounted-roots)))
          "parent unmount cascaded to the child — nothing leaks")
      (is (some #{:will-unmount} (mapv :phase (test-react/lifecycle-log @child-ref)))
          "the child saw its own :will-unmount during the cascade")
      (let [child-unmount-at  (phase-first-at @child-ref :will-unmount)
            parent-unmount-at (phase-first-at parent :will-unmount)]
        (is (<= child-unmount-at parent-unmount-at)
            "children tear down before (or no later than) their parent")))))

;; ----------------------------------------------------------------------------
;; B.1 — Organic sync-unmount-during-render (the rf2-4l7t2 class)
;; ----------------------------------------------------------------------------
;;
;; Real shape (Story senbl panel-host, PR #1577): a single persistent
;; panel-host owns the CURRENT panel's child root. When the chip-row picker
;; switches panels, the host re-renders and — inside that render body —
;; synchronously calls (.unmount) on the PREVIOUS panel's root. React 18+
;; raises "Attempted to synchronously unmount a root while React was already
;; rendering." The original fix deferred the unmount to a microtask.
;;
;; Here the bug is reproduced ORGANICALLY: the host's render body issues the
;; unmount while the global render depth is non-zero — no test sets
;; :currently-rendering? by hand. That converts the headline capability from
;; "guard logic verified" to "bug condition reproduced."

(deftest organic-sync-unmount-during-render-rf2-4l7t2
  (testing "a panel-host that synchronously unmounts the previous panel's root
            from inside its switch-panel re-render trips
            :rf.error/sync-unmount-during-render ORGANICALLY (no fabricated
            in-flight state) — the rf2-4l7t2 bug condition, reproduced"
    ;; Mount panel A as a standalone root (the host's current child).
    (let [panel-a (test-react/mount! [:div.panel "A"])]
      (is (= 1 (count (test-react/mounted-roots))))
      ;; The host re-renders to switch to panel B. The BUGGY render body
      ;; synchronously unmounts panel A's root mid-render (the senbl pattern
      ;; before the microtask-defer fix).
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/sync-unmount-during-render"
            (test-react/mount!
              {:rf/component
               (fn [_host]
                 ;; BUG: synchronous unmount of a separate root during render.
                 (test-react/unmount! panel-a))}))
          "the guard fires organically: the unmount happens while the host's
           render is in flight, which is React's actual guard condition")
      ;; Panel A is still mounted — the guard short-circuited the unmount
      ;; before it could tear the root down (matching React: it refuses the
      ;; synchronous unmount rather than racing).
      (is (true? @(:mounted? panel-a))
          "the guard refused the synchronous unmount — panel A was NOT torn down")
      (test-react/unmount! panel-a))))

(deftest deferred-unmount-after-render-is-safe-rf2-4l7t2-fix
  (testing "the rf2-4l7t2 FIX shape: unmounting the previous panel's root AFTER
            the host's render has completed (the microtask-defer) does not trip
            the guard — proves the guard discriminates in-render from
            after-render, so it is not a blunt always-throw"
    (let [panel-a (test-react/mount! [:div.panel "A"])
          ;; Host re-renders to switch to B WITHOUT unmounting A mid-render.
          host    (test-react/mount! {:rf/component (fn [_host] :switched-to-B)})]
      (is (= 2 (count (test-react/mounted-roots))))
      ;; Now that no render is in flight, unmounting panel A is safe (this is
      ;; what queueMicrotask buys you in the production fix).
      (is (false? (test-react/rendering?))
          "no render in flight after the host's render completed")
      (test-react/unmount! panel-a)        ; must NOT throw
      (is (= 1 (count (test-react/mounted-roots)))
          "deferred unmount tore down panel A cleanly")
      (test-react/unmount! host))))

;; ----------------------------------------------------------------------------
;; B.2 — Unbalanced subscribe/dispose (mount/unmount ref-count)
;; ----------------------------------------------------------------------------
;;
;; Bug class: a component acquires a resource on mount (a subscription, a
;; listener, a child root) and is supposed to release it on unmount. A faulty
;; teardown releases only SOME of what it acquired. The leak is invisible to a
;; happy-path test but shows up as an imbalanced ref-count / a non-zero live
;; mount after the component's own teardown should have drained everything.

(deftest unbalanced-subscribe-dispose-leaves-an-orphaned-root
  (testing "a parent spins up a SECOND root inside its render body but tracks it
            as a standalone mount (mount!) instead of a child (mount-child!), so
            the parent's teardown cascade never disposes it. The symptom is a
            non-zero live-mount count after the parent unmounts — the
            subscribe-without-matching-dispose imbalance, and the orphaned-root
            root cause behind the rf2-4l7t2 family."
    (let [orphan-ref (atom nil)
          ;; The host mounts a tracked child AND — the bug — a second root via
          ;; the standalone `mount!` seam (think: an effect that creates a
          ;; Reagent root but forgets to register its unmount thunk for
          ;; teardown). `mount!` does NOT attach to the parent's :children.
          host (test-react/mount!
                 {:rf/component
                  (fn [_host]
                    (test-react/mount-child! [:section "tracked-child"])
                    (reset! orphan-ref (test-react/mount! [:section "ORPHAN"])))})]
      (is (= 3 (count (test-react/mounted-roots)))
          "host + tracked child + orphaned root are all live")
      (is (= 1 (count (test-react/children host)))
          "the host only KNOWS about the one tracked child (the orphan is untracked)")
      ;; Correct-looking teardown: unmount the host. Its cascade tears down the
      ;; tracked child — but cannot reach the untracked orphan.
      (test-react/unmount! host)
      ;; Symptom: the orphan is still mounted; the count never returned to zero.
      (is (= 1 (count (test-react/mounted-roots)))
          "the orphaned root leaks — subscribe/dispose imbalance detected")
      (is (true? @(:mounted? @orphan-ref))
          "the specific leaked root is the orphan the host forgot to track")
      (is (not (zero? (count (test-react/mounted-roots))))
          "a balanced teardown would zero the live-mount count; this buggy path leaks")
      ;; Clean up the orphan so the fixture's drain has nothing to forcibly tear.
      (test-react/unmount! @orphan-ref))))

(deftest balanced-subscribe-dispose-returns-to-zero
  (testing "the CORRECT counterpart: a parent that lets the cascade tear all
            children down returns the live-mount count and ref count to zero —
            the green state a regression in B.2 would break"
    (let [live   (atom 0)
          parent (test-react/mount!
                   {:rf/component
                    (fn [_parent]
                      (test-react/mount-child! [:li "a"]) (swap! live inc)
                      (test-react/mount-child! [:li "b"]) (swap! live inc))})]
      (is (= 3 (count (test-react/mounted-roots))))
      ;; Correct teardown: just unmount the parent; the cascade handles
      ;; children, and we mirror the ref release per child it tears down.
      (let [n-children (count (test-react/children parent))]
        (test-react/unmount! parent)
        (dotimes [_ n-children] (swap! live dec)))
      (is (zero? (count (test-react/mounted-roots)))
          "cascade tore every child down — nothing leaks")
      (is (zero? @live)
          "ref count balanced back to zero"))))

;; ----------------------------------------------------------------------------
;; B.3 — Double-render
;; ----------------------------------------------------------------------------
;;
;; Bug class: a single logical state change drives TWO renders where the
;; contract is one. (E.g. a handler that both replaces app-db AND imperatively
;; pokes the component, or a sub that fires twice.) The symptom is an extra
;; :render / :did-update entry in the lifecycle log — the render count exceeds
;; the number of intended updates.

(deftest double-render-shows-an-extra-render-entry
  (testing "a buggy update path that re-renders twice for one logical change
            records TWO :did-update :render pairs; the symptom is a render
            count of 2 where the contract is 1"
    (let [mount (test-react/mount! [:div "v1"])]
      ;; CONTRACT: one logical change → one update render.
      ;; BUG: the update path fires trigger-update! twice (the redundant
      ;; second render real double-render bugs produce).
      (test-react/trigger-update! mount [:div "v2"])
      (test-react/trigger-update! mount [:div "v2"]) ; redundant re-render
      (let [renders (phase-count mount :render)
            updates (phase-count mount :did-update)]
        ;; Mount render + two update renders = 3.
        (is (= 3 renders)
            "render count is 3 (1 mount + 2 update) — the doubled update render is visible")
        (is (= 2 updates)
            "two :did-update entries expose the redundant second render"))
      (test-react/unmount! mount))))

(deftest single-render-is-the-balanced-baseline
  (testing "the CORRECT counterpart: one logical change → exactly one update
            render (one :did-update). A double-render regression breaks this."
    (let [mount (test-react/mount! [:div "v1"])]
      (test-react/trigger-update! mount [:div "v2"])
      (is (= 1 (phase-count mount :did-update))
          "exactly one update render for one logical change")
      (test-react/unmount! mount))))
