(ns re-frame.bench.fresco.arm1.runtime-cljs-test
  "ARM 1's RUNTIME, proved without a browser: the read surfaces, HD-002's
  ownership state machine and allowed edge-diff operation, the commit
  window, the generation fence's ceiling, and the zero-residue claim. The
  `-dom` suites prove React drives this seam; the six index laws are
  `arm1/cell_table_laws_cljs_test`.

  The adapter is UIx's, not `plain-atom`'s, and that is load-bearing:
  plain-atom has no reactivity layer, so a subscription under it never
  notifies and every commit assertion below would pass vacuously."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The map shape, because the residue claim is `async` — a reaper
     ;; horizon is not observable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::arm1-runtime)

(defn- seeded! []
  (rf.bench.fresco.arm1.runtime/reset-runtime!)
  (rf.bench.fresco.front.dogfood/make-frame! frame-id 3))

(defn- render
  "Run a body through the shell's own fence, minus React, and answer the
  read-set entry."
  [body-fn]
  (rf.bench.fresco.arm1.runtime/render-body frame-id body-fn {})
  (rf.bench.fresco.arm1.runtime/last-reads))

(defn- reads-of [entry] (rf.bench.fresco.arm1.runtime/reads-of entry))

(defn- key-of [query] [frame-id query])

(defn- mounted!
  "A boundary at the seam React occupies: render its body and commit its
  reads. Answers `{:entry :hits}`."
  [body-fn]
  (let [entry (render body-fn)
        hits  (volatile! 0)]
    (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))
    {:entry entry :hits hits}))

;; ---------------------------------------------------------------------------
;; The two read surfaces (HD-002)
;; ---------------------------------------------------------------------------

(deftest a-read-outside-a-render-is-a-loud-error
  (seeded!)
  (is (thrown-with-msg? js/Error #"outside a boundary render"
        (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))))

(deftest a-lazy-for-registers-its-edges-and-its-readers-re-run
  (seeded!)
  (testing "`for` is lazy, so its reads run when the codec walks it — inside
           the collector window. Moving the codec call out of `run-once`
           fails this"
    (let [b (mounted! (fn [_]
                        [:ul (for [id (rf.bench.fresco.arm1.runtime/sub [:dogfood/visible-ids])]
                               [:li {:key id} (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo id]))])]))]
      (is (= #{(key-of [:dogfood/visible-ids])
               (key-of [:dogfood/todo 0])
               (key-of [:dogfood/todo 1])
               (key-of [:dogfood/todo 2])}
             (reads-of (:entry b))))
      (is (= 4 (:edges (rf.bench.fresco.arm1.runtime/stats))) "and the commit installed all four")
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 1])
      (is (= 1 @(:hits b))
          "a write to a row query the `for` produced re-runs the boundary —
           the half a first render cannot show"))))

(deftest a-lazy-seq-returned-as-the-body-root-registers-its-edges-too
  (seeded!)
  (testing "at the root position, where no enclosing vector forces the walk"
    (let [entry (render (fn [_] (for [id (rf.bench.fresco.arm1.runtime/sub [:dogfood/visible-ids])]
                                  [:li {:key id} (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo id]))])))]
      (is (= 4 (count (reads-of entry)))))))

