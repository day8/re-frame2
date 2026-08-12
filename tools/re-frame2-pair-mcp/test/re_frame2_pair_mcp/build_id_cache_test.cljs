(ns re-frame2-pair-mcp.build-id-cache-test
  "Unit tests for the session-scoped `:resolved-build-id` cache.

  Adjacent to `probe_test.cljs`, which pins the `:probed-builds` cache.
  The two caches share a lifecycle (cleared by `close!` and reborn empty
  on a fresh `ensure-connection!` conn, but PRESERVED across a transient
  same-port socket reopen); their semantics differ:

    - `:probed-builds` is a set keyed by build-id, tracking which builds
      have had their `__re_frame2_pair_runtime` marker confirmed live.
    - `:resolved-build-id` is the single build-id `discover-app` last
      resolved — the default for tool calls that don't pass `:build`.

  The cache removes a pair-debug friction: without it, after a successful
  `discover-app` against `examples/step-deck`, every subsequent tool
  call still needs `build: examples/step-deck` or it silently defaults
  to `:app` (the env-var fallback) and returns `:runtime-not-preloaded`
  looking like a fresh discovery failure.

  The cache has three pieces, exercised by the deftests below:

    1. `discover-app` writes the resolved build-id into the conn-atom
       on success.
    2. `wire/arg-build` consults the cache before the env-var fallback
       when no explicit `:build` arg is passed.
    3. An explicit `:build` arg always wins (no surprise).
    4. nREPL `close!` clears the cache (a transport-only `connect!`
       reopen of the same port deliberately PRESERVES it)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
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
;; `wire/arg-build` — colon tolerance.
;; ---------------------------------------------------------------------------

(deftest arg-build-tolerates-a-leading-colon
  ;; The human-facing hint shows the colon form
  ;; (`--build=:examples/step-deck`); the MCP arg also accepts the bare
  ;; form. Both forms resolve to the SAME keyword, and a doubled colon
  ;; never reaches the resolver (no `::examples/step-deck`).
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
  ;; The non-namespaced case: `app` and `:app` both land on `:app`,
  ;; never the malformed `::app` (a `user`-ns keyword) that would probe a
  ;; build that doesn't exist.
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
  ;; The contract: a cached resolved-build-id is the default when the
  ;; operator omits `:build` — overrides the `:app` env fallback.
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
  ;; eval-path resolver honours it verbatim rather than second-guessing
  ;; via auto-detect. Without this, the cache would be useless on a
  ;; multi-build workspace.
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
;; Single-build auto-selection.
;;
;; When EXACTLY ONE build runs, discover-app with an omitted :build
;; selects that build and notes the choice — so a checkout where :app
;; isn't the running watch still resolves on the first no-arg call.
;; Zero/many running falls back to the diagnostic (which lists the
;; running builds) — never a silent most-recently-active pick.
;; ---------------------------------------------------------------------------

(defn- with-running-builds!
  "Stub `probe/running-builds` to resolve to `running-vec` and
  `nrepl/cljs-eval-value` to resolve to `health` (the runtime probe +
  health call). Restores both in `.finally`."
  [running-vec health body-fn]
  (let [orig-running probe/running-builds
        orig-eval    nrepl/cljs-eval-value
        eval-stub    (fn
                       ([_c _b _f] (js/Promise.resolve health))
                       ([_c _b _f _o] (js/Promise.resolve health)))]
    (set! probe/running-builds (fn [_conn] (js/Promise.resolve running-vec)))
    (set! nrepl/cljs-eval-value eval-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (set! probe/running-builds orig-running)
                    (tu/restore-eval! eval-stub orig-eval))))))

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

;; ---------------------------------------------------------------------------
;; Round-trippable build ids.
;;
;; The canonical build id is a keyword (`:examples/step-deck`). A value
;; copied out of a discover-app result's `:build` / `:build-id` slot must
;; resolve back to the SAME build when passed as a later `:build` arg.
;; The only alias axis is colon-tolerance (the EDN-ish `":foo"` vs bare
;; `"foo"` an agent might serialise); `fresh-keyword` normalises both to
;; the canonical keyword.
;; ---------------------------------------------------------------------------

