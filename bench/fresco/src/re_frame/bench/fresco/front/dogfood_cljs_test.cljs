(ns re-frame.bench.fresco.front.dogfood-cljs-test
  "THE DOGFOOD STATE LAYER, and the front half composed end to end.

  The events and subscriptions all three renderings share, and the proof
  that the front half composes: an intent in the authoring spelling,
  lowered through the codec's prop walk and invoked as the browser would,
  reaches a real event handler and moves a real app-db. The screen's
  narrow and broad writes against the arm's dependency edges are
  `the-table-answers-the-screens-own-narrow-and-broad-writes` in
  `arm1/cell_table_laws_cljs_test`. Nothing here mounts a screen."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id ::dogfood)

(def ^:private new-key rf.bench.fresco.front.dogfood/new-draft-key)

(defn- seeded! []
  (rf.bench.fresco.front.dogfood/make-frame! frame-id 3)
  frame-id)

(defn- read-sub [query]
  (rf/with-frame frame-id (deref (rf/subscribe query))))

(defn- send! [event]
  (rf/with-frame frame-id (rf/dispatch-sync event))
  nil)

(defn- title [id] (:title (read-sub [:dogfood/todo id])))

(defn- visible [] (read-sub [:dogfood/visible-ids]))

(defn- lowered
  "The element the codec builds for `hiccup` under the frame-locked
  dispatch a boundary would bind, and its `prop` handler."
  [hiccup prop]
  (let [el (rf.bench.fresco.front.intent/with-frame send!
                                                    (fn [] (rf.bench.fresco.front.codec/as-element hiccup)))]
    [el (aget (.-props el) prop)]))

(deftest the-subscriptions-read-what-the-screen-needs
  (seeded!)
  (is (= [[0 1 2] 3 "todo 1" false "" :all]
         [(visible) (read-sub [:dogfood/remaining]) (title 1) (read-sub [:dogfood/done? 1])
          (read-sub [:dogfood/draft 1]) (read-sub [:dogfood/filter])])
      "an untouched draft reads as empty, not nil"))

(deftest the-narrow-write-touches-one-row-and-the-header
  (seeded!)
  (send! [:dogfood/toggle 1])
  (is (= [false true false 2 [0 1 2]]
         [(read-sub [:dogfood/done? 0]) (read-sub [:dogfood/done? 1]) (read-sub [:dogfood/done? 2])
          (read-sub [:dogfood/remaining]) (visible)])))

(deftest the-broad-write-rebuilds-the-list
  (seeded!)
  (send! [:dogfood/toggle 1])
  (is (= [[0 2] [1] [0 1 2]]
         (mapv (fn [f] (send! [:dogfood/set-filter f]) (visible)) [:active :done :all]))))

(deftest keyed-insert-delete-and-reorder-move-the-order-and-nothing-else
  (seeded!)
  (testing "insert appends and mints the next id"
    (send! [:dogfood/edit-draft new-key "milk"])
    (send! [:dogfood/create])
    (is (= [[0 1 2 3] "milk"] [(visible) (title 3)])))
  (testing "delete removes the row and its draft"
    (send! [:dogfood/edit-draft 1 "half-typed"])
    (send! [:dogfood/remove 1])
    (is (= [[0 2 3] nil ""] [(visible) (read-sub [:dogfood/todo 1]) (read-sub [:dogfood/draft 1])])))
  (testing "reorder moves one id, and an out-of-range target clamps"
    (is (= [[3 0 2] [0 2 3] [2 3 0]]
           (mapv (fn [[id to]] (send! [:dogfood/move id to]) (visible)) [[3 0] [3 2] [0 99]])))))

(deftest one-parametric-sub-and-named-events-serve-every-draft-instance
  (seeded!)
  (send! [:dogfood/edit-draft 0 "zero"])
  (send! [:dogfood/edit-draft 2 "two"])
  (send! [:dogfood/edit-draft new-key "new"])
  (is (= ["zero" "two" "new" ""] (mapv #(read-sub [:dogfood/draft %]) [0 2 new-key 1]))
      "three instances, one subscription, and one nobody typed into")
  (send! [:dogfood/commit 0])
  (is (= ["zero" ""] [(title 0) (read-sub [:dogfood/draft 0])])
      "commit writes the draft and only then clears it")
  (send! [:dogfood/cancel 2])
  (is (= ["" "todo 2"] [(read-sub [:dogfood/draft 2]) (title 2)])
      "cancel discards without touching the to-do"))

(deftest an-empty-draft-creates-and-commits-nothing
  (seeded!)
  (send! [:dogfood/create])
  (send! [:dogfood/commit 1])
  (is (= [[0 1 2] "todo 1"] [(visible) (title 1)])))

(deftest a-lowered-intent-reaches-a-real-event-handler
  (seeded!)
  (let [[_ on-click] (lowered [:button {:on-click [:dogfood/toggle 1]} "toggle"] "onClick")]
    (on-click #js {:target #js {}})
    (is (= [true 2] [(read-sub [:dogfood/done? 1]) (read-sub [:dogfood/remaining])])
        "the click moved app-db")))

(deftest the-controlled-field-carries-its-value-through-the-marker
  (seeded!)
  (let [[el on-input] (lowered [:input {:value    (read-sub [:dogfood/draft new-key])
                                        :on-input [:dogfood/edit-draft new-key :re-frame.fresco/value]}]
                               "onInput")]
    (is (= ["" "mi" "milk"]
           (into [(aget (.-props el) "value")]
                 (map (fn [typed]
                        (on-input #js {:target #js {:value typed}})
                        (read-sub [:dogfood/draft new-key])))
                 ["mi" "milk"])))))

(deftest the-form-submits-once-and-prevents-the-browsers-navigation
  (seeded!)
  (send! [:dogfood/edit-draft new-key "milk"])
  (let [!prevented   (atom false)
        [_ on-submit] (lowered [:form {:on-submit [:dogfood/create]}] "onSubmit")]
    (on-submit #js {:target #js {} :preventDefault (fn [] (reset! !prevented true))})
    (is (= [true [0 1 2 3] "milk" ""]
           [@!prevented (visible) (title 3) (read-sub [:dogfood/draft new-key])]))))

(deftest the-key-map-commits-on-enter-cancels-on-escape-and-is-silent-mid-composition
  (seeded!)
  (send! [:dogfood/edit-draft 1 "renamed"])
  (let [[_ on-key-down] (lowered [:input {:on-key-down {"Enter"  [:dogfood/commit 1]
                                                        "Escape" [:dogfood/cancel 1]}}]
                                 "onKeyDown")]
    (on-key-down #js {:key "Enter" :isComposing true :target #js {}})
    (is (= ["todo 1" "renamed"] [(title 1) (read-sub [:dogfood/draft 1])])
        "a composing Enter commits nothing, and the draft survives")
    (on-key-down #js {:key "Enter" :target #js {}})
    (is (= "renamed" (title 1)) "a settled Enter commits")
    (send! [:dogfood/edit-draft 1 "second thoughts"])
    (on-key-down #js {:key "Escape" :target #js {}})
    (is (= ["" "renamed"] [(read-sub [:dogfood/draft 1]) (title 1)])
        "Escape discards the next draft")))
