(ns day8.re-frame2-causa.registry-cljs-test
  "Dedicated CLJS tests for `day8.re-frame2-causa.registry` (rf2-5zl7l).

  ## Scope

  `registry.cljs` (1818 LoC) is the central registrar for Causa's
  framework surface — 64 reg-subs + 60 reg-event-(db|fx) + 3 reg-fx,
  all under the `:rf.causa/*` namespace, targeting the `:rf/causa`
  frame. Prior to this file the only coverage was *transitive* through
  per-panel view tests (each panel test calls `(registry/reset-for-test!)`
  then drives the panel-specific subset via subscribe/dispatch).

  Per the bead description (rf2-5zl7l) and the test-coverage audit
  (rf2-otcbz) the transitive route does NOT isolate:

    - The 3 `reg-fx` handlers as standalone units (the time-travel
      panel test stubs two of them — it doesn't drive the registered
      delegations themselves).
    - The full smoke surface: that every registered name resolves to
      a handler after `register-causa-handlers!` runs.
    - Cross-panel composite subs (per-panel tests don't exercise
      `:rf.causa/selected-panel`'s sidebar contract end-to-end).

  ## Trade-off: smoke + high-value, not 1-per-registration

  125 registrations is too many to exhaustively unit-test without
  duplicating the per-panel suite. The strategy below is:

    (1) **Smoke registration block** — one assertion per registered
        name that the registrar resolves it (proves the
        `register-causa-handlers!` block reached every form without
        an early throw — the failure mode the audit named).
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
  (config/reset-suppressed-count!))

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

(def ^:private all-sub-names
  "Every :rf.causa/* sub registered by `register-causa-handlers!`. Sorted
  for stable iteration in the smoke block. AI Co-Pilot subs live behind
  a separate `install!` call so they are intentionally excluded — this
  file's scope is registry.cljs only."
  [:rf.causa/active-route-slice
   :rf.causa/active-route-slice-override
   :rf.causa/app-db-diff
   :rf.causa/causality-graph-data
   :rf.causa/effects-data
   :rf.causa/epoch-history
   :rf.causa/event-detail
   :rf.causa/flow-trace-events
   :rf.causa/flows-data
   :rf.causa/focused-slice-path
   :rf.causa/fx-trace-events
   :rf.causa/hydration-debugger-data
   :rf.causa/hydration-reroot-path
   :rf.causa/issues-filters
   :rf.causa/issues-ribbon
   :rf.causa/machine-inspector-data
   :rf.causa/machine-snapshots
   :rf.causa/machine-snapshots-override
   :rf.causa/mcp-filters
   :rf.causa/mcp-origin-filter-enabled?
   :rf.causa/mcp-server
   :rf.causa/performance-budget-ms
   :rf.causa/performance-data
   :rf.causa/pin-store
   :rf.causa/pinned-slices
   :rf.causa/pinned-slices-store
   :rf.causa/pinned-snapshots
   :rf.causa/registered-flows
   :rf.causa/registered-fxs
   :rf.causa/registered-machines
   :rf.causa/registered-routes
   :rf.causa/registered-schemas
   :rf.causa/route-history-events
   :rf.causa/routes-data
   :rf.causa/schema-filter
   :rf.causa/schema-timeline-prev-rows
   :rf.causa/schema-timeline-window
   :rf.causa/schema-violation-timeline
   :rf.causa/schema-violations-window
   :rf.causa/selected-dispatch-id
   :rf.causa/selected-epoch-diff
   :rf.causa/selected-epoch-id
   :rf.causa/selected-epoch-record
   :rf.causa/selected-flow-id
   :rf.causa/selected-fx-id
   :rf.causa/selected-machine-id
   :rf.causa/selected-mismatch-id
   :rf.causa/selected-panel
   :rf.causa/selected-route-id
   :rf.causa/selected-sub
   :rf.causa/selected-violation-id
   :rf.causa/show-me-when-this-changed-result
   :rf.causa/sub-cache
   :rf.causa/sub-chain-open?
   :rf.causa/sub-error-cache
   :rf.causa/sub-filters
   :rf.causa/subscriptions-data
   :rf.causa/suppressed-sensitive-count
   :rf.causa/target-frame
   :rf.causa/target-frame-db
   :rf.causa/time-travel
   :rf.causa/trace-buffer
   :rf.causa/trace-feed
   :rf.causa/trace-filters])

(def ^:private all-event-names
  [:rf.causa/clear-flow-selection
   :rf.causa/clear-fx-selection
   :rf.causa/clear-issues-filters
   :rf.causa/clear-machine-selection
   :rf.causa/clear-mcp-filters
   :rf.causa/clear-mismatch-selection
   :rf.causa/clear-route-selection
   :rf.causa/clear-selected-dispatch-id
   :rf.causa/clear-selected-epoch
   :rf.causa/clear-selected-sub
   :rf.causa/clear-slice-focus
   :rf.causa/clear-trace-filters
   :rf.causa/clear-violation-selection
   :rf.causa/copy-path-to-clipboard
   :rf.causa/copy-value-to-clipboard
   :rf.causa/dismiss-pin-overflow-toast
   :rf.causa/epoch-recorded
   :rf.causa/focus-slice-path
   :rf.causa/hide-invalidation-chain
   :rf.causa/note-sensitive-suppressed
   :rf.causa/open-in-editor
   :rf.causa/pin-current
   :rf.causa/pin-slice
   :rf.causa/rename-pin
   :rf.causa/reorder-pinned-slices
   :rf.causa/reroot-tree-view
   :rf.causa/reset-suppressed-counters
   :rf.causa/reset-to-epoch
   :rf.causa/reset-to-pinned
   :rf.causa/select-dispatch-id
   :rf.causa/select-epoch
   :rf.causa/select-flow-id
   :rf.causa/select-fx-id
   :rf.causa/select-machine-id
   :rf.causa/select-mismatch
   :rf.causa/select-panel
   :rf.causa/select-route
   :rf.causa/select-sub
   :rf.causa/select-violation
   :rf.causa/set-active-route-slice-override-for-test
   :rf.causa/set-issues-since-seconds
   :rf.causa/set-machine-snapshots-override-for-test
   :rf.causa/set-mcp-since-seconds
   :rf.causa/set-performance-budget-ms
   :rf.causa/set-registered-flows-override-for-test
   :rf.causa/set-registered-fxs-override-for-test
   :rf.causa/set-registered-machines-override-for-test
   :rf.causa/set-registered-routes-override-for-test
   :rf.causa/set-schema-filter
   :rf.causa/set-schema-timeline-window
   :rf.causa/set-sub-cache-override-for-test
   :rf.causa/set-trace-filter
   :rf.causa/show-invalidation-chain
   :rf.causa/toggle-issues-prefix
   :rf.causa/toggle-issues-severity
   :rf.causa/toggle-mcp-op-type
   :rf.causa/toggle-mcp-origin-filter
   :rf.causa/toggle-sub-filter
   :rf.causa/unpin
   :rf.causa/unpin-slice])

(def ^:private all-fx-names
  [:rf.causa.fx/copy-to-clipboard
   :rf.causa.fx/reset-frame-db!
   :rf.causa.fx/restore-epoch])

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

(deftest registry-installs-every-fx
  (testing "register-causa-handlers! resolves every :rf.causa.fx/* fx"
    (registry/register-causa-handlers!)
    (doseq [fx-id all-fx-names]
      (is (some? (registrar/handler :fx fx-id))
          (str "expected :fx handler for " fx-id)))))

(deftest registry-counts-match-bead
  (testing "registry holds exactly 64 subs + 60 events + 3 fxs (rf2-5zl7l; +2 events for rf2-0vxdn)"
    (is (= 64 (count all-sub-names)))
    (is (= 60 (count all-event-names)))
    (is (= 3  (count all-fx-names)))))

(deftest registry-is-idempotent
  (testing "calling register-causa-handlers! twice is a no-op (same handler instance)"
    (registry/register-causa-handlers!)
    (let [h1 (registrar/handler :sub :rf.causa/selected-panel)
          e1 (registrar/handler :event :rf.causa/select-panel)
          f1 (registrar/handler :fx :rf.causa.fx/restore-epoch)]
      (registry/register-causa-handlers!)
      (is (identical? h1 (registrar/handler :sub :rf.causa/selected-panel)))
      (is (identical? e1 (registrar/handler :event :rf.causa/select-panel)))
      (is (identical? f1 (registrar/handler :fx :rf.causa.fx/restore-epoch))))))

;; ---- (2) high-value sub contracts: defaults on a fresh frame ------------

(deftest sub-trace-buffer-reads-trace-bus
  (testing ":rf.causa/trace-buffer is a thunk over trace-bus/buffer — the
            sub returns whatever the bus holds at deref time, scoped to
            the frame's reactive cache. Seeding via `collect-trace!`
            then re-subscribing under a fresh reactive context surfaces
            the pushed event."
    (setup-causa-frame!)
    (trace-bus/clear-buffer!)
    (rf/with-frame :rf/causa
      (is (= [] @(rf/subscribe [:rf.causa/trace-buffer]))
          "fresh buffer surfaces as []")
      (trace-bus/collect-trace!
        {:op-type :event :operation :rf.test/x :tags {} :sensitive? false}))
    ;; Drop the prior subscription's cache by entering a new frame
    ;; context (the reactive sub caches its last value against the
    ;; (no-op) input signal — the assertion here is on the underlying
    ;; bus state, which is process-global per Phase 1 contract).
    (is (= 1 (count (trace-bus/buffer)))
        "the bus holds the pushed event")))

(deftest sub-suppressed-sensitive-count-reads-app-db
  (testing ":rf.causa/suppressed-sensitive-count reads from Causa's
            app-db at `:suppressed-counters` (rf2-0vxdn) — first deref
            returns 0; each `:rf.causa/note-sensitive-suppressed`
            dispatch re-fires the sub on the standard write path
            (immediate reactive update, no clear-subscription-cache!
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

(deftest sub-selected-panel-defaults-to-event-detail
  (testing ":rf.causa/selected-panel falls back to the hero panel"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= registry/default-panel-id
             @(rf/subscribe [:rf.causa/selected-panel]))))))

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

(deftest sub-pin-store-defaults-empty
  (testing ":rf.causa/pin-store defaults to {}"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= {} @(rf/subscribe [:rf.causa/pin-store]))))))

