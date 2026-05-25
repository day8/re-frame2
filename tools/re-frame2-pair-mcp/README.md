# tools/re-frame2-pair-mcp/

`@day8/re-frame2-pair-mcp` — the **MCP (Model Context Protocol) server**
that pair-programs with a live re-frame2 application over a persistent
nREPL connection.

This is the structural successor to the bash-shim → babashka → nREPL
chain under `skills/re-frame2-pair/scripts/`. The shims still ship for
back-compat, but new sessions should prefer the MCP server.

## What it is

A Node-based stdio JSON-RPC server (written in ClojureScript, compiled
via shadow-cljs to a single `.js` file) that exposes the sixteen re-frame2-pair
ops as MCP tools (fourteen read/inspect ops plus the two write tools
`restore-epoch` / `reset-frame-db`, which are gated behind
`--allow-writes`). AI agents (Claude Code, Cursor, Copilot) launch it
as a subprocess; one persistent nREPL socket is held for the lifetime
of the session.

Per-op latency drops from ~700ms (bash startup + babashka startup +
fresh nREPL connect per call) to ~5–50ms (one bencode round-trip on
the open socket). The first-connect inject step is also gone (rf2-7dvg):
the runtime ships into the consumer app via shadow-cljs `:preloads`,
so `discover-app` is just a marker probe rather than a hundreds-of-ms
cljs-eval compile.

## Tool surface

| MCP tool       | Bash-shim equivalent      | What it does |
|----------------|---------------------------|--------------|
| `discover-app` | `discover-app.sh`         | Verify shadow-cljs nREPL is reachable, probe the preloaded re-frame2-pair runtime marker, return a health summary. Run first every session. |
| `eval-cljs`    | `eval-cljs.sh`            | Evaluate a CLJS form via shadow-cljs's `cljs-eval`. Returns the EDN value. |
| `dispatch`     | `dispatch.sh`             | Fire a re-frame2 event with `:origin :pair`. Modes: queued, sync, trace. Frame and fx-overrides supported. |
| `restore-epoch`| _(new — no bash equivalent)_ | Time-travel undo (rf2-ee38b.18): rewind a frame's app-db to a recorded prior epoch. The canonical pair-tool undo gesture (Tool-Pair §Time-travel). `epoch-id` is EDN (the runtime emits integer ids). **Gated behind `--allow-writes`** — returns `:rf.error/writes-disabled` without the flag. |
| `reset-frame-db`| _(new — no bash equivalent)_ | State injection (rf2-ee38b.18): replace a frame's app-db with an arbitrary EDN value the runtime never recorded — the JSON-loaded-bug-repro case (Tool-Pair §Pair-tool writes). Records a synthetic epoch so `restore-epoch` can rewind past it. **Gated behind `--allow-writes`**. |
| `trace-window` | `trace-window.sh`         | Return the epochs that landed in the last N ms. Cursor-paginated (`:limit` / `:cursor`, default limit 50). |
| `watch-epochs` | `watch-epochs.sh`         | Pull-mode poll for matching epochs added after a given epoch-id. Predicate keys: `:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`, `:sub-ran`, `:render`, `:origin`, `:frame`, `:timing-ms` (number or `">N"` / `">=N"` / `"<N"` / `"<=N"` / `"=N"` — server-side wall-clock filter, rf2-r3azh). Cursor-paginated (`:limit` / `:cursor`, default limit 50). |
| `tail-build`   | `tail-build.sh`           | Wait for a hot-reload to land by polling a probe form until its value changes. |
| `snapshot`     | _(new — no bash equivalent)_ | Coarse-grained per-frame state read in one round-trip. Returns a map keyed by frame-id with `:app-db`, `:sub-cache`, `:machines`, `:epochs`, `:traces` slices. Prefer for investigate-X workflows over chaining 5-10 individual reads. The `:app-db` slice defaults to a tree-summary marker (rf2-tygdv); drill down with `path`. |
| `get-path`     | _(new — no bash equivalent)_ | Read a single value at `path` from a frame's app-db (rf2-tygdv). Minimal targeted-read primitive; server-side `get-in` so only the addressed subtree crosses the wire. Distinguishes a path that points at `nil` from a path that doesn't resolve, and attaches `deepest-valid-prefix` on misses so the agent can re-aim. |
| `subscribe`    | _(new — no bash equivalent)_ | Streaming subscription on the trace / epoch bus (rf2-hq49). Push-mode replacement for `watch-epochs`; each matching event arrives as a `notifications/progress` notification. Topics: `trace`, `epoch`, `fx`, `error`. |
| `unsubscribe`  | _(new — no bash equivalent)_ | Close a streaming subscription out-of-band. Idempotent — closing an unknown sub-id returns `:existed? false` rather than an error. |
| `list-subscriptions` | _(new — no bash equivalent)_ | List active streaming subscriptions with per-sub queue depth, drop counts, and `:overflow-reason` (rf2-zjz9q; renamed from `subscription-info` per rf2-4y595). Diagnostic for "what streams are open?" / "is my probe still alive?" — wraps the runtime fn directly so AI clients don't need an `eval-cljs` round-trip. Optional `topic` / `sub-id` filters. |
| `handler-meta` | _(new — no bash equivalent)_ | Registration metadata for a `(kind, id)` — source-coord (file/line/column/ns), `:doc`, `:tags`, plus an `:rf.source/uri` jump-to-editor link (rf2-pctf8). Eleven supported kinds: event, sub, fx, cofx, view, frame, route, flow, head, error-projector, machine. |
| `list-handlers` | _(new — no bash equivalent)_ | Every registered id under a kind — the discovery surface (rf2-pctf8; renamed from `registry-list` per rf2-4y595). Same eleven supported kinds as `handler-meta`. |
| `get-re-frame2-pair-instructions` | _(new — no bash equivalent)_ | Return the agent-onboarding prose for re-frame2-pair-mcp (rf2-fnpqg): tool catalogue, EDN posture, tagged-mutation conventions, streaming subscribe semantics, wire-boundary pipeline. Inline text, no nREPL round-trip — call at session start to orient. Mirrors story-mcp's `get-story-instructions`. |

