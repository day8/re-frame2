(ns re-frame.nine-states-cljs-test
  "Integration test: drives the nine-states example through the nine
   canonical UI states. Each helper spins a fresh frame via `make-frame`,
   drives `app-db` into one of the states, and asserts the matching
   machine tag union + the resolved `:ui/render` keyword. Browserless via
   `compute-sub` (no DOM required).

   The fixture fns live HERE (the adapter test tree), not under
   examples/patterns/nine_states/ — the example source stays test-free per
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
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            ;; `re-frame.http.test-support` registers
            ;; `:rf.http/managed-canned-failure` — the fx the failing Story
            ;; variant routes `:rf.http/managed` to. nine-states.core
            ;; already pulls it transitively, but require it here so this
            ;; ns is self-sufficient.
            [re-frame.http.test-support]
            [nine-states.core]
            ;; Requiring the example's Story file brings
            ;; `:nine-states.story/load-failing` (the variant's setup
            ;; event) into the registry. The story-failure-variant test
            ;; below exercises the SAME mechanism the
            ;; `:story.nine-states-lifecycle/error` variant uses — the
            ;; canned-FAILURE fx-override plus that load event — and
            ;; asserts the resulting frame computes `[:ui/render]` as
            ;; `:error`.
            [nine-states.stories])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): each test spins its OWN top-level frame via
    ;; `make-frame`; opt out of the ambient `:rf/default` scope so the new
    ;; frame's `:initial-events` drain synchronously (top-level boot) rather than
    ;; being treated as a mid-cascade child-frame creation.
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

;; ----------------------------------------------------------------------------
;; HELPERS
;; ----------------------------------------------------------------------------

(defn- machine-has-tag?
  "Read the machine's tag union against a frame's app-db."
  [frame tag]
  (contains? (get-in (rf/frame-state-value frame)
                     [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states :tags])
             tag))

(defn- render-model [frame]
  (rf/compute-sub [:ui/render] (rf/frame-state-value frame)))

(defn- snapshot
  "The `:ui/nine-states` machine snapshot from a frame's runtime-db — carries
   `:state` (the region→state map for a parallel machine) and shared `:data`."
  [frame]
  (get-in (rf/frame-state-value frame)
          [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states]))

(def ^:private demo-overrides
  "Per-test :fx-overrides map that routes `:rf.http/managed` to the in-process
   demo stub so tests run without a backend."
  {:rf.http/managed :nine-states.http/managed-demo})

