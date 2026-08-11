# Routing and navigation

Views are Hiccup and events are vectors. Navigation should stay the same
kind of data: the link you render, warm-up before a click, scroll and focus
after a route change, and an unsaved-changes guard.

The router itself is the core routing artefact (`re-frame.routing`), taught
under `docs/routing/`. There a route is a registry entry, navigation is an
event, and the active route is a subscription. This page assumes those three
moves and covers the Hicasso view side.

## Setup

Register routes once at boot:

```clojure
(ns app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/home     {} "/")
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article  {:params [:map [:id :string]]}       "/articles/:id")
(rf/reg-route :app/profile  {:params [:map [:username :string]]} "/profile/:username")
(rf/reg-route :app/inbox    {} "/inbox")
```

Views require the integration module:

```clojure
(ns app.views.articles
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.routing :refer [route-link]]))
```

## Route links

Spell an in-app link as `route-link`: name the route and its params, not a
URL. It is a plain function — use it inline in the view that owns the region:

```clojure
(h/defview article-card [{:keys [id]}]
  (let [{:keys [title author]} (h/sub [:article/summary id])]
    [:article.card
     [:h2 (route-link {:to :app/article :params {:id id}} title)]
     [:span.byline "by "
      (route-link {:to     :app/profile
                   :params {:username author}
                   :class  "author"}
        author)]]))
```

The call returns a real `<a>`, so hover preview, copy-link, and middle-click
work. The router builds the `:href`. The `:on-click` carries the click
decision as data under a reserved head (alongside `::h/prevent` from
[Events as data](03-events-as-data.md)):

```clojure
[:a {:href     "/profile/jane"
     :class    "author"
     :on-click [::h/navigate {:frame   :rf/default
                              :payload [:rf.route/url-requested
                                        {:url    "/profile/jane"
                                         :to     :app/profile
                                         :params {:username "jane"}}]
                              :native? false
                              :veto    nil}]}
 "jane"]
```

Two renders of one link are equal under `=`, so a structural test can read
the click decision off the tree ([Testing](14-testing.md)). Because
`route-link` is a plain function, not a separate re-rendering view, it
inlines: no subscription, no extra view cost. A nav bar of thirty links
costs what thirty anchors cost.

Click behaviour is the router's:

- A plain left-click: `preventDefault`, then the routing event dispatches to
  the frame captured at render.
- A modifier or auxiliary click belongs to the browser — new tab, no
  dispatch.
- A `:target` or `:download` anchor navigates natively.

Do not write `[::h/navigate …]` by hand. `route-link` creates it. The map
allows only `:frame`, `:payload`, `:native?`, and `:veto`. Any other key
raises `:rf.error/hicasso-malformed-navigate` at render and names the
position. A link rendered while the routing artefact is missing raises
`:rf.error/routing-artefact-missing` naming the `:to` — never a dead anchor.
Other props pass through to the `<a>`: classes, `:data-*`, ARIA.

### The active link

`route-link` does not compute active state. Compare against a route
subscription where the nav renders:

```clojure
(h/defview site-nav []
  (let [current (h/sub [:rf.route/id])
        nav     (fn [to label]
                  (route-link {:to           to
                               :class        (when (= to current) "is-active")
                               :aria-current (when (= to current) "page")}
                    label))]
    [:nav
     (nav :app/home     "Home")
     (nav :app/articles "Articles")]))
```

One view, one read, and a local helper — links stay inline. Use
`:aria-current "page"` for screen readers; style with the class.

### Cancel this link's navigation

A link may need to cancel its own navigation — for example, confirm before
discarding a scratch pane. Pass that as `:on-click` on `route-link`. Allowed
values: `nil`, `[::h/prevent [:app/event]]`, an `h/event` form, or a plain
function.

```clojure
(route-link {:to       :app/inbox
             :on-click (when draft-open?
                         [::h/prevent [:composer/confirm-discard]])}
  "Inbox")
```

`[::h/prevent …]` cancels the navigation and dispatches your event instead.
A bare event vector is refused: the click already produces one routing
event, and one user action must not yield two. Do not use this veto to guard
unsaved work across a whole page — that is the dirty-leave guard below,
which covers every exit, not only decorated links.

