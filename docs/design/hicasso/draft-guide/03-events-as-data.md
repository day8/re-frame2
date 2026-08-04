# Events as data

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Handler attributes are where a data-oriented view layer usually gives up. You
write pure data all the way down the tree, then `:on-click` needs a function, so
you write `#(rf/dispatch [:todo/toggle id])` and the node stops being
inspectable, comparable, or assertable by equality.

> **Put the event vector in the attribute.** The runtime builds the callback.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

That is not sugar for a closure you could have written. The tree still holds
data at that node, so a test can assert it with `=` and a tool can read it.

Lowering is by shape, not by roster: any prop whose name reads `on-` plus a
letter (or camelCase `onClick`, for the migrating author) is an event position.
There is no list of blessed event names to keep in step with the DOM.

## The value placeholders

Most handlers need something out of the event object. Two markers exist —
`::h/value` and `::h/checked` — substituted at dispatch time with the event
target's `.value` and `.checked`:

```clojure
[:input {:value    draft
         :on-input [:todo.ui/edit id ::h/value]}]
```

```clojure
[:input {:type      :checkbox
         :checked   done?
         :on-change [:todo/set-done id ::h/checked]}]
```

At dispatch, the placeholder is replaced by the input's value or checked state,
so the handler receives `[:todo.ui/edit 7 "milk"]` or `[:todo/set-done 7 true]`
— ordinary event vectors, indistinguishable from ones you dispatched by hand.
Substitution happens at the intent vector's top level only; a marker buried in
nested structure is not looked for.

Those two markers cover almost every handler site that only needs the target's
value or checked state. For the full controlled-input round-trip — value in from
a subscription, intent out, caret and same-tick echo — see
[Controlled inputs](04-controlled-inputs.md).

## Functions still work

An ordinary function remains legal at any event position:

```clojure
[:canvas {:on-pointer-move (fn [e] (draw! (.-clientX e) (.-clientY e)))}]
```

Use one when you genuinely need the event object — geometry, pointer capture, an
imperative call. The intent vector is the taught default because it covers almost
everything, not because functions are forbidden.

## When you need the event *and* an intent: `h/fn`

Sometimes the event has to be read before you know what to dispatch — a file
list, a drag delta, a filtered key. For that there is **one** callback form, and
it is an ordinary function:

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

That last row is worth knowing. `h/fn` is a real function everywhere. Put it
somewhere the runtime does not walk and you lose the contract, not the call —
the library gets a callable, not a marker object that blows up as a `TypeError`
naming nothing you wrote.

You never pick a form. There is one, and where you put it is the decision.

**Write the parameter vector the caller actually calls with.** A DOM event
position hands you one argument, so `(h/fn [e] …)` is what you will write almost
every time — but at a `defhost` callback the declaration named `:event`, the
arguments are the foreign component's, and it may pass two or three. Take them
all: they arrive exactly as that library sends them.

One thing the policy defaults below do *not* do: **`h/fn` at `:on-submit` does
not auto-prevent.** That default exists because an intent vector never sees the
event, so the runtime has to decide for it. A callback is handed the event, so
the event is yours — call `.preventDefault` in the body.

## Policy defaults the runtime owns

Two things a form does every single time, and every codebase reimplements badly.

### Submit auto-prevents

An intent vector at `:on-submit` prevents the browser's default navigation:

```clojure
[:form {:on-submit [:signup/submit]}
 [:input {:value email :on-input [:signup/set-email ::h/value]}]
 [:button {:type :submit} "Sign up"]]
```

No `(.preventDefault e)`. Forms almost always want it, so the runtime does it.
For the rare form that wants a real submission, write the handler as `h/fn`: a
callback is handed the event, so the runtime leaves it alone.

### Everywhere else, prevention is one head

A click does **not** auto-prevent, and must not: a modifier-click on a real link
has to open a tab. So the anchor-acting-as-a-button — the feed tab, the tag pill,
the pagination link — opts in, and the opt-in is part of the intent:

```clojure
[:a.nav-link {:href "#" :on-click [::h/prevent [:conduit/show-your-feed]]}]
```

`[::h/prevent INTENT]` and nothing else: exactly one inner intent vector, which
is what gets dispatched. Write it wrong — two payloads, a keyword instead of a
vector, a decorator inside a decorator — and you get
`:rf.error/hicasso-malformed-prevent` naming the attribute you wrote it at, at
render time rather than at the click.

It is a head rather than metadata on the vector for a reason: **metadata does
not participate in `=`**.
`(= [:app/go] ^{::h/prevent? true} [:app/go])` is `true`, so an annotation is
invisible to a structural test that compares the tree — and to `pr-str`, and to
anything hashing the intent. A head is visible to all three.

Markers still work inside it: `[::h/prevent [:filter/set ::h/value]]` prevents
and materializes, because the decorator is unwrapped before the markers are
looked for.

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

The runtime owns composition: mid-IME, Enter selects a candidate — it is not a
submit. Hand-write that check wrong and every user who composes loses a sentence.
See **Advanced** for the IME detail.

