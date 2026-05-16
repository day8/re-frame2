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
            [day8.re-frame2-causa.panels.trace :as trace]))

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
  ;; before the buffer push — the trace-view filter axes need to
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
      (let [tree (trace/trace-view)]
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
      (let [tree (trace/trace-view)
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
      (let [tree (trace/trace-view)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-events"))
            ":no-events empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list when buffer is empty")))))

(deftest empty-state-no-matches-renders-when-filters-hide-all
  (testing "with events present but a filter that matches nothing the
            panel renders the :no-matches empty-state with clear button"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :no-such-source])
      (let [tree (trace/trace-view)]
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
  (testing "setting :source narrows the feed to events with that source"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui :dispatch-id 1}))
      (push-trace! (mk-trace {:id 2 :op-type :event :operation :event/dispatched
                              :source :timer :dispatch-id 2}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:total feed)))
        (is (= 1 (:rendered feed)))
        (is (= [1] (mapv :id (:rows feed))))))))

(deftest filter-by-op-type-narrows-rendered-rows
  (testing "setting :op-type narrows the feed to events with that op-type"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched}))
      (push-trace! (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-threw}))
      (push-trace! (mk-trace {:id 3 :op-type :fx    :operation :rf.fx/handled}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :op-type :error])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 1 (:rendered feed)))
        (is (= [2] (mapv :id (:rows feed))))))))

(deftest filter-axes-compose-and-wise
  (testing "two filter axes compose AND-wise — only rows matching both
            survive"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :source :ui    :frame :rf/default}))
      (push-trace! (mk-trace {:id 2 :op-type :event :operation :event/dispatched
                              :source :ui    :frame :rf/causa}))
      (push-trace! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                              :source :timer :frame :rf/default}))
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (rf/dispatch-sync [:rf.causa/set-trace-filter :frame  :rf/default])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 1 (:rendered feed))
            "only the row matching both axes survives")
        (is (= [1] (mapv :id (:rows feed))))))))

(deftest filter-by-dispatch-id-collapses-cascade
  (testing "setting :dispatch-id slices the feed to one cascade's events"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (push-trace! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                              :dispatch-id 42}))
      (push-trace! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                              :dispatch-id 42}))
      (push-trace! (mk-trace {:id 3 :op-type :event :operation :event/dispatched
                              :dispatch-id 99}))
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
      (let [tree (trace/trace-view)]
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
      (is (nil? (find-by-testid (trace/trace-view) "rf-causa-trace-clear-filters"))
          "no Clear filters button when no axis is active")
      (rf/dispatch-sync [:rf.causa/set-trace-filter :source :ui])
      (is (some? (find-by-testid (trace/trace-view) "rf-causa-trace-clear-filters"))
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
          (let [tree    (trace/trace-view)
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
          (let [tree    (trace/trace-view)
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
            v:<variant-id> discipline in the story workspace."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed three rows.
      (sync-push! (mk-trace {:id 1 :op-type :event :operation :event/dispatched
                             :time 100 :dispatch-id 1}))
      (sync-push! (mk-trace {:id 2 :op-type :fx    :operation :rf.fx/handled
                             :time 200 :dispatch-id 1}))
      (sync-push! (mk-trace {:id 3 :op-type :sub/run :operation :sub/run
                             :time 300 :dispatch-id 1}))
      (let [tree-1   (trace/trace-view)
            keys-1   {1 (:key (second (row-li-by-id tree-1 1)))
                      2 (:key (second (row-li-by-id tree-1 2)))
                      3 (:key (second (row-li-by-id tree-1 3)))}]
        (is (every? some? (vals keys-1))
            "every initial row carries a React :key")
        (is (= 3 (count (distinct (vals keys-1))))
            "initial row keys are distinct")
        ;; Push three more events — under the broken positional-key
        ;; shape, this would shift every existing key.
        (sync-push! (mk-trace {:id 4 :op-type :event :operation :event/dispatched
                               :time 400 :dispatch-id 2}))
        (sync-push! (mk-trace {:id 5 :op-type :fx    :operation :rf.fx/handled
                               :time 500 :dispatch-id 2}))
        (sync-push! (mk-trace {:id 6 :op-type :error :operation :rf.error/handler-threw
                               :time 600 :dispatch-id 2}))
        (let [tree-2 (trace/trace-view)
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
      (let [tree (trace/trace-view)
            k11  (:key (second (row-li-by-id tree 11)))
            k22  (:key (second (row-li-by-id tree 22)))]
        (is (= "t:11" k11)
            "row 11 keyed on stable trace id alone (no positional prefix)")
        (is (= "t:22" k22)
            "row 22 keyed on stable trace id alone (no positional prefix)")
        (is (string? k11)
            "key is a string (not the positional-tuple from the
             pre-rf2-z4fza shape)")))))
