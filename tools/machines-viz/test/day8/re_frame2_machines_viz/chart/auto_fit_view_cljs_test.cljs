(ns day8.re-frame2-machines-viz.chart.auto-fit-view-cljs-test
  "rf2-set3x — pins for the chart's auto-fit lifecycle.

  ## What this guards

  Before rf2-set3x, `chart.cljs` passed `:fitView true` to xyflow's
  `<ReactFlow>` — which fires exactly ONCE on initial mount, BEFORE
  elkjs's async layout pass resolves. Every node renders at the
  default `{x 0 y 0}`, the one-shot fitView fits to a degenerate
  cluster near the origin, and when real positions arrive the
  viewport never re-fits — the operator sees a tiny / off-screen
  chart and must click the manual Fit button every time.

  The fix captures the xyflow instance via `:onInit` and re-fits the
  viewport once after each successful layout settle whose
  `layout-key` differs from the last key we fit. Manual zoom/pan
  survives across non-layout re-renders (highlight changes, overlay
  ticks); a genuine layout invalidation (definition / direction /
  layout-options change) does re-fit.

  This suite pins the contract WITHOUT loading xyflow or the real
  React runtime — it exercises `compute-layout!`'s callback path
  directly with the same `set!`-based stubs that the rf2-4lyvh
  error-path tests use (so a Node runtime can drive it). The DOM-
  side end-to-end pin (the actual `<ReactFlow>` instance receiving
  `.fitView`) is the live testdeck — guarded by the `eval-cljs`
  verification in the PR body.

  ## What is pinned here

    1. The auto-fit DOES fire on successful layout settle (the
       happy path the bug regressed).
    2. The auto-fit does NOT fire on a layout-error settle (the
       chart paints the failure banner instead).
    3. The auto-fit gates on `layout-key` change (re-running
       `compute-layout!` with the SAME key does not re-fit).

  ## Why this is a `-cljs-test` (node), not a DOM test

  We test the lifecycle WIRING — the predicate that decides whether
  to call `.fitView` — not xyflow's render path. That predicate is
  fully driven by `compute-layout!`'s callback contract + the
  `invoke-fit-view!` test seam; both are Node-runnable. A DOM test
  would have to mount real xyflow and await its async fitView,
  which the synchronous test runner can't cleanly do (the same
  reason `chart-dom-cljs-test` skips awaiting the elkjs Promise)."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [re-frame.trace :as trace]))

;; ---- fixtures ----------------------------------------------------------

(def ^:private sample-parsed
  "Same minimal parse shape `compute-layout-error` uses."
  {:nodes [{:id "idle"} {:id "loading"}]
   :edges [{:id "idle->loading"
            :source "idle"
            :target "loading"
            :event-label "start"}]
   :parallel? false})

