(ns re-frame.late-bind-cache-test
  "Per rf2-0kfd4 (testcov-core G1 + G2) — behavioural coverage for the
  late-bind sticky resolution cache and the `chain-fn!` runtime
  composition contract.

  These are rf2-f72pd hot-path machinery: every dispatch / subscribe
  resolves `:trace/emit!`, `:adapter/current-frame`, `:router/dispatch!`,
  `:epoch/capture-event`, … through `get-fn-cached`. The documented
  invariants previously had ZERO assertions:

    G1 — `get-fn-cached` / `invalidate-cache!` (late_bind.cljc):
      (a) a resolved fn is cached and re-served,
      (b) nil resolutions are NOT cached — a deferred publication is
          visible on the next call,
      (c) `set-fn!` / `chain-fn!` invalidate the slot so hot-reload swaps
          the fn on the next lookup. A stale slot serving a withdrawn
          hook is a silent correctness bug; this file pins the guard.

    G2 — `chain-fn!` ordering (late_bind.cljc):
      step-fn runs FIRST (last-registered = outer wrapper), each previous
      handler runs after with the same args, per-step throws PROPAGATE
      (not swallowed), the chained hook returns nil.

  Pure JVM unit — no adapter / frame / trace runtime needed. Every test
  drives a synthetic `:test/*` hook key so real published hooks are never
  touched; a fixture snapshots and restores the (private) `hooks` and
  `fn-cache` atoms regardless."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.late-bind :as late-bind]))

;; ---- fixtures -------------------------------------------------------------
;;
;; `hooks` and `fn-cache` are process-global `defonce` atoms; the tests
;; below mutate them via the public `set-fn!` / `chain-fn!` / public
;; `invalidate-cache!` API. We snapshot both and restore them after each
;; test so a synthetic `:test/*` key can never leak into a sibling test
;; or a real published hook can never be clobbered.

(defn isolate-hook-state [test-fn]
  (let [hooks-before     @(deref #'late-bind/hooks)
        fn-cache-before  @(deref #'late-bind/fn-cache)]
    (try
      (test-fn)
      (finally
        (reset! (deref #'late-bind/hooks)    hooks-before)
        (reset! (deref #'late-bind/fn-cache) fn-cache-before)))))

(use-fixtures :each isolate-hook-state)

(defn- cached?
  "True when `hook-key` currently has a populated cache slot."
  [hook-key]
  (contains? @(deref #'late-bind/fn-cache) hook-key))

;; =============================================================================
;; G1 — sticky resolution cache: get-fn-cached / invalidate-cache!
;; =============================================================================

(deftest get-fn-cached-caches-and-reserves-a-resolved-fn
  (testing "first hit reads `hooks`, populates the cache slot, returns the fn"
    (let [k  :test/g1-resolved
          f  (fn [] :resolved)]
      (is (not (cached? k)) "precondition: slot empty before any lookup")
      (late-bind/set-fn! k f)
      ;; set-fn! invalidates the slot — still uncached until the first read.
      (is (not (cached? k)) "set-fn! does not pre-warm the cache")
      (is (identical? f (late-bind/get-fn-cached k))
          "first get-fn-cached resolves through `hooks`")
      (is (cached? k) "the resolved fn is now cached/reserved")
      (is (identical? f (late-bind/get-fn-cached k))
          "subsequent hit re-serves the same fn"))))

(deftest get-fn-cached-re-serves-the-cached-slot-even-after-hooks-mutates
  (testing "once cached, get-fn-cached returns the cached slot, not a fresh `hooks` read"
    ;; This pins the *stickiness*: the cache is the source of truth until
    ;; explicitly invalidated. A bare `hooks` mutation (no invalidate)
    ;; must NOT be observed by get-fn-cached — only set-fn!/chain-fn!
    ;; (which invalidate) flip a cached resolution.
    (let [k  :test/g1-sticky
          f1 (fn [] :first)
          f2 (fn [] :second)]
      (late-bind/set-fn! k f1)
      (is (identical? f1 (late-bind/get-fn-cached k)) "warm the slot with f1")
      ;; Mutate `hooks` directly, bypassing set-fn!/invalidate-cache!.
      (swap! (deref #'late-bind/hooks) assoc k f2)
      (is (identical? f1 (late-bind/get-fn-cached k))
          "cached slot is sticky — a non-invalidating `hooks` write is not seen"))))

(deftest get-fn-cached-does-not-cache-nil-resolutions
  (testing "an unpublished key returns nil and is NOT cached — deferred publication is visible next call"
    (let [k :test/g1-deferred]
      (is (nil? (late-bind/get-fn-cached k)) "unpublished key resolves to nil")
      (is (not (cached? k))
          "nil resolution must NOT populate the cache slot")
      ;; Publish AFTER the first (nil) lookup — a sticky-nil bug would
      ;; keep returning nil here.
      (let [f (fn [] :late)]
        (late-bind/set-fn! k f)
        (is (identical? f (late-bind/get-fn-cached k))
            "deferred publication is visible on the next get-fn-cached call")))))

(deftest invalidate-cache!-drops-the-slot-so-the-next-lookup-re-resolves
  (testing "invalidate-cache! forces re-resolution through `hooks` on the next call"
    (let [k  :test/g1-invalidate
          f1 (fn [] :v1)
          f2 (fn [] :v2)]
      (late-bind/set-fn! k f1)
      (is (identical? f1 (late-bind/get-fn-cached k)) "warm the slot")
      (is (cached? k))
      ;; Swap the underlying `hooks` value, then invalidate the slot —
      ;; this is exactly the hot-reload sequence, but with the slot
      ;; cleared explicitly via the public helper.
      (swap! (deref #'late-bind/hooks) assoc k f2)
      (is (nil? (late-bind/invalidate-cache! k)) "invalidate-cache! returns nil")
      (is (not (cached? k)) "the slot is dropped")
      (is (identical? f2 (late-bind/get-fn-cached k))
          "next lookup re-resolves through `hooks` and serves the new fn"))))

(deftest set-fn!-invalidates-the-slot-so-hot-reload-swaps-the-fn
  (testing "re-publishing via set-fn! drops the cached resolution (hot-reload semantics)"
    (let [k  :test/g1-hot-reload
          f1 (fn [] :old)
          f2 (fn [] :new)]
      (late-bind/set-fn! k f1)
      (is (identical? f1 (late-bind/get-fn-cached k)) "warm with the old fn")
      (is (cached? k))
      ;; A genuine artefact hot-reload calls set-fn! again with the new fn.
      (late-bind/set-fn! k f2)
      (is (not (cached? k)) "set-fn! invalidated the slot")
      (is (identical? f2 (late-bind/get-fn-cached k))
          "the very next get-fn-cached serves the newly-published fn — no stale slot"))))

;; =============================================================================
;; G2 — chain-fn! runtime composition ordering
;; =============================================================================

(deftest chain-fn!-runs-step-first-then-previous-handler
  (testing "last-registered step is the OUTER wrapper — step-fn runs before the previous handler"
    (let [k     :test/g2-order
          order (atom [])]
      ;; First step becomes the inner handler.
      (late-bind/chain-fn! k (fn [_] (swap! order conj :first)))
      ;; Second step is registered last → runs first.
      (late-bind/chain-fn! k (fn [_] (swap! order conj :second)))
      (let [hook (late-bind/get-fn k)]
        (is (some? hook) "the chained hook is published under the key")
        (hook :arg))
      (is (= [:second :first] @order)
          "step-fn (last-registered) runs FIRST, the previous handler runs after"))))

(deftest chain-fn!-fans-out-the-same-args-to-every-step
  (testing "every chained step receives the same args"
    (let [k    :test/g2-args
          seen (atom [])]
      (late-bind/chain-fn! k (fn [a b] (swap! seen conj [:inner a b])))
      (late-bind/chain-fn! k (fn [a b] (swap! seen conj [:outer a b])))
      ((late-bind/get-fn k) 1 2)
      (is (= [[:outer 1 2] [:inner 1 2]] @seen)
          "both steps see identical args; outer (last-registered) first"))))

(deftest chain-fn!-chained-hook-returns-nil
  (testing "the chained hook is side-effecting — it returns nil regardless of step return values"
    (let [k :test/g2-return]
      (late-bind/chain-fn! k (fn [_] :inner-return))
      (late-bind/chain-fn! k (fn [_] :outer-return))
      (is (nil? ((late-bind/get-fn k) :arg))
          "chained hook returns nil — callers do not consume a step value"))))

(deftest chain-fn!-first-step-with-no-previous-runs-alone
  (testing "the very first chain-fn! step runs with no previous handler (previous is nil)"
    (let [k   :test/g2-first
          hit (atom 0)]
      (late-bind/chain-fn! k (fn [_] (swap! hit inc)))
      ((late-bind/get-fn k) :arg)
      (is (= 1 @hit) "the lone step runs exactly once with no previous to chain"))))

(deftest chain-fn!-propagates-per-step-throws
  (testing "a throwing step is NOT swallowed — the throw propagates out of the chained hook"
    (let [k          :test/g2-throw-outer
          inner-ran? (atom false)]
      (late-bind/chain-fn! k (fn [_] (reset! inner-ran? true)))
      ;; Outer (last-registered) step throws — it runs first, so the
      ;; throw escapes before the inner handler ever runs.
      (late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-outer" {}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-outer"
            ((late-bind/get-fn k) :arg))
          "the outer step's throw propagates")
      (is (false? @inner-ran?)
          "outer throws first, so the previous handler never runs — throws are not swallowed"))))

(deftest chain-fn!-propagates-throw-from-a-previous-step
  (testing "a throw from a previous (inner) step also propagates"
    (let [k          :test/g2-throw-inner
          outer-ran? (atom false)]
      ;; Inner step (registered first) throws.
      (late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-inner" {}))))
      ;; Outer step runs first, succeeds, then invokes the throwing inner.
      (late-bind/chain-fn! k (fn [_] (reset! outer-ran? true)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-inner"
            ((late-bind/get-fn k) :arg))
          "the inner step's throw propagates out of the chained hook")
      (is (true? @outer-ran?)
          "the outer step ran first (before the inner throw)"))))

(deftest chain-fn!-invalidates-the-cache-slot
  (testing "chain-fn! re-publishes via set-fn!, so a previously-cached resolution is dropped"
    (let [k :test/g2-cache-invalidate]
      ;; Seed an initial direct fn and warm the cache.
      (late-bind/set-fn! k (fn [_] :seed))
      (is (some? (late-bind/get-fn-cached k)) "warm the slot")
      (is (cached? k))
      ;; chain-fn! wraps it — must invalidate the slot.
      (late-bind/chain-fn! k (fn [_] nil))
      (is (not (cached? k))
          "chain-fn! invalidated the cache slot so the next dispatch sees the chained hook"))))
