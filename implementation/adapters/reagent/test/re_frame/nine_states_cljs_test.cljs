(ns re-frame.nine-states-cljs-test
  "Drives the nine-states example (`examples/patterns/nine_states/`) through
   its nine canonical UI states, asserting the machine's tag union and the
   resolved `:ui/render` keyword. Browserless via `compute-sub`."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            ;; Registers `:rf.http/managed-canned-failure`, the fx the failing
            ;; Story variant routes `:rf.http/managed` to.
            [re-frame.http.test-support]
            [nine-states.core]
            ;; Registers `:nine-states.story/load-failing`, the failing
            ;; variant's setup event.
            [nine-states.stories])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    ;; Each test makes its own top-level frame, so its `:initial-events`
    ;; drain synchronously rather than as a mid-cascade child frame.
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- snapshot [frame]
  (get-in (rf/frame-state-value frame)
          [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states]))

(defn- has-tag? [frame tag]
  (contains? (:tags (snapshot frame)) tag))

(defn- render-model [frame]
  (rf/compute-sub [:ui/render] (rf/frame-state-value frame)))

(defn- new-frame
  ([] (new-frame {:rf.http/managed :nine-states.http/managed-demo}))
  ([fx-overrides]
   (rf.frame/make-anon-frame-record!
     {:initial-events [[:nine-states.app/initialise]]
      :fx-overrides   fx-overrides})))

