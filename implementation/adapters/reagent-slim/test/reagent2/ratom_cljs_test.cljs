(ns reagent2.ratom-cljs-test
  "Unit tests for reagent2.ratom: the RAtom's atom protocols, Reaction
  dependency capture, memoisation, dispose and auto-run modes, the reactive
  context, the rea-queue drain, and printing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.ratom :as ratom :refer-macros [reaction]]))

;; ---------------------------------------------------------------------------
;; RAtom — atom-shape protocols
;; ---------------------------------------------------------------------------

(deftest ratom-construction
  (testing "atom returns an RAtom"
    (let [a (ratom/atom 1)]
      (is (instance? ratom/RAtom a))
      (is (= 1 @a))))

  (testing "atom accepts :meta and :validator kwargs"
    (let [a (ratom/atom 0 :meta {:tag :counter})]
      (is (= {:tag :counter} (meta a))))

    (let [a (ratom/atom 0 :validator number?)]
      (reset! a 5)
      (is (= 5 @a))
      (is (thrown? js/Error (reset! a "bad")))))

  (testing "RAtom satisfies IReactiveAtom (the ratom? protocol)"
    (is (satisfies? ratom/IReactiveAtom (ratom/atom 0)))))

(deftest ratom-reset-swap
  (let [a (ratom/atom 0)]
    (testing "reset!"
      (is (= 7 (reset! a 7)))
      (is (= 7 @a)))

    (testing "swap! 1-arity"
      (is (= 8 (swap! a inc)))
      (is (= 8 @a)))

    (testing "swap! n-arity"
      (is (= 18 (swap! a + 10)))
      (is (= 28 (swap! a + 5 5)))
      (is (= 38 (swap! a + 1 2 3 4))))))

(deftest ratom-watches
  (testing "add-watch fires on change with [k prev nu] arity"
    (let [a     (ratom/atom 0)
          fired (atom [])]
      (add-watch a :w (fn [k _r prev nu] (swap! fired conj [k prev nu])))
      (reset! a 1)
      (reset! a 2)
      ;; Each watch fires once per change with the actual previous value.
      (is (= [[:w 0 1] [:w 1 2]] @fired))))

  (testing "remove-watch stops notifications"
    (let [a     (ratom/atom 0)
          fired (atom 0)]
      (add-watch a :w (fn [_ _ _ _] (swap! fired inc)))
      (reset! a 1)
      (remove-watch a :w)
      (reset! a 2)
      (is (= 1 @fired)))))

(deftest ratom-meta
  (testing "with-meta produces a fresh RAtom with the new meta"
    (let [a (ratom/atom 0 :meta {:k :v})
          b (with-meta a {:k :z})]
      (is (= {:k :v} (meta a)))
      (is (= {:k :z} (meta b)))
      (is (= 0 @b)))))

(deftest ratom-identity-equality
  (testing "RAtoms are reference-equal only"
    (let [a (ratom/atom 1)
          b (ratom/atom 1)]
      (is (= a a))
      (is (not= a b)))))

;; ---------------------------------------------------------------------------
;; Reaction — derived value
;; ---------------------------------------------------------------------------

(deftest reaction-basic
  (testing "Reaction satisfies IReactiveAtom, the protocol ratom? checks"
    (is (satisfies? ratom/IReactiveAtom (ratom/make-reaction (fn [] 0))))))

(deftest reaction-equality-memo
  (testing "watchers do not fire when recomputed value is = old value"
    ;; :auto-run true → r subscribes eagerly + recomputes synchronously
    ;; on dep change. This is the path that exercises the equality
    ;; memoisation in _run (per IMPL-SPEC §3.2 — kept from stock).
    (let [a           (ratom/atom {:k 1})
          watch-calls (atom 0)
          r           (ratom/make-reaction
                        (fn [] (:k @a))
                        :auto-run true)]
      ;; Force initial compute so r subscribes to a.
      (is (= 1 @r))
      (add-watch r :w (fn [_ _ _ _] (swap! watch-calls inc)))
      ;; Same :k but different map identity — should NOT fire watch
      ;; (= memoisation kicks in: oldstate=1, res=1).
      (reset! a {:k 1})
      (is (= 0 @watch-calls))
      ;; Different :k — should fire.
      (reset! a {:k 2})
      (is (= 1 @watch-calls))
      (is (= 2 @r)))))

