(ns day8.re-frame2-xray.panels.trace-view-cljs-test
  "View tests for Xray's Trace panel (spec/023-Trace-Panel.md), walking the
  hiccup of `trace/panel-tree` — the pure body of the `Panel` Fresco
  boundary — by `data-testid` rather than mounting to the DOM. The
  projection itself is `trace_helpers_cljs_test.cljc`'s.

  The panel is epoch-scoped, so trace events are seeded on an
  `:rf/epoch-record`'s `:trace-events`, synced via
  `:rf.xray/sync-epoch-history`, and focused via `:rf.xray/focus-event`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.install :as install]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.resizable-table :as rt]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.panels.trace :as trace]))

;; ---- fixtures -----------------------------------------------------------

;; ---- evicted-focus helper ----------------------------------------------

;; Registered ABOVE the fixture form — the reset fixture's
;; source-store baseline is captured when `use-fixtures` evaluates, and a
;; top-level registration AFTER it would be erased from the store by every
;; per-test restore (invisible to the frame's sealed generation even though
;; the registrar-merge leg preserved it).
;; A test-only event that pins :focus to an :epoch-id that's not in
;; history — exercises the :epoch-evicted classifier path.
(rf/reg-event
  :day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus
  (fn [{:keys [db]} _event]
    {:db (assoc db :focus {:dispatch-id 999
                      :epoch-id    999
                      :mode        :retro
                      :frame       nil})}))

;; The PINNED-NO-EPOCH focus: the operator selected an event
;; bundle that settled no epoch, so `spine/epoch-id-for-event-bundle`
;; stamped `:epoch-id` nil while the pinned `:dispatch-id` survives. Seeded
;; directly (same route as the evicted pin above) so the state is
;; deterministic rather than dependent on which bundles happen to be in the
;; ring. Registered ABOVE the fixture form for the reason given
;; there.
(rf/reg-event
  :day8.re-frame2-xray.panels.trace-view-cljs-test/seed-no-epoch-focus
  (fn [{:keys [db]} _event]
    {:db (assoc db :focus {:dispatch-id 999
                      :epoch-id    nil
                      :mode        :retro
                      :frame       nil})}))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset:
  ;; plain-atom adapter + the default `:all` reset tier. (`trace-collector`
  ;; is required for the `seed-trace-for-test!` seeding below.)
  (xray-test-support/make-xray-runtime-fixture))

;; ---- the panel's body, and the two walkers over it ----------------------
;;
;; `trace/Panel` IS A FRESCO BOUNDARY, so the rows
;; below do not call it. A boundary is a real React function component:
;; its body reads through Fresco's collector, which refuses a read outside a
;; render extent by name (`:rf.error/fresco-sub-outside-render`). The panel's
;; body is `trace/panel-tree`, a pure fn of the four values the
;; boundary reads, and [[panel-tree]] below supplies them.

(defn- panel-tree
  "The Trace panel's body, driven from the ambient frame's own subs.

  `trace/panel-tree` renders the markup and testids the boundary commits,
  so the rows below read what the panel renders."
  []
  (trace/panel-tree
    {:feed                 @(rf/subscribe [:rf.xray/trace-feed])
     :focus                @(rf/subscribe [:rf.xray/focus])
     :focused-event-bundle @(rf/subscribe [:rf.xray.trace/focused-event-bundle])
     :expanded-ids         @(rf/subscribe [:rf.xray/trace-expanded-row-ids])}))

(defn- lower-head
  "One hiccup node with a FRESCO BOUNDARY head swapped for the Reagent
  `reg-view` sibling of the SAME widget, or `node` unchanged. `lower`
  selects which of the two widgets to lower — `:table`, `:inspector`.

  Both widgets ship both heads on purpose, and both hand the same values
  to ONE renderer — `views/resizable_table.cljs`'s `render-table` and
  `views/edn_inspector.cljs`'s `render-inspector` — differing only in HOW
  each resolves the two things the renderer cannot resolve for itself (the
  read and the dispatcher). So the swap changes the resolution route and
  not the rendering, which is what makes it honest for the structural
  assertions in this file.

  It is a LOWERING and never a claim about which head the panel ships:
  [[panel-heads-are-the-ones-the-codec-accepts]] grades that with the
  codec's own classifier over the UNLOWERED tree, so a Reagent head at any
  of the three sites reds that row whatever this walker does."
  [lower node]
  (let [head (first node)]
    (cond
      (and (lower :table) (identical? head rt/resizable-table-view))
      (assoc node 0 rt/resizable-table)

      (and (lower :inspector) (identical? head ei/edn-inspector-view))
      (let [{:keys [value opts]} (second node)]
        [ei/edn-inspector value opts])

      :else node)))

