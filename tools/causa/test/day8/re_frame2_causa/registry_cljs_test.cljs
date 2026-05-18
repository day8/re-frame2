(ns day8.re-frame2-causa.registry-cljs-test
  "Dedicated CLJS tests for `day8.re-frame2-causa.registry` (rf2-5zl7l).

  ## Scope

  `registry.cljs` is now a thin orchestrator that owns only the
  cross-panel primitives (trace-buffer sub, panel-selection slot,
  shared cascades projection, suppression-counter handlers) plus the
  per-panel `install!` fan-out (rf2-d4xda). Per-panel reg-subs /
  reg-events / reg-fxs live colocated with the panel ns that reads
  them. All registrations sit under the `:rf.causa/*` namespace and
  target the `:rf/causa` frame. Prior to this file the only coverage
  was *transitive* through per-panel view tests (each panel test
  calls `(registry/reset-for-test!)` then drives the panel-specific
  subset via subscribe/dispatch).

  Per the bead description (rf2-5zl7l) and the test-coverage audit
  (rf2-otcbz) the transitive route does NOT isolate:

    - The 3 `reg-fx` handlers as standalone units (the time-travel
      panel test stubs two of them — it doesn't drive the registered
      delegations themselves).
    - The full smoke surface: that every registered name resolves to
      a handler after `register-causa-handlers!` runs.
    - Cross-panel composite subs (per-panel tests don't exercise
      `:rf.causa/cascades`-driven projections end-to-end).

  ## Trade-off: smoke + high-value, not 1-per-registration

  The full registered surface is too many to exhaustively unit-test
  without duplicating the per-panel suite. The strategy below is:

    (1) **Smoke registration block** — one assertion per registered
        name that the registrar resolves it (proves the orchestrator
        `register-causa-handlers!` plus each panel `install!` reached
        every form without an early throw — the failure mode the
        audit named).
    (2) **High-value sub contracts** — defaults, composite shapes,
        override-aware readers (sub-cache, registered-flows, etc.),
        the panel-suppression / dormant-frame signal slots, and the
        REDACTED indicator (`:rf.causa/suppressed-sensitive-count`).
    (3) **High-value event contracts** — panel-select, hydration
        toggle, suppress-toggle, time-travel-scrub, filter axes
        (toggle / clear / set).
    (4) **Reg-fx contracts** — the three fxs each receive their args
        in the v2 `(fn [ctx args] ...)` shape. We capture the call
        site via reg-fx replacement (same pattern as time_travel_
        cljs_test.cljs) and assert the args round-trip.
    (5) **Edge cases** — empty app-db, override-takes-precedence,
        clear-all-filter events, set-since-seconds normalisation.

  Aim: ~30-50 deftests. The panel tests cover most paths transitively;
  this file's job is the smoke surface + the registered-fx isolation
  + the cross-panel slots no single panel test owns."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  ;; rf2-5m5n2 — reset the project-root prefix atom so a sibling test
  ;; that set it (e.g. `open_in_editor_cljs_test.cljs`) doesn't leak
  ;; into the registry tests' URI assertions.
  (config/set-project-root! nil))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- helpers ------------------------------------------------------------

