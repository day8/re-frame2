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
          the lifecycle log — the render counter is higher than the contract.

  C. Harness-contract guards (rf2-ynjts.6) — the documented public-surface
     invariants the uix/helix browser suites lean on. Each pins a guard /
     error / two-entry-point behaviour the harness PROMISES in its docstrings
     but the A/B layers above never exercised: trigger-update! / unmount!
     after teardown, mount-child! outside a render body, mount! under the
     wrong installed adapter, the no-emitter render-to-string throw, unmount!
     idempotency, the substrate :render entry point returning a working
     unmount thunk, and deep (grandchild) leaf-upward cascade ordering."
  (:require [re-frame.adapter.test-react :as test-react]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
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

(deftest unmount-actually-evicts-from-the-raw-active-set
  (testing "unmount! removes the mount from the RAW active-mounts set, not just
            flips its :mounted? flag. mounted-roots filters by :mounted?, so it
            reports zero even if the dead record were left in the backing set —
            this pins the eviction directly. Regression for the
            base/mount record-identity disj mismatch: the unmount thunk must
            disj the exact record conj'd, or the dead record leaks for the
            adapter's lifetime (defrecord equality includes :unmount-fn, so the
            pre-assoc skeleton would not match)."
    (let [active @#'test-react/active-mounts]
      (is (zero? (count @active))
          "precondition: raw active set starts empty")
      (let [mount (test-react/mount! [:div "live"])]
        (is (= 1 (count @active))
            "mount registered exactly one record in the raw set")
        (test-react/unmount! mount)
        (is (zero? (count @active))
            "unmount! EVICTED the record from the raw set — no leaked dead record"))
      ;; Many mount/unmount cycles must not grow the raw set (the leak symptom
      ;; was monotonic growth masked by the :mounted? filter).
      (dotimes [_ 25]
        (test-react/unmount! (test-react/mount! [:div "churn"])))
      (is (zero? (count @active))
          "25 mount/unmount cycles left the raw active set empty — no accumulation"))))

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

;; ---- dispose-adapter! clears every live frame's sub-cache (rf2-ghfkkk) ----
;;
;; The HIGH-severity finding: test-react's `dispose-adapter!` drained
;; active-mounts (the host-resource MUST) but did NOT walk per-frame
;; sub-caches the way the React adapters do via `spine/dispose-frame-sub-
;; caches!`. Because `make-derived-value` is a plain IDeref reify whose
;; reaction never auto-disposes (no IWatchable, no published :adapter/dispose!
;; hook on CLJS), the `{:reaction … :ref-count n}` CACHE ENTRY — which lives
;; on the FRAME, not the reaction — survived a dispose/reinstall cycle. A
;; later subscribe could then read the stale cached value instead of
;; recomputing from fresh state, breaking process isolation.
;;
;; The fix routes `dispose-adapter!` through the CLJC-safe shared helper
;; `re-frame.subs.cache/clear-all-frame-sub-caches!`, the externally-visible
;; counterpart of the spine walk. This test materializes a real frame
;; sub-cache entry (with a ref-count), disposes the adapter, asserts the
;; sub-cache is empty + the ref-count is gone, and verifies a re-subscribe
;; after reinstall recomputes from the CURRENT app-db rather than serving a
;; stale slot. Runs on both the JVM (`clojure -M:test`) and — by virtue of
;; this ns also ending in `-test` — has a `-cljs-test` companion at
;; `test_react_dispose_sub_cache_cljs_test.cljc` that exercises the same
;; contract under `npm run test:cljs`.

(defn- default-sub-cache-keys
  "The set of cache-keys currently materialised in the :rf/default frame's
  per-frame sub-cache. Empty when the frame is absent."
  []
  (if-let [cache (:sub-cache (frame/frame :rf/default))]
    (set (keys @cache))
    #{}))

(defn- default-entry-ref-count
  "The :ref-count on the :rf/default frame's sub-cache slot for `query-v`,
  or nil if the slot is absent."
  [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :ref-count]))

(deftest dispose-adapter-clears-frame-sub-cache-no-stale-cross-lifecycle-reads
  (testing "dispose-adapter! clears every live frame's sub-cache entries +
            ref-counts (the rf2-ghfkkk High finding): a materialised slot does
            NOT survive a dispose/reinstall cycle, and a re-subscribe after
            reinstall recomputes from the CURRENT app-db — no stale read."
    ;; Start from a clean frame registry so prior tests in this ns can't leak
    ;; a frame/cache in. The :each fixture installed test-react; ensure the
    ;; :rf/default frame exists under it.
    (reset! frame/frames {})
    (frame/ensure-default-frame!)
    (rf/reg-event ::seed (fn [{:keys [db]} [_ v]] {:db {:n v}}))
    (rf/reg-sub ::n (fn [db _] (:n db)))
    (try
      ;; Seed app-db = {:n 1} and materialise a sub-cache entry by subscribing.
      ;; Both the dispatch and the subscribe pin `:rf/default` explicitly: per
      ;; EP-0002 there is no ambient `:rf/default` floor, so the AMBIENT
      ;; 1-arity form (no `:frame` opt) would resolve a render-time frame
      ;; context this headless test does not establish and raise
      ;; `:rf.error/no-frame-context`. Use the explicit-frame 2-arity opts
      ;; form (and `subs/subscribe` below) which carries the target frame
      ;; directly — an explicit `:frame` opt always wins over ambient scope
      ;; (Spec 002 §Frame target resolution), macro or fn-form alike.
      (rf/dispatch-sync [::seed 1] {:frame :rf/default})
      (let [r (subs/subscribe [::n] {:frame :rf/default})]
        (is (= 1 @r) "sub reads the seeded value")
        (is (contains? (default-sub-cache-keys) [::n])
            "subscribe materialised a cache slot on the frame")
        (is (= 1 (default-entry-ref-count [::n]))
            "the slot carries a ref-count of 1"))

      ;; Dispose the adapter. Pre-fix this left the [::n] slot + its ref-count
      ;; in the frame's sub-cache (the reaction never auto-disposes, so the
      ;; entry survived). The fix walks every live frame's sub-cache and resets
      ;; it.
      (substrate-adapter/dispose-adapter!)
      (is (empty? (default-sub-cache-keys))
          "dispose-adapter! cleared the frame's sub-cache — no surviving slot")
      (is (nil? (default-entry-ref-count [::n]))
          "the stale ref-count is gone — not carried across the dispose")

      ;; Reinstall and change the app-db. A re-subscribe MUST recompute from
      ;; the new state (98 → 99 below), not serve a stale cached 1. If the
      ;; pre-fix slot had survived, the re-subscribe would have aliased the old
      ;; reaction and the isolation guarantee would be broken.
      (substrate-adapter/install-adapter! test-react/adapter)
      (rf/dispatch-sync [::seed 99] {:frame :rf/default})
      (let [r2 (subs/subscribe [::n] {:frame :rf/default})]
        (is (= 99 @r2)
            "re-subscribe after reinstall recomputed from the CURRENT app-db")
        (is (= 1 (default-entry-ref-count [::n]))
            "the rebuilt slot is a FRESH cache miss (ref-count back to 1), not
             a resurrected stale entry")
        (subs/unsubscribe :rf/default [::n]))
      (finally
        ;; Clean up: drop the test registrations + the frame so the ns's other
        ;; tests (and the :each fixture's outer dispose) start clean. The
        ;; adapter is left installed for the fixture's dispose to tear down.
        (rf/clear-sub ::n)
        (reset! frame/frames {})))))

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

;; ----------------------------------------------------------------------------
;; C. Harness-contract guards (rf2-ynjts.6)
;; ----------------------------------------------------------------------------
;;
;; The uix/helix browser suites consume this harness's PUBLIC surface
;; (`mount!` / `trigger-update!` / `unmount!` / `mount-child!` / the substrate
;; `:render` entry point / `render-to-string`). Each of the following pins a
;; guard or two-entry-point behaviour the harness docstrings PROMISE but the
;; A/B layers above never exercised. A silent regression in any of these would
;; weaken the downstream suites without tripping their own assertions — e.g. a
;; `trigger-update!` that no longer throws after teardown would let a test
;; re-render a dead mount and read a stale tree; a `mount!` that dropped its
;; installed-adapter guard would silently mount under whatever adapter the
;; suite forgot to install. These are the harness's own contract.

;; ---- trigger-update! after unmount throws ---------------------------------

(deftest trigger-update-after-unmount-throws
  (testing "trigger-update! on an already-unmounted mount throws
            :rf.error/update-after-unmount — you cannot re-render a dead mount.
            The lifecycle log is untouched (no stray :render / :did-update),
            so the throw is a true short-circuit, not a render-then-throw."
    (let [mount (test-react/mount! [:div "v1"])
          _     (test-react/unmount! mount)
          log-before (test-react/lifecycle-log mount)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/update-after-unmount"
            (test-react/trigger-update! mount [:div "v2"]))
          "trigger-update! refuses an unmounted mount")
      (is (= log-before (test-react/lifecycle-log mount))
          "no :render / :did-update leaked onto the log — the guard short-circuited before run-render!"))))

;; ---- unmount! is idempotent ------------------------------------------------

(deftest unmount-is-idempotent
  (testing "a second unmount! on an already-unmounted mount is a silent no-op:
            it does NOT throw, and records no second :will-unmount entry (so a
            double-teardown in downstream code can't double-count or corrupt
            the log)"
    (let [mount (test-react/mount! [:div "once"])]
      (test-react/unmount! mount)
      (is (= 1 (phase-count mount :will-unmount))
          "first unmount recorded exactly one :will-unmount")
      ;; Second call must neither throw nor append a second :will-unmount.
      (is (nil? (test-react/unmount! mount))
          "second unmount! returns nil without throwing")
      (is (= 1 (phase-count mount :will-unmount))
          "second unmount! recorded NO additional :will-unmount — idempotent")
      (is (zero? (count (test-react/mounted-roots)))
          "the forest stays empty across the redundant teardown"))))

;; ---- mount-child! outside a render body throws ----------------------------

(deftest mount-child-outside-render-body-throws
  (testing "mount-child! called with no render in flight (*rendering-mount* nil)
            throws :rf.error/mount-child-outside-render — a child needs a parent
            render to attach to. Guards the recursive-child seam against a stray
            top-level call that would otherwise produce an unparented mount."
    (is (false? (test-react/rendering?))
        "precondition: no render in flight at the test top level")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/mount-child-outside-render"
          (test-react/mount-child! [:span "orphan"]))
        "mount-child! demands an in-flight render body")
    (is (zero? (count (test-react/mounted-roots)))
        "the failed mount-child! left nothing in the forest")))

