# Coming from React Router

If you've used React Router's data APIs — `createBrowserRouter`, loaders,
`useNavigate`, `useLoaderData`, `useBlocker` — you already hold most of the ideas
re-frame2 routing is built on. React Router spent recent major versions migrating
*toward* this worldview: routes as data, data-fetching declared per route, the URL
as a first-class input. re-frame2 starts there and doesn't bolt anything on.

The translation is direct, with one structural difference that explains the smaller
ones: **React Router is a system beside your app** — its own context, component tree,
lifecycle. **re-frame2 routing isn't a separate system.** It's three things you
already have ([events](../core/glossary.md#event),
[subscriptions](../core/glossary.md#subscription),
[registrations](../core/glossary.md#registration)) pointed at the URL. No router
object, no `<RouterProvider>`, no context to thread. Once that lands, every row below
stops looking like a port and starts looking like a deletion.

For the full model from scratch, read [The model](concepts.md); this page assumes
you'd rather start from what you know.

## The mapping

| React Router | re-frame2 | Notes |
|---|---|---|
| `createBrowserRouter([...])` / `<Route>` config | [`reg-route`](concepts.md#move-1-a-route-is-a-registry-entry) entries | Each route is one row in a process-global table, registered like any other handler — not a node in a JSX tree. |
| Route object (`path`, `loader`, `errorElement`…) | The [route](glossary.md#route)'s metadata map | Same idea — behaviour declared as data — but a plain Clojure map, queryable from anywhere. |
| `:slug` path param, `useParams()` | `:id` in the path + `@(subscribe [:rf.route/params])` | [Route params](glossary.md#route-params) are a [subscription](../core/glossary.md#subscription), validated *and coerced* by a schema. |
| `useSearchParams()` | `@(subscribe [:rf.route/query])` | Separate map from path params — never merged. `?page=2` arrives as integer `2`. |
| `loader` function | [`:on-match`](concepts.md#loaders-declaring-a-pages-data) (events) / `:resources` (data) | The [loader](glossary.md#loader) as *data* (vector of event vectors, or resource decls), not a function you call. |
| `useLoaderData()` | An ordinary [subscription](../core/glossary.md#subscription) | The loader writes to [app-db](../core/glossary.md#app-db); the [view](../core/glossary.md#view) reads it like any other state. No special hook. |
| `useNavigate()` → `navigate("/x")` | `(dispatch [:rf.route/navigate {:to :route :params params}])` | [Navigation is an event](concepts.md#move-2-navigation-is-an-event) — traceable, interceptable, rewound by time-travel. |
| `<Link to>` / `<NavLink>` | `[route-link {:to :route}]` | Real `<a href>`, intercepts plain clicks, *defers* cmd/shift/middle-click to the browser. |
| `useNavigation().state` (`"loading"`) | `@(subscribe [:rf.route/transition])` | Global `:idle`/`:loading`/`:error` you read anywhere — never threaded through a component. |
| `errorElement` / `useRouteError()` | `:on-error` + `@(subscribe [:rf.route/error])` | Structured [error record](../core/glossary.md#error-record) in state, plus an optional event to respond. |
| `useBlocker()` / `usePrompt()` | `:can-leave` guard + `@(rf/subscribe [:rf/pending-navigation])` | [Route guard](glossary.md#route-guard) sub (boolean) and a *pending navigation you render from* — confirm dialog is an ordinary view. |
| Splat route `path="*"`, no-match route | Reserved [`:rf.route/not-found`](glossary.md#not-found) route | Ordinary route you register and design; carries loaders, scroll, and a `:reason` discriminator. |
| `<Outlet/>` + nested route config | `:parent` + `@(subscribe [:rf.route/chain])` | Nesting is data; you walk the chain and compose layout shells yourself (no render-slot machinery — see [below](#theres-no---layouts-are-data-you-compose), and [The model → Nested layouts](concepts.md#nested-layouts)). |
| `<RouterProvider router>` / router context | nothing | The route lives in [runtime-db](../core/glossary.md#runtime-db); any [view](../core/glossary.md#view) reads it via subscription. No provider, no context. |
| Framework-mode (v7 / Remix) server loaders | The *same* `:on-match` / `:resources` | One loader runs on client **and** server. No second "server loader" to keep in sync. |

If a row reads as "the same idea, minus the apparatus," you're reading it right.

## Where it diverges

A handful of differences are deliberate — each deletes a category of bug or
ceremony rather than renaming it.

### There are no hooks, because there's no component-local anything

`useNavigate`, `useLoaderData`, `useSearchParams`, `useBlocker`, `useNavigation` —
each is a hook because in React the router's state is reachable *only* from inside a
component React Router is currently rendering. re-frame2 doesn't have that coupling.
The active route lives in [runtime-db](../core/glossary.md#runtime-db); it's read with
the same `subscribe` you use for everything else — from a [view](../core/glossary.md#view),
an [event handler](../core/glossary.md#event-handler) (via a coeffect), a test, or the
REPL. Nothing has to be "inside the router" to see the URL, because there's no inside.

A loading bar that needs `useNavigation().state` no longer has to live high enough
in the tree to be a router descendant — it's a one-line view over
`:rf.route/transition`. An auth guard doesn't need a wrapper around the protected
subtree; it's an [interceptor](../core/glossary.md#interceptor) that reads a tag off
the route table.

### Navigation is an event, so it shows up on the wire

`navigate("/cart")` is an imperative call into React Router's internals — hard to
log without wrapping, hard to replay, hard to see next to the click that caused it.
In re-frame2 a navigation is `(dispatch [:rf.route/navigate …])` — the *same verb*
as every other state change. (Yes: your back button is a `dispatch`. Popstate fires,
an event runs, the route slice updates. It was always state change; re-frame2 stopped
pretending the browser was special.)

Because it travels the same wire as business events, a navigation appears in
[Xray](../core/glossary.md#xray) inline with the click that triggered it, and
[time-travel](../core/glossary.md#time-travel) rewinds it for free — the URL rewinds
*with* the [frame](../core/glossary.md#frame), because the URL was never the source of
truth, only a projection of it. React Router treats the URL as truth and your data as
a reaction; re-frame2 treats your state as truth and the URL as a print-out. Hence
"the URL is a sub."

### The loader is data, not a function

React Router's `loader` is a function — to know what a route fetches, you read its
body or run it. re-frame2's [loader](glossary.md#loader) is `:on-match` (a vector of
[event](../core/glossary.md#event) vectors) or `:resources` (a list of declarations).
Being *data* means you can read it, test it, and draw a route's data-dependency graph
**without executing it** — `(rf/handler-meta :route :app/cart)` hands you the list.

And `:resources` closes the click-away race: on route entry each resource is owned by
*this* navigation's nav-token; when a newer navigation supersedes it, a late reply is
*suppressed* rather than written. Classic bug — navigate away, old fetch resolves a
beat too late, clobbers the page you're on — fixed once, not in every loader. React
Router's loaders are abortable, which helps, but abort isn't guaranteed to win the
race; suppression is the correctness boundary, abort is the bandwidth optimisation on
top.

### The leave-guard is a subscription and the prompt is a view

React Router's `useBlocker` hands you an imperative `blocker` object with a state
machine you drive by hand; historically the "unsaved changes?" prompt bottomed out
in `window.confirm` or `beforeunload`. re-frame2 splits the job:
[`:can-leave`](glossary.md#route-guard) is a boolean *subscription* (`true` allows,
`false` blocks), and the blocked navigation parks in `:rf/pending-navigation` — *state
you render from*. Confirm dialog is an ordinary view that reads
`@(rf/subscribe [:rf/pending-navigation])`, with two buttons that `dispatch`
`:rf.route/continue` or `:rf.route/cancel`. No imperative blocker, no native dialog,
no modal-automation flakiness in tests — the whole flow asserts with zero DOM.

### One loader runs on both client and server

React Router's framework mode (v7, formerly Remix) gives you server loaders — same
instinct. The difference is structural: there, the server story is a *mode* with its
own build and runtime. Here there is no second router to be a server version *of*.
The request URL is fed to the same pure `match-url`, against a per-request frame; the
same `:on-match` and `:resources` run; state ships to the client and hydrates
**without re-fetching**. SSR isn't a parallel implementation kept in sync — it's the
*same* implementation pointed at a different event source. (See
[Routing on the server](concepts.md#the-same-handler-runs-on-the-server).)

### There's no `<Outlet/>` — layouts are data you compose

Here's the one place re-frame2 asks *more* of you, and it's honest to say so. React
Router's `<Outlet/>` is a genuinely nice ergonomic: declare nested routes and the
parent layout renders its active child into a slot automatically. re-frame2 has no
render-slot machinery. Nesting is still data — a route declares a `:parent`, and
`@(subscribe [:rf.route/chain])` gives you the parent chain — but you walk that chain
and compose the layout shells yourself in the root view. The trade is deliberate:
composition stays in plain Clojure (the same `case`/`cond` you'd write for any
conditional view) rather than a routing-specific rendering primitive. Whether that's
a feature or a chore depends on how much you liked `<Outlet/>`. Pre-alpha; this edge
is on the list. ([The model → Nested layouts](concepts.md#nested-layouts) has the
worked `reduce`-over-the-chain code; the [tutorial](tutorial.md#step-7--a-shared-layout)
builds it step by step.)

### Smaller, on-purpose differences

- **Plain `[:a {:href}]` anchors are *not* intercepted** — they do a native full-page
  navigation. Site-wide anchor interception is a host-adapter concern, not framework
  magic: opt in per link with `route-link` (or install your own document-level
  handler). React Router intercepts via `<Link>`; re-frame2 refuses to do it silently
  behind your back.
- **404 is a route you must register.** No-match doesn't fall to a built-in default
  you'd ship to users — the reserved [`:rf.route/not-found`](glossary.md#not-found)
  is yours to design, and its `:params` carry a `:reason` so you can tell a plain miss
  from a schema failure from a malformed URL.
- **Schema failures fail in opposite directions by entry point.** A bad URL from the
  world (deep link, back-button) is *user input* → 404, never an exception. A bad
  `route-url`/`navigate` call is *your code* → it throws/rejects. Same schemas,
  opposite failure modes: the world 404s, your bugs are loud.
- **Routes are queryable data.** Auth guards, breadcrumb generators, sitemap builders,
  and analytics `filter` and `map` over the table — the inverse of reading a route by
  being rendered inside its `<Route>`.

Internalise one thing: in React Router the router is a *thing you're inside of*, and
the hooks are how you ask it questions. In re-frame2 the route is just *state*, and
you read state the one way you always do. Every divergence above is a consequence of
that single move.
