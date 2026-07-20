# The re-frame.ui view shift (mental model)

> The one shift a migrating developer must internalise. If you map Reagent's
> "a view is a function I run every render" onto re-frame.ui's "a view is
> something the compiler analyses", every rule below stops being a surprise.

## Anchor: Storybook is to a component library what re-frame.ui is to Reagent hiccup

If you know React, the closest analogue is the move from **hand-written `React.createElement` / JSX evaluated at runtime** to a **compiled template**: the framework can see the shape of your view *before* it runs, so it can validate props, track dependencies, and optimise re-renders statically. Reagent evaluates your hiccup every render; re-frame.ui reads it once, at build time. Everything that only worked *because* Reagent re-ran a plain function each render is exactly what changes.

## The four shifts

### 1. Views COMPILE now

A Reagent view is an ordinary function returning hiccup, re-invoked on every render:

```clojure
(defn greeting [name]           ; a plain fn — Reagent calls it each render
  [:h1 "Hello, " name])
```

A re-frame.ui view is a **compiled** `ui/defview`. The compiler walks the body at build time and lowers it into an analysed template:

```clojure
(ui/defview greeting [{:keys [name]}]
  [:h1 "Hello, " name])
```

The consequence: **constructs the compiler can't see through are build errors, not runtime quirks.** A dynamic tag head (`[(if big? :h1 :h2) …]`), hiccup assembled at runtime by walking data (`(reduce conj [:div] …)`), a raw lazy `map` of markup — Reagent tolerates all of these because it just runs the fn. The compiler rejects them, at *build* time, with a named error. That is a feature: the wall is early and loud, not a runtime footgun.

Corollary — **props are a map keyed by param name.** Positional params (`[name]`) become a destructured map (`[{:keys [name]}]`), and every call site passes a map: `[greeting name]` → `[greeting {:name name}]`. A zero-param view is called with an explicit empty map: `[status-pill]` → `[status-pill {}]`.

### 2. Deref-drop

Reagent subscriptions are reactive atoms you deref:

```clojure
[:span @(subscribe [:total])]        ; deref a reaction
```

re-frame.ui reads them with a compiled `sub` and **no deref**:

```clojure
[:span (sub [:total])]               ; the compiler tracks the dependency
```

The `@` and the reactive-atom machinery are gone; the compiler establishes the reactive dependency for you. A `subscribe` that is *stored* rather than immediately deref'd (a held reaction, a cursor) is a different animal — that is derived-state territory (MIG-19), not a deref-drop.

### 3. The frame is EXPLICIT (carried, not ambient)

In Reagent + re-frame v1, `subscribe` and `dispatch` reach an *ambient* global frame. In re-frame2 the frame is **carried, not found** — and a compiled `defview` has **no ambient `subscribe`/`dispatch` in scope at all**. Reads go through the compiled `sub`; writes go through the compiled handler grammar; both resolve the **committed** frame at the right moment.

The sharp edge you migrate around: a **plain unregistered `defn`** that makes an ambient `@(subscribe …)` / `(dispatch …)` call raises **`:rf.error/no-frame-context`** — at first render for a deref, at interaction time for a dispatch closure. You *grep for these, you don't discover them by clicking* (MIG-26). The fix is to register the fn as a view (so the frame is established), or to carry the frame explicitly.

To carry a frame's ops across an async boundary, re-frame.ui gives a compiled render-body form `(frame)` — the counterpart of core's `capture-frame` — returning the `{:frame :dispatch :dispatch-sync :subscribe}` bundle, locked to the committed frame incarnation (MIG-31).

### 4. Dispatch lifts to data

This is the shift that makes the whole migration *analysable*. A Reagent handler is an opaque closure:

```clojure
{:on-click #(dispatch [:ev x])}       ; a closure — the framework can't see inside
```

re-frame.ui lifts the common case to **data** the compiler retains:

```clojure
{:on-click [:ev x]}                    ; an event vector — data, not a closure
```

Because the handler is now data, the compiler (and the devtools) can *see* what a click does. The narrow "a bare event vector in an `:on-*` slot" law is DOM/custom-element only, and it is why re-frame.ui can offer static handler analysis at all. Handlers that do more than dispatch one literal vector — extract from the event, guard with a condition, mix in local work — have their own richer compiled forms (`ui/event`, `ui/handler`, `ui/dispatch-fn`) that are judgment calls (MIG-18), not automatic lifts.

## Why this shapes the tiers

- **M-tier** rewrites are the shifts applied where they are unambiguous: deref-drop, the header/params change, the plain-hiccup pass-through, the literal-vector dispatch lift.
- **D-tier** is where a shift meets a *decision the source can't answer*: is this `r/atom` product state (→ app-db, an event + sub) or ephemeral UI state (→ `local`)? The compiler can't tell; the human can.
- **R-tier** is where a shift meets a construct with *no compiled spelling yet* — or ever. Those views stay on Reagent.

The migration is the disciplined application of these four shifts, view by view, holding back exactly the views where a shift has no answer.
