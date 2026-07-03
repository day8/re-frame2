(ns day8.re-frame2-xray.registry-cljs-test
  "Dedicated CLJS tests for `day8.re-frame2-xray.registry` (rf2-5zl7l).

  ## Scope

  `registry.cljs` is now a thin orchestrator that owns only the
  cross-panel primitives (trace-buffer sub, panel-selection slot,
  shared cascades projection, suppression-counter handlers) plus the
  per-panel `install!` fan-out (rf2-d4xda). Per-panel reg-subs /
  reg-events / reg-fxs live colocated with the panel ns that reads
  them. All registrations sit under the `:rf.xray/*` namespace and
  target the `:rf/xray` frame. Prior to this file the only coverage
  was *transitive* through per-panel view tests (each panel test
  calls `(registry/reset-for-test!)` then drives the panel-specific
  subset via subscribe/dispatch).

  Per the bead description (rf2-5zl7l) and the test-coverage audit
  (rf2-otcbz) the transitive route does NOT isolate:

    - The 3 `reg-fx` handlers as standalone units (the time-travel
      panel test stubs two of them — it doesn't drive the registered
      delegations themselves).
    - The full smoke surface: that every registered name resolves to
      a handler after `register-xray-handlers!` runs.
    - Cross-panel composite subs (per-panel tests don't exercise
      `:rf.xray/event-bundles`-driven projections end-to-end).

  ## Trade-off: smoke + high-value, not 1-per-registration

  The full registered surface is too many to exhaustively unit-test
  without duplicating the per-panel suite. The strategy below is:

    (1) **Smoke registration block** — one assertion per registered
        name that the registrar resolves it (proves the orchestrator
        `register-xray-handlers!` plus each panel `install!` reached
        every form without an early throw — the failure mode the
        audit named).
    (2) **High-value sub contracts** — defaults, composite shapes,
        override-aware readers (sub-cache, registered-flows, etc.),
        the panel-suppression / dormant-frame signal slots, and the
        REDACTED indicator (`:rf.xray/suppressed-sensitive-count`).
    (3) **High-value event contracts** — panel-select, hydration
        toggle, suppress-toggle, time-travel-scrub, filter axes
        (toggle / clear / set).
    (4) **Reg-fx contracts** — the three fxs each receive their args
        in the v2 `(fn [ctx args] ...)` shape. We capture the call
        site via reg-fx replacement (same pattern as time_travel_
        cljs_test.cljs) and assert the args round-trip.
    (5) **Edge cases** — empty app-db, override-takes-precedence,
        clear-all-filter events.

  Aim: ~30-50 deftests. The panel tests cover most paths transitively;
  this file's job is the smoke surface + the registered-fx isolation
  + the cross-panel slots no single panel test owns."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.self-noise :as self-noise]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!)
  (config/reset-suppressed-count!)
  ;; rf2-5m5n2 — reset the project-root prefix atom so a sibling test
  ;; that set it (e.g. `open_in_editor_cljs_test.cljs`) doesn't leak
  ;; into the registry tests' URI assertions.
  (config/set-project-root! nil))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- helpers ------------------------------------------------------------

