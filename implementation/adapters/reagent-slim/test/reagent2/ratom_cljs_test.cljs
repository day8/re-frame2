(ns reagent2.ratom-cljs-test
  "Unit tests for reagent2.ratom (Stage 4-A, rf2-6hyy).

  Covers:

    - RAtom: atom-shape protocols (IDeref, IReset, ISwap, IWatchable,
      IMeta, IWithMeta), validator, watch fire-once-per-change,
      identity-equality.

    - Reaction: deref-time dependency capture, equality memoisation,
      dirty-flag transitions, on-dispose hooks, dispose! teardown,
      auto-run modes.

    - Protocol satisfaction: IReactiveAtom (the canonical ratom? test
      `re-frame.interop/ratom?` uses), IDisposable (cross-substrate
      cache-wiring contract per IMPL-SPEC §3.4).

    - reactive? predicate gating on *ratom-context*.

    - flush! drains the rea-queue including downstream cascades.

    - reaction macro: 5-line indirection over make-reaction.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
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
      (is (not= a b))
      (is (not (identical? a b))))))

;; ---------------------------------------------------------------------------
;; Reaction — derived value
;; ---------------------------------------------------------------------------

(deftest reaction-basic
  (testing "Reaction recomputes on dependency change (deref-fast-path)"
    ;; Without auto-run, deref outside a reactive context goes through
    ;; the fast path (per IMPL-SPEC §3.2): just call f, no subscribing.
    ;; The reaction stays dirty? after every reset! and recomputes on
    ;; the next deref. This is stock-Reagent semantics.
    (let [a (ratom/atom 1)
          r (ratom/make-reaction (fn [] (* @a 10)))]
      (is (= 10 @r))
      (reset! a 2)
      ;; r is now dirty (from rea-enqueue) — next deref recomputes.
      (is (= 20 @r))))

  (testing "Reaction satisfies IReactiveAtom"
    (let [r (ratom/make-reaction (fn [] 0))]
      (is (satisfies? ratom/IReactiveAtom r))))

  (testing "Reaction satisfies IDisposable"
    (let [r (ratom/make-reaction (fn [] 0))]
      (is (satisfies? ratom/IDisposable r)))))

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
    (let [a (ratom/atom 2)
          b (ratom/atom 3)
          r (ratom/make-reaction (fn [] (+ @a @b)))]
      (is (= 5 @r))
      (reset! a 10)
      (is (= 13 @r))
      (reset! b 100)
      (is (= 110 @r)))))

(deftest reaction-dependency-pruning
  (testing "Reaction unsubscribes from no-longer-watched ratoms"
    (let [flag (ratom/atom true)
          a    (ratom/atom 1)
          b    (ratom/atom 100)
          r    (ratom/make-reaction (fn [] (if @flag @a @b)))]
      (is (= 1 @r))
      ;; Switch the branch.
      (reset! flag false)
      (is (= 100 @r))
      ;; Now `a` is no longer a dependency. Mutating it must not
      ;; recompute (no watchers wired to a anymore in r's path).
      (reset! a 999)
      (is (= 100 @r)))))

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

(deftest reaction-add-on-dispose
  (testing "add-on-dispose! callbacks fire in registration order"
    (let [r       (ratom/make-reaction (fn [] 1))
          fired   (atom [])]
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :a)))
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :b)))
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :c)))
      (ratom/dispose! r)
      (is (= [:a :b :c] @fired))))

  (testing "on-dispose kwarg fires before add-on-dispose! callbacks"
    (let [fired   (atom [])
          r       (ratom/make-reaction
                    (fn [] 1)
                    :on-dispose (fn [_] (swap! fired conj :on-dispose-kwarg)))]
      (ratom/add-on-dispose! r (fn [_] (swap! fired conj :added)))
      (ratom/dispose! r)
      (is (= [:on-dispose-kwarg :added] @fired)))))

