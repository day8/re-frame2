# Routing and navigation

The core routing artefact defines route registration, navigation events, and
route subscriptions. This page covers the Fresco view side: route links,
prefetch, scroll and focus policy, and unsaved-change guards.

## Register routes

Register routes once during boot:

```clojure
(ns app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/all    {} "/")
(rf/reg-route :app/active {} "/active")
(rf/reg-route :app/done   {} "/done")
(rf/reg-route :app/todo
  {:params [:map [:id :string]]}
  "/todos/:id")
```

The three filter routes put the todo list's `:showing` filter in the URL, so a
subscription can derive it from the current route id (cf.
`examples/core/todomvc`). `:app/todo` is a detail page for one todo.

`h/route-link` is part of `re-frame.fresco`, so a view namespace that renders
links requires nothing more:

```clojure
(ns app.views.todos
  (:require [re-frame.fresco :as h]))
```

## Boot a routed application

Routing needs a dependency and a frame option. The frame option goes on
`h/frame-root` with every other `rf/make-frame` option:

```clojure
;; deps.edn — beside the Fresco coordinate
{:deps {day8/re-frame2-fresco {:local/root "../re-frame2/implementation/fresco"}
        day8/re-frame2-routing {:local/root "../re-frame2/implementation/routing"}}}
```

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]                  ;; loads the routing artefact
            [re-frame.fresco.substrate :as substrate]
            [re-frame.fresco :as h]
            [app.routes]                        ;; the reg-route table above
            [app.views :as views]))

(defonce app-root (h/client-root))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (h/render! app-root
             [h/frame-root
              {:id             :app
               :url-bound?     true              ;; this frame owns the browser URL
               :initial-events [[:todo/initialise]]}
              [views/app-shell]]
             (js/document.getElementById "app"))
  nil)
```

Three things in that boot matter:

- **`re-frame.routing` is a separate dependency that Fresco does not bring in.**
  Require it before anything renders a route link, or `h/route-link` raises
  `:rf.error/routing-artefact-missing`.
- **A frame owns the browser URL only if it carries `:url-bound? true`.**
  Nothing infers it. Creating that frame installs the URL listener and syncs the
  current URL into the route state. Without it `route-link` still renders and
  navigation still updates the frame's route state, but the address bar never
  moves and a refresh loses the page.
- **`:url-bound?` goes on `h/frame-root`, not in `h/render!`'s options.**
  `h/render!` accepts only `:hydrate?` and `:identifier-prefix`; frame options
  belong on the frame-root with the rest of the `rf/make-frame` map, as in [A
  frame that needs more than a
  seed](00-installation.md#a-frame-that-needs-more-than-a-seed).

Exactly one frame may carry `:url-bound? true`. A second is still created, but
the runtime reports `:rf.error/duplicate-url-binding` naming both frames (an
error record, not a throw); the first claimant keeps the URL. Frames
without it — story variants, devcards, per-test fixtures — route independently
and never touch the address bar.

## Render an application route link

Call `h/route-link` as a plain helper. Name a registered route and its params
rather than constructing a URL:

```clojure
(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li {:class (when done? "done")}
     (h/route-link {:to     :app/todo
                    :params {:id (str id)}
                    :class  "title"}
       title)]))
```

`:query` and `:fragment` complete the address, so
`(h/route-link {:to :app/all :query {:q "milk"}} "Milk")` links to `/?q=milk`.

The result is a real anchor. The router builds `:href`, so hover preview,
copy-link, middle-click, and browser link menus continue to work. The helper
inlines into its caller; it does not create another Fresco view or
subscription.

`route-link` puts the click decision in the anchor's props as data, so two
renders of the same link compare equal with `=` and a structural test can
inspect where a click goes ([What a route link
renders](#what-a-route-link-renders) shows the form).

Click conduct is browser-compatible:

- a plain left-click prevents the browser default and dispatches the routing
  event to the frame captured during rendering
- modifier and auxiliary clicks remain browser operations, such as opening a
  new tab
- anchors with `:target` or `:download` navigate natively

A link takes the navigation policy `:rf.route/navigate` takes. `:replace? true`
replaces the current history entry instead of adding one, `:scroll` sets this
navigation's scroll policy, and `:bypass-leave? true` skips the current route's
`:can-leave` once. These keys ride the click's navigation payload and never
reach the anchor.

If the routing artefact was not loaded, rendering raises
`:rf.error/routing-artefact-missing` and names the requested route instead of
producing a dead anchor. Ordinary classes, data attributes, and ARIA props pass
through.

## Mark the active link

`route-link` does not decide which link is active. Read the current route once
where the navigation renders and pass the result into an inline helper:

```clojure
(h/defview filter-nav [_]
  (let [current (h/sub [:rf.route/id])
        nav     (fn [to label]
                  (h/route-link
                   {:to           to
                    :class        (when (= to current) "is-active")
                    :aria-current (when (= to current) "page")}
                   label))]
    [:nav.filters
     (nav :app/all "All")
     (nav :app/active "Active")
     (nav :app/done "Done")]))
