(ns day8.re-frame2-causa.panels.issues-ribbon-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Issues panel
  (rf2-jio48 rebuild; spec/021 §8).

  ## What's under test (in addition to the pure-data tests in
  `issues_ribbon_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/issues-ribbon` + every filter event.

    2. **Render contract** — the section + header + chip rows +
       counts + data-testid wiring matches the production view tree.

    3. **Focused-epoch scope** (spec/021 §1.2 + §8) — when the spine
       focuses an epoch the panel surfaces ONLY that epoch's
       `:trace-events`; refocusing changes the rendered feed.

    4. **Evicted-epoch placeholder** (spec/021 §10.7) — when focus
       pins an `:epoch-id` no longer in `:epoch-history` the panel
       paints the canonical placeholder block.

    5. **Empty states** — `:no-focus`, `:no-issues` (positive),
       `:epoch-evicted` (placeholder), `:no-matches` (filters hide
       everything) each render their distinct container.

    6. **Sub-driven rendering** — when issues live in the focused
       epoch the panel renders one `<li>` per issue.

    7. **Severity filter** — `:rf.causa.issues/toggle-severity` adds/
       removes severities from the active set and the rendered rows
       narrow to match.

    8. **Prefix filter** — `:rf.causa.issues/toggle-prefix` works the
       same way for category prefixes.

    9. **Row interactions** — clicking a row pivots to event-detail;
       clicking the source chip fires :open-in-editor and does NOT
       also pivot.

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
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.panels.issues-ribbon :as issues-ribbon]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-test-support/reset-all!}))

;; ---- hiccup walkers ----------------------------------------------------
;; Thin aliases over re-frame.test-helpers so the local call sites read
;; identically to before.

(def ^:private find-by-testid           th/find-by-testid)

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; Synthetic issue trace event — `mk-issue` is the per-`:trace-events`
;; shape (lives INSIDE an `:rf/epoch-record`). The panel scopes by
;; epoch record, so issues are seeded by attaching them to a record's
;; `:trace-events` slot — not pushed to the trace bus.
(defn- mk-issue
  [{:keys [id time op-type operation reason]
    :or   {time 1000}}]
  (cond-> {:id        id
           :time      time
           :op-type   op-type
           :operation operation
           :tags      (cond-> {}
                        reason (assoc :reason reason))}))

(defn- mk-epoch
  "Build a minimal `:rf/epoch-record` shape carrying the supplied
  `trace-events`. `dispatch-id` defaults to (10 + epoch-id) so the
  spine resolver pairs the record with `:rf.causa/focus-cascade
  <dispatch-id>` deterministically."
  ([epoch-id trace-events]
   (mk-epoch epoch-id (+ 10 epoch-id) trace-events))
  ([epoch-id dispatch-id trace-events]
   {:epoch-id      epoch-id
    :dispatch-id   dispatch-id
    :event-id      :test/event
    :trigger-event [:test/event]
    :db-before     {}
    :db-after      {}
    :renders       []
    :sub-runs      []
    :committed-at  (* 1000 epoch-id)
    :trace-events  (vec trace-events)}))

(defn- seed-history!
  "Dispatch `:rf.causa/sync-epoch-history` to seed the per-frame ring
  buffer. Must be called inside `(rf/with-frame :rf/causa ...)`."
  [records]
  (rf/dispatch-sync [:rf.causa/sync-epoch-history (vec records)]))

(defn- focus!
  "Pin focus to the cascade with the given `dispatch-id`. Per
  `spine/focus-cascade-reducer` the spine resolves the matching
  `:epoch-id` from `:epoch-history`."
  [dispatch-id]
  (rf/dispatch-sync [:rf.causa/focus-cascade dispatch-id nil]))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-issues-panel-handlers
  (testing "register-causa-handlers! installs the rf2-jio48 rebuild's
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
    (is (some? (registrar/handler :event :rf.causa.issues/clear-filters))
        ":rf.causa.issues/clear-filters event registered")))

(deftest legacy-since-ms-axis-is-gone
  (testing "rf2-jio48 dropped the since-ms axis (focused-epoch scoping
            makes it meaningless) — `:rf.causa.issues/set-since-seconds`
            MUST NOT register"
    (registry/register-causa-handlers!)
    (is (nil? (registrar/handler :event :rf.causa.issues/set-since-seconds))
        "since-seconds event NOT registered post-rebuild")))

(deftest legacy-ungrouped-lane-sub-is-gone
  (testing "rf2-jio48 dropped the `:ungrouped` lane (focused-epoch
            scoping renders it redundant — L2 row badges per spec/021
            §1.2 carry the cross-epoch navigation)"
    (registry/register-causa-handlers!)
    (is (nil? (registrar/handler :sub :rf.causa.issues/ungrouped))
        ":ungrouped lane sub NOT registered post-rebuild")))

;; ---- (2) defaults & render contract -----------------------------------

