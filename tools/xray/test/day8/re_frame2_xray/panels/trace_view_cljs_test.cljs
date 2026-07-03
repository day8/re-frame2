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
  `:rf.xray/focus-event`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
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
  resolver pairs the record with `:rf.xray/focus-event <dispatch-id>`."
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

(defn- mk-epoch-with-db
  "Like `mk-epoch` but pins `:db-before` / `:db-after` so the trace
  panel's per-path db-changed diff (rf2-b3zw2) has real snapshots to
  derive from."
  [epoch-id dispatch-id db-before db-after trace-events]
  (-> (mk-epoch epoch-id dispatch-id trace-events)
      (assoc :db-before db-before
             :db-after  db-after)))

(defn- seed-history!
  "Dispatch `:rf.xray/sync-epoch-history` to seed the per-frame ring
  buffer. Must be called inside `(rf/with-frame :rf/xray ...)`."
  [records]
  (rf/dispatch-sync [:rf.xray/sync-epoch-history (vec records)]))

(defn- focus!
  "Pin focus to the cascade with the given `dispatch-id`."
  [dispatch-id]
  (rf/dispatch-sync [:rf.xray/focus-event dispatch-id nil]))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-trace-handlers
  (testing "register-xray-handlers! installs the epoch-scoped composite
            sub + the row-expand event"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/trace-feed))
        ":rf.xray/trace-feed sub registered")
    (is (some? (registrar/handler :sub :rf.xray.trace/focused-cascade))
        ":rf.xray.trace/focused-cascade layer-3 sub registered (rf2-wcfsy)")
    (is (some? (registrar/handler :sub :rf.xray/trace-expanded-row-ids))
        ":rf.xray/trace-expanded-row-ids sub registered")
    (is (some? (registrar/handler :event :rf.xray/toggle-trace-row-expand))
        ":rf.xray/toggle-trace-row-expand event registered")))

(deftest band-collapse-handlers-are-gone
  (testing "rf2-aqusw — the flat list lost the phase-band hierarchy, so
            the band-collapse sub + event MUST NOT register"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/trace-collapsed-band-ids))
        ":rf.xray/trace-collapsed-band-ids sub removed with the bands")
    (is (nil? (registrar/handler :event :rf.xray/toggle-trace-band-collapse))
        ":rf.xray/toggle-trace-band-collapse event removed with the bands")))

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

(deftest flat-list-renders-every-op-as-a-row
  (testing "rf2-aqusw: a focused epoch renders ALL its ops as a single
            flat list of rows — no envelope, no phase bands. The
            epoch-lifecycle ops surface as ordinary rows."
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
        (is (some? (find-by-testid tree "rf-xray-trace-feed")) "feed container present")
        (is (some? (find-by-testid tree "rf-xray-trace-rows"))
            "the flat row list container renders")
        (testing "the hierarchy chrome is GONE (rf2-aqusw)"
          (is (nil? (find-by-testid tree "rf-xray-trace-envelope-open")))
          (is (nil? (find-by-testid tree "rf-xray-trace-envelope-close")))
          (is (nil? (find-by-testid tree "rf-xray-trace-band-dispatch")))
          (is (nil? (find-by-testid tree "rf-xray-trace-band-event-handling")))
          (is (nil? (find-by-testid tree "rf-xray-trace-band-effects")))
          (is (nil? (find-by-testid tree "rf-xray-trace-band-reactive")))
          (is (nil? (find-by-testid tree "rf-xray-trace-band-header-dispatch"))))
        (testing "every op — including the epoch-lifecycle ops — is a flat row"
          (is (some? (find-by-testid tree "rf-xray-trace-row-0"))
              "the EPOCH snapshotted lifecycle op is an ordinary row")
          (is (some? (find-by-testid tree "rf-xray-trace-row-1")) "dispatch row")
          (is (some? (find-by-testid tree "rf-xray-trace-row-2")) "fx row")
          (is (some? (find-by-testid tree "rf-xray-trace-row-3")) "sub row")
          (is (some? (find-by-testid tree "rf-xray-trace-row-8"))
              "the EPOCH outcome lifecycle op is an ordinary row"))))))

