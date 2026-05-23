(ns day8.re-frame2-causa.panels.trace-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Trace panel
  (Phase 5, rf2-argrj; epoch-scoped rework rf2-o6yqq + rf2-td380 +
  rf2-gkczt).

  ## What's under test (in addition to the pure-data tests in
  `trace_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/trace-feed` (the epoch-scoped feed).

    2. **Render contract** — the section + feed + data-testid wiring
       matches the production view tree. The top header row (counts /
       epoch-indicator / film-strip) is GONE (rf2-o6yqq); the
       chip-filter rows + clear-filters control are GONE (rf2-gkczt).

    3. **Focused-epoch scope** (spec/018 §6 + rf2-td380) — the panel
       surfaces the focused epoch record's `:trace-events` (the
       complete domino trail, including the async nil-dispatch-id
       reactive rows); refocusing changes the rendered feed.

    4. **Empty states** — `:no-events`, `:no-focus`, `:epoch-evicted`
       each render their distinct container.

    5. **Row interactions** — clicking a row toggles inline payload
       expansion; clicking the source-coord chip fires :open-in-editor
       and does NOT also toggle.

    6. **React-key stability** (rf2-z4fza) — rows keyed on the stable
       trace id.

  ## Pure hiccup

  Same approach as `issues_ribbon_view_cljs_test.cljs` — walk the
  view's hiccup tree by `data-testid` rather than mounting to the DOM.

  ## Seeding

  The Trace panel is epoch-scoped: it reads the focused epoch record's
  `:trace-events`, NOT the global trace bus. So trace events are seeded
  by attaching them to a `:rf/epoch-record`'s `:trace-events` slot and
  syncing the per-frame ring via `:rf.causa/sync-epoch-history`, then
  focusing via `:rf.causa/focus-cascade`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.panels.trace :as trace]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-test-support/reset-all!}))

;; ---- hiccup walkers ----------------------------------------------------
;; Thin aliases over re-frame.test-helpers so the local call sites read
;; identically to before.

(def ^:private find-by-testid           th/find-by-testid)
(def ^:private find-all-by-testid-prefix th/find-by-testid-prefix)

(defn- hiccup-seq
  "Expands fn components via the framework walker, then yields
  depth-first nodes."
  [tree]
  (tree-seq (some-fn vector? seq?) seq (th/expand-tree tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; Construct a synthetic trace event living INSIDE an epoch record's
;; `:trace-events` slot.
(defn- mk-trace
  [{:keys [id time op-type operation source origin frame
           event-id handler-id dispatch-id reason]
    :or   {time 1000}}]
  {:id        id
   :time      time
   :op-type   op-type
   :operation operation
   :source    source
   :tags      (cond-> {}
                origin      (assoc :rf.event/origin origin)
                frame       (assoc :frame frame)
                event-id    (assoc :rf.trace/event-id event-id)
                handler-id  (assoc :handler-id handler-id)
                dispatch-id (assoc :rf.trace/dispatch-id dispatch-id)
                source      (assoc :source source)
                reason      (assoc :reason reason))})

(defn- mk-epoch
  "Build a minimal `:rf/epoch-record` carrying the supplied
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

(deftest registry-installs-trace-handlers
  (testing "register-causa-handlers! installs the epoch-scoped
            composite sub + the row-expand events"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/trace-feed))
        ":rf.causa/trace-feed sub registered")
    (is (some? (registrar/handler :sub :rf.causa/trace-expanded-row-ids))
        ":rf.causa/trace-expanded-row-ids sub registered")
    (is (some? (registrar/handler :event :rf.causa/toggle-trace-row-expand))
        ":rf.causa/toggle-trace-row-expand event registered")))

(deftest filter-handlers-are-gone
  (testing "rf2-gkczt: the chip-filter subs + events MUST NOT register"
    (registry/register-causa-handlers!)
    (is (nil? (registrar/handler :sub :rf.causa/trace-filters))
        ":rf.causa/trace-filters sub is gone")
    (is (nil? (registrar/handler :sub :rf.causa/trace-feed-state))
        ":rf.causa/trace-feed-state buffer-snapshot sub is gone (rf2-td380)")
    (is (nil? (registrar/handler :event :rf.causa/set-trace-filter))
        ":rf.causa/set-trace-filter event is gone")
    (is (nil? (registrar/handler :event :rf.causa/clear-trace-filters))
        ":rf.causa/clear-trace-filters event is gone")))

(deftest trace-feed-defaults-no-focus
  (testing "rf2-td380: with no focus + no epoch history the composite
            returns an empty feed with :no-focus (cold start — the
            focus-resolver classifies nil-focus + empty-history as
            :no-focus)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= [] (:rows feed)))
        (is (= 0 (:total feed)))
        (is (= 0 (:rendered feed)))
        (is (= :no-focus (:empty-kind feed)))))))

(deftest trace-feed-projects-focused-epoch-events-into-rows
  (testing "rf2-td380: with a focused epoch the composite returns one
            row per trace event in that epoch's :trace-events"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :event-id :cart/add})
                    (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-exception
                               :dispatch-id 42 :reason "boom"})])])
      (focus! 42)
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= 2 (:total feed)))
        (is (= 2 (:rendered feed)))
        (is (nil? (:empty-kind feed)))
        (is (= #{1 2} (set (map :id (:rows feed)))))
        (is (= 1 (:epoch-id feed)))))))

