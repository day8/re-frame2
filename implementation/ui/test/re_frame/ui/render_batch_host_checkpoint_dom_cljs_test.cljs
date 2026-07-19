(ns re-frame.ui.render-batch-host-checkpoint-dom-cljs-test
  "rf2-vxgfnd.166 — the render-batch boundary on the REAL React 19 + real DOM
  path, counting REAL root commits.

  The headless sibling (`render-batch-host-checkpoint-cljs-test`) pins the
  scheduler's pending window across real router drains. This namespace closes
  the loop on the host that actually owns the checkpoint: a mounted root, a
  React `Profiler` counting genuine commits, and a genuine microtask yield.

  It proves the two halves that only a real host can distinguish:

    - A COMPLETED DRAIN CLOSES NOTHING. A full `dispatch-sync!` drain returns
      with the cell still pending, the revision unmoved and React having
      committed nothing. Only the following host checkpoint advances it. This
      is the direct falsification of the retired per-drain rule.
    - HOST YIELD → TWO BATCHES. Two drains separated by a real microtask yield
      produce two revision advances and two real root commits (guarantee 4).

  On sharing: the ruled contract says drains finishing before one checkpoint
  MAY share a batch, and the headless sibling pins a real shared batch. This
  namespace deliberately does NOT assert sharing, because it was measured not
  to hold universally here — a second `dispatch-sync!` in the same stack
  advances the first drain's pending mark before making its own. Asserting the
  permission as though it were the guarantee is exactly the over-claim this
  bead exists to remove.

  ## What advances that pending mark (rf2-kahkr — traced, not inferred)

  The mechanism is now identified, and it is NOT a hidden synchronous flush.
  Three back-to-back `dispatch-sync!` calls were instrumented on this exact
  fixture (stack capture on `flush-scope!` and on `advance-revision!`, with
  console markers bracketing each call). Across 3 browser runs, identically:

    - the revision advanced 0 -> 1 -> 2, one advance per call after the first;
    - EVERY `flush-scope!` stack was the same three frames —
      `flush-scope!` <- `flush-pending!` <- the `schedule-flush!` microtask
      closure — terminating there, with NO router or dispatch frame above it.
      That is a host job-queue entry point, i.e. the ordinary armed microtask;
    - `advance-revision!` (the `commit*` / step-8 route) NEVER fired, so the
      advance is scheduler phase 1, not a React layout-commit correction;
    - the flush was logged BETWEEN the `begin` and `end` markers of the second
      and third calls — i.e. a microtask checkpoint ran DURING `dispatch-sync!`.

  The control that settles it: a plain `js/queueMicrotask` enqueued by the
  fixture itself, immediately before the second call, ALSO ran between that
  call's begin and end markers. Nothing in re-frame is involved in that
  callback, so the checkpoint is genuinely the host's.

  Conclusion: `dispatch-sync!` is not microtask-atomic on the mounted path — it
  yields, and the ordinary checkpoint therefore lands mid-call and flushes the
  previous drain's mark. So there is nothing here to remove: the scheduler is
  behaving exactly as specified, and the earlier `flush-render!` /
  `dispatch-committed!` / late-bound-hook suspects are correctly ruled out.

  Whether `dispatch-sync!` SHOULD yield is a ROUTER question living in
  `implementation/core`, and it is a ruling, not a measurement. It is left open
  deliberately. Until it is ruled, no fixture here asserts sharing OR
  non-sharing across two `dispatch-sync!` calls: the first would assert a
  permission as a guarantee, the second would freeze possibly-unintended
  router behaviour into a contract. Both are over-claims.

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
  ;; MEASURED SCOPE NOTE (rf2-vxgfnd.166; mechanism traced by rf2-kahkr). The
  ;; ruled contract says drains finishing before one checkpoint MAY share a
  ;; batch; it does not promise they always will. On this mounted path they do
  ;; not always: a SECOND `dispatch-sync!` in the same stack was measured to
  ;; advance the first drain's pending mark before making its own. So this
  ;; fixture asserts the guarantee (a finished drain leaves the window open)
  ;; rather than the permission. The headless sibling pins an actual shared
  ;; batch, where the scheduler is observed without React in the loop.
  ;;
  ;; That advance is now traced: it is the ordinary armed `schedule-flush!`
  ;; microtask, landing mid-call because `dispatch-sync!` yields to the host
  ;; microtask queue. See the namespace docstring for the instrumentation, the
  ;; control microtask that identifies it, and why nothing asserts sharing (or
  ;; non-sharing) across two `dispatch-sync!` calls pending a router ruling.
  ;;
  ;; This test itself performs exactly ONE drain, so it is unaffected either way.
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
