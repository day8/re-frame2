(ns day8.re-frame2-causa.panels.trace-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Trace panel
  (Phase 5, rf2-argrj; view-test coverage filed under rf2-zvrbw).

  ## What's under test (in addition to the pure-data tests in
  `trace_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/trace-feed`, plus the per-axis filter events
       (`:rf.causa/set-trace-filter`, `:rf.causa/clear-trace-filters`).

    2. **Render contract** — the section + header + counts +
       data-testid wiring matches the production view tree.

    3. **Empty states** — `:no-events` and `:no-matches` branches each
       render their distinct container; the `:no-matches` branch
       carries a clear-filters affordance.

    4. **Sub-driven rendering** — when the trace buffer is populated
       the feed renders one `<li>` per row.

    5. **9-axis filter** — each canonical axis narrows the rendered
       row set (source / origin / frame / op-type / severity / event-id /
       handler-id / dispatch-id / operation) and axis combinations
       compose AND-wise.

    6. **Chip rows** — only axes with >=2 distinct values render their
       chip row (so a single-value axis isn't noise). The clear-all
       affordance surfaces whenever any filter axis is active.

    7. **Row interactions** — clicking a row with a dispatch-id pivots
       to event-detail; clicking a row chip narrows the corresponding
       axis; clicking the source-coord chip fires :open-in-editor and
       does NOT also pivot.

    8. **Frame isolation** — the panel's filter state lives on
       `:rf/causa`, never on `:rf/default`.

  ## Pure hiccup

  Same approach as `subscriptions_view_cljs_test.cljs` — walk the view's
  hiccup tree by `data-testid` rather than mounting to the DOM."
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
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.panels.trace-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror machine_inspector_view_cljs_test) ----------

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
  ;; atom; pushing via `seed-buffer-for-test!` (the test-side bypass
  ;; of the public collector) lands the event in the atom and the
  ;; next subscribe sees it. We bypass `collect-trace!` because
  ;; rf2-xs8vu's self-noise filter drops `:frame :rf/causa` events
  ;; before the buffer push — the trace Panel filter axes need to
  ;; assert against synthetic events with arbitrary `:frame` slots
  ;; (including `:rf/causa`) to lock the filter algebra, separately
  ;; from the ingest guard.
  (trace-bus/seed-buffer-for-test! ev))

;; Construct a synthetic trace event with the canonical 9-axis tags.
(defn- mk-trace
  [{:keys [id time op-type operation source origin frame
           severity event-id handler-id dispatch-id reason]
    :or   {time 1000}}]
  {:id        id
   :time      time
   :op-type   op-type
   :operation operation
   :source    source
   :tags      (cond-> {}
                origin      (assoc :origin origin)
                frame       (assoc :frame frame)
                event-id    (assoc :event-id event-id)
                handler-id  (assoc :handler-id handler-id)
                dispatch-id (assoc :dispatch-id dispatch-id)
                source      (assoc :source source)
                severity    (assoc :severity severity)
                reason      (assoc :reason reason))})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-trace-handlers
  (testing "register-causa-handlers! installs the Phase 5 (rf2-argrj)
            composite sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/trace-feed))
        ":rf.causa/trace-feed sub registered")
    (is (some? (registrar/handler :sub :rf.causa/trace-filters))
        ":rf.causa/trace-filters sub registered")
    (is (some? (registrar/handler :event :rf.causa/set-trace-filter))
        ":rf.causa/set-trace-filter event registered")
    (is (some? (registrar/handler :event :rf.causa/clear-trace-filters))
        ":rf.causa/clear-trace-filters event registered")))

(deftest trace-feed-defaults-empty
  (testing "with no events in the buffer the composite returns an empty
            feed with empty-kind :no-events"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= [] (:rows feed)))
        (is (= 0 (:total feed)))
        (is (= 0 (:rendered feed)))
        (is (false? (:any-filter? feed)))
        (is (= :no-events (:empty-kind feed)))))))

