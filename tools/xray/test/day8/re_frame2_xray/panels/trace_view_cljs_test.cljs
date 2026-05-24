(ns day8.re-frame2-xray.panels.trace-view-cljs-test
  "CLJS-side wiring + view tests for Xray's Trace panel
  (Phase 5, rf2-argrj; epoch-scoped rework rf2-o6yqq + rf2-td380 +
  rf2-gkczt).

  ## What's under test (in addition to the pure-data tests in
  `trace_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.xray/trace-feed` (the epoch-scoped feed).

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
  syncing the per-frame ring via `:rf.xray/sync-epoch-history`, then
  focusing via `:rf.xray/focus-cascade`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.trace :as trace]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-test-support/reset-all!}))

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

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

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

(deftest registry-installs-trace-handlers
  (testing "register-xray-handlers! installs the epoch-scoped
            composite sub + the row-expand events"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/trace-feed))
        ":rf.xray/trace-feed sub registered")
    (is (some? (registrar/handler :sub :rf.xray/trace-expanded-row-ids))
        ":rf.xray/trace-expanded-row-ids sub registered")
    (is (some? (registrar/handler :event :rf.xray/toggle-trace-row-expand))
        ":rf.xray/toggle-trace-row-expand event registered")))

(deftest filter-handlers-are-gone
  (testing "rf2-gkczt: the chip-filter subs + events MUST NOT register"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/trace-filters))
        ":rf.xray/trace-filters sub is gone")
    (is (nil? (registrar/handler :sub :rf.xray/trace-feed-state))
        ":rf.xray/trace-feed-state buffer-snapshot sub is gone (rf2-td380)")
    (is (nil? (registrar/handler :event :rf.xray/set-trace-filter))
        ":rf.xray/set-trace-filter event is gone")
    (is (nil? (registrar/handler :event :rf.xray/clear-trace-filters))
        ":rf.xray/clear-trace-filters event is gone")))

(deftest trace-feed-defaults-no-focus
  (testing "rf2-td380: with no focus + no epoch history the composite
            returns an empty feed with :no-focus (cold start — the
            focus-resolver classifies nil-focus + empty-history as
            :no-focus)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= [] (:rows feed)))
        (is (= 0 (:total feed)))
        (is (= 0 (:rendered feed)))
        (is (= :no-focus (:empty-kind feed)))))))

(deftest trace-feed-projects-focused-epoch-events-into-rows
  (testing "rf2-td380: with a focused epoch the composite returns one
            row per trace event in that epoch's :trace-events"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :event-id :cart/add})
                    (mk-trace {:id 2 :op-type :error :operation :rf.error/handler-exception
                               :dispatch-id 42 :reason "boom"})])])
      (focus! 42)
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= 2 (:total feed)))
        (is (= 2 (:rendered feed)))
        (is (nil? (:empty-kind feed)))
        (is (= #{1 2} (set (map :id (:rows feed)))))
        (is (= 1 (:epoch-id feed)))))))

;; ---- (2) render contract ------------------------------------------------

(deftest panel-container-renders
  (testing "the panel renders its root container regardless of focus state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace"))
            "panel container present")))))

(deftest top-header-row-is-gone
  (testing "rf2-o6yqq: the top header row — counts, epoch-indicator,
            film-strip nav — is removed from the Trace panel"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42})])])
      (focus! 42)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-counts"))
            "counts span is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-epoch-indicator"))
            "epoch indicator is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-film-strip"))
            "film-strip wrapper is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-film-strip-prev"))
            "film-strip prev button is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-film-strip-next"))
            "film-strip next button is gone")))))

(deftest chip-filter-ui-is-gone
  (testing "rf2-gkczt: the chip-filter rows, per-row chip affordances,
            and clear-filters control are removed"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :source :ui})
                    (mk-trace {:id 2 :op-type :error :operation :rf.error/x
                               :dispatch-id 42 :source :timer})])])
      (focus! 42)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-axis-row-op-type"))
            "op-type chip row is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-axis-row-source"))
            "source chip row is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-clear-filters"))
            "clear-filters control is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-1-source-chip"))
            "per-row source chip is gone")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-1-row-chips"))
            "per-row chip cell is gone")))))