;; ---- mount! under the wrong installed adapter throws ----------------------

(deftest mount-under-wrong-adapter-throws
  (testing "mount! throws :rf.error/test-react-not-installed when a DIFFERENT
            adapter is installed — the guard prevents a test from driving the
            simulator against a foreign adapter's container/render fns. We swap
            the install slot to a sentinel non-test-react adapter, assert the
            throw, then restore so the :each fixture's dispose finds the
            expected adapter."
    (let [sentinel-adapter {:kind             :rf.adapter/plain-atom
                            :dispose-adapter! (fn [] nil)}]
      ;; Tear down the fixture-installed test-react adapter and seat the
      ;; sentinel in its place.
      (substrate-adapter/dispose-adapter!)
      (substrate-adapter/install-adapter! sentinel-adapter)
      (try
        (is (thrown-with-msg?
              #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
              #":rf.error/test-react-not-installed"
              (test-react/mount! [:div "nope"]))
            "mount! refuses to run when test-react is not the installed adapter")
        (finally
          ;; Restore the test-react adapter the :each fixture expects to dispose.
          (substrate-adapter/dispose-adapter!)
          (substrate-adapter/install-adapter! test-react/adapter))))))

;; ---- mount! under a COPIED test-react adapter map succeeds (rf2-dkl5z1) ----

