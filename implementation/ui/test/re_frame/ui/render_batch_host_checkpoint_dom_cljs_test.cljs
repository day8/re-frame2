(ns re-frame.ui.render-batch-host-checkpoint-dom-cljs-test
  "rf2-vxgfnd.166 — the render-batch boundary on the REAL React 19 + real DOM
  path, counting REAL root commits.

  The headless sibling (`render-batch-host-checkpoint-cljs-test`) pins the
  scheduler's pending window across real router drains. This namespace closes
  the loop on the host that actually owns the checkpoint: a mounted root, a
  React `Profiler` counting genuine commits, and a genuine microtask yield.

  It proves the three halves that only a real host can distinguish:

    - A COMPLETED DRAIN CLOSES NOTHING. A full `dispatch-sync!` drain returns
      with the cell still pending, the revision unmoved and React having
      committed nothing. Only the following host checkpoint advances it. This
      is the direct falsification of the retired per-drain rule.
    - NO YIELD → ONE BATCH. Two back-to-back `dispatch-sync` calls in one
      JavaScript stack — INSIDE a `cljs.test/async` body, the exact context
      that produced the rf2-kahkr misreading — share one window: a control
      microtask queued BEFORE the second call cannot run until both calls have
      returned, and the pair yields ONE revision advance and ONE root commit.
    - HOST YIELD → TWO BATCHES. Two drains separated by a real microtask yield
      produce two revision advances and two real root commits (guarantee 4).

  ## What the earlier measurement actually measured (rf2-kahkr → rf2-i3dvj)

  This namespace previously recorded that a second `dispatch-sync!` in the
  same stack advanced the first drain's pending mark before making its own —
  revision 0 -> 1 -> 2, with a control `js/queueMicrotask` running between a
  call's begin and end markers, i.e. seemingly MID-CALL — and concluded that
  `dispatch-sync!` yields.

  The measurement was real. The ATTRIBUTION was wrong, and it is now reversed.
  `dispatch-sync!` never yielded. The yield was INSTRUMENTATION: the DEBUG
  call-site stamp spliced a runtime `cond->` coord-map construction into the
  CALLER's context, and the CLJS compiler lowers a spliced multi-step form to
  `await (async function(){...})()` when the call site sits inside an async
  context — which every body in this namespace is (`cljs.test/async`). The
  compiler-inserted `await` immediately BEFORE the second `dispatch_sync_impl`
  invocation is what reached the microtask checkpoint. The captured
  `flush-scope!` stacks correctly terminated at the armed `schedule-flush!`
  microtask closure with no router frame above them precisely BECAUSE the
  flush ran at that `await`, not inside the router.

  Per rf2-i3dvj the call-site macros now emit compile-time coord LITERALS and
  push dynamic scope inside the callee's own body, so nothing yield-bearing
  reaches the caller. `no-yield-in-one-stack-shares-one-batch` below is the
  standing regression: it asserts the ordering causally, and it FAILS if a
  pre-call yield is ever reintroduced into the expansion.

  ## Why the awaits here are ordering guarantees, not races

  The scheduler arms its flush with `queue-microtask!` during the drain. The
  microtask queue is FIFO, so a microtask this test enqueues AFTER the drain
  necessarily runs AFTER the flush that was already queued. `(await-microtask)`
  therefore does not race the flush — it is ordered behind it by the host's own
  queue discipline. Nothing here sleeps, and nothing here assumes a macrotask
  lands after React settles (it does not).

  React commit timing is then forced, not awaited: `flushSync` with an empty
  thunk drains React's pending sync work at a point of our choosing, so the
  Profiler counts are read at a deterministic moment.

  Browser-only bodies — the `-dom-cljs-test` suffix enrols this namespace in
  `:browser-test`; the node runner loads it and each body skips without a DOM.
  A node-only run of this file proves NOTHING, by construction."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; This ns is a checkpoint-TIMING discriminator, not an `act` test: `act` drains
;; the pending microtask and collapses exactly the boundary under test, which
;; would make every assertion below vacuous. So it runs the real flushSync path
;; with IS_REACT_ACT_ENVIRONMENT OFF (the react_shared_suite.cljs pattern),
;; stashing and restoring the flag for sibling suites on the shared page.
(defonce ^:private prior-act-env (atom nil))

(defn- disable-act-env! []
  (when (exists? js/globalThis)
    (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)))

(defn- restore-act-env! []
  (when (exists? js/globalThis)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (disable-act-env!) (reactive/reset-scheduler!) (client/reset-live-roots!))
   :after  #(do (reactive/reset-scheduler!) (client/reset-live-roots!) (restore-act-env!))})

(def ^:private frame-kw :rbhcd/frame)

(defn- container [] (js/document.createElement "div"))

(def ^:private evidence (atom {}))

(defn- note-body! [] (swap! evidence update :body-runs inc))

(defview leaf [_]
  (let [_ (note-body!)]
    [:span.leaf (str (sub [:rbhcd/n]))]))

(defn root-profiler [^js props]
  (react/createElement
   (.-Profiler react)
   #js {:id "render-batch-root"
        :onRender (fn [& _] (swap! evidence update :root-commits inc))}
   (.-children props)))

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "render-batch host-checkpoint probe frame"})
  (rf/reg-sub :rbhcd/n (fn [db _] (:n db)))
  (rf/reg-event :rbhcd/seed (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :rbhcd/inc (fn [{:keys [db]} _] {:db (update db :n inc)})))

