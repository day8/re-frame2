(ns re-frame.bench.hicasso.front.dogfood-cljs-test
  "THE DOGFOOD STATE LAYER, and the front half composed end to end
  (rf2-2rtt6.8).

  Two things are being proved. First, that the events and subscriptions
  the three renderings will share behave — which is worth its own tests
  precisely because all three renderings depend on them and none of them
  owns them. Second, and the reason this file is not merely a state
  test: that the front half **composes**. An intent written in the
  authoring spelling, lowered by the intent module through the codec's
  prop walk, invoked as the browser would invoke it, reaches a real
  re-frame2 event handler and moves a real app-db — and the index says
  which boundary that write dirtied.

  Nothing here mounts a screen. HD-014 starts the six-week clock at the
  first Hicasso-arm commit that mounts the dogfood screen, and that
  commit belongs to rf2-2rtt6.9 / rf2-2rtt6.10, not to this bead."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private frame-id ::dogfood)

(defn- seeded!
  "A frame with `n` to-dos, and the index reset alongside it."
  ([] (seeded! 3))
  ([n]
   (idx/reset-index!)
   (dogfood/make-frame! frame-id n)
   frame-id))

(defn- read-sub [query]
  (rf/with-frame frame-id (deref (rf/subscribe query))))

(defn- send! [event]
  (rf/with-frame frame-id (rf/dispatch-sync event))
  nil)

(defn- frame-dispatch
  "The frame-locked dispatch a boundary would resolve once, from the
  substrate's single internal context, and bind ambiently."
  []
  (fn [event] (send! event)))

;; ---------------------------------------------------------------------------
;; The shape
;; ---------------------------------------------------------------------------

(deftest the-seeded-shape-is-the-one-the-screen-is-written-against
  (let [db (dogfood/seed-db 3)]
    (is (= [0 1 2] (:order db)))
    (is (= 3 (count (:todos db))))
    (is (= {:id 1 :title "todo 1" :done? false} (get-in db [:todos 1])))
    (is (= {} (:drafts db)))
    (is (= :all (:filter db)))
    (is (= 3 (:next-id db)))))

(deftest the-subscriptions-read-what-the-screen-needs
  (seeded! 3)
  (is (= [0 1 2] (read-sub [:dogfood/visible-ids])))
  (is (= 3 (read-sub [:dogfood/remaining])))
  (is (= "todo 1" (:title (read-sub [:dogfood/todo 1]))))
  (is (false? (read-sub [:dogfood/done? 1])))
  (is (= "" (read-sub [:dogfood/draft 1])) "an untouched draft reads as empty, not nil")
  (is (= :all (read-sub [:dogfood/filter]))))

;; ---------------------------------------------------------------------------
;; The write shapes that are also the bulk witnesses
;; ---------------------------------------------------------------------------

(deftest the-narrow-write-touches-one-row-and-the-header
  (seeded! 3)
  (send! [:dogfood/toggle 1])
  (is (true? (read-sub [:dogfood/done? 1])))
  (is (false? (read-sub [:dogfood/done? 0])))
  (is (false? (read-sub [:dogfood/done? 2])))
  (is (= 2 (read-sub [:dogfood/remaining])))
  (is (= [0 1 2] (read-sub [:dogfood/visible-ids])) "under :all the list is unchanged"))

(deftest the-broad-write-rebuilds-the-list
  (seeded! 3)
  (send! [:dogfood/toggle 1])
  (send! [:dogfood/set-filter :active])
  (is (= [0 2] (read-sub [:dogfood/visible-ids])))
  (send! [:dogfood/set-filter :done])
  (is (= [1] (read-sub [:dogfood/visible-ids])))
  (send! [:dogfood/set-filter :all])
  (is (= [0 1 2] (read-sub [:dogfood/visible-ids]))))

(deftest keyed-insert-delete-and-reorder-move-the-order-and-nothing-else
  (seeded! 3)
  (testing "insert appends and mints the next id"
    (send! [:dogfood/edit-draft dogfood/new-draft-key "milk"])
    (send! [:dogfood/create])
    (is (= [0 1 2 3] (read-sub [:dogfood/visible-ids])))
    (is (= "milk" (:title (read-sub [:dogfood/todo 3])))))
  (testing "delete removes the row and its draft"
    (send! [:dogfood/edit-draft 1 "half-typed"])
    (send! [:dogfood/remove 1])
    (is (= [0 2 3] (read-sub [:dogfood/visible-ids])))
    (is (nil? (read-sub [:dogfood/todo 1])))
    (is (= "" (read-sub [:dogfood/draft 1]))))
  (testing "reorder moves one id and preserves every other position"
    (send! [:dogfood/move 3 0])
    (is (= [3 0 2] (read-sub [:dogfood/visible-ids])))
    (send! [:dogfood/move 3 2])
    (is (= [0 2 3] (read-sub [:dogfood/visible-ids])))
    (testing "an out-of-range target clamps rather than corrupting the list"
      (send! [:dogfood/move 0 99])
      (is (= [2 3 0] (read-sub [:dogfood/visible-ids]))))))

;; ---------------------------------------------------------------------------
;; Drafts — HD-009's claim, as code
;; ---------------------------------------------------------------------------

(deftest one-parametric-sub-and-named-events-serve-every-draft-instance
  (seeded! 3)
  (send! [:dogfood/edit-draft 0 "zero"])
  (send! [:dogfood/edit-draft 2 "two"])
  (send! [:dogfood/edit-draft dogfood/new-draft-key "new"])
  (testing "three instances, one subscription"
    (is (= "zero" (read-sub [:dogfood/draft 0])))
    (is (= "two" (read-sub [:dogfood/draft 2])))
    (is (= "new" (read-sub [:dogfood/draft dogfood/new-draft-key])))
    (is (= "" (read-sub [:dogfood/draft 1])) "an instance nobody typed into"))
  (testing "commit is by explicit caller revision, never by value equality"
    (send! [:dogfood/commit 0])
    (is (= "zero" (:title (read-sub [:dogfood/todo 0]))))
    (is (= "" (read-sub [:dogfood/draft 0])) "and only then is the draft cleared"))
  (testing "cancel discards without touching the to-do"
    (send! [:dogfood/cancel 2])
    (is (= "" (read-sub [:dogfood/draft 2])))
    (is (= "todo 2" (:title (read-sub [:dogfood/todo 2]))))))

(deftest an-empty-draft-creates-and-commits-nothing
  (seeded! 3)
  (send! [:dogfood/create])
  (is (= [0 1 2] (read-sub [:dogfood/visible-ids])))
  (send! [:dogfood/commit 1])
  (is (= "todo 1" (:title (read-sub [:dogfood/todo 1])))))

;; ---------------------------------------------------------------------------
;; The front half, composed: authoring spelling → codec → browser → app-db
;; ---------------------------------------------------------------------------

(deftest a-lowered-intent-reaches-a-real-event-handler
  (seeded! 3)
  (let [el (intent/with-frame (frame-dispatch)
                              (fn [] (codec/as-element
                                      [:button {:on-click [:dogfood/toggle 1]} "toggle"])))
        on-click (aget (.-props el) "onClick")]
    (is (false? (read-sub [:dogfood/done? 1])))
    (on-click #js {:target #js {}})
    (is (true? (read-sub [:dogfood/done? 1])) "the click moved app-db")
    (is (= 2 (read-sub [:dogfood/remaining])))))

(deftest the-controlled-field-carries-its-value-through-the-marker
  (seeded! 3)
  (let [el (intent/with-frame (frame-dispatch)
                              (fn [] (codec/as-element
                                      [:input {:value (read-sub [:dogfood/draft dogfood/new-draft-key])
                                               :on-input [:dogfood/edit-draft dogfood/new-draft-key
                                                          :re-frame.hicasso/value]}])))
        on-input (aget (.-props el) "onInput")]
    (is (= "" (aget (.-props el) "value")))
    (on-input #js {:target #js {:value "mi"}})
    (is (= "mi" (read-sub [:dogfood/draft dogfood/new-draft-key])))
    (on-input #js {:target #js {:value "milk"}})
    (is (= "milk" (read-sub [:dogfood/draft dogfood/new-draft-key])))))

(deftest the-form-submits-once-and-prevents-the-browsers-navigation
  (seeded! 3)
  (send! [:dogfood/edit-draft dogfood/new-draft-key "milk"])
  (let [!prevented (atom false)
        el (intent/with-frame (frame-dispatch)
                              (fn [] (codec/as-element
                                      [:form {:on-submit [:dogfood/create]}])))
        on-submit (aget (.-props el) "onSubmit")]
    (on-submit #js {:target #js {} :preventDefault (fn [] (reset! !prevented true))})
    (is (true? @!prevented))
    (is (= [0 1 2 3] (read-sub [:dogfood/visible-ids])))
    (is (= "milk" (:title (read-sub [:dogfood/todo 3]))))
    (is (= "" (read-sub [:dogfood/draft dogfood/new-draft-key])))))

(deftest the-key-map-commits-on-enter-cancels-on-escape-and-is-silent-mid-composition
  (seeded! 3)
  (send! [:dogfood/edit-draft 1 "renamed"])
  (let [el (intent/with-frame (frame-dispatch)
                              (fn [] (codec/as-element
                                      [:input {:on-key-down {"Enter"  [:dogfood/commit 1]
                                                             "Escape" [:dogfood/cancel 1]}}])))
        on-key-down (aget (.-props el) "onKeyDown")]
    (testing "a composing Enter commits nothing"
      (on-key-down #js {:key "Enter" :isComposing true :target #js {}})
      (is (= "todo 1" (:title (read-sub [:dogfood/todo 1]))))
      (is (= "renamed" (read-sub [:dogfood/draft 1])) "and the draft survives"))
    (testing "a settled Enter commits"
      (on-key-down #js {:key "Enter" :target #js {}})
      (is (= "renamed" (:title (read-sub [:dogfood/todo 1])))))
    (testing "Escape discards the next draft"
      (send! [:dogfood/edit-draft 1 "second thoughts"])
      (on-key-down #js {:key "Escape" :target #js {}})
      (is (= "" (read-sub [:dogfood/draft 1])))
      (is (= "renamed" (:title (read-sub [:dogfood/todo 1])))))))

;; ---------------------------------------------------------------------------
;; The index over the real screen's reads
;; ---------------------------------------------------------------------------

(deftest the-index-answers-the-screens-own-narrow-and-broad-writes
  (seeded! 3)
  (idx/mount! :header)
  (idx/mount! :row-0)
  (idx/mount! :row-1)
  (idx/record-reads! :header #{[:dogfood/remaining] [:dogfood/visible-ids]})
  (idx/record-reads! :row-0 #{[:dogfood/todo 0] [:dogfood/done? 0]})
  (idx/record-reads! :row-1 #{[:dogfood/todo 1] [:dogfood/done? 1]})
  (testing "the narrow write dirties the one row and the header that counts it"
    (is (= #{:row-1 :header}
           (idx/commit! #{[:dogfood/done? 1] [:dogfood/remaining]}))))
  (testing "the broad write dirties every reader of the list"
    (is (= #{:header} (idx/commit! #{[:dogfood/visible-ids]}))))
  (testing "a to-do nobody has mounted a row for dirties nothing"
    (is (= #{} (idx/commit! #{[:dogfood/todo 2]}))))
  (testing "unmounting a row takes its edges with it"
    (idx/unmount! :row-1)
    (is (= #{} (idx/commit! #{[:dogfood/done? 1]})))
    (is (= #{:header} (idx/commit! #{[:dogfood/remaining]})))))
