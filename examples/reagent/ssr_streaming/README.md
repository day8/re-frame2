# ssr_streaming — Spec 011 Streaming SSR worked example

A dashboard with three slow cards: the page's shell + header render
immediately on the server, then each card streams its content as its
data fetch resolves. In a real deployment with three slow microservices
the browser would show a usable shell within ~50ms while the cards
trickle in over ~300ms each; this canned demo seeds all three cards
synchronously from `:rf/server-init` so the demo runs offline. The
worked companion to
[Spec 011 §Streaming
SSR](../../../spec/011-SSR.md#streaming-ssr).

## What this demonstrates

- **`:rf/suspense-boundary`** — declarative hiccup marker for a
  streamable subtree. The server emits the fallback hiccup immediately
  and back-fills the real content when the boundary's data resolves.
- **Per-card fallback hiccup** — each card carries
  `[:div.card.skeleton …]` so the user sees a skeleton, not a blank
  region, while the real content streams.
- **Inline-fallback failure semantics** — one card deliberately
  throws to demonstrate that the failure path doesn't 500 the page;
  the failed boundary renders its fallback and the rest of the page
  continues.
- **Hydration interleaved per subtree** — each chunk's
  `<script data-rf2-suspense-hydrate>` carries the per-card app-db
  delta; the **client-side streaming runtime** (`ssr/streaming-install!`,
  wired up in `run`) swaps each fallback for its resolved content and
  merges the delta into `app-db` progressively, as chunks arrive,
  before the final payload.
- **Final `__rf_payload`** — arrives last with the canonical full
  state; `ssr/hydrate!` dispatches `:rf/hydrate` (`:replace-app-db`)
  against it, the correctness lock that supersedes the speculative
  per-card deltas. A client that missed inline chunks (e.g. JS disabled
  during streaming) still gets a coherent final app-db.

## Why this shape

Streaming SSR is the natural extension of Spec 011's per-request
frame model: the same `with-frame` boundary owns the streaming
request lifecycle, and `:rf/suspense-boundary` is the hiccup marker
that turns a region of the view tree into a streamable chunk.

The `.cljc` shape mirrors `examples/reagent/ssr/core.cljc`: server
branch in `:clj`, browser branch in `:cljs`. The `:clj` branch is
what a Ring server would invoke; the `:cljs` branch is what the page
bootstraps after the chunks arrive.

## Files

```
ssr_streaming/
  core.cljc                              — server + client, one artefact.
  index.html                             — host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/ssr-streaming
```

The watch build emits `main.js` into `out/examples/ssr-streaming/`;
copy this folder's hand-written [`index.html`](index.html) (and the
shared assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/ssr-streaming/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); this example's JVM smoke
(shell render → per-card resolved chunks → final payload) was folded
into [`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
(the `ssr-streaming-example-runs-end-to-end` deftest).
Broader streaming-SSR contract testing lives in the
`implementation/ssr/test/` suite.

## Cross-references

- [`spec/011-SSR.md` §Streaming SSR](../../../spec/011-SSR.md#streaming-ssr) — the normative section.
- [`examples/reagent/ssr/`](../ssr/) — the non-streaming SSR counterpart; read that first.
