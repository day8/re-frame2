(ns day8.re-frame2-machines-viz.chart.auto-fit-view-cljs-test
  "Pins for the chart's auto-fit and measure-then-relayout lifecycle.

  ## What this guards

  xyflow's `<ReactFlow>` `:fitView true` fires exactly ONCE on initial
  mount, BEFORE elkjs's async layout pass resolves. Every node renders
  at the default `{x 0 y 0}`, so that one-shot fitView would fit to a
  degenerate cluster near the origin, and when real positions arrive
  the viewport would never re-fit — the operator would see a tiny /
  off-screen chart and have to click the manual Fit button every time.

  So `MachineChart`:

    - captures the xyflow instance via `:onInit` and re-fits the viewport
      once after each successful layout settle whose layout key differs
      from the last key it fit, so a manual zoom/pan survives re-renders
      that do not invalidate the layout;
    - re-fits whenever the host's `:fit-signal` CHANGES — the chart-side
      half of Xray's Machine-tab fit-on-entry;
    - re-runs ELK once with xyflow's measured node boxes
      (measure-then-relayout), and never loops.

  ## How the production gates are driven

  Every gate lives in a closure inside the Form-2 `MachineChart`, so this
  suite drives the real component: `(chart/MachineChart props)` returns
  the inner render fn, calling it with new props IS the host-driven
  re-render sequence, and the ReactFlow `:onInit` / `:onNodesChange`
  handlers are taken off the returned hiccup and called with a stand-in
  instance. Building the hiccup is pure CLJS data, and every
  framework-reaching call goes through a `set!`-able seam:

    - `chart/compute-layout!` captures each pass's done-fn, so a test
      settles passes itself, in the order its scenario needs;
    - `chart/invoke-fit-view!` records every `.fitView` call;
    - `chart/invoke-project-definition!` answers a fixed parse where a
      scenario names one, and `projection/xyflow-graph` answers an empty
      graph (projection is not under test here).

  The stand-in instance answers `getNodes` / `getInternalNode` off an
  atom, so the REAL `read-measured-dims` reads whatever boxes a scenario
  has 'measured'. Node has no `requestAnimationFrame`, so `schedule-fit!`
  calls `.fitView` synchronously there; the double-rAF test installs a
  stepped frame queue to observe the deferral. The same idioms drive
  `stale-settle-cljs-test` and `parse-cache-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.post-elk :as post-elk]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

;; ---- fixtures ----------------------------------------------------------

(def ^:private machine-a
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :idle}}}})

(def ^:private machine-b
  {:initial :off
   :states  {:off {:on {:flip :on}}
             :on  {:on {:flip :off}}}})

(def ^:private parsed-a
  "Fixed parse of `machine-a`: two leaf states and no edges, so the chart's
  measurable-id set is exactly `#{\"idle\" \"loading\"}`."
  {:nodes [{:id "idle"} {:id "loading"}] :edges [] :initial-path [:idle]})

(def ^:private parsed-b
  {:nodes [{:id "off"} {:id "on"}] :edges [] :initial-path [:off]})

(def ^:private ok-result
  "A successful settle: non-empty positions and no `:layout-error`."
  {:positions   {"idle"    {:x 10 :y 20 :width 100 :height 50}
                 "loading" {:x 10 :y 80 :width 100 :height 50}}
   :edge-points {}
   :edge-labels {}})

(def ^:private error-result
  "The layout-error result shape `compute-layout!`'s failure path produces."
  {:positions    {}
   :edge-points  {}
   :edge-labels  {}
   :layout-error {:error {:message "elk: boom"} :input-summary {}}})

;; ---- harness ------------------------------------------------------------

