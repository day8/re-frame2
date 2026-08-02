# Events as data

> **Pre-implementation draft — Hicasso does not exist yet.** This page describes the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-026).

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

## The value placeholder

Most handlers need something out of the event object. `::h/value` **[unfrozen]** is
the placeholder that gets substituted at dispatch time:

```clojure
[:input {:value    draft
         :on-input [:todo.ui/edit id ::h/value]}]
```

At dispatch, the placeholder is replaced by the input's value, so the handler
receives `[:todo.ui/edit 7 "milk"]` — an ordinary event vector, indistinguishable
from one you dispatched by hand.

The census says this covers about **97% of the corpus's 183 handler sites**. That
number is why the placeholder exists: one marker turns nearly every handler
attribute in a real application into data.

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
         :on-keydown {"Enter"  [:todo.ui/commit id]
                      "Escape" [:todo.ui/cancel id]}}]
```

A map from key name to intent. Keys you don't list are ignored.

The reason this belongs in the runtime rather than in your view is the composition
law: **a composing Enter commits nothing.** Mid-IME, Enter selects a candidate — it
is not a submit, and treating it as one is how a Japanese or Chinese user loses a
sentence. The runtime centralises that check, including the legacy keyCode-229
signal that some browsers still use to say "this keystroke belongs to the IME."

Get this wrong in your own handler and it fails silently for every user who
composes. Which is a good argument for it not being your handler.

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
| Enter commits half-typed Japanese text | Composition handling was written by hand | Use the `:on-keydown` key map — the composition law is centralised there |
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
| The auto-prevent opt-out spelling | **Settled** (HD-026). The submit opt-out is `h/fn`; prevention elsewhere is opted in by the `[::h/prevent …]` head, and the metadata spelling this page once carried is retired |
| Whether markers other than `::h/value` exist | **Not addressed.** The charter names "the intent-marker roster and its one pure materializer" as a micro-mechanic worth copying from the predecessor, but only `::h/value` is spelled. A checked-state marker for checkboxes is an obvious gap this guide could not fill honestly — the example on [Getting started](01-getting-started.md) sidesteps it by passing the id and letting the handler flip the value |
| Which attributes get intent lowering | **Not addressed.** `:on-click`, `:on-input`, `:on-submit`, and `:on-keydown` appear in the record; whether lowering is universal across `:on-*` or a named roster is unstated |
| The key map's key vocabulary | Key names appear as strings (`"Enter"`, `"Escape"`); whether keywords are accepted, and how modifiers are spelled, is unstated |
| `::h/value` semantics on non-input elements | **Not addressed** |