(deftest op-row-renders-the-six-columns
  (testing "rf2-aqusw: each op row carries Δt · stage · area badge ·
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
        (is (= "DISPATCH" (last (find-by-testid tree "rf-xray-trace-row-1-stage")))
            "stage column reads the Epoch DISPATCH step (dispatched op)")
        (is (= "VIEWS" (last (find-by-testid tree "rf-xray-trace-row-2-stage")))
            "stage column reads the Epoch VIEWS step (view render op)")
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

(deftest op-row-carries-colour-coded-stage-edge-and-attrs
  (testing "rf2-aqusw: rows carry a 3px colour-coded left edge keyed to
            the Epoch pipeline stage + data attrs for area + stage"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                               :dispatch-id 1})
                    (mk-trace {:id 3 :op-type :rf.fx :operation :rf.fx/handled
                               :dispatch-id 1})
                    (mk-trace {:id 4 :op-type :rf.sub :operation :rf.sub/run})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (= "event" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-1"))))
        (is (= "db" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-2"))))
        (is (= "fx" (:data-rf-xray-area (node-attrs tree "rf-xray-trace-row-3"))))
        (testing "the stage data-attr names the Epoch pipeline step"
          (is (= "DISPATCH" (:data-rf-xray-stage
                              (node-attrs tree "rf-xray-trace-row-1"))))
          (is (= "SIDE-EFFECTS" (:data-rf-xray-stage
                                 (node-attrs tree "rf-xray-trace-row-2")))
              "the :db commit maps to the Epoch SIDE-EFFECTS step")
          (is (= "SIDE-EFFECTS" (:data-rf-xray-stage
                                 (node-attrs tree "rf-xray-trace-row-3")))
              "the fx op maps to the Epoch SIDE-EFFECTS step")
          (is (= "SUBSCRIPTIONS" (:data-rf-xray-stage
                                  (node-attrs tree "rf-xray-trace-row-4")))))
        (let [border (get-in (node-attrs tree "rf-xray-trace-row-1")
                             [:style :border-left])]
          (is (and (string? border) (re-find #"^3px solid " border))
              "the colour-coded stage band is a 3px left-border on the row"))))))

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

;; ---- (2b) per-path db-changed diff (rf2-b3zw2 / rf2-8q8i4 = (b)) -------
;;
;; The `:rf.event/db-changed` trace event carries only `:event` + `:frame`
;; — no per-path diff. The Trace panel derives per-path before→after
;; rows at render time from the focused epoch record's `:db-before` /
;; `:db-after` (Mike-decided rf2-8q8i4 = (b), 2026-05-25 — PANEL-SIDE
;; derive). The view renders one sub-row per changed path beneath the
;; DB row:
;;
;;     + [:path] new           (added)
;;     ~ [:path] old → new     (modified)
;;     - [:path]               (removed — path alone)
;;
;; spec/023 §APP-DB CHANGES — empty diff (db-before == db-after) renders
;; no sub-list.

(defn- db-diff-row-by-suffix
  "Walk the rendered tree for a per-path diff row under the
  db-changed row with id `parent-row-id`; suffix is the
  `path-suffix`-shaped string the renderer builds (e.g. `:counter` /
  `:user_:age`)."
  [tree parent-row-id suffix]
  (find-by-testid tree (str "rf-xray-trace-row-" parent-row-id
                            "-db-diff-row-" suffix)))

(deftest db-changed-row-renders-per-path-diff-rows-flow-less
  (testing "a flow-less event with a non-trivial db-changed renders one
            per-path row beneath the DB row — modified path only"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 {:counter 1} {:counter 2}
            [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :time 100 :dispatch-id 1
                        :tags {:rf.event/v [:counter/inc]}})
             (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-2-db-diff"))
            "the db-diff section renders beneath the db-changed row")
        (let [row (db-diff-row-by-suffix tree 2 ":counter")]
          (is (some? row)
              "the [:counter] modified row renders")
          (is (= "modified" (:data-op (second row))))
          (is (= "~" (last (find-by-testid
                             tree "rf-xray-trace-row-2-db-diff-row-:counter-glyph")))
              "modified-row glyph reads ~"))))))

(deftest db-changed-row-renders-added-and-removed-rows
  (testing "added (`+`) and removed (`-`) per-path rows render with
            their op tones"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 {:stale :x} {:flag true}
            [(mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (let [tree    (trace/Panel)
            added   (db-diff-row-by-suffix tree 2 ":flag")
            removed (db-diff-row-by-suffix tree 2 ":stale")]
        (is (some? added) "the [:flag] added row renders")
        (is (= "added" (:data-op (second added))))
        (is (= "+" (last (find-by-testid
                           tree "rf-xray-trace-row-2-db-diff-row-:flag-glyph"))))
        (is (some? removed) "the [:stale] removed row renders")
        (is (= "removed" (:data-op (second removed))))
        (is (= "-" (last (find-by-testid
                           tree "rf-xray-trace-row-2-db-diff-row-:stale-glyph"))))))))

(deftest db-changed-row-renders-nested-and-top-level-paths
  (testing "top-level + nested-key diffs both surface as per-path rows"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1
            {:counter 1 :user {:name "Ada" :age 30}}
            {:counter 2 :user {:name "Ada" :age 31}}
            [(mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (db-diff-row-by-suffix tree 2 ":counter"))
            "top-level [:counter] row renders")
        (is (some? (db-diff-row-by-suffix tree 2 ":user_:age"))
            "nested [:user :age] row renders")
        ;; :user :name unchanged → no row
        (is (nil? (db-diff-row-by-suffix tree 2 ":user_:name"))
            "unchanged [:user :name] does NOT render")))))

(deftest db-changed-row-empty-diff-renders-no-sub-list
  (testing "db-before == db-after → empty diff → no sub-list rendered
            (spec/023 §APP-DB CHANGES empty-diff case)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 {:counter 1} {:counter 1}
            [(mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
            "the db-changed row itself still renders")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-2-db-diff"))
            "no diff sub-list when db-before == db-after")))))