(deftest trace-feed-projects-events-into-rows
  (testing "with events in the buffer the composite returns one row per
            event with the canonical 9-axis projection"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui :origin :app :frame :rf/default
                              :event-id :cart/add :dispatch-id 42}))
      (push-trace! (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-threw
                              :source :ui :origin :app :frame :rf/default
                              :event-id :cart/add :handler-id :cart/add-handler
                              :dispatch-id 42 :reason "boom"}))
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:total feed)))
        (is (= 2 (:rendered feed)))
        (is (nil? (:empty-kind feed)))
        (is (= #{1 2} (set (map :id (:rows feed)))))))))

;; ---- (2) render contract ------------------------------------------------

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of buffer state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-trace-counts"))
            "counts span present")))))

(deftest feed-list-renders-when-events-present
  (testing "with events in the buffer the panel renders the <ul> feed
            with one <li> per row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :dispatch-id 1}))
      (push-trace! (mk-trace {:id 2 :op-type :fx :operation :rf.fx/handled
                              :dispatch-id 1}))
      (push-trace! (mk-trace {:id 3 :op-type :sub/run :operation :sub/run
                              :dispatch-id 1}))
      (let [tree (trace/Panel)
            rows (find-all-by-testid-prefix tree "rf-causa-trace-row-")]
        (is (some? (find-by-testid tree "rf-causa-trace-feed"))
            "feed <ul> present")
        ;; Each row has many sub-testids prefixed with rf-causa-trace-row-<id>-…
        ;; so filter to just the row containers (no trailing -dash).
        (is (>= (count rows) 3)
            "at least one rendered node per row")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-events-renders
  (testing "with no events the panel renders the :no-events empty-state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-events"))
            ":no-events empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list when buffer is empty")))))

(deftest empty-state-no-matches-renders-when-filters-hide-all
  (testing "with events present but a filter that matches nothing the
            panel renders the :no-matches empty-state with clear button.
            The event carries `:dispatch-id 1` so the spine has a
            focusable cascade (rf2-fzbrw strips :ungrouped from the
            focusable list; without a focusable cascade the panel
            would render :no-focus, not :no-matches)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :no-such-source])
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-matches"))
            ":no-matches empty-state container present")
        (is (some? (find-by-testid tree "rf-causa-trace-empty-clear-filters"))
            "clear-filters button surfaces in :no-matches state")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list when no rows survive filtering")))))

;; ---- (4) 9-axis filter --------------------------------------------------

(deftest set-trace-filter-writes-to-causa-frame
  (testing ":rf.causa/set-trace-filter mutates the per-axis filter map"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (is (= {:source :ui} @(rf/subscribe [:rf.causa/trace-filters])))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :origin :app])
      (is (= {:source :ui :origin :app}
             @(rf/subscribe [:rf.causa/trace-filters]))
          "second axis adds; existing axis is preserved")
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source nil])
      (is (= {:origin :app} @(rf/subscribe [:rf.causa/trace-filters]))
          "passing nil clears that axis"))))

(deftest clear-trace-filters-drops-every-axis
  (testing ":rf.causa/clear-trace-filters drops every axis in one shot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :frame :rf/default])
      (rf/dispatch-sync [:rf.causa/clear-trace-filters])
      (is (= {} @(rf/subscribe [:rf.causa/trace-filters]))
          "every filter axis cleared"))))

(deftest filter-by-source-narrows-rendered-rows
  (testing "setting :source narrows the feed to events with that source.
            All events share `:dispatch-id 1` so the rf2-ycoct cascade-
            scope is a no-op for this axis-in-isolation assertion (the
            spine's head cascade is 1, every row is in scope)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui :dispatch-id 1}))
      (push-trace! (mk-trace {:id 2 :op-type :event :operation :event/dispatched
                              :source :timer :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:total feed)))
        (is (= 1 (:rendered feed)))
        (is (= [1] (mapv :id (:rows feed))))))))

(deftest filter-by-op-type-narrows-rendered-rows
  (testing "setting :op-type narrows the feed to events with that op-type.
            All events share `:dispatch-id 1` so the rf2-ycoct cascade-
            scope is a no-op (rf2-fzbrw strips :ungrouped from focusable
            cascades — events without :dispatch-id would otherwise leave
            the spine with no focusable cascade and trigger :no-focus)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :dispatch-id 1}))
      (push-trace! (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-threw
                              :dispatch-id 1}))
      (push-trace! (mk-trace {:id 3 :op-type :fx    :operation :rf.fx/handled
                              :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :op-type :error])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 1 (:rendered feed)))
        (is (= [2] (mapv :id (:rows feed))))))))

