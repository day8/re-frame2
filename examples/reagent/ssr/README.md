# ssr — Spec 011 SSR + hydration worked example

A small server+client app: the server renders a "recent articles"
page; the client hydrates and remains interactive. The worked
companion to [Construction Prompt
CP-9](../../../spec/Construction-Prompts.md) and [Spec
011](../../../spec/011-SSR.md).

## What this demonstrates

- **Per-request frame on the server** — `with-frame` creates an
  isolated frame for the request; no global state leaks between
  concurrent renders.
- **`:rf/server-init`** — dispatched at frame creation, runs the
  per-request initialisation graph (article fetch, app-db seed).
- **Server-only fx via `:platforms #{:server}`** — fx that only fire
  on the server (and are skipped on the client). The article fetch
  uses this.
- **Pure hiccup → HTML emitter** — `rf/render-to-string` produces
  the HTML payload from registered views, with no React server-render
  dependency.
- **Hydration payload** — the `:rf/hydration-payload` schema is the
  contract between server and client; the server bakes it into a
  `<script id='__rf_payload'>` tag.
- **`:rf/hydrate` on the client** — *replaces* (not merges) the
  client app-db with the server payload, per the locked
  `:replace-app-db` policy.
- **`data-rf-render-hash`** — structural marker on the root element;
  the runtime diffs server vs. client hashes after first render and
  emits `:rf.ssr/hydration-mismatch` on disagreement.

## Why .cljc

The same code is evaluated server-side (JVM, with `:clj` branches)
and client-side (browser, with `:cljs` branches). One artefact, two
runtimes. This is the canonical shape — `:clj` for the `handle-request`
that returns HTML+payload; `:cljs` for the `run` that bootstraps from
the baked payload.

## Why this shape

SSR is part of the target architecture, not a future concession (per
Spec 011's framing). This example is the smallest end-to-end
demonstration of every contract surface in Spec 011: per-request
frame, hydration payload, replace-not-merge hydration, render-hash
sentinel.

Runnable form: the hand-written `index.html` ships with pre-rendered
HTML inside `<div id='app'>` and a pre-baked `<script id='__rf_payload'>` —
exactly the shape `handle-request` would emit if a real Clojure
server sat in front. The browser-side `run` reads the payload,
dispatches `:rf/hydrate`, and renders against the now-seeded app-db.

## Files

```
ssr/
  core.cljc                    — server + client, one artefact.
  index.html                   — pre-rendered shell + payload (mocks a real server emission).
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/ssr
```

The watch build emits `main.js` into `out/examples/ssr/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/ssr/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); this example's JVM smoke
(per-request frame → server flow → render-to-string + render-hash) was
folded into [`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
(the `ssr-example-runs-end-to-end` deftest). Broader SSR
contract testing lives in the `implementation/ssr/test/` suite plus
`spec/conformance/fixtures/ssr-*.edn`.

## Cross-references

- [Construction Prompts CP-9](../../../spec/Construction-Prompts.md) — the prompt this example instantiates.
- [`spec/011-SSR.md`](../../../spec/011-SSR.md) — the normative spec.
- [`examples/reagent/ssr_streaming/`](../ssr_streaming/) — the streaming-SSR counterpart.
- [`examples/reagent/realworld/`](../realworld/) — SSR boot folded into a broader app.
