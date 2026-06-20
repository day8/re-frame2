(ns re-frame2-pair-mcp.tools.raw-state
  "Raw-state boot-gate.

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
     reload (see `signal-runtime!`).

  When `--allow-sensitive-reads` is ON, the per-call args win.

  ## Single intention-naming predicate

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
  Positive-sense and single-name, it answers \"did the operator opt
  in?\" without negation gymnastics at the call site.

  Symmetric with the story-mcp `--allow-sensitive-reads` flag.

  re-frame2-pair-mcp's `eval-cljs` gate defaults ON, with `--no-eval` as
  the opt-out. The raw-state gate keeps a default-OFF posture because
  privacy elision IS a separable protection (eval-cljs surfaces what an
  operator asked for; raw reads can pour the entire app-db into the wire
  log without the operator ever typing the secret).

  The pair-mcp CLI flag is `--allow-sensitive-reads` (the canonical
  cross-MCP name). The internal Clojure identifiers below use
  `raw-state`.

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
  "Single intention-naming predicate.

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

  Positive-sense and single-name, this predicate matches its truth
  value to the operator's intent, so a call site answers \"did the
  operator opt in?\" with no negation gymnastics."
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
;; The runtime exposes `configure-raw-state!` which sets a
;; per-runtime flag controlling whether `app-db-reset!` taps raw values or
;; redacts via `elide-wire-value`. The MCP server pushes its boot-gate
;; state into the runtime before every state-emitting eval — the
;; per-runtime flag resets to its permissive default on every page/runtime
;; reload, so the posture is re-signalled rather than cached as delivered.

(defonce ^:private runtime-signalling
  ;; Per-build-id IN-FLIGHT configure-raw-state! Promise. A second
  ;; concurrent caller for the same build awaits the SAME Promise rather
  ;; than firing a duplicate signal OR racing ahead while the first
  ;; signal is still in flight. Cleared once resolved.
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
  per build

  The runtime's `raw-state-config` atom defaults to `:allow-raw-state?
  true` (the bare-CLJS-REPL posture) and is RE-MINTED on every full page
  reload / CLJS heap reset — a fresh preload evaluation re-`defonce`s it
  back to the permissive default, and re-mints `session-id` too. Caching
  the signal as DELIVERED once per `build-id` per server lifetime would
  leave the build-id in the resolved set after a reload, so
  `signal-runtime!` would no-op, the freshly-recreated runtime would stay
  at its permissive default, and the next state-emitting tool (snapshot /
  get-path / subscribe / replace-app-db / restore-epoch /
  dispatch-dry-run / record / watch-until) could tap RAW prev/next app-db
  through `tap>` even with `--allow-sensitive-reads` OFF.

  The server can't cheaply know the current `session-id` without a
  round-trip, and `configure-raw-state!` is idempotent + tiny (one
  bencode round-trip on the persistent socket — same shape every call).
  So the posture is NOT cached across runtime identity: always
  reconfigure before a tap-emitting write. The per-build IN-FLIGHT
  `runtime-signalling` dedup still applies — a burst of concurrent
  state-emitting calls for the same build share ONE configure Promise
  (the race guard: no caller proceeds to its state-emitting eval ahead of
  the posture landing) — but once that Promise resolves the next call
  reconfigures afresh rather than skipping.

  Failure (a runtime without `configure-raw-state!`) is swallowed
  silently — the wire-side enforcement still holds, so a degraded runtime
  just means `tap>` consumers see raw values.

  Called between `ensure-runtime!` and the first state-emitting eval in
  each tool body that taps / egresses app-db. Returns a Promise resolving
  to nil."
  [conn build-id]
  (if (contains? @runtime-signalling build-id)
    ;; A configure is already in flight for this build — share it so a
    ;; concurrent caller never races ahead of the posture landing
    ;; and we don't fire a duplicate round-trip in the burst.
    (get @runtime-signalling build-id)
    (let [form (ef/emit
                 (ef/rt-call 'configure-raw-state!
                             {:allow-raw-state? @allow-raw-state?}))
          ;; Drop the in-flight entry on BOTH the success and swallowed-
          ;; failure arms. We do NOT record a permanent 'delivered' flag:
          ;; the runtime's posture resets on reload, so a future call must
          ;; reconfigure.
          finish! (fn []
                    (swap! runtime-signalling dissoc build-id)
                    nil)
          p       (-> (nrepl/cljs-eval-value conn build-id form)
                      (.then (fn [_] (finish!)))
                      (.catch (fn [_]
                                ;; Degraded runtime — no
                                ;; `configure-raw-state!`. Swallow; the
                                ;; wire-side gate still enforces.
                                (finish!))))]
      (swap! runtime-signalling assoc build-id p)
      p)))

(defn reset-runtime-signal-cache!
  "Clear the per-session signal in-flight map. Exposed for tests."
  []
  (reset! runtime-signalling {}))