(deftest feed-list-renders-when-focused-epoch-has-events
  (testing "rf2-td380 + rf2-ad7zx.8: with a focused epoch the panel
            renders the <ul> feed; the core dominoes (dispatch/fx) are
            top-level op rows and the reactive aftermath (the
            nil-dispatch-id :rf.sub/run) collapses under one group
            (spec/021 §5.2)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled
                               :dispatch-id 1})
                    (mk-trace {:id 3 :op-type :rf.sub :operation :rf.sub/run})])])  ; nil dispatch-id
      (focus! 1)
      (let [tree (trace/Panel)
            rows (find-all-by-testid-prefix tree "rf-xray-trace-row-")]
        (is (some? (find-by-testid tree "rf-xray-trace-feed"))
            "feed <ul> present")
        (is (some? (find-by-testid tree "rf-xray-trace-row-1"))
            "the dispatch op row is a top-level row")
        (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
            "the fx op row is a top-level row")
        ;; The async nil-dispatch-id :rf.sub/run folds into the reactive
        ;; aftermath collapse group — NOT a top-level row (rf2-ad7zx.8).
        (is (nil? (find-by-testid tree "rf-xray-trace-row-3"))
            "the reactive sub row is collapsed (not a top-level row)")
        (is (some? (find-by-testid tree "rf-xray-trace-group-react-grp:3"))
            "a reactive-aftermath collapse group renders for the sub run")
        (is (>= (count rows) 2)
            "the two core-domino op rows render")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-events-renders-for-empty-epoch
  (testing "with a focused epoch carrying no trace events the panel
            renders the :no-events empty-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-empty-no-events"))
            ":no-events empty-state container present")
        (is (nil? (find-by-testid tree "rf-xray-trace-feed"))
            "no feed list when focused epoch carries no events")))))

(deftest empty-state-no-focus-renders
  (testing "rf2-td380: with no focus + no history the panel renders the
            :no-focus empty-state (cold start — the focus-resolver
            classifies nil-focus + empty-history as :no-focus)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-empty-no-focus"))
            "cold-start :no-focus empty-state present")
        (is (nil? (find-by-testid tree "rf-xray-trace-feed"))
            "no feed list at cold start")))))

(deftest empty-state-epoch-evicted-renders
  (testing "rf2-td380: when focus pins an :epoch-id no longer in
            :epoch-history the panel paints the evicted-epoch
            placeholder (mirrors the Issues panel, spec/021 §10.7)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (rf/dispatch-sync
        [:day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus])
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])
            tree (trace/Panel)]
        (is (= :epoch-evicted (:empty-kind feed))
            "composite signals :epoch-evicted when no record matches focus")
        (is (some? (find-by-testid tree "rf-xray-trace-empty-epoch-evicted"))
            "canonical placeholder container rendered")
        (is (nil? (find-by-testid tree "rf-xray-trace-feed"))
            "no feed list when the focused epoch has been evicted")))))

;; ---- (4) focused-epoch scope (refocus) ----------------------------------

(deftest trace-feed-rescopes-on-refocus
  (testing "rf2-td380: focusing a different epoch re-renders with that
            epoch's :trace-events"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= #{1 2} (set (map :id (:rows feed))))
            "epoch 1's rows — including the nil-dispatch-id reactive row 2")
        (is (= 1 (:epoch-id feed))))
      ;; Refocus to epoch 2 (cascade 200).
      (focus! 200)
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= #{3 4 5} (set (map :id (:rows feed))))
            "epoch 2's rows — the whole domino trail (event + sub + view)")
        (is (= 2 (:epoch-id feed)))))))

;; ---- (5) row interactions -----------------------------------------------

