# tools/testbed-support/

Dev-only support shared by the Xray and Story browser testbeds. It is not a
published library and no production namespace depends on it.

The surface contains two namespaces:

- `re-frame.testbed.story-host` switches one `#app` node between a live app and
  the Story shell.
- `re-frame.testbed.open-in-editor-server` provides the JVM-side
  `POST /__rf-open-in-editor` handler used by shadow-cljs dev servers.

## Adding a testbed

Three steps, and none of them is a source path or a checkout root:

1. Wire `re-frame.testbed.open-in-editor-server/handler` on the build's
   `:dev-http` entry in `implementation/shadow-cljs.edn`.
2. Call `mount-with-hash-routing!` from the build's entry point, if the
   testbed needs the live-app ↔ Story-shell hash toggle.
3. `npm run dev -- <build-id>` from `implementation/`.

"Open in editor" then works with nothing configured. The endpoint resolves a
classpath-relative source coordinate against the live JVM source paths at
request time, so it finds the file in whatever clone is running the watch, at
any path, on any OS.

## Story host

`mount-with-hash-routing!` selects the surface from the current hash:

- `#/stories...` mounts the Story shell
- any other hash mounts the supplied live-app root view

The consumer must create its frame, load its image, and complete its own boot
before calling the host. The host owns only the React-root handoff and the
`hashchange` listener. It unmounts the current root before the other surface
claims `#app`.

The listener handle is stored in a `defonce` atom and explicitly removed before
each install. This matters during hot reload: recompiling a top-level CLJS
function changes its JavaScript identity, so `addEventListener` cannot dedupe a
listener from the previous compile.

The host takes the live-app root view and nothing else:

```clojure
(mount-with-hash-routing! live-app)
```

It neither reads nor writes Story configuration. A host that wants
`:rf.story/project-root` set — an external or non-shadow host relying on the
client's `editor://` URI fallback rather than on a dev server — calls
`story/configure!` itself.

## Open-in-editor server

The JVM namespace is a shadow-cljs `:dev-http` fallback handler. It resolves a
classpath-relative `file` query parameter against the dev JVM's source paths,
falls back to the process working directory, and invokes the `launch-editor`
npm package through Node.

Because this endpoint can open a local file, it accepts launches only when all
of these conditions hold:

- the request arrives from a **loopback TCP peer** — Ring's `:remote-addr`
- the request method is `POST`
- the `Host` header names a loopback host
- when present, `Origin` also names a loopback host

The peer address is the boundary; the rest is defence in depth. `Host`,
`Origin` and every `X-Forwarded-*` header are strings the client writes, so a
remote caller can spell them as loopback — the socket the request arrived on is
the one fact it cannot choose, and forwarding headers are deliberately ignored
because a `:dev-http` server is a direct listener. Missing or malformed peer
addresses are refused. A non-loopback peer reaches nothing here, not even the
`OPTIONS` preflight.

Accepted peers are the whole `127.0.0.0/8` block, IPv6 `::1` — which
shadow-cljs reports in its expanded `0:0:0:0:0:0:0:1` spelling — and the
IPv4-mapped `::ffff:127.0.0.1` a dual-stack socket may report. This matters
because the testbeds listen beyond loopback: `:dev-http` entries that set no
`:host` bind `0.0.0.0`, so the endpoint is reachable from the network and only
the peer check turns those callers away.

Running the testbed on another machine or in a container therefore no longer
works by pointing a browser at it, and widening the check is not the answer. An
SSH local port-forward is: `ssh -L 8031:localhost:8031 <host>` makes the tunnel
itself the caller, so the peer the server sees is genuine loopback and nothing
needs configuring at either end.

CORS reflects only a validated loopback origin; it never emits `*`. Missing
files return 422 before Node is spawned so the browser client can fall back to
its `editor://` URI. The handler lives in a `.clj` file and is never compiled
into a browser bundle.

A 200 from this endpoint means the whole source coordinate reached an editor,
not merely that a process exited. `launch-editor` encodes a position per editor
binary and falls through to a bare-file launch for binaries it has no case for
— which still exits 0. A coordinate-bearing request that would take that
fall-through is declined with 422 `editor-position-unsupported`, and the
browser's `editor://` fallback — which does carry the position — opens the file
at the right place instead.

Two routes answer that question, because the endpoint does not always choose
the binary:

- **A named editor.** `editor=windsurf` maps to a command the pinned
  `launch-editor` has no `get-args.js` case for, so it is declined before Node
  is spawned. The browser then navigates
  `windsurf://file/<path>:<line>:<column>`.
- **No editor at all.** The client sends no `editor` parameter for a nil
  preference or a `{:custom …}` one, which puts `launch-editor` on auto-detect:
  it picks a binary from the running process list, and that list reaches
  editors with no position case (Brackets on every platform; on Windows also
  `Cursor.exe`, whose capitalised process name the lowercase `cursor` case does
  not match). The launch shim therefore asks `get-args.js` itself what it would
  emit before launching, and declines the same way if the coordinate would be
  dropped. A nil preference then falls back to the default `vscode://` scheme,
  and a `{:custom …}` preference to its own template — which auto-detect had
  been ignoring.

A request carrying no line or column is never declined: there is nothing to
lose, and classpath resolution is worth having. Editors whose position
`launch-editor` does encode are unaffected and keep preferring the endpoint.

## Wiring

The testbed builds add `tools/testbed-support/src` to their shadow-cljs source
paths. Test builds also add `tools/testbed-support/test`. The local `deps.edn`
exists only for the JVM endpoint tests; it is not a packaging surface.

## Tests

From `implementation/`:

```bash
npm run test:testbed-support
npm run test:browser
```

The focused Node suite covers the Story-host listener lifecycle and the
client's endpoint-declined / URI-fallback contract. The browser suite covers
the real React-root handoff on a DOM node.

From `tools/testbed-support/`:

```bash
clojure -M:test
```

The JVM suite covers file resolution, query parsing, launch argument handling,
JSON responses, and the peer/method/host/origin guard — including a remote peer
sending a forged loopback `Host`, which must never launch. It also witnesses a real
relative Story coordinate and a real relative Xray coordinate resolving to
their on-disk files through the handler, with `launch!` stubbed.

## See also

- [`tools/README.md`](../README.md)
- [`tools/xray/`](../xray/)
- [`tools/story/`](../story/)