(defn- measuring-instance
  "A stand-in ReactFlowInstance modelling xyflow v12's measured-box shape:
  `.getNodes()` returns the user-facing nodes WITHOUT `.measured` (this
  non-interactive chart never applies dimension changes back into its
  controlled `:nodes`), while `.getInternalNode(id)` returns the INTERNAL
  node the store merges the DOM-measured box onto. Both read `boxes` — an
  atom of `{id [width height]}` — at call time, so a scenario can change
  what xyflow has measured between handler calls. A nil or zero box is a
  node still awaiting measurement."
  [boxes]
  #js {:getNodes
       (fn [] (clj->js (mapv (fn [id] {:id id}) (keys @boxes))))
       :getInternalNode
       (fn [id]
         (let [[w h] (get @boxes id)]
           (cond-> #js {:id id}
             (and w h) (doto (aset "measured" #js {:width w :height h})))))})

(defn- find-prop
  "The value of key `k` on the first hiccup map carrying it — in practice
  the ReactFlow `:onInit` / `:onNodesChange` handlers."
  [hiccup k]
  (->> (tree-seq sequential? seq hiccup)
       (filter map?)
       (some #(when (contains? % k) (get % k)))))

(defn- with-chart-seams
  "Run `(f seams)` with the chart's framework seams rebound, then restore
  them. `parses` maps a definition to the fixed parse the chart sees; a
  definition absent from it is parsed for real. `seams` holds two atoms:
  `:passes` — one `{:measured-dims md :done done-fn}` per `compute-layout!`
  call — and `:fits` — one `[instance opts]` per `.fitView`."
  [parses f]
  (let [passes      (atom [])
        fits        (atom [])
        capture!    (fn [measured-dims done]
                      (swap! passes conj {:measured-dims measured-dims :done done})
                      nil)
        orig-parse  chart/invoke-project-definition!
        orig-proj   projection/xyflow-graph
        orig-layout chart/compute-layout!
        orig-fit    chart/invoke-fit-view!]
    (set! chart/invoke-project-definition!
          (fn [definition] (or (get parses definition) (orig-parse definition))))
    (set! projection/xyflow-graph (fn [_parsed _positions _opts] {:nodes [] :edges []}))
    ;; `compute-layout!` is multi-arity and the render calls its 8-arity
    ;; through shadow's direct `arity$8` dispatch, so the stub mirrors the
    ;; real fn's arity shape.
    (set! chart/compute-layout!
          (fn
            ([_p done] (capture! nil done))
            ([_p _d _lo done] (capture! nil done))
            ([_p _d _lo _mid done] (capture! nil done))
            ([_p _d _lo _mid md done] (capture! md done))
            ([_p _d _lo _mid md _cv done] (capture! md done))
            ([_p _d _lo _mid md _cv _cr done] (capture! md done))))
    (set! chart/invoke-fit-view!
          (fn [instance opts] (swap! fits conj [instance opts])))
    (try
      (f {:passes passes :fits fits})
      (finally
        (set! chart/invoke-project-definition! orig-parse)
        (set! projection/xyflow-graph orig-proj)
        (set! chart/compute-layout! orig-layout)
        (set! chart/invoke-fit-view! orig-fit)))))

(defn- settle!
  "Resolve captured layout pass `i` with `result`."
  [passes i result]
  ((:done (nth @passes i)) result))

(defn- init!
  "Call the `:onInit` handler of the render `rfn` produces for `props`
  with `instance`, as xyflow does once it has mounted."
  [rfn props instance]
  ((find-prop (rfn props) :onInit) instance))

;; ---- 1. happy path — settle DOES fit ----------------------------------

(deftest auto-fit-fires-after-successful-layout-settle
  (testing "a successful settle (non-empty positions, no :layout-error)
            calls `.fitView` once, against the instance `:onInit`
            captured, with the canonical 0.1 padding. Without it the
            viewport stays framed on the pre-settle origin cluster."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [rfn  (chart/MachineChart {:definition machine-a})
              inst (measuring-instance (atom {}))]
          (init! rfn {:definition machine-a} inst)
          (is (empty? @fits) "no fit before the layout settles")
          (settle! passes 0 ok-result)
          (is (= 1 (count @fits)) "exactly one .fitView call on the settle")
          (let [[called-on opts] (first @fits)]
            (is (identical? inst called-on)
                "called against the captured xyflow instance")
            (is (= 0.1 (.-padding opts))
                "called with the canonical 0.1 padding ratio")))))))

;; ---- 2. key-gating semantics ------------------------------------------

(deftest auto-fit-gate-keys-on-layout-key
  (testing "a settle re-fits only when its layout key differs from the key
            last fit. The measured relayout settles under the SAME key, so
            it leaves a manual zoom/pan alone; a key change re-fits; and a
            return to an earlier key is a change too."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [rfn   (chart/MachineChart {:definition machine-a})
              boxes (atom {})]
          (init! rfn {:definition machine-a} (measuring-instance boxes))
          (settle! passes 0 ok-result)
          ;; The first post-settle render also fires the orthogonal
          ;; first-observed `:fit-signal` entry fit, so count from after it.
          (let [hiccup (rfn {:definition machine-a})
                base   (count @fits)]
            (reset! boxes {"idle" [200 60] "loading" [260 72]})
            ((find-prop hiccup :onNodesChange) #js [])
            (is (= 2 (count @passes)) "the measurement launched the relayout pass")
            (settle! passes 1 ok-result)
            (is (= base (count @fits)) "a same-key settle does NOT re-fit")
            (rfn {:definition machine-a :direction :lr})
            (settle! passes 2 ok-result)
            (is (= (inc base) (count @fits)) "a new layout key re-fits")
            (rfn {:definition machine-a :direction :tb})
            (settle! passes 3 ok-result)
            (is (= (+ 2 base) (count @fits))
                "returning to an earlier key counts as a change and re-fits")))))))

;; ---- 3. no instance yet → the fit waits for :onInit --------------------

(deftest auto-fit-defers-when-instance-not-yet-captured
  (testing "a settle that lands BEFORE xyflow's `:onInit` (a fast layout)
            has no instance to fit; `:onInit` then fits the positions that
            have already arrived."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [rfn    (chart/MachineChart {:definition machine-a})
              hiccup (rfn {:definition machine-a})
              inst   (measuring-instance (atom {}))]
          (settle! passes 0 ok-result)
          (is (empty? @fits) "no fit while no instance is captured")
          ((find-prop hiccup :onInit) inst)
          (is (seq @fits) ":onInit fits the already-settled positions")
          (is (every? #(identical? inst (first %)) @fits)
              "against the instance it captured"))))))

