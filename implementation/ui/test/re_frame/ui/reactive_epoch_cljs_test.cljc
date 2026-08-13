(ns re-frame.ui.reactive-epoch-cljs-test
  "rf2-vxgfnd.10 (S2d) — epoch coalescing + `flush!` scope over the ViewCell
  notification scheduler (03 §3 invariant 6 'one notification per cell per
  render batch — the boundary is the host checkpoint, not drain quiescence and
  not epoch close'; Spec 006 §Render-batch finalization). Headless fixtures on
  the REAL
  observation port + plain-atom sub-cache — the value-movement `on-change`
  watch channel is a reactive-host surface, so these fixtures drive the
  notification seam DIRECTLY (`mark-dirty!` with an epoch tag) exactly the
  way the reactive spine's watch fan-out would, then force with `flush!`.

  Because they poke the seam directly, these fixtures say nothing about ROUTER
  boundaries — a batch here is bounded by the explicit `flush!` that stands in
  for the host checkpoint. The real-router proof that several completed drains
  share one window is `render-batch-host-checkpoint-cljs-test`
  (rf2-vxgfnd.166); do not read a drain boundary into the rows below.

  The rows:

    - BATCH COALESCING — N event/frame EPOCHS committed in ONE
      run-to-completion drain advance a cell's revision ONCE, coalescing
      into ONE render batch (the corrected sixth invariant: the render
      boundary is the host checkpoint, NOT epoch close and NOT drain
      completion; epoch ids are cause evidence, never render triggers). Work
      marked after a batch has closed notifies again — later work stays
      observable, but NO render count follows from the epoch count (replaces
      the retired false gate `N epochs ⇒ N renders`);
    - flush! SCOPE (the Q51 ruling) — the frame arity `flush-frame!` flushes
      only cells observing that frame; a scoped `flush-scope!` leaves
      out-of-scope cells pending; the global `flush-pending!` /
      `ui.test/flush!` drains every root;
    - NO EPOCH WORK LEAKS ACROSS ROOTS — a per-frame / per-scope flush never
      advances a cell outside its scope;
    - REENTRANCY-SAFE BY CONSTRUCTION — a notify-triggered re-entrant flush
      finds the registry already drained and cannot double-advance (the
      safety the dev-tier `:rf.error/flush-in-open-epoch` signal, whose
      typed throw + Spec 009 catalogue row ride the S2f 009 batch, sits atop);
    - DISCARD ON DISCONNECT/TEARDOWN/RESET — an unmounted cell leaves the
      registry without a stale flush; the test-support reset also clears every
      detached cell's dirty/evidence window, and a racing mark is coherently
      pre-reset-cleared or post-reset-enrolled;
    - THE SLICE-SCOPED PROBE MEMO — `sub-read` threads one memo per slice, so
      sibling probes compute shared derivation parents once.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node)
  AND `clojure -M:test` (JVM), so the scheduler is graft-checked on both."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [async deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.reactive           :as reactive]
            [re-frame.ui.test               :as uit]))

(use-fixtures :each
  ;; `:async?` declares async-CAPABILITY; `make-reset-runtime-fixture` picks
  ;; the shape per host (rf2-e8ea) — the map on CLJS, the fn-form on the JVM,
  ;; where `clojure.test` would silently swallow a map fixture. So this is a
  ;; plain option, not the reader-conditional `cond->` dance it used to be.
  (test-support/make-reset-runtime-fixture
   {:adapter plain-atom/adapter :async? true})
  #?(:clj
     (fn [f]
       (reactive/reset-scheduler!)
       (try (f) (finally (reactive/reset-scheduler!))))
     :cljs
     {:before reactive/reset-scheduler!
      :after  reactive/reset-scheduler!}))

(def ^:private fid :rf/default)

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- ref-count [q] (:ref-count (entry q)))
(defn- seed! [id db] (frame/replace-app-db! id db))
(defn- tk [id q] [:sub id q])

(defn- rc!
  "Render (probe) `queries` under frame `id`, then commit — the cell ends
  observing `id`."
  [cell id queries]
  (let [[_ capture] (rf/with-frame id
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:epoch/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

;; ===========================================================================
;; Batch coalescing — N epochs before ONE checkpoint → ONE render batch
;; ===========================================================================

(deftest n-deltas-in-one-epoch-notify-once
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (testing "N sub deltas in ONE epoch (same epoch tag) coalesce to one advance"
      (dotimes [_ 5] (reactive/mark-dirty! cell 7))
      (is (reactive/dirty? cell) "the cell is pending")
      (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 5 deltas")
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell)) "N deltas → ONE revision advance")
      (is (= 1 @hits) "N deltas → ONE notification")
      (is (not (reactive/dirty? cell)))
      (is (= 0 (reactive/pending-cell-count))))
    (testing "a fresh epoch after the flush notifies again (not swallowed)"
      (reactive/mark-dirty! cell 8)
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell)))
      (is (= 2 @hits)))))