(deftest discover-app-echoes-canonical-round-trippable-build
  (async done
    (let [conn (fresh-conn)
          _    (prime-probe-cache! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      (-> (tu/with-stubbed-eval! healthy-health
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                ;; Both :build-id and the input-name-matching :build carry
                ;; the canonical keyword.
                (is (= :examples/step-deck (:build-id edn)))
                (is (= :examples/step-deck (:build edn))
                    "discover-app echoes a canonical :build matching the input arg name")
                ;; Round-trip: feed the echoed value back as a `:build`
                ;; arg and confirm it resolves to the same keyword.
                (is (= (:build edn)
                       (wire/arg-build conn (tu/args->js {:build (:build edn)})))
                    "the echoed :build round-trips unchanged through arg-build"))
              (done)))))))

(deftest canonical-build-round-trips-from-both-alias-forms
  ;; The echoed canonical keyword, re-serialised either as the bare
  ;; qualified string or the colon form, resolves identically — the
  ;; deterministic alias contract. The two alias forms are
  ;; exactly the colon-tolerance axis: `subs (str kw) 1` (drop the
  ;; leading `:`) and `str kw` (keep it) both read back to `kw`.
  (let [conn      (fresh-conn)
        canonical :examples/step-deck
        bare      (subs (str canonical) 1)   ; "examples/step-deck"
        colon     (str canonical)]           ; ":examples/step-deck"
    (is (= "examples/step-deck" bare) "sanity: the bare qualified form")
    (is (= canonical (wire/arg-build conn (tu/args->js {:build bare})))
        "bare qualified string round-trips to the canonical keyword")
    (is (= canonical (wire/arg-build conn (tu/args->js {:build colon})))
        "colon-prefixed form round-trips identically")
    (is (= (wire/arg-build conn (tu/args->js {:build bare}))
           (wire/arg-build conn (tu/args->js {:build colon})))
        "the two alias forms are indistinguishable post-coercion")))

;; ---------------------------------------------------------------------------
;; Per-session isolation.
;;
;; The sticky target lives on the per-connection conn-atom, NOT in a
;; process-global. The MCP stdio model spawns one server process (and
;; thus one session-state / conn-atom) per client, so distinct sessions
;; are distinct conn-atoms by construction. This pins that a write to one
;; conn's resolved-build-id never leaks into another conn.
;; ---------------------------------------------------------------------------

(deftest resolved-build-id-does-not-leak-across-sessions
  (let [session-a (fresh-conn)
        session-b (fresh-conn)
        no-build  (tu/args->js {})]
    ;; Session A discovers / sticks examples/step-deck.
    (wire/mark-resolved-build-id! session-a :examples/step-deck)
    ;; Session B sticks a DIFFERENT build.
    (wire/mark-resolved-build-id! session-b :testbeds/panel-gallery)
    (is (= :examples/step-deck (wire/arg-build session-a no-build))
        "session A resolves to ITS sticky target")
    (is (= :testbeds/panel-gallery (wire/arg-build session-b no-build))
        "session B resolves to ITS sticky target — no cross-session leak")
    ;; A fresh, untouched session falls through to the env default — it
    ;; inherits NOTHING from A or B (no process-global).
    (is (= :app (wire/arg-build (fresh-conn) no-build))
        "a fresh session sees no sticky target from other sessions")))

(deftest sticky-target-is-not-a-process-global
  ;; Belt-and-braces: the sticky state is keyed on the conn-atom identity.
  ;; Two conns with the same shape are independent atoms; mutating one's
  ;; :resolved-build-id must not be observable on the other.
  (let [a (fresh-conn)
        b (fresh-conn)]
    (swap! a assoc :resolved-build-id :only-a)
    (is (= :only-a (:resolved-build-id @a)))
    (is (nil? (:resolved-build-id @b))
        "the sticky target is per-conn-atom, never shared across sessions")))

;; ---------------------------------------------------------------------------
;; Ambiguous-target / no-target structured error.
;;
;; "If no session target exists and multiple runtimes are available, fail
;; with a clear ambiguous-target error listing candidates." The eval-path
;; resolver (`resolve-build!`, used by eval-cljs) rejects with a
;; structured `:no-runtime-for-build` ex-info enumerating `:running-builds`
;; when the build is the bare default (no explicit/cached choice) and
;; zero-or-many builds run — never a silent wrong-build pick, never a
;; host-level transport failure. (The plain read path surfaces the same
;; candidate list via the diagnostic ladder's `:build-not-running` rung,
;; pinned in probe_test.)
;; ---------------------------------------------------------------------------

