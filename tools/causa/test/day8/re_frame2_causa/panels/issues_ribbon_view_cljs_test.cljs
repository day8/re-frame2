(ns day8.re-frame2-causa.panels.issues-ribbon-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Issues ribbon panel
  (Phase 5, rf2-d1p4o; view-test coverage filed under rf2-zvrbw).

  ## What's under test (in addition to the pure-data tests in
  `issues_ribbon_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/issues-ribbon` + every filter event.

    2. **Render contract** — the section + header + chip rows + since
       input + counts + data-testid wiring matches the production view
       tree.

    3. **Empty states** — `:no-issues` (the desired state — All clear)
       and `:no-matches` (filters hide everything) each render their
       distinct container.

    4. **Sub-driven rendering** — when issues are in the buffer the
       panel renders one `<li>` per issue.

    5. **Issue category pills** — every issue surfaces a category
       prefix; the prefix chip-row only renders when at least one
       prefix has issues.

    6. **Severity filter** — `:rf.causa.issues/toggle-severity` adds/
       removes severities from the active set and the rendered rows
       narrow to match.

    7. **Prefix filter** — `:rf.causa.issues/toggle-prefix` works the
       same way for category prefixes.

    8. **Since-ms filter** — `:rf.causa.issues/set-since-seconds`
       sets/clears the time window.

    9. **Row interactions** — clicking a row pivots to event-detail
       when the dispatch-id is present; clicking the source chip
       fires :open-in-editor and does NOT also pivot.

   10. **Frame isolation** — the panel's filter state lives on
       `:rf/causa`, never on `:rf/default`.

  ## Pure hiccup

  Same approach as the other Causa view tests — walk the view's
  hiccup tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror machine_inspector_view_cljs_test) -----------

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
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

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

(defn- push-trace! [ev]
  ;; Per rf2-e9s81: `:rf.causa/trace-buffer` thunks the trace-bus
  ;; atom; pushing via `collect-trace!` (the production path) lands
  ;; the event in the atom and the next subscribe sees it.
  (trace-bus/collect-trace! ev))

;; Synthetic issue trace event.
(defn- mk-issue
  [{:keys [id time op-type operation dispatch-id reason]
    :or   {time 1000}}]
  {:id        id
   :time      time
   :op-type   op-type
   :operation operation
   :tags      (cond-> {}
                dispatch-id (assoc :dispatch-id dispatch-id)
                reason      (assoc :reason reason))})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-issues-ribbon-handlers
  (testing "register-causa-handlers! installs the Phase 5 (rf2-d1p4o)
            composite sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/issues-ribbon))
        ":rf.causa/issues-ribbon sub registered")
    (is (some? (registrar/handler :sub :rf.causa/issues-filters))
        ":rf.causa/issues-filters sub registered")
    (is (some? (registrar/handler :event :rf.causa.issues/toggle-severity))
        ":rf.causa.issues/toggle-severity event registered")
    (is (some? (registrar/handler :event :rf.causa.issues/toggle-prefix))
        ":rf.causa.issues/toggle-prefix event registered")
    (is (some? (registrar/handler :event :rf.causa.issues/set-since-seconds))
        ":rf.causa.issues/set-since-seconds event registered")
    (is (some? (registrar/handler :event :rf.causa.issues/clear-filters))
        ":rf.causa.issues/clear-filters event registered")))

(deftest issues-ribbon-defaults-to-no-issues
  (testing "with no events the composite returns empty-kind :no-issues"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= [] (:issues data)))
        (is (= 0 (:total data)))
        (is (= :no-issues (:empty-kind data)))))))

(deftest issues-ribbon-filters-non-issue-events
  (testing "success-path traces (`:op-type :event`, `:fx`, `:frame`,
            `:sub/run`, `:view/render`) are NOT issues and never reach
            the ribbon — only `:error / :warning / :info` do"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Three non-issue events + two real issues.
      (push-trace! (mk-issue {:id 1 :op-type :event :operation :event/dispatched}))
      (push-trace! (mk-issue {:id 2 :op-type :fx    :operation :rf.fx/handled}))
      (push-trace! (mk-issue {:id 3 :op-type :view  :operation :view/render}))
      (push-trace! (mk-issue {:id 4 :op-type :error :operation :rf.error/handler-threw
                              :dispatch-id 1 :reason "kaboom"}))
      (push-trace! (mk-issue {:id 5 :op-type :warning :operation :rf.warning/recoverable
                              :dispatch-id 1}))
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 2 (:total data)) "only issues land on the ribbon")
        (is (= #{4 5} (set (map :id (:issues data)))))))))

;; ---- (2) render contract ------------------------------------------------

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of buffer state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-ribbon"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-issues-counts"))
            "counts span in header present")
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chips"))
            "severity chip row present")
        (is (some? (find-by-testid tree "rf-causa-issues-since-input"))
            "since-ms input present")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-issues-renders
  (testing "with no issues the panel renders the :no-issues empty-state
            (the 'All clear' positive-result branch)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-empty-no-issues"))
            ":no-issues empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when buffer carries no issues")))))