(defn- setup-causa-frame!
  "The canonical per-test boot: register handlers, allocate the
  :rf/causa frame, return."
  []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- causa-event-id? [id]
  (and (keyword? id)
       (#{"rf.causa" "rf.causa.issues"} (namespace id))))

(defn- catalogued-causa-event-id? [id]
  (causa-event-id? id))

(def ^:private all-sub-names
  "Every :rf.causa/* sub registered by `register-causa-handlers!`. Sorted
  for stable iteration in the smoke block."
  [:rf.causa/active-filters
   ;; rf2-7hwwe — Machine Inspector `:after` countdown rings.
   :rf.causa/active-timers-for-focused-machine
   :rf.causa/app-db-diff
   :rf.causa/cascades
   ;; rf2-59e7k — Cancellation-cascade visualiser subs (Machines
   ;; tab side-panel + Trace popover). Per
   ;; `tools/causa/spec/019-Cross-Cutting-Insight.md` §M.3.
   :rf.causa/cancellation-cascade-expanded?
   :rf.causa/cancellation-cascade-for-focused-event
   :rf.causa/cancellation-cascade-for-focused-machine
   :rf.causa/cancellation-cascade-popover-focus
   :rf.causa/cancellation-cascade-popover-open?
   ;; :rf.causa/causality-graph-data removed with rf2-dqnuu — the
   ;; Causality panel was replaced by the c-key popover; the popover's
   ;; payload sub `:rf.causa/causality-popover-payload` stands in for
   ;; the panel's composite sub.
   :rf.causa/causality-popover-open?
   :rf.causa/causality-popover-layout
   :rf.causa/causality-popover-payload
   ;; rf2-i39w2 Phase 3 — hiccup-diff micro-engine opt-in toggle.
   :rf.causa/diff-opts
   :rf.causa/edit-popup-draft
   :rf.causa/edit-popup-open?
   :rf.causa/edit-popup-trigger
   :rf.causa/epoch-history
   :rf.causa/event-detail
   :rf.causa/filtered-cascades
   :rf.causa/focus
   ;; rf2-a1z3b — focus-navigation primitive slot sub.
   :rf.causa/focus-set
   :rf.causa/focus-slot
   :rf.causa/focused-slice-path
   :rf.causa/issues-filters
   :rf.causa/issues-ribbon
   :rf.causa/forced-machine-mode
   ;; rf2-nqw0v Phase 5 — Machine Inspector per-instance arc + scrubber.
   :rf.causa/machine-arc-data
   :rf.causa/machine-arc-highlight-state
   :rf.causa/machine-arc-hover
   :rf.causa/machine-arc-trimmed
   :rf.causa/machine-definitions
   :rf.causa/machine-definitions-override
   :rf.causa/machine-inspector-data
   :rf.causa/machine-instances
   :rf.causa/machine-instances-override
   :rf.causa/machine-scrubber-position
   :rf.causa/machine-snapshots
   :rf.causa/machine-snapshots-override
   ;; rf2-uyp86 — managed-fx wire-boundary diff composite.
   :rf.causa/managed-fx-for-focused-event
   :rf.causa/mode-c-cluster-by
   :rf.causa/mode-c-clusters
   :rf.causa/mode-c-compare-table
   :rf.causa/mode-c-context-key
   :rf.causa/mode-c-expanded
   :rf.causa/mode-c-selection
   ;; rf2-7hwwe — `:after` ring tick driver wall-clock surface + hover slot.
   :rf.causa/now-ms
   :rf.causa/pinned-slices
   :rf.causa/pinned-slices-store
   :rf.causa/palette-active-item
   :rf.causa/palette-cursor
   :rf.causa/palette-index
   :rf.causa/palette-open?
   :rf.causa/palette-query
   :rf.causa/palette-results
   :rf.causa/registered-machines
   ;; rf2-nrbs9 — Routing tab (7th L3 tab) sub family.
   :rf.causa/registered-routes
   :rf.causa/registered-routes-override
   :rf.causa/current-route-slice
   :rf.causa/current-route-slice-override
   :rf.causa/routing-tab-data
   :rf.causa/selected-dispatch-frame
   :rf.causa/selected-dispatch-id
   :rf.causa/selected-epoch-annotated-tree
   :rf.causa/selected-epoch-diff
   :rf.causa/selected-epoch-id
   :rf.causa/selected-epoch-record
   :rf.causa/selected-epoch-sections
   :rf.causa/selected-machine-id
   ;; rf2-om6fa — Story-aware modal positioning opt.
   :rf.causa/modal-positioning
   ;; rf2-x8h9y — horizontal resize handle width.
   :rf.causa/panel-width-px
   :rf.causa/selected-tab
   ;; rf2-9poxq — Settings popup subs.
   :rf.causa/setting
   :rf.causa/settings
   :rf.causa/settings-active-tab
   :rf.causa/settings-open?
   :rf.causa/show-me-when-this-changed-result
   ;; rf2-v869p Phase 2 — UC1 Sim sub-mode subs.
   :rf.causa/sim-active?
   :rf.causa/sim-available-transitions
   :rf.causa/sim-by-machine
   :rf.causa/sim-event-suggestions
   :rf.causa/sim-state
   ;; rf2-nqw0v Phase 5 — Share affordance subs.
   :rf.causa/share-copy-status
   :rf.causa/share-modal-open?
   :rf.causa/share-state
   :rf.causa/share-url
   :rf.causa/suppressed-sensitive-count
   :rf.causa/target-frame
   :rf.causa/target-frame-db
   ;; rf2-7hwwe — `:after` countdown ring hover slot (rich tooltip lifecycle).
   :rf.causa/timer-hover
   :rf.causa/trace-buffer
   :rf.causa/trace-feed
   :rf.causa/trace-filters
   ;; Views panel (rf2-21ob3) replaces the legacy Subscriptions panel
   ;; — subs nest under the views that consumed them. See
   ;; `tools/causa/spec/012-Views.md`.
   :rf.causa/views-cluster-threshold
   :rf.causa/views-component-filter
   :rf.causa/views-data
   :rf.causa/views-expanded-clusters
   :rf.causa/views-expanded-rows
   :rf.causa/views-focused-cascade-pair
   :rf.causa/views-group-by
   :rf.causa/views-heatmap?
   ;; rf2-xjhhp Phase 2 — sub-output structural-diff composite for the
   ;; Views row drilldown. Reuses the Phase 1 engine
   ;; (`diff.annotated_tree` + `diff.section_grouping`).
   :rf.causa/views-sub-diff-for-focused-event])

(def ^:private all-event-names
  ;; Issues-ribbon panel-internal events (rf2-nmc1f) — nested under
  ;; `:rf.causa.issues/*` so the namespace itself encodes
  ;; "panel-internal, no cross-panel callers". Per the
  ;; `:rf.causa.<panel>/*` convention codified in
  ;; `tools/causa/spec/014-Registry-Catalogue.md` §Naming convention.
  [:rf.causa.issues/clear-filters
   :rf.causa.issues/set-since-seconds
   :rf.causa.issues/toggle-prefix
   :rf.causa.issues/toggle-severity
   :rf.causa/add-filter
   ;; rf2-59e7k — Cancellation-cascade visualiser events. Per
   ;; `tools/causa/spec/019-Cross-Cutting-Insight.md` §M.3.
   :rf.causa/cancellation-cascade-close
   :rf.causa/cancellation-cascade-open
   :rf.causa/cancellation-cascade-set-expanded
   :rf.causa/cancellation-cascade-toggle-expand
   ;; Causality popover events (rf2-dqnuu) — replace the dropped
   ;; Causality tab. See spec/018-Event-Spine.md §10.
   :rf.causa/causality-popover-close
   :rf.causa/causality-popover-layout-pulse
   :rf.causa/causality-popover-open
   :rf.causa/causality-popover-toggle
   :rf.causa/causality-popover-toggle-layout
   :rf.causa/clear-machine-selection
   ;; rf2-a1z3b — focus-navigation primitive (gutter click on L2 row sets a
   ;; focus-set; `[◀][▶]` step within the in-focus subset).
   :rf.causa/clear-focus
   :rf.causa/clear-machine-selection
   :rf.causa/clear-mode-c-selection
   :rf.causa/clear-selected-dispatch-id
   :rf.causa/clear-slice-focus
   :rf.causa/clear-trace-buffer
   :rf.causa/clear-trace-filters
   :rf.causa/close-edit-popup
   :rf.causa/close-shell
   :rf.causa/copy-path-to-clipboard
   :rf.causa/copy-share-url-to-clipboard
   :rf.causa/copy-value-to-clipboard
   :rf.causa/delete-edit-popup
   :rf.causa/edit-popup-set-mode
   :rf.causa/edit-popup-set-pattern
   :rf.causa/edit-popup-toggle-scope
   :rf.causa/epoch-recorded
   :rf.causa/focus-cascade
   :rf.causa/focus-cascade-next
   :rf.causa/focus-cascade-prev
   ;; rf2-uyp86 — managed-fx wire-boundary diff cross-link event
   ;; (HANDLER DISPATCHED row dispatches this to pivot the spine).
   :rf.causa/focus-event
   :rf.causa/focus-slice-path
   ;; rf2-59e7k — Cancellation-cascade row-click jump (delegates into
   ;; :rf.causa/select-dispatch-id via the spine shim).
   :rf.causa/focus-trace-entry
   :rf.causa/follow-head
   :rf.causa/hide-event-type
   :rf.causa/hydrate-filters
   ;; Phase 4 (rf2-m7co9) — ELK chart layout pulse.
   :rf.causa/machine-chart-layout-pulse
   :rf.causa/machine-state-clicked
   :rf.causa/note-sensitive-suppressed
   :rf.causa/note-trace-event
   :rf.causa/open-edit-popup
   :rf.causa/open-in-editor
   :rf.causa/open-settings
   :rf.causa/open-share-url-in-new-tab
   :rf.causa/palette-close
   :rf.causa/palette-cursor-down
   :rf.causa/palette-cursor-set
   :rf.causa/palette-cursor-up
   :rf.causa/palette-invoke
   :rf.causa/palette-open
   :rf.causa/palette-set-query
   :rf.causa/palette-toggle
   :rf.causa/pin-slice
   :rf.causa/preview-cascade
   :rf.causa/remove-filter
   :rf.causa/reorder-pinned-slices
   ;; rf2-x8h9y — resize-handle double-click reset.
   :rf.causa/reset-panel-width
   :rf.causa/reset-suppressed-counters
   :rf.causa/restore-from-share-url
   :rf.causa/save-edit-popup
   :rf.causa/select-dispatch-id
   :rf.causa/select-epoch
   :rf.causa/select-machine-id
   :rf.causa/select-tab
   ;; rf2-a1z3b — focus-navigation primitive write event.
   :rf.causa/set-focus
   :rf.causa/set-forced-machine-mode
   :rf.causa/set-frame
   :rf.causa/set-machine-definitions-override-for-test
   :rf.causa/set-machine-instances-override-for-test
   :rf.causa/set-machine-snapshots-override-for-test
   ;; rf2-nrbs9 — Routing tab test-only override events.
   :rf.causa/set-current-route-slice-override-for-test
   :rf.causa/set-registered-routes-override-for-test
   :rf.causa/set-mode-c-cluster-by
   :rf.causa/set-mode-c-context-key
   ;; rf2-om6fa — Story-aware modal positioning opt.
   :rf.causa/set-modal-positioning
   ;; rf2-x8h9y — resize-handle live update event.
   :rf.causa/set-panel-width-px
   ;; rf2-7hwwe — `:after` countdown rings now-ms override (test-only).
   :rf.causa/set-now-ms-override-for-test
   :rf.causa/set-registered-machines-override-for-test
   :rf.causa/set-arc-hover
   ;; rf2-a9cke — focused-event lens test overrides (Machine Inspector).
   :rf.causa/set-epoch-history-for-test
   :rf.causa/set-focus-epoch-id-for-test
   :rf.causa/set-scrubber-position
   :rf.causa/set-target-frame
   :rf.causa/set-trace-filter
   ;; rf2-9poxq — Settings popup events.
   :rf.causa/settings-close
   :rf.causa/settings-open
   :rf.causa/settings-select-tab
   :rf.causa/settings-toggle
   :rf.causa/settings-update
   ;; rf2-nqw0v Phase 5 — Share affordance events.
   :rf.causa/share-copy-status
   :rf.causa/share-modal-close
   :rf.causa/share-modal-open
   ;; rf2-v869p Phase 2 — UC1 Sim sub-mode events.
   :rf.causa/sim-reset
   :rf.causa/sim-set-pending-data
   :rf.causa/sim-set-pending-event
   :rf.causa/sim-start
   :rf.causa/sim-step
   :rf.causa/sim-stop
   :rf.causa/sync-epoch-history
   :rf.causa/sync-trace-buffer
   ;; rf2-7hwwe — `:after` countdown rings event family. timer-tick
   ;; is the rAF pulse; timer-hover writes the per-ring hovered slot
   ;; (v1 surfaces the tooltip via native SVG <title>; the slot is
   ;; plumbed for a follow-on rich-tooltip).
   :rf.causa/timer-hover
   :rf.causa/timer-tick
   :rf.causa/toggle-live-pause
   :rf.causa/toggle-mode-c-cluster-expanded
   :rf.causa/toggle-mode-c-selection
   :rf.causa/unpin-slice
   ;; Views panel events (rf2-21ob3) — replaces the legacy Subscriptions
   ;; panel events. See `tools/causa/spec/012-Views.md`.
   :rf.causa/views-collapse-all-rows
   :rf.causa/views-segment-click
   :rf.causa/views-set-cluster-threshold
   :rf.causa/views-set-component-filter
   :rf.causa/views-set-group-by
   :rf.causa/views-set-heatmap?
   :rf.causa/views-toggle-cluster
   :rf.causa/views-toggle-heatmap
   :rf.causa/views-toggle-row])

(def ^:private all-fx-names
  [:rf.causa.fx/copy-to-clipboard
   ;; rf2-nqw0v Phase 5 — Share affordance: new-tab open fx.
   :rf.causa.fx/open-in-new-tab
   ;; rf2-ak4ms — auto-filter persistence side-effect. Lives under the
   ;; filter-specific prefix because the localStorage write is bound
   ;; to the filter-mutating events (add-filter / remove-filter /
   ;; save-edit-popup / delete-edit-popup) — every mutation round-trips
   ;; to localStorage in one place.
   :rf.causa.filters/persist
   ;; rf2-wm7z4 — palette pop-out side-effect. Lives under the
   ;; palette-specific prefix because it wraps a mount-layer pop-out
   ;; call that no other Causa surface invokes.
   :rf.causa.palette.fx/popout
   ;; rf2-g5q8d — cross-panel open-in-editor side-effect. Lives under
   ;; the editor-generic `:rf.editor/*` prefix rather than `:rf.causa.fx/*`
   ;; because the rf2-cm93v allowlist seam is editor-related, not
   ;; Causa-specific.
   :rf.editor/open])

;; ---- (1) smoke: every registered name resolves -------------------------

(deftest registry-installs-every-sub
  (testing "register-causa-handlers! resolves every :rf.causa/* sub"
    (registry/register-causa-handlers!)
    (doseq [sub-id all-sub-names]
      (is (some? (registrar/handler :sub sub-id))
          (str "expected :sub handler for " sub-id)))))

(deftest registry-installs-every-event
  (testing "register-causa-handlers! resolves every :rf.causa/* event"
    (registry/register-causa-handlers!)
    (doseq [event-id all-event-names]
      (is (some? (registrar/handler :event event-id))
          (str "expected :event handler for " event-id)))))

(deftest registry-installs-every-catalogued-event-and-no-dead-core-events
  (testing "register-causa-handlers! installs the catalogued Causa events"
    (registry/register-causa-handlers!)
    (let [actual (->> (registrar/registrations :event)
                      keys
                      (filter catalogued-causa-event-id?)
                      set)]
      (is (= (set all-event-names) actual)))))

(deftest registry-registers-each-causa-event-once
  (testing "each Causa event id is registered exactly once during install"
    (let [registered        (atom [])
          original-register! registrar/register!]
      (with-redefs [registrar/register!
                    (fn [kind id metadata]
                      (when (and (= :event kind) (causa-event-id? id))
                        (swap! registered conj id))
                      (original-register! kind id metadata))]
        (registry/reset-for-test!)
        (registry/register-causa-handlers!))
      (let [freqs      (frequencies @registered)
            duplicates (into {}
                             (filter (fn [[_id n]] (> n 1)))
                             freqs)]
        (is (= (set all-event-names) (set @registered)))
        (is (= {} duplicates)
            (str "duplicate event registrations: " duplicates))))))

(deftest registry-installs-every-fx
  (testing "register-causa-handlers! resolves every :rf.causa.fx/* fx"
    (registry/register-causa-handlers!)
    (doseq [fx-id all-fx-names]
      (is (some? (registrar/handler :fx fx-id))
          (str "expected :fx handler for " fx-id)))))

(deftest registry-counts-match-bead
  (testing "registry holds the expected snapshot of subs + events + fxs"
    ;; Counts are snapshot-pinned to detect accidental registration
    ;; drift; bump them deliberately whenever a registered :rf.causa/*
    ;; sub/event/fx is added or removed. The per-feature breakdown
    ;; that lived here through rf2-qy0nu (the 8-dead-panel sweep) was
    ;; deleted with the panels themselves — every line referenced a
    ;; surface that no longer exists.
    (is (= 101 (count all-sub-names)))
    (is (= 120 (count all-event-names)))
    (is (= 5   (count all-fx-names)))))

(deftest registry-is-idempotent
  (testing "calling register-causa-handlers! twice is a no-op (same handler instance)"
    (registry/register-causa-handlers!)
    (let [h1 (registrar/handler :sub :rf.causa/target-frame)
          e1 (registrar/handler :event :rf.causa/select-tab)
          f1 (registrar/handler :fx :rf.causa.fx/copy-to-clipboard)]
      (registry/register-causa-handlers!)
      (is (identical? h1 (registrar/handler :sub :rf.causa/target-frame)))
      (is (identical? e1 (registrar/handler :event :rf.causa/select-tab)))
      (is (identical? f1 (registrar/handler :fx :rf.causa.fx/copy-to-clipboard))))))

(deftest registers-every-canonical-rf-causa-sub
  ;; Holistic subscribe-side smoke. The handler-resolution smokes above
  ;; assert that the registrar holds a handler for each id; this test
  ;; takes the subscriber's view — `(rf/subscribe [q-v])` returns a
  ;; non-nil reaction for every canonical sub-id once the registrar has
  ;; run. The two smokes catch different drift: handler-resolution
  ;; catches a missing registration; subscribe-resolution catches a sub
  ;; whose subscribe path throws (e.g. a downstream `install!` that
  ;; depends on a not-yet-registered upstream).
  (testing "every :rf.causa/* sub-id resolves through rf/subscribe after
            register-causa-handlers! runs (panel migrations should not
            silently drop a registration from the orchestrator)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (doseq [q-v all-sub-names]
        (is (some? (rf/subscribe [q-v]))
            (str q-v " must resolve through rf/subscribe after
                 register-causa-handlers!"))))))

;; ---- (2) high-value sub contracts: defaults on a fresh frame ------------

(deftest sub-trace-buffer-thunks-trace-bus
  (testing ":rf.causa/trace-buffer falls through to `trace-bus/buffer`
            (the process-global ring atom) when Causa's app-db slot is
            empty — the pre-mount fallback path that lets headless tests
            drive the collector without first dispatching the seed.
            Tests push BEFORE the first subscribe; the first deref of a
            fresh Reaction reads through to the atom and sees the
            current contents. Per rf2-in6l2 — see `trace_bus.cljc`
            §Reactivity for the dual atom + app-db slot, and
            `registry.cljs` for the sub's `(or (get db :trace-buffer)
            (trace-bus/buffer))` fall-through."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push first, then subscribe — the Reaction's first read sees
      ;; the current atom contents.
      (trace-bus/collect-trace!
        {:id 1 :op-type :event :operation :rf.test/x :tags {}})
      (trace-bus/collect-trace!
        {:id 2 :op-type :event :operation :rf.test/y :tags {}})
      (let [buf @(rf/subscribe [:rf.causa/trace-buffer])]
        (is (= 2 (count buf))
            "the two pushes are visible on the first subscribe")
        (is (= [1 2] (mapv :id buf))
            "events are oldest-first, matching trace-bus/push algebra")))))

(deftest sub-trace-buffer-fresh-frame-sees-empty
  (testing "a fresh :rf/causa frame with an empty `trace-bus/buffer-state`
            yields an empty :rf.causa/trace-buffer sub. Clearing the
            atom before the first subscribe is the canonical reset
            shape per rf2-e9s81 (the previous app-db-mirror reset
            shape via `:rf.causa/clear-trace-buffer` is removed)."
    (setup-causa-frame!)
    (trace-bus/clear-buffer!)
    (rf/with-frame :rf/causa
      (is (= [] @(rf/subscribe [:rf.causa/trace-buffer]))
          "empty atom → sub returns []"))))

(deftest sub-trace-buffer-immediate-reactive-update-via-mirror
  (testing "Per rf2-in6l2 — once the `:rf/causa` frame is registered,
            `collect-trace!` mirrors each push into Causa's app-db slot
            `:trace-buffer` via `:rf.causa/note-trace-event` so the
            layer-1 sub fires on the standard app-db-write reactive
            path. Drive the mirror event synchronously via dispatch-
            sync (the production path uses dispatch — async drain —
            but the same handler runs); assert that the sub reads
            from app-db rather than the atom fall-through once the
            slot is populated."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed the app-db slot via the production event handler. With
      ;; the slot populated the sub MUST read from app-db (not the atom
      ;; fall-through) — that's the reactive surface panels depend on.
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 1 :op-type :event :operation :rf.test/a :tags {}}])
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 2 :op-type :event :operation :rf.test/b :tags {}}])
      (let [buf @(rf/subscribe [:rf.causa/trace-buffer])]
        (is (= 2 (count buf))
            "two mirrored pushes are visible immediately on subscribe")
        (is (= [1 2] (mapv :id buf))
            "events are oldest-first, matching trace-bus/push algebra"))
      ;; Bump once more — the reaction re-fires on the same dispatch
      ;; path the production trace-collector takes. No
      ;; clear-sub-cache! workaround needed.
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 3 :op-type :event :operation :rf.test/c :tags {}}])
      (let [buf @(rf/subscribe [:rf.causa/trace-buffer])]
        (is (= 3 (count buf))
            "subsequent mirror dispatch re-fires the sub — immediate update")
        (is (= [1 2 3] (mapv :id buf)))))))

(deftest sub-trace-buffer-clear-event-drops-mirror-slot
  (testing "Per rf2-in6l2 — `:rf.causa/clear-trace-buffer` (dispatched
            from `trace-bus/clear-buffer!` in CLJS) drops the mirrored
            slot in lockstep with the atom reset. After clear the
            sub falls back through the atom path again."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 1 :op-type :event :operation :rf.test/x :tags {}}])
      (is (= 1 (count @(rf/subscribe [:rf.causa/trace-buffer]))))
      (rf/dispatch-sync [:rf.causa/clear-trace-buffer])
      ;; After clear the slot is dissoc'd; the sub falls back to the
      ;; atom (also empty since the trace-bus atom was cleared at the
      ;; start of the test via the fixture's causa-init!).
      (is (= [] @(rf/subscribe [:rf.causa/trace-buffer]))
          "clear-trace-buffer drops the slot and the atom is empty too"))))

(deftest sub-trace-buffer-sync-event-overwrites-slot
  (testing "Per rf2-in6l2 — `:rf.causa/sync-trace-buffer` overwrites the
            slot wholesale, used by `mount.cljs/open!` to seed the slot
            with the atom's pre-mount contents and by `set-buffer-depth!`
            to reflect post-shrink atom state."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [seed [{:id 100 :op-type :event :operation :rf.test/seeded :tags {}}
                  {:id 101 :op-type :event :operation :rf.test/seeded :tags {}}]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer seed])
        (is (= seed @(rf/subscribe [:rf.causa/trace-buffer]))
            "seed lands wholesale in the slot")
        ;; Overwrite with a smaller seed — wholesale replace, not merge.
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer [{:id 200 :tags {}}]])
        (is (= [{:id 200 :tags {}}] @(rf/subscribe [:rf.causa/trace-buffer]))
            "second sync wholly replaces the slot (no merge)")))))

(deftest sub-trace-buffer-note-event-dedupes-on-id
  (testing "rf2-z4fza follow-up: `:rf.causa/note-trace-event` skips the
            push when the incoming event's `:id` already exists in the
            slot. The mount seed-race window — where the `:frame/created`
            trace for `:rf/causa` lands in both the atom snapshot the
            seed reads AND a queued mirror dispatch — would otherwise
            land the same event id twice, producing a duplicate React
            `t:<id>` key in the Trace panel and one extra `<li>` past
            the 200-row budget that survives reconciliation."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Simulate the seed-race directly: seed lands the event id 42 in
      ;; the slot wholesale, then a `:rf.causa/note-trace-event` for the
      ;; same id arrives from a queued mirror dispatch.
      (rf/dispatch-sync [:rf.causa/sync-trace-buffer
                         [{:id 42 :op-type :frame :operation :frame/created
                           :tags {:frame :rf/causa}}]])
      (is (= 1 (count @(rf/subscribe [:rf.causa/trace-buffer]))))
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 42 :op-type :frame :operation :frame/created
                          :tags {:frame :rf/causa}}])
      (is (= 1 (count @(rf/subscribe [:rf.causa/trace-buffer])))
          "duplicate-id push is a no-op — slot length unchanged")
      (is (= [42] (mapv :id @(rf/subscribe [:rf.causa/trace-buffer])))
          "only the seeded entry remains; the duplicate mirror push was
           skipped")
      ;; Distinct ids still push normally — dedup is per-id, not blanket
      ;; deduplication-by-anything.
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:id 43 :op-type :event :operation :rf.test/y
                          :tags {}}])
      (is (= [42 43] (mapv :id @(rf/subscribe [:rf.causa/trace-buffer])))
          "fresh ids still append — dedup is :id-keyed, not push-blocking"))))

(deftest sub-trace-buffer-note-event-allows-events-without-id
  (testing "rf2-z4fza follow-up: the dedup gate predicates on `:id`
            being present (the framework's `next-event-id` stamp). An
            event without `:id` is still pushed — defensive against
            synthetic/test events that omit the slot."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:op-type :event :operation :rf.test/no-id
                          :tags {}}])
      (rf/dispatch-sync [:rf.causa/note-trace-event
                         {:op-type :event :operation :rf.test/also-no-id
                          :tags {}}])
      (is (= 2 (count @(rf/subscribe [:rf.causa/trace-buffer])))
          "events without :id are not deduped — both push lands"))))

(deftest sub-trace-buffer-evicts-on-overflow
  (testing "trace-bus enforces the eviction-on-overflow algebra against
            `current-depth`. We shrink the depth to 3 then push 5
            events BEFORE the first subscribe; the sub returns the 3
            newest in oldest-first order."
    (setup-causa-frame!)
    (trace-bus/set-buffer-depth! 3)
    (try
      (rf/with-frame :rf/causa
        (dotimes [i 5]
          (trace-bus/collect-trace!
            {:id i :op-type :event :operation :rf.test/x :tags {}}))
        (let [buf @(rf/subscribe [:rf.causa/trace-buffer])]
          (is (= 3 (count buf))
              "depth=3 caps the sub-visible buffer at 3 entries")
          (is (= [2 3 4] (mapv :id buf))
              "oldest entries evicted; newest retained in oldest-first order")))
      (finally
        (trace-bus/set-buffer-depth! 1000)))))

;; ---- :rf.causa/cascades — causa-internal filter (rf2-g1pt8) ------------

(defn- mk-cascade
  "Build a minimal cascade record for the data-layer filter test —
  matches the shape `re-frame.trace.projection/group-cascades`
  returns (`:dispatch-id` + `:event` vector)."
  [dispatch-id event-vec]
  {:dispatch-id dispatch-id
   :event       event-vec
   :other       []})

(defn- seed-buffer-with-dispatched-events!
  "Push synthetic `:event/dispatched` trace events into Causa's trace
  buffer so `group-cascades` produces one cascade per event. Each
  trace event needs `:operation :event/dispatched`, `:op-type :event`,
  a unique `:dispatch-id` (cascade-grouping key), and the event vector
  under `:tags :event`."
  [events]
  (doseq [{:keys [dispatch-id event-vec]} events]
    (trace-bus/collect-trace!
      {:operation   :event/dispatched
       :op-type     :event
       :id          dispatch-id
       :time        (* dispatch-id 1000)
       :tags        {:dispatch-id dispatch-id
                     :event       event-vec
                     :event-id    (first event-vec)
                     :frame       :rf/default}})))

(deftest sub-cascades-filters-causa-internal-events
  (testing "per rf2-g1pt8 — `:rf.causa/cascades` hard-filters cascades
            whose event-id is in the `rf.causa` namespace at the
            data-layer so every downstream consumer (filtered-cascades,
            L2 event list, spine, popovers, all tabs) inherits the
            filter automatically. Synthetic dispatched-event mix: 2
            user-app + 3 Causa-internal → sub returns only the 2
            user-app cascades."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item {:item-id "apple"}]}
         {:dispatch-id 2 :event-vec [:rf.causa/focus-cascade 99]}
         {:dispatch-id 3 :event-vec [:checkout/start]}
         {:dispatch-id 4 :event-vec [:rf.causa/select-tab :event]}
         {:dispatch-id 5 :event-vec [:rf.causa/open-settings]}])
      (let [cascades @(rf/subscribe [:rf.causa/cascades])
            event-ids (mapv #(first (:event %)) cascades)]
        (is (= 2 (count cascades))
            "the 3 :rf.causa/* cascades are filtered; the 2 user-app cascades survive")
        (is (= [:cart/add-item :checkout/start] event-ids)
            "the surviving cascades carry the user-app event-ids in oldest-first order")
        (is (every? (complement trace-bus/causa-internal-cascade?) cascades)
            "no Causa-internal cascade leaks past the data-layer filter")))))

(deftest sub-cascades-filter-also-applies-to-filtered-cascades
  (testing "per rf2-g1pt8 — because `:rf.causa/filtered-cascades`
            composes against `:rf.causa/cascades`, the data-layer
            filter propagates automatically. Synthetic Causa-internal
            cascades are gone before the auto-filter facade even sees
            them, so the L2 list (which subscribes to filtered-
            cascades) shows only user-app rows."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item]}
         {:dispatch-id 2 :event-vec [:rf.causa/select-tab :machines]}
         {:dispatch-id 3 :event-vec [:checkout/start]}])
      (let [filtered @(rf/subscribe [:rf.causa/filtered-cascades])
            event-ids (mapv #(first (:event %)) filtered)]
        (is (= [:cart/add-item :checkout/start] event-ids)
            "the :rf.causa/select-tab cascade is filtered out at the data-layer")))))

(deftest sub-cascades-pure-user-app-pass-through
  (testing "per rf2-g1pt8 — a buffer with only user-app cascades is
            untouched by the data-layer filter (count + ordering
            preserved). Symmetry guard: the filter is narrow, not a
            blanket rejection."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item {:item-id "apple"}]}
         {:dispatch-id 2 :event-vec [:cart/remove-item {:item-id "apple"}]}
         {:dispatch-id 3 :event-vec [:checkout/start]}])
      (let [cascades @(rf/subscribe [:rf.causa/cascades])]
        (is (= 3 (count cascades))
            "user-app-only buffer is untouched by the filter")
        (is (= [:cart/add-item :cart/remove-item :checkout/start]
               (mapv #(first (:event %)) cascades)))))))

(deftest sub-suppressed-sensitive-count-reads-app-db
  (testing ":rf.causa/suppressed-sensitive-count reads from Causa's
            app-db at `:suppressed-counters` (rf2-0vxdn) — first deref
            returns 0; each `:rf.causa/note-sensitive-suppressed`
            dispatch re-fires the sub on the standard write path
            (immediate reactive update, no clear-sub-cache!
            workaround required)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= 0 @(rf/subscribe [:rf.causa/suppressed-sensitive-count]))
          "empty :suppressed-counters slot → total of 0")
      (rf/dispatch-sync [:rf.causa/note-sensitive-suppressed :rf/default])
      (rf/dispatch-sync [:rf.causa/note-sensitive-suppressed :rf/default])
      (is (= 2 @(rf/subscribe [:rf.causa/suppressed-sensitive-count]))
          "two bumps via dispatch → sub returns 2 immediately")
      (rf/dispatch-sync [:rf.causa/note-sensitive-suppressed :rf/causa])
      (is (= 3 @(rf/subscribe [:rf.causa/suppressed-sensitive-count]))
          "different frame bucket bumps the same total")
      (rf/dispatch-sync [:rf.causa/reset-suppressed-counters])
      (is (= 0 @(rf/subscribe [:rf.causa/suppressed-sensitive-count]))
          "reset event drops every bucket"))))

(deftest sub-target-frame-defaults-to-rf-default
  (testing ":rf.causa/target-frame defaults to :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= registry/default-target-frame
             @(rf/subscribe [:rf.causa/target-frame]))))))

(deftest sub-epoch-history-defaults-empty
  (testing ":rf.causa/epoch-history defaults to []"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= [] @(rf/subscribe [:rf.causa/epoch-history]))))))

(deftest sub-pinned-slices-store-defaults-empty
  (testing ":rf.causa/pinned-slices-store defaults to {}"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= {} @(rf/subscribe [:rf.causa/pinned-slices-store]))))))

(deftest sub-issues-filters-default-disabled
  (testing ":rf.causa/issues-filters has empty axes on a fresh frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [filters @(rf/subscribe [:rf.causa/issues-filters])]
        (is (= #{} (:severities filters)))
        (is (= #{} (:prefixes filters)))
        (is (nil? (:since-ms filters)))))))

(deftest sub-trace-filters-default-empty-map
  (testing ":rf.causa/trace-filters defaults to {}"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= {} @(rf/subscribe [:rf.causa/trace-filters]))))))

(deftest sub-views-heatmap-defaults-false
  (testing ":rf.causa/views-heatmap? defaults to false (rf2-21ob3 —
            Views panel replaces the Subs panel's chain-open? slot)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/views-heatmap?]))))))

(deftest sub-views-group-by-defaults-component
  (testing ":rf.causa/views-group-by defaults to :component (rf2-21ob3
            — Views panel replaces the Subs panel's sub-filters slot)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= :component @(rf/subscribe [:rf.causa/views-group-by]))))))

;; ---- (3) high-value composite sub shapes --------------------------------

(deftest sub-event-detail-shape-on-empty-buffer
  (testing ":rf.causa/event-detail returns the canonical shape on an empty buffer"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/event-detail])]
        (is (contains? data :cascades))
        (is (contains? data :selected-dispatch-id))
        (is (contains? data :selected-cascade))
        (is (= [] (:cascades data)))
        (is (nil? (:selected-dispatch-id data)))
        (is (nil? (:selected-cascade data)))))))

(deftest sub-app-db-diff-shape-on-empty-history
  (testing ":rf.causa/app-db-diff returns history-empty? true with no epochs"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/app-db-diff])]
        (is (true? (:history-empty? data)))
        (is (= :rf/default (:target-frame data)))
        (is (contains? data :changed-non-reserved))
        (is (contains? data :changed-reserved))
        (is (= [] (:pinned-slices data)))
        (is (nil? (:focused-path data)))
        (is (= [] (:focused-hits data)))))))

(deftest sub-causality-popover-payload-shape
  (testing ":rf.causa/causality-popover-payload returns the canonical
            shape (rf2-dqnuu — the popover replaces the panel)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/causality-popover-payload])]
        (is (contains? data :focused))
        (is (contains? data :ancestors))
        (is (contains? data :descendants))
        (is (contains? data :nodes))
        (is (contains? data :edges))
        (is (true? (:empty? data))
            "no focused event on a fresh frame → :empty? true")))))

(deftest sub-causality-popover-open?-defaults-false
  (testing ":rf.causa/causality-popover-open? defaults to false"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/causality-popover-open?]))))))

(deftest sub-causality-popover-layout-defaults-tb
  (testing ":rf.causa/causality-popover-layout defaults to :tb"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= :tb @(rf/subscribe [:rf.causa/causality-popover-layout]))))))


(deftest sub-issues-ribbon-shape-on-empty-buffer
  (testing ":rf.causa/issues-ribbon returns :no-issues empty-kind initially"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (contains? data :issues))
        (is (= 0 (:total data)))
        (is (= 0 (:rendered data)))
        (is (= :no-issues (:empty-kind data)))))))

(deftest sub-trace-feed-shape-on-empty-buffer
  (testing ":rf.causa/trace-feed returns :no-events empty-kind initially"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 0 (:total data)))
        (is (= 0 (:rendered data)))
        (is (false? (:any-filter? data)))))))

(deftest sub-machine-inspector-data-shape-empty
  (testing ":rf.causa/machine-inspector-data returns :no-machines kind when
            the registered-machines override is forced to []"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; The framework's (rf/machines) registrar is process-global, so
      ;; on a node-test target where the machines artefact's own test
      ;; suite registered fixture machines, the live call surfaces a
      ;; non-empty vector. Pin the override to [] so the empty-state
      ;; contract is testable in isolation.
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test []])
      (let [data @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (contains? data :machines))
        (is (= 0 (:total data)))
        (is (= :no-machines (:empty-kind data)))))))

(deftest sub-views-data-shape-empty
  (testing ":rf.causa/views-data returns empty defaults when no cascade
            is focused (rf2-21ob3; replaces the legacy
            :rf.causa/subscriptions-data shape per spec/012-Views.md)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/views-data])]
        (is (contains? data :groups))
        (is (= 0 (:mounted   (:totals data))))
        (is (= 0 (:rendered  (:totals data))))
        (is (= 0 (:unmounted (:totals data))))
        (is (false? (:has-cascade? data)))
        (is (false? (:heatmap? data)))
        (is (= :component (:group-by data)))))))

;; ---- (4) high-value event contracts -------------------------------------

(deftest event-select-dispatch-id-and-clear
  (testing ":rf.causa/select-dispatch-id + clear round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 42])
      (is (= 42 @(rf/subscribe [:rf.causa/selected-dispatch-id])))
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (is (nil? @(rf/subscribe [:rf.causa/selected-dispatch-id]))))))

(deftest event-select-epoch-passive-scrub
  (testing ":rf.causa/select-epoch sets selected-epoch-id (passive scrub)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-epoch :e-7])
      (is (= :e-7 @(rf/subscribe [:rf.causa/selected-epoch-id])))
      ;; nil resets the slot — equivalent to the dropped explicit
      ;; `:rf.causa/clear-selected-epoch` event (deleted with rf2-qy0nu
      ;; alongside the Time Travel panel).
      (rf/dispatch-sync [:rf.causa/select-epoch nil])
      (is (nil? @(rf/subscribe [:rf.causa/selected-epoch-id]))))))

(deftest event-toggle-issues-severity-roundtrip
  (testing ":rf.causa.issues/toggle-severity adds + removes a chip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (= #{:error} (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :warning])
      (is (= #{:error :warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (= #{:warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters])))))))

(deftest event-clear-issues-filters
  (testing ":rf.causa.issues/clear-filters drops all three axes"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.error"])
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 60])
      (rf/dispatch-sync [:rf.causa.issues/clear-filters])
      (let [f @(rf/subscribe [:rf.causa/issues-filters])]
        (is (= #{} (:severities f)))
        (is (= #{} (:prefixes f)))
        (is (nil? (:since-ms f)))))))

(deftest event-set-issues-since-seconds-normalises
  (testing ":rf.causa.issues/set-since-seconds — positive sets ms; nil clears"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 30])
      (is (= 30000 (:since-ms @(rf/subscribe [:rf.causa/issues-filters]))))
      ;; non-positive clears
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 0])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 15])
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds nil])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/issues-filters])))))))

(deftest event-set-trace-filter-axis-and-clear
  (testing ":rf.causa/set-trace-filter sets/clears a single axis"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-trace-filter :op-type :event])
      (is (= {:op-type :event} @(rf/subscribe [:rf.causa/trace-filters])))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :test])
      (is (= {:op-type :event :source :test}
             @(rf/subscribe [:rf.causa/trace-filters])))
      ;; nil value clears that axis only
      (rf/dispatch-sync [:rf.causa/set-trace-filter :op-type nil])
      (is (= {:source :test} @(rf/subscribe [:rf.causa/trace-filters])))
      (rf/dispatch-sync [:rf.causa/clear-trace-filters])
      (is (= {} @(rf/subscribe [:rf.causa/trace-filters]))))))

(deftest event-views-toggle-row-adds-and-removes
  (testing ":rf.causa/views-toggle-row toggles set membership
            (rf2-21ob3 — Views panel inline-row expansion replaces
            the Subs panel filter toggle)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/views-toggle-row "row-a"])
      (is (= #{"row-a"} @(rf/subscribe [:rf.causa/views-expanded-rows])))
      (rf/dispatch-sync [:rf.causa/views-toggle-row "row-b"])
      (is (= #{"row-a" "row-b"} @(rf/subscribe [:rf.causa/views-expanded-rows])))
      (rf/dispatch-sync [:rf.causa/views-toggle-row "row-a"])
      (is (= #{"row-b"} @(rf/subscribe [:rf.causa/views-expanded-rows]))))))

(deftest event-views-toggle-heatmap-round-trip
  (testing ":rf.causa/views-toggle-heatmap flips the boolean; the
            segment-click event sets the filter AND drops heatmap mode
            in one step (per spec/012 §Heatmap segment interaction)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/views-toggle-heatmap])
      (is (true? @(rf/subscribe [:rf.causa/views-heatmap?])))
      (rf/dispatch-sync [:rf.causa/views-segment-click :my/view])
      (is (= :my/view @(rf/subscribe [:rf.causa/views-component-filter])))
      (is (false? @(rf/subscribe [:rf.causa/views-heatmap?])))
      (rf/dispatch-sync [:rf.causa/views-set-component-filter nil])
      (is (nil? @(rf/subscribe [:rf.causa/views-component-filter]))))))

(deftest event-pin-slice-and-unpin-slice
  (testing ":rf.causa/pin-slice + unpin-slice update :pinned-slices-store"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/pin-slice [:users :count]])
      (let [store @(rf/subscribe [:rf.causa/pinned-slices-store])]
        (is (= [[:users :count]] (get store :rf/default))))
      (rf/dispatch-sync [:rf.causa/unpin-slice [:users :count]])
      (let [store @(rf/subscribe [:rf.causa/pinned-slices-store])]
        (is (= [] (get store :rf/default [])))))))

(deftest event-focus-slice-path-and-clear
  (testing ":rf.causa/focus-slice-path + clear-slice-focus round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-slice-path [:a]])
      (is (= [:a] @(rf/subscribe [:rf.causa/focused-slice-path])))
      (rf/dispatch-sync [:rf.causa/clear-slice-focus])
      (is (nil? @(rf/subscribe [:rf.causa/focused-slice-path]))))))

(deftest event-select-machine-id-and-clear
  (testing ":rf.causa/select-machine-id + clear round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :traffic-light])
      (is (= :traffic-light @(rf/subscribe [:rf.causa/selected-machine-id])))
      (rf/dispatch-sync [:rf.causa/clear-machine-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-machine-id]))))))

(deftest event-open-in-editor-routes-through-editor-fx
  (testing "rf2-g5q8d — `:rf.causa/open-in-editor` is now a reg-event-fx
            that resolves the coord through the rf2-cm93v allowlist and
            fires `:rf.editor/open`. It does NOT write to app-db (the
            click is pure navigation; the prior stub's
            `:last-open-in-editor-coord` slot is gone). Detailed
            contract assertions live in `open_in_editor_cljs_test.cljs`;
            here we pin the registry-level shape only."
    (setup-causa-frame!)
    (let [captured (atom [])]
      ;; Replace the open fx with a capture stub (same pattern as the
      ;; time-travel reg-fx tests above).
      (rf/reg-fx :rf.editor/open
        (fn [_ctx args] (swap! captured conj args)))
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/open-in-editor
                           {:file "src/x.cljs" :line 10 :column 5}])
        (is (= 1 (count @captured))
            "the event-fx emits exactly one :rf.editor/open fx")
        (is (= "vscode://file/src/x.cljs:10:5"
               (:uri (first @captured)))
            "the fx carries the resolved URI")
        (is (nil? (:last-open-in-editor-coord
                    (frame/frame-app-db-value :rf/causa)))
            "Causa's app-db is NOT written — the stub's
             `:last-open-in-editor-coord` slot is intentionally
             gone (rf2-g5q8d)")))))

;; ---- (5) test-only override events --------------------------------------

(deftest event-override-events-set-then-clear
  (testing "every :set-*-override-for-test event sets a value AND clears on nil"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; rf2-qy0nu — only the Machine Inspector's registered-machines
      ;; override survives the dead-panel sweep. The flows / fxs /
      ;; routes overrides were retired with their panels.
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test [:m]])
      (is (= [:m] (:registered-machines-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test nil])
      (is (nil? (:registered-machines-override (frame/frame-app-db-value :rf/causa)))))))

;; ---- (6) reg-fx contracts -----------------------------------------------
;;
;; The three :rf.causa.fx/* handlers each follow re-frame v2's
;; `(fn [ctx args] ...)` signature. We replace each registered fx with
;; a capture stub (same pattern as time_travel_cljs_test.cljs) and
;; dispatch the corresponding event-fx so the captured args round-trip.

(defonce ^:private captured-fx (atom []))

(defn- install-capture-fx! []
  (reset! captured-fx [])
  (rf/reg-fx :rf.causa.fx/copy-to-clipboard
    (fn [_ctx args] (swap! captured-fx conj [:rf.causa.fx/copy-to-clipboard args]))))

(deftest fx-copy-value-to-clipboard-fires-with-pr-str
  (testing ":rf.causa/copy-value-to-clipboard routes through the clipboard fx"
    (setup-causa-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copy-value-to-clipboard {:a 1}]))
    (is (= 1 (count @captured-fx)))
    (let [[fx-id args] (first @captured-fx)]
      (is (= :rf.causa.fx/copy-to-clipboard fx-id))
      (is (= "{:a 1}" (:text args))
          "the value is pr-str'd before reaching the fx"))))

(deftest fx-copy-path-to-clipboard-fires-with-pr-str
  (testing ":rf.causa/copy-path-to-clipboard routes through the clipboard fx"
    (setup-causa-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/copy-path-to-clipboard [:users :count]]))
    (is (= 1 (count @captured-fx)))
    (let [[fx-id args] (first @captured-fx)]
      (is (= :rf.causa.fx/copy-to-clipboard fx-id))
      (is (= "[:users :count]" (:text args))))))

(deftest fx-copy-to-clipboard-handles-non-browser-target
  (testing ":rf.causa.fx/copy-to-clipboard does not throw on a node-test
            target (no js/navigator.clipboard); contract is best-effort"
    (setup-causa-frame!)
    ;; Re-register the LIVE handler (the registry's, not our capture).
    (registry/reset-for-test!)
    (registry/register-causa-handlers!)
    (rf/with-frame :rf/causa
      ;; Should not throw — the registry's reg-fx wraps the navigator
      ;; access in a try / catch :default _ nil.
      (is (nil? (rf/dispatch-sync [:rf.causa/copy-value-to-clipboard :hi]))))))

;; ---- (7) override-aware reader semantics --------------------------------

;; The legacy :rf.causa/sub-cache sub + :rf.causa/set-sub-cache-
;; override-for-test event retired with the Subs panel under rf2-21ob3
;; (Views panel reads :rf.causa/epoch-history directly per spec/012-
;; Views.md §Data sources; no separate sub-cache surface).

(deftest sub-registered-machines-honours-override
  (testing ":rf.causa/registered-machines returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test
                         [:m-1 :m-2]])
      (is (= [:m-1 :m-2]
             @(rf/subscribe [:rf.causa/registered-machines]))))))

;; ---- (8) frame isolation (rf2-tijr Option C) ----------------------------

(deftest events-write-to-causa-frame-not-default
  (testing "every :rf.causa/* event-db handler writes to :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; :event is an arbitrary live L3 tab id (rf2-xy4yb — the 4-layer
      ;; shell switches via `:rf.causa/selected-tab`, not the legacy
      ;; `:selected-panel` slot deleted with rf2-qy0nu).
      (rf/dispatch-sync [:rf.causa/select-tab :event])
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (rf/dispatch-sync [:rf.causa/select-epoch :e]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :event (:selected-tab causa-db)))
      (is (= 1 (:selected-dispatch-id causa-db)))
      (is (= :e (:selected-epoch-id causa-db)))
      (is (nil? (:selected-tab default-db)))
      (is (nil? (:selected-dispatch-id default-db)))
      (is (nil? (:selected-epoch-id default-db))))))

;; ---- (9) edge cases over a dormant frame --------------------------------

(deftest composite-subs-non-throwing-on-empty-frame
  (testing "every composite sub returns SOMETHING (no throw) on a fresh
            :rf/causa frame — the smoke contract the audit named"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Each `is` proves the subscribe + deref completes without throwing.
      ;; The contract is that the registry's composites tolerate empty
      ;; inputs; per-panel tests cover the populated cases.
      (doseq [sub-id [:rf.causa/event-detail
                      :rf.causa/app-db-diff
                      ;; :rf.causa/causality-graph-data removed with rf2-dqnuu;
                      ;; the popover's payload sub stands in for the smoke.
                      :rf.causa/causality-popover-payload
                      :rf.causa/issues-ribbon
                      :rf.causa/trace-feed
                      :rf.causa/machine-inspector-data
                      :rf.causa/views-data
                      ;; rf2-xjhhp Phase 2 — sub-output structural diff
                      ;; composite. Empty-frame contract: returns
                      ;; `{:dispatch-id nil :records []}`.
                      :rf.causa/views-sub-diff-for-focused-event]]
        (is (some? @(rf/subscribe [sub-id]))
            (str sub-id " must not throw on an empty frame"))))))

(deftest epoch-recorded-ignores-non-target-frames
  (testing ":rf.causa/epoch-recorded only writes when frame-id matches target-frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; A non-target frame-id is dropped — :epoch-history stays empty.
      ;; (We can't easily produce a real :rf/default epoch under
      ;; node-test without booting the epoch artefact, so we assert the
      ;; gate by passing a non-target frame and observing no write.)
      (rf/dispatch-sync [:rf.causa/epoch-recorded :rf/some-other-frame])
      (is (= [] @(rf/subscribe [:rf.causa/epoch-history]))))))
