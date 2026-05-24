(ns day8.re-frame2-xray.panels.issues-ribbon-view-cljs-test
  "CLJS-side wiring + view tests for Xray's Issues panel
  (rf2-jio48 rebuild; Figma reconcile rf2-ad7zx.9; spec/021 §8).

  ## What's under test (in addition to the pure-data tests in
  `issues_ribbon_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.xray/issues-ribbon`.

    2. **Render contract** — the section + feed + per-row cells +
       data-testid wiring matches the production view tree. NO filter
       chrome (rf2-ad7zx.9 — the Figma design renders pure rows).

    3. **Focused-epoch scope** (spec/021 §1.2 + §8) — when the spine
       focuses an epoch the panel surfaces ONLY that epoch's
       `:trace-events`; refocusing changes the rendered feed.

    4. **Evicted-epoch placeholder** (spec/021 §10.7) — when focus
       pins an `:epoch-id` no longer in `:epoch-history` the panel
       paints the canonical placeholder block.

    5. **Empty states** — `:no-focus`, `:no-issues` (positive),
       `:epoch-evicted` (placeholder) each render their distinct
       container.

    6. **Sub-driven rendering** — when issues live in the focused
       epoch the panel renders one `<li>` per issue.

    7. **Per-row Figma chrome** — each row carries a 3px
       severity-coloured LEFT-BORDER, an uppercase TEXT severity badge
       in its severity colour, a muted category cell, a description, a
       RIGHT-aligned mono timestamp, and an `↗` source affordance.

    8. **No filter chrome** — the severity / prefix chip rows, the
       per-epoch counts, and the clear-filters control are GONE
       (rf2-ad7zx.9).

    9. **Row interactions** — clicking a row pivots to event-detail;
       clicking the source `↗` fires :open-in-editor and does NOT
       also pivot.

  ## Pure hiccup

  Same approach as the other Xray view tests — walk the view's
  hiccup tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.issues-ribbon :as issues-ribbon]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-test-support/reset-all!}))

;; ---- hiccup walkers ----------------------------------------------------
;; Thin aliases over re-frame.test-helpers so the local call sites read
;; identically to before.

