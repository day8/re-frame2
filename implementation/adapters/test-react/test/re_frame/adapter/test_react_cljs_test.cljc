(ns re-frame.adapter.test-react-cljs-test
  "Tests for the Test-React adapter.

  Three layers:

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
          hand-fabricated in-flight render state — because the unmount happens
          while React (the global render depth) is rendering somewhere.

       2. Unbalanced subscribe/dispose (mount/unmount ref-count). A faulty
          teardown disposes only some of the resources it acquired on mount;
          the symptom is a non-zero live-mount / ref count after teardown.

       3. Double-render. A redundant re-render fires where exactly one was
          expected; the symptom is an extra :render (and :did-update) entry in
          the lifecycle log — the render counter is higher than the contract.

  C. Harness-contract guards (rf2-ynjts.6) — self-tests of this local
     harness's OWN documented public surface. Each pins a guard /
     error / two-entry-point behaviour the harness PROMISES in its docstrings
     but the A/B layers above never exercised: trigger-update! / unmount!
     after teardown, mount-child! outside a render body, mount! under the
     wrong installed adapter, the no-emitter render-to-string throw, unmount!
     idempotency, the substrate :render entry point returning a working
     unmount thunk, and deep (grandchild) leaf-upward cascade ordering."
  (:require [re-frame.adapter.test-react :as rf.adapter.test-react]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])))

;; ---- fixture ---------------------------------------------------------------

(defn- install-test-react! [t]
  ;; Clean install/dispose around each test. `install-adapter!` throws
  ;; if an adapter is still installed; the test-only seam wipes the
  ;; lifecycle state so each case starts cold.
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)
  (try
    (t)
    (finally
      (rf.substrate.adapter/dispose-adapter!))))

(use-fixtures :each install-test-react!)

;; ---- lifecycle-log query helpers -------------------------------------------
;; The assertions below repeatedly interrogate a mount's lifecycle log by
;; phase — counting how many times a phase fired (render-count contracts) and
;; reading the first `:seq` order-key for a phase (teardown-ordering checks).
;; These two helpers name those queries so each assertion reads as the
;; property under test rather than a `->>`/filter thread.

