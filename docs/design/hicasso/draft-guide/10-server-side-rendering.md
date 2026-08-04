# Server-side rendering

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

You want HTML on the first response, then the same app to take over in the
browser — without writing the UI twice and without a second renderer that drifts.
re-frame2 already treats that as a core goal: pure views, data events, a frame
cheap enough to build for one request and tear down when the response is written.
Hicasso is meant to be the native view layer in that story, so it has to render
on the server, adopt in the browser, and stay honest about both.

> **One renderer, run in two places, judged by the framework's hydration
> machinery.**

## What SSR means here

The runtime that paints in the browser is the runtime that paints on the server.
There is no separate HTML emitter hoping to match. The framework's existing
hydration path carries the result across: a payload of app state goes into the
page, the client seeds from it before its first render, and React's `hydrateRoot`
keeps the server's nodes and attaches listeners — reporting any divergence it
had to recover from on the way.

Hicasso does not invent a parallel mechanism. It participates in the framework
path — payload, `:rf/hydrate`, mismatch check, `ssr-ring` as the HTTP host — and
only has to hold up its own end: render a tree on the server, and adopt that tree
in the browser. The one place the tier matters is *which* mismatch check it gets;
[The framework story today](#the-framework-story-today) is precise about it.

## The framework story today

One app, written once, run twice. The runnable reference is
`examples/capabilities/ssr` — one `.cljc` shared between JVM and browser,
exercised headlessly on every PR. The published [SSR guide](../../../ssr/index.md)
teaches the full corpus.

**Server, per request:** create a fresh frame for that request; run
`:initial-events` (client-only effects with `:platforms #{:client}` are skipped);
render the settled snapshot to HTML; embed the serialised state in a
framework-owned payload (`__rf_payload`); destroy the frame in a `finally`.

**Client, once:** `hydrate!` reads the payload and dispatches `:rf/hydrate`
**before** the first render, so the server's state *replaces* the client's. Then
React adopts the DOM, and adoption is what checks the two halves agree.

**Which check you get depends on the substrate, and Hicasso gets React's.**
Spec 011 tiers mismatch detection by render-tree representation. A
*hiccup*-tier host — Reagent, Reagent-slim — has views that are pure functions
returning a data tree, so the server can hash its tree, the client can re-hash
its first render, and `verify-hydration!` compares the two structural hashes.
Hicasso is not in that tier: its views reach React as elements and the tree is
walked *inside* React, so there is no data tree to hash and **no
`:rf/render-hash` rides the wire**. Verification is React's own adoption
instead — React diffs the client's first render against the server DOM and
reports what it recovers from through `onRecoverableError`, which the framework
surfaces as the same `:rf.ssr/hydration-mismatch` diagnostic.

That is a real check with a documented edge: React reports a text-content
mismatch or a missing, extra or wrong-type element, and it does **not** report
an attribute-only divergence (a stale `class` or `style` on an element whose tag
and text still match) — React makes no guarantee to patch those, so it takes a
development-only warning path and calls no production callback. React's recovery
is a replacement, not a patch, so the page ends up correct and the disagreement
ends up on the diagnostic bus.

None of that is Hicasso-specific except the tier it lands in. Everything below is
what the view layer adds on top.

## What Hicasso adds

| Piece | Status |
|---|---|
| `hydrate-root!` **[unfrozen]** — adopt server DOM into a live root (same contract shape as `root!`) | Works under the freehand bench |
| `defhost` `:ssr` — what a foreign region does server-side | Works |
| Node render entry — existing runtime under `renderToString`, fixture bake, live demo | Works under the freehand bench |
| End-to-end measurements on a hydrated screen | Have run; not a ship decision |
| How production hosts will look | Not decided |

The server engine is the client runtime under `react-dom/server`'s
`renderToString`, so parity holds by construction. Under a server render every
`sub` read is a cold probe: no durable registration, no effects, no commit. The
per-request entry seeds app-db through the framework, renders, builds the
framework payload, and destroys the frame — see `ssr/entry.cljs` under the
freehand bench. That entry is **not** a production host; Spec 011's HTTP contract
stays with `ssr-ring`.

`hydrate-root!` **[unfrozen]** stands beside `root!`
([Getting started](01-getting-started.md)): same association of a DOM node that
already holds server HTML, a frame that has already adopted the server's state,
and one view. Names and arities are not frozen; treat the shape as the teaching
contract:

```clojure
;; After framework hydrate! has seeded app-db from #__rf_payload:
(defonce stop!                                    ;; hydrate-root! is [unfrozen]
  (h/hydrate-root! (js/document.getElementById "app")
                   {:frame :rf/default}
                   [views/todo-app {}]))
```

Properties that matter when you write apps:

- It calls React's `hydrateRoot` without `flushSync`, so it returns before
  adoption finishes. Completion is observable; do not assume the DOM on the next
  line is already client-owned.
- Presence children are **born present** — nothing that arrived with the page
  replays an entrance over content the user is already reading.
- Hydration never rewrites controlled text after the fact. The model's value is
  the one truth from the first paint.

## Instance state and the payload allowlist

`h/reg-state` and other UI instance state live under `[:ui …]` in app-db
([Ephemeral state](07-ephemeral-state.md)). The framework's hydration payload is
**fail-closed**: if server-side events write render-affecting values under
`:ui`, the payload policy must allowlist `:ui` (or the whole app-db). Otherwise
the client hydrates without those keys, paints from different state than the
server HTML, and you get `:rf.ssr/hydration-mismatch`.

## Write the app so SSR is free

Everything above is the runtime's problem. Whether SSR is cheap *for your app* is
decided in how you write views and events — and every rule below is usable now.

**Keep view bodies portable.** A tier-1 `defview` body is hiccup, reads, and
ordinary Clojure. No JS interop in the forms. JS lives at declared edges:
`defhost` and host-edge namespaces ([Interop](05-interop.md)). Keep it there and
view namespaces load anywhere — the precondition for every server story.

**Put events as data on the tree.** `[:todo/toggle id]` on `:on-click` has no
closure to ship. The runtime builds the callback from the vector on whichever
side is rendering. App state crosses the wire as EDN because it *is* data. There
is no "serialise my functions" problem.

**A body is a function of its props and reads.** Same snapshot in, same tree out
— that is what "render from a db snapshot" means, and what adoption demands. A
clock, a `js/window` sniff, or a random in a body paints one thing on the server
and another in the browser; React reports that as a hydration mismatch. Platform
work belongs in effects (`:platforms #{:client}`) and at host edges, never in
tier-1 bodies — the same purity [Views and reads](02-views-and-reads.md) already
requires.

**Controlled inputs are already SSR-shaped.** The value comes from the model
([Controlled inputs](04-controlled-inputs.md)); the server renders that value;
hydration does not converge the text afterward. Nothing extra to write.

**Do not gate entrances on "I just mounted."** Drive enter with insertion
animation or `@starting-style` — [Ephemeral state](07-ephemeral-state.md)'s rule
for enter. The entrance is a CSS fact of the markup; adoption reuses the server's
nodes, so it gets no second trigger. Presence handles the other half: a
presence-managed node hydrates born-present, so nothing that arrived with the
page replays an entrance.

**Declare each foreign region's server story at `defhost`:**

```clojure
(h/defhost chart Chart)                                 ;; :client-only — the default
(h/defhost chart Chart {:ssr :client-only})             ;; the same, said out loud
(h/defhost chart Chart {:ssr {:fallback [:div.skel]}})  ;; that markup until adoption
```

`:client-only` renders nothing in that slot until the client has adopted;
`{:fallback <hiccup>}` puts a skeleton there instead. Unknown policies fail at
declaration (`:rf.error/hicasso-host-bad-ssr-policy`); unknown options fail too
(`:rf.error/hicasso-host-unknown-option`). One declaration covers server render,
the first client pass after hydration, and a fresh client-only mount (which mounts
the foreign component immediately, with no placeholder flash).

Do all of that — write idiomatic Hicasso — and SSR is not a rewrite waiting to
happen. The app that is easiest to write is also the app that serves.

## Non-goals

Out of scope for this story: **streaming** (`renderToPipeableStream`), **React
Server Components**, **islands / partial hydration**, **no-JS progressive
enhancement**, and **SEO metadata management**. SSR *speed* is not a bar either —
fast applications, not fast SSR. The story is one page, rendered whole from a
snapshot, adopted whole by the client.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| React reports a hydration mismatch during adoption | The client's first render disagreed with the server HTML — a body read the platform (clock, `js/window`, random) instead of props and reads | Keep bodies pure; platform work goes to effects (`:platforms #{:client}`) and host edges |
| `:rf.ssr/hydration-mismatch` from the framework, with a `:where` and an `:error` | The same React adoption divergence, surfaced onto the diagnostic bus. It carries no hashes — this tier has none | Read the `:error`; the cause is the row above. Hydrate before anything renders, so the first client render is against the server's state |
| A divergence React never reported at all | It was **attribute-only** — a stale `class`, `style` or ARIA value under a matching tag and text. React makes no guarantee to patch those and calls no production callback | Not traceable on this tier by construction. Find it the way you would any other stale value: the attribute came from state the two sides disagreed on |
| A foreign component throws `window is not defined` on the server | Its render reached for the browser, and a bare component name says nothing about that | Declare `:ssr :client-only` (default) or `{:fallback …}` on `defhost` |
| A controlled input's text jumps just after adoption | Not this design: hydration does not converge controlled text | If you see it under another stack, the server markup and the model disagreed — fix the shared state |
| Frames accumulate on a long-running server | A request path skipped teardown | Destroy the per-request frame in a `finally`, throw path included — copy the reference example |

## When you need production SSR today

Use a first-class adapter. Reagent and UIx [adapters](../../../core/views.md) are
supported, maintained, and carry Spec 011 end to end. `examples/capabilities/ssr`
is that path, runnable now. Adapters remaining a production answer is a successful
outcome, not a consolation.

Hicasso's hydration path, `:ssr` host policy, Node render entry, and end-to-end
measurements work under the freehand bench; they are not yet a product package
under `implementation/hicasso/`. What this chapter still gives you, on any
substrate, is the authored discipline above — pure bodies, events as data,
platform work in effects — which is portable re-frame2 and costs nothing to adopt
early.

## Not settled yet

| Question | Status |
|---|---|
| Production host shape | Open. JVM structural walk (in-process with `ssr-ring`) vs a Node sidecar behind an EDN render contract. `ssr-ring` keeps Spec 011's HTTP contract either way. Do not design a deployment against either yet. |
| How boot composes once there is a product package | Open. Today the freehand path associates a container, a frame, and a view; framework `hydrate!` seeds and verifies state before first render. Whether the product offers one operation or two — and where `:initial-events` sits on a hydrating load — is unstated. |
| Spellings | `hydrate-root!` and every other name on this page are **[unfrozen]** until the API freeze. |