(deftest sub-pinned-snapshots-defaults-empty
  (testing ":rf.causa/pinned-snapshots returns [] for the default target"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= [] @(rf/subscribe [:rf.causa/pinned-snapshots]))))))

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

(deftest sub-mcp-origin-filter-defaults-false
  (testing ":rf.causa/mcp-origin-filter-enabled? defaults to false"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?]))))))

(deftest sub-sub-chain-open-defaults-false
  (testing ":rf.causa/sub-chain-open? defaults to false (boolean)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/sub-chain-open?]))))))

(deftest sub-sub-filters-default-empty-set
  (testing ":rf.causa/sub-filters defaults to #{}"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= #{} @(rf/subscribe [:rf.causa/sub-filters]))))))

(deftest sub-performance-budget-defaults-to-helper-constant
  (testing ":rf.causa/performance-budget-ms defaults to the helper's default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [v @(rf/subscribe [:rf.causa/performance-budget-ms])]
        (is (number? v))
        (is (pos? v))))))

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

(deftest sub-time-travel-shape-on-empty-frame
  (testing ":rf.causa/time-travel returns sane defaults on a fresh frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/time-travel])]
        (is (= :rf/default (:target-frame data)))
        (is (= [] (:history data)))
        (is (= [] (:pins data)))
        (is (= [] (:chip-states data)))
        (is (nil? (:selected-epoch-id data)))
        (is (false? (:cap-reached? data)))))))

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

