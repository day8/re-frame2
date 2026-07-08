(ns re-frame.crud-cljs-test
  "Behavioural regression coverage for the 7GUIs CRUD example (rf2-e4nwdr).

  CRUD is the master/detail screen: a filtered name list, a draft slice behind
  the two inputs, and Create/Update/Delete. Its demonstrated logic had no
  event/sub coverage — only `re-frame.example-frame-scoping-cljs-test` touched
  it, and only to assert its ns-load `[:crud]` app-schema landed on
  `:rf/default`. The uncovered, silently-breakable behaviour:

    - `:crud/can-update?` — Update/Delete are disabled when the selected row is
      HIDDEN by the current prefix filter, and re-enabled when the filter is
      cleared. The selection is NOT dropped; can-update? just asks whether the
      selection is still in `:crud/filtered-people` (the example calls this out
      as its point). A regression that lights Update/Delete on a filtered-out
      selection is invisible.
    - `:crud/create` — a monotonic `:next-id` allocator (replay-stable ids that
      are never reissued) + select-the-new-row.
    - `:crud/update` — merge the draft into the SELECTED row only.
    - `:crud/delete` — remove + clear selection/draft.
    - `:crud/select` — copy the person's name/surname into the draft.
    - `:crud/filtered-people` — case-insensitive SURNAME prefix.

  These belong in the framework test tree, NOT under `examples/` (examples stay
  test-free per rf2-8cevm). They `:require` `seven-guis.crud.core` (a
  Reagent-coupled `.cljs`-only ns) so they run under the consolidated
  `:node-test` CLJS build, which has `../examples/core` on its source paths.
  Handlers are driven via `dispatch-sync` on an anon frame; subs are read via
  `rf/compute-sub` (every CRUD sub is an app-db sub, so a bare app-db suffices)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [seven-guis.crud.core]))

;; `:ambient-frame nil` — these tests create + drive their own anon frames with
;; an explicit `{:frame f}`. The `[:crud]` app-schema is bound to `:rf/default`,
;; not to these anon frames, so commits here run unvalidated; behavioural logic
;; is what's under test, and the schema binding is pinned by example-frame-scoping.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- crud-frame!
  "Boot a fresh anon frame seeded via `:crud/initialise` — the three 7GUIs
  reference people (ids 1-3), next-id 4, empty filter, no selection, blank
  draft. Returns the frame id."
  []
  (let [f (frame/make-anon-frame-record! {:doc "crud test frame"})]
    (rf/dispatch-sync [:crud/initialise] {:frame f})
    f))

(defn- crud [f] (:crud (rf/app-db-value f)))
(defn- people [f] (:people (crud f)))
(defn- person [f id] (first (filter #(= id (:id %)) (people f))))
(defn- sub [f query-v] (rf/compute-sub query-v (rf/app-db-value f)))
(defn- surnames [ppl] (set (map :surname ppl)))

;; ---------------------------------------------------------------------------
;; seed + select
;; ---------------------------------------------------------------------------

(deftest initialise-seeds-the-reference-list
  (testing "the seed is the three 7GUIs people with counted ids, next-id 4, no
            filter, no selection, blank draft"
    (let [f (crud-frame!)]
      (is (= [1 2 3] (mapv :id (people f))))
      (is (= #{"Emil" "Mustermann" "Tisch"} (surnames (people f))))
      (is (= 4 (:next-id (crud f))))
      (is (= "" (:filter-text (crud f))))
      (is (nil? (:selected-id (crud f))))
      (is (= {:name "" :surname ""} (:draft (crud f)))))))

(deftest select-copies-name-and-surname-into-draft
  (testing "selecting a row remembers the selection and copies that person's
            name/surname into the draft (so the inputs show them)"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/select 2] {:frame f})
      (is (= 2 (:selected-id (crud f))))
      (is (= {:name "Max" :surname "Mustermann"} (:draft (crud f)))))))

;; ---------------------------------------------------------------------------
;; create — monotonic, replay-stable ids that are never reissued
;; ---------------------------------------------------------------------------

(deftest create-appends-with-next-id-and-selects-it
  (testing "create adds the draft as a new person with the next-id, bumps the
            allocator, and selects the new row"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/edit-name "Ada"] {:frame f})
      (rf/dispatch-sync [:crud/edit-surname "Lovelace"] {:frame f})
      (rf/dispatch-sync [:crud/create] {:frame f})
      (is (= [1 2 3 4] (mapv :id (people f))) "appended with id 4")
      (is (= {:id 4 :name "Ada" :surname "Lovelace"} (person f 4)))
      (is (= 5 (:next-id (crud f))) "allocator bumped")
      (is (= 4 (:selected-id (crud f))) "the new row is selected"))))

(deftest create-ids-are-monotonic-and-never-reissued
  (testing "rf2-e4nwdr — the id allocator is monotonic: a subsequent create
            never reuses an id, even one freed by an intervening delete. An
            allocator that reissues is a silent, replay-corrupting bug"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/create] {:frame f})            ;; id 4, selected 4
      (rf/dispatch-sync [:crud/create] {:frame f})            ;; id 5, selected 5
      (is (= [1 2 3 4 5] (mapv :id (people f))))
      (is (= 6 (:next-id (crud f))))
      (rf/dispatch-sync [:crud/delete] {:frame f})            ;; delete selected 5
      (is (= [1 2 3 4] (mapv :id (people f))))
      (rf/dispatch-sync [:crud/create] {:frame f})            ;; next id must be 6, NOT 5
      (is (= [1 2 3 4 6] (mapv :id (people f)))
          "the freed id 5 is NOT reissued — allocation stays monotonic")
      (is (= 7 (:next-id (crud f)))))))

;; ---------------------------------------------------------------------------
;; update — merge draft into the selected row only
;; ---------------------------------------------------------------------------

(deftest update-merges-draft-into-selected-row-only
  (testing "update writes the draft onto the selected person and leaves every
            other row untouched"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/select 1] {:frame f})          ;; draft <- Hans Emil
      (rf/dispatch-sync [:crud/edit-name "Johann"] {:frame f})
      (rf/dispatch-sync [:crud/update] {:frame f})
      (is (= {:id 1 :name "Johann" :surname "Emil"} (person f 1)) "selected row updated")
      (is (= {:id 2 :name "Max" :surname "Mustermann"} (person f 2)) "other rows untouched")
      (is (= {:id 3 :name "Roman" :surname "Tisch"} (person f 3))))))

(deftest update-with-no-selection-is-a-noop
  (testing "with nothing selected, update leaves the list unchanged"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/edit-name "Ghost"] {:frame f})
      (rf/dispatch-sync [:crud/update] {:frame f})
      (is (= [{:id 1 :name "Hans" :surname "Emil"}
              {:id 2 :name "Max" :surname "Mustermann"}
              {:id 3 :name "Roman" :surname "Tisch"}]
             (people f))))))

