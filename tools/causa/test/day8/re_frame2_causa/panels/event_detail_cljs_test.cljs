(ns day8.re-frame2-causa.panels.event-detail-cljs-test
  "Tests for the Causa event-detail hero panel (Phase 2, rf2-op3bz).

  ## Three contracts under test

  1. **Default-focus head cascade on cold start.** With trace events
     in the buffer but no explicit selection (rf2-639lc Bug 2), the
     panel's `:rf.causa/event-detail` composite sub defaults
     `:selected-dispatch-id` to the head (most recent routed)
     cascade so the view renders cascade DETAIL on first mount.
     The pre-rf2-639lc landing-list behaviour was redundant under the
     4-layer chrome — the L2 event list is always visible alongside
     L4 so the panel-internal list never carried weight (rf2-lv9bc).

  2. **Cascade detail when something is selected.** With a selection
     set via `:rf.causa/select-dispatch-id`, the composite sub returns
     the matching cascade record and the view renders the six-domino
     rows.

  3. **Clear selection.** Dispatching `:rf.causa/clear-selected-
     dispatch-id` empties the explicit selection; the panel
     default-focuses the head cascade again per (1).

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
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
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
  ([dispatch-id event-vec id-base]
   (cascade-evs dispatch-id event-vec id-base nil))
  ([dispatch-id event-vec id-base frame-id]
   [{:id (+ id-base 1) :op-type :event    :operation :event/dispatched
     :tags (cond-> {:dispatch-id dispatch-id :event event-vec}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 2) :op-type :event    :operation :event
     :tags (cond-> {:dispatch-id dispatch-id :phase :run-start}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 3) :op-type :event    :operation :event
     :tags (cond-> {:dispatch-id dispatch-id :phase :run-end}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 4) :op-type :event    :operation :event/do-fx
     :tags (cond-> {:dispatch-id dispatch-id}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 5) :op-type :fx       :operation :rf.fx/handled
     :tags (cond-> {:dispatch-id dispatch-id :fx-id :db}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 6) :op-type :fx       :operation :rf.fx/handled
     :tags (cond-> {:dispatch-id dispatch-id :fx-id :dispatch}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 7) :op-type :sub/run  :operation :sub/run
     :tags (cond-> {:dispatch-id dispatch-id :sub-id :sub/foo}
             frame-id (assoc :frame frame-id))}
    {:id (+ id-base 8) :op-type :view     :operation :view/render
     :tags (cond-> {:dispatch-id dispatch-id :render-key [:app/root nil]}
             frame-id (assoc :frame frame-id))}]))

(defn- seed-buffer!
  "Register Causa's handlers, allocate the :rf/causa frame, then push
  the supplied events through Causa's trace-bus atom via
  `collect-trace!` — the production path. Per rf2-e9s81
  `:rf.causa/trace-buffer` thunks the atom, so a subsequent
  subscribe returns the events directly."
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

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
  descends into the sub-tree they would render to.

  ## Recursive descent through fn-components (rf2-6ja23)

  `[handler-row handler]` is a fn-component vector — the walker must
  invoke `handler-row` to get the inner hiccup and then keep
  descending. tree-seq alone only walks the structural children of
  the original tree; without an `expand-fn-component` step in the
  `children` lambda, deep testids inside fn-component vector bodies
  (e.g. the `rf-causa-event-detail-tier-dot-*` span inside
  `handler-row`) are unreachable."
  [tree]
  (let [children (fn [node]
                   (let [expanded (expand-fn-component node)]
                     (when (or (vector? expanded) (seq? expanded))
                       (seq expanded))))]
    (->> (tree-seq (some-fn vector? seq?) children (expand-fn-component tree))
         (map expand-fn-component))))

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

;; ---- (1) initial state: LIVE auto-focuses head (rf2-s0s5x Phase A) ------
;;
;; Pre-rf2-s0s5x the panel landed on a cascade-list 'empty state' until
;; the user clicked a row. Phase A makes the panel spine-driven —
;; `:rf.causa/event-detail` reads its `:selected-dispatch-id` off the
;; spine `:rf.causa/focus` sub. With no stored focus, the spine
;; composer returns LIVE + head; the panel renders the head cascade's
;; detail. The cascade-list landing page only renders when the buffer
;; is empty (no head to focus on).
;;
;; This subsumes the rf2-639lc Bug 2 default-focus contract — the
;; spine's LIVE-tracks-head semantics is the canonical implementation
;; of "head is the default selection"; the panel-side filter below
;; preserves rf2-639lc Bug 1 (skip the :ungrouped bucket).