(deftest nine-states-runs-end-to-end
  (doseq [[state events tags render]
          [["1 nothing" [] #{:data/nothing} :nothing]
           ;; The demo stub replies synchronously, so :loading is observed by
           ;; dispatching :fetch-started with no reply.
           ["2 loading" [[:ui/nine-states [:fetch-started]]] #{:data/loading :data/transient} :loading]
           ["2 loading, then a load" [[:ui/nine-states [:fetch-started]] [:nine-states.demo/load {:n 4}]]
            #{:data/some} :some]
           ["3 empty" [[:nine-states.demo/load {:n 0}]] #{:data/empty} :empty]
           ["4 one" [[:nine-states.demo/load {:n 1}]] #{:data/one} :one]
           ["5 some" [[:nine-states.demo/load {:n 4}]] #{:data/some} :some]
           ["6 too-many" [[:nine-states.demo/load {:n 25}]] #{:data/too-many} :too-many]
           ["7 incorrect" [[:new-todo/edit-field :title "ab"] [:new-todo/submit]] #{:form/invalid} :incorrect]
           ;; :form/success and :data/one hold together; the priority table picks :correct.
           ["8 correct" [[:nine-states.demo/load {:n 0}] [:new-todo/edit-field :title "Buy milk"] [:new-todo/submit]]
            #{:form/success :data/one} :correct]
           ["9 done" [[:nine-states.demo/load {:n 4}] [:ui/nine-states [:archive {:now 1}]]]
            #{:mode/done :mode/read-only} :done]]]
    (with-new-frame [f (new-frame)]
      (doseq [event events]
        (rf/dispatch-sync event {:frame f}))
      (is (set/subset? tags (:tags (snapshot f))) state)
      (is (= render (render-model f)) state))))

(deftest too-many-searches-the-whole-list-without-changing-cardinality
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame f})
    (let [before (snapshot f)
          matches #(rf/compute-sub [:nine-states.search/matches]
                                   (rf/frame-state-value f))]
      (rf/dispatch-sync [:nine-states.search/set-query "  TODO #25  "] {:frame f})
      (is (= ["Todo #25"] (mapv :title (matches)))
          "search reaches a todo beyond the first seven, ignoring case and padding")
      (rf/dispatch-sync [:nine-states.search/set-query "no such todo"] {:frame f})
      (is (empty? (matches)))
      (is (= before (snapshot f)) "search does not mutate the machine's full list or state")
      (is (= :too-many (render-model f)) "zero matches does not become the empty-data state")
      (rf/dispatch-sync [:nine-states.search/set-query ""] {:frame f})
      (is (= 25 (count (matches))))
      (rf/dispatch-sync [:nine-states.search/set-query "old query"] {:frame f})
      (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
      (is (= "" (rf/compute-sub [:nine-states.search/query] (rf/frame-state-value f)))
          "Nothing resets the search along with the form and machine"))))

;; A region returning to :nothing does not by itself rewrite the machine's
;; :data, so reset must clear the owned items, error and archive stamp, or
;; old todos resurrect under a blank view on the next submit.

(def ^:private initial-state {:data :nothing :form :neutral :mode :active})
(def ^:private initial-data {:items [] :error nil :archived-at nil})

(deftest reset-clears-machine-owned-domain-data
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
    (is (= 4 (count (get-in (snapshot f) [:data :items]))) "precondition: four todos loaded")
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    (is (= [initial-state initial-data] ((juxt :state :data) (snapshot f))))
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (is (= ["Buy milk"] (mapv :title (rf/compute-sub [:todos/items] (rf/frame-state-value f))))
        "none of the four pre-reset rows reappear")
    (is (has-tag? f :data/one) "the :data region rests at :one, not :some")))

(deftest reset-clears-recorded-failure-and-thaws-done
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load-with-failure] {:frame f})
    (is (some? (get-in (snapshot f) [:data :error])) "precondition: failure recorded")
    (rf/dispatch-sync [:ui/nine-states [:archive {:now 7}]] {:frame f})
    (is (= [:done 7] [(get-in (snapshot f) [:state :mode]) (get-in (snapshot f) [:data :archived-at])])
        "precondition: archived and stamped")
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    (is (= [initial-state initial-data] ((juxt :state :data) (snapshot f)))
        ":mode thaws to :active and the failure and archive stamp are cleared")))

(deftest reset-from-loading-abandons-in-flight-load
  (with-new-frame [f (new-frame)]
    (let [data-region #(vector (get-in (snapshot f) [:state :data]) (get-in (snapshot f) [:data :items]))]
      (rf/dispatch-sync [:ui/nine-states [:fetch-started]] {:frame f})
      (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
      (is (= [:nothing []] (data-region)) "reset from :loading lands at :nothing with clean :data")
      (rf/dispatch-sync [:ui/nine-states
                         [:fetch-succeeded {:items [{:id 1 :title "ghost-1" :done? false}]}]]
                        {:frame f})
      (is (= [:nothing []] (data-region)) "the abandoned load's late completion is inert"))))

;; Add stays enabled after a valid submit, so pressing it again submits the
;; cleared draft; the form must leave :correct, or "Todo added" out-ranks the
;; new field error.
(deftest success-then-invalid-submit-selects-incorrect
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (is (seq (get-in (rf/app-db-value f) [:new-todo :errors :title])) "the title error is recorded")
    (is (= :incorrect (render-model f)) "the page renders the error, not \"Todo added\"")
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy eggs"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (is (= :correct (render-model f)) "a later valid submit returns to :correct")
    (is (= ["Buy milk" "Buy eggs"] (mapv :title (rf/compute-sub [:todos/items] (rf/frame-state-value f))))
        "the invalid submit added nothing")))

;; The `:story.nine-states-lifecycle/*` variants in
;; examples/patterns/nine_states/stories.cljs differ only in the managed-HTTP
;; stub they stamp; each must take its own branch.
(deftest story-lifecycle-variants-take-their-own-path
  (testing "the :error variant's canned-failure override lands :data at :error"
    (with-new-frame [f (new-frame {:rf.http/managed :rf.http/managed-canned-failure})]
      (rf/dispatch-sync [:nine-states.story/load-failing] {:frame f})
      (is (= :error (render-model f)))
      (is (some? (rf/compute-sub [:todos/error] (rf/frame-state-value f)))
          "the failure value is recorded in the machine's :data :error slot")))
  (testing "the :loaded variant's canned-success default still loads"
    (with-new-frame [f (new-frame {:rf.http/managed :rf.http/managed-canned-success})]
      (rf/dispatch-sync [:nine-states.story/load {:n 4}] {:frame f})
      (is (= :some (render-model f))))))
