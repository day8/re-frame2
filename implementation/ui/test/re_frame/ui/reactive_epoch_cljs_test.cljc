(ns re-frame.ui.reactive-epoch-cljs-test
  "rf2-vxgfnd.10 (S2d) — epoch coalescing + `flush!` scope over the ViewCell
  notification scheduler (03 §3 invariant 6 'one notification per cell per
  render batch — the boundary is drain quiescence, not epoch close'; Spec 006
  §Epoch finalization). Headless fixtures on the REAL
  observation port + plain-atom sub-cache — the value-movement `on-change`
  watch channel is a reactive-host surface, so these fixtures drive the
  notification seam DIRECTLY (`mark-dirty!` with an epoch tag) exactly the
  way the reactive spine's watch fan-out would, then force with `flush!`.

  The rows:

    - DRAIN COALESCING — N event/frame EPOCHS committed in ONE
      run-to-completion drain advance a cell's revision ONCE, coalescing
      into ONE render batch (the corrected sixth invariant: the render
      boundary is drain quiescence, NOT epoch close; epoch ids are cause
      evidence, never render triggers). A separate later drain notifies
      again — later work stays observable, but NO render count follows from
      the epoch count (replaces the retired false gate `N epochs ⇒ N renders`);
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
    - DISCARD ON DISCONNECT/TEARDOWN — an unmounted cell leaves the registry
      without a stale flush;
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
  (test-support/make-reset-runtime-fixture
   (cond-> {:adapter plain-atom/adapter}
     #?(:clj false :cljs true) (assoc :async? true)))
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
                       cell (fn [] (mapv reactive/sub-read queries))))]
    (reactive/commit! cell capture))
  cell)

;; ===========================================================================
;; Drain coalescing — N epochs in ONE drain → ONE render batch
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
  ;; regains control (and flushes). Every one of those epochs folds into ONE
  ;; render batch — the render boundary is DRAIN QUIESCENCE, not epoch close.
  ;; Epoch ids ride the invalidation as CAUSE EVIDENCE only: coalescing keys
  ;; on the pending flag, never on the epoch tag. Render SEPARATION follows
  ;; DRAIN boundaries, never the epoch count.
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (testing "8 distinct epochs committed in ONE drain ⇒ ONE render batch"
      ;; one drain: 8 queued events each commit their own epoch, each firing
      ;; on-change with a DISTINCT epoch tag, all BEFORE the flush that rides
      ;; drain quiescence (the CLJS microtask / the headless explicit flush)
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
    (testing "a SEPARATE later drain renders separately — later work stays observable"
      (reactive/mark-dirty! cell 9)
      (is (= 9 (reactive/pending-epoch cell)) "a fresh drain re-anchors the evidence")
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell))
          "render SEPARATION follows DRAIN boundaries, never the epoch count")
      (is (= 2 @hits)))))

;; ===========================================================================
;; G-3 multi-read scaling (S2f) — 1/4/8/16 sites: ONE body invocation, ONE
;; notification per drain, against the REAL observation port
;; ===========================================================================
;;
;; N lexical (sub …) sites in a view share ONE ViewCell (one
;; useSyncExternalStore hook). The render body runs ONCE for all N sites, the
;; commit acquires N REAL leases (deduped by target identity), and when all N
;; sites move in ONE drain the cell coalesces to ONE notification — the
;; notification count per drain is INVARIANT to the site count. Fully against
;; the real port + plain-atom cache: the one-drain fan-in is a REAL app-db move
;; caught at the headless commit evidence comparison (step 5/8), which advances
;; the cell's revision AT MOST ONCE per commit — no scheduler seam, no mock.

