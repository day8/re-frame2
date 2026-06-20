# tools/testbed-support/

A small **dev-only** support library shared by the Xray and Story
browser testbeds. Three namespaces:

- `re-frame.testbed.config` — derives the on-disk source root those
  testbeds hand to their "open in editor" source-coord resolvers (a
  build-env-seeded **checkout root** plus the caller's tool subdir),
  cross-platform and with no hardcoded checkout path.
- `re-frame.testbed.story-host` — the live-app ↔ Story-shell hash-toggle
  host harness the Story showcase testbeds share.
- `re-frame.testbed.open-in-editor-server` — a JVM-only (`.clj`)
  shadow-cljs `:dev-http` handler that answers the
  `POST /__rf-open-in-editor` endpoint, resolving a
  classpath-relative source `:file` against the dev JVM's source-paths at
  runtime and launching the editor via the `launch-editor` npm package
  (the re-frame2 equivalent of Vite's `/__open-in-editor`). Because it
  launches the editor on a local file, it is POST-only and loopback-guarded
  (the request must be addressed to a loopback `Host` and, when present,
  carry a loopback `Origin`; CORS reflects that origin, never `*`) — the
  drive-by class the historic Vite / react-dev-utils CVEs hit. It runs on
  the shadow-cljs SERVER JVM and is never part of any browser/CLJS build.

## What it is

Xray and Story turn a source-coord (`standard_epochs/core.cljs:42`) into
an editor URI by prepending a project-root. Shipped code reads that root
from host config; the *testbeds* that drive Xray / Story have to pass one
in, and they previously each hardcoded the author's absolute Windows
checkout — six copies of one machine-specific string that broke on every
other clone and every Mac/Linux maintainer (rf2-5dphw).

`re-frame.testbed.config` replaces that with a single shared helper:

- **`checkout-root`** — a `goog-define`d string (default `""`). Seeded per
  build via `#shadow/env` from the `RF2_TESTBED_PROJECT_ROOT` env var,
  which the dev launcher (`implementation/scripts/dev-testbed.cjs`,
  wired as the `dev` npm script) resolves from its own location using
  node's `path` module — identical on Windows, macOS, and Linux.
- **`resolve-source-root`** — joins a **checkout root** input with the
  tool-relative testbed subdir the caller passes (e.g.
  `"tools/xray/testbeds"`) to produce the **source root** output. A
  `?checkout-root=<checkout>` query string wins over the build-time
  `checkout-root` define as a per-session escape hatch (CI, a reader on
  another machine, a copied bundle). Both input tiers name a checkout
  root and have the subdir appended the same way, so the composed editor
  URI reaches `<checkout>/tools/xray/testbeds` where the classpath-relative
  source coords resolve (rf2-w4yw9q). Paste the checkout root unencoded,
  including a literal `+` (e.g. `?checkout-root=/home/dev/re-frame2+wip`);
  the parser decodes percent-escapes but preserves `+` rather than
  mapping it to a space (rf2-xdsat.1). Both tiers and the subdir are
  normalised to a canonical forward-slash form (`\` → `/`, trailing /
  leading slash stripped), so a Windows override
  (`?checkout-root=C:\Users\me\code\re-frame2\`, raw or `%5C`-encoded)
  resolves to a clean single-separator path rather than a `\/` boundary —
  matching the launcher's build-env normalisation and the
  separator-agnostic editor-URI composer (rf2-d01s6s). When neither tier
  is present it returns `nil`, and the testbed configures no root —
  "open in editor" degrades to a graceful no-op rather than a broken link.

## The Story host harness (`story-host`)

Every Story showcase entry point hosts two surfaces on the same `#app`
node, one React root at a time — `#/` the live app, `#/stories` the Story
shell. The plumbing that switches between them (a `defonce` React-root
handle, the tear-down-one-before-mounting-the-other dance, and the
`hashchange` listener) is pure React-DOM-root juggling — identical across
every testbed but for the live-app root view. That root view is not a bare
React tree: it is a **frame-scoped subtree** — a frame, supplied by the
consuming testbed via `frame-provider`, resolving its handlers against a
loaded image (EP-0023 §Views). This harness sits strictly ABOVE that
frame/image boundary — it owns the React-root + hashchange mechanics and
treats the testbed's root view as an opaque frame subtree; it neither
creates frames nor loads images (that is the consuming testbed's job, via
`reg-frame` / `with-frame` / `rf/init!`). It was copy-pasted across
six hosts (`counter_with_stories`, `login_form`, the `login` and
`nine_states` examples, the Xray `panel_gallery` testbed, plus the template
scaffolding), already drifting in the per-testbed boot specifics they each add.

`re-frame.testbed.story-host/mount-with-hash-routing!` owns that harness:
the testbed does its own boot (Xray config, `rf/init!`, `:fx-overrides`,
seed dispatches, CI hooks) and calls the helper LAST with its live-app root
view. Five of the six hosts now call it (the four Story showcases above plus
`panel_gallery`, which previously installed a bare per-`run` `hashchange`
listener — rf2-x31vn); the **template copy stays standalone by design** — it
is `resources/` scaffolding emitted into a fresh consumer project whose
classpath has no access to this dev-repo-internal helper.

### The open-in-editor project-root is a host responsibility (rf2-77wqzi)

Story stamps each registered source-coord with a **classpath-relative**
`:file` slot (e.g. `login/stories.cljs`); the 'open in editor' chip prepends
an on-disk project-root to build a real editor URI. That config used to be
left inline in every consuming `run` (`story/configure! {:rf.story/project-root …}`),
and the two `examples/reagent` showcases silently forgot it — they mounted
the shell fine but their Story source links resolved against a nil root, so
OS editor handlers could not open the file (a false green).

The optional second arg to `mount-with-hash-routing!` closes that gap: a
consumer declares its tool-relative source subdir via `:source-subdir` (e.g.
`{:source-subdir "examples/reagent"}`) and the host resolves the on-disk root
through `resolve-source-root` (build-env define or `?checkout-root=` override,
cross-platform) and calls `story/configure!` itself — which also bridges the
root into Xray's slot. Omit the opt (or use the 1-arity call) when the
consumer drives `story/configure!` itself. A blank subdir, or a checkout with
no resolvable root, configures nothing (graceful no-op). Declaring the subdir
means a Story-host consumer can no longer mount the shell while silently
forgetting the project-root config.

## Layout

```
tools/testbed-support/
├── README.md                                 ; this file
├── deps.edn                                  ; JVM :test classpath for the open-in-editor server (test-only, not a published jar)
├── src/re_frame/testbed/
│   ├── config.cljs                           ; resolve-source-root + the checkout-root goog-define
│   ├── story_host.cljs                       ; mount-with-hash-routing! (live-app↔shell host)
│   └── open_in_editor_server.clj             ; JVM-only :dev-http open-in-editor endpoint handler
└── test/re_frame/testbed/
    ├── config_cljs_test.cljs                 ; CLJS unit tests for the resolver
    ├── story_host_cljs_test.cljs             ; CLJS unit tests for the host harness (node)
    ├── story_host_dom_cljs_test.cljs         ; browser-level live↔shell root-handoff test
    └── open_in_editor_server_test.clj        ; JVM unit tests for the endpoint's :file resolution
```

## How it's wired

This library is **not a published jar** — it carries no Clojars coord.
The CLJS halves are consumed purely as an extra source path: the testbed
builds add `../tools/testbed-support/src` to their source paths
in [`implementation/shadow-cljs.edn`](../../implementation/shadow-cljs.edn),
and seed `re-frame.testbed.config/checkout-root` via that file's
`:closure-defines`. The sibling `../tools/testbed-support/test` path is
also listed so the always-on `:node-test` build discovers the unit
suites (see below). Bundle-isolation holds: nothing under
`implementation/` `:require`s it.

There **is** a `deps.edn`, but it is a **test-only** harness, not a
packaging surface: the open-in-editor endpoint is a JVM-only `.clj` that
no CLJS build compiles, so its `:file`-resolution logic can only be
exercised by a JVM `clojure -M:test` gate (see below). The `deps.edn`
exists solely to give that `.clj` a JVM test classpath; it declares no
Clojars coord and the testbeds keep consuming `src/` as a source path.

## How to test

Four suites under `test/` — three CLJS, one JVM:

- `config_cljs_test.cljs` — the resolver: param parsing, cross-platform
  path joining (Windows / POSIX, including the lone-`/` filesystem-root
  edge), and the build-time-vs-`?checkout-root=` tier precedence.
- `story_host_cljs_test.cljs` — the host harness's `hashchange`-listener
  lifecycle / hot-reload idempotence and the project-root config contract,
  with the mount switch stubbed to count-only no-ops (listener-identity
  focus, runs under `:node-test`).
- `story_host_dom_cljs_test.cljs` — a **browser-level** handoff test
  that drives the host through `#/` → `#/stories` → `#/`
  (plus a hot-reload re-run) on a real `#app` node with **real React
  roots**, asserting the live ↔ shell root handoff leaks no root and emits
  no `createRoot`-reuse warning. ns ends in `-dom-cljs-test`, so the
  `:browser-test` build runs the real-DOM assertions; under `:node-test`
  its body gates on `(browser?)` and no-ops.
- `open_in_editor_server_test.clj` — a **JVM** suite for the
  open-in-editor endpoint's `:file` resolution: that `resolve-file` /
  `file-url->path` decode a classpath `file:` URL with a literal `+` in
  the path verbatim (the `URLDecoder`-maps-`+`-to-space corruption guard),
  decode `%20` / `%2B` correctly, and strip the Windows drive-letter
  leading slash. The endpoint is JVM-only `.clj` that no CLJS build
  compiles, so it rides the `clojure -M:test` gate below rather than the
  node suites.

### The always-on gate

`npm run test:cljs` from `implementation/` compiles the `:node-test` build,
whose `:ns-regexp "cljs-test$"` discovers all three namespaces through this
slice's wired `../tools/testbed-support/test` source path; `npm run
test:browser` runs the DOM suite's real-React assertions. The slice rides
both on every PR.

### The focused CLJS gate

Workers no longer need the full ~3500-test consolidated build to
verify the CLJS halves of this slice. `npm run test:testbed-support` from
`implementation/` compiles a dedicated `:node-test-testbed-support` build
whose `:ns-regexp "^re-frame\.testbed\..+-cljs-test$"` matches ONLY the
`re-frame.testbed.*` test namespaces, then runs it on Node — a cheap,
named, slice-scoped gate. (It still needs the shared shadow-cljs binary
`npm install` provides; it does not depend on the rest of the node suite
compiling.) The DOM suite's real-React-root assertions still require the
`:browser-test` runner — the focused node build exercises only the
node-runnable bodies. The suites live under a dedicated `test/` root — the
same src/test split every other tool/artefact uses.

### The JVM gate (open-in-editor endpoint)

The open-in-editor endpoint is a JVM-only `.clj`, so its
`:file`-resolution logic is gated by a JVM test rather than the node
suites. Run `clojure -M:test` from `tools/testbed-support/` — it uses the
same `test/` root and the silent-on-success quiet runner every other
per-artefact `:test` alias uses, and exercises
`open_in_editor_server_test.clj` against the real classpath-resolution
path (including a throwaway classpath root whose directory name carries a
literal `+`).

## See also

- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation contract.
- [`tools/xray/`](../xray/) and [`tools/story/`](../story/) — the testbeds that consume this helper.
