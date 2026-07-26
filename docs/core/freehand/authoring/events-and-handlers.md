# Events and handlers

You need the view to **tell re-frame what happened** without burying intent in
closures. The paved path is an **event vector** on the control.

```clojure
[:button {:on-click [:cart/add product-id]} "Add to cart"]
```

When the button is clicked, Freehand dispatches `[:cart/add product-id]` to the
frame this view is running under. Everywhere else in re-frame2, intent is already
data; Freehand keeps the last mile the same.

> **A handler is data until it can’t be — and each non-data form names exactly
> what it needs.**

Vectors win for ordinary UI work because they are readable, assertable on a
structural tree, free of stale render closures, and friendly to memo.

## The paved path: handlers as data

The everyday handler is not a function. It is an **event vector** as above.

## Projections: live scalars in a data event

Sometimes the event needs a value that only exists when the browser fires — the
current text of an input, whether a checkbox is checked, which key was pressed.
Freehand reserves **projection markers** for that:

```clojure
[:input {:on-input  [:form/typed :email ::v/value]}]
[:input {:type :checkbox :on-change [:prefs/set :dark ::v/checked]}]
[:div   {:on-key-down [:editor/key ::v/key]}]   ; "Enter", "a", …
```

Five markers carry names, and `v/projections` is the whole named roster:

```clojure
v/projections
;; => #{::v/value ::v/checked ::v/key ::v/scroll-top ::v/new-state}
```

Each is sugar for the **general read door**, `[::v/read path]`, which reads any
other shallow scalar off the live event the same way. A keyword is a property of
the event itself; a vector is a chain walked from it:

```clojure
[:div {:on-scroll [:pane/scrolled [::v/read [:target :scrollLeft]]]}]
[:div {:on-key-down [:editor/chord [::v/read :altKey]]}]
```

`::v/value` is exactly `[::v/read [:target :value]]`, and `::v/scroll-top` is
`[::v/read [:target :scrollTop]]` — the named markers exist because those reads
are frequent, not because they are privileged.

What is closed is the **property**, not the set of names. A read is admitted only
when it resolves to a shallow scalar — string, number, boolean, keyword — which is
what keeps a projected read assertable by equality, printable, and host-neutral.
Anything richer (two reads, arithmetic, a live host object) is `v/event`'s job.

At fire time Freehand fills every matching **top-level** marker, then ordinary
`dispatch` runs on the finished vector.

- Nested markers stay ordinary data — a projection keyword inside an ordinary
  domain event is never secretly interpreted.  
- Position zero (the event id) must not be a marker.  

One function does all of it, and it is public: `v/materialize-event`. Literals,
forwarded `conj`s, options maps, `v/event` bodies, interpreted and compiled,
production and test all run through it, which is why general `rf/dispatch` needs
no payload arity. Tests call it directly ([Testing](../operate/testing.md)) rather than
growing a second splice path.

## Options maps

When you need ordinary DOM listener options, use a shallow map with a closed
vocabulary (`:prevent-default`, `:stop-propagation`, `:capture`, `:passive`,
`:once`):

```clojure
[:form {:on-submit {:event [:signup/requested] :prevent-default true}}]
```

The event is still data. The mechanics are data too, instead of a wrapper function
that calls `.preventDefault`.

## Keyboard branching

There is no key-condition map. A handler slot holds **one** handler form, so
branching on which key was pressed is one of two ordinary spellings.

Send the key with the event and branch in the handler, where the decision can also
see application state:

```clojure
[:input {:on-key-down [:picker/key-pressed ::v/key]}]
```

Or branch synchronously at the site with `v/event`, when the browser mechanics
(`preventDefault`) depend on which key it was:

```clojure
[:input {:on-key-down (v/event [e]
                        ;; IME: do not treat Enter as accept mid-composition
                        (when-not (.-isComposing e)
                          (case (.-key e)
                            "Enter"  [:picker/accept]
                            "Escape" [:picker/close]
                            nil)))}]
```

Prefer the first (data vector + handler branch) when you can. It stays visible to
tools and decides against the committed frame. When you must branch in the view
for browser mechanics (`preventDefault`, IME), keep the body tiny — and **skip
Enter while `isComposing`** so partial IME text is never accepted as a commit.

