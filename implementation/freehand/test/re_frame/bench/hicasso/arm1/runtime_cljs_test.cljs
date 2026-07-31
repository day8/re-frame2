(ns re-frame.bench.hicasso.arm1.runtime-cljs-test
  "ARM 1's RUNTIME, proved without a browser (rf2-2rtt6.9).

  Everything this arm does that is not React's is answerable here: the
  read tiers, the commit path, the index wiring, the generation fence,
  and the standing zero-leaked-subscription-ref-counts assertion. The
  `-dom` suites then prove that React drives *this* seam — they do not
  re-prove what the seam does.

  The adapter is UIx's, not `plain-atom`'s, and that is load-bearing:
  plain-atom has no reactivity layer at all (\"no caching, no
  listeners\"), so a subscription under it never notifies and every
  commit assertion below would pass vacuously by never firing. The React
  spine's derived values coalesce their source watches through an epoch
  that closes *inside* the synchronous dispatch, which is both what makes
  the watch land in the caller's turn (HD-019's door) and what lets these
  tests read the result on the next line."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-runtime)

(defn- seeded!
  ([] (seeded! 3))
  ([n] (rt/reset-runtime!) (dogfood/make-frame! frame-id n) frame-id))

(defn- render
  "Run a body through the shell's own fence, exactly as [[rt/shell]]
  does — minus React, which contributes nothing to what is asserted."
  ([body-fn] (render body-fn {}))
  ([body-fn props] (rt/render-body frame-id body-fn props)))

(defn- reads-of [out] (aget out 1))
(defn- element-of [out] (aget out 0))
(defn- collector? [out] (aget out 2))
(defn- grouped? [out] (aget out 3))

(defn- key-of [query] [frame-id query])

;; ---------------------------------------------------------------------------
;; The hook ledger and the retained inventory (HD-020(b))
;; ---------------------------------------------------------------------------

(deftest the-shell-declares-exactly-two-hooks
  (testing "the ≤2 budget is fully consumed by the subscription/epoch hook
           and the frame-context hook, and there is no room left"
    (is (= 2 (count rt/shell-hook-ledger)))
    (is (= [:use-context/frame :use-sync-external-store/subscription-epoch]
           rt/shell-hook-ledger))))

(deftest the-shell-retains-no-use-ref-and-no-use-state
  (testing "HD-020(b) bans useRef in the shell; this arm bans per-instance
           render-phase state outright, because that is what makes two
           hooks reachable rather than three"
    (let [inv    (rt/retained-inventory)
          tokens (into #{} (map :token) (:per-boundary inv))]
      (is (not (contains? tokens :react/use-ref)))
      (is (not (contains? tokens :react/use-state)))
      (is (contains? tokens :react/use-sync-external-store))
      (is (contains? tokens :react/use-context))
      (is (= #{:use-ref :use-state :view-cell :candidate-ledger}
             (into #{} (map :token) (:absent inv)))
          "the absences are enumerated, so a regression that adds one
           has to delete a line rather than merely appear"))))

;; ---------------------------------------------------------------------------
;; The two read tiers (HD-002)
;; ---------------------------------------------------------------------------

(deftest a-read-outside-a-render-is-a-loud-error
  (seeded!)
  (testing "`sub` outside a boundary is an error, never a silent read of
           whichever frame happened to be ambient"
    (is (thrown-with-msg? js/Error #"outside a boundary render"
          (rt/sub [:dogfood/remaining])))
    (is (thrown-with-msg? js/Error #"outside a boundary render"
          (rt/use-subs {:r [:dogfood/remaining]})))))

(deftest the-collector-records-the-reads-the-body-actually-made
  (seeded! 3)
  (let [out (render (fn [_]
                      (let [ids (rt/sub [:dogfood/visible-ids])]
                        [:ul (when (seq ids) [:li (str (rt/sub [:dogfood/todo (first ids)]))])])))]
    (is (collector? out))
    (is (not (grouped? out)))
    (is (= #{(key-of [:dogfood/visible-ids]) (key-of [:dogfood/todo 0])}
           (reads-of out)))))

(deftest a-conditional-read-is-an-edge-the-collector-simply-does-not-have
  (seeded! 3)
  (testing "law 4 at the authoring surface: the branch that is not taken
           contributes no edge, which is the collector's whole claim"
    (let [taken     (render (fn [{:keys [editable?]}]
                              [:li (when editable? (rt/sub [:dogfood/draft 1]))])
                            {:editable? true})
          not-taken (render (fn [{:keys [editable?]}]
                              [:li (when editable? (rt/sub [:dogfood/draft 1]))])
                            {:editable? false})]
      (is (= #{(key-of [:dogfood/draft 1])} (reads-of taken)))
      (is (= #{} (reads-of not-taken))))))

(deftest grouped-declares-its-edges-whatever-the-body-then-does
  (seeded! 3)
  (testing "the same branch under the product default: the declaration is
           the edge set, so the untaken branch still costs its edge —
           the honest price of a fixed read site"
    (let [out (render (fn [_]
                        (let [{:keys [todo draft]}
                              (rt/use-subs {:todo  [:dogfood/todo 1]
                                            :draft [:dogfood/draft 1]})]
                          [:li (:title todo) (when (:done? todo) draft)])))]
      (is (grouped? out))
      (is (not (collector? out)))
      (is (= #{(key-of [:dogfood/todo 1]) (key-of [:dogfood/draft 1])}
             (reads-of out))))))

(deftest grouped-returns-the-snapshot-the-body-destructures
  (seeded! 3)
  (let [captured (volatile! nil)]
    (render (fn [_] (vreset! captured (rt/use-subs {:todo      [:dogfood/todo 1]
                                                    :remaining [:dogfood/remaining]}))
              [:li]))
    (is (= {:id 1 :title "todo 1" :done? false} (:todo @captured)))
    (is (= 3 (:remaining @captured)))))

;; ---------------------------------------------------------------------------
;; The commit path: write -> dirty keys -> index -> dirty boundaries
;; ---------------------------------------------------------------------------

(defn- mounted!
  "A boundary at the seam React occupies: render its body, commit the
  reads, and hand back `{:notified :release! :reads}`."
  [body-fn]
  (let [out    (render body-fn)
        reads  (reads-of out)
        hits   (volatile! 0)
        release (rt/commit-boundary! reads (fn [] (vswap! hits inc)))]
    {:reads reads :hits hits :release! release}))

(deftest a-narrow-write-notifies-exactly-the-boundary-that-read-it
  (seeded! 3)
  (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
        b (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))]))]
    (rt/dispatch! frame-id [:dogfood/toggle 0])
    (is (= 1 @(:hits a)) "the reader of the moved subscription re-runs")
    (is (= 0 @(:hits b)) "and nothing else does")
    ((:release! a))
    ((:release! b))))

(deftest two-boundaries-sharing-a-subscription-both-run
  (seeded! 3)
  (let [a (mounted! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))
        b (mounted! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))]
    (rt/dispatch! frame-id [:dogfood/toggle 0])
    (is (= 1 @(:hits a)))
    (is (= 1 @(:hits b)))
    ((:release! a))
    ((:release! b))))

(deftest an-unmounted-boundary-is-never-notified-again
  (seeded! 3)
  (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
    ((:release! a))
    (rt/dispatch! frame-id [:dogfood/toggle 0])
    (is (= 0 @(:hits a)))))

(deftest a-write-that-moves-nothing-notifies-nobody
  (seeded! 3)
  (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
        g (rt/generation)]
    ;; `:dogfood/commit` on a row with no draft is a no-op by construction.
    (rt/dispatch! frame-id [:dogfood/commit 0])
    (is (= 0 @(:hits a)))
    (is (= g (rt/generation)) "and does not move the generation")
    ((:release! a))))

(deftest one-commit-window-is-one-flush-however-many-subscriptions-moved
  (seeded! 3)
  (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                             (str (rt/sub [:dogfood/remaining]))]))
        g (rt/generation)]
    (rt/with-commit (fn []
                      (rf/with-frame frame-id (rf/dispatch-sync [:dogfood/toggle 0]))
                      (rf/with-frame frame-id (rf/dispatch-sync [:dogfood/toggle 1]))))
    (is (= 1 @(:hits a)) "one notification, not one per moved subscription")
    (is (= (inc g) (rt/generation)) "and one generation, not two")
    ((:release! a))))

;; ---------------------------------------------------------------------------
;; The edge-set diff — a replacement, never a ledger
;; ---------------------------------------------------------------------------

(deftest a-changed-read-set-replaces-the-edges-rather-than-accumulating-them
  (seeded! 3)
  (let [wide   (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                  (str (rt/sub [:dogfood/todo 1]))]))
        _      ((:release! wide))
        narrow (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
        b      (first (:live (idx/snapshot)))]
    (is (= 1 (count (idx/edges-of (idx/snapshot) b)))
        "the boundary holds exactly the edges its latest commit installed")
    (is (empty? (idx/readers-of (idx/snapshot) (key-of [:dogfood/todo 1])))
        "and the dropped key has no reader left behind")
    ((:release! narrow))))

;; ---------------------------------------------------------------------------
;; The generation fence
;; ---------------------------------------------------------------------------

(deftest a-commit-landing-inside-a-body-re-runs-that-body
  (seeded! 3)
  (let [runs (volatile! 0)
        seen (volatile! nil)
        out  (render (fn [_]
                       (vswap! runs inc)
                       ;; Stage the stale read: the first run writes, so its
                       ;; own reads straddle two commits.
                       (when (= 1 @runs)
                         (rt/dispatch! frame-id [:dogfood/toggle 0]))
                       (vreset! seen (rt/sub [:dogfood/done? 0]))
                       [:li (str @seen)]))]
    (is (= 2 @runs) "the fence re-ran the body against the newer commit")
    (is (true? @seen) "and the winning run read the committed value")
    (is (= #{(key-of [:dogfood/done? 0])} (reads-of out))
        "the index sees the winning render's reads, not the abandoned one's")))

(deftest a-body-that-writes-on-every-run-fails-loudly
  (seeded! 3)
  (testing "the fence is a ceiling, not a budget — an unfenceable body is
           a write loop and says so"
    (is (thrown-with-msg? js/Error #"generation-fence-exhausted"
          (render (fn [_]
                    (rt/dispatch! frame-id [:dogfood/toggle 0])
                    [:li (str (rt/sub [:dogfood/done? 0]))]))))))

(deftest a-body-that-reads-without-writing-never-moves-the-generation
  (seeded! 3)
  (let [g (rt/generation)]
    (render (fn [_] [:li (str (rt/sub [:dogfood/remaining]))]))
    (is (= g (rt/generation)))))

;; ---------------------------------------------------------------------------
;; Boundaries, heads, and the ABI
;; ---------------------------------------------------------------------------

(deftest a-minted-view-is-a-legal-hiccup-head
  (let [v (rt/mint-view! "test/probe" (fn [_] [:li]))]
    (is (fn? v))
    (is (= "test/probe" (.-displayName v)))
    (is (true? (unchecked-get v "hicassoBoundary"))
        "the codec's own boundary marker, so a view is a head by
         construction and the stable-head cache has nothing to do")))

;; ---------------------------------------------------------------------------
;; Residue — the standing zero-leaked-refcounts assertion
;; ---------------------------------------------------------------------------

(deftest a-released-boundary-leaves-no-edge-and-no-reference
  (async done
    (seeded! 3)
    (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                               (str (rt/sub [:dogfood/remaining]))]))]
      (is (= 1 (:boundaries (rt/stats))))
      (is (= 2 (:edges (rt/stats))))
      (is (= 2 (:cell-refs (rt/stats))))
      ((:release! a))
      (is (= 0 (:boundaries (rt/stats))))
      (is (= 0 (:edges (rt/stats))))
      (is (= 0 (:cell-refs (rt/stats))))
      ;; The cells and the cached closure are reaped one macrotask later —
      ;; the grace that lets a keyed reorder reuse a reaction instead of
      ;; rebuilding it.
      (js/setTimeout (fn []
                       (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :closures 0}
                              (rt/residue))
                           "nothing survives the macrotask horizon")
                       (done))
                     4))))

(deftest a-render-that-never-commits-leaves-nothing-behind
  (async done
    (seeded! 3)
    ;; The abandoned-render class: a body runs, builds its cells, and its
    ;; commit never happens. Acquisition is commit-owned, so there is
    ;; nothing to unwind — the reaper is what releases the render's `+1`.
    (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                     (str (rt/sub [:dogfood/todo 1]))]))
    (is (= 0 (:boundaries (rt/stats))) "no boundary was ever registered")
    (is (= 0 (:cell-refs (rt/stats))) "and no reference was ever taken")
    (js/setTimeout (fn []
                     (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :closures 0}
                            (rt/residue)))
                     (done))
                   4)))
