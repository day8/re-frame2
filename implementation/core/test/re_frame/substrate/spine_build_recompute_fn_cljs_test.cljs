(ns re-frame.substrate.spine-build-recompute-fn-cljs-test
  "Unit coverage for the substrate-spine's `build-recompute-fn` helper
  (the arity-specialised recompute closure, which lives in the spine so
  every adapter shares one implementation).

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
            [re-frame.substrate.spine :as rf.substrate.spine]))

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
          f     (rf.substrate.spine/build-recompute-fn []
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
          f    (rf.substrate.spine/build-recompute-fn [s0] (fn [a] (* 2 a)))]
      (is (re-find #"recompute_1" (recompute-name f))
          "1-source case selects the recompute-1 branch")
      (is (= 14 (f)) "derefs source 0, applies compute-fn directly")
      (reset! s0 11)
      (is (= 22 (f)) "subsequent call derefs latest source value"))))

(deftest build-recompute-fn-two-arity-picks-2-branch
  (testing "2 sources → 2-arity closure that derefs s0 + s1 and calls compute-fn"
    (let [s0 (atom 3)
          s1 (atom 4)
          f  (rf.substrate.spine/build-recompute-fn [s0 s1] +)]
      (is (re-find #"recompute_2" (recompute-name f))
          "2-source case selects the recompute-2 branch")
      (is (= 7 (f)) "derefs both sources, calls compute-fn directly")
      (reset! s1 40)
      (is (= 43 (f)) "subsequent call derefs latest values"))))

(deftest build-recompute-fn-n-arity-uses-mapv-not-lazy-map
  (testing "N-arity (≥3) derefs every source before the recompute returns"
    ;; A lazy `map deref` over a vector realises in 32-element chunks, and
    ;; `apply` forces only the head of its arg seq, so sources past the first
    ;; chunk stay un-deref'd until compute-fn consumes them. 33 counting
    ;; sources and a compute-fn that returns its args unread tell eager
    ;; `mapv` (33 derefs) from lazy `map` (32).
    (let [derefs  (atom 0)
          sources (mapv (fn [i] (reify IDeref (-deref [_] (swap! derefs inc) i)))
                        (range 33))
          f       (rf.substrate.spine/build-recompute-fn sources (fn [& args] args))
          result  (f)]
      (is (= 33 @derefs)
          "every source was deref'd inside the recompute, none deferred into its result")
      (is (= (range 33) result) "compute-fn receives the values in source-vector order"))))

(deftest build-recompute-fn-honours-source-vector-order
  (testing "1-arity: s0 is the single source; 2-arity: order is s0 then s1"
    (let [s0 (atom 100)
          s1 (atom 1)
          f  (rf.substrate.spine/build-recompute-fn [s0 s1] -)]
      ;; `-` is non-commutative: (- @s0 @s1) = 99, (- @s1 @s0) = -99.
      (is (= 99 (f)) "argument order matches source-vector order"))))