```

Use `:aria-current "page"` as the semantic state and a class for styling.

## Veto one link

A specific link may replace its navigation with another action, such as asking
whether to discard a half-typed new todo. Pass one of the supported veto forms
as `:on-click`: `nil`, `[::h/prevent INTENT]`, an `h/event`, or a plain
function.

```clojure
(h/route-link
 {:to       :app/all
  :on-click (when draft-open?
              [::h/prevent [:todo.ui/confirm-discard]])}
 "All")
```

The prevent wrapper cancels navigation and dispatches the inner event. A bare
event vector is rejected because one click must not produce both an unrelated
application event and the routing event.

An `h/event` or plain function is the imperative veto: it receives the click
first and cancels the navigation only by calling `.preventDefault`. Its return
value is not dispatched, so use `[::h/prevent …]` to replace the navigation
with an event.

Use the route-level dirty-leave guard for unsaved work that must protect every
exit. A link veto covers only that link.

## Prefetch on user intent

Warming a destination on hover, focus, or touch is an event,
`[:rf.route/prefetch address]`, dispatched from the intent that signals the
interest. Write `:prefetch :intent` and the link fills the three intent
positions with that event, built from the address the link already carries:

```clojure
(h/route-link {:to :app/todo :params {:id "1"} :prefetch :intent}
  "Details")
```

renders

```clojure
[:a {:href           "/todos/1"
     :on-click       [...]                       ;; the navigation, as always
     :on-mouse-enter [:rf.route/prefetch {:to :app/todo :params {:id "1"}}]
     :on-focus       [:rf.route/prefetch {:to :app/todo :params {:id "1"}}]
     :on-touch-start [:rf.route/prefetch {:to :app/todo :params {:id "1"}}]}
 "Details"]
```

`:intent` is the only accepted value; to opt out, omit the key. A link that
needs one of those three positions for something else drops `:prefetch` and
writes the prefetch vector itself ([Write the prefetch
yourself](#write-the-prefetch-yourself)).

Prefetch does not navigate. It does not change the URL, run guards, apply
scroll/focus policy, or block activation. A later click uses ordinary resource
deduplication to reuse work already in flight. An unused prefetch remains
eligible for resource garbage collection.

Prefetch is not authorization. It may warm a destination that `:can-enter`
later refuses; the real navigation still evaluates its guards.

## Scroll policy

Scroll behaviour belongs to route or navigation data:

| Policy | Behaviour | Normal use |
| --- | --- | --- |
| `:top` | scroll to the top on entry | forward navigation |
| `:restore` | restore the saved position | Back/Forward |
| `:preserve` | leave the viewport unchanged | in-place query or filter changes |

For example, a pagination button that should keep the current viewport can
dispatch the navigation as an intent:

```clojure
[:button
 {:on-click [:rf.route/navigate {:query-merge {:page 2}
                                 :scroll      :preserve}]}
 "Next page"]
