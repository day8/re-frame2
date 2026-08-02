# Events as data

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-028), witnessed by the bench
> arm's tests under `implementation/freehand/test/re_frame/bench/hicasso/` — but no
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze.

Handler attributes are where a data-oriented view layer usually gives up. You have
been writing pure data all the way down the tree, and then `:on-click` needs a
function, so you write `#(rf/dispatch [:todo/toggle id])` and the node stops being
inspectable, comparable, or assertable by equality.

Hicasso's answer: **put the event vector in the attribute.** The runtime builds the
callback.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

That is not sugar for a closure you could have written. The tree still holds data at
that node, which means a test can assert it with `=` and a tool can read it.

Lowering is by shape, not by roster: any prop whose name reads `on-` plus a letter
(or camelCase `onClick`, for the migrating author) is an event position, so there is
no list of blessed event names to keep in step with the DOM.

## The value placeholders

Most handlers need something out of the event object. Two markers exist —
`::h/value` and `::h/checked` — substituted at dispatch time with the event
target's `.value` and `.checked`:

```clojure
[:input {:value    draft
         :on-input [:todo.ui/edit id ::h/value]}]
```

At dispatch, the placeholder is replaced by the input's value, so the handler
receives `[:todo.ui/edit 7 "milk"]` — an ordinary event vector, indistinguishable
from one you dispatched by hand. Substitution happens at the intent vector's top
level only, which is the shape the corpus writes; a marker buried in nested
structure is not looked for.

The census says the markers cover about **97% of the corpus's 183 handler sites**.
That number is why they exist: two markers turn nearly every handler attribute in a
real application into data.

## Functions still work

An ordinary function remains legal at any event position:

```clojure
[:canvas {:on-pointer-move (fn [e] (draw! (.-clientX e) (.-clientY e)))}]
```

Use one when you genuinely need the event object — geometry, pointer capture, an
imperative call. The intent vector is the taught default because it covers almost
everything, not because functions are forbidden.

## When you need the event *and* an intent: `h/fn`

Sometimes the event has to be read before you know what to dispatch — a file list,
a drag delta, a filtered key. For that there is **one** callback form, and it is an
ordinary function:

```clojure
[:input {:type "file"
         :on-change (h/fn [e] [:upload/picked (js/Array.from (.. e -target -files))])}]
```

**The position decides what the return means.** At an `:on-*` prop, a returned
vector is dispatched and anything else — including `nil` — is ignored, so a
conditional dispatch is an ordinary `when`:

```clojure
[:div {:on-drop (h/fn [e]
                  (.preventDefault e)
                  (when-let [f (aget (.. e -dataTransfer -files) 0)]
                    [:upload/dropped (.-name f)]))}]
```

| Where you wrote it | What it means |
|---|---|
| a native `:on-*` prop | a returned vector is dispatched; any other return is ignored |
| a `defhost` callback the declaration named | whatever the declaration said — `:event` or `:handler` |
| a slot or a foreign render prop | it runs during a render, so it must be pure. The return is output, not an intent, and dispatching from inside it is an error that **names the prop** |
| `:ref` | React's own contract — the node on attach, your return as the cleanup |
| a raw `#js` prop you built yourself | nothing at all. It is a function; it runs |

That last row is the one worth knowing about, because of what it is not. In the
predecessor there are four callback forms plus a rule about where they reach, and
the forms are *carriers* — marker objects, not functions. Hand one to a raw `#js`
prop and the library calls `props.onPing(…)` on something that is not callable, so
what you get is the JavaScript engine's own `TypeError`, worded after whatever
expression it tripped on, naming nothing you wrote. `h/fn` is a function
everywhere. Put it somewhere the runtime does not walk and you lose the contract,
not the call.

You never pick a form. There is one, and where you put it is the decision.

**Write the parameter vector the caller actually calls with.** A DOM event position
hands you one argument, so `(h/fn [e] …)` is what you will write almost every time
— but at a `defhost` callback the declaration named `:event`, the arguments are the
foreign component's, and it may pass two or three. Take them all: they arrive
exactly as that library sends them.

One thing the policy defaults above do *not* do: **`h/fn` at `:on-submit` does not
auto-prevent.** That default exists because an intent vector never sees the event,
so the runtime has to decide for it. A callback is handed the event, so the event
is yours — call `.preventDefault` in the body. Whoever holds the event owns it.

## Policy defaults the runtime owns

Two things a form does every single time, and every codebase reimplements badly.

### Submit auto-prevents

An intent vector at `:on-submit` prevents the browser's default navigation:

```clojure
[:form {:on-submit [:signup/submit]}
 [:input {:value email :on-input [:signup/set-email ::h/value]}]
 [:button {:type :submit} "Sign up"]]
```

No `(.preventDefault e)`. It is census-weighted policy — forms in the corpus wanted
it every time — so the runtime does it. For the rare form that wants a real
submission, write the handler as `h/fn`: a callback is handed the event, so the
runtime leaves it alone and the event is yours (HD-026).

### Everywhere else, prevention is one head

A click does **not** auto-prevent, and must not: a modifier-click on a real link
has to open a tab. So the anchor-acting-as-a-button — the feed tab, the tag pill,
the pagination link — opts in, and the opt-in is part of the intent:

```clojure
[:a.nav-link {:href "#" :on-click [::h/prevent [:conduit/show-your-feed]]}]
```

`[::h/prevent INTENT]` and nothing else: exactly one inner intent vector, which is
what gets dispatched. Write it wrong — two payloads, a keyword instead of a vector,
a decorator inside a decorator — and you get `:rf.error/hicasso-malformed-prevent`
naming the attribute you wrote it at, at render time rather than at the click.