(def ^:private find-by-testid           th/find-by-testid)

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

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
  spine resolver pairs the record with `:rf.xray/focus-cascade
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
  "Dispatch `:rf.xray/sync-epoch-history` to seed the per-frame ring
  buffer. Must be called inside `(rf/with-frame :rf/xray ...)`."
  [records]
  (rf/dispatch-sync [:rf.xray/sync-epoch-history (vec records)]))

(defn- focus!
  "Pin focus to the cascade with the given `dispatch-id`. Per
  `spine/focus-cascade-reducer` the spine resolves the matching
  `:epoch-id` from `:epoch-history`."
  [dispatch-id]
  (rf/dispatch-sync [:rf.xray/focus-cascade dispatch-id nil]))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-issues-panel-handlers
  (testing "register-xray-handlers! installs the composite sub"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/issues-ribbon))
        ":rf.xray/issues-ribbon sub registered")))

(deftest legacy-filter-machinery-is-gone
  (testing "rf2-ad7zx.9 dropped the filter chrome — the chip-filter sub +
            events MUST NOT register (the Figma design renders pure rows)"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/issues-filters))
        ":rf.xray/issues-filters sub NOT registered post-reconcile")
    (is (nil? (registrar/handler :event :rf.xray.issues/toggle-severity))
        "toggle-severity event NOT registered post-reconcile")
    (is (nil? (registrar/handler :event :rf.xray.issues/toggle-prefix))
        "toggle-prefix event NOT registered post-reconcile")
    (is (nil? (registrar/handler :event :rf.xray.issues/clear-filters))
        "clear-filters event NOT registered post-reconcile")))

(deftest legacy-since-ms-axis-is-gone
  (testing "the since-ms axis (dropped at rf2-jio48) stays gone —
            `:rf.xray.issues/set-since-seconds` MUST NOT register"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :event :rf.xray.issues/set-since-seconds))
        "since-seconds event NOT registered")))

(deftest legacy-ungrouped-lane-sub-is-gone
  (testing "the `:ungrouped` lane (dropped at rf2-jio48) stays gone —
            L2 row badges per spec/021 §1.2 carry the cross-epoch
            navigation"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray.issues/ungrouped))
        ":ungrouped lane sub NOT registered")))

;; ---- (2) defaults & render contract -----------------------------------

(deftest issues-ribbon-no-focus-empty
  (testing "with no focus + no history the composite returns
            :empty-kind :no-focus"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [data @(rf/subscribe [:rf.xray/issues-ribbon])]
        (is (= [] (:issues data)))
        (is (= 0 (:total data)))
        (is (= :no-focus (:empty-kind data)))
        (is (nil? (:epoch-id data)))))))

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of focus state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-xray-issues-ribbon"))
            "panel container present")))))

(deftest no-filter-chrome-renders
  (testing "rf2-ad7zx.9 — the panel renders NO filter chrome: no chip
            rows, no counts, no clear-filters control (the Figma design
            renders pure rows)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11 [(mk-issue {:id 1 :op-type :error
                                    :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-issues-severity-chips"))
            "severity chip row gone")
        (is (nil? (find-by-testid tree "rf-xray-issues-prefix-chips"))
            "prefix chip row gone")
        (is (nil? (find-by-testid tree "rf-xray-issues-counts"))
            "per-epoch counts gone")
        (is (nil? (find-by-testid tree "rf-xray-issues-clear-filters"))
            "clear-filters control gone")
        (is (nil? (find-by-testid tree "rf-xray-issues-epoch-chip"))
            "header epoch chip gone")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-focus-renders
  (testing "with no focused epoch the panel renders the :no-focus
            empty-state — the cold-start surface"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-xray-issues-empty-no-focus"))
            ":no-focus empty-state container present")
        (is (nil? (find-by-testid tree "rf-xray-issues-feed"))
            "no feed list when no focus")))))

(deftest empty-state-no-issues-renders
  (testing "with a focused epoch carrying no issues the panel renders
            the :no-issues empty-state (the 'No issues in this epoch.'
            positive-state per spec/021 §8.2)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [data @(rf/subscribe [:rf.xray/issues-ribbon])
            tree (issues-ribbon/Panel)]
        (is (= :no-issues (:empty-kind data)))
        (is (some? (find-by-testid tree "rf-xray-issues-empty-no-issues"))
            ":no-issues empty-state container present")
        (is (nil? (find-by-testid tree "rf-xray-issues-feed"))
            "no feed list when focused epoch carries no issues")))))

(deftest empty-state-epoch-evicted-renders
  (testing "spec/021 §10.7 — when focus pins an :epoch-id no longer in
            :epoch-history the panel paints the canonical evicted-epoch
            placeholder"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; Seed history with epoch 1, then explicitly mutate the
      ;; per-frame app-db focus to pin :epoch-id 999 (evicted).
      (seed-history! [(mk-epoch 1 11 [])])
      ;; Force the focus map onto an epoch-id that's not in history.
      (rf/dispatch-sync
        [:day8.re-frame2-xray.panels.issues-ribbon-view-cljs-test/seed-evicted-focus])
      (let [data @(rf/subscribe [:rf.xray/issues-ribbon])
            tree (issues-ribbon/Panel)]
        (is (= :epoch-evicted (:empty-kind data))
            "composite signals :epoch-evicted when no record matches focus")
        (is (some? (find-by-testid tree "rf-xray-issues-empty-epoch-evicted"))
            "canonical placeholder container rendered")
        (is (nil? (find-by-testid tree "rf-xray-issues-feed"))
            "no feed list when the focused epoch has been evicted")))))

;; ---- (4) sub-driven rendering -------------------------------------------

(deftest feed-list-renders-when-focused-epoch-has-issues
  (testing "with issues in the focused epoch's :trace-events the panel
            renders the <ul> feed with one <li> per issue"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-exception})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/missing})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-xray-issues-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-xray-issues-row-1"))
            "row for issue id 1 present")
        (is (some? (find-by-testid tree "rf-xray-issues-row-2"))
            "row for issue id 2 present")))))

;; ---- (5) per-row Figma chrome (rf2-ad7zx.9) ----------------------------

(deftest each-row-surfaces-figma-cells
  (testing "every row surfaces the severity badge + category +
            description + RIGHT-aligned timestamp"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 3 :op-type :error
                               :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-xray-issues-row-3-severity"))
            "row severity badge present")
        (is (some? (find-by-testid tree "rf-xray-issues-row-3-category"))
            "row category cell present")
        (is (some? (find-by-testid tree "rf-xray-issues-row-3-description"))
            "row description cell present")
        (is (some? (find-by-testid tree "rf-xray-issues-row-3-time"))
            "row timestamp cell present")))))

(deftest severity-badge-is-uppercase-text-in-severity-colour
  (testing "rf2-ad7zx.9 — the severity cell is an uppercase TEXT badge
            (ERROR / WARNING / ADVISORY) coloured by severity, NOT a
            glyph"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-exception})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/missing-doc})
                    (mk-issue {:id 3 :op-type :info
                               :operation :rf.info/note})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)
            badge (fn [id] (find-by-testid tree (str "rf-xray-issues-row-" id "-severity")))]
        (is (= "ERROR"    (last (badge 1))) "error badge text uppercase")
        (is (= "WARNING"  (last (badge 2))) "warning badge text uppercase")
        (is (= "ADVISORY" (last (badge 3))) "advisory badge text uppercase")
        (is (= (h/severity-colour :error)
               (-> (badge 1) second :style :color))
            "error badge painted in the error severity colour")
        (is (= (h/severity-colour :warning)
               (-> (badge 2) second :style :color))
            "warning badge painted in the warning severity colour")
        (is (= (h/severity-colour :advisory)
               (-> (badge 3) second :style :color))
            "advisory badge painted in the advisory severity colour")))))

(deftest each-row-carries-severity-left-border
  (testing "rf2-ad7zx.9 — each row carries a 3px severity-coloured
            LEFT-BORDER per the Figma design"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-exception})
                    (mk-issue {:id 2 :op-type :info
                               :operation :rf.info/note})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)
            row  (fn [id] (find-by-testid tree (str "rf-xray-issues-row-" id)))]
        (is (= (str "3px solid " (h/severity-colour :error))
               (-> (row 1) second :style :border-left))
            "error row left-border is 3px in the error colour")
        (is (= (str "3px solid " (h/severity-colour :advisory))
               (-> (row 2) second :style :border-left))
            "advisory row left-border is 3px in the advisory colour")))))

(deftest timestamp-is-right-aligned-mono
  (testing "rf2-ad7zx.9 — the timestamp cell is mono + RIGHT-aligned
            (Figma drops the legacy left-aligned timestamp)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11 [(mk-issue {:id 7 :op-type :error
                                    :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)
            ts   (find-by-testid tree "rf-xray-issues-row-7-time")]
        (is (= "right" (-> ts second :style :text-align))
            "timestamp is right-aligned")))))

;; ---- (5b) Figma fidelity — rf2-tha26 -----------------------------------

(deftest description-column-fills-available-width
  (testing "rf2-tha26 — the description column is the SOLE flexible track
            (`minmax(0, 1fr)` = Figma `flex-1`); the category column is a
            FIXED width so a long category no longer starves the
            description into a `:r…` ellipsis when the row has room"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11 [(mk-issue {:id 1 :op-type :error
                                    :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)
            cols (-> (find-by-testid tree "rf-xray-issues-row-1")
                     second :style :grid-template-columns)]
        (is (string? cols) "row uses a grid template")
        (is (re-find #"minmax\(0, 1fr\)" cols)
            "the description track is `minmax(0, 1fr)` (fills the row)")
        ;; The category column is a fixed width — NOT a fractional track
        ;; that competes with the description for flexible space.
        (is (re-find #"144px" cols)
            "the category column is a fixed width (Figma w-36)")
        (is (not (re-find #"0\.6fr" cols))
            "the prior fractional category track (`0.6fr`) is gone")))))

(deftest footer-hint-renders-below-the-feed
  (testing "rf2-tha26 — the Figma footer hint renders below the feed
            naming the row affordances (click a row → Event panel · click
            ↗ → source file:line)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11 [(mk-issue {:id 1 :op-type :error
                                    :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)
            hint (find-by-testid tree "rf-xray-issues-footer-hint")]
        (is (some? hint) "footer hint renders in the feed branch")
        (is (= "click a row → Event panel (the cascade) · click ↗ → source file:line"
               (last hint))
            "footer hint carries the reference copy")))))

(deftest footer-hint-absent-in-empty-states
  (testing "rf2-tha26 — the footer hint is scoped to the feed branch; it
            does NOT render when the panel shows an empty-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [tree (issues-ribbon/Panel)]
        (is (some? (find-by-testid tree "rf-xray-issues-empty-no-issues"))
            "empty-state renders")
        (is (nil? (find-by-testid tree "rf-xray-issues-footer-hint"))
            "footer hint absent in the empty-state branch")))))

;; ---- (6) focused-epoch scope (spec/021 §1.2 + §8) ---------------------

(deftest issues-panel-scopes-to-focused-epoch
  (testing "with two epochs in history the composite renders only
            issues from the spine's focused epoch — strict focused-
            epoch scope per spec/021 §1.2"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-exception})
                    (mk-issue {:id 2 :op-type :warning
                               :operation :rf.warning/recoverable})])
         (mk-epoch 2 12
                   [(mk-issue {:id 3 :op-type :error
                               :operation :rf.error/handler-exception})
                    (mk-issue {:id 4 :op-type :warning
                               :operation :rf.warning/recoverable})])])
      ;; Pin the spine focus to epoch 1 (defaults to head = 2 in LIVE).
      (focus! 11)
      (let [data @(rf/subscribe [:rf.xray/issues-ribbon])]
        (is (= 2 (:total data))
            "focused-epoch total reflects only the focused epoch")
        (is (= #{1 2} (set (map :id (:issues data))))
            "only issues from the focused epoch pass")))))

(deftest issues-panel-rebinds-when-focus-changes
  (testing "clicking a different L2 event re-renders the Issues panel
            for the newly-focused epoch"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 1 :op-type :error
                               :operation :rf.error/handler-exception})])
         (mk-epoch 2 12
                   [(mk-issue {:id 2 :op-type :error
                               :operation :rf.error/handler-exception})])])
      ;; Initially focus epoch 1.
      (focus! 11)
      (let [data-a @(rf/subscribe [:rf.xray/issues-ribbon])]
        (is (= [1] (mapv :id (:issues data-a)))))
      ;; Pivot focus to epoch 2.
      (focus! 12)
      (let [data-b @(rf/subscribe [:rf.xray/issues-ribbon])]
        (is (= [2] (mapv :id (:issues data-b))))))))

;; ---- (7) row interactions ------------------------------------------------

(deftest row-click-pivots-to-event-tab
  (testing "clicking an issue row dispatches :rf.xray/select-tab :event
            so the operator pivots to the Event panel for the cascade"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-issue {:id 4 :op-type :error
                               :operation :rf.error/handler-exception})])])
      (focus! 11)
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (issues-ribbon/Panel)
                row     (find-by-testid tree "rf-xray-issues-row-4")
                handler (:on-click (second row))]
            (is (some? row) "row node present in rendered tree")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.xray/select-tab :event] %) @dispatches)
            "select-tab fired to flip the visible tab to Event")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord `↗` fires :rf.xray/open-in-editor;
            stopPropagation prevents the row's pivot from also firing"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; Issue whose :rf.trace/trigger-handler carries source-coord.
      (let [iss (-> (mk-issue {:id 8 :op-type :error
                               :operation :rf.error/handler-exception})
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
                node    (find-by-testid tree "rf-xray-issues-row-8-source")
                handler (:on-click (second node))]
            (is (some? node) "source-coord `↗` rendered")
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/open-in-editor (first ev))
                         (= {:source-coord "events.cljs:17"} (second ev))))
                  @dispatches)
            ":rf.xray/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called so the row's pivot
                       handler doesn't also fire")))))

;; ---- evicted-focus helper ---------------------------------------------

;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path. Production
;; only reaches this state when the framework's `:epoch-history`
;; setting caps the buffer and older epochs roll off; in tests we
;; synthesise the same in-memory shape directly.
(rf/reg-event-db
  :day8.re-frame2-xray.panels.issues-ribbon-view-cljs-test/seed-evicted-focus
  (fn [db _event]
    (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})))
