# Events as data

With reads in place from [Views and reads](02-views-and-reads.md), this page
makes handlers data so trees stay inspectable.

Handler attributes are where a data-oriented view layer usually stops being
data. You write pure data down the whole tree. Then `:on-click` needs a
function, so you write `#(rf/dispatch [:todo/toggle id])`. That node stops
being inspectable, comparable, or assertable by equality.

Put the event vector in the attribute. The runtime creates the callback.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

The tree still holds data at that node, so a test asserts the node with `=`,
and a tool reads it off the tree. When the user clicks the button, the runtime
dispatches the vector into the re-frame2 frame owned by the rendering view.
The runtime built that callback at render, and the callback includes the frame
so the later click still has context.

Any prop whose name is `on-` plus a letter is an event position. CamelCase
`onClick` also works, for the migrating author. There is no list of approved
event names to keep in step with the DOM. An event vector at an event position
is an **[intent](glossary.md#intent)**: the meaning of the interaction, stated
as data. That is the word this guide uses for it.

## Value markers

Most handlers need a value out of the event. The runtime substitutes two
reserved words, [`::h/value`](glossary.md#hvalue) and
[`::h/checked`](glossary.md#hchecked), at dispatch time with the event
target's `.value` and `.checked`:

```clojure
[:input {:value    (h/sub [:todo.ui/draft id])
         :on-input [:todo.ui/edit id ::h/value]}]

[:input {:type      :checkbox
         :checked   (h/sub [:todo/done? id])
         :on-change [:todo/set-done id ::h/checked]}]
```

At dispatch, the handler receives `[:todo.ui/edit 7 "milk"]` or
`[:todo/set-done 7 true]`. These are ordinary event vectors, the same as
vectors that you dispatch by hand. Substitution happens at the intent vector's
top level only. The runtime does not look for a marker in nested structure. If
the vector has no marker, the runtime never peeks at the DOM event — so
nothing is substituted, and nothing is read "for free."

These two words, plus [`::h/prevent`](glossary.md#hprevent) below and
[`::h/revision`](glossary.md#hrevision)
([Controlled inputs](04-controlled-inputs.md)), are the entire reserved
vocabulary. For the full controlled round-trip — value in from a subscription,
intent out, caret and same-turn echo — see
[Controlled inputs](04-controlled-inputs.md).

## Prevention is explicit

Nothing auto-prevents — not a click, and not a form submit. Prevention is a
decision. The decision travels **as data**, in the intent itself:

```clojure
[:form {:on-submit [::h/prevent [:todo/submit]]}
 [:input {:value    (h/sub [:todo.ui/draft])
          :on-input [:todo.ui/set-draft ::h/value]}]
 [:button {:type :submit} "Add todo"]]
```

`[::h/prevent INTENT]` prevents the browser default and dispatches the inner
intent. The same head serves an anchor that acts as a button — a tab or a tag
pill. An unconditional default *cannot* exist there, because a modifier-click
on a real link must still open a tab:

```clojure
[:a.nav-link {:href "#" :on-click [::h/prevent [:todo/filter-active]]}
 "Active"]
```

!!! warning "A bare intent at `:on-submit` still submits"
    `{:on-submit [:todo/submit]}` dispatches your intent **and** lets the
    browser navigate. There is no submit special case. If the page reloads on
    submit, the [`::h/prevent`](glossary.md#hprevent) head is missing. The rare
    form that wants a real browser submission omits the head.

The shape is fixed: exactly one inner intent vector. That inner vector is what
actually gets dispatched. Two payloads, a keyword instead of a vector, a
decorator inside a decorator — each is
`:rf.error/hicasso-malformed-prevent`. The error names the attribute and fires
at render time, not at the click. Markers still work inside the head:
`[::h/prevent [:filter/set ::h/value]]` prevents and substitutes, because the
runtime unwraps the decorator before it looks for the markers.

The prevent form is a head, not metadata on the vector, for one reason:
**metadata does not participate in `=`**. An annotation would be invisible to
a structural test that compares the tree, to `pr-str`, and to any code that
hashes the intent. A head is visible to all three.

## `h/event`: when you need the callback arguments

Sometimes you must read the callback's arguments before you know what to
dispatch. Examples: a file list, a drag payload, a foreign component that
calls `(on-change date)` with no DOM event. [`h/event`](glossary.md#hevent) is
the one explicit event-producing form:

```clojure
[:input {:type      "file"
         :on-change (h/event [e]
                      [:todo/attach (js/Array.from (.. e -target -files))])}]
```

Its contract has three clauses, and they hold in **every** position:

- It **captures the frame at the place where you write it**. Inside a view
  body, that is the rendering view's frame.
- When the invoker calls it later, it receives **every argument that the
  invoker passes, in order**: one DOM event at a native position,
  `(date event)` from a value-first library — whatever the caller's contract
  says.
- The runtime **dispatches a returned event vector** to the captured frame.
  `nil` dispatches nothing, so a conditional dispatch is an ordinary `when`:

```clojure
[:div {:on-drop (h/event [e]
                  (.preventDefault e)
                  (when-let [f (aget (.. e -dataTransfer -files) 0)]
                    [:todo/attach-dropped (.-name f)]))}]
```

The meaning never varies by position. An `h/event` given to a
[`h/defhost`](glossary.md#defhost) callback, retained by a vendor SDK, or
placed in a raw `#js` prop does the same thing: it runs, it can return a
vector, and the runtime dispatches that vector to the frame it captured.
Prevention inside an `h/event` is your task: the function holds the event, so
it calls `.preventDefault` itself, as the drop example does.

**The vector spelling is event-first; `h/event` is not.** `::h/value`,
`::h/checked`, and `::h/prevent` all read the DOM event, and they read it from
argument one. Every native position and every event-first foreign contract
puts the event there. A value-first invoker, such as a date picker's
`(on-change date)`, has no event at argument one. Nothing guesses which
argument might be an event. The intent raises
`:rf.error/hicasso-intent-needs-the-event`. The error names the position and
points to `h/event`, the spelling that sees the library's own arguments in
order:

```clojure
(h/event [date _e] [:todo/set-due date])
```

## Ordinary functions still work

An ordinary `fn` stays legal at any event position, with ordinary JavaScript
callback semantics: it runs, and the runtime ignores its return value:

```clojure
[:canvas {:on-pointer-move (fn [e] (draw! (.-clientX e) (.-clientY e)))}]
```

Use an ordinary `fn` when the *work* is imperative and no intent exists:
geometry, pointer capture, `stopPropagation`, an SDK's imperative call.
Render props and slots on foreign components also take ordinary functions.
They run during a foreign render, so they stay pure. A dispatch from inside
one raises `:rf.error/hicasso-dispatch-in-render-position`
([Interop](09-interop.md) owns that crossing). The intent vector is the taught
default because it covers almost everything, not because functions are
forbidden.

An ordinary `fn` must not dispatch on its own:

```clojure
;; Don't — a hand-rolled dispatch closure
[:button {:on-click (fn [_] (rf/dispatch [:todo/toggle id]))} "✓"]
;; the click arrives long after the render's extent is gone —
;; :rf.error/no-frame-context, at the click

;; Do
[:button {:on-click [:todo/toggle id]} "✓"]
```

The intent vector and `h/event` exist so that the runtime answers the frame
question at render, once.

## Keyboard as data

A [keyboard map](glossary.md#keyboard-map) is a data map from DOM `.key` string
to intent — not a `case` over `.-key`:

```clojure
[:input {:value       (h/sub [:todo.ui/draft id])
         :on-input    [:todo.ui/edit id ::h/value]
         :on-key-down {"Enter"  [:todo.ui/commit id]
                       "Escape" [:todo.ui/cancel id]}}]
```

The names are strings. The runtime matches them against the DOM event's own
`.key`, and it ignores keys that you do not list. There is no modifier
language: a handler that needs `ctrl+Enter` reads the event with `h/event`.
Key maps are legal at **keyboard props only** (`:on-key-down`, `:on-key-up`).
A map is not an intent spelling anywhere else.

The composition rule is explicit, and the runtime owns it. An IME (input
method editor) composes text such as Japanese or Chinese from several
keystrokes. **A key event that arrives during IME composition matches nothing
in the map.** While a user composes, Enter selects a candidate; it is not a
commit. Escape cancels the composition; it is not your cancel. A wrong
hand-written check makes every user who composes lose a sentence. The map
applies the correct check centrally, and it includes the legacy signals that
some browsers still send (see [Advanced](#advanced)).

## Callbacks include their frame

Every generated callback closes over its view's frame. Intent vectors capture
the frame when the attribute is turned into a React prop; `h/event` captures
it at creation. This matters because the browser invokes callbacks long after
the render that created them, when the render's extent is gone. A hand-written
`#(rf/dispatch …)` raises `:rf.error/no-frame-context` from that gap. The data
spellings do not, because they closed over the frame.

**Async work belongs in the event layer, which already has the frame.** A view
that sets a timeout and dispatches when the timeout fires is a mis-layered
effect. An fx handler receives the frame in its context, and
`:dispatch-later` states the delay as data. Move the work to the event layer,
and the frame question moves with it.

One case remains after that move: a dispatching closure that you give to **a
caller you do not control** — an SDK attached through a ref, a library that
calls back with a value. The frame is knowable during the render and nowhere
else. Capture it there with core's one capture primitive:

```clojure
(ns app.map
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [app.sdk :as sdk]))

(h/defview map-panel [{:keys [id]}]
  (let [{:keys [dispatch]} (rf/capture-frame)]   ;; the rendering view's frame
    [:div.map
     {:ref (fn [node]                            ;; refs run after commit, not in render
             (when node
               (sdk/on-select node #(dispatch [:map/marker-selected id %]))))}]))
```

Inside a Hicasso body, `(rf/capture-frame)` resolves the view's frame. It
returns `{:frame :dispatch :dispatch-sync :subscribe}` locked to that frame.
`(rf/current-frame-id)` returns the bare id when a foreign API wants one.
These are core's frame doors; Hicasso adds no duplicate. (Ambient
`rf/dispatch` and `rf/subscribe` throw from a body. The capture doors include
the frame; the ambient calls do not.) A captured handle is valid for
the life of its frame's incarnation. If you destroy the frame and remount
under the same id, an operation from the stale handle raises
`:rf.error/frame-destroyed` and does not reach the successor frame. Capture
from the live render, never from a stash.

The general rule: **intents are always safe; async work goes through `:fx`; a
closure that crosses into foreign code captures `(rf/capture-frame)`.**

One nearby case is not this page's case. An anchor that *navigates* is a
[route link](glossary.md#route-link) from `re-frame.hicasso.routing`, not a
hand-written `:on-click`.
[Routing and navigation](07-routing-and-navigation.md) owns it.

## Troubleshooting

| Symptom | Error | Fix |
|---|---|---|
| Page navigates and reloads on form submit | none — nothing auto-prevents | Wrap the intent: `{:on-submit [::h/prevent [:todo/submit]]}` |
| Throws at render naming an event attribute | `:rf.error/hicasso-malformed-prevent` | `[::h/prevent INTENT]` wraps exactly one inner intent vector — no second payload, no nesting |
| Handler receives the literal `::h/value` keyword | none — marker below top level | Markers substitute at the vector's top level only; compute nested payloads in the handler, or read the event with `h/event` |
| A foreign callback rejects a marker-carrying intent | `:rf.error/hicasso-intent-needs-the-event` | The invoker is value-first — no DOM event at argument one. Use `h/event`, which receives every argument in order |
| Dispatch from a timeout or interval throws | `:rf.error/no-frame-context` | Own the async work in an fx handler (`:dispatch-later`); a closure handed to foreign code captures `(rf/capture-frame)` from the body |
| Enter commits half-typed Japanese text | none — hand-rolled key handling | Use the `:on-key-down` map; composition is centralised there |
| An intent fires but no handler runs | `:rf.error/no-such-handler` | Registration happens on namespace load; require the handlers namespace at boot |
| A vector at a declared host callback is rejected | `:rf.error/hicasso-intent-at-a-non-event-contract` | That position's declared contract does not dispatch; give it the value its contract asks for ([Interop](09-interop.md)) |
| A render prop that dispatches is rejected | `:rf.error/hicasso-dispatch-in-render-position` | Render props are pure output; move the dispatch to an event position ([Interop](09-interop.md)) |

## When not to use an intent

Do not use an intent when you need the event object itself and no dispatch:
pointer coordinates, `dataTransfer`, `stopPropagation`, a DOM measurement.
Those cases take ordinary functions, and an ordinary function is not a
failure. Use `h/event` when you want to read the arguments *and* dispatch. Use
a plain `fn` when you want to read the arguments and do other work.

## Advanced

### IME and the key map

The composition gate lives in the runtime, not in your view, because the
correct check is easy to get wrong in two ways. First, the signal is split.
Modern browsers set `isComposing` on the keyboard event, but some IME/browser
pairs only send the legacy `keyCode` 229, which means "this keystroke belongs
to the IME". The gate honours both signals. Second, the signal is on the
**native** event. React's synthetic keyboard event drops `isComposing`. A
hand-written handler that reads the synthetic object sees `undefined`, and it
commits a candidate-selection Enter as a submit. That is silent data loss for
every user who composes. The map therefore answers one question — "was this
keystroke the user's or the IME's?" — once, centrally, and it matches nothing
while composition is active.
