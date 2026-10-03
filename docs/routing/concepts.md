# The model

The [tutorial](tutorial.md) builds an articles app one step at a time. This page
explains the rules that app relies on, and what else they let you do. Exact
signatures are in the [re-frame.routing API](../api/re-frame.routing.md).

Routes are registered process-wide; each frame has its own active route.
These are the reader app's routes:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]))

(rf/reg-route :app/home {} "/")
(rf/reg-route :app/articles {:query [:map [:tag {:optional true} :string]]} "/articles")
(rf/reg-route :app/article
  {:parent :app/articles
   :params [:map [:slug :string]]
   :on-match [[:app/load-article]]}
  "/articles/:slug")
```

## Three ideas

<a id="routing-the-url-is-a-sub"></a>

Routing adds three things to the event pipeline you already use:

- **A route is data in a registry.** `reg-route` stores an id, a metadata map and a
  path pattern.
- **Navigation is an event.** `[:rf.route/navigate {:to :app/article :params {:slug
  "intro"}}]` changes the route, the same way any event changes state.
- **The active route is a subscription.** A view reads `@(subscribe [:rf.route/id])`
  and picks a page.

Links, Back and Forward, a typed URL and a server request all come through as events
that write the route slice, and the URL is written from that state. There is no
router component and no router context.

## A route is a registry entry

<a id="move-1-a-route-is-a-registry-entry"></a>

`reg-route` takes three arguments: id, metadata map, path. The path is always the
third argument; putting `:path` in the map throws `:rf.error/route-bad-metadata`.

A path pattern is built from literal segments (`/articles`), named params
(`/:slug`), optional groups (`{/:lang}?`), one trailing splat (`/files/*rest`) and
the root (`/`).

When several patterns match one URL, the most specific wins: more literal segments
first, so `/articles/new` beats `/articles/:slug` for `/articles/new`; then more
segments; then a named param over a splat, so `/articles/:slug` beats
`/articles/*rest` for `/articles/intro`. Registration order matters only when two
patterns have the same shape and can match the same URL. Registering the second
emits `:rf.warning/route-shadowed-by-equal-score`, and the first one registered wins.

Matching ignores a trailing slash, so `/articles/` matches `/articles`, and is
case-sensitive, so `/Articles` matches nothing.

### Params and query

`:params` and `:query` take [schemas](../core/how-to/validate-with-schemas.md) that
coerce the URL's strings: declare `[:page :int]` and `?page=2` arrives as the number
`2`. With `re-frame.schemas` loaded they also validate. Path params and query params
stay separate maps. A route can also fill in defaults. Here is a paginated version of
the tutorial's `:app/articles`, with a sort order too:

```clojure
(rf/reg-route :app/articles
  {:query          [:map [:tag {:optional true} :string]
                         [:page {:optional true} :int]
                         [:sort {:optional true} [:enum :new :top]]]
   :query-defaults {:page 1}}
  "/articles")
```

Defaults belong to the destination. A deep link, a `route-link`, a navigate and a
prefetch all resolve `/articles` to `:page 1`. `route-url` leaves out a key that is
already at its default, so `/articles?tag=ssr` is the canonical form of
`/articles?tag=ssr&page=1`.

A query key the route doesn't declare stays what the URL carries: a string key with a
string value, whichever way you navigate. Only declared keys become keywords, so a
URL full of made-up keys creates no keywords. In a query string `%20` decodes to a
space, `+` stays a literal `+`, and a key given twice keeps its last value.

A URL string is coerced according to the declared slot type. For example,
`?page=12abc` cannot become an integer: it stays a string and fails validation when
`re-frame.schemas` is loaded. The reference lists the
[supported conversions and query parsing rules](../api/re-frame.routing.md#match-url).

A slot type must survive the trip URL → value → URL. `reg-route` throws
`:rf.error/route-decimal-unsupported` for a `:double` slot and
`:rf.error/route-keyword-unbounded-unsupported` for a bare `:keyword` slot. For a
keyword value, list the allowed ones in an `[:enum …]`, as `:sort` does above.

<a id="metadata-keys"></a>
<a id="the-metadata-map-in-full"></a>

The metadata map also declares activation work, layout and guards as described
below. Namespaced keys (`:myapp/…`) are yours. For all keys and registration
rules, use the [`reg-route` reference](../api/re-frame.routing.md#reg-route).

### Try the matching rules

This cell registers the reader's routes with the paginated `:app/articles`, a
literal `/articles/new` and a not-found route. Each button hands the frame a URL
the way the address bar does, as `[:rf.route/handle-url-change url]`.
The view picks a heading by `:rf.route/id` and prints the params and query the
URL produced. The frame has no `:url-bound?`, so it routes in memory and the
address bar stays where it is. Add a URL to `urls` and press Mod-Enter to try
your own.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.routing])

(rf/reg-route :app/home {} "/")
(rf/reg-route :app/articles
  {:query          [:map [:tag {:optional true} :string]
                         [:page {:optional true} :int]
                         [:sort {:optional true} [:enum :new :top]]]
   :query-defaults {:page 1}}
  "/articles")
(rf/reg-route :app/new-article {} "/articles/new")
(rf/reg-route :app/article
  {:parent :app/articles
   :params [:map [:slug :string]]}
  "/articles/:slug")
(rf/reg-route :rf.route/not-found {} "/_404")

(def urls
  ["/articles/new" "/articles/intro" "/articles/" "/Articles"
   "/articles?tag=ssr&page=2" "/articles?sort=top&ref=feed" "/articles?page=12abc"])

(rf/reg-view matched-route []
  (let [id     @(subscribe [:rf.route/id])
        params @(subscribe [:rf.route/params])
        query  @(subscribe [:rf.route/query])]
    [:div
     (for [url urls]
       ^{:key url}
       [:button {:on-click #(dispatch [:rf.route/handle-url-change url])} url])
     [:h3 (case id
            :app/home           "Home"
            :app/articles       "All articles"
            :app/new-article    "New article"
            :app/article        (str "Article " (:slug params))
            :rf.route/not-found "Not found"
            nil)]
     [:pre (pr-str {:route-id id :params params :query query})]]))

[rf/frame-root {:id             :concepts/matching
                :initial-events [[:rf.route/navigate {:to :app/home}]]}
 [matched-route]]
```

`/articles/new` beats `/articles/:slug`, the trailing slash is ignored, and
`/Articles` matches nothing. `?page=2` arrives as the number `2`, `:sort` as a
keyword, and the undeclared `ref` stays a string key. `?page=12abc` matches the
pattern but fails the schema, so it lands on
[not found](#not-found-is-a-route-you-register) with `:reason :validation`.

## Navigation is an event

<a id="move-2-navigation-is-an-event"></a>

`:rf.route/navigate` takes one request map. Dispatch it like any other event: from a
view's injected `dispatch`, or from an event handler's `:fx`
([tutorial Step 8](tutorial.md#step-8--navigate-from-an-event)).

```clojure
[:rf.route/navigate {:to :app/article :params {:slug "intro"}}]
[:rf.route/navigate {:to :app/articles :query {:tag "ssr"}}]
[:rf.route/navigate {:to :app/login :replace? true}]
[:rf.route/navigate {:to :app/article :params {:slug "intro"} :fragment "comments"}]
[:rf.route/navigate {:url "/articles/intro"}]          ;; a raw URL, matched like a typed one
```

A navigation normally pushes a history entry. `:replace? true` replaces it
instead, useful when redirecting after sign-in so Back skips the login form.
`:fragment` names an element to scroll to. The
[request reference](../api/re-frame.routing.md#navigate-request-rules) lists
the policy keys and the valid combinations.

<a id="navigating-to-a-raw-url-string"></a>

`:url` takes a path inside the app, such as `/articles/intro?tag=ssr`, and matches it
the way a typed URL is matched; an unmatched one lands on
[not found](#not-found-is-a-route-you-register). A URL on another origin is refused:
the route stays where it is and the runtime emits `:rf.route/external-url-requested`.
Link to other sites with a plain `[:a {:href …}]`.

`:to` and `:url` are alternatives, and a `:url` carries its own path and query, so it
takes no `:params`, `:query` or `:query-merge`. A request that breaks a rule like that,
or names an unknown key, is rejected with `:rf.error/navigate-bad-request`, and
the error's `:reason` names the rule. The
[full rules](../api/re-frame.routing.md#navigate-request-rules) are in the API reference.

Outside a view there is no frame in scope. Navigate from a handler's `:fx`, or pass
`{:frame :app}` to `rf/dispatch` at the REPL. A bare `rf/dispatch` inside a timeout or
promise callback raises `:rf.error/no-frame-context`; let the effect that started the
async work deliver its reply as an event (managed [HTTP](../async/http.md) does this),
and navigate from that event.

<a id="what-happens-in-order"></a>

A `:rf.route/navigate` commit writes the route slice in runtime-db, pushes the URL,
then dispatches the route's `:on-match` events. A link click or Back/Forward reaches
the URL-change handler after the address bar has moved; that handler commits the
same route state. A resource planning failure skips `:on-match` and reports a route
error.

### Staying on the page

<a id="navigate-in-place-change-the-query-stay-on-the-route"></a>

Leave out `:to` and `:url` and the request edits the current location. The route and
its params stay; `:query-merge` folds into the query, `:query` replaces it, and
`:fragment` moves the anchor:

```clojure
;; Next page — change one key, keep the tag filter.
[:rf.route/navigate {:query-merge {:page 2}}]

;; A new tag resets the page — nil removes the key.
[:rf.route/navigate {:query-merge {:tag "ssr" :page nil}}]

;; Clear every filter.
[:rf.route/navigate {:query {}}]

;; Change a view option without adding a Back step.
[:rf.route/navigate {:query-merge {:sort :top} :replace? true}]
```

`@(subscribe [:rf.route/query])` reads the result, already coerced, so `:page` is `2`
rather than `"2"`.

Removing a query key restores its `:query-defaults` value, if it has one. In the
paginated route above, `{:query-merge {:page nil}}` therefore returns to page 1.

An in-place request needs a current route to edit, and it can't change path params:
`:params` names a new address, so it needs `:to`.

This cell uses the routes the [matching cell](#try-the-matching-rules) registered.
Routes are process-wide, and this second frame has its own active route. The last
line is `route-url` for the current query:

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.routing :as rf.routing])

(rf/reg-view article-filters []
  (let [{:keys [page] :as query} @(subscribe [:rf.route/query])]
    [:div
     [:button {:on-click #(dispatch [:rf.route/navigate {:query-merge {:page (inc page)}}])}
      "Next page"]
     [:button {:on-click #(dispatch [:rf.route/navigate {:query-merge {:tag "ssr" :page nil}}])}
      "#ssr"]
     [:button {:on-click #(dispatch [:rf.route/navigate {:query {}}])}
      "Clear filters"]
     [:pre (pr-str query)]
     [:p [:code (rf.routing/route-url {:to :app/articles :query query})]]]))

[rf/frame-root {:id             :concepts/in-place
                :initial-events [[:rf.route/navigate {:to :app/articles}]]}
 [article-filters]]
```

**Next page** twice reads `{:page 3}`, a number. **#ssr** keeps the route and
resets the page, and **Clear filters** returns to `/articles`, where `:page` is
back at its default and `route-url` leaves it out.

### Linking from views

```clojure
[rf/route-link {:to :app/article :params {:slug "intro"}} "Read intro"]
[rf/route-link {:to :app/articles :query {:tag "ssr"} :class "nav-link"} "#ssr"]
```

`route-link` renders a real `<a href>`, so hover, copy-link and cmd- or middle-click
work. It intercepts only a plain left click: it calls `.preventDefault` and
dispatches `:rf.route/url-requested`, the event the router listens for. Links with
`:target "_blank"` or `:download` are left to the browser. An `:on-click` of your own
runs first, and calling `.preventDefault` in it cancels the navigation.

A hand-written `[:a {:href …}]` dispatches nothing, so the browser does a full
page load. Use it for external links; use `route-link` for navigation in the app.

Every prop `route-link` doesn't use is passed through to the `<a>`, so classes,
`:data-*` and ARIA attributes work as usual. It uses the address keys to build the
`href`, and `:prefetch` ([warming a destination](#warming-a-destination-before-the-click)).
The navigate policy keys work on a link too, and apply to the navigation its click
makes: `:replace? true` replaces the current history entry instead of adding one,
`:scroll` overrides the route's scroll for this navigation, and `:bypass-leave? true`
skips the current route's `:can-leave` check.

#### Highlighting the active link

`route-link` has no active state. Compare against a route subscription in your own
view:

```clojure
(rf/reg-view nav-link [props label]
  (let [active? (= (:to props) @(subscribe [:rf.route/id]))]
    [rf/route-link (cond-> props
                     active? (assoc :aria-current "page"
                                    :class (str (:class props) " is-active")))
     label]))
```

To light up a parent for any of its children — the **Articles** tab on an article
page — check whether `(:to props)` is in `[:rf.route/chain]`. For one entry in a
filter strip, compare `:query` too.

## The active route is a subscription

The current route lives in runtime-db, beside app-db. You read it with these
subscriptions and never write it directly:

```clojure
[:rf/route]              ;; the whole slice
[:rf.route/id]
[:rf.route/params]
[:rf.route/query]
[:rf.route/fragment]
[:rf.route/transition]   ;; :idle | :loading | :error
[:rf.route/error]
[:rf.route/chain]        ;; :parent ancestry, root-most first
[:rf/pending-navigation] ;; a blocked leave waiting for an answer, or nil
```

Before the first navigation commits, `:rf/route` and all its projections are
`nil`, including `:rf.route/transition` and `:rf.route/chain`. This can last while
an initial entry guard refuses the URL; let the root view render a shell or a
session-loading state until there is an active route.

`:rf.route/transition` drives a global progress bar without per-page loading flags.
It reports on the route's blocking `:resources` ([details](#when-a-loader-fails)):

```clojure
(rf/reg-view progress-bar []
  (case @(subscribe [:rf.route/transition])
    :loading [:div.progress.active]
    :error   [:div.error (:reason @(subscribe [:rf.route/error]))]
    nil))
```

### Fragments and scrolling

A fragment-only change updates the slice and doesn't re-fire `:on-match`. A route's
`:scroll` (or a navigate's or link's) is `:top` (the default for links and navigates),
`:restore` (the default for Back, Forward and the first load), `:preserve`, or
`false` for no scroll effect. `:top` scrolls the element whose `id` is the fragment
into view, or the page to the top when there is no such element.

## Nested layouts

There is no outlet component. A child route names a `:parent`, and
`@(subscribe [:rf.route/chain])` returns the active route's ancestry, root-most
first — `[:app/articles :app/article]` on `/articles/intro`. The root view renders
the leaf's page and wraps it in each ancestor's shell; the tutorial writes that fold
in [Step 7](tutorial.md#step-7--a-shared-layout).

`:parent` also adds the ancestors' `:resources` to the child's plan
([below](#parent-resources-compose-to-the-child)). Nothing else is inherited.

## Activation work and page data

<a id="loaders-declaring-a-pages-data"></a>

A route can start work when it activates and declare the data its page needs.
These have different lifetimes:

- `:on-match` dispatches a vector of events on entry. The tutorial uses
  `[[:app/load-article]]` to copy an article from its local map into app-db.
  A page-visit event is another use. The runtime does not wait for work those
  events start; failures use the ordinary [event error channel](../core/errors.md).
- `:resources` declares reads managed by the Resources package. Entry loads
  them; leaving releases their route ownership. Late replies cannot overwrite
  the new page. Blocking reads drive the route's loading and error state.

Both run on the client and server. `:on-match` fires when params or query change,
but not for an identical or fragment-only navigation. If the resource plan
cannot be built, no `:on-match` events run.

### Declaring the data a page needs

<a id="declaring-resources-instead"></a>

Use `:resources` for server data the page needs. A requirement names a registered
read and converts the resolved route to that read's params:

```clojure
{:resources [{:resource :article/detail
              :params (fn [route] {:slug (get-in route [:params :slug])})
              :blocking? true}]}
```

[Load data for a route](how-to/load-page-data.md) shows the resource registration,
route declaration and article view together.

### Readiness comes from the blocking resources

<a id="when-a-loader-fails"></a>

`:rf.route/transition` reports readiness, and `:rf.route/error` holds a structured
failure. The route can render a skeleton while the client is loading:

| Plan state | Transition | Error |
|---|---|---|
| A blocking first load failed | `:error` | `:rf.error/resource-route-blocking` |
| The plan could not be built | `:error` | `:rf.error/resource-route-plan` |
| A blocking first load is pending, with no failures | `:loading` | `nil` |
| Every blocking read has data, or there are none | `:idle` | `nil` |

Background refreshes and non-blocking reads keep their state on the resource.
`:on-match` and [prefetches](#warming-a-destination-before-the-click) never change
route readiness. Without the Resources package, a committed route is `:idle`.

### Parent resources compose to the child

Naming a `:parent` includes its `:resources` in the child's plan. A shared
article-section shell can declare its tag list once on `:app/articles`; the
article page receives that read along with its own detail read. Identical
requirements are fetched once. The [parent-data example](how-to/load-page-data.md#share-a-parents-data)
shows the registrations.

Only `:resources` are inherited. `:on-match`, `:scroll`, `:head`, `:tags` and
guards remain specific to each route. The root view still
[composes the layout](#nested-layouts).

## Guards

### Blocking a navigation

`:can-leave` names a subscription on the route being left. `true` allows the
navigation; `false` keeps the route and URL in place and stores the attempt in
`[:rf/pending-navigation]`. A view can render a prompt from it and dispatch
`[:rf.route/continue id]` or `[:rf.route/cancel id]` using the pending value's `:id`.
[Guard against unsaved changes](how-to/guard-unsaved-changes.md) adds this to an
article editor.

Continuing replays the destination and its navigation policy, including a
destination's `:can-enter` check. Back/Forward works too: the runtime restores
the old URL while the prompt waits. `:bypass-leave? true` skips the current
route's leave guard for one navigation, useful after a successful save.

### Guarding entry — `:can-enter`

`:can-enter` names a subscription checked before entering a route, including
links, navigate events, typed URLs, Back/Forward, first load and SSR. A refusal
commits nothing and parks no pending navigation. It dispatches
`:rf.route/entry-denied`; the built-in handler does nothing, so a refused click
keeps the reader where they are. Under SSR, a refused entry answers `403`.

[Require sign-in on a route](how-to/require-sign-in-on-a-route.md) handles the
denial, stores its `:destination`, and returns there after sign-in. The return
is a new navigation and checks the guard again. There is no bypass for entry.

Both guards must return `true` or `false`; other values refuse and raise
`:rf.error/can-leave-non-boolean` or `:rf.error/can-enter-non-boolean`.
Subscriptions receive the resolved target as the last item of the query vector,
so the decision can depend on the destination.

An identical navigation checks neither guard. Changing app-db alone does not
recheck guards or remove the current page: signing out should also navigate to
a public route. For one policy shared across many routes, the sign-in recipe
also shows a [frame interceptor](how-to/require-sign-in-on-a-route.md#a-policy-that-is-not-about-routes).

## Not found is a route you register

An unmatched URL activates the reserved id `:rf.route/not-found`, with the URL in
`:params` and sometimes a `:reason`:

| `:params` | What happened |
|---|---|
| `{:url "…"}` | No pattern matched |
| `{:url "…" :reason :validation}` | A pattern matched but its schema failed |
| `{:url "…" :reason :malformed-url}` | Bad percent-encoding; also emits `:rf.warning/malformed-url` |
| `{:url "…" :reason :match-error}` | Matching the URL threw; also emits `:rf.warning/malformed-url` |

If `:rf.route/not-found` isn't registered, the runtime emits
`:rf.warning/no-not-found-route`. Only URLs end up here. A navigate or `route-url`
with params that fail the schema is a programming error and fails loudly instead.

A miss that arrives from the browser or the server (a link, a typed URL, Back or
Forward, a request) is also reported as an [error](../core/errors.md),
`:rf.error/no-such-handler` with `:kind :route`, which
[server rendering](../ssr/concepts.md#when-the-server-throws) turns into a `404`. A
`{:url …}` navigate that misses lands on not-found without that error.

## The browser is an event source

<a id="the-browser-is-just-another-event-source"></a>

```clojure
(rf/make-frame {:id :app :url-bound? true})
```

`:url-bound? true` makes this frame the owner of the address bar. Creating it reads
the current URL into the route slice and installs the Back/Forward listener; there is
no separate install call. Each press then arrives as an event. Frames without the flag
route in memory, which is what stories and test fixtures want. Only one frame owns
the URL ([several frames](#several-frames-one-address-bar)).

## The same handler runs on the server

[SSR](../ssr/concepts.md) feeds the request URL through the same URL-change event on a
per-request frame. `:on-match` and blocking `:resources` run, the state ships in the
page, and the client hydrates without fetching again. URL pushes and scrolling do
nothing on the server.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| First `reg-route` throws `:rf.error/routing-artefact-missing` | Routing is not loaded | Require `re-frame.routing` once at boot |
| A link reloads the page | A plain anchor is used for an app route | Use `route-link` |
| A declared query value is missing | The key is not in the route's schema, so it remains a string key | Declare the keys the view reads in `:query` |
| A navigation from a callback raises `:rf.error/no-frame-context` | A bare `rf/dispatch` has no frame | Return navigation from a handler's `:fx` |
| Entry or leave always fails with a non-boolean guard error | The guard returns a user map or `nil` | Return `true` or `false`, for example `(some? user)` |
| Back and the address bar do not follow the route | No frame owns the URL | Set `:url-bound? true` on the app frame |
| `:rf.error/duplicate-url-binding` is reported | Two frames request the address bar | Keep one URL-bound frame |

The [routing reference](../api/re-frame.routing.md) documents registration and
request failures. Loader failures are covered with their
[data-loading task](how-to/load-page-data.md#troubleshooting), and host failures
with [browser URL configuration](how-to/configure-browser-urls.md#troubleshooting).

## Advanced

### Hand-rolled async loader — capture the nav-token

<a id="a-hand-rolled-async-loader"></a>

Prefer resources for page loads. If an `:on-match` handler starts its own request,
capture the current navigation's token with `:rf.route/nav-token` and deliver
the reply through `:rf.route/with-nav-token`. A reply from an earlier navigation
is dropped instead of overwriting the current page. The
[token effect reference](../api/re-frame.routing.md#effects-fx) shows the complete
reply shape.

### Warming a destination before the click

`:prefetch :intent` on a `route-link` starts the destination's resources on hover,
focus or touch. It changes no active route state and runs no guards or
`:on-match`. A later click reuses the data or request; the real navigation still
checks entry. The [prefetch example](how-to/load-page-data.md#warm-the-destination-before-a-click)
shows the link.

### Replanning the active route's resources

When a restored session changes a resource's identity but keeps the same route,
`[:rf.route/replan-resources {:cause …}]` loads the newly selected reads. It
keeps reusable data and in-flight reads and releases requirements no longer in
the plan. It does not navigate or recheck guards. The
[identity-change recipe](how-to/load-page-data.md#reload-the-plan-after-an-identity-change)
shows when to dispatch it.

### Several frames, one address bar

Every frame has its own route slice, so a page can hold the app frame, a story and a
test fixture, each on a different route. Only frames with `:url-bound? true` touch the
browser, and only one of them owns it:

- **Outbound.** A navigation in the owner pushes or replaces the browser URL. A
  navigation in any other frame changes that frame's route slice and nothing else.
- **Inbound.** Back and Forward dispatch `:rf.route/handle-url-change` to the owner,
  looked up at the moment of the press.
- **Conflicts.** A second `:url-bound? true` frame emits
  `:rf.error/duplicate-url-binding` and doesn't take over: the first frame to claim the
  URL keeps it. Destroy the owner, or re-register it without the flag, and ownership
  passes to the next claimant.

`(rf.routing/url-owner-frame-id)` returns the owner's id, or `nil` when no frame is
URL-bound, in which case URL pushes do nothing and Back/Forward is ignored.

A URL-bound frame reads the current URL while `make-frame` runs, so register routes
and the resources they declare first. If the route is missing, the first page lands
on not-found. If a resource it declares is missing, the plan fails with
`:rf.error/resource-route-plan` and the route reads `:error`; repair that with
`[:rf.route/replan-resources {:cause …}]` rather than navigating to the same URL,
which is a no-op.

### Keeping tokens off the wire

A route can mark parts of its slice as secret. They are redacted from traces, Xray
and other tooling while the route is active. This one is an OAuth callback beside the
articles app:

```clojure
(rf/reg-route :app/oauth-callback
  {:query     [:map [:token :string] [:code :string]]
   :sensitive [[:query :token] [:query :code]]}
  "/oauth/callback")
```

More in [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).

### Carrying global state through the URL

A destination is taken literally. `[:rf.route/navigate {:to :app/settings}]` goes to
exactly `/settings`; it never picks up query keys from the current route.

If your app carries a theme or locale across pages, write that policy as a function
over the address:

```clojure
(defn with-shell-query
  "Copy the shell's global URL state onto a destination address.
   The destination's own query wins."
  [current-query address]
  (update address :query
          (fn [destination-query]
            (merge (select-keys current-query [:theme :locale])
                   (or destination-query {})))))

(rf/reg-view settings-link []
  (let [query @(subscribe [:rf.route/query])]
    [rf/route-link (with-shell-query query {:to :app/settings}) "Settings"]))
```

The carried keys are visible in the address, and
`(with-shell-query {:theme "dark"} {:to :app/settings})` is a unit test with no
frame. For an app-wide policy, apply it in your own navigation event or an
interceptor.

The helper tolerates a missing `:query`, because `{:to …}` usually has
none and a destination replayed from a pending navigation omits an empty one. And a
carried value has already been coerced by the *current* route's schema (an
`[:enum :light :dark]` key is `:dark`, not `"dark"`); the helper doesn't re-parse it.

So declare each carried key on every destination as well. A destination that doesn't
declare `:theme` writes `:dark` as `?theme=%3Adark`, which comes back as the string
key `"theme"` with the value `":dark"`, and the next page's helper no longer finds it.
With the schemas artefact loaded, a declared key whose value doesn't fit is caught at
the call site: `route-link` throws `:rf.error/route-url-validation` and a navigate is
rejected.

To change the *current* route's query, use an [in-place request](#staying-on-the-page)
instead.

### URL strategies

```clojure
(rf/make-frame {:id           :app
                :url-bound?   true
                :url-strategy rf.routing/hash-url-strategy})  ;; default: history-url-strategy
```

`route-url` and `match-url` always work in path form; the strategy adds the `#` at the
browser edge. [Configure browser URLs](how-to/configure-browser-urls.md) explains
when to choose hash routing and how to serve the app under a subpath with
`rf.routing/with-base-path`. SSR runs no history or listener, but it does build `route-link` hrefs through
the frame's strategy, so the server HTML carries the same `href` the client renders.

<a id="converting-routes--urls-by-hand"></a>

### Converting routes and URLs by hand

```clojure
(rf.routing/route-url {:to :app/article :params {:slug "intro"}})
;; => "/articles/intro"
(rf.routing/match-url "/articles/intro")
;; => {:route-id :app/article :params {:slug "intro"} …}
```

Both are pure and run on the JVM and in ClojureScript. A `nil` path param throws; a
`nil` query value is left out.
