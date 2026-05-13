(ns day8.re-frame2-causa.panels.event-detail-cljs-test
  "Tests for the Causa event-detail hero panel (Phase 2, rf2-op3bz).

  ## Three contracts under test

  1. **Cascade list when nothing is selected.** With trace events in
     the buffer but no selected dispatch-id, the panel's
     `:rf.causa/event-detail` composite sub returns every cascade and
     the view renders the empty-state list. Clicking a cascade row
     fires `:rf.causa/select-dispatch-id`.

  2. **Cascade detail when something is selected.** With a selection
     set via `:rf.causa/select-dispatch-id`, the composite sub returns
     the matching cascade record and the view renders the six-domino
     rows.

  3. **Clear selection.** Dispatching `:rf.causa/clear-selected-
     dispatch-id` empties the selection and the view returns to the
     cascade list.

  ## Pure-data scope

  The view is pure hiccup; the tests assert against the hiccup tree
  rather than booting a substrate adapter / mounting to the DOM. This
  keeps the suite fast and host-portable per the same node-test
  rationale as `preload_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  ;; Reset the :sensitive? privacy gate to its default (suppress) so the
  ;; redaction-state tests below start from a known baseline regardless
  ;; of test ordering or prior toggles. Symmetric with
  ;; sensitive_trace_cljs_test's own fixture.
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture trace stream ------------------------------------------------

(defn- cascade-evs
  "Produce a representative one-cascade event stream — same shape as
  re-frame.trace.projection's own tests. `dispatch-id` rides on every
  event's `:tags :dispatch-id` per rf2-g6ih4."
  [dispatch-id event-vec id-base]
  [{:id (+ id-base 1) :op-type :event    :operation :event/dispatched
    :tags {:dispatch-id dispatch-id :event event-vec}}
   {:id (+ id-base 2) :op-type :event    :operation :event
    :tags {:dispatch-id dispatch-id :phase :run-start}}
   {:id (+ id-base 3) :op-type :event    :operation :event
    :tags {:dispatch-id dispatch-id :phase :run-end}}
   {:id (+ id-base 4) :op-type :event    :operation :event/do-fx
    :tags {:dispatch-id dispatch-id}}
   {:id (+ id-base 5) :op-type :fx       :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id :fx-id :db}}
   {:id (+ id-base 6) :op-type :fx       :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id :fx-id :dispatch}}
   {:id (+ id-base 7) :op-type :sub/run  :operation :sub/run
    :tags {:dispatch-id dispatch-id :sub-id :sub/foo}}
   {:id (+ id-base 8) :op-type :view     :operation :view/render
    :tags {:dispatch-id dispatch-id :render-key [:app/root nil]}}])

(defn- seed-buffer!
  "Register Causa's handlers, allocate the :rf/causa frame, then push
  the supplied events through Causa's reactive trace-buffer slot.

  Per rf2-iw5ym `:rf.causa/trace-buffer` is reactive off Causa's
  app-db; the buffer-bus atom is no longer the sub's source of
  truth. Tests drive the new reactive write path via `dispatch-sync`
  of `:rf.causa/note-trace-event` so the composite sub re-fires
  synchronously before the view is rendered. (Production wiring
  still goes through `trace-bus/collect-trace!`; that path is
  covered by sensitive_trace_cljs_test + filter_vocab_consumer_
  cljs_test.)"
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (doseq [ev evs]
      (rf/dispatch-sync [:rf.causa/note-trace-event ev]))))

(defn- expand-fn-component
  "When `node` is a hiccup-style `[fn-component args...]` vector
  (first element is a function rather than a keyword/string), invoke
  the fn with the rest of the args so the test can recurse into the
  rendered sub-tree. Otherwise return the node unchanged."
  [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq
  "Walk a hiccup tree and emit every node (vectors only). Vectors
  whose first element is a function are invoked first so the walker
  descends into the sub-tree they would render to."
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid
  "Find the first node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns nil when no such node exists."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; ---- (1) empty state: cascade list --------------------------------------

(deftest empty-state-renders-cascade-list
  (testing "with cascades in the buffer but no selection, the panel
            renders the cascade list"
    (seed-buffer! (concat (cascade-evs 100 [:user/login {:id 42}] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present")
        (is (some? (find-by-testid tree "rf-causa-cascade-list"))
            "cascade list container present")
        (is (some? (find-by-testid tree "rf-causa-cascade-row-100"))
            "cascade 100 has a clickable row")
        (is (some? (find-by-testid tree "rf-causa-cascade-row-200"))
            "cascade 200 has a clickable row")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container absent when no selection")))))

(deftest empty-state-renders-with-no-cascades
  (testing "with an empty buffer + no selection the panel still
            renders the empty-state container"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present even with an empty buffer")
        (is (nil? (find-by-testid tree "rf-causa-cascade-list"))
            "no cascade list when there are zero cascades")))))

;; ---- (2) cascade-detail when a dispatch-id is selected ------------------

(deftest cascade-detail-renders-six-dominoes
  (testing "after selecting a dispatch-id the panel switches to the
            cascade-detail layout"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container absent once a selection is set")
        (is (some? (find-by-testid tree "rf-causa-event-detail-clear"))
            "clear button is rendered alongside the cascade")))))

(deftest selecting-non-existent-dispatch-id-shows-orphaned-state
  (testing "selecting a dispatch-id that's not in the buffer surfaces
            the orphaned-selection branch"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      ;; 999 is not in the buffer — the composite sub returns
      ;; :selected-cascade=nil; the view should surface the
      ;; orphaned-state branch rather than the cascade rows.
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 999])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "orphaned-selection container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail absent when the id has no matching cascade")))))

;; ---- (3) the select / clear events -------------------------------------

(deftest select-dispatch-id-event-writes-to-causa-frame
  (testing ":rf.causa/select-dispatch-id sets :selected-dispatch-id on
            the :rf/causa frame's db (not the host's :rf/default)"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 42]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= 42 (:selected-dispatch-id causa-db))
          "selection lands on the Causa frame")
      (is (nil? (:selected-dispatch-id default-db))
          "selection did NOT leak into :rf/default"))))

(deftest clear-selected-dispatch-id-returns-to-empty-state
  (testing "after select + clear the panel renders the empty state"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present after clear")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container absent after clear")))))

;; ---- (4) defaults / wiring ----------------------------------------------

(deftest default-panel-id-is-event-detail
  (testing "registry/default-panel-id is :event-detail per §10 Lock 7"
    (is (= :event-detail registry/default-panel-id))))

(deftest selected-panel-sub-defaults-to-event-detail
  (testing ":rf.causa/selected-panel returns :event-detail before any
            :rf.causa/select-panel has fired"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (= :event-detail
             @(rf/subscribe [:rf.causa/selected-panel]))))))

(deftest selected-panel-sub-tracks-select-panel-event
  (testing "dispatching :rf.causa/select-panel updates the sub"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-panel :app-db])
      (is (= :app-db @(rf/subscribe [:rf.causa/selected-panel]))))))

;; ---- (5) edge cases: empty buffer / missing id / sensitive redacted ----
;;
;; Per rf2-dkmq5: the existing tests cover the happy path; this section
;; extends to the edge cases listed in the bead's findings doc
;; (`ai/findings/causa-test-coverage-20260513-1706.md` recommendation #8).
;;
;;   - Empty buffer with a selection set — the panel must render the
;;     orphaned-state branch rather than crashing on a nil cascade.
;;   - No selection (initial state, empty buffer) — per spec/007-UX-IA.md
;;     §The default landing view the panel renders the empty-state
;;     container with the "no cascades yet" placeholder copy.
;;   - Sensitive-redacted state — when the privacy gate has dropped the
;;     selected dispatch-id's trace events the panel renders the
;;     orphaned branch AND the shell-level `:rf.causa/suppressed-
;;     sensitive-count` sub reports the suppression so the bottom-rail
;;     `[● REDACTED N]` marker (rf2-a6buk / PR #705) renders.
;;   - Sensitive opt-in pass-through — flipping `:trace/show-sensitive?`
;;     true lets the same cascade reach the buffer; the panel then
;;     renders the six-domino layout with no suppression count.

(deftest empty-buffer-with-selection-renders-orphaned-state
  (testing "selecting a dispatch-id when the buffer is empty surfaces
            the orphaned-selection branch rather than the cascade rows
            or a nil-deref crash"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 42])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "orphaned-selection container present when buffer is empty")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail absent — there is no cascade to render")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state cascade-list absent — a selection is set, so the
             panel is in detail mode not list mode")))))

(deftest no-selection-empty-buffer-renders-default-landing-view
  (testing "per spec/007-UX-IA.md §Default landing view, the panel's
            initial state (no selection, no events) renders the empty-
            state container with the 'no cascades yet' placeholder copy"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree    (event-detail/event-detail-view)
            empty   (find-by-testid tree "rf-causa-event-detail-empty")
            ;; Flatten every text node under the empty-state container
            ;; so the assertion is agnostic to the placeholder paragraph's
            ;; exact hiccup nesting.
            text    (->> (hiccup-seq empty)
                         (filter string?)
                         (apply str))]
        (is (some? empty)
            "empty-state container present in the initial state")
        (is (nil? (find-by-testid tree "rf-causa-cascade-list"))
            "no cascade list when there are zero cascades")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "no cascade-detail container in the initial state")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "no orphaned-state container in the initial state")
        (is (re-find #"No cascades yet" text)
            "placeholder copy guides the user to trigger a dispatch")))))

(deftest sensitive-redacted-cascade-renders-orphaned-and-bumps-count
  (testing "when the privacy gate drops a :sensitive? cascade entirely,
            the selected dispatch-id has no buffer match — the panel
            renders the orphaned branch AND the shell-level redaction-
            count sub reports the suppression so the bottom rail's
            [● REDACTED N] marker (rf2-a6buk / PR #705) renders.

            The bump is driven through the reactive production event
            `:rf.causa/note-sensitive-suppressed` (rf2-0vxdn) — same
            path `trace-bus/collect-trace!` uses when it drops a
            `:sensitive? true` trace event in CLJS. Dispatch-sync so
            the sub fires before the next render."
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    ;; Privacy gate at its default (suppress sensitive). Verify a
    ;; sensitive event going through trace-bus's collector is dropped
    ;; before the buffer (per Spec 009 §Privacy + rf2-azls9) — the
    ;; cascade's events never land, so the selected dispatch-id
    ;; resolves to no cascade.
    (trace-bus/collect-trace! {:id 1
                               :op-type :event
                               :operation :event/dispatched
                               :sensitive? true
                               :tags {:dispatch-id 777
                                      :event [:user/secret]
                                      :frame :rf/default}})
    (is (empty? (trace-bus/buffer))
        "the :sensitive? event was dropped before the buffer push")
    ;; Drive the redaction-count bump through the reactive path
    ;; explicitly. (The collect-trace! call above also dispatches
    ;; `:rf.causa/note-sensitive-suppressed`, but only when
    ;; `:rf/default` exists in the runtime; the test fixture leaves
    ;; the chain-routing detail to the shell-side tests.)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/note-sensitive-suppressed :rf/default])
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 777])
      (let [tree   (event-detail/event-detail-view)
            count* @(rf/subscribe [:rf.causa/suppressed-sensitive-count])]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "orphaned-selection container present — the redacted cascade
             never reached the buffer")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail absent — the redacted cascade has no rows
             to render")
        (is (pos? count*)
            "the suppressed-sensitive-count sub reports the drop so the
             shell's bottom-rail [● REDACTED N] marker renders")))))

(deftest sensitive-cascade-with-opt-in-renders-detail
  (testing "with (causa-config/configure! {:trace/show-sensitive? true})
            the same `:sensitive? true` event is NOT suppressed by
            the privacy gate — the predicate inverts and the cascade
            reaches the buffer. The panel then renders the cascade
            via the standard happy-path branch.

            We seed the reactive app-db buffer slot directly (same
            pattern the other tests use) so the assertion stays
            substrate-agnostic; the privacy-gate predicate is the
            unit under test here, not collect-trace!'s atom-swap
            (which `sensitive_trace_cljs_test` covers exhaustively)."
    (config/set-show-sensitive! true)
    ;; First — assert the privacy-gate predicate's opt-in path: with
    ;; show-sensitive? true, a sensitive event does NOT get suppressed.
    (is (false? (config/suppress-sensitive?
                  {:sensitive? true
                   :tags {:dispatch-id 888 :event [:user/secret]}}))
        "with show-sensitive? true, the privacy gate is inert")
    ;; Then — drive the reactive buffer to the same shape `collect-
    ;; trace!` would produce with the gate open, and assert the panel
    ;; renders the cascade-detail layout (no orphaned branch, no
    ;; suppressed-count bump).
    (seed-buffer! (->> (cascade-evs 888 [:user/secret] 200)
                       (map #(assoc % :sensitive? true))))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 888])
      (let [tree   (event-detail/event-detail-view)
            count* @(rf/subscribe [:rf.causa/suppressed-sensitive-count])]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail renders the six-domino layout under the
             opt-in flag")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "no orphaned-state when the cascade is in the buffer")
        (is (zero? count*)
            "the suppressed-count is zero — nothing was dropped")))))
