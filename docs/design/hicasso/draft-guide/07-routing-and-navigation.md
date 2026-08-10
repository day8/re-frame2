# Routing and navigation

Your views are Hiccup and your events are vectors; navigation should not bring
ceremony back. This page wires a [Hicasso](glossary.md#hicasso) app to the re-frame2 router. It covers
the link you render, the warm-up before the click, scroll and focus after the
route changes, and the "unsaved changes" guard. All of it is data and ordinary
state.

The router itself is the core routing artefact (`re-frame.routing`), taught in
the routing corpus under `docs/routing/`. There, a route is a registry entry,
navigation is an event, and the active route is a subscription. This page does
not re-teach any of that. It assumes those three moves and owns the view side.

> **The link renders its click decision as data. Everything after the click is
> ordinary state.**

## Setup

Register routes once, at boot, with the core artefact:

```clojure
(ns app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]))   ;; the core routing artefact

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

Spell an in-app link as [`route-link`](glossary.md#route-link): name the route and its params, never a
URL. It is a plain function. Use it inline, inside the view that already owns
the region:

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

The call returns one real `<a>`, so hover preview, copy-link, and middle-click
all behave. The router synthesizes the `:href`. The `:on-click` carries the
click decision as data, under the routing module's reserved head. This is the
second reserved head in the [intent](glossary.md#intent) grammar, beside [`::h/prevent`](glossary.md#hprevent) from
[Events as data](03-events-as-data.md):

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

Two renders of one link are equal under `=`, so a structural test reads the
click decision straight off the tree ([Testing](14-testing.md)). Because
[`route-link`](glossary.md#route-link) is a plain function, not a [boundary](glossary.md#boundary), it inlines. There is no
hook, no subscription read, and no row in any boundary count. A nav bar of
thirty links costs what thirty anchors cost.

The click law is the router's, and the link restates none of it:

- A plain left-click: `preventDefault` runs, then the routing [intent](glossary.md#intent)
  dispatches to the frame captured at render.
- A modifier or auxiliary click belongs to the browser — a new tab opens, and
  nothing dispatches.
- A `:target` or `:download` anchor navigates natively, untouched.

You never write `[::h/navigate …]` by hand. [`route-link`](glossary.md#route-link) mints it, and the
grammar is closed: exactly `:frame`, `:payload`, `:native?`, and `:veto`. Any
other key refuses at render with `:rf.error/hicasso-malformed-navigate`,
naming the position. A link rendered while the routing artefact is absent
also fails at render, with `:rf.error/routing-artefact-missing` naming the
`:to` — never a dead anchor. Every prop the link does not claim passes
through to the `<a>`: classes, `:data-*`, ARIA attributes.

### The active link

[`route-link`](glossary.md#route-link) computes no active state. "Am I on this page?" is a comparison
against a route subscription, read once where the nav renders:

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

One [boundary](glossary.md#boundary), one read, and a plain local helper — links stay inline.
`:aria-current "page"` is what a screen reader announces; the class is what you
style.

### The pre-navigation veto

A link may need to cancel its own navigation and do something else — for
example, confirm before it discards a scratch pane. The `:on-click` you pass
to [`route-link`](glossary.md#route-link) is that veto. Its roster is closed: `nil`,
`[::h/prevent [:app/event]]`, an [`h/event`](glossary.md#hevent) form, or a plain function.

```clojure
(route-link {:to       :app/inbox
             :on-click (when draft-open?          ;; an ordinary read in this view
                         [::h/prevent [:composer/confirm-discard]])}
  "Inbox")
```

The prevent form is the declarative veto: it cancels the navigation and
dispatches your [intent](glossary.md#intent) instead. The link refuses a *bare* intent vector
there, loudly. The click already produces the one routing intent, and one
user action must not yield two semantic events.

Do not use the veto to guard unsaved work across a whole page. That job
belongs to the dirty-leave guard below. The guard covers every exit, not
only the links you remembered to decorate.

## Warm a destination on intent

A link can warm its destination's data on hover, focus, and touch. The click
then lands on a fetch already in flight:

```clojure
(route-link {:to :app/article :params {:id "intro"} :prefetch :intent}
  "Read more")
```

Prefetch accepts only one value: `:intent`. Omit the key for a passive link.
Any other value fails at render on purpose — a silent wrong mode would look
identical to "warm on hover," and you would never trust the attribute.
Internally the [intent](glossary.md#intent) handlers dispatch
`[:rf.route/prefetch {:to … :params …}]` — an ordinary event that you can
also dispatch yourself from any handler.

A warm run is not a navigation. The destination may start loading data, but
that load is not "owned" by the route the way a real visit is: nothing blocks
the transition, the URL does not change, and guards / scroll / `:on-match`
do not run. Details of resource ownership live in
[Async resources](08-async-resources.md); here the rule is simpler — prefetch
is a performance hint, not authorization. Click through, and the ordinary
resource dedupe reuses the warmed work. Never click, and the warmed work
stays garbage-collectable. To warm a destination whose `:can-enter` would
refuse is permitted and means nothing: activation still evaluates that guard
on a real navigation.

## Scroll conduct

Scroll policy is route data, not view code. Declare it per route (`:scroll`
metadata) or per navigation (`:scroll` on the navigate request):

| Policy | Behaviour | Default for |
|---|---|---|
| `:top` | Scroll to the top on entry | Forward navigations |
| `:restore` | Return to where the page was left | Back/Forward |
| `:preserve` | Leave the viewport alone | Opt-in |

The defaults are right for pages. You set two cases by hand:

- An in-place query navigation on a list — pagination, a filter chip — should
  usually hold the viewport still:
  `(rf/dispatch [:rf.route/navigate {:query-merge {:page 2} :scroll :preserve}])`.
- Restoration works only when the page has its full height back. Back/Forward
  onto a page whose list is still loading restores against a short page, and
  the viewport lands at the top anyway. Declare the page's data as blocking
  route `:resources` — or keep previous data on screen — so the content is
  present when the restore runs.

## Focus after a route change

A route change repaints the whole page but moves focus nowhere. A
screen-reader user who activated "Articles" is still focused on the link they
clicked, and hears no announcement that anything happened. The recipe is
ordinary code, in three moves:

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
     [:main {:key       route        ;; remount exactly when the page changes…
             :tab-index -1           ;; …focusable, but not in the tab order
             :ref       focus-page}  ;; refs run after commit
      (case route
        :app/home    [home-page]
        :app/article [article-page]
        [not-found-page])]]))
```

`:key route` remounts `<main>` when the page identity changes, so the ref
runs again and focus lands on the new page. Query-only and fragment-only
changes keep the same route id. The region does not remount, and a filter
click does not move focus. `preventScroll` stops the focus call from fighting
the scroll policy above: routing owns scroll, and this recipe owns focus. If
"a new page" means more than the route id to you — article 7 to article 9 —
widen the key with the identifying param: `{:key (str route "|" article-id)}`.

Focus for [overlays](glossary.md#overlay) — modals, popovers — is a different job with its own owner:
the focus [intent](glossary.md#intent) in [Overlays and focus](12-overlays-and-focus.md).

## The dirty-leave guard

"You have unsaved changes" is state-driven code end to end. The guard is a
subscription, the blocked attempt is a value, the dialog is a view, and the
user's choice is an event. There is no blocker hook and no `window.confirm`.

Declare when leaving is safe, and put the guard on the route. The blocking
mechanism is the router's. The routing corpus's *Guard against unsaved
changes* recipe is the full treatment:

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
prompt off that value, with the choices as [intent](glossary.md#intent) vectors that carry the
pending id:

```clojure
(h/defview leave-guard-dialog []
  (when-let [pending (h/sub [:rf/pending-navigation])]
    [:div.modal {:role "alertdialog" :aria-modal true}
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:on-click [:rf.route/cancel   (:id pending)]} "Stay"]
     [:button {:on-click [:rf.route/continue (:id pending)]} "Discard and leave"]]))
```

Mount it once near the root; it renders nothing until an attempt parks.
`:rf.route/continue` replays exactly the navigation the user asked for —
destination, `:replace?`, scroll policy. `:rf.route/cancel` drops it. Both
take the pending id, so a stale click on an already-resolved dialog is a safe
no-op. In a real app, render the box through the [overlay](glossary.md#overlay) module's modal, so
focus is trapped and restored ([Overlays and focus](12-overlays-and-focus.md)).
The state shape here does not change.

"Save and close" must leave without the prompt. Save, then navigate with the
explicit one-shot bypass:

```clojure
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:editor :saved] (get-in db [:editor :draft]))
     :fx [[:dispatch [:rf.route/navigate {:to            :app/article
                                          :params        {:id (get-in db [:editor :id])}
                                          :bypass-leave? true}]]]}))
```

`:bypass-leave?` skips this route's `:can-leave` for this one navigation and
nothing else. The destination's `:can-enter` still runs.

!!! warning "The browser's exits are not yours"

    A pending value is a fact inside your app; it cannot stop a tab close, an
    external link, or a reload. Pair the guard with a `beforeunload` listener
    that reads the *same* `:editor/can-leave?` sub — one dirty flag, two
    exits. The listener recipe is in the routing corpus. Routing deliberately
    does not wrap the browser's own dialog.

## Deep links, Back and Forward

There is no separate code path for "the user arrived by URL" or "the user
pressed Back". The URL is an input. A deep link is the first URL input at
boot, and Back/Forward are history inputs. Both run the same match → validate
→ guard → activate pipeline as a link click. The conduct that follows:

- A deep link onto a route with `:query-defaults` arrives with the defaults
  filled. Views read `(h/sub [:rf.route/query])` and never special-case
  "first load".
- Guards run at every entry: navigate, link, address bar, Back/Forward,
  initial load, SSR. The dirty-leave dialog parks a Back press exactly as it
  parks a link click.
- Back/Forward restore scroll by default (`:restore`). The focus recipe moves
  focus because the route id changed, and `preventScroll` keeps the two from
  fighting.
- A navigation does not cancel in-flight async work. A save still pending
  when the user leaves is a mutation with an instance status readable from
  anywhere. Its cache consequences land regardless of the current page
  ([Async resources](08-async-resources.md)). The guard protects unsaved
  local state; supersession protects the reply race.
- On the server, the same pipeline runs for the request URL, and the client
  hydrates without re-running it ([SSR and hydration](17-ssr-and-hydration.md)).

??? info "Coming from React Router?"

    Three instincts mislead here. There is no `<Link>` component:
    [`route-link`](glossary.md#route-link) is a plain function that returns an anchor with data on it.
    There is no `useBlocker` or `usePrompt`: the blocked attempt is app state
    you render. There is no `router.prefetch()` call: warming is an event.
    Everything you would reach into router context for is a subscription.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Rendering a link throws `:rf.error/routing-artefact-missing` | The core routing artefact is not loaded | `(:require [re-frame.routing])` at boot, before first render |
| A link full-page reloads | A hand-written `[:a {:href …}]` bypasses interception | Use [`route-link`](glossary.md#route-link), or the document-level click listener recipe from the routing corpus |
| `:rf.error/hicasso-malformed-navigate` at render | A hand-built or edited `::h/navigate` head | Don't write the head; [`route-link`](glossary.md#route-link) mints it |
| A [`route-link`](glossary.md#route-link) refuses your `:on-click` [intent](glossary.md#intent) vector | One click must not yield two semantic events | Wrap it: `[::h/prevent [:app/event]]` — cancel-and-replace |
| A link carrying `:prefetch` refuses at render | Only `:intent` is accepted; passivity must be visible | Remove the key, or spell `:prefetch :intent` |
| Leaving always blocks, and an error names the guard | The `:can-leave` sub returned a non-boolean — fail-closed | `:rf.error/can-leave-non-boolean`: return a strict `true`/`false` |
| Back lands at the top of a long page | Restore ran before the page had its height | Make the page's data blocking route `:resources`, or keep previous data on screen |
| Focus goes nowhere after navigating | Main region not keyed, or not focusable | `:key` by page identity, `:tab-index -1`, focus in the `:ref` |

## When not to use this module

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing artefact at all — there is nothing to integrate |
| In-memory UI steps that shouldn't touch the URL — wizard panes, non-linkable tabs | app-db state, or a machine |
| External links | A plain `[:a {:href …}]` — [`route-link`](glossary.md#route-link) is for the route table |
| Guarding one button, not a page's exits | The veto roster on that link, or ordinary app logic |
