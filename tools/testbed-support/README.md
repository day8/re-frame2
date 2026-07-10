# tools/testbed-support/

Dev-only support shared by the Xray and Story browser testbeds. It is not a
published library and no production namespace depends on it.

The surface contains three namespaces:

- `re-frame.testbed.config` resolves an on-disk source root for source
  coordinates.
- `re-frame.testbed.story-host` switches one `#app` node between a live app and
  the Story shell.
- `re-frame.testbed.open-in-editor-server` provides the JVM-side
  `POST /__rf-open-in-editor` handler used by shadow-cljs dev servers.

## Source-root configuration

`resolve-source-root` appends a caller-supplied source subdirectory, such as
`tools/xray/testbeds`, to the checkout root. The checkout root comes from one of
two places, in precedence order:

1. `?checkout-root=<path>` in the browser URL, for a per-session override.
2. The `re-frame.testbed.config/checkout-root` goog-define, seeded from
   `RF2_TESTBED_PROJECT_ROOT` by `implementation/shadow-cljs.edn`.

The result uses forward slashes and one separator at each join. Both raw and
percent-encoded Windows separators are accepted. Query parsing preserves a
literal `+`, because checkout directory names may contain one. When neither
root is available, the resolver returns `nil` and open-in-editor remains a
no-op.

## Story host

`mount-with-hash-routing!` selects the surface from the current hash:

- `#/stories...` mounts the Story shell.
- Any other hash mounts the supplied live-app root view.

The consumer must create its frame, load its image, and complete its own boot
before calling the host. The host owns only the React-root handoff and the
`hashchange` listener. It unmounts the current root before the other surface
claims `#app`.

The listener handle is stored in a `defonce` atom and explicitly removed before
each install. This matters during hot reload: recompiling a top-level CLJS
function changes its JavaScript identity, so `addEventListener` cannot dedupe a
listener from the previous compile.

Pass `{:source-subdir "..."}` when the host should also configure Story's
open-in-editor project root:

```clojure
(mount-with-hash-routing!
  live-app
  {:source-subdir "tools/story/testbeds"})
```

Omit the option when the consumer manages `story/configure!` itself.

## Open-in-editor server

The JVM namespace is a shadow-cljs `:dev-http` fallback handler. It resolves a
classpath-relative `file` query parameter against the dev JVM's source paths,
falls back to the process working directory, and invokes the `launch-editor`
npm package through Node.

Because this endpoint can open a local file, it accepts launches only when all
of these conditions hold:

- The request method is `POST`.
- The `Host` header names a loopback host.
- When present, `Origin` also names a loopback host.

CORS reflects only a validated loopback origin; it never emits `*`. Missing
files return 422 before Node is spawned so the browser client can fall back to
its `editor://` URI. The handler lives in a `.clj` file and is never compiled
into a browser bundle.

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

The focused Node suite covers config parsing and Story-host listener lifecycle.
The browser suite covers the real React-root handoff on a DOM node.

From `tools/testbed-support/`:

```bash
clojure -M:test
```

The JVM suite covers file resolution, query parsing, launch argument handling,
JSON responses, and the method/loopback/origin guard.

## See also

- [`tools/README.md`](../README.md)
- [`tools/xray/`](../xray/)
- [`tools/story/`](../story/)
