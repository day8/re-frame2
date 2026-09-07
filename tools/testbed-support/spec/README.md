# Testbed support — contract

This is the canonical contract for the two dev-only namespaces in
`tools/testbed-support`. It is a source-path support library, not a published
artefact. Its local `deps.edn` runs JVM tests; it is not a release coordinate.
No production namespace may depend on this tool.

The scope is intentionally small: one shared live-app/Story host and one local
editor endpoint. Consumer frame/image boot, Story configuration, source-coordinate
vocabulary and editor URI construction retain their existing owners.
See the [usage and test commands](../README.md).

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

A request carrying no line or column is not declined for position support:
there is no position to lose, and classpath resolution is worth having. The
ordinary admission, file and launch checks still apply. Editors whose position
`launch-editor` does encode are unaffected and keep preferring the endpoint.

## Endpoint wire contract

`handler` is the shadow-cljs fallback entry point. The lower-level `handle`
returns nil for another URI; `handler` turns that fallthrough into a plain-text
404. On `/__rf-open-in-editor`, the request's query string carries `file`
(required), `line`, `column` and `editor`; it is not a JSON-body API.

Query values are percent-decoded without turning a literal `+` into a space.
A column without a line targets line 1. Classpath-relative source files are
resolved through the framework resolver, then the dev-process working directory;
absolute paths need no checkout-root setting.

| Result | Status | JSON body |
|---|---|---|
| Successful launch | 200 | `{"ok":true,"file":"<resolved path>"}` |
| Missing file parameter / malformed percent encoding | 400 | `{"ok":false,"error":"missing-file"}` / `{"ok":false,"error":"malformed-query"}` |
| Refused peer, Host or Origin | 403 | `{"ok":false,"error":"forbidden"}` |
| Disallowed method on an otherwise allowed request | 405 | `{"ok":false,"error":"method-not-allowed"}` |
| Missing file, unsupported position, launch failure or timeout | 422 | `{"ok":false,"error":"<reason>"}` |

Loopback `OPTIONS` returns 204 with no body and allows `POST, OPTIONS`;
a remote peer is rejected before preflight. JSON responses are non-cacheable.
CORS reflects a validated loopback Origin, otherwise `null`, never `*`.

The launcher receives file and position as separate process arguments, not a
shell command assembled from the coordinate. Its wait is bounded (10 seconds
by default); timeout terminates the child and returns a failure. Unused stdout
is discarded and stderr drained concurrently with a bounded retained diagnostic.
These are existing endpoint behavior, not a general process-management API.

## Ownership and witnesses

- [Story host implementation](../src/re_frame/testbed/story_host.cljs) owns
  the root handoff and retained hash-listener handle.
- [Editor endpoint implementation](../src/re_frame/testbed/open_in_editor_server.clj)
  owns request admission, runtime resolution and launch outcomes.
- [Host lifecycle tests](../test/re_frame/testbed/story_host_cljs_test.cljs)
  witness repeated installation and hash-driven ownership transfer.
- [Endpoint tests](../test/re_frame/testbed/open_in_editor_server_test.clj)
  cover admission, decoding, resolution, position preservation and launch
  outcomes; [client tests](../test/re_frame/testbed/open_in_editor_client_cljs_test.cljs)
  cover endpoint refusal handing control back to URI fallback.
- The browser-root handoff and test commands are indexed in the
  [tool README](../README.md#tests). A stubbed launch proves endpoint behavior,
  not that a user's editor actually opened.