```

Restoration requires the destination page to have its real height. If
Back/Forward activates a long page while its list is still absent, restore may
run against a short document and land at the top. Declare blocking route
resources or retain previous data until the new page is ready.

## Move focus after a page change

Changing the route does not automatically move keyboard or screen-reader
focus. Key the main region by page identity, make it programmatically
focusable, and focus it after commit:

```clojure
(defn- focus-page [node]
  (when node
    (.focus node #js {:preventScroll true})))

(h/defview app-shell [_]
  (let [route (h/sub [:rf.route/id])]
    [:div.app
     [filter-nav]
     [:main {:key       route
             :tab-index -1
             :ref       focus-page}
      [h/error-boundary
       {:fallback [:p.oops "This page could not be shown."]}
       (case route
         (:app/all :app/active :app/done) [todo-page]
         :app/todo                        [todo-detail-page]
         [not-found-page])]]]))
```

The key remounts `<main>` when page identity changes, causing the ref to run.
`:tab-index -1` allows programmatic focus without adding the region to normal
tab order. `preventScroll` lets the router's scroll policy remain authoritative.

The error boundary sits inside `<main>` so that a page that throws shows the
fallback while `filter-nav` stays usable; wrapping the root instead would replace
the whole application, navigation included
([Errors](17-errors.md#place-boundaries-at-useful-recovery-regions) explains
where boundaries belong). It needs no `:reset-key`: the `:key` above already
remounts `<main>` on a route change, so navigating away is the retry.

Query-only or fragment-only changes keep the same route id and therefore do
not move focus. If todo 7 and todo 9 count as separate pages, include the
route params in the key.

Modal and popover focus is handled by the overlays module ([Overlays and focus](13-overlays-and-focus.md)).

## Guard unsaved changes

A dirty-leave guard is ordinary state. Register a subscription that returns a
strict boolean and attach it to the route:

```clojure
(rf/reg-sub :todo.editor/can-leave?
  (fn [db _]
    (= (get-in db [:todo.editor :draft])
       (get-in db [:todo.editor :baseline]))))

(rf/reg-route :app/todo-edit
  {:params    [:map [:id :string]]
   :can-leave [:todo.editor/can-leave?]}
  "/todos/:id/edit")
```

When the guard returns `false`, the route and URL remain unchanged, and the
blocked attempt is readable through the `:rf/pending-navigation` subscription,
which a view can render:

```clojure
(h/defview leave-guard-dialog [_]
  (when-let [pending (h/sub [:rf/pending-navigation])]
    [:div.modal {:role "alertdialog"
                 :aria-modal true}
     [:p "You have unsaved changes. Leave anyway?"]
     [:button
      {:on-click [:rf.route/cancel (:id pending)]}
      "Stay"]
     [:button
      {:on-click [:rf.route/continue (:id pending)]}
      "Discard and leave"]]))
```

Mount the view once near the root. `:rf.route/continue` replays the original
destination, replace flag, and scroll policy. `:rf.route/cancel` drops the
attempt. Both include the pending id, so a stale click after resolution is a
no-op.

A real application should render this state through the modal overlay so focus
is trapped and restored.

After a successful save, navigate with a one-shot leave bypass:

```clojure
(rf/reg-event :todo.editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db
                   [:todo.editor :baseline]
                   (get-in db [:todo.editor :draft]))
     :fx [[:dispatch
           [:rf.route/navigate
            {:to            :app/todo
             :params        {:id (str (get-in db [:todo.editor :draft :id]))}
             :bypass-leave? true}]]]}))