(deftest mount-under-copied-test-react-map-succeeds
  (testing "mount! ACCEPTS a copied / wrapped Test-React adapter map — the
            installed-adapter guard is stable-token (same-adapter?), not raw
            object identity, so an `assoc`'d instrumentation copy (distinct
            object, same canonical :rf.adapter/test-react :kind) mounts
            normally. Mirrors the wrong-adapter test's swap-and-restore, but
            asserts the POSITIVE case the identity guard wrongly rejected
            pre-fix (rf2-dkl5z1)."
    (let [copied (assoc test-react/adapter :rf.test/instrumentation-wrapper true)]
      ;; Tear down the fixture-installed canonical map and seat the copy.
      (substrate-adapter/dispose-adapter!)
      (substrate-adapter/install-adapter! copied)
      (try
        (is (false? (identical? test-react/adapter (substrate-adapter/current-adapter-spec)))
            "precondition: the installed copy is NOT identical to the canonical map")
        (is (= :rf.adapter/test-react (substrate-adapter/current-adapter))
            "precondition: the copy preserves the canonical :kind token")
        (let [mount (test-react/mount! [:div "via-copied-map"])]
          (is (some? mount)
              "mount! returned a MountedComponent record under the copied map")
          (is (= [:constructor :render :did-mount]
                 (mapv :phase (test-react/lifecycle-log mount)))
              "the copied map drove a normal mount lifecycle (no test-react-not-installed throw)")
          (test-react/unmount! mount))
        (finally
          ;; Restore the canonical map the :each fixture expects to dispose.
          (substrate-adapter/dispose-adapter!)
          (substrate-adapter/install-adapter! test-react/adapter))))))

