(ns re-frame.nine-states-cljs-test
  "Integration test: drives the nine-states example through the nine
   canonical UI states. Each helper spins a fresh frame via `make-frame`,
   drives `app-db` into one of the states, and asserts the matching
   machine tag union + the resolved `:ui/render` keyword. Browserless via
   `compute-sub` (no DOM required).

   The fixture fns live HERE (the adapter test tree), not under
   examples/reagent/nine_states/ — the example source stays test-free per
   the locked test-free-examples policy (rf2-8cevm). The ns requires the
   example's production source (`nine-states.core`) so its handlers / subs
   / views / machines register at ns-load, then exercises them directly.
   (rf2-cd2zo folded the former `nine-states.core-test` fixture ns in here
   and retired the example test/ dir.)

   The example registers its handlers / subs / views / machines at
   namespace-load time. CLJS has no runtime (require :reload), so this
   test relies on those registrations staying live for the test run.
   Each helper uses make-frame to spin up a fresh frame, so per-test
   isolation comes from frame creation, not registry resets.

   Per rf2-am9d the fixture uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures — the snapshot captures
   the example's ns-load registrations, and the restore on the way out
   leaves them intact for any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [nine-states.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ----------------------------------------------------------------------------
;; HELPERS
;; ----------------------------------------------------------------------------

(defn- machine-has-tag?
  "Read the machine's tag union against a frame's app-db."
  [frame tag]
  (contains? (get-in (rf/app-db-value frame)
                     [:rf/runtime :machines :snapshots :ui/nine-states :tags])
             tag))

(defn- render-model [frame]
  (rf/compute-sub [:ui/render] (rf/app-db-value frame)))

(def ^:private demo-overrides
  "Per-test :fx-overrides map that routes `:rf.http/managed` to the in-process
   demo stub so tests run without a backend."
  {:rf.http/managed :nine-states.http/managed-demo})

(defn- new-frame []
  (rf/make-frame
    {:on-create    [:nine-states.app/initialise]
     :fx-overrides demo-overrides}))

;; ----------------------------------------------------------------------------
;; ONE HELPER PER STATE
;; ----------------------------------------------------------------------------

(defn- test-state-1-nothing []
  (with-new-frame [f (new-frame)]
    (is       (machine-has-tag?    f :data/nothing))
    (is (not  (machine-has-tag?    f :data/loading)))
    (is (=    :nothing     (render-model f)))))

(defn- test-state-2-loading []
  ;; The demo stub resolves synchronously, so we observe :loading by
  ;; dispatching :fetch-started directly (without a follow-up reply).
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ui/nine-states [:fetch-started]] {:frame f})
    (is       (machine-has-tag?    f :data/loading))
    (is       (machine-has-tag?    f :data/transient))
    (is (=    :loading     (render-model f)))))

(defn- test-state-3-empty []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 0}] {:frame f})
    (is       (machine-has-tag?    f :data/empty))
    (is (not  (machine-has-tag?    f :data/one)))
    (is (=    :empty       (render-model f)))))

(defn- test-state-4-one []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 1}] {:frame f})
    (is       (machine-has-tag?    f :data/one))
    (is (=    :one         (render-model f)))))

(defn- test-state-5-some []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
    (is       (machine-has-tag?    f :data/some))
    (is (=    :some        (render-model f)))))

(defn- test-state-6-too-many []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame f})
    (is       (machine-has-tag?    f :data/too-many))
    (is (=    :too-many    (render-model f)))))

(defn- test-state-7-incorrect []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:new-todo/edit-field :title "ab"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (is       (machine-has-tag?    f :form/invalid))
    (is (=    :incorrect   (render-model f)))))

(defn- test-state-8-correct []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 0}] {:frame f})
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    ;; Tags reflect the overlap honestly: :form/success AND :data/one
    ;; are both true simultaneously. The render-priority table
    ;; resolves it: :correct wins.
    (is       (machine-has-tag?    f :form/success))
    (is       (machine-has-tag?    f :data/one))
    (is (=    :correct     (render-model f)))))

(defn- test-state-9-done []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
    (rf/dispatch-sync [:ui/nine-states [:archive {:now 1}]] {:frame f})
    (let [snap (get-in (rf/app-db-value f) [:rf/runtime :machines :snapshots :ui/nine-states])]
      (is (= :done (get-in snap [:state :mode])))
      (is (= 1    (get-in snap [:data :archived-at]))))
    (is       (machine-has-tag?    f :mode/done))
    (is       (machine-has-tag?    f :mode/read-only))
    (is (=    :done        (render-model f)))))

(deftest nine-states-runs-end-to-end
  (testing "state 1 — nothing (never fetched)"
    (test-state-1-nothing))
  (testing "state 2 — loading (fetch in flight)"
    (test-state-2-loading))
  (testing "state 3 — empty (fetched, zero results)"
    (test-state-3-empty))
  (testing "state 4 — one (single-item layout)"
    (test-state-4-one))
  (testing "state 5 — some (small, manageable list)"
    (test-state-5-some))
  (testing "state 6 — too-many (overwhelming list)"
    (test-state-6-too-many))
  (testing "state 7 — incorrect (form validation error)"
    (test-state-7-incorrect))
  (testing "state 8 — correct (form submit happy path)"
    (test-state-8-correct))
  (testing "state 9 — done (terminal, read-only)"
    (test-state-9-done)))