;; ---- 4. focused-machine change ---------------------------------------

(deftest auto-fit-fires-on-focused-machine-change
  (testing "a new `:definition` (Xray's Machine panel swapping the focused
            machine) changes the layout key, so the chart launches a fresh
            layout pass whose settle re-fits — each machine is framed
            without a manual Fit click. A re-render that keeps the machine
            launches no pass and fits nothing."
    (with-chart-seams {machine-a parsed-a machine-b parsed-b}
      (fn [{:keys [passes fits]}]
        (let [rfn (chart/MachineChart {:definition machine-a})]
          (init! rfn {:definition machine-a} (measuring-instance (atom {})))
          (settle! passes 0 ok-result)
          (rfn {:definition machine-a})
          (let [base (count @fits)]
            (rfn {:definition machine-a})
            (is (= [1 base] [(count @passes) (count @fits)])
                "a same-machine re-render launches no pass and fits nothing")
            (rfn {:definition machine-b})
            (settle! passes 1 ok-result)
            (is (= (inc base) (count @fits))
                "switching to machine-b fits the new topology")
            (rfn {:definition machine-a})
            (settle! passes 2 ok-result)
            (is (= (+ 2 base) (count @fits))
                "switching back to machine-a re-fits")))))))

;; ---- 5. schedule-fit! defers via two animation frames ----------------

