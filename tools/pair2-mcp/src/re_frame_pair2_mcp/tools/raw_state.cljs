(ns re-frame-pair2-mcp.tools.raw-state
  "Raw-state boot-gate (rf2-c2dtu).

  Pair2-mcp's direct-read surfaces (`snapshot`, `get-path`, `subscribe`
  on `:epoch`) can return verbatim slices of a live app's state. The
  framework's per-call privacy table already defaults `:include-sensitive`
  to `false` and `:elision` to `true`, but a caller can still opt in
  to raw state by passing the args explicitly — and the preload runtime's
  `app-db-reset!` emits both before- and after-states through `tap>`
  unconditionally.

  The boot-gate closes both holes. When `--allow-raw-state` is OFF (the
  published-build default), pair2-mcp:

  1. FORCES `:include-sensitive false` on every snapshot / get-path /
     subscribe call, regardless of what the caller passed. The
     `force-redact?` predicate is the single arbiter.
  2. FORCES `:elision true` on every snapshot / get-path call,
     regardless of what the caller passed.
  3. Signals the preload runtime to default-elide its `tap>` emissions
     (see `runtime/configure-raw-state!`). This is one nREPL round-trip
     issued once per session at first tool use.

  When `--allow-raw-state` is ON, the per-call args win — the same
  behaviour pair2-mcp shipped pre-rf2-c2dtu.

  Symmetric with:
    - rf2-zyoj2 `--allow-eval`  (eval-cljs in pair2-mcp; tools/eval-cljs.cljs)
    - rf2-uaymx (b) `--allow-sensitive-reads` (story-mcp; in flight)

  The gate is a single atom (`allow-raw-state?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. Tests flip the atom directly via `set-allow-raw-state!`."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]))

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

(defn force-redact?
  "When the boot gate is OFF, per-call `:include-sensitive true` must
  not be honoured — return `true` so callers force the walker's
  `:rf.size/include-sensitive?` to `false`. When the boot gate is ON
  (operator opted in at server launch), the per-call arg wins; return
  `false`.

  Used by the snapshot / get-path / subscribe tool bodies to wrap the
  `parse-bool-arg :include-sensitive` value:

      (let [incl? (args/parse-bool-arg raw-args :include-sensitive)
            incl? (if (raw-state/force-redact?) false incl?)]
        ...)"
  []
  (not @allow-raw-state?))

(defn force-elision?
  "When the boot gate is OFF, per-call `:elision false` must not be
  honoured — return `true` so callers force the walker on. When the
  boot gate is ON, the per-call arg wins; return `false`.

  Used by the snapshot / get-path tool bodies to wrap the
  `parse-bool-arg :elision` value:

      (let [elision? (args/parse-bool-arg raw-args :elision)
            elision? (or elision? (raw-state/force-elision?))]
        ...)"
  []
  (not @allow-raw-state?))

;; ---------------------------------------------------------------------------
;; Preload-runtime signal — default-elide tap> emissions when the gate is OFF.
;; ---------------------------------------------------------------------------
;;
;; The preload runtime's `app-db-reset!` emits both the pre- and post-reset
;; app-db values through `tap>` unconditionally — any registered tap
;; consumer (10x, custom dev panels, the user's own `add-tap` call) sees
;; the full state. That bypasses pair2-mcp's wire-boundary redaction.
;;
;; The runtime exposes `configure-raw-state!` (rf2-c2dtu) which sets a
;; per-runtime flag controlling whether `app-db-reset!` taps raw values or
;; redacts via `elide-wire-value`. The MCP server pushes its boot-gate
;; state into the runtime once per session.

(defonce ^:private runtime-signalled?
  ;; Per-build-id flag — true once `configure-raw-state!` has been called
  ;; against this build's runtime in the current server lifetime.
  ;;
  ;; Build-keyed because pair2-mcp can talk to multiple shadow-cljs builds
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
