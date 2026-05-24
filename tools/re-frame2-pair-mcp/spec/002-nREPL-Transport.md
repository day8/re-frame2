# 002-nREPL-Transport

## Persistent socket

One TCP socket to `127.0.0.1:<nrepl-port>` is opened on first need
and held for the lifetime of the session. Subsequent ops reuse the
socket without reconnecting.

## Multiplexing by id

Each op carries a UUID `id`. The connection holds a `pending` map
of `{id → resolve-fn}`; incoming bencode frames are routed to the
right resolver. This means concurrent ops are correct in principle,
though the current MCP server invokes tools sequentially.

## Bencode framing

nREPL speaks bencode over the socket. We use the `bencode@2.0.x` npm
package. **Critical**: bencode@2 stores the post-decode cursor on
`bencode.decode.position` (the byte offset after the just-decoded
frame). The `bencode.decode.bytes` attribute is unreliable — it's
sometimes set to the *full* buffer length even when only the first
frame was decoded. The decoder uses `position` exclusively.

bencode@4+ is ESM-only and breaks shadow-cljs's CommonJS shim
(`Error: No "exports" main defined`). We pin `~2.0.3` deliberately.

## Reconnect protocol

A full page reload in the browser destroys the CLJS runtime in the
browser tab but leaves the JVM-side nREPL socket intact. The
**runtime sentinel** is the load-time marker
`js/globalThis.__re_frame2_pair_runtime`, installed by the
preloaded `re-frame2-pair.runtime` namespace — gone after a reload,
re-installed automatically when shadow-cljs re-runs the consumer's
`:devtools :preloads` on the next bundle load.

Every tool that needs the runtime calls `ensure-runtime!`:

1. `cljs-eval` the probe
   `(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))`.
2. If `true` comes back, the runtime is live; proceed.
3. Otherwise, reject with
   `{:reason :runtime-not-preloaded :hint <setup-message>}`.

There is no cljs-eval inject fallback (rf2-7dvg cut it). The consumer
adds the preload entry to their shadow-cljs build per
`skills/re-frame2-pair/SKILL.md` §Setup.

## Port discovery

In precedence order (rf2-3grub introduced step 3 as the proper generic
solution; rf2-umoz2 introduced what is now step 4 as the
shadow-specific fallback):

1. `--port-file <path>` launch flag — explicit, cwd-independent override
   (rf2-3dbwh).
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. **MCP `roots/list` walk** (rf2-3grub) — ask the MCP client for its
   workspace roots, walk each one (bounded shallow, skipping
   `node_modules` / `.git` / `target` / etc.) for `shadow-cljs.edn`,
   and pair each find with the adjacent `.shadow-cljs/nrepl.port`. One
   match → silent attach. Multiple → drive `elicitation/create` so the
   user picks. Zero matches → step 4. The discovery runs lazily on
   first tool call (so the client has finished `initialize` and its
   `roots` capability is observable).
4. **Shadow HTTP probe** — `GET http://127.0.0.1:9630/api/project-info`
   returns the consumer build's absolute `:project-home`; the server
   then reads `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`,
   `.nrepl-port` (in that order) resolved against that root. The
   shadow HTTP port is overridable via `--http-port <n>` (default
   9630; rf2-umoz2). Fallback for clients that don't expose `roots`.
5. CWD-relative scan of the same three candidates — legacy fallback
   for environments without shadow's web server.

If none resolve, the first tool call returns a structured error and
subsequent calls retry discovery (see § Lazy discovery below).

### Lazy discovery (rf2-3grub)

Port discovery is **lazy on first tool call**, not boot. The reason:
`roots/list` is a server→client request that can only be issued AFTER
the client's `initialize` handshake completes — driving it from `main`
would race the handshake. Instead, the boot path connects the stdio
transport and registers handlers; the first `tools/call` triggers the
five-step cascade.

**Per-tool-call port-file re-read.** After the initial discovery, each
subsequent tool call re-reads `<project-home>/.shadow-cljs/nrepl.port`
before using the cached socket. If the port has changed (shadow was
restarted; the dev server picks a fresh ephemeral port on each `watch`
start), the cached socket is closed and a new one opened transparently.
If the port file vanished, discovery re-runs from step 1.

### Why `roots/list` is the primary path

The MCP `roots` capability is the protocol's own answer to the
"where is the workspace" problem: the client (Claude Code) surfaces
the directories the user has opened. Generic across MCP servers — any
future tool facing the same CWD-discovery problem uses the same
primitive. Zero hardcoded paths, zero shadow-specific port probing,
zero operator config under the normal path. Survives multi-project
workspaces via `elicitation/create`. Survives shadow restarts via the
per-tool-call port-file re-read.

### Why the HTTP probe stays as step 4 (rf2-umoz2)

`roots/list` requires a recent MCP-protocol revision; older clients
fall through to step 4. Shadow's web server (default port 9630, fixed
across restarts) exposes `:project-home` at `/api/project-info` — the
same one-step cwd-resolution mechanism, shadow-specific but reliable
for any agent host that spawned the MCP server with the cwd ≠ project
root.

The probe is bounded (`probe-timeout-ms` = 500ms) and never blocks boot
indefinitely. Probe failure (shadow not running, non-default `:http :port`,
parse error) silently falls through to step 5.

### Why this is the right ceiling for cwd discovery

- **Zero hardcoded paths anywhere** under the normal path — not in
  `~/.claude.json`, not in launch args, not in source.
- **Zero shadow-cljs-specific port guessing** in the primary cascade —
  the workspace tells us where to look.
- **Generic across MCP servers** — `roots` is the official MCP pattern.
- **Survives shadow restarts** transparently via the per-tool re-read.
- **Survives multi-project workspaces** via `elicitation/create`.
- **Graceful degradation** — clients without `roots` fall through to
  step 4 (shadow HTTP probe), then step 5 (cwd scan), with steps 1-2
  as explicit operator overrides at any layer.

## cljs-eval wrapper

ClojureScript forms aren't evaluated directly on the JVM-side nREPL —
they're wrapped in `(shadow.cljs.devtools.api/cljs-eval <build-id> <form-str> {})`
which targets shadow-cljs's CLJS REPL bridge. The wrapper returns a
string-encoded EDN map like `{:results ["..."] :ns user}`; we read
the last `:results` entry as EDN to obtain the actual CLJS value.
This mirrors `cljs-eval-value` in the bash-shim chain.