;; ---- render-to-string with no emitter bound throws ------------------------

(deftest render-to-string-without-emitter-throws
  (testing "render-to-string with NO hiccup emitter bound throws
            :rf.error/no-hiccup-emitter-bound — the harness ships no built-in
            emitter, so a test that needs HTML must install one. We force the
            emitter cell to nil (it may carry a leftover SSR chain install) and
            assert the throw, then leave it nil for downstream tests."
    (test-react/set-hiccup-emitter! nil)
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/no-hiccup-emitter-bound"
          (substrate-adapter/render-to-string [:div "x"] nil))
        "no emitter bound → render-to-string throws the documented error")))

;; ---- the substrate :render entry point returns a working unmount thunk ----

(deftest substrate-render-entry-point-returns-working-thunk
  (testing "the substrate contract's :render fn (the path the runtime / browser
            suites actually drive, distinct from the public mount! driver)
            returns a thunk that, when called, tears the mount down. mount! and
            :render share the internal mount-tree! seam; :render discards the
            record and hands back ONLY the thunk. Pinning this proves the
            substrate-facing surface the uix/helix adapters route through stays
            a live unmount handle, not just the inspection-friendly mount!."
    (let [thunk (substrate-adapter/render [:div "via-render"] nil nil)]
      (is (fn? thunk)
          ":render returns a callable unmount thunk")
      (is (= 1 (count (test-react/mounted-roots)))
          "the :render entry point mounted exactly one root")
      (thunk)
      (is (zero? (count (test-react/mounted-roots)))
          "calling the returned thunk tore that root down")
      ;; The thunk is idempotent too — same contract as unmount!.
      (is (nil? (thunk))
          "second thunk call is a silent no-op"))))

;; ---- deep cascade tears down leaf-upward ----------------------------------

(deftest deep-cascade-tears-down-leaf-upward
  (testing "a parent → child → grandchild tree unmounts leaf-upward when the
            root unmounts: the grandchild's :will-unmount fires no later than
            the child's, which fires no later than the parent's. The A' layer
            proved one level of children-first teardown; this pins the
            documented 'deep tree unwinds leaf-upward' invariant across two
            levels — the recursive cascade real component trees rely on."
    (let [grandchild-ref (atom nil)
          child-ref      (atom nil)
          parent (test-react/mount!
                   {:rf/component
                    (fn [_parent]
                      (reset! child-ref
                              (test-react/mount-child!
                                {:rf/component
                                 (fn [_child]
                                   (reset! grandchild-ref
                                           (test-react/mount-child! [:span "leaf"])))})))})]
      (is (= 3 (count (test-react/mounted-roots)))
          "parent + child + grandchild all live")
      (is (= [@child-ref] (test-react/children parent))
          "child recorded under parent")
      (is (= [@grandchild-ref] (test-react/children @child-ref))
          "grandchild recorded under child — the tree is two levels deep")
      (test-react/unmount! parent)
      (is (zero? (count (test-react/mounted-roots)))
          "the whole forest drained — no orphaned descendant")
      (let [gc-at     (phase-first-at @grandchild-ref :will-unmount)
            child-at  (phase-first-at @child-ref :will-unmount)
            parent-at (phase-first-at parent :will-unmount)]
        (is (and gc-at child-at parent-at)
            "every level recorded a :will-unmount during the cascade")
        (is (<= gc-at child-at parent-at)
            "teardown order is grandchild ≤ child ≤ parent — leaf-upward unwind")))))
