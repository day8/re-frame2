# routing — Spec 012 worked example

A three-page app — home, articles list, article detail — that
demonstrates URL ↔ frame state, navigation as event, route as
subscription, and route-aware root-view dispatch. The worked
companion to [Construction Prompt
CP-7](../../../spec/Construction-Prompts.md) and [Spec
012](../../../spec/012-Routing.md).

## What this demonstrates

- **`reg-route`** — the route table as registered data, one entry per
  page (`:routing.app/home`, `:routing.app/articles`,
  `:routing.app/article-detail`, `:rf.route/not-found`).
- **`:rf.route/navigate`** — programmatic navigation. The view
  dispatches a navigate fx; the runtime updates the URL and the route
  slice.
- **`:rf.route/handle-url-change`** — popstate / initial-load handler
  that pulls the URL into frame state.
- **`:rf.route/id` and `:rf.route/params`** — route reads as plain
  subs; the root view dispatches on `:rf.route/id` to render the
  right page.
- **`:rf/url-requested`** — user-initiated anchor clicks. The runtime
  catches the click, dispatches the event, and the route slice
  updates without a full-page reload.
- **`route-link`** — convenience link component that synthesises the
  click → navigate flow.
- **Server-and-client-shared handler** — the route handler is the
  same on both sides; the SSR example reuses this surface.

## Why this shape

The smallest example that exercises the full Spec 012 surface end to
end. A real SPA needs a route table, navigation as event, route as
sub, and a root-view switch — this example has exactly those four in
the canonical shape, so AI agents reading it have a complete pattern
to scaffold against.

## Files

```
routing/
  core.cljs    — route table, app data, sub graph, root view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/routing
```

The watch build emits `main.js` into `out/examples/routing/`; copy
this folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/routing/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free
per [`examples/README.md`](../../README.md); routing contract testing
lives in `implementation/routing/test/` and the conformance fixtures.

## Cross-references

- [Construction Prompts CP-7](../../../spec/Construction-Prompts.md) — the prompt this example instantiates.
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the normative spec.
- [`examples/reagent/ssr/`](../ssr/) — SSR over a routed app reuses the same handler.
- [`examples/reagent/realworld/`](../realworld/) — broader sketch with routing folded in.
