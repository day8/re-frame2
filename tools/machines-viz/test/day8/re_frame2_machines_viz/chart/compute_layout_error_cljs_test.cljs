(ns day8.re-frame2-machines-viz.chart.compute-layout-error-cljs-test
  "The `chart/compute-layout!` ELK error path.

  An ELK failure — a sync throw or an async reject — must reach `done-fn` as
  the layout-error result map (so the chart paints its banner instead of
  stacking every node at the origin) and fire exactly one
  `:rf.error/machines-viz-elk-layout-failed` trace, the event tools read off
  the bus. A clean layout must do neither.

  `chart/invoke-elk-layout!` is the seam that stands in for elkjs. The async
  tests rebind with `set!` because `with-redefs` unwinds before the Promise
  callback runs."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures ----------------------------------------------------------

(def ^:private sample-parsed
  {:nodes [{:id "idle"} {:id "loading"}]
   :edges [{:id "idle->loading"
            :source "idle"
            :target "loading"
            :event-label "start"}]
   :parallel? false})

(def ^:private input-summary
  "The `:input-summary` `sample-parsed` laid out `:tb` with no options yields."
  {:node-count 2 :edge-count 1 :region-count 0 :parallel? false
   :direction :tb :layout-option-ks []})

(defn- error-result [message]
  {:positions    {}
   :edge-points  {}
   :edge-labels  {}
   :layout-error {:error         {:message message :name "Error"}
                  :input-summary input-summary}})

(defn- error-trace
  "The one `[operation tags]` emit a failure produces. The raw layout-options
  stay off the tags; only their key-set rides on `:input-summary`."
  [message]
  [:rf.error/machines-viz-elk-layout-failed
   {:elk-error     {:message message :name "Error"}
    :machine-id    :test/machine
    :input-summary input-summary}])

;; ---- tests -------------------------------------------------------------

(deftest compute-layout-sync-throw-delivers-error-result-and-emits-trace
  (let [result   (atom nil)
        captured (atom [])
        orig     (.-error js/console)]
    ;; The failure path logs through console.error by design; keep the runner
    ;; output clean.
    (set! (.-error js/console) (fn [& _args] nil))
    (try
      (with-redefs [rf.trace/emit-error!      (fn [op tags] (swap! captured conj [op tags]))
                    chart/invoke-elk-layout!  (fn [_input] (throw (js/Error. "elk: malformed input")))]
        (chart/compute-layout! sample-parsed :tb nil :test/machine #(reset! result %)))
      (finally (set! (.-error js/console) orig)))
    (is (= (error-result "elk: malformed input") @result))
    (is (= [(error-trace "elk: malformed input")] @captured))))

(deftest compute-layout-async-reject-delivers-error-result-and-emits-trace
  (async done
    (let [captured     (atom [])
          orig-elk     chart/invoke-elk-layout!
          orig-emit    rf.trace/emit-error!
          orig-console (.-error js/console)]
      (set! (.-error js/console) (fn [& _args] nil))
      (set! chart/invoke-elk-layout!
            (fn [_input] (js/Promise.reject (js/Error. "elk: rejected"))))
      (set! rf.trace/emit-error!
            (fn [op tags] (swap! captured conj [op tags])))
      (chart/compute-layout!
        sample-parsed :tb nil :test/machine
        (fn [r]
          (is (= (error-result "elk: rejected") r))
          (is (= [(error-trace "elk: rejected")] @captured))
          (set! chart/invoke-elk-layout! orig-elk)
          (set! rf.trace/emit-error! orig-emit)
          (set! (.-error js/console) orig-console)
          (done))))))

(deftest compute-layout-happy-path-does-not-emit-error-trace
  (testing "a clean elk result reaches done-fn as positions, with no
            :layout-error and no error trace"
    (async done
      (let [captured  (atom [])
            orig-elk  chart/invoke-elk-layout!
            orig-emit rf.trace/emit-error!
            node      (fn [id y] #js {:id id :x 0 :y y :width 100 :height 50 :children #js []})]
        (set! chart/invoke-elk-layout!
              (fn [_input]
                (js/Promise.resolve #js {:id       "root"
                                         :children (array (node "idle" 0) (node "loading" 60))
                                         :edges    #js []})))
        (set! rf.trace/emit-error! (fn [op tags] (swap! captured conj [op tags])))
        (chart/compute-layout!
          sample-parsed :tb nil :test/machine
          (fn [r]
            (is (= {:positions   {"idle"    {:x 0 :y 0  :width 100 :height 50}
                                  "loading" {:x 0 :y 60 :width 100 :height 50}}
                    :edge-points {}
                    :edge-labels {}}
                   r))
            (is (empty? @captured))
            (set! chart/invoke-elk-layout! orig-elk)
            (set! rf.trace/emit-error! orig-emit)
            (done)))))))
