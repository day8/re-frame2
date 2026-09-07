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

Call after the consumer has completed frame/image boot:

```clojure
(mount-with-hash-routing! live-app)
```

The host switches between the live app and Story on the same `#app` node.
See the [Story-host contract](spec/README.md#story-host) for hash selection,
React-root ownership and hot reload. Consumer Story configuration stays with
the consumer.

## Open-in-editor server

Wire `re-frame.testbed.open-in-editor-server/handler` as the shadow-cljs
fallback handler. It resolves source coordinates at request time and prefers
the local editor endpoint; the browser retains its URI fallback on refusal.

The [editor contract](spec/README.md#open-in-editor-server) owns the loopback
boundary and position-preserving launch behavior; the
[wire contract](spec/README.md#endpoint-wire-contract) names request arguments
and responses. For a remote dev host, use a local SSH port-forward rather than
widening the endpoint's peer check.

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
