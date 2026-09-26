# Events as data

Fresco accepts an event vector directly in an event attribute. The runtime
creates the callback and dispatches that vector when the callback runs.

```clojure
[:button {:on-click [:todo/toggle id]} "✓"]
```

The Hiccup tree still contains `[:todo/toggle id]`, so tests and tools can
inspect and compare the interaction as data. The generated callback also
retains the frame of the view that created it, which makes the later browser
event safe even though the original render has ended.

Any prop named `on-` followed by a letter is treated as an event position, and
so is the camelCase spelling (`:onClick`). Fresco keeps no fixed list of DOM
event names. An event vector in one of these positions is called an
[intent](glossary.md#intent).

## Read values from the browser event

Most input handlers need `.value` or `.checked` from the event target. Fresco
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

Marker replacement occurs only at the top level of the intent vector. Fresco
does not search nested data. When an intent contains no marker, the runtime
does not read the DOM event.

On a `<select multiple>`, `::h/value` is a vector of the selected option
values. A file input has no usable value, so `::h/value` there raises
`:rf.error/fresco-file-input-value-marker`; read `.files` with `h/event`
instead, as [shown below](#one-callback-form-hevent).

The full reserved vocabulary is `::h/value`, `::h/checked`,
[`::h/prevent`](glossary.md#hprevent), and
[`::h/revision`](glossary.md#hrevision). The controlled-input chapter owns the
round trip from subscription value to browser event and back.

## Prevent browser defaults explicitly

An intent at `:on-submit` prevents the browser's form submission for you,
because a form that dispatches and then reloads the page is never what the
application meant. It is the only position that prevents by default.

```clojure
[:form {:on-submit [:todo/submit]}
 [:input {:value    (h/sub [:todo.ui/draft])
          :on-input [:todo.ui/set-draft ::h/value]}]
 [:button {:type :submit} "Add todo"]]
```

At every other position, wrap the intent when the browser default must be
prevented — most often an anchor being used as an application control:

```clojure
[:a.filter
 {:href "#"
  :on-click [::h/prevent [:todo/set-showing :active]]}
 "Active"]
```

`[::h/prevent INTENT]` prevents the default and dispatches the one inner intent.
A real navigation link should use `h/route-link` instead (see [Routing and
navigation](07-routing-and-navigation.md)). Fresco does not prevent clicks by
default because a modifier-click on a real link must remain available to the
browser.

The wrapper must contain exactly one inner intent vector. A keyword instead of
a vector, a second payload, or a nested decorator raises
`:rf.error/fresco-malformed-prevent` during rendering and names the attribute.
Markers remain valid inside the inner intent:

```clojure
[::h/prevent [:todo.ui/set-draft ::h/value]]
```

The wrapper is represented in the vector rather than metadata because metadata
does not participate in `=`, printing, or hashes. Structural tests and tools
must be able to observe the prevention decision.

A callback at `:on-submit`, whether `h/event` or a plain function, is not
auto-prevented: call `.preventDefault` yourself, or leave it out when a real
browser submission is intended.

## One callback form: `h/event`

When a vector is not enough — a file list, drag payload, value-first foreign
callback, or any calculation over the real arguments — use
[`h/event`](glossary.md#event). It expands to an ordinary function, and the
position where you write it decides what its return value means:

```clojure
[:input {:type      "file"
         :on-change (h/event [e]
                      [:todo/attach
                       (js/Array.from (.. e -target -files))])}]
```

In an `on-*` prop, a returned vector is dispatched and any other return is
ignored. At a foreign component's render prop the same form returns rendered
output instead; [Interop](09-interop.md#callback-contracts) covers those
positions.

Rules that follow:

- A returned vector is dispatched to the frame of the view that rendered the
  callback.
- The body receives every callback argument in the caller's order.
- An `h/event` body may do imperative browser work such as `.preventDefault`.
  The `::h/prevent` wrapper is for the data-only intent form.
- Plain functions remain legal and reach React unchanged.

```clojure
[:div {:on-drop (h/event [e]
                  (.preventDefault e)
                  (when-let [f (aget (.. e -dataTransfer -files) 0)]
                    [:todo/attach-dropped (.-name f)]))}]
```

A marker-carrying intent reads the DOM event from argument one, so a
value-first foreign callback needs `h/event`
([Interop](09-interop.md#event-positions)).

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
also use ordinary functions when the position is pure: the return is the render
output, and nothing is dispatched from it. A plain function is not an error;
the intent form is the normal choice for application interactions.

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

The browser calls the handler after rendering has finished, so a plain
`rf/dispatch` there has no frame. Intents and `h/event` record the view's frame
when the view renders.

## Keyboard maps

A keyboard event position may contain a map from the DOM `.key` string to an
intent:

```clojure
[:input {:value       (h/sub [:todo.ui/draft id])
         :on-input    [:todo.ui/edit id ::h/value]
         :on-key-down {"Enter"  [:todo.ui/commit id]
                       "Escape" [:todo.ui/cancel id]}}]
```

Unlisted keys do nothing. The map's keys are the DOM `.key` strings, and a map
value may also be an `h/event` or a plain function. Use keyboard maps on
keyboard events such as `:on-key-down` and `:on-key-up`: at a position whose
event has no `.key`, the handler raises `:rf.error/fresco-intent-needs-the-event`.
There is no modifier syntax; use `h/event` when the handler must inspect
combinations such as Ctrl+Enter.

Keyboard maps also suppress application shortcuts during IME composition.
Enter may be choosing a composition candidate and Escape may be cancelling the
composition, so neither should dispatch the application's commit or cancel
intent. The runtime performs this check centrally, including legacy browser
signals described under [Advanced](#advanced).

<a id="frame-safe-callbacks-and-hframe"></a>
<a id="frame-safe-callbacks"></a>
## Frame-safe callbacks and `rf/capture-frame`

Intent callbacks and `h/event` callbacks dispatch to the frame of the view
that rendered them, or to the frame a nested `h/frame-root` / `h/frame-provider`
names for the markup below it. Application-owned async work should normally
move to the event and effect layer, where an effect handler already receives
the frame id and `:dispatch-later` expresses a delay as data.

- use an intent for an ordinary dispatching event
- use an effect for application-owned async work
- use `(rf/capture-frame)` for a closure retained by foreign code

For a closure that foreign code keeps and calls later, capture the frame while
the view renders. A plain `rf/subscribe` or `rf/dispatch` written in a view
body throws `:rf.error/ambient-frame-refused`: the read would not be tracked,
and the dispatch would run during rendering. Two core functions are allowed in
a body because they neither read nor dispatch: `(rf/current-frame-id)` returns
the rendering view's frame id, and zero-arity `(rf/capture-frame)` returns a
handle locked to that frame. They are the same functions the Reagent and UIx
adapters use.

```clojure
(ns app.todos.sortable
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [app.sdk :as sdk]))

(h/defview sortable-list [_]
  (let [{:keys [dispatch]} (rf/capture-frame)]
    [:ul.todo-list
     {:ref (fn [node]
             (when node
               (sdk/on-reorder node #(dispatch [:todo/reorder %]))))}]))
```

`(rf/capture-frame)` returns `{:frame :dispatch :dispatch-sync :subscribe}`
bound to that frame. Use it where foreign code you do not control keeps a
closure: an SDK attached from a ref, a value-first callback, a host slot.

## Troubleshooting

| Symptom | Error or cause | Fix |
| --- | --- | --- |
| A form dispatches and then reloads the page | An `h/event` or plain-function `:on-submit` — a callback owns its own event and is never auto-prevented | Call `.preventDefault` in the callback, or use the data spelling `{:on-submit [:todo/submit]}`, which prevents for you |
| Rendering reports a malformed prevent wrapper | `:rf.error/fresco-malformed-prevent` | Wrap exactly one inner intent vector; do not nest decorators or add a second payload |
| A handler receives the literal `::h/value` keyword | The marker was nested below the vector's top level | Keep the marker at top level or calculate the payload with `h/event`/the event handler |
| A foreign callback rejects an intent that needs the event | `:rf.error/fresco-intent-needs-the-event` | The callback is value-first. Use `h/event` and receive its actual arguments |
| An event vector raises `:rf.error/fresco-intent-outside-boundary` | The event vector was turned into a callback outside any view's render, for example inside a function a foreign component calls later | Keep event vectors in Hiccup a view returns; inside a foreign callback, use `h/event` |
| Dispatch from a timer or interval throws | `:rf.error/no-frame-context` | Move application async work to an effect. For foreign retention, capture with `(rf/capture-frame)` during rendering |
| `rf/subscribe` or `rf/dispatch` in a view body raises `:rf.error/ambient-frame-refused` | Fresco view bodies refuse untracked reads and render-time dispatches | Read with `h/sub`; dispatch through an intent, `h/event`, or a handle from `(rf/capture-frame)` |
| `(rf/capture-frame)` in a body raises `:rf.error/ambient-frame-refused` naming two frames | An enclosing `rf/with-frame` names a frame the view is not rendering | Drop the enclosing scope, or scope it to the view's own frame |
| Enter commits unfinished IME text | A hand-written key handler bypassed the keyboard map | Use the keyboard map so composition events are suppressed centrally |
| An intent fires but no handler runs | `:rf.error/no-such-handler` | Require the namespace that registers the handler before mounting |
| A captured callback reaches a destroyed frame | `:rf.error/frame-destroyed` | Recreate the callback from a render attached to the current frame incarnation |

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

### Captured handles: lifetime and errors

A captured handle is valid for that frame's lifetime. Destroying the frame and
creating another under the same id does not revive the old handle: a call
through it is dropped and reported as `:rf.error/frame-destroyed`, and never
reaches the new frame. Capture
during rendering rather than keeping a global stash. Do not render the frame id
into markup: on the server it is process-local, and it would break the
determinism check (`re-frame.fresco.test.server/render-twice`).

Outside any frame scope, `rf/current-frame-id` and `rf/capture-frame` raise
`:rf.error/no-frame-context`. An enclosing `rf/with-frame` naming a frame other
than the one the view renders raises `:rf.error/ambient-frame-refused`, naming
both frames.