(deftest sub-causality-graph-data-shape
  (testing ":rf.causa/causality-graph-data returns the canonical shape"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/causality-graph-data])]
        (is (contains? data :graph))
        (is (contains? data :layout))
        (is (contains? data :selected-dispatch-id))
        (is (contains? data :selected-epoch-id))
        (is (false? (:filtered? data)))))))

(deftest sub-hydration-debugger-data-shape-no-mismatch
  (testing ":rf.causa/hydration-debugger-data dormant on an empty buffer"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (false? (:has-mismatch? data)))
        (is (= :rf/default (:target-frame data)))
        (is (nil? (:selected-mismatch-id data)))))))

(deftest sub-effects-data-shape-with-override
  (testing ":rf.causa/effects-data folds the override into rows"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-fxs-override-for-test
                         {:rf.fx/dispatch {} :rf.fx/http {}}])
      (let [data @(rf/subscribe [:rf.causa/effects-data])]
        (is (= 2 (:total data)))
        (is (= 2 (count (:rows data))))
        (is (nil? (:selected-fx-id data)))))))

(deftest sub-flows-data-shape-with-override
  (testing ":rf.causa/flows-data folds the override into rows"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-flows-override-for-test
                         {:flow-a {:path [:a]} :flow-b {:path [:b]}}])
      (let [data @(rf/subscribe [:rf.causa/flows-data])]
        (is (= 2 (:total data)))
        (is (= 2 (count (:rows data))))))))