(defn- phase-count
  "How many `phase` entries the mount's lifecycle log holds."
  [mount phase]
  (->> (rf.adapter.test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       count))

(defn- phase-first-seq
  "The monotonic `:seq` order-key of the first `phase` entry in the mount's
  lifecycle log, or nil if the phase never fired. `:seq` increments once per
  logged phase across the whole adapter, so a strict `<` over two phases' seqs
  reflects their REAL firing order — unlike the wall-clock `:at` this replaced,
  which collapsed to equal integers for a sub-millisecond teardown cascade and
  made the ORDER check vacuous (it could not fail on a reversed teardown)."
  [mount phase]
  (->> (rf.adapter.test-react/lifecycle-log mount)
       (filter (comp #{phase} :phase))
       first
       :seq))

;; ----------------------------------------------------------------------------
;; A. Demonstration scenarios
;; ----------------------------------------------------------------------------

;; ---- happy-path lifecycle ordering ----------------------------------------

(deftest happy-path-lifecycle-ordering
  (testing "constructor → render → did-mount → did-update → will-unmount"
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
      (rf.adapter.test-react/trigger-update! mount [:div "v2"])
      (rf.adapter.test-react/unmount! mount)
      (is (= [:constructor :render :did-mount :render :did-update :will-unmount]
             (mapv :phase (rf.adapter.test-react/lifecycle-log mount)))
          "the simulated lifecycle records constructor, mount-render+did-mount, update-render+did-update, will-unmount"))))

(deftest lifecycle-log-entries-carry-exactly-phase-and-seq
  (testing "every lifecycle entry is EXACTLY #{:phase :seq} — the shape
            `lifecycle-log`'s docstring publishes as its contract. `log-phase!`
            is fixed-arity precisely so nothing can smuggle an extra key in
            (rf2-6r9j.83); this pins that, and would fail if a stray field —
            or a merge that clobbered :phase / :seq — ever reappeared."
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
      (rf.adapter.test-react/trigger-update! mount [:div "v2"])
      (rf.adapter.test-react/unmount! mount)
      (let [log (rf.adapter.test-react/lifecycle-log mount)]
        (is (seq log) "precondition: the log recorded entries at all")
        (is (= #{#{:phase :seq}}
               (into #{} (map (comp set keys)) log))
            "every entry's key set is exactly #{:phase :seq} — no extras arm")
        (is (every? (comp keyword? :phase) log)
            ":phase is always a keyword")
        (is (apply < (mapv :seq log))
            ":seq is strictly increasing across the entries, in firing order")))))

(deftest mounted-components-and-current-render-tree
  (testing "mounted-components tracks live mounts; current-render-tree returns the latest hiccup"
    (let [mount (rf.adapter.test-react/mount! [:div "initial"])]
      (is (= 1 (count (rf.adapter.test-react/mounted-components))))
      (is (= [:div "initial"] (rf.adapter.test-react/current-render-tree mount)))
      (rf.adapter.test-react/trigger-update! mount [:div "updated"])
      (is (= [:div "updated"] (rf.adapter.test-react/current-render-tree mount)))
      (rf.adapter.test-react/unmount! mount)
      ;; Exercise the compatibility alias after canonical-name coverage above.
      #_{:clj-kondo/ignore [:deprecated-var]}
      (let [legacy-mounted-roots (deref #'rf.adapter.test-react/mounted-roots)]
        (is (and (true? (:deprecated (meta #'rf.adapter.test-react/mounted-roots)))
                 (zero? (count (legacy-mounted-roots))))))
      (is (nil? (rf.adapter.test-react/current-render-tree mount))))))

(deftest unmount-actually-evicts-from-the-raw-active-set
  (testing "unmount! removes the mount from the RAW active-mounts set, not just
            flips its :mounted? flag. mounted-components filters by :mounted?, so it
            reports zero even if the dead record were left in the backing set —
            this pins the eviction directly. Regression for the
            base/mount record-identity disj mismatch: the unmount thunk must
            disj the exact record conj'd, or the dead record leaks for the
            adapter's lifetime (defrecord equality includes :unmount-fn, so the
            pre-assoc skeleton would not match)."
    (let [active @#'rf.adapter.test-react/active-mounts]
      (is (zero? (count @active))
          "precondition: raw active set starts empty")
      (let [mount (rf.adapter.test-react/mount! [:div "live"])]
        (is (= 1 (count @active))
            "mount registered exactly one record in the raw set")
        (rf.adapter.test-react/unmount! mount)
        (is (zero? (count @active))
            "unmount! EVICTED the record from the raw set — no leaked dead record"))
      ;; Many mount/unmount cycles must not grow the raw set (the leak symptom
      ;; was monotonic growth masked by the :mounted? filter).
      (dotimes [_ 25]
        (rf.adapter.test-react/unmount! (rf.adapter.test-react/mount! [:div "churn"])))
      (is (zero? (count @active))
          "25 mount/unmount cycles left the raw active set empty — no accumulation"))))

;; ---- adapter-disposal drains stranded mounts ------------------------------

(deftest dispose-adapter-drains-stranded-mounts
  (testing "dispose-adapter! drains mounts the test forgot to unmount; log carries :forced-teardown breadcrumb"
    (let [mount (rf.adapter.test-react/mount! [:div "leaked"])]
      ;; The :each fixture's `dispose-adapter!` will fire on the way
      ;; out; we invoke it explicitly here so the assertions land in
      ;; the test body rather than the fixture.
      (rf.substrate.adapter/dispose-adapter!)
      (is (not @(:mounted? mount))
          ":mounted? flips to false on forced teardown")
      (is (some #{:forced-teardown} (mapv :phase (rf.adapter.test-react/lifecycle-log mount)))
          ":forced-teardown phase records the drain so tests can spot leaked mounts")
      ;; Re-install so the fixture's outer dispose call below is a no-op.
      (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))

;; ---- mount! returns the exact record it created ---------------------------

(deftest mount-returns-its-own-record-under-many-live-mounts
  (testing "mount! returns the record it created — and its unmount thunk tears
            down THAT mount — even with many other mounts live at once. The
            record is threaded directly through the internal mount seam, so
            there is no scan/ordering heuristic to alias the wrong mount."
    ;; Mount a dozen components without unmounting any (they stay live), then
    ;; mount one more with a unique sentinel render-tree.
    (let [earlier (doall (for [i (range 12)]
                           (rf.adapter.test-react/mount! [:div (str "v" i)])))
          latest  (rf.adapter.test-react/mount! [:div "SENTINEL-LATEST"])]
      (is (= [:div "SENTINEL-LATEST"] (rf.adapter.test-react/current-render-tree latest))
          "mount! returned the record for the mount it just created")
      (is (= 13 (count (rf.adapter.test-react/mounted-components)))
          "all thirteen mounts are live")
      ;; The unmount thunk on the returned record tears down the SENTINEL
      ;; mount specifically — not a neighbour.
      (rf.adapter.test-react/unmount! latest)
      (is (nil? (rf.adapter.test-react/current-render-tree latest))
          "unmounting the returned record tore down the correct (latest) mount")
      (is (= 12 (count (rf.adapter.test-react/mounted-components)))
          "exactly one mount torn down; the other twelve remain live")
      ;; Drain the 12 still-live earlier mounts so the fixture's
      ;; dispose-adapter! has nothing surprising to forcibly tear down.
      (doseq [m earlier] (rf.adapter.test-react/unmount! m)))))

;; ---- render-to-string survives a dispose/reinstall cycle ------------------

(deftest render-to-string-survives-dispose-reinstall
  (testing "render-to-string stays ARMED across a dispose/reinstall cycle.
            Two independent forces keep it armed: test-react's dispose
            deliberately does NOT clear its emitter atom (re-derivable
            infrastructure, not a host resource), AND install-adapter! replays
            the durable authoritative SSR emitter (:ssr/current-hiccup-emitter,
            rf2-vxgfnd.204) whenever re-frame.ssr is loaded. Either way the
            re-installed generation renders rather than throwing
            :rf.error/no-hiccup-emitter-bound.

            The emitter that WINS after reinstall is classpath-dependent, so
            the assertion pins armed-ness (the tree still renders) rather than a
            specific emitter's output: on the all-artefact :node-test classpath
            re-frame.ssr is loaded, so the .204 replay overrides the transient
            stub with the real SSR emitter (real HTML); on the standalone
            test-react JVM run (core + test-quiet only, no re-frame.ssr) the
            durable slot is unset, the replay no-ops, and test-react's own
            un-cleared stub survives."
    (rf.adapter.test-react/set-hiccup-emitter! (fn [tree _opts] (str "HTML:" (pr-str tree))))
    (try
      (is (= "HTML:[:div \"a\"]"
             (rf.substrate.adapter/render-to-string [:div "a"] nil))
          "the transient emitter is bound before the dispose cycle")
      ;; Dispose + reinstall (the standard fixture shape).
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter)
      (let [html (rf.substrate.adapter/render-to-string [:div "b"] nil)]
        (is (and (string? html) (re-find #"b" html))
            "render-to-string still armed after dispose + reinstall — it renders
             the tree and never throws :rf.error/no-hiccup-emitter-bound"))
      (finally
        (rf.adapter.test-react/set-hiccup-emitter! nil)))))

;; ----------------------------------------------------------------------------
;; A'. Recursive child mounting — the structural seam the regressions ride on
;; ----------------------------------------------------------------------------

(deftest child-mounts-recurse-through-their-own-lifecycle
  (testing "a parent's render body mounts a child via mount-child!; the child
            runs its own constructor → render → did-mount, is tracked as a
            child of the parent, and is torn down children-first when the
            parent unmounts"
    (let [child-ref (atom nil)
          parent    (rf.adapter.test-react/mount!
                      {:rf/component
                       (fn [_parent]
                         (reset! child-ref
                                 (rf.adapter.test-react/mount-child! [:span "child"])))})]
      ;; The child ran its full mount lifecycle.
      (is (= [:constructor :render :did-mount]
             (mapv :phase (rf.adapter.test-react/lifecycle-log @child-ref)))
          "child recursed through its own class-3 mount lifecycle")
      ;; The forest holds both mounts; the parent records the child.
      (is (= 2 (count (rf.adapter.test-react/mounted-components)))
          "parent + child are both live in the forest")
      ;; Exercise the compatibility alias; later checks use mounted-children.
      #_{:clj-kondo/ignore [:deprecated-var]}
      (let [legacy-children (deref #'rf.adapter.test-react/children)]
        (is (and (true? (:deprecated (meta #'rf.adapter.test-react/children)))
                 (= [@child-ref] (legacy-children parent)))
            "the deprecated alias remains callable and returns the live child"))
      ;; Unmounting the parent cascades to the child, children-first.
      (rf.adapter.test-react/unmount! parent)
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "parent unmount cascaded to the child — nothing leaks")
      (is (some #{:will-unmount} (mapv :phase (rf.adapter.test-react/lifecycle-log @child-ref)))
          "the child saw its own :will-unmount during the cascade")
      (let [child-unmount-seq  (phase-first-seq @child-ref :will-unmount)
            parent-unmount-seq (phase-first-seq parent :will-unmount)]
        (is (< child-unmount-seq parent-unmount-seq)
            "child tears down STRICTLY before its parent — monotonic order-key,
             so a reversed (parent-first) teardown FAILS here (the old <=-on-ms
             check passed vacuously because sub-ms timestamps collapsed to equal)")))))

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
;; unmount while the global render depth is non-zero — no test fabricates
;; in-flight render state by hand. That converts the headline capability from
;; "guard logic verified" to "bug condition reproduced."

(deftest organic-sync-unmount-during-render-rf2-4l7t2
  (testing "a panel-host that synchronously unmounts the previous panel's root
            from inside its switch-panel re-render trips
            :rf.error/sync-unmount-during-render ORGANICALLY (no fabricated
            in-flight state) — the rf2-4l7t2 bug condition, reproduced"
    ;; Mount panel A as a standalone root (the host's current child).
    (let [panel-a (rf.adapter.test-react/mount! [:div.panel "A"])]
      (is (= 1 (count (rf.adapter.test-react/mounted-components))))
      ;; The host re-renders to switch to panel B. The BUGGY render body
      ;; synchronously unmounts panel A's root mid-render (the senbl pattern
      ;; before the microtask-defer fix).
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/sync-unmount-during-render"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [_host]
                 ;; BUG: synchronous unmount of a separate root during render.
                 (rf.adapter.test-react/unmount! panel-a))}))
          "the guard fires organically: the unmount happens while the host's
           render is in flight, which is React's actual guard condition")
      ;; The host's render body THREW, yet run-render!'s `finally` decremented
      ;; the global render-depth back to zero on unwind. Assert that invariant
      ;; DIRECTLY: without the finally-restore a leaked non-zero depth would
      ;; poison every LATER test — the next unmount! anywhere would spuriously
      ;; trip :rf.error/sync-unmount-during-render. Previously this was only
      ;; covered indirectly (the trailing unmount! below would throw a
      ;; confusing uncaught ExceptionInfo if depth leaked, and that coverage
      ;; vanished if the trailing unmount was ever refactored away).
      (is (false? (rf.adapter.test-react/rendering?))
          "run-render!'s finally restored render-depth to zero even though the
           render body threw — no leaked in-flight state poisons later tests")
      ;; mount-tree! registers into active-mounts only AFTER run-render!
      ;; returns, so a throw mid-render never registers the host — the
      ;; partially-constructed root cannot leak into the live forest.
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "only panel A is live — the throwing host never registered, no leak")
      ;; Panel A is still mounted — the guard short-circuited the unmount
      ;; before it could tear the root down (matching React: it refuses the
      ;; synchronous unmount rather than racing).
      (is (true? @(:mounted? panel-a))
          "the guard refused the synchronous unmount — panel A was NOT torn down")
      (rf.adapter.test-react/unmount! panel-a))))

(deftest deferred-unmount-after-render-is-safe-rf2-4l7t2-fix
  (testing "the rf2-4l7t2 FIX shape: unmounting the previous panel's root AFTER
            the host's render has completed (the microtask-defer) does not trip
            the guard — proves the guard discriminates in-render from
            after-render, so it is not a blunt always-throw"
    (let [panel-a (rf.adapter.test-react/mount! [:div.panel "A"])
          ;; Host re-renders to switch to B WITHOUT unmounting A mid-render.
          host    (rf.adapter.test-react/mount! {:rf/component (fn [_host] :switched-to-B)})]
      (is (= 2 (count (rf.adapter.test-react/mounted-components))))
      ;; Now that no render is in flight, unmounting panel A is safe (this is
      ;; what queueMicrotask buys you in the production fix).
      (is (false? (rf.adapter.test-react/rendering?))
          "no render in flight after the host's render completed")
      (rf.adapter.test-react/unmount! panel-a)        ; must NOT throw
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "deferred unmount tore down panel A cleanly")
      (rf.adapter.test-react/unmount! host))))

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
          host (rf.adapter.test-react/mount!
                 {:rf/component
                  (fn [_host]
                    (rf.adapter.test-react/mount-child! [:section "tracked-child"])
                    (reset! orphan-ref (rf.adapter.test-react/mount! [:section "ORPHAN"])))})]
      (is (= 3 (count (rf.adapter.test-react/mounted-components)))
          "host + tracked child + orphaned root are all live")
      (is (= 1 (count (rf.adapter.test-react/mounted-children host)))
          "the host only KNOWS about the one tracked child (the orphan is untracked)")
      ;; Correct-looking teardown: unmount the host. Its cascade tears down the
      ;; tracked child — but cannot reach the untracked orphan.
      (rf.adapter.test-react/unmount! host)
      ;; Symptom: the orphan is still mounted; the count never returned to zero.
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "the orphaned root leaks — subscribe/dispose imbalance detected")
      (is (true? @(:mounted? @orphan-ref))
          "the specific leaked root is the orphan the host forgot to track")
      ;; Clean up the orphan so the fixture's drain has nothing to forcibly tear.
      (rf.adapter.test-react/unmount! @orphan-ref))))

(deftest balanced-subscribe-dispose-returns-to-zero
  (testing "the CORRECT counterpart: a parent that lets the cascade tear all
            children down returns the live-mount count to zero and empties its
            tracked-child set — the green state a regression in B.2 would break"
    (let [parent (rf.adapter.test-react/mount!
                   {:rf/component
                    (fn [_parent]
                      (rf.adapter.test-react/mount-child! [:li "a"])
                      (rf.adapter.test-react/mount-child! [:li "b"]))})]
      (is (= 3 (count (rf.adapter.test-react/mounted-components))))
      (is (= 2 (count (rf.adapter.test-react/mounted-children parent)))
          "both children are tracked under the parent before teardown")
      ;; Correct teardown: just unmount the parent; the cascade tears every
      ;; child down leaf-first — no per-resource bookkeeping needed.
      (rf.adapter.test-react/unmount! parent)
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "cascade tore every child down — nothing leaks")
      ;; `mounted-children` returns only STILL-MOUNTED children, so an empty result
      ;; after teardown is a REAL framework property: the cascade set every
      ;; tracked child's mounted? false. A child the cascade failed to reach
      ;; would still read as mounted and show up here.
      (is (empty? (rf.adapter.test-react/mounted-children parent))
          "the parent's live-child set drained — the cascade reached every child"))))

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
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
      ;; CONTRACT: one logical change → one update render.
      ;; BUG: the update path fires trigger-update! twice (the redundant
      ;; second render real double-render bugs produce).
      (rf.adapter.test-react/trigger-update! mount [:div "v2"])
      (rf.adapter.test-react/trigger-update! mount [:div "v2"]) ; redundant re-render
      (let [renders (phase-count mount :render)
            updates (phase-count mount :did-update)]
        ;; Mount render + two update renders = 3.
        (is (= 3 renders)
            "render count is 3 (1 mount + 2 update) — the doubled update render is visible")
        (is (= 2 updates)
            "two :did-update entries expose the redundant second render"))
      (rf.adapter.test-react/unmount! mount))))

(deftest single-render-is-the-balanced-baseline
  (testing "the CORRECT counterpart: one logical change → exactly one update
            render (one :did-update). A double-render regression breaks this."
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
      (rf.adapter.test-react/trigger-update! mount [:div "v2"])
      (is (= 1 (phase-count mount :did-update))
          "exactly one update render for one logical change")
      (rf.adapter.test-react/unmount! mount))))

;; ----------------------------------------------------------------------------
;; B.4 — Transactional failed initial mount (rf2-3fc89f.2)
;; ----------------------------------------------------------------------------
;;
;; Bug class: a parent's render body mounts a child (registered in the live
;; forest) and then THROWS. Because `mount-tree!` registered the child during
;; the render but never reaches the parent's own registration, a naive
;; implementation leaves the child mounted yet unreachable — `mount!` returned
;; no handle, so nothing can tear it down until whole-adapter disposal. That
;; phantom live mount is exactly the lifecycle imbalance Test-React exists to
;; catch, so it corrupts the harness's core purpose.
;;
;; The fix makes initial mount TRANSACTIONAL: a failed render rolls back every
;; child it speculatively mounted (nested descendants too), removing them from
;; the live forest before the ORIGINAL render exception is rethrown — without
;; masking that exception and without weakening the sync-unmount-during-render
;; guard (B.1). Each of the following pins one property the fix guarantees; on
;; the pre-fix code every leak assertion FAILS (the child / subtree survives).

(deftest failed-initial-render-rolls-back-mounted-child-rf2-3fc89f2
  (testing "a parent render that mounts a child and then throws is
            transactional: the ORIGINAL render exception escapes unmasked,
            no render is left in flight, the live forest is empty, and the
            speculatively-mounted child is torn down (not a phantom live mount)"
    (let [child-ref (atom nil)]
      ;; The render body mounts a child (registering it in the live forest),
      ;; captures its handle, then throws a distinctively-tagged exception.
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-parent-render"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [_parent]
                 (reset! child-ref (rf.adapter.test-react/mount-child! [:span "child"]))
                 (throw (ex-info "boom-parent-render" {})))}))
          "the ORIGINAL render exception propagates — rollback never masks it")
      (is (false? (rf.adapter.test-react/rendering?))
          "run-render!'s finally restored render-depth to zero on unwind")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the live forest is empty — the child mounted before the throw was
           rolled back, not leaked as a phantom live mount (this FAILS pre-fix)")
      (is (false? @(:mounted? @child-ref))
          "the child record itself was torn down (mounted? flipped false)")
      (is (= 1 (phase-count @child-ref :forced-teardown))
          "the rollback recorded a :forced-teardown on the child — teardown
           logging is preserved, not a silent drop")
      (is (nil? (rf.adapter.test-react/current-render-tree @child-ref))
          "the child's render tree was cleared by the forced teardown"))))

(deftest failed-initial-render-rolls-back-nested-subtree-rf2-3fc89f2
  (testing "the rollback reaches NESTED descendants: parent → child →
            grandchild, then the parent throws. The whole speculative subtree
            (child AND grandchild) is removed, leaf-upward, not just the direct
            child"
    (let [child-ref      (atom nil)
          grandchild-ref (atom nil)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-nested-parent"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [_parent]
                 (reset! child-ref
                         (rf.adapter.test-react/mount-child!
                           {:rf/component
                            (fn [_child]
                              (reset! grandchild-ref
                                      (rf.adapter.test-react/mount-child! [:span "leaf"])))}))
                 (throw (ex-info "boom-nested-parent" {})))}))
          "the original render exception escapes")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "no descendant leaks — child AND grandchild both left the forest
           (pre-fix this is 2)")
      (is (and (false? @(:mounted? @child-ref))
               (false? @(:mounted? @grandchild-ref)))
          "both the direct child and the nested grandchild were torn down")
      (is (and (= 1 (phase-count @child-ref :forced-teardown))
               (= 1 (phase-count @grandchild-ref :forced-teardown)))
          "each rolled-back level recorded exactly one :forced-teardown")
      (let [gc-seq    (phase-first-seq @grandchild-ref :forced-teardown)
            child-seq (phase-first-seq @child-ref :forced-teardown)]
        (is (< gc-seq child-seq)
            "rollback ran leaf-upward: the grandchild tore down STRICTLY before
             the child, mirroring React's children-first teardown order")))))

(deftest failed-child-render-rolls-back-its-own-descendants-rf2-3fc89f2
  (testing "the throw can originate in a nested render: parent mounts child,
            child mounts grandchild then the CHILD throws. The grandchild is
            rolled back and the whole attempt leaves the forest empty — the
            child's own mount-tree! is transactional before its failure
            unwinds through the parent"
    (let [grandchild-ref (atom nil)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-child-render"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [_parent]
                 (rf.adapter.test-react/mount-child!
                   {:rf/component
                    (fn [_child]
                      (reset! grandchild-ref
                              (rf.adapter.test-react/mount-child! [:span "leaf"]))
                      (throw (ex-info "boom-child-render" {})))}))}))
          "the child's render exception propagates out through the parent")
      (is (false? (rf.adapter.test-react/rendering?))
          "every nested run-render! finally unwound render-depth to zero")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the grandchild the failing child mounted was rolled back — no
           orphaned descendant survives the nested failure")
      (is (false? @(:mounted? @grandchild-ref))
          "the grandchild record was torn down by the child's own rollback"))))

(deftest failed-mount-leaves-no-did-mount-and-spares-live-sibling-rf2-3fc89f2
  (testing "a failed initial mount records NO :did-mount for the throwing
            parent, registers nothing in the live forest, and does not disturb
            a separate root that was already live before the failed mount"
    (let [survivor  (rf.adapter.test-react/mount! [:div "survivor"])
          parent-ref (atom nil)
          child-ref  (atom nil)]
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "precondition: exactly the survivor root is live")
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-with-sibling"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [parent]
                 (reset! parent-ref parent)
                 (reset! child-ref (rf.adapter.test-react/mount-child! [:span "child"]))
                 (throw (ex-info "boom-with-sibling" {})))}))
          "the failed mount throws its original exception")
      (is (zero? (phase-count @parent-ref :did-mount))
          "the throwing parent never reached :did-mount — a failed render does
           NOT emit the mount-completed phase")
      (is (= [survivor] (rf.adapter.test-react/mounted-components))
          "the ONLY live root is the pre-existing survivor: the parent never
           registered and the child was rolled back — no hidden leaked record
           remains in the live forest for a later test to trip over")
      (is (true? @(:mounted? survivor))
          "the separate live root was untouched by the failed mount's rollback")
      (is (zero? (phase-count survivor :forced-teardown))
          "the survivor took no forced teardown — rollback is scoped to the
           failed parent's own speculative children")
      (is (false? @(:mounted? @child-ref))
          "the speculative child is torn down, not a manually-cleanable phantom")
      ;; Clean up the survivor so the fixture's drain has nothing to force.
      (rf.adapter.test-react/unmount! survivor))))

