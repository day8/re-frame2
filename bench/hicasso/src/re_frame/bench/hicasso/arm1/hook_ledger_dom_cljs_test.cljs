(ns re-frame.bench.hicasso.arm1.hook-ledger-dom-cljs-test
  "THE ≤2-HOOK BUDGET, COUNTED AT REACT'S DISPATCHER (rf2-2rtt6.9).

  HD-020(b): \"the ≤2 budget is fully consumed by the subscription/epoch
  hook and the frame-context hook; boundary shells use callback refs,
  never `useRef` — a third hook in the shell is a budget breach.\"

  Three claims, and the third is the one that matters:

  1. a boundary's shell calls exactly two hooks;
  2. neither is `useRef` and neither is `useState`;
  3. **the count does not move with the read count.** One read, seven
     reads, twenty reads — the census archetype and the stress rung — all
     cost two. That is the whole argument for why the scalar per-read
     spine is a comparator and not a product: N reads = N hooks cannot
     satisfy this budget on any rung.

  The comparator is measured in the same run, on the same page, by the
  same probe, so the contrast is a reading rather than a recollection.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.hook-probe :as rf.bench.hicasso.arm1.hook-probe]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.front.dogfood :as rf.bench.hicasso.front.dogfood]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.test-support :as rf.test-support]
            [uix.core :refer [$ defui]])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; `:ambient-frame nil` is load-bearing, not tidiness. The fixture's
     ;; default leaves a dynamic-var frame stamp in scope, and the
     ;; carried-invariant chain resolves that tier BEFORE React context —
     ;; so the comparator's ambient `use-subscribe` would read the
     ;; ambient frame's app-db while `use-current-frame` reported the
     ;; provider's, and a parity miss would look like a rendering
     ;; difference. Caught by the frame probe below, which is why the
     ;; probe stays.
     :ambient-frame nil
     :init-fn (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::arm1-hooks)

;; ---------------------------------------------------------------------------
;; The boundaries under the probe — one shape, three read counts
;; ---------------------------------------------------------------------------

(defview reader
  "One Hicasso boundary reading `n` subscriptions through the collector.
  The reads sit in a `for`, which no hook-shaped surface can do, and
  which is exactly why the count below is interesting."
  [{:keys [n]}]
  [:ul.reads
   (for [i (range n)]
     [:li.read {:key i} (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/todo i]))])])

(defui uix-one [{:keys [i]}]
  ($ :li.read (str (rf.adapter.uix/use-subscribe [:dogfood/todo i]))))

(defui uix-three [{:keys [i]}]
  ;; Three reads, three hooks, spelled out — a loop is illegal here, and
  ;; that illegality is the finding rather than an inconvenience.
  (let [a (rf.adapter.uix/use-subscribe [:dogfood/todo i])
        b (rf.adapter.uix/use-subscribe [:dogfood/todo (inc i)])
        c (rf.adapter.uix/use-subscribe [:dogfood/todo (+ i 2)])]
    ($ :li.read (str a b c))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skip! [why] (is true (str "a dispatcher-level hook count needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the ≤2-hook budget is "
                 "UNWITNESSED on this build. A gate nobody has watched fire is not "
                 "evidence — fix " (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                 " rather than reading this as a pass.")))

(defn- mount-and-count
  "Mount `hiccup` through the arm's own root and answer the hook names
  React was asked for while it mounted. The root, the page and the frame
  are identical for every caller, so the only thing that varies between
  readings is the component."
  [hiccup]
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.front.dogfood/make-frame! frame-id 32)
  (rf.bench.hicasso.front.dogfood/reseed! frame-id 32)
  (let [container (rf.bench.hicasso.arm1.mount/fresh-container!)
        handle    (volatile! nil)
        names     (rf.bench.hicasso.arm1.hook-probe/record!
                    (fn [] (vreset! handle (rf.bench.hicasso.arm1.mount/root! container frame-id hiccup))))]
    (rf.bench.hicasso.arm1.mount/release! @handle)
    names))

(defn- hicasso-hooks
  "The hook names for ONE Hicasso boundary reading `n` subscriptions."
  [n]
  (mount-and-count [reader {:n n}]))

;; ---------------------------------------------------------------------------
;; The claims
;; ---------------------------------------------------------------------------

(deftest the-shell-calls-exactly-two-hooks
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.bench.hicasso.arm1.hook-probe/install!)
      (unwitnessed!)
      (testing "one boundary, one read: the frame hook and the
               subscription/epoch hook, and nothing else"
        (let [names (hicasso-hooks 1)]
          (is (= 2 (count names)) (str "hooks React was asked for: " (pr-str names)))
          (is (= ["useContext" "useSyncExternalStore"] names)
              "in the order shell-hook-ledger declares")
          (is (= (count rf.bench.hicasso.arm1.runtime/shell-hook-ledger) (count names))
              "and the declared ledger is the measured one"))))))

(deftest the-count-does-not-move-with-the-read-count
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.bench.hicasso.arm1.hook-probe/install!)
      (unwitnessed!)
      (testing "1, 7 and 20 reads — the first rung, the census archetype
               and the stress rung — all cost two hooks"
        (doseq [n [1 7 20]]
          (let [names (hicasso-hooks n)]
            (is (= 2 (count names))
                (str n " reads still cost two hooks: " (pr-str names)))))))))

(deftest no-use-ref-and-no-use-state-in-the-shell
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.bench.hicasso.arm1.hook-probe/install!)
      (unwitnessed!)
      (let [names (set (hicasso-hooks 7))]
        (is (not (contains? names "useRef")) "HD-020(b) bans useRef in the shell")
        (is (not (contains? names "useState"))
            "and this arm holds no per-instance render-phase state at all")
        (is (not (contains? names "useMemo")))
        (is (not (contains? names "useCallback")))))))

(deftest the-scalar-comparator-pays-per-read-and-is-measured-saying-so
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.bench.hicasso.arm1.hook-probe/install!)
      (unwitnessed!)
      (testing "the raw UIx spine's per-read hooks, counted by the same
               probe on the same page — a comparator read rather than
               recalled. No bar row is published from this: it is a hook
               count, not a clock or a heap figure."
        (let [host-1 (rf.bench.hicasso.arm1.runtime/mint-view! "uix-host-1" (fn [_] ($ uix-one {:i 0})))
              host-3 (rf.bench.hicasso.arm1.runtime/mint-view! "uix-host-3" (fn [_] ($ uix-three {:i 0})))
              base   (count (hicasso-hooks 1))
              one    (count (mount-and-count [host-1 {}]))
              three  (count (mount-and-count [host-3 {}]))]
          ;; The host boundary contributes the shell's two; everything above
          ;; that is the comparator's own per-read cost.
          (is (> one base)
              (str "one UIx read already costs more than the whole Hicasso "
                   "shell (" one " vs " base ")"))
          (is (> three one)
              (str "and three reads cost more again — the count moves WITH the "
                   "read count, which is the property the product budget "
                   "forbids (" three " vs " one ")"))
          (is (= 2 base)
              "while the Hicasso shell stays at two on every rung"))))))