(defn- setup-xray-frame!
  "The canonical per-test boot: register handlers, install the test-only
  override seam (rf2-e8330v — production registration installs no
  `-for-test` ids), allocate the :rf/xray frame, return."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

;; ---- focus ↔ registry single-source-of-truth cross-check ----------------
;;
;; rf2-1sddi6 / rf2-7ed9ms — `focus/valid-panels` (the host-facing
;; focus vocabulary) is a static `.cljc` mirror of the LIVE Dynamic L4
;; tab registry. This test fails the build the instant the two drift:
;; adding/removing a `reg-l4-tab!` Dynamic tab without updating
;; `valid-panels` (or vice-versa) breaks here. The drift this guards is
;; exactly the rf2-1sddi6 / rf2-7ed9ms finding — focus published `:routes`
;; (no such tab) while omitting shipped `:resources` / `:module-view`.

(deftest focus-valid-panels-mirrors-live-dynamic-registry
  (testing "focus/valid-panels == panel-registry/tab-ids-for-mode :dynamic"
    (setup-xray-frame!)
    (is (= focus/valid-panels
           (panel-registry/tab-ids-for-mode :dynamic))
        (str "focus/valid-panels drifted from the live Dynamic L4 tab "
             "registry. Reconcile focus.cljc `valid-panels` with the "
             "shipped `reg-l4-tab!` ids."))
    (testing "every focus alias target is a live registered tab"
      (let [live (panel-registry/tab-ids-for-mode :dynamic)]
        (doseq [[alias target] focus/panel-aliases]
          (is (contains? live target)
              (str "focus alias " alias " → " target
                   " must resolve to an installed Dynamic tab")))))))

(defn- xray-id?
  "True when `id` is a Xray-namespaced keyword. Used to filter the
  registrar's full per-kind registration map down to the Xray subset
  for the snapshot test (rf2-39n8h). Covers both the bare `:rf.xray/*`
  prefix and the per-panel `:rf.xray.<panel>/*` prefixes codified in
  `tools/xray/spec/014-Registry-Catalogue.md` §Naming convention."
  [id]
  (and (keyword? id)
       (when-let [ns (namespace id)]
         (or (= "rf.xray" ns)
             (re-matches #"rf\.xray\..*" ns)
             ;; rf2-g5q8d — `:rf.editor/open` lives under the editor-
             ;; generic prefix (cross-tool allowlist seam, rf2-cm93v).
             (= "rf.editor" ns)))))

(def ^:private all-sub-names
  "Every Xray-namespaced sub registered by `register-xray-handlers!`.
  Sorted-set literal — order-independent so concurrent PRs adding subs
  produce rebase-clean diffs (rf2-39n8h)."
  (sorted-set
   :rf.xray/active-filters
   ;; rf2-7hwwe — Machine Inspector `:after` countdown rings.
   :rf.xray/active-timers-for-focused-machine
   ;; rf2-yng0y — atomic current-state + focused-epoch before-image
   ;; (collapses the former 5-deep focus chain so `:before` / `:epoch-id`
   ;; move together — no stale-`before` frame on zoom navigation).
   ;; rf2-p53m2 — the former `:rf.xray/app-db-diff` composite + its
   ;; `:rf.xray/selected-epoch-diff` / `-flow-writes` / `-redacted-
   ;; modified-count` inputs were PRUNED (no production view consumer);
   ;; this atomic sub is the app-db tab's primary read-model.
   :rf.xray/app-db-current+diff
   ;; rf2-okvit — app-db tab current-state inspector section model
   ;; (derived from the atomic sub above).
   :rf.xray/app-db-state
   :rf.xray/event-bundles
   ;; First-class edn-inspector widget owns the WHOLE renderer contract
   ;; — browse + diff + mini — via `:rf.xray.edn-inspector/*` events &
   ;; subs (rf2-oqa60 phase 1 + rf2-q3dzw phase 5). The pre-rf2-q3dzw
   ;; sibling slots (`:rf.xray/edn-inspector-{expansion,node-state}`
   ;; and `:rf.xray.data-inspector/{expansion,all-expansion}`) are
   ;; deleted with the legacy `edn-inspector.render` +
   ;; `theme.data-inspector` engines.
   ;; rf2-uji72 — shared draggable column-resize. Sub returns
   ;; the per-table {col-id → px} override map.
   :rf.xray.column-widths/for-table
   :rf.xray.edn-inspector/expansion
   ;; rf2-kbdk8 — per-mount measured container widths for the width-aware
   ;; expansion heuristic. Keyed by mount-id; updated via ResizeObserver
   ;; in the widget's ref callback.
   :rf.xray.edn-inspector/widths
   ;; rf2-h71e0 — per-mount zoom-into-node path. Keyed by [panel-id
   ;; site-or-mount-id]; non-empty vec stores the path the widget zooms
   ;; into; nil / empty renders the full tree (un-zoomed default).
   :rf.xray.edn-inspector/zoom
   ;; rf2-l4625 — edn-inspector popup overlay subs (stack + entries +
   ;; projection subs for open? / top / per-mount-id entry).
   :rf.xray.edn-inspector-popup/stack
   :rf.xray.edn-inspector-popup/entries
   :rf.xray.edn-inspector-popup/open?
   :rf.xray.edn-inspector-popup/top
   :rf.xray.edn-inspector-popup/entry
   ;; rf2-59e7k — Cancellation-cascade visualiser subs (Machines
   ;; tab side-panel + Trace popover). Per
   ;; `tools/xray/spec/019-Cross-Cutting-Insight.md` §M.3.
   :rf.xray/cancellation-cascade-expanded?
   :rf.xray/cancellation-cascade-for-focused-event
   :rf.xray/cancellation-cascade-for-focused-machine
   :rf.xray/cancellation-cascade-popover-focus
   :rf.xray/cancellation-cascade-popover-open?
   ;; rf2-i39w2 Phase 3 — hiccup-diff micro-engine opt-in toggle.
   :rf.xray/diff-opts
   :rf.xray/edit-popup-draft
   :rf.xray/edit-popup-open?
   :rf.xray/edit-popup-trigger
   ;; rf2-dudqz — host-side editor default exposed to the Settings
   ;; popup's editor-override picker so the "Project default: <name>"
   ;; hint renders. Reads `config/editor` atom directly.
   :rf.xray/editor-host-default
   :rf.xray/epoch-history
   ;; rf2-sc3r1 — Epoch panel composite (focused epoch's pipeline
   ;; rows) + per-row expansion-set sub.
   :rf.xray/epoch-pipeline
   :rf.xray.epoch/expanded-rows
   ;; rf2-tzmmf — Epoch panel SUBSCRIPTIONS filter-mode sub
   ;; (`[all][changed][unchanged]` button-bar; supersedes
   ;; rf2-kfh1v's boolean `subs-show-unchanged?`).
   :rf.xray.epoch/subs-filter-mode
   ;; rf2-vv3m6 (2026-05-29) — the per-surface diff-mode subs retired
   ;; alongside the `[diff][full][full+diff]` toggle. The four subs that
   ;; previously sat here (`:rf.xray.epoch/db-diff-mode`,
   ;; `:rf.xray.epoch/subs-value-diff-mode`, `:rf.xray.app-db/diff-mode`,
   ;; `:rf.xray.machine-inspector/diff-mode`) are gone — FULL+DIFF is
   ;; the single rendering across every consumer surface.
   ;; rf2-7ed9ms — renamed off the retired-panel name `:rf.xray/event-detail`
   ;; to the behaviour name `:rf.xray/focused-event-bundle-detail`.
   :rf.xray/focused-event-bundle-detail
   :rf.xray/filtered-event-bundles
   ;; rf2-jvghz — model behind the L2 'N hidden by filters' indicator
   ;; (raw vs filtered visible counts + the active filter cause).
   :rf.xray/hidden-by-filters
   :rf.xray/focus
   ;; rf2-70tkv — App-DB diff subs pivot off the spine's focus
   ;; `:epoch-id` (which auto-tracks head in LIVE mode). This sub is
   ;; the thin projection seam. (rf2-uy7nz retired the legacy
   ;; `:selected-epoch-id` mirror slot that this superseded.)
   :rf.xray/focus-epoch-id
   :rf.xray/focus-slot
   ;; rf2-iwwou — hardened L1 frame-switcher slot. Public contract
   ;; every frame-aware feature reaches through: the ribbon picker,
   ;; the Cmd-K palette's `:select-frame` verb, future panel-by-frame
   ;; surfaces. See `frame_switcher.cljs` for the full contract.
   :rf.xray/current-frame
   :rf.xray/available-frames
   ;; rf2-4vp5j — the dedicated VIEW-SCOPE frame slot (frame is a view
   ;; scope, not a filter): the resolved scope + its raw stored slot +
   ;; the raw target-frame slot it falls back to on a host seed.
   :rf.xray/view-scope-frame
   :rf.xray/view-scope-frame-slot
   :rf.xray/target-frame-slot
   :rf.xray/focused-slice-path
   ;; rf2-ad7zx.9 — the `:rf.xray/issues-filters` sub was dropped with
   ;; the Issues panel's filter-chrome reconcile to the Figma design
   ;; (pure rows, no filtering — spec/021 §8.2).
   :rf.xray/issues-ribbon
   :rf.xray/machine-definitions
   :rf.xray/machine-inspector-data
   ;; rf2-3d987 issue #4 — chart-collapsed slot per machine, persisted
   ;; to localStorage. Lets the operator hide the chart so the snapshot
   ;; pair has room without scrolling. (rf2-48fwsi retired the sibling
   ;; view-mode-by-id / view-mode-for subs along with the dead
   ;; Canvas/List toggle.)
   :rf.xray.machine-canvas/chart-collapsed-by-id
   :rf.xray.machine-canvas/chart-collapsed-for
   ;; rf2-a9cke — focused-event lens composite consumed by the
   ;; Machine Inspector + the cancellation-cascade SidePanel.
   :rf.xray/machine-transitions-for-focused-event
   ;; rf2-g2axio — the focused epoch's projected machine-cascade rows
   ;; for the SHARED EVENT HANDLER mini-pipeline (the Machine tab
   ;; consumes the SAME renderer the Epoch panel does).
   :rf.xray/machine-focused-epoch-cascade
   ;; rf2-y9xmf — scrubber-position slot survives the collapse; the
   ;; `:after`-rings overlay reads it to gate ring rendering to the
   ;; `:present` position. (rf2-nugvv removed the share-URL surface that
   ;; previously also round-tripped it.) The scrubber UI itself is gone
   ;; (sibling bead rf2-r4nao re-hosts it under Static).
   :rf.xray/machine-scrubber-position
   :rf.xray/machine-snapshots
   ;; rf2-uyp86 — managed-fx wire-boundary diff composite.
   :rf.xray/managed-fx-for-focused-event
   ;; rf2-7hwwe — `:after` ring tick driver wall-clock surface + hover slot.
   :rf.xray/now-ms
   ;; rf2-39n8h discovered — focused-frame slot consumed across panels.
   :rf.xray/observed-frame
   ;; rf2-e9tb0 — App-DB segment-inspector popup subs.
   :rf.xray/segment-inspector-open?
   :rf.xray/segment-inspector-path
   :rf.xray/segment-inspector-slot
   :rf.xray/segment-inspector-value
   :rf.xray/palette-active-item
   :rf.xray/palette-cursor
   :rf.xray/palette-index
   :rf.xray/palette-open?
   :rf.xray/palette-query
   ;; rf2-ybjkx — recents vector (last-used commands, persisted to
   ;; localStorage).
   :rf.xray/palette-recents
   :rf.xray/palette-results
   :rf.xray/registered-machines
   ;; rf2-nrbs9 — Routes tab (7th L3 tab) sub family. (The `*-override`
   ;; subs split out behind `install-test-overrides!` — rf2-e8330v.)
   :rf.xray/registered-routes
   :rf.xray/current-route-slice
   :rf.xray/routing-tab-data
   ;; Spec 016 §Xray and AI tooling — Resources tab (8th L3 tab) sub
   ;; family. The static registry + the live runtime-db cache/ledger
   ;; slices + the view-facing composite + the scope-mismatch-lint
   ;; sub-reads slot. (The per-kind `*-override` subs split out behind
   ;; `install-test-overrides!` — rf2-e8330v.)
   :rf.xray/registered-resources
   ;; rf2-hls77w (EP-0016 D3) — named resource-scope resolver registry
   ;; (the third resources kind).
   :rf.xray/registered-scope-resolvers
   :rf.xray/resource-entries
   :rf.xray/resource-work-ledger
   :rf.xray/resource-sub-reads
   ;; rf2-m5u3gt — the live route/resource graph reads the routing slice.
   :rf.xray/resource-routing-slice
   :rf.xray/resources-tab-data
   ;; rf2-9ett2d (EP-0014 prop-3) — Derivation-Graph tab subs: the
   ;; assembled `re-frame.derivation.graph` view (static/live), the mode
   ;; toggle slot, and the view-facing composite. (The `*-override` sub
   ;; split out behind `install-test-overrides!` — rf2-e8330v.)
   :rf.xray/derivation-graph
   :rf.xray/derivation-graph-mode
   :rf.xray/derivation-graph-tab-data
   ;; rf2-32siq3.12 — EP-0023 image/frame model on the Module-view tab: the
   ;; live image-loaded frames as execution contexts, each carrying its
   ;; resolved image (the generation's [kind id] descriptors). Reads the
   ;; EP-0023 live-frame registry + sealed generations via the fail-soft
   ;; `image-view-reads` seam; presents Xray itself as its OWN image
   ;; inspecting the target frame as data (EP-0023 §Xray Beside The Target).
   :rf.xray/image-view
   ;; rf2-o5f5f.3 — Routes browse + Simulate-URL state lives under
   ;; the Static Routes panel (promoted from `:rf.xray.routing/*` per
   ;; the two-verbs-two-homes split). The Dynamic Routing lens narrows
   ;; to the focused-event surface and no longer owns these slots.
   :rf.xray.static.routes/query
   :rf.xray.static.routes/sim-url
   :rf.xray.static.routes/expanded
   ;; rf2-o5f5f.3 — Static Routes hermetic Simulate-navigation toggle
   ;; set + view-facing composite.
   :rf.xray.static.routes/sim-nav-open
   :rf.xray.static.routes/tab-data
   ;; rf2-o5f5f.4 — Static Schemas sub-tab subs (browse-all over the
   ;; app-db schemas storage + the registrar's `:event` / `:sub`
   ;; `:spec` slots + view-facing composite). The `*-override` sub split
   ;; out behind `install-test-overrides!` (rf2-e8330v).
   :rf.xray.static.schemas/query
   :rf.xray.static.schemas/registry
   :rf.xray.static.schemas/tab-data
   ;; rf2-uhsqb — Static Flows sub-tab subs (browse-all over the live
   ;; `re-frame.flows.registry/flows` atom + view-facing composite). The
   ;; `*-override` sub split out behind `install-test-overrides!`
   ;; (rf2-e8330v).
   :rf.xray.static.flows/query
   :rf.xray.static.flows/registered-flows
   :rf.xray.static.flows/tab-data
   ;; rf2-o5f5f.6 — Static Interceptors sub-tab subs (pure-browse over
   ;; the interceptors surfaced through registered events). The
   ;; `*-override` sub split out behind `install-test-overrides!`
   ;; (rf2-e8330v).
   :rf.xray.static.interceptors/query
   :rf.xray.static.interceptors/registry
   :rf.xray.static.interceptors/tab-data
   ;; rf2-hga49 — tab-ribbon Reset rewind affordance: the transient
   ;; inline failure-flash slot the ribbon reads.
   :rf.xray/reset-flash
   ;; rf2-p53m2 — `:rf.xray/selected-epoch-diff`,
   ;; `:rf.xray/selected-epoch-flow-writes`, and
   ;; `:rf.xray/selected-epoch-redacted-modified-count` were PRUNED with
   ;; the dead `:rf.xray/app-db-diff` composite. Only
   ;; `:rf.xray/selected-epoch-record` (the Epoch panel's `:db` diff
   ;; source) survives in this family.
   :rf.xray/selected-epoch-record
   :rf.xray/selected-machine-id
   ;; rf2-om6fa — Story-aware modal positioning opt.
   :rf.xray/modal-positioning
   ;; rf2-ikuwt — per-event-id mute filter subs.
   :rf.xray/mute-manager-open?
   :rf.xray/muted-event-ids
   :rf.xray/muted-event-ids-count
   :rf.xray/row-context-menu
   ;; rf2-o5f5f.1 — Dynamic ↔ Static mode slot + Static-scoped tab.
   :rf.xray/mode
   :rf.xray.static/selected-tab
   ;; rf2-o5f5f.2 — Static Machines sub-tab subs (browse-all + per-
   ;; machine sub-mode). Composite + raw slots feeding the L4 master-
   ;; detail surface.
   :rf.xray.static.machines/data
   :rf.xray.static.machines/rows
   :rf.xray.static.machines/search
   :rf.xray.static.machines/selected-id
   :rf.xray.static.machines/sort-key
   :rf.xray.static.machines/sub-mode
   :rf.xray.static.machines/sub-mode-by-id
   ;; rf2-x8h9y — horizontal resize handle width.
   :rf.xray/panel-width-px
   ;; rf2-6ni62 — L2 event-list user-resizable column widths.
   :rf.xray/event-list-col-widths
   ;; rf2-t2dsh — L2/L3 seam-handle event-list height.
   :rf.xray/events-list-height-px
   ;; rf2-vbbq0 / rf2-0s2at — L2 row relative-time chip anchor (sub
   ;; composed off `:rf.xray/event-bundles` — dispatched-time of the most
   ;; recent cascade; flips on event arrival, not on a per-second tick).
   :rf.xray/relative-time-now-ms
   :rf.xray/selected-tab
   ;; rf2-6tw7t — Machine tab fit-on-entry nonce (bumped by
   ;; `:rf.xray/select-tab :machines` + `:rf.xray.static/select-tab
   ;; :machines`; forwarded to MachineChart's `:fit-signal`).
   :rf.xray/machine-tab-fit-signal
   ;; rf2-ttnst — Settings popup expansion convenience subs.
   :rf.xray/density
   :rf.xray/long-keyword-threshold
   ;; rf2-4s08ov — open-in-editor 'pick an editor in Settings' hint
   ;; toast mount-gate sub.
   :rf.xray/editor-hint-open?
   ;; rf2-9poxq — Settings popup subs.
   :rf.xray/setting
   :rf.xray/settings
   :rf.xray/settings-active-tab
   :rf.xray/settings-clear-confirm-open?
   :rf.xray/settings-open?
   :rf.xray/show-me-when-this-changed-result
   :rf.xray/show-tool-frames?
   ;; rf2-r9lyy — opt-in surface for the :ungrouped pseudo-event-bundle bucket.
   :rf.xray/show-ungrouped?
   ;; rf2-r4nao — Static Machines Sim sub-mode subs (rehost from
   ;; rf2-v869p Phase 2; ns moved from :rf.xray/sim-* to
   ;; :rf.xray.static.machines/sim-*).
   :rf.xray.static.machines/sim-active?
   :rf.xray.static.machines/sim-available-transitions
   :rf.xray.static.machines/sim-by-machine
   :rf.xray.static.machines/sim-event-suggestions
   :rf.xray.static.machines/sim-state
   ;; rf2-u422r (epic rf2-nrrtb) — on-chart sim binding subs: the
   ;; current snapshot state (active-state highlight) + the last
   ;; transition (focused-edge animation).
   :rf.xray.static.machines/sim-current-state
   :rf.xray.static.machines/sim-last-transition
   ;; rf2-nugvv (2026-06-04) — the Share affordance (rf2-nqw0v) + its
   ;; per-cascade structured export (rf2-0us27) subs are removed with
   ;; the Machine panel's Share button (the modal's sole UI entry point).
   :rf.xray/suppressed-sensitive-count
   :rf.xray/target-frame
   :rf.xray/target-frame-db
   ;; EP-0001 (rf2-vzld77) — the observed frame's runtime-db partition value;
   ;; sibling of target-frame-db sourcing the durable framework state (machine
   ;; snapshots, route slice) that moved out of app-db into runtime-db.
   :rf.xray/target-frame-runtime-db
   ;; rf2-7hwwe — `:after` countdown ring hover slot (rich tooltip lifecycle).
   :rf.xray/timer-hover
   :rf.xray/trace-buffer
   ;; rf2-l2f2g — per-row inline raw-EDN payload expansion state
   ;; (spec/023-Trace-Panel.md §3 — row click → raw EDN).
   :rf.xray/trace-expanded-row-ids
   ;; rf2-aqusw — the per-band collapse state sub
   ;; (`:rf.xray/trace-collapsed-band-ids`) was REMOVED with the phase-band
   ;; hierarchy; the flat Trace panel has no collapsible bands.
   ;; rf2-td380 — epoch-scoped feed (reads the focused epoch record's
   ;; `:trace-events` directly). The `:rf.xray/trace-feed-state`
   ;; buffer snapshot + `:rf.xray/trace-filters` chip-filter slot were
   ;; removed with rf2-td380 + rf2-gkczt. The feed now projects the arc
   ;; shape (envelope + 4 phase bands) per spec/023 (rf2-l2f2g).
   :rf.xray/trace-feed
   ;; rf2-wcfsy — layer-3 composite over `:rf.xray/event-bundles` +
   ;; `:rf.xray/focus`; returns the focused cascade record (or nil
   ;; when no focus is pinned). Replaces an inline `some` scan that
   ;; ran on every Trace-panel render — the composite memoises on
   ;; its input signals so the scan only re-runs when cascades /
   ;; focus actually change. Read by the Trace panel's
   ;; `event-bundle-status-bar`.
   :rf.xray.trace/focused-event-bundle
   ;; Reactive panel (rf2-wyvf2 · spec/021 §3 · renamed from Views per
   ;; §11.5; tab key stays `:views`, display label rebases). Reads the
   ;; focused cascade's `:trace-events` for the substrate ops landed in
   ;; PRs #1728 + #1729 (`:rf.view/rendered`, `:rf.sub/skipped`,
   ;; `:rf.cascade/captured`).
   :rf.xray/reactive-data
   :rf.xray/reactive-show-unchanged?))

(def ^:private all-event-names
  "Every Xray-namespaced event registered by `register-xray-handlers!`.
  Sorted-set literal (rf2-39n8h) — order-independent so concurrent PRs
  adding events produce rebase-clean diffs.

  Issues-ribbon panel-internal events (rf2-nmc1f) nest under
  `:rf.xray.issues/*` so the namespace itself encodes
  \"panel-internal, no cross-panel callers\". Per the
  `:rf.xray.<panel>/*` convention codified in
  `tools/xray/spec/014-Registry-Catalogue.md` §Naming convention."
  (sorted-set
   ;; (Pre-rf2-q3dzw `:rf.xray.data-inspector/*` events covered per-
   ;; row toggle/set + large-value confirm flow; the ns is deleted
   ;; with phase 5 and the edn-inspector widget's
   ;; `:rf.xray.edn-inspector/*` events cover the same surface. Large-
   ;; blob confirm chrome moves to the popup phase, D6=a.)
   ;; rf2-ad7zx.9 — the `:rf.xray.issues/toggle-severity` /
   ;; `toggle-prefix` / `clear-filters` events were dropped with the
   ;; Issues panel's filter-chrome reconcile to the Figma design (pure
   ;; rows, no filtering — spec/021 §8.2).
   :rf.xray/add-filter
   ;; rf2-uji72 — shared draggable column-resize events. `resize-pair-
   ;; tick` updates both adjacent column widths in one writeback so the
   ;; sum is conserved; `resize-pair-commit` (rf2-xm1jy) persists the
   ;; settled widths to localStorage exactly once on pointerup;
   ;; `reset` clears all overrides for a table.
   ;; rf2-xzg1y — `hydrate` lifts the persisted column-widths map from
   ;; localStorage into app-db at first-mount time.
   :rf.xray.column-widths/hydrate
   :rf.xray.column-widths/resize-pair-commit
   :rf.xray.column-widths/resize-pair-tick
   :rf.xray.column-widths/reset
   ;; rf2-59e7k — Cancellation-cascade visualiser events. Per
   ;; `tools/xray/spec/019-Cross-Cutting-Insight.md` §M.3.
   :rf.xray/cancellation-cascade-close
   :rf.xray/cancellation-cascade-open
   :rf.xray/cancellation-cascade-set-expanded
   :rf.xray/cancellation-cascade-toggle-expand
   ;; rf2-jvghz — one-click reset behind the L2 'N hidden by filters'
   ;; indicator (resets IN/OUT pills + frame pin + mutes).
   :rf.xray/clear-all-filters
   :rf.xray/clear-machine-selection
   ;; rf2-hga49 — clear the tab-ribbon Reset failure flash.
   :rf.xray/clear-reset-flash
   :rf.xray/clear-selected-dispatch-id
   :rf.xray/clear-slice-focus
   :rf.xray/clear-trace-buffer
   ;; rf2-e9tb0 — App-DB segment-inspector popup close event.
   :rf.xray/close-segment-inspector
   ;; rf2-7dyi8 — drop every expanded trace-row id in one shot.
   :rf.xray/clear-trace-expand
   :rf.xray/close-edit-popup
   :rf.xray/close-shell
   :rf.xray/copy-path-to-clipboard
   :rf.xray/copy-value-to-clipboard
   ;; First-class edn-inspector widget events (sticky-expansion +
   ;; reset). Per `tools/xray/spec/021-Dynamic-Panel-Designs.md` §10.4.
   ;; The pre-rf2-q3dzw flat-shape sibling events
   ;; (`:rf.xray/edn-inspector-*`) are deleted with the legacy
   ;; `edn-inspector.render` engine in phase 5.
   :rf.xray.edn-inspector/reset-expansion
   :rf.xray.edn-inspector/set-node
   :rf.xray.edn-inspector/toggle-node
   ;; rf2-kbdk8 — width-aware heuristic events. set-width records the
   ;; ResizeObserver measurement; clear-width is dispatched on unmount.
   :rf.xray.edn-inspector/set-width
   :rf.xray.edn-inspector/clear-width
   ;; rf2-h71e0 — zoom-into-node + breadcrumb navigation events.
   ;; zoom-to stores an absolute path under the per-mount slot; zoom-up
   ;; pops one segment; zoom-reset clears (per-mount when args given,
   ;; otherwise the whole slot).
   :rf.xray.edn-inspector/zoom-to
   :rf.xray.edn-inspector/zoom-up
   :rf.xray.edn-inspector/zoom-reset
   ;; rf2-l4625 — edn-inspector popup overlay events (open / close /
   ;; close-top / close-all).
   :rf.xray.edn-inspector-popup/open
   :rf.xray.edn-inspector-popup/close
   :rf.xray.edn-inspector-popup/close-top
   :rf.xray.edn-inspector-popup/close-all
   :rf.xray/delete-edit-popup
   :rf.xray/edit-popup-set-mode
   :rf.xray/edit-popup-set-pattern
   ;; rf2-4s08ov — open-in-editor 'pick an editor in Settings' hint
   ;; toast events. Shown when an open-in-editor chip is clicked but no
   ;; editor is effectively configured (instead of a silent vscode:
   ;; no-op).
   :rf.xray/editor-hint-show
   :rf.xray/editor-hint-dismiss
   :rf.xray/editor-hint-open-settings
   ;; rf2-sc3r1 — Epoch panel per-row expansion events.
   :rf.xray.epoch/toggle-row-expand
   :rf.xray.epoch/clear-row-expand
   ;; rf2-tzmmf — Epoch panel SUBSCRIPTIONS filter-mode write event
   ;; (`[all][changed][unchanged]` button-bar; supersedes
   ;; rf2-kfh1v's `toggle-subs-show-unchanged`).
   :rf.xray.epoch/set-subs-filter-mode
   ;; rf2-vv3m6 (2026-05-29) — the per-surface diff-mode write events
   ;; retired alongside the `[diff][full][full+diff]` toggle. The four
   ;; events that previously sat here
   ;; (`:rf.xray.epoch/set-db-diff-mode`,
   ;; `:rf.xray.epoch/set-subs-value-diff-mode`,
   ;; `:rf.xray.app-db/set-diff-mode`,
   ;; `:rf.xray.machine-inspector/set-diff-mode`) are gone.
   :rf.xray/epoch-recorded
   ;; rf2-piye4 — typed-predicate filter events. Each appends a
   ;; typed `{:kind <kw> :params {…}}` IN pill from a right-click
   ;; affordance on the Machines / managed-fx panels.
   :rf.xray/filter-by-fx
   :rf.xray/filter-by-http-correlation
   :rf.xray/filter-by-machine
   :rf.xray/focus-event
   :rf.xray/focus-event-next
   :rf.xray/focus-event-prev
   ;; rf2-5qp4g — DISPATCH source-kind enrichment parent-epoch
   ;; navigation: the Epoch panel's :fx-dispatch / :fx-dispatch-later
   ;; parent-epoch chip dispatches this to pivot the spine by epoch-id.
   :rf.xray/focus-epoch
   ;; rf2-uyp86 — managed-fx wire-boundary diff cross-link: the HANDLER
   ;; DISPATCHED row reuses the spine's canonical `:rf.xray/focus-event`
   ;; (listed above); rf2-fsqlgz collapsed the former panel-local
   ;; duplicate onto it, so there is one registration.
   :rf.xray/focus-slice-path
   ;; rf2-59e7k — Cancellation-cascade row-click jump (delegates into
   ;; :rf.xray/select-dispatch-id via the spine shim).
   :rf.xray/focus-trace-entry
   :rf.xray/follow-head
   :rf.xray/hide-event-type
   :rf.xray/hydrate-filters
   ;; rf2-ikuwt — per-event-id mute filter events.
   :rf.xray/clear-muted-event-ids
   :rf.xray/close-mute-manager
   :rf.xray/close-row-context-menu
   :rf.xray/hydrate-muted-event-ids
   :rf.xray/mute-event-id
   :rf.xray/open-mute-manager
   :rf.xray/open-row-context-menu
   :rf.xray/unmute-event-id
   ;; Phase 4 (rf2-m7co9) — ELK chart layout pulse.
   :rf.xray/machine-chart-layout-pulse
   :rf.xray/machine-state-clicked
   ;; rf2-3d987 issue #4 — chart-collapsed events. (rf2-48fwsi retired
   ;; the sibling set-view-mode / hydrate-view-modes events with the
   ;; dead Canvas/List toggle.)
   :rf.xray.machine-canvas/hydrate-chart-collapsed
   :rf.xray.machine-canvas/set-chart-collapsed
   ;; (Pre-rf2-q3dzw the legacy `edn-inspector.render` engine emitted
   ;; `:rf.xray/navigate-to-path` from its clickable-path glyphs and
   ;; registered a default no-op handler. The new widget ships ZERO
   ;; path-click interactions in phase 1 — the locked B.9 / rf2-sndui
   ;; surface ships exactly one path-click semantic which is deferred
   ;; to a follow-on. With the legacy engine deleted in phase 5 the
   ;; default handler is gone too.)
   :rf.xray/note-sensitive-suppressed
   :rf.xray/open-edit-popup
   :rf.xray/open-in-editor
   ;; rf2-e9tb0 — App-DB segment-inspector popup open event.
   :rf.xray/open-segment-inspector
   :rf.xray/open-settings
   :rf.xray/palette-close
   :rf.xray/palette-cursor-down
   :rf.xray/palette-cursor-set
   :rf.xray/palette-cursor-up
   :rf.xray/palette-invoke
   :rf.xray/palette-open
   :rf.xray/palette-set-query
   :rf.xray/palette-toggle
   ;; rf2-czcg5 — chrome `⛶` pop-out button → lowers to mount/popout!
   ;; via the :rf.xray.fx/popout-shell effect.
   :rf.xray/popout-shell
   :rf.xray/preview-cascade
   :rf.xray/remove-filter
   ;; rf2-6ni62 — L2 event-list column-divider double-click reset.
   :rf.xray/reset-event-list-col-width
   ;; rf2-x8h9y — resize-handle double-click reset.
   :rf.xray/reset-panel-width
   ;; rf2-t2dsh — L2/L3 seam-handle double-click reset.
   :rf.xray/reset-events-list-height
   :rf.xray/reset-suppressed-counters
   ;; rf2-hga49 — tab-ribbon Reset rewind affordance: the event-fx that
   ;; trampolines into `:rf.xray.fx/restore-epoch`, plus its inline
   ;; failure-flash setter.
   :rf.xray/reset-to-epoch
   :rf.xray/reset-flash-failed
   :rf.xray/save-edit-popup
   :rf.xray/select-dispatch-id
   :rf.xray/select-epoch
   ;; rf2-iwwou — canonical frame-switcher write surface. Dispatches
   ;; the spine's `:rf.xray/set-frame` + fires `:rf.xray.frame-
   ;; switcher/persist` for localStorage. The L1 ribbon picker and the
   ;; Cmd-K palette's `:palette/select-frame` verb both dispatch this
   ;; event so every persistence / instrumentation layer lives in one
   ;; place.
   :rf.xray/select-frame
   :rf.xray/select-machine-id
   :rf.xray/select-tab
   ;; rf2-o5f5f.1 — Dynamic ↔ Static mode events + Static-scoped tab.
   ;; `set-mode` writes a specific mode; `toggle-mode` flips between
   ;; them; both attach the `:rf.xray.static/persist-mode` fx so the
   ;; choice round-trips through localStorage.
   :rf.xray/set-mode
   :rf.xray/toggle-mode
   :rf.xray.static/select-tab
   ;; rf2-o5f5f.2 — Static Machines sub-tab events. Selection +
   ;; search + sort + sub-mode + hydrate (post-localStorage restore) +
   ;; two no-op slots (state-clicked + open-chart-popout) reserved so
   ;; click affordances land on a known handler rather than emitting
   ;; `:rf.warning/no-handler`.
   :rf.xray.static.machines/clear-search
   :rf.xray.static.machines/cycle-sort
   :rf.xray.static.machines/hydrate
   :rf.xray.static.machines/open-chart-popout
   :rf.xray.static.machines/select
   :rf.xray.static.machines/set-search
   :rf.xray.static.machines/set-sub-mode
   :rf.xray.static.machines/state-clicked
   :rf.xray/set-frame
   ;; rf2-9ett2d (EP-0014 prop-3) — Derivation-Graph tab mode toggle.
   ;; (The `set-*-override-for-test` events across every panel split out
   ;; behind `install-test-overrides!` — rf2-e8330v.)
   :rf.xray/set-derivation-graph-mode
   ;; rf2-o5f5f.3 — Static Routes UI-state events (search input,
   ;; Simulate-URL input, expand-row toggle, hermetic Simulate-nav
   ;; toggle, cross-link to Dynamic Routing). Promoted from the
   ;; Dynamic `:rf.xray.routing/*` group per the two-verbs-two-homes
   ;; split.
   :rf.xray.static.routes/set-query
   :rf.xray.static.routes/set-sim-url
   :rf.xray.static.routes/toggle-row
   :rf.xray.static.routes/toggle-sim-nav
   :rf.xray.static.routes/jump-to-dynamic
   ;; rf2-o5f5f.4 — Static Schemas sub-tab events.
   :rf.xray.static.schemas/set-query
   ;; rf2-uhsqb — Static Flows sub-tab events.
   :rf.xray.static.flows/set-query
   ;; rf2-o5f5f.6 — Static Interceptors sub-tab events (search slot).
   :rf.xray.static.interceptors/set-query
   ;; rf2-om6fa — Story-aware modal positioning opt.
   :rf.xray/set-modal-positioning
   ;; rf2-6ni62 — L2 event-list column-divider live update event.
   :rf.xray/set-event-list-col-width
   ;; rf2-x8h9y — resize-handle live update event.
   :rf.xray/set-panel-width-px
   ;; rf2-t2dsh — L2/L3 seam-handle live update event.
   :rf.xray/set-events-list-height-px
   ;; rf2-y9xmf — scrubber-position slot reducer (UI is gone; the
   ;; `:after`-rings overlay reads the slot this event writes. The
   ;; share-URL surface that also round-tripped it was removed in
   ;; rf2-nugvv).
   :rf.xray/set-scrubber-position
   ;; rf2-y9xmf — per-machine prev/next nav (walks the spine's epoch-
   ;; history to the prior/next epoch that ALSO touched the focused
   ;; machine).
   :rf.xray/machine-focus-prev
   :rf.xray/machine-focus-next
   :rf.xray/set-target-frame
   ;; rf2-9poxq — Settings popup events.
   ;; rf2-ttnst — Settings popup Buffer-tab clear-buffer family
   ;; (confirm / cancel / clear).
   :rf.xray/settings-cancel-clear-buffer
   :rf.xray/settings-clear-buffer
   :rf.xray/settings-close
   :rf.xray/settings-confirm-clear-buffer
   :rf.xray/settings-open
   :rf.xray/settings-select-tab
   :rf.xray/settings-toggle
   :rf.xray/settings-update
   ;; rf2-nugvv (2026-06-04) — the Share affordance (rf2-nqw0v) + its
   ;; per-cascade structured export (rf2-0us27) events are removed with
   ;; the Machine panel's Share button (the modal's sole UI entry point).
   ;; rf2-r4nao — Static Machines Sim sub-mode events (rehost from
   ;; rf2-v869p Phase 2; ns moved from :rf.xray/sim-* to
   ;; :rf.xray.static.machines/sim-*).
   :rf.xray.static.machines/sim-reset
   :rf.xray.static.machines/sim-set-pending-data
   :rf.xray.static.machines/sim-set-pending-event
   :rf.xray.static.machines/sim-start
   :rf.xray.static.machines/sim-step
   :rf.xray.static.machines/sim-stop
   ;; rf2-u422r (epic rf2-nrrtb) — on-chart edge click → step. Coerces
   ;; the clicked transition edge's fireable event-id and folds one
   ;; step through the same engine path the step-button drives.
   :rf.xray.static.machines/sim-chart-edge-clicked
   :rf.xray/sync-epoch-history
   :rf.xray/sync-trace-buffer
   ;; rf2-7hwwe — `:after` countdown rings event family. timer-tick
   ;; is the rAF pulse; timer-hover writes the per-ring hovered slot
   ;; (v1 surfaces the tooltip via native SVG <title>; the slot is
   ;; plumbed for a follow-on rich-tooltip).
   :rf.xray/timer-hover
   :rf.xray/timer-tick
   :rf.xray/toggle-live-pause
   ;; rf2-l2f2g — toggle the inline raw-EDN payload expansion for one
   ;; trace row (spec/023-Trace-Panel.md §3 — Row click → raw EDN).
   :rf.xray/toggle-trace-row-expand
   ;; rf2-aqusw — the per-band collapse-toggle event
   ;; (`:rf.xray/toggle-trace-band-collapse`) was REMOVED with the
   ;; phase-band hierarchy; the flat Trace panel has no collapsible bands.
   ;; Reactive panel events (rf2-wyvf2 · spec/021 §3 · renamed from
   ;; Views per §11.5; tab key stays `:views`).
   :rf.xray/reactive-set-unchanged
   :rf.xray/reactive-toggle-unchanged))

(def ^:private all-fx-names
  "Every Xray-namespaced fx registered by `register-xray-handlers!`.
  Sorted-set literal (rf2-39n8h) — order-independent so concurrent PRs
  adding fxs produce rebase-clean diffs."
  (sorted-set
   :rf.xray.fx/copy-to-clipboard
   ;; rf2-hga49 — tab-ribbon Reset rewind affordance: calls
   ;; `rf/restore-epoch!` against the observed frame + focused epoch, and
   ;; dispatches the inline failure flash on a false return.
   :rf.xray.fx/restore-epoch
   ;; rf2-nugvv (2026-06-04) — the Share affordance new-tab fx
   ;; (`:rf.xray.fx/open-in-new-tab`, rf2-nqw0v) + the per-cascade export
   ;; download fx (`:rf.xray.fx/download-text-file`, rf2-0us27) are
   ;; removed with the Machine panel's Share button.
   ;; rf2-fq491 — `✕` close button: the DOM-side shell-hide effect fired
   ;; by `:rf.xray/close-shell`. Registered via `mount/install-fx!` from
   ;; the orchestrator; calls `mount/close!`.
   :rf.xray.fx/hide-shell
   ;; rf2-czcg5 — `⛶` pop-out button: the DOM-side second-window launch
   ;; effect fired by `:rf.xray/popout-shell`. Registered via
   ;; `mount/install-fx!` alongside hide-shell; calls `mount/popout!`.
   :rf.xray.fx/popout-shell
   ;; rf2-xzg1y — shared draggable column-widths persistence fx.
   ;; Attached to every `resize-pair` + `reset` event so the
   ;; post-mutation `{table-id {col-id px}}` map round-trips to
   ;; localStorage in one place (mirrors the filter-persistence shape
   ;; below).
   :rf.xray.column-widths/persist
   ;; rf2-ak4ms — auto-filter persistence side-effect. Lives under the
   ;; filter-specific prefix because the localStorage write is bound
   ;; to the filter-mutating events (add-filter / remove-filter /
   ;; save-edit-popup / delete-edit-popup) — every mutation round-trips
   ;; to localStorage in one place.
   :rf.xray.filters/persist
   ;; rf2-iwwou — frame-switcher slot persistence side-effect. Bound to
   ;; the `:rf.xray/select-frame` handler so the user's last-picked
   ;; frame survives a reload. Lives under the frame-switcher-specific
   ;; prefix (mirror of the filter-persistence shape).
   :rf.xray.frame-switcher/persist
   ;; rf2-ikuwt — per-event-id mute filter persistence side-effect.
   ;; Bound to mute / unmute / clear handlers; writes the post-mutation
   ;; set to localStorage in one place (mirrors the filter-persistence
   ;; shape above).
   :rf.xray.spine-filters/persist
   ;; rf2-3d987 issue #4 — chart-collapsed persistence side-effect.
   ;; (rf2-48fwsi retired the sibling persist-view-mode fx with the
   ;; dead Canvas/List toggle.)
   :rf.xray.machine-canvas/persist-chart-collapsed
   ;; rf2-wm7z4 — palette pop-out side-effect. Lives under the
   ;; palette-specific prefix because it wraps a mount-layer pop-out
   ;; call that no other Xray surface invokes.
   :rf.xray.palette.fx/popout
   ;; rf2-ybjkx — palette snapshot-app-db side-effect. Drops the
   ;; focused frame's app-db onto the JS console + clipboard so the
   ;; user can share a session state.
   :rf.xray.palette.fx/snapshot-app-db
   ;; rf2-ybjkx — palette recents localStorage round-trip. Bound to
   ;; every `:command` invocation so the persisted list is always
   ;; current.
   :rf.xray.palette.fx/persist-recents
   ;; rf2-o5f5f.1 — Dynamic ↔ Static mode persistence side-effect.
   ;; Bound to the `:rf.xray/set-mode` + `:rf.xray/toggle-mode`
   ;; handlers; writes the post-mutation mode to localStorage in one
   ;; place (mirrors the filter-persistence shape above).
   :rf.xray.static/persist-mode
   ;; rf2-o5f5f.2 — Static Machines sub-tab persistence side-effects.
   ;; Bound to the `:rf.xray.static.machines/select` +
   ;; `:rf.xray.static.machines/set-sub-mode` handlers; mirrors the
   ;; mode-persist shape above (one fx per slot so future surfaces
   ;; can compose against them without re-implementing the LS round-
   ;; trip).
   :rf.xray.static.machines/persist-selection
   :rf.xray.static.machines/persist-sub-mode
   ;; rf2-g5q8d — cross-panel open-in-editor side-effect. Lives under
   ;; the editor-generic `:rf.editor/*` prefix rather than `:rf.xray.fx/*`
   ;; because the rf2-cm93v allowlist seam is editor-related, not
   ;; Xray-specific.
   :rf.editor/open))

;; ---- test-only override seam snapshot (rf2-e8330v / xxo3zz F3) ----------
;;
;; Production `register-xray-handlers!` installs NONE of these — the
;; override seam is split out per panel and installed via
;; `xray-test-support/install-test-overrides!`. The snapshot tests above
;; (`registry-snapshot-matches-expected-set`) assert production carries
;; no `-for-test` ids; the seam-snapshot test below asserts the seam
;; installs exactly this set.

(def ^:private test-override-sub-names
  "Every `*-override` sub the per-panel test seam re-registers."
  (sorted-set
   :rf.xray/registered-routes-override
   :rf.xray/current-route-slice-override
   :rf.xray/registered-resources-override
   :rf.xray/registered-scope-resolvers-override
   :rf.xray/resource-entries-override
   :rf.xray/resource-work-ledger-override
   :rf.xray/resource-sub-reads-override
   :rf.xray/resource-routing-slice-override
   :rf.xray/derivation-graph-override
   :rf.xray/machine-snapshots-override
   :rf.xray/machine-definitions-override
   :rf.xray.static.schemas/registry-override
   :rf.xray.static.flows/registered-flows-override
   :rf.xray.static.interceptors/registry-override))

(def ^:private test-override-event-names
  "Every `set-*-override-for-test` / `*-for-test` seeding event the
  per-panel test seam installs."
  (sorted-set
   :rf.xray/set-registered-routes-override-for-test
   :rf.xray/set-current-route-slice-override-for-test
   :rf.xray/set-registered-resources-override-for-test
   :rf.xray/set-registered-scope-resolvers-override-for-test
   :rf.xray/set-resource-entries-override-for-test
   :rf.xray/set-resource-work-ledger-override-for-test
   :rf.xray/set-resource-sub-reads-override-for-test
   :rf.xray/set-resource-routing-slice-override-for-test
   :rf.xray/set-derivation-graph-override-for-test
   :rf.xray/set-now-ms-override-for-test
   :rf.xray/set-registered-machines-override-for-test
   :rf.xray/set-machine-snapshots-override-for-test
   :rf.xray/set-machine-definitions-override-for-test
   :rf.xray/set-epoch-history-for-test
   :rf.xray/set-focus-epoch-id-for-test
   :rf.xray.static.schemas/set-registry-override-for-test
   :rf.xray.static.flows/set-registered-flows-override-for-test
   :rf.xray.static.interceptors/set-registry-override-for-test))

;; ---- (1) smoke: every registered name resolves -------------------------

(deftest registry-installs-every-sub
  (testing "register-xray-handlers! resolves every :rf.xray/* sub"
    (registry/register-xray-handlers!)
    (doseq [sub-id all-sub-names]
      (is (some? (registrar/handler :sub sub-id))
          (str "expected :sub handler for " sub-id)))))

(deftest registry-installs-every-event
  (testing "register-xray-handlers! resolves every :rf.xray/* event"
    (registry/register-xray-handlers!)
    (doseq [event-id all-event-names]
      (is (some? (registrar/handler :event event-id))
          (str "expected :event handler for " event-id)))))

(deftest registry-registers-each-xray-event-once
  (testing "each Xray event id `register-xray-handlers!` registers
            is registered exactly once during install. Scoped to the
            orchestrator's surface — data-inspector + other per-ns
            registrations that run at ns-load (NOT via the orchestrator)
            are excluded; the snapshot test covers the full surface."
    (let [registered        (atom [])
          original-register! registrar/register!]
      (with-redefs [registrar/register!
                    (fn [kind id metadata]
                      (when (and (= :event kind) (xray-id? id))
                        (swap! registered conj id))
                      (original-register! kind id metadata))]
        (registry/reset-for-test!)
        (registry/register-xray-handlers!))
      (let [freqs      (frequencies @registered)
            duplicates (into {}
                             (filter (fn [[_id n]] (> n 1)))
                             freqs)]
        ;; The orchestrator surface must be a subset of the full
        ;; snapshot — every event the orchestrator registers appears
        ;; in `all-event-names`.
        (is (every? all-event-names @registered)
            (str "orchestrator registered events not in snapshot: "
                 (remove all-event-names @registered)))
        (is (= {} duplicates)
            (str "duplicate event registrations: " duplicates))))))

(deftest registry-installs-every-fx
  (testing "register-xray-handlers! resolves every :rf.xray.fx/* fx"
    (registry/register-xray-handlers!)
    (doseq [fx-id all-fx-names]
      (is (some? (registrar/handler :fx fx-id))
          (str "expected :fx handler for " fx-id)))))

(deftest registry-snapshot-matches-expected-set
  (testing "registry's actual Xray-namespaced registrations match the
            expected sorted-set snapshots (rf2-39n8h). Set equality
            yields a precise drift message: clojure.test's default
            failure on `(= expected actual)` for sets names exactly the
            ids that differ, so concurrent PRs adding/removing subs or
            events rebase cleanly on a sorted-set literal rather than
            fighting a count-drift war on a single integer.

            Per the bead description: when two concurrent PRs both add
            one sub, each PR adds its sub-id to the expected set; the
            rebase shows a clean diff at the level of distinct sorted-
            set entries. No `(is (= N (count ...)))` line for the second
            PR to update."
    (registry/register-xray-handlers!)
    (let [actual-subs   (->> (registrar/registrations :sub)
                             keys
                             (filter xray-id?)
                             set)
          actual-events (->> (registrar/registrations :event)
                             keys
                             (filter xray-id?)
                             set)
          actual-fxs    (->> (registrar/registrations :fx)
                             keys
                             (filter xray-id?)
                             set)]
      (is (= all-sub-names actual-subs)
          "Sub registry drift — diff names the added/removed :rf.xray/* sub-ids")
      (is (= all-event-names actual-events)
          "Event registry drift — diff names the added/removed :rf.xray/* event-ids")
      (is (= all-fx-names actual-fxs)
          "Fx registry drift — diff names the added/removed :rf.xray/* fx-ids"))))

(deftest production-registration-installs-no-for-test-ids
  (testing "rf2-e8330v (xxo3zz F3) — register-xray-handlers! installs NO id
            ending in -for-test, and none of the `*-override` reader subs"
    (registry/register-xray-handlers!)
    (let [actual-events (->> (registrar/registrations :event) keys (filter xray-id?))
          actual-subs   (->> (registrar/registrations :sub) keys (filter xray-id?))
          for-test      (filter #(re-find #"-for-test$" (name %)) actual-events)
          override-subs (filter #(re-find #"-override$" (name %)) actual-subs)]
      (is (empty? for-test)
          (str "production registration leaked -for-test events: " for-test))
      (is (empty? override-subs)
          (str "production registration leaked *-override subs: " override-subs)))))

(deftest test-seam-installs-exactly-the-override-surface
  (testing "rf2-e8330v — install-test-overrides! installs exactly the
            test-override sub + event snapshot on top of production"
    (registry/register-xray-handlers!)
    (xray-test-support/install-test-overrides!)
    (let [actual-events     (->> (registrar/registrations :event) keys (filter xray-id?) set)
          actual-subs       (->> (registrar/registrations :sub) keys (filter xray-id?) set)
          seam-events       (set (filter #(re-find #"-for-test$" (name %)) actual-events))
          seam-override-subs (set (filter #(re-find #"-override$" (name %)) actual-subs))]
      (is (= test-override-event-names seam-events)
          "test-override event drift — diff names the added/removed -for-test ids")
      (is (= test-override-sub-names seam-override-subs)
          "test-override sub drift — diff names the added/removed *-override subs"))))

(deftest registry-is-idempotent
  (testing "calling register-xray-handlers! twice is a no-op (same handler instance)"
    (registry/register-xray-handlers!)
    (let [h1 (registrar/handler :sub :rf.xray/target-frame)
          e1 (registrar/handler :event :rf.xray/select-tab)
          f1 (registrar/handler :fx :rf.xray.fx/copy-to-clipboard)]
      (registry/register-xray-handlers!)
      (is (identical? h1 (registrar/handler :sub :rf.xray/target-frame)))
      (is (identical? e1 (registrar/handler :event :rf.xray/select-tab)))
      (is (identical? f1 (registrar/handler :fx :rf.xray.fx/copy-to-clipboard))))))

(deftest registers-every-canonical-rf-xray-sub
  ;; Holistic subscribe-side smoke. The handler-resolution smokes above
  ;; assert that the registrar holds a handler for each id; this test
  ;; takes the subscriber's view — `(rf/subscribe [q-v])` returns a
  ;; non-nil reaction for every canonical sub-id once the registrar has
  ;; run. The two smokes catch different drift: handler-resolution
  ;; catches a missing registration; subscribe-resolution catches a sub
  ;; whose subscribe path throws (e.g. a downstream `install!` that
  ;; depends on a not-yet-registered upstream).
  (testing "every :rf.xray/* sub-id resolves through rf/subscribe after
            register-xray-handlers! runs (panel migrations should not
            silently drop a registration from the orchestrator)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (doseq [q-v all-sub-names]
        (is (some? (rf/subscribe [q-v]))
            (str q-v " must resolve through rf/subscribe after
                 register-xray-handlers!"))))))

;; ---- Machine tab fit-on-entry signal (rf2-6tw7t) ------------------------
;;
;; Entering / activating the Machine tab must auto fit-to-view the
;; topology. The mechanism: `:rf.xray/select-tab :machines` (and the
;; Static `:rf.xray.static/select-tab :machines`) bump a monotonic
;; counter at `:machine-tab-activations`; the `:rf.xray/machine-tab-fit-
;; signal` sub surfaces it; the Machine panel forwards the value as
;; `MachineChart`'s `:fit-signal`, which re-fits the viewport on every
;; CHANGE (the chart-side gate is pinned in machines-viz
;; `auto-fit-view-cljs-test`). These pins guard the host-side wiring: the
;; activation path bumps the signal, NON-machine activations don't, and
;; the sub reads it back.

(deftest machine-tab-fit-signal-default-zero
  (testing "rf2-6tw7t — a fresh :rf/xray frame reports fit-signal 0 (no
            activation yet). The chart's `::unfit` sentinel start means
            the FIRST activation (→ 1) is still a change, so the initial
            entry frames the graph."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= 0 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "no Machine-tab activation yet → 0"))))

(deftest machine-tab-fit-signal-bumps-on-dynamic-activation
  (testing "rf2-6tw7t — `:rf.xray/select-tab :machines` bumps the fit-
            signal so the Machine panel re-fits on entry; re-clicking the
            already-active Machine tab still bumps it (re-entry re-frames
            even without a tab change), and the counter increases
            monotonically per activation."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "first Machine-tab activation → 1")
      ;; Re-clicking the active Machine tab re-frames (fresh nonce).
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 2 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "re-activating the same tab still bumps the signal")
      (is (= :machines @(rf/subscribe [:rf.xray/selected-tab]))
          "the tab selection itself is unchanged"))))

(deftest machine-tab-fit-signal-steady-on-non-machine-tabs
  (testing "rf2-6tw7t — activating a NON-Machine tab must NOT bump the
            fit-signal (only Machine-tab entry re-frames the topology).
            A round-trip away and back bumps exactly once on the return."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal])))
      ;; Leave the Machine tab — no bump.
      (rf/dispatch-sync [:rf.xray/select-tab :epoch])
      (rf/dispatch-sync [:rf.xray/select-tab :app-db])
      (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "non-Machine tabs leave the signal steady")
      ;; Re-enter the Machine tab — exactly one bump.
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 2 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "re-entering the Machine tab re-frames (one bump)"))))

(deftest machine-tab-fit-signal-bumps-on-static-activation
  (testing "rf2-6tw7t — Static shares the `:machines` tab id +
            `machine-canvas/Chart`, so `:rf.xray.static/select-tab
            :machines` bumps the SAME fit-signal counter the Dynamic
            select-tab does. A non-machine Static tab activation does
            not bump it."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.static/select-tab :machines])
      (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "Static Machine-tab activation bumps the shared counter")
      (rf/dispatch-sync [:rf.xray.static/select-tab :routes])
      (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal]))
          "a non-machine Static tab does not bump the fit-signal"))))

;; ---- (2) high-value sub contracts: defaults on a fresh frame ------------

(deftest sub-trace-buffer-empty-by-default
  (testing "a fresh :rf/xray frame with an empty `:trace-buffer` slot
            yields `[]` from the `:rf.xray/trace-buffer` sub. Per
            rf2-43koh — the sub reads directly from the app-db slot
            (no atom fall-through; the slot is populated by the
            microtask-coalesced `:rf.xray/sync-trace-buffer` dispatch
            from `trace-collector/refresh-trace-rings!`)."
    (setup-xray-frame!)
    (trace-collector/reset-for-test!)
    (rf/with-frame :rf/xray
      (is (= [] @(rf/subscribe [:rf.xray/trace-buffer]))
          "empty rings + empty slot → sub returns []"))))

(deftest sub-trace-buffer-reads-from-app-db-slot
  (testing "Per rf2-43koh — the sub reads off the `:trace-buffer` slot
            in Xray's app-db, populated by the
            `:rf.xray/sync-trace-buffer` dispatch carrying the snapshot
            from `trace-collector/refresh-trace-rings!`."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [seed [{:id 1 :op-type :rf.event :operation :rf.test/a :tags {}}
                  {:id 2 :op-type :rf.event :operation :rf.test/b :tags {}}]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer seed])
        (let [buf @(rf/subscribe [:rf.xray/trace-buffer])]
          (is (= 2 (count buf))
              "snapshot lands in slot; sub reads it back")
          (is (= [1 2] (mapv :id buf))
              "events are oldest-first, matching the snapshot sort order"))))))

(deftest sub-trace-buffer-clear-event-drops-mirror-slot
  (testing "Per rf2-43koh — `:rf.xray/clear-trace-buffer` (dispatched
            from `trace-collector/retroactive-scrub!` on privacy
            toggle-off / the Settings clear affordance) drops the
            mirrored slot in lockstep with the rings reset."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                         [{:id 1 :op-type :rf.event :operation :rf.test/x :tags {}}]])
      (is (= 1 (count @(rf/subscribe [:rf.xray/trace-buffer]))))
      (rf/dispatch-sync [:rf.xray/clear-trace-buffer])
      (is (= [] @(rf/subscribe [:rf.xray/trace-buffer]))
          "clear-trace-buffer drops the slot"))))

(deftest sub-trace-buffer-sync-event-overwrites-slot
  (testing "Per rf2-43koh — `:rf.xray/sync-trace-buffer` overwrites the
            slot wholesale; used by `mount.cljs/open!` to seed at first
            paint and by `trace-collector/refresh-trace-rings!` on every
            microtask drain."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [seed [{:id 100 :op-type :rf.event :operation :rf.test/seeded :tags {}}
                  {:id 101 :op-type :rf.event :operation :rf.test/seeded :tags {}}]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer seed])
        (is (= seed @(rf/subscribe [:rf.xray/trace-buffer]))
            "seed lands wholesale in the slot")
        ;; Overwrite with a smaller seed — wholesale replace, not merge.
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer [{:id 200 :tags {}}]])
        (is (= [{:id 200 :tags {}}] @(rf/subscribe [:rf.xray/trace-buffer]))
            "second sync wholly replaces the slot (no merge)")))))

(deftest sub-trace-buffer-frameless-ring-overflow
  (testing "frameless emits (no `:frame` / no `:dispatch-id`) land in
            Xray's secondary ring per the rf2-3g9nw D2=a ruling. The
            ring's depth caps the secondary capture independent of the
            framework's per-frame ring depth."
    (setup-xray-frame!)
    (trace-collector/set-frameless-ring-depth! 3)
    (try
      (dotimes [i 5]
        (trace-collector/seed-trace-for-test!
          {:id i :op-type :rf.event :operation :rf.test/x :tags {}}))
      (let [buf (trace-collector/buffer-for-test)]
        (is (= 3 (count buf))
            "depth=3 caps the frameless ring at 3 entries")
        (is (= [2 3 4] (mapv :id buf))
            "oldest entries evicted; newest retained in oldest-first order"))
      (finally
        (trace-collector/set-frameless-ring-depth!
          trace-collector/default-frameless-ring-depth)))))

;; ---- :rf.xray/event-bundles — xray-internal filter (rf2-g1pt8) ------------

(defn- mk-cascade
  "Build a minimal cascade record for the data-layer filter test —
  matches the shape `re-frame.trace.projection/group-by-event`
  returns (`:dispatch-id` + `:event` vector)."
  [dispatch-id event-vec]
  {:dispatch-id dispatch-id
   :event       event-vec
   :other       []})

(defn- seed-buffer-with-dispatched-events!
  "Push synthetic `:rf.event/dispatched` trace events into Xray's trace
  buffer so `group-by-event` produces one cascade per event. Each
  trace event needs `:operation :rf.event/dispatched`, `:op-type :rf.event`,
  a unique `:dispatch-id` (cascade-grouping key), and the event vector
  under `:tags :event`."
  [events]
  (doseq [{:keys [dispatch-id event-vec]} events]
    (trace-collector/seed-trace-for-test!
      {:operation   :rf.event/dispatched
       :op-type     :rf.event
       :id          dispatch-id
       :time        (* dispatch-id 1000)
       :tags        {:rf.trace/dispatch-id dispatch-id
                     :rf.event/v       event-vec
                     :rf.trace/event-id    (first event-vec)
                     :frame       :rf/default}})))

(deftest sub-cascades-filters-xray-internal-events
  (testing "per rf2-g1pt8 — `:rf.xray/event-bundles` hard-filters cascades
            whose event-id is in the `rf.xray` namespace at the
            data-layer so every downstream consumer (filtered-event-bundles,
            L2 event list, spine, popovers, all tabs) inherits the
            filter automatically. Synthetic dispatched-event mix: 2
            user-app + 3 Xray-internal → sub returns only the 2
            user-app cascades."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item {:item-id "apple"}]}
         {:dispatch-id 2 :event-vec [:rf.xray/focus-event 99]}
         {:dispatch-id 3 :event-vec [:checkout/start]}
         {:dispatch-id 4 :event-vec [:rf.xray/select-tab :event]}
         {:dispatch-id 5 :event-vec [:rf.xray/open-settings]}])
      (let [cascades @(rf/subscribe [:rf.xray/event-bundles])
            event-ids (mapv #(first (:event %)) cascades)]
        (is (= 2 (count cascades))
            "the 3 :rf.xray/* cascades are filtered; the 2 user-app cascades survive")
        (is (= [:cart/add-item :checkout/start] event-ids)
            "the surviving cascades carry the user-app event-ids in oldest-first order")
        (is (every? (complement self-noise/xray-internal-event-bundle?) cascades)
            "no Xray-internal cascade leaks past the data-layer filter")))))

(deftest sub-cascades-filter-also-applies-to-filtered-event-bundles
  (testing "per rf2-g1pt8 — because `:rf.xray/filtered-event-bundles`
            composes against `:rf.xray/event-bundles`, the data-layer
            filter propagates automatically. Synthetic Xray-internal
            cascades are gone before the auto-filter facade even sees
            them, so the L2 list (which subscribes to filtered-
            cascades) shows only user-app rows."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item]}
         {:dispatch-id 2 :event-vec [:rf.xray/select-tab :machines]}
         {:dispatch-id 3 :event-vec [:checkout/start]}])
      (let [filtered @(rf/subscribe [:rf.xray/filtered-event-bundles])
            event-ids (mapv #(first (:event %)) filtered)]
        (is (= [:cart/add-item :checkout/start] event-ids)
            "the :rf.xray/select-tab cascade is filtered out at the data-layer")))))

(deftest sub-cascades-pure-user-app-pass-through
  (testing "per rf2-g1pt8 — a buffer with only user-app cascades is
            untouched by the data-layer filter (count + ordering
            preserved). Symmetry guard: the filter is narrow, not a
            blanket rejection."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-buffer-with-dispatched-events!
        [{:dispatch-id 1 :event-vec [:cart/add-item {:item-id "apple"}]}
         {:dispatch-id 2 :event-vec [:cart/remove-item {:item-id "apple"}]}
         {:dispatch-id 3 :event-vec [:checkout/start]}])
      (let [cascades @(rf/subscribe [:rf.xray/event-bundles])]
        (is (= 3 (count cascades))
            "user-app-only buffer is untouched by the filter")
        (is (= [:cart/add-item :cart/remove-item :checkout/start]
               (mapv #(first (:event %)) cascades)))))))

(deftest sub-suppressed-sensitive-count-reads-app-db
  (testing ":rf.xray/suppressed-sensitive-count reads from Xray's
            app-db at `:suppressed-counters` (rf2-0vxdn) — first deref
            returns 0; each `:rf.xray/note-sensitive-suppressed`
            dispatch re-fires the sub on the standard write path
            (immediate reactive update, no clear-sub-cache!
            workaround required)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= 0 @(rf/subscribe [:rf.xray/suppressed-sensitive-count]))
          "empty :suppressed-counters slot → total of 0")
      (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed :rf/default])
      (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed :rf/default])
      (is (= 2 @(rf/subscribe [:rf.xray/suppressed-sensitive-count]))
          "two bumps via dispatch → sub returns 2 immediately")
      (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed :rf/xray])
      (is (= 3 @(rf/subscribe [:rf.xray/suppressed-sensitive-count]))
          "different frame bucket bumps the same total")
      (rf/dispatch-sync [:rf.xray/reset-suppressed-counters])
      (is (= 0 @(rf/subscribe [:rf.xray/suppressed-sensitive-count]))
          "reset event drops every bucket"))))

(deftest sub-target-frame-defaults-to-unselected
  (testing "EP-0002 (rf2-bd4div) — :rf.xray/target-frame defaults to
            `default-target-frame` = nil = UNSELECTED (not :rf/default)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= registry/default-target-frame
             @(rf/subscribe [:rf.xray/target-frame])))
      (is (nil? @(rf/subscribe [:rf.xray/target-frame]))
          "the unselected default is nil"))))

(deftest sub-epoch-history-defaults-empty
  (testing ":rf.xray/epoch-history defaults to []"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= [] @(rf/subscribe [:rf.xray/epoch-history]))))))

;; rf2-e9tb0 — :rf.xray/pinned-slices-store sub was dropped when the
;; pinned-watches strip was superseded by the segment-inspector popup.

(deftest sub-segment-inspector-defaults-closed
  (testing "rf2-e9tb0 — :rf.xray/segment-inspector-open? defaults to
            false on a fresh frame; :rf.xray/segment-inspector-path
            defaults to nil"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (is (nil? @(rf/subscribe [:rf.xray/segment-inspector-path]))))))

;; rf2-ad7zx.9 — `sub-issues-filters-default-disabled` was removed with
;; the Issues panel's filter-chrome reconcile to the Figma design (pure
;; rows, no filtering — spec/021 §8.2). The `:rf.xray/issues-filters`
;; sub no longer exists.

(deftest sub-reactive-show-unchanged-defaults-false
  (testing ":rf.xray/reactive-show-unchanged? defaults to false
            (rf2-wyvf2 — Reactive panel disclosure slot per spec/021
            §3.4; default OFF means the panel hides unchanged-subs
            behind a footer disclosure)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/reactive-show-unchanged?]))))))

;; ---- (3) high-value composite sub shapes --------------------------------

(deftest sub-focused-event-bundle-detail-shape-on-empty-buffer
  (testing ":rf.xray/focused-event-bundle-detail returns the canonical shape on an empty buffer"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/focused-event-bundle-detail])]
        (is (contains? data :event-bundles))
        (is (contains? data :selected-dispatch-id))
        (is (contains? data :selected-event-bundle))
        (is (= [] (:event-bundles data)))
        (is (nil? (:selected-dispatch-id data)))
        (is (nil? (:selected-event-bundle data)))))))

;; rf2-p53m2 — `sub-app-db-diff-shape-on-empty-history` was removed: the
;; `:rf.xray/app-db-diff` composite it exercised had no production view
;; consumer and was pruned. The app-db tab's empty-history shape is
;; pinned via `:rf.xray/app-db-current+diff` / `:rf.xray/app-db-state`
;; in app_db_diff_cljs_test.

(deftest sub-issues-ribbon-shape-on-empty-buffer
  (testing ":rf.xray/issues-ribbon returns :no-focus empty-kind when
            no focused epoch yet (rf2-jio48 — focused-epoch-scoped
            projection per spec/021 §1.2; cold-start surfaces :no-focus.
            rf2-gbz39 — the Issues tab was removed under Option (c), but
            this composite survives as the auto-open-on-error signal
            source + now lives in registry.cljs)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/issues-ribbon])]
        (is (contains? data :issues))
        (is (= 0 (:total data)))
        (is (= 0 (:rendered data)))
        (is (= :no-focus (:empty-kind data)))))))

(deftest sub-trace-feed-shape-on-empty-buffer
  (testing ":rf.xray/trace-feed returns :no-focus empty-kind initially
            (rf2-td380 — epoch-scoped: no focused epoch + empty history
            → :no-focus / 0 rows)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= 0 (:total data)))
        (is (= 0 (:rendered data)))
        (is (= :no-focus (:empty-kind data)))))))

(deftest sub-machine-inspector-data-shape-empty
  (testing ":rf.xray/machine-inspector-data returns :no-machines kind when
            the registered-machines override is forced to []"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; The framework's (rf/machines) registrar is process-global, so
      ;; on a node-test target where the machines artefact's own test
      ;; suite registered fixture machines, the live call surfaces a
      ;; non-empty vector. Pin the override to [] so the empty-state
      ;; contract is testable in isolation.
      (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test []])
      (let [data @(rf/subscribe [:rf.xray/machine-inspector-data])]
        (is (contains? data :machines))
        (is (= 0 (:total data)))
        (is (= :no-machines (:empty-kind data)))))))

(deftest sub-reactive-data-shape-empty
  (testing ":rf.xray/reactive-data returns empty defaults when no
            cascade is focused (rf2-wyvf2 · spec/021 §3 · renamed from
            :rf.xray/views-data per §11.5)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/reactive-data])]
        (is (contains? data :subs-ran))
        (is (contains? data :subs-skipped))
        (is (contains? data :views-rendered))
        (is (false? (:has-event-bundle? data)))))))

;; ---- (4) high-value event contracts -------------------------------------

(deftest event-select-dispatch-id-and-clear
  (testing ":rf.xray/select-dispatch-id + clear round-trip — rf2-ee38b.2
            reads focus off the canonical spine `:rf.xray/focus` sub
            (the dead standalone :rf.xray/selected-dispatch-id sub is
            gone; focus is the single source of truth)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 42])
      (is (= 42 (:dispatch-id @(rf/subscribe [:rf.xray/focus]))))
      (rf/dispatch-sync [:rf.xray/clear-selected-dispatch-id])
      (is (nil? (:dispatch-id @(rf/subscribe [:rf.xray/focus])))))))

(deftest event-select-epoch-passive-scrub
  (testing ":rf.xray/select-epoch pins the spine focus epoch (passive
            scrub) — rf2-uy7nz: the single source of truth is
            `[:focus :epoch-id]`, surfaced by `:rf.xray/focus-epoch-id`"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-epoch :e-7])
      (is (= :e-7 @(rf/subscribe [:rf.xray/focus-epoch-id])))
      ;; nil resets the focus epoch — equivalent to the dropped explicit
      ;; `:rf.xray/clear-selected-epoch` event (deleted with rf2-qy0nu
      ;; alongside the Time Travel panel).
      (rf/dispatch-sync [:rf.xray/select-epoch nil])
      (is (nil? @(rf/subscribe [:rf.xray/focus-epoch-id]))))))

;; rf2-ad7zx.9 — `event-toggle-issues-severity-roundtrip` and
;; `event-clear-issues-filters` were removed with the Issues panel's
;; filter-chrome reconcile to the Figma design (pure rows, no filtering
;; — spec/021 §8.2). The `:rf.xray.issues/toggle-severity` /
;; `toggle-prefix` / `clear-filters` events no longer exist.

(deftest event-reactive-toggle-unchanged-flips-slot
  (testing ":rf.xray/reactive-toggle-unchanged toggles the panel's
            disclosure slot (rf2-wyvf2 · spec/021 §3.4)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/reactive-show-unchanged?])))
      (rf/dispatch-sync [:rf.xray/reactive-toggle-unchanged])
      (is (true? @(rf/subscribe [:rf.xray/reactive-show-unchanged?])))
      (rf/dispatch-sync [:rf.xray/reactive-toggle-unchanged])
      (is (false? @(rf/subscribe [:rf.xray/reactive-show-unchanged?]))))))

(deftest event-reactive-set-unchanged-writes-slot
  (testing ":rf.xray/reactive-set-unchanged writes the slot directly
            (rf2-wyvf2)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/reactive-set-unchanged true])
      (is (true? @(rf/subscribe [:rf.xray/reactive-show-unchanged?])))
      (rf/dispatch-sync [:rf.xray/reactive-set-unchanged false])
      (is (false? @(rf/subscribe [:rf.xray/reactive-show-unchanged?]))))))

(deftest event-segment-inspector-open-and-close
  (testing "rf2-e9tb0 — :rf.xray/open-segment-inspector writes the
            requested path into Xray's frame; close drops it. The
            popup is then visible / invisible via the open? sub."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:users :count]])
      (is (true? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (is (= [:users :count]
             @(rf/subscribe [:rf.xray/segment-inspector-path])))
      (rf/dispatch-sync [:rf.xray/close-segment-inspector])
      (is (false? @(rf/subscribe [:rf.xray/segment-inspector-open?])))
      (is (nil? @(rf/subscribe [:rf.xray/segment-inspector-path]))))))

(deftest event-focus-slice-path-and-clear
  (testing ":rf.xray/focus-slice-path + clear-slice-focus round-trip"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-slice-path [:a]])
      (is (= [:a] @(rf/subscribe [:rf.xray/focused-slice-path])))
      (rf/dispatch-sync [:rf.xray/clear-slice-focus])
      (is (nil? @(rf/subscribe [:rf.xray/focused-slice-path]))))))

(deftest event-select-machine-id-and-clear
  (testing ":rf.xray/select-machine-id + clear round-trip"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-machine-id :traffic-light])
      (is (= :traffic-light @(rf/subscribe [:rf.xray/selected-machine-id])))
      (rf/dispatch-sync [:rf.xray/clear-machine-selection])
      (is (nil? @(rf/subscribe [:rf.xray/selected-machine-id]))))))

(deftest event-open-in-editor-routes-through-editor-fx
  (testing "rf2-g5q8d — `:rf.xray/open-in-editor` is now a reg-event handler
            that returns `:fx` — it resolves the coord through the rf2-cm93v allowlist and
            fires `:rf.editor/open`. It does NOT write to app-db (the
            click is pure navigation; the prior stub's
            `:last-open-in-editor-coord` slot is gone). Detailed
            contract assertions live in `open_in_editor_cljs_test.cljs`;
            here we pin the registry-level shape only."
    (setup-xray-frame!)
    ;; rf2-4s08ov — model a wired host: explicitly set an editor so the
    ;; click navigates (fires `:rf.editor/open`) rather than surfacing
    ;; the unconfigured-host DX hint. The unconfigured-host branch is
    ;; covered in `open_in_editor_cljs_test.cljs`.
    (config/set-editor! :vscode)
    (let [captured (atom [])]
      ;; Replace the open fx with a capture stub (same pattern as the
      ;; time-travel reg-fx tests above).
      (rf/reg-fx :rf.editor/open
        (fn [_ctx args] (swap! captured conj args)))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-in-editor
                           {:file "src/x.cljs" :line 10 :column 5}])
        (is (= 1 (count @captured))
            "the event-fx emits exactly one :rf.editor/open fx")
        (is (= {:file "src/x.cljs" :line 10 :column 5}
               (:source-coord (first @captured)))
            "rf2-wn3bh — the fx carries the structured :source-coord so
             :rf.editor/open can prefer the dev-server endpoint and fall
             back to the editor:// URI")
        (is (nil? (:last-open-in-editor-coord
                    (frame/frame-app-db-value :rf/xray)))
            "Xray's app-db is NOT written — the stub's
             `:last-open-in-editor-coord` slot is intentionally
             gone (rf2-g5q8d)")))))

;; ---- (5) test-only override events --------------------------------------

(deftest event-override-events-set-then-clear
  (testing "every :set-*-override-for-test event sets a value AND clears on nil"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; rf2-qy0nu — only the Machine Inspector's registered-machines
      ;; override survives the dead-panel sweep. The flows / fxs /
      ;; routes overrides were retired with their panels.
      (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test [:m]])
      (is (= [:m] (:registered-machines-override (frame/frame-app-db-value :rf/xray))))
      (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test nil])
      (is (nil? (:registered-machines-override (frame/frame-app-db-value :rf/xray)))))))

;; ---- (6) reg-fx contracts -----------------------------------------------
;;
;; The three :rf.xray.fx/* handlers each follow re-frame v2's
;; `(fn [ctx args] ...)` signature. We replace each registered fx with
;; a capture stub (same pattern as time_travel_cljs_test.cljs) and
;; dispatch the corresponding event-fx so the captured args round-trip.

(defonce ^:private captured-fx (atom []))

(defn- install-capture-fx! []
  (reset! captured-fx [])
  (rf/reg-fx :rf.xray.fx/copy-to-clipboard
    (fn [_ctx args] (swap! captured-fx conj [:rf.xray.fx/copy-to-clipboard args]))))

(deftest fx-copy-value-to-clipboard-fires-with-pr-str
  (testing ":rf.xray/copy-value-to-clipboard routes through the clipboard fx"
    (setup-xray-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard {:a 1}]))
    (is (= 1 (count @captured-fx)))
    (let [[fx-id args] (first @captured-fx)]
      (is (= :rf.xray.fx/copy-to-clipboard fx-id))
      (is (= "{:a 1}" (:text args))
          "the value is pr-str'd before reaching the fx"))))

(deftest fx-copy-path-to-clipboard-fires-with-pr-str
  (testing ":rf.xray/copy-path-to-clipboard routes through the clipboard fx"
    (setup-xray-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/copy-path-to-clipboard [:users :count]]))
    (is (= 1 (count @captured-fx)))
    (let [[fx-id args] (first @captured-fx)]
      (is (= :rf.xray.fx/copy-to-clipboard fx-id))
      (is (= "[:users :count]" (:text args))))))

(deftest fx-copy-to-clipboard-handles-non-browser-target
  (testing ":rf.xray.fx/copy-to-clipboard does not throw on a node-test
            target (no js/navigator.clipboard); contract is best-effort"
    (setup-xray-frame!)
    ;; Re-register the LIVE handler (the registry's, not our capture).
    (registry/reset-for-test!)
    (registry/register-xray-handlers!)
    (rf/with-frame :rf/xray
      ;; Should not throw — the registry's reg-fx wraps the navigator
      ;; access in a try / catch :default _ nil.
      (is (nil? (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard :hi]))))))

;; ---- (7) override-aware reader semantics --------------------------------

;; The legacy :rf.xray/sub-cache sub + :rf.xray/set-sub-cache-
;; override-for-test event retired with the Subs panel under rf2-21ob3
;; (Views panel reads :rf.xray/epoch-history directly per spec/012-
;; Views.md §Data sources; no separate sub-cache surface).

(deftest sub-registered-machines-honours-override
  (testing ":rf.xray/registered-machines returns the override when set"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test
                         [:m-1 :m-2]])
      (is (= [:m-1 :m-2]
             @(rf/subscribe [:rf.xray/registered-machines]))))))

;; ---- (8) frame isolation (rf2-tijr Option C) ----------------------------

(deftest events-write-to-xray-frame-not-default
  (testing "every :rf.xray/* event-db handler writes to :rf/xray, never :rf/default"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; :event is an arbitrary live L3 tab id (rf2-xy4yb — the 4-layer
      ;; shell switches via `:rf.xray/selected-tab`, not the legacy
      ;; `:selected-panel` slot deleted with rf2-qy0nu).
      (rf/dispatch-sync [:rf.xray/select-tab :event])
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
      (rf/dispatch-sync [:rf.xray/select-epoch :e]))
    (let [xray-db   (frame/frame-app-db-value :rf/xray)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :event (:selected-tab xray-db)))
      ;; rf2-ee38b.2 — :rf.xray/select-dispatch-id writes focus to the
      ;; spine `:focus` slot (the dead :selected-dispatch-id mirror is
      ;; gone); rf2-uy7nz — :select-epoch writes the spine `[:focus
      ;; :epoch-id]` (the :selected-epoch-id mirror is retired too).
      (is (= 1 (get-in xray-db [:focus :dispatch-id])))
      (is (= :e (get-in xray-db [:focus :epoch-id])))
      (is (nil? (:selected-tab default-db)))
      (is (nil? (get-in default-db [:focus :dispatch-id])))
      (is (nil? (get-in default-db [:focus :epoch-id]))))))

;; ---- (9) edge cases over a dormant frame --------------------------------

(deftest composite-subs-non-throwing-on-empty-frame
  (testing "every composite sub returns SOMETHING (no throw) on a fresh
            :rf/xray frame — the smoke contract the audit named"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; Each `is` proves the subscribe + deref completes without throwing.
      ;; The contract is that the registry's composites tolerate empty
      ;; inputs; per-panel tests cover the populated cases.
      (doseq [sub-id [:rf.xray/focused-event-bundle-detail
                      ;; rf2-p53m2 — `:rf.xray/app-db-diff` pruned (dead
                      ;; surface); the app-db tab's composite is now
                      ;; `:rf.xray/app-db-current+diff` → `:rf.xray/app-db-state`.
                      :rf.xray/app-db-current+diff
                      ;; rf2-okvit — current-state inspector section model.
                      :rf.xray/app-db-state
                      :rf.xray/issues-ribbon
                      :rf.xray/trace-feed
                      :rf.xray/machine-inspector-data
                      ;; rf2-wyvf2 — Reactive-panel composite (was
                      ;; `:rf.xray/views-data`; renamed to `:rf.xray/
                      ;; reactive-data` per spec/021 §11.5 / §3).
                      ;; Empty-frame contract: returns map with
                      ;; `:has-event-bundle? false`.
                      :rf.xray/reactive-data]]
        (is (some? @(rf/subscribe [sub-id]))
            (str sub-id " must not throw on an empty frame"))))))

(deftest epoch-recorded-ignores-non-target-frames
  (testing ":rf.xray/epoch-recorded only writes when frame-id matches target-frame"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; A non-target frame-id is dropped — :epoch-history stays empty.
      ;; (We can't easily produce a real :rf/default epoch under
      ;; node-test without booting the epoch artefact, so we assert the
      ;; gate by passing a non-target frame and observing no write.)
      (rf/dispatch-sync [:rf.xray/epoch-recorded :rf/some-other-frame])
      (is (= [] @(rf/subscribe [:rf.xray/epoch-history]))))))