;; ---- (2) render contract ------------------------------------------------

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of focus state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace"))
            "panel container present")))))

(deftest top-header-row-is-gone
  (testing "rf2-o6yqq: the top header row — counts, epoch-indicator,
            film-strip nav — is removed from the Trace panel"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42})])])
      (focus! 42)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-trace-counts"))
            "counts span is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-epoch-indicator"))
            "epoch indicator is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-film-strip"))
            "film-strip wrapper is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-film-strip-prev"))
            "film-strip prev button is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-film-strip-next"))
            "film-strip next button is gone")))))

(deftest chip-filter-ui-is-gone
  (testing "rf2-gkczt: the chip-filter rows, per-row chip affordances,
            and clear-filters control are removed"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :source :ui})
                    (mk-trace {:id 2 :op-type :error :operation :rf.error/x
                               :dispatch-id 42 :source :timer})])])
      (focus! 42)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-trace-axis-row-op-type"))
            "op-type chip row is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-axis-row-source"))
            "source chip row is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-clear-filters"))
            "clear-filters control is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-row-1-source-chip"))
            "per-row source chip is gone")
        (is (nil? (find-by-testid tree "rf-causa-trace-row-1-row-chips"))
            "per-row chip cell is gone")))))

(deftest feed-list-renders-when-focused-epoch-has-events
  (testing "rf2-td380: with a focused epoch the panel renders the <ul>
            feed with one <li> per row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled
                               :dispatch-id 1})
                    (mk-trace {:id 3 :op-type :rf.sub :operation :rf.sub/run})])])  ; nil dispatch-id
      (focus! 1)
      (let [tree (trace/Panel)
            rows (find-all-by-testid-prefix tree "rf-causa-trace-row-")]
        (is (some? (find-by-testid tree "rf-causa-trace-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-causa-trace-row-3"))
            "the async nil-dispatch-id reactive row is present (rf2-td380)")
        (is (>= (count rows) 3)
            "at least one rendered node per row")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-events-renders-for-empty-epoch
  (testing "with a focused epoch carrying no trace events the panel
            renders the :no-events empty-state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-events"))
            ":no-events empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list when focused epoch carries no events")))))

(deftest empty-state-no-focus-renders
  (testing "rf2-td380: with no focus + no history the panel renders the
            :no-focus empty-state (cold start — the focus-resolver
            classifies nil-focus + empty-history as :no-focus)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-causa-trace-empty-no-focus"))
            "cold-start :no-focus empty-state present")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list at cold start")))))

(deftest empty-state-epoch-evicted-renders
  (testing "rf2-td380: when focus pins an :epoch-id no longer in
            :epoch-history the panel paints the evicted-epoch
            placeholder (mirrors the Issues panel, spec/021 §10.7)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history! [(mk-epoch 1 11 [])])
      (rf/dispatch-sync
        [:day8.re-frame2-causa.panels.trace-view-cljs-test/seed-evicted-focus])
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])
            tree (trace/Panel)]
        (is (= :epoch-evicted (:empty-kind feed))
            "composite signals :epoch-evicted when no record matches focus")
        (is (some? (find-by-testid tree "rf-causa-trace-empty-epoch-evicted"))
            "canonical placeholder container rendered")
        (is (nil? (find-by-testid tree "rf-causa-trace-feed"))
            "no feed list when the focused epoch has been evicted")))))

;; ---- (4) focused-epoch scope (refocus) ----------------------------------

(deftest trace-feed-rescopes-on-refocus
  (testing "rf2-td380: focusing a different epoch re-renders with that
            epoch's :trace-events"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 100
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 100})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled})])  ; nil dispatch-id reactive
         (mk-epoch 2 200
                   [(mk-trace {:id 3 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 200})
                    (mk-trace {:id 4 :op-type :rf.sub :operation :rf.sub/run})
                    (mk-trace {:id 5 :op-type :rf.view :operation :rf.view/render})])])
      ;; Focus epoch 1 (cascade 100).
      (focus! 100)
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= #{1 2} (set (map :id (:rows feed))))
            "epoch 1's rows — including the nil-dispatch-id reactive row 2")
        (is (= 1 (:epoch-id feed))))
      ;; Refocus to epoch 2 (cascade 200).
      (focus! 200)
      (let [feed @(rf/subscribe [:rf.causa/trace-feed])]
        (is (= #{3 4 5} (set (map :id (:rows feed))))
            "epoch 2's rows — the whole domino trail (event + sub + view)")
        (is (= 2 (:epoch-id feed)))))))

;; ---- (5) row interactions -----------------------------------------------

