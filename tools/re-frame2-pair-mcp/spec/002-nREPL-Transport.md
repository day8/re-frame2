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

In precedence order (rf2-umoz2 introduced step 3):

1. `--port-file <path>` launch flag — explicit, cwd-independent override
   (rf2-3dbwh).
2. `$SHADOW_CLJS_NREPL_PORT` env var.
3. **Shadow HTTP probe** — `GET http://127.0.0.1:9630/api/project-info`
   returns the consumer build's absolute `:project-home`; the server
   then reads `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`,
   `.nrepl-port` (in that order) resolved against that root. The
   shadow HTTP port is overridable via `--http-port <n>` (default
   9630; rf2-umoz2).
4. CWD-relative scan of the same three candidates — legacy fallback
   for environments without shadow's web server.

If none resolve, the server boots in degraded mode (see
`001-Wire-Protocol.md` § Degraded boot).

### Why the HTTP probe

shadow-cljs's nREPL port is ephemeral (a fresh OS-assigned port on each
`shadow-cljs watch` start) and the port file lives at a relative path
inside the consumer project. Pre-rf2-umoz2 the server relied on
`process.cwd()` being the project root to find that file — but agent
hosts (Claude Code / Cursor / Copilot) spawn MCP subprocesses with a cwd
they choose, frequently `$HOME` or the host's install dir. Shadow's own
HTTP server (default port 9630, fixed across restarts) exposes the
absolute project root via `/api/project-info`; that closes the loop
without forcing every operator to hardcode `--port-file` in their MCP
config.

The probe is bounded (`probe-timeout-ms` = 500ms) and never blocks boot
indefinitely. Probe failure (shadow not running, non-default `:http :port`,
parse error) silently falls through to step 4.

## cljs-eval wrapper

ClojureScript forms aren't evaluated directly on the JVM-side nREPL —
they're wrapped in `(shadow.cljs.devtools.api/cljs-eval <build-id> <form-str> {})`
which targets shadow-cljs's CLJS REPL bridge. The wrapper returns a
string-encoded EDN map like `{:results ["..."] :ns user}`; we read
the last `:results` entry as EDN to obtain the actual CLJS value.
This mirrors `cljs-eval-value` in the bash-shim chain.
