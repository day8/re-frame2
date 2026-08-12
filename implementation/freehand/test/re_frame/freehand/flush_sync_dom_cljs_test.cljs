(ns re-frame.freehand.flush-sync-dom-cljs-test
  "rf2-w2m25 — a Freehand `v/sub` read repaints inside `react-dom/flushSync`.

  ## The defect

  Found by the Freehand-vs-Reagent measurement (rf2-dq20a), on the UIx
  adapter, in a real browser. A mounted Freehand view whose body reads
  `(v/sub …)` was NOT repainted by a state write made inside
  `react-dom/flushSync`. The store was written and the subscription
  recomputed, but `cell/mark-dirty!` schedules no React work at all — it
  marks the cell and arms a MICROTASK — and `flushSync` returns as soon
  as the work scheduled inside its callback has committed. So it
  committed nothing, and said nothing about it.

  Four ways of driving one write were measured on a mounted grid, and
  only the first failed:

  | how the write was driven | did the DOM change? |
  |---|---|
  | `flushSync(write)` | **no** |
  | `write` then a microtask then `flushSync(noop)` | yes |
  | `write` then `setTimeout 0` | yes |
  | `write` then `requestAnimationFrame` | yes |

  All four are asserted below, because a fix verified against fewer than
  the defect was measured against is not verified — the three that always
  worked are what say the repair did not simply move the hole.

  ## Why it mattered beyond a bench

  `flushSync` is what a consumer reaches for when the NEXT LINE reads the
  DOM: measuring layout, moving focus, setting a caret, handing off to
  imperative third-party code, or asserting in a test. A substrate that
  silently does nothing there is a trap, and the silence is the worst
  part — the write lands, the subscription recomputes, and only a DOM
  read-back inside the same turn can tell you the page never moved.

  ## What the fix does, and the row that proves it did not overreach

  The microtask is NOT abolished. Notifying synchronously from a mark
  would recompute and re-render inside the source write — the trap Spec
  006 invariant 6 forbids — so instead the window gained a SECOND closer
  that React can see (`re-frame.freehand.checkpoint`), armed by the same
  first mark and running the same idempotent `cell/flush!`. Inside
  `flushSync` it takes the sync lane and closes the window before the
  flush returns; outside it, the microtask closer still gets there first
  and nothing about ordinary batching changes.

  That second claim is the adversarial one, and
  [[an-ordinary-write-outside-flushsync-still-batches]] is where it is
  made: three writes in one task must still leave the DOM untouched until
  the microtask checkpoint, and must still cost exactly ONE render.
  Proving the batch survived matters as much as proving the flush works.

  Rides the browser lane through its `-dom-cljs-test` suffix; under the
  node suites there is no DOM to mount and each row says so rather than
  passing quietly.

  The adapter is UIx, which is the configuration the defect was measured
  under, and it is also the stronger claim: the repair lives in the
  ViewCell registry, not in an adapter, so a Freehand root repaints
  correctly under a substrate that has never heard of Freehand."
  (:require ["react-dom" :as react-dom]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true}))

(defn- leave-act-environment!
  "Every row here is about REAL host scheduling — a sync lane, a
  microtask, a task, a frame. Inside React's `act` environment React
  diverts that work to act's own queue, so an assertion made there would
  be about act's drain order rather than about the substrate."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture))
             (leave-act-environment!))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

;; ---------------------------------------------------------------------------
;; The application under test
;; ---------------------------------------------------------------------------

(def ^:private renders
  "How many times the view body has run. A render counter in a view body
  is a side effect, and deliberate: it is the only way to say `ONE
  render` about a batch, and nothing here runs under StrictMode's
  double-invoke."
  (atom 0))

(v/defview counter
  "The smallest view that can show the defect: one reactive read, whose
  value is the whole of the DOM under test."
  [_]
  (swap! renders inc)
  [:div.counter [:output.count (str (v/sub [::count]))]])

