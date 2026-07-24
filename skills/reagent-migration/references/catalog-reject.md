# R-tier — "don't migrate this" (stay on Reagent)

> This list is the honesty backbone of the whole skill. Freehand is
> **pre-alpha**; some views should not move, because the construct has no
> Freehand equivalent — by design, or with no verb exported for it today. For
> every case here the answer is the same: **keep this view on Reagent** (a
> fully-supported configuration), and say so plainly. Never contort a view to
> force it across, and never emit a form for a surface that has not shipped
> (cardinal rule 6). The React host boundary — the largest hold this list used
> to carry — has since landed in both directions; its cases moved to
> [§No longer a hold](#no-longer-a-hold).

## Hold on Reagent — no Freehand spelling today

The construct has no Freehand equivalent, so the whole view **stays on Reagent**
and you say why. (The foreign-React holds that used to head this section have
landed — see [§No longer a hold](#no-longer-a-hold).)

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

## No longer a hold

**MIG-09 / MIG-10 — foreign React components and their fn-valued props.** The
React host boundary is now open in **both** directions, so a foreign React
component no longer forces the whole view onto Reagent.

```clojure
[:> Button {:label "x"}]                      ; a Reagent interop head — NOT a Freehand shape
[(r/adapt-react-class Widget) {:p 1}]
[DatePicker {:on-change #(pick! %)}]
```

A bare React **component** at a vector head is still illegal. The path is one
step removed: **create the React element** with your project's ordinary React
interop (`react/createElement`, a JSX helper, or `reagent.core/as-element` for a
Reagent component) and place that **element** in a **child** position. It renders
like any other child, and its props — the fn-valued ones (MIG-10) included — are
ordinary foreign React props on the element you created; the projection markers
and the `v/event` / `v/handler` seams are for Freehand's own sites, not a foreign
component's props. Feed a subscription into such a prop with a render-time
`(v/sub …)` read.

Two real limits decide whether to take this path or hold:

- **Browser-only.** The JVM structural renderer accepts no React elements, so the
  region around a foreign child cannot be covered by a `t/render` structural test.
  Assert the Freehand parts structurally; cover the integration in a mounted
  browser test.
- **The head stays illegal.** Only a *created element* crosses inward; a bare
  component symbol at a vector head is `:rf.error/ui-tree-malformed`.

If neither limit bites, this is a judgment-flavoured conversion, not a hold:
decide the reshape (head → created-element child) with the author, then convert
the whole view.

**MIG-22 — third-party Reagent wrapper components (re-com et al.).** A re-com
control or a charting wrapper is a **Reagent** component, not a plain React one,
so the inward path runs through `reagent.core/as-element` (Reagent → React
element → child) and carries the caveats of two renderers sharing a tree — a
judgment call, browser-only, worth measuring before you commit. Often the cleaner
move is the **outward** bridge: keep the wrapper subtree on Reagent and hand any
converted Freehand view up to it with `v/->react`, mounting it as
`[:> (v/->react the-view) {:frame f …}]`. Either way a Reagent wrapper library is
no longer a blanket hold, and a converted view is no longer un-renderable under a
Reagent parent.

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

> *"`article-body` renders a CMS HTML string through `:dangerouslySetInnerHTML`.
> Freehand exports no trusted-markup verb — there is no spelling to write — so
> I'm keeping this view on Reagent, which is a fully-supported configuration. The
> three leaf views around it are plain hiccup and convert cleanly; I've done
> those."*

A held view is not a failure of the migration. Holding the right views is what
makes it *honest* — and Freehand being pre-alpha means there will be held views.
That is expected.
