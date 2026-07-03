(ns re-frame.view-rendered-op-cljs-test
  "Per rf2-25zo2 — the substrate-agnostic `:rf.view/rendered` op fires
  alongside `:rf.view/render` for every render of a registered view, and
  carries the cascade-attribution slots Xray's Reactive panel uses to
  graph cause→effect for re-renders.

  This test exercises the Reagent adapter side of the op; UIx and Helix
  ship their own mirror tests. The emit site is the substrate-agnostic
  `views.cljs` frame-aware-view wrapper, so all three adapter tests pin
  the same shape — drift would surface as one of the three tests
  diverging from the locked tag set.

  Locked tags (rf2-25zo2 acceptance):

    :frame          — the frame the render landed in
    :rf.view/id        — the registered view id
    :rf.view/render-key     — [view-id instance-token] (parity with :rf.view/render)
    :rf.view/cause-event-id — (when in-cascade) the dispatching cascade's event-id
    :rf.view/cause-subs     — (when in-cascade) sub-ids that ran in the cascade,
                      distinct, first-seen order, capped at 100"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.epoch]) ;; load so :epoch/run-cause hook is bound
  (:require-macros [re-frame.core :refer [reg-view]]
                   [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private view-rendered-pred
  #(= :rf.view/rendered (:operation %)))

(defn- in-op-set [op-set]
  (fn [ev] (contains? op-set (:operation ev))))

;; ---- emission ---------------------------------------------------------------

(deftest rf-view-rendered-fires-on-every-render
  (testing "every render of a registered view emits one :rf.view/rendered
   alongside :rf.view/render — same emit site, two ops"
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.view/render :rf.view/rendered})
                                     :shape :by-op}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/sample} sample-view []
        [:span "ok"])
      (let [render (rf/view :rf2-25zo2/sample)]
        (render)
        (render)
        (render))
      (is (= 3 (count (:rf.view/render @observed))) "three :rf.view/render emits")
      (is (= 3 (count (:rf.view/rendered @observed))) "three :rf.view/rendered emits"))))

(deftest rf-view-rendered-carries-view-id-and-frame
  (testing ":rf.view/rendered carries :rf.view/id, :frame and :rf.view/render-key
   on every emit"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/shape} shape-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/shape))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (= :rf2-25zo2/shape (:rf.view/id t)) ":rf.view/id matches the registered id")
        (is (some? (:frame t)) ":frame is present")
        (is (vector? (:rf.view/render-key t)) ":rf.view/render-key is a tuple")
        (is (= :rf2-25zo2/shape (first (:rf.view/render-key t)))
            ":rf.view/render-key's first slot is the view-id")))))

(deftest rf-view-rendered-carries-cause-event-id-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :rf.view/cause-event-id — the in-flight cascade's :event/run-start event-id,
   sourced from the epoch capture buffer at emit time (rf2-25zo2). The
   attribution is meant for Xray's Reactive panel to graph cause→effect
   for re-renders."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/with-cause} cause-view []
        [:span "x"])

      ;; Render INSIDE a dispatched event so the in-flight cascade
      ;; buffer has the :event/run-start the attribution walk consumes.
      (let [render (rf/view :rf2-25zo2/with-cause)]
        (rf/reg-event :rf2-25zo2/render-during-cascade
          (fn [_ _]
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/render-during-cascade]))

      (let [ev (first (filter #(some? (get-in % [:tags :rf.view/cause-event-id]))
                              @traces))]
        (is (some? ev)
            "at least one :rf.view/rendered fired inside a cascade with attribution")
        (when ev
          (is (= :rf2-25zo2/render-during-cascade
                 (get-in ev [:tags :rf.view/cause-event-id]))
              ":rf.view/cause-event-id matches the dispatching event-id"))))))

