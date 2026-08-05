# When a view throws

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

A view body throws — `nil` where a map was expected, a key that moved, a
subscription shape last week's code cannot handle. React's default is not a red
box around the broken component.

> **React unmounts the entire root. You fence regions.**

```clojure
(defview article-page [{:keys [id]}]
  [:main
   [site-header {}]
   [h/boundary {:fallback [:p.oops "We couldn't load this article."]}   ;; [unfrozen]
    [article-body {:id id}]]
   [site-footer {}]])
```

If `article-body` throws, the header and footer stay up and the paragraph takes
its place. Nothing else in the tree notices. `h/boundary` is a component you
write; it is not the [rendering boundary](02-views-and-reads.md#boundaries-and-inlining)
a `defview` mints. Same word, different thing, and only this one catches.

## The three keys

`h/boundary` **[unfrozen]** takes exactly three props. No built-in classification,
retry policy, or logging — those are your app's decisions; `:on-error` is where
you make them.

| Key | Shape | What it does |
|---|---|---|
| `:fallback` | hiccup, or `(fn [error] hiccup)` | renders instead of the children once something below has thrown |
| `:reset-key` | any value, compared with `=` | changing it clears the caught failure and re-mounts the children |
| `:on-error` | an intent vector, or a plain function | fires once per caught failure |

**`:fallback` can read the error.** Pass a function and it receives whatever was
thrown — show the message in development, a sentence in production. Hiccup it
returns is lowered like any other, intents included.

**`:reset-key` is how retry works.** The boundary never guesses. It clears when the
key changes and remounts the children; if they throw again, it catches again. A
counter in app-db is the usual shape:

```clojure
(defview article-page [{:keys [id]}]
  [:main
   [h/boundary {:fallback  (fn [_error]
                             [:div.oops
                              [:p "We couldn't load this article."]
                              [:button {:on-click [:article/retry id]} "Try again"]])
                :reset-key (sub [:article/attempt id])
                :on-error  [:app/record-failure]}
    [article-body {:id id}]]])
```

The retry button is an ordinary intent inside the fallback. The fallback is walked
in the boundary's own render under the frame the boundary was mounted in, so
handlers lower the same way they would in the parent.

**`:on-error` fires once per failure.** A vector is dispatched with the error
appended — `[:app/record-failure]` reaches the handler as
`[:app/record-failure error]` — in the boundary's frame. A function is called with
the error and nothing is dispatched. Once means once even under StrictMode: the
throwing render may run twice in development, and React still reports a single
catch.

## What it catches

React's line, inherited exactly:

**Caught:** a throw from render, and from lifecycle and effects of the tree below.

**Not caught:** a throw from an event handler, a `setTimeout`, or anything else the
browser calls outside React's work loop. An intent handler that throws goes to the
browser's error channel — correct, because the event pipeline has its own error
handling and the view layer should not intercept it.

## Where to put them

Not everywhere, and not once at the root.

A single root boundary is a whole-page fallback — barely better than a blank
screen; navigation goes with it. One boundary per view is noise, and most views
cannot fail independently of their parent.

The useful grain is a **region the user can still work without**: a panel, a tab
body, a sidebar widget, a route's main content. Ask what the rest of the page is
worth if this part is gone. If the answer is "nothing", put the boundary higher.

**A fallback that throws while rendering is caught by the next boundary up.** A
clever fallback that re-reads the state that caused the failure can turn one
broken panel into a broken page. Keep fallbacks dull.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Whole page blanks when one view throws | Nothing caught it — React unmounts the root by default | Put `h/boundary` around the region that can fail |
| Throw from an event handler isn't caught | Handlers run outside React's work loop | Handle it in the event pipeline |
| Fallback shows and never leaves | No `:reset-key`, or the key never changes | Move a counter in app-db and read it as the key |
| Retry button in the fallback does nothing | Missing intent at `:on-click`, or no frame in scope | Intents in a fallback lower under the boundary's frame; without a frame you get `:rf.error/hicasso-intent-outside-boundary` naming the intent |
| One panel breaks and the whole page follows | The fallback itself threw; the next boundary up caught that | Keep the fallback dull — no reads of the failed state, no heavy work |
| `:on-error` seems to fire twice in development | It doesn't — StrictMode re-runs the failing render; React still reports one catch | Two fires means two real failures |

## When not to use one

If the failure is *expected* — a request that can 404, a form that can be invalid,
a resource that can be unavailable — it is a state, not an exception. Model it in
app-db and render it. A boundary is for failures you did not plan for. Using one
for control flow is a worse version of a `:status` key.

## Not settled yet

| Question | Status |
|---|---|
| `h/boundary` name and its three key names | Behaviour fixed; spellings **[unfrozen]** with the rest of the declaration API |
| Whether `:on-error` should also reach an app-wide handler | Not addressed — per-boundary `:on-error` ships; aggregation is the app's business |
| A richer boundary API | Open after the first ship |