(deftest live-focus-renders-head-cascade-detail
  (testing "with cascades in the buffer + no explicit selection, the
            spine LIVE-tracks head and the panel renders the head
            cascade's detail (rf2-s0s5x Phase A / rf2-639lc Bug 2)"
    (seed-buffer! (concat (cascade-evs 100 [:user/login {:id 42}] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      ;; Composite sub: effective selection is the head's dispatch-id.
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 200 (:selected-dispatch-id data))
            "head cascade (200, latest) is the default selection"))
      ;; View: cascade-detail container renders for the head, not the
      ;; landing list.
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container renders for the head cascade")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "no empty-state container when there's a head to focus on")
        (is (nil? (find-by-testid tree "rf-causa-cascade-list"))
            "panel-internal cascade list NOT rendered on default-focus
             — the L2 event list (rf-causa-event-list) is the list
             affordance under the 4-layer chrome (rf2-lv9bc)")))))

(deftest cold-start-with-no-cascades-renders-empty-container
  (testing "with an empty buffer + no selection the panel still
            renders the empty-state container — no head to focus on"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present even with an empty buffer")
        (is (nil? (find-by-testid tree "rf-causa-cascade-list"))
            "no cascade list when there are zero cascades")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "no cascade-detail when there is nothing to default-focus")))))

(deftest cold-start-default-focus-skips-ungrouped-bucket
  (testing "per rf2-639lc Bug 1 the head-fallback ignores the
            `:ungrouped` cascade (registry-time emits / frame
            lifecycle outside a drain). Synthesise a trace with a
            real cascade plus a stray registry-time emit (no
            :dispatch-id tag — lands in the :ungrouped bucket); the
            default focus must land on the real cascade's id."
    (seed-buffer!
      (concat (cascade-evs 100 [:user/login] 0)
              ;; Stray emit with no :dispatch-id — group-cascades
              ;; routes this to the :ungrouped bucket.
              [{:id 50 :op-type :registry :operation :sub/registered
                :tags {:sub-id :foo/bar}}]))
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= 100 (:selected-dispatch-id data))
            "default-focus picks the routed cascade (100), not the
             :ungrouped bucket")))))

;; ---- (2) cascade-detail when a dispatch-id is selected ------------------

(deftest cascade-detail-renders-six-dominoes
  (testing "after selecting a dispatch-id the panel switches to the
            cascade-detail layout"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container absent once a selection is set")
        ;; Per rf2-lv9bc — the panel-internal '← Events' back-link was
        ;; a pre-4-layer-chrome leftover. Under the 4-layer chrome the
        ;; L2 event list is always visible alongside the L4 detail, so
        ;; the affordance is meaningless. Assert positively that no
        ;; back-link is rendered in either the header (cascade view)
        ;; or the orphaned branch.
        (is (nil? (find-by-testid tree "rf-causa-event-detail-back"))
            "no internal '← Events' back-link in the header — L2 event
             list is always visible alongside L4 detail (rf2-lv9bc)")))))

(deftest selecting-non-existent-dispatch-id-shows-orphaned-state
  (testing "selecting a dispatch-id that's not in the buffer surfaces
            the orphaned-selection branch"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      ;; 999 is not in the buffer — the composite sub returns
      ;; :selected-cascade=nil; the view should surface the
      ;; orphaned-state branch rather than the cascade rows.
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 999])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "orphaned-selection container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail absent when the id has no matching cascade")
        ;; rf2-lv9bc — the orphaned branch's '← Events' button was the
        ;; same pre-4-layer-chrome leftover; under the chrome the L2
        ;; list is always visible so the user picks another cascade
        ;; directly. The handler (`:rf.causa/clear-selected-dispatch-id`)
        ;; is retained for programmatic clear.
        (is (nil? (find-by-testid tree "rf-causa-event-detail-orphaned-back"))
            "no '← Events' button in the orphaned branch (rf2-lv9bc)")))))

(deftest frame-qualified-selection-renders-the-matching-cascade
  (testing "same dispatch-id in two frames resolves to the selected frame's cascade"
    (seed-buffer! (concat (cascade-evs 100 [:counter/a-inc] 0 :counter/a)
                          (cascade-evs 100 [:counter/b-inc] 100 :counter/b)))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100 :counter/b])
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (= :counter/b (:selected-dispatch-frame data)))
        (is (= :counter/b (get-in data [:selected-cascade :frame])))
        (is (= [:counter/b-inc] (get-in data [:selected-cascade :event])))))))

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

