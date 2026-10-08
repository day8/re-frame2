(ns day8.re-frame2-xray.panels.machine-after-rings-fresco-boundary-dom-cljs-test
  "The `:after`-rings overlay's Fresco boundary, read off a real React
  commit.

  `machine-after-rings/AfterRingsOverlay` is an `rf.fresco/defview` whose
  whole job is to DELEGATE to the machines-viz `AfterRingsOverlay`, a
  Reagent component Fresco can neither take as a hiccup head (HD-016) nor
  call. It crosses as a React element — `overlay-tree`'s `:as-child` —
  with its CLJS props map carried by identity. W1 asserts the foreign
  component's OWN committed DOM, which exists only if that crossing
  worked, and that the boundary's read lands in the frame the enclosing
  `frame-provider` named. W5 asserts the overlay's `:ref` callback clears
  the rAF tick loop's `:mounted?` liveness on unmount.

  The hover interaction is `machine_after_rings_cljs_test`'s
  `hover-dispatch-lands-on-the-render-frame`: the machines-viz overlay
  paints its hover targets only after measuring a rendered xyflow chart.

  The ns ends in `-dom-cljs-test`, so it runs under `:browser-test`; the
  `:node-test` build also loads it, where every row reports the skip
  through [[browser?]]."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(def ^:private app-frame
  "An ordinary application frame: W1's negative half of the frame-targeting
  claim."
  ::app)

(def ^:private timers-q
  "The overlay's primary read, which is also its sub-cache key."
  [:rf.xray/active-timers-for-focused-machine])

(def ^:private overlay-sel
  "The machines-viz overlay's own committed root."
  "[data-testid=\"rf-xray-machine-inspector-after-rings-overlay\"]")

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     ;; `:ambient-frame nil`: the default ambient `:rf/default` scope would
     ;; shadow the React-context tier W1 measures.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; A mounted overlay kicks the rAF clock from its render.
                      (after-rings/stop-tick!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture does not reset.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup!
  "Register Xray's handlers, install the test-only `now-ms` override seam,
  and make both frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed-one-armed-timer!
  "Put exactly one ARMED `:after` timer, on a focused machine, in front of
  the overlay. Targets `:rf/xray` only: `seed-trace-for-test!` snapshots
  the trace rings into `:rf/xray`'s `:trace-buffer` slot and no other."
  []
  (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test
                     [:auth/login]] {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-machine-definitions-override-for-test
                     {:auth/login fixture-definition}] {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test 2000]
                    {:frame :rf/xray})
  (rf/dispatch-sync
    [:rf.xray/set-epoch-history-for-test
     [{:epoch-id 1
       :trace-events
       [{:id 1 :time 10 :operation :rf.machine/transition
         :tags {:machine-id           :auth/login
                :before               {:state :idle :data {}}
                :after                {:state :authing :data {}}
                :event                [:auth/submit]
                :rf.trace/dispatch-id "d-1"}}]}]]
    {:frame :rf/xray})
  (trace-collector/seed-trace-for-test!
    {:id 1000 :time 1000
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id :auth/login
            :state :idle
            :delay 5000
            :delay-source :literal
            :epoch 0}})
  nil)

(defn- mount-overlay!
  "Mount the overlay the way `machine-canvas/Chart` does — through the
  public `AfterRingsOverlay-bridge` — inside a `frame-provider` scoping
  `frame`. Committed synchronously: React 19's `root.render` is async."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [after-rings/AfterRingsOverlay-bridge]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync`, so the `:ref` callback has RUN by the next
  line."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- cache-of
  "The frame's live sub-cache map; throws if the frame is not live."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- mounted-flag
  "The rAF tick loop's `:mounted?` liveness. TWO derefs: `@#'v` unwraps the
  Var to the atom, the second reads its map — with one, every key reads
  nil and W5's closed-gate assertions would pass measuring nothing."
  []
  (:mounted? @@#'day8.re-frame2-xray.panels.machine-after-rings/tick-state))

(deftest w1-overlay-paints-through-the-seam-and-reads-the-named-frame
  (testing "the machines-viz Reagent component behind the substrate seam
            paints its own root with the projection's ring, and the
            boundary's read resolves against the frame the enclosing
            `frame-provider` named"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the zero below is measured
            ;; by an instrument shown able to see that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            _ (seed-one-armed-timer!)
            {:keys [container root]} (mount-overlay! :rf/xray)]
        (try
          (is (= "1" (.getAttribute (q container overlay-sel) "data-ring-count"))
              "THE SEAM: the foreign overlay committed its own root and got the
               one ring-spec — props crossed by identity, not camelCased or
               `clj->js`ed into nil")
          (is (pos? (ref-count-of :rf/xray timers-q))
              (str "the read holds a reference in :rf/xray's sub-cache. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame timers-q))
              "and NOT in the application frame's")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the same instrument does see the probe's entry there")
          (finally
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))

(deftest w5-unmount-clears-the-tick-loop-mount-gate
  (testing "mounting the boundary arms the rAF clock's `:mounted?` liveness
            and unmounting CLEARS it, because Fresco carried the `:ref`
            callback through to React. Without it the off-render 60Hz loop
            outlives its panel."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed-one-armed-timer!)]
        (after-rings/stop-tick!)
        (is (false? (boolean (mounted-flag)))
            "PRECONDITION: nothing holds the gate open before the mount")
        (let [{:keys [container root]} (mount-overlay! :rf/xray)]
          (is (true? (boolean (mounted-flag)))
              "the mounted overlay armed the tick loop's liveness")
          (teardown! root container)
          (is (false? (boolean (mounted-flag)))
              "the unmount CLEARED it: React invoked the `:ref` callback with nil"))))))
