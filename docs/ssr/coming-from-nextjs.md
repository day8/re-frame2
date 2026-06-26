# Coming from Next.js

If you've shipped a Next.js app, you already hold the right mental shapes: render real HTML on the server, hydrate it on the client, fetch data before the page paints, stream the slow parts in later. The vocabulary changes; the goals don't.

What transfers is the *thinking*. What doesn't is the *machinery* — and that's mostly good news. Next.js gives you a server runtime to learn: a router that's also a data layer, Server Components that run in one place and Client Components that run in another, a `"use server"` / `"use client"` boundary you have to reason about at every import. re-frame2 has none of that, for a reason worth stating up front: **there is no separate server layer.** The same [event handlers](../guide/glossary.md#event-handler), [subscriptions](../guide/glossary.md#subscription), and [views](../guide/glossary.md#view) you wrote for the browser run unchanged on the JVM, against a per-request [frame](../guide/glossary.md#frame). One app, [run twice](concepts.md).

So read this page as a translation table, not a feature comparison. The capabilities line up almost one-to-one. The divergences — covered last, and they're the part actually worth your attention — are deliberate, and each one trades a Next.js convenience for a property re-frame2 cared about more.

## The mapping

| Next.js | re-frame2 |
|---|---|
| Server Component (runs on server) | Any [event handler](../guide/glossary.md#event-handler), [sub](../guide/glossary.md#subscription), or [view](../guide/glossary.md#view) — they're pure, so they run on either side. No separate component kind. |
| Client Component (`"use client"`) | The same view, after [hydration](glossary.md#hydration). There's no second flavour; browser-only *effects* are gated with `:platforms #{:client}`. |
| `"use server"` / `"use client"` directives | [`:platforms`](concepts.md#platforms--one-handler-gated-per-runtime) on an effect or coeffect — declared once on the *capability*, not at every import site. |
| `getServerSideProps` / a route loader | Your ordinary events firing in the per-request frame's `:initial-events`. The "loader" is just dispatch + [drain](../guide/glossary.md#drain--run-to-completion). |
| `Promise.all` of N fetches in a loader | [Pattern-SSR-Loaders](../../spec/Pattern-SSR-Loaders.md) — a [machine](../machines/glossary.md#machine) fans fetches out with `:spawn-all`, joins on complete. Same site drives client-nav fetch. |
| A route's `loader` (App Router) | A re-frame2 route [loader](../routing/glossary.md#loader), which compiles to those `:initial-events` server-side. |
| Server Action (form `action={fn}`) | [Pattern-FormAction](../../spec/Pattern-FormAction.md) — a real `method="POST"` form routes to the *same* [event](../guide/glossary.md#event) the client's `:on-submit` dispatches. |
| `hydrateRoot` (React 18) | [`ssr/hydrate!`](glossary.md#hydration) — but the server's *state* rides along explicitly in the payload, installed by `:rf/hydrate` before the first render. |
| Reading `cookies()` / `headers()` | A declared [coeffect](../guide/glossary.md#coeffect): `:rf.cofx/requires [:rf.server/request]`, value flat in the handler's coeffects. |
| `redirect()` / `notFound()` | The data effects `:rf.server/redirect` / a routing miss your [error projector](concepts.md#when-the-server-throws) maps to `404`. |
| `cookies().set(...)` | The `:rf.server/set-cookie` effect — a structured map, [adapter](../guide/glossary.md#adapter) does the wire encoding. |
| The `Metadata` API / `generateMetadata` | [`reg-head`](concepts.md#head-metadata----opengraph-json-ld) — a head model *derived from app-db*, shaped exactly like a sub. |
| `<Suspense fallback>` + `loading.js` (streaming) | [`:rf/suspense-boundary`](concepts.md#streaming-rfsuspense-boundary) — one hiccup marker with a `:fallback`, streams its subtree in as a chunk. |
| Hydration mismatch (console warning, content flash) | A [hydration mismatch](glossary.md#hydration-mismatch) located by a structural hash — it tells you the `:first-diff-path`, not just that it happened. |
| `unstable_cache` / `fetch` cache | A [resource](../resources/glossary.md#resource) — preloaded server-side, ridden across in the payload, renders without a duplicate fetch. |
| `next/server` runtime, route handlers, middleware | The Ring host adapter (`day8/re-frame2-ssr-ring`). One handler constructor; the lifecycle is yours to read, not a framework you configure. |

The shape is reassuring: nearly everything you do in Next.js has a direct counterpart. But a one-to-one table hides the interesting bit — *why* some of these look different. That's next.

## Where it diverges

These aren't gaps. They're places where re-frame2 looked at the Next.js answer and chose differently, each time buying a specific property. Knowing the *why* is what lets you stop fighting the framework's grain.

### There is no server/client boundary to police

This is the big one, so it goes first. Next.js's central concept — the line between Server and Client Components, drawn with `"use client"` — exists because a React component is *entangled with its runtime*. It might touch `window`; it might `await` a database. So the framework makes you declare, per module, which world a component belongs to, and then polices what can cross: you can't import a Server Component into a Client one, you serialize props across the seam, you learn the rules of what's allowed where.

re-frame2 doesn't have that boundary because it doesn't have that entanglement. [Event handlers](../guide/glossary.md#event-handler) are pure — `(coeffects, event) → effect map`, no `window`, no `await`. [Subscriptions](../guide/glossary.md#subscription) are pure derivations. [Hiccup](../guide/glossary.md#hiccup) is just data. The "does this run on the server?" question that Next.js answers at *every* component is, here, answered *once*, structurally: yes, all of it, because none of it can reach the runtime directly. The only thing that genuinely differs between server and client is impure work — a `localStorage` write, a focus trap — and that lives in [effects](../guide/glossary.md#effect), which you tag with [`:platforms`](concepts.md#platforms--one-handler-gated-per-runtime). One tag on the effect, not a directive on every file that imports it. The `typeof window === 'undefined'` guard, and the whole mental tax of "which side is this code on," simply isn't a thing you carry.

### A loader is not a special function — it's just your app running

Next.js gives data-fetching its own ceremony: `getServerSideProps`, or an App Router `loader`, a function with a privileged signature that runs only on the server and hands props down. It's a distinct concept you learn, with its own caching rules and its own relationship to the component tree.

In re-frame2 the [loader](../routing/glossary.md#loader) is not a special function. The per-request [frame](../guide/glossary.md#frame) fires its `:initial-events`, the runtime [drains](../guide/glossary.md#drain--run-to-completion) — runs every event those events trigger, to a fixed point — and *then* renders. The "loader" is your ordinary [events](../guide/glossary.md#event) and [effects](../guide/glossary.md#effect), the same ones the client dispatches on navigation. The payoff is that there's no second code path to keep in sync: server fetch and client-nav fetch are the *identical* machine (see [Pattern-SSR-Loaders](../../spec/Pattern-SSR-Loaders.md)), only the spawn site moves. You don't maintain a server-flavoured fetch and a client-flavoured one and pray they agree.

### Hydration is verified, not hoped-for

React's `hydrateRoot` walks the server's DOM, reattaches listeners, and *trusts* your components to re-render the same tree. When they don't — a date in two timezones, an unordered map serialized two ways — you get a console warning most teams have learned to scroll past, and a content flash. The mismatch is real but unlocated; finding it is an afternoon.

re-frame2 refuses the shrug. The server embeds a structural hash of its render-tree; the client hashes its own first render and compares. A [hydration mismatch](glossary.md#hydration-mismatch) fires a structured [trace event](../guide/glossary.md#trace-event) carrying the `:first-diff-path` — *where* it diverged, down to the key. Default recovery is warn-and-replace so the user never sees a broken page; a strict mode escalates to a thrown exception for CI. The deeper reason this works at all is that the server's *state* is explicit: `hydrate!` doesn't just adopt DOM, it installs the server's [app-db](../guide/glossary.md#app-db) (and the serializable [runtime-db](../guide/glossary.md#runtime-db) slice) from the payload *before* the first render. You never re-fetch on the client to "catch up" — the state the server computed is already there, which is also why the two sides can be expected to agree in the first place.

### What crosses the wire is an allowlist that fails closed

Next.js serializes whatever props your loader returns. The discipline of not shipping secrets is *yours* — return the wrong field and it lands in the client bundle, silently, on the first request.

re-frame2 makes the wire a declared boundary. [`:payload`](concepts.md#payload--the-fail-closed-allowlist) is an allowlist of app-db keys, and it [fails closed](../guide/glossary.md#fail-loud-not-silent): everything not named stays server-side, *including keys you write next year*. Add a `:secrets/api-token` and forget the allowlist, and the worst case is it doesn't reach the client — the opposite of the Next.js failure mode. A denylist ("ship everything except…") was considered and rejected precisely because it leaks every new server-only key the moment you add one. And forgetting `:payload` entirely is a loud boot error, not a quiet default. At a security boundary, the framework would rather stop you cold than surprise you in production.

### Metadata and the response are data derived from state, not imperative calls

Next.js's `generateMetadata` is a function that *returns* a metadata object, and `cookies().set()` / `redirect()` are imperative calls you make. They work fine, but they sit slightly outside the data flow — side-effecting functions you invoke at the right moment.

re-frame2 folds both into the same data discipline everything else obeys. The [head model](concepts.md#head-metadata----opengraph-json-ld) is *derived from app-db*, a pure `(db, route) → head-model` with the exact shape of a [sub](../guide/glossary.md#subscription) — which is why an SPA route change after load keeps `<title>` and `<meta>` current with zero extra wiring, and why the head rides the *same* mismatch detector as the body. Response control is the same idea: status, headers, cookies, redirects are server-only [effects](../guide/glossary.md#effect) (`:rf.server/*`) returned in the [effect map](../guide/glossary.md#effect-map), so your response logic stays as pure and testable as the rest of your handlers. Cookies are structured maps, not hand-built header strings — the adapter does the RFC 6265 encoding, the place raw-string APIs grow quoting bugs. And header injection [fails loud](../guide/glossary.md#fail-loud-not-silent): a `\r\n` smuggled into a value throws rather than getting silently stripped, because silent normalization masks the attack.

### Streaming is one marker, not a component contract

React 18 streaming and Next.js's `loading.js` give you `<Suspense>` boundaries — a real component with a `fallback` prop, plus conventions about where `loading.js` lives in the route tree. It's powerful and it's a surface to learn.

re-frame2's [`:rf/suspense-boundary`](concepts.md#streaming-rfsuspense-boundary) is one declarative hiccup marker: an `:id`, a `:fallback`, a subtree. The walker flushes the shell with fallbacks in place (your first byte), then streams each region in as its data resolves, each chunk carrying the app-db delta its subscriptions need. Failure isolation is free — one boundary throwing keeps *that* region on its fallback and streams the rest. The one rule worth internalizing is the correctness lock: those streamed deltas are a *speed* optimization, and the final chunk is the canonical full payload — if they ever disagree, the payload wins. You get streaming's latency with a single authoritative `:rf/hydrate`'s correctness, rather than trusting that N independently-streamed regions composed into the right whole.

---

The honest summary: if you liked Next.js's *goals*, you'll be at home. If you spent real time wrestling its *boundary* — the Server/Client seam, the "why won't this import," the silent prop that leaked — that wrestling is the part that's gone. The full contract lives in [Spec 011 — SSR & Hydration](../../spec/011-SSR.md).
