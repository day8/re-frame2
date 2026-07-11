# Resources and asynchronous UI

## Loading is explicit

re-frame2 UI does not throw promises from subscription reads. A resource view model is ordinary inspectable state:

```clojure
{:status :idle | :loading | :loaded | :error
 :data ...
 :error ...
 :refresh-error ...
 :loading? ...
 :fetching? ...
 :stale? ...
 :has-data? ...}
```

Views branch on it. Xray, SSR, tests, machines, and sibling views can observe the same fact.

## Three separate operations

1. Register a resource definition at boot.
2. Cause work through a route, event, or machine command such as `:rf.resource/ensure`.
3. Read state passively through `ui/sub`.

Do not combine them mentally. A read never starts work.

## Read a resource

```clojure
(defn article-descriptor [slug]
  {:resource :article/by-slug
   :scope {:from-db :session/current}
   :params {:slug slug}})

(ui/defview article-page [{:keys [slug]}]
  (let [descriptor (article-descriptor slug)
        state (ui/sub [:rf/resource descriptor])]
    (case (:status state)
      :idle    [:p "Article has not been requested"]
      :loading [article-skeleton]
      :error   [article-error {:error (:error state)}]
      :loaded  [article-body {:article (:data state)}]
      [article-skeleton])))
```

Scope is part of identity. Supply the same resolved scope/params used by the owner that ensured the resource; re-frame2 fails closed on unresolved scope rather than silently reading a different cache entry.

## Prefer route ownership for page data

When navigation determines the data, declare it on the route. The route plan ensures it before/with navigation, owns its blocking status, releases the route owner on exit, and gives SSR a deterministic loader plan.

The page view remains a passive projection. It should not need an effect or `ui/lease` merely because it displays route data.

## Event or machine ownership for workflow

When a user action or state machine transition causes the read:

```clojure
{:fx [[:dispatch
       [:rf.resource/ensure
        {:resource :report/by-id
         :scope scope
         :params {:id report-id}
         :owner [:workflow workflow-id]
         :cause [::report-requested report-id]
         :reply-to [::report-settled workflow-id]}]]]}
```

The exact resource event contract is owned by the resources artefact. The important UI rule is that causal work remains in the event/machine layer. Use the optional completion continuation when workflow must react to settlement; do not watch a subscription from an effect just to dispatch another event.

## View lifetime lease

Some resources genuinely live while a widget is mounted, such as a polled operations tile:

```clojure
(ui/defview service-health [{:keys [service-id]}]
  (let [descriptor {:resource :service/health
                    :scope :rf.scope/global
                    :params {:service-id service-id}}]
    (ui/lease descriptor {:cause [:dashboard-tile service-id]})
    (let [state (ui/sub [:rf/resource descriptor])]
      [health-tile {:state state}])))
```

`ui/lease`:

- declares liveness during render but causes no work then;
- ensures after the render commits;
- owns a distinct stable lease per lexical site;
- releases on site removal, target change, or unmount;
- is idempotent under Strict Mode effect replay;
- does nothing on JVM SSR.

It returns no data. The read remains explicit and passive.

## Conditional lease

```clojure
(ui/defview live-preview [{:keys [enabled? document-id]}]
  (let [descriptor {:resource :document/live-preview
                    :scope :rf.scope/global
                    :params {:id document-id}}]
    (when enabled?
      (ui/lease descriptor))
    (if enabled?
      [preview-state {:state (ui/sub [:rf/resource descriptor])}]
      [:p "Preview paused"])))
```

Only the committed enabled branch owns the lease and subscription. An abandoned render cannot start or retain the resource.

Each lease site may run at most once per render. For a dynamic set of live items, give each item a keyed child view or manage the owner set in an event/route subsystem.

## Loaded data with background refresh

Do not replace useful content with a blank skeleton merely because a refresh is running:

```clojure
(ui/defview article-state [{:keys [state]}]
  (cond
    (and (:has-data? state) (:fetching? state))
    [:<>
     [article-body {:article (:data state)}]
     [:span.refreshing "Refreshing…"]]

    (:has-data? state)
    [:<>
     [article-body {:article (:data state)}]
     (when-let [error (:refresh-error state)]
       [refresh-warning {:error error}])]

    (:loading? state)
    [article-skeleton]

    (:error state)
    [article-error {:error (:error state)}]

    :else
    [:p "Not requested"]))
```

The resource model distinguishes first-load error from refresh error and previous data. Render those states intentionally.

## Retry and refetch

Buttons dispatch resource/application events as data:

```clojure
[:button
 {:on-click [::article-retry descriptor]}
 "Retry"]
```

The application handler returns the canonical resource refetch effect/event. This keeps UI intent domain-shaped and centralizes policy/cause/owner data.

## Polling

Polling belongs to the resource registration/policy. A live owner keeps it active; releasing the final owner stops it according to the resource contract.

Do not build a `setInterval` effect that dispatches refetch from each widget. That duplicates owner, visibility, dedupe, stale, abort, and cleanup logic the resource runtime already has.

## Infinite feeds

Read the whole feed view model through its canonical resource query. “Load more” is a user event/command, not a new view subscription or route-plan data waterfall.

Rows should be keyed by entity/page identity. Preserve existing loaded pages while a next page is fetching, and render the separate page error channel rather than treating it as first-load failure.

## No Suspense for resource state

Suspense can be used for lazy JavaScript modules or a foreign React protocol, but re-frame2 resources do not suspend the view. Explicit status wins because it is:

- replayable;
- testable without React;
- visible in Xray;
- coordinated across siblings/routes/machines;
- deterministic on JVM SSR;
- compatible with the external-store contract.

## SSR

On the server:

1. create a per-request frame;
2. run server init/loaders/route resource plan;
3. wait according to the SSR/blocking policy;
4. render the view from populated resource state;
5. project allowed resource state into the hydration payload;
6. destroy the request frame after response/stream completion.

`ui/lease` effects do not run on the server. After client hydration commits, live view owners attach without invalidating a still-fresh cache merely because the component mounted.

## Debugging resources

From a resource-rendering view, Xray should show:

- descriptor, resolved scope, and params summary;
- active owners and this view's lease site if present;
- status, freshness, request/work generation, and stale suppression;
- route/event/machine cause;
- resource subscription → ViewCell render link;
- retry/refetch event site;
- redaction/large-value markers instead of unsafe raw data.

A permanent `:idle` view often means no owner caused the work. A permanent wrong-scope `:idle` should be a loud scope mismatch, not a mystery skeleton.

## Common mistakes

| Mistake | Why it fails | Better shape |
|---|---|---|
| Fetch in render/`ui/sub` | Render can repeat/abort; causality hidden | Route/event/machine ensure. |
| `useEffect` watch resource then dispatch completion | Per-observer duplicate workflow | `:reply-to` on causal command. |
| Lease route-owned page data in every view | Duplicate/unclear ownership | Route owner. |
| Poll with component timer | Reimplements dedupe/visibility/cleanup | Resource polling policy + owner. |
| Hide loading in local state | Not SSR/replay/tool visible | Resource view model. |
| Omit/mismatch scope | Reads different entry or fails | Use canonical descriptor/scope resolver. |
| Clear data during refresh | Unnecessary UI instability | Keep data; show fetching/refresh-error. |