(deftest row-click-toggles-inline-payload-expansion
  (testing "rf2-7dyi8 — clicking a row dispatches
            :rf.xray/toggle-trace-row-expand with the row's :id rather
            than pivoting to event-detail. Per spec/021 §5.4 the Trace
            panel surfaces row payloads inline; no nav."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
                row     (find-by-testid tree "rf-xray-trace-row-7")
                handler (:on-click (second row))]
            (is (some? row) "row node present")
            (is (some? handler) "row carries an :on-click handler")
            (when handler (handler))))
        (is (some #(= [:rf.xray/toggle-trace-row-expand 7] %) @dispatches)
            ":rf.xray/toggle-trace-row-expand fired with the row's :id")
        (is (not-any? #(and (vector? %)
                            (= :rf.xray/select-dispatch-id (first %)))
                      @dispatches)
            "the legacy pivot is gone — no select-dispatch-id fired")))))

(deftest toggle-trace-row-expand-event-mutates-set
  (testing "rf2-7dyi8 — :rf.xray/toggle-trace-row-expand toggles row
            membership in :trace-expanded-row-ids set."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{11} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))
          "first toggle adds the id")
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 22])
      (is (= #{11 22} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))
          "second toggle on a different id appends")
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{22} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))
          "toggling an already-expanded row removes it")
      (rf/dispatch-sync [:rf.xray/clear-trace-expand])
      (is (= #{} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))
          ":rf.xray/clear-trace-expand drops every expanded id"))))

(deftest expanded-row-renders-cljs-devtools-payload
  (testing "rf2-7dyi8 + rf2-dmso5 — when a row's :id is in the expanded
            set, the current-state cljs-devtools browse renderer mounts
            below the row (Trace payloads are current-state)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 13 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1 :source :ui :origin :app})])])
      (focus! 1)
      ;; Pre-expansion — no payload.
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-row-13-payload"))
            "payload block absent when row is not in expanded set"))
      ;; Toggle expand → payload renders.
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 13])
      (let [tree    (trace/Panel)
            payload (find-by-testid tree "rf-xray-trace-row-13-payload")]
        (is (some? payload) "payload block renders when row is expanded")
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-widget-browse-trace-trace-row-13"))
            "cljs-devtools browse mounted with per-row render-id")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord chip fires :rf.xray/open-in-editor;
            stopPropagation prevents the row's expand-toggle from also
            firing (rf2-7dyi8 — the row click toggles inline expansion
            now, the source-coord button bubbles must not toggle it)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
                node    (find-by-testid tree "rf-xray-trace-row-9-source-coord")
                handler (:on-click (second node))]
            (is (some? node) "source-coord chip rendered")
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/open-in-editor (first ev))
                         (= {:source-coord "core.cljs:42"} (second ev))))
                  @dispatches)
            ":rf.xray/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called so the row's pivot
                       handler doesn't also fire")))))

;; ---- (5b) Figma structural surfaces — rf2-ad7zx.8 ----------------------

(defn- node-attrs
  "The attribute map of the rendered <li> with the given data-testid."
  [tree testid]
  (some-> (find-by-testid tree testid) second))

