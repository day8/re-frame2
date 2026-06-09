(ns re-frame2-pair-mcp.tools.raw-state
  "Raw-state boot-gate (rf2-c2dtu).

  re-frame2-pair-mcp's direct-read surfaces (`snapshot`, `get-path`, `subscribe`
  on `:epoch`) can return verbatim slices of a live app's state. The
  framework's per-call privacy table already defaults `:include-sensitive`
  to `false` and `:elision` to `true`, but a caller can still opt in
  to raw state by passing the args explicitly — and the preload runtime's
  `app-db-reset!` emits both before- and after-states through `tap>`
  unconditionally.

  The boot-gate closes both holes. When `--allow-sensitive-reads` is OFF
  (the published-build default), re-frame2-pair-mcp:

  1. FORCES `:include-sensitive false` on every snapshot / get-path /
     subscribe call, regardless of what the caller passed.
  2. FORCES `:elision true` on every snapshot / get-path call,
     regardless of what the caller passed.
  3. Signals the preload runtime to default-elide its `tap>` emissions
     (see `runtime/configure-raw-state!`). This is one tiny idempotent
     nREPL round-trip issued before every state-emitting tool eval —
     re-signalled rather than cached-as-delivered, because the runtime's
     posture resets to its permissive default on every page/runtime
     reload (rf2-olvr5 finding 2; see `signal-runtime!`).

  When `--allow-sensitive-reads` is ON, the per-call args win — the same
  behaviour re-frame2-pair-mcp shipped pre-rf2-c2dtu.

  ## Single intention-naming predicate (rf2-p1qli)

  Call sites consume the gate state through ONE predicate:

      (raw-state/raw-state-allowed?)
      ; ⇒ true when --allow-sensitive-reads was passed at launch
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
  the truthy value means \"the operator opted in via --allow-sensitive-reads\".
  This replaces the prior `force-redact?` / `force-elision?` pair which
  returned `(not @allow-raw-state?)` and required three negations to
  trace through (see rf2-p1qli / audit Finding #2).

  Symmetric with:
    - rf2-uaymx (b) / rf2-g9fje `--allow-sensitive-reads` (story-mcp)

  Note: re-frame2-pair-mcp's `eval-cljs` gate was inverted in rf2-a0z0h
  — eval-cljs now defaults ON, with `--no-eval` as the opt-out. The
  raw-state gate keeps its default-OFF posture because privacy elision
  IS a separable protection (eval-cljs surfaces what an operator asked
  for; raw reads can pour the entire app-db into the wire log without
  the operator ever typing the secret).

  Per rf2-2x3ql the pair-mcp CLI flag is `--allow-sensitive-reads`
  (canonical cross-MCP name). The internal Clojure identifiers below
  retain `raw-state` for legacy reasons; only the operator-facing flag
  was renamed.

  The gate is a single atom (`allow-raw-state?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. Tests flip the atom directly via `set-allow-raw-state!`."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

(defonce ^:private allow-raw-state?
  ;; Default OFF in published builds. `server.cljs/main` flips this to
  ;; `true` when `--allow-sensitive-reads` is present in `process.argv`.
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

  Returns `true` when the operator opted in via `--allow-sensitive-reads`
  at server launch; `false` otherwise (the published-build default).

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
;; state into the runtime before every state-emitting eval — the
;; per-runtime flag resets to its permissive default on every page/runtime
;; reload, so the posture is re-signalled rather than cached as delivered
;; (rf2-olvr5 finding 2).

(defonce ^:private runtime-signalling
  ;; Per-build-id IN-FLIGHT configure-raw-state! Promise. A second
  ;; concurrent caller for the same build awaits the SAME Promise rather
  ;; than firing a duplicate signal OR racing ahead while the first
  ;; signal is still in flight (rf2-z7roa). Cleared once resolved.
  ;;
  ;; Build-keyed because re-frame2-pair-mcp can talk to multiple shadow-cljs
  ;; builds over the same nREPL connection; each build has its own
  ;; preloaded runtime atom.
  (atom {}))

(defn signal-runtime!
  "Reconfigure the preload runtime's raw-state posture before each
  state-emitting eval. The runtime's `configure-raw-state!` flips its own
  atom — subsequent `app-db-reset!` calls then elide before emitting
  through `tap>`.

  ## Why this re-signals on EVERY call rather than caching 'delivered'
  per build (rf2-olvr5 finding 2)

  The runtime's `raw-state-config` atom defaults to `:allow-raw-state?
  true` (the bare-CLJS-REPL posture) and is RE-MINTED on every full page
  reload / CLJS heap reset — a fresh preload evaluation re-`defonce`s it
  back to the permissive default, and re-mints `session-id` too. The
  pre-fix shape recorded the signal as DELIVERED once per `build-id` per
  server lifetime and short-circuited thereafter. So after a reload, the
  build-id was still in the resolved set, `signal-runtime!` returned a
  no-op, the freshly-recreated runtime stayed at its permissive default,
  and the next state-emitting tool (snapshot / get-path / subscribe /
  replace-app-db / restore-epoch / dispatch-dry-run / record /
  watch-until) could tap RAW prev/next app-db through `tap>` even with
  `--allow-sensitive-reads` OFF.

  The server can't cheaply know the current `session-id` without a
  round-trip, and `configure-raw-state!` is idempotent + tiny (one
  bencode round-trip on the persistent socket — same shape every call).
  So the correct fix is to NOT cache the posture across runtime identity:
  always reconfigure before a tap-emitting write. The per-build IN-FLIGHT
  `runtime-signalling` dedup is preserved — a burst of concurrent
  state-emitting calls for the same build still share ONE configure
  Promise (the rf2-z7roa race guard: no caller proceeds to its
  state-emitting eval ahead of the posture landing) — but once that
  Promise resolves the next call reconfigures afresh rather than skipping.

  Failure (a runtime predating rf2-c2dtu) is swallowed silently — the
  wire-side enforcement still holds, so a degraded runtime just means
  `tap>` consumers see raw values (the pre-rf2-c2dtu posture).

  Called between `ensure-runtime!` and the first state-emitting eval in
  each tool body that taps / egresses app-db. Returns a Promise resolving
  to nil."
  [conn build-id]
  (if (contains? @runtime-signalling build-id)
    ;; A configure is already in flight for this build — share it so a
    ;; concurrent caller never races ahead of the posture landing
    ;; (rf2-z7roa) and we don't fire a duplicate round-trip in the burst.
    (get @runtime-signalling build-id)
    (let [form (ef/emit
                 (ef/rt-call 'configure-raw-state!
                             {:allow-raw-state? @allow-raw-state?}))
          ;; Drop the in-flight entry on BOTH the success and swallowed-
          ;; failure arms. We do NOT record a permanent 'delivered' flag:
          ;; the runtime's posture resets on reload, so a future call must
          ;; reconfigure (rf2-olvr5 finding 2).
          finish! (fn []
                    (swap! runtime-signalling dissoc build-id)
                    nil)
          p       (-> (nrepl/cljs-eval-value conn build-id form)
                      (.then (fn [_] (finish!)))
                      (.catch (fn [_]
                                ;; Degraded runtime — predates rf2-c2dtu.
                                ;; Swallow; the wire-side gate still
                                ;; enforces.
                                (finish!))))]
      (swap! runtime-signalling assoc build-id p)
      p)))

(defn reset-runtime-signal-cache!
  "Clear the per-session signal in-flight map. Exposed for tests."
  []
  (reset! runtime-signalling {}))