## One event per user action

An event-producing site yields **exactly one** event vector, or `nil`. A vector of
vectors is an error.

If one click must do several things, write **one** domain event and return the
effects from its handler — one epoch, one cause, one place to reason.

## Controlled inputs

```clojure
[:input {:value (v/sub [:form/email])
         :on-input [:form/typed :email ::v/value]}]
```

A **controlled** native node has `:value` or `:checked` in props (key present,
including `nil`). On **`:on-input`** and **`:on-change` only**, eligible handlers
ride the **synchronous door**:

1. materialize projections  
2. dispatch  
3. drain re-frame  
4. flush dirty Freehand cells on **this frame**  
5. then return into React  

That same-tick round trip protects characters, caret, and IME.

Eligible: vector, options map with a vector, or synchronous `v/event` → vector/`nil`.
Not door-eligible: `v/handler`, promises, foreign components (they own timing).
Blur, keydown, and friends stay ordinary sites — they do not run the door flush.

**`:on-before-input` is not on the door** (fires before the DOM mutation). Treat it
as an ordinary listener.

Other dirty cells on the same frame may ride the flush — keystroke latency can
couple to background work. Measure under load when it matters.

Forwarding attrs onto a controlled input: use **`v/spread-safe`** (preserves door
proof). Plain `v/spread` does not. Details:
[Composition — spreading props](composition.md#spreading-props-attribute-forwarding).

## Three ways to wire a text field

No controller required for ordinary forms. Day to day, pick A, B, or (rarely) C.

### A — Live domain field (simplest)

Each keystroke **is** the product value — live filter, simple form field, autosave
path.

```clojure
[:input {:value    (v/sub [:lines/label id])
         :on-input [:lines/set-label id ::v/value]}]
```

Usually you only need the `on-input` event. Enter might submit a surrounding form,
or do nothing special for this field.

### B — App-owned draft, then commit (still no controller)

You want: type freely → accept on blur or Enter → cancel on Escape — but only in
**this** app, with **your** app-db keys.

```clojure
[:input {:value       (v/sub [:lines/draft id])
         :on-input    [:lines/draft-changed id ::v/value]
         :on-blur     [:lines/label-committed id]
         :on-key-down [:lines/draft-key id ::v/key]}]
```

`:lines/draft-key` is the branch point, and it is an ordinary handler:

```clojure
(rf/reg-event :lines/draft-key
  (fn [{:keys [db]} [_ id k]]
    (case k
      "Enter"  {:fx [[:dispatch [:lines/label-committed id]]]}
      "Escape" {:fx [[:dispatch [:lines/draft-cancelled id]]]}
      {:db db})))
```

**Which events should match?**

| Sites | Same event? | Why |
|---|---|---|
| `on-input` vs Enter | **No** | Keystroke updates the **draft**; Enter means **accept** |
| `on-blur` vs Enter | **Yes** | Both are the **commit** transition |
| Escape | Different | **Cancel** — clear draft, do not write the domain value |

Commit handlers usually **read the draft from app-db**. You typically do not need
`::v/value` on Enter or blur. Put `::v/value` on `on-input`, where the new string
arrives from the DOM.

You own cleanup: clear drafts in cancel and commit handlers, and when leaving the
form or route if needed. Unmount does not invent cleanup for you.

### C — Library semantic controller (optional)

Same *kind* of user experience as B, but packaged for reuse: stale blur after
cancel, required reset generation, many call sites. The call site looks like a
`buffered-field` with a `:control` address.

That control is **not** core Freehand. See
[Semantic controllers](../advanced/semantic-controllers.md).

### Choosing quickly

| Need | Pattern |
|---|---|
| Keystroke is the truth | **A** |
| Draft then commit, app-specific | **B** |
| Same hard protocol everywhere | **C** (library) |

## When not this path

- **Uncontrolled** (`:default-value`, no `:value`) when you deliberately want zero
  per-keystroke re-frame traffic — see [below](#uncontrolled-inputs-deliberate-escape).  
- **`v/handler` / bare fn** on a controlled input when you need the sync door —
  you forfeit caret/IME protection. Prefer a vector or `v/event` → vector/`nil`.  
- **Raw `createElement` props** for Freehand intent — use `v/defhost` for roster
  callbacks, or a closure over `rf/capture-frame` on the escape path.

## Recap

Stop when you can:

- put event vectors on buttons and forms  
- use `::v/value` / `::v/checked` / `::v/key` for live scalars, and
  `[::v/read path]` for anything else shallow  
- wire pattern **A** or **B** for a text field  
- keep multi-step work as **one** domain event + effects  

## Troubleshooting

| Symptom | Fix |
|---|---|
| Vector of vectors on one click | one semantic event; effects do the rest |
| Nested `::v/value` “did nothing” | markers only at **top-level** event args |
| Caret/IME broken while typing | controlled door: `:value`/`:checked` + `:on-input`/`:on-change` as a vector or `v/event` → vector |
| Enter commits mid-IME composition | guard with `(.-isComposing e)` (or skip Enter until `compositionend`) |
| Stale “still open?” in a callback | decide in the re-frame handler at fire time |
| Bare fn at a prop position Freehand WALKS | `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` |
| `v/event` in a raw `createElement` `#js` prop | plain closure over `rf/capture-frame`, or declare `v/defhost` |
| `:rf.error/no-frame-context` from a library callback | close over `(rf/capture-frame)`'s `:dispatch` during render |

## Each keystroke is an event

On patterns A–C, while the field is controlled and typing updates re-frame,
**each keystroke dispatches a new re-frame event**. Freehand does not batch
keystrokes into one event for free.

What that means in practice:

- Typing `hello` is about five edit events, plus whatever those runs emit.  
- In **Xray**, the event list will look busy while typing. That is expected, not a
  bug.  
- Filter by event id (draft-changed vs committed), or focus on commit events when
  you care about “what was accepted.”  
- Keep edit handlers cheap and subscriptions narrow. Latency is roughly “one event
  path per character.”  

If you truly want **zero** per-keystroke re-frame traffic, that is a different
design — for example a host-owned buffer and one event on commit only. That is an
escape hatch, not the paved controlled path, and you give up mid-edit
inspectability in app-db and Xray.

## Uncontrolled inputs (deliberate escape)

The paved path is **controlled** (`:value` / `:checked` present → synchronous
door). Sometimes a large grid or edit-in-place cell wants **zero mid-edit
app-db traffic**: the DOM holds text until commit.

```clojure
;; Uncontrolled: no :value key — not on the Freehand door
[:input {:default-value raw
         :auto-focus    true
         :on-blur       [:cells/commit-edit id ::v/value]
         :on-key-down   (v/event [e]
                          (case (.-key e)
                            "Enter"  [:cells/commit-edit id (.. e -target -value)]
                            "Escape" [:cells/cancel-edit id]
                            nil))}]
```

| You gain | You give up |
|---|---|
| No per-keystroke events / writes | Mid-edit text **not** in app-db or Xray |
| Cheap for huge edit grids | No controlled-door caret/IME guarantee from Freehand |
| Simple commit-on-blur | Harder structural tests of “what is typed right now” |

Rules:

- **Do not mix** `:value` and `:default-value` on the same node as a clever hybrid.  
- Prefer controlled A/B for ordinary forms; use uncontrolled when cost measurement
  shows per-keystroke state is the problem (fitness harness cells-style).  
- Commit still goes through **one** re-frame event; cleanup still is not unmount
  magic.

## Forwarding intent through components

Parents pass data. The child’s own site dispatches in its committed frame:

```clojure
(v/defview icon-button [{:keys [event label]}]
  [:button {:aria-label label :on-click event}
   label])

[icon-button {:event [:item/deleted item-id] :label "Delete"}]
```

A library control that cannot know your full event grammar often takes an **event
prefix** and appends the live payload at its own DOM site:

```clojure
;; caller
[text-field {:value email :on-change [:form/set-email]}]

;; inside text-field
[:input {:value value
         :on-input (conj on-change ::v/value)}]
```

## The escape roster

When a vector is not enough, pick the form that matches the job:

| You need | Write | Notes |
|---|---|---|
| Dispatch intent (the common case) | `[:event … ::v/value]` | data; canonical |
| Live event — files, geometry, filters | `(v/event [e] …)` | return one vector or `nil`; no `sub`, hooks, or refs inside |
| Imperative foreign work | `(v/handler [x] …)` | return ignored; stable per site |
| Callback invoked **while rendering** | `(v/render-fn [x] …)` | pure; no dispatch or app state |
| Identity-as-protocol APIs | `(v/raw-fn f)` | identity passes through unchanged |
| React hooks / portals / context | a React component, entered through the child fold | not neutral Freehand |
| Owning a DOM node imperatively | `v/defbehavior` + `[v/behavior …]` | [Host boundaries](../host/host-boundaries.md) |
| A callback in a foreign element's raw `#js` props | a plain closure over `(rf/capture-frame)`'s `:dispatch` | those props are the library's ABI — see below |

**Phase rule:** event handlers see **committed** values — what the user actually
saw. Render callbacks see the **current** render. One bare function cannot honestly
promise both.

There is no generic `v/dispatcher` helper, and no dependency-annotated `v/event`.
Foreign argument conversion is `v/event`.

### Where the roster reaches

The roster forms are **site-owned**, and a site is a prop position Freehand
itself walks: a native `:on-*` prop, a declared view's props map, a `v/slot`.
Those are the positions where Freehand can mint a stable proxy, publish a body
at commit, and retire the callback with its view.

A React element you build yourself with `react/createElement` is **not** such a
position. Its `#js` props are the library's own ABI and the child fold passes the
finished element through untouched, so nothing materialises a roster carrier that
sits inside them — the library receives the carrier object itself, which is
deliberately **not the function it wraps** (`v/event` returns a marker) and so
cannot answer the call.

What you see when it fails depends on who is calling. Reach the carrier from
ClojureScript and you get a Freehand error naming the form you wrote, this position,
and the closure below. Reach it the way a JavaScript library actually does —
`props.onPing(…)`, a native call on a value that is not a native function — and you
get the host's own `TypeError`, worded by the engine after whatever expression it
tripped on (`props.onPing is not a function`) and naming nothing you wrote, because
a call JavaScript never routes through the carrier is a call nothing can intercept.
The carrier is a marker object, not a function, and making it one would quietly turn
it into a function for everything else that asks. Either way it cannot work, which is
the part worth knowing.

So a callback there is an ordinary closure, and it closes over
`(rf/capture-frame)`'s `:dispatch` rather than calling `rf/dispatch`: the render
scope has unwound by the time the library calls back, and the captured bundle is
fenced to the exact frame incarnation it was taken from.

The trade is explicit. A closure gets no site-owned identity (it is fresh per
render, so a library memoising on callback identity sees a changed prop) and is
not retired with the view. When either matters, own the node with a behavior and
mint the callback once in `:connect`, where it lives in the connection's private
memory and the context's `:dispatch` is generation-fenced.

## Callback identity and lifetime

Each runtime event or handler site is owned by a pair: the **committed node
identity** and the **callback prop** (`:on-click`, `:on-change`, and so on) —
a prop Freehand walks, per [the roster's reach](#where-the-roster-reaches). Node
identity is key-aware among siblings.

A few laws that keep foreign libraries and memoisation sane:

| Law | Meaning |
|---|---|
| Equal values at **two sites** | two independent callbacks — they never share `:once` state or lifecycle |
| Unchanged handler value | the **same** JS function across re-renders of that site |
| Changed handler value | re-mint at commit with the new body |
| Abandoned render | never updates live callbacks |
| Disconnect, key change, HMR fence | that exact callback is **retired** — if something still calls it, it stays inert and does not dispatch into a replacement frame |
| `v/render-fn` | not on that mutable-proxy scheme; it may run during an uncommitted foreign render |

That is the whole reason the roster exists at a position Freehand walks: without
an annotation Freehand cannot know the phase or the identity contract the protocol
wants. It also draws the boundary of the guarantee — a closure you hand a
foreign element's `#js` props gets none of these laws, because Freehand never saw
the prop.

## Decisions that depend on live state

When acceptance depends on changing application state — stale blur after cancel,
late dropdown select — put identity or generation in the intent if you need them,
but make the **decision in the re-frame handler** against the committed frame at
fire time.

Do not close over a render-time “still editing?” guard inside a minted callback and
hope it is still true later. That is the classic race Freehand is trying to make
unattractive.