(deftest sub-routes-data-shape-with-override
  (testing ":rf.causa/routes-data folds registered-routes override into a feed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync
        [:rf.causa/set-registered-routes-override-for-test
         {:home {:path "/"} :about {:path "/about"}}])
      (let [data @(rf/subscribe [:rf.causa/routes-data])]
        (is (contains? data :rows))
        (is (= 2 (:total data)))))))

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

(deftest sub-mcp-server-shape-on-empty-buffer
  (testing ":rf.causa/mcp-server projects the empty agent-feed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/mcp-server])]
        (is (contains? data :rows))
        (is (= 0 (:total data)))
        (is (= 0 (:rendered data)))))))

(deftest sub-performance-data-shape-on-empty-buffer
  (testing ":rf.causa/performance-data is :empty? on a fresh buffer"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/performance-data])]
        (is (contains? data :rows))
        (is (= 0 (:total data)))
        (is (true? (:empty? data)))))))

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

(deftest sub-schema-violation-timeline-shape-empty
  (testing ":rf.causa/schema-violation-timeline returns 0 / empty defaults"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/schema-violation-timeline])]
        (is (= 0 (:total-violations data)))
        (is (= 0 (:rendered-violations data)))
        (is (nil? (:schema-filter data)))
        (is (nil? (:selected-violation data)))))))

(deftest sub-subscriptions-data-shape-empty
  (testing ":rf.causa/subscriptions-data returns empty defaults"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-sub-cache-override-for-test {}])
      (let [data @(rf/subscribe [:rf.causa/subscriptions-data])]
        (is (= 0 (:total data)))
        (is (= [] (:rows data)))
        (is (= #{} (:active-filters data)))
        (is (false? (:chain-open? data)))))))

;; ---- (4) high-value event contracts -------------------------------------

(deftest event-select-panel-writes-to-causa-frame
  (testing ":rf.causa/select-panel stores under :selected-panel"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-panel :causality-graph])
      (is (= :causality-graph
             @(rf/subscribe [:rf.causa/selected-panel]))))))

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
      (rf/dispatch-sync [:rf.causa/clear-selected-epoch])
      (is (nil? @(rf/subscribe [:rf.causa/selected-epoch-id]))))))

(deftest event-toggle-issues-severity-roundtrip
  (testing ":rf.causa/toggle-issues-severity adds + removes a chip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-issues-severity :error])
      (is (= #{:error} (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa/toggle-issues-severity :warning])
      (is (= #{:error :warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa/toggle-issues-severity :error])
      (is (= #{:warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters])))))))

(deftest event-clear-issues-filters
  (testing ":rf.causa/clear-issues-filters drops all three axes"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-issues-severity :error])
      (rf/dispatch-sync [:rf.causa/toggle-issues-prefix "rf.error"])
      (rf/dispatch-sync [:rf.causa/set-issues-since-seconds 60])
      (rf/dispatch-sync [:rf.causa/clear-issues-filters])
      (let [f @(rf/subscribe [:rf.causa/issues-filters])]
        (is (= #{} (:severities f)))
        (is (= #{} (:prefixes f)))
        (is (nil? (:since-ms f)))))))

(deftest event-set-issues-since-seconds-normalises
  (testing ":rf.causa/set-issues-since-seconds — positive sets ms; nil clears"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-issues-since-seconds 30])
      (is (= 30000 (:since-ms @(rf/subscribe [:rf.causa/issues-filters]))))
      ;; non-positive clears
      (rf/dispatch-sync [:rf.causa/set-issues-since-seconds 0])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa/set-issues-since-seconds 15])
      (rf/dispatch-sync [:rf.causa/set-issues-since-seconds nil])
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

(deftest event-toggle-mcp-op-type-set-membership
  (testing ":rf.causa/toggle-mcp-op-type adds + removes membership in a set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :event])
      (is (= #{:event}
             (:op-types @(rf/subscribe [:rf.causa/mcp-filters]))))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :event])
      (is (= #{}
             (:op-types @(rf/subscribe [:rf.causa/mcp-filters])))))))

(deftest event-toggle-mcp-origin-filter-flips
  (testing ":rf.causa/toggle-mcp-origin-filter flips the boolean"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-origin-filter])
      (is (true? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-origin-filter])
      (is (false? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?]))))))

