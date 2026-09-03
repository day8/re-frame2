# Routing and navigation

The core routing artefact defines route registration, navigation events, and
route subscriptions. This page covers the Hicasso view side: route links,
prefetch, scroll and focus policy, and unsaved-change guards.

## Register routes

Register routes once during boot:

```clojure
(ns app.routes
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/home     {} "/")
(rf/reg-route :app/articles {} "/articles")
(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")
(rf/reg-route :app/profile
  {:params [:map [:username :string]]}
  "/profile/:username")
(rf/reg-route :app/inbox    {} "/inbox")
```

`h/route-link` ships on the door, so a view that renders links requires nothing
beyond it:

```clojure
(ns app.views.articles
  (:require [re-frame.hicasso :as h]))
```

## Boot a routed application

Routing costs a dependency and a frame option, and a mount line says neither out
loud: `h/mount!`'s config carries no routing key at all.

```clojure
;; deps.edn — beside the Hicasso coordinate
{:deps {day8/re-frame2-hicasso {:local/root "../re-frame2/implementation/hicasso"}
        day8/re-frame2-routing {:local/root "../re-frame2/implementation/routing"}}}
```

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]                  ;; loads the routing artefact
            [re-frame.hicasso.substrate :as substrate]
            [re-frame.hicasso :as h]
            [app.routes]                        ;; the reg-route table above
            [app.views :as views]))

(defonce !root (atom nil))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (rf/make-frame {:id             :app/main
                  :url-bound?     true          ;; this frame owns the browser URL
                  :initial-events [[:app/initialise]]})
  (reset! !root
          (h/mount! (js/document.getElementById "app")
                    {:frame :app/main}
                    [views/app-root]))
  nil)
```

Three things in that shape are load-bearing:

- **`re-frame.routing` is a separate coordinate, and Hicasso does not bring it
  in.** Require it before anything renders a route link, or `h/route-link` raises
  `:rf.error/routing-artefact-missing`.
- **A frame owns the browser URL only by carrying `:url-bound? true`.** There is
  no default and nothing infers it; the declaration *is* the wiring, and creating
  the frame installs the URL listener and syncs the current URL into the route
  slice in one step. Without it `route-link` still renders and navigation still
  updates that frame's own route state — the address bar simply never moves, and
  a refresh loses the page.
- **The frame is made before the mount, because the mount cannot carry
  `:url-bound?`.** `h/mount!`'s config is closed at `:frame`, `:initial-events`
  and `:identifier-prefix`, and any other key is ignored without complaint. The
  mount then joins the live frame, so the seed belongs to `rf/make-frame` — see
  [A frame that needs more than a
  seed](00-installation.md#a-frame-that-needs-more-than-a-seed).

Exactly one frame may carry `:url-bound? true`. A second raises
`:rf.error/duplicate-url-binding` and the first claimant keeps the URL. Frames
without it — story variants, devcards, per-test fixtures — route independently
and never touch the address bar.

## Render an application route link

Call `h/route-link` as a plain helper. Name a registered route and its params
rather than constructing a URL:

```clojure
(h/defview article-card [{:keys [id]}]
  (let [{:keys [title author]}
        (h/sub [:article/summary id])]
    [:article.card
     [:h2
      (h/route-link {:to :app/article
                     :params {:id id}}
        title)]
     [:span.byline
      "by "
      (h/route-link {:to     :app/profile
                     :params {:username author}
                     :class  "author"}
        author)]]))
```

The result is a real anchor. The router builds `:href`, so hover preview,
copy-link, middle-click, and browser link menus continue to work. The helper
inlines into its caller; it does not create another Hicasso view or
subscription.

The generated Hiccup carries the click decision as data at `:on-click` — a
vector headed by a keyword the implementation owns, wrapping a map:

```clojure
[:a {:href     "/profile/jane"
     :class    "author"
     :on-click [navigate-head                       ; route-link's own head
                {:frame   :rf/default
                 :payload [:rf.route/url-requested
                           {:url    "/profile/jane"
                            :to     :app/profile
                            :params {:username "jane"}}]
                 :native? false
                 :veto    nil}]}
 "jane"]