## Quick start

### Install

```bash
npm install -g @day8/re-frame2-pair-mcp
```

(or use `npx @day8/re-frame2-pair-mcp` for one-off runs).

### Configure Claude Code

Add to your `~/.claude/settings.json` (or per-project `.claude/settings.json`):

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "env": {
        "SHADOW_CLJS_BUILD_ID": "app"
      }
    }
  }
}
```

The server auto-discovers the nREPL port from (highest precedence first):

1. `--port-file <path>` launch flag — an explicit, **cwd-independent**
   path to the port file. See [Launch flags](#launch-flags).
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. **MCP `roots/list` walk (rf2-3grub)** — primary zero-config path.
   On the first tool call the server asks its MCP client for the
   workspace directories the user has opened, walks each one for
   `shadow-cljs.edn`, and reads the adjacent `.shadow-cljs/nrepl.port`.
   Multiple running shadow builds trigger an `elicitation/create`
   prompt so the user picks the project to attach to. Survives shadow
   restarts via a per-tool-call port-file re-read. Requires an MCP
   client that exposes `roots` (Claude Code 2.1.39+, Cursor, etc.).
4. **Shadow HTTP probe (rf2-umoz2)** — fallback for clients without
   `roots`. `GET http://127.0.0.1:9630/api/project-info` returns the
   live build's absolute `:project-home`; the server then reads the
   port-file candidates resolved against that root. The HTTP port is
   overridable via `--http-port <n>` for the rare case shadow's `:http
   :port` is pinned.
5. CWD-relative scan of `target/shadow-cljs/nrepl.port`,
   `.shadow-cljs/nrepl.port`, `.nrepl-port` — legacy fallback for
   setups without shadow's web server (older shadow, manual nREPL boot).

> **The cwd caveat step 3 solves (rf2-3grub).** Step 5 is bare
> *relative* paths resolved against `process.cwd()`. The MCP server
> runs as a **subprocess of the agent host** (Claude Code / Cursor /
> Copilot), whose cwd is frequently **not** your project root. Step 3
> closes that gap by asking the MCP client (which knows the workspace)
> for the open project roots; step 4 keeps the shadow-specific HTTP
> escape hatch for clients that pre-date `roots`. The explicit overrides
> in steps 1–2 remain the deterministic operator escape — pass
> `--port-file <absolute-path>` or set `SHADOW_CLJS_NREPL_PORT` if
> discovery still fails.

### Per-call build-id resolution

Each tool call routes to a shadow-cljs build. The build-id is resolved
from (highest precedence first):

1. An explicit `:build` MCP arg on this call — always wins.
2. **Session-scoped cache (rf2-l9ixp)** — populated by `discover-app`
   on success. Run `discover-app` once at the start of a session against
   the build you want to debug; subsequent tool calls default to that
   build without re-passing `:build`. Removes the friction of a multi-
   build workspace silently routing follow-up calls to `:app` (the
   env-var fallback) and returning `:runtime-not-preloaded`. The cache
   resets on nREPL reconnect (e.g. shadow restart, full page reload
   destroying the runtime sentinel).
3. `$SHADOW_CLJS_BUILD_ID` env var, defaulting to `:app`.

### Path-drift probe

After repo-side renames of this tool (e.g. PR #1504 `tools/pair2-mcp/`
→ `tools/re-frame2-pair-mcp/`) your `~/.claude.json` keeps pointing at
the old path until you edit it, and the MCP server then fails to start
with no obvious clue why. A self-healing **read-only** probe ships in
this directory to surface that drift on demand:

```bash
# From this directory:
npm run probe-mcp-path

