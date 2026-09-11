(ns day8.re-frame2-xray.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the Machines canvas adapter.

  rf2-gpzb4 (2026-05-21 xyflow migration) — the previous test corpus
  was dominated by the viewport-reducer + drag-state machinery the
  SVG renderer needed. Post-migration xyflow owns zoom/pan/fit
  internally; the `Chart` hiccup wrapper + the chart-collapsed slot
  survive on the Xray side. (rf2-48fwsi retired the dead Canvas/List
  view-mode toggle + its slot/events/fx.)

  Covers:

    1. Registry wires the surviving subs + events + fx.
    2. The chart-collapsed slot mutates + persists per machine.
    3. The `Chart` view returns hiccup carrying the canvas-host
       data-testid."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; rf2-k97c.3 — `boundary-head?` is the production predicate the
            ;; codec grades a hiccup head with. The dual-head rows read it
            ;; rather than restating how each head was spelled.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.registrar :as rf.registrar]
            [day8.re-frame2-machines-viz.chart :as mv-chart]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.panels.machine-canvas :as mc]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; (core `make-reset-runtime-fixture` + Xray `reset-all!`) into one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- 1. Registry wires the surviving canvas surface -------------------

(deftest registry-wires-canvas-subs
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (testing "every machine-canvas sub resolves through rf/subscribe"
      (doseq [q-v [[:rf.xray.machine-canvas/chart-collapsed-for :m]
                   [:rf.xray.machine-canvas/chart-collapsed-by-id]]]
        (is (some? (rf/subscribe q-v))
            (str q-v " must resolve through rf/subscribe"))))))

;; ---- 2. Chart-collapsed slot (rf2-3d987 issue #4) ---------------------

(deftest chart-collapsed-defaults-to-false
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [collapsed? @(rf/subscribe
                        [:rf.xray.machine-canvas/chart-collapsed-for :m])]
      (is (= false collapsed?)
          "rf2-3d987 issue #4 — unset slot defaults to false (expanded)"))))

(deftest chart-collapsed-set-collapsed-and-toggle
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :collapsed}])
    (is (= true @(rf/subscribe
                   [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :collapsed flips the slot to true")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :toggle}])
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :toggle inverts the slot")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :toggle}])
    (is (= true @(rf/subscribe
                   [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        "second :toggle inverts again")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :expanded}])
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :expanded flips the slot to false")))

(deftest chart-collapsed-is-per-machine
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :auth/login :mode :collapsed}])
    (is (= true  @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :auth/login])))
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :checkout/flow]))
        "rf2-3d987 issue #4 — per-machine slot, one machine's collapse
         does not affect another's")))

(deftest persist-chart-collapsed-fx-registered
  (setup-xray-frame!)
  (is (some?
        (rf.registrar/handler
          :fx :rf.xray.machine-canvas/persist-chart-collapsed))
      "persist-chart-collapsed fx is in the registrar"))

(deftest persist-chart-collapsed-fx-actually-fires-rf2-04tx
  (testing "rf2-04tx — the set-chart-collapsed handler must REACH the
            persist fx, not merely have one registered. The handler used
            to return the fx-id as a TOP-LEVEL effect key beside `:db`;
            the effect map is closed, so the runtime policed the key as
            `:rf.error/effect-map-shape` and dropped it — the `:db` write
            landed, the toggle looked like it worked, and the operator's
            choice never reached localStorage. `persist-chart-collapsed-
            fx-registered` above cannot see that: a registered fx nobody
            routes to satisfies it perfectly. This one observes the do-fx
            plane, which is the only place the drop is visible."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [persisted (atom [])]
        (rf/dispatch-sync
          [:rf.xray.machine-canvas/set-chart-collapsed
           {:machine-id :auth/login :mode :collapsed}]
          {:fx-overrides
           {:rf.xray.machine-canvas/persist-chart-collapsed
            (fn [_ctx by-id] (swap! persisted conj by-id))}})
        (is (= 1 (count @persisted))
            "the persist fx ran exactly once — it reached the :fx walk")
        (is (= {:auth/login true} (first @persisted))
            "and carried the POST-mutation chart-collapsed map")
        (rf/dispatch-sync
          [:rf.xray.machine-canvas/set-chart-collapsed
           {:machine-id :checkout/flow :mode :collapsed}]
          {:fx-overrides
           {:rf.xray.machine-canvas/persist-chart-collapsed
            (fn [_ctx by-id] (swap! persisted conj by-id))}})
        (is (= {:auth/login true :checkout/flow true} (second @persisted))
            "a second toggle persists the WHOLE by-id map, not just the
             machine that moved — the reload-restore contract")))))

;; ---- 3. Chart view hiccup shape ---------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle {:on {:start :loading}}
             :loading {}}})

(deftest chart-view-emits-canvas-host
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (mc/Chart {:definition fixture-definition :machine-id :m})]
      (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
          "canvas host wrapper present"))))

(deftest chart-view-never-emits-view-mode-toggle-rf2-48fwsi
  (testing "rf2-48fwsi — the vestigial Canvas/List view-mode toggle is
            removed; the Chart never renders it (it was dead after the
            rf2-g2axio events-as-nodes redesign — no view branched on
            the persisted mode)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (mc/Chart {:definition fixture-definition :machine-id :m})]
        (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
            "canvas host mounts")
        (is (nil? (find-by-testid tree "rf-xray-machine-canvas-view-mode-toggle"))
            "the retired view-mode toggle never renders")))))

