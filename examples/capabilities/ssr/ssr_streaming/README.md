# A dashboard that streams in, card by card

This example models a dashboard with three slow cards (revenue, signups,
latency), plus a fourth that fails on purpose. With a live streaming host,
the page shell and header flush first and each card follows as its own chunk
when its data resolves. Picture three slow microservices behind those cards:
the browser can paint a usable skeleton before the real numbers arrive,
instead of holding the whole page until the slowest service answers. The
offline demo below preserves that wire shape, but does not simulate those
timings.

That's *streaming SSR*: the page stops waiting on its slowest part. It's
the React-18 / Next.js `loading.js` move — ship the shell first, fill the
holes as the data lands. In re-frame2 you wrap every slow region of the
[view](../../../../docs/core/glossary.md#view) in an
[`ssr/boundary`](../../../../docs/ssr/concepts.md#streaming-ssrboundary)
and name a `:fallback` to show in the meantime:

```clojure
[ssr/boundary
 {:id :card.revenue :fallback [card-skeleton :revenue]}
 [card-view :revenue]]
```

That is one form for both runtimes. On the server it becomes a deferred
region; in the browser it renders the card. There is no server-flavoured
copy of the view and no reader conditional.

Note the children are **Var references**, not keywords. A bare
`[:dashboard/card :revenue]` head is an HTML element, never a view — the
runtime doesn't intercept the keyword case to dispatch through the view
registry (see
[Conventions §Render-tree shape vs runtime lookup](../../../../spec/Conventions.md)),
so it would paint `<card>revenue</card>`. That rule now holds on **every**
host, server included: the JVM SSR emitter is a pure hiccup → HTML
function with no registry lookup, so a keyword head paints the same
element there as it does in the browser. Render trees reference views by
Var (`card-view`, which `reg-view` defs for you) or by `(rf/view :id)`.

One caveat up front: this demo runs **offline**, with no Clojure server.
Instead of fetching from three real services, `:rf/server-init` seeds all
three card values synchronously, and the page boots from a hand-written
`index.html` that bakes the whole streamed *wire shape* — the shell with a
fallback `<template>` per boundary, each card's resolved chunk, the failed
`:card.flaky` chunk, and the final payload — captured verbatim from the
streaming emitter (the plain data `handle-request` returns), frozen and
replayable, no live streaming host needed. The client runtime processes
those baked bytes exactly as it would a live stream; only the network
*timing* is missing. It's the worked
companion to
[Spec 011 §Streaming SSR](../../../../spec/011-SSR.md#streaming-ssr).

## What this demonstrates

- **The whole streaming API is one component.** Wrap a subtree with
  `ssr/boundary`, name a `:fallback`, and give it a stable `:id`. The
  streaming walker emits the fallback
  [hiccup](../../../../docs/core/glossary.md#hiccup) into the shell and
  flushes it on the first byte, then renders the real subtree and streams
  it in as its own chunk. There's no streaming-render mode and no
  per-host API — and because the boundary is a component rather than a
  keyword, the same form is what the browser renders too.

- **Each chunk carries its own slice of state.** A streamed-in card is no
  use if its [subscriptions](../../../../docs/core/glossary.md#subscription)
  can't see the data. So each resolved chunk ships a
  `<script data-rf2-suspense-hydrate>` carrying that card's
  [app-db](../../../../docs/core/glossary.md#app-db) delta. The client-side
  streaming runtime (`ssr/streaming-install!`, wired up in `run`) swaps
  the fallback for the resolved content and merges the delta into app-db
  — chunk by chunk, before the final payload arrives.

- **A failing region can't take down the page.** The fourth card
  ([`throwing-card`](core.cljc)) throws on purpose, standing in for a
  flaky third-party metric service. The streaming runtime catches the
  throw inside that one boundary, ships its fallback HTML in the
  resolved-chunk slot (marked `data-rf2-suspense-failed`) with no hydrate
  delta, and emits a `:rf.ssr/suspense-boundary-failed`
  [trace event](../../../../docs/core/glossary.md#trace-event). The other
  three cards stream on as if nothing happened. A thrown render takes down
  exactly one boundary — never the whole page.

- **The deltas are a speed bet; the final payload is the truth.** This is
  the load-bearing idea. The per-card deltas are *speculative* — they
  exist only to paint each region early. The final
  `<script id="__rf_payload">` arrives last, carrying the canonical
  whole-app-db state, and `ssr/hydrate!` dispatches `:rf/hydrate` against
  it with
  [replace-frame-state](../../../../docs/ssr/glossary.md#hydration) semantics
  (the server's state replaces the client's whole frame-state in one
  step). If a speculative delta and the canonical payload ever disagree,
  the payload wins. You get the *latency* of streaming with the
  *correctness* of a single authoritative hydrate. Even a client that
  missed the inline chunks entirely (JS disabled mid-stream, say) still
  lands on a coherent app-db from the final payload alone.

## Why this shape

Notice what *isn't* here: no second, server-flavoured copy of the app.
Streaming SSR falls straight out of the per-request
[frame](../../../../docs/core/glossary.md#frame) model. The server runs
your real views against an isolated frame for the request, and
`:rf/suspense-boundary` is just the marker that says "this region is
allowed to arrive late."

One subtlety the source is careful about (worth a glance if you read
`core.cljc`): there are *two* frames in play, and they don't share an id.
The server renders under a fresh per-request frame (a gensym, in
`handle-request`); the client hydrates a fixed app-frame (`:rf/default`,
in `run`). The same
[schema](../../../../docs/core/glossary.md#schema) is registered explicitly
against *each* with a `{:frame …}` override, because that's where the
`:cards` commit actually validates on either side. That validation is a
development-time assertion: a production build performs the registration
and elides the check ([Spec 010 §Production
builds](../../../../spec/010-Schemas.md#production-builds)), so neither
commit is verified in a release build. The server then drops its
per-request frame-id from the payload, so the client's explicit `:frame`
stands rather than tripping a frame-id mismatch.

The other subtlety is the boundary itself. `ssr/boundary` is a
**component**, not a hiccup keyword, and that is load-bearing rather than
cosmetic. A keyword head is an HTML element on every host, so a marker
left in a client render tree paints a phantom `<suspense-boundary>`
element — a quiet failure, not a loud one. And the marker could not be
given client semantics either: stock Reagent is an external dependency
whose element dispatch isn't ours to extend, and UIx views are
`defui` / `$` forms where a hiccup keyword head cannot occur at all. A
callable component is the one form expressible everywhere, so that is the
authoring surface. `:rf/suspense-boundary` still exists, demoted to
internal wire syntax between the component and the shell walker.

Which fallback the client shows is the boundary's business, not every
view's. `:card.flaky`'s boundary failed on the server, so the final
payload reports it in its failed set, and the boundary that declared
`[card-skeleton :flaky]` re-renders exactly that. `card-view` needs no
defensive nil branch duplicating the skeleton to keep the client's render
agreeing with the DOM the stream painted.

Hydration waits for **readiness**. The streaming runtime signals
`:on-ready` once the last chunk has landed, every delta is consumed, and
every `<rf-suspense>` mount it created has been unwrapped; only then does
`run` hydrate. Those wrappers are transport, and hydrating while they are
still in the DOM is a structural mismatch on every boundary — the page's
content can be byte-correct and React will still discard it.

The `.cljc` shape mirrors [`examples/capabilities/ssr/ssr/core.cljc`](../ssr/core.cljc):
the server entry point lives in `:clj`, the browser bootstrap in `:cljs`.
The `:clj` branch is what a Ring streaming adapter would invoke per
request; the `:cljs` branch is what the page bootstraps once the chunks
land. The *views* are shared verbatim — only the entry points differ. One
artefact, two runtimes — the same trick the non-streaming SSR example
plays, with the streaming counterparts swapped in.

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

Then serve the built folder over HTTP alongside this folder's
hand-written [`index.html`](index.html) and open it.

New to SSR here? Read the non-streaming
[`examples/capabilities/ssr/ssr/`](../ssr/) counterpart first. The normative
section is [`spec/011-SSR.md` §Streaming SSR](../../../../spec/011-SSR.md#streaming-ssr).