(deftest rf-view-rendered-carries-cause-subs-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :rf.view/cause-subs — distinct sub-ids that ran in the cascade before the
   render, sourced from the epoch capture buffer. A sub run by the
   event handler before the render kicked off is the canonical upstream-
   sub case the Reactive panel uses to attribute re-renders to the
   sub-recomputes that drove them."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-sub :rf2-25zo2/n (fn [_ _] 7))

      (rf/reg-view ^{:rf/id :rf2-25zo2/with-upstream-sub} upstream-sub-view []
        [:span "x"])

      (let [render (rf/view :rf2-25zo2/with-upstream-sub)]
        (rf/reg-event :rf2-25zo2/cascade-with-sub
          (fn [_ _]
            ;; Run a sub from inside the handler so :rf.sub/run lands in
            ;; the cascade buffer BEFORE the render emits.
            @(rf/subscribe [:rf2-25zo2/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/cascade-with-sub]))

      (let [ev (first (filter #(some? (get-in % [:tags :rf.view/cause-subs]))
                              @traces))]
        (is (some? ev) "an :rf.view/rendered carries :rf.view/cause-subs")
        (when ev
          (let [subs (get-in ev [:tags :rf.view/cause-subs])]
            (is (vector? subs) ":rf.view/cause-subs is a vector")
            (is (some #{:rf2-25zo2/n} subs)
                ":rf.view/cause-subs contains the sub-id that ran upstream of the render")))))))

(deftest rf-view-rendered-carries-elapsed-ms
  (testing ":rf.view/rendered carries :rf.view/elapsed-ms — the wall-clock
   duration of the user render-fn for this render (rf2-8wrzz.1). Present on
   every dev-build render (the timing rides interop/debug-enabled?)."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-8wrzz1/timed} timed-view []
        [:span "x"])
      ((rf/view :rf2-8wrzz1/timed))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (contains? t :rf.view/elapsed-ms) ":rf.view/elapsed-ms is present")
        (is (number? (:rf.view/elapsed-ms t)) ":rf.view/elapsed-ms is a number")
        (is (>= (:rf.view/elapsed-ms t) 0) ":rf.view/elapsed-ms is non-negative")))))

(deftest rf-view-rendered-carries-render-args
  (testing ":rf.view/rendered carries :rf.view/render-args — the vector of
   positional render args/props passed to THIS render (rf2-rpgq8). Captured
   by the substrate-agnostic views.cljs frame-aware-view wrapper. A no-arg
   render omits the slot (additive — existing consumers unaffected)."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-rpgq8/with-args} args-view [_label _n]
        [:span "ok"])
      ((rf/view :rf2-rpgq8/with-args) {:label "hi"} 42)
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (= [{:label "hi"} 42] (:rf.view/render-args t))
            ":rf.view/render-args is the vector of positional render args")))))

(deftest rf-view-rendered-omits-render-args-on-no-arg-render
  (testing ":rf.view/render-args is ABSENT on a no-arg render (rf2-rpgq8 —
   additive contract)."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-rpgq8/no-args} no-args-view []
        [:span "static"])
      ((rf/view :rf2-rpgq8/no-args))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) ":rf.view/rendered still fires")
        (is (not (contains? t :rf.view/render-args))
            ":rf.view/render-args omitted when the view took no args")))))

(deftest rf-view-rendered-render-args-elided-at-emit
  (testing "PRIVACY (rf2-rpgq8 / Spec 009 §Privacy): render args are
   arbitrary user data, so :rf.view/render-args routes through the SAME
   emit-time elision chokepoint as :rf.event/db — the marks projection runs
   `elide-wire-value` against the frame's app-db elision registry. A
   frame-declared `:sensitive` app-db path inside a render arg reaches the
   trace surface as :rf/redacted, never raw."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      ;; EP-0025: durable app-db classification rides the commit-plane
      ;; classification effects. Seed the [:auth :password] sensitive
      ;; declaration on this frame's elision registry (index-free :rf/path)
      ;; via `elision/apply-classification-effects` — the same registry the
      ;; egress projection consults to elide the render arg at emit, and the
      ;; same write a `reg-event` returning `:sensitive` performs. The fixture
      ;; reg-frames the ambient :rf/default the render lands in.
      (frame/swap-runtime-db! :rf/default
        (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
      (rf/reg-view ^{:rf/id :rf2-rpgq8/sensitive} sensitive-view [_props]
        [:span "ok"])
      ((rf/view :rf2-rpgq8/sensitive) {:auth {:username "ada" :password "hunter2"}})
      (let [ev   (first @traces)
            arg0 (first (get-in ev [:tags :rf.view/render-args]))]
        (is (some? ev) "an :rf.view/rendered event was emitted")
        (is (= :rf/redacted (get-in arg0 [:auth :password]))
            "the [:auth :password] leaf inside the render arg is redacted at emit")
        (is (= "ada" (get-in arg0 [:auth :username]))
            "a non-sensitive sibling leaf is preserved")))))

(deftest rf-sub-run-carries-elapsed-ms
  (testing ":rf.sub/run carries :rf.sub/elapsed-ms — the wall-clock duration
   of the sub body recompute (rf2-hhh92). The reactive memo wrapper brackets
   the body with interop/now-ms inside the debug-enabled? gate, so the dev
   trace stream carries per-op timing for the Trace panel's DURATION column.
   Driven through a real reactive recompute (the plain-atom JVM substrate
   does not run the memo wrapper, so this lives in the adapter test)."
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.sub/run})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-hhh92/n (fn [_ _] 42))
      ;; A fresh subscribe forces the body's first recompute → :rf.sub/run.
      (rf/reg-event :rf2-hhh92/touch-sub
        (fn [_ _]
          @(rf/subscribe [:rf2-hhh92/n])
          {}))
      (rf/dispatch-sync [:rf2-hhh92/touch-sub])
      (let [sub-runs (:rf.sub/run @observed)]
        (is (seq sub-runs) "at least one :rf.sub/run emitted")
        (doseq [ev sub-runs]
          (let [t (:tags ev)]
            (is (contains? t :rf.sub/elapsed-ms) ":rf.sub/elapsed-ms is present")
            (is (number? (:rf.sub/elapsed-ms t)) ":rf.sub/elapsed-ms is a number")
            (is (>= (:rf.sub/elapsed-ms t) 0) ":rf.sub/elapsed-ms is non-negative")))))))

