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

## Projections: three live scalars

Sometimes the event needs a value that only exists when the browser fires — the
current text of an input, whether a checkbox is checked, which key was pressed.

Freehand reserves three **projection markers** for that:

```clojure
[:input {:on-input  [:form/typed :email ::v/value]}]
[:input {:type :checkbox :on-change [:prefs/set :dark ::v/checked]}]
[:div   {:on-key-down [:editor/key ::v/key]}]   ; "Enter", "a", …
```

At fire time Freehand fills every matching **top-level** marker, then ordinary
`dispatch` runs on the finished vector.

- Nested markers stay ordinary data — not magic.  
- Position zero (the event id) must not be a marker.  
- Marker present but payload unavailable → typed error, no dispatch.  

The same materializer covers literals, forwarded vectors, options maps, `v/event`
results, both modes, production and tests. Reuse it in tests
([Testing](testing.md)) — no second splice path. General `rf/dispatch` does **not**
grow a payload-map arity; that is Freehand’s job at the event site.

## Options maps

When you need ordinary DOM listener options, use a shallow map with a closed
vocabulary (`:prevent-default`, `:stop-propagation`, `:capture`, `:passive`,
`:once`):

```clojure
[:form {:on-submit {:event [:signup/requested] :prevent-default true}}]
```

The event is still data. The mechanics are data too, instead of a wrapper function
that calls `.preventDefault`.

## Keyboard conditions

For simple key branching on `:on-key-down` and `:on-key-up`, Freehand allows one
extra closed data form: a map from exact key strings to handler forms.

```clojure
{:on-key-down
 {"Enter"     {:event [:picker/accept] :prevent-default true}
  "Escape"    [:picker/close]
  "ArrowDown" {:event [:picker/move 1] :prevent-default true}}}
```

Keys are exact `KeyboardEvent.key` values (`"Enter"`, not a key code). Values are
the same handler shapes you already know: vector, options map, `v/event`, or
`nil`. Selection is one level deep. A missing key is a no-op. During IME
composition, no branch matches.

No wildcards, regexes, modifier chords, or state predicates in this map — use
`v/event` or a host boundary for those. The form is deliberately small; it may be
removed before release if pilots do not show repeated use.

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
[:input {:value   (v/sub [:lines/draft id])
         :on-input [:lines/draft-changed id ::v/value]
         :on-blur  [:lines/label-committed id]
         :on-key-down {"Enter"  [:lines/label-committed id]
                       "Escape" [:lines/draft-cancelled id]}}]
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
[Semantic controllers](semantic-controllers.md).

### Choosing quickly

| Need | Pattern |
|---|---|
| Keystroke is the truth | **A** |
| Draft then commit, app-specific | **B** |
| Same hard protocol everywhere | **C** (library) |

## Day-one checklist

Stop when you can:

- put event vectors on buttons and forms  
- use `::v/value` / `::v/checked` / `::v/key` for live scalars  
- wire pattern **A** or **B** for a text field  
- keep multi-step work as **one** domain event + effects  

## If something feels wrong

| Symptom | Fix |
|---|---|
| Vector of vectors on one click | one semantic event; effects do the rest |
| Nested `::v/value` “did nothing” | markers only at **top-level** event args |
| Caret/IME broken while typing | controlled door: `:value`/`:checked` + `:on-input`/`:on-change` |
| Stale “still open?” in a callback | decide in the re-frame handler at fire time |
| Bare fn on a foreign component prop | `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` |

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
         :on-blur       (v/event [e]
                          [:cells/commit-edit id (.. e -target -value)])
         :on-key-down   {"Enter"  (v/event [e]
                                    [:cells/commit-edit id (.. e -target -value)])
                         "Escape" [:cells/cancel-edit id]}}]
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
| React hooks / portals / context | UIx wrapper | not neutral Freehand |
| Quick local work on a **native** `:on-*` | bare `#(…)` | legal there only |
| Any callback on a **foreign** component | one of the explicit forms | bare fn is rejected |

**Phase rule:** event handlers see **committed** values — what the user actually
saw. Render callbacks see the **current** render. One bare function cannot honestly
promise both.

There is no generic `v/dispatcher` helper, and no dependency-annotated `v/event`.
Foreign argument conversion is `v/event`.

## Callback identity and lifetime

Each runtime event or handler site is owned by a pair: the **committed node
identity** and the **callback prop** (`:on-click`, `:onChange`, and so on). Node
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

That is why bare functions are illegal at foreign callback positions: Freehand
cannot know the phase or the identity contract. Annotate with the roster form that
matches the protocol.

## Decisions that depend on live state

When acceptance depends on changing application state — stale blur after cancel,
late dropdown select — put identity or generation in the intent if you need them,
but make the **decision in the re-frame handler** against the committed frame at
fire time.

Do not close over a render-time “still editing?” guard inside a minted callback and
hope it is still true later. That is the classic race Freehand is trying to make
unattractive.