(deftest schedule-fit-deferral-uses-double-raf
  (testing "`.fitView` runs through TWO nested `requestAnimationFrame`
            tasks, not one. A single frame races xyflow's node measurement
            on the focused-machine-change path: React commits the new
            machine's positions, the frame fires before xyflow has
            re-measured the new nodes, and `.fitView` reads stale or
            zero-size bounds. Two frames put the fit after React's commit
            AND xyflow's measurement pass."
    (let [g        js/globalThis
          had-raf? (js-in "requestAnimationFrame" g)
          orig-raf (.-requestAnimationFrame g)
          queue    (atom [])
          step!    (fn []
                     (let [cb (first @queue)]
                       (swap! queue subvec 1)
                       (cb 0)))]
      (set! (.-requestAnimationFrame g) (fn [cb] (swap! queue conj cb) 1))
      (try
        (with-chart-seams {machine-a parsed-a}
          (fn [{:keys [passes fits]}]
            (let [rfn (chart/MachineChart {:definition machine-a})]
              (init! rfn {:definition machine-a} (measuring-instance (atom {})))
              (settle! passes 0 ok-result)
              (is (= [1 0] [(count @queue) (count @fits)])
                  "the settle queues ONE frame and fits nothing yet")
              (step!)
              (is (= [1 0] [(count @queue) (count @fits)])
                  "the first frame queues a SECOND frame, still no fit")
              (step!)
              (is (= [0 1] [(count @queue) (count @fits)])
                  "the fit runs in the second frame"))))
        (finally
          (if had-raf?
            (set! (.-requestAnimationFrame g) orig-raf)
            (js-delete g "requestAnimationFrame")))))))

;; ---- 6. measure-then-relayout -----------------------------------------
;;
;; Fed CONSTANT floor dims, ELK would lay a node whose content exceeds the
;; floor over its neighbours. So the chart runs the canonical React Flow +
;; ELK two-pass: mount at content size → xyflow measures (`node.measured`)
;; → re-run ELK with the measured box.

(deftest read-measured-dims-keeps-only-fully-measured-nodes
  (testing "`read-measured-dims` lifts xyflow's measured box (read off the
            INTERNAL node via `getInternalNode`, NOT the user-facing
            `getNodes()` objects) into `{id {:width :height}}`, KEEPING only
            nodes with a positive measured width AND height. A node still
            awaiting measurement is omitted so the caller can tell when the
            whole topology has been measured."
    (let [dims (chart/read-measured-dims
                 (measuring-instance (atom {"idle"    [200 60]
                                            "loading" [260 72]
                                            "pending" nil
                                            "zero"    [0 0]})))]
      (is (= {"idle"    {:width 200 :height 60}
              "loading" {:width 260 :height 72}}
             dims)
          "only fully + positively measured nodes survive"))))