(deftest non-db-changed-row-renders-no-diff-section
  (testing "an event-row that is not :rf.event/db-changed never renders
            a per-path diff section, regardless of db-before/db-after"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 {:counter 1} {:counter 2}
            [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :time 100 :dispatch-id 1})
             (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})
             (mk-trace {:id 3 :op-type :rf.fx :operation :rf.fx/handled
                        :time 105 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-trace-row-1-db-diff"))
            "the dispatch row carries no diff section")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-3-db-diff"))
            "the fx row carries no diff section")
        (is (some? (find-by-testid tree "rf-xray-trace-row-2-db-diff"))
            "only the db-changed row carries the diff section")))))

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

;; ---- (4b) focused-cascade layer-3 sub (rf2-wcfsy) ----------------------
;;
;; The Trace panel reads the focused cascade record via the layer-3
;; composite `:rf.xray.trace/focused-cascade` rather than scanning the
;; full cascades vector inline in its render body. The sub composes
;; over `:rf.xray/cascades` + `:rf.xray/focus`; its result is the
;; cascade whose `:dispatch-id` matches the focus' `:dispatch-id`, or
;; nil when no focus is pinned.

(defn- mk-cascade-trace
  "Seed a synthetic `:rf.event/dispatched` trace event into Xray's
  trace buffer so `group-by-event` yields one cascade record per
  dispatch-id. Mirrors the seeding pattern in registry_cljs_test.cljs
  so the data-layer pipe matches production."
  [dispatch-id event-vec]
  (trace-collector/seed-trace-for-test!
    {:operation :rf.event/dispatched
     :op-type   :rf.event
     :id        dispatch-id
     :time      (* dispatch-id 1000)
     :tags      {:rf.trace/dispatch-id dispatch-id
                 :rf.event/v           event-vec
                 :rf.trace/event-id    (first event-vec)
                 :frame                :rf/default}}))

(deftest focused-cascade-sub-nil-when-cascades-empty
  (testing "rf2-wcfsy — with no cascades + no focus the composite
            returns nil (the inline scan's
            `(when focused-id ...)` guard preserved). The spine
            composer auto-snaps focus to head only when cascades
            exist, so the truly-empty case is the structural nil
            path."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= 0 (count @(rf/subscribe [:rf.xray/cascades])))
          "no cascades in the input signal")
      (is (nil? (:dispatch-id @(rf/subscribe [:rf.xray/focus])))
          "no focus pinned + no head → focused-id is nil")
      (is (nil? @(rf/subscribe [:rf.xray.trace/focused-cascade]))
          "focused-id nil → composite returns nil"))))