(defn- leaf-cell
  "The mounted ViewCell whose committed dependency set observes [:rbhcd/n]."
  []
  (some (fn [cell]
          (when (contains? (reactive/committed-target-keys cell)
                           [:sub frame-kw [:rbhcd/n]])
            cell))
        (reactive/current-live-cells)))

(defn- await-microtask
  "Resolve on a microtask enqueued NOW. FIFO ordering puts it strictly after any
  flush microtask the preceding drain already armed, so this is an ordering
  guarantee rather than a race."
  []
  (js/Promise.resolve))

(defn- settle-react!
  "Force React to commit whatever the scheduler has already notified. Not a
  wait — a forcing checkpoint at a moment we choose."
  []
  (react-dom/flushSync (fn [])))

(defn- mount-probe!
  "Mount the leaf under a Profiler, seeded and committed. Returns the root."
  [c]
  (register-app!)
  (rf/dispatch-sync [:rbhcd/seed] {:frame frame-kw})
  (reset! evidence {:body-runs 0 :root-commits 0})
  (react-dom/flushSync
   #(ui/mount [root-profiler {}
               [frame-provider {:frame frame-kw} [leaf {}]]]
              c {:root-id :rbhcd/root})))

;; ===========================================================================
;; NO YIELD → ONE BATCH (guarantee 3)
;; ===========================================================================

