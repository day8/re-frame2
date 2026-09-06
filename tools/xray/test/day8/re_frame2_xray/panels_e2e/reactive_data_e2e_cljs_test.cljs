(ns day8.re-frame2-xray.panels-e2e.reactive-data-e2e-cljs-test
  "Real-substrate e2e coverage for the Views panel's reactive-data
  projection (rf2-vxgfnd.22, Finding B · spec/021 §3).

  ## The gap this closes

  Every prior test of `reactive-panel-subs/project-record` fed HAND-BUILT
  epoch records (`{:sub-runs [...] :renders [...]}`) — see
  `panels/reactive-panel-subs-cljs-test` (pure projection) and
  `panels-e2e/evicted-epoch-all-panels-cljs-test` (directly-seeded
  `:epoch-history`). None drove a REAL cascade whose `:sub-runs` rows were
  projected by the substrate's `capture.cljc/sub-run-row`. So a key-rename
  in `sub-run-row` (e.g. `:sub-id` -> `:subid`, or dropping `:recomputed?`)
  reddened NO e2e — the rf2-wyvf2 class (subs-ran pinned to zero while the
  panel showed empty) re-emerging one level up.

  ## What this test drives

  The SAME real-pipeline harness the machine / trace e2e use
  (`e2e/with-host-and-xray-frames` — NO `:rf.xray/set-epoch-history-for-test`
  injection seam):

    - install the canonical counter host (`:counter/value` sub +
      `:counter/inc` event);
    - dispatch the REAL `[:counter/inc]` host event;
    - force `:counter/value` to recompute (plain-atom recomputes on every
      deref) so the substrate emits a real `:rf.sub/run` that back-fills
      (rf2-wi900) into the just-settled `:counter/inc` epoch;
    - mirror the framework epoch ring into Xray's `:epoch-history`;
    - read `:rf.xray/reactive-data` and assert on CONTENT — the real sub
      that ran (`:counter/value`), captured through `sub-run-row` into the
      focused epoch's `:sub-runs` and projected to `:subs-ran`.

  ## Mutation-proof (rf2-vxgfnd.22 fix-PR protocol)

  Renaming any `sub-run-row` output key in
  `implementation/epoch/src/re_frame/epoch/capture.cljc` — e.g.
  `:sub-id` -> `:subid`, or `:query-v` -> `:queryv` — reddens
  `reactive-data-subs-ran-names-real-sub`: the projected `:subs-ran` row
  then lacks that key, so the content match fails. (Verified by mutating
  the key, running this ns, then reverting.)

  ## Scope note — view-rows / renders

  The Node CLJS test process runs the plain-atom substrate, so views
  cannot mount (no React) — there are no real `:rf.view/rendered` ops, so
  `:view-rows` / `:views-rendered` (the `render-row` path) can't be
  exercised headlessly here. Real-render coverage of the `render-row`
  keys belongs to a mounted-adapter (browser) harness, outside this slim
  node gate; tracked as a follow-up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- inc-and-recompute!
  "Drive a REAL `[:counter/inc]` cascade, then force `:counter/value` to
  recompute so the substrate emits a real `:rf.sub/run`.

  The plain-atom substrate recomputes a derived value on every deref (no
  eager reactive flush), so a post-settle `@(subscribe ...)` runs the sub
  body and emits the `:rf.sub/run` the framework back-fills (rf2-wi900)
  into the just-settled `:counter/inc` epoch's `:sub-runs`. We then mirror
  the framework epoch ring into Xray's `:epoch-history` (the same sync the
  production epoch-collector does) so the panel subs see the back-filled
  record."
  []
  (rf/dispatch-sync [:counter/inc] {:frame e2e/default-host-frame})
  @(rf/subscribe [:counter/value] {:frame e2e/default-host-frame})
  (e2e/sync-xray-trace-mirror!)
  (e2e/sync-xray-epoch-history!))

(deftest reactive-data-subs-ran-names-real-sub
  (testing "rf2-vxgfnd.22 — a REAL `[:counter/inc]` cascade recomputes the
            `:counter/value` sub; the reactive-data projection's
            `:subs-ran` names that real sub, captured through the
            substrate's `sub-run-row` (NO injection seam). Reddens on a
            `sub-run-row` key rename in capture.cljc."
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (inc-and-recompute!)
        (let [d        (e2e/sub-xray [:rf.xray/reactive-data])
              subs-ran (:subs-ran d)]
          (is (seq subs-ran)
              (str ":subs-ran is EMPTY — no real :rf.sub/run captured into "
                   "the focused epoch (the rf2-wyvf2 class). reactive-data: "
                   (pr-str (select-keys d [:subs-ran :counts :has-event-bundle?]))))
          (let [row (some #(when (= :counter/value (:sub-id %)) %) subs-ran)]
            (is (some? row)
                (str ":subs-ran does not name the real :counter/value sub — "
                     "sub-run-row projection broke (key rename?). subs-ran: "
                     (pr-str subs-ran)))
            (is (true? (:recomputed? row))
                "the real sub-run row carries :recomputed? true (sub-run-row)")
            (is (= [:counter/value] (:query-v row))
                (str "the row carries the real :query-v (a :query-v key "
                     "rename in sub-run-row reddens here). row: " (pr-str row))))
          (is (pos? (-> d :counts :subs-ran))
              ":counts :subs-ran tallies the real run-set"))))))

