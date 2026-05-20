(ns re-frame2-pair-mcp.tools.raw-state
  "Raw-state boot-gate (rf2-c2dtu).

  Pair2-mcp's direct-read surfaces (`snapshot`, `get-path`, `subscribe`
  on `:epoch`) can return verbatim slices of a live app's state. The
  framework's per-call privacy table already defaults `:include-sensitive`
  to `false` and `:elision` to `true`, but a caller can still opt in
  to raw state by passing the args explicitly — and the preload runtime's
  `app-db-reset!` emits both before- and after-states through `tap>`
  unconditionally.

  The boot-gate closes both holes. When `--allow-raw-state` is OFF (the
  published-build default), re-frame2-pair-mcp:

  1. FORCES `:include-sensitive false` on every snapshot / get-path /
     subscribe call, regardless of what the caller passed.
  2. FORCES `:elision true` on every snapshot / get-path call,
     regardless of what the caller passed.
  3. Signals the preload runtime to default-elide its `tap>` emissions
     (see `runtime/configure-raw-state!`). This is one nREPL round-trip
     issued once per session at first tool use.

  When `--allow-raw-state` is ON, the per-call args win — the same
  behaviour re-frame2-pair-mcp shipped pre-rf2-c2dtu.

  ## Single intention-naming predicate (rf2-p1qli)

  Call sites consume the gate state through ONE predicate:

      (raw-state/raw-state-allowed?)
      ; ⇒ true when --allow-raw-state was passed at launch
      ; ⇒ false otherwise (the published-build default)

  Per-tool bodies branch on this predicate directly — no inversion
  layer, no `force-*?` predicate-pair gymnastics:

      incl?    (if (raw-state/raw-state-allowed?)
                 (args/parse-bool-arg raw-args :include-sensitive)
                 false)
      elision? (if (raw-state/raw-state-allowed?)
                 (args/parse-bool-arg raw-args :elision)
                 true)

  The predicate name asserts the operator's opt-in state directly —
  the truthy value means \"the operator opted in via --allow-raw-state\".
  This replaces the prior `force-redact?` / `force-elision?` pair which
  returned `(not @allow-raw-state?)` and required three negations to
  trace through (see rf2-p1qli / audit Finding #2).

  Symmetric with:
    - rf2-zyoj2 `--allow-eval`  (eval-cljs in re-frame2-pair-mcp; tools/eval-cljs.cljs)
    - rf2-uaymx (b) `--allow-sensitive-reads` (story-mcp; in flight)

  The gate is a single atom (`allow-raw-state?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. Tests flip the atom directly via `set-allow-raw-state!`."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

(defonce ^:private allow-raw-state?
  ;; Default OFF in published builds. `server.cljs/main` flips this to
  ;; `true` when `--allow-raw-state` is present in `process.argv`.
  (atom false))

(defn set-allow-raw-state!
  "Set the raw-state launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! allow-raw-state? (boolean enabled?)))

(defn allow-raw-state-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @allow-raw-state?)

(defn raw-state-allowed?
  "Single intention-naming predicate (rf2-p1qli).

  Returns `true` when the operator opted in via `--allow-raw-state` at
  server launch; `false` otherwise (the published-build default).

  Call sites branch on this predicate directly:

      ;; :include-sensitive — gate-on → caller's arg; gate-off → false
      incl?    (if (raw-state/raw-state-allowed?)
                 (args/parse-bool-arg raw-args :include-sensitive)
                 false)

      ;; :elision — gate-on → caller's arg; gate-off → true (force walker)
      elision? (if (raw-state/raw-state-allowed?)
                 (args/parse-bool-arg raw-args :elision)
                 true)

  Replaces the pre-rf2-p1qli `force-redact?` / `force-elision?`
  predicate-pair, both of which returned `(not @allow-raw-state?)` and
  required three negations at the call site to answer \"did the operator
  opt in?\" The new predicate is positive-sense, single-name, and
  matches its truth value to the operator's intent."
  []
  @allow-raw-state?)

;; ---------------------------------------------------------------------------
;; Preload-runtime signal — default-elide tap> emissions when the gate is OFF.
;; ---------------------------------------------------------------------------
;;
;; The preload runtime's `app-db-reset!` emits both the pre- and post-reset
;; app-db values through `tap>` unconditionally — any registered tap
;; consumer (10x, custom dev panels, the user's own `add-tap` call) sees
;; the full state. That bypasses re-frame2-pair-mcp's wire-boundary redaction.
;;
;; The runtime exposes `configure-raw-state!` (rf2-c2dtu) which sets a
;; per-runtime flag controlling whether `app-db-reset!` taps raw values or
;; redacts via `elide-wire-value`. The MCP server pushes its boot-gate
;; state into the runtime once per session.

(defonce ^:private runtime-signalled?
  ;; Per-build-id flag — true once `configure-raw-state!` has been called
  ;; against this build's runtime in the current server lifetime.
  ;;
  ;; Build-keyed because re-frame2-pair-mcp can talk to multiple shadow-cljs builds
  ;; over the same nREPL connection; each build has its own preloaded
  ;; runtime atom.
  (atom #{}))

(defn signal-runtime!
  "Send the boot-gate state to the preload runtime exactly once per
  build-id per server lifetime. The runtime's `configure-raw-state!`
  flips its own atom — subsequent `app-db-reset!` calls then elide
  before emitting through `tap>`.

  Idempotent: a no-op once the signal has landed for `build-id`. Failure
  (the runtime predates rf2-c2dtu) is swallowed silently — the wire-side
  enforcement still holds, so a degraded runtime just means `tap>`
  consumers see raw values (the pre-rf2-c2dtu posture).

  Called from `tools/invoke` before the first state-emitting tool body
  runs. Returns a Promise resolving to nil."
  [conn build-id]
  (if (contains? @runtime-signalled? build-id)
    (js/Promise.resolve nil)
    (let [form (ef/emit
                 (ef/rt-call 'configure-raw-state!
                             {:allow-raw-state? @allow-raw-state?}))]
      (swap! runtime-signalled? conj build-id)
      (-> (nrepl/cljs-eval-value conn build-id form)
          (.then (fn [_] nil))
          (.catch (fn [_]
                    ;; Degraded runtime — predates rf2-c2dtu. Swallow;
                    ;; the wire-side gate still enforces.
                    nil))))))

(defn reset-runtime-signal-cache!
  "Clear the per-session signal cache. Exposed for tests."
  []
  (reset! runtime-signalled? #{}))
