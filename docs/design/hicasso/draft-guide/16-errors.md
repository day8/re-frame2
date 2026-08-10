# Errors

A view body throws: `nil` where a map was expected, a key that moved, a shape
that last week's code cannot handle. React's default answer is not a red box
around the broken component. React unmounts the entire root, and the user
gets a blank page.

> **React unmounts the root, so you fence regions and keep expected failures
> out of the exception channel.**

```clojure
(h/defview article-page [{:keys [id]}]
  [:main
   [site-header {}]
   [h/error-boundary {:fallback [:p.oops "We couldn't load this article."]}
    [article-body {:id id}]]
   [site-footer {}]])
```

If `article-body` throws during render, the header and footer stay up, and
the paragraph takes the article's place. Nothing else in the tree notices.

[`h/error-boundary`](glossary.md#error-boundary) is a component that you write into the tree. It is not the
rendering [boundary](glossary.md#boundary) that a [`defview`](glossary.md#defview) mints
([Views and reads](02-views-and-reads.md)). The word is the same, the thing
is different — and only [`h/error-boundary`](glossary.md#error-boundary) catches.

## The three keys

[`h/error-boundary`](glossary.md#error-boundary) takes exactly three props. There is no built-in
classification, retry policy, or logging. Those are your app's decisions, and
you make them in `:on-error`.

| Key | Shape | What it does |
|---|---|---|
| `:fallback` | hiccup, or `(fn [error] hiccup)` | renders instead of the children once something below has thrown |
| `:reset-key` | any value, compared with `=` | changing it clears the caught failure and remounts the children |
| `:on-error` | an event vector, or a plain function | fires once per caught failure |

**`:fallback` can read the error.** Pass a function, and the function
receives the thrown value. Show `(ex-message error)` in development and a
plain sentence in production. The returned hiccup is lowered like any other,
[intents](glossary.md#intent) included, under the frame where the region was mounted.

**`:reset-key` is how retry works.** The region never guesses. When the key
changes, the region clears the caught failure and remounts the children — a
fresh mount, not a re-render of the survivors. If the children throw again,
the region catches again. A counter in app-db is the usual shape; the example
below drives one.

**`:on-error` fires once per caught failure.** The region dispatches an event
vector with the error appended — `[:diagnostics/record-failure]` reaches its
handler as `[:diagnostics/record-failure error]` — in the region's frame. If
you pass a plain function, the region calls the function with the error and
dispatches nothing. Once means once, even under StrictMode: the throwing
render can run twice in development, and React still reports a single catch.

## A nested region, with retry

Regions nest, and the inner region wins. The nearest region above a throw
catches the throw, and everything outside that region continues to render.

```clojure
(rf/reg-sub :chart/attempt
  (fn [db _query] (:chart/attempt db)))

(rf/reg-event :chart/retry
  (fn [{:keys [db]} _event]
    {:db (update db :chart/attempt (fnil inc 0))}))

(rf/reg-event :diagnostics/record-failure
  (fn [{:keys [db]} [_ error]]
    {:db (update db :diagnostics/failures (fnil conj []) (ex-message error))}))

(h/defview reports-page [_props]
  [:main
   [report-header {}]
   [h/error-boundary {:fallback [:p.oops "We couldn't show this report."]}
    [report-summary {}]
    [h/error-boundary {:fallback  (fn [_error]
                                    [:div.panel-oops
                                     [:p "The live chart failed."]
                                     [:button {:on-click [:chart/retry]}
                                      "Try again"]])
                       :reset-key (h/sub [:chart/attempt])
                       :on-error  [:diagnostics/record-failure]}
     [live-chart {}]]]])
```

When `live-chart` throws, the inner region catches. The panel shows its
fallback, `report-summary` and the header stay up, and the outer region never
fires. The retry button is an ordinary [intent](glossary.md#intent). Its handler increments
`:chart/attempt`, the `:reset-key` changes, and the chart remounts. If the
bad data is still there, the region catches again, and the panel shows its
fallback again. A throw in `report-summary`, which sits outside the inner
region, lands in the outer region instead: the whole report body is replaced,
and the header survives.

**A fallback that throws is caught by the next region up.** A fallback that
re-reads the state that just failed can turn one broken panel into a broken
page. Keep fallbacks plain: static markup, a retry intent, nothing heavy.

## What it never sees

The region inherits React's rule exactly.

**Caught:** a throw from render, and a throw from the lifecycle and effects
of the tree below the region.

**Not caught:** anything the browser calls outside render — an event handler,
a `setTimeout`, a promise continuation. Those failures belong to re-frame2,
not to the view layer. An [intent](glossary.md#intent)'s handler runs inside the event pipeline.
The pipeline catches the throw, reports a structured error record
(`:rf.error/handler-exception`, with the event, frame, and recovery
attached), and the app continues to run. A raw `fn` callback that throws goes
to the browser's error channel like any other JavaScript. Either way the
region's fallback never shows. That is correct, because nothing below the
region failed to *render*.

## Expected failures stay data

One law keeps regions rare: **if you can name the failure in advance, the
failure is state, not an exception.** A request can 404, a form can be
invalid, a resource can be unavailable. Model each in app-db, and render each
with an ordinary view.

```clojure
;; Don't — a 404 is not an exception, and a region is not control flow
(h/defview article-body [{:keys [id]}]
  (let [article (h/sub [:article/by-id id])]
    (when (nil? article)
      (throw (ex-info "article missing" {:id id})))
    [:article (:title article)]))
```

```clojure
(h/defview article-body [{:keys [id]}]
  (case (h/sub [:article/status id])
    :loading [loading-placeholder {}]
    :failed  [load-failed {:id id}]
    [:article (:title (h/sub [:article/by-id id]))]))
```

The second version is testable with `=`, renders a real message instead of a
generic fallback, and leaves the region free for its real job: the failure
you did *not* plan for. Fetch status and mutation status are ordinary app-db
state; [Async resources](08-async-resources.md) owns that shape.

## Where regions go

Regions do not go everywhere, and not once at the root. A single root region
is a whole-page fallback: not much better than a blank screen, and navigation
goes down with the page. One region per view is noise, and most views cannot
fail independently of their parent.

The useful grain is **a region the user can still work without**: a panel, a
tab body, a sidebar widget, a route's main content. Ask what the rest of the
page is worth if this part is gone. If the answer is "nothing", put the
region higher.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Whole page blanks when one view throws | Nothing caught it — React unmounts the root by default | Put [`h/error-boundary`](glossary.md#error-boundary) around the region that can fail |
| A throw from an event handler is not caught by the region | Handlers run in the event pipeline, not in render; the pipeline catches it and reports `:rf.error/handler-exception` | Expected — read the error record; the view layer never sees it |
| Fallback shows and never leaves | No `:reset-key`, or the key never changes | Drive the key from app-db and bump it to retry |
| Retry button in the fallback does nothing | The [intent](glossary.md#intent) lowers under the region's frame; without a frame in scope it refuses with `:rf.error/hicasso-intent-outside-boundary`, naming the intent | Keep the fallback ordinary hiccup inside the mounted tree; dispatch a plain intent |
| One panel breaks and the page follows it down | The fallback itself threw; the next region up caught that | Keep fallbacks plain — no reads of the failed state, no heavy work |
| `:on-error` seems to fire twice in development | It does not — StrictMode re-runs the failing render; React reports one catch | Two fires means two real failures |
| The region did not catch during a server render | React routes server render errors through its server error channel; client regions never see them | The surface's [server policy](glossary.md#server-policy) owns that path ([SSR and hydration](17-ssr-and-hydration.md)) |

## When not

- **The failure is expected** — a 404, invalid input, an empty result. That
  is app-db state rendered by an ordinary view. A region there is a worse
  version of a status key.
- **Around every view** — a region earns its place at the grain of "the user
  can still work without this"; below that grain it is noise.
- **As loading UI** — a fallback is for failure, not for pending. Pending is
  data too.