It is a head rather than metadata on the vector for a reason worth knowing, because
it is the reason you can test it: **metadata does not participate in `=`**.
`(= [:app/go] ^{::h/prevent? true} [:app/go])` is `true`, so an annotation is
invisible to a structural test that compares the tree — and to `pr-str`, and to
anything hashing the intent. A head is visible to all three.

Markers still work inside it: `[::h/prevent [:filter/set ::h/value]]` prevents and
materializes, because the decorator is unwrapped before the markers are looked for.

### The key map

Keyboard handling arrives as data, not as a `case` over `.-key`:

```clojure
[:input {:value    draft
         :on-input [:todo.ui/edit id ::h/value]
         :on-key-down {"Enter"  [:todo.ui/commit id]
                       "Escape" [:todo.ui/cancel id]}}]
```

A map from key name to intent. The names are strings, matched against the DOM
event's own `.key` value; keys you don't list are ignored, and there is no
modifier language.

The reason this belongs in the runtime rather than in your view is the composition
law: **a composing Enter commits nothing.** Mid-IME, Enter selects a candidate — it
is not a submit, and treating it as one is how a Japanese or Chinese user loses a
sentence. The runtime centralises that check, including the legacy keyCode-229
signal that some browsers still use to say "this keystroke belongs to the IME" —
and it reads both signals off the *native* event, because React's synthetic
keyboard event drops `isComposing`, which is exactly the trap a hand-written
handler falls into.

Get this wrong in your own handler and it fails silently for every user who
composes. Which is a good argument for it not being your handler.

## Links: `route-link` and the navigate head

Most of an application's anchors are navigations — the census counts **106
route-links across 85 files**, which makes the link the most-repeated tier-1 form
there is. Its spelling is `route-link`, a plain function over the routing
artefact's link seam (HD-027):

```clojure
(route-link {:to :conduit.profile/show :params {:username username}}
  username)
```

You name a route and its params and never see a URL. What comes back is one real
`<a>` whose `:href` is routing's own synthesis and whose `:on-click` carries the
click decision **as data**, under the second reserved head, `[::h/navigate {…}]` —
so two renders of one link are equal under `=`, and a structural test reads the
click decision off the tree. Because it is a plain function, not a boundary, it
inlines: no hook, no subscription read, no row in any boundary count. An author
byline is not a unit of re-render.

The click law is routing's, stated once, and it is the one every other link
surface already runs: a modifier-click or auxiliary click stays the browser's (a
new tab opens), a `:target` or `:download` anchor stays native, and everything
else is `preventDefault` plus a dispatch of the routing intent to the frame that
was captured at render. Rendering a `route-link` with routing absent fails at
render, naming the `:to` — never a dead anchor.

**The `:on-click` you pass is the pre-navigation veto**, and its roster is closed:
`nil`, `[::h/prevent [:app/event]]`, `h/fn`, or a plain function. The prevent form
is the declarative veto — the navigation is cancelled and your intent dispatched
instead, which is exactly the cancelable-navigation case the prevent head was
built for. A **bare** intent vector there is refused loudly: the click already
produces the one routing intent, and one user action must not yield two semantic
events. Witnessed end-to-end — grammar, equality, refusals, real clicks through a
real router — in `front/route_link_cljs_test` and
`shapes/route_link_dom_cljs_test`.

## Callbacks carry their frame

A generated callback closes over its boundary's frame, resolved once per boundary
from the substrate's single internal context.

That matters because the browser invokes the callback long after the render that
created it, when the render's dynamic extent is gone. A hand-written
`#(rf/dispatch …)` from an async context raises `:rf.error/no-frame-context` for
exactly this reason. Intent vectors don't, because the frame was captured when the
callback was built.

The rule of thumb: **intents are always safe; hand-written async needs the frame
explicitly.**

## Troubleshooting

This table names mechanisms rather than error ids; the ids the record does mint
are named in the sections above.

| Symptom | What went wrong | Fix |
|---|---|---|
| Page navigates and reloads on form submit | Something bypassed the intent path — a hand-written `:on-submit` function | Use an intent vector, or call `.preventDefault` yourself in the function |
| Handler receives the placeholder keyword instead of a value | The placeholder was used at an event position with no value to substitute | `::h/value` is for value-bearing events; read the event object with a function elsewhere |
| Enter commits half-typed Japanese text | Composition handling was written by hand | Use the `:on-key-down` key map — the composition law is centralised there |
| A `route-link` refuses your `:on-click` intent vector | The click already produces the one routing intent — a bare second intent is one action, two events | Wrap it: `[::h/prevent [:app/event]]` cancels the navigation and dispatches yours instead |
| `:rf.error/no-frame-context` from a timeout | A bare `dispatch` in async code | Capture the frame at the call site, or dispatch an event that owns the async work through `:fx` |
| An intent fires but no handler runs | Unregistered event id | Registration happens on namespace load; check the boot namespace requires it |

## When not to use an intent

When you need the event object itself. Pointer coordinates, `dataTransfer`,
`stopPropagation`, anything measuring the DOM — those are functions, and reaching
for one is not a failure. The census puts these at roughly 3% of handler sites,
which is the right size for an escape hatch: too small to design the syntax around,
too real to pretend away. Reach for `h/fn` when you want to read the event *and*
dispatch, and a plain `(fn …)` when you want to read it and do something else.

## Not settled yet

| Question | Status |
|---|---|
| `h/fn`'s spelling | Working name; HD-024 leaves the spelling unfrozen like every declaration spelling |
| A symmetric allow-default head | **Explicitly not shipped.** Added only if dogfooding produces a real site needing both native submission and equality-based structural testing (HD-026) |
| `:prefetch` on `route-link` | **Declined for v0** — the census counts no prefetch site; reopens if one appears (HD-027) |