(deftest reactive-data-composite-shape-holds-through-real-pipeline
  (testing "rf2-vxgfnd.22 — the composite `:rf.xray/reactive-data` resolves
            its documented shape over a REAL cascade: the flow-graph slots
            (`:level-1-subs` / `:view-rows` / `:sub-readers`) are present,
            `:subs-ran` is the run-set, and the `:subs-skipped` memo-hit
            slice is present (rf2-ty5r5o — empty here, no memo hit driven)."
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (inc-and-recompute!)
        (let [d (e2e/sub-xray [:rf.xray/reactive-data])]
          (is (contains? d :subs-ran))
          (is (contains? d :level-1-subs))
          (is (contains? d :view-rows))
          (is (contains? d :sub-readers))
          (is (contains? d :subs-skipped)
              "rf2-ty5r5o — the :subs-skipped memo-hit slice is a real slot")
          (is (= [] (:subs-skipped d))
              "no memo hit driven → empty (a run alone is not a skip)")
          ;; :counter/value reads app-db directly → a Level-1 sub.
          (is (some #(= :counter/value (:sub-id %)) (:level-1-subs d))
              (str ":counter/value should partition to Level 1 (reads "
                   "app-db directly). level-1-subs: "
                   (pr-str (:level-1-subs d)))))))))

;; ---- causal memo-hit → :subs-skipped (rf2-ty5r5o) ----------------------

(defn- install-ab-host!
  "A tiny host whose LAYER-2 sub (`:ab/derived-a`) composes a LAYER-1 sub
  (`:ab/read-a`) — the shape that lets a real cascade drive BOTH categories
  the panel must keep distinct in ONE epoch: bumping the UNRELATED `:b`
  path forces `:ab/read-a` to RECOMPUTE (db changed) but its VALUE is
  unchanged (`:a` held), so its layer-2 reader `:ab/derived-a` sees a
  value-equal input and MEMO-HITS — the substrate emits a canonical
  `:rf.sub/skip` for it.

  Dispatches `[:ab/init]` at install (like the counter fixture's
  `install-and-init!`) so a cascade EXISTS when Xray installs — that seeds
  Xray's target frame to this host frame (`default-target-frame` is nil /
  UNSELECTED, so an install with no cascade would leave the epoch-history
  mirror empty)."
  []
  (rf/reg-event :ab/init   (fn [_ _] {:db {:a 0 :b 0}}))
  (rf/reg-event :ab/bump-b (fn [{:keys [db]} _] {:db (update db :b inc)}))
  (rf/reg-sub :ab/read-a (fn [db _] (:a db)))
  (rf/reg-sub :ab/derived-a {:inputs [[:ab/read-a]]} (fn [[a] _] (* 10 a)))
  (rf/dispatch-sync [:ab/init])
  nil)

(deftest reactive-data-subs-skipped-names-real-memo-hit
  (testing "rf2-ty5r5o — a REAL memo hit (the layer-2 `:ab/derived-a` whose
            layer-1 input `:ab/read-a` recomputed to the SAME value after an
            unrelated `:b` bump) emits a canonical `:rf.sub/skip` that
            back-fills into the epoch and reaches `:rf.xray/reactive-data`'s
            `:subs-skipped` — kept DISTINCT from `:subs-ran` (which carries
            the value-changed? false recompute of `:ab/read-a`). No injection
            seam; drives the substrate's real emit-skip path."
    (e2e/with-host-and-xray-frames
      {:install-host install-ab-host!}
      (fn []
        (rf/with-frame e2e/default-host-frame
          (let [r (rf/subscribe [:ab/derived-a])]
            @r                                  ; prime the memo (first-run)
            (rf/dispatch-sync [:ab/bump-b] {:frame e2e/default-host-frame})
            @r                                  ; read-a recomputes (value 0, unchanged)
            @r))                                ; derived-a memo-hits → :rf.sub/skip
        (e2e/sync-xray-trace-mirror!)
        (e2e/sync-xray-epoch-history!)
        (let [d       (e2e/sub-xray [:rf.xray/reactive-data])
              skipped (:subs-skipped d)
              ran     (:subs-ran d)
              ran-ids (into #{} (map :sub-id) ran)
              skip-ids (into #{} (map :sub-id) skipped)]
          (is (contains? skip-ids :ab/derived-a)
              (str ":subs-skipped must name the real memo-hit sub "
                   ":ab/derived-a — the :rf.sub/skip evidence the panel "
                   "drops today. reactive-data: "
                   (pr-str (select-keys d [:subs-ran :subs-skipped :counts]))))
          (let [row (some #(when (= :ab/derived-a (:sub-id %)) %) skipped)]
            (is (= :input-value-equal (:reason row))
                "the skip row carries the canonical :input-value-equal reason")
            (is (= [[:ab/read-a]] (:input-paths-unchanged row))
                "the layer-2 skip names its upstream :ab/read-a query-vector"))
          ;; distinctness: the memo-hit sub is NOT in the run-set, and the
          ;; recompute that produced the same value stays in the run-set.
          (is (not (contains? ran-ids :ab/derived-a))
              ":ab/derived-a skipped — it must NOT appear in :subs-ran")
          (is (not (contains? skip-ids :ab/read-a))
              ":ab/read-a recomputed (it fired) — it must NOT appear in :subs-skipped")
          (is (pos? (-> d :counts :subs-skipped))
              ":counts :subs-skipped tallies the real memo-hit set"))))))