(defn- new-frame []
  (rf.frame/make-anon-frame-record!
    {:initial-events [[:nine-states.app/initialise]]
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
    (let [snap (get-in (rf/frame-state-value f) [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states])]
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

;; ----------------------------------------------------------------------------
;; RESET — whole-machine return to square one clears owned domain data
;;
;; The shipped "1. Nothing" control dispatches [:nine-states.app/initialise],
;; which seeds the form slice and broadcasts [:reset] into the machine. A
;; region's state keyword flipping back to :nothing does NOT, on its own,
;; rewrite the machine's :data — machine data is preserved across transitions
;; unless an action changes it. So reset must run a named :reset-domain action
;; to clear the loaded :items, any recorded :error, and the :archived-at
;; stamp. Without it, previously loaded todos survive hidden under a blank
;; :nothing view and resurrect on the next valid submit. These tests are the
;; sequence the per-state fixtures never exercise: two states separated by the
;; reset control. They pin that reset clears state AND owned data, matching
;; Pattern-RemoteData's reset contract.
;; ----------------------------------------------------------------------------

(deftest reset-clears-machine-owned-domain-data
  (testing "initialise after a load returns every region to initial AND wipes :data"
    (with-new-frame [f (new-frame)]
      ;; Load four todos → :data region rests at :some with 4 items.
      (rf/dispatch-sync [:nine-states.demo/load {:n 4}] {:frame f})
      (is (= 4 (count (get-in (snapshot f) [:data :items])))
          "precondition: four todos loaded")
      (is (= :some (render-model f)) "precondition: renders :some")

      ;; The shipped reset control ("1. Nothing").
      (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})

      ;; Full region map back to initial; :data wiped clean.
      (is (= {:data :nothing :form :neutral :mode :active}
             (:state (snapshot f)))
          "all three regions return to their initial state")
      (is (= {:items [] :error nil :archived-at nil}
             (:data (snapshot f)))
          "the machine's owned :data is wiped — no hidden survivors")
      (is (= :nothing (render-model f)) "the page renders the blank :nothing slate")

      ;; A valid submit now yields exactly ONE todo (State 4 / :one), not five.
      ;; The four pre-reset rows must NOT resurrect.
      (rf/dispatch-sync [:new-todo/edit-field :title "Buy milk"] {:frame f})
      (rf/dispatch-sync [:new-todo/submit] {:frame f})
      (let [items (rf/compute-sub [:todos/items] (rf/frame-state-value f))]
        (is (= 1 (count items))
            "only the freshly-submitted todo is present")
        (is (= ["Buy milk"] (mapv :title items))
            "none of the four pre-reset rows reappear"))
      ;; :form/success wins the render-priority table over :data/one, so the
      ;; page shows :correct — but the :data region has genuinely settled at
      ;; :one beneath it (single item), not :some.
      (is (= :correct (render-model f)) "the submit acknowledgement wins the priority table")
      (is       (machine-has-tag? f :data/one) "the :data region rests at :one, not :some")
      (is (not  (machine-has-tag? f :data/some)) "the pre-reset :some cardinality is gone"))))

(deftest reset-clears-recorded-failure-and-thaws-done
  (testing "reset wipes a recorded :error and returns an archived :done machine to :active"
    (with-new-frame [f (new-frame)]
      ;; Record a failure: the demo's rigged-to-fail load routes the :data
      ;; region to :error, stamping the failure map at [:data :error].
      (rf/dispatch-sync [:nine-states.demo/load-with-failure] {:frame f})
      (is       (machine-has-tag? f :data/error) "precondition: failure recorded")
      (is (some? (get-in (snapshot f) [:data :error])) "precondition: :error slot populated")

      ;; Archive it: the :mode region freezes at :done, stamping :archived-at.
      (rf/dispatch-sync [:ui/nine-states [:archive {:now 7}]] {:frame f})
      (is (= :done (get-in (snapshot f) [:state :mode])) "precondition: archived (:mode :done)")
      (is (= 7    (get-in (snapshot f) [:data :archived-at])) "precondition: archive stamped")

      ;; Reset the whole machine.
      (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
      (is (= {:data :nothing :form :neutral :mode :active} (:state (snapshot f)))
          ":mode thaws from :done back to :active; data and form reset too")
      (is (= {:items [] :error nil :archived-at nil} (:data (snapshot f)))
          "the recorded failure and the archive stamp are both cleared")
      (is (= :nothing (render-model f))
          "renders the blank slate, not the archived or error view"))))

(deftest reset-from-loading-abandons-in-flight-load
  (testing "reset accepted mid-load lands at :nothing; a late completion cannot mutate it"
    (with-new-frame [f (new-frame)]
      ;; Enter :loading without a synchronous reply, as the state-2 fixture
      ;; does — dispatch :fetch-started directly with no follow-up.
      (rf/dispatch-sync [:ui/nine-states [:fetch-started]] {:frame f})
      (is (machine-has-tag? f :data/loading) "precondition: fetch in flight")

      ;; Reset while loading.
      (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
      (is (= :nothing (get-in (snapshot f) [:state :data]))
          "reset from :loading lands the :data region at :nothing")
      (is (= [] (get-in (snapshot f) [:data :items])) ":data is clean after the abandon")

      ;; The abandoned load now completes. At :nothing the machine does not
      ;; listen for :fetch-succeeded, so the stale reply is inert.
      (rf/dispatch-sync [:ui/nine-states
                         [:fetch-succeeded {:items [{:id 1 :title "ghost-1" :done? false}
                                                    {:id 2 :title "ghost-2" :done? false}]}]]
                        {:frame f})
      (is (= :nothing (get-in (snapshot f) [:state :data]))
          "the late completion does not move the region off :nothing")
      (is (= [] (get-in (snapshot f) [:data :items]))
          "the abandoned load's items never land"))))

;; ----------------------------------------------------------------------------
;; STORY LIFECYCLE — :error variant
;;
;; The `:story.nine-states-lifecycle/error` variant in
;; examples/patterns/nine_states/stories.cljs stamps
;; `:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}` so
;; its `[:nine-states.story/load-failing]` setup runs against the canned-
;; FAILURE stub (overriding the `:story` preset's canned-success default).
;; This regression pins that the failing variant actually takes the error
;; branch: a frame carrying the SAME failure override, running the SAME
;; load-failing event, must land the `:data` region at `:error` (tag
;; `:data/error`) and compute `[:ui/render]` as `:error` — NOT settle on a
;; loaded/empty success state, which is the bug rf2-t5ky67 caught.
;; ----------------------------------------------------------------------------

(def ^:private story-failure-overrides
  "The exact `:fx-overrides` the lifecycle `:error` variant stamps — routes
   `:rf.http/managed` to the framework canned-FAILURE stub."
  {:rf.http/managed :rf.http/managed-canned-failure})

(deftest story-lifecycle-error-variant-takes-failure-path
  (testing "the failing Story variant's mechanism lands :data at :error and renders :error"
    ;; Sanity: requiring nine-states.stories registered the variant's
    ;; setup event.
    (is (some? (rf/handler-meta :event :nine-states.story/load-failing))
        ":nine-states.story/load-failing registered (stories.cljs required)")
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:initial-events [[:nine-states.app/initialise]]
                          :fx-overrides story-failure-overrides})]
      ;; Drive the variant's setup load event. The canned-failure stub
      ;; resolves synchronously (no :after-ms), dispatching :on-failure →
      ;; :nine-states.demo/load-failed → :fetch-failed → :data/:error.
      (rf/dispatch-sync [:nine-states.story/load-failing] {:frame f})
      (is       (machine-has-tag? f :data/error)
                "the :data region reaches the :error state (tag :data/error)")
      (is (not  (machine-has-tag? f :data/some))
                "it did NOT settle on a success/loaded cardinality bucket")
      (is (=    :error (render-model f))
          "[:ui/render] resolves to :error under the machine snapshot")
      ;; The failure category threaded through to the snapshot's :data.
      (let [err (rf/compute-sub [:todos/error] (rf/frame-state-value f))]
        (is (some? err)
            "the failure value is recorded in the machine's :data :error slot")))))

(deftest story-lifecycle-success-variant-stays-on-success-path
  (testing "the loaded variant's success-stub mechanism still proves the canned-success cascade"
    ;; Mirror the `:story` preset's DEFAULT: route `:rf.http/managed` to
    ;; the canned-SUCCESS stub (which echoes the request's `:value` slot),
    ;; exactly as the `:story.nine-states-lifecycle/loaded` variant does
    ;; with no per-variant override. Guards that success and failure stay
    ;; SEPARATELY represented (acceptance: keep the success lifecycle
    ;; variant proving canned-success).
    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:initial-events [[:nine-states.app/initialise]]
                          :fx-overrides {:rf.http/managed
                                         :rf.http/managed-canned-success}})]
      ;; `:nine-states.story/load` carries the synthetic todos on `:value`
      ;; (the slot canned-success echoes), so 4 items land the :data
      ;; region at :some via the :always-cascade.
      (rf/dispatch-sync [:nine-states.story/load {:n 4}] {:frame f})
      (is       (machine-has-tag? f :data/some))
      (is (not  (machine-has-tag? f :data/error)))
      (is (=    :some (render-model f))))))
