(ns re-frame.story.ui.a11y-teardown-eviction-cljs-test
  "rf2-cpbut — the a11y panel's per-frame state is RECLAIMED when the
  variant frame is torn down.

  `drop-frame-state!` existed, was correct, and had no production caller:
  its docstring named 'the canvas / shell teardown', which does not
  destroy variant frames (`ui/canvas`'s `component-will-unmount` clears
  its own render sentinels and nothing else, and no `ui/` namespace calls
  `rf.story.frames/destroy!` at all). So every frame ever scanned kept its slot for
  the life of the page.

  WHAT THAT COSTS. Not a stale verdict — RETAINED DOM. A stored violation
  is a raw axe-core object that references the offending elements through
  `:nodes` / `:target`. An entry that outlives its frame therefore pins
  that variant's DETACHED subtree, one leaked subtree per
  scanned-then-destroyed variant. `the-leak` below measures exactly that:
  it asserts the SCANNED NODE OBJECT ITSELF is still reachable from the
  panel's state after teardown when the eviction is absent, and
  unreachable when it is present. Reachability by object identity, not a
  GC probe — deterministic, and it is the retention relation that matters
  rather than whether a particular collector has run.

  THE LEVER. Every reclamation test here runs twice against the SAME
  teardown call: once with the `:drop-a11y-state` late-bind hook
  unregistered (reproducing pre-fix behaviour exactly — before rf2-cpbut
  no producer ever registered it) and once with it registered. The
  un-registered arm is the red-before, executed permanently in the suite
  rather than trusted to a one-off revert.

  Pure `.cljs`: the panel is CLJS-only, and the `async` tests need
  cljs.test MAP fixtures, which a `.cljc` may not use
  (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.late-bind :as rf.story.late-bind]
            [re-frame.story.ui.a11y :as rf.story.ui.a11y]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------
;;
;; Same shape as `a11y-stale-settlement-cljs-test`: installing a fake axe
;; on `js/window` makes `ensure-axe-loaded!` resolve immediately, so the
;; REAL `run-axe!` runs end to end with no `with-redefs` routing around
;; the code under test. The node runtime has no `window`; the fixture
;; installs one and restores whatever was there, because this process runs
;; every other CLJS namespace too and a leaked `window` would flip
;; browser-detection branches elsewhere.

(def ^:private saved-window (atom nil))

(defn- install-axe!
  "Point the fake axe-core's `run` at `run-fn`, which receives the scan
  context and returns the results promise."
  [run-fn]
  (gobj/set (gobj/get js/globalThis "window") "axe"
            #js {"run" (fn [ctx] (run-fn ctx))})
  nil)

(def ^:private variant-id :story.a11y-teardown/probe)

(defn- register-probe-variant!
  "A minimal events-only variant so `rf.story.frames/allocate!` takes the
  fast-path and `rf.story.frames/destroy!` has a real registered frame to tear
  down. The teardown seam under test is frame-level, not lifecycle-level,
  so the variant deliberately carries nothing else."
  []
  (rf.story/reg-story :story.a11y-teardown {:doc "teardown probe story"})
  (rf.story/reg-variant variant-id {:doc "teardown probe variant"}))

(defn- setup! []
  (reset! saved-window (gobj/get js/globalThis "window"))
  (gobj/set js/globalThis "window" #js {})
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (register-probe-variant!)
  (rf.story.ui.a11y/reset-state!)
  ;; `ensure-axe-loaded!` latches this once it has seen a global axe;
  ;; clear it so each test re-reads the fake installed for it.
  (reset! rf.story.ui.a11y/axe-loaded? false)
  (rf.story.ui.a11y/set-cdn-opt-in! true))

(defn- teardown! []
  (rf.story.ui.a11y/set-cdn-opt-in! false)
  (reset! rf.story.ui.a11y/axe-loaded? false)
  (rf.story.ui.a11y/reset-state!)
  (gobj/set js/globalThis "window" @saved-window))

(use-fixtures :each {:before setup! :after teardown!})

;; A context object that is `nodeType`-bearing (so `run-axe!` treats it as
;; the overlay scope) and selector-inert (its `querySelector` finds
;; nothing, so the overlay decorator is a no-op).
(defn- ctx [] #js {:nodeType 1 "querySelector" (fn [_] nil)})

(defn- violation-holding
  "An axe-core-shaped violation whose `nodes` reference `node-obj` — the
  shape that makes an un-evicted entry retain detached DOM."
  [id node-obj]
  #js {:id     id
       :impact "serious"
       :help   (str "help for " id)
       :nodes  #js [#js {"element" node-obj "target" #js ["#probe"]}]})

(defn- results-holding
  [id node-obj]
  #js {:violations #js [(violation-holding id node-obj)]})

(defn- never-settles
  "A promise that never resolves — a scan still in flight when the test
  makes its assertions, so the run holding the slot demonstrably still
  holds it."
  []
  (js/Promise. (fn [_ _] nil)))

(defn- signal
  "A promise plus the fn that resolves it. Lets a test wait for a specific
  point in a run to be reached instead of counting microtask turns."
  []
  (let [resolve-fn (atom nil)
        p          (js/Promise. (fn [res _] (reset! resolve-fn res)))]
    {:promise p :fire! (fn [] (@resolve-fn true))}))

;; ---------------------------------------------------------------------------
;; The lever
;; ---------------------------------------------------------------------------
;;
;; Before rf2-cpbut NO producer registered `:drop-a11y-state`, so the
;; consumer's `when-let` in `rf.story.frames/run-teardown-walks!` simply skipped.
;; Unregistering the hook reproduces that state exactly — this is the
;; red-before lever, and it isolates precisely the wiring this bead added
;; without touching the teardown call itself.

(defn- with-eviction-hook-removed
  "Run `f` with the `:drop-a11y-state` hook unregistered, then restore it.
  Restoration re-reads the live registry rather than assuming, so a
  failure inside `f` cannot leave the hook off for sibling namespaces in
  this shared process."
  [f]
  (let [saved (rf.story.late-bind/get-fn :drop-a11y-state)]
    (try
      (rf.story.late-bind/set-fn! :drop-a11y-state nil)
      (f)
      (finally
        (rf.story.late-bind/set-fn! :drop-a11y-state saved)))))

(defn- allocate-probe-frame! []
  (rf.story.frames/allocate! variant-id nil))

(defn- node-reachable-from-panel?
  "True when `node-obj` is still reachable from the a11y panel's per-frame
  violations bag — the retention relation the leak is made of. Walks the
  stored axe violation the same way a real one references its elements
  (`:nodes` → `element`)."
  [frame-id node-obj]
  (boolean
    (some (fn [v]
            (some (fn [n] (identical? node-obj (gobj/get n "element")))
                  (array-seq (gobj/get v "nodes"))))
          (get @rf.story.ui.a11y/violations-by-frame frame-id []))))

;; ---------------------------------------------------------------------------
;; The leak, measured
;; ---------------------------------------------------------------------------

(deftest the-leak
  (testing "a scanned-then-destroyed variant retains its detached DOM
            through the violations bag WITHOUT the teardown eviction, and
            reclaims it WITH the eviction — same scan, same teardown call,
            the hook the only difference"
    (async done
      (let [detached-node #js {"tagName" "BUTTON" "id" "probe"}]
        (allocate-probe-frame!)
        (install-axe! (fn [_] (js/Promise.resolve
                                (results-holding "color-contrast" detached-node))))
        (-> (rf.story.ui.a11y/run-axe! variant-id (ctx))
            (.then
              (fn [_]
                ;; The scan landed: the panel holds the frame's slot AND
                ;; the node the violation points at.
                (is (contains? @rf.story.ui.a11y/violations-by-frame variant-id)
                    "precondition: the scan stored a per-frame entry")
                (is (node-reachable-from-panel? variant-id detached-node)
                    "precondition: the stored violation references the node")

                ;; --- RED-BEFORE arm: no eviction hook ---
                (with-eviction-hook-removed
                  (fn []
                    (rf.story.frames/destroy! variant-id)
                    (is (contains? @rf.story.ui.a11y/violations-by-frame variant-id)
                        "WITHOUT the eviction the slot survives frame destruction")
                    (is (node-reachable-from-panel? variant-id detached-node)
                        "WITHOUT the eviction the DESTROYED variant's detached
                         node is still reachable from the panel — the leak")))

                ;; --- GREEN arm: hook registered, same teardown call ---
                ;; Re-allocate + re-scan so the second teardown has real
                ;; state to reclaim rather than the residue of the first.
                (allocate-probe-frame!)
                (-> (rf.story.ui.a11y/run-axe! variant-id (ctx))
                    (.then
                      (fn [_]
                        (is (node-reachable-from-panel? variant-id detached-node)
                            "precondition: the re-scan re-stored the node")
                        (rf.story.frames/destroy! variant-id)
                        (is (not (contains? @rf.story.ui.a11y/violations-by-frame variant-id))
                            "WITH the eviction the violations slot is ABSENT")
                        (is (not (contains? @rf.story.ui.a11y/run-state variant-id))
                            "WITH the eviction the run-state slot is ABSENT")
                        (is (not (node-reachable-from-panel? variant-id detached-node))
                            "WITH the eviction the detached node is no longer
                             reachable from the panel — reclaimed")
                        (is (= :idle (rf.story.ui.a11y/status-for variant-id))
                            "a reclaimed frame reads :idle, the never-scanned status")
                        (done)))))))))))

;; ---------------------------------------------------------------------------
;; Sequence — a guard carrying state can pass every single transition
;; ---------------------------------------------------------------------------

(deftest the-eviction-does-not-latch
  (testing "SCAN -> DESTROY -> reclaimed -> SCAN again -> DESTROY again ->
            reclaimed again, four transitions on one frame id in ONE
            process. A teardown that reclaimed only once (or only after
            the first allocation) would pass every single-transition test
            and still be broken."
    (async done
      (let [node-1 #js {"tagName" "A" "id" "first"}
            node-2 #js {"tagName" "IMG" "id" "second"}]
        ;; --- round 1 ---
        (allocate-probe-frame!)
        (install-axe! (fn [_] (js/Promise.resolve (results-holding "round-1" node-1))))
        (-> (rf.story.ui.a11y/run-axe! variant-id (ctx))
            (.then
              (fn [_]
                (is (node-reachable-from-panel? variant-id node-1)
                    "round 1 ADMIT: the scan stored its violation")
                (is (= :done (rf.story.ui.a11y/status-for variant-id))
                    "round 1 ADMIT: the run reached :done")
                (rf.story.frames/destroy! variant-id)
                (is (not (contains? @rf.story.ui.a11y/violations-by-frame variant-id))
                    "round 1 RECLAIM: violations slot absent")
                (is (not (contains? @rf.story.ui.a11y/run-state variant-id))
                    "round 1 RECLAIM: run-state slot absent")
                (is (not (node-reachable-from-panel? variant-id node-1))
                    "round 1 RECLAIM: node-1 unreachable")

                ;; --- round 2, SAME frame id, fresh incarnation ---
                (allocate-probe-frame!)
                (install-axe! (fn [_] (js/Promise.resolve
                                        (results-holding "round-2" node-2))))
                (-> (rf.story.ui.a11y/run-axe! variant-id (ctx))
                    (.then
                      (fn [_]
                        (is (node-reachable-from-panel? variant-id node-2)
                            "round 2 ADMIT: a re-allocated frame can scan again —
                             the first teardown did not poison the slot")
                        (is (not (node-reachable-from-panel? variant-id node-1))
                            "round 2 ADMIT: round 1's node did NOT come back")
                        (is (= :done (rf.story.ui.a11y/status-for variant-id))
                            "round 2 ADMIT: the second run reached :done")
                        (rf.story.frames/destroy! variant-id)
                        (is (not (contains? @rf.story.ui.a11y/violations-by-frame variant-id))
                            "round 2 RECLAIM: violations slot absent again")
                        (is (not (contains? @rf.story.ui.a11y/run-state variant-id))
                            "round 2 RECLAIM: run-state slot absent again")
                        (is (not (node-reachable-from-panel? variant-id node-2))
                            "round 2 RECLAIM: node-2 unreachable")
                        (done)))))))))))

;; ---------------------------------------------------------------------------
;; Interaction with the rf2-2amkm supersession fence (#6410)
;; ---------------------------------------------------------------------------
;;
;; #6410 stamped the run token INTO the slot so that "does the slot still
;; exist" and "is it still mine" are ONE question. This teardown is a NEW
;; clearing path, so the claim it revokes must be revoked for free. Verify
;; that rather than assume it.
;;
;; NOTE the failure shape being excluded: on CLJS this class does NOT
;; throw. `(swap! run-state assoc frame-id …)` over a map the frame was
;; dissoc'd from RESURRECTS the entry. So these assert the slot is ABSENT
;; and the status terminal — never that an exception was raised.

(deftest teardown-revokes-an-in-flight-runs-claim
  (testing "a scan still in flight when the frame is torn down settles into
            NOTHING: it must not resurrect the slot it no longer owns"
    (async done
      (let [scan-started (signal)
            detached     #js {"tagName" "DIV" "id" "in-flight"}
            release      (atom nil)]
        (allocate-probe-frame!)
        ;; The scan parks until the test releases it, so teardown is
        ;; positioned exactly INSIDE the in-flight window — never raced.
        (install-axe!
          (fn [_]
            ((:fire! scan-started))
            (js/Promise. (fn [res _] (reset! release #(res (results-holding
                                                             "late" detached)))))))
        (rf.story.ui.a11y/run-axe! variant-id (ctx))
        (-> (:promise scan-started)
            (.then
              (fn [_]
                (is (contains? @rf.story.ui.a11y/run-state variant-id)
                    "precondition: the in-flight run holds the slot")
                ;; Tear the frame down mid-scan through the PRODUCTION path.
                (rf.story.frames/destroy! variant-id)
                (is (not (contains? @rf.story.ui.a11y/run-state variant-id))
                    "teardown cleared the slot the in-flight run held")
                ;; Now let the scan settle over the cleared state.
                (@release)
                ;; Yield past the settlement's own callback chain before
                ;; reading, so the assertion sees the post-settlement world.
                (-> (js/Promise.resolve)
                    (.then (fn [_] nil))
                    (.then (fn [_] nil))
                    (.then
                      (fn [_]
                        (is (not (contains? @rf.story.ui.a11y/run-state variant-id))
                            "the superseded settlement did NOT resurrect the
                             run-state slot — the fence still declines after a
                             teardown-driven revocation")
                        (is (not (contains? @rf.story.ui.a11y/violations-by-frame variant-id))
                            "the superseded settlement did NOT resurrect the
                             violations slot")
                        (is (not (node-reachable-from-panel? variant-id detached))
                            "the late scan's node never entered the panel")
                        (is (= :idle (rf.story.ui.a11y/status-for variant-id))
                            "status reads :idle (terminal, never-scanned) rather
                             than a fabricated :done for a frame that is gone")
                        (done)))))))))))

(deftest teardown-leaves-an-unrelated-frames-state-alone
  (testing "the eviction is frame-scoped: destroying one variant frame must
            not reclaim a SIBLING frame's a11y state"
    (async done
      (let [other-id :story.a11y-teardown/sibling
            node-a   #js {"tagName" "P" "id" "a"}
            node-b   #js {"tagName" "P" "id" "b"}]
        (rf.story/reg-variant other-id {:doc "sibling probe variant"})
        (allocate-probe-frame!)
        (rf.story.frames/allocate! other-id nil)
        (install-axe! (fn [_] (js/Promise.resolve (results-holding "a" node-a))))
        (-> (rf.story.ui.a11y/run-axe! variant-id (ctx))
            (.then
              (fn [_]
                (install-axe! (fn [_] (js/Promise.resolve
                                        (results-holding "b" node-b))))
                (rf.story.ui.a11y/run-axe! other-id (ctx))))
            (.then
              (fn [_]
                (is (node-reachable-from-panel? variant-id node-a)
                    "precondition: probe frame holds its own scan")
                (is (node-reachable-from-panel? other-id node-b)
                    "precondition: sibling frame holds its own scan")
                (rf.story.frames/destroy! variant-id)
                (is (not (contains? @rf.story.ui.a11y/violations-by-frame variant-id))
                    "the destroyed frame's slot is reclaimed")
                (is (node-reachable-from-panel? other-id node-b)
                    "the SIBLING frame's state survives — the eviction is
                     scoped to the frame being torn down")
                (is (= :done (rf.story.ui.a11y/status-for other-id))
                    "the sibling's run status is untouched")
                (done))))))))