(deftest rf-view-rendered-carries-triggered-by-when-own-sub-changed
  (testing ":rf.view/rendered carries :rf.view/triggered-by — the single
   sub-id in THIS view's read-set whose value changed in the cascade, the
   precise per-view re-render cause (rf2-8wrzz.1). A view that derefs a sub
   which the cascade recomputed with a changed value names that sub. A sub's
   FIRST recompute in a cascade reports value-changed? true (the ::unset
   sentinel), so running + reading the sub fresh inside the handler before
   the render lands a value-changed :rf.sub/run in the in-flight buffer and
   the view's deref-sink carries the matching query-vector."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-sub :rf2-8wrzz1/n (fn [_ _] 42))

      (rf/reg-view ^{:rf/id :rf2-8wrzz1/reader} reader-view []
        [:span @(rf/subscribe [:rf2-8wrzz1/n])])

      ;; Render INSIDE a cascade: run the sub from the handler (its first
      ;; recompute reports value-changed? true into the in-flight buffer),
      ;; then render the view (its deref-sink records [:rf2-8wrzz1/n]). The
      ;; intersection resolves :triggered-by to the changed own-sub.
      (let [render (rf/view :rf2-8wrzz1/reader)]
        (rf/reg-event :rf2-8wrzz1/run-then-render
          (fn [_ _]
            @(rf/subscribe [:rf2-8wrzz1/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-8wrzz1/run-then-render]))

      (let [ev (first (filter #(some? (get-in % [:tags :rf.view/triggered-by]))
                              @traces))]
        (is (some? ev)
            ":rf.view/triggered-by present when an own sub changed value in-cascade")
        (when ev
          (is (= :rf2-8wrzz1/n (get-in ev [:tags :rf.view/triggered-by]))
              ":rf.view/triggered-by names the sub that caused the re-render"))))))

(deftest rf-view-rendered-omits-triggered-by-on-structural-render
  (testing ":rf.view/triggered-by is ABSENT on a structural render — a view
   with no subs (or whose subs did not change) names no cause (rf2-8wrzz.1).
   The consumer reads its absence as the `← parent re-render` reason."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-8wrzz1/no-subs} no-subs-view []
        [:span "static"])
      ((rf/view :rf2-8wrzz1/no-subs))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) ":rf.view/rendered still fires")
        (is (not (contains? t :rf.view/triggered-by))
            ":rf.view/triggered-by omitted when no own sub changed (structural)")))))

(deftest rf-view-rendered-omits-attribution-when-no-cascade
  (testing "a render outside any cascade (e.g. headless direct invocation
   with no in-flight buffer) emits :rf.view/rendered with :rf.view/cause-event-id
   and :rf.view/cause-subs simply absent — consumers see the marker but no
   misleading attribution"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/no-cascade} no-cascade-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/no-cascade))
      (let [ev (first @traces)
            t  (:tags ev)]
        (is (some? ev) ":rf.view/rendered still fires outside a cascade")
        (is (= :rf2-25zo2/no-cascade (:rf.view/id t)) ":rf.view/id present")
        (is (not (contains? t :rf.view/cause-event-id))
            ":rf.view/cause-event-id omitted when no cascade is in flight")
        (is (not (contains? t :rf.view/cause-subs))
            ":rf.view/cause-subs omitted when no cascade is in flight")))))