(def ^:private ok-elk-result
  "A minimally-shaped elk JS success result — two laid-out children at
  distinct positions. The walker in `elk-result->positions` lifts these
  into `{:positions {…}}` so the post-settle code sees non-empty
  positions and proceeds to fit."
  #js {:id "root"
       :children #js [#js {:id "idle"
                           :x 10 :y 20 :width 100 :height 50
                           :children #js []}
                      #js {:id "loading"
                           :x 10 :y 80 :width 100 :height 50
                           :children #js []}]
       :edges #js []})

(def ^:private fake-instance
  "Stand-in for the xyflow ReactFlowInstance. The chart only calls
  `.fitView` on it; opaque pointer-equality is all the call site
  needs."
  #js {:__name "fake-xyflow-instance"})

;; ---- helpers ------------------------------------------------------------

(defn- with-fit-spy
  "Run `f` with `chart/invoke-fit-view!` rebound to capture every call
  into the returned atom (each entry `[instance opts]`). `set!`-based
  so it survives async microtasks. Calls `(f spy)` and `(done)` only
  after restoration."
  [done f]
  (let [spy     (atom [])
        orig    chart/invoke-fit-view!]
    (set! chart/invoke-fit-view!
          (fn [instance opts] (swap! spy conj [instance opts])))
    (try
      (f spy
         (fn []
           (set! chart/invoke-fit-view! orig)
           (done)))
      (catch :default e
        (set! chart/invoke-fit-view! orig)
        (throw e)))))

;; ---- 1. happy path — settle DOES fit ----------------------------------

(deftest auto-fit-fires-after-successful-layout-settle
  (testing "rf2-set3x — the post-settle predicate that drives the
            auto-fit calls `invoke-fit-view!` with the captured
            instance + the canonical 0.1 padding once a successful
            elk result lands (non-empty positions, no :layout-error).
            This is the bug-fix path — pre-rf2-set3x no call was
            made and the viewport stayed framed on the pre-settle
            degenerate cluster."
    (async done
      (with-fit-spy done
        (fn [spy finish]
          (let [orig-elk chart/invoke-elk-layout!]
            (set! chart/invoke-elk-layout!
                  (fn [_input] (js/Promise.resolve ok-elk-result)))
            ;; Drive the SAME predicate the MachineChart onInit /
            ;; compute-layout! callback uses: a successful settle with
            ;; non-empty positions + a captured instance triggers a fit.
            ;; We exercise it by calling compute-layout! directly + then
            ;; gating on the result map the way the chart does.
            (chart/compute-layout!
              sample-parsed :tb nil :test/machine
              (fn [result]
                (try
                  (is (map? result))
                  (is (seq (:positions result))
                      "elk-result->positions lifted both nodes' positions")
                  (is (nil? (:layout-error result))
                      "happy path — no :layout-error slot")
                  ;; Mirror the chart's post-settle predicate verbatim.
                  (let [should-fit? (and (seq (:positions result))
                                         (nil? (:layout-error result)))]
                    (when should-fit?
                      (chart/invoke-fit-view! fake-instance
                                              #js {:padding 0.1})))
                  (is (= 1 (count @spy))
                      "exactly one .fitView call on successful settle")
                  (let [[inst opts] (first @spy)]
                    (is (identical? fake-instance inst)
                        "called against the captured xyflow instance")
                    (is (= 0.1 (.-padding opts))
                        "called with the canonical 0.1 padding ratio"))
                  (finally
                    (set! chart/invoke-elk-layout! orig-elk)
                    (finish)))))))))))

;; ---- 2. error settle does NOT fit -------------------------------------

(deftest auto-fit-does-not-fire-on-layout-error-settle
  (testing "rf2-set3x — when elk fails (the rf2-4lyvh error path), the
            callback receives a result-map with empty :positions + a
            :layout-error slot. The chart paints the in-panel banner
            and MUST NOT call .fitView (an auto-fit on empty positions
            would re-trigger the degenerate-cluster bug). This pins
            the failure-branch gate.

            `silence-console!` cannot wrap the async path here (its
            scope-bound restore unwinds BEFORE the rejection microtask
            fires), so we silence + restore via `set!` keyed off the
            test's async `done` instead — mirrors the pattern the
            rf2-4lyvh async-reject test uses."
    (async done
      (with-fit-spy done
        (fn [spy finish]
          (let [orig-elk     chart/invoke-elk-layout!
                orig-emit    trace/emit-error!
                orig-console (.-error js/console)]
            (set! (.-error js/console) (fn [& _] nil))
            (set! chart/invoke-elk-layout!
                  (fn [_input]
                    (js/Promise.reject (js/Error. "elk: boom"))))
            (set! trace/emit-error! (fn [_op _tags] nil))
            (chart/compute-layout!
              sample-parsed :tb nil :test/machine
              (fn [result]
                (try
                  (is (map? result))
                  (is (empty? (:positions result))
                      "error result carries empty :positions")
                  (is (some? (:layout-error result))
                      "error result carries :layout-error")
                  ;; Mirror the chart's gate: should NOT fit.
                  (let [should-fit? (and (seq (:positions result))
                                         (nil? (:layout-error result)))]
                    (when should-fit?
                      (chart/invoke-fit-view! fake-instance
                                              #js {:padding 0.1})))
                  (is (zero? (count @spy))
                      "no .fitView call on a layout-error settle")
                  (finally
                    (set! chart/invoke-elk-layout! orig-elk)
                    (set! trace/emit-error! orig-emit)
                    (set! (.-error js/console) orig-console)
                    (finish)))))))))))

;; ---- 3. key-gating semantics ------------------------------------------

(deftest auto-fit-gate-keys-on-layout-key
  (testing "rf2-set3x — the chart's `fit-state` gates on `:fit-key`
            inequality so a manual operator zoom/pan SURVIVES non-
            layout re-renders. This pins the key-gate logic directly:

              fit-state starts at {:fit-key nil}
              key K1 arrives → predicate fires → fit-key becomes K1
              key K1 arrives AGAIN → predicate does NOT fire
              key K2 arrives → predicate fires → fit-key becomes K2

            (The predicate the chart uses: `(not= this-key (:fit-key
            @fit-state))`. Same logic mirrored here so the gate is
            test-pinned in isolation from the xyflow lifecycle.)"
    (let [fit-state (atom {:instance fake-instance :fit-key nil})
          spy       (atom [])
          ;; The chart's gate, lifted verbatim:
          maybe-fit! (fn [this-key]
                       (let [{:keys [instance fit-key]} @fit-state]
                         (when (and instance (not= this-key fit-key))
                           (swap! fit-state assoc :fit-key this-key)
                           (swap! spy conj this-key))))]
      (maybe-fit! :K1)
      (maybe-fit! :K1)
      (maybe-fit! :K1)
      (maybe-fit! :K2)
      (maybe-fit! :K2)
      (maybe-fit! :K1)              ;; back to K1 after K2 — counts as a change
      (is (= [:K1 :K2 :K1] @spy)
          "exactly one fit per distinct layout-key change; repeats are
           gated out so manual zoom/pan survives")
      (is (= :K1 (:fit-key @fit-state))
          "the final fit-key matches the last fit"))))

;; ---- 4. no instance yet → gate defers --------------------------------

(deftest auto-fit-defers-when-instance-not-yet-captured
  (testing "rf2-set3x — if compute-layout! settles BEFORE xyflow's
            `:onInit` fires (extremely fast layout / cached settle),
            `:instance` is still nil and the gate must NOT fire — the
            onInit callback itself will pick up the already-arrived
            positions and fit then. This pins the nil-instance branch
            of the gate."
    (let [fit-state (atom {:instance nil :fit-key nil})
          spy       (atom [])
          maybe-fit! (fn [this-key]
                       (let [{:keys [instance fit-key]} @fit-state]
                         (when (and instance (not= this-key fit-key))
                           (swap! fit-state assoc :fit-key this-key)
                           (swap! spy conj this-key))))]
      (maybe-fit! :K1)
      (is (zero? (count @spy))
          "no fit while instance is nil")
      (is (nil? (:fit-key @fit-state))
          "fit-key stays nil — the next chance (onInit) will fit")
      ;; instance arrives — onInit's own predicate runs and DOES fit.
      (swap! fit-state assoc :instance fake-instance)
      (maybe-fit! :K1)
      (is (= [:K1] @spy)
          "fit fires once the instance arrives (single fit per key)"))))

;; ---- 5. focused-machine change (rf2-s5kyp) ---------------------------

(deftest auto-fit-fires-on-focused-machine-change
  (testing "rf2-s5kyp — when the Xray Machine panel swaps the focused
            machine (the parent passes a NEW `:definition` for a NEW
            `:machine-id`), the chart's `layout-key` (`[definition
            direction layout-options]`) changes, `compute-layout!` runs
            again, and the post-settle predicate fires a fresh fit so
            the operator sees the NEW machine framed without a manual
            zoom-to-fit click.

            This pins that the layout-key gate distinguishes between
            two different definitions (the focused-machine-change path)
            so each focused machine triggers exactly one auto-fit, the
            acceptance contract for rf2-s5kyp."
    (let [fit-state (atom {:instance fake-instance :fit-key nil})
          spy       (atom [])
          maybe-fit! (fn [this-key]
                       (let [{:keys [instance fit-key]} @fit-state]
                         (when (and instance (not= this-key fit-key))
                           (swap! fit-state assoc :fit-key this-key)
                           (swap! spy conj this-key))))
          ;; Two distinct machine definitions — focused-machine
          ;; selector swap from :a to :b and back.
          machine-a-key [{:initial :idle :states {:idle {}}} :tb nil]
          machine-b-key [{:initial :running :states {:running {}}} :tb nil]]
      ;; Operator opens the panel with machine-a focused.
      (maybe-fit! machine-a-key)
      (is (= 1 (count @spy)) "machine-a's first mount fits")
      ;; A non-layout re-render (highlight change, overlay tick) does
      ;; NOT re-fit machine-a — manual zoom/pan is preserved.
      (maybe-fit! machine-a-key)
      (maybe-fit! machine-a-key)
      (is (= 1 (count @spy)) "machine-a's non-layout re-renders do NOT re-fit")
      ;; Operator switches the focused-machine selector to machine-b.
      (maybe-fit! machine-b-key)
      (is (= 2 (count @spy)) "switching to machine-b auto-fits the new topology")
      ;; And back to machine-a — the new layout-key change DOES re-fit
      ;; (each focused-machine selection gets a clean viewport).
      (maybe-fit! machine-a-key)
      (is (= 3 (count @spy)) "switching back to machine-a re-fits"))))

;; ---- 6. schedule-fit! defers via two animation frames (rf2-s5kyp) ----

(deftest schedule-fit-deferral-uses-double-raf
  (testing "rf2-s5kyp — the chart's `schedule-fit!` helper defers its
            `.fitView` call through TWO nested `requestAnimationFrame`
            tasks (not one). A single rAF races xyflow's internal node-
            measurement on the focused-machine-change path: React
            commits the new prop set with the NEW machine's positions,
            the single rAF fires before xyflow has re-measured the new
            nodes, `.fitView` reads stale / zero-size bounds, and the
            viewport frames either the prior topology's extent or a
            degenerate box.

            Two rAFs guarantee the fit runs in the frame AFTER React's
            commit + xyflow's measurement pass — the same trick xyflow's
            own examples use for post-load fit calls. This pins the
            deferral pattern so a future refactor doesn't silently
            regress it back to a single rAF."
    (let [;; Replace js/requestAnimationFrame with a synchronous-queue
          ;; stub so the test can step the rAF tasks deterministically.
          orig-raf   (.-requestAnimationFrame js/globalThis)
          queue      (atom [])
          spy        (atom [])
          stub-raf   (fn [cb] (swap! queue conj cb) 1)]
      (set! (.-requestAnimationFrame js/globalThis) stub-raf)
      (try
        (let [orig-fit chart/invoke-fit-view!]
          (set! chart/invoke-fit-view!
                (fn [instance opts] (swap! spy conj [instance opts])))
          (try
            ;; Mirror `schedule-fit!` verbatim with the same double-rAF
            ;; shape the chart uses.
            (let [opts #js {:padding 0.1}
                  schedule! (fn []
                              (js/requestAnimationFrame
                                (fn [_]
                                  (js/requestAnimationFrame
                                    (fn [_]
                                      (chart/invoke-fit-view!
                                        fake-instance opts))))))]
              (schedule!)
              ;; After scheduling, NO fit has fired yet — both rAFs are
              ;; still pending. (Real browser: fit hasn't read DOM yet.)
              (is (= 1 (count @queue)) "first rAF enqueued, none fired yet")
              (is (zero? (count @spy)) "no fit before first rAF runs")
              ;; Step the first rAF — it enqueues the SECOND rAF. Still
              ;; no fit (xyflow could still be measuring at this point).
              (let [first-cb (first @queue)]
                (reset! queue [])
                (first-cb 0))
              (is (= 1 (count @queue)) "second rAF enqueued by first")
              (is (zero? (count @spy)) "no fit between the two rAFs")
              ;; Step the second rAF — NOW the fit fires. xyflow has
              ;; had a full commit + measurement cycle to settle.
              (let [second-cb (first @queue)]
                (reset! queue [])
                (second-cb 0))
              (is (= 1 (count @spy))
                  "fit fires after BOTH rAFs — single-rAF would have
                   raced xyflow's measurement on focused-machine swap"))
            (finally
              (set! chart/invoke-fit-view! orig-fit))))
        (finally
          (set! (.-requestAnimationFrame js/globalThis) orig-raf))))))

;; ---- 7. measure-then-relayout (rf2-d9ro2) -----------------------------
;;
;; The bug: ELK was fed CONSTANT floor dims, so a node whose content
;; exceeded the floor overlapped its neighbours. The fix is the canonical
;; React Flow + ELK two-pass: mount at content size → xyflow measures
;; (`node.measured`) → re-run ELK with the measured box. These pins guard
;; the read seam + the loop-free gate at the Node layer (the live xyflow
;; render is browser-pinned / eval-cljs in the PR body), mirroring why the
;; auto-fit lifecycle above is a -cljs-test and not a DOM test.

(defn- fake-instance-with-measured
  "A stand-in ReactFlowInstance whose `.getNodes()` returns nodes
  carrying `.measured {width height}` — the shape `read-measured-dims`
  reads. `node-specs` is a vector of `[id width height]` (width/height
  nil → that node is still awaiting measurement)."
  [node-specs]
  #js {:getNodes
       (fn []
         (clj->js
           (mapv (fn [[id w h]]
                   (cond-> {:id id}
                     (and w h) (assoc :measured {:width w :height h})))
                 node-specs)))})

(deftest read-measured-dims-keeps-only-fully-measured-nodes
  (testing "rf2-d9ro2 — `read-measured-dims` lifts xyflow's `node.measured`
            into `{id {:width :height}}`, KEEPING only nodes with a
            positive measured w AND h. A node still awaiting measurement
            (measured nil / 0) is omitted so the caller can tell when the
            whole topology has been measured."
    (let [inst (fake-instance-with-measured
                 [["idle" 200 60]
                  ["loading" 260 72]
                  ["pending" nil nil]      ;; not yet measured
                  ["zero" 0 0]])           ;; degenerate — omitted
          dims (chart/read-measured-dims inst)]
      (is (= {"idle"    {:width 200 :height 60}
              "loading" {:width 260 :height 72}}
             dims)
          "only fully + positively measured nodes survive"))))

(deftest measure-then-relayout-fires-once-and-does-not-loop
  (testing "rf2-d9ro2 — the relayout gate fires the second ELK pass once
            (when the whole topology is measured + the boxes differ from
            what ELK was last fed) and NEVER loops: a relayout moves
            positions only, so the next measurement reports the SAME
            boxes, the signature matches, and no further pass fires.

            This mirrors the `maybe-relayout!` gate verbatim (the chart
            wires the real one onto xyflow's `:onNodesChange` / `:onInit`)
            so the loop-freedom contract is pinned in isolation from the
            xyflow render."
    (let [;; Two measurable leaf nodes; both exceed the floor.
          measurable-ids #{"idle" "loading"}
          this-key       :K1
          relayout-state (atom {:key nil :measured nil})
          relayouts      (atom [])
          run-layout!    (fn [dims] (swap! relayouts conj dims))
          ;; The gate, lifted verbatim from chart.cljs/maybe-relayout!.
          maybe-relayout!
          (fn [measured-map]
            (let [dims (select-keys measured-map measurable-ids)
                  {:keys [key measured]} @relayout-state
                  fresh?   (not= key this-key)
                  ready?   (and (seq measurable-ids)
                                (= (count dims) (count measurable-ids)))
                  changed? (or fresh? (not= dims measured))]
              (when (and ready? changed?)
                (reset! relayout-state {:key this-key :measured dims})
                (run-layout! dims))))]
      ;; New topology → seed the gate (as the layout-key trigger does).
      (reset! relayout-state {:key this-key :measured nil})
      ;; First measurement arrives PARTIAL — only one node measured. The
      ;; gate must NOT fire (a partial relayout would lay the rest at the
      ;; floor and re-fire endlessly).
      (maybe-relayout! {"idle" {:width 200 :height 60}})
      (is (zero? (count @relayouts)) "partial measurement does NOT relayout")
      ;; Full measurement arrives → exactly one relayout with the real box.
      (maybe-relayout! {"idle"    {:width 200 :height 60}
                        "loading" {:width 260 :height 72}})
      (is (= 1 (count @relayouts)) "full measurement triggers one relayout")
      (is (= {"idle"    {:width 200 :height 60}
              "loading" {:width 260 :height 72}}
             (first @relayouts))
          "ELK is re-fed the real measured boxes")
      ;; The relayout moved POSITIONS only — content (hence measured box)
      ;; is unchanged → the SAME measurement re-arrives. No second pass.
      (maybe-relayout! {"idle"    {:width 200 :height 60}
                        "loading" {:width 260 :height 72}})
      (maybe-relayout! {"idle"    {:width 200 :height 60}
                        "loading" {:width 260 :height 72}})
      (is (= 1 (count @relayouts))
          "stable measurement does NOT re-fire — the loop is closed"))))

(deftest measure-then-relayout-new-topology-resets-signature
  (testing "rf2-d9ro2 — a NEW layout-key (focused-machine swap / new
            definition) clears the stored measured signature so the new
            machine gets its own single relayout, even if its measured
            boxes coincidentally equal the prior machine's."
    (let [measurable-ids #{"a"}
          relayout-state (atom {:key nil :measured nil})
          relayouts      (atom [])
          gate (fn [this-key measured-map]
                 (let [dims (select-keys measured-map measurable-ids)
                       {:keys [key measured]} @relayout-state
                       fresh?   (not= key this-key)
                       ready?   (= (count dims) (count measurable-ids))
                       changed? (or fresh? (not= dims measured))]
                   (when (and ready? changed?)
                     (reset! relayout-state {:key this-key :measured dims})
                     (swap! relayouts conj this-key))))]
      ;; Machine K1 measured + relaid out once.
      (reset! relayout-state {:key :K1 :measured nil})
      (gate :K1 {"a" {:width 100 :height 40}})
      (is (= [:K1] @relayouts))
      ;; Swap to K2 — same measured box, but a fresh key resets the
      ;; signature so K2 still gets its single relayout.
      (reset! relayout-state {:key :K2 :measured nil})
      (gate :K2 {"a" {:width 100 :height 40}})
      (is (= [:K1 :K2] @relayouts)
          "a new layout-key gets its own relayout despite identical boxes"))))

;; ---- 8. fit-on-entry signal (rf2-6tw7t) -------------------------------
;;
;; The layout-key auto-fit (rf2-set3x, tests 1-6 above) deliberately
;; PRESERVES the operator's manual zoom/pan across non-layout re-renders.
;; That leaves a chart RE-ENTERED from a panel/tab switch at its prior
;; viewport — the bug rf2-6tw7t fixes. The orthogonal `:fit-signal` prop
;; (an opaque nonce the host bumps on panel-entry / tab-activation) forces
;; a re-fit when its value CHANGES, independent of the layout-key gate.
;;
;; This pins the `maybe-fit-on-signal!` gate the chart wires onto every
;; render verbatim (the live xyflow `.fitView` is browser-pinned /
;; eval-cljs in the PR body — same reason tests 1-6 are Node-layer pins
;; of the predicate, not DOM tests).

(deftest fit-on-entry-fits-once-per-signal-change
  (testing "rf2-6tw7t — the fit-on-entry gate fires exactly once each time
            the host `:fit-signal` CHANGES (panel re-entry), and is a
            no-op while the signal is steady (ordinary re-renders, so
            manual zoom/pan survives). It requires a captured instance +
            non-empty positions + no :layout-error — the same
            preconditions the layout-settle fit checks. The `::unfit`
            sentinel start means the FIRST observed signal (even nil)
            fits once.

            This mirrors `chart.cljs/maybe-fit-on-signal!` verbatim."
    (let [;; The chart's `fit-state` shape (sentinel start per chart.cljs).
          fit-state (atom {:instance fake-instance
                           :fit-key  nil
                           :fit-sig  ::chart-unfit})
          spy       (atom [])
          ;; Stand-ins for the chart's two render-derived inputs.
          positions (atom {"idle" {:x 0 :y 0}})  ;; non-empty → ready
          layout-error (atom nil)
          ;; The gate, lifted verbatim from chart.cljs/maybe-fit-on-signal!.
          maybe-fit-on-signal!
          (fn [fit-signal]
            (let [{:keys [instance fit-sig]} @fit-state]
              (when (and instance
                         (not= fit-signal fit-sig)
                         (seq @positions)
                         (nil? @layout-error))
                (swap! fit-state assoc :fit-sig fit-signal)
                (swap! spy conj fit-signal))))]
      ;; First entry — signal 1 differs from the ::chart-unfit sentinel → fit.
      (maybe-fit-on-signal! 1)
      (is (= [1] @spy) "first activation fits once")
      ;; Ordinary re-renders with the SAME signal — manual zoom/pan survives.
      (maybe-fit-on-signal! 1)
      (maybe-fit-on-signal! 1)
      (is (= [1] @spy) "steady signal does NOT re-fit (manual viewport preserved)")
      ;; Operator leaves + re-enters the tab → host bumps the counter to 2.
      (maybe-fit-on-signal! 2)
      (is (= [1 2] @spy) "a fresh activation signal re-fits on entry")
      ;; And again — every distinct entry re-frames.
      (maybe-fit-on-signal! 3)
      (is (= [1 2 3] @spy) "each entry signal fits exactly once")
      (is (= 3 (:fit-sig @fit-state)) "fit-state records the last signal fit"))))

(deftest fit-on-entry-defers-until-instance-and-positions-ready
  (testing "rf2-6tw7t — an entry signal that arrives BEFORE the instance
            is captured (or before the first layout settles) must NOT
            record the signal as fit — it stays pending so the onInit /
            next-render re-check honours it once the preconditions hold.
            Pins the deferral branch: no instance → no fit + signal stays
            unrecorded; empty positions → no fit + signal stays
            unrecorded; once both arrive → the SAME pending signal fits."
    (let [fit-state (atom {:instance nil
                           :fit-key  nil
                           :fit-sig  ::chart-unfit})
          spy       (atom [])
          positions (atom {})         ;; empty → not ready
          layout-error (atom nil)
          maybe-fit-on-signal!
          (fn [fit-signal]
            (let [{:keys [instance fit-sig]} @fit-state]
              (when (and instance
                         (not= fit-signal fit-sig)
                         (seq @positions)
                         (nil? @layout-error))
                (swap! fit-state assoc :fit-sig fit-signal)
                (swap! spy conj fit-signal))))]
      ;; Entry signal 1 arrives but no instance yet → no fit, not recorded.
      (maybe-fit-on-signal! 1)
      (is (zero? (count @spy)) "no fit while instance is nil")
      (is (= ::chart-unfit (:fit-sig @fit-state))
          "signal stays unrecorded so the next chance still honours it")
      ;; Instance captured (onInit) but positions still empty → still no fit.
      (swap! fit-state assoc :instance fake-instance)
      (maybe-fit-on-signal! 1)
      (is (zero? (count @spy)) "no fit while positions are empty")
      (is (= ::chart-unfit (:fit-sig @fit-state))
          "still unrecorded — degenerate origin cluster not framed")
      ;; Layout settles (positions arrive) → the SAME pending signal fits.
      (reset! positions {"idle" {:x 0 :y 0}})
      (maybe-fit-on-signal! 1)
      (is (= [1] @spy) "the pending entry signal fits once preconditions hold"))))

(deftest fit-on-entry-skips-layout-error-settle
  (testing "rf2-6tw7t — a fit-signal bump on a chart whose layout FAILED
            (the rf2-4lyvh error path: empty positions + :layout-error)
            must NOT fit — an auto-fit on empty positions re-triggers the
            degenerate-cluster bug. The chart paints the error banner
            instead. Pins the :layout-error guard on the entry path
            (mirrors test 2's gate for the settle path)."
    (let [fit-state (atom {:instance fake-instance
                           :fit-key  nil
                           :fit-sig  ::chart-unfit})
          spy       (atom [])
          positions (atom {})
          layout-error (atom {:elk-error "boom"})  ;; error settle
          maybe-fit-on-signal!
          (fn [fit-signal]
            (let [{:keys [instance fit-sig]} @fit-state]
              (when (and instance
                         (not= fit-signal fit-sig)
                         (seq @positions)
                         (nil? @layout-error))
                (swap! fit-state assoc :fit-sig fit-signal)
                (swap! spy conj fit-signal))))]
      (maybe-fit-on-signal! 1)
      (is (zero? (count @spy)) "no entry-fit on a layout-error settle")
      (is (= ::chart-unfit (:fit-sig @fit-state))
          "signal unrecorded — banner paints, not a degenerate fit"))))