(deftest resolve-build-rejects-with-candidates-when-no-target-and-many-run
  (async done
    ;; NB restore `probe/running-builds` INLINE before calling `done` — a
    ;; `.finally`-scoped restore fires AFTER `done` advances to the next
    ;; test and would leak the multi-build stub into a neighbour (the
    ;; cross-test stub race the orient/invoke suites document). Both arms
    ;; restored identically, so the restore now sits in the single trailing
    ;; step, still ahead of the `done` in that same step.
    (let [orig probe/running-builds
          restore! (fn [] (set! probe/running-builds orig))
          conn (fresh-conn)]
      ;; No cached build, no explicit arg → build is the bare :app default
      ;; (explicit? false). Two builds run → ambiguous.
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/step-deck :testbeds/panel-gallery])))
      ;; The rejection arm IS this row's success path, so both handlers are
      ;; siblings of one two-arg `.then`, with one trailing `done`.
      (-> (probe/resolve-build! conn :app false)
          (.then (fn [_]
                   (is false "must reject on an ambiguous target"))
                 (fn [err]
                   (let [data (ex-data err)]
                     (is (= :no-runtime-for-build (:reason data))
                         "structured reason, not a host failure")
                     (is (= [:examples/step-deck :testbeds/panel-gallery]
                            (:running-builds data))
                         "the error lists the candidate builds"))))
          (.then (fn [_] (restore!) (done)))))))

(deftest resolve-build-honours-cached-session-target-over-ambiguity
  ;; A session-sticky target (set by a prior discover-app) is treated as
  ;; deliberate — `resolve-build!` resolves to it verbatim even on a
  ;; multi-build workspace, so the sticky default actually removes the
  ;; ambiguity instead of re-triggering it.
  (async done
    (let [conn (fresh-conn)]
      ;; Cache a session target, then resolve with explicit? derived from
      ;; the cache (arg-build-explicit? treats a cache hit as deliberate).
      (swap! conn assoc :resolved-build-id :examples/step-deck)
      (let [bid       (wire/arg-build conn (tu/args->js {}))
            explicit? (wire/arg-build-explicit? conn (tu/args->js {}))]
        (is (= :examples/step-deck bid))
        (is (true? explicit?) "a cached session target counts as deliberate")
        (-> (probe/resolve-build! conn bid explicit?)
            (.then (fn [resolved]
                     (is (= :examples/step-deck resolved)
                         "the sticky session target wins — no ambiguous-target error")))
            (.catch (fn [_] (is false "must not reject when a session target is cached") nil))
            (.then (fn [_] (done))))))))

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

;; ---------------------------------------------------------------------------
;; :port-discover sticky path — faithful no-pre-probe end-to-end.
;;
;; The flow these tests guard (multi-build):
;;
;;   discover-app {port 8033} -> OK, resolves :examples/machine-epochs
;;   orient {}  (NO :build)   -> must target :examples/machine-epochs
;;
;; `sticky_build_invoke_test/port-discover-sticks-build-through-invoke`
;; drives discover-app{port} then a no-build call through `tools/invoke`,
;; but it PRE-SEEDS `:probed-builds` with the resolved build, so
;; `discover-app`'s own `ensure-runtime!` short-circuits without the real
;; preload probe. In the LIVE flow the `:port`-resolved build is NOT
;; pre-probed — discover-app must run the actual `runtime-preloaded?`
;; round-trip (a DISTINCT eval form from the `(runtime/health)` read) and
;; `mark-conn-probed!` itself before reaching the cache-writing branch.
;;
;; These tests cover that path: a form-DISCRIMINATING stub answers the
;; preload-probe form with `true` and the health form with the health map
;; — exactly what a live runtime returns — so the `:port` branch exercises
;; the real probe-then-mark-then-cache sequence, then a no-build call
;; THROUGH `tools/invoke` (incl. the `canonicalize-build-step` that reads
;; the sticky default back) must land on the resolved build, NOT `:app`.
;; ---------------------------------------------------------------------------