(deftest reaction-multiple-deps
  (testing "Reaction tracks multiple dependencies"
    ;; :auto-run so the body runs through `_run` and captures its deps; a
    ;; deref then reads the cached state. On the fast path (no :auto-run,
    ;; non-reactive deref) every deref just re-calls f and tracks nothing,
    ;; so the values below would hold with no dependency capture at all.
    (let [a (ratom/atom 2)
          b (ratom/atom 3)
          r (ratom/make-reaction (fn [] (+ @a @b)) :auto-run true)]
      (is (= 5 @r))
      (reset! a 10)
      (is (= 13 @r))
      (reset! b 100)
      (is (= 110 @r)))))

(deftest reaction-dependency-pruning
  (testing "Reaction unsubscribes from no-longer-watched ratoms"
    ;; :auto-run so r captures its deps and recomputes when one changes; the
    ;; fast path re-calls f on every deref and cannot tell a pruned dep from
    ;; a live one. `runs` counts body executions.
    (let [flag (ratom/atom true)
          a    (ratom/atom 1)
          b    (ratom/atom 100)
          runs (atom 0)
          r    (ratom/make-reaction (fn [] (swap! runs inc) (if @flag @a @b))
                                    :auto-run true)]
      (is (= 1 @r))
      ;; Switch the branch.
      (reset! flag false)
      (is (= 100 @r))
      ;; Now `a` is no longer a dependency. Mutating it must not
      ;; recompute (no watch from r left on a).
      (let [runs-before @runs]
        (reset! a 999)
        (is (= runs-before @runs)
            "mutating the dropped dependency `a` does not re-run r's body")
        (is (= 100 @r))))))

(deftest reaction-dispose-clears-watches
  (testing "dispose! removes watches from upstream RAtoms"
    ;; Use :auto-run to ensure r subscribes to a (the fast-path deref
    ;; doesn't wire upstream watches per IMPL-SPEC §3.2).
    (let [a              (ratom/atom 0)
          r              (ratom/make-reaction (fn [] @a) :auto-run true)
          _              @r ;; trigger _run, which wires upstream watch
          watches-before (.-watches a)]
      (is (some? watches-before))
      (ratom/dispose! r)
      (let [watches-after (.-watches a)]
        ;; Either nil or empty after dispose.
        (is (or (nil? watches-after)
                (empty? watches-after)))))))

;; ---- dispose! idempotence + re-entrancy -----------------------------------
;; The Reaction has no disposed flag: `dispose!` clears its callback holders
;; before firing them, so a second or re-entrant `dispose!` fires nothing.

(deftest reaction-dispose-is-idempotent
  (testing "a second dispose! does NOT re-fire on-dispose / add-on-dispose! callbacks"
    (let [fired (atom [])
          r     (ratom/make-reaction
                  (fn [] 1)
                  :on-dispose (fn [_] (swap! fired conj :on-dispose-kwarg)))]
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :added-1)))
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :added-2)))
      (ratom/dispose! r)
      (is (= [:on-dispose-kwarg :added-1 :added-2] @fired)
          "first dispose! fired the kwarg then the array callbacks in registration order")
      (ratom/dispose! r)
      (is (= [:on-dispose-kwarg :added-1 :added-2] @fired)
          "second dispose! re-fired nothing (idempotent)"))))

(deftest reaction-dispose-is-re-entrant-safe
  (testing "an on-dispose callback that re-enters dispose! on the same
  Reaction does not recurse or double-fire the callback set"
    (let [fired (atom [])
          r     (ratom/make-reaction (fn [] 1))]
      ;; The :on-dispose-arr callbacks receive the Reaction; the first one
      ;; defensively re-disposes it — the re-entrant shape. The holders are
      ;; cleared before firing, so the re-entrant call
      ;; sees nil holders and re-fires nothing.
      (ratom/add-on-dispose! r (fn [this]
                                 (swap! fired conj :re-entrant-cb)
                                 (ratom/dispose! this)))
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :after-cb)))
      (ratom/dispose! r)
      (is (= [:re-entrant-cb :after-cb] @fired)
          "each callback fired exactly once despite the re-entrant dispose!; no recursion, no double-fire"))))