# Or directly:
node bin/probe-mcp-path.cjs
```

The probe:

- Reads `~/.claude.json`, scans both top-level `mcpServers` and any
  per-project `projects.<path>.mcpServers` entries.
- For each entry whose `command`, `args`, `cwd`, or `env` value still
  references the legacy `tools/pair2-mcp/` token, prints a clear
  remediation message naming the stale field + the suggested
  replacement.
- **Never writes the file.** `~/.claude.json` is the operator's; the
  probe only reads.
- Silent on success — zero output if no drift is detected, the file
  is absent, or there are no matching entries.

Exit codes: `0` clean / absent / malformed (non-blocking); `1` drift
detected. Suitable for CI / preflight scripts.

Sample output when drift is present:

```
re-frame2-pair-mcp: stale path detected in ~/.claude.json

PR #1504 (rf2-e2ufx) renamed the MCP source dir from
  tools/pair2-mcp/  ->  tools/re-frame2-pair-mcp/
but your ~/.claude.json still points at the old path. The MCP server
will fail to start until the references are updated.

Stale references:
  [global] mcpServers.re-frame-pair2
    - arg: C:/Users/miket/code/re-frame2/tools/pair2-mcp/out/server.js
      suggested: C:/Users/miket/code/re-frame2/tools/re-frame2-pair-mcp/out/server.js

