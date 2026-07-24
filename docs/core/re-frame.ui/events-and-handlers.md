# Events and handlers

The idiomatic handler is not a function. It is the **event vector**:

```clojure
[:button {:on-click [:cart/add product-id]} "Add to cart"]
```

Clicked → `[:cart/add product-id]` dispatches to the frame this view runs under.
Your intent was already data everywhere else in re-frame2
([Events](../events.md)); the view layer stops converting it to a closure for the
last ten feet.

> **A handler is data until it can't be** — and each non-data form names exactly
> what it needs.

Why vectors win, in four lines: they're **readable** (what does this button do? it
says so — in source, in [Xray](../observability.md) before any click); **testable
without a DOM** (assert the vector on the JVM tree —
[Testing](testing.md)); **stale-closure-free** (nothing is captured; the handler
reads committed values and the committed frame); and **fast** (vectors are values,
so props stay honestly comparable and memoisation keeps working).

## Payloads: three placeholders

```clojure
[:input {:on-input  [:form/typed :email :rf.ui/value]}]
[:input {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
[:div   {:on-key-down [:editor/key :rf.ui/key]}]        ; "Enter", "a", …
```

`:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key` — a closed set of three scalars,
spliced into the vector at dispatch time. There is deliberately no `:rf.ui/event`
and no `:rf.ui/form-data`: raw events are host objects and form payloads aren't
data — both cases are what `ui/event` (below) exists for.

**Placeholders work in literal vectors only.** They are compiled, not interpreted:
a vector forwarded through props dispatches its contents as-is, and dev warns
(`:rf.warning/placeholder-in-dynamic-vector`) if it spots a placeholder riding one.

DOM listener options use the map form, with an explicit closed vocabulary
(`:prevent-default`, `:stop-propagation`, `:capture`, `:passive`, `:once`):

```clojure
[:form {:on-submit {:event [:signup/requested] :prevent-default true}}]
```

## The live event: `ui/event`

For payloads that need the native event — form contents, files, coordinates,
filtering — `ui/event` runs a body when the callback fires and dispatches whatever
event vector the body returns (`nil` means dispatch nothing):

```clojure
[:input {:on-key-down (ui/event [e]
                        (when (= "Enter" (.-key e))
                          [:todo/added item-id]))}]
```

The callback is per-site stable and sees the view's **committed** values plus the
live event. When the body is *only* a literal vector, prefer the plain vector
handler — `ui/event` earns its keep when the payload needs the event or a `nil`
filter.

## Controlled inputs and the sync door

```clojure
[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]
```

works without caret jumps or IME breakage. Where the compiler can *prove* an
element controlled — a literal `:value`/`:checked` co-present with the handler —
dispatches from that site drain **synchronously within the DOM event**: input event
→ drain and commit → React's re-render observes the same value, so React performs
no restorative DOM write. This is the one sanctioned synchronous door; everything
else batches. You do not configure it; it is the law of those sites.

Two handler shapes ride the door: a **literal event vector**, and a **synchronous
`ui/event` whose result is an event vector** (`nil` = no dispatch) — the shape a
reusable input control uses to append its live payload to an event prefix it
received through props. A dynamic props map, general `ui/spread`, or a bare-fn
dispatch at such a site falls back to ordinary batching, and dev tells you so
(`:rf.ui.compile/controlled-input-async-handler`). Keep controlled fields literal.

