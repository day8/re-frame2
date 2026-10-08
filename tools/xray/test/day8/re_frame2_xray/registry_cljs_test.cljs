(ns day8.re-frame2-xray.registry-cljs-test
  "CLJS tests for `day8.re-frame2-xray.registry`, the orchestrator that owns
  the cross-panel primitives and the per-panel `install!` fan-out. Per-panel
  suites cover their own subs and events; this file owns the registration
  surface (snapshots, hygiene, the schema-migration seam) and the
  cross-panel slots no single panel test owns."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as reactive-panel-subs]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (config/set-project-root! nil))}))

(defn- setup-xray-frame!
  "Register handlers, install the test-only override seam and allocate
  the :rf/xray frame."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

;; `focus/valid-panels` (the host-facing focus vocabulary) is a static
;; `.cljc` mirror of the LIVE Dynamic L4 tab registry; this fails the instant
;; the two drift.

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
  for the snapshot test. Covers both the bare `:rf.xray/*`
  prefix and the per-panel `:rf.xray.<panel>/*` prefixes codified in
  `tools/xray/spec/014-Registry-Catalogue.md` §Naming convention."
  [id]
  (and (keyword? id)
       (when-let [ns (namespace id)]
         (or (= "rf.xray" ns)
             (re-matches #"rf\.xray\..*" ns)))))

(def ^:private all-sub-names
  "Every Xray-namespaced sub `register-xray-handlers!` registers."
  (sorted-set
   :rf.xray/active-filters
   :rf.xray/active-timers-for-focused-machine
   :rf.xray/app-db-current+diff
   :rf.xray/app-db-state
   :rf.xray/event-bundles
   :rf.xray.column-widths/for-table
   :rf.xray.edn-inspector/expansion
   :rf.xray.edn-inspector/widths
   :rf.xray.edn-inspector/zoom
   :rf.xray.edn-inspector-popup/stack
   :rf.xray.edn-inspector-popup/entries
   :rf.xray.edn-inspector-popup/open?
   :rf.xray.edn-inspector-popup/top
   :rf.xray.edn-inspector-popup/entry
   :rf.xray/cancellation-cascade-expanded?
   :rf.xray/cancellation-cascade-for-focused-event
   :rf.xray/cancellation-cascade-for-focused-machine
   :rf.xray/cancellation-cascade-popover-focus
   :rf.xray/cancellation-cascade-popover-open?
   :rf.xray/diff-opts
   :rf.xray/edit-popup-draft
   :rf.xray/edit-popup-open?
   :rf.xray/edit-popup-trigger
   :rf.xray/editor-host-default
   :rf.xray/epoch-history
   :rf.xray/epoch-pipeline
   :rf.xray.epoch/expanded-rows
   :rf.xray/focused-epoch-record
   :rf.xray.epoch/parent-epoch-index
   :rf.xray.epoch/subs-filter-mode
   :rf.xray/focused-event-bundle-detail
   :rf.xray/filtered-event-bundles
   :rf.xray/filters-auto-hide-error-overrides?
   :rf.xray/hidden-by-filters
   :rf.xray/focus
   :rf.xray/focus-epoch-id
   :rf.xray/focus-slot
   :rf.xray/current-frame
   :rf.xray/available-frames
   :rf.xray/view-scope-frame
   :rf.xray/view-scope-frame-slot
   :rf.xray/target-frame-slot
   :rf.xray/issues-ribbon
   :rf.xray/machine-definitions
   :rf.xray/machine-inspector-data
   :rf.xray.machine-canvas/chart-collapsed-by-id
   :rf.xray.machine-canvas/chart-collapsed-for
   :rf.xray/machine-transitions-for-focused-event
   :rf.xray/machine-focused-epoch-cascade
   :rf.xray/machine-scrubber-position
   :rf.xray/machine-snapshots
   :rf.xray/managed-fx-for-focused-event
   :rf.xray/managed-fx-expanded-sections
   :rf.xray/now-ms
   :rf.xray/observed-frame
   :rf.xray/palette-active-item
   :rf.xray/palette-cursor
   :rf.xray/palette-index
   :rf.xray/palette-open?
   :rf.xray/palette-query
   :rf.xray/palette-recents
   :rf.xray/palette-results
   :rf.xray/registered-machines
   :rf.xray/registered-routes
   :rf.xray/current-route-slice
   :rf.xray/routing-tab-data
   :rf.xray/registered-resources
   :rf.xray/registered-scope-resolvers
   :rf.xray/resource-entries
   :rf.xray/resource-work-ledger
   :rf.xray/resource-routing-slice
   :rf.xray/resources-tab-data
   :rf.xray/derivation-graph
   :rf.xray/derivation-graph-mode
   :rf.xray/derivation-graph-tab-data
   :rf.xray/image-view
   :rf.xray.fresco/view
   :rf.xray.fresco/data
   :rf.xray.static.routes/query
   :rf.xray.static.routes/sim-url
   :rf.xray.static.routes/expanded
   :rf.xray.static.routes/sim-nav-open
   :rf.xray.static.routes/tab-data
   :rf.xray.static.schemas/query
   :rf.xray.static.schemas/registry
   :rf.xray.static.schemas/tab-data
   :rf.xray.static.flows/query
   :rf.xray.static.flows/registered-flows
   :rf.xray.static.flows/tab-data
   :rf.xray.static.interceptors/query
   :rf.xray.static.interceptors/registry
   :rf.xray.static.interceptors/tab-data
   :rf.xray/reset-flash
   :rf.xray/selected-epoch-record
   :rf.xray/selected-machine-id
   :rf.xray/modal-positioning
   :rf.xray/mute-manager-open?
   :rf.xray/muted-event-ids
   :rf.xray/muted-event-ids-count
   :rf.xray/row-context-menu
   :rf.xray/mode
   :rf.xray.static/selected-tab
   :rf.xray.static.machines/copy-mermaid-status
   :rf.xray.static.machines/data
   :rf.xray.static.machines/rows
   :rf.xray.static.machines/search
   :rf.xray.static.machines/selected-id
   :rf.xray.static.machines/sort-key
   :rf.xray.static.machines/sub-mode
   :rf.xray.static.machines/sub-mode-by-id
   :rf.xray/panel-width-px
   :rf.xray/event-list-col-widths
   :rf.xray/events-list-height-px
   :rf.xray/selected-tab
   :rf.xray/machine-tab-fit-signal
   :rf.xray/density
   :rf.xray/long-keyword-threshold
   :rf.xray/editor-hint-open?
   :rf.xray/setting
   :rf.xray/settings
   :rf.xray/settings-active-tab
   :rf.xray/settings-clear-confirm-open?
   :rf.xray/settings-open?
   :rf.xray/keybinding-enabled?
   :rf.xray/show-ungrouped?
   :rf.xray.static.machines/sim-active?
   :rf.xray.static.machines/sim-available-transitions
   :rf.xray.static.machines/sim-by-machine
   :rf.xray.static.machines/sim-event-suggestions
   :rf.xray.static.machines/sim-state
   :rf.xray.static.machines/sim-current-state
   :rf.xray.static.machines/sim-last-transition
   :rf.xray/suppressed-sensitive-count
   :rf.xray/target-frame
   :rf.xray/target-frame-db
   :rf.xray/target-frame-runtime-db
   :rf.xray/timer-hover
   :rf.xray/trace-buffer
   :rf.xray/trace-expanded-row-ids
   :rf.xray/trace-feed
   :rf.xray.trace/focused-event-bundle
   :rf.xray/reactive-data
   :rf.xray/reactive-show-unchanged?))

(def ^:private all-event-names
  "Every Xray-namespaced event `register-xray-handlers!` registers."
  (sorted-set
   :rf.xray/add-filter
   :rf.xray.fresco/set-view
   :rf.xray.column-widths/hydrate
   :rf.xray.column-widths/resize-pair-commit
   :rf.xray.column-widths/resize-pair-tick
   :rf.xray.column-widths/reset
   :rf.xray/cancellation-cascade-close
   :rf.xray/cancellation-cascade-open
   :rf.xray/cancellation-cascade-set-expanded
   :rf.xray/cancellation-cascade-toggle-expand
   :rf.xray/clear-machine-selection
   :rf.xray/clear-reset-flash
   :rf.xray/clear-selected-dispatch-id
   :rf.xray/clear-trace-buffer
   :rf.xray/clear-trace-expand
   :rf.xray/close-edit-popup
   :rf.xray/close-shell
   :rf.xray.edn-inspector/reset-expansion
   :rf.xray.edn-inspector/set-node
   :rf.xray.edn-inspector/toggle-node
   :rf.xray.edn-inspector/set-width
   :rf.xray.edn-inspector/clear-width
   :rf.xray.edn-inspector/zoom-to
   :rf.xray.edn-inspector/zoom-up
   :rf.xray.edn-inspector/zoom-reset
   :rf.xray.edn-inspector-popup/open
   :rf.xray.edn-inspector-popup/close
   :rf.xray.edn-inspector-popup/close-top
   :rf.xray.edn-inspector-popup/close-all
   :rf.xray/delete-edit-popup
   :rf.xray/edit-popup-set-mode
   :rf.xray/edit-popup-set-pattern
   :rf.xray/editor-hint-show
   :rf.xray/editor-hint-dismiss
   :rf.xray/editor-hint-open-settings
   :rf.xray.epoch/toggle-row-expand
   :rf.xray.epoch/clear-row-expand
   :rf.xray.epoch/set-subs-filter-mode
   :rf.xray/epoch-recorded
   :rf.xray/filter-by-fx
   :rf.xray/filter-by-http-correlation
   :rf.xray/filter-by-machine
   :rf.xray/focus-after-frame
   :rf.xray/focus-event
   :rf.xray/focus-event-next
   :rf.xray/focus-event-prev
   :rf.xray/focus-epoch
   :rf.xray/focus-trace-entry
   :rf.xray/follow-head
   :rf.xray/hide-event-type
   :rf.xray/hydrate-filters
   :rf.xray/clear-muted-event-ids
   :rf.xray/close-mute-manager
   :rf.xray/close-row-context-menu
   :rf.xray/hydrate-muted-event-ids
   :rf.xray/mute-event-id
   :rf.xray/open-mute-manager
   :rf.xray/open-row-context-menu
   :rf.xray/unmute-event-id
   :rf.xray/managed-fx-toggle-section
   :rf.xray/machine-chart-layout-pulse
   :rf.xray/machine-state-clicked
   :rf.xray.machine-canvas/hydrate-chart-collapsed
   :rf.xray.machine-canvas/set-chart-collapsed
   :rf.xray/note-sensitive-suppressed
   :rf.xray/open-edit-popup
   :rf.xray/open-in-editor
   :rf.xray/open-settings
   :rf.xray/palette-close
   :rf.xray/palette-cursor-down
   :rf.xray/palette-cursor-set
   :rf.xray/palette-cursor-up
   :rf.xray/palette-invoke
   :rf.xray/palette-open
   :rf.xray/palette-set-query
   :rf.xray/palette-toggle
   :rf.xray/popout-shell
   :rf.xray/preview-event
   :rf.xray/remove-filter
   :rf.xray/reset-event-list-col-width
   :rf.xray/reset-panel-width
   :rf.xray/reset-events-list-height
   :rf.xray/reset-suppressed-counters
   :rf.xray/reset-to-epoch
   :rf.xray/reset-flash-failed
   :rf.xray/save-edit-popup
   :rf.xray/select-dispatch-id
   :rf.xray/select-epoch
   :rf.xray/select-frame
   :rf.xray/select-machine-id
   :rf.xray/select-tab
   :rf.xray/set-mode
   :rf.xray/toggle-mode
   :rf.xray.static/select-tab
   :rf.xray.static.machines/clear-search
   :rf.xray.static.machines/copy-mermaid
   :rf.xray.static.machines/copy-mermaid-done
   :rf.xray.static.machines/cycle-sort
   :rf.xray.static.machines/hydrate
   :rf.xray.static.machines/open-chart-popout
   :rf.xray.static.machines/select
   :rf.xray.static.machines/set-search
   :rf.xray.static.machines/set-sub-mode
   :rf.xray.static.machines/state-clicked
   :rf.xray/set-frame
   :rf.xray/set-derivation-graph-mode
   :rf.xray.static.routes/set-query
   :rf.xray.static.routes/set-sim-url
   :rf.xray.static.routes/toggle-row
   :rf.xray.static.routes/toggle-sim-nav
   :rf.xray.static.routes/jump-to-dynamic
   :rf.xray.static.schemas/set-query
   :rf.xray.static.flows/set-query
   :rf.xray.static.interceptors/set-query
   :rf.xray/set-modal-positioning
   :rf.xray/set-event-list-col-width
   :rf.xray/set-panel-width-px
   :rf.xray/set-events-list-height-px
   :rf.xray/set-scrubber-position
   :rf.xray/machine-focus-prev
   :rf.xray/machine-focus-next
   :rf.xray/set-target-frame
   :rf.xray/settings-cancel-clear-buffer
   :rf.xray/settings-clear-buffer
   :rf.xray/settings-close
   :rf.xray/settings-confirm-clear-buffer
   :rf.xray/settings-open
   :rf.xray/settings-select-tab
   :rf.xray/settings-toggle
   :rf.xray/settings-update
   :rf.xray/keybinding-enabled-update
   :rf.xray.static.machines/sim-reset
   :rf.xray.static.machines/sim-set-pending-data
   :rf.xray.static.machines/sim-set-pending-event
   :rf.xray.static.machines/sim-start
   :rf.xray.static.machines/sim-step
   :rf.xray.static.machines/sim-stop
   :rf.xray.static.machines/sim-chart-edge-clicked
   :rf.xray/sync-epoch-history
   :rf.xray/sync-trace-buffer
   :rf.xray/timer-hover
   :rf.xray/timer-tick
   :rf.xray/toggle-live-pause
   :rf.xray/toggle-trace-row-expand
   :rf.xray/reactive-set-unchanged
   :rf.xray/reactive-toggle-unchanged))

(def ^:private all-fx-names
  "Every Xray-namespaced fx `register-xray-handlers!` registers."
  (sorted-set
   :rf.xray.fx/copy-to-clipboard
   :rf.xray.fx/restore-epoch
   :rf.xray.fx/hide-shell
   :rf.xray.fx/popout-shell
   :rf.xray.column-widths/persist
   :rf.xray.frame-switcher/persist
   :rf.xray.spine-filters/persist
   :rf.xray.machine-canvas/persist-chart-collapsed
   :rf.xray.palette.fx/popout
   :rf.xray.palette.fx/snapshot-app-db
   :rf.xray.palette.fx/persist-recents
   :rf.xray.static/persist-mode
   :rf.xray.static.machines/persist-selection
   :rf.xray.static.machines/persist-sub-mode
   :rf.xray.fx/open-in-editor))

;; ---- test-only override seam snapshot ----------------------------------
;;
;; Production registration installs none of these; the per-panel seam is
;; installed by `xray-test-support/install-test-overrides!`.

(def ^:private test-override-sub-names
  "Every `*-override` sub the per-panel test seam re-registers."
  (sorted-set
   :rf.xray/registered-routes-override
   :rf.xray/current-route-slice-override
   :rf.xray/registered-resources-override
   :rf.xray/registered-scope-resolvers-override
   :rf.xray/resource-entries-override
   :rf.xray/resource-work-ledger-override
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

;; ---- (0) load-time registrar hygiene -----------------------------------

(defn- expected-widget-ids
  "The widget's share of the maintained snapshot sets above."
  [snapshot]
  (set (filter #(= "rf.xray.edn-inspector" (namespace %)) snapshot)))

(deftest edn-inspector-widget-registers-through-the-orchestrator
  ;; A `reg-sub` / `reg-event` left at the top level of a required widget
  ;; namespace runs at ns-load, outside the preload's
  ;; `(when rf.interop/debug-enabled? …)` block, so it would mutate the
  ;; HOST's registrar in a goog.DEBUG=false bundle. Asserting absence before
  ;; install cannot discriminate here (the preload boots at ns-load under
  ;; test), so this captures what `register-xray-handlers!` itself writes.
  (let [written            (atom #{})
        original-register! rf.registrar/register!]
    (with-redefs [rf.registrar/register!
                  (fn [kind id metadata]
                    (when (and (#{:sub :event} kind)
                               (keyword? id)
                               (= "rf.xray.edn-inspector" (namespace id)))
                      (swap! written conj id))
                    (original-register! kind id metadata))]
      (registry/reset-for-test!)
      (registry/register-xray-handlers!))
    (is (= (into (expected-widget-ids all-sub-names)
                 (expected-widget-ids all-event-names))
           @written)
        "a documented :rf.xray.edn-inspector/* id was not written by
         `register-xray-handlers!`, so it is registered at ns-load: move it
         into the widget's `install!`")))

;; ---- (1) smoke: every registered name resolves -------------------------

(deftest registry-registers-each-xray-event-once
  ;; A duplicate registration silently replaces the first handler.
  (let [registered         (atom [])
        original-register! rf.registrar/register!]
    (with-redefs [rf.registrar/register!
                  (fn [kind id metadata]
                    (when (and (= :event kind) (xray-id? id))
                      (swap! registered conj id))
                    (original-register! kind id metadata))]
      (registry/reset-for-test!)
      (registry/register-xray-handlers!))
    (is (= {} (into {} (filter (fn [[_id n]] (> n 1))) (frequencies @registered)))
        "duplicate event registrations")))

(deftest registry-registers-no-resource-event
  ;; Observing pins no resource (Spec 016 §Active owners and causes; 024
  ;; §Read-only). The registrar may hold the Resources runtime's own events,
  ;; so this reads what Xray's install WRITES.
  (let [written            (atom #{})
        original-register! rf.registrar/register!]
    (with-redefs [rf.registrar/register!
                  (fn [kind id metadata]
                    (when (= :event kind) (swap! written conj id))
                    (original-register! kind id metadata))]
      (registry/reset-for-test!)
      (registry/register-xray-handlers!))
    (is (contains? @written :rf.xray/select-epoch)
        "control: the capture sees Xray's own event writes")
    (is (empty? (filter #(and (keyword? %) (= "rf.resource" (namespace %))) @written)))))

(deftest registry-snapshot-matches-expected-set
  ;; Set equality names exactly the ids that drifted.
  (registry/register-xray-handlers!)
  (let [actual (fn [kind] (->> (rf.registrar/registrations kind) keys (filter xray-id?) set))]
    (is (= all-sub-names (actual :sub)))
    (is (= all-event-names (actual :event)))
    (is (= all-fx-names (actual :fx)))))

(deftest production-registration-installs-no-for-test-ids
  (testing "register-xray-handlers! installs NO id
            ending in -for-test, and none of the `*-override` reader subs"
    (registry/register-xray-handlers!)
    (let [actual-events (->> (rf.registrar/registrations :event) keys (filter xray-id?))
          actual-subs   (->> (rf.registrar/registrations :sub) keys (filter xray-id?))
          for-test      (filter #(re-find #"-for-test$" (name %)) actual-events)
          override-subs (filter #(re-find #"-override$" (name %)) actual-subs)]
      (is (empty? for-test)
          (str "production registration leaked -for-test events: " for-test))
      (is (empty? override-subs)
          (str "production registration leaked *-override subs: " override-subs)))))

(deftest test-seam-installs-exactly-the-override-surface
  (testing "install-test-overrides! installs exactly the
            test-override sub + event snapshot on top of production"
    (registry/register-xray-handlers!)
    (xray-test-support/install-test-overrides!)
    (let [actual-events     (->> (rf.registrar/registrations :event) keys (filter xray-id?) set)
          actual-subs       (->> (rf.registrar/registrations :sub) keys (filter xray-id?) set)
          seam-events       (set (filter #(re-find #"-for-test$" (name %)) actual-events))
          seam-override-subs (set (filter #(re-find #"-override$" (name %)) actual-subs))]
      (is (= test-override-event-names seam-events)
          "test-override event drift — diff names the added/removed -for-test ids")
      (is (= test-override-sub-names seam-override-subs)
          "test-override sub drift — diff names the added/removed *-override subs"))))

(deftest registry-is-idempotent
  (registry/register-xray-handlers!)
  (let [h1 (rf.registrar/handler :sub :rf.xray/target-frame)]
    (registry/register-xray-handlers!)
    (is (identical? h1 (rf.registrar/handler :sub :rf.xray/target-frame)))))

;; ---- registration-schema seam: fresh-install atomicity -----------------
;;
;; A throw mid bulk-install rolls the `registered?` umbrella back and leaves
;; the schema unstamped, so the next call replays the whole install rather
;; than running a bridge-only migration over a partial one.

(deftest fresh-install-retryable-after-partial-failure-rf2-g2jf3
  ;; The runtime fixture restores a registrar snapshot that already holds the
  ;; handlers, so the observable is whether the bulk block RE-RUNS.
  (let [orig-install routing/install!
        calls        (atom 0)]
    (with-redefs [routing/install!
                  (fn []
                    (swap! calls inc)
                    (if (= 1 @calls)
                      (throw (ex-info "rf2-g2jf3 throw-once mid-list leaf" {}))
                      (orig-install)))]
      (is (thrown-with-msg? js/Error #"rf2-g2jf3 throw-once"
            (registry/register-xray-handlers!)))
      (registry/register-xray-handlers!)
      (is (= 2 @calls) "the second call replays the bulk install")
      (registry/register-xray-handlers!)
      (is (= 2 @calls) "the completed install no-ops on a further reload"))))

;; ---- registration-schema seam: changed handlers migrate too ------------
;;
;; A sub that gains inputs keeps its id, so the umbrella no-ops it; the
;; schema-3 migration re-runs the owning facade `install!` so a live process
;; picks up the four-input `:rf.xray/reactive-data`.

(defn- reactive-data-input-ids
  "The static `:inputs` sub-ids of `:rf.xray/reactive-data`, read off the
  live `sub-topology` (each `[query-id args]` reduced to its head). `[]` when
  the sub is unregistered."
  []
  (let [inputs (:inputs (get (rf.subs.tooling/sub-topology) :rf.xray/reactive-data))]
    (mapv #(if (vector? %) (first %) %) (or inputs []))))

(deftest changed-reactive-data-migrates-as-a-schema-delta-rf2-sa8j3
  (setup-xray-frame!)
  (reactive-panel-subs/install-legacy-reactive-data-sub-for-test!)
  (registry/simulate-registration-at-schema! 2)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/reactive-set-unchanged true]))
  (testing "PRECONDITION — the cached two-input predecessor ignores the axis"
    (is (= [:rf.xray/focus :rf.xray/epoch-history] (reactive-data-input-ids)))
    (is (false? (rf/with-frame :rf/xray
                  (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data]))))))
  ;; The live upgrade: `:after-load` re-runs register-xray-handlers!.
  (registry/register-xray-handlers!)
  (is (= [:rf.xray/focus :rf.xray/epoch-history
          :rf.xray/reactive-show-unchanged? :rf.xray/setting]
         (reactive-data-input-ids)))
  (is (true? (rf/with-frame :rf/xray
               (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data]))))
      "the stale two-input reaction was evicted"))

;; ---- schema 4: the donor cutover's live upgrade -------------------------
;;
;; A schema-3 process carries five donor-era registrar entries nothing would
;; ever remove, and possibly the donor `re-frame.ui.tool.evidence` projection
;; it still owns. The registrar half migrates live; the projection cannot be
;; released by this build (`re-frame.ui` is off the classpath), so a process
;; holding it is told to reload and is not stamped current. The ownership
;; marker key is spelled as a literal so a production-side rename fails here.

(def ^:private donor-ownership-marker-key
  "The literal `js/globalThis` key `viewcell_evidence.cljs` used, pinned
  independently of the production constant. See the block comment above."
  "__day8_re_frame2_xray_viewcell_evidence")

(def ^:private schema-3-donor-sub-ids
  [:rf.xray/viewcell-evidence
   :rf.xray/viewcell-evidence-version
   :rf.xray/view-evidence-sites
   :rf.xray/viewcell-evidence-ownership])

(def ^:private schema-3-donor-event-id
  :rf.xray/viewcell-evidence-ownership-changed)

(def ^:private schema-4-sub-ids
  "The three ids schema 4 ADDED and schema 6 REMOVED — the Views
  panel's reads over `re-frame.freehand.tool`. No current boot registers
  them and every behind process must be relieved of them, so the schema-4
  tests below assert their ABSENCE."
  [:rf.xray/mounted-views
   :rf.xray/mounted-views-schema
   :rf.xray/mounted-view-sites])

(defn- pose-schema-3-registry!
  "Turn the just-booted current registry into the REGISTRAR image a schema-3
  process carries: the three Freehand reads absent (schema 3 never had them)
  and the five donor-era ids present, with the ownership sub reading the
  app-db key its deleted event handler wrote. Poses the registrar only —
  `simulate-registration-at-schema!` poses the sentinels."
  []
  (run! #(rf/clear :sub %) schema-4-sub-ids)
  (rf/reg-event schema-3-donor-event-id
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ revision]]
      {:db (assoc db :viewcell-evidence-ownership-rev revision)}))
  (rf/reg-sub :rf.xray/viewcell-evidence-ownership
    (fn [db _query]
      (get db :viewcell-evidence-ownership-rev 0)))
  (rf/reg-sub :rf.xray/viewcell-evidence
    {:inputs [[:rf.xray/epoch-history] [:rf.xray/viewcell-evidence-ownership]]}
    (fn [[_history _rev] _query] []))
  (rf/reg-sub :rf.xray/viewcell-evidence-version
    {:inputs [[:rf.xray/epoch-history] [:rf.xray/viewcell-evidence-ownership]]}
    (fn [[_history _rev] _query] {:status :supported}))
  (rf/reg-sub :rf.xray/view-evidence-sites
    {:inputs [[:rf.xray/viewcell-evidence]]}
    (fn [[_rows] _query] {}))
  nil)

(defn- registered-ids
  "The subset of `ids` under `kind` the registrar currently resolves."
  [kind ids]
  (into #{} (filter #(some? (rf.registrar/handler kind %))) ids))

(defn- pose-donor-ownership-marker! []
  (gobj/set js/globalThis donor-ownership-marker-key
            (js-obj "owner" ":rf.xray/viewcell-evidence"
                    "installed" (.now js/Date))))

(deftest schema-4-migrates-the-registrar-half-of-the-donor-cutover-rf2-7gth0
  (setup-xray-frame!)
  (pose-schema-3-registry!)
  (registry/simulate-registration-at-schema! 3)
  (testing "PRECONDITION — the posed process is a schema-3 registrar"
    (is (= (set schema-3-donor-sub-ids) (registered-ids :sub schema-3-donor-sub-ids)))
    (is (some? (rf.registrar/handler :event schema-3-donor-event-id))))
  (registry/register-xray-handlers!)
  (testing "the donor-era ids go, the Freehand reads schema 6 removed never
            arrive, and the process is stamped current"
    (is (= #{} (registered-ids :sub (concat schema-3-donor-sub-ids schema-4-sub-ids))))
    (is (nil? (rf.registrar/handler :event schema-3-donor-event-id)))
    (is (= registry/schema-version (registry/installed-schema-version)))))

(deftest schema-4-refuses-to-stamp-a-donor-resident-process-rf2-7gth0
  (setup-xray-frame!)
  (pose-schema-3-registry!)
  (pose-donor-ownership-marker!)
  (registry/simulate-registration-at-schema! 3)
  ;; Capture ONLY `.warn`: swapping the whole console would also swallow the
  ;; failure output.
  (let [prior-warn (.-warn js/console)
        warns      (atom [])]
    (set! (.-warn js/console)
          (fn [& args] (swap! warns conj (apply str args)) nil))
    (try
      (registry/register-xray-handlers!)
      (testing "the registrar half migrates anyway"
        (is (= #{} (registered-ids :sub (concat schema-3-donor-sub-ids schema-4-sub-ids))))
        (is (nil? (rf.registrar/handler :event schema-3-donor-event-id))))
      (testing "the process is NOT stamped current, and is told to reload once"
        (is (= 3 (registry/installed-schema-version)))
        (is (= 1 (count @warns)))
        (is (re-find #"(?i)reload" (first @warns))))
      (testing "a second `:after-load` still refuses and says so again"
        (registry/register-xray-handlers!)
        (is (= [3 2] [(registry/installed-schema-version) (count @warns)])))
      (testing "dropping the marker (what a page reload does) lets the next
                call stamp current"
        (gobj/remove js/globalThis donor-ownership-marker-key)
        (registry/register-xray-handlers!)
        (is (= registry/schema-version (registry/installed-schema-version))))
      (finally
        (set! (.-warn js/console) prior-warn)
        (gobj/remove js/globalThis donor-ownership-marker-key)))))

;; ---- schema 6: the Freehand tool-door reads REMOVED ---------------------
;;
;; A process that ran the deleted `install-mounted-views-subs!` under schema
;; 4 or 5 keeps three phantom ids; schema 6 clears them in place. The door was
;; a reader, so nothing needs a reload.

(defn- pose-schema-5-mounted-view-reads!
  "Register the three ids a schema-4/5 process holds, with stand-in bodies.
  Only the IDS are the subject — the real bodies closed over
  `re-frame.freehand.tool`, which is precisely what is absent."
  []
  (doseq [q-id schema-4-sub-ids]
    (rf/reg-sub q-id (fn [_db _query] [])))
  nil)

(deftest schema-6-clears-the-retired-freehand-reads-rf2-l86mm
  (setup-xray-frame!)
  (pose-schema-5-mounted-view-reads!)
  (registry/simulate-registration-at-schema! 5)
  (is (= (set schema-4-sub-ids) (registered-ids :sub schema-4-sub-ids))
      "PRECONDITION — the posed process resolves all three")
  (registry/register-xray-handlers!)
  (is (= #{} (registered-ids :sub schema-4-sub-ids)))
  (is (= registry/schema-version (registry/installed-schema-version))))

;; ---- schema-delta governance pin ----------------------------------------
;;
;; Any gated registration edit, add OR replace, must bump `schema-version`
;; and pair a `migrate-schema!` clause (or record why none is needed).

(def ^:private expected-schema-version 7)

(deftest schema-version-is-pinned-so-changed-registrations-name-a-migration
  (is (= expected-schema-version registry/schema-version)
      (str "registration-schema version changed. If you ADDED or CHANGED a "
           "gated registration, bump schema-version, pair a migrate-schema! "
           "clause (or document why no migration is needed), then update "
           "expected-schema-version.")))

;; ---- Machine tab fit-on-entry signal ------------------------------------
;;
;; Activating the Machine tab bumps a monotonic counter the Machine panel
;; forwards as `MachineChart`'s `:fit-signal` (the chart side is pinned in
;; machines-viz `auto-fit-view-cljs-test`).

(deftest machine-tab-fit-signal-bumps-on-dynamic-activation
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [signal #(deref (rf/subscribe [:rf.xray/machine-tab-fit-signal]))]
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 2 (signal)) "re-activating the active Machine tab still bumps")
      (rf/dispatch-sync [:rf.xray/select-tab :epoch])
      (is (= 2 (signal)) "a non-Machine tab leaves the signal steady")
      (rf/dispatch-sync [:rf.xray/select-tab :machines])
      (is (= 3 (signal))))))

(deftest machine-tab-fit-signal-bumps-on-static-activation
  ;; Static shares the `:machines` tab id and the chart, so it bumps the
  ;; same counter.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static/select-tab :machines])
    (rf/dispatch-sync [:rf.xray.static/select-tab :routes])
    (is (= 1 @(rf/subscribe [:rf.xray/machine-tab-fit-signal])))))

;; ---- (2) high-value sub contracts: defaults on a fresh frame ------------

(deftest sub-trace-buffer-sync-and-clear-events
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [seed [{:id 100 :op-type :rf.event :operation :rf.test/seeded :tags {}}
                {:id 101 :op-type :rf.event :operation :rf.test/seeded :tags {}}]]
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer seed])
      (is (= seed @(rf/subscribe [:rf.xray/trace-buffer])))
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer [{:id 200 :tags {}}]])
      (is (= [{:id 200 :tags {}}] @(rf/subscribe [:rf.xray/trace-buffer]))
          "a sync replaces the slot wholesale")
      (rf/dispatch-sync [:rf.xray/clear-trace-buffer])
      (is (= [] @(rf/subscribe [:rf.xray/trace-buffer]))))))

;; ---- :rf.xray/event-bundles — xray-internal filter ------------------------

(defn- seed-buffer-with-dispatched-events!
  "Seed one `:rf.event/dispatched` trace event per entry, so
  `group-by-event` yields one cascade each."
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
  ;; `:rf.xray/event-bundles` hard-filters `rf.xray` cascades at the data
  ;; layer, so every downstream consumer inherits the filter.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (seed-buffer-with-dispatched-events!
      [{:dispatch-id 1 :event-vec [:cart/add-item {:item-id "apple"}]}
       {:dispatch-id 2 :event-vec [:rf.xray/focus-event 99]}
       {:dispatch-id 3 :event-vec [:checkout/start]}
       {:dispatch-id 4 :event-vec [:rf.xray/select-tab :event]}])
    (is (= [:cart/add-item :checkout/start]
           (mapv #(first (:event %)) @(rf/subscribe [:rf.xray/event-bundles]))))))

(deftest sub-suppressed-sensitive-count-reads-app-db
  ;; Each `:rf.xray/note-sensitive-suppressed` carries one task's per-frame
  ;; counts and re-fires the REDACTED sub on the ordinary write path.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed {:rf/default 2}])
    (rf/dispatch-sync [:rf.xray/note-sensitive-suppressed {:rf/default 1 :rf/xray 1}])
    (is (= 4 @(rf/subscribe [:rf.xray/suppressed-sensitive-count]))
        "later counts ADD, across frame buckets")
    (rf/dispatch-sync [:rf.xray/reset-suppressed-counters])
    (is (= 0 @(rf/subscribe [:rf.xray/suppressed-sensitive-count])))))

;; ---- the PINNED-NO-EPOCH focus ------------------------------------------
;;
;; A focus pinned to a bundle that settled no epoch carries a nil `:epoch-id`
;; beside a pinned `:dispatch-id`, the same shape as the cold-start UNSET
;; focus the head-fallback serves. A consumer reading `:epoch-id` alone
;; projects the HEAD record under the operator's selection; these rows cover
;; `:rf.xray/issues-ribbon` and `:rf.xray/reactive-data`. The spine slots are
;; seeded directly because `:rf.xray/sync-epoch-history` stamps the focus
;; from the head record.

(def ^:private pin-dispatch-id
  "The pinned `:dispatch-id` of a bundle that settled no epoch. Deliberately
  NOT any `:dispatch-id` in `pin-history`, so nothing can resolve it to a
  record by accident."
  999)

(def ^:private pin-history
  "A single REAL epoch survives in the ring, carrying an issue-bearing trace
  event, a sub-run and a render. That is what makes the rows below
  discriminating: head-fallback has something to fall back TO, so a consumer
  reading `:epoch-id` alone would surface epoch 11's issues / cascade rather
  than an empty projection that happens to look right."
  [{:epoch-id      11
    :dispatch-id   11
    :event         [:test/event]
    :db-before     {}
    :db-after      {}
    :sub-runs      [{:sub-id :counter/value :recomputed? true :value-changed? true}]
    :renders       [{:render-key [:counter-view 0]}]
    :trace-events  [{:id 1 :op-type :error :operation :rf.error/handler-threw
                     :tags {}}
                    {:operation :rf.view/rendered
                     :tags {:rf.view/id          :counter-view
                            :rf.view/render-key  [:counter-view 0]
                            :rf.view/mount?      false
                            :rf.view/deref-subs  [[:counter/value]]}}]}])

(defn- install-pin-seeder!
  "Register the test-local spine seeder. Registration is process-global while
  the dispatch below is frame-scoped, so this is called OUTSIDE
  `rf/with-frame` — and re-registered per test rather than at load time, so a
  fixture registrar reset cannot leave it missing."
  []
  (rf/reg-event ::seed-focus+history
    (fn [{:keys [db]} [_ focus history]]
      {:db (-> db
               (assoc :epoch-history (vec history))
               (assoc :focus focus))})))

(defn- seed-focus+history!
  "Seed both spine slots in ONE write. Must be called inside
  `(rf/with-frame :rf/xray ...)`."
  [focus history]
  (rf/dispatch-sync [::seed-focus+history focus (vec history)]))

(defn- pin-focus
  "A RETRO focus pinning `dispatch-id` with `epoch-id`. `:frame` nil keeps the
  composite frame-unscoped, matching the seeded history."
  [dispatch-id epoch-id]
  {:mode :retro :dispatch-id dispatch-id :epoch-id epoch-id :frame nil})

(deftest sub-issues-ribbon-rejects-pinned-bundle-that-settled-no-epoch
  ;; The ribbon is the auto-open-on-error signal, so a head-fallback here
  ;; would fire the watcher on an unrelated epoch's issues.
  (setup-xray-frame!)
  (install-pin-seeder!)
  (rf/with-frame :rf/xray
    (seed-focus+history! (pin-focus pin-dispatch-id nil) pin-history)
    (is (= {:issues [] :total 0 :epoch-id nil :empty-kind :no-epoch}
           (select-keys @(rf/subscribe [:rf.xray/issues-ribbon])
                        [:issues :total :epoch-id :empty-kind])))))

(deftest sub-issues-ribbon-rejects-only-the-pinned-no-epoch-shape
  ;; POSITIVE CONTROL: an unset focus still head-falls-back and an ordinary
  ;; pinned epoch still projects its own issues.
  (setup-xray-frame!)
  (install-pin-seeder!)
  (rf/with-frame :rf/xray
    (doseq [focus [(pin-focus nil nil) (pin-focus 11 11)]]
      (seed-focus+history! focus pin-history)
      (is (= {:total 1 :epoch-id 11 :empty-kind nil}
             (select-keys @(rf/subscribe [:rf.xray/issues-ribbon])
                          [:total :epoch-id :empty-kind]))
          (pr-str focus)))))

(deftest sub-reactive-data-rejects-pinned-bundle-that-settled-no-epoch
  ;; The composite still reports the operator's pin, so projecting the head
  ;; record would make the two disagree about one selection.
  (setup-xray-frame!)
  (install-pin-seeder!)
  (rf/with-frame :rf/xray
    (seed-focus+history! (pin-focus pin-dispatch-id nil) pin-history)
    (let [data @(rf/subscribe [:rf.xray/reactive-data])]
      (is (= [false pin-dispatch-id true true]
             [(:has-event-bundle? data) (:dispatch-id data)
              (empty? (:subs-ran data)) (empty? (:view-rows data))])))))

(deftest sub-reactive-data-rejects-only-the-pinned-no-epoch-shape
  ;; POSITIVE CONTROL for Views.
  (setup-xray-frame!)
  (install-pin-seeder!)
  (rf/with-frame :rf/xray
    (doseq [focus [(pin-focus nil nil) (pin-focus 11 11)]]
      (seed-focus+history! focus pin-history)
      (let [data @(rf/subscribe [:rf.xray/reactive-data])]
        (is (and (true? (:has-event-bundle? data)) (seq (:subs-ran data)))
            (pr-str focus))))))

(deftest sub-reactive-data-show-unchanged-resolves-both-axes
  (testing "the §3.4 disclosure open-state (`:show-unchanged?`
            on :rf.xray/reactive-data, the flag the panel view reads) is the
            OR of the panel-local quick-toggle
            (:rf.xray/reactive-toggle-unchanged) AND the
            :show-unchanged-subs? Settings pin. Either axis visibly changes
            it; collapsed by default."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (false? (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data])))
          "collapsed by default")
      ;; panel-local quick-toggle axis
      (rf/dispatch-sync [:rf.xray/reactive-toggle-unchanged])
      (is (true? (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data])))
          "the per-panel toggle expands the disclosure")
      (rf/dispatch-sync [:rf.xray/reactive-toggle-unchanged])
      (is (false? (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data])))
          "toggling back collapses it")
      ;; Settings always-expand pin axis
      (rf/dispatch-sync [:rf.xray/settings-update :general :show-unchanged-subs? true])
      (is (true? (:show-unchanged? @(rf/subscribe [:rf.xray/reactive-data])))
          "the Settings pin expands the disclosure independently"))))

;; ---- (4) high-value event contracts -------------------------------------

(deftest event-select-dispatch-id-and-clear
  (testing ":rf.xray/select-dispatch-id + clear round-trip — reads
            focus off the canonical spine `:rf.xray/focus` sub
            (focus is the single source of truth)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 42])
      (is (= 42 (:dispatch-id @(rf/subscribe [:rf.xray/focus]))))
      (rf/dispatch-sync [:rf.xray/clear-selected-dispatch-id])
      (is (nil? (:dispatch-id @(rf/subscribe [:rf.xray/focus])))))))

;; ---- select-dispatch-id head-id must thread show-ungrouped? ------------
;;
;; `:rf.xray/select-dispatch-id` and `:rf.xray/focus-event` both pick
;; LIVE/RETRO by `(= dispatch-id head-id)`, so they must compute the head the
;; same way when the :ungrouped bucket is shown.

(defn- seed-buffer-with-ungrouped-head!
  "Seed Xray's trace buffer with two routed dispatches plus one event
  carrying NO `:rf.trace/dispatch-id` tag — `group-by-event` buckets it
  under the synthetic `:ungrouped` id (registry-time emits / REPL evals
  / frame lifecycle outside a drain). The ungrouped event's `:id` is
  the highest, so it sorts last — the user-visible head once
  `show-ungrouped?` reveals the bucket."
  []
  (trace-collector/seed-trace-for-test!
    {:id 1 :op-type :rf.event :operation :rf.event/dispatched
     :tags {:rf.trace/dispatch-id :routed-a
            :frame :rf/default
            :rf.event/v [:cart/routed-a]}})
  (trace-collector/seed-trace-for-test!
    {:id 2 :op-type :rf.event :operation :rf.event/dispatched
     :tags {:rf.trace/dispatch-id :routed-b
            :frame :rf/default
            :rf.event/v [:cart/routed-b]}})
  (trace-collector/seed-trace-for-test!
    ;; No :rf.trace/dispatch-id tag → :ungrouped bucket.
    {:id 3 :op-type :rf.event :operation :registry-time/emit
     :tags {:frame :rf/default}}))

(deftest select-dispatch-id-mode-matches-focus-event-with-show-ungrouped-rf2-pqt7cb
  ;; With show-ungrouped? on, :ungrouped is the head, so selecting :routed-b
  ;; must pin RETRO on both handlers.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-update :general :show-ungrouped? true])
    (seed-buffer-with-ungrouped-head!)
    (rf/dispatch-sync [:rf.xray/select-dispatch-id :routed-b :rf/default])
    (is (= :retro (:mode @(rf/subscribe [:rf.xray/focus]))))
    (rf/dispatch-sync [:rf.xray/clear-selected-dispatch-id])
    (rf/dispatch-sync [:rf.xray/focus-event :routed-b :rf/default])
    (is (= :retro (:mode @(rf/subscribe [:rf.xray/focus]))))))

(deftest event-select-epoch-passive-scrub
  (testing ":rf.xray/select-epoch pins the spine focus epoch (passive
            scrub) — the single source of truth is
            `[:focus :epoch-id]`, surfaced by `:rf.xray/focus-epoch-id`"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-epoch :e-7])
      (is (= :e-7 @(rf/subscribe [:rf.xray/focus-epoch-id])))
      ;; nil resets the focus epoch.
      (rf/dispatch-sync [:rf.xray/select-epoch nil])
      (is (nil? @(rf/subscribe [:rf.xray/focus-epoch-id]))))))

(deftest event-select-machine-id-and-clear
  (testing ":rf.xray/select-machine-id + clear round-trip"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-machine-id :traffic-light])
      (is (= :traffic-light @(rf/subscribe [:rf.xray/selected-machine-id])))
      (rf/dispatch-sync [:rf.xray/clear-machine-selection])
      (is (nil? @(rf/subscribe [:rf.xray/selected-machine-id]))))))

;; ---- (8) frame isolation ------------------------------------------------

(deftest events-write-to-xray-frame-not-default
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/select-tab :event])
    (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
    (rf/dispatch-sync [:rf.xray/select-epoch :e]))
  (let [slots (juxt :selected-tab #(get-in % [:focus :dispatch-id]) #(get-in % [:focus :epoch-id]))]
    (is (= [:event 1 :e] (slots (rf.frame/frame-app-db-value :rf/xray))))
    (is (= [nil nil nil] (slots (rf.frame/frame-app-db-value :rf/default))))))

(deftest epoch-recorded-ignores-non-target-frames
  ;; A target is selected first: an UNSELECTED slot adopts the recording
  ;; frame instead (below), which would not exercise this arm.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/some-target])
    (rf/dispatch-sync [:rf.xray/epoch-recorded :rf/some-other-frame])
    (is (= [] @(rf/subscribe [:rf.xray/epoch-history])))
    (is (= :rf/some-target @(rf/subscribe [:rf.xray/target-frame])))))

;; ---- cold-start adoption ------------------------------------------------
;;
;; Mounted before the host's first cascade, the target is UNSELECTED, so
;; `:rf.xray/epoch-recorded` adopts the recording frame; otherwise the Epoch
;; panel would stay empty until the user's first click.

(deftest epoch-recorded-adopts-the-recording-frame-when-target-unselected
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (with-redefs [rf/epoch-history (fn [frame-id]
                                     (if (= :above frame-id)
                                       [{:epoch-id :e-above :frame :above}]
                                       []))]
      (rf/dispatch-sync [:rf.xray/epoch-recorded :above]))
    (is (= [:above [:e-above] :above]
           [@(rf/subscribe [:rf.xray/target-frame])
            (mapv :epoch-id @(rf/subscribe [:rf.xray/epoch-history]))
            (:frame @(rf/subscribe [:rf.xray/focus]))])
        "target, history and [:focus :frame] move together")))

(deftest epoch-recorded-never-adopts-xrays-own-frame
  ;; Xray's own chrome events are the likeliest first settle at cold start.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/epoch-recorded :rf/xray])
    (is (nil? @(rf/subscribe [:rf.xray/target-frame])))
    (is (= [] @(rf/subscribe [:rf.xray/epoch-history])))))