(deftest clear-selected-dispatch-id-snaps-to-live-head
  (testing "after select + clear the panel snaps back to LIVE
            head-tracking (rf2-s0s5x Phase A / rf2-639lc Bug 2).
            Clearing the selection means 'resume following the live
            stream' — the spine resets to :live and the panel renders
            the head cascade's detail."
    (seed-buffer! (concat (cascade-evs 100 [:user/login] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (let [data @(rf/subscribe [:rf.causa/event-detail])
            tree (event-detail/Panel)]
        (is (= 200 (:selected-dispatch-id data))
            "after clear the focus snaps back to the head (200) via the
             spine's LIVE head-tracking")
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "head cascade's detail renders after clear (LIVE auto-tracks head)")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "no empty-state container — LIVE is focused on the head cascade")))))

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
      (let [tree (event-detail/Panel)]
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
            state container silently — per rf2-b9f6z the panel reflects
            the L2 event-list focus like every other panel, so the
            empty container carries no prose"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree    (event-detail/Panel)
            empty   (find-by-testid tree "rf-causa-event-detail-empty")
            ;; Flatten every text node under the empty-state container
            ;; so the silent-by-default assertion is agnostic to the
            ;; container's exact hiccup nesting.
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
        (is (= "" text)
            "silent-by-default — empty-state container carries no prose
             (rf2-b9f6z: drop pre-spine 'pick from cascade list' wording)")))))

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
      (let [tree   (event-detail/Panel)
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
      (let [tree   (event-detail/Panel)
            count* @(rf/subscribe [:rf.causa/suppressed-sensitive-count])]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail renders the six-domino layout under the
             opt-in flag")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "no orphaned-state when the cascade is in the buffer")
        (is (zero? count*)
            "the suppressed-count is zero — nothing was dropped")))))

;; ---- (6) handler-row perf-tier dot (rf2-6ja23) --------------------------
;;
;; The rf2-6ja23 audit hoists the perf-tier ladder into `theme/perf_tier.cljc`
;; and wires the first cross-panel consumer: the handler row already
;; surfaces `:duration-ms` (as raw EDN text); the audit adds a coloured
;; dot + label so the eye picks up the tier the same way it does on the
;; Performance panel.
;;
;; The tests below assert the dot renders for each tier's representative
;; duration. The dot's testid carries the tier suffix
;; (`rf-causa-event-detail-tier-dot-<tier>`) so the assertion is precise.

(defn- cascade-evs-with-duration
  "Variant of `cascade-evs` where the `:run-end` handler trace carries
  a `:duration-ms` tag. The handler-row should render a perf-tier dot
  matching `(classify-tier duration-ms)`."
  [dispatch-id event-vec id-base duration-ms]
  (mapv (fn [ev]
          (if (= :run-end (get-in ev [:tags :phase]))
            (assoc-in ev [:tags :duration-ms] duration-ms)
            ev))
        (cascade-evs dispatch-id event-vec id-base)))

(deftest handler-row-renders-fast-tier-dot
  (testing "handler :duration-ms 5ms classifies as :fast — green dot"
    (seed-buffer! (cascade-evs-with-duration 300 [:counter/inc] 300 5))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 300])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-tier-dot-fast"))
            "fast-tier dot present alongside the :duration-ms text")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-medium")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-slow")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-blocking")))))))

(deftest handler-row-renders-medium-tier-dot
  (testing "handler :duration-ms 30ms classifies as :medium — yellow dot"
    (seed-buffer! (cascade-evs-with-duration 301 [:counter/inc] 310 30))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 301])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-tier-dot-medium"))
            "medium-tier dot present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-fast")))))))

(deftest handler-row-renders-slow-tier-dot
  (testing "handler :duration-ms 75ms classifies as :slow — orange triangle"
    (seed-buffer! (cascade-evs-with-duration 302 [:counter/inc] 320 75))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 302])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-tier-dot-slow"))
            "slow-tier dot present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-fast")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-medium")))))))

(deftest handler-row-renders-blocking-tier-dot
  (testing "handler :duration-ms 250ms classifies as :blocking — red triangle"
    (seed-buffer! (cascade-evs-with-duration 303 [:counter/inc] 330 250))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 303])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-tier-dot-blocking"))
            "blocking-tier dot present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-slow")))))))

(deftest handler-row-omits-tier-dot-when-duration-ms-absent
  (testing "without :duration-ms on the :run-end emit, no tier-dot
            renders — the audit only wires the dot to nodes that
            carry a measurable duration"
    ;; The baseline `cascade-evs` builder leaves :duration-ms unset.
    (seed-buffer! (cascade-evs 304 [:counter/inc] 340))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 304])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-fast"))
            "no tier-dot when :duration-ms is absent")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-medium")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-slow")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-blocking")))))))

