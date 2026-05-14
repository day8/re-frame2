(ns day8.re-frame2-causa.panels.mcp-server-view-cljs-test
  "CLJS-side wiring + view tests for Causa's MCP Server panel
  (Phase 5, rf2-81qjj, parent rf2-5aw5v).

  ## What's under test (in addition to the pure-data tests in
  `mcp_server_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub + supporting events** under
       `:rf.causa/mcp-*`. The composite returns rows + counts +
       attached-state in the shape the view consumes.

    2. **Filter chip toggles** mutate `:mcp-active-op-types` on the
       Causa frame's db; the panel renders the toggled state.

    3. **Settings sub-pane** — origin colour swatch + origin-filter
       toggle render; clicking the toggle flips the
       `:rf.causa/mcp-origin-filter-enabled?` sub.

    4. **Empty states** — `:no-activity` when the buffer carries no
       :origin :causa-mcp events; `:no-matches` when filters hide
       all matches.

    5. **Row pivot** — clicking a row with a `:dispatch-id` fires
       `:rf.causa/select-dispatch-id` + `:rf.causa/select-panel`.

  ## Pure hiccup

  Same approach as `subscriptions_view_cljs_test.cljs` /
  `causality_graph_view_cljs_test.cljs` — walk the view's hiccup tree
  by `data-testid` rather than mounting to the DOM. Keeps the suite
  fast + host-portable on node-test."
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
            [day8.re-frame2-causa.panels.mcp-server :as mcp-server]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror subscriptions_view_cljs_test) ---------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

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

;; Inject events directly into the Causa-side ring buffer. Mirrors
;; the pattern other view tests use (trace-bus is the source the
;; composite sub reads through `:rf.causa/trace-buffer`).
(defn- push-event! [ev]
  ;; Per rf2-e9s81: `:rf.causa/trace-buffer` thunks the trace-bus
  ;; atom; pushing via `collect-trace!` (the production path) lands
  ;; the event in the atom and the next subscribe sees it.
  (trace-bus/collect-trace! ev))

(defn- mcp-event
  "Build a :origin :causa-mcp trace event in the shape the buffer
  carries."
  ([id op-type operation]
   (mcp-event id op-type operation {}))
  ([id op-type operation {:keys [time tags]
                          :or {time 1000 tags {}}}]
   {:id        id
    :op-type   op-type
    :operation operation
    :time      time
    :tags      (assoc tags :origin :causa-mcp)}))

(defn- app-event
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      1000
   :tags      {:origin :app}})

;; ---- (1) registry wires the composite sub + events ----------------------

(deftest registry-installs-mcp-surface
  (testing "register-causa-handlers! installs the Phase 5 MCP composite
            sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/mcp-server)))
    (is (some? (registrar/handler :sub :rf.causa/mcp-filters)))
    (is (some? (registrar/handler :sub :rf.causa/mcp-origin-filter-enabled?)))
    (is (some? (registrar/handler :event :rf.causa/toggle-mcp-op-type)))
    (is (some? (registrar/handler :event :rf.causa/set-mcp-since-seconds)))
    (is (some? (registrar/handler :event :rf.causa/clear-mcp-filters)))
    (is (some? (registrar/handler :event :rf.causa/toggle-mcp-origin-filter)))))

(deftest mcp-server-sub-defaults-empty
  (testing "with no events in the buffer the composite returns an empty
            rows vector + agent-attached? false + :no-activity empty-kind"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/mcp-server])]
        (is (= [] (:rows data)))
        (is (= 0 (:total data)))
        (is (false? (:agent-attached? data)))
        (is (= :no-activity (:empty-kind data)))))))

(deftest mcp-server-sub-projects-events
  (testing "with causa-mcp events in the buffer the composite returns
            one row per event"
    (setup-causa-frame!)
    (push-event! (mcp-event 1 :event :event/dispatched
                            {:tags {:tool :dispatch}}))
    (push-event! (mcp-event 2 :fx :fx/handled
                            {:tags {:tool :restore-epoch}}))
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/mcp-server])]
        (is (= 2 (:total data)))
        (is (= 2 (:rendered data)))
        (is (true? (:agent-attached? data)))))))

(deftest mcp-server-sub-ignores-non-mcp-events
  (testing "events with :origin :app or no :origin tag don't appear"
    (setup-causa-frame!)
    (push-event! (app-event 1))
    (push-event! (mcp-event 2 :event :event/dispatched))
    (push-event! (app-event 3))
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/mcp-server])]
        (is (= 1 (:total data)))
        (is (= [2] (mapv :id (:rows data))))))))

;; ---- (2) filter chip toggle --------------------------------------------

(deftest toggle-op-type-writes-to-causa-frame
  (testing "dispatching :rf.causa/toggle-mcp-op-type toggles set
            membership on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :event])
      (is (= #{:event}
             (:op-types @(rf/subscribe [:rf.causa/mcp-filters]))))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :fx])
      (is (= #{:event :fx}
             (:op-types @(rf/subscribe [:rf.causa/mcp-filters]))))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :event])
      (is (= #{:fx}
             (:op-types @(rf/subscribe [:rf.causa/mcp-filters])))))))

(deftest clear-filters-drops-every-axis
  (testing ":rf.causa/clear-mcp-filters drops every axis at once"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :event])
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds 30])
      (rf/dispatch-sync [:rf.causa/clear-mcp-filters])
      (let [filters @(rf/subscribe [:rf.causa/mcp-filters])]
        (is (= #{} (:op-types filters)))
        (is (nil? (:since-ms filters)))))))

(deftest set-since-seconds-converts-to-ms
  (testing "the set-since-seconds event converts s → ms"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds 30])
      (is (= 30000
             (:since-ms @(rf/subscribe [:rf.causa/mcp-filters])))))))

(deftest set-since-seconds-nil-clears-axis
  (testing "passing nil / non-positive clears the since-ms axis"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds 30])
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds nil])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/mcp-filters]))))
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds 30])
      (rf/dispatch-sync [:rf.causa/set-mcp-since-seconds 0])
      (is (nil? (:since-ms @(rf/subscribe [:rf.causa/mcp-filters])))))))

;; ---- (3) settings sub-pane ---------------------------------------------

(deftest origin-filter-toggle-flips-the-sub
  (testing "the origin-filter toggle event flips the boolean sub"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-origin-filter])
      (is (true?  @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?])))
      (rf/dispatch-sync [:rf.causa/toggle-mcp-origin-filter])
      (is (false? @(rf/subscribe [:rf.causa/mcp-origin-filter-enabled?]))))))

;; ---- (4) view renders --------------------------------------------------

(deftest empty-state-renders-when-buffer-has-no-mcp-events
  (testing "with no mcp events the panel renders the :no-activity
            empty state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (mcp-server/mcp-server-view)]
        (is (some? (find-by-testid tree "rf-causa-mcp-server"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-mcp-empty-no-activity"))
            ":no-activity empty-state present")
        (is (nil? (find-by-testid tree "rf-causa-mcp-feed"))
            "no feed when there are zero rows")))))

(deftest feed-renders-when-buffer-has-mcp-events
  (testing "with mcp events the panel renders one row per event +
            settings + filter chips"
    (setup-causa-frame!)
    (push-event! (mcp-event 1 :event :event/dispatched
                            {:tags {:tool :dispatch}}))
    (push-event! (mcp-event 2 :fx :fx/handled
                            {:tags {:tool :restore-epoch}}))
    (rf/with-frame :rf/causa
      (let [tree (mcp-server/mcp-server-view)]
        (is (some? (find-by-testid tree "rf-causa-mcp-feed"))
            "feed container present")
        (is (some? (find-by-testid tree "rf-causa-mcp-row-1"))
            "row for id=1 present")
        (is (some? (find-by-testid tree "rf-causa-mcp-row-2"))
            "row for id=2 present")
        (is (some? (find-by-testid tree "rf-causa-mcp-settings"))
            "Settings sub-pane present")
        (is (some? (find-by-testid tree "rf-causa-mcp-origin-swatch"))
            "Origin colour swatch present")
        (is (some? (find-by-testid tree "rf-causa-mcp-origin-filter-toggle"))
            "Origin-filter toggle present")
        (is (some? (find-by-testid tree "rf-causa-mcp-op-type-chips"))
            "op-type chip row present")))))

(deftest no-matches-empty-state-renders-when-filter-hides-all
  (testing "with mcp events present but an active filter that hides
            them all, the panel renders the :no-matches empty state"
    (setup-causa-frame!)
    (push-event! (mcp-event 1 :event :event/dispatched))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-mcp-op-type :fx])
      (let [tree (mcp-server/mcp-server-view)]
        (is (some? (find-by-testid tree "rf-causa-mcp-empty-no-matches"))
            ":no-matches empty state present")
        (is (nil? (find-by-testid tree "rf-causa-mcp-feed"))
            "no feed when filter hides every row")))))

(deftest attached-badge-says-no-activity-when-buffer-empty
  (testing "with an empty buffer the badge in the header shows
            'no activity'"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree  (mcp-server/mcp-server-view)
            badge (find-by-testid tree "rf-causa-mcp-attached-badge")
            txt   (pr-str badge)]
        (is (re-find #"no activity" txt))
        (is (not (re-find #"agent attached" txt)))))))

(deftest attached-badge-says-attached-when-mcp-event-in-buffer
  (testing "with at least one :origin :causa-mcp event in the buffer
            the badge shows 'agent attached'"
    (setup-causa-frame!)
    (push-event! (mcp-event 1 :event :event/dispatched))
    (rf/with-frame :rf/causa
      (let [tree  (mcp-server/mcp-server-view)
            badge (find-by-testid tree "rf-causa-mcp-attached-badge")
            txt   (pr-str badge)]
        (is (re-find #"agent attached" txt))))))

;; ---- (5) row pivot ----------------------------------------------------

(deftest row-click-pivots-to-event-detail
  (testing "clicking a row with a dispatch-id dispatches both
            :rf.causa/select-dispatch-id and :rf.causa/select-panel"
    (setup-causa-frame!)
    (push-event! (mcp-event 1 :event :event/dispatched
                            {:tags {:dispatch-id 42}}))
    (let [dispatches (atom [])]
      ;; `rf/dispatch` is a macro that expands to `rf/dispatch*`; the
      ;; redef target is the underlying fn.
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree    (mcp-server/mcp-server-view)
                row     (find-by-testid tree "rf-causa-mcp-row-1")
                on-click (:on-click (second row))]
            (is (some? row) "row is present in the rendered tree")
            (is (fn? on-click) "row has an on-click handler")
            (on-click))))
      (let [evs @dispatches]
        (is (some #(= [:rf.causa/select-dispatch-id 42] %) evs)
            "select-dispatch-id fired with the cascade id")
        (is (some #(= [:rf.causa/select-panel :event-detail] %) evs)
            "select-panel fired with :event-detail")))))

(deftest row-without-dispatch-id-does-not-pivot
  (testing "a row whose event carries no :dispatch-id is non-clickable
            (cursor: default; on-click is a no-op)"
    (setup-causa-frame!)
    ;; No :dispatch-id in tags.
    (push-event! (mcp-event 1 :event :event/dispatched))
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (rf/with-frame :rf/causa
          (let [tree    (mcp-server/mcp-server-view)
                row     (find-by-testid tree "rf-causa-mcp-row-1")
                on-click (:on-click (second row))]
            (on-click))))
      (is (empty? @dispatches)
          "no dispatch fires when :dispatch-id is absent"))))
