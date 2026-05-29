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
            [re-frame2-pair-mcp.tools.probe :as probe]
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

;; ---------------------------------------------------------------------------
;; Single-build auto-selection (rf2-v70kv).
;;
;; discover-app used to default an omitted :build to :app; on a checkout
;; where :app isn't the running watch, the first no-arg call failed by
;; construction. When EXACTLY ONE build runs, discover-app now selects it
;; and notes the choice. Zero/many running keeps the existing diagnostic
;; (which lists the running builds) — never a silent most-recently-active
;; pick.
;; ---------------------------------------------------------------------------

(defn- with-running-builds!
  "Stub `probe/running-builds` to resolve to `running-vec` and
  `nrepl/cljs-eval-value` to resolve to `health` (the runtime probe +
  health call). Restores both in `.finally`."
  [running-vec health body-fn]
  (let [orig-running probe/running-builds
        orig-eval    nrepl/cljs-eval-value]
    (set! probe/running-builds (fn [_conn] (js/Promise.resolve running-vec)))
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b _f] (js/Promise.resolve health))
            ([_c _b _f _o] (js/Promise.resolve health))))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (set! probe/running-builds orig-running)
                    (set! nrepl/cljs-eval-value orig-eval))))))

(deftest discover-app-auto-selects-the-single-running-build
  ;; No :build arg, exactly one running build → discover-app selects it,
  ;; notes the auto-selection, caches it, and echoes :auto-selected-build.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)]
      (-> (with-running-builds! [:examples/step-deck] healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {}))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (true? (:ok? edn)))
                (is (= :examples/step-deck (:build-id edn))
                    "auto-selected the single running build")
                (is (= :examples/step-deck (:auto-selected-build edn))
                    "result flags the auto-selection")
                (is (re-find #"auto-selected" (:note edn))
                    "note explains the auto-selection")
                (is (= :examples/step-deck (:resolved-build-id @conn))
                    "auto-selected build is cached for follow-up calls"))
              (done)))))))

(deftest discover-app-no-arg-does-not-auto-select-when-many-run
  ;; Two running builds, no :build arg → NO auto-select. The build falls
  ;; back to the :app default and the diagnostic surfaces the running
  ;; list (here: the build is running per the stub, so we assert the
  ;; payload is NOT auto-selected against :app rather than step-deck).
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :app)]
      (-> (with-running-builds! [:testbeds/panel-gallery :examples/step-deck]
                                healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {}))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                ;; The default :app is probed (the stub answers health for
                ;; any build) — the point is no SILENT pick of one of the
                ;; two ambiguous builds.
                (is (not (contains? edn :auto-selected-build))
                    "must NOT auto-select when multiple builds run")
                (is (= :app (:build-id edn))
                    "falls back to the :app default, not a guessed build"))
              (done)))))))

(deftest discover-app-explicit-build-skips-auto-select
  ;; An explicit :build arg is honoured verbatim — auto-select never
  ;; fires, even though only one OTHER build is running.
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :my-app)]
      (-> (with-running-builds! [:examples/step-deck] healthy-health
            (fn [] (discover-app/discover-app conn (tu/args->js {:build "my-app"}))))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (= :my-app (:build-id edn))
                    "explicit build used verbatim")
                (is (not (contains? edn :auto-selected-build))
                    "explicit build is not an auto-selection"))
              (done)))))))

(deftest auto-select-single-build-returns-pair
  ;; Unit-pin the probe helper directly: exactly-one → [build true];
  ;; zero/many → [nil false].
  (async done
    (let [conn (fresh-conn)
          orig probe/running-builds]
      (set! probe/running-builds (fn [_] (js/Promise.resolve [:only])))
      (-> (probe/auto-select-single-build conn)
          (.then (fn [[b auto?]]
                   (is (= :only b))
                   (is (true? auto?))
                   (set! probe/running-builds (fn [_] (js/Promise.resolve [:a :b])))
                   (probe/auto-select-single-build conn)))
          (.then (fn [[b auto?]]
                   (is (nil? b))
                   (is (false? auto?))
                   (set! probe/running-builds (fn [_] (js/Promise.resolve [])))
                   (probe/auto-select-single-build conn)))
          (.then (fn [[b auto?]]
                   (is (nil? b))
                   (is (false? auto?))))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))
