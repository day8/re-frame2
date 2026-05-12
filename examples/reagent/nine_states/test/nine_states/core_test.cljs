(ns nine-states.core-test
  "Headless tests for nine-states.core.

   One fixture per state. Each: drive `app-db` to the state via
   `dispatch-sync`, then assert against the machine's tag union and the
   resolved `:ui/render` keyword. Browserless via `compute-sub` (no DOM
   required).

   Wired into the framework's CLJS test run by
   `re-frame.nine-states-cljs-test` under
   `implementation/adapters/reagent/test/re_frame/`, which wraps each
   fixture in a `testing` block under a single `deftest`."
  (:require [cljs.test :refer-macros [is]]
            [re-frame.core :as rf]
            [nine-states.core])
  (:require-macros [re-frame.core :refer [with-frame]]))

(defn- has-tag?
  "Read the machine's tag union against a frame's app-db."
  [frame tag]
  (contains? (get-in (rf/get-frame-db frame)
                     [:rf/machines :ui/nine-states :tags])
             tag))

(defn- render-model [frame]
  (rf/compute-sub [:ui/render] (rf/get-frame-db frame)))

(def ^:private demo-overrides
  "Per-test :fx-overrides map that routes `:rf.http/managed` to the in-process
   demo stub so tests run without a backend."
  {:rf.http/managed :nine-states.http/managed-demo})

(defn- new-frame []
  (rf/make-frame
    {:on-create    [:nine-states.app/initialise]
     :fx-overrides demo-overrides}))

(defn test-state-1-nothing []
  (with-frame [f (new-frame)]
    (is       (has-tag?    f :data/nothing))
    (is (not  (has-tag?    f :data/loading)))
    (is (=    :nothing     (render-model f)))))

(defn test-state-2-loading []
  ;; The demo stub resolves synchronously, so we observe :loading by
  ;; dispatching :fetch-started directly (without a follow-up reply).
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:ui/nine-states [:fetch-started]] {:frame f})
    (is       (has-tag?    f :data/loading))
    (is       (has-tag?    f :data/transient))
    (is (=    :loading     (render-model f)))))

(defn test-state-3-empty []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 0}] {:frame f})
    (is       (has-tag?    f :data/empty))
    (is (not  (has-tag?    f :data/one)))
    (is (=    :empty       (render-model f)))))

(defn test-state-4-one []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 1}] {:frame f})
    (is       (has-tag?    f :data/one))
    (is (=    :one         (render-model f)))))

(defn test-state-5-some []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
    (is       (has-tag?    f :data/some))
    (is (=    :some        (render-model f)))))

(defn test-state-6-too-many []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame f})
    (is       (has-tag?    f :data/too-many))
    (is (=    :too-many    (render-model f)))))

(defn test-state-7-incorrect []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:new-todo/edit-field :title "ab"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    (is       (has-tag?    f :form/invalid))
    (is (=    :incorrect   (render-model f)))))

(defn test-state-8-correct []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 0}] {:frame f})
    (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
    (rf/dispatch-sync [:new-todo/submit] {:frame f})
    ;; Tags reflect the overlap honestly: :form/success AND :data/one
    ;; are both true simultaneously. The render-priority table
    ;; resolves it: :correct wins.
    (is       (has-tag?    f :form/success))
    (is       (has-tag?    f :data/one))
    (is (=    :correct     (render-model f)))))

(defn test-state-9-done []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
    (rf/dispatch-sync [:ui/nine-states [:archive {:now 1}]] {:frame f})
    (let [snap (get-in (rf/get-frame-db f) [:rf/machines :ui/nine-states])]
      (is (= :done (get-in snap [:state :mode])))
      (is (= 1    (get-in snap [:data :archived-at]))))
    (is       (has-tag?    f :mode/done))
    (is       (has-tag?    f :mode/read-only))
    (is (=    :done        (render-model f)))))
