(ns re-frame.bench.hicasso.shapes.hook-budget-dom-cljs-test
  "**THE ≤2-HOOK BUDGET, HELD AT EVERY TIER-1 SHAPE** (HD-020(b);
  rf2-2rtt6.51).

  `arm1_hook_ledger_dom_cljs_test` proves the budget on a synthetic
  boundary at 1, 7 and 20 reads. That is the right instrument for the
  claim it makes, and it leaves one question open: **does the budget
  survive the shapes v0 is judged on?** A budget that held on a probe and
  broke on a real screen would be a budget nobody had checked.

  So this file re-takes it on the roster, with the same probe, counting
  at React's own dispatcher:

  | shape | boundaries | reads | hooks |
  |---|---|---|---|
  | 1 ordinary | 7 | 15 | 14 |
  | 2 large template | **1** | **141** | **2** |
  | 3/4 feed | 301 | 603 | 602 |

  Row 2 is the one worth reading twice. One hundred and forty-one
  subscription reads — written inside a `for`, inside a plain helper
  called from inside that `for` — cost the same two hooks as one read,
  because `subscribe` closes over the read SET and nothing else, so the
  read count cannot reach the hook count. No per-read hook surface has a
  row here at all: 141 reads would be 141 hooks, which no rule about hook
  order permits inside a loop.

  **A shape that cost a third hook would be a finding, not a cost to
  spend.** None did.

  Runtime: `-dom-cljs-test`. Under `:node-test`, and on any React build
  whose internals slot has moved, the claims degrade to a stated skip or
  to **unwitnessed** — never to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.feed :as feed]
            [re-frame.bench.hicasso.shapes.large-template :as large-template]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.bench.hicasso.shapes.ordinary :as ordinary]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::shape-hooks)

(defn- skip! [why]
  (is true (str "a dispatcher-level hook count needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the ≤2-hook budget is "
                 "UNWITNESSED on these shapes. A gate nobody has watched fire is "
                 "not evidence — fix " (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                 " rather than reading this as a pass.")))

(defn- mount-and-count
  "Mount `hiccup` on `seed` and answer the hook names React was asked for
  while it mounted, alongside the runtime's own boundary and edge counts —
  read BEFORE the release that would empty them."
  [seed hiccup]
  (lane/leave-act-environment!)
  (m/make-frame! frame-id seed)
  (m/reseed! frame-id seed)
  (let [container (mount/fresh-container!)
        handle    (volatile! nil)
        names     (probe/record!
                    (fn [] (vreset! handle (mount/root! container frame-id hiccup))))
        stats     (rt/stats)]
    (mount/release! @handle)
    {:hooks      names
     :boundaries (:boundaries stats)
     :edges      (:edges stats)}))

(def ^:private shapes
  "The roster, as the probe sees it. Shape 4 is shape 3's page — the same
  mount — so it has no separate row: the budget is a property of the
  mounted page, and one commit later cannot add a hook."
  [{:label "1 — ordinary views"
    :seed  {:articles 2 :comments 5 :tags 2}
    :tree  [ordinary/screen {}]}
   {:label "2 — large templates"
    :seed  large-template/seed
    :tree  [large-template/page {}]}
   {:label "3/4 — bulk + narrow"
    :seed  feed/seed
    :tree  [feed/page {}]}])

;; ---------------------------------------------------------------------------
;; The budget, shape by shape
;; ---------------------------------------------------------------------------

(deftest every-shape-costs-exactly-two-hooks-per-boundary
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (doseq [{:keys [label seed tree]} shapes]
        (testing label
          (let [{:keys [hooks boundaries edges]} (mount-and-count seed tree)]
            (is (pos? boundaries) (str label ": the mount built boundaries to count"))
            (is (= (* 2 boundaries) (count hooks))
                (str label ": " boundaries " boundaries reading " edges
                     " subscriptions cost " (count hooks) " hooks — the budget is "
                     (* 2 boundaries)))
            (is (= #{"useContext" "useSyncExternalStore"} (set hooks))
                (str label ": and they are the two `shell-hook-ledger` declares"))
            (is (= (vec (mapcat (fn [_] ["useContext" "useSyncExternalStore"])
                                (range boundaries)))
                   (vec hooks))
                (str label ": in `shell-hook-ledger`'s order, once per boundary —
                     React runs a component's hooks to completion before it
                     starts the next, so the whole page's sequence is the
                     declared pair repeated"))
            (is (= (count rt/shell-hook-ledger) 2)
                "and the ledger the runtime declares is still two entries long")
            (testing "no third hook of any kind"
              (doseq [banned ["useRef" "useState" "useMemo" "useCallback" "useEffect"
                              "useLayoutEffect" "useReducer" "useId"]]
                (is (not (contains? (set hooks) banned))
                    (str label ": " banned " must not appear in a boundary shell"))))))))))

;; ---------------------------------------------------------------------------
;; The row the budget exists for
;; ---------------------------------------------------------------------------

(deftest the-read-count-cannot-reach-the-hook-count
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (testing "shape 2 is one boundary making 141 subscription reads, every
               one of them inside a `for` and inside a plain helper called
               from inside it. It costs the same two hooks as a boundary with
               one read, because `subscribe` closes over the read SET and
               nothing else."
        (let [{:keys [hooks boundaries edges]}
              (mount-and-count large-template/seed [large-template/page {}])]
          (is (= 1 boundaries))
          (is (= 141 edges) "141 reads")
          (is (= 2 (count hooks))
              (str "and two hooks: " (pr-str hooks)))
          (is (= ["useContext" "useSyncExternalStore"] hooks)
              "in the declared order"))))))

(deftest the-budget-does-not-move-when-the-page-grows
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (testing "hooks per boundary is 2 at 50, 150 and 300 mounted card
               boundaries — the axis a bulk row grows along"
        (doseq [n [50 150 300]]
          (let [{:keys [hooks boundaries]}
                (mount-and-count {:articles n :tags feed/tag-count} [feed/page {}])]
            (is (= (inc n) boundaries))
            (is (= (* 2 (inc n)) (count hooks))
                (str "B = " n ": " (count hooks) " hooks over " boundaries
                     " boundaries"))))))))
