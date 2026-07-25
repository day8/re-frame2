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
| `loader` function | [`:resources`](concepts.md#declaring-resources-instead) (data a page needs) / [`:on-match`](concepts.md#loaders-declaring-a-pages-data) (activation work) | The [loader](glossary.md#loader) as *data* — resource declarations, or a vector of event vectors — not a function you call. The two jobs are separate keys here; see [below](#one-loader-splits-into-two-honest-keys). |
| `useLoaderData()` | An ordinary [subscription](../core/glossary.md#subscription) | The loaded data lands in the resource cache or [app-db](../core/glossary.md#app-db); the [view](../core/glossary.md#view) reads it like any other state. No special hook. |
| `useNavigate()` → `navigate("/x")` | `(dispatch [:rf.route/navigate {:to :route :params params}])` | [Navigation is an event](concepts.md#move-2-navigation-is-an-event) — traceable, interceptable, rewound by time-travel. |
| `<Link to>` | `[route-link {:to :route}]` | Real `<a href>`, intercepts plain clicks, *defers* cmd/shift/middle-click to the browser. |
| `<NavLink>`'s `isActive` | Compare `:to` against `@(subscribe [:rf.route/id])` in your own view | `route-link` computes **no** active state. "Am I on this page?" is a comparison against a route sub, and it becomes a four-line wrapper that sets `:aria-current` and a class — [the idiom](concepts.md#highlighting-the-active-link). |
| `<Link prefetch="intent">` (Remix / framework mode) | `[route-link {:to :route :prefetch :intent}]` | Hover / focus / touch warms the destination's resource plan without navigating — [intent prefetch](concepts.md#warming-a-destination-before-the-click). `:intent` is the only mode; there is no render or viewport preloading. |
| `useNavigation().state` (`"loading"`) | `@(subscribe [:rf.route/transition])` | Global `:idle`/`:loading`/`:error` you read anywhere — never threaded through a component. It reports the route's *blocking data*, not whether a router state machine is mid-flight. |
| `errorElement` / `useRouteError()` | A blocking `:resources` entry + `@(subscribe [:rf.route/error])` | Declare the read the page can't do without as `:blocking? true` and its failure projects onto `:rf.route/transition :error` with a structured [error record](../core/glossary.md#error-record) on `:rf.route/error`. There is no route-level error *callback*: a route never manufactures an error out of activation work it merely started. |
| `useBlocker()` / `usePrompt()` | `:can-leave` guard + `@(rf/subscribe [:rf/pending-navigation])` | [Route guard](glossary.md#route-guard) sub (boolean) and a *pending navigation you render from* — confirm dialog is an ordinary view. Leave-only; the entry guard is terminal ([below](#leaving-asks-the-user-entering-asks-the-app)). |
| Route-level auth in a `loader` (`throw redirect(...)`) | `:can-enter` guard + a `:rf.route/entry-denied` handler | The guard runs in the one planning pipeline, so it covers programmatic navigation, link clicks, the URL bar, Back/Forward, initial load, and SSR without per-door plumbing. Denial is terminal — [Require sign-in](how-to/require-sign-in-on-a-route.md). |
| Splat route `path="*"`, no-match route | Reserved [`:rf.route/not-found`](glossary.md#not-found) route | Ordinary route you register and design; carries activation events, scroll, and a `:reason` discriminator. |
| `<Outlet/>` + nested route config | `:parent` + `@(subscribe [:rf.route/chain])` | Nesting is data; you walk the chain and compose layout shells yourself (no render-slot machinery — see [below](#theres-no---layouts-are-data-you-compose), and [The model → Nested layouts](concepts.md#nested-layouts)). A parent's `:resources` *do* compose into the child's plan, so a shared shell read is declared once. |
| `state={{backgroundLocation}}` modal routing | An ordinary app-db flag beside the route | The URL names the thing being shown; whether it is shown *over* the list is a rendering decision your root view makes. Nothing masks the route, so Back, refresh, and deep-link all agree — [below](#modals-over-a-page-are-a-state-model-not-a-masked-route). |
| `<RouterProvider router>` / router context | nothing | The route lives in [runtime-db](../core/glossary.md#runtime-db); any [view](../core/glossary.md#view) reads it via subscription. No provider, no context. |
| Framework-mode (v7 / Remix) server loaders | The *same* `:resources` / `:on-match` | One declaration runs on client **and** server. No second "server loader" to keep in sync. |

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
`:rf.route/transition`. An auth guard needs no wrapper around the protected subtree
either: it's a [`:can-enter`](glossary.md#route-guard) boolean subscription named on
the protected route itself, which the runtime consults in the one planning pipeline —
so there is no tree position for it to be in, and no door for it to miss.

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
body or run it. re-frame2's [loader](glossary.md#loader) is `:resources` (a list of
declarations) or `:on-match` (a vector of [event](../core/glossary.md#event)
vectors). Being *data* means you can read it, test it, and draw a route's
data-dependency graph **without executing it** —
`(rf/handler-meta :route :app/cart)` hands you the list.

And `:resources` closes the click-away race: on route entry each resource is owned by
*this* navigation's nav-token; when a newer navigation supersedes it, a late reply is
*suppressed* rather than written. Classic bug — navigate away, old fetch resolves a
beat too late, clobbers the page you're on — fixed once, not in every loader. React
Router's loaders are abortable, which helps, but abort isn't guaranteed to win the
race; suppression is the correctness boundary, abort is the bandwidth optimisation on
top.

### One loader splits into two honest keys

A React Router `loader` carries two jobs that pull in opposite directions: fetching
the data the page cannot render without, and starting work that merely *begins* when
you arrive — analytics, a host notification, a background sync. Because both live in
one function, the router's `"loading"` state and its error surface answer for both,
and a failed analytics beacon can redden a page whose content arrived fine.

re-frame2 keeps them apart. `:resources` declares what the page needs, and it alone
drives `:rf.route/transition` / `:rf.route/error`. `:on-match` is fire-and-forget
activation: the runtime dispatches its events and never waits on them, correlates
them, or rewrites their failures into route state — an `:on-match` handler that
throws surfaces on the ordinary event error channel, attributed to the event that
threw. So the progress bar means "this page's data isn't here yet" and nothing else,
and work that owns its own status keeps it.

The nesting story follows the same split. Declaring `:parent` composes the
ancestors' `:resources` into the child's plan — a shell read is written once, and
identical requirements across the branch dedupe to one fetch. Nothing else is
inherited, because `:on-match`, `:scroll`, `:tags`, and the guards would each want
a different merge rule.

### Leaving asks the user, entering asks the app

React Router's `useBlocker` hands you an imperative `blocker` object with a state
machine you drive by hand; historically the "unsaved changes?" prompt bottomed out
in `window.confirm` or `beforeunload`. re-frame2 splits the job:
[`:can-leave`](glossary.md#route-guard) is a boolean *subscription* (`true` allows,
`false` blocks), and the blocked navigation parks in `:rf/pending-navigation` — *state
you render from*. Confirm dialog is an ordinary view that reads
`@(rf/subscribe [:rf/pending-navigation])`, with two buttons that `dispatch`
`:rf.route/continue` or `:rf.route/cancel`. No imperative blocker, no native dialog,
no modal-automation flakiness in tests — the whole flow asserts with zero DOM.

The entry guard is deliberately *not* symmetric. "Really discard your draft?" is a
question to the user, so it parks and waits. "Is this visitor signed in?" is a
question to application state, answered the same way every time it's asked — so a
`:can-enter` refusal is **terminal**: it commits nothing, parks nothing, and
dispatches `:rf.route/entry-denied` once. The post-login return is an ordinary fresh
navigation whose guard re-evaluates naturally, which is why nothing can loop and why
there is no "enter anyway" flag to punch a hole through the gate.

### Modals over a page are a state model, not a masked route

React Router's idiom for "open the photo in a dialog over the feed" is to navigate
to the photo route while stashing a `backgroundLocation` in history state, so the
router renders the *old* match underneath the new one. It works, but the URL and the
match now disagree, and the disagreement lives in history state — invisible to
anything that reads the route.

Here the URL still names the thing being shown, and whether it is shown *over* the
list is an ordinary rendering decision:

```clojure
(rf/reg-view root-view []
  (let [id @(subscribe [:rf.route/id])]
    [:div
     [page-for (if (= id :app/photo) :app/feed id)]   ;; the feed stays mounted
     (when (= id :app/photo)
       [photo-dialog])]))
```

The route is never masked, so Back, refresh, and a pasted deep link all agree on
what the URL means — a deep link to the photo can render the dialog over the feed,
or the full page, and that is your call rather than a consequence of how the visitor
got there. If "came from the feed" genuinely matters, it is app state you write down
on purpose, not a hidden field on a history entry.

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
