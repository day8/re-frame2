(ns day8.re-frame2-machines-viz.chart.compute-layout-error-cljs-test
  "rf2-4lyvh — pins for the `chart/compute-layout!` ELK error path.

  ## What this guards

  Before rf2-4lyvh, `compute-layout!` had two `(catch :default _ nil)`
  clauses that discarded ELK errors entirely; the downstream
  `(when result ...)` guard in MachineChart then no-op'd, leaving
  every node at the default origin (stacked boxes; no console output;
  no diagnostic). The fix surfaces ELK failures THREE ways — the bus
  (`:rf.error/machines-viz-elk-layout-failed`), a dev-only
  `console.error`, AND a `:layout-error` slot on the callback result-
  map so the chart can render an in-panel banner.

  This suite pins:

    1. SYNC ELK throw → callback receives the layout-error result-map
       (positions/edge-points empty + `:layout-error` slot present).
    2. ASYNC ELK reject → same callback shape arrives.
    3. The canonical `:rf.error/machines-viz-elk-layout-failed` trace
       event fires (op-type `:error`, the right operation, the right
       tag shape including machine-id) — and ONLY once per failure.
    4. NEGATIVE: a successful layout does NOT emit the error trace
       AND the callback receives a result without `:layout-error`.

  ## Why this is a `-cljs-test` (node), not a DOM test

  `compute-layout!` is JS-interop-heavy but the elkjs Promise + the
  trace bus are both Node-runnable. We rebind the private
  `chart/invoke-elk-layout!` (an indirection added precisely so tests
  can substitute the elkjs call) + `re-frame.trace/emit-error!` so
  the suite never reaches the real elkjs runtime. No DOM needed."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [re-frame.trace :as trace]))

;; ---- fixtures ----------------------------------------------------------

(def ^:private sample-parsed
  "Minimal `parse-definition` shape sufficient for `compute-layout!`'s
  `->elk-input` projection. Two flat states, one transition."
  {:nodes [{:id "idle"} {:id "loading"}]
   :edges [{:id "idle->loading"
            :source "idle"
            :target "loading"
            :event-label "start"}]
   :parallel? false})

(defn- silence-console!
  "Stub `js/console.error` to a no-op for the duration of `f`. The
  failure path under test deliberately calls `console.error` (dev-
  build operator affordance per rf2-4lyvh), so the test would
  otherwise spam the test runner's stdout with stack traces that
  read as failures. Returns the value of `(f)`."
  [f]
  (let [orig (.-error js/console)]
    (set! (.-error js/console) (fn [& _args] nil))
    (try (f)
         (finally (set! (.-error js/console) orig)))))

(defn- capture-trace-emits
  "Run `f` with `trace/emit-error!` rebound to capture the (operation,
  tags) calls into the returned atom, AND with `js/console.error`
  silenced. Returns the atom."
  [f]
  (let [captured (atom [])]
    (silence-console!
      (fn []
        (with-redefs [trace/emit-error! (fn [op tags]
                                          (swap! captured conj [op tags]))]
          (f captured))))
    captured))

;; ---- sync ELK throw ----------------------------------------------------

(deftest compute-layout-sync-throw-delivers-error-result
  (testing "When elkjs throws synchronously, `done-fn` receives the
            canonical layout-error result-map (empty positions/edge-
            points + :layout-error slot). The downstream `(when result
            ...)` guard sees a TRUTHY map, so the chart reset!s
            layout-state and paints the banner instead of silently
            no-op'ing."
    (let [result-promise (atom nil)]
      (silence-console!
        (fn []
          (with-redefs [chart/invoke-elk-layout!
                        (fn [_input]
                          (throw (js/Error. "elk: malformed input")))
                        trace/emit-error! (fn [_op _tags] nil)]
            (chart/compute-layout! sample-parsed :tb nil :test/machine
                                   (fn [r] (reset! result-promise r))))))
      (let [r @result-promise]
        (is (map? r) "callback was called with a non-nil map")
        (is (= {} (:positions r)) "empty positions on failure")
        (is (= {} (:edge-points r)) "empty edge-points on failure")
        (is (some? (:layout-error r))
            ":layout-error slot carries the failure")
        (is (= "elk: malformed input"
               (get-in r [:layout-error :error :message]))
            ":layout-error :error :message carries the ELK error")
        (is (= 2 (get-in r [:layout-error :input-summary :node-count]))
            ":layout-error :input-summary carries the input counts")))))

;; ---- async ELK reject --------------------------------------------------

(deftest compute-layout-async-reject-delivers-error-result
  (testing "When elkjs returns a rejected Promise, the .catch handler
            invokes `done-fn` with the same layout-error result-map
            shape the sync branch produces. `set!`-based stubbing
            (not `with-redefs`) so the stubs survive the async
            microtask boundary the rejection rides."
    (async done
      (let [orig-elk     chart/invoke-elk-layout!
            orig-emit    trace/emit-error!
            orig-console (.-error js/console)]
        (set! (.-error js/console) (fn [& _args] nil))
        (set! chart/invoke-elk-layout!
              (fn [_input] (js/Promise.reject (js/Error. "elk: rejected"))))
        (set! trace/emit-error! (fn [_op _tags] nil))
        (chart/compute-layout!
          sample-parsed :tb nil :test/machine
          (fn [r]
            (is (map? r))
            (is (= {} (:positions r)))
            (is (some? (:layout-error r)))
            (is (= "elk: rejected"
                   (get-in r [:layout-error :error :message])))
            (set! chart/invoke-elk-layout! orig-elk)
            (set! trace/emit-error! orig-emit)
            (set! (.-error js/console) orig-console)
            (done)))))))

