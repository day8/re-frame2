(ns re-frame2-pair-mcp.tools.writes
  "Write-authority boot-gate (rf2-ee38b.18).

  The Tool-Pair contract names two FIRST-CLASS write primitives the
  pair server is the canonical consumer of:

    - `restore-epoch`   — time-travel undo: rewind a frame's app-db to
                          a recorded prior epoch
                          (spec/Tool-Pair.md §Time-travel).
    - `reset-frame-db!` — state injection: replace a frame's app-db
                          with an arbitrary value the runtime never
                          recorded (the JSON-loaded-bug-repro case,
                          spec/Tool-Pair.md §Pair-tool writes).

  Both are dev-only (production builds elide the primitives via the
  `goog.DEBUG` gate, per spec/009 §Production builds) and both MUTATE
  the live app-db wholesale — qualitatively more powerful than a
  `dispatch` (which drives the app's own handlers). A stale-bug-repro
  injection or a surprise rewind on a developer's running app is
  exactly the kind of destructive surprise the gate model exists to
  forbid by default.

  ## Default-safe (mirrors `--allow-eval`, rf2-cxx5s)

  Published builds ship these two tools **DISABLED**. The operator opts
  in at server launch via `--allow-writes`. When OFF (the default), the
  `restore-epoch` and `reset-frame-db` tools return a structured
  `{:ok? false :reason :rf.error/writes-disabled ...}` WITHOUT touching
  the nREPL socket — a stock install cannot rewind history or inject
  state over the MCP socket.

  The gate is a single atom (`allow-writes?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. Tests flip the atom directly via `set-allow-writes!`.

  Symmetric with:
    - rf2-cxx5s `--allow-eval`             (eval-cljs; tools/eval_cljs.cljs)
    - rf2-c2dtu `--allow-sensitive-reads`  (raw reads; tools/raw_state.cljs)"
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
     :hint   (str "State-mutating tools (restore-epoch, reset-frame-db) are "
                  "disabled by default; pass --allow-writes at server launch "
                  "to opt in. Read tools and dispatch are unaffected.")}))
