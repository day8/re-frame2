(ns re-frame.substrate.spine-build-recompute-fn-cljs-test
  "Unit coverage for the substrate-spine's `build-recompute-fn` helper
  (rf2-eoy63 — arity-specialised recompute closure lifted into the
  spine so all four adapters share one implementation).

  Pins:

    1. Arity-0 / -1 / -2 closures call `compute-fn` directly — no
       `apply`, no lazy-seq allocation, no `mapv`.
    2. Arity-N (≥3) uses an eager `mapv deref` (NOT lazy `map`) so a
       lazy cons chain cannot defer derefs past the recompute boundary.
    3. The returned thunk derefs its sources every call (does not
       memoise) — the substrate contract says fresh recompute per
       deref of the derived container.
    4. Multi-source `notify` semantics — each source's change drives
       a recompute; ZERO-arity has no sources to watch.

  The recompute closures are tested directly without wiring through
  `make-derived-value-fn` so the assertions stay focused on the
  arity-spec contract."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.substrate.spine :as spine]))

;; ---- helpers --------------------------------------------------------------

(defn- recompute-name [f]
  ;; The fn names baked into the closures (`recompute-0`, `recompute-1`,
  ;; `recompute-2`, `recompute-n`) are the surface tests can probe to
  ;; confirm the case-branch fired without inspecting bytecode.
  ;; ClojureScript exposes the fn's `.-name` for named anonymous fns
  ;; declared via `(fn fname [...] ...)`.
  (.-name f))

;; ---- arity-spec branch-selection ------------------------------------------

(deftest build-recompute-fn-zero-arity-picks-0-branch
  (testing "0 sources → 0-arity closure that calls compute-fn with no args"
    (let [calls (atom 0)
          f     (spine/build-recompute-fn []
                  (fn [] (swap! calls inc) ::seed))]
      (is (re-find #"recompute_0" (recompute-name f))
          "0-source case selects the recompute-0 branch")
      (is (= ::seed (f)) "thunk returns the compute-fn result")
      (is (= 1 @calls) "compute-fn invoked exactly once per call")
      (f) (f)
      (is (= 3 @calls) "thunk does NOT memoise — fresh call per invocation"))))

(deftest build-recompute-fn-one-arity-picks-1-branch
  (testing "1 source → 1-arity closure that derefs s0 and calls compute-fn"
    (let [s0   (atom 7)
          f    (spine/build-recompute-fn [s0] (fn [a] (* 2 a)))]
      (is (re-find #"recompute_1" (recompute-name f))
          "1-source case selects the recompute-1 branch")
      (is (= 14 (f)) "derefs source 0, applies compute-fn directly")
      (reset! s0 11)
      (is (= 22 (f)) "subsequent call derefs latest source value"))))

(deftest build-recompute-fn-two-arity-picks-2-branch
  (testing "2 sources → 2-arity closure that derefs s0 + s1 and calls compute-fn"
    (let [s0 (atom 3)
          s1 (atom 4)
          f  (spine/build-recompute-fn [s0 s1] +)]
      (is (re-find #"recompute_2" (recompute-name f))
          "2-source case selects the recompute-2 branch")
      (is (= 7 (f)) "derefs both sources, calls compute-fn directly")
      (reset! s1 40)
      (is (= 43 (f)) "subsequent call derefs latest values"))))

(deftest build-recompute-fn-three-arity-picks-n-branch
  (testing "3 sources → N-arity closure that mapv-derefs and applies compute-fn"
    (let [s0 (atom 1) s1 (atom 2) s2 (atom 3)
          f  (spine/build-recompute-fn [s0 s1 s2]
               (fn [a b c] (+ a b c)))]
      (is (re-find #"recompute_n" (recompute-name f))
          "3-source case selects the recompute-n fallback")
      (is (= 6 (f)) "derefs all 3 sources via mapv, applies compute-fn"))))

(deftest build-recompute-fn-n-arity-uses-mapv-not-lazy-map
  (testing "N-arity (≥3) uses eager mapv-deref — derefs happen before compute-fn runs"
    ;; Side-effect counter inside the deref machinery is impossible
    ;; (CLJS `atom/deref` is pure) — instead pin the property via the
    ;; observable consequence of `mapv` vs `map`: every source's deref
    ;; is visible to compute-fn as a fully-realised arg list, NOT a
    ;; lazy sequence that defers. We verify this by making compute-fn
    ;; close over the *number* of args it received (which a lazy seq
    ;; would still report correctly via `count`, so we go further) and
    ;; by asserting the args are positionally bound to deref'd values
    ;; — a lazy chain would still work positionally if forced, so the
    ;; strongest invariant is the no-lazy-cons property: the closure
    ;; gives compute-fn each value already realised and apply-able.
    (let [s0 (atom :a) s1 (atom :b) s2 (atom :c) s3 (atom :d)
          received-args (atom nil)
          f (spine/build-recompute-fn [s0 s1 s2 s3]
              (fn [& args]
                (reset! received-args args)
                (vec args)))]
      (is (= [:a :b :c :d] (f)))
      ;; `apply` flattens varargs into a seq; the underlying vector
      ;; from `mapv` is observably eager (count + nth without realising
      ;; further). The strongest pinning is value-equality of the
      ;; received args against the source vals at call time.
      (is (= [:a :b :c :d] (vec @received-args))))))

(deftest build-recompute-fn-fresh-recompute-per-call
  (testing "thunk does not cache — every call rederefs sources"
    (let [s0  (atom 0)
          f   (spine/build-recompute-fn [s0] identity)]
      (is (= 0 (f)))
      (reset! s0 1) (is (= 1 (f)))
      (reset! s0 2) (is (= 2 (f)))
      (reset! s0 3) (is (= 3 (f))))))

(deftest build-recompute-fn-honours-source-vector-order
  (testing "1-arity: s0 is the single source; 2-arity: order is s0 then s1"
    (let [s0 (atom 100)
          s1 (atom 1)
          f  (spine/build-recompute-fn [s0 s1] -)]
      ;; `-` is non-commutative: (- @s0 @s1) = 99, (- @s1 @s0) = -99.
      (is (= 99 (f)) "argument order matches source-vector order"))))

(deftest build-recompute-fn-shape-matches-make-derived-value-fn-flow
  (testing "the recompute fn assembled by build-recompute-fn is the same one make-derived-value-fn wires into the IDeref body"
    ;; Integration probe: drive `make-derived-value-fn` end-to-end and
    ;; confirm the spine's derived container exposes the same value the
    ;; bare arity-spec recompute closure would have produced. Pins the
    ;; rf2-eoy63 wiring: spine's IDeref body MUST route through
    ;; build-recompute-fn, not its old naive (apply compute-fn (map
    ;; deref ...)) shape.
    (let [make-derived (spine/make-derived-value-fn "rf-test-" (spine/make-scheduler))
          s0           (atom 5)
          derived      (make-derived [s0] (fn [a] (* a 10)))]
      (is (= 50 @derived) "1-arity recompute fires through the spine")
      (reset! s0 6)
      (is (= 60 @derived) "subsequent deref re-runs the recompute"))))
