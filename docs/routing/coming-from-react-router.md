# Coming from React Router

If you have used React Router's data APIs — `createBrowserRouter`, loaders,
`useNavigate`, `useLoaderData`, `useBlocker` — most of re-frame2 routing will be
familiar: routes are data, a route declares the data it needs, and the URL is an
input to the app.

The main difference is that re-frame2 has no router object. Routes are
[registrations](../core/glossary.md#registration), a navigation is an
[event](../core/glossary.md#event), and the active route is read with a
[subscription](../core/glossary.md#subscription), so there is no `<RouterProvider>`
and no router context. [The model](concepts.md) explains routing from scratch; this
page starts from what you know.

## The mapping

| React Router | re-frame2 | Notes |
|---|---|---|
| `createBrowserRouter([...])` / `<Route>` config | [`reg-route`](concepts.md#move-1-a-route-is-a-registry-entry) | Each route is one entry in a process-global table, registered like an event handler rather than placed in a component tree. |
| Route object (`path`, `loader`, `errorElement`…) | The [route](glossary.md#route)'s metadata map | A plain Clojure map, which any code can read. |
| `:slug` path param, `useParams()` | `:slug` in the path + `@(subscribe [:rf.route/params])` | [Route params](glossary.md#route-params) are coerced by the route's schema, and validated by it when `re-frame.schemas` is loaded. |
| `useSearchParams()` | `@(subscribe [:rf.route/query])` | A separate map from path params. A key declared as `:int` arrives as a number. |
| `useLocation()` | `@(subscribe [:rf/route])` | The whole route slice — route id, params, query, fragment, and readiness — as one map. |
| `generatePath()` / `matchPath()` | `rf.routing/route-url` / `rf.routing/match-url` | Pure functions and exact inverses, runnable on the JVM — [converting by hand](concepts.md#converting-routes--urls-by-hand). |
| `loader` function | [`:resources`](concepts.md#declaring-resources-instead) for data the page needs; [`:on-match`](concepts.md#loaders-declaring-a-pages-data) for work to start on entry | Both are data, not functions. React Router's one `loader` does both jobs; here they are separate keys — see [below](#one-loader-becomes-two-keys). |
| `useLoaderData()` | An ordinary subscription | The data lands in the resource cache or [app-db](../core/glossary.md#app-db), and the [view](../core/glossary.md#view) reads it like any other state. |
| `useNavigate()` → `navigate("/x")` | `(dispatch [:rf.route/navigate {:to :app/article :params {:slug "intro"}}])` | [Navigation is an event](concepts.md#move-2-navigation-is-an-event), so it is traced and can be intercepted. |
| `redirect()` from a loader or action, `<Navigate replace>` | `:fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]` in an event handler | A redirect is an ordinary navigation, returned as an effect. |
| `<Link to>` | `[rf/route-link {:to :app/articles}]` | Renders a real `<a href>`, handles plain clicks, and leaves cmd/shift/middle-click to the browser. |
| `<NavLink>`'s `isActive` | Compare against `@(subscribe [:rf.route/id])` in your own view | `route-link` has no active state; a small wrapper sets `:aria-current` and a class — [highlighting the active link](concepts.md#highlighting-the-active-link). |
| `<Link prefetch="intent">` (framework mode) | `[rf/route-link {:to :app/article :params {:slug "intro"} :prefetch :intent}]` | Hover, focus or touch loads the destination's resources without navigating — [warming a destination](concepts.md#warming-a-destination-before-the-click). `:intent` is the only mode. |
| `useNavigation().state` (`"loading"`) | `@(subscribe [:rf.route/transition])` | `:idle`, `:loading` or `:error`, readable from any view. It reports the route's blocking resources only. |
| `errorElement` / `useRouteError()` | A `:blocking? true` resource + `@(subscribe [:rf.route/error])` | When a blocking read fails, `:rf.route/transition` is `:error` and `:rf.route/error` holds a structured [error record](../core/glossary.md#error-record). Failures in `:on-match` work never reach the route. |
| `useBlocker()` / `usePrompt()` | `:can-leave` guard + `@(subscribe [:rf/pending-navigation])` | A boolean [guard](glossary.md#route-guard) sub, and a pending navigation your own view renders a prompt from — [Guard against unsaved changes](how-to/guard-unsaved-changes.md). |
| Auth in a `loader` (`throw redirect(...)`) | `:can-enter` guard + a `:rf.route/entry-denied` handler | Checked on every way into the route, including the first load and server rendering — [Require sign-in on a route](how-to/require-sign-in-on-a-route.md). |
| Splat route `path="*"` | [`:rf.route/not-found`](glossary.md#not-found) | An ordinary route you register and render; its params carry the URL and a `:reason`. |
| `<Outlet/>` + nested routes | `:parent` + `@(subscribe [:rf.route/chain])` | You fold the chain into layout shells in the root view — [see below](#layouts-instead-of-an-outlet). A parent's `:resources` are included in the child's. |
| `state={{backgroundLocation}}` modal routing | An ordinary rendering decision | [See below](#modals-over-a-page). |
| `<ScrollRestoration/>` | Built in; override with a route's or a navigation's `:scroll` | `:top` when following a link, `:restore` on Back/Forward — [fragments and scrolling](concepts.md#fragments-and-scrolling). |
| `createHashRouter` / `basename` | `:url-strategy rf.routing/hash-url-strategy`, wrapped in `(rf.routing/with-base-path strategy "/base")` for a sub-path | Set on the url-bound frame — [URL strategies](concepts.md#url-strategies). |
| `createMemoryRouter` | A frame without `:url-bound? true` | Routes in memory without touching the address bar, which is what tests use. |
| `<RouterProvider router>` | Nothing | The route lives in [runtime-db](../core/glossary.md#runtime-db), and any view subscribes to it. |
| Framework-mode server loaders | The same `:resources` and `:on-match` | One declaration runs on the client and the server. |

## Where it differs

### No hooks

In React Router, hooks such as `useNavigate` and `useNavigation` exist because router
state is reachable only from components rendered inside the router. In re-frame2 the
active route is in [runtime-db](../core/glossary.md#runtime-db), and you read it with
`subscribe` from any view, from an [event handler](../core/glossary.md#event-handler)
(through its coeffects), from a test, or from the REPL.

So a loading bar is a small view over `:rf.route/transition`, wherever it sits in the
tree. An auth guard is a [`:can-enter`](glossary.md#route-guard) subscription named on
the route itself rather than a wrapper component around a subtree.

### Navigation is an event

`navigate("/articles")` calls into React Router directly. In re-frame2 a navigation is
`(dispatch [:rf.route/navigate …])`, like any other state change, and Back/Forward
arrive as events too. Navigations therefore appear in [Xray](../core/glossary.md#xray)
next to the click that caused them, and [time-travel](../core/glossary.md#time-travel)
rewinds the URL along with the rest of the [frame](../core/glossary.md#frame)'s state:
the URL is derived from the state, not the other way round.

### Loaders are data

React Router's `loader` is a function, so you find out what a route fetches by reading
or running it. In re-frame2 a route's [loader](glossary.md#loader) is `:resources`, a
list of declarations, or `:on-match`, a vector of event vectors. Either can be read
without running anything: `(rf/handler-meta {:source :store :kind :route :id :app/article})`
returns the route's metadata.

`:resources` also handles the click-away race. Each resource loaded on entry belongs
to that navigation's [nav-token](glossary.md#nav-token); if a newer navigation
replaces it, a late reply is discarded instead of overwriting the page the reader is
now on. React Router aborts superseded loaders, which saves bandwidth, but an abort can
lose the race with a reply that has already arrived.

### One loader becomes two keys

A React Router `loader` both fetches data the page cannot render without and starts
work that merely begins on arrival, such as analytics. Both share the router's loading
state and error handling, so a failed analytics call can put the page into its error
state.

re-frame2 separates them. `:resources` declares the data the page needs, and only
`:resources` drives `:rf.route/transition` and `:rf.route/error`. `:on-match` events
are dispatched and not waited on; a handler that throws reports on the ordinary event
error channel and does not affect the route.

With `:parent`, a child route includes its ancestors' `:resources`, and identical
requests are fetched once. Nothing else is inherited: `:on-match`, `:scroll`, `:tags`
and the guards stay per route.

### Leaving asks the reader; entering asks the app

`useBlocker` returns a `blocker` object whose state you manage. In re-frame2,
[`:can-leave`](glossary.md#route-guard) is a boolean subscription, and a blocked
navigation is parked in `:rf/pending-navigation`. Your own view renders the prompt
from it and dispatches `:rf.route/continue` or `:rf.route/cancel`, so tests need no
DOM and no native dialog.

`:can-enter` works differently. Whether the reader is signed in does not change while
you wait, so a refusal parks nothing: it commits nothing and dispatches
`:rf.route/entry-denied` once. The return after sign-in is an ordinary new
navigation, which the guard checks again. There is no flag that skips `:can-enter`.

### Modals over a page

To show an item in a dialog over a list, React Router navigates to the item while
keeping a `backgroundLocation` in history state, and renders the old match underneath.
The URL and the rendered match then disagree, and only history state knows why.

In re-frame2 the URL names the article, and your root view decides to render it over
the list:

```clojure
(rf/reg-view root-view []
  (let [id @(subscribe [:rf.route/id])]
    [:div
     [page-for (if (= id :app/article) :app/articles id)]   ;; keep the list mounted
     (when (= id :app/article)
       [article-dialog])]))
```

`page-for` is the tutorial's; `article-dialog` is your view of the article. Back,
refresh and a pasted link all give the same result. If "opened from the list" needs to
matter, store it in app-db.

### The same loaders run on the server

React Router's framework mode has server loaders with their own build and runtime. In
re-frame2, server rendering feeds the request URL to the same routes on a per-request
frame; the same `:on-match` events and `:resources` run, and the state is sent to the
client, which hydrates without fetching again — see
[routing on the server](concepts.md#the-same-handler-runs-on-the-server).

### Layouts instead of an outlet

`<Outlet/>` renders a parent layout's active child into a slot for you. re-frame2 has
no slot: a route names its `:parent`, `@(subscribe [:rf.route/chain])` returns the
chain, and the root view folds the chain into layout shells with ordinary Clojure.
That is more code than `<Outlet/>`, in exchange for no routing-specific rendering.
[The tutorial](tutorial.md#step-7--a-shared-layout) builds it, and
[nested layouts](concepts.md#nested-layouts) has the code.

### Smaller differences

- **Plain `[:a {:href …}]` links are not intercepted**; they load the page. Use
  `route-link`, or install your own document-level click handler.
- **You register the 404 page.** [`:rf.route/not-found`](glossary.md#not-found) is
  your route, and its `:reason` param tells a plain miss from a schema failure or a
  malformed URL.
- **Bad values fail differently by source** (with `re-frame.schemas` loaded; without
  it, values are coerced but not checked). A bad URL from outside, such as a deep link,
  lands on not-found. A bad `route-url` call throws, and a bad navigation is rejected
  with an error.
- **The route table is data you can query**, for breadcrumbs, sitemaps or analytics.