(v/defview app [_] [:main#fh-flush-sync [counter {}]])

(defn- register! []
  (rf/reg-sub ::count (fn [db _] (:count db)))
  (rf/reg-event ::set (fn [{:keys [db]} [_ n]] {:db (assoc db :count n)})))

(defn- seed! [frame-id db]
  (live-frame/make-frame {:id frame-id})
  (frame/replace-app-db! frame-id db)
  frame-id)

(defn- text [container]
  (some-> (.querySelector container ".count") .-textContent))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- mount!
  "Mount inside `flushSync` so the page is on screen when this returns —
  the same shape the B6 measurement mounts its Freehand arms with, and
  the one that keeps every row below free of an initial yield."
  [container frame-id disambiguator]
  (react-dom/flushSync
    (fn [] (v/mount [app {}] container {:frame         frame-id
                                        :disambiguator disambiguator}))))

;; The write the defect was measured with — an out-of-band store write,
;; not a dispatch, so nothing in the router's drain can be credited with
;; the repair. `dispatch-sync` gets its own row below.
(defn- write! [frame-id n]
  (frame/replace-app-db! frame-id {:count n}))

(defn- microtask [] (js/Promise.resolve nil))

(defn- macrotask []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

(defn- animation-frame []
  (js/Promise. (fn [resolve] (js/requestAnimationFrame #(resolve nil)))))

(defn- teardown! [mounted container]
  (react-dom/flushSync (fn [] (v/unmount! mounted)))
  (.remove container)
  nil)

;; ===========================================================================
;; Case 1 — the defect itself
;; ===========================================================================

(deftest a-write-inside-flushsync-repaints-before-it-returns
  (testing "The row rf2-w2m25 exists for. The write moves app-db INSIDE
            `react-dom/flushSync`; the subscription recomputes and the
            ViewCell is marked; and the DOM must already carry the new
            value on the very next line, with no yield of any kind between
            the flush returning and the read. Before the fix this read the
            OLD value and nothing said so."
    (if-not (browser?)
      (skip! "the browser job runs the flushSync assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-write {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-1)]
          (is (= "0" (text container)) "the page mounted showing the seeded value")
          (react-dom/flushSync (fn [] (write! fid 4242)))
          (is (= 4242 (:count (frame/frame-app-db-value fid)))
              "the store really was written inside the flush")
          (is (= "4242" (text container))
              "and the DOM already shows it — NO microtask, NO act queue, NO
               yield between flushSync returning and this read")
          (is (zero? (cell/pending-count))
              "the pending window is quiescent, not merely drained-and-refilled")
          (teardown! mounted container)
          (done))))))

;; ===========================================================================
;; Cases 2, 3, 4 — the three that always worked, and still must
;; ===========================================================================

(deftest a-write-then-a-microtask-then-an-empty-flushsync-repaints
  (testing "Case 2 of the measured four: the workaround the B6 update row
            had to be shaped around. The write's own microtask closes the
            window and the empty `flushSync` commits it. It must keep
            working — a fix that moved the notification onto the sync lane
            and off the microtask would break exactly this."
    (if-not (browser?)
      (skip! "the browser job runs the microtask assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-microtask {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-2)]
          (is (= "0" (text container)))
          (write! fid 7)
          (-> (microtask)
              (.then (fn [_]
                       (react-dom/flushSync (fn [] nil))
                       (is (= "7" (text container))
                           "one yielded microtask plus an empty forced drain
                            later, the page is current")))
              (.catch (fn [e]
                        (is false (str "microtask arm rejected: " e))
                        nil))
              (.then (fn [_]
                       (teardown! mounted container)
                       (done)))))))))

(deftest a-write-then-a-task-repaints
  (testing "Case 3: nothing forces anything. The write is made and one
            browser TASK is yielded, which is strictly later than the
            microtask checkpoint the window closes at, so the page must be
            current with no flush of any kind involved."
    (if-not (browser?)
      (skip! "the browser job runs the task assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-task {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-3)]
          (is (= "0" (text container)))
          (write! fid 11)
          (-> (macrotask)
              (.then (fn [_]
                       (is (= "11" (text container))
                           "a yielded task is enough on its own — the automatic
                            repaint channel, unforced")))
              (.catch (fn [e]
                        (is false (str "task arm rejected: " e))
                        nil))
              (.then (fn [_]
                       (teardown! mounted container)
                       (done)))))))))

(deftest a-write-then-an-animation-frame-repaints
  (testing "Case 4: the same claim taken at the paint boundary. The
            window's microtask checkpoint runs BEFORE the next paint, so a
            page read inside `requestAnimationFrame` can never show a torn
            frame."
    (if-not (browser?)
      (skip! "the browser job runs the animation-frame assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-raf {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-4)]
          (is (= "0" (text container)))
          (write! fid 23)
          (-> (animation-frame)
              (.then (fn [_]
                       (is (= "23" (text container))
                           "current before the frame the browser was about to
                            paint")))
              (.catch (fn [e]
                        (is false (str "animation-frame arm rejected: " e))
                        nil))
              (.then (fn [_]
                       (teardown! mounted container)
                       (done)))))))))

;; ===========================================================================
;; The adversarial row — batching is UNCHANGED
;; ===========================================================================

(deftest an-ordinary-write-outside-flushsync-still-batches
  (testing "The half of the fix that is easiest to break and hardest to
            notice. Three writes in ONE task, with no flush anywhere, must
            behave exactly as they did before: nothing repaints while the
            stack is still unwinding, the pending window stays OPEN across
            all three, and the whole batch costs exactly ONE render at the
            microtask checkpoint — not three, and not one per write.

            A repair that made a mark notify synchronously would pass every
            row above and fail this one, which is why it is here."
    (if-not (browser?)
      (skip! "the browser job runs the batching assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-batch {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-batch)]
          (is (= "0" (text container)))
          (let [before @renders]
            (write! fid 1)
            (write! fid 2)
            (write! fid 3)
            (is (= "0" (text container))
                "NOTHING repainted while the stack was still unwinding — three
                 source writes scheduled work, they did not perform it")
            (is (pos? (cell/pending-count))
                "and the pending window is still open, holding all three")
            (is (= before @renders)
                "so the view body has not run again either")
            (-> (microtask)
                (.then (fn [_]
                         (is (zero? (cell/pending-count))
                             "ONE microtask checkpoint later the window is
                              closed — all three writes, one close")
                         (macrotask)))
                (.then (fn [_]
                         (is (= "3" (text container))
                             "and the page shows the LAST write")
                         (is (= 1 (- @renders before))
                             (str "with the three writes coalesced into exactly
                                   ONE render — " (- @renders before)
                                  " observed"))
                         (macrotask)))
                (.then (fn [_]
                         (is (= 1 (- @renders before))
                             "and nothing rendered afterwards either — the
                              host-visible closer adds no second pass over the
                              application's own tree")
                         (is (= "3" (text container)))))
                (.catch (fn [e]
                          (is false (str "batching arm rejected: " e))
                          nil))
                (.then (fn [_]
                         (teardown! mounted container)
                         (done))))))))))

;; ===========================================================================
;; The other doors the bead named
;; ===========================================================================

(deftest a-dispatch-sync-inside-flushsync-repaints-before-it-returns
  (testing "The bead measured three write doors and none of them committed
            inside the flush. `frame/replace-app-db!` is the row above;
            this is `rf/dispatch-sync`, which runs a whole
            run-to-completion drain inside the callback. The drain still
            cannot be split — the window cannot close while the stack is
            unwinding — but it must be closed by the time the flush
            returns."
    (if-not (browser?)
      (skip! "the browser job runs the dispatch-sync assertions")
      (async done
        (register!)
        (let [fid       (seed! ::flush-sync-dispatch {:count 0})
              container (host-node!)
              mounted   (mount! container fid :case-dispatch)]
          (is (= "0" (text container)))
          (react-dom/flushSync
            (fn [] (rf/dispatch-sync [::set 99] {:frame fid})))
          (is (= "99" (text container))
              "the event drained and the page committed, both inside the flush")
          (teardown! mounted container)
          (done))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-proof-is-not-vacuous
  (testing "Every row above rests on the installed adapter really being
            UIx — the configuration the defect was measured under, and a
            substrate that knows nothing about Freehand — and on the view
            really being a reactive declaration. Without this row a suite
            that had quietly run on Freehand's own adapter, or on a view
            whose body read nothing, would be green for the wrong reason."
    (is (= :rf.adapter/uix (rf/current-adapter))
        "the fixture installed the UIx adapter, not Freehand's own")
    (is (v/view? counter) "the subject is a declared view")
    (is (= :interpreted (:lowering (v/describe counter)))
        "on the interpreted paved path")))
