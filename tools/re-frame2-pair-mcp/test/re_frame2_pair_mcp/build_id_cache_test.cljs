(ns re-frame2-pair-mcp.build-id-cache-test
  "Unit tests for the session-scoped `:resolved-build-id` cache (rf2-l9ixp).

  Adjacent to `probe_test.cljs`, which pins the `:probed-builds` cache
  (rf2-sjpx0). The two caches share a lifecycle (reset on (re)connect
  and `close!`); their semantics differ:

    - `:probed-builds` is a set keyed by build-id, tracking which builds
      have had their `__re_frame2_pair_runtime` marker confirmed live.
    - `:resolved-build-id` is the single build-id `discover-app` last
      resolved — the default for tool calls that don't pass `:build`.

  The cache removes a 2026-05-25 pair-debug friction: after a successful
  `discover-app` against `examples/step-deck`, every subsequent tool
  call still needed `build: examples/step-deck` or it silently defaulted
  to `:app` (the env-var fallback) and returned `:runtime-not-preloaded`
  looking like a fresh discovery failure.

  The fix has three pieces, exercised by the deftests below:

    1. `discover-app` writes the resolved build-id into the conn-atom
       on success.
    2. `wire/arg-build` consults the cache before the env-var fallback
       when no explicit `:build` arg is passed.
    3. An explicit `:build` arg always wins (no surprise).
    4. nREPL `connect!` / `close!` clear the cache."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ---------------------------------------------------------------------------
;; Fixtures.
;; ---------------------------------------------------------------------------

(defn- fresh-conn
  "A conn-atom shaped like one fresh out of `connect!` — `:probed-builds`
  cleared, `:resolved-build-id` nil. Mirrors the `probe_test/fresh-conn`
  shape so the two caches' lifecycle assertions read the same."
  []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(def ^:private healthy-health
  "Canonical `(runtime/health)` payload — the shape `discover-app` reads
  to decide which warning branch (if any) to surface. The `:ok? true`
  branch with no warnings exercises the cache-write path."
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default]
   :ambiguous-frame?           false})

;; ---------------------------------------------------------------------------
;; `wire/arg-build` — colon tolerance (rf2-8ohwv).
;; ---------------------------------------------------------------------------

(deftest arg-build-tolerates-a-leading-colon
  ;; rf2-8ohwv: the human-facing hint shows the colon form
  ;; (`--build=:examples/step-deck`) but the MCP arg used to want the bare
  ;; form — a leading colon double-prepended into the malformed
  ;; `::examples/step-deck` and failed the round-trip. Both forms must now
  ;; resolve to the SAME keyword, and a doubled colon must never reach the
  ;; resolver.
  (let [conn (fresh-conn)]
    (is (= :examples/step-deck
           (wire/arg-build conn (tu/args->js {:build "examples/step-deck"})))
        "bare form")
    (is (= :examples/step-deck
           (wire/arg-build conn (tu/args->js {:build ":examples/step-deck"})))
        "colon form resolves identically")
    (is (= (wire/arg-build conn (tu/args->js {:build "examples/step-deck"}))
           (wire/arg-build conn (tu/args->js {:build ":examples/step-deck"})))
        "the two forms are indistinguishable post-coercion")))

(deftest arg-build-colon-tolerance-on-bare-build
  ;; The non-namespaced case the original `keyword` mishandled too:
  ;; `:app` → `::app` (a `user`-ns keyword) → probes a build that doesn't
  ;; exist. Both forms must land on `:app`.
  (let [conn (fresh-conn)]
    (is (= :app (wire/arg-build conn (tu/args->js {:build "app"}))))
    (is (= :app (wire/arg-build conn (tu/args->js {:build ":app"}))))))

(deftest arg-build-explicit-predicate-sees-either-colon-form
  ;; Explicitness keys on arg PRESENCE, not coercion shape — both the
  ;; bare and colon forms count as a deliberate `:build`.
  (let [conn (fresh-conn)]
    (is (true? (wire/arg-build-explicit? conn (tu/args->js {:build "app"}))))
    (is (true? (wire/arg-build-explicit? conn (tu/args->js {:build ":app"}))))))

;; ---------------------------------------------------------------------------
;; `wire/arg-build` — cache lookup precedence.
;; ---------------------------------------------------------------------------

(deftest arg-build-without-cache-uses-env-default
  ;; Baseline: nothing in the cache, no `:build` arg → env default `:app`.
  (let [conn (fresh-conn)
        args (tu/args->js {})]
    (is (= :app (wire/arg-build conn args)))))

(deftest arg-build-with-cache-uses-cached-build-id
  ;; rf2-l9ixp contract: a cached resolved-build-id is the default when
  ;; the operator omits `:build` — overrides the `:app` env fallback.
  (let [conn (fresh-conn)
        args (tu/args->js {})]
    (swap! conn assoc :resolved-build-id :examples/step-deck)
    (is (= :examples/step-deck (wire/arg-build conn args)))))

(deftest arg-build-explicit-arg-overrides-cache
  ;; Explicit-wins rule: a `:build` MCP arg ALWAYS beats the cache.
  ;; Operator can route a one-off call to a different build without
  ;; clearing the session cache.
  (let [conn (fresh-conn)
        args (tu/args->js {:build "other-build"})]
    (swap! conn assoc :resolved-build-id :examples/step-deck)
    (is (= :other-build (wire/arg-build conn args))
        "Explicit :build arg must win over the cache")))