(deftest op-rows-band-by-op-family-left-border
  (testing "rf2-ad7zx.8: each op row carries a 3px op-family LEFT-BORDER
            (not an op-type dot) and a data-rf-xray-op-family attr"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                               :dispatch-id 1})
                    (mk-trace {:id 3 :op-type :rf.fx :operation :rf.fx/handled
                               :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        ;; The legacy op-type dot is gone.
        (is (nil? (find-by-testid tree "rf-xray-trace-row-1-op-type-dot"))
            "op-type dot is removed (rf2-ad7zx.8)")
        (is (= "dispatch" (:data-rf-xray-op-family
                            (node-attrs tree "rf-xray-trace-row-1")))
            "dispatch row carries the dispatch op-family")
        (is (= "db" (:data-rf-xray-op-family
                      (node-attrs tree "rf-xray-trace-row-2")))
            "db-changed row carries the db op-family")
        (is (= "fx" (:data-rf-xray-op-family
                      (node-attrs tree "rf-xray-trace-row-3")))
            "fx row carries the fx op-family")
        (let [border (get-in (node-attrs tree "rf-xray-trace-row-1")
                             [:style :border-left])]
          (is (and (string? border) (re-find #"^3px solid " border))
              "the op-family band is a 3px left-border on the row"))))))

(deftest op-rows-render-relative-time-and-duration-columns
  (testing "rf2-ad7zx.8: rows lead with a relative t+N.Nms time and
            carry a duration column (elapsed for event/view rows)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 1000 :dispatch-id 1})
                    (-> (mk-trace {:id 2 :op-type :rf.view :operation :rf.view/render
                                   :time 1002})
                        (assoc-in [:tags :elapsed-ms] 0.4))])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (= "t+0.0ms" (->> (find-by-testid tree "rf-xray-trace-row-1-time")
                              last))
            "first row reads t+0.0ms relative to the epoch origin")
        ;; The view-render row carries its elapsed duration; it folds into
        ;; the reactive group, so expand the group to read its child cell.
        (rf/dispatch-sync [:rf.xray/toggle-trace-group-expand "react-grp:2"])
        (let [tree (trace/Panel)
              dur  (->> (find-by-testid tree "rf-xray-trace-row-2-duration")
                       last)]
          (is (= "0.4 ms" dur)
              "the view-render row shows its 0.4 ms elapsed duration"))))))

(deftest reactive-aftermath-group-renders-and-toggles
  (testing "rf2-ad7zx.8: the reactive aftermath collapses under one
            expandable group; toggling it renders the child reactive
            rows inline"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.sub :operation :rf.sub/run :time 110})
                    (mk-trace {:id 3 :op-type :rf.sub :operation :rf.sub/run :time 111})
                    (mk-trace {:id 4 :op-type :rf.view :operation :rf.view/render :time 120})])])
      (focus! 1)
      ;; Collapsed by default — the group renders, children do not.
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-group-react-grp:2"))
            "reactive-aftermath group renders")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-2"))
            "child reactive rows are collapsed by default"))
      ;; Expand the group → children render.
      (rf/dispatch-sync [:rf.xray/toggle-trace-group-expand "react-grp:2"])
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-group-react-grp:2-children"))
            "expanded group renders its children container")
        (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
            "a child reactive row renders when the group is expanded")))))

(deftest reactive-group-click-fires-toggle-event
  (testing "rf2-ad7zx.8: clicking the reactive group dispatches
            :rf.xray/toggle-trace-group-expand with the group's id"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.sub :operation :rf.sub/run :time 110})])])
      (focus! 1)
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]    (swap! dispatches conj ev) nil)
                                     ([ev _o] (swap! dispatches conj ev) nil))]
          (let [tree    (trace/Panel)
                grp     (find-by-testid tree "rf-xray-trace-group-react-grp:2")
                handler (:on-click (second grp))]
            (is (some? grp) "reactive group node present")
            (when handler (handler))))
        (is (some #(= [:rf.xray/toggle-trace-group-expand "react-grp:2"] %)
                  @dispatches)
            "group click fires the toggle event with the group id")))))

(deftest toggle-trace-group-expand-event-mutates-set
  (testing "rf2-ad7zx.8: :rf.xray/toggle-trace-group-expand toggles
            membership in :trace-expanded-group-ids"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-group-expand "react-grp:5"])
      (is (= #{"react-grp:5"}
             @(rf/subscribe [:rf.xray/trace-expanded-group-ids]))
          "first toggle adds the group id")
      (rf/dispatch-sync [:rf.xray/toggle-trace-group-expand "react-grp:5"])
      (is (= #{} @(rf/subscribe [:rf.xray/trace-expanded-group-ids]))
          "toggling again removes it"))))