(defn- expand-tree*
  "`rf.test-helpers/expand-tree`, with [[lower-head]] applied ON THE WAY
  DOWN.

  A pre-pass will not do, and that is the whole reason this exists rather
  than a `postwalk` over the finished tree: the panel's boundary heads are
  not all present at the start. Expanding the table invokes the
  `:row-extras` callback, and an expanded row's payload is a FRESH
  `[ei/edn-inspector-view …]` node minted during that expansion — which
  the framework walker would reach, call, and throw on.

  A BOUNDARY HEAD IS NEVER INVOKED, lowered or not — that is what makes
  [[inspector-heads-tree]] possible and what stops a boundary this walker
  does not know about from throwing `:rf.error/fresco-sub-outside-render`
  half way down someone else's row. An un-lowered boundary node is left
  standing, which the head-grading row reads and the structural rows do
  not reach past.

  Mirrors the framework walker's two component shapes (plain fn, and the
  Form-2 fn-returning-a-fn `edn-inspector` uses). It does NOT mirror the
  Form-3 reagent-class arm: this panel's tree contains no class
  components, and a class head here would fall through to the `mapv` and
  be left un-expanded rather than silently mis-rendered."
  [lower node]
  (cond
    (vector? node)
    (let [n    (lower-head lower node)
          head (first n)]
      (if (and (fn? head) (not (rf.fresco.impl.codec/boundary-head? head)))
        (let [out (apply head (rest n))]
          (expand-tree* lower (if (fn? out) (apply out (rest n)) out)))
        ;; Metadata is carried across the rebuild, which the framework
        ;; walker does not do.
        (with-meta (mapv #(expand-tree* lower %) n) (meta n))))

    (seq? node) (map #(expand-tree* lower %) node)
    :else       node))

(defn- rendered-tree
  "The panel's tree, fully expanded — what the rows below walk."
  []
  (expand-tree* #{:table :inspector} (panel-tree)))

(defn- inspector-heads-tree
  "The panel's tree with the TABLE lowered and expanded but the payload
  inspector left as the boundary head the panel wrote — so the head
  grading row below can read a head that only exists once the table's
  `:row-extras` callback has run."
  []
  (expand-tree* #{:table} (panel-tree)))

;; The framework finder is applied to an ALREADY-EXPANDED
;; tree: its own `expand-tree` pass is then a no-op rebuild, because
;; `expand-tree*` left no fn in head position for it to invoke.
(def ^:private find-by-testid rf.test-helpers/find-by-testid)

(defn- hiccup-seq
  "Depth-first nodes of an already-expanded tree."
  [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- node-attrs
  "The attribute map of the rendered node with the given data-testid."
  [tree testid]
  (some-> (find-by-testid tree testid) second))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

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
  panel's per-path db-changed diff has real snapshots to
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

;; ---- (2) render contract ------------------------------------------------

(deftest op-row-renders-the-six-columns
  (testing "each op row carries Δt · stage · area badge ·
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
      (let [tree (rendered-tree)]
        (is (= "+0.0" (last (find-by-testid tree "rf-xray-trace-row-1-time")))
            "Δt column reads +0.0 relative to the epoch origin")
        (is (= "DISPATCH" (last (find-by-testid tree "rf-xray-trace-row-1-stage")))
            "stage column reads the Epoch DISPATCH step (dispatched op)")
        (is (= "EVENT" (last (find-by-testid tree "rf-xray-trace-row-1-badge")))
            "area badge column reads the neutral EVENT badge")
        (is (= "dispatched" (last (find-by-testid tree "rf-xray-trace-row-1-verb")))
            "what-happened column reads the verb")
        (is (= "0.4 ms" (last (find-by-testid tree "rf-xray-trace-row-2-duration")))
            "duration column reads the view's elapsed ms")
        (is (= "—" (last (find-by-testid tree "rf-xray-trace-row-1-duration")))
            "an untimed op renders an em-dash duration")))))

(deftest op-row-carries-colour-coded-stage-edge-and-attrs
  (testing "rows carry a 3px left edge keyed to the Epoch pipeline stage,
            plus data attrs for area and stage"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                               :dispatch-id 1})])])
      (focus! 1)
      (let [attrs (node-attrs (rendered-tree) "rf-xray-trace-row-1")]
        (is (= "event" (:data-rf-xray-area attrs)))
        (is (= "DISPATCH" (:data-rf-xray-stage attrs)))
        (is (re-find #"^3px solid " (str (get-in attrs [:style :border-left])))
            "the stage colour is a 3px left-border on the row")))))

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
      (let [tree (rendered-tree)]
        (is (= "error" (:data-rf-xray-severity (node-attrs tree "rf-xray-trace-row-2")))
            "the error row carries the severity attr")
        (is (= "ERROR" (last (find-by-testid tree "rf-xray-trace-row-2-badge")))
            "the error row's badge reads ERROR")
        (let [t (last (find-by-testid tree "rf-xray-trace-row-2-time"))]
          (is (and (string? t) (re-find #"^!" t))
              "the error row's Δt leads with ! for emphasis"))))))

;; ---- (2b) per-path db-changed diff ----------------------------------------
;;
;; The `:rf.event/db-changed` trace event carries only `:event` + `:frame`
;; — no per-path diff. The Trace panel derives per-path before→after
;; rows at render time from the focused epoch record's `:db-before` /
;; `:db-after` (derived PANEL-SIDE). The view renders one sub-row per
;; changed path beneath the
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
      (let [tree (rendered-tree)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-2-db-diff"))
            "the db-diff section renders beneath the db-changed row")
        (let [row (db-diff-row-by-suffix tree 2 ":counter")]
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
      (let [tree    (rendered-tree)
            added   (db-diff-row-by-suffix tree 2 ":flag")
            removed (db-diff-row-by-suffix tree 2 ":stale")]
        (is (= "added" (:data-op (second added))))
        (is (= "+" (last (find-by-testid
                           tree "rf-xray-trace-row-2-db-diff-row-:flag-glyph"))))
        (is (= "removed" (:data-op (second removed))))
        (is (= "-" (last (find-by-testid
                           tree "rf-xray-trace-row-2-db-diff-row-:stale-glyph"))))))))

(deftest db-changed-row-renders-nested-and-top-level-paths
  (testing "a nested-key diff surfaces as its own per-path row"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1
            {:counter 1 :user {:name "Ada" :age 30}}
            {:counter 2 :user {:name "Ada" :age 31}}
            [(mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (is (some? (db-diff-row-by-suffix (rendered-tree) 2 ":user_:age"))))))

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
      (let [tree (rendered-tree)]
        (is (some? (find-by-testid tree "rf-xray-trace-row-2"))
            "the db-changed row itself still renders")
        (is (nil? (find-by-testid tree "rf-xray-trace-row-2-db-diff"))
            "no diff sub-list when db-before == db-after")))))

;; ---- (3) empty states ---------------------------------------------------

(deftest empty-state-no-events-renders-for-empty-epoch
  (testing "a focused epoch carrying no trace events → :no-events"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (focus! 11)
      (is (some? (find-by-testid (rendered-tree) "rf-xray-trace-empty-no-events"))))))

(deftest empty-state-no-focus-renders
  (testing "with no focus + no history → :no-focus empty-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (some? (find-by-testid (rendered-tree) "rf-xray-trace-empty-no-focus"))))))

(deftest empty-state-epoch-evicted-renders
  (testing "when focus pins an :epoch-id absent from :epoch-history →
            the evicted-epoch placeholder"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history! [(mk-epoch 1 11 [])])
      (rf/dispatch-sync
        [:day8.re-frame2-xray.panels.trace-view-cljs-test/seed-evicted-focus])
      (is (some? (find-by-testid (rendered-tree) "rf-xray-trace-empty-epoch-evicted"))))))

(deftest empty-state-no-epoch-renders-for-pinned-bundle-that-settled-nothing
  (testing "a pinned bundle that settled NO epoch (a :dispatch-id beside a nil
            :epoch-id) is shape-identical to the cold-start unset focus, so
            reading :epoch-id alone would paint the HEAD epoch's trail under
            the operator's selection; the panel shows the cause-neutral
            :no-epoch state instead"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      ;; A real epoch with rows survives in the ring, so a head-fallback
      ;; answer would be visible as its rows.
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-trace {:id 1 :op-type :rf.event
                               :operation :rf.event/dispatched :dispatch-id 11})
                    (mk-trace {:id 2 :op-type :rf.fx :operation :rf.fx/handled})])])
      (rf/dispatch-sync
        [:day8.re-frame2-xray.panels.trace-view-cljs-test/seed-no-epoch-focus])
      (is (some? (find-by-testid (rendered-tree) "rf-xray-trace-empty-no-epoch"))))))