(deftest coalescing-is-independent-of-the-epoch-tag-while-pending
  ;; while already pending, ANY further delta folds in — the coalescing
  ;; gate is the pending flag; the epoch tag is evidence, not a second key.
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/mark-dirty! cell 1)
    (reactive/mark-dirty! cell 2)          ;; different tag, still pending
    (reactive/mark-dirty! cell 2)
    (reactive/flush-pending!)
    (is (= 1 (reactive/revision cell)))
    (is (= 1 @hits) "still one notification across the flush boundary")))

(deftest n-epochs-in-one-drain-coalesce-to-one-render-batch
  ;; The corrected sixth invariant, replacing the retired false gate
  ;; "N epochs ⇒ N renders". A run-to-completion drain may settle SEVERAL
  ;; queued events, each committing its OWN epoch record, before the host
  ;; regains control. Every one of those epochs folds into ONE render batch —
  ;; the render boundary is the HOST CHECKPOINT (here the explicit `flush!`),
  ;; not epoch close and not drain completion.
  ;; Epoch ids ride the invalidation as CAUSE EVIDENCE only: coalescing keys
  ;; on the pending flag, never on the epoch tag. Render SEPARATION follows
  ;; HOST CHECKPOINTS, never the epoch count and never the drain count.
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (testing "8 distinct epochs committed in ONE drain ⇒ ONE render batch"
      ;; one drain: 8 queued events each commit their own epoch, each firing
      ;; on-change with a DISTINCT epoch tag, all BEFORE the flush that rides
      ;; the host checkpoint (the CLJS microtask / the headless explicit flush)
      (doseq [e (range 1 9)] (reactive/mark-dirty! cell e))
      (is (reactive/dirty? cell) "the cell is pending after the drain")
      (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 8 epochs")
      (is (= 1 (reactive/pending-epoch cell))
          "the pending notification stays anchored to the FIRST epoch's
           evidence; later queued epochs fold in without re-anchoring")
      (reactive/flush-pending!)              ;; the drain-quiescence read batch
      (is (= 1 (reactive/revision cell)) "8 epochs in one drain ⇒ ONE revision advance")
      (is (= 1 @hits) "⇒ ONE notification — one render batch, not eight")
      (is (nil? (reactive/pending-epoch cell)) "the evidence tag clears with the flush"))
    (testing "a mark AFTER the batch closed renders separately — later work stays observable"
      ;; NB: what separates these two batches is the CHECKPOINT above, not a
      ;; drain boundary — this fixture never ran a drain at all. The real-router
      ;; separation proof (a genuine host yield) is
      ;; render-batch-host-checkpoint-dom-cljs-test (rf2-vxgfnd.166).
      (reactive/mark-dirty! cell 9)
      (is (= 9 (reactive/pending-epoch cell)) "a fresh window re-anchors the evidence")
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell))
          "render SEPARATION follows HOST CHECKPOINTS, never the epoch count")
      (is (= 2 @hits)))))

;; ===========================================================================
;; G-3 multi-read scaling (S2f) — 1/4/8/16 sites: ONE body invocation, ONE
;; notification per render batch, against the REAL observation port
;; ===========================================================================
;;
;; N lexical (sub …) sites in a view share ONE ViewCell (one
;; useSyncExternalStore hook). The render body runs ONCE for all N sites, the
;; commit acquires N REAL handles (deduped by target identity), and when all N
;; sites fan in before ONE checkpoint the cell coalesces to ONE notification —
;; the notification count per batch is INVARIANT to the site count. Fully against
;; the real port + plain-atom cache — no scheduler seam, no mock — but be
;; honest about WHICH invalidation channel drives the fan-in: each sub is
;; RE-REGISTERED (reg-sub again), so the port disposes its canonical node and
;; fires every committed handle's REAL :hmr-cause on-change, caught at
;; mark-dirty! (constant-work enrolment) and advanced once by the coalesced
;; flush. This is NOT the app-db value-movement channel: on plain-atom a
;; value move has no watch and is caught at the commit evidence comparison
;; (step 5/8) — that path is pinned by the reconcile suite's moved-evidence
;; fixtures (re-frame.ui.reactive-reconcile-cljs-test), and the end-to-end
;; app-db-movement G-3 proof on the watchable adapter is the MOUNTED
;; counterpart (re-frame.ui.mounted-g3-cardinality-dom-cljs-test) — do not
;; deprioritise it on the strength of this headless gate.