(deftest filter-axes-compose-and-wise
  (testing "two filter axes compose AND-wise — only rows matching both
            survive. All events share `:dispatch-id 1` so the rf2-ycoct
            cascade-scope is a no-op for this composition assertion."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui    :frame :rf/default :dispatch-id 1}))
      (push-trace! (mk-trace {:id 2 :op-type :event :operation :event/dispatched
                              :source :ui    :frame :rf/causa   :dispatch-id 1}))
      (push-trace! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                              :source :timer :frame :rf/default :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :frame  :rf/default])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 1 (:rendered feed))
            "only the row matching both axes survives")
        (is (= [1] (mapv :id (:rows feed))))))))

(deftest filter-by-dispatch-id-collapses-cascade
  (testing "setting :dispatch-id slices the feed to one cascade's events.
            Per rf2-ycoct the panel is cascade-scoped to the focused
            cascade by default; here we explicitly focus 42, then the
            user's `:dispatch-id` chip-filter narrows to the same id
            (redundant in this case — both filters land on the same
            set, which is the expected workflow when the user clicks
            the dispatch-id chip on a row of the already-focused
            cascade)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :dispatch-id 42}))
      (push-trace! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                              :dispatch-id 42}))
      (push-trace! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                              :dispatch-id 99}))
      ;; Focus cascade 42 (the spine's LIVE auto-snap would land on 99 —
      ;; the head — so we pin to 42 explicitly to assert the axis
      ;; filter algebra in isolation from the auto-scope).
      (rf/dispatch-sync [:rf.causa/focus-cascade 42])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :dispatch-id 42])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:rendered feed)))
        (is (= #{1 2} (set (map :id (:rows feed)))))))))

;; ---- (5) chip rows ------------------------------------------------------

(deftest chip-row-renders-only-when-axis-has-two-plus-values
  (testing "an axis with one distinct value has nothing to filter on,
            so its chip row is suppressed; an axis with >=2 renders"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Two events with the same :source but different :op-type.
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui}))
      (push-trace! (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-threw
                              :source :ui}))
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-axis-row-op-type"))
            ":op-type has two distinct values → chip row renders")
        (is (nil? (find-by-testid tree "rf-causa-trace-axis-row-source"))
            ":source has one distinct value → chip row suppressed")))))

(deftest clear-filters-button-renders-only-when-axis-active
  (testing "the header's Clear filters affordance appears iff at least
            one filter axis is active"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui}))
      (is (nil? (find-by-testid (trace/Panel) "rf-causa-trace-clear-filters"))
          "no Clear filters button when no axis is active")
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (is (some? (find-by-testid (trace/Panel) "rf-causa-trace-clear-filters"))
          "Clear filters button surfaces once an axis is active"))))

;; ---- (6) row interactions -----------------------------------------------

(deftest row-click-pivots-to-event-detail-when-dispatch-id-present
  (testing "clicking a row whose event carries a :dispatch-id dispatches
            :rf.causa/select-dispatch-id + :rf.causa/select-panel"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 7 :op-type :event :operation :event/dispatched
                              :dispatch-id 42 :frame :rf/default}))
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (trace/Panel)
                row     (find-by-testid tree "rf-causa-trace-row-7")
                handler (:on-click (second row))]
            (is (some? row) "row node present")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.causa/select-dispatch-id 42 :rf/default] %) @dispatches)
            "select-dispatch-id fired with the row's dispatch-id and frame")
        (is (some #(= [:rf.causa/select-panel :event-detail] %) @dispatches)
            "select-panel fired to pivot")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord chip fires :rf.causa/open-in-editor;
            stopPropagation prevents the row's pivot from also firing"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push a trace whose :rf.trace/trigger-handler carries a source-coord.
      (push-trace! (-> (mk-trace {:id 9 :op-type :event :operation :event/dispatched
                                  :dispatch-id 1})
                       (assoc :rf.trace/trigger-handler
                              {:source-coord {:file "core.cljs" :line 42}})))
      (let [dispatches (atom [])
            stop-evt   (atom nil)]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (trace/Panel)
                node    (find-by-testid tree "rf-causa-trace-row-9-source-coord")
                handler (:on-click (second node))]
            (is (some? node) "source-coord chip rendered")
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.causa/open-in-editor (first ev))
                         (= {:source-coord "core.cljs:42"} (second ev))))
                  @dispatches)
            ":rf.causa/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called so the row's pivot
                       handler doesn't also fire")))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest trace-filter-state-does-not-leak-into-default-frame
  (testing "the panel's filter state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= {:source :ui} (:trace-filters causa-db))
          "filter map lands on Causa")
      (is (nil? (:trace-filters default-db))
          "filter map did NOT leak into :rf/default"))))

