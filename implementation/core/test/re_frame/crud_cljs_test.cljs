(ns re-frame.crud-cljs-test
  "Event and sub behaviour of the 7GUIs CRUD example (`seven-guis.crud.core`),
  driven with `dispatch-sync` on an anon frame; subs are read with
  `rf/compute-sub` over app-db, since every CRUD sub is an app-db sub. The
  example's point is `:crud/can-update?`: Update/Delete go dark when the filter
  hides the selection, without dropping it."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [seven-guis.crud.core]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- crud-frame!
  "A fresh anon frame seeded by `:crud/initialise`: the three 7GUIs people
  (ids 1-3), next-id 4, no filter, no selection."
  []
  (let [f (rf.frame/make-anon-frame-record! {:doc "crud test frame"})]
    (rf/dispatch-sync [:crud/initialise] {:frame f})
    f))

(defn- crud [f] (:crud (rf/app-db-value f)))
(defn- people [f] (:people (crud f)))
(defn- person [f id] (first (filter #(= id (:id %)) (people f))))
(defn- sub [f query-v] (rf/compute-sub query-v (rf/app-db-value f)))
(defn- surnames [ppl] (set (map :surname ppl)))

(deftest select-copies-name-and-surname-into-draft
  (let [f (crud-frame!)]
    (rf/dispatch-sync [:crud/select 2] {:frame f})
    (is (= [2 {:name "Max" :surname "Mustermann"}]
           [(:selected-id (crud f)) (:draft (crud f))]))))

(deftest create-appends-with-next-id-and-selects-it
  (let [f (crud-frame!)]
    (rf/dispatch-sync [:crud/edit-name "Ada"] {:frame f})
    (rf/dispatch-sync [:crud/edit-surname "Lovelace"] {:frame f})
    (rf/dispatch-sync [:crud/create] {:frame f})
    (is (= [[1 2 3 4] {:id 4 :name "Ada" :surname "Lovelace"} 5 4]
           [(mapv :id (people f)) (person f 4) (:next-id (crud f)) (:selected-id (crud f))])
        "appended under the next id, allocator bumped, new row selected")))

(deftest create-ids-are-monotonic-and-never-reissued
  (let [f (crud-frame!)]
    (rf/dispatch-sync [:crud/create] {:frame f})
    (rf/dispatch-sync [:crud/create] {:frame f})
    (rf/dispatch-sync [:crud/delete] {:frame f})
    (rf/dispatch-sync [:crud/create] {:frame f})
    (is (= [1 2 3 4 6] (mapv :id (people f)))
        "the id freed by the delete is not reissued — a reissuing allocator breaks replay")))

(deftest update-merges-draft-into-selected-row-only
  (let [f (crud-frame!)]
    (rf/dispatch-sync [:crud/select 1] {:frame f})
    (rf/dispatch-sync [:crud/edit-name "Johann"] {:frame f})
    (rf/dispatch-sync [:crud/update] {:frame f})
    (is (= [{:id 1 :name "Johann" :surname "Emil"}
            {:id 2 :name "Max" :surname "Mustermann"}
            {:id 3 :name "Roman" :surname "Tisch"}]
           (people f)))))

(deftest delete-removes-selected-and-clears-selection
  (let [f (crud-frame!)]
    (rf/dispatch-sync [:crud/select 2] {:frame f})
    (rf/dispatch-sync [:crud/delete] {:frame f})
    (is (= [[1 3] nil {:name "" :surname ""}]
           [(mapv :id (people f)) (:selected-id (crud f)) (:draft (crud f))]))))

(deftest filtered-people-matches-surname-prefix-case-insensitively
  (let [f (crud-frame!)]
    (doseq [[prefix expected] [[""     #{"Emil" "Mustermann" "Tisch"}]
                               ["mus"  #{"Mustermann"}]
                               ["EMIL" #{"Emil"}]
                               ["z"    #{}]]]
      (rf/dispatch-sync [:crud/set-filter prefix] {:frame f})
      (is (= expected (surnames (sub f [:crud/filtered-people]))) (pr-str prefix)))))

(deftest can-update?-tracks-selection-visibility-under-filter
  (let [f (crud-frame!)]
    (is (false? (sub f [:crud/can-update?])) "no selection -> disabled")
    (rf/dispatch-sync [:crud/select 1] {:frame f})          ;; Hans, surname Emil
    (is (true? (sub f [:crud/can-update?])) "selected + visible -> enabled")
    (rf/dispatch-sync [:crud/set-filter "Mus"] {:frame f})  ;; hides Emil
    (is (= [false 1] [(sub f [:crud/can-update?]) (:selected-id (crud f))])
        "a filter hiding the selection disables Update/Delete but keeps the selection")))
