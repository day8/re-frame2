# The model

The [tutorial](tutorial.md) builds an articles app one step at a time. This page
explains the rules that app relies on, and what else they let you do. Exact
signatures are in the [re-frame.routing API](../api/re-frame.routing.md).

The examples use the tutorial's routes, registered in the `:app` frame:

```clojure
(rf/reg-route :app/home {} "/")
(rf/reg-route :app/articles {:query [:map [:tag {:optional true} :string]]} "/articles")
(rf/reg-route :app/article
  {:parent   :app/articles
   :params   [:map [:slug :string]]
   :on-match [[:app/load-article]]}
  "/articles/:slug")
(rf/reg-route :app/article-editor
  {:params    [:map [:slug :string]]
   :on-match  [[:editor/open]]
   :can-leave [:editor/can-leave?]}
  "/articles/:slug/edit")
(rf/reg-route :app/settings {:can-enter [:auth/signed-in?]} "/settings")
(rf/reg-route :app/login {} "/login")
(rf/reg-route :rf.route/not-found {} "/_404")
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
validate and coerce: declare `[:page :int]` and `?page=2` arrives as the number `2`.
Path params and query params stay separate maps. A route can also fill in defaults.
Here is a paginated version of the tutorial's `:app/articles`:

```clojure
(rf/reg-route :app/articles
  {:query          [:map [:tag {:optional true} :string]
                         [:page {:optional true} :int]]
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

A URL string is converted for these slot types: `:int` (a whole integer only, so
`?page=12abc` stays the string `"12abc"`), `:uuid`, `:boolean` (`true` or `false`) and
an `[:enum …]` of keywords, each also inside `[:maybe …]`. A slot of any other type
receives the string.

A slot type must survive the trip URL → value → URL. `reg-route` throws
`:rf.error/route-decimal-unsupported` for a `:double` slot and
`:rf.error/route-keyword-unbounded-unsupported` for a bare `:keyword` slot. For a
keyword value, list the allowed ones: `[:sort {:optional true} [:enum :new :top]]`.

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

Two details. The helper tolerates a missing `:query`, because `{:to …}` usually has
none and a destination replayed from a pending navigation omits an empty one. And a
carried value has already been coerced by the *current* route's schema (an
`[:enum :light :dark]` key is `:dark`, not `"dark"`); the helper doesn't re-parse it.
With the schemas artefact loaded, a mismatch is caught at the call site: `route-link`
throws `:rf.error/route-url-validation` and a navigate is rejected.

To change the *current* route's query, use an [in-place request](#staying-on-the-page)
instead.

### Metadata keys

<a id="the-metadata-map-in-full"></a>

| Group | Keys | Controls |
|---|---|---|
| Shape | `:params`, `:query`, `:query-defaults` | URL ↔ maps |
| Lifecycle | `:on-match`, `:can-leave`, `:can-enter` | Activation work and guards |
| Layout | `:doc`, `:parent`, `:tags`, `:scroll` | Nesting (and `:resources` composition), grouping, scroll |
| Classification | `:sensitive`, `:large` | Redaction of the route slice at egress |
| From other artefacts | `:resources` (Resources), `:head` ([SSR head](../ssr/head.md)) | Server state, head model |

An unknown unqualified key throws `:rf.error/route-bad-metadata` at registration.
Namespaced keys (`:myapp/…`) are yours. The registered map is data you can query —
`(rf/handler-meta {:source :store :kind :route :id :app/settings})` returns it,
`:tags` included. The per-key reference is under
[`reg-route`](../api/re-frame.routing.md#reg-route).

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

| Key | Effect |
|---|---|
| `:to` | Destination route id (`:url` is the raw-URL alternative) |
| `:params` | Path params for `:to` |
| `:query` | Replace the query |
| `:query-merge` | In-place only: edit the current query with a **map** of changes (a `nil` value removes that key; a non-map value is rejected) |
| `:fragment` | `#fragment` |
| `:replace?` | Replace the current history entry instead of pushing one |
| `:scroll` | `:top`, `:restore`, `:preserve` or `false`, overriding the route's |
| `:bypass-leave?` | `true` skips the current route's `:can-leave` check for this navigation |

<a id="navigating-to-a-raw-url-string"></a>

`:url` takes a path inside the app, such as `/articles/intro?tag=ssr`, and matches it
the way a typed URL is matched; an unmatched one lands on
[not found](#not-found-is-a-route-you-register). A URL on another origin is refused:
the route stays where it is and the runtime emits `:rf.route/external-url-requested`.
Link to other sites with a plain `[:a {:href …}]`.

`:to` and `:url` are alternatives, and a `:url` carries its own path and query, so it
takes no `:params`, `:query` or `:query-merge`. A request that breaks a rule like that,
names a key outside the table (a namespaced one included), or isn't exactly one map is
rejected with `:rf.error/navigate-bad-request`, and the error's `:reason` names the
rule it broke.

Outside a view there is no frame in scope. Navigate from a handler's `:fx`, or pass
`{:frame :app}` to `rf/dispatch` at the REPL. A bare `rf/dispatch` inside a timeout or
promise callback raises `:rf.error/no-frame-context`; let the effect that started the
async work deliver its reply as an event (managed [HTTP](../async/http.md) does this),
and navigate from that event.

<a id="what-happens-in-order"></a>

A navigation runs in a fixed order: write the route slice in runtime-db, push the
URL, then dispatch the route's `:on-match` events. State changes before the URL.

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
[:rf.route/navigate {:query-merge {:sort "top"} :replace? true}]
```

`@(subscribe [:rf.route/query])` reads the result, already coerced, so `:page` is `2`
rather than `"2"`.

An in-place request needs a current route to edit, and it can't change path params:
`:params` names a new address, so it needs `:to`. Use `:query` or `:query-merge`, not
both.

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

A hand-written `[:a {:href …}]` dispatches nothing, so the browser does a full page
load. To keep such clicks in the app without `route-link`, install one document-level
click listener that checks eligibility itself (primary button, no modifier keys, no
`:target` or `:download`, a same-origin in-app `href`) and dispatches
`:rf.route/url-requested` for the clicks it accepts.

Every prop `route-link` doesn't use is passed through to the `<a>`, so classes,
`:data-*` and ARIA attributes work as usual. It uses the address keys to build the
`href`, and `:prefetch` ([warming a destination](#warming-a-destination-before-the-click)).
A link click always adds a history entry: `:replace?`, `:scroll` and `:bypass-leave?`
are navigate keys, so on a `route-link` they are passed to the `<a>` like any other
prop. When a click needs one of them, dispatch `:rf.route/navigate` instead.

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
`:scroll` (or a navigate's) is `:top` (the default for links and navigates),
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

Two different jobs can sit on a route: work to start when it activates, and data the
page can't render without.

`:on-match` is the first. It is a vector of event vectors that the runtime dispatches
whenever the route becomes active, including the same route with changed params or
query. An identical navigation, or one that only changes the `#fragment`, doesn't
re-fire it. The tutorial's `:app/article` uses it to load the article:

```clojure
{:on-match [[:app/load-article]]}
```

It runs on the client and on the server, after the route slice is written and the
URL pushed. If the route's resource plan fails, none of its events run. It is not a
readiness signal: `:on-match` never moves `:rf.route/transition`, never waits for
the async work its events start, and never turns a handler's failure into a route
error. A handler that throws reports on the ordinary
[event error channel](../core/errors.md).

### Declaring the data a page needs

<a id="declaring-resources-instead"></a>

Data the page needs before it is ready is declared with `:resources`, from the
Resources artefact, in place of an `:on-match` loader:

```clojure
(rf/reg-route :app/article
  {:parent    :app/articles
   :params    [:map [:slug :string]]
   :resources [{:resource       :article/detail
                :params         (fn [route] {:slug (get-in route [:params :slug])})
                :blocking?      true}
               {:resource       :article/comments
                :params         (fn [route] {:slug (get-in route [:params :slug])})
                :blocking?      false
                :keep-previous? true}]}
  "/articles/:slug")
```

Entering the route fetches both with the route as owner; leaving or navigating
elsewhere releases them, and a reply that arrives after that is dropped. Per-user data
uses a scope resolver (`{:from-db …}`), which fails closed when nobody is signed in.
The [Resources model](../resources/concepts.md) covers the rest.

<a id="when-a-loader-fails"></a>

### Readiness comes from the blocking resources

`:rf.route/transition` and `:rf.route/error` report whether the route's blocking
reads have data, are still on their first load, or failed:

| Plan state | `:transition` | `:error` |
|---|---|---|
| A blocking first load is still pending | `:loading` | `nil` |
| A blocking first load failed | `:error` | the first failure (`:rf.error/resource-route-blocking`) |
| The plan could not be built | `:error` | `:rf.error/resource-route-plan` |
| Every blocking read has data, or there are none | `:idle` | `nil` |

A background refresh of data already on screen is not `:loading`, and a failed
refresh stays on the resource rather than on the route. Non-blocking reads,
[prefetches](#warming-a-destination-before-the-click) and `:on-match` never change
either value. Without the Resources artefact the route is always `:idle`.

### Parent resources compose to the child

A child route gets its ancestors' `:resources` as well as its own, so data the shell
needs is declared once on the parent:

```clojure
(rf/reg-route :app/articles
  {:query     [:map [:tag {:optional true} :string]]
   :resources [{:resource :article/tags :blocking? true}]}   ;; the section's tag list
  "/articles")

(rf/reg-route :app/article
  {:parent    :app/articles                                   ;; also plans :article/tags
   :params    [:map [:slug :string]]
   :resources [{:resource  :article/detail
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

Naming the `:parent` is the opt-in. Only `:resources` are inherited; `:on-match`,
`:scroll`, `:head`, `:tags` and the guards stay per route. A requirement contributed
by more than one route in the chain is fetched once, and a child that repeats its
parent's requirement gets an advisory rather than a second fetch. Rendering is not
composed for you: the [layout fold](#nested-layouts) is still yours.

## Guards

### Blocking a navigation

`:can-leave` names a subscription on the route being left. `true` lets the navigation
go; `false` stops it. The URL and the route slice stay where they were, the attempt
is parked in `[:rf/pending-navigation]`, and the runtime dispatches
`:rf.route/navigation-blocked`. Answer with `[:rf.route/continue id]` or
`[:rf.route/cancel id]`, passing the pending value's `:id` — the tutorial's editor
does this in [Step 11](tutorial.md#step-11--warn-before-losing-unsaved-changes).

```clojure
@(subscribe [:rf/pending-navigation])
;; => {:id              "pn-1"
;;     :destination     {:to :app/home}
;;     :target          {:route-id :app/home :params {} :query {} :fragment nil :url "/"}
;;     :cause           :navigate     ;; or :link, :popstate
;;     :policy          {}            ;; the request's :replace? and :scroll
;;     :requested-url   "/"
;;     :rejecting-route :app/article-editor
;;     :rejecting-guard :editor/can-leave?}
```

A blocked Back or Forward also carries `:url-restored? true`: the address bar had
already moved, and the runtime put it back. `:rf.route/continue` replays the
`:destination` with its `:policy`, so the navigation that happens is the one that was
asked for, and it re-checks the destination's `:can-enter`. To skip
the check for one navigation, such as "save and close", pass
`{:bypass-leave? true}`. Recipe: [Guard against unsaved changes](how-to/guard-unsaved-changes.md).

### Guarding entry — `:can-enter`

`:can-enter` names a subscription checked before entering a route, which makes it the
usual sign-in gate. It runs on every way in: navigate, link, typed URL, Back/Forward,
first load and SSR.

A refusal is final. Nothing commits, no pending value is created, and the runtime
dispatches `:rf.route/entry-denied` once. The built-in handler does nothing, so an
unhandled refusal simply keeps the reader where they are (and returns a `403` under
SSR). The tutorial's handler sends the reader to `/login`; this one also remembers
where they were going:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]}))
```

After sign-in, navigate to the stored `destination`. That is a new navigation, so the
guard runs again. Recipe: [Require sign-in on a route](how-to/require-sign-in-on-a-route.md).

Both guards must return `true` or `false`. Anything else refuses the navigation and
raises `:rf.error/can-leave-non-boolean` or `:rf.error/can-enter-non-boolean`. Both
subscriptions receive the resolved target as the last element of their query vector,
`(fn [db [_ target]] …)`, with `target` shaped like the pending value's `:target`, so
one guard can answer differently for each destination.

Use `:can-enter` for a route's own auth rule, as
[realworld_http](../../examples/real-apps/realworld_http) does. An interceptor suits
one policy shared by many routes; the sign-in recipe shows both.

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
| First `reg-route` throws `:rf.error/routing-artefact-missing` | `re-frame.routing` is not required | Require it once at boot |
| `reg-route` throws `:rf.error/route-bad-metadata` | `:path` inside the map, or an unknown unqualified key | The path is the third argument |
| `reg-route` throws `:rf.error/invalid-route-pattern` | The pattern breaks the grammar (no leading `/`, an empty `//` segment, …) | The error names the character position |
| `reg-route` throws `:rf.error/route-decimal-unsupported` or `:rf.error/route-keyword-unbounded-unsupported` | A `:double` or bare `:keyword` slot can't round-trip through a URL | Use `:string`, `:int` or `[:enum …]` |
| `reg-route` throws `:rf.error/invalid-route-classification` | A `:sensitive` / `:large` path is malformed | Fix the path |
| `:rf.warning/route-shadowed-by-equal-score` at registration | Same shape as an existing route that matches the same URLs | The earlier route wins; make one pattern more specific |
| `:rf.warning/route-classification-query-key-unpromoted` | A `[:query k]` classification names an undeclared key, which stays a string and is never redacted | Declare `k` in `:query` or `:query-defaults` |
| `route-link` or `route-url` throws `:rf.error/no-such-route` | The route id isn't registered (often a typo) | Fix the id |
| `route-url` throws `:rf.error/missing-route-param` | A path param is missing, `nil` or `""` | Supply it (a `nil` query value is simply left out) |
| `route-url` throws `:rf.error/route-url-validation` | Params or query fail the schema, name a param the path doesn't capture, or the address carries a key that isn't an address key, such as `:replace?` | Fix the address |
| `route-url` throws `:rf.error/route-url-non-edn-value` | A float, `Date` or other value with no URL form | Encode it as a string first |
| `route-link` throws `:rf.error/route-link-bad-prefetch` | `:prefetch` is something other than `:intent` | Use `:intent`, or leave the key off |
| Navigation rejected with `:rf.error/navigate-bad-request` | The payload is not one request map, or the map breaks a [request rule](#navigating-to-a-raw-url-string) | Write `[:rf.route/navigate {:to …}]`; the error's `:reason` names the rule |
| A navigate does nothing; `:rf.error/schema-validation-failure` in traces | Unknown route id, or params that fail the schema | Fix the address; the route slice is unchanged |
| A navigate from a callback throws `:rf.error/no-frame-context` | Bare `rf/dispatch` in a timeout or promise | Navigate from an event handler's `:fx` |
| `[:rf.route/prefetch …]` does nothing; `:rf.error/prefetch-bad-address` | Malformed address or unknown destination | Fix the address |
| A guard always refuses, with `:rf.error/can-leave-non-boolean` / `:rf.error/can-enter-non-boolean` | The guard sub returned something other than `true` / `false` | Wrap it in `boolean`, `some?` or `not` |
| Guards are ignored; `:rf.warning/can-leave-subs-artefact-missing` | The subscription runtime isn't available to evaluate them | Load it |
| A plain `[:a {:href …}]` reloads the page | It doesn't go through `route-link` | Use `route-link`, or a document-level listener that dispatches `:rf.route/url-requested` |
| The page doesn't scroll; `:rf.error/unsupported-scroll-strategy` | `:scroll` isn't `:top`, `:restore`, `:preserve` or `false` | Use one of those |
| `:rf.warning/no-not-found-route` on an unmatched URL | `:rf.route/not-found` isn't registered | Register it |
| `:rf.error/duplicate-url-binding` | Two frames have `:url-bound? true` | Keep one; the first keeps the URL |
| `make-frame` throws `:rf.error/invalid-url-strategy` | `:url-strategy` isn't a strategy map | Use `rf.routing/history-url-strategy`, `hash-url-strategy` or `with-base-path` |
| First page shows `:rf.error/resource-route-plan` | A resource the route declares was registered after the URL-bound frame was created | Register first, or dispatch `:rf.route/replan-resources` ([details](#several-frames-one-address-bar)) |
| A route reads `:error` with `:rf.error/resource-route-plan` and `:recovery :fix-parent` | Its `:parent` names a route that isn't registered, often a typo | Fix the `:parent` id |
| `[:rf.route/replan-resources …]` does nothing; `:rf.error/replan-bad-request` | `:cause` is missing or `nil` (`:reason :missing-cause`), or no route is active yet (`:no-active-route`) | Pass `{:cause …}`, after the first navigation |

## Advanced

### Hand-rolled async loader — capture the nav-token

<a id="a-hand-rolled-async-loader"></a>

`:resources` handles page loads for you. If you fetch from `:on-match` yourself, you
own a race: open article A, go to B before A's reply lands, and A's late reply
overwrites B. Capture the **navigation token** when the load starts and deliver the
reply only if it still matches.

The `:rf.route/nav-token` coeffect gives an `:on-match` handler the live token. The
`:rf.route/with-nav-token` effect delivers a reply only while that token is still
current; otherwise it drops it and emits `:rf.route.nav-token/stale-suppressed`.

```clojure
;; Capture the live token, start your fetch, and carry the token into the reply.
(rf/reg-event :app/load-article
  {:rf.cofx/requires [:rf.route/nav-token]}
  (fn [{:rf.route/keys [nav-token] rt :rf.db/runtime} _]
    (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
      ;; :app/fetch-article is your async effect; on reply it dispatches
      ;; :app/article-arrived with the captured token and the payload.
      {:fx [[:app/fetch-article {:slug slug :on-reply [:app/article-arrived nav-token slug]}]]})))

;; Still current → the :rf/reply-to event is dispatched with a reply map appended as
;; its last argument, the payload under :value. Stale → nothing is dispatched.
(rf/reg-event :app/article-arrived
  (fn [_ [_ captured-token slug payload]]
    {:fx [[:rf.route/with-nav-token
           {:rf/reply-to [:app/article-loaded slug]
            :nav-token   captured-token
            :value       payload}]]}))

(rf/reg-event :app/article-loaded
  (fn [{:keys [db]} [_ _slug {:keys [value]}]]    ;; reply = {:status :ok :value …}
    {:db (assoc db :article/current value)}))
```

### Warming a destination before the click

A link can start loading its destination's data on hover, focus or touch, so the
click lands on a fetch already in flight:

```clojure
[rf/route-link {:to :app/article :params {:slug "intro"} :prefetch :intent}
 "Read intro"]
```

`:intent` is the only accepted value; a passive render dispatches nothing. To turn it
off, leave `:prefetch` out. Any other value throws at render, so a typo can't
silently give you a link that never warms. The link dispatches
`[:rf.route/prefetch {:to :app/article :params {:slug "intro"}}]`, which you can also
dispatch yourself.

A prefetch plans the same resources a real navigation would, without an owner:
`:blocking?` has no effect, and nothing about the route changes — no slice write, URL,
scroll, guards or `:on-match`. If the click follows, the navigation reuses the warmed
data; if it doesn't, the data can be garbage-collected. A prefetch is not an
authorization check: warming a route whose `:can-enter` would refuse is allowed,
because entering still runs the guard.

### Replanning the active route's resources

Sometimes the identity behind a route's reads changes while the route doesn't: a saved
session is restored after the page opened, or an admin switches tenant. A
`{:from-db …}` resource re-keys to the new identity, but the newly selected entry just
sits `:idle`, and navigating to the same address is a no-op. Dispatch:

```clojure
[:rf.route/replan-resources {:cause [:session-restore]}]
```

This reruns the active route's plan, parents included, against the current app-db,
under the same nav-token and route owner. Requirements the new plan still needs are
kept without a request; new ones are fetched with your `:cause`; ones it drops are
released. Readiness is recomputed, so a route whose plan failed while the identity was
unknown is repaired in place. If the replan itself fails to plan, the route releases
everything it held, so data from the old identity can't keep arriving. `:cause` is
required. For an identity switch: clear the old scope, commit the new identity, then
replan. Unchanged data is never refetched, and no guards, `:on-match`, URL or scroll
work runs. See the [Resources model](../resources/concepts.md).

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

### URL strategies

```clojure
(rf/make-frame {:id           :app
                :url-bound?   true
                :url-strategy rf.routing/hash-url-strategy})  ;; default: history-url-strategy
```

`route-url` and `match-url` always work in path form; the strategy adds the `#` at the
browser edge. Wrap a strategy in `rf.routing/with-base-path` to serve the app under a
subpath. SSR runs no history or listener, but it does build `route-link` hrefs through
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