## Warm a destination on intent

A link can start loading its destination data on hover, focus, and touch so
the click lands on work already in flight:

```clojure
(route-link {:to :app/article :params {:id "intro"} :prefetch :intent}
  "Read more")
```

The only accepted value is `:intent`. Omit the key for a passive link. Any
other value fails at render — a silent wrong mode would look like "warm on
hover" and be hard to trust. Internally the handlers dispatch
`[:rf.route/prefetch {:to … :params …}]`, which you can also dispatch from
any event handler.

Prefetch is not navigation. Data may start loading, but nothing blocks a
transition, the URL does not change, and guards, scroll, and `:on-match` do
not run. Details of resource ownership live in
[Async resources](08-async-resources.md). Prefetch is a performance hint,
not authorization. Click through and ordinary resource dedupe reuses the
warmed work. Never click, and that work stays garbage-collectable. Warming a
destination whose `:can-enter` would refuse is allowed and means nothing:
activation still evaluates the guard on a real navigation.

## Scroll

Scroll policy is route data, not view code. Declare it per route (`:scroll`
metadata) or per navigation (`:scroll` on the navigate request):

| Policy | Behaviour | Default for |
|---|---|---|
| `:top` | Scroll to the top on entry | Forward navigations |
| `:restore` | Return to where the page was left | Back/Forward |
| `:preserve` | Leave the viewport alone | Opt-in |

Defaults are right for most pages. Set two cases by hand:

- An in-place query navigation on a list — pagination, a filter chip —
  usually should hold the viewport:
  `(rf/dispatch [:rf.route/navigate {:query-merge {:page 2} :scroll :preserve}])`.
- Restoration needs full page height. Back/Forward onto a page whose list is
  still loading restores against a short page and lands at the top. Declare
  the page's data as blocking route `:resources`, or keep previous data on
  screen, so content is present when restore runs.

## Focus after a route change

A route change repaints the page but moves focus nowhere. A screen-reader
user who activated "Articles" is still on the link they clicked and hears no
announcement. The recipe:

1. Key the main region by page identity.
2. Make the region programmatically focusable.
3. Focus the region on attach.

```clojure
(defn- focus-page [node]
  (when node (.focus node #js {:preventScroll true})))

(h/defview app-root []
  (let [route (h/sub [:rf.route/id])]
    [:div.app
     [site-nav]
     [:main {:key       route        ;; remount when the page changes
             :tab-index -1           ;; focusable, not in the tab order
             :ref       focus-page}  ;; refs run after commit
      (case route
        :app/home    [home-page]
        :app/article [article-page]
        [not-found-page])]]))
```

`:key route` remounts `<main>` when the page identity changes, so the ref
runs again and focus lands on the new page. Query-only and fragment-only
changes keep the same route id — the region does not remount, and a filter
click does not move focus. `preventScroll` stops the focus call from
fighting scroll policy: routing owns scroll; this recipe owns focus. If "a
new page" means more than the route id (article 7 → article 9), widen the
key: `{:key (str route "|" article-id)}`.

Focus for modals and popovers is a different job —
[Overlays and focus](12-overlays-and-focus.md).

## Unsaved changes (dirty-leave guard)

"You have unsaved changes" is ordinary state end to end. The guard is a
subscription, the blocked attempt is a value, the dialog is a view, and the
user's choice is an event. There is no blocker hook and no `window.confirm`.

Declare when leaving is safe, and put the guard on the route:

```clojure
(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft])
       (get-in db [:editor :saved]))))          ;; true = safe to leave

(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}
  "/articles/:id/edit")
```

When the guard returns `false`, nothing commits: the URL and state do not
change, and the attempt parks in `[:rf/pending-navigation]`. Render the
prompt from that value:

```clojure
(h/defview leave-guard-dialog []
  (when-let [pending (h/sub [:rf/pending-navigation])]
    [:div.modal {:role "alertdialog" :aria-modal true}
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click [:rf.route/cancel   (:id pending)]} "Stay"]
     [:button {:on-click [:rf.route/continue (:id pending)]} "Discard and leave"]]))
```

