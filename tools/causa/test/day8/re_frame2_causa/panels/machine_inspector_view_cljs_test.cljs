(ns day8.re-frame2-causa.panels.machine-inspector-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Machine Inspector panel
  (Phase 5+, rf2-r9f9u).

  ## What's under test (in addition to the pure-data tests in
  `machine_inspector_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub + supporting subs / events**
       under `:rf.causa/*`.

    2. **The empty state** renders when no machines are registered.

    3. **The picker** enumerates rows from the override and dispatching
       the change event updates Causa's frame.

    4. **The placeholder banner** + prop summary render when a machine
       is selected (the MachineChart impl is deferred per spec).

    5. **The transition history ribbon** renders entries from the
       trace-buffer when transition events are present for the
       selected machine.

  ## Pure hiccup

  Same approach as `subscriptions_view_cljs_test.cljs` /
  `causality_graph_view_cljs_test.cljs` — walk the view's hiccup tree
  by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers -----------------------------------------------------
;;
;; The picker / placeholder / ribbon are *function components* (the panel
;; calls them as `[picker rows id]`). A naive `tree-seq` over the
;; rendered view sees the fn-as-first-element vector but doesn't descend
;; into its expansion. We expand fn-components eagerly first (recursively
;; rewriting the tree), then `tree-seq` walks the fully-expanded hiccup —
;; so a `:select` nested inside the picker's expansion is reachable.

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (let [expanded (expand-fn-component tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-snapshots! [snapshots]
  (rf/dispatch-sync
    [:rf.causa/set-machine-snapshots-override-for-test snapshots]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- force-mode!
  "Force the panel out of the rf2-a9cke focused-event default into one
  of the picker-driven exploration modes for tests that pin the
  picker / chart / ribbon chrome that only renders under those modes."
  [mode]
  (rf/dispatch-sync [:rf.causa/set-forced-machine-mode mode]))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-machine-inspector-handlers
  (testing "register-causa-handlers! installs every rf2-r9f9u handler
            (plus the rf2-2tkza Phase 1 chart + Mode A/B additions)"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-machines)))
    (is (some? (registrar/handler :sub :rf.causa/machine-snapshots)))
    (is (some? (registrar/handler :sub :rf.causa/machine-definitions)))
    (is (some? (registrar/handler :sub :rf.causa/machine-definitions-override)))
    (is (some? (registrar/handler :sub :rf.causa/selected-machine-id)))
    (is (some? (registrar/handler :sub :rf.causa/machine-inspector-data)))
    (is (some? (registrar/handler :event :rf.causa/select-machine-id)))
    (is (some? (registrar/handler :event :rf.causa/clear-machine-selection)))
    (is (some? (registrar/handler :event :rf.causa/machine-state-clicked))
        "rf2-2tkza Phase 1: chart click → source-coord jump trigger")
    (is (some? (registrar/handler
                 :event :rf.causa/set-registered-machines-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-machine-snapshots-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-machine-definitions-override-for-test))
        "rf2-2tkza Phase 1: definitions test override hook")))

(deftest composite-defaults-to-empty-when-no-override
  (testing "with an empty machines override the composite returns the
            empty-shape map. The override is required because the
            process-global registrar may carry machines registered by
            other test namespaces (fixture-reset clears Causa's app-db
            but not the global registrar's :machines kind)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [])
      (let [d @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (= [] (:machines d)))
        (is (= 0 (:total d)))
        (is (= :no-machines (:empty-kind d)))))))

(deftest composite-projects-machines-into-rows
  (testing "with a machines + snapshots override the composite returns
            one row per id with the live snapshot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!  [:auth/login :checkout/flow])
      (override-snapshots! {:auth/login {:state :authing :data {}}})
      (let [d @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (= 2 (:total d)))
        (is (= #{:auth/login :checkout/flow}
               (set (map :machine-id (:machines d)))))
        (is (= :auth/login (:selected-id d))
            "first row is the default focus")
        (is (= :authing (-> d :selected :state)))))))

;; ---- (2) empty state ----------------------------------------------------

(deftest empty-state-renders-when-no-machines
  (testing "with the override-empty machines slot the panel renders
            the empty state. The override is required because the
            process-global registrar may carry machines registered by
            other test namespaces."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-picker"))
            "no picker when there are zero machines")))))

;; ---- (3) picker ---------------------------------------------------------

(deftest picker-renders-with-machines
  (testing "with the override populated the picker renders the dropdown
            under any picker-driven exploration mode (rf2-a9cke: Mode A
            here; the focused-event default has its own per-section
            header instead)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login :checkout/flow])
      (force-mode! :mode-a)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-picker"))
            "picker container present")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-picker-select"))
            "picker dropdown present")))))

(deftest picker-hidden-in-focused-event-default-mode
  (testing "the rf2-a9cke focused-event default has its own per-section
            header chrome; the picker is opt-in via the mode strip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login :checkout/flow])
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-causa-machine-inspector")]
        (is (= "focused-event" (:data-view-mode (second root)))
            "default mode is rf2-a9cke focused-event lens")
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-picker"))
            "picker chrome is hidden in the focused-event default")))))

(deftest select-machine-id-event-writes-to-causa-frame
  (testing ":rf.causa/select-machine-id stores the machine-id on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :checkout/flow])
      (is (= :checkout/flow @(rf/subscribe [:rf.causa/selected-machine-id]))))))

(deftest clear-machine-selection-drops-the-pick
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :checkout/flow])
    (rf/dispatch-sync [:rf.causa/clear-machine-selection])
    (is (nil? @(rf/subscribe [:rf.causa/selected-machine-id])))))

(deftest selection-narrows-the-composite
  (testing "the explicit selection drives the chart-props machine-id"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login :checkout/flow])
      (rf/dispatch-sync [:rf.causa/select-machine-id :checkout/flow])
      (let [d @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (= :checkout/flow (:selected-id d)))
        (is (= :checkout/flow (-> d :chart-props :machine-id)))))))

;; ---- (4) placeholder ----------------------------------------------------

(deftest placeholder-banner-renders-with-selection
  (testing "the placeholder banner calls out the deferred machines-viz
            impl whenever a machine is selected (picker-driven Mode A;
            the rf2-a9cke focused-event default has its own per-section
            chrome)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (force-mode! :mode-a)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-placeholder-banner"))
            "placeholder banner present — surfaces the deferred impl")))))

(deftest placeholder-falls-back-to-prop-summary-without-definition
  (testing "when no machine-definition is available (no introspection
            metadata), the panel falls back to the prop-summary
            view so the panel still renders something useful (picker-
            driven Mode B; the rf2-a9cke focused-event default has its
            own no-definition fallback)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!  [:auth/login])
      (override-snapshots! {:auth/login {:state :authing :data {}}})
      (force-mode! :mode-b)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-placeholder")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-prop-machine-id")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-prop-frame-id")))))))

(deftest chart-renders-when-definition-is-available
  (testing "with the definitions override populated, the panel renders
            the live SVG chart instead of the prop-summary fallback
            (picker-driven Mode B; the rf2-a9cke focused-event default
            renders its own chart per section)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :authing :data {}}})
      (override-definitions! {:auth/login fixture-definition})
      (force-mode! :mode-b)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-chart"))
            "the chart container replaces the prop-summary fallback")
        (is (some? (find-by-testid tree "rf-causa-chart-svg"))
            "the SVG primitive emits a root <svg>")
        (is (nil? (find-by-testid tree "rf-causa-machine-inspector-placeholder"))
            "the fallback is suppressed when a definition is available")))))

(deftest chart-highlights-active-state-from-snapshot
  (testing "the chart's root data-highlight-id matches the snapshot's :state
            (picker-driven Mode B)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :authing :data {}}})
      (override-definitions! {:auth/login fixture-definition})
      (force-mode! :mode-b)
      (let [tree (machine-inspector/Panel)
            chart (find-by-testid tree "rf-causa-machine-inspector-chart")]
        (is (some? chart))
        (is (= "authing" (:data-highlight-id (second chart)))
            "the chart container carries the resolved highlight-id")))))

;; ---- (5) transition history ribbon --------------------------------------

(deftest ribbon-renders-empty-when-no-transitions
  (testing "with no transition events in the buffer the ribbon shows
            its empty branch (picker-driven Mode A — the ribbon is
            scoped to a single picker-selected machine; the rf2-a9cke
            focused-event default surfaces transitions via per-section
            sections instead)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (force-mode! :mode-a)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-ribbon")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-ribbon-empty")))))))

(deftest ribbon-renders-entries-when-transitions-present
  (testing "with transition events in the buffer for the selected machine
            the ribbon renders one entry per transition (picker-driven
            Mode A)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (force-mode! :mode-a)
      ;; Push two transition events into the Causa trace buffer via
      ;; the production `collect-trace!` path (per rf2-e9s81 the
      ;; sub thunks the trace-bus atom).
      (trace-bus/collect-trace!
        {:id 1 :operation :rf.machine/transition :time 100
         :tags {:machine-id :auth/login :from :idle :to :authing
                :event [:auth/submit] :dispatch-id "d-1"}})
      (trace-bus/collect-trace!
        {:id 2 :operation :rf.machine/transition :time 200
         :tags {:machine-id :auth/login :from :authing :to :idle
                :event [:auth/cancel] :dispatch-id "d-2"}})
      (let [tree    (machine-inspector/Panel)
            entries (find-all-by-testid-prefix
                      tree "rf-causa-machine-inspector-transition-")]
        (is (= 2 (count entries))
            "two ribbon entries — one per transition event")))))

(deftest ribbon-entry-click-pivots-to-event-detail
  (testing "clicking a ribbon entry dispatches :rf.causa/select-dispatch-id
            (parity with the Issues ribbon + Trace panel cross-panel jump;
            picker-driven Mode A)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (force-mode! :mode-a)
      ;; Push a transition event via `collect-trace!` (per rf2-e9s81
      ;; the sub thunks the trace-bus atom).
      (trace-bus/collect-trace!
        {:id 1 :operation :rf.machine/transition :time 100
         :tags {:machine-id :auth/login :from :idle :to :authing
                :event [:auth/submit] :dispatch-id "d-42"}})
      (let [dispatches (atom [])]
        ;; rf/dispatch is async; with-redefs on the underlying
        ;; rf/dispatch* fn captures the event vector synchronously
        ;; (same approach the mcp-server view test uses).
        (with-redefs [rf/dispatch* (fn
                                     ([ev] (swap! dispatches conj ev) nil)
                                     ([ev _opts] (swap! dispatches conj ev) nil))]
          (let [tree    (machine-inspector/Panel)
                entry   (find-by-testid
                          tree "rf-causa-machine-inspector-transition-1")
                handler (:on-click (second entry))]
            (is (some? entry) "entry node present in the rendered tree")
            (is (some? handler) "entry carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.causa/select-dispatch-id "d-42"] %) @dispatches)
            "select-dispatch-id fired with the dispatch-id from the trace event")))))

;; ---- (5b) Mode A / Mode B (UC2 dynamic instance modes) ----------------

(deftest view-mode-helper-routes-by-instance-count
  (testing "the view-mode auto-classifier returns :mode-a / :mode-b /
            :mode-c per spec/003-Machine-Inspector.md §UC2. Note that
            per rf2-a9cke `view-mode` is now only the AUTO classifier
            for the picker-driven modes; the panel default is
            :focused-event (see resolve-mode below)"
    (is (= :mode-a (machine-inspector/view-mode 0)))
    (is (= :mode-a (machine-inspector/view-mode nil)))
    (is (= :mode-b (machine-inspector/view-mode 1)))
    (is (= :mode-b (machine-inspector/view-mode 3)))
    (is (= :mode-c (machine-inspector/view-mode 4)))
    (is (= :mode-c (machine-inspector/view-mode 100)))))

(deftest resolve-mode-defaults-to-focused-event
  (testing "per rf2-a9cke the panel's default mode is :focused-event
            (the canonical lens-on-focused-event). The picker-driven
            Mode A/B/C explorations are opt-in via the mode strip."
    (is (= :focused-event (machine-inspector/resolve-mode 0 nil)))
    (is (= :focused-event (machine-inspector/resolve-mode 5 nil)))
    (is (= :focused-event (machine-inspector/resolve-mode nil nil)))))

(deftest resolve-mode-honours-forced-pick
  (testing "a forced-mode override pins the resolved mode regardless of
            instance-count"
    (is (= :mode-a (machine-inspector/resolve-mode 0 :mode-a)))
    (is (= :mode-b (machine-inspector/resolve-mode 0 :mode-b)))
    (is (= :mode-c (machine-inspector/resolve-mode 100 :mode-c)))
    (is (= :focused-event
           (machine-inspector/resolve-mode 100 :focused-event)))))

(deftest mode-a-renders-when-forced
  (testing "with a registered machine + the picker-driven Mode A forced,
            the panel renders in Mode A (definition view; instance-tabs
            hidden)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (force-mode! :mode-a)
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-causa-machine-inspector")]
        (is (= "mode-a" (:data-view-mode (second root))))
        (is (= 0 (:data-instance-count (second root))))
        (is (nil? (find-by-testid
                    tree "rf-causa-machine-inspector-instance-tabs"))
            "instance-tabs strip is hidden in Mode A")))))

(deftest mode-b-renders-instance-tabs-when-forced
  (testing "with a registered machine + a live snapshot + Mode B forced,
            the panel renders in Mode B (instance tabs visible above
            the chart)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :authing :data {}}})
      (override-definitions! {:auth/login fixture-definition})
      (force-mode! :mode-b)
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-causa-machine-inspector")
            tabs (find-by-testid
                   tree "rf-causa-machine-inspector-instance-tabs")]
        (is (= "mode-b" (:data-view-mode (second root))))
        (is (= 1 (:data-instance-count (second root))))
        (is (some? tabs) "instance-tabs strip is present in Mode B")
        (is (= "mode-b" (:data-mode (second tabs))))))))

;; ---- (5c) focused-event lens (rf2-a9cke) ------------------------------

(defn- override-epoch-history!
  "Write an epoch-history slot so the focused-event lens has cascade
  windows to walk. Used by the rf2-a9cke wiring tests; mirrors the
  existing `override-*` pattern but writes the slot directly since
  the panel's existing test-overrides cover registered-machines /
  snapshots / definitions only."
  [history]
  (rf/dispatch-sync
    [:rf.causa/set-epoch-history-for-test history]))

(defn- focus-epoch!
  "Pin the spine's focus to a specific :epoch-id by writing through
  `:rf.causa/focus-cascade` (which the spine reduces to also stamp
  :epoch-id when resolvable). Tests that want explicit control of
  the focused epoch write the slot directly via the event below."
  [epoch-id]
  (rf/dispatch-sync
    [:rf.causa/set-focus-epoch-id-for-test epoch-id]))

(deftest focused-event-mode-is-the-default
  (testing "the panel's default mode is :focused-event per rf2-a9cke
            (no forced-mode → focused-event lens wins)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)
            root (find-by-testid tree "rf-causa-machine-inspector")]
        (is (= "focused-event" (:data-view-mode (second root))))
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-focused-event-host"))
            "the focused-event host is mounted at the default surface")))))

(deftest focused-event-lens-is-silent-when-no-machine-traces
  (testing "per rf2-g3ghh silent-by-default: an empty cascade window
            renders the host but NO focused-event sections"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)
            host (find-by-testid
                   tree "rf-causa-machine-inspector-focused-event-host")]
        (is (some? host) "host present (the chrome always mounts)")
        (is (nil? (find-by-testid tree "rf-causa-machine-focused-event"))
            "no inner focused-event surface when no machine transitions")))))

(deftest focused-event-lens-renders-one-section-per-transition
  (testing "an epoch whose :trace-events carry ≥ 1 :rf.machine/transition
            events yields one focused-event-section per record (rf2-a9cke
            canonical design)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1 :trace-events []}
         {:epoch-id 2
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit]
                   :dispatch-id "d-1"}}]}])
      (focus-epoch! 2)
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-focused-event"))
            "the focused-event surface mounts when the cascade has a transition")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-focused-event-section-auth/login"))
            "one section per transitioned machine")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-focused-event-chart"))
            "the section renders the topology chart")))))

(deftest focused-event-lens-renders-multi-machine-cascade
  (testing "a cascade triggering transitions across multiple machines
            yields one section per machine, document-order (rf2-a9cke
            multi-machine acceptance)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow :session/clock])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition
                              :session/clock fixture-definition})
      (override-epoch-history!
        [{:epoch-id 7
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :dispatch-id "d-1"}}
           {:id 2 :time 11 :operation :rf.machine/transition
            :tags {:machine-id :checkout/flow
                   :before     {:state :idle :data {}}
                   :after      {:state :done :data {}}
                   :event      [:cart/sync] :dispatch-id "d-1"}}
           {:id 3 :time 12 :operation :rf.machine/transition
            :tags {:machine-id :session/clock
                   :before     {:state :idle :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:tick] :dispatch-id "d-1"}}]}])
      (focus-epoch! 7)
      (let [tree     (machine-inspector/Panel)
            sections (find-all-by-testid-prefix
                       tree "rf-causa-machine-focused-event-section-")]
        (is (= 3 (count sections))
            "three sections — one per transitioned machine")
        (is (= [":auth/login" ":checkout/flow" ":session/clock"]
               (mapv #(:data-machine-id (second %)) sections))
            "sections appear in cascade document order")))))

(deftest focused-event-section-emits-from-and-to-highlight-ids
  (testing "the per-section chart carries data-from/to-highlight-id so
            the chart's render path applies the dashed-origin + bold-
            landing visual grammar"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (override-epoch-history!
        [{:epoch-id 1
          :trace-events
          [{:id 1 :time 10 :operation :rf.machine/transition
            :tags {:machine-id :auth/login
                   :before     {:state :idle    :data {}}
                   :after      {:state :authing :data {}}
                   :event      [:auth/submit] :dispatch-id "d-1"}}]}])
      (focus-epoch! 1)
      (let [tree   (machine-inspector/Panel)
            chart  (find-by-testid
                     tree "rf-causa-machine-focused-event-chart")]
        (is (some? chart))
        (is (= "idle"    (:data-from-highlight-id (second chart))))
        (is (= "authing" (:data-to-highlight-id   (second chart))))))))

;; ---- (6) frame isolation ------------------------------------------------

(deftest selection-state-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :auth/login (:selected-machine-id causa-db))
          "selection lands on Causa")
      (is (nil? (:selected-machine-id default-db))
          "selection did NOT leak into :rf/default"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard.
;;
;; Two `for` loops in the Machine Inspector panel previously attached
;; `^{:key …}` reader meta to a function-call list form — `(transition-
;; entry row)` and `(focused-event-section rec)`. Reagent's
;; `get-react-key` only reads `:key` from vector meta, so the keys were
;; silently lost and React emitted "unique key prop" warnings. The fix
;; routes both per-row children through `with-meta` so the `:key` meta
;; lands on the returned `[:li …]` / `[:section …]` vector. This test
;; renders both loops under populated fixtures and asserts every
;; per-row child carries `:key` meta so the regression cannot recur
;; silently. (rf2-ppzid)
;; ---------------------------------------------------------------------------

;; Meta-preserving walker. The `expand-fn-component`/`mapv` walker
;; above rewrites every vector node with `mapv`, stripping element
;; meta. To assert `:key` meta survives we walk via `tree-seq` with a
;; custom children fn: for fn-component vectors we descend into
;; `(apply f args)`; for pure hiccup vectors we yield their children
;; in place. Either way the data-testid-bearing vectors land in our
;; hand as-is, with meta intact.
(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest ribbon-entries-carry-key-meta
  (testing "ribbon entries (transition-entry for-loop) carry :key meta
            on the returned [:li …] vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (force-mode! :mode-a)
      (trace-bus/collect-trace!
        {:id 1 :operation :rf.machine/transition :time 100
         :tags {:machine-id :auth/login :from :idle :to :authing
                :event [:auth/submit] :dispatch-id "d-1"}})
      (trace-bus/collect-trace!
        {:id 2 :operation :rf.machine/transition :time 200
         :tags {:machine-id :auth/login :from :authing :to :idle
                :event [:auth/cancel] :dispatch-id "d-2"}})
      (trace-bus/collect-trace!
        {:id 3 :operation :rf.machine/transition :time 300
         :tags {:machine-id :auth/login :from :idle :to :authing
                :event [:auth/retry] :dispatch-id "d-3"}})
      (let [tree    (machine-inspector/Panel)
            entries (raw-find-all-by-testid-prefix
                      tree "rf-causa-machine-inspector-transition-")]
        (is (= 3 (count entries))
            "three ribbon entries — one per transition event")
        (doseq [entry entries]
          (is (vector? entry) "ribbon entry is a hiccup vector")
          (is (some? (some-> (meta entry) :key))
              (str "ribbon entry carries :key meta — got "
                   (pr-str (meta entry)))))))))

(deftest focused-event-sections-carry-key-meta
  (testing "focused-event-section per-record for-loop ships per-section
            children carrying :key meta on the returned [:section …]
            vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login :checkout/flow])
      (override-definitions! {:auth/login    fixture-definition
                              :checkout/flow fixture-definition})
      ;; Two transitions across two machines so the focused-event lens
      ;; renders two sibling sections.
      (trace-bus/collect-trace!
        {:id 1 :operation :event/dispatched :op-type :event :time 100
         :tags {:event [:cascade/start] :dispatch-id "d-cascade"
                :frame :rf/default}})
      (trace-bus/collect-trace!
        {:id 2 :operation :rf.machine/transition :time 110
         :tags {:machine-id :auth/login :from :idle :to :authing
                :event [:cascade/start] :dispatch-id "d-cascade"}})
      (trace-bus/collect-trace!
        {:id 3 :operation :rf.machine/transition :time 120
         :tags {:machine-id :checkout/flow :from :idle :to :authing
                :event [:cascade/start] :dispatch-id "d-cascade"}})
      (rf/dispatch-sync [:rf.causa/select-dispatch-id "d-cascade"])
      (let [tree     (machine-inspector/Panel)
            sections (raw-find-all-by-testid-prefix
                       tree "rf-causa-machine-focused-event-section-")]
        (when (seq sections)
          (doseq [section sections]
            (is (vector? section) "focused-event-section is a hiccup vector")
            (is (some? (some-> (meta section) :key))
                (str "focused-event-section carries :key meta — got "
                     (pr-str (meta section))))))))))
