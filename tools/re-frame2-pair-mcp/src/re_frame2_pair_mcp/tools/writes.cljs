(ns re-frame2-pair-mcp.tools.writes
  "Write-authority boot-gate.

  The Tool-Pair contract names two FIRST-CLASS write primitives the
  pair server is the canonical consumer of:

    - `restore-epoch`   — time-travel undo: rewind a frame's whole
                          frame-state (BOTH app-db and runtime-db) to a
                          recorded prior epoch's `:frame-state-after`
                          via `replace-frame-state!`
                          (spec/Tool-Pair.md §Time-travel).
    - `replace-app-db!` — state injection: replace a frame's app-db
                          with an arbitrary value the runtime never
                          recorded (the JSON-loaded-bug-repro case,
                          spec/Tool-Pair.md §Pair-tool writes).

  Both are dev-only (production builds elide the primitives via the
  `goog.DEBUG` gate, per spec/009 §Production builds) and both MUTATE
  live frame-state wholesale — `restore-epoch` reinstalls both
  partitions, `replace-app-db!` swaps the app-db partition — qualitatively
  more powerful than a `dispatch` (which drives the app's own handlers).
  A stale-bug-repro
  injection or a surprise rewind on a developer's running app is
  exactly the kind of destructive surprise the gate model exists to
  forbid by default.

  ## Default-safe

  Published builds ship these two tools **DISABLED**. The operator opts
  in at server launch via `--allow-writes`. When OFF (the default), the
  `restore-epoch` and `replace-app-db` tools return a structured
  `{:ok? false :reason :rf.error/writes-disabled ...}` WITHOUT touching
  the nREPL socket — a stock install cannot rewind history or inject
  state over the MCP **named-write** surface.

  The gate is a single atom (`allow-writes?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. Tests flip the atom directly via `set-allow-writes!`.

  Symmetric with:
    - `--allow-sensitive-reads`  (raw reads; tools/raw_state.cljs)

  ## Composition with eval-cljs

  This gate protects the named-write audit trail (\"did my app produce
  this state change, or did the pair tool?\"). It is NOT a defence
  against eval-driven writes — `eval-cljs` (on by default)
  can express any write `--allow-writes` would block. For a true
  read-only debug session compose `--no-eval` with `--allow-writes`
  absent."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]))

(defonce ^:private allow-writes?
  ;; Default OFF in published builds. `server.cljs/main` flips this to
  ;; `true` when `--allow-writes` is present in `process.argv`.
  (atom false))

(defn set-allow-writes!
  "Set the writes launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! allow-writes? (boolean enabled?)))

(defn allow-writes-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @allow-writes?)

(defn writes-allowed?
  "Single intention-naming predicate. True when the operator opted in
  via `--allow-writes` at launch; false otherwise (the published-build
  default). Write-tool bodies branch on this before any nREPL eval."
  []
  @allow-writes?)

(defn disabled-result
  "The structured `:rf.error/writes-disabled` envelope a gated write
  tool returns when `--allow-writes` was not passed. `tool` names the
  refused tool so the agent's hint is specific."
  [tool]
  (wire/err-text
    {:ok?    false
     :reason :rf.error/writes-disabled
     :tool   tool
     :hint   (str "State-mutating tools (restore-epoch, replace-app-db) are "
                  "disabled by default; pass --allow-writes at server launch "
                  "to opt in. Read tools and dispatch are unaffected.")}))

;; ---------------------------------------------------------------------------
;; Server-boundary pre-dispatch gate.
;;
;; The write-tool BODIES (`restore-epoch-tool` / `replace-app-db-tool`)
;; each refuse `:rf.error/writes-disabled` as their first action without
;; touching the nREPL socket — correct AT THE TOOL LAYER. But the real MCP
;; server handler (`server.cljs/handle-call`) calls `ensure-connection!`
;; for EVERY tool BEFORE the tool body runs. On a stock install with no
;; nREPL port available, that connection step REJECTS (`:nrepl-port-not-
;; found`) and the tool body — and thus its write gate — never fires; the
;; refusal would surface as a misleading discovery error instead of the
;; intended destructive-tool refusal, and (when discovery WAS available)
;; the server would perform unnecessary connect / elicitation work for a
;; request that should be refused locally.
;;
;; This set + predicate let the server refuse the gated writes at the
;; pre-dispatch boundary — BEFORE `ensure-connection!` — when writes are
;; off, so the "default-safe write posture is observably true at the MCP
;; boundary" contract holds (spec/Tool-Pair.md §Pair-tool writes;
;; tools/writes.cljs §Default-safe). The tool-body gates STAY (defence in
;; depth + the direct-call test surface); this is the outer ring.

(def gated-write-tools
  "Tool names refused at the server boundary when `--allow-writes` is OFF.
  These two MUTATE app-db wholesale and can be refused with
  NO runtime connection — the gate is a pure function of the boot flag."
  #{"restore-epoch" "replace-app-db"})

(defn refuse-pre-connection
  "Server-boundary pre-dispatch guard. Returns the
  `:rf.error/writes-disabled` result when `tool` is a gated write tool
  AND `--allow-writes` is OFF — so `server.cljs/handle-call` can refuse it
  locally BEFORE `ensure-connection!` runs (no nREPL socket touched, no
  discovery / elicitation triggered). Returns `nil` for any tool that
  must proceed to normal dispatch (a non-write tool, or a write tool when
  writes are enabled — the latter falls through to its body, which then
  needs the connection). The tool-body gates remain as defence in depth."
  [tool]
  (when (and (contains? gated-write-tools tool)
             (not (writes-allowed?)))
    (disabled-result tool)))