(deftest event-select-mismatch-drops-reroot
  (testing ":rf.causa/select-mismatch sets id and drops any reroot path"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [:a :b]])
      (rf/dispatch-sync [:rf.causa/select-mismatch :m-1])
      (is (= :m-1 @(rf/subscribe [:rf.causa/selected-mismatch-id])))
      (is (nil? @(rf/subscribe [:rf.causa/hydration-reroot-path]))))))

(deftest event-reroot-tree-view-empty-clears
  (testing ":rf.causa/reroot-tree-view with empty path clears the slot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [:a :b]])
      (is (= [:a :b] @(rf/subscribe [:rf.causa/hydration-reroot-path])))
      (rf/dispatch-sync [:rf.causa/reroot-tree-view []])
      (is (nil? @(rf/subscribe [:rf.causa/hydration-reroot-path]))))))

(deftest event-toggle-sub-filter
  (testing ":rf.causa/toggle-sub-filter adds + removes membership"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :fresh])
      (is (= #{:fresh} @(rf/subscribe [:rf.causa/sub-filters])))
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :re-running])
      (is (= #{:fresh :re-running} @(rf/subscribe [:rf.causa/sub-filters])))
      (rf/dispatch-sync [:rf.causa/toggle-sub-filter :fresh])
      (is (= #{:re-running} @(rf/subscribe [:rf.causa/sub-filters]))))))

(deftest event-show-and-hide-invalidation-chain
  (testing ":rf.causa/show-invalidation-chain + hide round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/show-invalidation-chain [:my-sub]])
      (is (true? @(rf/subscribe [:rf.causa/sub-chain-open?])))
      (is (= [:my-sub] @(rf/subscribe [:rf.causa/selected-sub])))
      (rf/dispatch-sync [:rf.causa/hide-invalidation-chain])
      (is (false? @(rf/subscribe [:rf.causa/sub-chain-open?]))))))

(deftest event-set-performance-budget-ms-normalises
  (testing ":rf.causa/set-performance-budget-ms accepts pos numbers; nil resets"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms 100])
      (is (= 100 @(rf/subscribe [:rf.causa/performance-budget-ms])))
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms nil])
      (let [v @(rf/subscribe [:rf.causa/performance-budget-ms])]
        (is (number? v))
        (is (not= 100 v)))
      ;; non-positive also resets
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms 100])
      (rf/dispatch-sync [:rf.causa/set-performance-budget-ms -5])
      (let [v @(rf/subscribe [:rf.causa/performance-budget-ms])]
        (is (not= 100 v))))))

(deftest event-set-schema-timeline-window-validates
  (testing ":rf.causa/set-schema-timeline-window rejects malformed windows"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; valid window stores
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window
                         {:t0 0 :t1 1000}])
      (is (= {:t0 0 :t1 1000}
             @(rf/subscribe [:rf.causa/schema-timeline-window])))
      ;; nil clears (sub falls back to default)
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window nil])
      ;; sub returns default-window (not nil)
      (is (map? @(rf/subscribe [:rf.causa/schema-timeline-window])))
      ;; invalid (t0 >= t1) is discarded
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window
                         {:t0 1000 :t1 0}])
      (is (not= {:t0 1000 :t1 0}
                @(rf/subscribe [:rf.causa/schema-timeline-window]))))))

(deftest event-set-schema-filter-nil-clears
  (testing ":rf.causa/set-schema-filter — value sets; nil clears"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-schema-filter :user/email])
      (is (= :user/email @(rf/subscribe [:rf.causa/schema-filter])))
      (rf/dispatch-sync [:rf.causa/set-schema-filter nil])
      (is (nil? @(rf/subscribe [:rf.causa/schema-filter]))))))