(defn- g3-scaling-case
  "One G-3 case at `n` sites: render a cell with n distinct sub sites, commit,
  then invalidate ALL n in one drain and assert exactly one body invocation, n
  real handles, and one notification."
  [n]
  (let [ids       (mapv (fn [i] (keyword "g3" (str n "-" i))) (range n))
        queries   (mapv vector ids)
        body-runs (atom 0)
        hits      (atom 0)]
    (doseq [i (range n)]
      (rf/reg-sub (ids i) (fn [db _] (get db i))))
    (seed! fid (into {} (map (fn [i] [i i])) (range n)))
    (let [cell (reactive/make-cell (keyword (str "g3-cell-" n)))]
      ;; ONE render body invocation drives all n sites (one ViewCell, one hook)
      (let [[_ capture] (rf/with-frame fid
                          (reactive/with-capture
                           cell
                           (fn []
                             (swap! body-runs inc)
                             (mapv (fn [i q]
                                     (reactive/sub-read [:epoch/site i] q))
                                   (range) queries))))]
        (reactive/commit! cell capture))
      (is (= 1 @body-runs)
          (str n " sites → ONE body invocation (one shared ViewCell, not " n ")"))
      (is (= n (count (reactive/committed-target-keys cell)))
          (str n " distinct REAL handles acquired against the observation port"))
      (doseq [q queries]
        (is (= 1 (ref-count q)) "each site owns exactly one handle — deduped by target"))
      (reactive/subscribe cell (fn [] (swap! hits inc)))
      ;; ONE batch fans in ALL n sites through the REAL port: re-registering
      ;; each sub disposes its canonical node and fires that committed handle's
      ;; REAL on-change (cause :hmr) — the genuine invalidation channel the port
      ;; drives, not a scheduler seam. All n fan into the SAME ViewCell, which
      ;; enrols ONCE; the coalesced flush notifies exactly once, whatever n is.
      (doseq [i (range n)]
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (is (reactive/dirty? cell)
          (str "the " n " real on-change fan-ins marked the cell dirty"))
      (is (= 1 (reactive/pending-cell-count))
          (str "enrolled ONCE despite " n " site invalidations in one batch"))
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell))
          (str n " site invalidations in ONE batch → ONE revision advance (not " n ")"))
      (is (= 1 @hits)
          (str n " sites → ONE notification per batch — scaling the site count "
               "does not scale notifications-per-batch"))
      ;; release the case's handles so the next case starts clean
      (reactive/teardown! cell))))

(deftest g3-multi-read-scaling-one-notification-per-batch
  (doseq [n [1 4 8 16]]
    (testing (str n " sites share one ViewCell and one per-batch notification")
      (reactive/reset-scheduler!)
      (g3-scaling-case n))))

;; ===========================================================================
;; flush! scope (the Q51 ruling) + no epoch work leaks across roots
;; ===========================================================================