```

This form remains comparable with `=` and visible to structural tests, which
read it through `re-frame.hicasso.impl.intent/navigate-head?`. It is not an
authoring spelling: `route-link` owns its shape, and there is nothing to write.

Click conduct is browser-compatible:

- a plain left-click prevents the browser default and dispatches the routing
  event to the frame captured during rendering
- modifier and auxiliary clicks remain browser operations, such as opening a
  new tab
- anchors with `:target` or `:download` navigate natively

The generated map carries `:frame`, `:payload`, `:native?`, and `:veto`;
`route-link` creates it, and application code does not author it.
If the core routing artefact was not loaded, rendering raises
`:rf.error/routing-artefact-missing` and names the requested route instead of
producing a dead anchor. Ordinary classes, data attributes, and ARIA props pass
through.

## Mark the active link

`route-link` does not decide which link is active. Read the current route once
where the navigation renders and pass the result into an inline helper:

```clojure
(h/defview site-nav []
  (let [current (h/sub [:rf.route/id])
        nav     (fn [to label]
                  (h/route-link
                   {:to           to
                    :class        (when (= to current) "is-active")
                    :aria-current (when (= to current) "page")}
                   label))]
    [:nav
     (nav :app/home "Home")
     (nav :app/articles "Articles")]))
```

Use `:aria-current "page"` as the semantic state and a class for styling.

## Veto one link

A specific link may replace its navigation with another action, such as asking
whether to discard a local scratch pane. Pass one of the supported veto forms
as `:on-click`: `nil`, `[::h/prevent INTENT]`, an `h/event`, or a plain
function.

```clojure
(h/route-link
 {:to       :app/inbox
  :on-click (when draft-open?
              [::h/prevent [:composer/confirm-discard]])}
 "Inbox")
```

The prevent wrapper cancels navigation and dispatches the inner event. A bare
event vector is rejected because one click must not produce both an unrelated
application event and the routing event.

Use the route-level dirty-leave guard for unsaved work that must protect every
exit. A link veto covers only that link.

## Prefetch on user intent

Warming a destination on hover, focus, or touch is an event,
`[:rf.route/prefetch address]`, dispatched from the intent that signals the
interest. `h/route-link` does not read a `:prefetch` key — the link installs
none of routing's prefetch handlers — because that event needs nothing the link
does not already give you:

```clojure
(h/route-link {:to             :app/article
               :params         {:id "intro"}
               :on-mouse-enter [:rf.route/prefetch {:to     :app/article
                                                    :params {:id "intro"}}]}
  "Read more")
```

The address takes `:to`, `:params`, `:query` and `:fragment` and nothing else.
`:on-focus` carries the same vector for keyboard intent, and application code
may dispatch the event directly.

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

For example, pagination that should keep the current viewport can dispatch:

```clojure
(rf/dispatch
 [:rf.route/navigate
  {:query-merge {:page 2}
   :scroll      :preserve}])
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

(h/defview app-root []
  (let [route (h/sub [:rf.route/id])]
    [:div.app
     [site-nav]
     [:main {:key       route
             :tab-index -1
             :ref       focus-page}
      [h/error-boundary
       {:fallback [:p.oops "This page could not be shown."]}
       (case route
         :app/home    [home-page]
         :app/article [article-page]
         [not-found-page])]]]))