;; ---- trace event emit --------------------------------------------------

(deftest compute-layout-sync-throw-emits-rf-error-trace
  (testing "On sync ELK failure, `trace/emit-error!` fires ONCE with
            operation = :rf.error/machines-viz-elk-layout-failed,
            tags = {:elk-error … :machine-id … :input-summary …}.
            This is the contract tools (Xray Issues panel, off-box
            monitors) read off the bus."
    (let [captured (capture-trace-emits
                     (fn [_]
                       (with-redefs [chart/invoke-elk-layout!
                                     (fn [_input]
                                       (throw (js/Error. "boom")))]
                         (chart/compute-layout! sample-parsed :tb nil
                                                :test/machine
                                                (fn [_r] nil)))))
          emits    @captured]
      (is (= 1 (count emits))
          "exactly one error trace event per failure (no double-emit)")
      (let [[op tags] (first emits)]
        (is (= :rf.error/machines-viz-elk-layout-failed op)
            "canonical operation keyword")
        (is (= :test/machine (:machine-id tags))
            ":machine-id carries through onto the trace tags")
        (is (= "boom" (get-in tags [:elk-error :message]))
            ":elk-error :message carries the ELK error message")
        (is (= 2 (get-in tags [:input-summary :node-count]))
            ":input-summary carries node-count")
        (is (= :tb (get-in tags [:input-summary :direction]))
            ":input-summary carries direction")
        (is (not (contains? tags :layout-options))
            "raw caller-supplied layout-options are NOT on the trace
            (only the key-set, on :input-summary)")))))

(deftest compute-layout-async-reject-emits-rf-error-trace
  (testing "On async ELK rejection, the same `:rf.error/machines-viz-
            elk-layout-failed` event fires (op-type :error).

            `with-redefs` is synchronous-scoped — it unwinds at body
            exit, which arrives BEFORE the rejected-promise microtask
            runs. So we install the stubs via `set!` (CLJS's mutable
            Var rebind), exercise the async path, then restore the
            originals inside `done-fn`. Asserts run before `(done)`
            so cljs.test's async machinery sees them."
    (async done
      (let [captured     (atom [])
            orig-elk     chart/invoke-elk-layout!
            orig-emit    trace/emit-error!
            orig-console (.-error js/console)]
        (set! (.-error js/console) (fn [& _args] nil))
        (set! chart/invoke-elk-layout!
              (fn [_input] (js/Promise.reject (js/Error. "async-boom"))))
        (set! trace/emit-error!
              (fn [op tags] (swap! captured conj [op tags])))
        (chart/compute-layout!
          sample-parsed :tb nil :test/machine
          (fn [_r]
            (let [emits @captured]
              (is (= 1 (count emits))
                  "one emit on async reject")
              (let [[op tags] (first emits)]
                (is (= :rf.error/machines-viz-elk-layout-failed op))
                (is (= "async-boom"
                       (get-in tags [:elk-error :message])))
                (is (= :test/machine (:machine-id tags)))))
            ;; Restore — async tests must clean up their globals so
            ;; following tests start from a clean slate.
            (set! chart/invoke-elk-layout! orig-elk)
            (set! trace/emit-error! orig-emit)
            (set! (.-error js/console) orig-console)
            (done)))))))

;; ---- negative: happy path keeps existing contract ---------------------

(deftest compute-layout-happy-path-does-not-emit-error-trace
  (testing "When elkjs resolves cleanly, NO `:rf.error/*` trace fires
            AND the callback receives a result WITHOUT :layout-error.
            Guards against an over-eager emit that would put the
            Issues panel in a red state on every render. Async path
            via `set!` (see the rationale on the reject test above)."
    (async done
      (let [captured  (atom [])
            orig-elk  chart/invoke-elk-layout!
            orig-emit trace/emit-error!
            ;; Build a minimally-shaped elk JS result (.children +
            ;; .edges) so `elk-result->positions` walks it without
            ;; throwing — we don't care about positions here, only
            ;; that the success branch runs.
            ok-result #js {:id "root"
                           :children #js [#js {:id "idle" :x 0 :y 0
                                               :width 100 :height 50
                                               :children #js []}
                                          #js {:id "loading" :x 0 :y 60
                                               :width 100 :height 50
                                               :children #js []}]
                           :edges #js []}]
        (set! chart/invoke-elk-layout! (fn [_input] (js/Promise.resolve ok-result)))
        (set! trace/emit-error! (fn [op tags] (swap! captured conj [op tags])))
        (chart/compute-layout!
          sample-parsed :tb nil :test/machine
          (fn [r]
            (is (map? r))
            (is (nil? (:layout-error r))
                "happy path → no :layout-error")
            (is (zero? (count @captured))
                "happy path → no :rf.error/* emits")
            (set! chart/invoke-elk-layout! orig-elk)
            (set! trace/emit-error! orig-emit)
            (done)))))))