(deftest empty-state-no-matches-renders-when-filters-hide-all
  (testing "with issues present but a filter that matches nothing the
            panel renders the :no-matches empty-state with clear button"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 1 :op-type :error
                              :operation :rf.error/handler-threw}))
      ;; Toggle in a severity the issue doesn't carry — filter excludes it.
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :advisory])
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-empty-no-matches"))
            ":no-matches empty-state container present")
        (is (some? (find-by-testid tree "rf-causa-issues-empty-clear-filters"))
            "clear-filters button surfaces in :no-matches state")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when no rows survive filtering")))))

;; ---- (4) sub-driven rendering -------------------------------------------

(deftest feed-list-renders-when-issues-present
  (testing "with issues in the buffer the panel renders the <ul> feed
            with one <li> per issue"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 1 :op-type :error
                              :operation :rf.error/handler-threw
                              :dispatch-id 1}))
      (push-trace! (mk-issue {:id 2 :op-type :warning
                              :operation :rf.warning/missing
                              :dispatch-id 1}))
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-1"))
            "row for issue id 1 present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-2"))
            "row for issue id 2 present")))))

;; ---- (5) issue category pills -------------------------------------------

(deftest each-row-surfaces-severity-glyph-and-category
  (testing "every row surfaces the severity glyph + the category prefix"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 3 :op-type :error
                              :operation :rf.error/handler-threw}))
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-row-3-time"))
            "row timestamp span present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-3-severity"))
            "row severity glyph present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-3-category"))
            "row category prefix span present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-3-description"))
            "row description span present")))))

(deftest severity-chip-row-renders-three-buckets
  (testing "the severity chip-row renders one chip per bucket in
            severity-order — error / warning / advisory"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push at least one issue so any-filter? is false initially.
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-error")))
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-warning")))
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-advisory")))))))

(deftest prefix-chip-row-suppressed-when-no-issues
  (testing "the prefix chip-row only renders when at least one issue
            carries a prefix — with an empty buffer the chip-row is
            suppressed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; No issues — prefix chip-row suppressed.
      (is (nil? (find-by-testid (issues-ribbon/issues-ribbon-view)
                                "rf-causa-issues-prefix-chips"))
          "no prefix chip-row when buffer carries no issues"))))

(deftest prefix-chip-row-renders-when-issues-have-prefixes
  (testing "with an issue carrying a category prefix the chip-row
            renders the corresponding prefix chip. Seed the trace
            buffer BEFORE the first subscribe — mirrors the production
            sequencing where preload's trace-cb fires before any panel
            mounts."
    (setup-causa-frame!)
    (push-trace! (mk-issue {:id 1 :op-type :error
                            :operation :rf.error/handler-threw}))
    (rf/with-frame :rf/causa
      (let [tree (issues-ribbon/issues-ribbon-view)]
        (is (some? (find-by-testid tree "rf-causa-issues-prefix-chips"))
            "prefix chip-row renders once at least one prefix exists")
        (is (some? (find-by-testid tree "rf-causa-issues-prefix-chip-rf.error"))
            "rf.error prefix chip surfaces")))))

;; ---- (6) severity filter ------------------------------------------------

(deftest toggle-severity-mutates-causa-frame
  (testing ":rf.causa.issues/toggle-severity toggles set membership on
            the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (= #{:error}
             (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :warning])
      (is (= #{:error :warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (= #{:warning}
             (:severities @(rf/subscribe [:rf.causa/issues-filters])))
          "second toggle removes the severity"))))

(deftest severity-filter-narrows-rendered-rows
  (testing "an active severity filter cuts the row list down to matching rows"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 1 :op-type :error
                              :operation :rf.error/handler-threw}))
      (push-trace! (mk-issue {:id 2 :op-type :warning
                              :operation :rf.warning/recoverable}))
      (push-trace! (mk-issue {:id 3 :op-type :info
                              :operation :rf.info/note}))
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :warning])
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 3 (:total data)))
        (is (= 1 (:rendered data)))
        (is (= [2] (mapv :id (:issues data))))))))

;; ---- (7) prefix filter --------------------------------------------------

