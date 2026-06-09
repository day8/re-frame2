# tools/testbed-support/

A small **dev-only** support library shared by the Xray and Story
browser testbeds. Two namespaces:

- `re-frame.testbed.config` — derives the on-disk project root those
  testbeds hand to their "open in editor" source-coord resolvers,
  cross-platform and with no hardcoded checkout path.
- `re-frame.testbed.story-host` — the live-app ↔ Story-shell hash-toggle
  host harness the Story showcase testbeds share (rf2-tq26t / rf2-uv7sn).

## What it is

Xray and Story turn a source-coord (`standard_epochs/core.cljs:42`) into
an editor URI by prepending a project-root. Shipped code reads that root
from host config; the *testbeds* that drive Xray / Story have to pass one
in, and they previously each hardcoded the author's absolute Windows
checkout — six copies of one machine-specific string that broke on every
other clone and every Mac/Linux maintainer (rf2-5dphw).

`re-frame.testbed.config` replaces that with a single shared helper:

- **`repo-root`** — a `goog-define`d string (default `""`). Seeded per
  build via `#shadow/env` from the `RF2_TESTBED_PROJECT_ROOT` env var,
  which the dev launcher (`implementation/scripts/dev-testbed.cjs`,
  wired as the `dev` npm script) resolves from its own location using
  node's `path` module — identical on Windows, macOS, and Linux.
- **`resolve-project-root`** — joins a checkout root with the
  tool-relative testbed subdir the caller passes (e.g.
  `"tools/xray/testbeds"`). A `?project-root=<checkout>` query string
  wins over the build-time root as a per-session escape hatch (CI, a
  reader on another machine, a copied bundle). It names the same thing
  as the build-time root — a **checkout root**, not the final source-path
  root — and the subdir is appended the same way, so the composed editor
  URI reaches `<checkout>/tools/xray/testbeds` where the classpath-relative
  source coords resolve (rf2-w4yw9q). Paste the checkout root unencoded,
  including a literal `+` (e.g. `?project-root=/home/dev/re-frame2+wip`);
  the parser decodes percent-escapes but preserves `+` rather than
  mapping it to a space (rf2-xdsat.1). Both tiers and the subdir are
  normalised to a canonical forward-slash form (`\` → `/`, trailing /
  leading slash stripped), so a Windows override
  (`?project-root=C:\Users\me\code\re-frame2\`, raw or `%5C`-encoded)
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
every testbed but for the live-app root view. It was copy-pasted across
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
consumer declares its tool-relative source subdir via `:story-subdir` (e.g.
`{:story-subdir "examples/reagent"}`) and the host resolves the on-disk root
through `resolve-project-root` (build-env define or `?project-root=` override,
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
├── src/re_frame/testbed/
│   ├── config.cljs                           ; resolve-project-root + the repo-root goog-define
│   └── story_host.cljs                       ; mount-with-hash-routing! (live-app↔shell host)
└── test/re_frame/testbed/
    ├── config_cljs_test.cljs                 ; CLJS unit tests for the resolver
    └── story_host_cljs_test.cljs             ; CLJS unit tests for the host harness
```

## How it's wired

This library is **not a published jar** — there is no `deps.edn` or
Clojars coord. It is consumed purely as an extra source path: the
testbed builds add `../tools/testbed-support/src` to their source paths
in [`implementation/shadow-cljs.edn`](../../implementation/shadow-cljs.edn),
and seed `re-frame.testbed.config/repo-root` via that file's
`:closure-defines`. The sibling `../tools/testbed-support/test` path is
also listed so the always-on `:node-test` build discovers the unit
suites (see below). Bundle-isolation holds: nothing under
`implementation/` `:require`s it.

## How to test

Both `config_cljs_test.cljs` (the resolver) and `story_host_cljs_test.cljs`
(the host harness's listener lifecycle / hot-reload behaviour) run as part
of the always-on CLJS gate: `npm run test:cljs` from `implementation/`
compiles the `:node-test` build, whose `:ns-regexp "cljs-test$"` discovers
both namespaces through this slice's wired `../tools/testbed-support/test`
source path. There is no standalone test alias for this directory (no
`deps.edn`); the suites live under a dedicated `test/` root — the same
src/test split every other tool/artefact uses — and the wired test source
path picks them up.

## See also

- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation contract.
- [`tools/xray/`](../xray/) and [`tools/story/`](../story/) — the testbeds that consume this helper.