## Links: `route-link` and the navigate head

Most of an application's anchors are navigations. Spell the link as
`route-link`, a plain function over the routing artefact:

```clojure
(ns conduit.views.profile
  (:require [re-frame.hicasso :as h :refer [defview sub route-link]]))

(defview author-byline [{:keys [username]}]
  (route-link {:to :conduit.profile/show :params {:username username}
               :class "author"}
    username))
```

You name a route and its params and never see a URL. What comes back is one real
`<a>` whose `:href` is routing's own synthesis and whose `:on-click` carries the
click decision **as data**, under the second reserved head:

```clojure
;; Closed map, four keys — the shape a structural test can `=`.
;; :payload is routing's own synthesis, not something you invent at the link site.
[::h/navigate {:frame   :rf/default
               :payload [:rf.route/url-requested {:url    "/profile/jane"
                                                  :to     :conduit.profile/show
                                                  :params {:username "jane"}}]
               :native? false
               :veto    nil}]
```

So two renders of one link are equal under `=`, and a structural test reads the
click decision off the tree. Because it is a plain function, not a boundary, it
inlines: no hook, no subscription read, no row in any boundary count.

The click law is routing's, stated once: a modifier-click or auxiliary click
stays the browser's (a new tab opens), a `:target` or `:download` anchor stays
native, and everything else is `preventDefault` plus a dispatch of the routing
intent to the frame that was captured at render. Rendering a `route-link` with
the routing artefact absent fails at render with
`:rf.error/routing-artefact-missing`, naming the `:to` — never a dead anchor.

**The `:on-click` you pass is the pre-navigation veto**, and its roster is
closed: `nil`, `[::h/prevent [:app/event]]`, `h/fn`, or a plain function. The
prevent form is the declarative veto — the navigation is cancelled and your
intent dispatched instead. A **bare** intent vector there is refused loudly: the
click already produces the one routing intent, and one user action must not yield
two semantic events.

Routing also publishes a `:prefetch` opt-in; Hicasso declines it today — out
loud, rather than by ignoring it. A `route-link` carrying `:prefetch` in any
form, `nil` and `false` included, fails at render with
`:rf.error/hicasso-route-link-prefetch-declined`. A key that merely fell through
would leave you with a link that prefetches nothing, reports it nowhere, and
carries a stray attribute on the anchor. If you want prefetching today, write it
as an ordinary intent at `:on-mouse-enter`.

## Callbacks carry their frame

A generated callback closes over its boundary's frame, resolved once per boundary
from the substrate's single internal context.

That matters because the browser invokes the callback long after the render that
created it, when the render's dynamic extent is gone. A hand-written
`#(rf/dispatch …)` from an async context raises `:rf.error/no-frame-context` for
exactly this reason. Intent vectors don't, because the frame was captured when
the callback was built.

The rule of thumb: **intents are always safe; hand-written async needs the frame
explicitly.**

## Troubleshooting

This table names mechanisms rather than error ids; the ids the page does mint
are named in the sections above.

| Symptom | What went wrong | Fix |
|---|---|---|
| Page navigates and reloads on form submit | Hand-written `:on-submit` function bypassed the intent path | Use an intent vector, or call `.preventDefault` yourself |
| Handler receives the placeholder keyword instead of a value | Placeholder used where there is no value to substitute | `::h/value` for value-bearing events; `::h/checked` for checkboxes; read the event with a function elsewhere |
| Enter commits half-typed Japanese text | Composition handling written by hand | Use the `:on-key-down` key map — composition is centralised there |
| A `route-link` refuses your `:on-click` intent vector | Click already produces the routing intent — bare second intent is one action, two events | Wrap it: `[::h/prevent [:app/event]]` |
| `:rf.error/routing-artefact-missing` when rendering a `route-link` | Routing not on the classpath / not required at boot | Require `re-frame.routing` (and the rest of your routing setup) before the link renders |
| `:rf.error/no-frame-context` from a timeout | Bare `dispatch` in async code | Capture the frame at the call site, or own the async work through `:fx` |
| An intent fires but no handler runs | Unregistered event id | Registration happens on namespace load; check the boot namespace requires it |

## When not to use an intent

When you need the event object itself. Pointer coordinates, `dataTransfer`,
`stopPropagation`, anything measuring the DOM — those are functions, and
reaching for one is not a failure. Reach for `h/fn` when you want to read the
event *and* dispatch, and a plain `(fn …)` when you want to read it and do
something else.

## Advanced

### IME and the key map

The reason the key map belongs in the runtime rather than in your view is
composition: **a composing Enter commits nothing.** Mid-IME, Enter selects a
candidate — it is not a submit. The runtime centralises that check, including
the legacy keyCode-229 signal that some browsers still use to say "this
keystroke belongs to the IME" — and it reads both signals off the *native*
event, because React's synthetic keyboard event drops `isComposing`. That is the
trap a hand-written handler falls into: silent failure for every user who
composes.

## Not settled yet

| Question | Status |
|---|---|
| `h/fn`'s spelling | Working name; **[unfrozen]** like every declaration spelling |