(deftest trace-feed-empty-states-reject-only-the-pinned-no-epoch-shape
  (testing "POSITIVE CONTROL — an UNSET focus over a non-empty ring still
            head-falls-back to the head epoch's rows, so the :no-epoch
            discriminator cannot pass by emptying the panel"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 11
                   [(mk-trace {:id 1 :op-type :rf.event
                               :operation :rf.event/dispatched :dispatch-id 11})])
         (mk-epoch 2 22
                   [(mk-trace {:id 2 :op-type :rf.event
                               :operation :rf.event/dispatched :dispatch-id 22})
                    (mk-trace {:id 3 :op-type :rf.fx :operation :rf.fx/handled})])])
      (is (= #{2 3} (set (map :id (:rows @(rf/subscribe [:rf.xray/trace-feed])))))))))

(deftest ungrouped-pin-lists-a-real-hydration-mismatch-and-its-hashes
  (testing "`verify-hydration!` runs outside any event, so its mismatch
            lands in the `:ungrouped` pseudo-bundle, which settles no
            epoch. Selecting that row must still list the bundle's events
            in the Trace tab, so the mismatch's hashes can be read there:
            in the row's reason, and as tags in the expanded raw trace."
    (setup-xray-frame!)
    (install/register-trace-collector!)
    (rf.ssr/verify-hydration! :rf/default "cafef00d" {:server-hash "deadbeef"})
    (trace-collector/refresh-trace-rings!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/settings-update :general :show-ungrouped? true])
      ;; The L2 row's own click, for the `:ungrouped` bundle's row.
      (rf/dispatch-sync [:rf.xray/focus-event :ungrouped nil])
      (let [feed      @(rf/subscribe [:rf.xray/trace-feed])
            mismatch  (some #(when (= :rf.ssr/hydration-mismatch (:operation %)) %)
                            (:rows feed))
            id        (:id mismatch)]
        (let [target (find-by-testid (rendered-tree) (str "rf-xray-trace-row-" id "-target"))
              text   (apply str (filter string? (hiccup-seq target)))]
          (is (and (re-find #"deadbeef" text) (re-find #"cafef00d" text))
              (str "the row's reason names both hashes. target: " (pr-str text))))
        (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand id])
        (let [inspector (some #(when (and (vector? %)
                                          (identical? ei/edn-inspector-view (first %))
                                          (= (str "rf-xray-trace-row-" id)
                                             (:mount-id (second %))))
                                 %)
                              (hiccup-seq (inspector-heads-tree)))
              tags      (:tags (:value (second inspector)))]
          (is (= {:server-hash "deadbeef" :client-hash "cafef00d"}
                 (select-keys tags [:server-hash :client-hash]))
              "the expanded row's raw trace carries both hash tags"))))))

