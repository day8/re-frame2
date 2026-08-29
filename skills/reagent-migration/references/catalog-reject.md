# R-tier — "don't migrate this" (stay on Reagent)

> This list is the honesty backbone of the whole skill: some views should not
> move, because the construct has **no Hicasso equivalent** — by design, or with
> nothing shipped for it today. For every case here the answer is the same:
> **keep this view on Reagent** (a fully-supported configuration), and say so
> plainly. Never contort a view to force it across, and never emit a form for a
> surface that has not shipped (cardinal rule 6).
>
> The list is deliberately **short**. Hicasso has a first-class foreign-React
> door, a callback ref, an error boundary, portals, an ephemeral-state sugar and
> a real test kit, so most of what an earlier substrate had to refuse now
> converts. Three things genuinely do not.

## MIG-36 — the prev-props / prev-state update protocol

```clojure
(r/create-class
 {:get-snapshot-before-update (fn [this old-argv] (scroll-geometry …))
  :component-did-update       (fn [this old-argv snapshot] (restore! …))})
```

**Hicasso has no `component-did-update` mechanism at all**, and no
previous-props channel. Re-render *is* the update: a body re-runs on commit with
the new values and nothing hands it the old ones. Scroll restoration reading
pre-mutation geometry, a diff-driven imperative sync, "animate when this
particular prop changed" — none of them has a door.

Two honest routes before you hold:

- If the update work is genuinely **the DOM's** and can be expressed as *"React
  ran an effect with these dependencies"*, it belongs in a React island — a UIx
  `defui` or a raw React function component mounted through `h/defhost` — where
  `react/useEffect` and its dependency array are legal because you own the
  source and its call order. `n/use-sub` and `n/use-frame` give the island a
  frame-joined read and a frame-pinned dispatch when it needs Hicasso state.
- If the "previous value" is really **domain history**, it is app-db: the event
  that changed the value knows both sides, and the consequence belongs to the
  handler, not to the view.

If neither fits — and `get-snapshot-before-update` in particular has no
counterpart, because there is no pre-mutation moment to hook — **hold the whole
view on Reagent.**

## MIG-03 — a read pinned to a non-ambient frame

```clojure
@(subscribe [:cell v] {:frame report})
```

`h/sub` resolves the **view's own** frame; there is no frame-pinning arity, and
reaching across frames from a view is an anti-pattern the framework diagnoses
rather than an ergonomics gap. Two honest routes before you hold:

- The read is **non-reactive** (a one-shot in a callback) → that is a
  `re-frame.core` verb taking a frame option, not a view one. Check the core
  facade for what the project's version exports rather than assuming a spelling.
- The subtree genuinely belongs to another frame → it is another **root**. Make
  that frame and mount it with `h/mount!` in its own container.

If neither fits — a cell inside one tree that must reactively read a sibling
frame — hold the view.

## MIG-35 — Reagent component-introspection and schedulers

```clojure
(r/current-component) · (r/props c) · (r/children c)
(r/force-update c) · (r/next-tick f) · (r/after-render f)
```

Each encodes a Reagent-specific assumption about *this renderer's* component
object and render scheduling. **The dangerous part:** these are ordinary calls,
so a converted view **still compiles** with them in it and then fails or returns
`nil` at runtime, outside a Reagent render. No build gate catches this; you
must, and the census reports them as `:component-introspection`.

Some of it dissolves rather than migrating: `props` and `children` are the props
map the view already receives (children arrive at `:children`), and
`force-update` has no meaning under memoised boundaries.

**The schedulers are not a callback ref.** `next-tick` / `after-render` are
one-shot render-queue callbacks that fire around a flush even when nothing
renders; a ref fires on commit and on identity change. Different phase,
frequency and owner — a silent swap changes behaviour no diff review catches.
They stay R-tier unless the author *explicitly* redesigns the work's phase and
ownership.

## MIG-20 — a shared ratom store (recap)

The ratom-as-store second state model ([`catalog-judgment.md`](catalog-judgment.md)
MIG-20) is a reject *as written* — there is no Hicasso equivalent, by design. It
appears under D because there is a real restructure decision (into app-db);
until that restructure happens, the views on the store **stay on Reagent**.

## MIG-25 — effectful subscription bodies

```clojure
(reg-sub :q (fn [db] (dispatch [:log]) (:x db)))    ; a side effect in a sub body
```

Subs are pure. This is a *dataflow-side* finding you surface for the author —
never a view rewrite, and **not** a reason to hold the view: its own deref
converts fine (MIG-02) once the sub body is made pure.

## No longer a hold

These were holds under the previous substrate and are not holds now. Do not
carry the old refusal across:

- **Foreign React components and their fn-valued props** (MIG-09/10). `[:> …]`
  is legal, `h/defhost` declares a repeated crossing once (its callbacks
  inferred from the spelling as on a native tag, with a `:callbacks` override
  for a vendor's on*-named render prop), `h/as-element` crosses one element
  through a prop, and
  `h/as-component` bridges outward to a React/UIx/Reagent parent. → D-tier.
- **Callback refs** (MIG-29). A function at `:ref` is React's own contract and
  Hicasso honours it, with the return value as the detach cleanup. Only the
  *vector* spelling is refused, and it is reserved rather than missing. → D-tier
  under MIG-17.
- **Dynamic tag heads and runtime-built markup** (MIG-21/30). Hicasso walks the
  tree at render; there is no finite grammar and no compiled tier to opt into,
  so a head that evaluates to a keyword and markup a helper returns are both
  ordinary content. → M-tier pass-through.
- **`dangerouslySetInnerHTML`** (MIG-34). It passes through to React untouched.
  It needs no rewrite — but it does need **flagging**, because Reagent deleted
  the prop and Hicasso does not, so a dead site becomes live. That is a
  behaviour change to raise with the author, not a hold. → M-tier.
- **SSR-then-hydrate** (MIG-23). The whole pipeline shipped: `server/render` is
  the optional server module's product door, `re-frame.ssr/hydrate!` installs
  the server's app-db, and `h/hydrate!` adopts the server DOM. What is left is
  an infrastructure decision — the renderer is React's, running on Node — not a
  missing spelling. → D-tier.

## How to phrase a hold to the author

Be specific and non-apologetic. Name the construct, name the gap, name the safe
home:

> *"`article-list` restores scroll position from `get-snapshot-before-update` —
> it reads the pre-mutation geometry and puts it back after React commits.
> Hicasso has no update protocol and no pre-mutation moment, so there is no
> spelling to write; I'm keeping this view on Reagent, which is a
> fully-supported configuration. The four leaf views around it are plain hiccup
> and convert cleanly; I've done those."*

A held view is not a failure of the migration. Holding the right views is what
makes it *honest*.