(deftest issues-ribbon-no-focus-empty
  (testing "with no focus + no history the composite returns
            :empty-kind :no-focus"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= [] (:issues data)))
        (is (= 0 (:total data)))
        (is (= :no-focus (:empty-kind data)))
        (is (nil? (:epoch-id data)))))))

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of focus state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-causa-issues-ribbon"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-issues-counts"))
            "counts span in header present")
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chips"))
            "severity chip row present")
        ;; rf2-ezx8w · spec/021 §17.1.5 — per-panel header icon.
        (is (some? (find-by-testid tree "rf-causa-issues-panel-icon"))
            "panel header icon (⚠ in :red) present")))))

(deftest since-input-removed
  (testing "rf2-jio48 dropped the since-ms axis — the since input MUST
            NOT render in the header"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (nil? (find-by-testid (issues-ribbon/Panel)
                                "rf-causa-issues-since-input"))
          "since-ms input is gone post-rebuild"))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-focus-renders
  (testing "with no focused epoch the panel renders the :no-focus
            empty-state — the cold-start surface"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-causa-issues-empty-no-focus"))
            ":no-focus empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when no focus")))))

(deftest empty-state-no-issues-renders
  (testing "with a focused epoch carrying no issues the panel renders
            the :no-issues empty-state (the 'No issues in this epoch.'
            positive-state per spec/021 §8.2)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])
            tree (issues-ribbon/Panel)]
        (is (= :no-issues (:empty-kind data)))
        (is (some? (find-by-testid tree "rf-causa-issues-empty-no-issues"))
            ":no-issues empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when focused epoch carries no issues")))))

(deftest empty-state-epoch-evicted-renders
  (testing "spec/021 §10.7 — when focus pins an :epoch-id no longer in
            :epoch-history the panel paints the canonical evicted-epoch
            placeholder"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed history with epoch 1, then explicitly mutate the
      ;; per-frame app-db focus to pin :epoch-id 999 (evicted).
      (seed-history! [(mk-epoch 1 11 [])])
      ;; Force the focus map onto an epoch-id that's not in history.
      (rf/dispatch-sync
        [:day8.re-frame2-causa.panels.issues-ribbon-view-cljs-test/seed-evicted-focus])
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])
            tree (issues-ribbon/Panel)]
        (is (= :epoch-evicted (:empty-kind data))
            "composite signals :epoch-evicted when no record matches focus")
        (is (some? (find-by-testid tree "rf-causa-issues-empty-epoch-evicted"))
            "canonical placeholder container rendered")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when the focused epoch has been evicted")))))

(deftest empty-state-no-matches-renders-when-filters-hide-all
  (testing "with issues in the focused epoch but a chip filter that
            matches nothing the panel renders the :no-matches empty-
            state with clear-filters button"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 11)
      ;; Toggle in a severity the issue doesn't carry — filter excludes it.
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :advisory])
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-causa-issues-empty-no-matches"))
            ":no-matches empty-state container present")
        (is (some? (find-by-testid tree "rf-causa-issues-empty-clear-filters"))
            "clear-filters button surfaces in :no-matches state")
        (is (nil? (find-by-testid tree "rf-causa-issues-feed"))
            "no feed list when no rows survive filtering")))))

;; ---- (4) sub-driven rendering -------------------------------------------

(deftest feed-list-renders-when-focused-epoch-has-issues
  (testing "with issues in the focused epoch's :trace-events the panel
            renders the <ul> feed with one <li> per issue"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/missing})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-causa-issues-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-1"))
            "row for issue id 1 present")
        (is (some? (find-by-testid tree "rf-causa-issues-row-2"))
            "row for issue id 2 present")))))

(deftest header-surfaces-focused-epoch-id
  (testing "the header carries the focused epoch's id chip so the
            operator sees which epoch is in view"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 42 142
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 142)
      (let [tree (issues-ribbon/Panel)
            chip (find-by-testid tree "rf-causa-issues-epoch-chip")]
        (is (some? chip)
            "epoch chip rendered when focus resolves")
        (is (re-find #"#42" (last chip))
            "chip surfaces the focused :epoch-id")))))

;; ---- (5) per-row chrome ---------------------------------------------

(deftest each-row-surfaces-severity-glyph-and-category
  (testing "every row surfaces the severity glyph + the category prefix"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 3 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
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
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-error")))
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-warning")))
        (is (some? (find-by-testid tree "rf-causa-issues-severity-chip-advisory")))))))

(deftest prefix-chip-row-suppressed-when-no-issues
  (testing "the prefix chip-row only renders when at least one issue
            carries a prefix — with an empty focused epoch the chip-
            row is suppressed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (is (nil? (find-by-testid (issues-ribbon/Panel)
                                "rf-causa-issues-prefix-chips"))
          "no prefix chip-row when focused epoch carries no issues"))))

(deftest prefix-chip-row-renders-when-issues-have-prefixes
  (testing "with an issue carrying a category prefix the chip-row
            renders the corresponding prefix chip"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
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
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/recoverable})
                    (mk-issue {:id 3 :op-type :info
                               :operation :rf.info/note})])])
      (focus! 11)
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
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 2 :op-type :error
                               :operation :rf.ssr/hydration-mismatch})])])
      (focus! 11)
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.ssr"])
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 1 (:rendered data)))
        (is (= [2] (mapv :id (:issues data))))))))

