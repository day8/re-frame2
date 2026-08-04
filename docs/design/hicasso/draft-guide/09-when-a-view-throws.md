# When a view throws

> **Draft ahead of the product artefact.** No `implementation/hicasso/` artefact
> ships yet. Spellings marked **[unfrozen]** stay provisional until the API freeze.

A view body throws — a `nil` where a map was expected, a key that moved, a
subscription returning a shape last week's code doesn't handle. React's answer is
not a red box around the broken component. **React unmounts the entire root**, and
your user is looking at a blank page.

That is the right default for a framework that cannot know which parts of your app
are independent. You do know, so you say so:

```clojure
(defview article-page [{:keys [id]}]
  [:main
   [site-header {}]
   [h/boundary {:fallback [:p.oops "We couldn't load this article."]}   ;; [unfrozen]
    [article-body {:id id}]]
   [site-footer {}]])
```

If `article-body` throws, the header and footer stay on screen and the paragraph
takes its place. Nothing else in the tree notices.

## The three keys

`h/boundary` **[unfrozen]** is the runtime's own error boundary, and it takes
exactly three props. There is no error classification, no retry policy, no logging
surface — each of those is your application's decision, and `:on-error` is the door
you make them behind.

| Key | Shape | What it does |
|---|---|---|
| `:fallback` | hiccup, or `(fn [error] hiccup)` | renders instead of the children once something below has thrown |
| `:reset-key` | any value, compared with `=` | changing it clears the caught failure and re-mounts the children |
| `:on-error` | an intent vector, or a plain function | fires once per caught failure |

**`:fallback` can read the error.** Pass a function and it is called with whatever
was thrown, so a development build can show the message and a production one can
show a sentence. Hiccup the function returns is lowered exactly like hiccup you
wrote inline — intents included.

**`:reset-key` is how a retry happens, and it is yours to schedule.** The boundary
never guesses. It clears its caught failure when the key changes, and remounts the
children; if they throw again, it catches again. A counter in app-db is the usual
shape:

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

That retry button is an ordinary intent inside a fallback, and it works — the
fallback is walked inside the boundary's own render, and the boundary re-binds the
frame it was mounted under around that walk so a handler there lowers exactly as it
would in the parent's body.

**`:on-error` fires once per failure.** A vector is dispatched with the error
appended, so `[:app/record-failure]` reaches your handler as
`[:app/record-failure error]`, in the frame the boundary is mounted under. A
function is called with the error instead, and nothing is dispatched. Once means
once even in development, where StrictMode runs the throwing render twice and React
still reports a single catch.

## What it does not catch

Worth knowing precisely, because a boundary that quietly does not catch is worse
than no boundary at all. This is React's line, and Hicasso inherits it exactly.

**Caught:** a throw from a render, and from the lifecycle and effects of the tree
below.

**Not caught:** a throw from an event handler, from a `setTimeout`, or from
anything else the browser calls outside React's own work loop. An intent handler
that throws lands in the browser's error channel, not here — and that is the right
place for it, because your event pipeline has its own error handling and a view
layer should not be intercepting it.

## Where to put them

Not everywhere, and not once at the root.

One boundary at the root gives you a whole-page fallback, which is barely better
than the blank page: the user has lost their navigation too. One boundary per view
is the other extreme — a fallback per byline is noise, and most of your views
cannot fail independently of their parent anyway.

The useful grain is a **region a user can still work without**: a panel, a tab
body, a sidebar widget, a route's main content. Ask what the rest of the page is
still good for if this part is gone. If the answer is "nothing", the boundary
belongs further up.

One caution on nesting. **A fallback that throws while rendering is caught by the
next boundary up**, so a clever fallback that reads the state that caused the
failure can turn one broken panel into a broken page. Keep fallbacks dull.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| The whole page goes blank when one view throws | Nothing caught it — React unmounts the root by default | Put an `h/boundary` around the region that can fail |
| A throw from an event handler isn't caught | Handlers run outside React's work loop; boundaries only see render, lifecycle and effects | Handle it in the event pipeline, where the error already has a home |
| The fallback shows and never goes away | No `:reset-key`, or the key never changes | The retry is the caller's to schedule — move a counter in app-db and read it as the key |
| A retry button in the fallback does nothing | Check it is an intent vector at `:on-click` and that the boundary is mounted under a frame | Intents in a fallback lower under the boundary's own frame; with no frame in scope you get `:rf.error/hicasso-intent-outside-boundary` naming the intent |
| One panel breaks and the whole page goes with it | The fallback itself threw, and the next boundary up caught that instead | Keep the fallback dull — no reads of the state that failed, no computation |
| `:on-error` fires twice in development | It doesn't — StrictMode re-runs the failing render, and React still reports one catch | If you genuinely see two, they are two failures |

## When not to reach for one

If the failure is *expected* — a request that can 404, a form that can be invalid,
a resource that can be unavailable — it is not an exception, it is a state. Model
it in app-db and render it. A boundary is for the failures you did not anticipate,
and using one for the ones you did makes a thrown exception part of your control
flow, which is a worse version of a `:status` key.

## Not settled yet

| Question | Status |
|---|---|
| `h/boundary`'s name and its three key names | Semantics pinned; the spellings are unfrozen like every other declaration spelling |
| Whether `:on-error` should reach an application-wide handler as well | **Not addressed.** The per-boundary door ships; how an app aggregates failures is its own business today |
| A richer boundary API | **Post-v0** |