(deftest arg-build-nil-conn-falls-through-to-env-default
  ;; Defensive: the 1-arity legacy form (and any caller passing nil
  ;; conn) skips the cache lookup without throwing. Conformance tests
  ;; rely on this stub-conn-friendly shape.
  (let [args (tu/args->js {})]
    (is (= :app (wire/arg-build nil args)))
    (is (= :app (wire/arg-build args))
        "1-arity legacy form must still resolve to the env default")))

(deftest arg-build-explicit-predicate-treats-cache-as-deliberate
  ;; A session-cache hit is treated as a deliberate choice — the
  ;; eval-path resolver (rf2-ivlb3) honours it verbatim rather than
  ;; second-guessing via auto-detect. Without this, the cache would be
  ;; useless on a multi-build workspace.
  (let [conn (fresh-conn)
        args (tu/args->js {})]
    (is (false? (wire/arg-build-explicit? conn args))
        "Fresh conn + no arg → not explicit")
    (swap! conn assoc :resolved-build-id :examples/step-deck)
    (is (true? (wire/arg-build-explicit? conn args))
        "Cache hit must count as deliberate")
    (is (false? (wire/arg-build-explicit? nil args))
        "1-arity legacy form (no conn) ignores the cache")))

;; ---------------------------------------------------------------------------
;; `discover-app` populates the cache on success.
;; ---------------------------------------------------------------------------

(defn- prime-probe-cache!
  "Pre-populate `:probed-builds` so `runtime-preloaded?` short-circuits
  without hitting the stub — the stub then sees only the
  `(runtime/health)` round-trip and we don't need to discriminate by
  form string."
  [conn build-id]
  (swap! conn update :probed-builds (fnil conj #{}) build-id))

(deftest discover-app-caches-resolved-build-id-on-success
  ;; The acceptance criterion: a successful `discover-app` records the
  ;; build-id on the conn so subsequent tool calls don't need `:build`.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      (-> (tu/with-stubbed-eval! healthy-health
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [_result]
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "Successful discover-app must cache the resolved build-id")
              (done)))))))

(deftest discover-app-cache-survives-into-subsequent-arg-build-call
  ;; End-to-end: after a discover-app run, the next call's `arg-build`
  ;; (with no `:build` arg) routes to the same build discover-app
  ;; resolved — the friction the bead removes.
  (async done
    (let [conn          (fresh-conn)
          _             (prime-probe-cache! conn :examples/step-deck)
          discover-args (tu/args->js {:build "examples/step-deck"})
          tool-args     (tu/args->js {})]
      (-> (tu/with-stubbed-eval! healthy-health
            (fn [] (discover-app/discover-app conn discover-args)))
          (.then
            (fn [_]
              (is (= :examples/step-deck (wire/arg-build conn tool-args))
                  "Post-discover-app, omitted :build must default to the cached id")
              (done)))))))

(deftest discover-app-does-not-cache-on-precondition-failure
  ;; `discover-app` short-circuits on precondition failures (e.g.
  ;; `:debug-enabled? false`) without caching — the build isn't a usable
  ;; default in that state, so subsequent tool calls should NOT silently
  ;; route to it.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})
          unhealthy (assoc healthy-health :debug-enabled? false)]
      (-> (tu/with-stubbed-eval! unhealthy
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [_]
              (is (nil? (:resolved-build-id @conn))
                  "Failed precondition must not populate the cache")
              (done)))))))

(deftest discover-app-caches-on-warning-branches
  ;; The ambiguous-frame and no-source-coord-annotation branches return
  ;; `:ok? true` with a warning — the runtime IS reachable on that
  ;; build, just with a caveat. Cache the build-id so the operator
  ;; doesn't have to keep re-specifying it on follow-up calls.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})
          ambiguous (assoc healthy-health
                           :frames [:rf/default :feature/sandbox]
                           :ambiguous-frame? true)]
      (-> (tu/with-stubbed-eval! ambiguous
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [_]
              (is (= :examples/step-deck (:resolved-build-id @conn))
                  "Ambiguous-frame warning is still a discoverable build — cache it")
              (done)))))))

;; ---------------------------------------------------------------------------
;; Cache invalidation — shared lifecycle with `:probed-builds`.
;; ---------------------------------------------------------------------------

(deftest cache-cleared-by-close
  ;; `nrepl/close!` drops `:resolved-build-id` so a reconnect doesn't
  ;; carry a stale build-id from the previous session — the operator
  ;; may have restarted shadow against a different build between
  ;; reconnects.
  (let [conn (fresh-conn)]
    (swap! conn assoc :resolved-build-id :examples/step-deck)
    (nrepl/close! conn)
    (is (nil? (:resolved-build-id @conn))
        "close! must drop the resolved-build-id cache")))

(deftest make-conn-initialises-cache-to-nil
  ;; A fresh conn-atom has the slot present and nil — `arg-build` falls
  ;; through to the env default cleanly, and `connect!` doesn't need to
  ;; create the slot, just reset it.
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (is (contains? @conn :resolved-build-id))
    (is (nil? (:resolved-build-id @conn)))))