(deftest handler-row-omits-tier-dot-when-duration-ms-is-non-numeric
  (testing "a malformed :duration-ms tag (e.g. nil or a string) does
            NOT render a tier-dot — the dot is gated on (number?
            duration-ms) so the panel never surfaces a misleading
            green dot for un-measurable nodes"
    (seed-buffer! (cascade-evs-with-duration 305 [:counter/inc] 350 "not-a-number"))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 305])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-fast")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-medium")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-slow")))
        (is (nil? (find-by-testid tree "rf-causa-event-detail-tier-dot-blocking")))))))

;; ---- (7) EFFECTS row — non-lying empty state (rf2-s0s5x Phase A) -------
;;
;; Per the bead's Bug 1: the EFFECTS row previously showed `(none)` on
;; cascades whose handler returned only `:db` — `:event/db-changed`
;; lives in the projection's `:other` bucket (no `:op-type :fx` is
;; emitted for the framework-driven `:db` commit), so the row's
;; `(empty? effects)` branch fired even though the user clearly saw
;; the state change. The fix folds `:event/db-changed` in as a virtual
;; `:db` entry alongside the standard fx-handled traces; the row now
;; shows `(none)` only when neither signal is present (true silence).

(defn- cascade-evs-db-only
  "Pure-db cascade — like `cascade-evs` but instead of the two
  `:rf.fx/handled` rows it emits one `:event/db-changed` so the
  projection bucketises `:effects` as empty AND surfaces the db
  commit in `:other`. Mirrors what `reg-event-db` produces in
  production (the shop testbed's `:cart/add-item` is the canonical
  repro)."
  [dispatch-id event-vec id-base]
  [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
    :tags {:dispatch-id dispatch-id :event event-vec}}
   {:id (+ id-base 2) :op-type :event :operation :event
    :tags {:dispatch-id dispatch-id :phase :run-end}}
   {:id (+ id-base 3) :op-type :event :operation :event/db-changed
    :tags {:dispatch-id dispatch-id :event-id (first event-vec)}}
   {:id (+ id-base 4) :op-type :event :operation :event/do-fx
    :tags {:dispatch-id dispatch-id}}])

(deftest effects-row-renders-db-when-db-changed-emitted
  (testing "rf2-s0s5x Phase A — a reg-event-db cascade emits
            `:event/db-changed` but no `:rf.fx/handled`; the EFFECTS
            row surfaces a virtual `:db` entry so the panel doesn't
            lie with `(none)` when the cascade committed a `:db`
            effect"
    (seed-buffer! (cascade-evs-db-only 400 [:counter/inc] 400))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 400])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects"))
            "EFFECTS row renders the entries list, not the (none) span")
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-row-db"))
            "db row carries a stable testid")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-effects-none"))
            "no (none) caption when an effect was committed")))))

(deftest effects-row-renders-fx-entries-when-handled
  (testing "an fx-bearing cascade emits one `:rf.fx/handled` per fx
            handler invocation; the EFFECTS row lists each by fx-id +
            operation. Same fixture as `cascade-detail-renders-six-
            dominoes` — two :rf.fx/handled entries (`:fx-id :db` and
            `:fx-id :dispatch`)."
    (seed-buffer! (cascade-evs 401 [:counter/inc] 400))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 401])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects")))
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-row-db"))
            "fx-id :db row present (from the cascade-evs fixture)")
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-row-dispatch"))
            "fx-id :dispatch row present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-effects-none")))))))

(deftest effects-row-shows-none-when-truly-empty
  (testing "a cascade with no fx-handled traces AND no db-changed
            trace renders `(none)` — the empty-state is only a lie
            when there's actual cascade work; the silent path stays
            silent."
    (seed-buffer! [{:id 1 :op-type :event :operation :event/dispatched
                    :tags {:dispatch-id 500 :event [:noop]}}
                   {:id 2 :op-type :event :operation :event
                    :tags {:dispatch-id 500 :phase :run-end}}
                   {:id 3 :op-type :event :operation :event/do-fx
                    :tags {:dispatch-id 500}}])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 500])
      (let [tree (event-detail/Panel)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-effects-none"))
            "(none) caption renders for a truly empty cascade")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-effects")))))))

(deftest effects-entries-orders-db-first
  (testing "the entries fn surfaces db before fx-vector entries —
            matches the runtime ordering (db commit precedes the fx
            walk per Spec 002 §Drain-loop pseudocode)"
    (let [cascade {:effects [{:id 5 :op-type :fx :operation :rf.fx/handled
                              :tags {:fx-id :dispatch}}]
                   :other   [{:id 3 :op-type :event :operation :event/db-changed
                              :tags {}}]}
          entries (event-detail/effects-entries cascade)]
      (is (= [:db :dispatch] (mapv :fx-id entries))
          "db row first (cascade order), then fx vector entries"))))