;; ---- (4b) focused-event-bundle layer-3 sub ------------------------------
;;
;; The Trace panel reads the focused cascade record via the layer-3
;; composite `:rf.xray.trace/focused-event-bundle` rather than scanning the
;; full cascades vector inline in its render body. The sub composes
;; over `:rf.xray/event-bundles` + `:rf.xray/focus`; its result is the
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

(deftest focused-event-bundle-sub-rescopes-on-refocus
  (testing "refocusing changes the returned cascade
            record (reactivity wired through the composite signals)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (mk-cascade-trace 1 [:cart/add])
      (mk-cascade-trace 2 [:cart/remove])
      (focus! 1)
      (is (= 1 (:dispatch-id @(rf/subscribe [:rf.xray.trace/focused-event-bundle]))))
      (focus! 2)
      (is (= 2 (:dispatch-id @(rf/subscribe [:rf.xray.trace/focused-event-bundle])))))))

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
        (with-redefs [rf/dispatch-impl (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [handler (:on-click (second (find-by-testid (rendered-tree)
                                                           "rf-xray-trace-row-7")))]
            (when handler (handler))))
        (is (some #(= [:rf.xray/toggle-trace-row-expand 7] %) @dispatches)
            ":rf.xray/toggle-trace-row-expand fired with the row's :id")))))