(deftest a-completed-drain-does-not-close-the-render-batch
  ;; The DIRECT falsification of the retired rule, on the real host. A complete
  ;; `dispatch-sync!` drain runs start to finish; if drain completion were the
  ;; batch boundary, the cell would have advanced and be quiescent by the time
  ;; the drain returns. It is neither: the revision is unchanged and the cell is
  ;; still pending. Nothing about the router closes a render batch — only the
  ;; host checkpoint does.
  ;;
  ;; This test performs exactly ONE drain. Sharing across TWO same-stack drains
  ;; is pinned separately by `no-yield-in-one-stack-shares-one-batch` below.
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (async
     done
     (let [c    (container)
           root (mount-probe! c)]
       (is (= "0" (.-textContent (.querySelector c ".leaf"))) "leaf mounted")
       (let [cell         (leaf-cell)
             base-rev     (reactive/revision cell)
             base-commits (:root-commits @evidence)]
         ;; ONE complete router drain, start to finish.
         (rf/dispatch-sync [:rbhcd/inc] {:frame frame-kw})

         (testing "the drain has FULLY completed and closed no batch"
           (is (= base-rev (reactive/revision cell))
               "no revision advance — drain completion is not a batch boundary")
           (is (reactive/dirty? cell)
               "the cell is STILL PENDING after a finished drain: the window
                stays open across the drain's end (rf2-vxgfnd.166)")
           (is (= 1 (reactive/pending-cell-count)))
           (is (= base-commits (:root-commits @evidence))
               "and React has committed nothing for it yet"))

         (-> (await-microtask)
             (.then (fn [_]
                      (settle-react!)
                      (testing "the HOST CHECKPOINT is what closes the batch"
                        (is (= (inc base-rev) (reactive/revision cell))
                            "the revision advances at the checkpoint, not at the
                             drain's end")
                        (is (not (reactive/dirty? cell)) "the window has closed")
                        (is (= (inc base-commits) (:root-commits @evidence))
                            "exactly one REAL React root commit followed it")
                        (is (= "1" (.-textContent (.querySelector c ".leaf")))
                            "…and the DOM shows the write"))
                      (react-dom/flushSync #(ui/unmount! root))
                      (done)))))))))

(deftest no-yield-in-one-stack-shares-one-batch
  ;; rf2-i3dvj — the standing regression against a hidden pre-call yield, and
  ;; the restoration of guarantee 3's example on the MOUNTED path.
  ;;
  ;; The whole point is WHERE this runs: inside a `cljs.test/async` body, which
  ;; is a CLJS async context. That is the exact setting in which a call-site
  ;; macro expansion carrying a runtime `cond->` (or a `binding`) gets lowered
  ;; by the compiler to `await (async function(){...})()` — a real microtask
  ;; yield spliced in immediately BEFORE the instrumented call. rf2-kahkr
  ;; measured that yield and mis-attributed it to `dispatch-sync!` itself.
  ;;
  ;; The assertion is CAUSAL, not incidental. A control microtask is queued
  ;; BETWEEN the two `dispatch-sync` calls. A microtask cannot run while a
  ;; JavaScript stack is live, so:
  ;;
  ;;   - expansion is evaluation-order-transparent → the control runs AFTER
  ;;     both calls have returned; the log reads [1 2 control];
  ;;   - a pre-call yield is reintroduced → the second call's `await` reaches
  ;;     the microtask checkpoint, the control runs BETWEEN the calls, the log
  ;;     reads [1 control 2], and this test REDS.
  ;;
  ;; Nothing here asserts that an exception was thrown: a compiler-inserted
  ;; yield does not throw on CLJS, so only observable ORDERING (plus the
  ;; revision and real-commit counts) can discriminate it.
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (async
     done
     (let [c    (container)
           root (mount-probe! c)]
       (let [cell         (leaf-cell)
             base-rev     (reactive/revision cell)
             base-commits (:root-commits @evidence)
             log          (atom [])]
         ;; DRAIN 1.
         (rf/dispatch-sync [:rbhcd/inc] {:frame frame-kw})
         (swap! log conj :returned-from-call-1)
         ;; THE CONTROL — queued before call 2, on the same live stack.
         (js/queueMicrotask (fn [] (swap! log conj :control-microtask)))
         ;; DRAIN 2 — same JavaScript stack, no yield between them.
         (rf/dispatch-sync [:rbhcd/inc] {:frame frame-kw})
         (swap! log conj :returned-from-call-2)

         (testing "two back-to-back dispatch-sync calls are ONE stack"
           (is (= [:returned-from-call-1 :returned-from-call-2] @log)
               "the control microtask queued BEFORE the second dispatch-sync
                had NOT run by the time that call returned — neither call
                yielded to the host (rf2-i3dvj)")
           (is (= base-rev (reactive/revision cell))
               "no revision advanced during either drain — the window is still
                open, so nothing flushed mid-stack")
           (is (= base-commits (:root-commits @evidence))
               "and React committed nothing during either drain"))

         (-> (await-microtask)
             (.then (fn [_]
                      (settle-react!)
                      (testing "the two same-stack drains SHARED one batch"
                        (is (= [:returned-from-call-1 :returned-from-call-2
                                :control-microtask]
                               @log)
                            "the control ran at the checkpoint, strictly after
                             both calls returned")
                        (is (= (inc base-rev) (reactive/revision cell))
                            "ONE revision advance for TWO drains (guarantee 3's
                             example, on the real mounted host)")
                        (is (= (inc base-commits) (:root-commits @evidence))
                            "ONE real React root commit for TWO drains")
                        (is (= "2" (.-textContent (.querySelector c ".leaf")))
                            "…and that single render shows BOTH writes"))
                      (react-dom/flushSync #(ui/unmount! root))
                      (done)))))))))

;; ===========================================================================
;; HOST YIELD → TWO BATCHES (guarantee 4)
;; ===========================================================================

(deftest drains-separated-by-a-host-yield-produce-two-render-batches
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (async
     done
     (let [c    (container)
           root (mount-probe! c)]
       (let [cell           (leaf-cell)
             base-rev       (reactive/revision cell)
             {:keys [body-runs root-commits]} @evidence]
         ;; DRAIN 1, then a REAL host yield closes its batch.
         (rf/dispatch-sync [:rbhcd/inc] {:frame frame-kw})
         (-> (await-microtask)
             (.then (fn [_]
                      (settle-react!)
                      (is (= (inc base-rev) (reactive/revision cell))
                          "the first batch closed at the yield")
                      ;; DRAIN 2, in a LATER task — a separate window.
                      (rf/dispatch-sync [:rbhcd/inc] {:frame frame-kw})
                      (await-microtask)))
             (.then (fn [_]
                      (settle-react!)
                      (testing "a real host yield separates batches"
                        (is (= (+ 2 base-rev) (reactive/revision cell))
                            "TWO revision advances — one per checkpoint")
                        (is (= (+ 2 body-runs) (:body-runs @evidence))
                            "TWO view body invocations")
                        (is (= (+ 2 root-commits) (:root-commits @evidence))
                            "TWO REAL React root commits — yield-separated
                             drains render separately (guarantee 4)")
                        (is (= "2" (.-textContent (.querySelector c ".leaf")))))
                      (react-dom/flushSync #(ui/unmount! root))
                      (done)))))))))