```

`:bypass-leave?` skips this route's `:can-leave` once. The destination's
`:can-enter` still runs.

!!! warning "Application routing cannot block browser exits"
    A route guard cannot stop closing the tab, reloading, or following an
    external link. Install a `beforeunload` listener that reads the same
    `:todo.editor/can-leave?` fact. Keep one dirty calculation and expose it to the
    two exit mechanisms; do not maintain separate flags.

## Deep links, Back, and Forward

Initial URLs and browser history inputs use the same match, validation, guard,
and activation pipeline as route links:

- Query defaults apply on a deep link before views read
  `[:rf.route/query]`.
- Entry and leave guards run for links, dispatched navigation, address-bar
  input, Back/Forward, initial load, and SSR.
- Back/Forward use `:restore` by default. The focus recipe may also run; its
  `preventScroll` option prevents focus from overriding restoration.
- Navigation does not automatically cancel unrelated async work. A pending
  mutation remains readable and its cache effects may land after the user
  leaves. Route guards protect local state; mutation supersession protects
  reply races.
- The server runs the same routing pipeline for the request URL. Hydration
  adopts that result rather than navigating again.

!!! warning "A route deeper than one segment moves what relative URLs resolve against"

    A page's relative URLs resolve against the document URL, which under the
    default history strategy is the route. A host page carrying
    `href="css/style.css"` works at `/`, but after a deep link or a refresh on
    `/todos/1` it requests `/todos/css/style.css` and gets a 404, so the app
    boots and routes with no stylesheet or favicon.

    Give every asset in the host page an absolute path, as [chapter 00's
    `index.html`](00-installation.md#add-the-dependencies) does for
    `/js/main.js`, or add `<base href="/">` to `<head>`. Under a sub-path
    deployment use `<base href="/my-app/">` and wrap the frame's
    `:url-strategy` in `re-frame.routing/with-base-path` so the two agree.

??? info "For readers coming from React Router"
    `route-link` is a plain function returning an anchor, not a component with
    private router context. A blocked transition is app state, not a blocker
    hook. Prefetch is an event, and router facts are subscriptions.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Rendering a route link raises `:rf.error/routing-artefact-missing` | The core routing artefact was not required before rendering | Require `re-frame.routing` during boot |
| Rendering a route link raises `:rf.error/no-such-route` or `:rf.error/route-url-validation` | `:to` is not a registered route, or, with the schemas artefact loaded, `:params` or `:query` fail the route's schema (a number where the route expects a string, say) | Fix the address; the [routing guide](../../routing/concepts.md) covers route schemas |
| An in-app link performs a full page load | A hand-written anchor bypassed route interception | Use `route-link`, or a document-level click listener that dispatches `:rf.route/url-requested` ([Linking from views](../../routing/concepts.md#linking-from-views)) |
| Links change the page but the address bar never moves, and a refresh loses the route | No frame carries `:url-bound? true`, so nothing owns the browser URL | Declare it on the frame — [Boot a routed application](#boot-a-routed-application) |
| The page loads and behaves but is unstyled after a deep link or a refresh | Relative asset paths in the host page resolve against the current route | Make host-page asset paths absolute, or add `<base href="/">` |
| `route-link` raises `:rf.error/fresco-route-link-bad-on-click` for a bare `:on-click` vector | The click would produce two application events | Use `[::h/prevent [:app/event]]`, `h/event`, or a plain function according to the intended veto |
| A link raises `:rf.error/route-link-bad-prefetch` at render | `:prefetch` carries a value other than `:intent` (`true`, `nil`, or another router's mode such as `:render`) | Write `:prefetch :intent`, or omit the key |
| A link carrying `:prefetch :intent` raises `:rf.error/fresco-route-link-claimed-intent-position` | The link also supplies `:on-mouse-enter`, `:on-focus` or `:on-touch-start`, and `:prefetch` claims all three | Drop `:prefetch` and dispatch `[:rf.route/prefetch address]` by hand from the positions you are not otherwise using |
| Every attempt to leave is rejected and the guard is named | `:rf.error/can-leave-non-boolean` | Return strict `true` or `false` from the guard subscription |
| Back/Forward restores to the top | Scroll restoration ran before content restored page height | Block activation on required resources or keep previous content visible |
| Focus stays on the old navigation link | Main region was not keyed/focusable or its ref did not run | Key by page identity, add `:tab-index -1`, and focus from the callback ref |
| A tab close ignores the dirty guard | Browser exits are outside application routing | Add `beforeunload` using the same can-leave state |

## When not to use the routing integration

| Situation | Prefer |
| --- | --- |
| A single-screen application with no shareable URL state | No routing artefact |
| Wizard steps or temporary tabs that should not change the URL | app-db state or a state machine |
| External destinations | A plain anchor |
| Guarding one control rather than every page exit | A link veto or ordinary application event logic |

## Advanced

### What a route link renders

The generated Hiccup carries the click decision as data at `:on-click`, a
vector headed by an internal keyword and wrapping a map:

```clojure
[:a {:href     "/todos/1"
     :class    "title"
     :on-click [navigate-head                       ; route-link's own head
                {:frame   :app
                 :payload [:rf.route/url-requested {:url "/todos/1"}]
                 :native? false
                 :veto    nil}]}
 "Buy milk"]
```

You never write this form yourself; `route-link` creates it.

### Write the prefetch yourself

`:prefetch :intent` abbreviates a form you can always write yourself, and that
longer form is the answer whenever a position must carry something else:

```clojure
(h/route-link {:to             :app/todo
               :params         {:id "1"}
               :on-mouse-enter [:rf.route/prefetch {:to     :app/todo
                                                    :params {:id "1"}}]}
  "Details")
```

The address takes `:to`, `:params`, `:query` and `:fragment` and nothing else
(`:fragment` is dropped from the prefetch — a fragment is never a resource
input). Application code may dispatch the event directly.

Do not write both. `:prefetch :intent` claims `:on-mouse-enter`, `:on-focus`
and `:on-touch-start`, and a value of your own at any of them raises
`:rf.error/fresco-route-link-claimed-intent-position` at render, because a
position carries one intent and a half-applied warm-up would prefetch from only
some positions. Choose the sugar or the explicit vectors per link.