(deftest scoped-flush-leaves-out-of-scope-cells-pending
  (let [c1 (reactive/make-cell ::c1)
        c2 (reactive/make-cell ::c2)]
    (reactive/mark-dirty! c1 1)
    (reactive/mark-dirty! c2 1)
    (is (= 2 (reactive/pending-cell-count)))
    (testing "a scoped flush advances only the matching cell"
      (is (= 1 (reactive/flush-scope! #(identical? % c1))) "one cell flushed")
      (is (= 1 (reactive/revision c1)))
      (is (= 0 (reactive/revision c2)) "the out-of-scope cell stays put — no leak")
      (is (reactive/dirty? c2))
      (is (= 1 (reactive/pending-cell-count))))
    (testing "the remaining scope flushes independently"
      (reactive/flush-scope! #(identical? % c2))
      (is (= 1 (reactive/revision c2)))
      (is (= 0 (reactive/pending-cell-count))))))

(deftest flush-frame-flushes-only-that-frames-cells
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/make-frame {:id ::f2})
  (seed! fid  {:a 1})
  (seed! ::f2 {:a 2})
  (let [c1 (rc! (reactive/make-cell ::c1) fid   [[:r/a]])
        c2 (rc! (reactive/make-cell ::c2) ::f2  [[:r/a]])]
    (is (= #{(tk fid  [:r/a])} (reactive/committed-target-keys c1)))
    (is (= #{(tk ::f2 [:r/a])} (reactive/committed-target-keys c2)))
    (is (reactive/cell-observes-frame? c1 fid))
    (is (reactive/cell-observes-frame? c2 ::f2))
    (is (not (reactive/cell-observes-frame? c1 ::f2)) "frames are isolated scopes")
    (reactive/mark-dirty! c1 (frame/frame-commit-epoch fid))
    (reactive/mark-dirty! c2 (frame/frame-commit-epoch ::f2))
    (testing "flush-frame! flushes only the cells observing that frame"
      (is (= 1 (reactive/flush-frame! fid)) "only the :rf/default cell")
      (is (= 1 (reactive/revision c1)))
      (is (= 0 (reactive/revision c2)) "the ::f2 cell's epoch work did not leak across roots")
      (is (reactive/dirty? c2)))
    (testing "the other frame flushes on its own scope"
      (is (= 1 (reactive/flush-frame! ::f2)))
      (is (= 1 (reactive/revision c2)))
      (is (= 0 (reactive/pending-cell-count))))))

(deftest global-flush-drains-every-root
  (let [c1 (reactive/make-cell ::c1)
        c2 (reactive/make-cell ::c2)
        c3 (reactive/make-cell ::c3)]
    (doseq [c [c1 c2 c3]] (reactive/mark-dirty! c 1))
    (is (= 3 (reactive/pending-cell-count)))
    (testing "the global registry drain settles every dirty root to fixed point"
      #?(:clj
         ;; JVM has no ui.test/flush! (no React tree to settle) — the drain
         ;; being tested here IS reactive/flush-pending! + converge-flush!,
         ;; the internal law ui.test/flush! wraps on CLJS. Drive it directly.
         (do
           (reactive/flush-pending!)
           (reactive/converge-flush! 'global-flush-drains-every-root
                                     reactive/flush-pending!)
           (is (= 1 (reactive/revision c1)))
           (is (= 1 (reactive/revision c2)))
           (is (= 1 (reactive/revision c3)))
           (is (= 0 (reactive/pending-cell-count))))
         :cljs
         (async done
           (-> (uit/flush!)
               (.then (fn []
                        (is (= 1 (reactive/revision c1)))
                        (is (= 1 (reactive/revision c2)))
                        (is (= 1 (reactive/revision c3)))
                        (is (= 0 (reactive/pending-cell-count)))
                        (done))
                      (fn [e]
                        (is false (str "flush! Promise rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; flush-frame! override-only guard (S2f) — an override-only cell observes NO
;; frame, so the frame arity is a no-op / guarded for it
;; ===========================================================================

(deftest flush-frame-is-a-no-op-for-an-override-only-cell
  ;; An override-only cell commits only [:override id] target keys — cell-frames
  ;; keeps :sub keys ONLY, so the cell observes NO frame. flush-frame! scopes on
  ;; cell-observes-frame?, so it can never reach an override-only cell; only the
  ;; global flush-pending! drains one (the override-only-cell-has-no-frame
  ;; contract — see reactive/flush-frame! + cell-frames, reactive.cljc).
  (binding [reactive/*sub-overrides* {[:r/a] 99}]
    (let [cell (rc! (reactive/make-cell ::ov) fid [[:r/a]])
          hits (atom 0)]
      (is (= #{[:override [:r/a]]} (reactive/committed-target-keys cell))
          "the cell's whole committed dep set is a static override — no :sub site")
      (is (not (reactive/cell-observes-frame? cell fid))
          "an override-only cell observes NO frame (cell-frames keeps :sub keys only)")
      (reactive/subscribe cell (fn [] (swap! hits inc)))
      (reactive/mark-dirty! cell 1)
      (is (reactive/dirty? cell) "the cell is pending")
      (testing "flush-frame! is a NO-OP — the override-only cell is never in frame scope"
        (is (= 0 (reactive/flush-frame! fid)) "no cell flushed by the frame arity")
        (is (reactive/dirty? cell) "still pending — the frame flush did not reach it")
        (is (= 0 @hits) "and no notification fired")
        (is (= 0 (reactive/revision cell))))
      (testing "the GLOBAL flush is the only forcing that drains an override-only cell"
        (reactive/flush-pending!)
        (is (not (reactive/dirty? cell)))
        (is (= 1 @hits) "the global flush drains it")
        (is (= 1 (reactive/revision cell)))))))

;; ===========================================================================
;; Reentrancy — safe by construction (the flush-in-open-epoch safety net)
;; ===========================================================================

(deftest reentrant-flush-during-notify-does-not-double-advance
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    ;; a listener that re-enters the GLOBAL flush from inside its own notify
    (reactive/subscribe cell (fn []
                               (swap! hits inc)
                               (reactive/flush-pending!)))  ;; re-entrant
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (reactive/revision cell))
        "the atomic drain-then-notify means the re-entrant flush sees an
         empty registry — one advance, never two")
    (is (= 1 @hits))))

;; ===========================================================================
;; Discard on disconnect / teardown — no stale flush of an unmounted cell
;; ===========================================================================

(deftest disconnect-discards-pending-notification
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (reactive/mark-dirty! cell 1)
    (is (= 1 (reactive/pending-cell-count)))
    (reactive/disconnect! cell)
    (is (= :disconnected (reactive/lifecycle cell)))
    (is (not (reactive/dirty? cell)) "a disconnected cell holds no pending flush")
    (is (= 0 (reactive/pending-cell-count)) "and leaves the registry")
    (testing "a later global flush does not advance the unmounted cell"
      (reactive/flush-pending!)
      (is (= 0 (reactive/revision cell))))))

(deftest teardown-discards-pending-notification
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (reactive/mark-dirty! cell 1)
    (reactive/teardown! cell)
    (is (= :dead (reactive/lifecycle cell)))
    (is (= 0 (reactive/pending-cell-count)) "teardown drops the pending flush")))

(deftest scheduler-reset-clears-detached-cell-state-and-opens-a-fresh-window
  ;; rf2-vxgfnd.181 — clearing only the registry strands this cell: its dirty
  ;; flag remains true, epoch 2 folds into epoch 1, and the false->true enrolment
  ;; edge never happens. Reset owns the detached cells as well as the set.
  (let [cell (reactive/make-cell ::reset-cell)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/mark-dirty! cell 1)
    (is (= 1 (reactive/pending-cell-count)))
    (is (= {:first-epoch 1 :latest-epoch 1 :count 1}
           (select-keys (reactive/pending-evidence cell)
                        [:first-epoch :latest-epoch :count])))

    (reactive/reset-scheduler!)
    (is (false? (reactive/dirty? cell))
        "every cell detached from the registry is clean")
    (is (nil? (reactive/pending-evidence cell))
        "the pre-reset evidence window is gone")
    (is (= 0 (reactive/pending-cell-count)))
    (is (= 0 (reactive/revision cell)) "reset never advances the revision")
    (is (= 0 @hits) "reset never notifies")

    (reactive/mark-dirty! cell 2)
    (is (true? (reactive/dirty? cell)))
    (is (= 1 (reactive/pending-cell-count))
        "epoch 2 crosses a fresh false->true edge and re-enrols")
    (is (= {:first-epoch 2 :latest-epoch 2 :count 1}
           (select-keys (reactive/pending-evidence cell)
                        [:first-epoch :latest-epoch :count]))
        "evidence cannot span the fixture reset")
    (is (= 1 (reactive/flush-pending!)))
    (is (= 1 (reactive/revision cell)))
    (is (= 1 @hits))))

#?(:clj
   (deftest reset-versus-mark-linearizes-to-a-post-reset-enrolment
     ;; Deterministically pause reset AFTER it detaches the registry but BEFORE
     ;; it clears the detached cell. The scheduler lock must keep the epoch-2
     ;; mark outside that ownership window. Without the lock the mark returns
     ;; while the cell is still dirty, skips enrolment, and reset erases it.
     (let [cell       (reactive/make-cell ::reset-race)
           hits       (atom 0)
           detached   (java.util.concurrent.CountDownLatch. 1)
           release    (java.util.concurrent.CountDownLatch. 1)
           mark-start (java.util.concurrent.CountDownLatch. 1)
           real-swap  swap-vals!]
       (reactive/subscribe cell (fn [] (swap! hits inc)))
       (reactive/mark-dirty! cell 1)
       (with-redefs [clojure.core/swap-vals!
                     (fn [a f & args]
                       (let [result (apply real-swap a f args)]
                         ;; The registry is the only set-valued atom touched by
                         ;; reset. Pause after its atomic detach while reset
                         ;; still owns the scheduler lock.
                         (when (set? (first result))
                           (.countDown detached)
                           (.await release 5 java.util.concurrent.TimeUnit/SECONDS))
                         result))]
         (let [reset-f (future (reactive/reset-scheduler!))]
           (is (.await detached 5 java.util.concurrent.TimeUnit/SECONDS)
               "reset reached the deterministic detach boundary")
           (let [mark-f (future
                          (.countDown mark-start)
                          (reactive/mark-dirty! cell 2))]
             (is (.await mark-start 5 java.util.concurrent.TimeUnit/SECONDS))
             (is (= ::blocked (deref mark-f 250 ::blocked))
                 "a mark cannot enter the detached-but-not-cleared window")
             (.countDown release)
             (is (nil? (deref reset-f 5000 ::reset-timeout)))
             (is (nil? (deref mark-f 5000 ::mark-timeout)))
             (is (true? (reactive/dirty? cell)))
             (is (= 1 (reactive/pending-cell-count))
                 "the racing mark linearized after reset and is registered")
             (is (= {:first-epoch 2 :latest-epoch 2 :count 1}
                    (select-keys (reactive/pending-evidence cell)
                                 [:first-epoch :latest-epoch :count])))
             (is (= 1 (reactive/flush-pending!)))
             (is (= 1 (reactive/revision cell)))
             (is (= 1 @hits))))))))

;; ===========================================================================
;; The slice-scoped probe memo — sibling probes share derivation parents
;; ===========================================================================

(deftest slice-memo-shares-derivation-parents-within-one-render
  (let [parent-runs (atom 0)]
    (rf/reg-sub :s/parent (fn [db _] (swap! parent-runs inc) (:n db)))
    (rf/reg-sub :s/a :<- [:s/parent] (fn [n _] [:a n]))
    (rf/reg-sub :s/b :<- [:s/parent] (fn [n _] [:b n]))
    (seed! fid {:n 5})
    (reactive/reset-scheduler!)          ;; a clean slice
    (reset! parent-runs 0)
    (let [cell (reactive/make-cell ::v)
          out  (rf/with-frame fid
                 (reactive/with-capture cell
                   (fn [] [(reactive/sub-read ::site-a [:s/a])
                           (reactive/sub-read ::site-b [:s/b])])))]
      (is (= [[:a 5] [:b 5]] (first out)))
      (is (= 1 @parent-runs)
          "sibling cold probes in ONE render compute the shared parent ONCE
           via the slice-scoped memo threaded through sub-read")
      (is (nil? (entry [:s/parent])) "probes stayed ownership-free — no cache node"))))

#?(:clj
   (deftest slice-memo-dies-with-slice
     ;; rf2-vxgfnd.174 — the slice memo dies with its slice, exercised through
     ;; the REAL runtime path (`with-capture` → `sub-read` → `current-slice-memo`),
     ;; NOT a proxy that hand-constructs two `obs/make-slice-memo` handles. On the
     ;; JVM each top-level render (`with-capture`) opens a thread-local slice: the
     ;; sibling sites share their derivation parent ONCE, and a LATER render
     ;; recomputes because the prior slice's table did NOT survive — proven with
     ;; the frame/incarnation/registry tag UNCHANGED and WITHOUT `reset-scheduler!`,
     ;; so only the per-slice scope (not a tag flip or a reset) explains the
     ;; recompute. On current main the JVM module holder persisted across slices
     ;; and the second render MEMO-HIT the prior table (parent-runs stays 1). The
     ;; CLJS boundary is the MICROTASK, not the synchronous with-capture — that
     ;; host's counterpart is `cljs-slice-memo-holder-dies-at-the-microtask-checkpoint`.
     (let [parent-runs (atom 0)]
       (rf/reg-sub :s/parent (fn [db _] (swap! parent-runs inc) (:n db)))
       (rf/reg-sub :s/a :<- [:s/parent] (fn [n _] [:a n]))
       (rf/reg-sub :s/b :<- [:s/parent] (fn [n _] [:b n]))
       (seed! fid {:n 5})
       (let [render! (fn []
                       (rf/with-frame fid
                         (reactive/with-capture (reactive/make-cell ::v)
                           (fn [] [(reactive/sub-read ::site-a [:s/a])
                                   (reactive/sub-read ::site-b [:s/b])]))))]
         (testing "WITHIN one slice sibling cold probes share the parent ONCE"
           (let [[out _] (render!)]
             (is (= [[:a 5] [:b 5]] out))
             (is (= 1 @parent-runs)
                 "the slice-scoped memo threaded through sub-read computes the
                  shared parent once for the two sibling sites")
             (is (nil? (entry [:s/parent])) "cold probes stayed ownership-free")))
         (testing "a LATER slice recomputes — the prior slice's memo did NOT survive"
           ;; Tag is IDENTICAL (same frame, commit epoch, registry epoch,
           ;; incarnation) and NO reset-scheduler! ran between renders, so a
           ;; recompute can only come from the slice dying — not a tag/reset.
           (render!)
           (is (= 2 @parent-runs)
               "the fresh render slice recomputes the shared parent — the prior
                slice's table is unreachable (dies with the slice)"))))))

(deftest slice-memo-across-sequential-executor-tasks-never-reuses-a-table
  ;; rf2-vxgfnd.174 AC — two SEQUENTIAL top-level renders on a background
  ;; executor task cannot reuse the same memo table (the JVM concurrent-render
  ;; case). Same tag, no reset-scheduler!; each render is its own slice, so the
  ;; parent recomputes per render. JVM-only: it drives a real executor future.
  #?(:clj
     (let [parent-runs (atom 0)]
       (rf/reg-sub :sx/parent (fn [db _] (swap! parent-runs inc) (:n db)))
       (rf/reg-sub :sx/a :<- [:sx/parent] (fn [n _] [:a n]))
       (rf/reg-sub :sx/b :<- [:sx/parent] (fn [n _] [:b n]))
       (seed! fid {:n 9})
       (let [render! (fn []
                       (rf/with-frame fid
                         (reactive/with-capture (reactive/make-cell ::v)
                           (fn [] [(reactive/sub-read ::a [:sx/a])
                                   (reactive/sub-read ::b [:sx/b])]))))
             task    (fn [] (let [[out _] (render!)] out))]
         (is (= [[:a 9] [:b 9]] @(future (task))) "task 1 renders on its own thread")
         (is (= [[:a 9] [:b 9]] @(future (task))) "task 2 renders on its own thread")
         (is (= 2 @parent-runs)
             "two sequential executor tasks each got a FRESH slice — the table
              is never reused across renders (no cross-task retention)")))
     :cljs (is true "executor-task isolation is a JVM concern")))

#?(:cljs
   (deftest cljs-slice-memo-holder-dies-at-the-microtask-checkpoint
     ;; rf2-vxgfnd.174 CLJS AC — the module holder shares one handle across a
     ;; SYNCHRONOUS render pass and is released at the MICROTASK checkpoint (NOT
     ;; the `next-tick` macrotask). Two headless probes in ONE synchronous task
     ;; share the parent once; a probe scheduled on a later MICROTASK sees the
     ;; holder already released and recomputes — proof the holder dies with the
     ;; synchronous slice at the microtask boundary, aligned with the port's
     ;; table clear. Reverting the holder clear to `interop/next-tick` (a
     ;; macrotask) leaves the holder live through this microtask and the probe
     ;; MEMO-HITS instead — this fixture goes red.
     (async done
       (let [parent-runs (atom 0)]
         (rf/reg-sub :sm/parent (fn [db _] (swap! parent-runs inc) (:n db)))
         (rf/reg-sub :sm/a :<- [:sm/parent] (fn [n _] [:a n]))
         (rf/reg-sub :sm/b :<- [:sm/parent] (fn [n _] [:b n]))
         (seed! fid {:n 3})
         ;; ONE synchronous slice: two headless sibling probes share the module
         ;; holder, so the parent computes once.
         (rf/with-frame fid
           (reactive/sub-read ::a [:sm/a])
           (reactive/sub-read ::b [:sm/b]))
         (is (= 1 @parent-runs) "the synchronous slice shared the parent ONCE")
         ;; A later MICROTASK: the holder-clear microtask (armed by the first
         ;; probe) runs first (FIFO), so this probe finds a released holder and
         ;; recomputes.
         (js/queueMicrotask
           (fn []
             (rf/with-frame fid (reactive/sub-read ::a [:sm/a]))
             (is (= 2 @parent-runs)
                 "the microtask probe recomputed — the holder died at the
                  microtask checkpoint (macrotask next-tick would still memo-hit)")
             (done)))))))

;; ===========================================================================
;; Bounded invalidation evidence across a coalesced batch (rf2-vxgfnd.46)
;; ===========================================================================
;;
;; The observation port emits a rich constant-work invalidation payload
;; (`:cause`/`:target`/`:node-*`/`:frame-epoch`/`:registry-epoch`). re-frame.ui
;; keeps TWO planes: the production scheduler folds only the pending flag +
;; identity-deduped registry membership; a DEBUG plane accumulates a BOUNDED,
;; constant-size causal summary of the coalesced batch for tooling. Coalescing
;; and evidence stay independent — one dirty enrolment / one render, yet the
;; debug plane retains enough to attribute that render to its contributing
;; movement (first/latest epoch, a cause set, a capped target vector + a
;; bounded `:dropped` set whose `:dropped-exact?` flag records saturation)
;; WITHOUT forcing a render for every epoch.

(deftest n-invalidations-in-one-drain-preserve-bounded-evidence
  ;; N invalidations with DISTINCT epochs fold before one flush: ONE revision
  ;; advance + ONE notification, while the evidence plane summarizes the whole
  ;; batch (first/latest epoch + fold count) and the flush CARRIES it to the
  ;; consumer sink — never a per-epoch render (AC 2/3/5).
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)
        seen (atom [])]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/set-evidence-sink! (fn [c ev] (swap! seen conj [c ev])))
    (doseq [e (range 1 9)] (reactive/mark-dirty! cell e))
    (testing "the pending window's evidence summarizes the whole batch"
      (let [ev (reactive/pending-evidence cell)]
        (is (= 1 (:first-epoch ev)) "anchored to the FIRST epoch")
        (is (= 8 (:latest-epoch ev)) "…and tracks the LATEST")
        (is (= 8 (:count ev)) "all 8 invalidations folded")
        (is (= #{} (:dropped ev)) "nothing dropped — the loss set is empty")
        (is (:dropped-exact? ev) "…and the loss account is exact")))
    (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 8 marks")
    (reactive/flush-pending!)
    (testing "coalesced to ONE render, and the flush CARRIED the evidence"
      (is (= 1 (reactive/revision cell)) "ONE revision advance")
      (is (= 1 @hits) "ONE notification — one render batch, not eight")
      (is (= 1 (count @seen)) "the sink received the coalesced batch exactly once")
      (is (identical? cell (first (first @seen))))
      (is (= 8 (:count (second (first @seen)))) "…with the full coalesced count")
      (is (nil? (reactive/pending-evidence cell)) "evidence clears with the flush"))))

(deftest evidence-folds-the-real-cause-and-target-from-the-port-payload
  ;; The rich port payload is CONSUMED at on-change, not discarded (AC 1/4). A
  ;; committed cell whose sub is re-registered receives a REAL `:hmr`
  ;; invalidation carrying the moving target; the evidence records both.
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (is (nil? (reactive/pending-evidence cell)) "clean before any movement")
    (rf/reg-sub :r/a (fn [db _] (:a db)))   ;; HMR re-registration → real :hmr fan-out
    (let [ev (reactive/pending-evidence cell)]
      (is (reactive/dirty? cell) "the HMR invalidation marked the cell dirty")
      (is (= #{:hmr} (:causes ev)) "the real :hmr cause is recorded, not thrown away")
      (is (= [(tk fid [:r/a])] (:targets ev)) "…with the moving target key")
      (is (= 1 (:count ev))))))

(deftest evidence-target-vector-is-bounded-with-a-loss-account
  ;; MORE distinct moving targets than the cap fold into a CAPPED SHOWN vector
  ;; plus a bounded `:dropped` SET of the distinct OMITTED targets — the evidence
  ;; is constant-size, and overflow is reported by IDENTITY rather than silently
  ;; lost (AC 3). End-to-end through the real :hmr fan-out.
  (let [n       10                          ;; > the target cap (8)
        ids     (mapv (fn [i] (keyword "r" (str "s" i))) (range n))
        queries (mapv vector ids)]
    (doseq [i (range n)]
      (rf/reg-sub (ids i) (fn [db _] (get db i))))
    (seed! fid (into {} (map (fn [i] [i i])) (range n)))
    (let [cell (rc! (reactive/make-cell ::v) fid queries)]
      (doseq [i (range n)]                  ;; re-register each → n distinct :hmr targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (let [ev (reactive/pending-evidence cell)]
        (is (= n (:count ev)) "every invalidation is counted")
        (is (= 8 (count (:targets ev))) "the shown target vector is capped at target-cap")
        (is (= 2 (count (:dropped ev)))
            "the 2 DISTINCT overflow targets are recorded by identity, not silently lost")
        (is (every? vector? (:dropped ev)) ":dropped holds target KEYS, not a bare count")
        (is (:dropped-exact? ev) "the loss account is exact — well under dropped-cap")
        (is (= #{:hmr} (:causes ev)) "the cause set stays bounded"))
      (testing "the whole capped batch still coalesces to ONE render"
        (let [hits (atom 0)]
          (reactive/subscribe cell (fn [] (swap! hits inc)))
          (reactive/flush-dirty! cell)
          (is (= 1 @hits) "one notification for the whole bounded batch"))))))

(deftest evidence-dropped-counts-distinct-omissions-not-occurrences
  ;; rf2-vxgfnd.74 — the HONESTY fix, exercised at the fold unit. `:dropped` is
  ;; the DISTINCT-omitted-target axis, NOT overflow OCCURRENCES: re-invalidating
  ;; one already-omitted target N times advances `:count` by N but must NOT
  ;; inflate the loss. The fold unit is the right level here — a SAME-target
  ;; re-invalidation storm can't be staged through the real :hmr path (an HMR
  ;; dispose fires a target's live handle exactly once), yet it is the exact
  ;; shape that made the old occurrence-counting `:dropped` dishonest.
  (let [fold    @#'reactive/fold-evidence
        target  (fn [i] {:kind :subscription :frame-id fid :query [(keyword "r" (str "s" i))]})
        key-i   (fn [i] (tk fid [(keyword "r" (str "s" i))]))
        payload (fn [i] {:cause :hmr :target (target i) :frame-epoch i})
        after-8 (reduce fold nil (map payload (range 8)))]  ;; 8 distinct shown, once each
    (testing "the headline example — 8 retained once, then ONE ninth target 100×"
      ;; PRE-FIX this window read {:count 108 :targets 8 :dropped 100} — claiming
      ;; 100 omitted targets when only ONE distinct target was ever dropped.
      (let [ev (reduce fold after-8 (repeat 100 (payload 8)))]
        (is (= 108 (:count ev)) ":count still totals every invalidation OCCURRENCE")
        (is (= 8 (count (:targets ev))) "the shown sample stays capped at target-cap")
        (is (= #{(key-i 8)} (:dropped ev))
            "ONE distinct target omitted — the field reports its IDENTITY, not 100")
        (is (= 1 (count (:dropped ev)))
            "…so the fan-out loss is 1, NOT 100 — the field no longer overstates")
        (is (:dropped-exact? ev) "and the loss account is exact — far under dropped-cap")))
    (testing "genuinely-distinct overflow — the distinct-omission set grows honestly"
      (let [ev (reduce fold after-8 (map payload (range 8 11)))]  ;; 9th/10th/11th distinct
        (is (= 11 (:count ev)))
        (is (= 8 (count (:targets ev))))
        (is (= #{(key-i 8) (key-i 9) (key-i 10)} (:dropped ev))
            "three DISTINCT overflow targets → three distinct omissions, by identity")
        (is (:dropped-exact? ev))))
    (testing "the loss set is ITSELF bounded — saturation flips :dropped-exact? false"
      (let [cap @#'reactive/dropped-cap
            n   (+ 8 cap 50)                        ;; far past BOTH caps
            ev  (reduce fold nil (map payload (range n)))]
        (is (= n (:count ev)) "every occurrence still counts")
        (is (= 8 (count (:targets ev))) "shown sample bounded")
        (is (= cap (count (:dropped ev)))
            "the loss set is bounded at dropped-cap — constant-size, never unbounded")
        (is (false? (:dropped-exact? ev))
            "…and honestly flags that (count :dropped) is now a LOWER bound")))))