(deftest reaction-auto-run-fn
  (testing ":auto-run fn-form receives the reaction on change"
    (let [a       (ratom/atom 1)
          received (atom nil)
          r       (ratom/make-reaction
                    (fn [] @a)
                    :auto-run (fn [r] (reset! received r)))]
      @r
      (reset! a 2)
      (is (instance? ratom/Reaction @received)))))

;; ---------------------------------------------------------------------------
;; reactive? + reactive context
;; ---------------------------------------------------------------------------

(deftest reactive-predicate
  (testing "reactive? false outside any reactive context"
    (is (false? (ratom/reactive?))))

  (testing "reactive? true inside a Reaction body that goes through _run"
    ;; The non-reactive fast path binds no context, so :auto-run routes the
    ;; first deref through `_run`, which does.
    (let [seen (atom nil)
          r    (ratom/make-reaction
                 (fn [] (reset! seen (ratom/reactive?)))
                 :auto-run true)]
      @r
      (is (true? @seen)))))

;; ---------------------------------------------------------------------------
;; flush! — rea-queue drain
;; ---------------------------------------------------------------------------

;; Neither block below reaches the queue: `:auto-run true` recomputes inside
;; the `reset!`. The queue + `flush!` drain is pinned by
;; `queued-reaction-drains-on-flush`.
(deftest auto-run-recompute-notifies-watchers-synchronously
  (testing "an auto-run Reaction's watch fires inside the source reset!"
    (let [a     (ratom/atom 1)
          fired (atom [])
          r     (ratom/make-reaction (fn [] @a) :auto-run true)]
      ;; Force initial subscription via _run.
      @r
      (add-watch r :w (fn [_ _ _ nu] (swap! fired conj nu)))
      ;; auto-run true → synchronous recompute on dep change.
      ;; The watch fires inside the auto-run's _run path.
      (reset! a 2)
      (is (= [2] @fired))))

  (testing "a chain of auto-run Reactions recomputes synchronously end to end"
    ;; r2 derefs r1, so r2's deref-capture subscribes r2 to r1, and r1
    ;; subscribes to a. Both are auto-run, so mutating a recomputes r1 and
    ;; then r2 inside the reset!.
    (let [a       (ratom/atom 1)
          r1      (ratom/make-reaction (fn [] (* @a 10)) :auto-run true)
          r2-vals (atom [])
          r2      (ratom/make-reaction
                    (fn [] (+ @r1 1))
                    :auto-run true)]
      ;; Force initial run-paths to wire subscriptions.
      @r1 @r2
      (add-watch r2 :w (fn [_ _ _ nu] (swap! r2-vals conj nu)))
      (reset! a 2)
      ;; auto-run on both is synchronous: a=2 → r1=20 → r2=21.
      (is (= [21] @r2-vals)))))

(deftest queued-reaction-drains-on-flush
  (testing "rea-queue path: enqueue on dep change, flush! drains"
    ;; A no-auto-run Reaction subscribes to its deps via deref-capture
    ;; only when its body runs in a reactive context. We exercise this
    ;; by deref'ing r INSIDE another (auto-run) Reaction's body — the
    ;; outer reactive context is what triggers the subscription.
    (let [a       (ratom/atom 1)
          r-vals  (atom [])
          r       (ratom/make-reaction (fn [] (* @a 10)))
          ;; Outer with auto-run derefs r — wires r → a watching, AND
          ;; r → outer via r's own watch.
          outer   (ratom/make-reaction
                    (fn [] @r)
                    :auto-run true)]
      ;; Force initial run paths.
      @outer
      ;; Now mutate a. Stock Reagent semantics: r enqueues itself; the
      ;; auto-run on outer fires when r changes. Drain via flush!.
      (add-watch outer :w (fn [_ _ _ nu] (swap! r-vals conj nu)))
      (reset! a 3)
      (ratom/flush!)
      ;; outer should have observed r=30.
      (is (= [30] @r-vals)))))

;; ---------------------------------------------------------------------------
;; throwing Reaction body — the check=true (_queued-run / flush!) error path
;; ---------------------------------------------------------------------------