;; ---------------------------------------------------------------------------
;; delete — remove + clear selection/draft
;; ---------------------------------------------------------------------------

(deftest delete-removes-selected-and-clears-selection
  (testing "delete removes the selected person and clears both selection and draft"
    (let [f (crud-frame!)]
      (rf/dispatch-sync [:crud/select 2] {:frame f})
      (rf/dispatch-sync [:crud/delete] {:frame f})
      (is (= [1 3] (mapv :id (people f))) "row 2 removed")
      (is (nil? (:selected-id (crud f))) "selection cleared")
      (is (= {:name "" :surname ""} (:draft (crud f))) "draft cleared"))))

;; ---------------------------------------------------------------------------
;; filtered-people — case-insensitive surname prefix
;; ---------------------------------------------------------------------------

(deftest filtered-people-matches-surname-prefix-case-insensitively
  (testing ":crud/filtered-people keeps rows whose SURNAME starts with the
            prefix, case-insensitively; a blank filter shows everyone"
    (let [f (crud-frame!)]
      (is (= #{"Emil" "Mustermann" "Tisch"} (surnames (sub f [:crud/filtered-people])))
          "empty filter -> all")
      (rf/dispatch-sync [:crud/set-filter "mus"] {:frame f})
      (is (= #{"Mustermann"} (surnames (sub f [:crud/filtered-people]))))
      (rf/dispatch-sync [:crud/set-filter "EMIL"] {:frame f})
      (is (= #{"Emil"} (surnames (sub f [:crud/filtered-people])))
          "prefix match is on the SURNAME and case-insensitive")
      (rf/dispatch-sync [:crud/set-filter "z"] {:frame f})
      (is (= #{} (surnames (sub f [:crud/filtered-people]))) "no match -> empty"))))

;; ---------------------------------------------------------------------------
;; can-update? — filter-visibility of the selection (the example's point)
;; ---------------------------------------------------------------------------

(deftest can-update?-tracks-selection-visibility-under-filter
  (testing "rf2-e4nwdr — can-update? is true only when the selected row is
            visible under the current filter. Hiding the selection with a
            filter disables Update/Delete WITHOUT dropping the selection;
            clearing the filter re-enables them. Lighting them on a
            filtered-out selection would be a silent bug"
    (let [f (crud-frame!)]
      (is (false? (sub f [:crud/can-update?])) "no selection -> disabled")
      (rf/dispatch-sync [:crud/select 1] {:frame f})          ;; Hans, surname Emil
      (is (true? (sub f [:crud/can-update?])) "selected + visible -> enabled")
      (rf/dispatch-sync [:crud/set-filter "Mus"] {:frame f})  ;; hides Emil
      (is (false? (sub f [:crud/can-update?])) "selection hidden by filter -> disabled")
      (is (= 1 (:selected-id (crud f))) "the selection is NOT dropped, only hidden")
      (rf/dispatch-sync [:crud/set-filter ""] {:frame f})     ;; row reappears
      (is (true? (sub f [:crud/can-update?])) "filter cleared -> re-enabled"))))