(deftest row-click-toggles-inline-payload-expansion
  (testing "rf2-7dyi8 — clicking a row dispatches
            :rf.causa/toggle-trace-row-expand with the row's :id rather
            than pivoting to event-detail. Per spec/021 §5.4 the Trace
            panel surfaces row payloads inline; no nav."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 7 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :frame :rf/default})])])
      (focus! 42)
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
        (is (some #(= [:rf.causa/toggle-trace-row-expand 7] %) @dispatches)
            ":rf.causa/toggle-trace-row-expand fired with the row's :id")
        (is (not-any? #(and (vector? %)
                            (= :rf.causa/select-dispatch-id (first %)))
                      @dispatches)
            "the legacy pivot is gone — no select-dispatch-id fired")))))

(deftest toggle-trace-row-expand-event-mutates-set
  (testing "rf2-7dyi8 — :rf.causa/toggle-trace-row-expand toggles row
            membership in :trace-expanded-row-ids set."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-trace-row-expand 11])
      (is (= #{11} @(rf/subscribe [:rf.causa/trace-expanded-row-ids]))
          "first toggle adds the id")
      (rf/dispatch-sync [:rf.causa/toggle-trace-row-expand 22])
      (is (= #{11 22} @(rf/subscribe [:rf.causa/trace-expanded-row-ids]))
          "second toggle on a different id appends")
      (rf/dispatch-sync [:rf.causa/toggle-trace-row-expand 11])
      (is (= #{22} @(rf/subscribe [:rf.causa/trace-expanded-row-ids]))
          "toggling an already-expanded row removes it")
      (rf/dispatch-sync [:rf.causa/clear-trace-expand])
      (is (= #{} @(rf/subscribe [:rf.causa/trace-expanded-row-ids]))
          ":rf.causa/clear-trace-expand drops every expanded id"))))

(deftest expanded-row-renders-cljs-devtools-payload
  (testing "rf2-7dyi8 + rf2-dmso5 — when a row's :id is in the expanded
            set, the current-state cljs-devtools browse renderer mounts
            below the row (Trace payloads are current-state)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 13 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1 :source :ui :origin :app})])])
      (focus! 1)
      ;; Pre-expansion — no payload.
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-trace-row-13-payload"))
            "payload block absent when row is not in expanded set"))
      ;; Toggle expand → payload renders.
      (rf/dispatch-sync [:rf.causa/toggle-trace-row-expand 13])
      (let [tree    (trace/Panel)
            payload (find-by-testid tree "rf-causa-trace-row-13-payload")]
        (is (some? payload) "payload block renders when row is expanded")
        (is (some? (find-by-testid tree
                                   "rf-causa-edn-widget-browse-trace-trace-row-13"))
            "cljs-devtools browse mounted with per-row render-id")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord chip fires :rf.causa/open-in-editor;
            stopPropagation prevents the row's expand-toggle from also
            firing (rf2-7dyi8 — the row click toggles inline expansion
            now, the source-coord button bubbles must not toggle it)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 1
                   [(-> (mk-trace {:id 9 :op-type :rf.event
                                   :operation :rf.event/dispatched :dispatch-id 1})
                        (assoc :rf.trace/trigger-handler
                               {:source-coord {:file "core.cljs" :line 42}}))])])
      (focus! 1)
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

;; ---- (6) frame isolation ------------------------------------------------

(deftest trace-expand-state-does-not-leak-into-default-frame
  (testing "the panel's expand state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/toggle-trace-row-expand 5]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{5} (:trace-expanded-row-ids causa-db))
          "expand set lands on Causa")
      (is (nil? (:trace-expanded-row-ids default-db))
          "expand set did NOT leak into :rf/default"))))

;; ---- (7) React-key stability across the feed — rf2-z4fza ---------------
;;
;; Sibling of rf2-kgn0c (same React-key discipline class). The rendered
;; `<li>` for any row keys on the stable trace `:id` (`t:<id>`), not a
;; positional index — so React's reconciler reuses DOM nodes instead of
;; remounting the viewport.

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

(deftest trace-row-react-keys-are-stable-trace-ids
  (testing "rf2-z4fza — the rendered <li> :key is the stable trace id
            (`t:<id>`), distinct per row and free of any positional
            component"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 11 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 22 :op-type :rf.fx :operation :rf.fx/handled
                               :time 200 :dispatch-id 1})
                    (mk-trace {:id 33 :op-type :rf.sub :operation :rf.sub/run
                               :time 300})])])
      (focus! 1)
      (let [tree (trace/Panel)
            k11  (:key (second (row-li-by-id tree 11)))
            k22  (:key (second (row-li-by-id tree 22)))
            k33  (:key (second (row-li-by-id tree 33)))]
        (is (= "t:11" k11)
            "row 11 keyed on stable trace id alone (no positional prefix)")
        (is (= "t:22" k22))
        (is (= "t:33" k33))
        (is (= 3 (count (distinct [k11 k22 k33])))
            "all row keys distinct")))))

;; ---- evicted-focus helper ----------------------------------------------

;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path. Production
;; only reaches this state when the framework's `:epoch-history`
;; setting caps the buffer and older epochs roll off; in tests we
;; synthesise the same in-memory shape directly.
(rf/reg-event-db
  :day8.re-frame2-causa.panels.trace-view-cljs-test/seed-evicted-focus
  (fn [db _event]
    (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})))