(deftest every-read-that-escapes-the-render-is-loud-rather-than-a-missing-edge
  (seeded!)
  (testing "the render frame is cleared in a `finally`, so a read deferred
           past the render throws instead of silently recording no edge"
    (let [d (volatile! nil)
          s (volatile! nil)
          h (volatile! nil)]
      (render (fn [_]
                (vreset! d (delay (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining])))
                (vreset! s (map (fn [id] (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo id])) (range 3)))
                (vreset! h (fn [] (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining])))
                [:li]))
      (is (thrown-with-msg? js/Error #"outside a boundary render" @@d) "an author-held delay")
      (is (thrown-with-msg? js/Error #"outside a boundary render" (doall @s)) "a stashed lazy seq")
      (is (thrown-with-msg? js/Error #"outside a boundary render" (@h)) "a handler, called"))))

(deftest grouped-declares-its-edges-whatever-the-body-then-does
  (seeded!)
  (testing "`use-subs` returns the snapshot the body destructures, and its
           edges are its declaration — held whether or not the body uses them"
    (let [snapshot (volatile! nil)
          entry    (render (fn [_]
                             (vreset! snapshot (rf.bench.fresco.arm1.runtime/use-subs {:todo      [:dogfood/todo 1]
                                                                                       :remaining [:dogfood/remaining]}))
                             [:li]))]
      (is (= {:todo {:id 1 :title "todo 1" :done? false} :remaining 3} @snapshot))
      (is (= #{(key-of [:dogfood/todo 1]) (key-of [:dogfood/remaining])} (reads-of entry))))))

;; ---------------------------------------------------------------------------
;; HD-002 clause (a) — the ownership state machine
;; ---------------------------------------------------------------------------
;;
;; Its invariant — no render-phase code mutates the cell table or a reference
;; — is `a-render-that-never-commits-leaves-nothing-behind` below and
;; `cold_read_cljs_test`'s `a-cold-read-leaves-the-world-as-it-found-it`.

(deftest a-re-render-before-the-commit-destroys-the-previous-candidate-by-overwrite
  (seeded!)
  (testing "one scratch, so an abandoned render's reads are replaced rather
           than joined"
    (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                     (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))
    (is (= #{(key-of [:dogfood/todo 2])}
           (reads-of (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 2]))])))))))

(deftest a-body-that-throws-leaves-nothing-behind
  (seeded!)
  (let [before (rf.bench.fresco.arm1.runtime/residue)]
    (is (thrown? js/Error
          (render (fn [_] (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]) (throw (js/Error. "body"))))))
    (is (= before (rf.bench.fresco.arm1.runtime/residue)))
    (testing "and the next render starts from a clean scratch rather than
             concatenating the throwing run's reads"
      (is (= #{(key-of [:dogfood/remaining])}
             (reads-of (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))))))))

;; ---------------------------------------------------------------------------
;; HD-002 clause (b) — the allowed edge-diff operation, and its cost law
;; ---------------------------------------------------------------------------

(deftest an-unchanged-read-set-is-detected-without-building-anything
  (seeded!)
  (testing "the same reads resolve to the SAME entry, so React's `subscribe`
           identity does not move and React does not re-subscribe"
    (let [body (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                        (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))
                        (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))])
          a    (render body)]
      (is (identical? a (render body)))
      (is (= 1 (:entries (rf.bench.fresco.arm1.runtime/stats)))))))

(deftest a-changed-read-set-takes-a-different-subscribe-identity
  (seeded!)
  (testing "which is what makes React's own subscribe/cleanup pair perform
           the edge-set replacement"
    (let [wide   (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                                  (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))
          narrow (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))]
      (is (not (identical? (.-subscribe wide) (.-subscribe narrow)))))))

(deftest a-boundary-holds-exactly-the-edges-its-latest-commit-installed
  (seeded!)
  (let [release (rf.bench.fresco.arm1.runtime/commit-boundary!
                  (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                                   (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))
                  (fn []))]
    (release)
    (rf.bench.fresco.arm1.runtime/commit-boundary!
      (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))
      (fn []))
    (is (= [#{(key-of [:dogfood/todo 0])} []]
           [(rf.bench.fresco.arm1.runtime/boundary-reads
              (first (rf.bench.fresco.arm1.runtime/cell-readers (key-of [:dogfood/todo 0]))))
            (rf.bench.fresco.arm1.runtime/cell-readers (key-of [:dogfood/todo 1]))])
        "the narrowed read set, and no reader left behind on the dropped key")))