(deftest focused-cascade-sub-returns-matching-cascade
  (testing "rf2-wcfsy — when focus is pinned the composite returns the
            cascade whose :dispatch-id matches focus :dispatch-id;
            same record-shape the previous inline scan returned"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (mk-cascade-trace 1 [:cart/add])
      (mk-cascade-trace 2 [:cart/remove])
      (mk-cascade-trace 3 [:checkout/start])
      (focus! 2)
      (let [result @(rf/subscribe [:rf.xray.trace/focused-cascade])]
        (is (some? result) "the focused cascade record is returned")
        (is (= 2 (:dispatch-id result))
            "the returned cascade's :dispatch-id matches the focus")
        (is (= [:cart/remove] (:event result))
            "the returned record carries the cascade's :event vector")))))

(deftest focused-cascade-sub-nil-when-focus-misses
  (testing "rf2-wcfsy — focus pinned to a :dispatch-id that's not in
            the cascades vector → nil (the previous inline scan's
            `some` returned nil in this case; same shape)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (mk-cascade-trace 1 [:cart/add])
      (focus! 999)
      (is (nil? @(rf/subscribe [:rf.xray.trace/focused-cascade]))
          "focused-id with no matching cascade → nil"))))

(deftest focused-cascade-sub-rescopes-on-refocus
  (testing "rf2-wcfsy — refocusing changes the returned cascade
            record (reactivity wired through the composite signals)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (mk-cascade-trace 1 [:cart/add])
      (mk-cascade-trace 2 [:cart/remove])
      (focus! 1)
      (is (= 1 (:dispatch-id @(rf/subscribe [:rf.xray.trace/focused-cascade]))))
      (focus! 2)
      (is (= 2 (:dispatch-id @(rf/subscribe [:rf.xray.trace/focused-cascade])))))))

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
            raw trace-event EDN renders below the row via the first-class
            edn-inspector widget (rf2-hhtbl phase 2: direct
            `[ei/edn-inspector value opts]`, no facade hop)"
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
        ;; rf2-hhtbl phase 2 — the per-row payload renders the widget
        ;; with a per-row panel-id qualifier (`:rf.xray.trace/row-<id>`)
        ;; so two simultaneously-expanded rows can't share expansion
        ;; state. The widget's `testid-for` uses `(name panel-id)`, so
        ;; the container testid resolves to `rf-xray-edn-inspector-row-13-<uuid>`.
        (let [dd-nodes (filter (fn [n]
                                 (and (vector? n)
                                      (map? (second n))
                                      (some-> (:data-testid (second n))
                                              (.startsWith "rf-xray-edn-inspector-row-13-"))))
                               (hiccup-seq tree))]
          (is (seq dd-nodes)
              "edn-inspector widget mounted for the expanded row with the per-row panel-id qualifier"))))))

(deftest expanded-row-uses-per-row-panel-id-qualifier
  (testing "rf2-hhtbl phase 2 — two simultaneously-expanded rows each
            mount the edn-inspector widget with a DISTINCT per-row
            panel-id qualifier (`:rf.xray.trace/row-<id>` rendered as
            `row-<id>` in the testid via `(name ...)`) so their
            expansion state can't collide. Combined with the widget's
            auto-generated per-mount UUID this gives belt-and-braces
            isolation."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 41 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1 :source :ui})
                    (mk-trace {:id 42 :op-type :rf.fx :operation :rf.fx/handled
                               :time 110 :dispatch-id 1})])])
      (focus! 1)
      ;; Expand both rows at once.
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 41])
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 42])
      (let [tree         (trace/Panel)
            dd-testids   (->> (hiccup-seq tree)
                              (keep (fn [n]
                                      (when (and (vector? n) (map? (second n)))
                                        (:data-testid (second n)))))
                              (filter #(and (string? %)
                                            (.startsWith ^String %
                                                         "rf-xray-edn-inspector-row-")))
                              (into #{}))
            row-41-hits  (filter #(.startsWith ^String % "rf-xray-edn-inspector-row-41-")
                                 dd-testids)
            row-42-hits  (filter #(.startsWith ^String % "rf-xray-edn-inspector-row-42-")
                                 dd-testids)]
        (is (seq row-41-hits)
            "row 41's edn-inspector container carries the :rf.xray.trace/row-41 qualifier")
        (is (seq row-42-hits)
            "row 42's edn-inspector container carries the :rf.xray.trace/row-42 qualifier")
        (is (not= (first row-41-hits) (first row-42-hits))
            "the two rows mount distinct edn-inspector containers")))))

