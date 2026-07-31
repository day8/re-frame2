(ns re-frame.bench.hicasso.arm1.runtime-cljs-test
  "ARM 1's RUNTIME, proved without a browser (rf2-2rtt6.9).

  Everything this arm does that is not React's is answerable here: the
  read surfaces, the commit path, the index wiring, the generation fence,
  HD-002's ownership state machine and allowed edge-diff operation, and
  the standing zero-leaked-subscription-ref-counts assertion. The `-dom`
  suites then prove that React drives *this* seam — they do not re-prove
  what the seam does.

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
     ;; The map shape, because the residue claims are `async` — a reaper
     ;; horizon is not observable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-runtime)

(defn- seeded!
  ([] (seeded! 3))
  ([n] (rt/reset-runtime!) (dogfood/make-frame! frame-id n) frame-id))

(defn- render
  "Run a body through the shell's own fence, exactly as `rt/shell` does —
  minus React, which contributes nothing to what is asserted. Returns the
  read-set entry; the element is discarded because no assertion here is
  about markup."
  ([body-fn] (render body-fn {}))
  ([body-fn props] (rt/render-body frame-id body-fn props) (rt/last-reads)))

(defn- reads-of [entry] (rt/reads-of entry))

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
          "the absences are enumerated, so a regression that adds one has
           to delete a line rather than merely appear"))))

;; ---------------------------------------------------------------------------
;; The two read surfaces (HD-002; the collector is the one being made to work)
;; ---------------------------------------------------------------------------

(defn- mounted!
  "A boundary at the seam React occupies: render its body, commit the
  reads, and hand back `{:hits :release!}`."
  [body-fn]
  (let [entry   (render body-fn)
        hits    (volatile! 0)
        release (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
    {:entry entry :hits hits :release! release}))

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
  (let [entry (render (fn [_]
                        (let [ids (rt/sub [:dogfood/visible-ids])]
                          [:ul (when (seq ids)
                                 [:li (str (rt/sub [:dogfood/todo (first ids)]))])])))]
    (is (:collector? (rt/last-tiers)))
    (is (not (:grouped? (rt/last-tiers))))
    (is (= #{(key-of [:dogfood/visible-ids]) (key-of [:dogfood/todo 0])}
           (reads-of entry)))))

(deftest the-collector-reads-inside-a-for-and-inside-an-inlined-helper
  (seeded! 3)
  (testing "the surface the operator ruled the only acceptable one: a read
           in a loop and a read donated by a plain helper, both landing on
           the enclosing boundary's edge set"
    (letfn [(label [id] [:span (str (rt/sub [:dogfood/todo id]))])]
      (let [entry (render (fn [_] [:ul (for [id (rt/sub [:dogfood/visible-ids])]
                                         [:li (label id)])]))]
        (is (= #{(key-of [:dogfood/visible-ids])
                 (key-of [:dogfood/todo 0])
                 (key-of [:dogfood/todo 1])
                 (key-of [:dogfood/todo 2])}
               (reads-of entry)))))))

(deftest a-lazy-for-registers-its-edges-and-its-readers-re-run
  (seeded! 3)
  (testing "**A Surface B property, and the one a lazy host language can lose
           silently.** `for` returns a LAZY SEQUENCE, so every `(sub …)`
           inside it runs when something walks the seq — not when the body
           returns. A collector closed at the body's return would collect
           ZERO edges for every row, register no dependency, and look
           perfectly correct on the first render (the values ARE right once
           realised) while never updating again. Arm 2 hit exactly that in
           Chromium and flagged it across the tournament.

           This arm is safe **by construction rather than by care**: the
           collector window closes around `codec/as-element`, and the codec
           is eager everywhere it walks — `expand-seq` drives a seq to
           exhaustion, `realize-children` folds one into a vector, and a
           seq at a prop position goes through `clj->js`. So a lazy read is
           forced inside the window by the same pass that turns hiccup into
           elements. This test is what keeps that true: it fails the moment
           the codec call moves outside `run-once`."
    (let [b (mounted! (fn [_]
                        [:ul (for [id (rt/sub [:dogfood/visible-ids])]
                               [:li {:key id} (str (rt/sub [:dogfood/todo id]))])]))]
      (is (= #{(key-of [:dogfood/visible-ids])
               (key-of [:dogfood/todo 0])
               (key-of [:dogfood/todo 1])
               (key-of [:dogfood/todo 2])}
             (reads-of (:entry b)))
          "every row query the lazy seq produced is an edge")
      (is (= 4 (:edges (rt/stats)))
          "and the commit installed all four, not just the eager one")
      (rt/dispatch! frame-id [:dogfood/toggle 1])
      (is (= 1 @(:hits b))
          "a write to a row query the `for` produced re-runs the boundary —
           the half a first render cannot tell you")
      ((:release! b)))))