Remediation: open ~/.claude.json in an editor, replace each
'tools/pair2-mcp/' fragment with 'tools/re-frame2-pair-mcp/',
save, then restart Claude Code. This probe never writes the file;
your editor is the source of truth.
```

### Launch flags

| Flag                        | Default | What it does                                                                         |
|-----------------------------|---------|--------------------------------------------------------------------------------------|
| `--no-eval`                 | absent (eval-cljs ON) | Opt OUT of the `eval-cljs` tool. Default is eval-cljs ENABLED (rf2-a0z0h; inverts the prior rf2-cxx5s default-OFF posture). See "eval-cljs gate" below. |
| `--allow-sensitive-reads`   | OFF     | Honour caller-supplied `:include-sensitive true` and `:elision false` on direct-read tools (`snapshot` / `get-path` / `subscribe` / `trace-window` / `watch-epochs`). Default-OFF gate (rf2-c2dtu). Canonical cross-MCP flag name shared with story-mcp (rf2-2x3ql); see "sensitive-reads gate" below. |
| `--allow-writes`            | OFF     | Enable the state-mutating tools `restore-epoch` (time-travel undo) and `reset-frame-db` (state injection). Default-OFF gate (rf2-ee38b.18); without it both return `{:ok? false :reason :rf.error/writes-disabled}` without touching the nREPL socket. `dispatch` (which drives the app's own handlers) is unaffected. Note: this gate protects the named-write audit trail; it does NOT defend against eval-driven writes (eval can express the same writes), so for a true read-only posture compose with `--no-eval`. See "writes gate" below. |
| `--port-file <path>`        | —       | Explicit, **cwd-independent** path to the nREPL port file. Highest precedence in port discovery (rf2-3dbwh); see "port-file flag" below. Accepts `--port-file <path>` and `--port-file=<path>`. |
| `--http-port <n>`           | `9630`  | Shadow's web-server port for the auto-discovery probe (rf2-umoz2). Only consulted at port-discovery step 3; setting it has no effect when `--port-file` or `SHADOW_CLJS_NREPL_PORT` is present. |

#### port-file flag (rf2-3dbwh)

The default port-file fallbacks (`target/shadow-cljs/nrepl.port`,
`.shadow-cljs/nrepl.port`, `.nrepl-port`) are **relative** paths resolved
against the server's current working directory. Because the MCP server is
launched as a subprocess of the agent host, that cwd is frequently *not*
your project root — in which case the relative scan misses and only the
env var or `--port-file` resolve a port. `--port-file` takes an
**absolute** path and wins over every other source:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--port-file", "/abs/path/to/project/.shadow-cljs/nrepl.port"]
    }
  }
}
```

It is the cwd-independent escape hatch when port auto-discovery fails.

#### eval-cljs gate (rf2-a0z0h — default ON; inverts rf2-cxx5s)

`eval-cljs` evaluates arbitrary CLJS / Clojure source against the live
runtime — it is the REPL primitive of a pair-debug session. Published
builds ship the tool **ENABLED**. The operator opts OUT at server
launch with `--no-eval` for the rare paranoid case (CI runs, shared
dev environments where multiple humans share a single MCP process):

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--no-eval"]
    }
  }
}
```

With `--no-eval`, calls to `eval-cljs` return the structured error
`{:ok? false :reason :rf.error/eval-cljs-disabled ...}` without
touching the nREPL socket.

##### Threat-model rationale (rf2-a0z0h)

The prior default-OFF posture (rf2-cxx5s) parallelled `--allow-writes`
in shape but not in effect. `--allow-writes` is load-bearing because
pair-tool writes can confuse the debug audit trail ("did my app
produce this state change, or did the pair tool?"). A default-OFF
`--allow-eval` did NOT parallel that protection because eval-cljs can
express any write the writes-gate would block — the two gates are not
independent. Once eval is on, writes are de-facto on. So default-OFF
eval added friction (every operator had to edit `~/.claude.json` and
restart Claude Code to access the REPL surface) without adding a
separable protection.

The real defence is **don't expose this MCP to untrusted callers**.
Once an operator has installed re-frame2-pair-mcp and wired it into
`~/.claude.json`, they have already declared trust in the surface.

##### Migration

Operators with `--allow-eval` in their `~/.claude.json` from the
prior rf2-cxx5s era can leave it — the parser silently ignores
unrecognised flags (no warning is printed because there's no harm
done; the surface the legacy flag would have opened is now on by
default anyway). Removing it is recommended for clarity.

#### sensitive-reads gate (rf2-c2dtu)

The direct-read surfaces (`snapshot`, `get-path`, `subscribe`,
`trace-window`, `watch-epochs`) can return verbatim slices of a live
app's state. Spec 009 §Privacy mandates default-suppression: sensitive
slots redact and large slots elide before any payload crosses the
LLM-facing wire. Published builds ship with the gate **OFF**:

- A caller's `:include-sensitive true` is overridden to `false`.
- A caller's `:elision false` is overridden to `true`.
- The preload runtime's `app-db-reset!` taps default-elide both
  `:previous` and `:next` payloads through `re-frame.core/elide-wire-value`
  before any tap consumer sees them.

Operators who need raw state for offline debug opt in at server launch:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "re-frame2-pair-mcp",
      "args": ["--allow-sensitive-reads"]
    }
  }
}
```