(deftest chart-mounts-the-after-rings-bridge-rf2-k97c-3
  (testing "rf2-k97c.3 — `machine-after-rings/AfterRingsOverlay` is now an
            `rf.fresco/defview`, i.e. a real React component, while `Chart`
            here is still a `reg-view`, i.e. a Reagent tree. So `Chart` must
            mount the `as-component` BRIDGE and not the boundary: handing
            Reagent a React component where it expects a render fn is
            exactly what `defview`'s contract forbids, and it would paint
            nothing. The boundary's own end-to-end DOM evidence lives in
            `machine_after_rings_fresco_boundary_dom_cljs_test`, which
            mounts this same public var — this row is what says CHART is
            the caller holding it, so the two cannot drift apart silently.

            Both halves are asserted. The bridge being present is the claim;
            the boundary being ABSENT is what would catch a well-meaning
            revert to the pre-migration spelling, which type-checks fine and
            fails only at first paint."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree  (mc/Chart {:definition fixture-definition :machine-id :m})
            heads (into #{}
                        (comp (filter vector?) (map first))
                        (hiccup-seq tree))]
        (is (contains? heads after-rings/AfterRingsOverlay-bridge)
            "Chart mounts AfterRingsOverlay-bridge (:show-after-rings? defaults true)")
        (is (not (contains? heads after-rings/AfterRingsOverlay))
            "and NOT the boundary itself, which a Reagent tree cannot mount")))))

(deftest chart-omits-the-after-rings-bridge-when-suppressed
  (testing "the three Static / topology call sites pass `:show-after-rings?
            false`; the bridge must then be absent altogether. This is the
            NON-VACUITY control for the row above — without it, a `heads`
            set that contained the bridge unconditionally would satisfy it."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree  (mc/Chart {:definition fixture-definition
                             :machine-id :m
                             :show-after-rings? false})
            heads (into #{}
                        (comp (filter vector?) (map first))
                        (hiccup-seq tree))]
        (is (not (contains? heads after-rings/AfterRingsOverlay-bridge))
            ":show-after-rings? false drops the overlay mount entirely")))))

;; ---- 3b. the two heads, and the one body behind them (rf2-k97c.3) ------

(deftest the-two-chart-heads-differ-only-in-boundary-grade
  (testing "rf2-k97c.3 — this ns ships a DUAL-HEAD FACADE, the shape
            `views/edn_widget.cljs` already uses for `inspect` /
            `inspect-view`: `Chart` for a Reagent parent, `Chart-view` for a
            Fresco one, both one call to [[mc/chart-tree]].

            Graded by the production predicate `codec/boundary-head?`, which
            reads the single own property (`frescoBoundary`) that only
            `rf.fresco/defview` sets — so this row states what the codec will
            actually do with each head rather than restating how each was
            spelled.

            BOTH DIRECTIONS, because a predicate that answered true for
            everything would satisfy the first half alone. The `reg-view`
            must grade FALSE: it is what the two Static consumers head from
            inside `definition_detail`'s own Reagent island, and heading it
            under a boundary is the loud `:invalid` this facade exists to
            avoid."
    (is (rf.fresco.impl.codec/boundary-head? mc/Chart-view)
        "Chart-view is a Fresco boundary head")
    (is (not (rf.fresco.impl.codec/boundary-head? mc/Chart))
        "CONTROL: the surviving reg-view is NOT, so the assertion above
         discriminates rather than reading true for any fn")))

(deftest chart-tree-wraps-only-the-machines-viz-mount-in-as-child
  (testing "rf2-k97c.3 — `as-child` covers the machines-viz chart and NOTHING
            else. Driven with a marking wrapper rather than a real substrate
            walk, so the row reads the seam's EXTENT without depending on a
            React element being constructible in the node lane.

            The extent is the claim worth pinning: widen it and the Fresco
            lane would cross its own wrapper divs into Reagent, which paints
            the same and silently puts the panel's chrome back under the
            installed adapter's renderer."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [crossed  (atom [])
            marking  (fn [v] (swap! crossed conj v) [:marked v])
            tree     (mc/chart-tree {:definition fixture-definition
                                     :machine-id :m}
                                    marking
                                    [::overlay-sentinel])
            heads    (into #{}
                           (comp (filter vector?) (map first))
                           (hiccup-seq tree))]
        (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
            "the canvas host is emitted by chart-tree itself and does NOT
             cross the seam — the boundary's own chrome stays its own")
        (is (= 1 (count @crossed))
            (str "exactly one thing crosses. Crossed: " (pr-str @crossed)))
        (is (= mv-chart/MachineChart (ffirst @crossed))
            "and it is the machines-viz chart, the one head in this body that
             Fresco's codec would refuse")
        (is (contains? heads ::overlay-sentinel)
            "the overlay value the caller handed in is mounted verbatim —
             which is how the two heads mount DIFFERENT overlay heads (the
             bridge, or the boundary) through one body")))))

(deftest chart-tree-reads-show-after-rings-and-nothing-else-gates-the-overlay
  (testing "rf2-k97c.3 — the NON-VACUITY control for the row above: with
            `:show-after-rings? false` the caller's overlay value is dropped
            altogether, so the assertion that it is mounted verbatim is a
            claim about the gate and not about a value that is always there."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree  (mc/chart-tree {:definition fixture-definition
                                  :machine-id :m
                                  :show-after-rings? false}
                                 identity
                                 [::overlay-sentinel])
            heads (into #{}
                        (comp (filter vector?) (map first))
                        (hiccup-seq tree))]
        (is (not (contains? heads ::overlay-sentinel))
            ":show-after-rings? false drops the caller's overlay entirely")))))
