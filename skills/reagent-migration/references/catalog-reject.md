# R-tier — "don't migrate this" (stay on Reagent, or wait)

> This list is the honesty backbone of the whole skill. Freehand is
> **pre-alpha**; some views should not move — either because the construct has
> no Freehand equivalent *by design*, or because the surface is **declared and
> has not landed**. For every case here the answer is the same: **keep this
> view on Reagent** (a fully-supported configuration), and say so plainly.
> Never contort a view to force it across, and never emit a form for a surface
> that has not shipped (cardinal rule 6).

## Not landed yet — "wait"

These are the largest holds by volume, and they are all one boundary: **Freehand
cannot yet host a foreign React component, and cannot yet be hosted by one.**

### MIG-09 / MIG-10 — foreign React heads and their fn-valued props

```clojure
[:> Button {:label "x"}]                      ; Reagent interop head
[(r/adapt-react-class Widget) {:p 1}]
[DatePicker {:on-change #(pick! %)}]
```

Freehand's vector-head classification is **total** — a head is a declared
Freehand view, a keyword element, or a *declared host descriptor* — and the
qualified host leaf that would declare a foreign component is the substrate's
remaining declared vacancy. There is no spelling today, so a view mounting a
third-party React component **stays on Reagent**.

The callback forms that will serve that boundary (`v/event`, `v/handler`,
`v/render-fn`, `v/raw-fn`) *are* shipped and are used at the boundaries that
exist — internal view props and DOM sites. Do not read their presence as
evidence that the foreign boundary is open.

### MIG-22 — third-party Reagent wrapper components (re-com et al.)

A re-com control, a charting wrapper, any Reagent component library: same
boundary, same answer. **Keep the subtree on Reagent** — a fully-supported mixed
page. There is no inward embed and no outward bridge, so a converted view cannot
be rendered from an unconverted Reagent parent either. That is what makes the
unit of a pass a **closed subtree** ([`procedure.md`](procedure.md)) rather than
a lone file.

### MIG-29 — callback and object refs

```clojure
{:ref (fn [n] (when n (.focus n)))}
```

`:ref` has no author-facing spelling in Freehand today: the interpreted tier has
no ref machinery and refuses the key rather than honouring it half-way, and the
spec is explicit that there is no ref an author may declare and no way for an
application to hold a node.

**The successor is a registered behavior** (MIG-17), which is handed the node it
decorates and can reach no other. If the ref exists to do bounded work on one
node — focus it, measure it, attach an observer, drive a chart — write the
behavior. If the ref exists to *hand the node to something else*, hold the view.

### MIG-34 — `dangerouslySetInnerHTML` / trusted markup

```clojure
[:div {:dangerouslySetInnerHTML {:__html s}}]
```

The prop is refused, and no trusted-markup verb is exported yet. A view that
renders pre-rendered HTML (a CMS body, rendered Markdown) **stays on Reagent**.

Do not reach for `v/markup` here: `v/markup` crosses a **hiccup value** into a
compiled body (MIG-30). It does not parse or inject an HTML string, and using it
that way renders the markup as text.

### MIG-03 — a read pinned to a non-ambient frame

```clojure
@(subscribe [:cell v] {:frame report})
```

`v/sub` resolves the **view's own** frame; there is no frame-pinning arity, and
reaching across frames from a view is an anti-pattern the framework diagnoses,
not an ergonomics gap. Two honest routes before you hold:

- The read is **non-reactive** (a one-shot in a callback or a handler) →
  `rf/subscribe-once` accepts a `{:frame f}` opt. That is a `re-frame.core` verb,
  not a view one.
- The subtree genuinely belongs to another frame → it is another **root**. Mount
  it with `v/mount` and that frame in its `:frame` opt, in its own container.

If neither fits — a cell inside one tree that must reactively read a sibling
frame — hold the view.

## Genuine rejects — no Freehand equivalent, by design

### MIG-35 — Reagent component-introspection / scheduler API

```clojure
(r/current-component) · (r/props c) · (r/children c)
(r/force-update c) · (r/next-tick f) · (r/after-render f)
```

Each encodes a Reagent-specific assumption about *this renderer's* component
object and render scheduling. **The dangerous part:** these are ordinary calls,
so a converted view **still compiles** with them in it and then fails or returns
`nil` at runtime, outside a Reagent render. No build gate catches this; you must.

Some of it dissolves rather than migrating: `props` and `children` are the props
map the view already receives (`:children` is a declared prop under
`:children-policy`), and `force-update` has no meaning under memoised boundaries
and committed slots.

**The schedulers are not a behavior.** `next-tick` / `after-render` are one-shot
render-queue callbacks that fire before the next flush and after its queued
renders, even when nothing renders; a behavior's `:connect` / `:update` are
node-owned and fire on commit and on config change. Different phase, frequency
and owner — a silent swap changes behaviour no diff review catches. They stay
R-tier unless the author *explicitly* redesigns the work's phase and ownership.

### MIG-20 — a shared ratom store (recap)

The ratom-as-store second state model ([`catalog-judgment.md`](catalog-judgment.md)
MIG-20) is a reject *as written* — there is no Freehand equivalent, by design. It
appears under D because there is a real restructure decision (into app-db); until
that restructure happens, the views on the store **stay on Reagent**.

### MIG-25 — effectful subscription bodies

```clojure
(reg-sub :q (fn [db] (dispatch [:log]) (:x db)))    ; a side effect in a sub body
```

Subs are pure. This is a *dataflow-side* finding you surface for the author —
never a view rewrite, and **not** a reason to hold the view: its own deref
converts fine (MIG-02) once the sub body is made pure.

## No longer a reject

**MIG-21 — dynamic tag heads.** `[(if big? :h1 :h2) …]` is legal in the
interpreted tier: the head evaluates to a keyword and classifies as an element
like any other. So is markup assembled from data by a helper (MIG-30). Both
become refusals only if that one declaration is later promoted with
`{:compiled true}`, whose recovery is to split the branches (`(if big? [:h1 …]
[:h2 …])`) or to cross the value through `v/markup`. Do not hold a view for
either; do not promote a view that needs either.

## How to phrase a hold to the author

Be specific and non-apologetic. Name the construct, name the gap, name the safe
home:

> *"`report-grid` mounts `[rc/single-dropdown …]`. Freehand has no host boundary
> for foreign React components yet — there is no spelling to write — so I'm
> keeping this view on Reagent, which is a fully-supported configuration. The
> three leaf views below it have no foreign heads and convert cleanly; I've done
> those."*

A held view is not a failure of the migration. Holding the right views is what
makes it *honest* — and Freehand being pre-alpha means there will be held views.
That is expected.