Mount it once near the root; it renders nothing until an attempt parks.
`:rf.route/continue` replays the navigation the user asked for —
destination, `:replace?`, scroll policy. `:rf.route/cancel` drops it. Both
take the pending id, so a stale click on an already-resolved dialog is a
safe no-op. In a real app, render the box through the overlay module's modal
so focus is trapped and restored ([Overlays and focus](12-overlays-and-focus.md)).
The state shape here does not change.

"Save and close" must leave without the prompt. Save, then navigate with the
one-shot bypass:

```clojure
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))
     :fx [[:dispatch [:rf.route/navigate {:to            :app/article
                                          :params        {:id (get-in db [:editor :id])}
                                          :bypass-leave? true}]]]}))
```

`:bypass-leave?` skips this route's `:can-leave` for this one navigation
only. The destination's `:can-enter` still runs.

!!! warning "The browser's exits are not yours"
    A pending value is a fact inside your app; it cannot stop a tab close, an
    external link, or a reload. Pair the guard with a `beforeunload` listener
    that reads the same `:editor/can-leave?` sub — one dirty flag, two exits.
    The listener recipe is in the routing corpus. Routing does not wrap the
    browser's own dialog.

## Deep links, Back and Forward

There is no separate path for "arrived by URL" or "pressed Back". The URL is
an input. A deep link is the first URL at boot; Back/Forward are history
inputs. Both run the same match → validate → guard → activate pipeline as a
link click.

- A deep link onto a route with `:query-defaults` arrives with defaults
  filled. Views read `(h/sub [:rf.route/query])` and do not special-case
  first load.
- Guards run at every entry: navigate, link, address bar, Back/Forward,
  initial load, SSR. The dirty-leave dialog parks a Back press the same way
  it parks a link click.
- Back/Forward restore scroll by default (`:restore`). The focus recipe
  moves focus because the route id changed; `preventScroll` keeps the two
  from fighting.
- Navigation does not cancel in-flight async work. A save still pending when
  the user leaves keeps an instance status readable from anywhere; cache
  consequences land regardless of the current page
  ([Async resources](08-async-resources.md)). The guard protects unsaved
  local state; supersession protects the reply race.
- On the server, the same pipeline runs for the request URL; the client
  hydrates without re-running it ([SSR and hydration](17-ssr-and-hydration.md)).

??? info "Coming from React Router?"
    There is no `<Link>` component: `route-link` is a plain function that
    returns an anchor with data on it. There is no `useBlocker` or
    `usePrompt`: the blocked attempt is app state you render. There is no
    `router.prefetch()` call: warming is an event. Everything you would
    reach into router context for is a subscription.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Rendering a link throws `:rf.error/routing-artefact-missing` | Core routing artefact not loaded | `(:require [re-frame.routing])` at boot, before first render |
| Link full-page reloads | Hand-written `[:a {:href …}]` bypasses interception | Use `route-link`, or the document-level click listener from the routing corpus |
| `:rf.error/hicasso-malformed-navigate` at render | Hand-built or edited `::h/navigate` head | Do not write the head; `route-link` creates it |
| `route-link` refuses your `:on-click` event vector | One click must not yield two semantic events | Wrap it: `[::h/prevent [:app/event]]` |
| Link with `:prefetch` refuses at render | Only `:intent` is accepted | Remove the key, or spell `:prefetch :intent` |
| Leaving always blocks; error names the guard | `:can-leave` sub returned a non-boolean | `:rf.error/can-leave-non-boolean`: return strict `true`/`false` |
| Back lands at the top of a long page | Restore ran before the page had its height | Blocking route `:resources`, or keep previous data on screen |
| Focus goes nowhere after navigating | Main region not keyed, or not focusable | `:key` by page identity, `:tab-index -1`, focus in the `:ref` |

## When not to use this module

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing artefact |
| In-memory UI steps that should not touch the URL — wizard panes, non-linkable tabs | app-db state, or a machine |
| External links | A plain `[:a {:href …}]` — `route-link` is for the route table |
| Guarding one button, not a page's exits | The veto roster on that link, or ordinary app logic |