(deftest clear-filters-button-renders-when-filter-active
  (testing "the header's Clear filters button surfaces iff at least one
            filter axis is active"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11 [(mk-issue {:id 1 :op-type :error
                                    :operation :rf.error/handler-threw})])])
      (focus! 11)
      (is (nil? (find-by-testid (issues-ribbon/Panel)
                                "rf-causa-issues-clear-filters"))
          "no Clear filters button when no axis is active")
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (is (some? (find-by-testid (issues-ribbon/Panel)
                                 "rf-causa-issues-clear-filters"))
          "Clear filters button surfaces once a severity is active"))))

;; ---- (8) focused-epoch scope (spec/021 §1.2 + §8) ---------------------

(deftest issues-panel-scopes-to-focused-epoch
  (testing "with two epochs in history the composite renders only
            issues from the spine's focused epoch — strict focused-
            epoch scope per spec/021 §1.2"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/recoverable})])
         (mk-epoch 2 12
                   [(mk-issue {:id 3 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 4 :op-type :warning
                               :operation :rf.warning/recoverable})])])
      ;; Pin the spine focus to epoch 1 (defaults to head = 2 in LIVE).
      (focus! 11)
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 2 (:total data))
            "focused-epoch total reflects only the focused epoch")
        (is (= #{1 2} (set (map :id (:issues data))))
            "only issues from the focused epoch pass")))))

(deftest issues-panel-rebinds-when-focus-changes
  (testing "clicking a different L2 event re-renders the Issues panel
            for the newly-focused epoch"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})])
         (mk-epoch 2 12
                   [(mk-issue {:id 2 :op-type :error
                               :operation :rf.error/handler-threw})])])
      ;; Initially focus epoch 1.
      (focus! 11)
      (let [data-a @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= [1] (mapv :id (:issues data-a)))))
      ;; Pivot focus to epoch 2.
      (focus! 12)
      (let [data-b @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= [2] (mapv :id (:issues data-b))))))))

(deftest issues-panel-focused-epoch-ands-with-chip-filters
  (testing "user chip filters AND on top of focused-epoch scope —
            both axes restrict the rendered feed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-threw})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/recoverable})])
         (mk-epoch 2 12
                   [(mk-issue {:id 3 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 11)
      (rf/dispatch-sync [:rf.causa.issues/toggle-severity :error])
      (let [data @(rf/subscribe [:rf.causa/issues-ribbon])]
        (is (= 2 (:total data)) "focused-epoch scope: 2 in epoch 1")
        (is (= 1 (:rendered data)) "severity chip narrows to 1")
        (is (= [1] (mapv :id (:issues data))))))))

;; ---- (9) row interactions ------------------------------------------------

(deftest row-click-pivots-to-event-tab
  (testing "clicking an issue row dispatches :rf.causa/select-tab :event
            so the operator pivots to the Event panel for the cascade"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 4 :op-type :error
                               :operation :rf.error/handler-threw})])])
      (focus! 11)
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (issues-ribbon/Panel)
                row     (find-by-testid tree "rf-causa-issues-row-4")
                handler (:on-click (second row))]
            (is (some? row) "row node present in rendered tree")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.causa/select-tab :event] %) @dispatches)
            "select-tab fired to flip the visible tab to Event")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord chip fires :rf.causa/open-in-editor;
            stopPropagation prevents the row's pivot from also firing"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Issue whose :rf.trace/trigger-handler carries source-coord.
      (let [iss (-> (mk-issue {:id 8 :op-type :error
                               :operation :rf.error/handler-threw})
                    (assoc :rf.trace/trigger-handler
                           {:source-coord {:file "events.cljs" :line 17}}))]
        (seed-history! [(mk-epoch 1 11 [iss])]))
      (focus! 11)
      (let [dispatches (atom [])
            stop-evt   (atom nil)]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (issues-ribbon/Panel)
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
      (rf/dispatch-sync [:rf.causa.issues/toggle-prefix "rf.error"]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{:error} (:issues-active-severities causa-db))
          "severities land on Causa")
      (is (= #{"rf.error"} (:issues-active-prefixes causa-db))
          "prefixes land on Causa")
      (is (nil? (:issues-active-severities default-db))
          "severities did NOT leak into :rf/default")
      (is (nil? (:issues-active-prefixes default-db))
          "prefixes did NOT leak into :rf/default"))))

;; ---- evicted-focus helper ---------------------------------------------

;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path. Production
;; only reaches this state when the framework's `:epoch-history`
;; setting caps the buffer and older epochs roll off; in tests we
;; synthesise the same in-memory shape directly.
(rf/reg-event-db
  :day8.re-frame2-causa.panels.issues-ribbon-view-cljs-test/seed-evicted-focus
  (fn [db _event]
    (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})))