(deftest measure-then-relayout-fires-once-and-does-not-loop
  (testing "the relayout gate fires the second ELK pass once the whole
            topology is measured, feeding ELK the measured boxes, and does
            not loop: a relayout moves positions only, so the next
            measurement reports the SAME boxes and fires nothing. A box
            that genuinely changes does relayout again."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes]}]
        (let [rfn       (chart/MachineChart {:definition machine-a})
              hiccup    (rfn {:definition machine-a})
              boxes     (atom {"idle" [200 60]})
              relayout! (find-prop hiccup :onNodesChange)]
          ((find-prop hiccup :onInit) (measuring-instance boxes))
          (is (= 1 (count @passes)) "a partial measurement does NOT relayout")
          (swap! boxes assoc "loading" [260 72])
          (relayout! #js [])
          (is (= 2 (count @passes)) "the full measurement triggers one relayout")
          (is (= {"idle"    {:width 200 :height 60}
                  "loading" {:width 260 :height 72}}
                 (:measured-dims (nth @passes 1)))
              "ELK is re-fed the real measured boxes")
          (relayout! #js [])
          (relayout! #js [])
          (is (= 2 (count @passes))
              "a stable measurement does NOT re-fire — the loop is closed")
          (swap! boxes assoc "loading" [300 72])
          (relayout! #js [])
          (is (= 3 (count @passes)) "a box that changes relayouts again"))))))

(deftest measure-then-relayout-new-topology-resets-signature
  (testing "a NEW layout key clears the stored measured signature, so the
            new layout gets its own relayout even though its measured boxes
            equal the prior key's."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes]}]
        (let [rfn   (chart/MachineChart {:definition machine-a})
              boxes (atom {"idle" [200 60] "loading" [260 72]})]
          (init! rfn {:definition machine-a} (measuring-instance boxes))
          (is (= 2 (count @passes)) "the first key's measured relayout ran")
          (let [hiccup (rfn {:definition machine-a :direction :lr})]
            (is (= 3 (count @passes)) "the new key launched its own first pass")
            ((find-prop hiccup :onNodesChange) #js [])
            (is (= 4 (count @passes))
                "identical boxes still relayout under the new key")
            (is (= {"idle"    {:width 200 :height 60}
                    "loading" {:width 260 :height 72}}
                   (:measured-dims (nth @passes 3)))
                "that pass is the measured relayout")))))))

;; ---- 7. fit-on-entry signal -------------------------------------------
;;
;; The layout-key auto-fit deliberately PRESERVES the operator's manual
;; zoom/pan across non-layout re-renders, which on its own would leave a
;; chart RE-ENTERED from a panel/tab switch at its prior viewport. The
;; orthogonal `:fit-signal` prop (an opaque nonce the host bumps on
;; panel-entry / tab-activation) forces a re-fit when its value CHANGES.
;; These are the chart-side pins of that contract; the host-side wiring
;; (Xray bumping the signal on Machine-tab activation) is pinned in Xray.

(deftest fit-on-entry-fits-once-per-signal-change
  (testing "the fit-on-entry gate fires once each time the host
            `:fit-signal` CHANGES and is a no-op while the signal is
            steady, so ordinary re-renders keep a manual zoom/pan. The
            `::unfit` sentinel start makes the FIRST observed signal fit."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [rfn   (chart/MachineChart {:definition machine-a :fit-signal 1})
              props (fn [sig] {:definition machine-a :fit-signal sig})]
          (init! rfn (props 1) (measuring-instance (atom {})))
          (settle! passes 0 ok-result)
          (let [after-settle (count @fits)]
            (rfn (props 1))
            (is (= (inc after-settle) (count @fits))
                "the first observed signal fits once")
            (rfn (props 1))
            (rfn (props 1))
            (is (= (inc after-settle) (count @fits))
                "a steady signal does NOT re-fit")
            (rfn (props 2))
            (is (= (+ 2 after-settle) (count @fits))
                "a bumped signal (panel re-entry) re-fits")
            (rfn (props 3))
            (is (= (+ 3 after-settle) (count @fits))
                "every distinct entry signal fits exactly once")))))))

(deftest fit-on-entry-defers-until-instance-and-positions-ready
  (testing "an entry signal that arrives BEFORE the instance is captured,
            or before the first layout settles, is not recorded as fit: it
            stays pending, and the SAME signal fits once both have
            arrived."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [props  {:definition machine-a :fit-signal 1}
              rfn    (chart/MachineChart props)
              hiccup (rfn props)]
          (is (empty? @fits) "no entry fit while no instance is captured")
          ((find-prop hiccup :onInit) (measuring-instance (atom {})))
          (is (empty? @fits) "nor while the layout has no positions")
          (settle! passes 0 ok-result)
          (let [after-settle (count @fits)]
            (rfn props)
            (is (= (inc after-settle) (count @fits))
                "the pending signal fits once the positions arrive")))))))

