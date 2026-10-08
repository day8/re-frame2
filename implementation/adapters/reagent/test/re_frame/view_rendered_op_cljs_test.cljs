(ns re-frame.view-rendered-op-cljs-test
  "`:rf.view/rendered` fires alongside `:rf.view/render` for every render of
  a registered view, carrying the cascade-attribution slots Xray's Reactive
  panel graphs re-renders with. The emit site is the substrate-agnostic
  `views.cljs` frame-aware-view wrapper. The tag set:

    :frame                  — the frame the render landed in
    :rf.view/id             — the registered view id
    :rf.view/render-key     — [view-id instance-token] (parity with :rf.view/render)
    :rf.view/cause-event-id — (when in-cascade) the dispatching cascade's event-id
    :rf.view/cause-subs     — (when in-cascade) sub-ids that ran in the cascade,
                              distinct, first-seen order, capped at 100"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            [re-frame.epoch]) ;; load so :epoch/run-cause hook is bound
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helpers ---------------------------------------------------------------

(def ^:private view-rendered-pred
  #(= :rf.view/rendered (:operation %)))

(defn- in-op-set [op-set]
  (fn [ev] (contains? op-set (:operation ev))))

(defn- first-tag
  "The first non-nil `tag` across the recorded events."
  [traces tag]
  (some #(get-in % [:tags tag]) traces))

(defn- elapsed-ms? [ms]
  (and (number? ms) (>= ms 0)))

;; ---- emission ---------------------------------------------------------------

(deftest rf-view-rendered-carries-view-id-and-frame
  (testing ":rf.view/rendered carries :rf.view/id, :frame and :rf.view/render-key
   on every emit"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/shape} shape-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/shape))
      (let [t  (:tags (first @traces))
            rk (:rf.view/render-key t)]
        (is (= [:rf2-25zo2/shape true true :rf2-25zo2/shape]
               [(:rf.view/id t) (some? (:frame t)) (vector? rk) (first rk)])
            "the emit carries the registered :rf.view/id, a :frame, and a render-key tuple led by the view-id")))))

(deftest rf-view-rendered-carries-cause-event-id-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :rf.view/cause-event-id, the in-flight cascade's :event/run-start
   event-id, read from the epoch capture buffer at emit time"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/with-cause} cause-view []
        [:span "x"])
      (let [render (rf/view :rf2-25zo2/with-cause)]
        (rf/reg-event :rf2-25zo2/render-during-cascade
          (fn [_ _]
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/render-during-cascade]))
      (is (= :rf2-25zo2/render-during-cascade
             (first-tag @traces :rf.view/cause-event-id))
          ":rf.view/cause-event-id matches the dispatching event-id"))))

(deftest rf-view-rendered-carries-cause-subs-in-cascade
  (testing ":rf.view/rendered emitted inside a cascade carries
   :rf.view/cause-subs, the distinct sub-ids that ran in the cascade before
   the render: here a sub the handler runs before rendering"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-sub :rf2-25zo2/n (fn [_ _] 7))
      (rf/reg-view ^{:rf/id :rf2-25zo2/with-upstream-sub} upstream-sub-view []
        [:span "x"])
      (let [render (rf/view :rf2-25zo2/with-upstream-sub)]
        (rf/reg-event :rf2-25zo2/cascade-with-sub
          (fn [_ _]
            @(rf/subscribe [:rf2-25zo2/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-25zo2/cascade-with-sub]))
      (let [subs (first-tag @traces :rf.view/cause-subs)]
        (is (and (vector? subs) (some #{:rf2-25zo2/n} subs))
            (str ":rf.view/cause-subs is a vector naming the sub that ran upstream of the render; got "
                 (pr-str subs)))))))

(deftest rf-view-rendered-carries-elapsed-ms
  (testing ":rf.view/rendered carries :rf.view/elapsed-ms, the wall-clock
   duration of the user render-fn, on every dev-build render"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-8wrzz1/timed} timed-view []
        [:span "x"])
      ((rf/view :rf2-8wrzz1/timed))
      (let [t (:tags (first @traces))]
        (is (elapsed-ms? (:rf.view/elapsed-ms t))
            (str ":rf.view/elapsed-ms is a non-negative number; got " (pr-str t)))))))

(deftest rf-view-rendered-carries-render-args
  (testing ":rf.view/rendered carries :rf.view/render-args, the vector of
   positional args passed to THIS render"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-rpgq8/with-args} args-view [_label _n]
        [:span "ok"])
      ((rf/view :rf2-rpgq8/with-args) {:label "hi"} 42)
      (is (= [{:label "hi"} 42] (get-in (first @traces) [:tags :rf.view/render-args]))
          ":rf.view/render-args is the vector of positional render args"))))