(deftest toggle-trace-row-expand-event-mutates-set
  (testing ":rf.xray/toggle-trace-row-expand toggles row membership"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{11} @(rf/subscribe [:rf.xray/trace-expanded-row-ids])))
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 22])
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 11])
      (is (= #{22} @(rf/subscribe [:rf.xray/trace-expanded-row-ids]))))))

(deftest expanded-row-uses-per-row-panel-id-qualifier
  (testing "two simultaneously-expanded rows each
            mount the edn-inspector widget with a DISTINCT per-row
            panel-id qualifier (`:rf.xray.trace/row-<id>` rendered as
            `row-<id>` in the testid via `(name ...)`) so their
            expansion state can't collide. Combined with the per-row
            `:mount-id` this gives belt-and-braces
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
      (let [tree         (rendered-tree)
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
            "row 42's edn-inspector container carries the :rf.xray.trace/row-42 qualifier")))))

(deftest expanded-row-edn-inspector-carries-popup-affordance
  (testing "the expanded-row payload mount passes
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
      (is (nil? (find-by-testid (rendered-tree) "rf-xray-trace-row-51-payload"))
          "payload absent while the row is not expanded")
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 51])
      (let [tree     (rendered-tree)
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
        (with-redefs [rf/dispatch-impl (fn
                                     ([ev]      (swap! dispatches conj ev) nil)
                                     ([ev _o]   (swap! dispatches conj ev) nil))]
          (let [tree    (rendered-tree)
                node    (find-by-testid tree "rf-xray-trace-row-9-source-coord")
                handler (:on-click (second node))]
            (when handler
              (handler #js {:stopPropagation #(reset! stop-evt true)}))))
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.xray/open-in-editor (first ev))
                         (= {:source-coord "core.cljs:42"} (second ev))))
                  @dispatches)
            ":rf.xray/open-in-editor fired with the projected coord")
        (is @stop-evt "stopPropagation was called")))))

;; ---- (6) flat list, no hierarchy ---------------------------------------

(deftest flat-list-preserves-fire-order-across-stages
  (testing "ops from different pipeline stages render in ONE
            flat list, in fire order (oldest-first) — not regrouped into
            bands."
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
      ;; the rows render in seeded fire order — the reactive SUB op
      ;; lands BETWEEN the dispatch and the fx, not regrouped to the end
      (let [tree (rendered-tree)
            rows-container (find-by-testid tree "rf-xray-trace-rows")
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
            "rows are flat + in fire order, not regrouped into bands")))))

;; ---- (8) React-key stability across the feed ---------------------------

(defn- row-node-by-id
  "Walk the rendered tree and return the row container (a `:div`)
  whose data-testid is
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
  (testing "a row keys on its stable trace id, stamped into the ATTRS map
            rather than vector metadata (which `resizable_table_key_cljs_test`
            gates)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 11 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})])])
      (focus! 1)
      (is (= "t:11" (:key (second (row-node-by-id (rendered-tree) 11))))))))

;; ---- feed children reach React with keys -------------------------------

(defn- feed-children
  "The trace feed's two children, taken from the RAW, unexpanded panel tree.

  Navigated structurally rather than via `find-by-testid`, because
  `rf.test-helpers/expand-tree` rebuilds nested vectors with `mapv` and
  STRIPS reader metadata — it would erase the very shape under test and
  report a false nil for the meta-keyed sibling."
  [tree]
  (let [scroll (nth tree 3)
        feed   (nth scroll 2)]
    (is (= "rf-xray-trace-feed" (:data-testid (second feed)))
        "structural navigation landed on the feed container")
    (vec (drop 2 feed))))

