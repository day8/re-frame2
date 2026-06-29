# Coming from React Router

If you've used React Router's data APIs — `createBrowserRouter`, loaders, `useNavigate`, `useLoaderData`, `useBlocker` — you already hold most of the ideas re-frame2 routing is built on. React Router spent its last few major versions migrating *toward* this worldview: routes as data, data-fetching declared per route, the URL treated as a first-class input rather than a thing you read out of `window.location` by hand. re-frame2 starts there and doesn't bolt anything on.

So this page is mostly good news. The translation is honest and direct, with one structural difference that explains all the smaller ones: **React Router is a system that lives beside your app — its own context, its own component tree, its own lifecycle. re-frame2 routing isn't a system at all.** It's three things you already have ([events](../core/glossary.md#event), [subscriptions](../core/glossary.md#subscription), [registrations](../core/glossary.md#registration)) pointed at the URL. There's no router object, no `<RouterProvider>`, no context to thread. Once that lands, every row in the table below stops looking like a port and starts looking like a deletion.

For the full model from scratch, read [Routing concepts](concepts.md); this page assumes you'd rather start from what you know.

## The mapping

| React Router | re-frame2 | Notes |
|---|---|---|
| `createBrowserRouter([...])` / `<Route>` config | [`reg-route`](concepts.md#move-1-a-route-is-a-registry-entry) entries | Each route is one row in a process-global table, registered like any other handler — not a node in a JSX tree. |
| Route object (`path`, `loader`, `errorElement`…) | The [route](glossary.md#route)'s metadata map | Same idea — a route's behaviour declared as data — but a plain Clojure map, queryable from anywhere. |
| `:slug` path param, `useParams()` | `:id` in the path + `@(subscribe [:rf.route/params])` | [Route params](glossary.md#route-params) are a [subscription](../core/glossary.md#subscription) read, validated *and coerced* by a schema. |
| `useSearchParams()` | `@(subscribe [:rf.route/query])` | A separate map from path params — never merged into one bag. `?page=2` arrives as the integer `2`. |
| `loader` function | [`:on-match`](concepts.md#loaders-declaring-a-pages-data) (events) / `:resources` (data) | The [loader](glossary.md#loader) — but as *data* (a vector of event vectors, or a list of resource decls), not a function you call. |
| `useLoaderData()` | An ordinary [subscription](../core/glossary.md#subscription) | The loader writes to [app-db](../core/glossary.md#app-db); your [view](../core/glossary.md#view) reads it like any other state. No special hook. |
| `useNavigate()` → `navigate("/x")` | `(dispatch [:rf.route/navigate :route params])` | [Navigation is an event](concepts.md#move-2-navigation-is-an-event) — traceable, interceptable, and rewound by time-travel. |
| `<Link to>` / `<NavLink>` | `[route-link {:to :route}]` | Renders a real `<a href>`, intercepts plain clicks, *defers* cmd/shift/middle-click to the browser. |
| `useNavigation().state` (`"loading"`) | `@(subscribe [:rf.route/transition])` | A global `:idle`/`:loading`/`:error` you read anywhere — never threaded through a component. |
| `errorElement` / `useRouteError()` | `:on-error` + `@(subscribe [:rf.route/error])` | A structured [error record](../core/glossary.md#error-record) in state, plus an optional event to respond. |
| `useBlocker()` / `usePrompt()` | `:can-leave` guard + `@(subscribe [:rf/pending-navigation])` | A [route guard](glossary.md#route-guard) sub (boolean) and a *pending navigation you render from* — your confirm dialog is an ordinary view. |
| Splat route `path="*"`, no-match route | The reserved [`:rf.route/not-found`](glossary.md#not-found) route | An ordinary route you register and design; it carries its own loaders, scroll, and a `:reason` discriminator. |
| `<Outlet/>` + nested route config | `:parent` + `@(subscribe [:rf.route/chain])` | Nesting is data; you walk the chain and compose layout shells yourself (no render-slot machinery — see [below](#theres-no---layouts-are-data-you-compose), and [Concepts → Nested layouts](concepts.md#nested-layouts) for the worked code). |
| `<RouterProvider router>` / router context | nothing | The route lives in [runtime-db](../core/glossary.md#runtime-db); any [view](../core/glossary.md#view) reads it via subscription. No provider, no context. |
| Framework-mode (v7 / Remix) server loaders | The *same* `:on-match` / `:resources` | One loader runs on client **and** server. There's no second "server loader" to keep in sync. |

If a row reads as "the same idea, minus the apparatus," you're reading it right. That's the whole point.

## Where it diverges

The table makes the two look like dialects of one language. They mostly are — but a handful of differences are deliberate, and they're the part worth your attention, because each one is re-frame2 deleting a category of bug or a category of ceremony rather than renaming it.

### There are no hooks, because there's no component-local anything

`useNavigate`, `useLoaderData`, `useSearchParams`, `useBlocker`, `useNavigation` — every one of these is a hook because in React the router's state is reachable *only* from inside the render of a component that React Router is currently rendering. That coupling is the thing re-frame2 doesn't have. The active route lives in [runtime-db](../core/glossary.md#runtime-db); it's read with the same `subscribe` you use for everything else, from a [view](../core/glossary.md#view), an [event handler](../core/glossary.md#event-handler) (via a coeffect), a test, or the REPL. Nothing has to be "inside the router" to see the URL, because there's no inside.

The practical payoff is that the awkward cases stop being awkward. A loading bar that needs `useNavigation().state` no longer has to live high enough in the tree to be a router descendant — it's a one-line view over `:rf.route/transition`. An auth guard doesn't need a wrapper component rendered around the protected subtree; it's an [interceptor](../core/glossary.md#interceptor) that reads a tag off the route table. The router's reach was always a tax, and removing the router removes the tax.

### Navigation is an event, so it shows up on the wire

`navigate("/cart")` is an imperative call into React Router's internals. You can't log it without wrapping it, can't replay it, can't see it next to the click that caused it. In re-frame2 a navigation is `(dispatch [:rf.route/navigate …])` — the *same verb* as every other state change in the app. (Dry aside: yes, your back button is now technically a `dispatch`. The popstate fires, an event runs, the route slice updates. It was always state change; re-frame2 just stopped pretending the browser was a special case.)

Because it travels the same wire as your business events, a navigation appears in [Xray](../core/glossary.md#xray) inline with the click that triggered it, and [time-travel](../core/glossary.md#time-travel) rewinds it for free — the URL rewinds *with* the [frame](../core/glossary.md#frame), because the URL was never the source of truth, only a projection of it. That last clause is the deep version of the divergence: React Router treats the URL as truth and your data as a reaction to it; re-frame2 treats your state as truth and the URL as a print-out. Hence "the URL is a sub."

### The loader is data, not a function

This is the difference that looks cosmetic and isn't. React Router's `loader` is a function — to know what a route fetches, you read its body or run it. re-frame2's [loader](glossary.md#loader) is `:on-match` (a vector of [event](../core/glossary.md#event) vectors) or `:resources` (a list of declarations). Being *data* means you can read it, test it, and draw a route's data-dependency graph from it **without executing it** — `(rf/handler-meta :route :app/cart)` hands you the list. A function can't tell you what it does until it runs; a data structure can.

And `:resources` quietly fixes the click-away race for you. On route entry each resource is owned by *this* navigation's nav-token; when a newer navigation supersedes it, a late-landing reply is *suppressed* rather than written. That's the classic bug where you navigate away, an old fetch resolves a beat too late, and clobbers the page you're now looking at — fixed once in the substrate instead of in every loader you'll ever write. React Router's loaders are abortable, which helps, but abort isn't guaranteed to win the race; suppression is the correctness boundary, abort is the bandwidth optimisation on top.

### The leave-guard is a subscription and the prompt is a view

React Router's `useBlocker` hands you an imperative `blocker` object with a state machine you drive by hand, and historically the "unsaved changes?" prompt bottomed out in `window.confirm` or the `beforeunload` hack. re-frame2 splits the job along its natural seam: [`:can-leave`](glossary.md#route-guard) is a boolean *subscription* (declarative, derives from state — `true` allows, `false` blocks), and the blocked navigation parks in `:rf/pending-navigation`, which is *state you render from*. So your confirm dialog is an ordinary view with two buttons that `dispatch` `:rf.route/continue` or `:rf.route/cancel`. No imperative blocker to manage, no native dialog, no modal-automation flakiness in tests — the whole flow asserts with zero DOM.

### One loader runs on both client and server

React Router's framework mode (v7, formerly Remix) gives you server loaders — a real achievement, and the same instinct as re-frame2. The difference is structural: there, the server story is a *mode* the framework provides, with its own build and runtime. Here there is no second router to be a server version *of*. The request URL is fed to the same pure `match-url`, against a per-request frame; the same `:on-match` and `:resources` run; the state ships to the client and hydrates **without re-fetching**. SSR isn't a parallel implementation kept in sync with the client — it's the *same* implementation pointed at a different event source. The seam between server and client routing doesn't exist because there was never a second router to seam against. (See [Routing on the server](concepts.md#the-same-handler-runs-on-the-server).)

### There's no `<Outlet/>` — layouts are data you compose

Here's the one place re-frame2 asks *more* of you, and it's honest to say so. React Router's `<Outlet/>` is a genuinely nice ergonomic: declare nested routes and the parent layout renders its active child into a slot automatically. re-frame2 has no render-slot machinery. Nesting is still expressed as data — a route declares a `:parent`, and `@(subscribe [:rf.route/chain])` gives you the parent chain of the active route — but you walk that chain and compose the layout shells yourself in your root view. The trade is deliberate: it keeps composition in plain Clojure (the same `case`/`cond` you'd write for any conditional view) rather than introducing a routing-specific rendering primitive. Whether that's a feature or a chore depends on how much you liked `<Outlet/>`. Pre-alpha; this edge is on the list. ([Concepts → Nested layouts](concepts.md#nested-layouts) has the worked `reduce`-over-the-chain code; the [tutorial](tutorial.md#step-7--a-shared-layout) builds it up step by step.)

### A grab-bag of smaller, on-purpose differences

- **Plain `[:a {:href}]` anchors are *not* intercepted** — they do a native full-page navigation. Site-wide anchor interception is a host-adapter concern, not framework magic, so you opt in per link with `route-link` (or install your own document-level handler). React Router intercepts via `<Link>`; re-frame2 just refuses to do it silently behind your back.
- **404 is a route you must register.** No-match doesn't fall to a built-in default you'd ship to users — the reserved [`:rf.route/not-found`](glossary.md#not-found) is yours to design, and its `:params` carry a `:reason` so you can tell a plain miss from a schema failure from a malformed URL.
- **Schema failures fail in opposite directions by entry point.** A bad URL from the world (a deep link, a back-button) is *user input* → 404, never an exception. A bad `route-url`/`navigate` call is *your code* → it throws/rejects. Same schemas, opposite failure modes: the world 404s, your bugs are loud.
- **Routes are queryable data.** Because the route table is a plain table, auth guards, breadcrumb generators, sitemap builders, and analytics all just `filter` and `map` over it — the inverse of reading a route by being rendered inside its `<Route>`.

If you internalise one thing: in React Router the router is a *thing you're inside of*, and the hooks are how you ask it questions. In re-frame2 the route is just *state*, and you read state the one way you always do. Every divergence above is a consequence of that single move.