(deftest a-lazy-seq-returned-as-the-body-root-registers-its-edges-too
  (seeded! 3)
  (testing "the same property at the root position, where there is no
           enclosing vector to force the walk"
    (let [entry (render (fn [_] (for [id (rt/sub [:dogfood/visible-ids])]
                                  [:li {:key id} (str (rt/sub [:dogfood/todo id]))])))]
      (is (= 4 (count (reads-of entry)))))))

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
  (testing "the control: the declaration is the edge set, so the untaken
           branch still costs its edge"
    (let [entry (render (fn [_]
                          (let [{:keys [todo draft]}
                                (rt/use-subs {:todo  [:dogfood/todo 1]
                                              :draft [:dogfood/draft 1]})]
                            [:li (:title todo) (when (:done? todo) draft)])))]
      (is (:grouped? (rt/last-tiers)))
      (is (not (:collector? (rt/last-tiers))))
      (is (= #{(key-of [:dogfood/todo 1]) (key-of [:dogfood/draft 1])}
             (reads-of entry))))))

(deftest grouped-returns-the-snapshot-the-body-destructures
  (seeded! 3)
  (let [captured (volatile! nil)]
    (render (fn [_] (vreset! captured (rt/use-subs {:todo      [:dogfood/todo 1]
                                                    :remaining [:dogfood/remaining]}))
              [:li]))
    (is (= {:id 1 :title "todo 1" :done? false} (:todo @captured)))
    (is (= 3 (:remaining @captured)))))

;; ---------------------------------------------------------------------------
;; HD-002 clause (a) — the ownership state machine
;; ---------------------------------------------------------------------------

(deftest a-render-mutates-neither-the-index-nor-a-reference
  (seeded! 3)
  (testing "the invariant the whole state machine reduces to: no
           render-phase code mutates the index or a subscription
           ref-count, so an abandoned render needs no cleanup because it
           never did anything that would need cleaning"
    (let [before (rt/stats)]
      (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                       (str (rt/sub [:dogfood/remaining]))]))
      (let [after (rt/stats)]
        (is (= (:boundaries before) (:boundaries after)) "no boundary registered")
        (is (= (:edges before) (:edges after)) "no edge added")
        (is (= (:cells before) (:cells after)) "no cell built")
        (is (= (:cell-refs before) (:cell-refs after)) "no reference taken")))))

