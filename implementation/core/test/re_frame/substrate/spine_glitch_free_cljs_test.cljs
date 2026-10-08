(ns re-frame.substrate.spine-glitch-free-cljs-test
  "The substrate spine's derived-value epoch scheduler (Spec 006
  §Invalidation algorithm): one recompute and at most one notification per
  dirty derived value per write, and a failure anywhere in a drain neither
  strands the rest of the drain nor gets swallowed.

  A naive spine that recomputes and notifies inline per source watch would run
  a multi-input sub body once per changed input and cascade that waste down the
  graph. The spine's derived value is pull-based, so notified values are always
  coherent; the property under test is the absence of redundant recomputes and
  notifications, read off per-derived counters.

  The graphs are built directly over spine state containers and plain atoms,
  with every derived value on one scheduler, as `make-react-spine` wires them.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; Derived values are LAZY, so each test derefs once to establish prev-state
;; before it watches, as the sub-cache does on subscribe.

(defn- build-graph []
  (let [scheduler (rf.substrate.spine/make-scheduler)]
    {:make-derived (rf.substrate.spine/make-derived-value-fn "rf-glitch-" scheduler)
     :replace!     (rf.substrate.spine/make-replace-container-fn scheduler)
     :root         (rf.substrate.spine/make-state-container {:a 1 :b 10})}))

(defn- direct-derived-fn
  "A `make-derived` on a fresh scheduler, for graphs over a raw atom written
  directly (depth zero, no `replace-container!`)."
  []
  (rf.substrate.spine/make-derived-value-fn "rf-direct-" (rf.substrate.spine/make-scheduler)))

(defn- thrown-by
  "What `thunk` threw, or ::none. Catches falsey throws by presence."
  [thunk]
  (try (thunk) ::none (catch :default e e)))

;; ---- laziness and propagation ---------------------------------------------

(deftest make-derived-value-is-lazy-no-compute-at-construction
  ;; Seeding prev-state eagerly would run the sub body (and emit :rf.sub/run)
  ;; at subscribe time rather than deref time.
  (let [{:keys [make-derived root]} (build-graph)
        runs   (atom 0)
        d      (make-derived [root] (fn [db] (swap! runs inc) (:a db)))
        before @runs]
    (is (= [0 1 1] [before @d @runs]))))

(deftest layer-1-value-equal-write-does-not-notify
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1    (make-derived [root] (fn [db] (:a db)))
        notes (atom 0)]
    @l1
    (add-watch l1 :w (fn [_ _ _ _] (swap! notes inc)))
    (replace! root {:a 1 :b 99})
    (is (= 0 @notes))))

(deftest layer-2-multi-input-recomputes-once-and-notifies-once
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1a  (make-derived [root] (fn [db] (:a db)))
        l1b  (make-derived [root] (fn [db] (:b db)))
        runs (atom 0)
        l2   (make-derived [l1a l1b] (fn [a b] (swap! runs inc) (+ a b)))
        seen (atom [])]
    (add-watch l2 :w (fn [_ _ _ nu] (swap! seen conj nu)))
    @l2
    (reset! runs 0)
    (replace! root {:a 2 :b 20})
    (is (= [1 [22]] [@runs @seen]))))

(deftest layer-3-cascade-each-tier-notifies-once-glitch-free
  ;; Recompute counts are not asserted here: layer-3's pull-based recompute
  ;; legitimately derefs layer-2.
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1a     (make-derived [root] (fn [db] (:a db)))
        l1b     (make-derived [root] (fn [db] (:b db)))
        l2      (make-derived [l1a l1b] (fn [a b] (+ a b)))
        l3      (make-derived [l2] (fn [s] (* 100 s)))
        l2-seen (atom [])
        l3-seen (atom [])]
    (add-watch l2 :w (fn [_ _ _ nu] (swap! l2-seen conj nu)))
    (add-watch l3 :w (fn [_ _ _ nu] (swap! l3-seen conj nu)))
    @l2
    @l3
    (replace! root {:a 2 :b 20})
    (is (= [[22] [2200]] [@l2-seen @l3-seen]))))

(deftest disposed-layer-2-flush-mid-cascade-does-not-recompute
  ;; l1a's flush marks l2 dirty and queues its flush; a watcher on l1b then
  ;; disposes l2 before that queued flush drains. The queued flush cannot be
  ;; dequeued, so `flush!` itself must short-circuit on the disposed guard.
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1a  (make-derived [root] (fn [db] (:a db)))
        l1b  (make-derived [root] (fn [db] (:b db)))
        runs (atom 0)
        l2   (make-derived [l1a l1b] (fn [a b] (swap! runs inc) (+ a b)))]
    @l2
    (reset! runs 0)
    (add-watch l1b :dispose-l2 (fn [_ _ _ _] (rf.disposable/-dispose l2)))
    (replace! root {:a 2 :b 20})
    (is (= 0 @runs))))

;; ---- a throwing recompute -------------------------------------------------

