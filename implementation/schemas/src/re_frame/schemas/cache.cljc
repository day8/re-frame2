(ns re-frame.schemas.cache
  "Clearable memo cache primitive (rf2-17sqc shape, consolidated rf2-mse54).

  Two schemas-slice memos — the digest printer (`validator/default-edn-print`)
  and the sensitive-path walker (`walker/extract-sensitive-paths-from-schema`)
  — share one requirement: a process-lifetime memo that is *clearable* for
  test isolation. `clojure.core/memoize` exposes no clear hook, so a test
  that registers many distinct fresh schemas (the concurrency-stress
  harness) could never reset the process-lifetime cache; both memos hand-
  rolled the same atom-backed lookup-or-compute + reset! clear pair.

  `clearable-memo` is that pair, parameterised on the underlying fn.

  ## Boot-once invariant (Mike ruled rf2-17sqc, option B)

  App schemas are registered ONCE at boot, so each memo is bounded by the
  registered-schema cardinality and is intentionally NOT evicted — no
  bounded LRU. The only scenario that violates boot-once is a test that
  deliberately registers many distinct fresh schemas; such tests call the
  returned `clear!` fn in fixture teardown so the cache doesn't grow
  unbounded across the suite. See [010 §Schema digest].")

#?(:clj (set! *warn-on-reflection* true))

(defn clearable-memo
  "Return `[memoized-fn clear!]` — an atom-backed memo of `f` plus a
  reset hook.

  `memoized-fn` has the same arity as `f`; results are cached by the
  argument vector (`find`/`val` so a cached `nil`/`false` result is a
  hit, not a recompute). Behaviour for callers is byte-identical to
  `(memoize f)` — same args always return the same value — but the
  cache is *clearable*.

  `clear!` resets the cache to `{}` and returns nil — the test-support
  hook (schemas register once at boot, so production never needs it;
  tests that register many distinct fresh schemas call it in fixture
  teardown so the cache doesn't grow unbounded across the suite)."
  [f]
  (let [cache (atom {})]
    [(fn [& args]
       (if-let [e (find @cache args)]
         (val e)
         (let [v (apply f args)]
           (swap! cache assoc args v)
           v)))
     (fn [] (reset! cache {}) nil)]))