(defn- g3-scaling-case
  "One G-3 case at `n` sites: render a cell with n distinct sub sites, commit,
  then invalidate ALL n in one drain and assert exactly one body invocation, n
  real leases, and one notification."
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
                             (mapv reactive/sub-read queries))))]
        (reactive/commit! cell capture))
      (is (= 1 @body-runs)
          (str n " sites → ONE body invocation (one shared ViewCell, not " n ")"))
      (is (= n (count (reactive/committed-target-keys cell)))
          (str n " distinct REAL leases acquired against the observation port"))
      (doseq [q queries]
        (is (= 1 (ref-count q)) "each site owns exactly one lease — deduped by target"))
      (reactive/subscribe cell (fn [] (swap! hits inc)))
      ;; ONE drain fans in ALL n sites through the REAL port: re-registering
      ;; each sub disposes its canonical node and fires that committed lease's
      ;; REAL on-change (cause :hmr) — the genuine invalidation channel the port
      ;; drives, not a scheduler seam. All n fan into the SAME ViewCell, which
      ;; enrols ONCE; the coalesced flush notifies exactly once, whatever n is.
      (doseq [i (range n)]
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (is (reactive/dirty? cell)
          (str "the " n " real on-change fan-ins marked the cell dirty"))
      (is (= 1 (reactive/pending-cell-count))
          (str "enrolled ONCE despite " n " site invalidations in one drain"))
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell))
          (str n " site invalidations in ONE drain → ONE revision advance (not " n ")"))
      (is (= 1 @hits)
          (str n " sites → ONE notification per drain — scaling the site count "
               "does not scale notifications-per-drain"))
      ;; release the case's leases so the next case starts clean
      (reactive/teardown! cell))))

(deftest g3-multi-read-scaling-one-notification-per-drain
  (doseq [n [1 4 8 16]]
    (testing (str n " sites share one ViewCell and one per-drain notification")
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
    (testing "ui.test/flush! is the test-only GLOBAL all-roots spelling"
      #?(:clj
         (do
           (uit/flush!)
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
                   (fn [] [(reactive/sub-read [:s/a])
                           (reactive/sub-read [:s/b])])))]
      (is (= [[:a 5] [:b 5]] (first out)))
      (is (= 1 @parent-runs)
          "sibling cold probes in ONE render compute the shared parent ONCE
           via the slice-scoped memo threaded through sub-read")
      (is (nil? (entry [:s/parent])) "probes stayed ownership-free — no cache node"))))

(deftest slice-memo-dies-with-slice
  ;; make-slice-memo scopes derivation sharing to ONE handle = ONE render slice.
  ;; The reactive layer mints a FRESH handle per slice (and releases it on the
  ;; next tick / microtask), so a DEAD slice's memo is never retained into the
  ;; next slice. Proven at the port primitive the reactive layer consumes:
  ;; sharing holds WITHIN a slice, and a fresh slice recomputes.
  (let [parent-runs (atom 0)]
    (rf/reg-sub :s/parent (fn [db _] (swap! parent-runs inc) (:n db)))
    (rf/reg-sub :s/a :<- [:s/parent] (fn [n _] [:a n]))
    (rf/reg-sub :s/b :<- [:s/parent] (fn [n _] [:b n]))
    (seed! fid {:n 5})
    (rf/with-frame fid
      (let [h1 (obs/make-slice-memo)]
        (is (= {:tag nil :memo nil} @h1) "a fresh slice handle retains no table")
        (obs/probe (obs/resolve-target {:query-v [:s/a]}) h1)
        (obs/probe (obs/resolve-target {:query-v [:s/b]}) h1)
        (is (= 1 @parent-runs)
            "WITHIN one slice the shared parent computes ONCE (memo hit)")
        (is (some? (:memo @h1)) "the live slice retains its table")
        ;; a NEW slice = a FRESH handle; the dead slice's memo is not reused
        (let [h2 (obs/make-slice-memo)]
          (obs/probe (obs/resolve-target {:query-v [:s/a]}) h2)
          (obs/probe (obs/resolve-target {:query-v [:s/b]}) h2)
          (is (= 2 @parent-runs)
              "a FRESH slice recomputes the parent — the prior slice's memo did
               NOT survive (dies with the slice; no retained memo)")
          (is (not (identical? h1 h2)) "each slice owns a DISTINCT memo handle"))))))

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
;; dropped-count) WITHOUT forcing a render per epoch.

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
  ;; dispose fires a target's live lease exactly once), yet it is the exact
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