```

The key remounts `<main>` when page identity changes, causing the ref to run.
`:tab-index -1` allows programmatic focus without adding the region to normal
tab order. `preventScroll` lets the router's scroll policy remain authoritative.

The boundary sits inside `<main>` on purpose. Every Hicasso refusal is a throw,
and React unmounts a root whose tree throws with nothing above it to catch, so a
single refused head anywhere in a page takes the whole application to a blank
screen with the error only in the console. Catching at the page keeps the
navigation usable, which is the region rule
[Errors](17-errors.md#place-boundaries-at-useful-recovery-regions) states and the
reason not to wrap the root instead — that would turn every failure into a
whole-page fallback and remove `site-nav` along with the broken content. It needs
no `:reset-key`: the `:key` above already remounts `<main>` on a route change, so
navigating away is the retry.

Query-only or fragment-only changes keep the same route id and therefore do
not move focus. If article 7 and article 9 count as separate pages, include the
route params in the key.

Modal and popover focus is owned by the overlays module, not this recipe.

## Guard unsaved changes

A dirty-leave guard is ordinary state. Register a subscription that returns a
strict boolean and attach it to the route:

```clojure
(rf/reg-sub :editor/can-leave?
  (fn [db _]
    (= (get-in db [:editor :draft])
       (get-in db [:editor :saved]))))

(rf/reg-route :app/article-editor
  {:params    [:map [:id :string]]
   :can-leave [:editor/can-leave?]}
  "/articles/:id/edit")
```

When the guard returns `false`, the route and URL remain unchanged. The
attempt is stored in `[:rf/pending-navigation]`, which a view can render:

```clojure
(h/defview leave-guard-dialog []
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
(rf/reg-event :editor/save-and-close
  (fn [{:keys [db]} _]
    {:db (assoc-in db
                   [:editor :saved]
                   (get-in db [:editor :draft]))
     :fx [[:dispatch
           [:rf.route/navigate
            {:to            :app/article
             :params        {:id (get-in db [:editor :id])}
             :bypass-leave? true}]]]}))
```

`:bypass-leave?` skips this route's `:can-leave` once. The destination's
`:can-enter` still runs.

!!! warning "Application routing cannot block browser exits"
    A route guard cannot stop closing the tab, reloading, or following an
    external link. Install a `beforeunload` listener that reads the same
    `:editor/can-leave?` fact. Keep one dirty calculation and expose it to the
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

    A page's relative URLs resolve against the *document* URL, and under the
    default history strategy the document URL **is** the route. So a host page
    carrying `href="css/style.css"` is correct while every route is `/`, and
    silently wrong the moment somebody deep-links or refreshes on
    `/articles/intro`, where it resolves to `/articles/css/style.css` and 404s.
    Nothing in the application fails: the script tag is usually absolute already,
    so the app boots, routes and behaves — with no stylesheet and no favicon.

    Give every asset in the host page an absolute path, as [chapter 00's
    `index.html`](00-installation.md#add-the-dependencies) does for
    `/js/main.js`, or add one `<base href="/">` to `<head>`. Under a sub-path
    deployment that becomes `<base href="/my-app/">`, with
    `rf.routing/with-base-path` wrapped around the frame's `:url-strategy` so
    the two agree.

??? info "For readers coming from React Router"
    `route-link` is a plain function returning an anchor, not a component with
    private router context. A blocked transition is app state, not a blocker
    hook. Prefetch is an event, and router facts are subscriptions.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Rendering a route link raises `:rf.error/routing-artefact-missing` | The core routing artefact was not required before rendering | Require `re-frame.routing` during boot |
| An in-app link performs a full page load | A hand-written anchor bypassed route interception | Use `route-link` or the documented document-level routing listener |
| Links change the page but the address bar never moves, and a refresh loses the route | No frame carries `:url-bound? true`, so nothing owns the browser URL | Declare it on the frame — [Boot a routed application](#boot-a-routed-application) |
| The page loads and behaves but is unstyled after a deep link or a refresh | Relative asset paths in the host page resolve against the current route | Make host-page asset paths absolute, or add `<base href="/">` |
| `route-link` rejects a bare `:on-click` vector | The click would produce two semantic events | Use `[::h/prevent [:app/event]]`, `h/event`, or a plain function according to the intended veto |
| A link carrying `:prefetch` warms nothing | `route-link` installs none of routing's prefetch handlers | Dispatch `[:rf.route/prefetch address]` from `:on-mouse-enter` |
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