A whole form is fields over app-db — one event, one sub, every field; the
[Build a form](../how-to/build-a-form.md) recipe carries the full pattern.
**Uncommitted drafts stay in `local`**: when only this view cares until submit,
hold the draft locally and dispatch on submit — the search-box pattern in
[State](state.md#local-ephemera-local).

## Forwarding intent through components

Parents pass data; the child's site dispatches in its committed frame:

```clojure
(ui/defview icon-button [{:keys [event label icon]}]
  [:button {:aria-label label :on-click event}
   [icon-view {:name icon}]])

[icon-button {:event [:item/deleted item-id] :label "Delete" :icon :trash}]
```

A library control that cannot know your event grammar takes an **event prefix** and
appends its payload at dispatch time:

```clojure
[text-input {:value email :on-value [:form/set-email]}]
;; inside the control:
;;   [:input {:value value
;;            :on-input (ui/event [e] (conj on-value (.. e -target -value)))}]
```

Three rules keep this honest:

1. Placeholders are never expanded in a vector received through props — build
   literals at your own DOM sites; hand libraries a prefix.
2. At a compiler-proven controlled site, the control's synchronous `ui/event`
   vector result rides the same sync door as a literal vector — the caret/IME
   guarantee reaches library inputs.
3. An ordinary fn prop between **internal** views is a legal **opaque value** —
   identity-compared like any other prop, never invoked by the framework, promising
   no phase. To opt a fn into a phase, use `ui/handler` (committed, per-site
   stable) or `ui/render-fn` (pure, during render).

## The decision table

| You need | Write | Notes |
|---|---|---|
| Dispatch intent (the 90%) | `[:event … :rf.ui/value]` | data; canonical |
| The live event — forms, files, filtering | `(ui/event [e] … [:vector …])` | committed values + the event; `nil` filters |
| Imperative work, stable identity | `(ui/handler [x] …)` | return ignored; foreign change-callbacks, internal fn-prop sites |
| A callback invoked **while rendering** — a row-key, a cell renderer | `(ui/render-fn [x] …)` | pure; current render; no dispatch/state inside |
| Quick local work in a **native** `:on-*` | bare `#(…)` | legal there only — shorthand for `ui/handler` |
| Any callback prop on a **foreign component** | one of the forms above, explicitly | a bare fn there is a compile error |
| Identity-as-protocol foreign APIs, callback refs | `(ui/raw-fn f)` | passes identity through untouched |

**The phase rule** is why the forms are distinct: event handlers see **committed**
values (a click on old DOM means old values — coherent with what the user saw);
render callbacks see the **current** render. One function cannot promise both.

```clojure
;; ui/handler — a foreign change-callback with stable identity
[VirtualList {:items rows
              :on-scroll (ui/handler [offset] (save-scroll! offset))}]

;; ui/render-fn — called while rendering, pure
[DataGrid {:rows rows
           :row-key (ui/render-fn [row] (:id row))}]
```

`ui/render-fn` doubles as the value a library's `ui/slot` call site accepts — the
[interop page](interop-and-limits.md) covers that library-author territory.

## Lists

A handler that ignores the loop variable is fine inline — `{:on-click
[:list/refresh]}` is one shared callback and every row can carry it. The moment a
handler needs the row itself, extract a keyed child view so each row owns its own
site; a vector that captures the loop binding is a compile error with that fix in
the message.

## Links that navigate

For in-app navigation, `ui/route-link` is a framework-provided compiled view over
the optional routing artefact: it renders a **real** `<a href=…>` (copy-link,
open-in-new-tab, and keyboard activation all work) and, on a plain left click,
dispatches `:rf.route/url-requested` to the committed frame instead of reloading.

```clojure
[ui/route-link {:to :orders/show :params {:id order-id}} "Open order"]
```

Rendering it without `day8/re-frame2-routing` on the classpath fails loud with
`:rf.error/routing-artefact-missing`; a plain `[:a]` stays available for
intentional browser-native navigation. The click law and route grammar live in the
[Routing corpus](../../routing/concepts.md).

## Lifecycle is not an event

There is deliberately no `:on-mount`. React mounts, replays, hides, and restores
components for mechanical reasons (StrictMode, hidden subtrees, hot reload, error
recovery) — "the user viewed this" cannot be inferred from them. Dispatch domain
visibility from domain transitions (a route match, a machine state, the event that
opened the modal); synchronise with the host world in `effect`
([State](state.md#the-world-outside-effect-and-dispatch-fn)).

## Troubleshooting

Every rule on this page fails where you can see it — at build time, or at the
first dev render. None of them becomes a handler that silently does nothing:

| If you write | What you see | The fix |
|---|---|---|
| A typo'd event id — `[:cart/ad id]` | Dev warning `:rf.warning/unregistered-event-id` at render, with the element's file:line | Fix the spelling, or register the event |
| A placeholder inside a vector received through props | Dev warning `:rf.warning/placeholder-in-dynamic-vector`; the vector dispatches as-is | Build literals at your own DOM sites; hand libraries a prefix |
| A bare `#(…)` on a **foreign** component's callback prop | Compile error `:rf.ui.compile/bare-fn-prop` | Choose a form from the decision table |
| A bare fn as `:ref` | Compile error `:rf.ui.compile/bare-fn-ref` | `(ui/raw-fn set-node)` or an object ref |
| A vector handler that captures the loop binding | Compile error `:rf.ui.compile/loop-capturing-handler` | Extract a keyed child view so each row owns its site |
| A bare fn inside a loop | Dev warning `:rf.ui.compile/bare-fn-in-loop` — works, at per-row closure cost | Prefer a data handler or a keyed child view |
| A dynamic props map or `ui/spread` at a controlled input | Dev warning `:rf.ui.compile/controlled-input-async-handler`; the site batches | Keep `:value`/`:checked` and the handler literal |

## When not

- Vectors are the default, not a straitjacket: real imperative work (driving a
  foreign widget, flipping `local` state) belongs in `ui/handler`, and genuinely
  event-shaped payloads in `ui/event`. Reaching for them *first* — before a data
  handler — is the smell.
- The event *vocabulary* itself — naming, granularity, `dispatch` semantics — is
  core territory ([Events](../events.md)); this page only changes the spelling at
  the DOM site.
- Reminder: `re-frame.ui` is experimental — the retained adapters are the default
  choice, where handlers are ordinary closures and this page does not apply.