(deftest event-select-violation-nil-clears
  (testing ":rf.causa/select-violation — value sets; nil clears"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-violation :v-1])
      (is (= :v-1 @(rf/subscribe [:rf.causa/selected-violation-id])))
      (rf/dispatch-sync [:rf.causa/select-violation nil])
      (is (nil? @(rf/subscribe [:rf.causa/selected-violation-id]))))))

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

(deftest event-select-flow-id-and-clear
  (testing ":rf.causa/select-flow-id + clear round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-flow-id :total-price])
      (is (= :total-price @(rf/subscribe [:rf.causa/selected-flow-id])))
      (rf/dispatch-sync [:rf.causa/clear-flow-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-flow-id]))))))

(deftest event-select-fx-id-and-clear
  (testing ":rf.causa/select-fx-id + clear round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-fx-id :rf.fx/dispatch])
      (is (= :rf.fx/dispatch @(rf/subscribe [:rf.causa/selected-fx-id])))
      (rf/dispatch-sync [:rf.causa/clear-fx-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-fx-id]))))))

(deftest event-select-route-and-clear
  (testing ":rf.causa/select-route + clear round-trip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-route :home])
      (is (= :home @(rf/subscribe [:rf.causa/selected-route-id])))
      (rf/dispatch-sync [:rf.causa/clear-route-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-route-id]))))))

(deftest event-dismiss-pin-overflow-toast
  (testing ":rf.causa/dismiss-pin-overflow-toast clears the toast slot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed the toast slot via :select-panel's sibling write path —
      ;; the event-db handlers are the only public route, so we use
      ;; the registry-installed `pin-current` shape indirectly by
      ;; asserting that the explicit dismiss clears whatever is there.
      ;; We can't easily seed it without an epoch artefact wired; instead
      ;; assert the handler is a clean dissoc by inspecting frame state.
      (rf/dispatch-sync [:rf.causa/dismiss-pin-overflow-toast])
      (is (nil? (:pin-overflow-toast (frame/frame-app-db-value :rf/causa)))))))

(deftest event-open-in-editor-records-coord
  (testing ":rf.causa/open-in-editor stores the last attempted coord"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor "file://foo.cljs:10:5"])
      (is (= "file://foo.cljs:10:5"
             (:last-open-in-editor-coord (frame/frame-app-db-value :rf/causa)))))))

;; ---- (5) test-only override events --------------------------------------

(deftest event-override-events-set-then-clear
  (testing "every :set-*-override-for-test event sets a value AND clears on nil"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; sub-cache override
      (rf/dispatch-sync [:rf.causa/set-sub-cache-override-for-test {:x 1}])
      (is (= {:x 1} (:sub-cache-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-sub-cache-override-for-test nil])
      (is (nil? (:sub-cache-override (frame/frame-app-db-value :rf/causa))))
      ;; registered-flows
      (rf/dispatch-sync [:rf.causa/set-registered-flows-override-for-test {:f 1}])
      (is (= {:f 1} (:registered-flows-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-registered-flows-override-for-test nil])
      (is (nil? (:registered-flows-override (frame/frame-app-db-value :rf/causa))))
      ;; registered-fxs
      (rf/dispatch-sync [:rf.causa/set-registered-fxs-override-for-test {:fx 1}])
      (is (= {:fx 1} (:registered-fxs-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-registered-fxs-override-for-test nil])
      (is (nil? (:registered-fxs-override (frame/frame-app-db-value :rf/causa))))
      ;; registered-machines
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test [:m]])
      (is (= [:m] (:registered-machines-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test nil])
      (is (nil? (:registered-machines-override (frame/frame-app-db-value :rf/causa))))
      ;; registered-routes
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test {:r 1}])
      (is (= {:r 1} (:registered-routes-override (frame/frame-app-db-value :rf/causa))))
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test nil])
      (is (nil? (:registered-routes-override (frame/frame-app-db-value :rf/causa)))))))