(deftest rollback-does-not-mask-guard-and-spares-guard-target-rf2-3fc89f2
  (testing "the transactional rollback COMPOSES with the B.1 guard: a render
            body that mounts a tracked child and then synchronously unmounts a
            SEPARATE live root still trips :rf.error/sync-unmount-during-render
            (rollback does not mask that guard error), the guard's target root
            stays mounted (the guard refused the unmount), and the tracked
            child the same body mounted IS rolled back"
    (let [target    (rf.adapter.test-react/mount! [:div.panel "target"])
          child-ref (atom nil)]
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "precondition: only the guard's target root is live")
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/sync-unmount-during-render"
            (rf.adapter.test-react/mount!
              {:rf/component
               (fn [_host]
                 ;; Mount a tracked child (registers in the forest), THEN trip
                 ;; the sync-unmount-during-render guard on a separate root.
                 (reset! child-ref (rf.adapter.test-react/mount-child! [:span "child"]))
                 (rf.adapter.test-react/unmount! target))}))
          "the guard error — NOT the rollback — is what escapes; rollback does
           not swallow or replace the render body's exception")
      (is (true? @(:mounted? target))
          "the guard refused the synchronous unmount — the target root survives")
      (is (= [target] (rf.adapter.test-react/mounted-components))
          "only the target is live: the host never registered and its tracked
           child was rolled back despite the render failing via the guard")
      (is (false? @(:mounted? @child-ref))
          "the child mounted before the guard tripped was rolled back too")
      (rf.adapter.test-react/unmount! target))))