(deftest toggle-prefix-mutates-causa-frame
  (testing ":rf.causa.issues/toggle-prefix toggles set membership on
            the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.error"])
      (is (= #{"rf.error"}
             (:prefixes @(rf/subscribe [:rf.causa/issues-filters]))))
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.error"])
      (is (= #{} (:prefixes @(rf/subscribe [:rf.causa/issues-filters])))
          "second toggle removes the prefix"))))

(deftest prefix-filter-narrows-rendered-rows
  (testing "an active prefix filter cuts the row list down to matching prefixes"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 1 :op-type :error
                              :operation :rf.error/handler-threw}))
      (push-trace! (mk-issue {:id 2 :op-type :error
                              :operation :rf.ssr/hydration-mismatch}))
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.ssr"])
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 1 (:rendered data)))
        (is (= [2] (mapv :id (:issues data))))))))

;; ---- (8) since-ms filter ------------------------------------------------

(deftest set-since-seconds-converts-to-ms
  (testing ":rf.causa.issues/set-since-seconds takes seconds and stores ms"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 30])
      (is (= 30000 (:since-ms @(rf/subscribe [:rf.causa/issues-filters])))
          "30s stored as 30000ms")
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds nil])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/issues-filters])))
          "nil clears the axis")
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 0])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/issues-filters])))
          "0 / non-positive clears the axis"))))

(deftest clear-issues-filters-drops-every-axis
  (testing ":rf.causa.issues/clear-filters drops all three axes in one shot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.error"])
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 60])
      (rf/dispatch-sync [:rf.causa.issues/clear-filters])
      (let [filters @(rf/subscribe [:rf.causa/issues-filters])]
        (is (or (nil? (:severities filters)) (empty? (:severities filters))))
        (is (or (nil? (:prefixes filters)) (empty? (:prefixes filters))))
        (is (nil? (:since-ms filters)))))))

(deftest clear-filters-button-renders-when-filter-active
  (testing "the header's Clear filters button surfaces iff at least one
            filter axis is active"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 1 :op-type :error
                              :operation :rf.error/handler-threw}))
      (is (nil? (find-by-testid (issues-ribbon/issues-ribbon-view)
                                "rf-causa-issues-clear-filters"))
          "no Clear filters button when no axis is active")
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (some? (find-by-testid (issues-ribbon/issues-ribbon-view)
                                 "rf-causa-issues-clear-filters"))
          "Clear filters button surfaces once a severity is active"))))

;; ---- (9) row interactions ------------------------------------------------

(deftest row-click-pivots-to-event-detail-when-dispatch-id-present
  (testing "clicking an issue row dispatches :rf.causa/select-dispatch-id
            and :rf.causa/select-panel — same cross-panel pivot as the
            other ribbons"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-issue {:id 4 :op-type :error
                              :operation :rf.error/handler-threw
                              :dispatch-id 99}))
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (issues-ribbon/issues-ribbon-view)
                row     (find-by-testid tree "rf-causa-issues-row-4")
                handler (:on-click (second row))]
            (is (some? row) "row node present in rendered tree")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.causa/select-dispatch-id 99] %) @dispatches)
            "select-dispatch-id fired with the issue's dispatch-id")
        (is (some #(= [:rf.causa/select-panel :event-detail] %) @dispatches)
            "select-panel fired to pivot")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord chip fires :rf.causa/open-in-editor;
            stopPropagation prevents the row's pivot from also firing"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push an issue whose :rf.trace/trigger-handler carries source-coord.
      (push-trace! (-> (mk-issue {:id 8 :op-type :error
                                  :operation :rf.error/handler-threw
                                  :dispatch-id 1})
                       (assoc :rf.trace/trigger-handler
                              {:source-coord {:file "events.cljs" :line 17}})))
      (let [dispatches (atom [])
            stop-evt   (atom nil)]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (issues-ribbon/issues-ribbon-view)
                node    (find-by-testid tree "rf-causa-issues-row-8-source")
                handler (:on-click (second node))]
            (is (some? node) "source-coord chip rendered")
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.causa/open-in-editor (first ev))
                         (= {:source-coord "events.cljs:17"} (second ev))))
                  @dispatches)
            ":rf.causa/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called so the row's pivot
                       handler doesn't also fire")))))

;; ---- (10) frame isolation ----------------------------------------------

(deftest issues-filter-state-does-not-leak-into-default-frame
  (testing "the panel's filter state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (rf/dispatch-sync [:rf.causa.issues/set-since-seconds 60]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{:error} (:issues-active-severities causa-db))
          "severities land on Causa")
      (is (= 60000 (:issues-since-ms causa-db))
          "since-ms lands on Causa")
      (is (nil? (:issues-active-severities default-db))
          "severities did NOT leak into :rf/default")
      (is (nil? (:issues-since-ms default-db))
          "since-ms did NOT leak into :rf/default"))))
