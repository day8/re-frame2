# Errors

When a view throws during render, React does not automatically replace only
that view with an error message. Without an error boundary, React can unmount
the entire root and leave the user with a blank page.

Wrap independently useful regions with `h/error-boundary`:

```clojure
(ns app.todos
  (:require [re-frame.fresco :as h]))

(h/defview todo-page [_]
  [:main
   [todo-header {}]

   [h/error-boundary
    {:fallback [:p.oops "We couldn't show your todos."]}
    [todo-list {}]]

   [todo-footer {}]])
```

If `todo-list` throws while rendering, the fallback replaces that region.
The header and footer stay mounted.

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

Show detailed messages in development and user-safe copy in production. The
fallback renders under the same frame as the boundary, so event vectors in it
dispatch normally.

Keep fallbacks simple. A fallback that reads the same broken state or performs
heavy work can throw itself. That second failure is caught only by the next
boundary above it.

### Retry with `:reset-key`

A caught boundary stays in the failed state until its `:reset-key` changes.
The change clears the failure and remounts the children from scratch.

Keep the key in app-db and increment it when the user presses Retry. The next
section shows the full pattern.

### Report with `:on-error`

An event vector receives the thrown error as its final argument:

```clojure
:on-error [:todo/record-failure]
;; dispatched as [:todo/record-failure error]
```

The event dispatches into the boundary's frame. A plain function is called
with the error instead and dispatches nothing.

`:on-error` runs once per caught failure. StrictMode may run the failing render
more than once in development, but one React catch produces one report.

## Nested boundaries and retry

The nearest error boundary above the throw handles it:

```clojure
(ns app.todos
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]))

(rf/reg-sub :todo.ui/list-attempt
  (fn [db _query]
    (:todo.ui/list-attempt db 0)))

(rf/reg-event :todo/retry-list
  (fn [{:keys [db]} _event]
    {:db (update db :todo.ui/list-attempt (fnil inc 0))}))

(rf/reg-event :todo/record-failure
  (fn [{:keys [db]} [_ error]]
    {:db (update db :todo/failures (fnil conj []) (ex-message error))}))

(h/defview todo-page [_]
  [:main
   [todo-header {}]

   [h/error-boundary
    {:fallback [:p.oops "We couldn't show your todos."]}

    [todo-filters {}]

    [h/error-boundary
     {:fallback
      (fn [_error]
        [:div.oops
         [:p "The list failed to render."]
         [:button {:on-click [:todo/retry-list]} "Try again"]])
      :reset-key (h/sub [:todo.ui/list-attempt])
      :on-error  [:todo/record-failure]}
     [todo-list {}]]]])
```

If `todo-list` throws, the inner boundary catches it and shows its fallback.
The filters and header stay, and the outer boundary sees nothing.

The Try again button increments `:todo.ui/list-attempt`, so the reset key
changes and the list mounts from scratch. If it throws again, the boundary
catches the new failure.

A throw from `todo-filters`, which sits outside the inner boundary, reaches the
outer boundary instead.

## What an error boundary catches

The boundary follows React's error-boundary rules.

**Caught:** throws during render and throws from lifecycle or effect work in
the descendant React tree.

**Not caught:** work the browser invokes outside render, including event
handlers, timers, and promise continuations.

A re-frame2 event handler runs in the event pipeline. If it throws, the
pipeline reports `:rf.error/handler-exception` with the event and frame, and
the runtime keeps going. No view fallback renders.

A raw JavaScript callback that throws reaches the browser's error channel. The
error boundary does not see it, because nothing failed during React rendering.

## Expected failures are state

Use app-db values for failures you can name in advance: a 404, invalid
input, an unavailable resource, or an expected permission denial.

Do not throw to express ordinary control flow:

```clojure
;; Don't do this: a missing todo is an expected state.
(h/defview todo-detail [{:keys [id]}]
  (let [todo (h/sub [:todo/by-id id])]
    (when (nil? todo)
      (throw (ex-info "todo missing" {:id id})))
    [:h2 (:title todo)]))
```

Render the case explicitly:

```clojure
(h/defview todo-detail [{:keys [id]}]
  (if-let [todo (h/sub [:todo/by-id id])]
    [:h2 (:title todo)]
    [:p "That todo no longer exists."]))
```

The explicit version is easy to test, shows a precise message, and leaves the
error boundary for failures you did not plan for. Loading and failed statuses
for remote data are covered in [Async resources](08-async-resources.md).

## Place boundaries at useful recovery regions

A single boundary around the root turns every failure into a whole-page
fallback and may remove navigation along with the broken content. A boundary
around every small view creates noise without useful recovery.

Place a boundary around a region the user can continue without: a panel, a
tab body, a sidebar widget, or a route's main content inside a shell that
stays usable. Ask what should stay available when this region fails, and put
the boundary at the level that preserves it.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| One view throws and the whole page blanks | No boundary caught the render failure, so React unmounted the root | Wrap the independently recoverable region with `h/error-boundary` |
| An event-handler exception does not show the fallback | Event handlers run in the re-frame2 pipeline, not descendant React render | Inspect the `:rf.error/handler-exception` record; do not expect a view fallback |
| The console still shows the error although the fallback rendered | React logs every caught render error by default | Expected: the boundary caught it. Record it with `:on-error` |
| Fallback appears and never clears | There is no `:reset-key`, or its value never changes | Drive a generation value from app-db and change it on Retry |
| An intent in the fallback, or a vector `:on-error`, raises `:rf.error/fresco-intent-outside-boundary` | No frame is mounted above the boundary, so there is nowhere to dispatch the event. A vector `:on-error` is checked on the boundary's first paint, not when it catches | Mount the region under `h/frame-root` or `h/frame-provider`, or give `:on-error` a function, which needs no frame |
| The boundary raises `:rf.error/fresco-boundary-unknown-prop` | A prop other than `:fallback`, `:reset-key` or `:on-error`, usually a misspelling such as `:on-errors` | Fix the key; the boundary accepts only those three |
| The boundary raises `:rf.error/fresco-boundary-bad-on-error` | `:on-error` is a bare keyword or another non-callable value | Use an event vector such as `[:todo/record-failure]` or a function |
| A panel fallback throws and the larger page fallback appears | The fallback itself failed and the next outer boundary caught it | Keep fallbacks small and avoid re-reading the failed state |
| `:on-error` appears to fire twice in development | Two distinct failures occurred; StrictMode alone still produces one report per catch | Inspect the two error records and their causes |
| A server-render throw is not caught by the client boundary | Server rendering uses the server error channel; a client error boundary cannot handle server execution | Apply the surface's server policy and server error handling ([SSR and hydration](18-ssr-and-hydration.md)) |

## When not to use an error boundary

Do not use it:

- for an expected failure such as a 404, a validation error, or an empty result;
- around every small view that has no recovery of its own;
- as loading UI. Pending data is state; render it explicitly.