(deftest expanded-row-edn-inspector-carries-popup-affordance
  (testing "rf2-l4625 — the expanded-row payload mount passes
            `:popup-affordance? true` to the edn-inspector widget so
            the operator can pop a trace event's full EDN into the
            popup overlay (trace rows are narrow column space). After
            `expand-tree` the edn-inspector widget's outer `:div` carries
            `:data-rf-popup-affordance \"1\"` when the opt is set."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 51 :op-type :rf.event
                               :operation :rf.event/dispatched
                               :dispatch-id 1 :source :ui :origin :app})])])
      (focus! 1)
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 51])
      (let [tree     (trace/Panel)
            payload  (find-by-testid tree "rf-xray-trace-row-51-payload")
            ;; Walk the payload subtree (which is itself the result of
            ;; `expand-tree`-ing) and find the edn-inspector widget's
            ;; outer container — it carries the `data-rf-popup-affordance`
            ;; attribute when the opt is enabled.
            dd-containers
            (filter (fn [n]
                      (and (vector? n) (map? (second n))
                           (= "1" (:data-rf-popup-affordance
                                    (second n)))))
                    (hiccup-seq payload))]
        (is (some? payload) "expanded-row payload renders")
        (is (seq dd-containers)
            "edn-inspector container surfaces the popup-affordance attr")))))

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

;; ---- (6) flat list, no hierarchy — rf2-aqusw --------------------------

(deftest flat-list-preserves-fire-order-across-stages
  (testing "rf2-aqusw: ops from different pipeline stages render in ONE
            flat list, in fire order (oldest-first) — not regrouped into
            bands. A no-op event no longer renders empty phase bands."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})
                    (mk-trace {:id 2 :op-type :rf.sub :operation :rf.sub/run :time 110})
                    (mk-trace {:id 3 :op-type :rf.fx :operation :rf.fx/handled
                               :time 120 :dispatch-id 1})])])
      (focus! 1)
      (let [tree (trace/Panel)]
        ;; no band chrome at all (rf2-aqusw)
        (is (nil? (find-by-testid tree "rf-xray-trace-band-effects")))
        (is (nil? (find-by-testid tree "rf-xray-trace-band-count-effects")))
        (is (nil? (find-by-testid tree "rf-xray-trace-band-rows-dispatch")))
        ;; the rows render in seeded fire order — the reactive SUB op
        ;; lands BETWEEN the dispatch and the fx, not regrouped to the end
        (let [rows-container (find-by-testid tree "rf-xray-trace-rows")
              row-ids (->> (hiccup-seq rows-container)
                           (keep (fn [n]
                                   (when (and (vector? n) (map? (second n)))
                                     (let [tid (:data-testid (second n))]
                                       (when (and (string? tid)
                                                  (re-find #"^rf-xray-trace-row-\d+$" tid))
                                         (subs tid (count "rf-xray-trace-row-")))))))
                           (distinct)
                           (vec))]
          (is (= ["1" "2" "3"] row-ids)
              "rows are flat + in fire order, not regrouped into bands"))))))

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

(defn- row-node-by-id
  "Walk the rendered tree and return the row container (`:div` since
  rf2-jnxfj — formerly `:li`) whose data-testid is
  `rf-xray-trace-row-<id>`."
  [tree id]
  (let [testid (str "rf-xray-trace-row-" id)]
    (some (fn [node]
            (when (and (vector? node)
                       (map? (second node))
                       (= testid (:data-testid (second node))))
              node))
          (hiccup-seq tree))))

(deftest trace-row-react-keys-are-stable-trace-ids
  (testing "rf2-jnxfj — rows now ride `rt/resizable-table` so the row
            container is a `:div` (formerly `:li`). `op-row-attrs`
            stamps the `(h/row-key row)` value into the attrs map's
            `:key` slot so the contract surface stays observable in
            the rendered hiccup; resizable-table also threads the
            same value via the meta-key it emits for React's
            reconciler."
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
            k11  (:key (second (row-node-by-id tree 11)))
            k22  (:key (second (row-node-by-id tree 22)))
            k33  (:key (second (row-node-by-id tree 33)))]
        (is (= "t:11" k11))
        (is (= "t:22" k22))
        (is (= "t:33" k33))
        (is (= 3 (count (distinct [k11 k22 k33]))))))))

;; ---- evicted-focus helper ----------------------------------------------

;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path.
(rf/reg-event
  :day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus
  (fn [{:keys [db]} _event]
    {:db (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})}))