(deftest the-wired-path-replaces-wholesale-and-never-takes-a-difference
  (seeded!)
  (testing "a changed read set arrives as a FRESH registration, so the
           previous cleanup drops every reference — the unchanged key's
           too — and every `:edges-changed` adds its whole read set and
           drops nothing"
    (let [seen (atom [])]
      (rf.bench.fresco.arm1.runtime/set-evidence-sink!
        (fn [e] (when (= :edges-changed (:event e)) (swap! seen conj e))))
      (try
        (let [stop (rf.bench.fresco.arm1.runtime/commit-boundary!
                     (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                                      (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))
                     (fn []))]
          (stop)
          (is (= 0 (:cell-refs (rf.bench.fresco.arm1.runtime/stats)))
              "there is no cheap route for `n-1 of n unchanged`")
          (rf.bench.fresco.arm1.runtime/commit-boundary!
            (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))
            (fn [])))
        (finally (rf.bench.fresco.arm1.runtime/set-evidence-sink! nil)))
      (is (= [{:added #{(key-of [:dogfood/todo 0]) (key-of [:dogfood/todo 1])} :dropped #{}}
              {:added #{(key-of [:dogfood/todo 0])} :dropped #{}}]
             (mapv #(select-keys % [:added :dropped]) @seen))))))

(deftest reading-one-key-twice-is-one-edge
  (seeded!)
  (testing "the scratch is a sequence and the read set a set, so a key read
           twice takes one membership rather than two"
    (let [entry (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                                 (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))]
      (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))
      (is (= 1 (count (reads-of entry))))
      (is (= 1 (:edges (rf.bench.fresco.arm1.runtime/stats)))))))

(deftest the-bucket-scan-does-not-grow-with-the-number-of-boundaries
  (seeded!)
  (testing "64 rows reading a page-wide key FIRST, then a per-row key.
           Bucketing on the first key would stack all 64 entries in one
           bucket — a quadratic mount — so the scan stays one deep"
    (dotimes [i 64]
      (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))
                       (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo i]))])))
    (is (= {:buckets 64 :max-bucket 1} (rf.bench.fresco.arm1.runtime/entry-buckets)))))

;; ---------------------------------------------------------------------------
;; The commit window
;; ---------------------------------------------------------------------------
;;
;; Who a write notifies is laws 1, 3, 5 and 6 in `cell_table_laws_cljs_test`.

(deftest one-commit-window-is-one-flush-however-many-subscriptions-moved
  (seeded!)
  (let [a (mounted! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                             (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))
        g (rf.bench.fresco.arm1.runtime/generation)]
    (rf.bench.fresco.arm1.runtime/with-commit (fn []
                                                (rf/with-frame frame-id (rf/dispatch-sync [:dogfood/toggle 0]))
                                                (rf/with-frame frame-id (rf/dispatch-sync [:dogfood/toggle 1]))))
    (is (= 1 @(:hits a)) "one notification, not one per moved subscription")
    (is (= (inc g) (rf.bench.fresco.arm1.runtime/generation)) "and one generation, not two")))

(deftest a-warm-read-performs-no-new-attach-or-release
  (seeded!)
  (testing "validation.md's standing assertion: a re-render of a mounted
           read set moves no reference count"
    (mounted! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))
    (let [before (:cell-refs (rf.bench.fresco.arm1.runtime/stats))]
      (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))
      (is (= before (:cell-refs (rf.bench.fresco.arm1.runtime/stats)))))))

;; ---------------------------------------------------------------------------
;; The generation fence
;; ---------------------------------------------------------------------------
;;
;; The re-run itself is `hydrate_cljs_test`'s
;; `a-fenced-re-run-is-two-body-runs-because-two-bodies-ran` and
;; `staged_read_tear_cljs_test`'s
;; `the-fence-sees-a-mid-body-move-of-a-key-nothing-holds`.

(deftest a-body-that-writes-on-every-run-fails-loudly
  (seeded!)
  (testing "the fence is a ceiling, not a budget — a body that writes on
           every run is a write loop and says so"
    (is (thrown-with-msg? js/Error #"generation-fence-exhausted"
          (render (fn [_]
                    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
                    [:li]))))))

;; ---------------------------------------------------------------------------
;; Heads
;; ---------------------------------------------------------------------------

(deftest a-minted-view-is-a-legal-hiccup-head
  (let [v (rf.bench.fresco.arm1.runtime/mint-view! "test/probe" (fn [_] [:li]))]
    (is (rf.bench.fresco.front.codec/boundary-head? v))
    (is (some? (unchecked-get v "frescoMemo"))
        "and it carries its memo wrapper (HD-006), attached to the head
         rather than returned in its place")))

;; ---------------------------------------------------------------------------
;; Residue
;; ---------------------------------------------------------------------------

(deftest a-render-that-never-commits-leaves-nothing-behind
  (async done
    (seeded!)
    ;; Acquisition is commit-owned, so an abandoned render has nothing to
    ;; undo; the entry it minted is a cache, dropped at the reap horizon.
    (render (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                     (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 1}
           (rf.bench.fresco.arm1.runtime/residue)))
    (js/setTimeout (fn []
                     (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                            (rf.bench.fresco.arm1.runtime/residue))
                         "nothing survives the macrotask horizon")
                     (done))
                   8)))