(deftest child-dispatch-rows-indent-via-depth
  (testing "rf2-ad7zx.8: a child dispatch (parent-dispatch-id → a parent
            in the same epoch) renders with a causal-nesting depth attr
            and a margin-left indent"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(-> (mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                   :time 100 :dispatch-id 1}))
                    ;; child dispatch — parent-dispatch-id points at 1
                    (-> (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/dispatched
                                   :time 110 :dispatch-id 2})
                        (assoc-in [:tags :rf.trace/parent-dispatch-id] 1))])])
      (focus! 1)
      (let [tree    (trace/Panel)
            parent  (node-attrs tree "rf-xray-trace-row-1")
            child   (node-attrs tree "rf-xray-trace-row-2")]
        (is (= 0 (:data-rf-xray-depth parent)) "root dispatch is depth 0")
        (is (= 1 (:data-rf-xray-depth child))  "child dispatch is depth 1")
        (is (= "0px" (get-in parent [:style :margin-left]))
            "root row has no indent")
        (is (not= "0px" (get-in child [:style :margin-left]))
            "child row is indented under its parent (cascade tree)")))))

;; ---- (5c) Figma fidelity — rf2-tha26 -----------------------------------

(deftest footer-legend-renders-below-the-feed
  (testing "rf2-tha26 — the Figma footer legend renders below the feed
            naming the row reading conventions (colour-banded by op-family
            · click a row → expand raw :operation + tags)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})])])
      (focus! 1)
      (let [tree   (trace/Panel)
            legend (find-by-testid tree "rf-xray-trace-footer-legend")]
        (is (some? legend) "footer legend renders in the feed branch")
        (is (= "colour-banded by op-family · click a row → expand raw :operation + tags"
               (last legend))
            "footer legend carries the reference copy")))))

(deftest footer-legend-absent-in-empty-states
  (testing "rf2-tha26 — the footer legend is scoped to the feed branch; it
            does NOT render when the panel shows an empty-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; Focused epoch with no trace events → :no-events empty-state.
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-empty-no-events"))
            "empty-state renders")
        (is (nil? (find-by-testid tree "rf-xray-trace-footer-legend"))
            "footer legend absent in the empty-state branch")))))

(deftest op-rows-are-rounded-pills-not-flat-divided
  (testing "rf2-tha26 — Figma rounded hover-pill rows: each op row carries
            a border-radius (rounded) + inter-row rhythm and DROPS the
            flat hairline `border-bottom` divider; the op-family 3px
            left-border is preserved"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})])])
      (focus! 1)
      (let [tree* (trace/Panel)
            attrs (node-attrs tree* "rf-xray-trace-row-1")
            style (:style attrs)]
        (is (= "4px" (:border-radius style)) "row is a rounded pill")
        (is (nil? (:border-bottom style))
            "the flat hairline divider is gone (pills, not divided rows)")
        (let [bl (:border-left style)]
          (is (and (string? bl) (re-find #"^3px solid " bl))
              "the op-family 3px left-border band is preserved"))))))

;; ---- (6) frame isolation ------------------------------------------------

(deftest trace-expand-state-does-not-leak-into-default-frame
  (testing "the panel's expand state lives on :rf/xray, never :rf/default"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 5]))
    (let [xray-db   (frame/frame-app-db-value :rf/xray)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{5} (:trace-expanded-row-ids xray-db))
          "expand set lands on Xray")
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
  `rf-xray-trace-row-<id>`. Returns nil when the row isn't rendered."
  [tree id]
  (let [testid (str "rf-xray-trace-row-" id)]
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
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; All three are core-domino op rows (event/fx/db-changed) so they
      ;; render as top-level rows — the reactive aftermath collapses into
      ;; a group (rf2-ad7zx.8), so this test uses non-reactive rows to
      ;; exercise the per-row key discipline directly.
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 11 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 22 :op-type :rf.fx :operation :rf.fx/handled
                               :time 200 :dispatch-id 1})
                    (mk-trace {:id 33 :op-type :rf.event :operation :rf.event/db-changed
                               :time 300 :dispatch-id 1})])])
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
  :day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus
  (fn [db _event]
    (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})))