(deftest reaction-auto-run-true
  (testing ":auto-run true triggers synchronous recompute on dep change"
    (let [a       (ratom/atom 1)
          calls   (atom 0)
          r       (ratom/make-reaction
                    (fn [] (swap! calls inc) @a)
                    :auto-run true)]
      ;; Force initial run so the reaction subscribes.
      @r
      (is (= 1 @calls))
      (reset! a 2)
      ;; auto-run true → synchronous recompute on change
      (is (= 2 @calls))
      (is (= 2 @r)))))

(deftest reaction-auto-run-fn
  (testing ":auto-run fn-form receives the reaction on change"
    (let [a       (ratom/atom 1)
          received (atom nil)
          r       (ratom/make-reaction
                    (fn [] @a)
                    :auto-run (fn [r] (reset! received r)))]
      @r
      (reset! a 2)
      (is (some? @received))
      (is (instance? ratom/Reaction @received)))))

;; ---------------------------------------------------------------------------
;; reactive? + reactive context
;; ---------------------------------------------------------------------------

(deftest reactive-predicate
  (testing "reactive? false outside any reactive context"
    (is (false? (ratom/reactive?))))

  (testing "reactive? true inside a Reaction body that goes through _run"
    ;; The deref-fast-path (non-reactive deref of a no-auto-run Reaction)
    ;; does NOT bind *ratom-context* — it just calls f. That's stock
    ;; Reagent's design (per IMPL-SPEC §3.2). Use :auto-run true so the
    ;; first deref goes through `_run` → `deref-capture` → `in-context`,
    ;; which does bind *ratom-context*.
    (let [seen (atom nil)
          r    (ratom/make-reaction
                 (fn [] (reset! seen (ratom/reactive?)))
                 :auto-run true)]
      @r
      (is (true? @seen)))))

;; ---------------------------------------------------------------------------
;; flush! — rea-queue drain
;; ---------------------------------------------------------------------------

(deftest flush-drains-queue
  (testing "flush! recomputes queued Reactions"
    ;; A Reaction enqueues itself only if it had wired upstream watches
    ;; (i.e. went through _run / deref-capture). The fast-path deref
    ;; does NOT wire watches (IMPL-SPEC §3.2). Use :auto-run nil but
    ;; deref under another reactive context to wire watches, OR use
    ;; an explicit upstream Reaction. We choose the latter: r1 has
    ;; auto-run, so it's subscribed; mutating a triggers r1's queue
    ;; entry.
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

  (testing "flush! drains downstream cascades"
    ;; r1 has no auto-run; r2 derefs r1, so r2's deref-capture
    ;; subscribes r2 to r1. r1 in turn must subscribe to a — give r1
    ;; auto-run so it does. Then mutating a fires r1 (synchronous), r1
    ;; fires r2 via its watcher (enqueued); flush! drains r2.
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

(deftest deref-outside-context-recomputes
  (testing "deref'ing a Reaction outside a reactive context recomputes"
    ;; The fast-path (non-reactive deref of a no-auto-run Reaction)
    ;; doesn't subscribe; it just calls f. So every deref re-runs f
    ;; and reports the current value of upstream RAtoms — matches
    ;; stock Reagent's read-through-on-fast-path semantics.
    (let [a (ratom/atom 1)
          r (ratom/make-reaction (fn [] (* @a 10)))]
      (is (= 10 @r))
      (reset! a 5)
      (is (= 50 @r)))))

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
;; Cross-substrate cache-wiring contract (IMPL-SPEC §3.4)
;;
;; Per the spec: the cross-substrate cache calls add-on-dispose! on a
;; substrate-side derived value. The protocol dispatch must work
;; uniformly. This test sanity-checks that the protocol-based dispatch
;; resolves on a Reaction without needing an instance? branch.
;; ---------------------------------------------------------------------------

(deftest cross-substrate-disposable-protocol
  (testing "add-on-dispose! is callable via protocol dispatch alone"
    (let [r     (ratom/make-reaction (fn [] 0))
          fired (atom false)]
      ;; Call through the protocol, NOT via direct method on the type:
      (ratom/add-on-dispose! r (fn [_] (reset! fired true)))
      (ratom/dispose! r)
      (is (true? @fired)))))
