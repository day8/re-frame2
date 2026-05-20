(ns re-frame.adapter.uix-view-rendered-op-cljs-test
  "Per rf2-25zo2 — adapter-parity mirror of the Reagent
  `view-rendered-op` test (`implementation/adapters/reagent/test/re_frame/
  view_rendered_op_cljs_test.cljs`) for the UIx adapter.

  The emit site is in the substrate-agnostic `views.cljs` frame-aware-
  view wrapper, so the same `:rf.view/rendered` op fires under every
  React-shaped adapter. This test pins that the UIx adapter's late-bind
  hook stack composes correctly with the views.cljs emit path — same
  ops, same tag shape — and that a UIx-installed runtime sees identical
  cascade-attribution.

  Adapter parity is the load-bearing contract: a regression that broke
  the emit under UIx (e.g. an adapter-routed hook intercepting the
  late-bind `:trace/emit!` lookup) would fail here but pass under the
  Reagent companion test, surfacing the substrate-specific drift.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.epoch])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter uix-adapter/adapter}))

(defn- record-traces! []
  (let [recorded (atom [])]
    (trace-tooling/register-trace-listener! ::recorder
      (fn [ev]
        (when (= :rf.view/rendered (:operation ev))
          (swap! recorded conj ev))))
    recorded))

(deftest uix-rf-view-rendered-fires-on-render
  (testing ":rf.view/rendered fires under the UIx adapter — same emit
   site as Reagent, same tag shape"
    (let [traces (record-traces!)]
      (rf/reg-view ^{:rf/id :rf2-25zo2.uix/sample} sample-view []
        [:span "ok"])
      ((rf/view :rf2-25zo2.uix/sample))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event fired under UIx")
        (is (= :rf2-25zo2.uix/sample (:view-id t)) ":view-id matches")
        (is (some? (:frame t)) ":frame present")
        (is (vector? (:render-key t)) ":render-key is a tuple"))
      (trace-tooling/unregister-trace-listener! ::recorder))))

(deftest uix-rf-view-rendered-attribution-in-cascade
  (testing ":rf.view/rendered emitted inside a UIx cascade carries
   :cause-event-id + :cause-subs sourced from the in-flight epoch
   capture buffer — same attribution dataset as the Reagent test"
    (let [traces (record-traces!)]
      (rf/reg-sub :rf2-25zo2.uix/n  (fn [_ _] 1))

      (rf/reg-view ^{:rf/id :rf2-25zo2.uix/with-sub} with-sub-view []
        [:span "x"])

      (let [render (rf/view :rf2-25zo2.uix/with-sub)]
        (rf/reg-event-fx :rf2-25zo2.uix/cascade
          (fn [_ _]
            @(rf/subscribe [:rf2-25zo2.uix/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2.uix/cascade]))

      (let [ev (first (filter #(some? (get-in % [:tags :cause-event-id]))
                              @traces))]
        (is (some? ev) "at least one in-cascade :rf.view/rendered")
        (when ev
          (let [t (:tags ev)]
            (is (= :rf2-25zo2.uix/cascade (:cause-event-id t)))
            (is (some #{:rf2-25zo2.uix/n} (:cause-subs t))))))
      (trace-tooling/unregister-trace-listener! ::recorder))))