(deftest throwing-reaction-checked-recompute-preserves-error
  ;; Drives the checked (`_queued-run`) path: a no-auto-run inner reaction
  ;; subscribed through an outer one whose auto-run is a no-op, so the inner's
  ;; error does not cascade into an outer re-deref.
  (testing "a throwing Reaction body on the checked recompute path"
    (let [a            (ratom/atom 1)
          ;; Inner reaction throws once a crosses a threshold.
          inner        (ratom/make-reaction
                         (fn []
                           (when (> @a 1)
                             (throw (ex-info "boom" {:a @a})))
                           @a))
          ;; Outer derefs inner once (wiring outer→inner and, via inner's
          ;; own _run, inner→a). NO-OP auto-run: on inner's change the outer
          ;; just marks, never re-derefs the errored inner.
          outer        (ratom/make-reaction (fn [] @inner)
                                            :auto-run (fn [_this] nil))
          watch-fires  (atom [])]
      ;; Force initial run paths (a=1 ⇒ inner=1, no throw).
      @outer
      (is (= 1 @inner) "precondition: inner computes cleanly at a=1")
      ;; Watch the INNER reaction so we can detect a spurious notify.
      (add-watch inner :w (fn [_ _ _ nu] (swap! watch-fires conj nu)))
      ;; Cross the threshold ⇒ inner's queued/checked recompute throws.
      (reset! a 2)
      (ratom/flush!)
      (testing "the captured error rethrows on deref (not a clobbered false)"
        (is (thrown-with-msg? cljs.core/ExceptionInfo #"boom" @inner)))
      (testing "watchers are NOT notified of a spurious `false` value change"
        (is (not (some false? @watch-fires))
            "the error recompute must not notify watchers with the catch
             block's `false` return")))))

;; ---------------------------------------------------------------------------
;; reaction macro — 5-line indirection
;; ---------------------------------------------------------------------------

(deftest reaction-macro
  (testing "(reaction body) is equivalent to (make-reaction (fn [] body))"
    (let [a (ratom/atom 3)
          r (reaction (* @a @a))]
      (is (= 9 @r))
      (reset! a 4)
      (is (= 16 @r))
      (is (instance? ratom/Reaction r)))))

;; ---------------------------------------------------------------------------
;; Printed representation: `#object[reagent2.ratom.<Type> {:val <v>}]`, with
;; the value printed recursively.
;; ---------------------------------------------------------------------------

(deftest ratom-printed-representation
  (doseq [[x expected]
          [[(ratom/atom 1) "#object[reagent2.ratom.RAtom {:val 1}]"]
           [(ratom/atom {:inner (ratom/atom 7)})
            "#object[reagent2.ratom.RAtom {:val {:inner #object[reagent2.ratom.RAtom {:val 7}]}}]"]
           [(ratom/make-reaction (fn [] 42)) "#object[reagent2.ratom.Reaction {:val 42}]"]]]
    (is (= expected (pr-str x)))))

;; ---------------------------------------------------------------------------
;; Printing captures the printed ratom's own deref (taken at the call site)
;; but not the derefs of ratoms nested in its value, so a printer does not
;; come to depend on what it printed.
;; ---------------------------------------------------------------------------

(deftest pr-atom-does-not-capture-nested-derefs
  (testing "printing a ratom-in-a-ratom captures the outer, never the inner"
    (let [inner (ratom/atom 7)
          outer (ratom/atom {:inner inner})
          r     (ratom/make-reaction (fn [] (pr-str outer)) :auto-run true)]
      @r
      (let [watched (set (.-watching r))]
        (is (contains? watched outer)
            "the printed ratom itself is deref'd at the call site")
        (is (not (contains? watched inner))
            "the nested ratom is deref'd inside pr-writer, under the guard"))))

  (testing "so changing the nested ratom does not re-run the printer"
    (let [inner (ratom/atom 7)
          outer (ratom/atom {:inner inner})
          runs  (volatile! 0)
          r     (ratom/make-reaction (fn [] (vswap! runs inc) (pr-str outer))
                                     :auto-run true)]
      @r
      (is (= 1 @runs))
      (reset! inner 8)
      (is (= 1 @runs)
          "a spurious dependency on the nested ratom would recompute here"))))