;; ----------------------------------------------------------------------------
;; B.5 — Failed update unmounts the whole root (rf2-j538f7.1)
;; ----------------------------------------------------------------------------
;;
;; Bug class (the update-path analogue of B.4): a LIVE root re-renders, the
;; update body mounts a child and then THROWS. `run-render!` stores the
;; candidate tree BEFORE invoking the body, so a naive `trigger-update!` left
;; the throwing candidate exposed as the committed `current-render-tree` and
;; left the speculatively-mounted child live and attached — a phantom committed
;; tree plus an extra live mount, exactly the lifecycle imbalance Test-React
;; exists to catch. Unlike the failed INITIAL mount (B.4), the parent here is
;; ALREADY committed, so the initial-mount rollback cannot cover it.
;;
;; The fix honors React 18+'s uncaught-render-error semantics — the same
;; contract `run-render!`'s docstring already documents ("React 18+ unmounts the
;; root"). A failed update UNMOUNTS THE WHOLE LIVE ROOT: the root and its entire
;; child subtree (pre-existing children AND this attempt's speculative ones) are
;; force-torn-down grandchildren-first, evicted from the live forest with their
;; render trees cleared, then the ORIGINAL exception is rethrown and NO
;; `:did-update` fires. Parity is the whole point of the test double — real
;; React tears the root down rather than silently keeping the prior tree. The
;; teardown reuses the SAME `force-teardown-record!` primitive as B.4 and the
;; adapter drain, and preserves the B.1 sync-unmount-during-render guard. Each
;; test pins one property; on the pre-fix code the unmount / no-leak assertions
;; FAIL (candidate committed as the live tree, root + child both survive).

(deftest failed-update-unmounts-the-whole-root-rf2-j538f71
  (testing "a live root whose update body mounts a child then throws is
            UNMOUNTED whole (React-18 uncaught-render semantics): the ORIGINAL
            exception escapes unmasked, the root AND its speculative child leave
            the forest with cleared render trees, and NO :did-update is logged"
    (let [child-ref (atom nil)
          mount     (rf.adapter.test-react/mount! [:div "committed-v1"])]
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "precondition: the root is live on its committed tree")
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-update-body"
            (rf.adapter.test-react/trigger-update!
              mount
              {:rf/component
               (fn [_mount]
                 (reset! child-ref (rf.adapter.test-react/mount-child! [:span "speculative"]))
                 (throw (ex-info "boom-update-body" {})))}))
          "the ORIGINAL update exception propagates — teardown never masks it")
      (is (false? (rf.adapter.test-react/rendering?))
          "run-render!'s finally restored render-depth to zero on unwind")
      (is (false? @(:mounted? mount))
          "the root was UNMOUNTED — a throwing update tears the root down, it is
           NOT left live on a preserved tree (this FAILS pre-fix: root stays live)")
      (is (nil? (rf.adapter.test-react/current-render-tree mount))
          "the root's render tree was CLEARED — the throwing candidate is not
           exposed as committed (pre-fix the candidate leaked as current tree)")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the live forest is empty — root AND its speculative child both left
           (pre-fix this is 2: candidate root + leaked child)")
      (is (false? @(:mounted? @child-ref))
          "the speculative child record was torn down (mounted? flipped false)")
      (is (= 1 (phase-count @child-ref :forced-teardown))
          "the child recorded a :forced-teardown — teardown logging preserved")
      (is (= 1 (phase-count mount :forced-teardown))
          "the ROOT itself recorded a :forced-teardown — the whole root unmount")
      (is (zero? (phase-count mount :did-update))
          "a FAILED update publishes NO :did-update — the update did not commit")
      (is (= [:constructor :render :did-mount :render :forced-teardown]
             (mapv :phase (rf.adapter.test-react/lifecycle-log mount)))
          "the root logged mount lifecycle, the failed update's :render, then a
           :forced-teardown — no :did-update, matching React's unmount-the-root"))))

(deftest failed-update-tears-down-pre-existing-and-speculative-children-rf2-j538f71
  (testing "a live parent that already has a pre-existing child A fails an update
            after mounting a SECOND child B: the whole root unmounts — BOTH A
            (pre-existing) and B (speculative) tear down children-first, not just
            the attempt's own child. Proves cleanup neither strands the new
            child nor spares the old one under uncaught-root semantics"
    (let [child-a-ref (atom nil)
          child-b-ref (atom nil)
          ;; Initial mount whose render body mounts a pre-existing child (A).
          parent      (rf.adapter.test-react/mount!
                        {:rf/component
                         (fn [_parent]
                           (reset! child-a-ref
                                   (rf.adapter.test-react/mount-child! [:span "child-a"])))})]
      (is (= 2 (count (rf.adapter.test-react/mounted-components)))
          "precondition: parent + pre-existing child A are live")
      (is (= [@child-a-ref] (rf.adapter.test-react/mounted-children parent))
          "precondition: A is the parent's only tracked child")
      ;; Update fails after mounting a second child (B).
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-update-with-preexisting"
            (rf.adapter.test-react/trigger-update!
              parent
              {:rf/component
               (fn [_parent]
                 (reset! child-b-ref (rf.adapter.test-react/mount-child! [:span "child-b"]))
                 (throw (ex-info "boom-update-with-preexisting" {})))}))
          "the original update exception propagates")
      (is (false? @(:mounted? parent))
          "the root was unmounted whole")
      (is (nil? (rf.adapter.test-react/current-render-tree parent))
          "the root's render tree was cleared — no candidate survives")
      (is (and (false? @(:mounted? @child-a-ref))
               (false? @(:mounted? @child-b-ref)))
          "BOTH the pre-existing child A AND the speculative child B were torn
           down — the update unmounts the whole subtree (pre-fix A survives with
           the parent, and B leaks)")
      (is (and (= 1 (phase-count @child-a-ref :forced-teardown))
               (= 1 (phase-count @child-b-ref :forced-teardown)))
          "each child recorded exactly one :forced-teardown")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the live forest is empty — parent + both children all gone")
      (let [a-seq      (phase-first-seq @child-a-ref :forced-teardown)
            b-seq      (phase-first-seq @child-b-ref :forced-teardown)
            parent-seq (phase-first-seq parent :forced-teardown)]
        (is (and (< a-seq parent-seq) (< b-seq parent-seq))
            "teardown ran children-first: both children tore down STRICTLY
             before the parent, mirroring React's leaf-upward order")))))

(deftest failed-update-tears-down-nested-subtree-rf2-j538f71
  (testing "the failed-update teardown reaches NESTED descendants: the update
            body mounts child → grandchild then throws; the whole root subtree
            (root, child AND grandchild) is torn down leaf-upward"
    (let [child-ref      (atom nil)
          grandchild-ref (atom nil)
          parent         (rf.adapter.test-react/mount! [:div "old"])]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-nested-update"
            (rf.adapter.test-react/trigger-update!
              parent
              {:rf/component
               (fn [_parent]
                 (reset! child-ref
                         (rf.adapter.test-react/mount-child!
                           {:rf/component
                            (fn [_child]
                              (reset! grandchild-ref
                                      (rf.adapter.test-react/mount-child! [:span "leaf"])))}))
                 (throw (ex-info "boom-nested-update" {})))}))
          "the original update exception escapes")
      (is (false? @(:mounted? parent))
          "the root was unmounted whole")
      (is (nil? (rf.adapter.test-react/current-render-tree parent))
          "the root's render tree was cleared")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "no node leaks — root, child AND grandchild all left the forest
           (pre-fix this is 3)")
      (is (and (false? @(:mounted? @child-ref))
               (false? @(:mounted? @grandchild-ref)))
          "both the direct child and the nested grandchild were torn down")
      (let [gc-seq     (phase-first-seq @grandchild-ref :forced-teardown)
            child-seq  (phase-first-seq @child-ref :forced-teardown)
            parent-seq (phase-first-seq parent :forced-teardown)]
        (is (< gc-seq child-seq parent-seq)
            "teardown ran leaf-upward: grandchild < child < root — the whole
             subtree unwound children-first, mirroring React's teardown order")))))

(deftest failed-update-spares-unrelated-sibling-root-rf2-j538f71
  (testing "a failed update on one root does NOT disturb a separate live sibling
            root: teardown is scoped to the updated root and its own subtree —
            the sibling and its committed tree are untouched"
    (let [sibling (rf.adapter.test-react/mount! [:div "sibling"])
          target  (rf.adapter.test-react/mount! [:div "target-v1"])]
      (is (= 2 (count (rf.adapter.test-react/mounted-components)))
          "precondition: sibling + target both live")
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-scoped-update"
            (rf.adapter.test-react/trigger-update!
              target
              {:rf/component
               (fn [_target]
                 (rf.adapter.test-react/mount-child! [:span "speculative"])
                 (throw (ex-info "boom-scoped-update" {})))}))
          "the target's update exception propagates")
      (is (true? @(:mounted? sibling))
          "the unrelated sibling root stays live")
      (is (= [:div "sibling"] (rf.adapter.test-react/current-render-tree sibling))
          "the sibling's committed tree is untouched")
      (is (zero? (phase-count sibling :forced-teardown))
          "the sibling took no forced teardown — teardown did not reach it")
      (is (false? @(:mounted? target))
          "the target root was unmounted whole by its failed update")
      (is (nil? (rf.adapter.test-react/current-render-tree target))
          "the target's render tree was cleared")
      (is (= [sibling] (rf.adapter.test-react/mounted-components))
          "only the sibling remains live — the target and its speculative child
           are gone, the sibling is scoped out")
      (rf.adapter.test-react/unmount! sibling))))

(deftest failed-update-fully-unmounts-root-so-later-update-is-rejected-rf2-j538f71
  (testing "a failed update UNMOUNTS the root for real (not just clears its
            tree): a subsequent trigger-update! on it is rejected with
            :rf.error/update-after-unmount — the update-after-unmount guard sees
            a dead mount, proving the teardown fully evicted it"
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #"boom-then-dead"
            (rf.adapter.test-react/trigger-update!
              mount
              {:rf/component
               (fn [_mount]
                 (rf.adapter.test-react/mount-child! [:span "speculative"])
                 (throw (ex-info "boom-then-dead" {})))}))
          "the update fails")
      (is (false? @(:mounted? mount))
          "the root is unmounted after the failed update")
      (is (nil? (rf.adapter.test-react/current-render-tree mount))
          "its render tree was cleared")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the forest is empty")
      ;; The mount is genuinely dead — a later update must be rejected, not
      ;; silently re-render a torn-down root.
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/update-after-unmount"
            (rf.adapter.test-react/trigger-update! mount [:div "v2"]))
          "trigger-update! refuses the unmounted root — the failed update fully
           evicted it, so the update-after-unmount guard fires (pre-fix the root
           stayed live and this update would have silently re-rendered it)"))))

(deftest failed-update-composes-with-sync-unmount-guard-rf2-j538f71
  (testing "the failed-update teardown COMPOSES with the B.1 guard: an update
            body that mounts a tracked child then synchronously unmounts a
            SEPARATE live root still trips :rf.error/sync-unmount-during-render
            (teardown does not mask it); the guard's target survives (the guard
            refused its unmount), while the HOST root — whose update threw via
            the guard — is unmounted whole along with its speculative child"
    (let [target    (rf.adapter.test-react/mount! [:div.panel "target"])
          host      (rf.adapter.test-react/mount! [:div "host-v1"])
          child-ref (atom nil)]
      (is (= 2 (count (rf.adapter.test-react/mounted-components)))
          "precondition: guard target + host both live")
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/sync-unmount-during-render"
            (rf.adapter.test-react/trigger-update!
              host
              {:rf/component
               (fn [_host]
                 (reset! child-ref (rf.adapter.test-react/mount-child! [:span "child"]))
                 (rf.adapter.test-react/unmount! target))}))
          "the guard error — NOT the teardown — is what escapes")
      (is (true? @(:mounted? target))
          "the guard refused the synchronous unmount — the target survives")
      (is (false? @(:mounted? host))
          "the host root, whose update threw via the guard, was unmounted whole")
      (is (nil? (rf.adapter.test-react/current-render-tree host))
          "the host's render tree was cleared")
      (is (false? @(:mounted? @child-ref))
          "the child the host mounted before the guard tripped was torn down too")
      (is (= [target] (rf.adapter.test-react/mounted-components))
          "only the guard's target is live — host + its speculative child are gone")
      (rf.adapter.test-react/unmount! target))))

;; ----------------------------------------------------------------------------
;; C. Harness-contract guards (rf2-ynjts.6)
;; ----------------------------------------------------------------------------
;;
;; These pin the harness's OWN documented PUBLIC surface — `mount!` /
;; `trigger-update!` / `unmount!` / `mount-child!` / the substrate `:render`
;; entry point / `render-to-string` — the contract its docstrings promise to
;; whoever writes a lifecycle test against it. There is no downstream
;; consumer: nothing outside this artefact requires `re-frame.adapter.test-
;; react` (the only non-test mention anywhere is the quoted producer roster in
;; `re-frame.late-bind.directory`), so these guards are self-tests, not a
;; proxy for some other suite's coverage. Each pins a guard or two-entry-point
;; behaviour the harness docstrings PROMISE but the A/B layers above never
;; exercised. A silent regression in any of them would weaken every test
;; WRITTEN AGAINST this harness without tripping that test's own assertions —
;; e.g. a `trigger-update!` that no longer throws after teardown would let a
;; test re-render a dead mount and read a stale tree; a `mount!` that dropped
;; its installed-adapter guard would silently mount under whatever adapter the
;; test forgot to install.

;; ---- trigger-update! after unmount throws ---------------------------------

(deftest trigger-update-after-unmount-throws
  (testing "trigger-update! on an already-unmounted mount throws
            :rf.error/update-after-unmount — you cannot re-render a dead mount.
            The lifecycle log is untouched (no stray :render / :did-update),
            so the throw is a true short-circuit, not a render-then-throw."
    (let [mount (rf.adapter.test-react/mount! [:div "v1"])
          _     (rf.adapter.test-react/unmount! mount)
          log-before (rf.adapter.test-react/lifecycle-log mount)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
            #":rf.error/update-after-unmount"
            (rf.adapter.test-react/trigger-update! mount [:div "v2"]))
          "trigger-update! refuses an unmounted mount")
      (is (= log-before (rf.adapter.test-react/lifecycle-log mount))
          "no :render / :did-update leaked onto the log — the guard short-circuited before run-render!"))))

;; ---- unmount! is idempotent ------------------------------------------------

(deftest unmount-is-idempotent
  (testing "a second unmount! on an already-unmounted mount is a silent no-op:
            it does NOT throw, and records no second :will-unmount entry (so a
            double-teardown in downstream code can't double-count or corrupt
            the log)"
    (let [mount (rf.adapter.test-react/mount! [:div "once"])]
      (rf.adapter.test-react/unmount! mount)
      (is (= 1 (phase-count mount :will-unmount))
          "first unmount recorded exactly one :will-unmount")
      ;; Second call must neither throw nor append a second :will-unmount.
      (is (nil? (rf.adapter.test-react/unmount! mount))
          "second unmount! returns nil without throwing")
      (is (= 1 (phase-count mount :will-unmount))
          "second unmount! recorded NO additional :will-unmount — idempotent")
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the forest stays empty across the redundant teardown"))))

;; ---- mount-child! outside a render body throws ----------------------------

(deftest mount-child-outside-render-body-throws
  (testing "mount-child! called with no render in flight (*rendering-mount* nil)
            throws :rf.error/mount-child-outside-render — a child needs a parent
            render to attach to. Guards the recursive-child seam against a stray
            top-level call that would otherwise produce an unparented mount."
    (is (false? (rf.adapter.test-react/rendering?))
        "precondition: no render in flight at the test top level")
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/mount-child-outside-render"
          (rf.adapter.test-react/mount-child! [:span "orphan"]))
        "mount-child! demands an in-flight render body")
    (is (zero? (count (rf.adapter.test-react/mounted-components)))
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
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! sentinel-adapter)
      (try
        (is (thrown-with-msg?
              #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
              #":rf.error/test-react-not-installed"
              (rf.adapter.test-react/mount! [:div "nope"]))
            "mount! refuses to run when test-react is not the installed adapter")
        (finally
          ;; Restore the test-react adapter the :each fixture expects to dispose.
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))))

;; ---- mount! under a COPIED test-react adapter map succeeds (rf2-dkl5z1) ----

(deftest mount-under-copied-test-react-map-succeeds
  (testing "mount! ACCEPTS a copied / wrapped Test-React adapter map — the
            installed-adapter guard is stable-token (same-adapter?), not raw
            object identity, so an `assoc`'d instrumentation copy (distinct
            object, same canonical :rf.adapter/test-react :kind) mounts
            normally. Mirrors the wrong-adapter test's swap-and-restore, but
            asserts the POSITIVE case the identity guard wrongly rejected
            pre-fix (rf2-dkl5z1)."
    (let [copied (assoc rf.adapter.test-react/adapter :rf.test/instrumentation-wrapper true)]
      ;; Tear down the fixture-installed canonical map and seat the copy.
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! copied)
      (try
        (is (false? (identical? rf.adapter.test-react/adapter (rf.substrate.adapter/current-adapter-spec)))
            "precondition: the installed copy is NOT identical to the canonical map")
        (is (= :rf.adapter/test-react (rf.substrate.adapter/current-adapter))
            "precondition: the copy preserves the canonical :kind token")
        (let [mount (rf.adapter.test-react/mount! [:div "via-copied-map"])]
          (is (some? mount)
              "mount! returned a MountedComponent record under the copied map")
          (is (= [:constructor :render :did-mount]
                 (mapv :phase (rf.adapter.test-react/lifecycle-log mount)))
              "the copied map drove a normal mount lifecycle (no test-react-not-installed throw)")
          (rf.adapter.test-react/unmount! mount))
        (finally
          ;; Restore the canonical map the :each fixture expects to dispose.
          (rf.substrate.adapter/dispose-adapter!)
          (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))))

;; ---- render-to-string with no emitter bound throws ------------------------

(deftest render-to-string-without-emitter-throws
  (testing "render-to-string with NO hiccup emitter bound throws
            :rf.error/no-hiccup-emitter-bound — the harness ships no built-in
            emitter, so a test that needs HTML must install one. We force the
            emitter cell to nil (it may carry a leftover SSR chain install) and
            assert the throw, then leave it nil for downstream tests."
    (rf.adapter.test-react/set-hiccup-emitter! nil)
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #":rf.error/no-hiccup-emitter-bound"
          (rf.substrate.adapter/render-to-string [:div "x"] nil))
        "no emitter bound → render-to-string throws the documented error")))

;; ---- the substrate :render entry point returns a working unmount thunk ----

(deftest substrate-render-entry-point-returns-working-thunk
  (testing "the substrate contract's :render fn (the slot the core runtime
            drives, distinct from the public mount! driver) returns a thunk
            that, when called, tears the mount down. mount! and :render share
            the internal mount-tree! seam; :render discards the record and
            hands back ONLY the thunk. Pinning this proves THIS fixture's
            substrate-facing :render slot stays a live unmount handle, not just
            the inspection-friendly mount!."
    (let [thunk (rf.substrate.adapter/render [:div "via-render"] nil nil)]
      (is (fn? thunk)
          ":render returns a callable unmount thunk")
      (is (= 1 (count (rf.adapter.test-react/mounted-components)))
          "the :render entry point mounted exactly one root")
      (thunk)
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
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
          parent (rf.adapter.test-react/mount!
                   {:rf/component
                    (fn [_parent]
                      (reset! child-ref
                              (rf.adapter.test-react/mount-child!
                                {:rf/component
                                 (fn [_child]
                                   (reset! grandchild-ref
                                           (rf.adapter.test-react/mount-child! [:span "leaf"])))})))})]
      ;; The parent's render nested two levels deep (parent → child →
      ;; grandchild), so the global render-depth climbed to 3 then unwound.
      ;; Because it is a COUNTER, not a boolean (src note ~lines 128-129), each
      ;; nested render's run-render! `finally` decremented exactly its own
      ;; level, leaving depth at zero once the outermost mount! returned. A
      ;; boolean would have been clobbered to false by the innermost unwind
      ;; while an outer render was still notionally in flight. This pins the
      ;; counter-not-boolean nested-unwind restore, which no test checked
      ;; directly before.
      (is (false? (rf.adapter.test-react/rendering?))
          "render-depth restored to zero after a two-level nested render
           unwound — the counter decrements each nested level independently")
      (is (= 3 (count (rf.adapter.test-react/mounted-components)))
          "parent + child + grandchild all live")
      (is (= [@child-ref] (rf.adapter.test-react/mounted-children parent))
          "child recorded under parent")
      (is (= [@grandchild-ref] (rf.adapter.test-react/mounted-children @child-ref))
          "grandchild recorded under child — the tree is two levels deep")
      (rf.adapter.test-react/unmount! parent)
      (is (zero? (count (rf.adapter.test-react/mounted-components)))
          "the whole forest drained — no orphaned descendant")
      (let [gc-seq     (phase-first-seq @grandchild-ref :will-unmount)
            child-seq  (phase-first-seq @child-ref :will-unmount)
            parent-seq (phase-first-seq parent :will-unmount)]
        (is (and gc-seq child-seq parent-seq)
            "every level recorded a :will-unmount during the cascade")
        (is (< gc-seq child-seq parent-seq)
            "teardown order is grandchild < child < parent — leaf-upward unwind
             (strict monotonic seq: a root-downward regression FAILS this, where
             the old <=-on-ms check stayed green because the cascade ran sub-ms)")))))
