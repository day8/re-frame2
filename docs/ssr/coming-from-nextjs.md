# Coming from Next.js

Next.js and re-frame2 SSR do the same jobs: render HTML on the server, hydrate it
on the client, load data before the page paints, and stream slow regions later.
The main structural difference is that re-frame2 has no separate server layer. The
[event handlers](../core/glossary.md#event-handler),
[subscriptions](../core/glossary.md#subscription) and
[views](../core/glossary.md#view) you write for the browser run unchanged on the
JVM, against a per-request [frame](../core/glossary.md#frame). There is no
Server/Client component split; code that must run on one side only is declared on
the effect with `:platforms`.

## The mapping

| Next.js | re-frame2 |
|---|---|
| Server Component | Any [event handler](../core/glossary.md#event-handler), [subscription](../core/glossary.md#subscription) or [view](../core/glossary.md#view). They are pure, so they run on either side; there is no separate component kind. |
| Client Component (`"use client"`) | The same view, after [hydration](glossary.md#hydration). Browser-only effects are declared with `:platforms #{:client}`. |
| `"use server"` / `"use client"` directives | [`:platforms`](concepts.md#platforms--one-handler-gated-per-runtime) on an effect or coeffect, declared once where the effect is registered. |
| `getServerSideProps` (Pages Router) | Your ordinary events, dispatched by the per-request frame's `:initial-events` and [drained](../core/glossary.md#drain--run-to-completion) before the render. The drain settles synchronous work only; data fetched over the network belongs in a route's blocking resource (next rows). |
| A page's data fetching (`fetch` in a Server Component) | A route [loader](../routing/glossary.md#loader): the `:resources` the route declares, loaded on route entry (on the server during the request, on the client on navigation). |
| `await`ing data in a Server Component before it renders | A route resource declared `:blocking? true`. The Ring handler waits for it, within a 5-second budget, before rendering. A fetch your `:initial-events` start themselves is not waited for. See [blocking resource](glossary.md#blocking-resource). |
| `Promise.all` of N fetches | [The SSR loader pattern](concepts.md#two-patterns-in-brief): several `:blocking? true` entries in the route's `:resources`. They load in parallel, so the wait is the slowest fetch, and the same declaration drives the fetch on client navigation. |
| Server Action (form `action={fn}`) | [The form-action pattern](concepts.md#two-patterns-in-brief): a real `method="POST"` form routes to the same [event](../core/glossary.md#event) the client's `:on-submit` dispatches. |
| `hydrateRoot` | [`ssr/hydrate!`](glossary.md#hydration), which installs the server's state from the payload by dispatching `:rf/hydrate` before the first render, then the adapter's `render!` with `{:hydrate? true}` to adopt the DOM. |
| Several `hydrateRoot` calls on one page (islands) | [`ssr/hydrate-page!`](glossary.md#several-roots-on-one-page): each root hydrates and mounts inside its own failure boundary, usually into one shared frame. |
| Reading `cookies()` / `headers()` | A declared [coeffect](../core/glossary.md#coeffect): `:rf.cofx/requires [:rf.server/request]`, with the value in the handler's coeffects. The value is the Ring request, so `:cookies` is there only when Ring's `wrap-cookies` runs in front. |
| `cookies().set(...)` | The `:rf.server/set-cookie` effect, which takes a map. See [Controlling the response](response.md). |
| `redirect()` / `notFound()` | The `:rf.server/redirect` effect; `[:rf.server/set-status 404]`, or an unmatched URL, which the default [error projector](concepts.md#when-the-server-throws) answers with `404`. |
| `error.js` / `global-error.js` | The [error projector](glossary.md#error-projector) maps the failure to a sanitised public error, and a 5xx renders `ssr-handler`'s [`:error-view`](glossary.md#error-view) from that alone. |
| The `Metadata` API / `generateMetadata` | [`reg-head`](head.md): a head model derived from app-db, a pure function of `(db, route)`. |
| The root `layout.js` / `_document.js` document | The [page shell](glossary.md#page-shell): `default-html-shell`, adjusted with `:head`, `:body-end`, `:script-src` and `:app-element-id`, or replaced with `:html-shell`. |
| `<Suspense fallback>` + `loading.js` (streaming) | [`ssr/boundary`](streaming.md): one component with an `:id` and a `:fallback`, whose subtree streams in as its own chunk. |
| Hydration mismatch (console warning, content flash) | A [hydration mismatch](glossary.md#hydration-mismatch) trace, from a structural hash comparison, plus a strict mode that throws in CI. That covers views that return hiccup; UIx and Fresco roots rely on React's own hydration check. |
| `unstable_cache` / `fetch` cache | A [resource](../resources/glossary.md#resource): loaded on the server, shipped in the payload, and rendered on the client without a second fetch. |
| `next/server` runtime, route handlers, middleware | The Ring adapter, `day8/re-frame2-ssr-ring`: `ssr-handler` returns a Ring handler, and `ssr-middleware` mounts it inside an existing Ring app. See [Ring handler](glossary.md#ring-handler). |
| Rendering in Node | The JVM renders by default. A native view layer such as Fresco renders its body on a Node sidecar through the [Node renderer](glossary.md#node-renderer), while the JVM keeps the request, the payload and the response. |
| `next build` / `NODE_ENV=production` | A production build: an `:advanced` client bundle, and the server JVM started with [`-Dre-frame.debug=false`](../core/how-to/configure-dev-and-prod.md#3-shipping-a-jvmssr-tier-one-system-property). |

## Differences to know

**One declaration loads data on both sides.** A route's `:resources` drive the
fetch on the server during the request and on the client during navigation, so
there is no server-only data function to keep in step with a client one. The
per-request frame runs its `:initial-events`, drains, waits for the route's
blocking resources, and then renders.

**The client receives the server's state as well as its HTML.** `hydrate!` installs
the server's [app-db](../core/glossary.md#app-db) and the serialisable
[runtime-db](../core/glossary.md#runtime-db) slice before the first render, so the
client does not fetch again to catch up. For views that return hiccup, the server
embeds a hash of the render tree and the client compares it with its own first
render. A mismatch emits a [trace event](../core/glossary.md#trace-event) carrying
both hashes; by default the client's render replaces the server's, and strict mode
throws instead.

**What reaches the client is an allowlist.** Next.js serialises whatever props your
loader returns. re-frame2's [`:payload`](concepts.md#payload--the-fail-closed-allowlist)
names the top-level app-db keys that may ship; every other key stays on the server,
including keys added later. There is no denylist form, and constructing a handler
without `:payload` throws at boot.

**The head is not updated on client navigation.** `generateMetadata` re-runs on an
App Router navigation and Next.js updates the live document head. re-frame2 ships
no DOM-head reconciler, so refreshing `<title>` and `<meta>` after a client-side
route change is the app's job, reading the same head model. The head's
`:rf/head-hash` is written but not compared on hydration; only the body's
`:rf/render-hash` is. [Head metadata](head.md) covers both.

**Response control is data.** Status, headers, cookies and redirects are
server-only `:rf.server/*` [effects](../core/glossary.md#effect) returned from
handlers. Cookies are maps, and a CR, LF or NUL in a header value, redirect
location or cookie attribute throws. See [Controlling the response](response.md).

**A streaming boundary is the same form on both sides.** `ssr/boundary` defers its
subtree on the server and renders it in the browser. A boundary that throws keeps
its fallback while the rest of the page streams, and the final chunk carries the
full payload, which wins over the per-region deltas. See [Streaming](streaming.md).
