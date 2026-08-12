(ns re-frame.bench.hicasso.lane-window-dom-cljs-test
  "`lane/verified-write!`'s two window shapes, and the claim that neither
  one serves both scheduler families (rf2-pq7d8).

  This is a CORRECTNESS test of the instrument, not a benchmark: nothing
  here reads a clock for a published figure and no assertion depends on
  how long anything took. What it pins is the ORDER of operations inside
  a measured window, which is what the recorded fault turned on.

  ## Why fake substrates rather than Reagent and reagent-slim

  The fault is not about either library. It is about WHICH QUEUE an arm's
  render work sits on relative to the queue the harness yields to, and
  that is a two-line property. Two fakes model the two families exactly
  and deterministically:

    [[queued-notification-arm]]  the notification reaches the render
        queue ON A MICROTASK and `force!` drains that queue synchronously
        — stock Reagent's family, whose `reagent.impl.batching` queue is
        `requestAnimationFrame`-scheduled and so is still full a
        microtask later. The harness's one microtask yield is
        LOAD-BEARING here.

    [[microtask-scheduled-arm]]  the write fills the render queue
        synchronously, and the substrate's OWN microtask drain then takes
        that work back out of the queue and hands it to React outside any
        `flushSync` boundary, where React parks it — reagent-slim's
        family, whose `reagent2.impl.batching` is microtask-based. The
        same yield is FATAL.

  Using the real substrates would need a bundle carrying both engines and
  would prove the same two-line property more slowly and less legibly.
  The real-substrate evidence is not re-derived here because it already
  exists: rf2-z3vlz's standalone rig, written up in
  `docs/design/hicasso/studio/slim-non-reactive-arm-diagnosis.md`.

  ## What would have caught the recorded fault

  [[microtask-arm-is-unverified-under-the-yielding-window]] is that test.
  It goes red on the window `lane/verified-write!` had before rf2-pq7d8,
  and for the right reason: `N unverified of N` against a DOM that never
  changed. The row that fault produced read `0.16–0.50x` the floor — a
  precise wrong number, and the sixteenth of its kind on these surfaces.

  Runtime: these are DOM claims. The file carries the `-dom-cljs-test`
  suffix so `:browser-test` runs it for real, and every test degrades to
  a stated skip under `:node-test`, which is the posture the other `*-dom`
  suites keep."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [re-frame.bench.hicasso.lane :as lane]))

(def ^:private off-browser
  "no DOM on this runtime — the claim is a DOM read-back inside a measured
  window, and :browser-test is where it is asked")

;; ---------------------------------------------------------------------------
;; A page with one cell in it
;; ---------------------------------------------------------------------------

(defn- cell-container!
  "A container holding one `data-i=\"0\"` cell reading `\"old\"` — the
  smallest page `lane/text-at` can probe."
  []
  (let [c (js/document.createElement "div")]
    (set! (.-innerHTML c) "<span data-i=\"0\">old</span>")
    (.appendChild js/document.body c)
    c))

(defn- grid-container!
  "A container holding `n` cells, all reading `\"old\"` — the smallest page
  a BATCHED window can write `k` distinct cells into."
  [n]
  (let [c (js/document.createElement "div")]
    (set! (.-innerHTML c)
          (apply str (map (fn [i] (str "<span data-i=\"" i "\">old</span>")) (range n))))
    (.appendChild js/document.body c)
    c))

(defn- commit!
  "Put `v` on the page. The ONLY way anything here reaches the DOM, so a
  window that does not reach it leaves the cell reading `\"old\"`."
  ([container v] (commit! container 0 v))
  ([container i v]
   (set! (.-textContent (.querySelector container (str "[data-i=\"" i "\"]"))) (str v))))

;; ---------------------------------------------------------------------------
;; The two scheduler families, as fakes
;; ---------------------------------------------------------------------------

(defn- queued-notification-arm
  "An arm whose notification reaches its render queue ON A MICROTASK.

  `force!` drains whatever is in the queue when it runs, so a window that
  drains immediately after the write finds it EMPTY and commits nothing.
  The microtask yield is what makes this arm measurable at all."
  [container]
  (let [queue (atom nil)]
    {:id     :queued-notification
     :write! (fn [_i v]
               (.then (js/Promise.resolve nil) (fn [_] (reset! queue v)))
               nil)
     :force! (fn [] (when-some [v @queue] (commit! container v) (reset! queue nil)))}))

(defn- microtask-scheduled-arm
  "An arm whose OWN render queue is drained on the microtask queue.

  The write fills the queue synchronously, so a drain that runs straight
  away finds the work and commits it. But the substrate's own microtask
  drain, if it gets to run first, takes the work back OUT of the queue and
  hands it to React outside any `flushSync` boundary, where it is PARKED
  and never reaches the DOM inside this window. That is the recorded
  fault, and `:parked` is where the lost commit can be seen sitting."
  [container]
  (let [queue  (atom nil)
        parked (atom nil)]
    {:id        :microtask-scheduled
     :scheduler :microtask
     :parked    parked
     :write!    (fn [_i v]
                  (reset! queue v)
                  (.then (js/Promise.resolve nil)
                         (fn [_] (when-some [q @queue]
                                   (reset! parked q)
                                   (reset! queue nil))))
                  nil)
     :force!    (fn [] (when-some [v @queue] (commit! container v) (reset! queue nil)))}))

(defn- write-once!
  "One `lane/verified-write!` against `arm`; answers a promise of
  `[result tally-value]`."
  [arm container]
  (let [t (lane/tally)]
    (-> (lane/verified-write! t {:arm arm :container container} 0 "new" [0])
        (.then (fn [r] [r (lane/tally-value t)])))))

;; ---------------------------------------------------------------------------
;; The microtask family — the fault, and the repair
;; ---------------------------------------------------------------------------

(deftest microtask-arm-is-unverified-under-the-yielding-window
  (testing "a microtask-scheduled arm measured through the YIELDING window
           loses its commit, and the DOM read-back is what says so"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c   (cell-container!)
              arm (dissoc (microtask-scheduled-arm c) :scheduler)]
          (-> (write-once! arm c)
              (.then (fn [[r tally]]
                       (is (false? (:ok? r))
                           "the yielding window must NOT verify a microtask-scheduled arm")
                       (is (= {:writes 1 :unverified 1} tally)
                           "and the tally must publish it as 1 unverified of 1")
                       ;; The page is untouched, so the milliseconds this
                       ;; window measured are a measurement of nothing.
                       (is (= "old" (lane/text-at c 0))
                           "the cell must still read the OLD value")
                       ;; The commit is not lost, merely parked: a LATE DOM,
                       ;; not a dead one, which is why the clock reading
                       ;; looked plausible.
                       (is (= "new" @(:parked arm))
                           "the commit must be sitting parked outside the window")))
              ;; Reports and RELEASES; it never finishes (rf2-fyba). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time. The container detach both
              ;; arms duplicated rides the single trailing step instead — written
              ;; once, still run once per path, and every DOM read is upstream
              ;; of it. Every chain in this file has the same shape.
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

(deftest microtask-arm-verifies-under-its-own-window
  (testing "declaring :scheduler :microtask puts NOTHING between the write
           and the drain, so the commit lands inside the window"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c   (cell-container!)
              arm (microtask-scheduled-arm c)]
          (-> (write-once! arm c)
              (.then (fn [[r tally]]
                       (is (true? (:ok? r)) "the write-then-drain window must verify")
                       (is (= {:writes 1 :unverified 0} tally) "0 unverified of 1")
                       (is (= "new" (lane/text-at c 0))
                           "and the page must actually carry the written value")
                       (is (nil? @(:parked arm))
                           "nothing was parked — the drain got there first")
                       ;; 0.0 and it MEANS it: there is no harness turn
                       ;; inside this window to price.
                       (is (zero? (:gap-ms r)) ":gap-ms must be 0.0, not merely small")))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

;; ---------------------------------------------------------------------------
;; The other family — and therefore why ONE shape cannot serve both
;; ---------------------------------------------------------------------------

(deftest queued-notification-arm-verifies-under-the-yielding-window
  (testing "the yield is LOAD-BEARING for an arm whose notification is
           queued somewhere other than the queue the harness yields to"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c   (cell-container!)
              arm (queued-notification-arm c)]
          (-> (write-once! arm c)
              (.then (fn [[r tally]]
                       (is (true? (:ok? r)) "the yielding window must verify this family")
                       (is (= {:writes 1 :unverified 0} tally) "0 unverified of 1")
                       (is (= "new" (lane/text-at c 0)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

(deftest queued-notification-arm-is-unverified-under-the-microtask-window
  (testing "and the write-then-drain window BREAKS that same arm — so
           neither shape is universally right, which is the finding"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c   (cell-container!)
              arm (assoc (queued-notification-arm c) :scheduler :microtask)]
          (-> (write-once! arm c)
              (.then (fn [[r tally]]
                       (is (false? (:ok? r))
                           "draining immediately must find this arm's queue empty")
                       (is (= {:writes 1 :unverified 1} tally) "1 unverified of 1")
                       (is (= "old" (lane/text-at c 0)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

;; ---------------------------------------------------------------------------
;; The default is the window every published P0 row was taken through
;; ---------------------------------------------------------------------------

(deftest an-arm-that-declares-no-scheduler-keeps-the-yielding-window
  (testing "an arm with no :scheduler key is measured through exactly the
           window it was measured through before rf2-pq7d8 — which is what
           makes this change additive and leaves every published P0 row
           standing"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c    (cell-container!)
              seen (atom [])
              ;; The write queues a microtask that records the fact. If the
              ;; window still yields ONE microtask before draining, that
              ;; microtask has run by the time `force!` is called.
              arm  {:id     :no-declaration
                    :write! (fn [_i v]
                              (.then (js/Promise.resolve nil)
                                     (fn [_] (swap! seen conj :microtask-ran)))
                              (swap! seen conj :wrote)
                              (commit! c v)
                              nil)
                    :force! (fn [] (swap! seen conj :forced))}]
          (is (not (contains? arm :scheduler)) "the arm declares no scheduler")
          (-> (write-once! arm c)
              (.then (fn [[r tally]]
                       (is (= [:wrote :microtask-ran :forced] @seen)
                           "exactly one microtask must sit between the write and the drain")
                       (is (true? (:ok? r)))
                       (is (= {:writes 1 :unverified 0} tally))
                       ;; The gap is a real, priced turn on this path: the
                       ;; asymmetry against the microtask window is a NUMBER
                       ;; rather than an assurance.
                       (is (number? (:gap-ms r)) ":gap-ms is measured, not asserted")))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

;; ---------------------------------------------------------------------------
;; The batched window — k operations, ONE clock (rf2-zb3qg)
;; ---------------------------------------------------------------------------

(defn- recording-arm
  "An arm that writes straight to the page and records the ORDER of its
  turns, so a test can assert how many harness microtasks a window holds.

  The write queues a microtask that records the fact; if the window yields
  before draining, that microtask has run by the time `force!` is called."
  [container seen]
  {:id     :recording
   :write! (fn [i v]
             (.then (js/Promise.resolve nil) (fn [_] (swap! seen conj :microtask-ran)))
             (swap! seen conj :wrote)
             (commit! container i v)
             nil)
   :force! (fn [] (swap! seen conj :forced))})

(defn- ops-for
  "`k` ops, each its OWN cell and its OWN value — the caller obligation
  `lane/verified-writes!` states, so no read-back can be satisfied by a
  neighbour's commit."
  [k]
  (mapv (fn [i] [i (str "v" i) [i]]) (range k)))

(deftest batched-yielding-window-holds-exactly-one-yield-per-operation
  (testing "k ops under one clock keep the fixed yield PER OPERATION — the
           window holds k harness turns, never 2k and never one for the
           whole batch, which is what makes a batched figure comparable
           with the unbatched one it supersedes"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [k    3
              c    (grid-container! k)
              seen (atom [])
              t    (lane/tally)]
          (-> (lane/verified-writes! t {:arm (recording-arm c seen) :container c} (ops-for k))
              (.then (fn [r]
                       (is (= [:wrote :microtask-ran :forced
                               :wrote :microtask-ran :forced
                               :wrote :microtask-ran :forced]
                              @seen)
                           "exactly one microtask sits between each write and its own drain")
                       (is (true? (:ok? r)))
                       (is (= [true true true] (:oks r)) "one flag per op")
                       ;; The tally counts OPERATIONS, not samples: a batched
                       ;; row that banked one write per window would publish
                       ;; `N unverified of M` with an M a tenth of the truth.
                       (is (= {:writes k :unverified 0} (lane/tally-value t))
                           "the tally banks every op in the batch")))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

(deftest a-batch-of-one-is-the-unbatched-window-exactly
  (testing "k = 1 is the pre-batch window, turn for turn — this is what lets
           the BROAD row pass a batch of one and not move, while the narrow
           row batches"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [c    (grid-container! 1)
              seen (atom [])
              t    (lane/tally)]
          (-> (lane/verified-writes! t {:arm (recording-arm c seen) :container c} (ops-for 1))
              (.then (fn [r]
                       (is (= [:wrote :microtask-ran :forced] @seen)
                           "one write, one yield, one drain — the unbatched shape")
                       (is (true? (:ok? r)))
                       (is (= {:writes 1 :unverified 0} (lane/tally-value t)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

(deftest a-batched-microtask-arm-puts-nothing-between-write-and-drain
  (testing "the microtask family's batched window is one synchronous run —
           not even a microtask separates a drain from the next write"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [k    3
              c    (grid-container! k)
              seen (atom [])
              t    (lane/tally)
              arm  (assoc (recording-arm c seen) :scheduler :microtask)]
          (-> (lane/verified-writes! t {:arm arm :container c} (ops-for k))
              (.then (fn [r]
                       ;; The first 2k entries are the WINDOW, and they are
                       ;; write/drain/write/drain with nothing in between. The
                       ;; arm's own queued microtasks appear only AFTER all of
                       ;; them, which is the claim stated from the other side:
                       ;; the batch is one synchronous run, so no microtask —
                       ;; not even one the arm queued itself — can interleave
                       ;; with it. Under the yielding shape those same three
                       ;; turns land INSIDE the window, one per operation.
                       (is (= [:wrote :forced :wrote :forced :wrote :forced]
                              (vec (take (* 2 k) @seen)))
                           "no turn of any kind between a write and its drain")
                       (is (= [:microtask-ran :microtask-ran :microtask-ran]
                              (vec (drop (* 2 k) @seen)))
                           "and the arm's own microtasks ran only once the window had closed")
                       (is (zero? (:gap-ms r)) ":gap-ms must be 0.0, not merely small")
                       (is (= {:writes k :unverified 0} (lane/tally-value t)))))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))

(deftest a-batch-reports-every-op-that-missed-the-dom
  (testing "an arm whose commits never land reads `k unverified of k`, not
           one — the count a published row quotes is per OPERATION"
    (async done
      (if-not (lane/browser?)
        (do (is true off-browser) (done))
        (let [k   3
              c   (grid-container! k)
              t   (lane/tally)
              ;; Writes nothing to the page at all: the window's milliseconds
              ;; are real and they measure nothing.
              arm {:id :never-commits :write! (fn [_i _v] nil) :force! (fn [] nil)}]
          (-> (lane/verified-writes! t {:arm arm :container c} (ops-for k))
              (.then (fn [r]
                       (is (false? (:ok? r)))
                       (is (= [false false false] (:oks r)))
                       (is (= {:writes k :unverified k} (lane/tally-value t))
                           "k unverified of k")))
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (.remove c) (done)))))))))
