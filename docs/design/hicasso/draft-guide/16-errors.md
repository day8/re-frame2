# Errors

When a view throws during render, React does not automatically replace only
that view with an error message. Without an error boundary, React can unmount
the entire root and leave the user with a blank page.

Wrap independently useful regions with `h/error-boundary`:

```clojure
(ns app.articles
  (:require [re-frame.hicasso :as h]))

(h/defview article-page [{:keys [id]}]
  [:main
   [site-header {}]

   [h/error-boundary
    {:fallback
     [:p.oops "We couldn't load this article."]}
    [article-body {:id id}]]

   [site-footer {}]])
```

If `article-body` throws while rendering, the fallback replaces that region.
The header and footer remain mounted.

`h/error-boundary` is the component that catches. A normal `defview` boundary
only defines an independently re-rendering view; it is not an error boundary.

## Boundary options

An error boundary accepts three props:

| Prop | Shape | Behaviour |
| --- | --- | --- |
| `:fallback` | Hiccup, or `(fn [error] hiccup)` | Replaces the children after a caught failure |
| `:reset-key` | Any value compared with `=` | When it changes, clear the caught failure and remount the children |
| `:on-error` | Event vector or plain function | Run once for each caught failure |

### Fallbacks

A fallback may be static Hiccup:

```clojure
{:fallback [:p.oops "This panel failed."]}
```

Or it may inspect the thrown value:

```clojure
{:fallback
 (fn [error]
   [:div.oops
    [:p "This panel failed."]
    [:pre (ex-message error)]])}
```

Use detailed messages in development and user-safe copy in production. Hiccup
returned by the fallback is rendered under the same frame as the boundary, so
its event intents work normally.

Keep fallbacks simple. A fallback that reads the same broken state or performs
heavy work can throw itself. That second failure is caught only by the next
boundary above it.

### Retry with `:reset-key`

A caught boundary remains in the failed state until its `:reset-key` changes.
The change clears the failure and **remounts** the children. It does not
re-render the surviving failed subtree.

Use an app-db counter or generation value when the user presses Retry.

### Report with `:on-error`

An event vector receives the thrown error as its final argument:

```clojure
:on-error [:diagnostics/record-failure]
```

The handler receives:

```clojure
[:diagnostics/record-failure error]
```

The event dispatches into the boundary's frame. A plain function is called
with the error and does not dispatch anything.

`on-error` runs once for each caught failure. Development StrictMode may run
the failing render more than once, but one React catch produces one report.

## Nested boundaries and retry

The nearest error boundary above the throw handles it:

```clojure
(ns app.reports
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :chart/attempt
  (fn [db _query]
    (:chart/attempt db 0)))

(rf/reg-event :chart/retry
  (fn [{:keys [db]} _event]
    {:db (update db :chart/attempt (fnil inc 0))}))

(rf/reg-event :diagnostics/record-failure
  (fn [{:keys [db]} [_ error]]
    {:db (update db
                 :diagnostics/failures
                 (fnil conj [])
                 (ex-message error))}))

(h/defview reports-page [_]
  [:main
   [report-header {}]

   [h/error-boundary
    {:fallback
     [:p.oops "We couldn't show this report."]}

    [report-summary {}]

    [h/error-boundary
     {:fallback
      (fn [_error]
        [:div.panel-oops
         [:p "The live chart failed."]
         [:button {:on-click [:chart/retry]}
          "Try again"]])
      :reset-key (h/sub [:chart/attempt])
      :on-error  [:diagnostics/record-failure]}
     [live-chart {}]]]])
```

If `live-chart` throws:

- the inner boundary catches it;
- the chart region shows its fallback;
- the report summary and header remain;
- the outer boundary does not report the failure.

The Retry button increments `:chart/attempt`. The reset key changes and the
chart mounts from scratch. If it throws again, the boundary catches the new
failure.

A throw from `report-summary`, which is outside the inner boundary, reaches the
outer boundary instead.

## What an error boundary catches

The boundary follows React's error-boundary rules.

**Caught:** throws during render and throws from lifecycle or effect work in
the descendant React tree.

**Not caught:** work the browser invokes outside render, including event
handlers, timers, and promise continuations.

A re-frame2 event handler runs in the event pipeline. If it throws, the
pipeline reports `:rf.error/handler-exception` with the event, frame, and
recovery data, then keeps the application runtime alive. It does not render a
view fallback.

A raw JavaScript callback that throws reaches the browser's error channel.
Again, the error boundary does not see it because no descendant failed during
React rendering or lifecycle.

## Expected failures are state

Use app-db status values for failures you can name in advance: a 404, invalid
input, an unavailable resource, or an expected permission denial.

Do not throw to express ordinary control flow:

```clojure
;; Don't: a missing article is an expected state.
(h/defview article-body [{:keys [id]}]
  (let [article (h/sub [:article/by-id id])]
    (when (nil? article)
      (throw (ex-info "article missing" {:id id})))
    [:article (:title article)]))
```

Render the status explicitly:

```clojure
(h/defview article-body [{:keys [id]}]
  (case (h/sub [:article/status id])
    :loading [loading-placeholder {}]
    :failed  [load-failed {:id id}]
    [:article
     (:title (h/sub [:article/by-id id]))]))
```

The explicit version is easy to test, can show a precise message, and reserves
the error boundary for failures the application did not plan for. Resource and
mutation statuses are covered in [Async resources](08-async-resources.md).

## Place boundaries at useful recovery regions

A single boundary around the root turns every failure into a whole-page
fallback and may remove navigation along with the broken content. A boundary
around every small view creates noise without useful recovery.

Place a boundary around a region the user can continue without:

- a dashboard panel;
- a tab body;
- a sidebar widget;
- a route's main content while the surrounding shell remains usable.

Ask what should stay available when this region fails. Put the boundary at the
level that preserves it.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| One view throws and the whole page blanks | No boundary caught the render failure, so React unmounted the root | Wrap the independently recoverable region with `h/error-boundary` |
| An event-handler exception does not show the fallback | Event handlers run in the re-frame2 pipeline, not descendant React render | Inspect the `:rf.error/handler-exception` record; do not expect a view fallback |
| Fallback appears and never clears | There is no `:reset-key`, or its value never changes | Drive a generation value from app-db and change it on Retry |
| Retry intent in the fallback raises `:rf.error/hicasso-intent-outside-boundary` | The fallback was rendered outside the mounted Hicasso/frame tree | Keep fallback Hiccup inside the boundary and use an ordinary event intent |
| A panel fallback throws and the larger page fallback appears | The fallback itself failed and the next outer boundary caught it | Keep fallbacks small and avoid re-reading the failed state |
| `:on-error` appears to fire twice in development | Two distinct failures occurred; StrictMode alone still produces one report per catch | Inspect the two error records and their causes |
| A server-render throw is not caught by the client boundary | Server rendering uses the server error channel; a client error boundary cannot handle server execution | Apply the surface's server policy and server error handling ([SSR and hydration](17-ssr-and-hydration.md)) |

## When not to use an error boundary

Do not use it:

- for an expected failure such as a 404, validation error, or empty result;
- around every small view without an independent recovery experience;
- as loading UI. Pending is state, not a render exception.