(defn- preload-probe-form?
  "True for the `runtime-preloaded?` sentinel-probe eval form (it tests
  the `__re_frame2_pair_runtime` global), false for the
  `(re-frame2-pair.runtime/health)` read. Lets a single stub mimic a live
  runtime: `true` to the probe, the health map to the health read — so
  `discover-app`'s `ensure-runtime!` does the REAL probe + `mark-conn-probed!`
  instead of being short-circuited by a pre-seeded `:probed-builds`."
  [form-str]
  (and (string? form-str)
       (re-find #"__re_frame2_pair_runtime" form-str)))

(defn- live-like-eval-stub
  "A `cljs-eval-value` stub that answers like a CONNECTED runtime on a
  build that was never pre-probed: `true` for the preload-sentinel probe,
  `health` for the `(runtime/health)` read. Both arities."
  [health]
  (fn
    ([_c _b form] (js/Promise.resolve (if (preload-probe-form? form) true health)))
    ([_c _b form _o] (js/Promise.resolve (if (preload-probe-form? form) true health)))))

(deftest port-discover-without-pre-probe-still-caches
  ;; discover-app{port} on a multi-build setup, with NO pre-seeded
  ;; `:probed-builds` — discover-app must probe the resolved build live,
  ;; mark it probed, AND write `:resolved-build-id`. The faithful model of
  ;; a live first-contact call.
  (async done
    (let [conn         (fresh-conn)
          orig-running probe/running-builds
          orig-port    probe/resolve-build-by-port
          orig-eval    nrepl/cljs-eval-value
          orig-jvm     nrepl/jvm-eval
          eval-stub    (live-like-eval-stub healthy-health)
          jvm-stub     (fn [& _] (js/Promise.resolve {:value ""}))]
      ;; multi-build workspace; the port resolves to machine-epochs.
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve :examples/machine-epochs)))
      (set! nrepl/cljs-eval-value eval-stub)
      ;; freshness JVM-half degrades to :unknown (harmless): this suite's
      ;; fresh-conn carries no :socket, so jvm-build-freshness short-circuits
      ;; to nil without a round-trip. The jvm-eval stub is belt-and-braces.
      (set! nrepl/jvm-eval jvm-stub)
      (-> (discover-app/discover-app conn (tu/args->js {:port 8033}))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (true? (:ok? edn))
                    "discover-app{port} succeeds against the live-like runtime")
                (is (= :examples/machine-epochs (:build-id edn))
                    "resolved the build serving the port")
                (is (contains? (:probed-builds @conn) :examples/machine-epochs)
                    "discover-app probed + marked the resolved build live (no pre-seed)")
                (is (= :examples/machine-epochs (:resolved-build-id @conn))
                    "the :port-resolved build is cached even without a pre-probe — the live gap"))))
          (.finally (fn []
                      (set! probe/running-builds orig-running)
                      (set! probe/resolve-build-by-port orig-port)
                      (tu/restore-eval! eval-stub orig-eval)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)))
          (.then (fn [_] (done)))))))

(deftest port-discover-no-pre-probe-sticks-through-invoke
  ;; THE acceptance: discover-app{port} (no pre-probe) then a no-build
  ;; `get-path` THROUGH `tools/invoke` (the single MCP egress, incl.
  ;; `canonicalize-build-step`) lands on the resolved build, NOT `:app`.
  (async done
    (let [conn          (fresh-conn)
          captured      (atom :NOT-CALLED)
          orig-running  probe/running-builds
          orig-port     probe/resolve-build-by-port
          orig-eval     nrepl/cljs-eval-value
          orig-jvm      nrepl/jvm-eval
          orig-get-path get-path/get-path-tool
          eval-stub     (live-like-eval-stub healthy-health)
          jvm-stub      (fn [& _] (js/Promise.resolve {:value ""}))]
      (set! probe/running-builds
            (fn [_] (js/Promise.resolve [:examples/machine-epochs :examples/standard-epochs])))
      (set! probe/resolve-build-by-port (fn [_c _p] (js/Promise.resolve :examples/machine-epochs)))
      (set! nrepl/cljs-eval-value eval-stub)
      (set! nrepl/jvm-eval jvm-stub)
      (set! get-path/get-path-tool
            (fn [c args]
              (reset! captured (wire/arg-build c args))
              (js/Promise.resolve
                #js {:content #js [#js {:type "text" :text "{:ok? true}"}]})))
      (-> (discover-app/discover-app conn (tu/args->js {:port 8033}))
          (.then (fn [_]
                   ;; second call: NO :build arg, through the invoke egress.
                   (tools/invoke conn "get-path" (tu/args->js {:path "[:k]"}) nil)))
          (.then (fn [_]
                   (is (= :examples/machine-epochs @captured)
                       "post-discover-app{port} (no pre-probe), a no-build call THROUGH invoke targets the resolved build")
                   (is (not= :app @captured)
                       "it must NOT fall back to the :app env default (the live-repro bug)")))
          (.finally (fn []
                      (set! probe/running-builds orig-running)
                      (set! probe/resolve-build-by-port orig-port)
                      (tu/restore-eval! eval-stub orig-eval)
                      (tu/restore-jvm-eval! jvm-stub orig-jvm)
                      (set! get-path/get-path-tool orig-get-path)))
          (.then (fn [_] (done)))))))