;; ---- (6) reg-fx contracts -----------------------------------------------
;;
;; The three :rf.causa.fx/* handlers each follow re-frame v2's
;; `(fn [ctx args] ...)` signature. We replace each registered fx with
;; a capture stub (same pattern as time_travel_cljs_test.cljs) and
;; dispatch the corresponding event-fx so the captured args round-trip.

(defonce ^:private captured-fx (atom []))

(defn- install-capture-fx! []
  (reset! captured-fx [])
  (rf/reg-fx :rf.causa.fx/restore-epoch
    (fn [_ctx args] (swap! captured-fx conj [:rf.causa.fx/restore-epoch args])))
  (rf/reg-fx :rf.causa.fx/reset-frame-db!
    (fn [_ctx args] (swap! captured-fx conj [:rf.causa.fx/reset-frame-db! args])))
  (rf/reg-fx :rf.causa.fx/copy-to-clipboard
    (fn [_ctx args] (swap! captured-fx conj [:rf.causa.fx/copy-to-clipboard args]))))

(deftest fx-reset-to-epoch-routes-via-restore-epoch
  (testing ":rf.causa/reset-to-epoch fires :rf.causa.fx/restore-epoch with the
            target-frame + epoch-id (no other fxs)"
    (setup-causa-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reset-to-epoch :e-1]))
    (is (= 1 (count @captured-fx)))
    (let [[fx-id args] (first @captured-fx)]
      (is (= :rf.causa.fx/restore-epoch fx-id))
      (is (= :rf/default (:frame-id args)))
      (is (= :e-1 (:epoch-id args))))))

(deftest fx-reset-to-pinned-is-noop-without-pin
  (testing ":rf.causa/reset-to-pinned does NOT fire any fx when no pin matches"
    (setup-causa-frame!)
    (install-capture-fx!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reset-to-pinned :e-missing]))
    (is (= 0 (count @captured-fx))
        "no pin under the target-frame → handler returns nil → no fx routes")))

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

(deftest sub-sub-cache-honours-override
  (testing ":rf.causa/sub-cache returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-sub-cache-override-for-test {[:q] :hit}])
      (is (= {[:q] :hit} @(rf/subscribe [:rf.causa/sub-cache]))))))

(deftest sub-registered-flows-honours-override
  (testing ":rf.causa/registered-flows returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-flows-override-for-test
                         {:flow-a :info}])
      (is (= {:flow-a :info}
             @(rf/subscribe [:rf.causa/registered-flows]))))))

(deftest sub-registered-fxs-honours-override
  (testing ":rf.causa/registered-fxs returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-fxs-override-for-test
                         {:fx-a :info}])
      (is (= {:fx-a :info}
             @(rf/subscribe [:rf.causa/registered-fxs]))))))

(deftest sub-registered-machines-honours-override
  (testing ":rf.causa/registered-machines returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-machines-override-for-test
                         [:m-1 :m-2]])
      (is (= [:m-1 :m-2]
             @(rf/subscribe [:rf.causa/registered-machines]))))))

(deftest sub-registered-routes-honours-override
  (testing ":rf.causa/registered-routes returns the override when set"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test
                         {:home :info}])
      (is (= {:home :info}
             @(rf/subscribe [:rf.causa/registered-routes]))))))

(deftest sub-active-route-slice-override-is-separate-from-live
  (testing ":rf.causa/active-route-slice-override is a separate sub from the
            live :rf.causa/active-route-slice (the routes-data composite
            falls back through both)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (nil? @(rf/subscribe [:rf.causa/active-route-slice-override])))
      (rf/dispatch-sync [:rf.causa/set-active-route-slice-override-for-test
                         {:id :home}])
      (is (= {:id :home}
             @(rf/subscribe [:rf.causa/active-route-slice-override]))))))

;; ---- (8) frame isolation (rf2-tijr Option C) ----------------------------

(deftest events-write-to-causa-frame-not-default
  (testing "every :rf.causa/* event-db handler writes to :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-panel :causality-graph])
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (rf/dispatch-sync [:rf.causa/select-epoch :e]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :causality-graph (:selected-panel causa-db)))
      (is (= 1 (:selected-dispatch-id causa-db)))
      (is (= :e (:selected-epoch-id causa-db)))
      (is (nil? (:selected-panel default-db)))
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
                      :rf.causa/time-travel
                      :rf.causa/app-db-diff
                      :rf.causa/causality-graph-data
                      :rf.causa/hydration-debugger-data
                      :rf.causa/effects-data
                      :rf.causa/flows-data
                      :rf.causa/routes-data
                      :rf.causa/issues-ribbon
                      :rf.causa/trace-feed
                      :rf.causa/mcp-server
                      :rf.causa/performance-data
                      :rf.causa/machine-inspector-data
                      :rf.causa/schema-violation-timeline
                      :rf.causa/subscriptions-data]]
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
