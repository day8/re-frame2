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

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-machine-inspector-handlers
  (testing "register-causa-handlers! installs every rf2-r9f9u handler"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-machines)))
    (is (some? (registrar/handler :sub :rf.causa/machine-snapshots)))
    (is (some? (registrar/handler :sub :rf.causa/selected-machine-id)))
    (is (some? (registrar/handler :sub :rf.causa/machine-inspector-data)))
    (is (some? (registrar/handler :event :rf.causa/select-machine-id)))
    (is (some? (registrar/handler :event :rf.causa/clear-machine-selection)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-registered-machines-override-for-test)))
    (is (some? (registrar/handler
                 :event :rf.causa/set-machine-snapshots-override-for-test)))))

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
  (testing "with the override populated the picker renders the dropdown"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login :checkout/flow])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-picker"))
            "picker container present")
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-picker-select"))
            "picker dropdown present")))))

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
            impl whenever a machine is selected"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-placeholder-banner"))
            "placeholder banner present — surfaces the deferred impl")))))

(deftest placeholder-shows-prop-summary-with-selection
  (testing "the placeholder renders the MachineChart prop summary for
            the selected machine — the contract is what matters at v1"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!  [:auth/login])
      (override-snapshots! {:auth/login {:state :authing :data {}}})
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-placeholder")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-prop-machine-id")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-prop-frame-id")))))))

;; ---- (5) transition history ribbon --------------------------------------

(deftest ribbon-renders-empty-when-no-transitions
  (testing "with no transition events in the buffer the ribbon shows
            its empty branch"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-ribbon")))
        (is (some? (find-by-testid tree "rf-causa-machine-inspector-ribbon-empty")))))))

(deftest ribbon-renders-entries-when-transitions-present
  (testing "with transition events in the buffer for the selected machine
            the ribbon renders one entry per transition"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
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
            (parity with the Issues ribbon + Trace panel cross-panel jump)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:auth/login])
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