With the flag, the per-call args win again — `:include-sensitive true`
and `:elision false` ride through to the walker unchanged. Same
architecture as story-mcp's `--allow-sensitive-reads` (rf2-uaymx /
rf2-g9fje) — they share the canonical cross-MCP flag name (rf2-2x3ql).
The sibling `--no-eval` opt-out (rf2-a0z0h) keeps the inverse posture:
default ON, explicit opt-out, because eval-cljs is the REPL primitive
of a pair-debug session.

#### writes gate (rf2-ee38b.18)

`restore-epoch` (time-travel undo) and `reset-frame-db` (state
injection) are the two Tool-Pair **write** primitives the server is the
canonical consumer of (Tool-Pair §Time-travel, §Pair-tool writes). Both
replace a frame's `app-db` wholesale — qualitatively more powerful than
`dispatch` (which drives the application's own handlers). Published
builds ship them **DISABLED**; the operator opts in at launch with
`--allow-writes`:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "npx",
      "args": ["--allow-writes"]
    }
  }
}
```

Without the flag, both tools return
`{:ok? false :reason :rf.error/writes-disabled}` without touching the
nREPL socket — a stock install cannot rewind history or inject state
over the MCP **named-write** surface. The two tools still appear in
`tools/list` (descriptors are unconditional); the gate is enforced at
`tools/call` time. The tools also carry the `:destructiveHint`
annotation so agent hosts gate them behind a confirmation prompt even
when the flag is on.

> **Note on composition with `eval-cljs`.** This gate protects the
> named-write audit trail. It does NOT defend against eval-driven
> writes — `eval-cljs` (enabled by default post-rf2-a0z0h) can express
> any write `--allow-writes` would block. For a true read-only debug
> session (no app-db mutation through this MCP at all), compose with
> `--no-eval`.

### First call

```text
Agent: tools/call eval-cljs {form: "@(rf/subscribe [:user/email])"}
Server: {:ok? true :value "alice@example.com"}
```

Or, with the re-frame2-pair skill loaded, just describe the task — the skill's
SKILL.md teaches the agent which tool to call. See
[`../../skills/re-frame2-pair/SKILL.md`](../../skills/re-frame2-pair/SKILL.md).

### eval-cljs and Promise-returning forms (rf2-xn4f9)

By default, `eval-cljs` captures the form's **synchronous** return
value and `pr-str`'s it. When the form returns a JS Promise — any
async work (`fetch`, `.layout()`, an `async` fn, anything chained
with `.then`) — the synchronous return IS the Promise object, and
`pr-str` produces `"#object[Promise [object Promise]]"`: a string
saying "I'm a Promise" with no access to the eventually-resolved value:

```text
Agent: tools/call eval-cljs {form: "(-> (js/Promise.resolve {:hello \"world\"}) (.then identity))"}
Server: {:ok? true :value "#object[Promise [object Promise]]"}   ; lost the resolved value
```

Pass `:await true` (opt-in) to have the server await the Promise and
return the resolved value as `:value`. The caller picks the deadline
via `:timeout-ms` (default 5000):

```text
Agent: tools/call eval-cljs {form: "(-> (js/Promise.resolve {:hello \"world\"}) (.then identity))", await: true}
Server: {:ok? true :value {:hello "world"} :build :app}
```

Rejections and timeouts surface as structured `:rf.error/*` failures:

```text
Agent: tools/call eval-cljs {form: "(js/Promise.reject (ex-info \"nope\" {}))", await: true}
Server: {:ok? false :reason :rf.error/eval-cljs-rejected :rejection "..." :build :app}

Agent: tools/call eval-cljs {form: "(js/Promise. (fn [_ _]))", await: true, timeout-ms: 500}
Server: {:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms 500 :build :app}
```

The default `:await false` preserves the pass-through semantic for
forms that intentionally hand a Promise object to other code. See
[`spec/003-Tool-Catalogue.md` §eval-cljs](./spec/003-Tool-Catalogue.md#eval-cljs)
for the full contract including the await wrapper's mailbox protocol.

### Snapshot — one call instead of five

For investigate-X workflows (post-mortems, "what state is the app in
right now?", "what changed between these two epochs?"):

```text
Agent: tools/call snapshot {frames: "all"}
Server: {:ok? true
         :frames :all
         :include [:app-db :sub-cache :machines :epochs :traces]
         :snapshot {:rf/default {:app-db {...}
                                 :sub-cache {[:cart/total] {:value 42 ...}}
                                 :machines {:ids [:auth] :state {:auth {...}}}
                                 :epochs [{:epoch-id "..." ...} ...]
                                 :traces [{:operation :rf.event/dispatched ...}]}}}
```

Subset what you need with `include`:

```text
Agent: tools/call snapshot {frames: ["rf/default"], include: ["app-db", "epochs"]}
```

Per-op reads (`eval-cljs` against `runtime/app-db-at`, etc.) remain
available — they're still the right call when you genuinely need one
slice for one frame. `snapshot` is the right surface when you don't
yet know which slice carries the answer.

## How preload probing works (rf2-7dvg)

A full page reload in the browser destroys the CLJS runtime but
leaves the nREPL socket on the JVM side intact. shadow-cljs re-runs
the consumer's `:preloads` as part of the next bundle load, so the
`re-frame2-pair.runtime` namespace reappears in the new realm
together with the load-time marker at
`js/globalThis.__re_frame2_pair_runtime`.

Every tool that needs the runtime calls `ensure-runtime!` first; the
probe is one bencode round-trip on the persistent socket. There is
no cljs-eval inject fallback (rf2-7dvg cut it for pre-alpha
simplicity).

### Failure-path diagnostic ladder (rf2-7tgfk)

When the probe fails, `ensure-runtime!` no longer surfaces a single
blanket `:runtime-not-preloaded` reason. A diagnostic ladder runs on
the failure path and reports one of four specific reasons:

| `:reason`                                | Meaning |
|------------------------------------------|---------|
| `:nrepl-unreachable`                     | The JVM-side nREPL round-trip fails. The JVM may have stopped, or the MCP server is holding a stale socket. Restart `shadow-cljs watch`. |
| `:build-not-running`                     | shadow-cljs's `active-builds` doesn't include the targeted build. Carries `:running-builds` so the operator can pick the right `--build=<id>`. |
| `:no-runtime-connected`                  | The build IS running but no CLJS runtime answered the eval (cljs-eval returned blank). Open the app in a browser tab — or reload an existing tab whose WebSocket has dropped. |
| `:runtime-loaded-but-preload-missing`    | A CLJS runtime is alive but the `__re_frame2_pair_runtime` marker is absent. The original setup hint ("add the preload to your shadow-cljs.edn") applies here. |

The ladder costs one extra `jvm-eval` (active-builds enumeration) on
the failure path; the probe cache means the success path stays free.

## Spec

The contract lives in [`spec/`](./spec/):

| File | Covers |
|------|--------|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What this server is, why it replaces the bash-shim chain. |
| [`spec/001-Wire-Protocol.md`](./spec/001-Wire-Protocol.md) | JSON-RPC 2.0 over stdio; lifecycle; tool dispatch. |
| [`spec/002-nREPL-Transport.md`](./spec/002-nREPL-Transport.md) | Persistent socket, bencode framing, sentinel-based reconnect. |
| [`spec/003-Tool-Catalogue.md`](./spec/003-Tool-Catalogue.md) | The sixteen tools (the original per-op set + the `snapshot` mega-op + the streaming `subscribe` / `unsubscribe` / `list-subscriptions` triad + `get-path` direct-read + the `handler-meta` / `list-handlers` registrar-introspection pair + the `restore-epoch` / `reset-frame-db` write pair gated behind `--allow-writes` + `get-re-frame2-pair-instructions` agent-onboarding), their argument schemas, EDN result shape. |

## Development

```bash
# Install deps
npm install

# Compile production build
npm run build      # → out/server.js

# Watch mode
npm run watch

# Unit tests (cljs)
npm test

# Stdio integration test (no nREPL needed — exercises the degraded path)
npm run stdio-roundtrip

# Live-nREPL integration test (requires an nREPL running on $NREPL_TEST_PORT)
NREPL_TEST_PORT=17777 node test/live-nrepl.js

# Stale-binary post-merge hook tests (rf2-6jj3r)
npm run test:post-merge-hook
```

### Stale-binary post-merge hook (rf2-6jj3r)

`out/server.js` is gitignored and rebuilt locally. After
`git pull` brings down MCP source changes, the binary on disk is
stale until you re-run `npm run build` AND bounce the MCP server in
your host (typically by restarting Claude Code). To get a stderr
warning automatically on every pull, install the repo's git hooks
once per clone (from the repo root):

```bash
scripts/install-git-hooks.sh
# or, on Windows / PowerShell:
powershell -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1
```

Both installers are idempotent. The hook is advisory only — it never
blocks `git pull`. Source lives at
[`scripts/git-hooks/post-merge`](../../scripts/git-hooks/post-merge);
the testable detection helper lives at
[`scripts/git-hooks/lib/check-stale-mcp-binary.sh`](../../scripts/git-hooks/lib/check-stale-mcp-binary.sh).

## Implementation language

ClojureScript compiled via shadow-cljs to a `:node-script` target.
End users install Node only; the compiled output is plain JS. The
language pick is locked — see the bead notes on the implementing PR.

`pilot/` contains the original toolchain pilot — a minimal MCP server
with two tools (`ping` and `nrepl-ping`) used to verify the
shadow-cljs + npm MCP SDK + bencode round-trip worked before the full
port. Kept for reference and as a stripped-down smoke harness.

## File layout

```
tools/re-frame2-pair-mcp/
├── README.md                                 ; this file
├── package.json                              ; npm package
├── shadow-cljs.edn                           ; build config
├── spec/                                     ; contract
├── pilot/                                    ; pre-port toolchain pilot
├── bin/
│   └── probe-mcp-path.cjs                    ; read-only ~/.claude.json drift probe (rf2-vsxgz)
└── src/re_frame2_pair_mcp/
    ├── nrepl.cljs                            ; persistent socket + bencode
    ├── tools.cljs                            ; the sixteen MCP tools (per-op + snapshot + get-path + restore-epoch/reset-frame-db writes + subscribe/unsubscribe/list-subscriptions + get-re-frame2-pair-instructions)
    └── server.cljs                           ; stdio JSON-RPC entry point
└── test/
    ├── re_frame2_pair_mcp/nrepl_test.cljs    ; bencode framing unit tests
    ├── probe-mcp-path-test.cjs               ; probe-mcp-path unit tests
    ├── stdio-roundtrip.js                    ; stdio integration test
    └── live-nrepl.js                         ; live-nREPL integration test
```

## Co-install with browser-substrate MCP servers (rf2-gj1kr)

re-frame2-pair-mcp is intentionally **re-frame2-runtime-only** — every tool
routes through one of the eight [Tool-Pair primitives](../../spec/Tool-Pair.md)
on the JVM-side nREPL socket. It does **not** drive the browser
directly. The absence of "click this button" / "screenshot this
viewport" / "navigate to this URL" tools is by design: browser
substrate is the concern of a peer MCP server.

For a fuller agent workflow, co-install one of the browser-substrate
MCP servers alongside re-frame2-pair-mcp. Each layer carries its own slice of
the surface:

| Layer | Server | Tools |
|---|---|---|
| Browser substrate | [Chrome DevTools MCP](https://github.com/anthropics/chrome-devtools-mcp) or [Playwright MCP](https://github.com/microsoft/playwright-mcp) | Click, type, navigate, screenshot, viewport |
| re-frame2 runtime | **re-frame2-pair-mcp** (this artefact) | `dispatch`, `snapshot`, `get-path`, `subscribe`, `eval-cljs`, … |

Browser-substrate ops and re-frame2-runtime ops are different
contracts, and bundling them into one server would force every
re-frame2 developer to take on the heavyweight Chromium dep just to
read `app-db`.

Example session: the browser MCP clicks a button → re-frame2-pair-mcp's
`subscribe` receives the resulting `:rf/epoch-record` → the agent
inspects the new app-db slice via `get-path`. Each server stays
single-purpose; the agent host glues them at the workflow level.

## Concurrent agents (rf2-hrcoj)

**v1 posture: single-agent per session.** Today's re-frame2-pair-mcp assumes
one agent host (Claude Code, Cursor, or Copilot) per running server
process. The shared mutable state — the nREPL connection, the
per-session response cache (`cache.cljs`), and the active
subscription registry — is **not** partitioned by agent.

Two agents attaching to the same re-frame2-pair-mcp instance simultaneously
work today (no lock-out), but they will see each other's
side-effects: a `dispatch` from agent A may show up in agent B's
`watch-epochs` poll; a `subscribe` from agent A counts against
agent B's `list-subscriptions`. For pre-alpha this is the documented
behaviour, not a bug — single-agent is the expected workflow.

**v2 sketch (not implemented; deferred).** Multi-agent semantics
would need: agent-scoped session ids on every `tools/call`,
per-agent subscription tables, optional lock-out on mutating ops
(`dispatch` with `:trace` mode + `eval-cljs` + `tail-build`), and
either per-agent response caches or a shared cache keyed by the
agent's request hash. The cache layer (`cache.cljs`) already keys
by request-args hash, which makes the shared-cache path the
likely first step.

If a workflow needs concurrent-agent isolation **today**, the
recourse is **one re-frame2-pair-mcp instance per agent host**, each
holding its own nREPL socket — shadow-cljs's nREPL supports
multiple concurrent clients.

## Record / replay session (rf2-f9acs, deferred)

**Status: deferred to a future drop.** Playwright MCP can record a
session (every click + viewport state) into a replayable artefact;
re-frame2-pair-mcp has no peer surface today. The existing surfaces give
agents push-mode visibility (`subscribe`) and pull-mode replay over
the epoch ring (`watch-epochs`, `trace-window`), but neither
persists across server lifetimes.

**v2 sketch (not implemented).** Two paired tools would round it
out:

- `record-session` — start capturing every `tools/call` (and its
  result envelope) into a session log keyed by an opaque
  session-id. Default off; opt-in per session. The log is plain EDN
  on disk so an agent can audit it out-of-band.
- `replay-session` — given a session-id and a target nREPL
  connection, re-issue each recorded call in order. Side-effects
  fire for real (same `dispatch` / `eval-cljs` path); useful for
  AI-assisted regression debugging where "this bug happened in the
  cell three replays ago" needs to be re-staged on demand.

Open questions before implementation: which calls record (all of
them, or only mutating ones?), session-log eviction (size cap?
time cap? per-host?), and how replay interacts with the
per-session response cache (replay a cache-hit verbatim, or
invalidate?). Filed as a follow-on RFE rather than a P2 because the
existing pull-mode / push-mode surfaces cover the high-frequency
debug-loop need; this is the cold-storage slot.

## Back-compat with the bash shims

The shims under `skills/re-frame2-pair/scripts/` still work and are
not slated for removal in this drop. Their headers carry a
deprecation notice pointing here. Migration is opt-in per session;
agents can mix shim calls and MCP tool calls in the same workflow
during the transition.