(deftest throwing-thunk-mid-drain-does-not-strand-downstream
  ;; The thrower is constructed first, so its flush drains first and the
  ;; survivor is the queued entry a rethrowing drain would strand: its flush
  ;; never run and its dirty? guard stuck, so a later change could not re-mark it.
  (let [{:keys [make-derived replace! root]} (build-graph)
        boom?    (atom false)
        thrower  (make-derived [root] (fn [db] (when @boom? (throw (js/Error. "boom"))) (:a db)))
        survivor (make-derived [root] (fn [db] (:b db)))
        notes    (atom [])]
    @thrower
    @survivor
    (add-watch survivor :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
    (reset! boom? true)
    (is (= ["boom" [[10 20]]]
           [(.-message (thrown-by #(replace! root {:a 99 :b 20}))) @notes]))
    (reset! boom? false)
    (replace! root {:a 99 :b 30})
    (is (= [[10 20] [20 30]] @notes))))

(deftest throwing-thunk-clears-its-own-dirty-guard
  (let [{:keys [make-derived replace! root]} (build-graph)
        boom?   (atom false)
        thrower (make-derived [root] (fn [db] (when @boom? (throw (js/Error. "boom"))) (:a db)))
        notes   (atom [])]
    @thrower
    (add-watch thrower :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
    (reset! boom? true)
    (thrown-by #(replace! root {:a 2 :b 10}))
    (reset! boom? false)
    (replace! root {:a 3 :b 10})
    (is (= [[1 3]] @notes))))

;; ---- direct source writes (depth zero) ------------------------------------

(deftest layer-1-direct-source-write-still-flushes
  ;; A bare `reset!` outside replace-container! (test or tooling) drains at once.
  (let [src   (atom 5)
        l1    ((direct-derived-fn) [src] (fn [x] (* x 10)))
        notes (atom [])]
    @l1
    (add-watch l1 :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
    (reset! src 6)
    (is (= [[50 60]] @notes))))

(deftest direct-source-sole-dependent-falsey-throw-surfaces-by-presence
  (doseq [thrown-val [false nil]]
    (let [src   (atom 1)
          boom? (atom false)
          d     ((direct-derived-fn) [src] (fn [x] (when @boom? (throw thrown-val)) x))]
      @d
      (reset! boom? true)
      (is (identical? thrown-val (thrown-by #(reset! src 2))) (pr-str thrown-val)))))

(deftest direct-source-three-plus-dependents-attempt-all-then-surface-earliest
  ;; The per-source coordinator brackets the whole fan-out in one with-epoch,
  ;; so every dependent is attempted once and then the earliest failure
  ;; surfaces. Surfacing at the next sibling's drain instead would abort the
  ;; atom's notify loop (stranding the tail) or park a last-position failure.
  (doseq [throwers [#{:first} #{:middle} #{:last} #{:first :last}]]
    (let [make-derived (direct-derived-fn)
          src      (atom 1)
          boom?    (atom false)
          ran      (atom {:first 0 :middle 0 :last 0})
          errs     {:first  (js/Error. "d-first")
                    :middle (js/Error. "d-middle")
                    :last   (js/Error. "d-last")}
          ds       (mapv (fn [pos]
                           (make-derived [src]
                             (fn [x]
                               (swap! ran update pos inc)
                               (when (and @boom? (throwers pos)) (throw (errs pos)))
                               (* x 10))))
                         [:first :middle :last])
          earliest (some throwers [:first :middle :last])]
      (run! deref ds)
      (reset! ran {:first 0 :middle 0 :last 0})
      (reset! boom? true)
      (is (= [true {:first 1 :middle 1 :last 1}]
             [(identical? (errs earliest) (thrown-by #(reset! src 2))) @ran])
          (pr-str throwers)))))

(deftest direct-source-failure-leaves-scheduler-state-clean
  ;; After a failed fan-out the scheduler retains no failure and no stuck
  ;; guard: an unrelated source's write and the failed source's next write
  ;; both flush normally.
  (let [make-derived (direct-derived-fn)
        src     (atom 1)
        other   (atom 100)
        boom?   (atom false)
        d       (make-derived [src] (fn [x] (when @boom? (throw (js/Error. "boom"))) x))
        d-other (make-derived [other] (fn [y] y))
        notes   (atom [])]
    @d
    @d-other
    (reset! boom? true)
    (thrown-by #(reset! src 2))
    (reset! boom? false)
    (add-watch d :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
    (add-watch d-other :w (fn [_ _ prev nu] (swap! notes conj [prev nu])))
    (is (= [::none ::none [[100 200] [1 3]]]
           [(thrown-by #(reset! other 200)) (thrown-by #(reset! src 3)) @notes]))))

(deftest with-epoch-body-throw-wins-over-drain-throw
  ;; The body queues a flush whose recompute throws E2, then throws its own
  ;; value. The body's escape is the primary and surfaces by identity, falsey
  ;; values included; a bare try/finally would surface E2 instead.
  (doseq [body-throw [(js/Error. "E1 body") false nil]]
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-b2-" scheduler)
          replace!     (rf.substrate.spine/make-replace-container-fn scheduler)
          root         (rf.substrate.spine/make-state-container {:a 1})
          boom?        (atom false)
          d            (make-derived [root]
                         (fn [db] (when @boom? (throw (js/Error. "E2 drain"))) (:a db)))
          notes        (atom [])]
      @d
      (reset! boom? true)
      (is (identical? body-throw
                      (thrown-by #(#'rf.substrate.spine/with-epoch scheduler
                                    (fn [] (reset! root {:a 2}) (throw body-throw)))))
          (pr-str body-throw))
      (reset! boom? false)
      (add-watch d :w (fn [_ _ _ nu] (swap! notes conj nu)))
      (replace! root {:a 5})
      (is (= [5] @notes) "the scheduler recovered"))))

;; ---- notification fan-out -------------------------------------------------

(deftest nan-to-nan-derived-does-not-fan-out-on-no-move
  ;; Movement is judged by rf=, under which ##NaN equals itself (raw `not=`
  ;; would fire); an ordinary move on the same tick still notifies.
  (let [{:keys [make-derived replace! root]} (build-graph)
        nan-d (make-derived [root] (fn [_db] js/NaN))
        ord-d (make-derived [root] (fn [db] (:a db)))
        notes (atom [])]
    @nan-d
    @ord-d
    (add-watch nan-d :w (fn [_ _ _ _] (swap! notes conj :nan)))
    (add-watch ord-d :w (fn [_ _ _ _] (swap! notes conj :ord)))
    (replace! root {:a 2 :b 10})
    (is (= [:ord] @notes))))

(deftest throwing-subscriber-delivery-is-order-independent
  ;; A subscriber throwing mid-registration-order stops neither an earlier nor
  ;; a later sibling; its throw, captured by presence (falsey throws included),
  ;; surfaces after every sibling ran.
  (doseq [thrown-val [(js/Error. "middle subscriber boom") false nil]]
    (let [{:keys [make-derived replace! root]} (build-graph)
          l1    (make-derived [root] (fn [db] (:a db)))
          fired (atom #{})]
      @l1
      (add-watch l1 :b (fn [_ _ _ _] (swap! fired conj :b)))
      (add-watch l1 :a (fn [_ _ _ _] (swap! fired conj :a) (throw thrown-val)))
      (add-watch l1 :c (fn [_ _ _ _] (swap! fired conj :c)))
      (is (= [true #{:a :b :c}]
             [(identical? thrown-val (thrown-by #(replace! root {:a 2 :b 10}))) @fired])
          (pr-str thrown-val)))))

(deftest single-subscriber-throw-surfaces-on-fast-path
  ;; One watcher takes the allocation-free path with no capture; its throw must
  ;; still reach the caller.
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1       (make-derived [root] (fn [db] (:a db)))
        sentinel (js/Error. "solo boom")]
    @l1
    (add-watch l1 :only (fn [_ _ _ _] (throw sentinel)))
    (is (identical? sentinel (thrown-by #(replace! root {:a 2 :b 10}))))))

(deftest watcher-add-remove-during-fan-out-snapshots-to-next-wave
  ;; Fan-out iterates a snapshot of the watcher map: a watcher removed mid-wave
  ;; still fires this wave, and one added mid-wave first fires on the next.
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1      (make-derived [root] (fn [db] (:a db)))
        counts  (atom {:a 0 :c 0 :d 0})
        bump!   (fn [k] (fn [_ _ _ _] (swap! counts update k inc)))
        mutated (atom false)]
    @l1
    (add-watch l1 :a (bump! :a))
    (add-watch l1 :mutator (fn [_ _ _ _]
                             (when-not @mutated
                               (reset! mutated true)
                               (add-watch l1 :d (bump! :d))
                               (remove-watch l1 :c))))
    (add-watch l1 :c (bump! :c))
    (replace! root {:a 2 :b 10})
    (is (= {:a 1 :c 1 :d 0} @counts))
    (replace! root {:a 3 :b 10})
    (is (= {:a 2 :c 1 :d 1} @counts))))

(deftest re-entrant-write-from-subscriber-coalesces-into-outer-drain
  ;; The nested epoch cannot drain while the outer drain holds `flushing?`, so
  ;; the running outer loop picks up the re-entrant write.
  (let [{:keys [make-derived replace! root]} (build-graph)
        l1    (make-derived [root] (fn [db] (:a db)))
        seen  (atom [])
        done? (atom false)]
    @l1
    (add-watch l1 :w (fn [_ _ prev nu]
                       (swap! seen conj [prev nu])
                       (when-not @done?
                         (reset! done? true)
                         (replace! root {:a 3 :b 10}))))
    (replace! root {:a 2 :b 10})
    (is (= [[1 2] [2 3]] @seen))
    (replace! root {:a 4 :b 10})
    (is (= [[1 2] [2 3] [3 4]] @seen))))
