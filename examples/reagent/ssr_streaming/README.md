# ssr_streaming — Spec 011 Streaming SSR worked example

The whole point of streaming SSR is to stop making the page wait on its
slowest subscription. This example is a dashboard with three slow cards
(revenue, signups, latency) plus a fourth that deliberately falls over.
The page shell and header render and flush *immediately* on the server;
then each card streams in as its own chunk, the moment its data
resolves. Picture three slow microservices behind those cards: the
browser would paint a usable skeleton within ~50ms and the real numbers
would trickle in over ~300ms each, rather than the whole page sitting
blank until the slowest service answers.

That's the React-18 / Next.js `loading.js` move — ship the shell first,
fill the holes as the data lands — and in re-frame2 it's *one hiccup
marker*, not a component API. Every slow region in the
[view](../../../docs/guide/glossary.md#view) is wrapped in a
[`:rf/suspense-boundary`](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary)
that names a `:fallback` to show in the meantime:

```clojure
[:rf/suspense-boundary
 {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
 [:dashboard/card :revenue]]
```

The one wrinkle worth knowing up front: this demo runs **offline**, with
no Clojure server in the loop. Rather than fetch from three real
services, `:rf/server-init` seeds all three card values synchronously,
and the page boots from a hand-written `index.html` that already carries
the resolved chunks and the final payload pre-baked. So you're reading
the *wire shape* streaming produces, frozen and replayable, without
needing a live streaming host. It's the worked companion to
[Spec 011 §Streaming SSR](../../../spec/011-SSR.md#streaming-ssr).

## What this demonstrates

- **`:rf/suspense-boundary` — the whole streaming API is one marker.**
  Wrap a subtree, name a `:fallback`, give it a stable `:id`. The
  streaming walker emits the fallback [hiccup](../../../docs/guide/glossary.md#hiccup)
  into the shell and flushes it on the first byte, then renders the real
  subtree and streams it in as its own chunk. No streaming-render mode,
  no per-host API — the marker *is* the contract.

- **Each chunk carries its own slice of state.** A streamed-in card is
  no good if the [subscriptions](../../../docs/guide/glossary.md#subscription)
  inside it can't see the data. So each resolved chunk ships a
  `<script data-rf2-suspense-hydrate>` carrying that card's
  [app-db](../../../docs/guide/glossary.md#app-db) delta, and the
  **client-side streaming runtime** (`ssr/streaming-install!`, wired up
  in `run`) swaps the fallback for the resolved content *and* merges the
  delta into app-db — progressively, chunk by chunk, before the final
  payload ever arrives.

- **A failing region can't take down the page.** The fourth card
  ([`throwing-card`](core.cljc)) throws on purpose — standing in for a
  flaky third-party metric service. The streaming runtime catches the
  throw inside that one boundary, ships its fallback HTML in the
  resolved-chunk slot (marked `data-rf2-suspense-failed`) with no
  hydrate delta, and emits a `:rf.ssr/suspense-boundary-failed`
  [trace](../../../docs/guide/glossary.md#trace-event). The other three
  cards stream on as if nothing happened. The blast radius of a thrown
  render is exactly one boundary — never the whole 500'd page.

- **The deltas are a speed bet; the final payload is the truth.** This
  is the load-bearing idea, so it's worth saying plainly. Those
  per-card deltas are *speculative* — they exist only to paint each
  region early. The final `<script id="__rf_payload">` arrives last
  carrying the canonical, whole-app-db state, and `ssr/hydrate!`
  dispatches `:rf/hydrate` against it with
  [replace-frame-state](../../../docs/ssr/glossary.md#hydration) semantics
  (the server's `:rf/app-db` — and any serialisable runtime-db slice —
  replaces the client's whole frame-state in one step).
  If a speculative delta and the canonical payload ever disagree, the
  payload wins, every time. You get the *latency* of streaming with the
  *correctness* of a single authoritative hydrate — and a client that
  missed the inline chunks entirely (JS disabled mid-stream, say) still
  lands on a coherent app-db from the final payload alone.

## Why this shape

Notice what *isn't* here: no second, server-flavoured copy of the app.
Streaming SSR falls straight out of the per-request
[frame](../../../docs/guide/glossary.md#frame) model — the server runs
your real views against an isolated frame for the request, and
`:rf/suspense-boundary` is just the marker that says "this region of the
view tree is allowed to arrive late." One subtlety the source is careful
about (and worth a glance if you read `core.cljc`): there are *two*
frames in play, and they don't share an id. The server renders under a
fresh per-request frame (a gensym, in `handle-request`); the client
hydrates a fixed app-frame (`:rf/default`, in `run`). The same
[schema](../../../docs/guide/glossary.md#schema) contract is registered
explicitly against *each* with a `{:frame …}` override, because that's
where the `:cards` commit actually validates on either side — and the
server drops its per-request frame-id from the payload, so the client's
explicit `:frame` stands unchallenged rather than tripping a
frame-id-mismatch.

The `.cljc` shape mirrors [`examples/reagent/ssr/core.cljc`](../ssr/core.cljc):
the server branch lives in `:clj`, the browser branch in `:cljs`. The
`:clj` branch is what a Ring streaming adapter would invoke per request;
the `:cljs` branch is what the page bootstraps once the chunks land. One
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

If you're new to SSR here, read the non-streaming
[`examples/reagent/ssr/`](../ssr/) counterpart first; the normative
section is [`spec/011-SSR.md` §Streaming SSR](../../../spec/011-SSR.md#streaming-ssr).
