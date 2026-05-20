(ns re-frame.view-rendered-op-cljs-test
  "Per rf2-25zo2 — the substrate-agnostic `:rf.view/rendered` op fires
  alongside `:view/render` for every render of a registered view, and
  carries the cascade-attribution slots Causa's Reactive panel uses to
  graph cause→effect for re-renders.

  This test exercises the Reagent adapter side of the op; UIx and Helix
  ship their own mirror tests. The emit site is the substrate-agnostic
  `views.cljs` frame-aware-view wrapper, so all three adapter tests pin
  the same shape — drift would surface as one of the three tests
  diverging from the locked tag set.

  Locked tags (rf2-25zo2 acceptance):

    :frame          — the frame the render landed in
    :view-id        — the registered view id
    :render-key     — [view-id instance-token] (parity with :view/render)
    :cause-event-id — (when in-cascade) the dispatching cascade's event-id
    :cause-subs     — (when in-cascade) sub-ids that ran in the cascade,
                      distinct, first-seen order, capped at 100"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.epoch]) ;; load so :epoch/cascade-cause hook is bound
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- record-traces!
  "Attach a listener that captures every `:rf.view/rendered` trace into
  an atom. Returns the atom; caller is responsible for
  `unregister-trace-listener!`."
  []
  (let [recorded (atom [])]
    (trace-tooling/register-trace-listener! ::recorder
      (fn [ev]
        (when (= :rf.view/rendered (:operation ev))
          (swap! recorded conj ev))))
    recorded))

(defn- record-by-op!
  "Variant that captures multiple ops into a per-op map of vectors."
  [op-set]
  (let [recorded (atom {})]
    (trace-tooling/register-trace-listener! ::recorder
      (fn [ev]
        (when (contains? op-set (:operation ev))
          (swap! recorded update (:operation ev) (fnil conj []) ev))))
    recorded))

;; ---- emission ---------------------------------------------------------------

(deftest rf-view-rendered-fires-on-every-render
  (testing "every render of a registered view emits one :rf.view/rendered
   alongside :view/render — same emit site, two ops"
    (let [observed (record-by-op! #{:view/render :rf.view/rendered})]
      (rf/reg-view ^{:rf/id :rf2-25zo2/sample} sample-view []
        [:span "ok"])
      (let [render (rf/view :rf2-25zo2/sample)]
        (render)
        (render)
        (render))
      (is (= 3 (count (:view/render @observed))) "three :view/render emits")
      (is (= 3 (count (:rf.view/rendered @observed))) "three :rf.view/rendered emits")
      (trace-tooling/unregister-trace-listener! ::recorder))))

(deftest rf-view-rendered-carries-view-id-and-frame
  (testing ":rf.view/rendered carries :view-id, :frame and :render-key
   on every emit"
    (let [traces (record-traces!)]
      (rf/reg-view ^{:rf/id :rf2-25zo2/shape} shape-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/shape))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (= :rf2-25zo2/shape (:view-id t)) ":view-id matches the registered id")
        (is (some? (:frame t)) ":frame is present")
        (is (vector? (:render-key t)) ":render-key is a tuple")
        (is (= :rf2-25zo2/shape (first (:render-key t)))
            ":render-key's first slot is the view-id"))
      (trace-tooling/unregister-trace-listener! ::recorder))))

(deftest rf-view-rendered-carries-cause-event-id-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :cause-event-id — the in-flight cascade's :event/run-start event-id,
   sourced from the epoch capture buffer at emit time (rf2-25zo2). The
   attribution is meant for Causa's Reactive panel to graph cause→effect
   for re-renders."
    (let [traces (record-traces!)]
      (rf/reg-view ^{:rf/id :rf2-25zo2/with-cause} cause-view []
        [:span "x"])

      ;; Render INSIDE a dispatched event so the in-flight cascade
      ;; buffer has the :event/run-start the attribution walk consumes.
      (let [render (rf/view :rf2-25zo2/with-cause)]
        (rf/reg-event-fx :rf2-25zo2/render-during-cascade
          (fn [_ _]
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/render-during-cascade]))

      (let [ev (first (filter #(some? (get-in % [:tags :cause-event-id]))
                              @traces))]
        (is (some? ev)
            "at least one :rf.view/rendered fired inside a cascade with attribution")
        (when ev
          (is (= :rf2-25zo2/render-during-cascade
                 (get-in ev [:tags :cause-event-id]))
              ":cause-event-id matches the dispatching event-id")))
      (trace-tooling/unregister-trace-listener! ::recorder))))

(deftest rf-view-rendered-carries-cause-subs-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :cause-subs — distinct sub-ids that ran in the cascade before the
   render, sourced from the epoch capture buffer. A sub run by the
   event handler before the render kicked off is the canonical upstream-
   sub case the Reactive panel uses to attribute re-renders to the
   sub-recomputes that drove them."
    (let [traces (record-traces!)]
      (rf/reg-sub :rf2-25zo2/n (fn [_ _] 7))

      (rf/reg-view ^{:rf/id :rf2-25zo2/with-upstream-sub} upstream-sub-view []
        [:span "x"])

      (let [render (rf/view :rf2-25zo2/with-upstream-sub)]
        (rf/reg-event-fx :rf2-25zo2/cascade-with-sub
          (fn [_ _]
            ;; Run a sub from inside the handler so :sub/run lands in
            ;; the cascade buffer BEFORE the render emits.
            @(rf/subscribe [:rf2-25zo2/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/cascade-with-sub]))

      (let [ev (first (filter #(some? (get-in % [:tags :cause-subs]))
                              @traces))]
        (is (some? ev) "an :rf.view/rendered carries :cause-subs")
        (when ev
          (let [subs (get-in ev [:tags :cause-subs])]
            (is (vector? subs) ":cause-subs is a vector")
            (is (some #{:rf2-25zo2/n} subs)
                ":cause-subs contains the sub-id that ran upstream of the render"))))
      (trace-tooling/unregister-trace-listener! ::recorder))))

(deftest rf-view-rendered-omits-attribution-when-no-cascade
  (testing "a render outside any cascade (e.g. headless direct invocation
   with no in-flight buffer) emits :rf.view/rendered with :cause-event-id
   and :cause-subs simply absent — consumers see the marker but no
   misleading attribution"
    (let [traces (record-traces!)]
      (rf/reg-view ^{:rf/id :rf2-25zo2/no-cascade} no-cascade-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/no-cascade))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) ":rf.view/rendered still fires outside a cascade")
        (is (= :rf2-25zo2/no-cascade (:view-id t)) ":view-id present")
        (is (not (contains? t :cause-event-id))
            ":cause-event-id omitted when no cascade is in flight")
        (is (not (contains? t :cause-subs))
            ":cause-subs omitted when no cascade is in flight"))
      (trace-tooling/unregister-trace-listener! ::recorder))))