;; ---- both feed keys live where BOTH substrates read -------------------

(deftest trace-feed-children-keys-live-in-the-attribute-map
  (testing "both feed keys live in the ATTRIBUTE MAP — the one place both
            substrates read, since Fresco's codec reads Clojure metadata
            nowhere — and the codec commits them"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch 1 1
                   [(mk-trace {:id 11 :op-type :rf.event :operation :rf.event/dispatched
                               :time 100 :dispatch-id 1})])])
      (focus! 1)
      (is (= ["ops-header" "rows"]
             (mapv #(.-key (rf.fresco.impl.codec/as-element %))
                   (feed-children (panel-tree))))
          "Fresco's codec commits both feed keys"))))

;; ---- the db-diff rows key through the ATTRIBUTE MAP ----------------------
;;
;; `db-diff-row` carries its `(pr-str path)` key in its own `:div` attrs. A
;; `with-meta` key on the returned vector would reach React under Reagent,
;; which reads meta AND props, and be a NO-OP under Fresco's codec, which
;; reads metadata nowhere — so the codec is the door asserted.

(defn- node-by-testid
  "The first node in an ALREADY-EXPANDED tree carrying `testid`, found
  without `rf.test-helpers/find-by-testid`'s own `expand-tree` rebuild."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- db-diff-row-nodes
  "The per-path diff rows under the db-changed row with id `parent-row-id`.
  `db-diff-rows` is CALLED by `op-row-extras`, so the rows exist as soon as
  the table has been expanded and need no inspector lowering."
  [tree parent-row-id]
  (vec (drop 2 (node-by-testid tree (str "rf-xray-trace-row-" parent-row-id
                                         "-db-diff")))))

(def ^:private diff-seed
  "Three changed paths in one db-changed row — modified, added, removed."
  {:before {:counter 1 :stale :x}
   :after  {:counter 2 :flag true}})

(deftest db-diff-row-keys-reach-the-renderer-through-attrs
  (testing "the codec commits each db-diff row's `(pr-str path)` key"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 (:before diff-seed) (:after diff-seed)
            [(mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      ;; `subvec` drops the subtree, which the codec would otherwise lower
      ;; eagerly; the key is read off the attribute map either way.
      (is (= ["[:counter]" "[:flag]" "[:stale]"]
             (sort (mapv #(.-key (rf.fresco.impl.codec/as-element (subvec % 0 2)))
                         (db-diff-row-nodes (rendered-tree) 2))))))))

;; ---- the three heads are the ones Fresco accepts ----------------------

(deftest panel-heads-are-the-ones-the-codec-accepts
  (testing "every hiccup head in the `Panel` body is one Fresco's codec
            accepts: a `reg-view` head would raise HD-016 with no error
            boundary above, presenting as a tab that never appears. Read over
            the UNLOWERED tree, so `lower-head` cannot launder one"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-history!
        [(mk-epoch-with-db 1 1 (:before diff-seed) (:after diff-seed)
            [(mk-trace {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :time 100 :dispatch-id 1})
             (mk-trace {:id 2 :op-type :rf.event :operation :rf.event/db-changed
                        :time 102 :dispatch-id 1})])])
      (focus! 1)
      (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand 1])
      ;; The panel's own body: the two table heads, unexpanded and unlowered.
      (let [kinds (frequencies (map rf.fresco.impl.codec/head-kind
                                    (map first (filter vector? (hiccup-seq (panel-tree))))))]
        (is (= 2 (get kinds :boundary 0))
            "both `resizable-table` sites are boundary heads")
        (is (zero? (get kinds :invalid 0))
            (str "every head the panel body writes is one Fresco accepts — "
                 kinds)))
      ;; And the payload inspector, which only exists once the table's
      ;; `:row-extras` callback has run.
      (let [heads (map first (filter vector? (hiccup-seq (inspector-heads-tree))))
            kinds (frequencies (map rf.fresco.impl.codec/head-kind heads))]
        (is (some #(identical? ei/edn-inspector-view %) heads)
            "the expanded row's inspector head is `edn-inspector-view`, the boundary")
        (is (zero? (get kinds :invalid 0))
            (str "no invalid head anywhere below the table — " kinds))))))

