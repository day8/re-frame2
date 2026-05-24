(ns day8.re-frame2-xray.panels.trace-view-cljs-test
  "CLJS-side wiring + view tests for Xray's Trace panel — the whole-epoch
  trace ARC (spec/023-Trace-Panel.md).

  ## What's under test (in addition to the pure-data tests in
  `trace_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under `:rf.xray/trace-feed`
       (the epoch-scoped feed) + the row-expand / band-collapse events.

    2. **Render contract** — the arc tree (epoch envelope + 4 phase
       bands + the 5-column rows) matches the production view; the prior
       top header / chip-filter UI stays gone.

    3. **Focused-epoch scope** (spec/018 §6) — the panel surfaces the
       focused epoch record's `:trace-events` (the complete arc);
       refocusing changes the rendered feed.

    4. **Empty states** — `:no-events`, `:no-focus`, `:epoch-evicted`
       each render their distinct container.

    5. **Row interactions** — clicking a row toggles inline raw-EDN
       payload expansion; clicking the source-coord ↗ fires
       :open-in-editor and does NOT also toggle.

    6. **Phase bands** — every band renders (empty bands dimmed
       `(none)`); collapsing a band hides its rows.

    7. **React-key stability** — rows keyed on the stable trace id.

  ## Pure hiccup

  Same approach as `issues_ribbon_view_cljs_test.cljs` — walk the view's
  hiccup tree by `data-testid` rather than mounting to the DOM.

  ## Seeding

  The Trace panel is epoch-scoped: it reads the focused epoch record's
  `:trace-events`. Trace events are seeded by attaching them to a
  `:rf/epoch-record`'s `:trace-events` slot and syncing the per-frame
  ring via `:rf.xray/sync-epoch-history`, then focusing via
  `:rf.xray/focus-cascade`."
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

(def ^:private find-by-testid th/find-by-testid)

(defn- hiccup-seq
  "Expands fn components via the framework walker, then yields
  depth-first nodes."
  [tree]
  (tree-seq (some-fn vector? seq?) seq (th/expand-tree tree)))

(defn- node-attrs
  "The attribute map of the rendered node with the given data-testid."
  [tree testid]
  (some-> (find-by-testid tree testid) second))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; Construct a synthetic trace event living INSIDE an epoch record's
;; `:trace-events` slot.
(defn- mk-trace
  [{:keys [id time op-type operation source origin frame
           event-id handler-id dispatch-id reason tags]
    :or   {time 1000 tags {}}}]
  {:id        id
   :time      time
   :op-type   op-type
   :operation operation
   :source    source
   :tags      (cond-> tags
                origin      (assoc :rf.event/origin origin)
                frame       (assoc :frame frame)
                event-id    (assoc :rf.trace/event-id event-id)
                handler-id  (assoc :handler-id handler-id)
                dispatch-id (assoc :rf.trace/dispatch-id dispatch-id)
                source      (assoc :source source)
                reason      (assoc :reason reason))})

(defn- mk-epoch
  "Build a minimal `:rf/epoch-record` carrying the supplied
  `trace-events`. `dispatch-id` defaults to (10 + epoch-id) so the spine
  resolver pairs the record with `:rf.xray/focus-cascade <dispatch-id>`."
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
  "Pin focus to the cascade with the given `dispatch-id`."
  [dispatch-id]
  (rf/dispatch-sync [:rf.xray/focus-cascade dispatch-id nil]))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-trace-handlers
  (testing "register-xray-handlers! installs the epoch-scoped composite
            sub + the row-expand + band-collapse events"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/trace-feed))
        ":rf.xray/trace-feed sub registered")
    (is (some? (registrar/handler :sub :rf.xray/trace-expanded-row-ids))
        ":rf.xray/trace-expanded-row-ids sub registered")
    (is (some? (registrar/handler :sub :rf.xray/trace-collapsed-band-ids))
        ":rf.xray/trace-collapsed-band-ids sub registered")
    (is (some? (registrar/handler :event :rf.xray/toggle-trace-row-expand))
        ":rf.xray/toggle-trace-row-expand event registered")
    (is (some? (registrar/handler :event :rf.xray/toggle-trace-band-collapse))
        ":rf.xray/toggle-trace-band-collapse event registered")))

(deftest filter-handlers-are-gone
  (testing "the chip-filter subs + events MUST NOT register"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/trace-filters)))
    (is (nil? (registrar/handler :sub :rf.xray/trace-feed-state)))
    (is (nil? (registrar/handler :event :rf.xray/set-trace-filter)))
    (is (nil? (registrar/handler :event :rf.xray/clear-trace-filters)))))

(deftest trace-feed-defaults-no-focus
  (testing "with no focus + no epoch history the composite returns an
            empty feed with :no-focus"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= [] (:rows feed)))
        (is (zero? (:total feed)))
        (is (zero? (:rendered feed)))
        (is (= :no-focus (:empty-kind feed)))))))

(deftest trace-feed-projects-focused-epoch-events-into-rows
  (testing "with a focused epoch the composite returns one row per trace
            event in that epoch's :trace-events"
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

(deftest top-header-and-chip-filter-ui-are-gone
  (testing "the top header row + chip-filter UI stay removed"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 42
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 42 :source :ui})])])
      (focus! 42)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-counts")))
        (is (nil? (find-by-testid tree "rf-xray-trace-epoch-indicator")))
        (is (nil? (find-by-testid tree "rf-xray-trace-film-strip")))
        (is (nil? (find-by-testid tree "rf-xray-trace-axis-row-op-type")))
        (is (nil? (find-by-testid tree "rf-xray-trace-clear-filters")))))))

(deftest arc-renders-envelope-bands-and-rows
  (testing "spec/023 §2: a focused epoch renders the EPOCH OPEN/CLOSE
            envelope, the four phase bands in arc order, and the op rows
            in their bands"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 0 :op-type :rf.epoch :operation :rf.epoch/snapshotted
                               :time 99})
                    (mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled
                               :time 103 :dispatch-id 1})
                    (mk-trace {:id 3 :op-type :rf.sub :operation :rf.sub/run :time 110})
                    (mk-trace {:id 8 :op-type :rf.epoch :operation :rf.epoch/outcome
                               :time 121 :tags {:rf.epoch/outcome :ok}})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-feed")) "arc container present")
        (is (some? (find-by-testid tree "rf-xray-trace-envelope-open"))
            "EPOCH OPEN envelope present")
        (is (some? (find-by-testid tree "rf-xray-trace-envelope-close"))
            "EPOCH CLOSE envelope present")
        (is (some? (find-by-testid tree "rf-xray-trace-outcome-ok"))
            "the :ok outcome renders on the close envelope")
        (testing "all four phase bands render"
          (is (some? (find-by-testid tree "rf-xray-trace-band-dispatch")))
          (is (some? (find-by-testid tree "rf-xray-trace-band-event-handling")))
          (is (some? (find-by-testid tree "rf-xray-trace-band-effects")))
          (is (some? (find-by-testid tree "rf-xray-trace-band-reactive")))
          (is (some? (find-by-testid tree "rf-xray-trace-band-header-dispatch"))))
        (testing "the op rows land in their bands"
          (is (some? (find-by-testid tree "rf-xray-trace-row-1"))
              "the dispatch row renders")
          (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
              "the fx row renders")
          (is (some? (find-by-testid tree "rf-xray-trace-row-3"))
              "the reactive sub row renders"))))))

(deftest op-row-renders-the-five-columns
  (testing "spec/023 §3: each op row carries Δt · area badge ·
            what-happened · target/detail · duration"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 1000 :dispatch-id 1
                               :tags {:rf.event/v [:counter/inc]}})
                    (-> (mk-trace {:id 2 :op-type :rf.view :operation :rf.view/render
                                   :time 1002})
                        (assoc-in [:tags :elapsed-ms] 0.4))])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (= "+0.0" (last (find-by-testid tree "rf-xray-trace-row-1-time")))
            "Δt column reads +0.0 relative to the epoch origin")
        (is (= "EVENT" (last (find-by-testid tree "rf-xray-trace-row-1-badge")))
            "area badge column reads the neutral EVENT badge")
        (is (= "dispatched" (last (find-by-testid tree "rf-xray-trace-row-1-verb")))
            "what-happened column reads the verb")
        (is (some? (find-by-testid tree "rf-xray-trace-row-1-target"))
            "target/detail column present")
        (is (= "0.4 ms" (last (find-by-testid tree "rf-xray-trace-row-2-duration")))
            "duration column reads the view's elapsed ms")
        (is (= "—" (last (find-by-testid tree "rf-xray-trace-row-1-duration")))
            "an untimed op renders an em-dash duration")))))

(deftest op-row-carries-op-family-left-border-and-area-attr
  (testing "spec/023 §3: rows band by op-family 3px left-border + carry
            data attrs for area + op-family"
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
        (is (= "event" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-1"))))
        (is (= "db" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-2"))))
        (is (= "fx" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-3"))))
        (is (= "dispatch" (:data-rf-xray-op-family
                            (node-attrs tree "rf-xray-trace-row-1"))))
        (let [border (get-in (node-attrs tree "rf-xray-trace-row-1")
                             [:style :border-left])]
          (is (and (string? border) (re-find #"^3px solid " border))
              "the op-family band is a 3px left-border on the row"))))))

(deftest error-rows-are-emphasised-inline
  (testing "spec/023 §7: an error op renders inline at its chronological
            point, emphasised (severity attr + Δt leads with !)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :error :operation :rf.error/fx-handler-exception
                               :time 105 :reason "boom"})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
            "the error row renders inline (not hidden)")
        (is (= "error" (:data-rf-xray-severity (node-attrs tree "rf-xray-trace-row-2")))
            "the error row carries the severity attr")
        (is (= "ERROR" (last (find-by-testid tree "rf-xray-trace-row-2-badge")))
            "the error row's badge reads ERROR")
        (let [t (last (find-by-testid tree "rf-xray-trace-row-2-time"))]
          (is (and (string? t) (re-find #"^!" t))
              "the error row's Δt leads with ! for emphasis"))))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-events-renders-for-empty-epoch
  (testing "a focused epoch carrying no trace events → :no-events"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-empty-no-events")))
        (is (nil? (find-by-testid tree "rf-xray-trace-feed"))
            "no arc when focused epoch carries no events")))))

(deftest empty-state-no-focus-renders
  (testing "with no focus + no history → :no-focus empty-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-empty-no-focus")))
        (is (nil? (find-by-testid tree "rf-xray-trace-feed")))))))

(deftest empty-state-epoch-evicted-renders
  (testing "when focus pins an :epoch-id no longer in :epoch-history →
            the evicted-epoch placeholder"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (rf/dispatch-sync
        [:day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus])
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])
            tree (trace/Panel)]
        (is (= :epoch-evicted (:empty-kind feed)))
        (is (some? (find-by-testid tree "rf-xray-trace-empty-epoch-evicted")))
        (is (nil? (find-by-testid tree "rf-xray-trace-feed")))))))

;; ---- (4) focused-epoch scope (refocus) ----------------------------------

(deftest trace-feed-rescopes-on-refocus
  (testing "focusing a different epoch re-renders with that epoch's
            :trace-events"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 100
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 100})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled})])
         (mk-epoch 2 200
                   [(mk-trace {:id 3 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 200})
                    (mk-trace {:id 4 :op-type :rf.sub :operation :rf.sub/run})
                    (mk-trace {:id 5 :op-type :rf.view :operation :rf.view/render})])])
      (focus! 100)
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= #{1 2} (set (map :id (:rows feed)))))
        (is (= 1 (:epoch-id feed))))
      (focus! 200)
      (let [feed @(rf/subscribe [:rf.xray/trace-feed])]
        (is (= #{3 4 5} (set (map :id (:rows feed)))))
        (is (= 2 (:epoch-id feed)))))))

;; ---- (5) row interactions -----------------------------------------------

(deftest row-click-toggles-inline-payload-expansion
  (testing "spec/023 §3 — clicking a row dispatches
            :rf.xray/toggle-trace-row-expand with the row's :id; no nav"
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
            "no legacy pivot fired")))))

(deftest toggle-trace-row-expand-event-mutates-set
  (testing ":rf.xray/toggle-trace-row-expand toggles row membership"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{11} @(rf/subscribe [:rf.xray/trace-expanded-row-ids])))
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 22])
      (is (= #{11 22} @(rf/subscribe [:rf.xray/trace-expanded-row-ids])))
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{22} @(rf/subscribe [:rf.xray/trace-expanded-row-ids])))
      (rf/dispatch-sync [:rf.xray/clear-trace-expand])
      (is (= #{} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))))))

(deftest expanded-row-renders-raw-edn-payload
  (testing "spec/023 §3 — when a row's :id is in the expanded set, the
            raw trace-event EDN renders below the row via cljs-devtools"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 13 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1 :source :ui :origin :app})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-row-13-payload"))
            "payload absent when row not expanded"))
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 13])
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-13-payload"))
            "payload renders when row expanded")
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-widget-browse-trace-trace-row-13"))
            "cljs-devtools browse mounted with per-row render-id")))))

(deftest source-coord-click-fires-open-in-editor
  (testing "clicking the source-coord ↗ fires :rf.xray/open-in-editor;
            stopPropagation prevents the row's expand-toggle from firing"
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
            (is (some? node) "source-coord ↗ rendered")
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/open-in-editor (first ev))
                         (= {:source-coord "core.cljs:42"} (second ev))))
                  @dispatches)
            ":rf.xray/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called")))))

;; ---- (6) phase bands — spec/023 §13 / §14 -----------------------------

(deftest empty-band-renders-dimmed-none
  (testing "spec/023 §13: a no-op event keeps ③④ present with a `(none)`
            count, never hidden"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-band-effects"))
            "③ EFFECTS band present even when empty")
        (is (= "(none)" (last (find-by-testid tree "rf-xray-trace-band-count-effects")))
            "an empty band's count reads (none)")
        (is (true? (:data-rf-xray-empty
                     (node-attrs tree "rf-xray-trace-band-header-effects")))
            "the empty band header carries the empty attr")))))

(deftest collapsing-a-band-hides-its-rows
  (testing "spec/023 §2 / §14: collapsing a band hides its rows; the
            header stays"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})])])
      (focus! 1)
      ;; expanded by default — the dispatch row + its band rows ul render
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-band-rows-dispatch")))
        (is (some? (find-by-testid tree "rf-xray-trace-row-1"))))
      ;; collapse the dispatch band → rows hidden, header remains
      (rf/dispatch-sync [:rf.xray/toggle-trace-band-collapse :dispatch])
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-band-rows-dispatch"))
            "collapsed band hides its rows ul")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-1"))
            "the band's row is hidden when collapsed")
        (is (some? (find-by-testid tree "rf-xray-trace-band-header-dispatch"))
            "the band header stays so the phase is still labelled")))))

(deftest band-header-click-fires-toggle-event
  (testing "spec/023 §2: clicking a non-empty band header dispatches
            :rf.xray/toggle-trace-band-collapse with the band id"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})])])
      (focus! 1)
      (let [dispatches (atom [])]
        (with-redefs [rf/dispatch* (fn
                                     ([ev]    (swap! dispatches conj ev) nil)
                                     ([ev _o] (swap! dispatches conj ev) nil))]
          (let [tree    (trace/Panel)
                header  (find-by-testid tree "rf-xray-trace-band-header-dispatch")
                handler (:on-click (second header))]
            (is (some? header))
            (is (some? handler) "non-empty band header carries on-click")
            (when handler (handler))))
        (is (some #(= [:rf.xray/toggle-trace-band-collapse :dispatch] %)
                  @dispatches)
            "band header click fires the toggle with the band id")))))

(deftest toggle-trace-band-collapse-event-mutates-set
  (testing ":rf.xray/toggle-trace-band-collapse toggles membership"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-band-collapse :effects])
      (is (= #{:effects} @(rf/subscribe [:rf.xray/trace-collapsed-band-ids])))
      (rf/dispatch-sync [:rf.xray/toggle-trace-band-collapse :effects])
      (is (= #{} @(rf/subscribe [:rf.xray/trace-collapsed-band-ids]))))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest trace-expand-state-does-not-leak-into-default-frame
  (testing "the panel's expand state lives on :rf/xray, never :rf/default"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 5]))
    (let [xray-db    (frame/frame-app-db-value :rf/xray)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= #{5} (:trace-expanded-row-ids xray-db)))
      (is (nil? (:trace-expanded-row-ids default-db))))))

;; ---- (8) React-key stability across the feed ---------------------------

(defn- row-li-by-id
  "Walk the rendered tree and return the `<li>` whose data-testid is
  `rf-xray-trace-row-<id>`."
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
  (testing "the rendered <li> :key is the stable trace id (`t:<id>`),
            distinct per row and free of any positional component"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
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
        (is (= "t:11" k11))
        (is (= "t:22" k22))
        (is (= "t:33" k33))
        (is (= 3 (count (distinct [k11 k22 k33]))))))))

;; ---- evicted-focus helper ----------------------------------------------

;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path.
(rf/reg-event-db
  :day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus
  (fn [db _event]
    (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})))