;; ---- (8) React-key stability across trace pushes — rf2-z4fza -----------
;;
;; Sibling of rf2-kgn0c (same React-key discipline class). The earlier
;; trace row shape keyed `<li>` on a tuple that included the row's
;; positional index inside the visible viewport. Every trace push
;; shifted every visible row's index → every key changed → React
;; unmounted + remounted the entire viewport on EVERY push — the
;; dominant frame cost under burst event rate (animation frames,
;; polling). This test pins the fix: the rendered `<li>` for any row
;; that survives a push has the SAME `:key` before and after.

(defn- row-li-by-id
  "Walk the rendered tree and return the `<li>` whose data-testid is
  `rf-causa-trace-row-<id>`. Returns nil when the row isn't rendered."
  [tree id]
  (let [testid (str "rf-causa-trace-row-" id)]
    (some (fn [node]
            (when (and (vector? node)
                       (= :li (first node))
                       (map? (second node))
                       (= testid (:data-testid (second node))))
              node))
          (hiccup-seq tree))))

(defn- sync-push!
  "Push a trace event AND synchronously flush the `:rf.causa/note-
  trace-event` mirror into `:rf/causa`'s app-db. The production
  `collect-trace!` mirror dispatches async (`rf/dispatch`); the
  layer-1 `:rf.causa/trace-buffer` sub falls back to the atom when
  the db slot is nil, which lets the first read succeed in tests.
  But once the sub has materialised, subsequent atom-only pushes
  are invisible to the cached sub. `dispatch-sync` of the note
  event forces the db update so the sub re-fires."
  [ev]
  (push-trace! ev)
  (rf/dispatch-sync [:rf.causa/note-trace-event ev]))

(deftest trace-row-react-keys-are-stable-across-pushes
  (testing "rf2-z4fza — appending a new trace event must NOT change the
            :key of any previously-rendered <li>. Same input row → same
            React key → React's reconciler reuses the DOM node instead
            of unmounting + remounting it. Mirrors rf2-kgn0c's
            v:<variant-id> discipline in the story workspace.

            All six events share `:dispatch-id 1` so the rf2-ycoct
            cascade-scope is a no-op for this key-stability assertion
            (the spine's head cascade stays 1, every row is in scope)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed three rows.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 1}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 200 :dispatch-id 1}))
      (sync-push! (mk-trace {:id 3 :op-type :sub/run :operation :sub/run
                             :time 300 :dispatch-id 1}))
      (let [tree-1   (trace/Panel)
            keys-1   {1 (:key (second (row-li-by-id tree-1 1)))
                      2 (:key (second (row-li-by-id tree-1 2)))
                      3 (:key (second (row-li-by-id tree-1 3)))}]
        (is (every? some? (vals keys-1))
            "every initial row carries a React :key")
        (is (= 3 (count (distinct (vals keys-1))))
            "initial row keys are distinct")
        ;; Push three more events in the SAME cascade — under the
        ;; broken positional-key shape, this would shift every
        ;; existing key.
        (sync-push! (mk-trace {:id 4 :op-type :event :operation :event/dispatched
                               :time 400 :dispatch-id 1}))
        (sync-push! (mk-trace {:id 5 :op-type :fx    :operation :rf.fx/handled
                               :time 500 :dispatch-id 1}))
        (sync-push! (mk-trace {:id 6 :op-type :error :operation :rf.error/handler-threw
                               :time 600 :dispatch-id 1}))
        (let [tree-2 (trace/Panel)
              keys-2 {1 (:key (second (row-li-by-id tree-2 1)))
                      2 (:key (second (row-li-by-id tree-2 2)))
                      3 (:key (second (row-li-by-id tree-2 3)))
                      4 (:key (second (row-li-by-id tree-2 4)))
                      5 (:key (second (row-li-by-id tree-2 5)))
                      6 (:key (second (row-li-by-id tree-2 6)))}]
          (doseq [id [1 2 3]]
            (is (= (get keys-1 id) (get keys-2 id))
                (str "row id " id ": React :key must be identical before "
                     "and after the burst push (pre=" (pr-str (get keys-1 id))
                     " post=" (pr-str (get keys-2 id)) "). "
                     "If this fails, the trace ribbon is re-mounting the "
                     "viewport on every push — the rf2-z4fza regression.")))
          (is (every? some? (vals keys-2))
              "every row in the post-push tree carries a React :key")
          (is (= 6 (count (distinct (vals keys-2))))
              "all six row keys remain distinct after the burst push"))))))

(deftest trace-row-react-keys-have-no-positional-component
  (testing "rf2-z4fza acceptance: the rendered <li> :key contains only
            the stable trace id, NOT a positional row-index. Pins the
            key shape so a future regression that re-introduces a
            positional component would fail loudly here."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 11 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 1}))
      (sync-push! (mk-trace {:id 22 :op-type :fx    :operation :rf.fx/handled
                             :time 200 :dispatch-id 1}))
      (let [tree (trace/Panel)
            k11  (:key (second (row-li-by-id tree 11)))
            k22  (:key (second (row-li-by-id tree 22)))]
        (is (= "t:11" k11)
            "row 11 keyed on stable trace id alone (no positional prefix)")
        (is (= "t:22" k22)
            "row 22 keyed on stable trace id alone (no positional prefix)")
        (is (string? k11)
            "key is a string (not the positional-tuple from the
             pre-rf2-z4fza shape)")))))

;; ---- (9) orphan-filter surfacing — rf2-vu0mp ---------------------------
;;
;; When the buffer rotates past the cap and the selected filter value's
;; last instance ages out, :trace-filters still carries the selection
;; but the chip-row no longer shows that value (audit F6). Per rf2-vu0mp
;; the fix:
;;
;;   1. The header's chip-row keeps rendering the active value (the
;;      helper's effective-distinct unions it back into distinct);
;;      orphan chips are marked visually (count = 0, dashed border,
;;      italic).
;;   2. The :no-matches empty state surfaces an 'narrowing on:' strip
;;      listing every active axis=value pair so the user always has
;;      an in-panel cue what is filtering the ribbon — orphan or not.

(deftest orphan-chip-renders-in-header-when-filter-value-aged-out
  (testing "rf2-vu0mp: filtering on a value not present in the buffer
            still renders an axis chip for that value so the user can
            see / toggle off their selection. The chip carries count 0."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push a single :ui-sourced event then narrow on :timer — the
      ;; :timer value is NOT in the buffer (orphan).
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :timer])
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-axis-row-source"))
            "the :source chip-row renders even though only one buffered
             distinct value exists (the active orphan brings the chip
             count to 2 — distinct :ui + orphan :timer)")
        (is (some? (find-by-testid tree "rf-causa-trace-axis-chip-source-timer"))
            "an orphan chip for the active :timer selection renders")))))

(deftest orphan-empty-state-surfaces-active-filter-strip
  (testing "rf2-vu0mp: the :no-matches empty state surfaces an
            'narrowing on:' strip listing each active axis=value pair
            so the user always sees what is filtering the ribbon.
            Events carry `:dispatch-id 1` so the spine has a focusable
            cascade (rf2-fzbrw)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push two events that won't match the filter.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :origin :app :frame :rf/default
                             :dispatch-id 1}))
      (sync-push! (mk-trace {:id 2 :op-type :event :operation :event/dispatched
                             :source :ui :origin :app :frame :rf/default
                             :dispatch-id 1}))
      ;; Narrow on a value the buffer doesn't carry — orphan.
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :timer])
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-matches"))
            ":no-matches empty state renders")
        (is (some? (find-by-testid tree "rf-causa-trace-empty-active-filters"))
            "rf2-vu0mp: active-filters strip renders inside no-matches")
        (is (some? (find-by-testid tree "rf-causa-trace-empty-active-source"))
            "a pill for the :source axis surfaces")))))

(deftest active-filter-pill-click-drops-the-axis
  (testing "rf2-vu0mp: clicking an active-filter pill drops that axis
            from the filter map (lets the user clear an orphan without
            hunting through the header). Event carries `:dispatch-id 1`
            so the spine has a focusable cascade (rf2-fzbrw)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default
                             :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :timer])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :frame :rf/missing])
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (trace/Panel)
                pill    (find-by-testid tree "rf-causa-trace-empty-active-source")
                handler (:on-click (second pill))]
            (is (some? pill) ":source pill rendered")
            (when handler (handler))))
        (is (some #(= [:rf.causa/set-trace-filter :source nil] %) @dispatches)
            "clicking the pill fires set-trace-filter with nil-value
             (the canonical 'drop this axis' shape)")))))

(deftest empty-state-active-filter-pill-marks-present-vs-orphan
  (testing "rf2-vu0mp: a present axis pill (the value still exists in
            the buffer) is styled differently from an orphan pill — the
            data-driven marker on the chip label. Event carries
            `:dispatch-id 1` so the spine has a focusable cascade
            (rf2-fzbrw)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Push :ui-sourced events; then add TWO filters: source = :ui
      ;; (present, but combined with the second filter renders zero
      ;; rows) AND frame = :rf/missing (orphan).
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default
                             :dispatch-id 1}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :frame  :rf/missing])
      (let [tree         (trace/Panel)
            source-pill  (find-by-testid tree "rf-causa-trace-empty-active-source")
            frame-pill   (find-by-testid tree "rf-causa-trace-empty-active-frame")
            label-of     (fn [node]
                           (->> (hiccup-seq node)
                                (filter string?)
                                (apply str)))]
        (is (some? source-pill) "present axis pill renders")
        (is (some? frame-pill)  "orphan axis pill renders")
        (testing "present pill has no orphan marker"
          (is (not (re-find #"orphaned" (label-of source-pill)))))
        (testing "orphan pill carries the orphan marker"
          (is (re-find #"orphaned" (label-of frame-pill))))))))

;; ---- (10) incremental projection wiring — rf2-44vzy --------------------
;;
;; The trace-feed sub now reads :trace-feed-state — an incrementally-
;; maintained snapshot of the projection updated O(axes) per push by
;; the :rf.causa/note-trace-event handler. These tests pin the wiring
;; contract: registry installs the new state-keeping handlers; the
;; sub returns the same shape as before; clear/sync drop the
;; snapshot in lockstep with the buffer (privacy invariant from
;; rf2-lqmje §Privacy retroactive-scrub).

(deftest trace-feed-state-sub-registered
  (testing "registry registers the :rf.causa/trace-feed-state sub
            (the per-rf2-44vzy reactive surface)"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/trace-feed-state))
        ":rf.causa/trace-feed-state sub registered")))

(deftest note-trace-event-updates-feed-state-snapshot
  (testing "rf2-44vzy: the note-trace-event handler dual-writes —
            populates both :trace-buffer and :trace-feed-state. The
            snapshot's :total, :counts, and :seen mirror what a from-
            scratch project-feed would produce."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default}))
      (sync-push! (mk-trace {:id 2 :op-type :error :operation :rf.error/x
                             :source :timer :frame :rf/causa}))
      (let [state @(rf/subscribe [:rf.causa/trace-feed-state])]
        (is (= 2 (:total state)))
        (is (= 2 (count (:projected-rows state))))
        (is (contains? (get-in state [:seen :source]) :ui))
        (is (contains? (get-in state [:seen :source]) :timer))
        (is (= 1 (get-in state [:counts :source :ui])))
        (is (= 1 (get-in state [:counts :source :timer])))))))

(deftest clear-trace-buffer-drops-feed-state
  (testing "rf2-44vzy + rf2-lqmje §Privacy retroactive-scrub: the
            `:rf.causa/clear-trace-buffer` handler dissocs BOTH
            `:trace-buffer` and `:trace-feed-state` in lockstep so
            the incremental snapshot never carries pre-clear residue.

            We assert the handler shape directly (sync dispatch) — the
            production path is `trace-bus/clear-buffer!`, which also
            clears the atom + queues this dispatch."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Populate both the app-db slot AND the atom by going through
      ;; sync-push! (dispatches :rf.causa/note-trace-event sync).
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui}))
      (is (pos? (:total @(rf/subscribe [:rf.causa/trace-feed-state])))
          "snapshot populated before clear")
      ;; Clear the atom + the slot atomically: drop the atom first so
      ;; the sub's fallback path produces an empty state, then sync-
      ;; dispatch the clear handler so the slot is dissoced too.
      (trace-bus/clear-buffer!)
      (rf/dispatch-sync [:rf.causa/clear-trace-buffer])
      (let [state @(rf/subscribe [:rf.causa/trace-feed-state])]
        (testing "post-clear: snapshot rebuilt from now-empty buffer →
                 empty / fresh state"
          (is (= 0 (:total state)))
          (is (= [] (:projected-rows state))))))))

(deftest sync-trace-buffer-rebuilds-feed-state
  (testing "rf2-44vzy: :rf.causa/sync-trace-buffer rebuilds the
            snapshot from the seeded buffer — every distinct value
            present in the seed must land in :seen"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [seed [(mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default})
                  (mk-trace {:id 2 :op-type :error :operation :rf.error/x
                             :source :timer :frame :rf/causa})]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer seed])
        (let [state @(rf/subscribe [:rf.causa/trace-feed-state])]
          (is (= 2 (:total state)))
          (is (contains? (get-in state [:seen :source]) :ui))
          (is (contains? (get-in state [:seen :source]) :timer)))))))

(deftest trace-feed-shape-stable-across-incremental-path
  (testing "rf2-44vzy: the public :rf.causa/trace-feed shape is
            unchanged — :rows / :total / :rendered / :distinct /
            :counts / :filters / :any-filter? / :empty-kind all
            present. Adds rf2-vu0mp's :active-filters key."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :source :ui :frame :rf/default}))
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (contains? feed :rows))
        (is (contains? feed :total))
        (is (contains? feed :rendered))
        (is (contains? feed :distinct))
        (is (contains? feed :counts))
        (is (contains? feed :filters))
        (is (contains? feed :any-filter?))
        (is (contains? feed :empty-kind))
        (is (contains? feed :active-filters)
            "rf2-vu0mp adds :active-filters for the empty-state strip")
        (is (contains? feed :cascade-dispatch-id)
            "rf2-ycoct adds :cascade-dispatch-id for the spine-scoped
             view")))))

;; ---- (11) cascade-scope (focused-event invariant) — rf2-ycoct ----------
;;
;; Per spec/018 §6 every L4 panel is a lens on the spine's focused event,
;; not a global ribbon. Mike's call (audit findings 2026-05-18) on
;; rf2-ycoct: the Trace tab is cascade-scoped by default. The composite
;; reads :rf.causa/focus and pre-filters rows to the focused cascade's
;; events; user chip filters AND on top of the scope.

(deftest trace-feed-cascade-scoped-to-focused-event
  (testing "rf2-ycoct: with two cascades in the buffer, focusing one
            scopes the feed to that cascade's events. Switching focus
            to the other re-renders with the other cascade's events."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Cascade A: dispatch-id 100, events 1+2.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 100 :frame :rf/default}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 110 :dispatch-id 100 :frame :rf/default}))
      ;; Cascade B: dispatch-id 200, events 3+4+5.
      (sync-push! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                             :time 200 :dispatch-id 200 :frame :rf/default}))
      (sync-push! (mk-trace {:id 4 :op-type :fx    :operation :rf.fx/handled
                             :time 210 :dispatch-id 200 :frame :rf/default}))
      (sync-push! (mk-trace {:id 5 :op-type :error :operation :rf.error/x
                             :time 220 :dispatch-id 200 :frame :rf/default}))
      ;; Focus cascade A.
      (rf/dispatch-sync [:rf.causa/focus-cascade 100])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 5 (:total feed))
            ":total reflects the ENTIRE buffer (unscoped count) —
             cascade-scope narrows what RENDERS, not what's in the
             buffer")
        (is (= 2 (:rendered feed))
            "only the two events in cascade A are rendered")
        (is (= #{1 2} (set (mapv :id (:rows feed))))
            "rows are exactly cascade A's events")
        (is (= 100 (:cascade-dispatch-id feed))
            "the scope value rides on the feed shape"))
      ;; Pivot focus to cascade B.
      (rf/dispatch-sync [:rf.causa/focus-cascade 200])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 3 (:rendered feed))
            "rendered count flips to cascade B's three events")
        (is (= #{3 4 5} (set (mapv :id (:rows feed))))
            "rows are exactly cascade B's events")
        (is (= 200 (:cascade-dispatch-id feed)))))))

(deftest trace-feed-user-chip-filter-ands-with-cascade-scope
  (testing "rf2-ycoct: user chip filters AND on top of the cascade
            scope — the panel is a lens AND a filterable ribbon."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Cascade A: two events, mixed sources.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 100 :source :ui}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 110 :dispatch-id 100 :source :timer}))
      ;; Cascade B: one :ui-sourced event (would survive a global
      ;; :source :ui filter if scope were not applied).
      (sync-push! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                             :time 200 :dispatch-id 200 :source :ui}))
      ;; Focus cascade A, then narrow on :source :ui — the AND-wise
      ;; result must be cascade-A AND :source :ui = event 1 only.
      (rf/dispatch-sync [:rf.causa/focus-cascade 100])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 1 (:rendered feed))
            "AND-wise: cascade-A ∩ :source :ui = event 1 only")
        (is (= [1] (mapv :id (:rows feed))))
        (is (true? (:any-filter? feed))
            ":any-filter? reflects USER filter state (cascade-scope is
             a system invariant, not a user narrowing)")))))

(deftest trace-feed-live-mode-auto-tracks-head-cascade
  (testing "rf2-ycoct + rf2-s0s5x: in LIVE mode the spine auto-tracks
            the head cascade, so a new cascade landing rebinds the
            scope automatically. No explicit focus event needed — the
            panel ALWAYS shows the latest cascade by default."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Cascade A — head while it's alone.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 100}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 110 :dispatch-id 100}))
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:rendered feed))
            "with one cascade, LIVE auto-scope shows that cascade")
        (is (= 100 (:cascade-dispatch-id feed))))
      ;; Cascade B lands — head pivots, LIVE auto-tracks.
      (sync-push! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                             :time 200 :dispatch-id 200}))
      (sync-push! (mk-trace {:id 4 :op-type :fx    :operation :rf.fx/handled
                             :time 210 :dispatch-id 200}))
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:rendered feed))
            "scope auto-pivoted to cascade B (the new head)")
        (is (= #{3 4} (set (mapv :id (:rows feed)))))
        (is (= 200 (:cascade-dispatch-id feed)))))))

(deftest trace-feed-cascade-scope-no-match-renders-no-matches
  (testing "rf2-ycoct: when the focused cascade is in the buffer but
            user filters reduce it to zero rendered rows, the
            :no-matches empty-state renders (not :no-events)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 100 :source :ui}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 110 :dispatch-id 100 :source :ui}))
      (rf/dispatch-sync [:rf.causa/focus-cascade 100])
      ;; Narrow on a source that doesn't exist in cascade A.
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :timer])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])
            tree (trace/Panel)]
        (is (= :no-matches (:empty-kind feed)))
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-matches")))))))

(deftest trace-feed-defensive-no-focus-empty-state
  (testing "rf2-ycoct defensive branch: when the buffer is non-empty
            but the spine's focus has no :dispatch-id (default-focus
            rule from rf2-639lc broken / not yet applied), the panel
            renders the terse :no-focus empty state.

            We construct this state directly via the helper to pin the
            contract — the spine's LIVE auto-snap normally prevents
            this state from arising in the running app."
    (let [state  (h/rebuild-feed-state
                   [(mk-trace {:id 1 :op-type :event
                               :operation :event/dispatched
                               :dispatch-id 100})])
          feed   (h/project-feed-from-state
                   state {} {:cascade-dispatch-id nil})]
      (is (= :no-focus (:empty-kind feed)))
      (is (= 0 (:rendered feed))
          "no rows render in the no-focus defensive branch")
      (is (= 1 (:total feed))
          ":total still reflects the buffer (the state is broken,
           not the data)"))))

(deftest panel-spine-auto-snap-guards-against-no-focus-in-production
  (testing "rf2-ycoct + rf2-s0s5x guard: in LIVE mode with cascades
            present, the spine ALWAYS snaps focus to head — the
            defensive :no-focus state should never arise in production.
            This pins the contract: a regression in the spine's auto-
            snap behaviour that left :dispatch-id nil with a non-empty
            buffer would fail this assertion."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :dispatch-id 100}))
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (not= :no-focus (:empty-kind feed))
            "LIVE auto-snap → focus has :dispatch-id 100 → no :no-focus")
        (is (= 100 (:cascade-dispatch-id feed))
            "scope landed on the head cascade automatically")))))