(deftest a-re-render-before-the-commit-destroys-the-previous-candidate-by-overwrite
  (seeded! 3)
  (testing "PENDING -> RENDERING, which is the abandoned render: there is
           one scratch and never two, so the second run's reads replace
           the first's rather than joining them"
    (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                     (str (rt/sub [:dogfood/todo 1]))]))
    (let [entry (render (fn [_] [:li (str (rt/sub [:dogfood/todo 2]))]))]
      (is (= #{(key-of [:dogfood/todo 2])} (reads-of entry))))))

(deftest a-body-that-throws-leaves-nothing-behind
  (seeded! 3)
  (let [before (rt/stats)]
    (is (thrown? js/Error
          (render (fn [_] (rt/sub [:dogfood/todo 0]) (throw (js/Error. "body"))))))
    (is (= (select-keys before [:boundaries :edges :cell-refs])
           (select-keys (rt/stats) [:boundaries :edges :cell-refs])))
    (testing "and the next render starts from a clean scratch rather than
             concatenating the throwing run's reads"
      (let [entry (render (fn [_] [:li (str (rt/sub [:dogfood/remaining]))]))]
        (is (= #{(key-of [:dogfood/remaining])} (reads-of entry)))))))

;; ---------------------------------------------------------------------------
;; HD-002 clause (b) — the allowed edge-diff operation, and its cost law
;; ---------------------------------------------------------------------------

(deftest an-unchanged-read-set-is-detected-without-building-anything
  (seeded! 3)
  (testing "the same reads twice resolve to the SAME entry, so React's
           `subscribe` identity does not move, so React does not
           re-subscribe and the commit does no work at all — which is the
           survival metric's flat slope seen from the mechanism's side"
    (let [body  (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                         (str (rt/sub [:dogfood/todo 1]))
                         (str (rt/sub [:dogfood/remaining]))])
          a     (render body)
          b     (render body)]
      (is (identical? a b) "one entry, not two")
      (is (identical? (.-subscribe a) (.-subscribe b))
          "and therefore one `subscribe` identity")
      (is (= 1 (:entries (rt/stats)))))))

(deftest a-changed-read-set-takes-a-different-subscribe-identity
  (seeded! 3)
  (testing "which is what makes React's own subscribe/cleanup pair perform
           the edge-set replacement — the whole of the allowed operation"
    (let [wide   (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                  (str (rt/sub [:dogfood/todo 1]))]))
          narrow (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
      (is (not (identical? wide narrow)))
      (is (not (identical? (.-subscribe wide) (.-subscribe narrow)))))))

(deftest a-boundary-holds-exactly-the-edges-its-latest-commit-installed
  (seeded! 3)
  (let [wide    (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                 (str (rt/sub [:dogfood/todo 1]))]))
        release (rt/commit-boundary! wide (fn []))]
    (is (= 2 (:edges (rt/stats))))
    (release)
    (let [narrow  (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
          release (rt/commit-boundary! narrow (fn []))
          b       (first (:live (idx/snapshot)))]
      (is (= 1 (count (idx/edges-of (idx/snapshot) b))))
      (is (empty? (idx/readers-of (idx/snapshot) (key-of [:dogfood/todo 1])))
          "and the dropped key has no reader left behind")
      (release))))

(deftest reading-one-key-twice-is-one-edge
  (seeded! 3)
  (testing "the buffer is a sequence and the index edge is set-valued; the
           diff collapses them"
    (let [entry   (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                   (str (rt/sub [:dogfood/todo 0]))]))
          release (rt/commit-boundary! entry (fn []))]
      (is (= 1 (count (reads-of entry))))
      (is (= 1 (:edges (rt/stats))))
      (release))))

;; ---------------------------------------------------------------------------
;; The commit path: write -> dirty keys -> index -> dirty boundaries
;; ---------------------------------------------------------------------------

(deftest a-narrow-write-notifies-exactly-the-boundary-that-read-it
  (seeded! 3)
  (let [a (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
        b (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))]))]
    (is (= #{(key-of [:dogfood/todo 0])} (reads-of (:entry a))))
    (is (= #{(key-of [:dogfood/todo 1])} (reads-of (:entry b))))
    (is (= 1 (count (idx/readers-of (idx/snapshot) (key-of [:dogfood/todo 0])))))
    (is (= 1 (count (idx/readers-of (idx/snapshot) (key-of [:dogfood/todo 1])))))
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

(deftest a-warm-read-performs-no-new-attach-or-release
  (seeded! 3)
  (testing "validation.md's standing assertion, stated as a reference
           count that does not move across a re-render"
    (let [a      (mounted! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
          before (:cell-refs (rt/stats))]
      (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
      (is (= before (:cell-refs (rt/stats))))
      ((:release! a)))))

;; ---------------------------------------------------------------------------
;; The generation fence
;; ---------------------------------------------------------------------------

(deftest a-commit-landing-inside-a-body-re-runs-that-body
  (seeded! 3)
  ;; The boundary must already hold the key for a mid-body write to be
  ;; observable at all — a key nothing holds has no watch to fire.
  (let [runs       (volatile! 0)
        first-read (volatile! nil)
        seen       (volatile! nil)
        warm       (mounted! (fn [_] [:li (str (rt/sub [:dogfood/done? 0]))]))
        entry      (render (fn [_]
                          (vswap! runs inc)
                          (vreset! first-read (rt/sub [:dogfood/done? 0]))
                          (when (= 1 @runs)
                            (rt/dispatch! frame-id [:dogfood/toggle 0]))
                          (vreset! seen (rt/sub [:dogfood/done? 0]))
                          [:li (str @seen)]))]
    (is (= 2 @runs) "the fence re-ran the body against the newer commit")
    (is (true? @seen) "and the winning run read the committed value")
    (is (true? @first-read) "both of the winning run's reads are on one commit")
    (is (= #{(key-of [:dogfood/done? 0])} (reads-of entry))
        "the index sees the winning render's reads, not the abandoned one's")
    ((:release! warm))))

(deftest a-body-that-writes-on-every-run-fails-loudly
  (seeded! 3)
  (testing "the fence is a ceiling, not a budget — an unfenceable body is
           a write loop and says so"
    (let [warm (mounted! (fn [_] [:li (str (rt/sub [:dogfood/done? 0]))]))]
      (is (thrown-with-msg? js/Error #"generation-fence-exhausted"
            (render (fn [_]
                      (rt/sub [:dogfood/done? 0])
                      (rt/dispatch! frame-id [:dogfood/toggle 0])
                      [:li (str (rt/sub [:dogfood/done? 0]))]))))
      ((:release! warm)))))

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
      ;; The cells and the cached entry are reaped one macrotask later —
      ;; the grace that lets a keyed reorder reuse a reaction instead of
      ;; rebuilding it.
      (js/setTimeout (fn []
                       (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                              (rt/residue))
                           "nothing survives the macrotask horizon")
                       (done))
                     8))))

(deftest a-render-that-never-commits-leaves-nothing-behind
  (async done
    (seeded! 3)
    ;; The abandoned-render class. Acquisition is commit-owned, so there is
    ;; nothing to unwind — and the cached entry a discarded render minted is
    ;; a cache, dropped at the macrotask horizon rather than undone.
    (render (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                     (str (rt/sub [:dogfood/todo 1]))]))
    (is (= 0 (:boundaries (rt/stats))) "no boundary was ever registered")
    (is (= 0 (:cell-refs (rt/stats))) "and no reference was ever taken")
    (is (= 0 (:cells (rt/stats))) "and no cell was ever built")
    (js/setTimeout (fn []
                     (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                            (rt/residue)))
                     (done))
                   8)))
