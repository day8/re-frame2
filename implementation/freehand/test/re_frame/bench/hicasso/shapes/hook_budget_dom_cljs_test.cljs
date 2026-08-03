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

  | shape | boundaries | reads | shell hooks | shadow hooks |
  |---|---|---|---|---|
  | 1 ordinary | 7 | 15 | 14 | **1** |
  | 2 large template | **1** | **141** | **2** | 0 |
  | 3/4 feed | 301 | 603 | 602 | 0 |

  Row 2 is the one worth reading twice. One hundred and forty-one
  subscription reads — written inside a `for`, inside a plain helper
  called from inside that `for` — cost the same two hooks as one read,
  because `subscribe` closes over the read SET and nothing else, so the
  read count cannot reach the hook count. No per-read hook surface has a
  row here at all: 141 reads would be 141 hooks, which no rule about hook
  order permits inside a loop.

  **A shape that cost a third hook IN A SHELL would be a finding, not a
  cost to spend.** None does, and that is what HD-020(b)'s budget is
  about: `2 × boundaries`, `useContext` then `useSyncExternalStore`, in
  that order, with nothing interleaved.

  ## The fourth column, and why it is not a breach (rf2-digtt)

  Shape 1's page carries **one hook that is not in any shell**: the
  `useState` belonging to
  [[re-frame.bench.hicasso.front.controlled]]'s composition shadow,
  which stands in front of the shape's one controlled `<textarea>` (the
  comment draft in `shapes/ordinary`). It is the price of the IME
  composition carve-out the operator ruled in on 2026-08-03, and this
  file is where it is paid in public.

  The budget is **untouched**, and the rows below say so in a way a
  reading eye can check rather than take on trust:

  - the shell sequence — the hooks with the shadow's `useState` removed —
    is still the declared pair, once per boundary, in order;
  - the shadow's hooks are counted per shape, from a number this file
    declares, so a second one appearing anywhere reds;
  - **no hook of any kind is interleaved into a shell's pair**: every
    `useContext` is immediately followed by its `useSyncExternalStore`,
    which is the property that makes "not in a shell" a measurement
    rather than an assurance.

  The cost is per controlled TEXT FIELD, not per boundary and not per
  read — a page with no controlled input pays nothing, which rows 2 and
  3/4 are the witnesses for.

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

(def ^:private shadow-hook
  "The one hook a shell never asks for, and the one thing on this page
  that is not a boundary's: the composition shadow's state cell, one per
  controlled text element (rf2-digtt)."
  "useState")

(def ^:private shapes
  "The roster, as the probe sees it. Shape 4 is shape 3's page — the same
  mount — so it has no separate row: the budget is a property of the
  mounted page, and one commit later cannot add a hook.

  `:shadows` is the number of controlled TEXT elements the shape mounts,
  declared here rather than derived, so a shadow appearing where no
  controlled field exists reds the row that names it."
  [{:label   "1 — ordinary views"
    :seed    {:articles 2 :comments 5 :tags 2}
    :tree    [ordinary/screen {}]
    ;; The comment draft — `shapes/ordinary`'s one controlled `<textarea>`.
    :shadows 1}
   {:label   "2 — large templates"
    :seed    large-template/seed
    :tree    [large-template/page {}]
    :shadows 0}
   {:label   "3/4 — bulk + narrow"
    :seed    feed/seed
    :tree    [feed/page {}]
    :shadows 0}])

;; ---------------------------------------------------------------------------
;; The budget, shape by shape
;; ---------------------------------------------------------------------------

(deftest every-shape-costs-exactly-two-hooks-per-boundary
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (doseq [{:keys [label seed tree shadows]} shapes]
        (testing label
          (let [{:keys [hooks boundaries edges]} (mount-and-count seed tree)
                shell-hooks (vec (remove #{shadow-hook} hooks))]
            (is (pos? boundaries) (str label ": the mount built boundaries to count"))
            (is (= (* 2 boundaries) (count shell-hooks))
                (str label ": " boundaries " boundaries reading " edges
                     " subscriptions cost " (count shell-hooks) " shell hooks — the "
                     "budget is " (* 2 boundaries)))
            (is (= #{"useContext" "useSyncExternalStore"} (set shell-hooks))
                (str label ": and they are the two `shell-hook-ledger` declares"))
            (is (= (vec (mapcat (fn [_] ["useContext" "useSyncExternalStore"])
                                (range boundaries)))
                   shell-hooks)
                (str label ": in `shell-hook-ledger`'s order, once per boundary —
                     React runs a component's hooks to completion before it
                     starts the next, so the whole page's sequence is the
                     declared pair repeated"))
            (is (= (count rt/shell-hook-ledger) 2)
                "and the ledger the runtime declares is still two entries long")
            (testing "the composition shadow's hook, and only it (rf2-digtt)"
              (is (= shadows (count (filter #{shadow-hook} hooks)))
                  (str label ": one `useState` per controlled text element, "
                       shadows " declared — the price of the IME carve-out, paid "
                       "per FIELD rather than per boundary or per read"))
              (is (every? (fn [[a b]] (or (not= "useContext" a)
                                          (= "useSyncExternalStore" b)))
                          (partition 2 1 hooks))
                  (str label ": and never inside a shell — every `useContext` is
                       immediately followed by its `useSyncExternalStore` in the
                       RAW sequence, so nothing was interleaved into the pair.
                       This is what makes \"not in a shell\" a measurement")))
            (testing "no other hook of any kind"
              (doseq [banned ["useRef" "useMemo" "useCallback" "useEffect"
                              "useLayoutEffect" "useReducer" "useId"]]
                (is (not (contains? (set hooks) banned))
                    (str label ": " banned " must not appear anywhere on the page"))))))))))

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
