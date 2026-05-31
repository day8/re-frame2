# tools/testbed-support/

A small **dev-only** support library shared by the Xray and Story
browser testbeds. One namespace — `re-frame.testbed.config` — that
derives the on-disk project root those testbeds hand to their
"open in editor" source-coord resolvers, cross-platform and with no
hardcoded checkout path.

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
- **`resolve-project-root`** — joins that build-time root with the
  tool-relative testbed subdir the caller passes (e.g.
  `"tools/xray/testbeds"`). A `?project-root=<path>` query string wins
  as a per-session escape hatch (CI, a reader on another machine, a
  copied bundle). When neither is present it returns `nil`, and the
  testbed configures no root — "open in editor" degrades to a graceful
  no-op rather than a broken link.

## Layout

```
tools/testbed-support/
├── README.md                                 ; this file
└── src/re_frame/testbed/
    ├── config.cljs                           ; resolve-project-root + the repo-root goog-define
    └── config_cljs_test.cljs                 ; CLJS unit tests for the resolver
```

## How it's wired

This library is **not a published jar** — there is no `deps.edn` or
Clojars coord. It is consumed purely as an extra source path: the
testbed builds add `../tools/testbed-support/src` to their source paths
in [`implementation/shadow-cljs.edn`](../../implementation/shadow-cljs.edn),
and seed `re-frame.testbed.config/repo-root` via that file's
`:closure-defines`. Bundle-isolation holds: nothing under
`implementation/` `:require`s it.

## How to test

The `config_cljs_test.cljs` corpus runs as part of the testbed CLJS test
surface; there is no standalone test alias for this directory (no
`deps.edn`).

## See also

- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation contract.
- [`tools/xray/`](../xray/) and [`tools/story/`](../story/) — the testbeds that consume this helper.
