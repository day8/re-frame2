# Events as data

Hicasso accepts an event vector directly in an event attribute. The runtime
creates the callback and dispatches that vector when the callback runs.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

The Hiccup tree still contains `[:todo/toggle id]`, so tests and tools can
inspect and compare the interaction as data. The generated callback also
retains the frame of the view that created it, which makes the later browser
event safe even though the original render has ended.

Any prop named `on-` followed by a letter is treated as an event position.
CamelCase spellings such as `onClick` are also accepted for migration. Hicasso
does not maintain a fixed roster of DOM event names. An event vector in one of
these positions is called an [intent](glossary.md#intent).

## Read values from the browser event

Most input handlers need `.value` or `.checked` from the event target. Hicasso
replaces [`::h/value`](glossary.md#hvalue) and
[`::h/checked`](glossary.md#hchecked) when the callback runs:

```clojure
[:input {:value    (h/sub [:todo.ui/draft id])
         :on-input [:todo.ui/edit id ::h/value]}]

[:input {:type      :checkbox
         :checked   (h/sub [:todo/done? id])
         :on-change [:todo/set-done id ::h/checked]}]
```

The dispatched events are ordinary vectors such as
`[:todo.ui/edit 7 "milk"]` and `[:todo/set-done 7 true]`.

Marker replacement occurs only at the top level of the intent vector. Hicasso
does not search nested data. When an intent contains no marker, the runtime
does not read the DOM event.

The full reserved vocabulary is `::h/value`, `::h/checked`,
[`::h/prevent`](glossary.md#hprevent), and
[`::h/revision`](glossary.md#hrevision). The controlled-input chapter owns the
round trip from subscription value to browser event and back.

## Prevent browser defaults explicitly

An intent does not automatically call `preventDefault`, including at
`:on-submit`. Wrap the intent when the browser default must be prevented:

```clojure
[:form {:on-submit [::h/prevent [:todo/submit]]}
 [:input {:value    (h/sub [:todo.ui/draft])
          :on-input [:todo.ui/set-draft ::h/value]}]
 [:button {:type :submit} "Add todo"]]
```

`[::h/prevent INTENT]` prevents the default and dispatches the one inner intent.
It also works for an anchor being used as an application control:

```clojure
[:a.nav-link
 {:href "#"
  :on-click [::h/prevent [:todo/filter-active]]}
 "Active"]
```

A real navigation link should normally use the routing module rather than this
pattern. A modifier-click on a real link must remain available to the browser,
which is why Hicasso does not prevent clicks or submits by default.

!!! warning "A bare submit intent still performs the browser submission"
    `{:on-submit [:todo/submit]}` dispatches the event and then allows the
    browser default. If the page reloads, wrap the intent with `::h/prevent`.
    Omit the wrapper only when a real browser form submission is intended.

The wrapper must contain exactly one inner intent vector. A keyword instead of
a vector, a second payload, or a nested decorator raises
`:rf.error/hicasso-malformed-prevent` during rendering and names the attribute.
Markers remain valid inside the inner intent:

```clojure
[::h/prevent [:filter/set ::h/value]]
```

The wrapper is represented in the vector rather than metadata because metadata
does not participate in `=`, printing, or hashes. Structural tests and tools
must be able to observe the prevention decision.

## One callback form: `h/fn`

When a vector is not enough — a file list, drag payload, value-first foreign
callback, or any calculation over the real arguments — use
[`h/fn`](glossary.md#hfn). It expands to an **ordinary function**. The
contract comes from the **position** where that function is written, not from
a second API:

```clojure
[:input {:type      "file"
         :on-change (h/fn [e]
                      [:todo/attach
                       (js/Array.from (.. e -target -files))])}]
```

| Position | Contract for `h/fn` (and for an intent at that slot) |
| --- | --- |
| Native `:on-*` event prop | **event** — a returned vector is dispatched; other returns are ignored |
| `defhost` `:callbacks` entry | As **declared** (`:event`, `:handler`, or `:render`) |
| Other walked prop (for example a foreign render prop) | **render** — pure; return is output; dispatching inside is a loud error naming the position |
| `defhost` prop with no callback claim, or a declared ReactNode slot | **none** — refused with `:rf.error/hicasso-host-unclaimed-callback` |
| `:ref` | React's own contract; not lowered by Hicasso |
| Positions Hicasso does not walk | Plain function behaviour |

Rules that follow:

- `h/fn` captures the current frame where it is created.
- The body receives every callback argument in the caller's order.
- At an **event** position, a returned vector is dispatched; `nil` dispatches
  nothing.
- An `h/fn` body may do imperative browser work such as `.preventDefault`. The
  `::h/prevent` wrapper is for the data-only intent form.
- Ordinary unmarked functions remain legal and cross by identity, so there is
  no second “identity-preserving” form.

```clojure
[:div {:on-drop (h/fn [e]
                  (.preventDefault e)
                  (when-let [f (aget (.. e -dataTransfer -files) 0)]
                    [:todo/attach-dropped (.-name f)]))}]
```

Marker-carrying intents assume an **event-first** invoker: they read the DOM
event from argument one. A value-first foreign component has no event there,
so a marker-carrying intent raises
`:rf.error/hicasso-intent-needs-the-event`. Use `h/fn` and name the real
arguments:

```clojure
(h/fn [date _event]
  [:todo/set-due date])
```

## Ordinary functions remain available

Use a normal function when the callback is imperative and does **not**
represent a re-frame event:

```clojure
[:canvas
 {:on-pointer-move
  (fn [e]
    (draw! (.-clientX e) (.-clientY e)))}]
```

Typical cases include pointer geometry, pointer capture, `stopPropagation`, or
an SDK call that is not an application event. Foreign render props and slots
also use ordinary functions when the position is pure; dispatching from a
render position raises `:rf.error/hicasso-dispatch-in-render-position`.

Do not hand-roll an ambient dispatch closure:

```clojure
;; Don't
[:button
 {:on-click (fn [_]
              (rf/dispatch [:todo/toggle id]))}
 "✓"]
;; :rf.error/no-frame-context when the click runs

;; Do
[:button {:on-click [:todo/toggle id]} "✓"]
```

The browser invokes the callback after the rendering extent has gone, so an
ambient `rf/dispatch` has no frame. Intents and `h/fn` capture that context
when the view is rendered.

## Keyboard maps

A keyboard event position may contain a map from the DOM `.key` string to an
intent:

```clojure
[:input {:value       (h/sub [:todo.ui/draft id])
         :on-input    [:todo.ui/edit id ::h/value]
         :on-key-down {"Enter"  [:todo.ui/commit id]
                       "Escape" [:todo.ui/cancel id]}}]
```

Unlisted keys do nothing. The keys are strings, and keyboard maps are valid
only at `:on-key-down` and `:on-key-up`. There is no modifier grammar; use
`h/fn` when the handler must inspect combinations such as Ctrl+Enter.

Keyboard maps also suppress application shortcuts during IME composition.
Enter may be choosing a composition candidate and Escape may be cancelling the
composition, so neither should dispatch the application's commit or cancel
intent. The runtime performs this check centrally, including legacy browser
signals described under [Advanced](#advanced).

## Frame-safe callbacks and `h/frame`

Generated intent callbacks and `h/fn` callbacks retain their view's frame.
Application-owned async work should normally move to the event/effect layer,
where an fx handler already receives the frame id in its context and
`:dispatch-later` expresses delay as data.

A Hicasso view body does **not** have ambient frame lookup. Zero-arity
`(rf/capture-frame)` refuses under Hicasso's render discipline. The author-
facing frame **read** is [`h/frame`](glossary.md#hframe): a plain function,
legal only during a boundary body (or a render callback that boundary
supplied), that returns the current frame **id keyword**. It is not a tracked
subscription.

The carry spelling is composition with core's capture primitive:

```clojure
(ns app.map
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [app.sdk :as sdk]))

(h/defview map-panel [{:keys [id]}]
  (let [{:keys [dispatch]} (rf/capture-frame (h/frame))]
    [:div.map
     {:ref (fn [node]
             (when node
               (sdk/on-select
                 node
                 #(dispatch [:map/marker-selected id %]))))}]))
```

`(rf/capture-frame (h/frame))` returns
`{:frame :dispatch :dispatch-sync :subscribe}` bound to that frame. Prefer this
at a foreign edge you do not control — an SDK attach ref, a value-first
callback, a host slot that retains a closure.

A captured handle remains valid for that frame incarnation. Destroying the
frame and creating another under the same id does not revive the old handle;
using it raises `:rf.error/frame-destroyed` and does not reach the successor.
Capture during the live render rather than keeping a global stash. Do not put
the frame id into markup: on the server it is process-local identity and would
break deterministic render-twice checks.

Calling `h/frame` outside a Hicasso render extent raises
`:rf.error/hicasso-frame-outside-boundary`.

The practical rule is:

- use an intent for an ordinary dispatching event
- use an effect for application-owned async work
- use `(rf/capture-frame (h/frame))` for a closure retained by foreign code

A link whose job is navigation belongs to the routing module's route-link
surface rather than a custom click handler.

## Troubleshooting

| Symptom | Error or cause | Fix |
| --- | --- | --- |
| A form dispatches and then reloads the page | Browser submission was not prevented | Use `{:on-submit [::h/prevent [:todo/submit]]}` |
| Rendering reports a malformed prevent wrapper | `:rf.error/hicasso-malformed-prevent` | Wrap exactly one inner intent vector; do not nest decorators or add a second payload |
| A handler receives the literal `::h/value` keyword | The marker was nested below the vector's top level | Keep the marker at top level or calculate the payload with `h/fn`/the event handler |
| A foreign callback rejects an intent that needs the event | `:rf.error/hicasso-intent-needs-the-event` | The callback is value-first. Use `h/fn` and receive its actual arguments |
| Dispatch from a timer or interval throws | `:rf.error/no-frame-context` | Move application async work to an effect. For foreign retention, capture with `(rf/capture-frame (h/frame))` during rendering |
| `h/frame` raises `:rf.error/hicasso-frame-outside-boundary` | No Hicasso render extent | Call it only inside a view body or a render callback that body supplied |
| Enter commits unfinished IME text | A hand-written key handler bypassed the keyboard map | Use the keyboard map so composition events are suppressed centrally |
| An intent fires but no handler runs | `:rf.error/no-such-handler` | Require the namespace that registers the handler before mounting |
| A vector is rejected at a host callback | `:rf.error/hicasso-intent-at-a-non-event-contract` | That host position is not declared as an event contract; supply the value its declaration requires |
| A render prop dispatches | `:rf.error/hicasso-dispatch-in-render-position` | Keep render props pure and move the dispatch to an event position |
| A captured callback reaches a destroyed frame | `:rf.error/frame-destroyed` | Recreate the callback from a render attached to the current frame incarnation |

## When not to use an intent

Use a plain function when you need the callback arguments but no dispatch:
pointer coordinates, `dataTransfer`, DOM measurement, `stopPropagation`, or an
imperative SDK operation.

Use `h/fn` when the arguments are needed to decide which event vector to
dispatch. An ordinary function is not an error; the intent form is simply the
normal choice for declarative application interactions.

## Advanced

### IME detection in keyboard maps

IME composition is signalled in more than one way. Modern browsers expose
`isComposing` on the native keyboard event, while some IME/browser combinations
use legacy `keyCode` 229. React's synthetic keyboard event may not preserve the
native `isComposing` value.

The runtime checks the native event and both signals. While composition is
active, a keyboard map matches no application intent. Keeping this check in the
runtime avoids treating candidate-selection Enter as submit or composition
Escape as application cancel, both of which can discard user input.