(deftest fit-on-entry-skips-layout-error-settle
  (testing "a `:fit-signal` bump on a chart whose layout FAILED must not
            fit — the chart paints the error banner instead of framing the
            degenerate origin cluster."
    (with-chart-seams {machine-a parsed-a}
      (fn [{:keys [passes fits]}]
        (let [rfn (chart/MachineChart {:definition machine-a :fit-signal 1})]
          (init! rfn {:definition machine-a :fit-signal 1} (measuring-instance (atom {})))
          (settle! passes 0 error-result)
          (rfn {:definition machine-a :fit-signal 1})
          (rfn {:definition machine-a :fit-signal 2})
          (is (empty? @fits) "no entry fit on a layout-error settle"))))))

;; ---- 8. layout-key folds in the adaptive post-ELK mode ----------------
;;
;; The chart keys its ELK layout pass by the RESOLVED `elk-direction`, but the
;; post-ELK transform (parallel transpose + back-edge reroute) is gated by the
;; RAW `:auto` opt-in. When `:auto` resolves to the SAME direction as a
;; forced/default `:tb` (a linear / parallel machine — `aspect-direction`
;; returns `:tb`), the resolved direction is identical for `:direction :tb`
;; and `:direction :auto`, so without the opt-in flag in the key a
;; `:tb → :auto → :tb` flip would NOT invalidate the cached layout: the
;; back-edge reroute / parallel transpose would never apply on opt-IN and
;; would stale-stay on opt-OUT. So `chart/compute-layout-key` folds the
;; `adaptive?` mode flag in.

(def ^:private door-cyclic-definition
  "The door shape: a forward spine with a back-edge whose `:auto` aspect-
  heuristic resolves to `:tb` (a chain with a 2-way fan, under the landscape
  threshold) — so its resolved `elk-direction` is `:tb` whether the host
  forces `:tb` or opts in with `:auto` — the collision case the mode flag in
  the layout key exists for."
  {:initial :locked
   :states  {:locked   {:on {:insert-coin :closed}}
             :closed   {:on {:push :open}}
             :open     {:on {:close :closed :trip :alarming}}
             :alarming {:on {:reset :locked}}}})

(deftest layout-key-folds-in-adaptive-mode
  (let [parsed (layout/project-definition door-cyclic-definition)]
    (testing "sanity: :auto resolves to the SAME direction as a forced :tb here"
      ;; this is what makes the resolved-direction-only key collide.
      (is (= :tb (post-elk/resolve-direction :auto parsed)))
      (is (= :tb (post-elk/resolve-direction :tb parsed))))

    (testing "the forced-:tb and resolved-to-:tb-:auto keys DIFFER (mode is in the key)"
      ;; what the chart computes on each path: the RESOLVED elk-direction is
      ;; :tb for BOTH, but adaptive? differs (true on :auto, false on :tb).
      (is (not= (chart/compute-layout-key door-cyclic-definition :tb nil :comfortable 0 false)
                (chart/compute-layout-key door-cyclic-definition :tb nil :comfortable 0 true))
          "without adaptive? in the key these would collide — the post-ELK
           pass would never apply on opt-in and would stale-stay on opt-out"))

    (testing "a :tb → :auto → :tb flip launches a layout pass each way; a
              re-render with the SAME prop set launches none"
      (with-chart-seams {}
        (fn [{:keys [passes]}]
          (let [props (fn [direction] {:definition door-cyclic-definition
                                       :direction  direction})
                rfn   (chart/MachineChart (props :tb))]
            (rfn (props :tb))
            (rfn (props :auto))
            (rfn (props :tb))
            (is (= 3 (count @passes))
                "each direction-prop flip invalidates the layout, so the
                 post-ELK transform applies on opt-in and is removed on
                 opt-out")
            (rfn (props :tb))
            (rfn (props :tb))
            (is (= 3 (count @passes))
                "the same prop set keeps the same key — the flag does not
                 over-invalidate")))))))