(deftest rf-view-rendered-render-args-elided-at-emit
  (testing "PRIVACY (Spec 009 §Privacy): render args are arbitrary user data,
   so :rf.view/render-args routes through the same emit-time elision
   chokepoint as :rf.event/db, and a frame-declared `:sensitive` app-db path
   inside a render arg reaches the trace surface as :rf/redacted"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      ;; The same registry write a `reg-event` returning `:sensitive` performs.
      (rf.frame/swap-runtime-db! :rf/default
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
      (rf/reg-view ^{:rf/id :rf2-rpgq8/sensitive} sensitive-view [_props]
        [:span "ok"])
      ((rf/view :rf2-rpgq8/sensitive) {:auth {:username "ada" :password "hunter2"}})
      (let [arg0 (first (get-in (first @traces) [:tags :rf.view/render-args]))]
        (is (= [:rf/redacted "ada"]
               [(get-in arg0 [:auth :password]) (get-in arg0 [:auth :username])])
            "the [:auth :password] leaf is redacted at emit, and its non-sensitive sibling preserved")))))

(deftest rf-sub-run-carries-elapsed-ms
  (testing ":rf.sub/run carries :rf.sub/elapsed-ms, the wall-clock duration
   of the sub body recompute, which the reactive memo wrapper brackets. The
   plain-atom JVM substrate does not run that wrapper, so this lives here."
    (with-trace-recorder! [observed {:pred  (in-op-set #{:rf.sub/run})
                                     :shape :by-op}]
      (rf/reg-sub :rf2-hhh92/n (fn [_ _] 42))
      (rf/reg-event :rf2-hhh92/touch-sub
        (fn [_ _]
          @(rf/subscribe [:rf2-hhh92/n])
          {}))
      (rf/dispatch-sync [:rf2-hhh92/touch-sub])
      (let [sub-runs (:rf.sub/run @observed)]
        (is (and (seq sub-runs) (every? #(elapsed-ms? (get-in % [:tags :rf.sub/elapsed-ms])) sub-runs))
            (str "every :rf.sub/run carries a non-negative :rf.sub/elapsed-ms; got "
                 (pr-str (map :tags sub-runs))))))))

(deftest rf-view-rendered-carries-triggered-by-when-own-sub-changed
  (testing ":rf.view/rendered carries :rf.view/triggered-by, the sub in THIS
   view's read-set whose value changed in the cascade. A sub's first
   recompute in a cascade reports value-changed? true, so running it in the
   handler before the render lands a changed :rf.sub/run the view's
   deref-sink matches."
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-sub :rf2-8wrzz1/n (fn [_ _] 42))
      (rf/reg-view ^{:rf/id :rf2-8wrzz1/reader} reader-view []
        [:span @(rf/subscribe [:rf2-8wrzz1/n])])
      (let [render (rf/view :rf2-8wrzz1/reader)]
        (rf/reg-event :rf2-8wrzz1/run-then-render
          (fn [_ _]
            @(rf/subscribe [:rf2-8wrzz1/n])
            (render)
            {}))
        (rf/dispatch-sync [:rf2-8wrzz1/run-then-render]))
      (is (= :rf2-8wrzz1/n (first-tag @traces :rf.view/triggered-by))
          ":rf.view/triggered-by names the sub that caused the re-render"))))

(deftest rf-view-rendered-omits-every-optional-slot-on-a-bare-render
  (testing "a no-arg, no-sub view rendered outside any cascade emits
   :rf.view/rendered with every optional slot absent; a consumer reads an
   absent :rf.view/triggered-by as the `← parent re-render` reason"
    (with-trace-recorder! [traces {:pred view-rendered-pred}]
      (rf/reg-view ^{:rf/id :rf2-25zo2/no-cascade} no-cascade-view []
        [:span "x"])
      ((rf/view :rf2-25zo2/no-cascade))
      (let [t (:tags (first @traces))]
        (is (= [:rf2-25zo2/no-cascade {}]
               [(:rf.view/id t)
                (select-keys t [:rf.view/render-args :rf.view/triggered-by
                                :rf.view/cause-event-id :rf.view/cause-subs])])
            ":rf.view/rendered still fires, with no optional slot present")))))
