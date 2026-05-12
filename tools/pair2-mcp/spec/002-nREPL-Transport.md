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
**runtime sentinel** is the var
`re-frame-pair2.runtime/session-id` — set on inject, gone after a
reload.

Every tool that needs the runtime calls `ensure-runtime!`:

1. `cljs-eval` the form `re-frame-pair2.runtime/session-id`.
2. If a non-blank string comes back, the runtime is live; proceed.
3. Otherwise, slurp the canonical `runtime.cljs` from
   `skills/re-frame-pair2/scripts/runtime.cljs` (or
   `$PAIR2_RUNTIME_PATH`) and ship it through `cljs-eval`. Then
   proceed.

This is the same check the bash-shim chain implemented in
`runtime-already-injected?` (see `skills/re-frame-pair2/scripts/ops.clj`).

## Port discovery

In order:

1. `$SHADOW_CLJS_NREPL_PORT` env var.
2. `target/shadow-cljs/nrepl.port` (shadow-cljs's standard location).
3. `.shadow-cljs/nrepl.port`.
4. `.nrepl-port` (generic nREPL convention).

If none resolve, the server boots in degraded mode (see
`001-Wire-Protocol.md` § Degraded boot).

## cljs-eval wrapper

ClojureScript forms aren't evaluated directly on the JVM-side nREPL —
they're wrapped in `(shadow.cljs.devtools.api/cljs-eval <build-id> <form-str> {})`
which targets shadow-cljs's CLJS REPL bridge. The wrapper returns a
string-encoded EDN map like `{:results ["..."] :ns user}`; we read
the last `:results` entry as EDN to obtain the actual CLJS value.
This mirrors `cljs-eval-value` in the bash-shim chain.
