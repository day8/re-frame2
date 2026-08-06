# Spec 004 — Views — the common Freehand contract

> Status: Drafting. **v1-required.** This Spec owns the **common** view contract of
> **Freehand**, re-frame2's one re-frame-native view substrate: how a view is
> **declared**, how it is **authored and called**, what its **semantics** mean, and
> where the **host boundary** sits. Freehand has two execution modes over one
> semantic model — the **interpreted** mode is the paved path, and the **compiled**
> mode is the hot tier selected by `{:compiled true}` on the same declaration.
> Every clause here holds in both modes unless the clause says otherwise. The
> finite, versioned `:re-frame.freehand/v1` compiled grammar — its analyzer, its two
> emitters, and the static evidence they produce — is owned by
> [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md), referenced
> here and never restated. The public namespace is `re-frame.freehand`,
> conventionally aliased `v`.
>
> **This document is incomplete, and stays that way.**
> [EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md) cut the
> ownership so that no two Specs claim one surface, and it planned to author the
> substrate's contract in vertical slices rather than as one prose waterfall. It was
> **withdrawn on 2026-07-30**: the programme is closed and no further slice is
> scheduled. What that leaves is a gap in the prose, not a gap in the contract — the
> code is not removed, `implementation/freehand/` ships, and every clause written
> below describes behaviour that is live. Several semantic headings below carry no
> body; each is marked **Not authored here**. For those surfaces the description of
> record is the Freehand design record (`docs/design/freehand/`), and the donor-era
> shipped behaviour stays described by the Spec that shipped it — chiefly
> [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md). The
> single-owner rule still binds: no surface is authored anywhere but under its own
> heading here, and none may leave two owners standing.

## Abstract

A **view** is a declared, vector-called boundary: `v/defview` binds a var to a
**descriptor** that cannot be successfully called, and a call site writes
`[the-view props]`. Rendering that boundary is a pure computation from the ambient
frame and one props map to a semantic tree. The pattern-level commitments:

1. **One declaration, two modes.** The same `v/defview` form declares an interpreted
   view and a compiled one; `{:compiled true}` changes the lowering, not the public
   view model. Promotion changes one declaration and no call site.
2. **Passive render.** A render may run, restart, or be abandoned. It reads values
   and builds a *candidate* bundle; it MUST NOT dispatch, acquire ownership, mutate
   committed state, publish evidence, or create or seed frames. Per-mount work
   belongs to the frame's `:initial-events` or to an ordinary re-frame event, never
   to a render body.
3. **Atomic selection.** Only a render selected for commit publishes its frame
   incarnation, dependencies, event sites, and evidence — as one bundle. An
   abandoned or stale render publishes none of it.
4. **One reactive state system.** Application and interaction state is re-frame
   data. Host objects — DOM nodes, React elements, third-party instances — stay
   private behind qualified host boundaries.
5. **Intent is data.** One user action yields exactly one semantic event vector or
   `nil`. Mount, unmount, and host lifecycle are tool facts, not domain events.
6. **Frame-explicit, carried never guessed.** A view scopes live frames; it never
   creates them. Frames are created at host preflight (per [002](002-Frames.md)).

## Governing laws

These are the seven laws ratified by
[EP-0036 §Governing laws](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md#governing-laws).
They bind every section of this Spec and every clause of
[004D](004D-Freehand-Compiled-Grammar.md). Nothing in the substrate ships a second
answer beside one of them.

1. **One declaration.** Every mounted boundary is a vector-called `v/defview`; plain
   helpers are direct-called functions and never vector heads.
2. **One semantic model.** Props, children, keys, events, frames, controlled
   scheduling, structural output, errors, and evidence mean the same in both modes.
3. **Passive render and atomic selection.** A speculative render owns nothing; the
   selected commit publishes its frame, dependencies, events, and evidence as one
   bundle.
4. **One reactive state system.** Application and interaction state is re-frame
   data. Host objects remain private at qualified host boundaries.
5. **Intent is data.** One user action yields one semantic event vector or `nil`.
   Mount, unmount, and host lifecycle are tool facts, not domain events.
6. **Compilation is explicit.** No automatic promotion, second compiler, or hidden
   interpreted walker inside compiled markup.
7. **Proof is honest.** Separate React and JVM emitters may share normalizers but
   prove parity through common conformance values and fixtures.

The detailed rulings behind these laws are the Freehand decision register
(`docs/design/freehand/decisions/`, D001–D022). They are all ruled; a slice cites
them, it does not reopen them.

## What this Spec owns, and what it defers

Exactly one Spec owns each surface. This table is the map; the owning document is
the contract.

| Surface | Owner |
|---|---|
| Declaration, authoring, call convention, props/children/keys, common semantics, the host boundary | **this Spec** |
| The finite `:re-frame.freehand/v1` compiled grammar, its analyzer, both emitters, static manifests and elision, the compiled checker | [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md) |
| The semantic UI tree ABI and the one DOM conversion table both emitters consume | [004B-UI-Tree-and-Conversion](004B-UI-Tree-and-Conversion.md) |
| Root identity, the Root Descriptor, mount, hydration, and teardown | [004C-Roots-and-Mount](004C-Roots-and-Mount.md) |
| Frame creation, identity, lifecycle, and preflight | [002-Frames](002-Frames.md) |
| The observation-port contract the shared reactor consumes | [006-ReactiveSubstrate](006-ReactiveSubstrate.md) |
| Structural and mounted testing, the host/mode matrix, the executable cross-mode conformance contract | [008-Testing](008-Testing.md) |
| Diagnostic ids, evidence and retention fields, lifecycle facts, error egress | [009-Instrumentation](009-Instrumentation.md) |
| SSR consumption, hydration, fallback, and server-error projection | [011-SSR](011-SSR.md) |
| Route-link href and click semantics over the late-bound routing seam | [012-Routing](012-Routing.md) |
| Reserved namespaces, id derivation, and packaging conventions | [Conventions](Conventions.md) |
| The published var roster and its per-var reference rows | [API](API.md) |

## Declaration and authoring

### The descriptor and `v/defview`

`v/defview` is the one declaration form, and the only way to create an internal
mounted boundary:

```clojure
(v/defview panel
  "Optional docstring."
  {:children-policy :optional}       ; optional options map
  [{:keys [title children]}]         ; exactly one parameter — the props map
  [:section.panel [:h2 title] children])
```

The parameter vector takes **exactly one argument, the props map**. There are no
positional view arguments; ordinary map destructuring applies. The body is
Clojure returning a semantic tree. `{:compiled true}` on the same declaration
selects the compiled lowering ([§Selecting the compiled mode](#selecting-the-compiled-mode));
it changes the lowering, never the view model, so promotion edits **one
definition site and no call site**.

A declaration is rejected at **macro-expansion time**, with
`:rf.error/defview-bad-args`, when it is silently malformed rather than
obviously wrong:

- **the option roster is closed.** An unknown key is never discarded — it is
  named. That holds for a **reserved option whose owning slice has not landed**,
  the way `{:compiled true}` raised until the compiled tier landed and `:props`
  raised until the schema surface did. A silently-ignored
  option turns a one-character typo into valid code with different semantics, and
  a reserved one accepted-and-ignored makes a declaration report itself as
  something other than it says — the failure mode a declaration form must not
  have.
- **a declaration needs a body.** A parameter vector with nothing after it would
  expand into a view that renders nothing and says nothing. A view that
  deliberately renders nothing writes an explicit `nil` body.

An implementation MUST reject at the declaration, not at the call: a malformed
declaration that expands cleanly becomes a boundary whose defect surfaces
somewhere else entirely.

Declared view identity is the **qualified name** — `:app.cart/cart-badge` —
stable across recompilation, hot reload, and promotion between modes. Runtime
generations and compiler signatures are internal facts and MUST NOT appear in any
surface a caller can depend on.

#### A declared view cannot be called

`v/defview` interns a small **descriptor value** and binds the var to it. A
declared view is *mounted*, never *invoked*, and the law is a **property**:

> **A declared view cannot be successfully called.** A direct call
> `(the-view props)` raises `:rf.error/view-called-directly`, naming the three
> legal recoveries — mount it, inline it as a plain `defn` helper, or extract
> the shared work into one.

That property is normative, on every host and in both modes. HOW a host achieves
it is an implementation detail and MUST NOT be read as part of the contract: a
value that implements no call protocol satisfies it, and so does one that
implements the call protocol solely in order to throw.

The property is load-bearing rather than stylistic, and the failure it forecloses
is *silent success*, not calling as such. A descriptor shaped as a plain map
would answer `(the-view props)` as a **lookup** — returning `nil`, rendering
nothing, reporting no error. A `defrecord` would answer some arities the same way
and would differ across hosts besides, which is precisely the cross-host
divergence one semantic model exists to remove.

##### `(ifn? the-view)` is true, and says nothing about mountability

Stated plainly, because it is the kind of fact a reader would otherwise meet as a
surprise. `(my-view {…})` is normal, legal, idiomatic Reagent, so it is trained
muscle memory in exactly the population migrating to this substrate — and on the
JVM `(x arg)` compiles to a cast to the call protocol *before* any framework code
runs, leaving no interception point. A reference implementation therefore MAY
implement the host call protocol on the descriptor **solely in order to throw**,
and the CLJS reference does; the consequence is that `(ifn? the-view)` is
**true**. `(fn? the-view)`, `(map? the-view)` and `(coll? the-view)` remain
**false**.

Nothing may treat `ifn?` as a proxy for "is this a view?". Vector-head
classification asks `v/view?`, and tooling has `v/view?` and `v/describe`, which
are strictly more precise. The one thing `ifn?` buys is that a Freehand
descriptor reaching a foreign callable-component test — Reagent's, for
instance — is answered by *our* message rather than a host cast failure.

An implementation MUST cover every arity its host's call protocol declares.
A partially-implemented protocol does not fall through to nothing; it falls
through to the host's own arity error, which is the poor first encounter this law
exists to remove, reintroduced one arity along. Where a host's protocol has a
ceiling of its own — ClojureScript's `IFn` declares twenty-one `-invoke` arities
and admits no variadic one — a call beyond it still cannot **succeed**, and only
the message is the host's.

The runtime owns the didactic diagnostic for every other mistake it can observe
too: those are [§Vector-head classification](#vector-head-classification) and
[§Props, children, and `:key`](#props-children-and-key). The compiled checker and
development tooling still report a direct call *statically*, before it runs.

#### Views and helpers — the call convention

The convention is sharp, and it is the same rule for people, tools, and generated
edits:

1. **`[view props & children]` is the only internal boundary call.** Square
   brackets always mean "mount this named Freehand boundary."
2. **`(helper args)` is ordinary Clojure in the current boundary.** Parentheses
   always mean "do ordinary work inside the enclosing boundary."
3. **A reactive read reached through a helper belongs to the enclosing declared
   view.** A helper creates no ownership boundary at all.
4. **Directly calling a declared view is an authoring error**, per above.
5. **A plain function is never an internal vector head** — see
   [§Vector-head classification](#vector-head-classification).

Changing brackets to parentheses changes runtime **ownership**, not spelling:

| | mounted boundary `[view props]` | inline helper `(helper args)` |
|---|---|---|
| reactive reads | its own; invalidation re-renders it alone | recorded against the enclosing boundary |
| memoization | on its one props map | none — re-runs with its caller |
| occurrence identity | its own | none |
| event sites | its own committed per-site slots | the enclosing boundary's |
| errors, profiling, evidence | its own | attributed to the enclosing boundary |

Both granularities are legitimate. A plain helper **is** the deliberately coarse
region; there is no region DSL and none is needed. When an inline helper later
needs independent invalidation the move is one visible edit — `defn` →
`v/defview`, parentheses → brackets — which is exactly where a new
subscription-owning, memoized, error-contained boundary should surface in review.

#### The inspection projection

What tools, registries and catalogues read is the descriptor's **inspection
projection**: a plain map, distinct from the runtime value.

```clojure
{:re-frame.freehand/view true
 :view-id                :app.todo/todo-row
 :source                 {:ns … :file … :line …}   ; per 001's capture rules
 :lowering               :interpreted              ; or :compiled
 :children-policy        :optional                 ; or :none, :required
 :props-schema           <schema>}                 ; absent when none declared
```

- `:view-id` is the qualified, reload-stable identity.
- `:lowering` reports which mode the declaration selected. It is inspection data,
  never a dispatch surface — every view is mounted the same way.
- `:props-schema` is **absent** when no schema was declared. Absence is reported
  as absence, never as `:any`, so an undeclared schema stays distinguishable from
  a declared permissive one. The schema surface itself is
  [§Props, children, and `:key`](#props-children-and-key).
- The descriptor's **render body**, its host **mount** entry and its structural
  **tree** entry are private and are **not** projected. Browser heads resolve
  through the mount entry and structural heads through the tree entry; their
  shapes are runtime ABI, not a contract an application may depend on.

The key roster is closed in both directions: an extra key is as much a defect as
a missing one.

#### Cross-mode children

Interpreted and compiled descriptors MUST be mountable as children in either
direction, through the same descriptor. An interpreted parent mounts a compiled
descriptor exactly as it mounts an interpreted one. A compiled parent mounts a
statically named interpreted descriptor through one emitted interpreted-child
boundary; dynamic head selection belongs inside that interpreted child, never in
compiled markup. The analyzer recognises the shared descriptor as an internal
view, never as a foreign component. The grammar of that seam is
[004D](004D-Freehand-Compiled-Grammar.md).

A crossing is invisible in the output. All four pairings of parent mode and
child mode over one body MUST yield one structural tree, and a caller MUST NOT
be able to tell from a mount which mode the mounted declaration selected. What a
crossing does change is what the parent can claim: a compiled parent's manifest
marks each child boundary with the mode it crosses into
([004D](004D-Freehand-Compiled-Grammar.md#manifests-mark-the-crossing)).

**Conformance:** [FH-CALL-001](conformance/freehand/conformance-index.md#fh-call--calls),
FH-CALL-003, FH-CALL-004.

#### The `v/markup` boundary

Interpreted Clojure treats markup as a value — a helper returns Hiccup, a prop
carries it, a late transform rewrites it — and the compiled tier cannot lower
that, because a runtime value is not a template. Per
[D010](../docs/design/freehand/decisions/D010-compiled-dynamic-markup-crossing.md)
the v1 compiled grammar admits no valve for it: there is no `v/interp`, and no
arm that walks a child expression whose value turns out to be markup.

The framework therefore supplies `v/markup` — the declared boundary that markup
already in hand crosses at:

```clojure
(v/defview editor
  {:compiled true}
  [{:keys [error hint]}]
  [:section
   [v/markup {:value (field-help error hint)}]])
```

Freehand MUST provide it, and it MUST be an **ordinary interpreted view**: the
same declaration form an application uses, the same call spelling, the same
boundary node. Nothing in the compiled tier may know its name, and a compiled
parent MUST NOT treat it specially — mounting it is mounting a statically named
interpreted child, which the section above already requires to work.

That distinction is the whole design. A grammar valve would put an interpreter
*inside* compiled markup, and every claim a compiled manifest makes would become
conditional on values the analyzer never saw. A declared child puts the
interpreter on the far side of a boundary that is visible in the source, marked
in the manifest, and addressable in the tree.

- `:value` accepts anything a view body may return: a Hiccup vector, a seq of
  them, text, a number, or nothing.
- It accepts **no children** (`:children-policy :none`). The value is the
  content; a dropped child would be a silent loss, so the call is rejected
  instead.
- The child owns its own occurrence, its own recorded props and its own
  expansion. It is never inlined into the compiled parent's structure.

Adding a valve later is a new grammar decision and a new grammar version, never
a widening of `:re-frame.freehand/v1`.

**Conformance:** [FH-CALL-005](conformance/freehand/conformance-index.md#fh-call--calls).

### Props, children, and `:key`

Every internal view receives **one props map**.

```clojure
(v/defview panel [{:keys [title children]}]
  [:section.panel
   [:h2 title]
   children])

[panel {:key panel-id :title "Details"}
 [details {:id panel-id}]]
```

1. **One map, no positional arguments.** The mount form is
   `[view props & children]`; `props` is a single map, `{}` when the view needs
   nothing. A missing or non-map props slot is an error. The uniform grammar is
   what lets a call site be read without a special case, in either mode.
2. **Trailing children arrive as the reserved `:children` vector.** Forms after
   the props map arrive in the props map under `:children`, and compare as one
   slot. The key is **absent** when the call supplied no children, so a childless
   call yields the smallest props map that can compare equal.
3. **Caller-authored `:children` is rejected.** A `:children` key written into a
   literal or computed props map is an authoring error — there is exactly one way
   for children to arrive.
4. **A view declares its children policy.** `:children-policy` is descriptor
   metadata — `:optional` (the default), `:none`, or `:required` — and it is
   enforced at the call. It sits outside the props schema.
5. **`:key` selects sibling identity and is stripped before delivery.** `:key`
   feeds occurrence identity for reconciliation; the view body never sees it, and
   it is outside props equality, so two sibling calls differing only by key
   deliver props that compare equal. Keys remain mandatory wherever sibling
   identity matters — the keyed-list law is unweakened.

   ```clojure
   (v/defview todo-list [_]
     [:ul.todo-list
      (for [id (v/sub [:todo/visible-ids])]
        [todo-row {:key id :id id}])])
   ```

6. **Props compare by value equality.** Boundary memoization is over the one props
   map, and both modes share one normalizer, so both deliver the same props map
   for the same call by construction rather than by convention.
7. **Mutable host values belong at an explicit host boundary.** Internal props are
   values; DOM nodes, third-party instances and other host objects cross only
   through [§Qualified host leaves](#qualified-host-leaves).

**The props-schema seam.** A declaration MAY carry a props schema under
`:props`; it is optional in `:re-frame.freehand/v1`, including for compiled
application views, and required by build and catalogue policy for shipped
reusable library views and wherever a report claims generated coverage. Where
one is declared it CLOSES the props map to the keys it names, identically in
both modes — the modes differ in when a breach is reported, never in which props
are legal. `:key` is outside the schema, and children policy is descriptor
metadata rather than a schema slot. The schema's grammar, its explicit open
escape, validation timing, elision and generation dependencies are owned by
[Spec 004D §Props schemas](004D-Freehand-Compiled-Grammar.md#props-schemas).

**Conformance:** [FH-PROPS-001](conformance/freehand/conformance-index.md#fh-props--props),
FH-PROPS-002, FH-PROPS-003, FH-PROPS-004, FH-PROPS-005.

### Vector-head classification

One rule covers every internal vector head — in the interpreter, in the compiled
analyzer, and in the JVM structural host. The same order, the same outcome, no
heuristic arm and no fallback:

| Head | Classification |
|---|---|
| a Freehand descriptor | internal view boundary — mount it |
| a keyword | a DOM or custom element |
| a declared host descriptor | a foreign boundary ([§Qualified host leaves](#qualified-host-leaves)) |
| anything else | **an error**, naming those three legal forms |

**Totality is the contract.** There is no fourth case and an implementation MUST
NOT add one: no bare-function heads, no string heads, no duck-typed component
detection. A plain map is *not* a host boundary merely by being a map — the host
arm keys off the nominal type `v/defhost` mints, so the classification stays
total without any duck-typing at all, and no map an application can write is a
host boundary.

The error names all three legal forms. When the offending head is callable it
additionally names the recovery for the mistake this arm actually catches in the
field — declare the function with `v/defview` to mount it as a boundary, or call
it with parentheses as an inline helper. The offending head rides in the
diagnostic as a **shape summary**, never as the value itself (per
[015 §Data-Classification](015-Data-Classification.md)). Foreign React components
are legal only through the qualified host boundary; they are not an exception for
arbitrary internal function heads.

This single rule is what keeps head resolution uniform across interpreted code,
compiled code, the structural host, hot reload, catalogues, tools, and generated
edits.

**Conformance:** [FH-CALL-002](conformance/freehand/conformance-index.md#fh-call--calls).

### Identity, hot reload, and remount

**Not authored here.** This heading covers mounted occurrence identity, the
internal revision/generation vocabulary, and the compatible-shell versus clean-remount
hot-reload contract.

### Selecting the compiled mode

**Not authored here.** This heading covers the `{:compiled true}` selection, the
checker-first workflow that precedes it, and the interpreted↔compiled crossing rules;
the grammar itself is [004D](004D-Freehand-Compiled-Grammar.md).

## Common semantics

### Passive render and atomic selection

**Not authored here.** This heading covers the candidate bundle, the selection and
publication rule, acquire-before-release, and the fate of an abandoned render.

### The ambient frame

**Not authored here.** This heading covers frame observation and rebinding at a view
boundary, and the loud failure when no live frame is in scope.

### Reactive reads — `v/sub`

**Not authored here.** This heading covers the render-only law, same-render-thread
capture, stabilized return values, invalidation ownership, and the non-reactive
one-shot alternative.

### Event intent and the payload materializer

One user action yields **exactly one semantic event vector or `nil`**. Intent is
ordinary data, written where it happens:

```clojure
[:button {:on-click [:cart/add product-id]} "Add"]

[:input {:value email
         :on-input [:form/edit :email ::v/value]}]

[:input {:type    :checkbox
         :checked dark?
         :on-change [:prefs/set-dark ::v/checked]}]

[:form {:on-submit {:event [:article/save article-id]
                    :prevent-default true}}
 …]

[:div {:style {:overflow-y "auto"}
       :on-scroll [:table/scrolled table-key ::v/scroll-top]}
 …]

[:div {:popover :auto
       ::web/popover-open? open?
       :on-toggle [:menu/toggle-reported menu-key ::v/new-state]}
 …]

[:canvas {:on-pointer-down (v/event [e]
                             [:canvas/pressed (point-in-canvas e)])}]
```

Because the intent is a vector rather than a closure, a reusable control can
**forward** it. `(conj on-change ::v/value)` turns a caller's
`[:account/email-edited]` into `[:account/email-edited ::v/value]` without the
control knowing anything about the caller's domain, and the whole site stays
inspectable — and structurally testable — before anything mounts.

**The scalar projection door.** A projected read is **one shallow scalar** off
the live event or its target, and there are two spellings of the same read:

- the **named roster** — `::v/value`, `::v/checked`, `::v/key`, `::v/scroll-top`,
  `::v/new-state` — the reads common enough to have earned a name; and
- the general door **`[::v/read <path>]`**, which reads any other shallow scalar
  the same way: a property keyword read off the event (`[::v/read :key]` is
  `event.key`), or a vector of keywords walked as a chain from it
  (`[::v/read [:target :scrollTop]]` is `event.target.scrollTop`).

```clojure
[:input {:type :number
         :on-input [:form/edit :qty [::v/read [:target :valueAsNumber]]]}]
```

The named markers are **sugar** over the door: each is a `::v/read` of the one
host property it names — `::v/value` is `[::v/read [:target :value]]`,
`::v/scroll-top` is `[::v/read [:target :scrollTop]]`, and so on — so there is one
reader, one law, and one place the payload is assembled. A member is present in
the payload exactly when the host carries the property it reads: a `<div>` has no
`value`, so a click on one cannot ask for `::v/value`; every element has a scroll
offset, so `[::v/read [:target :scrollTop]]` is available wherever an element
target is, truthfully at 0.

What stays closed is the **property every projection keeps, not the set of
members**. A read is admitted only when the path resolves to a scalar in the
accepted-identity domain — a string, number, boolean or keyword — because that is
exactly what makes the site's intent assertable by equality, printable,
comparable, and identical on both hosts. A read that lands on a host object, a
collection, or nothing is a typed error naming `v/event`, not intent data. So the
door is open to more **paths** and never to more than a **scalar's** worth of
intent: reading a scalar off the platform is a projection, and **anything richer
— multiple reads, indexing, computation, a live host object, a side effect — is
`v/event`'s opaque job**, a [normative absence](#absent-at-the-event-surface)
along with a selector or transform language. Admitting `::v/new-state` — now
`[::v/read :newState]` under a name — is also what makes the top layer's dismissal
advisory answerable: a controlled popover or dialog whose state the browser
changes on its own is [told to reconcile that report "with ordinary event
intent"](#the-dom-top-layer), and the door is the spelling for the report's own
state.

**One materializer, at firing time.** At firing time the native or qualified host
adapter obtains the live scalar payload, and one pure materializer —
`v/materialize-event` — replaces the markers before the resulting plain vector
reaches ordinary re-frame dispatch. Its rules are deliberately small:

1. **Position zero may not be a marker.** An event id is a name, not a projection
   or a `[::v/read <path>]` door.
2. **Only top-level argument positions are replaced.** Projection is shallow and
   by value: a named marker or a `[::v/read <path>]` door in an argument position
   is substituted, while the same form nested inside a map or a deeper vector is
   ordinary application data and survives untouched.
3. **A projection must read a shallow scalar.** A read that lands on a host
   object, a collection or nothing is a typed error naming `v/event` — the
   scalar law is what keeps a projected read equality-assertable and
   host-neutral. It binds **both spellings identically**: the named markers are
   sugar over the door, so `::v/value` and `[::v/read [:target :value]]` accept
   the same domain and refuse the same values. A named marker that admitted what
   its own expansion refuses would be a second law wearing the door's name.
4. **Every occurrence is replaced**, not merely the first.
5. **A requested but unavailable payload is a typed error, and nothing is
   dispatched.** A malformed event reaching a handler is worse than no event; a
   silently `nil` argument is worse still.
6. **The result is a plain vector.** An event carrying no marker is returned
   unchanged, so an unprojected site allocates nothing.

The marker and the payload key are **the same value** — a site asks for
`::v/value` (or `[::v/read [:target :value]]`) and the adapter supplies exactly
that key — so "did this callback offer what this event asked for?" is one lookup
and there is no second vocabulary to keep in step. Projection reads the payload the *callback* supplies, never a
render-captured value, so the same intent vector materializes differently on
successive keystrokes without being rebuilt.

Every path runs through that one function: a literal vector, a forwarded prefix,
an options map's `:event`, a `v/event` body's result, interpreted, compiled,
production and test. Both hosts share it, so a JVM structural test supplies a
literal payload and asserts the exact dispatched vector without a browser — the
production mechanism, not a test-only splice convention.

"Every path" includes the vector a `v/event` body **returns**. A body that does
its own work and then yields `[:table/scrolled [::v/read [:target :scrollTop]]]`
has that door read off the **live callback argument the body was handed**,
exactly as the same vector written declaratively would — the payload is read for
the vector about to be dispatched, whichever way the site produced it. A door in
a returned vector is therefore an ordinary projection and never an unmet one.

**General dispatch gains no payload arity.** `rf/dispatch` and `rf/dispatch-sync`
take an event vector and an optional opts map, and nothing else. Projection
belongs to the layer that understands UI callback payloads: enlarging the general
event runtime would mean explaining projection semantics for every dispatch
source, and would make an accidental reserved keyword in a non-UI event
genuinely hard to reason about. A projection keyword travelling in an ordinary
domain event is never secretly interpreted.

**No host object enters an intent vector.** Native events supply the three
normalized scalars; a qualified foreign leaf supplies its own plain payload
values or converts them with `v/event`. DOM events, React synthetic events and
third-party instances stay behind the host boundary.

**Listener options.** An event position may state its intent inside a closed
options map:

| Key | Meaning |
|---|---|
| `:event` | the intent vector — required |
| `:prevent-default` | run the browser mechanic before dispatch |
| `:stop-propagation` | run the browser mechanic before dispatch |
| `:once` | the site's intent fires once; the consumed state is the **site's**, retained across re-render |
| `:passive` | native listener-attachment fact |
| `:capture` | native listener-attachment fact |

The roster is closed, and an unknown key is rejected — an option that silently
does nothing is an event site that looks correct and is not. A **map** at an event
position is this form and no other, so the roster is the whole grammar of a map
there: a string key is an unknown option like any other, and an empty map is an
options map that never stated its `:event`. Native attachment for `:passive` or
`:once` is internal event-adapter lifecycle, not a public effect system. The
structural host runs no browser mechanics because it fires no native event; the
options still normalize and still ride the site plan, so both hosts read one
shape.

**Keyboard branching is an ordinary event.** Dispatch
`[:picker/key-pressed ::v/key]` and branch in the registered handler, which can
weigh committed application state; where the browser mechanic has to be decided
synchronously in the listener, write `(v/event [e] …)`. A closed exact-key
condition map shipped for a time under a delete-before-release pilot gate, and
was **deleted** when the pilots showed no use for it —
[D007](../docs/design/freehand/decisions/D007-key-condition-event-maps.md)
records the fork and its outcome.

**One event, or none.** After the shallow options are interpreted, a site yields
exactly one event vector or `nil`. `nil` dispatches nothing — that is how a
callback declines, without a second control channel. A **vector of event
vectors** is an error: multi-step work is one semantic event whose re-frame
handler returns the effects the step needs, which keeps one inspectable causal
unit instead of a miniature dispatcher inside the view. An intent may carry
identity, a generation or a candidate value; when acceptance depends on changing
application state, the receiving handler decides against the exact committed
frame at dispatch, rather than a render-time callback closing over a stale
guard.

Mount, unmount and host lifecycle are **tool facts, not domain events**
(governing law 5). Per-mount work belongs to the frame's `:initial-events` or to
an ordinary re-frame event.

**Conformance:** [FH-EVENT-001](conformance/freehand/conformance-index.md#fh-event--events),
FH-EVENT-002.

### Callback roles and identity

Foreign APIs use functions for several unrelated protocols, and a bare function
says nothing about which one. Freehand's roster is closed, and each form carries
one phase and one identity contract:

| Form | Role | Identity |
|---|---|---|
| event vector / options map | declarative application intent | stable adapter owned by the committed site |
| `v/event` | convert an invoker's arguments to one event vector or `nil`; no `v/sub`, hooks, refs or effects | stable committed adapter per site; body changes publish atomically |
| `v/handler` | explicit imperative foreign work; not a disguised render or state store | stable committed adapter per site; retired at disconnect |
| `v/render-fn` | pure function invoked during the *foreign owner's* render; may return Freehand content, may not `v/sub`, dispatch, use hooks or touch refs | **no stability guarantee** — reuse is an optimization |
| `v/raw-fn` | expert seam where the authored function's identity is itself protocol data | exactly the supplied identity; no stabilization |
| a UIx / Helix wrapper | React owns hooks, effects, context, refs, Suspense or compound protocols | the wrapper's React contract |

Classification over an event position is **total**: a vector, an options map, one
of the four declared forms, a plain function, or `nil` — and anything else is an
error naming that roster. Bare functions stay legal at native `:on-*` sites,
because the site's own committed adapter owns their lifetime, and as opaque
values passed between internal views. They are rejected in a *declared foreign
callback position*, where the invoker, phase and identity contract are otherwise
unknown.

There is **no `v/dispatcher`**. A prefix-plus-arguments shorthand would be short
to write and would invite mutable dates, selections and host events straight into
intent vectors; `v/event` is the explicit conversion seam, and it is one form to
teach rather than two.

**Per-site committed slots.** A render builds an ownership-free **candidate**
site table; only the render *selected for commit* publishes it, as part of the
same bundle that publishes the frame incarnation and the dependencies. An
abandoned render publishes nothing — not because a flag stops it, but because
its candidate is simply dropped.

Each runtime event site is owned by `(committed node identity, callback prop)`,
and the runtime mints **one proxy** per site. The proxy reads the exact committed
body and dispatch target when it is invoked:

- **identity survives re-render.** An unchanged site keeps the exact callback
  across every re-render. This is the load-bearing property: it is what stops a
  re-render from churning callback identity through React reconciliation, and
  what lets a memoized foreign child stay memoized.
- **a later commit replaces the body, not the identity.** Retargeting a site — a
  new intent, a new frame — is a re-commit; the destination moves and not one
  callback identity changes. There are no dependency arrays: committed
  publication is what supplies freshness.
- **equal values at two sites stay independent.** Two sites holding an equal
  authored value get two distinct proxies, so their lifetimes, `:once` state and
  diagnostics never merge. Equality of intent is not identity of site.
- **retirement makes the exact proxy inert.** Removal, a key change, node
  replacement, disconnect or an incompatible hot-reload generation retires the
  site. A retired proxy stays *callable* — a foreign listener may already hold it
  — and dispatches nothing, emitting development evidence rather than firing into
  whatever owns the node now.

Node identity is key-aware within its sibling scope and positional only where no
key exists. Per-site ownership is the public law; the private key representation
is not — an interpreted runtime may key a site by committed node identity while
the compiled tier uses an owner plus a lexical site id, and both must keep equal
values at different sites independent.

`v/render-fn` is deliberately outside this scheme. It can be invoked during an
uncommitted candidate render, so a mutable "latest body" proxy would be unsafe
under concurrent rendering; its identity may therefore change on any render. An
API that treats callback identity as a separate protocol uses `v/raw-fn`, a
component bridge, or a wrapper, instead of asking Freehand to guess.

**Conformance:** [FH-EVENT-003](conformance/freehand/conformance-index.md#fh-event--events),
FH-EVENT-004.

### Controlled inputs

A controlled input is the one place where the render cycle meets a live node the
user is typing into, and where the DOM value is only *provisional* until
application state has round-tripped back through render. Hosts enforce that
literally: React restores a controlled node's value from the props it last
rendered at the end of a discrete event. A keystroke whose state change has not
landed by then is not merely late — it is erased, and with it the caret, the
selection, and any composition in flight.

```clojure
(v/defview title-field [{:keys [value]}]
  [:input {:value    value
           :on-input [:document/title-edited ::v/value]}])
```

Freehand's answer is a **narrow door**, not a mode. Making every event
synchronous would impose that cost and its reentrancy on the whole system; the
useful rule is one small enough to recognise mechanically, and identical in both
execution modes. The ruling is
[D009](../docs/design/freehand/decisions/D009-controlled-input-synchronous-flush.md).

**The door predicate.** A site is inside the door when *all five* facts hold:

| # | Fact |
|---|---|
| 1 | The element is a supported native control — `input`, `textarea`, `select`. Not a foreign component, and not a custom element, whose own synchronous protocol belongs to its adapter |
| 2 | Its **final normalized** props carry `value` or `checked`. Presence, not truth: an explicit `nil` is a controlled node whose value is empty |
| 3 | The firing attribute **normalizes to** `onInput` or `onChange` |
| 4 | The handler's outcome is *synchronously known* to be one event vector or `nil` — a vector, an options map carrying one, or `v/event` |
| 5 | No listener option moves the site onto a different native attachment lane. `:capture` and `:passive` are outside |

The predicate reads **normalized slots, never authored keywords**. Both walks
project an attribute key onto the prop they actually write, so `:x/value`
reaches `value` and makes the node controlled exactly as `:value` does; judging
the authored keyword would leave every other spelling of one emitted prop
outside the door. It is a property of the *whole element*, so it is decided
after every prop is normalized, not handler by handler.

`v/handler`, bare functions, promises and foreign callback protocols are
outside. Not because they cannot dispatch, but because what they dispatch — and
whether they dispatch at all, or later — is unknowable at the moment the door
would have to open. `nil` means no dispatch and needs no flush.

**`:on-before-input` is outside the door.** `beforeinput` fires *before* the DOM
mutation, so the target's value is not generally the candidate value, and
composition needs its own evidence. It remains an ordinary event site; admitting
it would require a browser-backed projection and composition contract, not a
name added to a list.

**The synchronous round trip.** Opening the door commits a second, frame-bound
dispatcher beside the ordinary one. A site belongs to exactly one lane, and both
are published by the same commit against the same frame, so a retarget moves
them together:

```text
native callback
  → select and materialize one intent
  → dispatch against the exact committed frame
  → drain the frame synchronously
  → flush the cells observing that frame
  → return to the host's event processing
```

The flush is **frame-scoped**. Not global: a keystroke in one frame must never
force another frame's pending work to settle inside a listener it has nothing to
do with. Not narrower than the frame either: an occurrence-scoped flush would
let a sibling reading the same frame commit an older snapshot of state the
flushed cell has already moved past. Cells dirty on that frame for unrelated
reasons do ride the flush — that coupling is honest, it is measurable, and it is
reduced with ordinary boundary and subscription granularity, not with a second
source of truth.

The public guarantee is the **observable round trip**, not a promise about a
particular host API: the value the user typed reaches application state and
comes back to the element within the same user action, so characters are not
dropped, the caret and selection do not move, and composition survives. A host
may flush additional pending work; that is a performance fact, and it belongs in
the trace rather than in the contract.

**One predicate, both modes.** There is one copy of the predicate and one moment
it is asked. The interpreted walk asks it over the props it just normalized. The
compiled tier bakes **no verdict** into its emission: a compiled element's props
map is lexically visible, so the tag, the controlled-ness and each handler's final
slot are emitted as compile-time constants and the same predicate decides from them
at commit. That is what makes promotion parity structural rather than a pair of
lists someone keeps in step — there is no second decision to diverge. The analyzer
does ask the predicate at build as well, over the facts it can statically prove, but
what it does with that answer is **evidence**: the site's manifest fact, and the
near-miss advisory below. So where the compiled grammar cannot prove a handler's
class, the lane verdict still belongs to the common predicate at commit, never to
an unconditionally batched lowering.

**A reusable library input owns its event site.** A library control receives the
caller's intent as an event *prefix* through props, and it has two places to put it.
Forwarding the prefix straight into the controlled position is the obvious spelling
and the wrong one:

```clojure
(v/defview field [{:keys [value on-input]}]
  [:input {:value value :on-input on-input}])   ;; the caller's vector, forwarded
```

Nothing static pins that handler's class, so the site is opaque: its intent is
absent from the compiled manifest, no structural test or tool can say what the field
dispatches before it fires, and the build reports the near-miss with
`:rf.ui.compile/controlled-input-async-handler` — the advisory that fires exactly
where the door could have opened and the handler's shape is the one fact left
unproven. A control whose whole promise is that its behaviour is inspectable has
given that promise away at its most important site.

The paved spelling is for the library to own a **literal** site and carry the
caller's prefix as an argument inside it:

```clojure
[:input {:value    value
         :on-input [:acme.ui.field/changed on-input ::v/value]}]
```

The site's proof stays static, the prefix stays a runtime value, and the library's
own registered handler appends the live payload and dispatches the caller's event.
That costs one more dispatch per keystroke, and the hop rides inside the synchronous
drain the door already opened, so it costs correctness nothing — the component pilot
proves it in a real browser under sustained typing, with no character dropped and no
caret moved. Where the payload must be *converted* rather than appended,
`(v/event [e] …)` is inside the door too, at the price of an opaque site: the intent
is no longer assertable as data before it fires. All three spellings work; only the
literal one keeps the site's intent readable.

**Forwarding preserves the proof.** A component library that forwards a
consumer's attributes onto its own controlled element does so through
`v/spread-safe`, which denies the caller `value`, `checked`, and the component's
own handler slots — in every build, by normalized slot, so no alternate spelling
routes around it. An opaque spread cannot claim the guarantee, because nothing
about it is provable.

**Not this slice.** Buffered and draft controls — a field that holds a local
draft and commits on blur or Enter — are a controller protocol, not a scheduling
rule; they are ruled by
[D016](../docs/design/freehand/decisions/D016-buffered-and-revision-controls.md).
There is no general synchronous-render escape hatch, and render-phase mutation
of a host node is permanently outside the model.

**Conformance:** [FH-INPUT-001](conformance/freehand/conformance-index.md#fh-input--controlled-input),
FH-INPUT-002.

### Semantic controllers

A reusable view is **props-only by default**: it receives values and emits intent,
and the state lives wherever the application already keeps it.

```clojure
[disclosure {:open?     (v/sub [:faq/open? question-id])
             :on-toggle [:faq/toggled question-id]
             :label     "Why?"}]
```

A **semantic controller** is the exception, and it has to be earned. Some controls
own a protocol that spans several interactions and cannot honestly be pushed onto
every caller: a field that drafts, commits on blur or Enter, cancels on Escape, and
must survive a cancel racing a late blur; a dropdown holding an open flag and an
active option; a typeahead holding a typed query beside a settled one. Making every
caller rebuild those state machines is poor library ergonomics, and hiding them in
host state would put interaction facts where re-frame cannot see them. So a library
MAY own the protocol — and the state it owns is still ordinary re-frame data.

A controller is therefore **not a new kind of thing**. It is a `v/defview` plus
ordinary `reg-sub` and `reg-event` registrations, and the substrate contributes
exactly one rule: how the record is addressed. There is no controller registry, no
declaration form, no reducer language, and no second state system. The rulings are
[D003](../docs/design/freehand/decisions/D003-reusable-control-state-model.md) and
[D004](../docs/design/freehand/decisions/D004-state-identity-and-addressing.md);
who owns the event vocabulary is
[D017](../docs/design/freehand/decisions/D017-framework-control-and-policy-vocabulary.md).

#### The published surface — three verbs, and no more

Everything the substrate contributes to a controller is reachable from the ordinary
`re-frame.freehand` door, and it is exactly three functions:

| Verb | The question it answers |
|---|---|
| `v/controller-key` | **where** — the `(kind, :control address)` pair this record lives under |
| `v/controller-revision` | **which generation** this render is under — the caller's `:reset-key` |
| `v/controller-current?` | **is this work still that generation** — the fence itself |

They are **published**, at the `advanced` tier, and that is a deliberate reversal of
the natural instinct to keep an authoring verb off the porch. A component library is
consumer code by construction — §Vocabulary and absences gives it `buffered-field`,
its record schema and its event ids outright — so an unpublished mechanism does not
prevent controllers, it merely obliges every library that ships one to reach into a
substrate-internal namespace to write an ordinary field, with no public-API gate
watching the signature it depends on. The advertising cost is real and it is carried
by the **tier** instead: the Guide and the skills load front-porch only, so the three
are reachable by the library author who needs them and out of the way of the app
author who does not. They ride the one door rather than a controller-specific sibling
namespace, per [Conventions §Freehand — one public namespace](Conventions.md#freehand--one-public-namespace-one-alias-one-reserved-root).

The split between the first verb and the second is the split between this section and
§The buffered controller and the reset generation, and it is load-bearing: asking for
the **key** is what makes a controller writable, and asking for the **revision** is
what makes it buffered. A dropdown holding an open flag is writable and not buffered,
so it takes the key and no generation. One combined verb would either force every
writable controller to be buffered, or make the revision optional — and optional is
ruled the worse design below.

None of the three writes anything. They form a key, read a prop, and compare two
values; the state still moves only through the library's own registered events.

#### Controller identity

A controller record is addressed by one pair:

```text
(controller kind, caller-supplied :control address)
```

The **kind** is the library's — which protocol this record obeys — and it is part of
the key so that a dropdown and a field addressed at the same domain identity read two
records rather than each other's. The **address** is the caller's, and it arrives as
the conventional `:control` prop: immutable EDN naming the domain thing that owns the
state, never a DOM id, a React key, a callback, or a runtime token.

```clojure
[buffered-field {:control   [:invoice invoice-id :amount]
                 :value     (v/sub [:invoice/amount invoice-id])
                 :on-commit [:invoice/amount-committed invoice-id]}]
```

- **The address is caller-supplied, never derived.** Freehand MUST NOT mint a
  writable state address from render position, a view's qualified name, a `:key`, or
  any other occurrence-derived path. A derived anchor makes a sort, a view rename, a
  parent extraction, an isolated story render, or a virtualized remount into a silent
  state migration; `[:invoice 42 :amount]` survives all of them. `:key` selects
  sibling identity and MAY reuse the same domain id, but neither is ever derived from
  the other.
- **The address is mandatory for a writable controller.** A control that writes
  under no address is refused loudly with
  `:rf.error/view-control-address-missing`, naming the kind and the recovery. There
  is no default and no synthesised address: every controller that skipped one would
  otherwise share a single record, which presents as one field editing another and
  has no local explanation. Asking for the record key IS what makes a controller
  writable — a props-only view asks for nothing and pays nothing. The verb is
  `v/controller-key`, and it forms the pair and raises the refusal in one call, so a
  controller cannot half-implement the rule.
- **`nil` is not an address, and it is a different mistake from omitting one.** An
  explicit `:control nil` is refused under its own id,
  `:rf.error/view-control-address-nil`. The prop is present, so nothing was forgotten
  at the call site; a `nil` address is an expression *upstream* answering nothing — an
  unresolved route parameter, a subscription that has not landed, a lookup on a key
  that moved — and a diagnostic claiming the prop was absent points at the one place
  the mistake is not. What decides between the two refusals is PRESENCE, never
  truthiness: `false`, `0` and `""` are ordinary addresses and are keyed like any
  other value. Component schemas that name `:control` MUST therefore spell it `:some`
  rather than `:any`, so the catalogue and the view agree about what a caller may
  pass.
- **The same address twice is deliberate sharing.** Two occurrences passed one
  `:control` value read and write ONE record, on purpose: the address is the caller's
  statement about which state this is, so two views onto one draft are spelled by
  giving them one address. It is a feature of caller-supplied addressing, not an
  accident of it, and it is not diagnosed. Two occurrences passed different addresses
  hold independent records for the same reason.
- **Occurrence identity is evidence, never an address.** The renderer's occurrence
  identity keeps its own jobs — reconciliation, event-site ownership, presence,
  connection generations. Development tooling MAY record the join from an occurrence
  to the controller record it read, so a tool can navigate from a mounted view to its
  state; that join is evidence only, and no public reader of occurrence identity is
  exposed for state addressing.

#### Controller state is ordinary frame data

A controller record is frame-scoped re-frame data — normally that frame's app-db. It
is read by ordinary subscriptions, moved by ordinary events, carried by epochs and
snapshots, and inspectable by ordinary tools without mounting a host. Freehand fixes
the identity model and **not** the storage path: the library chooses the root its
records live under, exactly as it chooses its own event ids, so there is no reserved
`[:rf/controllers …]` location to migrate later.

Only controllers with live state need a record. An idle buffered field showing its
external `:value` has none; a record appears on the first real edit and is gone at
commit or cancel.

#### Semantic transitions and owner cleanup

State moves by **named semantic events**, not by storage verbs. `edited`,
`committed`, `cancelled`, `opened`, `moved` say what the user did and why the state
moved; a `put` or a `toggle` records only that a location changed, and a trace of
storage mechanics cannot explain a protocol. One user action still yields one event
vector or `nil` (§Event intent and the payload materializer), and the transition's
own handler returns whatever effects the step needs — including dispatching the
caller's intent — so a commit and the domain event it causes settle as one epoch.

A decision that depends on changing state is made **in the handler against the
committed frame**, never by a guard captured during render: that is what lets Escape
beat a late blur rather than racing it.

Cleanup is the caller's, and it has two layers that must not be confused:

| What | Released by |
|---|---|
| the view's subscriptions, callbacks, and host connections | disconnect, automatically |
| the controller record | a semantic transition, or its causal owner clearing the address |
| the domain value the commit produced | ordinary domain events |

**There is no lifecycle cleanup hook, and there will not be one.** Freehand exposes
no unmount callback, no dispose registration, and no per-occurrence teardown slot a
controller could hang cleanup on — mount and unmount are tool facts, not domain
events (§Governing laws, law 5). A record therefore persists until a transition
retires it or its owner clears it: the route, form, workflow, or record whose
lifetime the state actually follows dispatches an ordinary event that removes the
addresses it owns. This is deliberate. Unmount-driven cleanup destroys a draft when a
virtualized row scrolls out of view, and makes retention a property of what is on
screen rather than of what the work is; the cost is that a forgotten owner leaves an
orphaned record, so development tooling reports orphaned and stale controller records
instead of the substrate guessing.

#### The buffered controller and the reset generation

A **buffered** control is one that holds a draft while the user edits and commits it
later — on blur, on Enter — instead of publishing every keystroke. It is the one
controller shape a component library cannot avoid, and it is the one that goes wrong
in the same way in every framework, so Freehand ships exactly **one** of it. The
ruling is
[D016](../docs/design/freehand/decisions/D016-buffered-and-revision-controls.md).

The difficulty is not the draft. It is that a caller must be able to **reject** one,
and rejection is frequently spelled by standing by what the caller already had:

> The accepted amount is `"10"`. The user types `"bad"` and commits it. The caller
> validates, refuses, and reasserts `"10"`.

The value before that decision and the value after it are identical, so nothing
derived from the value can see the decision at all — and a control that watched the
value keeps the refused draft on screen. This is not a subtle case. It is the
ordinary shape of asynchronous validation, of a transforming caller, and of a "revert"
button, and it is the failure every hand-rolled buffered input eventually acquires.

So a buffered control is **generation-fenced**, and its generation is the caller's:

```clojure
[buffered-field {:control   [:invoice invoice-id :amount]
                 :value     (v/sub [:invoice/amount invoice-id])
                 :reset-key (v/sub [:invoice/amount-revision invoice-id])
                 :on-commit [:invoice/amount-committed invoice-id]}]
```

- **`:reset-key` is REQUIRED.** A buffered control rendered without one is refused
  loudly with `:rf.error/view-control-reset-revision-missing`, naming the kind and
  the recovery. Optional would be the worse design, not the friendlier one: a control
  with no generation buffers correctly right up to the first rejection and then loses
  it, which is a defect that reaches production because development never rejects
  anything. Requiring it puts the obligation at every call site, and a caller that
  genuinely never resets says so — `:reset-key 0` reads as *do not externally reset
  an active edit*, which is a statement rather than a silence. The verb is
  `v/controller-revision`, which takes the caller's revision and raises the refusal.
- **`nil` is not a generation.** An explicit `:reset-key nil` is refused under its own
  id, `:rf.error/view-control-reset-revision-nil` — the same split the address makes,
  for the same reason, and an uninitialised counter read before its baseline exists is
  the ordinary way to produce one. The fence answers *not current* for an unstamped
  record, so a `nil` generation would leave every draft in the control permanently
  invisible while the control went on accepting keystrokes. As with the address,
  presence decides and a schema naming `:reset-key` spells it `:some`.
- **It is a revision, never the value.** It is any immutable EDN the caller advances
  when it establishes a new baseline decision, compared only for `rf=` equality. A
  caller that passed the value as its own reset key would have written the bug above.
- **The draft carries the generation it was made under.** A live record is at minimum
  `{:reset-key … :draft …}`, and the generation stamp is what the fence reads.

Nothing about buffering changes how the keystroke itself is delivered: the control's
element is an ordinary controlled input, so its edit takes the synchronous round trip
under the same door predicate as any other (§Controlled inputs). What the buffer
changes is only *where the value round-trips to* — the draft rather than the domain
value — and the draft is ordinary frame data, so an epoch, a snapshot and a JVM test
all see it.

#### The generation fence

One predicate decides currency, and both of a buffered control's boundaries ask it:

| Boundary | The question |
|---|---|
| the **read**, each render | is this draft's generation still the caller's? If not, display `:value` |
| the **write**, in the handler | does the committed record's generation still match the intent's? If not, produce nothing |

They are the same question because a control whose display and whose commit disagreed
about which generation is live would commit something the user could not see. They are
therefore asked through ONE published function, `v/controller-current?`, so the two
boundaries cannot drift into two spellings. A missing stamp is **not** current,
whatever the generation is: work that cannot prove its currency does not have it — the
half a hand-rolled equality check gets wrong, since two absent stamps compare equal and
an unstamped record would read as current.

**A superseded draft is invisible, not erased.** That is the whole mechanism, and it
is why this needs no new machinery. The fence is a comparison over ordinary frame
data, so a new generation exposes the caller's baseline on the very next render with
**no render-time dispatch, no render-phase mutation of state or of a host node, and
no remount**. A render that the host restarts, double-invokes or abandons therefore
changes nothing — the same law the atomic shell already states, applied to a draft
rather than to a dependency set (§Governing laws, law 3). Key-remount and
effect-driven reset are both excluded, and for the same reason: a remount destroys
focus, selection and any composition in flight, and an effect commits one stale frame
before it corrects.

**The reset target is the caller's current value, not a mount-time snapshot.** The
control has no snapshot to restore — it simply stops displaying the draft, and what
is left is the `:value` expression evaluated by *this* render. A caller that rejects
by transforming rather than by refusing gets the transformed value for free.

**Late work is fenced by the last committed render.** An event site fires the intent
of the render the host SELECTED — an abandoned candidate published no event bodies at
all — so the generation an intent carries is always the last committed one. A blur
arriving after a reset has rendered therefore speaks for the new generation and finds
a record that speaks for the old, and produces nothing. In the other direction an
edit stamps the record with the generation its own render displayed, so an edit that
lands after a reset is **born stale**: never shown, and not committable either. The
reset wins, deliberately — a keystroke aimed at a baseline the caller has withdrawn
must not reinstate it.

Decisions that depend on changing state are made in the handler against committed
state, exactly as §Semantic transitions and owner cleanup requires, so cancel beats a
late blur and a repeated commit is an idempotent no-op rather than a second domain
event.

**The evidence is the ordinary record.** A reset is the caller's own event moving the
caller's own data; the substrate publishes no reset channel, emits no discard event,
and deletes nothing to tidy up. A superseded record keeps its stamp until the next
edit replaces it or its owner clears it, so an app-db diff across the caller's event
names precisely which generation was superseded and which drafts it orphaned.

**Conformance:** [FH-CTRL-006](conformance/freehand/conformance-index.md#fh-ctrl--controllers),
FH-CTRL-007, FH-CTRL-008, FH-CTRL-009, FH-CTRL-010, FH-CTRL-011.

#### Vocabulary and absences

Control families are **first-party library vocabulary, not framework grammar**.
Freehand owns the laws above — addressing, one-event intent, committed-state
decisions, evidence — and a component library owns `buffered-field`, its record
schema, and its `edited` / `committed` / `cancelled` event ids under its own
namespace. No `:rf.field/*`, `:rf.dropdown/*`, or `:rf.typeahead/*` namespace is
reserved: a policy graduates into framework vocabulary only when independent
consumers need identical semantics, and retracting a reserved id from application
event logs is far more expensive than never promising it.

A Freehand implementation MUST NOT provide:

- **`local`, or any component-local state tier.** The donor's `local` and its
  placement machinery do not cross; the replacement is this section.
- **generic storage verbs.** No public `put` / `merge` / `toggle` / `clear` over a
  controller root. Generic verbs would become `local` in app-db under another name,
  and they lose the protocol's meaning from the trace.
- **derived writable anchors, or a public occurrence reader.** No `v/self`, and no
  automatic address behind an omitted `:control`.
- **a controller declaration form or DSL.** No `register-kind!`, no
  `def-control-event`, no reducer language. Controllers stay ordinary re-frame
  registrations until repeated mechanics across shipped control families justify
  extracting something, and the extraction is then its own decision.
- **a lifecycle cleanup hook.** No unmount callback, dispose registration, or
  per-occurrence teardown slot, in either execution mode.

**Conformance:** [FH-CTRL-001](conformance/freehand/conformance-index.md#fh-ctrl--controllers),
FH-CTRL-002, FH-CTRL-003, FH-CTRL-004, FH-CTRL-005.

### Forms and the first-party control kit

The sections above give a library the two things the substrate owes it —
addressing, and the generation fence. What they do not give an application is a
**form**, and a form is where those two ideas are actually needed. The accepted
product-completion setpoint's DC-03 and DC-04 fill that in, and ER-04 keeps both
inside Freehand's own distribution rather than in a second artefact:
`re-frame.freehand.form` for the transitions and `re-frame.freehand.controls`
for the controls.

Neither is a form runtime. There is no atom, no validator engine, no schema
renderer, no generic local store and no widget catalogue — the non-goals of the
programme stand. What there is, is a value and some functions over it.

#### Pure form transitions

`re-frame.freehand.form` is **side-effect-free CLJC over ordinary application
data**. It MUST register no event and no subscription, own no mutable state, and
read no frame; every operation is `(f form …) -> form`, so an application owns
event registration, validation policy, app-db placement and rendering, exactly as
it does today.

```clojure
(rf/reg-event :editor/edited
  (fn [{:keys [db]} [_ path text]]
    {:db (update db :editor form/edit path text)}))
```

The starting public roster is `init`, `edit`, `visit`, `seed`, `reset`,
`rebase`, `set-errors`, `attempt-submit` and `reset-key`, plus the one narrow
reader §The narrow read requires.

A form is a plain map, and its slots are public because a documented map needs no
reader for each key:

| Slot | |
|---|---|
| `:baseline` | the last values the DOMAIN accepted |
| `:draft` | what the controls show and edit |
| `:visited` | leaf paths the user has focused and left |
| `:edited` | leaf paths the user has CHANGED |
| `:resets` | leaf path → reset revision |
| `:errors` | leaf path → structured message |
| `:submit` | `{:attempted? :attempts :pending}` |

- **A leaf path is a non-empty vector.** `[:contact :email]`,
  `[:lines "line-7" :qty]`. That is what makes four of the slots flat sets and
  maps keyed by one comparable value rather than four parallel nested trees, and
  it is what lets a row keep its identity: a row addressed by its **stable domain
  id** survives an insert, a delete and a re-sort, where a row addressed by its
  index silently becomes a different row.
- **`:visited` and `:edited` are two sets, and collapsing them is a bug.**
  `:edited` is *the user changed this* and is what protects a leaf from a late
  load; `:visited` is *the user has been here and left* and is what reveals an
  error. A tab-through is visited and not edited. One `touched` set either makes
  a tab-through wipe-protected, or makes an untouched required field scream.
- **`edit` marks the leaf edited unconditionally**, including at a value equal to
  the baseline. A leaf typed into and corrected back is still the user's.
- **The reset revision is PER LEAF.** A form-wide generation would make rejecting
  one field discard every other field's in-flight edit.
- **Submit stays attemptable.** `attempt-submit` always marks the form attempted,
  which reveals every error at once; when there are none it also opens a pending
  save stamped with the attempt number. That stamp is the **staleness fence**: a
  reply carrying a superseded number is inert. `rebase`, `set-errors` and `reset`
  each settle a pending submit, because each of them IS the reply landing.

**Conformance:** [FH-CTRL-012](conformance/freehand/conformance-index.md#fh-ctrl--controllers),
FH-CTRL-015.

#### Seed, reset and rebase

Three transitions move a form from the outside, and the whole of the design is
that they are **three and not one**.

| | draft | edited marks | reset revisions |
|---|---|---|---|
| `seed` | edited leaves held; the rest take the new value | untouched | untouched |
| `reset` | discarded, back to baseline | cleared | **ADVANCED** |
| `rebase` | KEPT | cleared where the draft now agrees | untouched |

- **`seed` is LEAFWISE, and that is the fix for the oldest bug in forms.** A slow
  load returns while the user is typing and a whole-map `assoc` over the draft
  erases the keystrokes. Seeding leafwise MUST hold every leaf in `:edited` — in
  the draft *and* in the baseline, so the work is neither displayed over nor
  silently re-based under — MUST leave every leaf the payload does not mention
  untouched, including a sibling of one that is mentioned and a whole sub-tree
  the payload omits, and MUST mark nothing visited or edited, because the server
  typing is not the user typing. A seeded leaf moves baseline and draft together
  and clears the error its old value carried.
- **A payload leaf that OVERLAPS an edited leaf is held too, and held WHOLE.**
  Two leaf paths overlap when one addresses data inside the other, which
  membership of `:edited` does not answer: `{:contact {}}` is a single leaf at
  `[:contact]` — an empty map, like a scalar, stops the walk — so writing it
  would erase an edited `[:contact :email]` from *above*, while a payload leaf
  `[:tags :featured]` reaches *below* an edited `[:tags]` and would overwrite it.
  `seed` MUST therefore decline a payload leaf that is an ancestor **or** a
  descendant of an edited leaf, entirely rather than partially — a partial write
  of a leaf is not a thing this model has. The consequence is a second MUST that
  holds over every seed: **no `seed` may leave an `:edited` path naming data that
  is no longer there.** A marker describing an absent leaf is a defect on its own
  terms, whatever the draft beside it looks like.
- **`reset` is a REJECTION and advances the generation.** A caller refuses a
  draft by reasserting what it already had, so nothing derived from the value can
  observe the decision (§The generation fence). Advancing the leaf's revision is
  the one signal equality is not blind to. Scoped to a leaf, it advances that
  leaf's generation and no other.
- **Back to the baseline means back to ABSENT where the baseline has no key.** A
  baseline `nil` is a value the domain accepted and MUST be restored as one; a
  baseline key that is *missing* is the domain never having held that leaf, and a
  scoped `reset` MUST remove the draft leaf rather than materialise it holding
  `nil` — at any depth, together with any enclosing map the removal empties, up
  to the first ancestor the baseline does have a key for. `get-in` answers `nil`
  for both cases and `assoc-in` materialises whichever it is handed, so this is a
  distinction an implementation has to make deliberately and a conformance row
  has to pin through **presence** rather than through the value. The scoped arity
  therefore converges on what the whole-form arity takes, which is the baseline
  itself.
- **`rebase` is an ACCEPTANCE and advances nothing.** The baseline moved under a
  live draft — a save landed, an authoritative refresh arrived — and the user's
  unsaved work is not a draft to be rejected, so a control holding a live buffer
  keeps it. A leaf whose draft now equals the new baseline stops being edited;
  there is nothing left to protect there.

**Conformance:** [FH-CTRL-013](conformance/freehand/conformance-index.md#fh-ctrl--controllers),
FH-CTRL-014.

#### The narrow read

A form read through its **container** — subscribe to `:draft`, then `get` the key
— is invalidated by a character typed in any *other* field; and because a
controlled input's synchronous door drains re-frame and flushes the dirty cells
on **this** frame before returning into React (§Controlled inputs), every one of
those fields re-renders *inside* the keystroke rather than after it. Whole-container
reads therefore make keystroke latency scale with the size of the form. On a form
this is a cost model rather than a matter of taste, which is why the narrow read
is a published derivation rather than advice.

```clojure
(rf/reg-sub :editor/field
  (fn [db [_ path]] (form/field (:editor db) path)))
```

`form/field` answers everything one control needs about one leaf and nothing
about any other — `:value`, `:baseline`, `:error`, `:visited?`, `:edited?`,
`:show-error?`, `:reset-key`, and the leaf path itself under the reserved
`:re-frame.freehand/path`. That is **one subscription for a form of any size**: it
recomputes on every keystroke, as every subscription over app-db does, and its
value changes only when that leaf's does.

`:show-error?` is the REVEAL policy and not the validation policy. An error shows
once the user has visited the leaf, or once a submit has been attempted; which
errors exist is the application's to decide.

#### The first-party control kit

`re-frame.freehand.controls` is a **small kit grown through serious witnesses**,
not a catalogue promise. Skins, layouts and application compositions are meant to
be copied and adapted; the correctness machinery is not copy-only. Today it is
`field`, `buffered-field` and `release`.

```clojure
[c/field {:field    (v/sub [:editor/field [:contact :email]])
          :on-edit  [:editor/edited      [:contact :email]]
          :on-visit [:editor/visited     [:contact :email]]}]
```

- **Neither control has a `:value` prop.** The only value either accepts is a
  `form/field` projection, and a caller's forwarded attributes go through
  `v/spread-safe`, whose deny law refuses `value` on the component's own
  controlled element in every build. The container read is not discouraged here;
  there is nowhere to put it.
- **Each renders one native `<input>`** carrying `value` with a literal event
  vector at `onInput` — the shape the door recognises — and one attribute of its
  own beyond the contract, `aria-invalid`, from the projection's `:show-error?`.
  The label, the message and the layout are the application's: a control that
  owned its markup would be un-adaptable exactly where design systems differ.
- **`buffered-field` keeps its draft IN THE FORM.** What is buffered is the
  *domain's* view of it: `:on-edit` writes the form's draft leaf on every
  keystroke, and `:on-commit` fires on blur and on Enter, carrying the leaf's
  reset revision so the receiving handler decides against committed state. There
  is no host slot, no local buffer and no controller record, so a re-render, an
  abandoned candidate, a StrictMode double-invoke and a hot reload are all
  uneventful.
- **A COMPOSING ENTER MUST NOT COMMIT.** The Enter that accepts an input-method
  candidate belongs to the IME, and a control that reads it as a commit fires the
  domain event mid-word on every phrase a Japanese, Chinese or Korean user types.
  A composing Escape is the IME's too. The rule is a pure function of the key and
  the composition flag (`controls/key-intent`), so it is provable without a
  browser.
- **`release` is the causal owner's clear**, and it is exact: it removes the form
  slices the owner NAMED — never "every form", never "everything this kind holds"
  — in one value, creating nothing where a path's parent is absent and changing
  nothing when it runs twice. It exists because there is no lifecycle cleanup hook
  and there will not be one (§Semantic transitions and owner cleanup); **unmount
  is not a domain event, and the absence of a mounted occurrence is not proof of
  orphaning.** Nothing in this kit can be orphaned, because its controls keep
  their state in the form the owner already owns.

Both declarations are inside the compiled grammar as they stand, so promotion is
a keyword rather than a rewrite and interpreted/compiled structural parity holds
by construction.

**Conformance:** [FH-CTRL-016](conformance/freehand/conformance-index.md#fh-ctrl--controllers),
FH-CTRL-017.

### Presence

Presence is declarative enter/exit retention over keyed children — deliberately
bounded, and not an animation system. It keeps a departed child mounted long
enough for the child's own exit transition to run, and nothing more: no easing,
no curves, no timeline, no presence-event bus.

```clojure
(v/presence {:timeout-ms 300}
  (for [t toasts]
    [toast-card {:key (:id t) :toast t}]))
```

One retention contract, honoured identically by both execution modes. In the
interpreted mode `(v/presence …)` is an ordinary function call whose result the
walk lowers; in the compiled mode it is a form the analyzer recognises and the
emitter lowers. Both modes reach the SAME retention runtime, so the behaviour
below is one implementation, not two that agree.

- **The phase machine.** Every keyed child passes `:mounting → :present`. When a
  key leaves the incoming set its child is not removed but RETAINED in
  `:unmounting`, so its exit transition runs; the child is dropped from the set
  only by its own removal, never by a later render.
- **The terminal bound.** `:timeout-ms` is MANDATORY and is a positive number of
  milliseconds. It is the exit retention duration AND the terminal safety bound
  in one: a retained child is removed when `:timeout-ms` fires — not when some
  other completion signal arrives — and the removal is terminal and
  exactly-once, releasing every subscription, event and effect the retained
  subtree owned.
- **Keyed children.** Presence tracks children BY KEY — a key IS a retained
  identity — so every child must be keyed. The compiled mode rejects an unkeyed
  presence child at build time; the interpreted mode drops a child whose runtime
  key is absent and reports it, and two children under one key resolve to the
  first claimant with the collision reported.
- **First-appearance order is frozen.** A key holds the slot it first mounted
  into; a newly-appearing key appends at the tail regardless of its incoming
  position, and an incoming reorder is ignored. An exiting child never jumps
  position mid-transition. A list whose rendered order must track a changing sort
  does not belong under a presence boundary — sort upstream, and handle display
  order at the presentation layer.
- **Re-entry interrupts.** Removal-then-reinsertion of a key before its timeout
  fires flips it from `:unmounting` back to `:present` and cancels the pending
  removal; re-entry is the same child returning, never a second one.
- **The phase read.** `(v/presence-phase)` is the single phase read — `:mounting`
  / `:present` / `:unmounting` inside a boundary, and `:present` outside one, so
  a presence-aware child stays reusable anywhere.
- **DOM-agnostic.** The boundary inserts no wrapper node, stamps no attributes,
  and observes no DOM events. A presence-aware child owns its own exit styling
  and accessibility: it stamps `inert` / `aria-hidden` and its exit class against
  `(v/presence-phase)` = `:unmounting`, and its stylesheet honours
  `prefers-reduced-motion`.
- **The structural projection.** The structural render has no lifecycle, so it
  renders every retained child `:present` and exposes the presence fact
  structurally as a fragment carrying `:rf.ui/presence {:phase :present
  :timeout-ms n}` — a droppable reserved marker, so a consumer that does not
  read it sees a plain fragment of `:present` children. That is a fact about the
  RENDERER, not about the host or the mode: the structural render answers
  `(v/presence-phase)` = `:present` on the JVM and in ClojureScript alike, and
  from an interpreted declaration and a `{:compiled true}` one alike, so a
  presence-aware child RENDERS under a structural test rather than reaching for
  a lifecycle that is not there.
- **Test behaviour.** A deterministic clock advances retention without a
  wall-clock sleep, so a retention window closes exactly when a test says and a
  run carries no wall-clock flake.

`(v/presence-phase)` remains for the uncommon child whose STRUCTURE, rather than
its attributes, depends on phase; the ordinary case reads the phase for styling
and accessibility alone.

### Error boundaries and error egress

A render failure is inevitable — a nil assumption, malformed Hiccup, a foreign
component throwing, stale data violating a contract. The atomic shell already
guarantees that a candidate which throws owns nothing and publishes nothing
(§Passive render and atomic selection). It does not decide what the user sees
next, or how the failure reaches production telemetry. `v/error-boundary` is
that decision, and it is common: the JVM structural host contains a throwing
child with a `try` around its walk, and the browser realises the same law
through a React class boundary, but both drive one host-neutral state machine,
so a structural test and a mounted boundary contain the same failure the same
way. The contract is ruled by
[D019](../docs/design/freehand/decisions/D019-error-boundaries-and-production-reports.md);
the egress category and its channel are
[009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).

`v/error-boundary` is a declared framework boundary, mounted `[v/error-boundary
{…} child]` and never called, with a closed option roster: `:fallback`
(required — what renders when a throw is caught below the boundary),
`:reset-key` (the caller-owned retry value), and `:on-error` (the optional safe
intent). It catches render-class failures below it — a Freehand child body
throwing, Hiccup normalization or common prop/event validation throwing, and,
in the browser, a descendant foreign component throwing where React boundaries
apply. It does **not** catch event-handler, asynchronous, or re-frame
handler/sub/resource failures: those keep their existing typed owners. An
unknown option, a missing `:fallback`, an `:on-error` that is not an
event-prefix vector, or anything other than exactly one guarded child is
`:rf.error/error-boundary-bad-args` at the interpreted mount and
`:rf.ui.compile/bad-error-boundary` at the compiled build. The boundary guards
**one** region, so a second declared child is refused rather than kept and
discarded — wrap siblings in a fragment. A dropped subtree would be missing from
the page with nothing on screen or in the console to say so, and the two modes
would disagree about one declaration.

#### Containment and the abandoned candidate

A throwing child is **contained**: the boundary shows its `:fallback` while every
sibling subtree keeps rendering, and the throw never reaches the surrounding
markup, so the rest of the tree still renders. On the structural host the
boundary node holds the fallback subtree instead of the child's; in the browser
the boundary re-renders its fallback in place of the failed child's fiber.

The candidate that threw **publishes nothing at all**. This is the atomic
shell's law, not a second mechanism: an abandoned render is abandoned by
dropping its candidate, so its cell is still `:new`, owns no dependencies, and
carries no evidence — the selected-commit bundle is simply absent. A boundary
contains a failure; it never has to un-publish one.

If the fallback itself throws, the error propagates to the next outer boundary
rather than being caught here — a boundary never tries to catch its own fallback
indefinitely. On the server, a render failure propagates to the server error
projector rather than simulating client recovery; that projection is the SSR
slice's, cited here so the boundary law and the server law do not drift.

#### Reset and the once-per-generation safe intent

`:reset-key` is a caller-owned value. When it changes by `rf=`, the boundary
clears the captured failure and re-mounts the child, retrying it; an unchanged
key leaves a failed boundary on its fallback. There is no boundary ref and no
imperative reset handle — a retry button dispatches an ordinary event that moves
the reset value, so recovery is data, not a side channel.

`:on-error`, when present, is one event prefix. The framework appends the safe
public summary and dispatches the result exactly **once per failure
generation**, after the fallback commits. A failure generation is one captured
episode: a fresh capture from the cleared state advances the generation, and a
repeated capture while already failed — a StrictMode double-invoke, an HMR
re-render, a repeated parent render of the same failure — is a no-op that
reports nothing new. So under repeated failures the intent fires exactly once,
not "at least once"; a new generation, after a reset and another throw, reports
exactly once again. Lifecycle and render errors are not domain events: an
application receives an intent only when the author explicitly supplies
`:on-error`, and an error site produces at most one event vector, never a
secondary event DSL.

#### The safe summary and the private frame egress

One caught failure has two audiences that must not share a representation.

The **safe public summary** is the bounded, serialisable envelope an application
receives — on its `:on-error` event, and in any structural value. Its field set
is closed: a stable diagnostic id (`:re-frame.freehand/render-failed`), the
failing declared view id and the boundary's own view id, the finite phase
(`:render`, `:normalize`, `:foreign-render`), the resolved frame id, a stable
fingerprint, and D020 evidence (scope, basis, completeness, loss).

The failing view id is the view that **threw**, however far below the guarded
child it sits — the boundary that caught it is a separate field. Both facts are
carried because they are two facts: a summary naming the catcher sends a reader
to the wrong file, and since the fingerprint derives from the failing view and
the phase, it would also collapse every failure beneath one boundary onto a
single correlation token. Where no occurrence observed which declared view threw
— a foreign component's failure, a host without the occurrence seam — the
summary says so: the view id is `:re-frame.freehand/unknown-view` and the
evidence is incomplete with a `:loss` naming why. Truthful ignorance, never the
boundary's own id in the failing slot.

Attribution is **failure-local**: it belongs to the throw, not to the boundary
that catches next. Failures overlap routinely — a host may finish rendering
every failed subtree of a commit before it reports any of them, a fallback may
fail while the failure it replaced is still unreported, and two renders may run
at once — and each report names the view that threw the failure that report is
about. One failure can neither borrow, suppress, nor erase another's identity,
and a failure nothing ever reported leaves nothing a later boundary can read.

The summary carries **nothing** host-shaped: not the raw exception, not
`ex-data`, not props, not app-db, not event payloads — every one of those is a
data-classification hazard, and the envelope is what may reach an event vector
and a serializable trace.

The **private frame error egress** is the second channel. At most one record per
failure generation is promoted onto re-frame's existing always-on error axis
(`:rf.error/view-render-failed`) and the frame-owned observability sink,
carrying the safe summary plus the opaque exception and a capped host/component
stack an off-box shipper needs. Observer and sink code own redaction,
source-map processing, transport, and vendor integration; a sink failure is
isolated and never replaces the user's fallback. This record carries **no**
automatic app-db or event-history capture. That omission is deliberate
(D019 rejects it): "redacted" is application-specific, event payloads and state
routinely carry secrets and large values, and coupling basic containment to
history policy invites a false promise that every report is replayable. An
application that wants a redacted snapshot obtains it through its own `:on-error`
handler and an allow-list it owns.

### Diagnostics and evidence

**Not authored here.** This heading covers the occurrence-keyed evidence
model and the scope/basis/completeness/loss statement; the ids and retention axis are
[009](009-Instrumentation.md).

## Composition

### Children, compound children, and parameterized content

**Not authored here.** This heading covers the default child region and
compound child views. The parameterized-render pair IS described: it is
[§Render slots](#render-slots) below.

### Render slots

A component that renders a list knows the list; it does not know what a row looks
like. **A render slot is content the caller supplies and the component invokes**, at
the site the component chooses, with the arguments the component supplies.

`v/render-fn` declares the content and `v/slot` invokes it. Both are **common
grammar**: seq forms the compiled analyzer recognises and lowers, and an ordinary
macro and an ordinary function call in an interpreted body — one spelling, one
contract, two front ends.

```clojure
(v/defview data-table [{:keys [rows row]}]
  [:tbody (for [r rows] [:tr {:key (:id r)} (v/slot row r)])])

[data-table {:rows rows
             :row  (v/render-fn [r] [:td (:name r)])}]
```

Normatively:

- **A slot value is a `v/render-fn`, or `nil`.** `nil` renders nothing, so a component
  may offer content it does not require and a caller may decline it without the
  component branching on absence. Anything else is `:rf.error/ui-tree-malformed`
  naming the render-fn recovery.
- **The arity is a contract, not a convention.** A render-fn declares a fixed
  parameter vector; a slot passes exactly that many arguments, checked **before** the
  call. The two hosts disagree about what a mismatch does — JavaScript drops surplus
  arguments silently and passes `undefined` for missing ones, the JVM throws a raw
  `ArityException` — and neither is a diagnostic, so the count is settled where the
  answer can be the same on both. An **inline** render-fn's arity is settled at build
  time in a compiled body; a **prop-carried** one carries its declared count and is
  checked at the seam.
- **The rendered output is an ordinary child.** It participates in the surrounding
  children exactly like any other: there is no slot node, no wrapper element, and no
  special representation in the structural tree. A slot-carrying **prop** is recorded
  on the boundary as `{:rf.ui/opaque :v/render-fn}` — the authoring form is named
  because a slot prop is a contract between the caller and the seam, and a test
  asserting the caller supplied one is asserting something the mode-neutral `:fn`
  marker cannot say ([004B §The opaque marker](004B-UI-Tree-and-Conversion.md#the-opaque-marker)).
- **A slot body is a pure render fragment.** It may run during an uncommitted
  candidate render — that is why `v/render-fn` sits outside the committed-proxy
  scheme ([§Callback roles and identity](#callback-roles-and-identity)) — so it may
  not `v/sub`, dispatch, use hooks or touch refs. A statically named internal view
  head stays legal, which is the recovery: a *stateful* part is a pure slot body
  mounting a declared view that owns its own state.

**One asymmetry between the modes, and it is deliberate.** An **interpreted** slot
also accepts an ordinary pure function of the same arguments. It has nothing to prove
about what it invokes, and refusing the plainest spelling of "a function of a row"
would be ceremony. A **compiled** slot does not: the compiled tier's whole claim is
that it can *see* what it lowers, and a function value is exactly what it cannot. A
bare fn written lexically at a compiled `v/slot` is a build-time refusal naming
`v/render-fn`; the always-available rung — drop `{:compiled true}` — accepts the body
unchanged.

The same law read from the other side gives the crossing rule. What a render-fn body
*answers* differs by mode: interpreted it answers markup, compiled it answers a node.
A compiled render-fn is therefore usable from an interpreted slot — a node is a child
value anywhere — while an interpreted one handed to a **compiled** slot lands on the
markup-inside-compiled-markup refusal, with the recovery D010 already states. Caller
and seam are promoted together, or neither is.

**Conformance:** [FH-CALL-006](conformance/freehand/conformance-index.md#fh-call--calls).

### Props forwarding

Forwarding a consumer's attribute map onto an element you own is **two different
bargains**, and the grammar makes the author pick one at the site rather than
inferring which was meant.

`(v/spread base)` / `(v/spread base overrides)` is the **visible-cost** forward.
Whatever the maps carry lands on the element, `overrides` winning every collision,
and the author said so at the site.

```clojure
[:div.card (v/spread attrs {:class "is-open"})]
```

`(v/spread-safe owned caller)` is the **bounded** one, and the bound is what a
component library needs.

```clojure
(v/defview text-field [{:keys [value attrs]}]
  [:input (v/spread-safe {:value value :on-change [:field/changed]} attrs)])
```

Normatively, for both forms:

- **A runtime map is judged by the rule a literal map is judged by.** Every forwarded
  key takes the same refusals the direct attribute path applies, read off the same
  emitted slot ([004B §Attribute names](004B-UI-Tree-and-Conversion.md#attribute-names)),
  so a map assembled at run time cannot carry a spelling the grammar refuses at a
  visible site. This is not a second table: it is the same one, asked at the seam the
  compiler cannot see through.
- **`:key` is refused outright.** A key is not an attribute — the reconciler consumes
  it and it never reaches the DOM — and it is literal at the element that carries it.
  Honouring one in a forwarded map would decide element identity from a value the
  compiler cannot see, so it is refused rather than silently honoured in one mode and
  dropped in the other.
- **Both forms fold onto an element, and neither is legal at a `v/defhost` head.**
  Their entire content is the attribute grammar — the refusals above, the slot
  canonicalization [004B §Attribute names](004B-UI-Tree-and-Conversion.md#attribute-names)
  applies to an alias of an accepted key, the `.class#id` sugar, the controlled door —
  and a host prop is none of those. It is an exact unqualified keyword handed shallowly
  to a foreign API ([§Three disjoint planes](#three-disjoint-planes)), so running one
  through the element rule is wrong in both directions at once. It **admits** what the
  host head refuses, because an alias of a slot-owning key is rewritten on the way
  through: `:className` — the name a React library actually wants — arrives as `class`,
  a prop the component does not read, and `"class"` walks past the exactness check that
  would have caught it. And it **refuses** what the host head accepts, because
  `:class-name`, a mixed-case `data-*` and `:key` name nothing on a foreign prop ABI
  that the attribute table is entitled to judge. Forward to a host with an ordinary
  map — `merge`, caller's remainder as the base and the props you own second — and the
  host's own naming law then judges every key that arrives, which is the only law that
  was ever true there. The refusal is `:rf.error/view-bad-props` with
  `:forward-to-a-host-with-an-ordinary-map`, and it runs BEFORE the head's exactness
  law ([§Three disjoint planes](#three-disjoint-planes)), because a spread has already
  rewritten the very names that law reads. Both forms answer a plain map, so what the
  boundary reads is a MARK the forms leave on their own result — provenance, not a key
  inspection, since a corrupted map is indistinguishable from an authored one by the
  time it arrives.

And for `v/spread-safe` alone:

- **The deny law runs in EVERY build.** `:key`, `:ref`, `:value`, `:checked` and the
  component's own `on-*` handler families — both the bubble and the capture phase —
  may not appear in `caller`. A literal offender is the compile error
  `:rf.ui.compile/spread-safe-owned-key`; a runtime one is
  `:rf.error/ui-tree-malformed`. Neither is elided in an advanced build, because a
  denial a component library relies on is not a development aid.
- **Alternate spellings do not route around it.** A key is judged by the slot it is
  about to be written into, so a namespaced keyword, a string, a symbol or an
  already-camel spelling of a denied name is denied with it.
- **What survives folds UNDER the owned props**, with `:class` the one exception: the
  two class values **compose**, owned classes first, because a caller passing a
  utility class is adding to the element rather than replacing what the component put
  there.

That bound is what lets a component keep a promise about the element it renders. A
controlled input stays controlled and its sync door survives
([§Controlled inputs](#controlled-inputs)), an owned handler stays the one that fires,
and the consumer still passes `aria-*`, `data-*`, a class and a style. A general
`v/spread` claims none of that, which is why it stays the visible-cost escape rather
than the default.

**Conformance:** [FH-PROPS-006](conformance/freehand/conformance-index.md#fh-props--props).

### Theming and semantic parts

Theming is **two planes**. The **styling** plane is CSS tokens and semantic part
addresses; the **structural** plane is the composition forms that already exist. The
split is what lets a component library be restyled freely without making its
correctness overridable, and it costs the substrate nothing: a theme is CSS and a
part is an address, both ordinary host values. There is no theme registry, no theme
context protocol, no styling DSL, and no re-theming verb on the public surface — a
library opts into this contract through its own props schema and its own part
roster. The ruling is
[D018](../docs/design/freehand/decisions/D018-theming-and-parts.md).

**Tokens ride the cascade.** Colour, spacing, radius, typography and motion travel
as CSS custom properties, selected by an ancestor scope the application renders
once — conventionally a `data-theme` attribute or a class. Inheritance reaches every
descendant, so switching themes changes that one attribute and
**re-renders one boundary, and nothing behind a memoized view immediately below it**: no token
subscription per leaf, no remount, and no reactive fan-out proportional to the tree. The residue is
the value a stylesheet cannot know — a genuinely dynamic colour or measure supplied
at a call site — and it rides as an inline namespaced custom property on the node
that needs it. That is the escape, not the default path: a component given no
dynamic value emits no inline style at all and takes everything from the cascade.

**A part is a stable semantic address.** A component declares a finite roster of
public part ids and emits each as a literal `data-part` value under a
`data-component` scope. The scope is what lets `label`, `control` and `icon` be the
obvious names in every library at once without two libraries colliding, and it is
what a caller's stylesheet selects through. Three consequences the component author
owns:

- **The roster is a deliberate public subset**, not every node. A private
  implementation node carries no part id, which is what makes the roster a promise
  rather than a description of today's DOM.
- **A part id is API.** Renaming or removing one breaks every stylesheet that
  reached it, exactly as renaming a prop does, and it breaks it silently — the
  rendered output can look identical. Growing the roster is additive; shrinking it
  is a breaking component-contract change.
- **A variant is not a part.** `:tone`, `:size` and `:density` are fixed choices the
  component anticipated, so they are ordinary value props lowered to a class. A part
  id is an open address a caller styles; spelling a variant as one turns a closed
  choice into a public surface.

**A per-part override is a bounded spread, not a second merge law.** Stylesheets
cannot carry a per-instance analytics attribute or an `aria-*` relationship, so a
library may accept per-part attributes — conventionally a map from declared part id
to an ordinary attribute map — and merge each through `v/spread-safe`
([§Props forwarding](#props-forwarding)). It inherits that law entire: `:key`,
`:ref`, `:value`, `:checked` and the component's own handler families cannot appear,
in any spelling, in any build, and what survives folds under the owned props with
`:class` composing. So an override adds to the element and cannot replace the
controlled contract, the element's identity, or the handler that fires — which is
precisely why a themed controlled input stays controlled and keeps its synchronous
door ([§Controlled inputs](#controlled-inputs)). The substrate defines no `:parts`
prop and validates no part id: the roster is the library's data and the library
polices it, over a merge law the substrate does own.

**Structure is the other plane's job.** Replacing a label with an icon and a help
popover is composition, not styling: trailing children for one default region, a
compound child view for a fixed one, `v/render-fn` and `v/slot` for parameterized
content ([§Render slots](#render-slots)), and a declared child view when the
replacement owns state. Smuggling structure through a theme hides keys, event sites
and reactive boundaries from the analysis both modes depend on, and the established
forms keep all three visible.

**There is no portable late tree transform.** A pure Hiccup-to-Hiccup rewrite is
ordinary interpreted code and stays perfectly legal in an application and in
tooling. It is not a cross-mode theming seam, and Freehand publishes no verb that
would make it one. A compiled leaf has no tree to intercept before lowering — its
whole claim is that its template is settled at build — so a portable transform would
have to become either a second compiler language or an interpreted walker inside
compiled markup, both refused by governing law 6. Worse, a transform running after
analysis can move identity, event ownership, controlled scheduling, diagnostics and
source attribution, so a visual theme becomes a behaviour rewrite. The refusal is
the contract, and the recovery is the structural plane above.

**Where each plane is proved.** CSS is a browser mechanism, so only a mounted
browser can say that a rule targeting a part reached an element's computed style,
that an inline token resolved through `var()`, or that flipping an ancestor
`data-theme` recoloured a node the substrate never re-rendered. Everything else is
deterministic in the structural tree on both hosts: the `data-component` scope,
every emitted `data-part`, the variant class and the inline token are ordinary
attributes a JVM or SSR test reads by equality, which is how a component's emitted
address set is held to exactly its declared roster. The shipped evidence for both
halves is the theming pilot —
[`pilot_theming_cljs_test.cljc`](../implementation/freehand/test/re_frame/freehand/pilot_theming_cljs_test.cljc)
structurally and
[`pilot_theming_dom_cljs_test.cljs`](../implementation/freehand/test/re_frame/freehand/pilot_theming_dom_cljs_test.cljs)
in a real browser.

### Framework-supplied views

**Not authored here.** This heading covers the rule that framework views
are ordinary descriptors, and the route-link view over the [012](012-Routing.md) seam.

## The host boundary

### Qualified host leaves

`v/defhost` declares a React component as a Freehand **host**. It is the SOLE
public inward React boundary, and the only way to mint the third legal vector
head.

```clojure
(v/defhost date-picker
  "A third-party date picker."
  DatePicker
  {:callbacks {:onChange :event}
   :children  :none
   :ssr       :client-only})

[date-picker
 {:selected date
  :onChange (v/event [js-date]
              [:booking/date-picked (from-js-date js-date)])}]
```

**Sole is a contract, not a description.** [§Vector-head
classification](#vector-head-classification) has three legal answers and one of
them is a host descriptor; the diagnostic that refuses a bad head names it in
prose. An implementation MUST NOT publish a second way to produce one. A runtime
`v/host` constructor, a leaf/wrapper `:kind` split, and a `v/react-el` helper
that receives an already-created element are each REJECTED by name: each is the
same crossing under a second spelling, and a second spelling is a second set of
laws to keep in step. A helper that grew enough declaration and commit machinery
to carry the laws would simply *be* this door, renamed.

#### Identity and invocation

A declaration produces one stable, qualified, **non-callable** host descriptor.
It is used as a vector head; a React component MUST NOT become a legal bare
head. There is ONE descriptor kind — "leaf" and "wrapper" describe the
registered React implementation, which may itself use hooks, context, refs,
effects, Suspense, or a compound-component protocol, and not two ABIs.

A direct call MUST NOT succeed. The descriptor is nominal for the reason
[§A declared view cannot be called](#a-declared-view-cannot-be-called)
gives: a map answers `(the-host {…})` as a *lookup*, returning `nil` and
rendering nothing, which is the silent failure the boundary exists to remove at
exactly the place a hand arriving from React is most likely to call by habit.

There is **no public runtime constructor**. Host identity comes from the
declaration and stays source-locatable and checkable.

#### The declaration

The option roster is CLOSED — `:callbacks`, `:children`, `:ssr`,
`:map-props`, `:props` — and an unknown key is refused at macro expansion
rather than ignored. `:children` and `:ssr` are REQUIRED and carry no default:
Freehand never executes the registered component on the JVM, so a default would
be the substrate choosing a server behaviour silently.

#### Three disjoint planes

Ordinary props, declared callbacks, and children are disjoint, and the split is
performed once by a common normalizer both emitters call.

**Ordinary props pass shallowly and exactly by default.** `:selected` reaches
React as `selected`. There is no automatic case conversion, no deep
Clojure-to-JavaScript conversion, no callback inference, and no per-prop
conversion language. A function value in an ordinary slot is REFUSED: a callback
reaches a host only at a position the declaration named, because that is what
makes the site finite, checkable, and able to carry the committed identity
below.

**A prop NAME is exact too: an unqualified keyword, and nothing else.** The
crossing names each prop by its bare name, so a qualified keyword would arrive
with its namespace silently dropped, and a string would give one React prop a
second spelling. A second spelling is not cosmetic — every law at this boundary
is enforced BY the name, so `"children"`, `:x/key` and a `"onSelect"` no
declaration can see would each reach React under the reserved name while
passing the check that looked for the keyword. Exactness is what makes those
checks total, and it is the same law `:callbacks` already states on the
declaration side.

**`:callbacks` is a finite map from EXACT prop names to the roles `:event` or
`:handler`.** A position accepts the corresponding carrier and is never inferred
from an `on*` name. A bare event vector at a foreign callback position is NOT an
implicit converter and is refused — a host may itself want a vector at that
prop, and guessing would confuse a host value with re-frame intent.
`v/render-fn` and `v/raw-fn` are real roster forms and are deliberately not
declarable positions: neither can carry the committed-site laws a declared
position promises. Carriers materialize only at declared positions, after the
candidate is selected, and the resulting functions obey [§Callback roles and
identity](#callback-roles-and-identity) — stable identity per site, the
latest committed body and frame, silence for abandoned renders, retirement after
unmount and hot reload.

**Every declaration states its children policy**, drawn from the same closed
`#{:none :optional :required}` roster an internal boundary uses. Undeclared
child crossing is a refusal, not an opaque accident. Accepted children are
ordinary React children in the registered component's own tree, preserving that
tree's context. Freehand does NOT promise that a Freehand-authored child can
participate in `asChild`, arbitrary `cloneElement`, or ref-injection protocols;
keep such a region React-owned and register its wrapper through `v/defhost`.

Hooks remain outside `v/defview`. Using a hook inside the registered React
component is the intended route, not a hook API in Freehand.

#### The one ordinary-props adapter

A declaration MAY carry one whole-ordinary-props `:map-props` adapter to prepare
non-portable host values. It runs in the browser only, and on the ordinary plane
only: callback carriers and trailing children are WITHHELD from it and installed
afterwards.

**The adapter's answer carries EXACTLY the keys the call authored.** It owns the
ordinary plane's VALUES — that is why it exists, to build what the authored map
could not hold — and the names stay the caller's, so key-set equality is the
whole law the answer owes and it is enforced where the authored plane and the
answer both exist. Equality rather than a subset, in both directions. A created
name is a rename no call site can see: it would reach React under a name nobody
authored, and it would put `:key`, `:children` and every declared callback
position back within reach of a plane they were withheld from — which is how the
adapter MUST NOT supply or replace a reserved Freehand fact, without a
reserved-name filter for a second spelling to walk past. A dropped name would
leave the structural projection, which records the AUTHORED props, reporting a
prop React never received. A host whose props want renaming renames them inside
the registered React component.

Props-contract evidence under `:props` is OPTIONAL for application-private
declarations; the stronger publication policy of
[§Props, children, and `:key`](#props-children-and-key) still governs public
library, catalogue, and generated-parity surfaces.

#### Structure and SSR

Freehand NEVER executes the registered React component on the JVM. Every
declaration states an SSR policy, and in v1 a React host is client-only: the
declaration chooses either explicit no-server content (`:ssr :client-only`) or a
portable fallback (`:ssr {:fallback <markup>}`).

Structural rendering emits a stable honest marker carrying the declared host
identity and the permitted public evidence, never the opaque React
implementation:

```clojure
{:rf.ui/host           :app.booking/date-picker
 :rf.ui/host-ssr       :client-only
 :rf.ui/host-children  1
 :rf.ui/host-map-props true
 :props                {:selected "2026-01-05"
                        :onChange {:rf.ui/opaque :v/event}}
 :children             []}
```

Three of those slots are chosen against a way the node could lie.

- **`:children` is the SSR PROJECTION and nothing else** — the declared
  fallback, or empty. It is the slot that folds to HTML, so it MUST carry only
  what the server can honestly emit. It is retained when empty, like a fragment,
  and for the fragment's reason: the serialiser folds a host by splicing that
  slot, so a node without it is a map with nothing to emit and nothing to say so
  ([004B §The SSR consumption boundary](004B-UI-Tree-and-Conversion.md#the-ssr-consumption-boundary)).
  The variant, its discriminator and its place in the closed node set are 004B's
  ([004B §The node schema](004B-UI-Tree-and-Conversion.md#the-node-schema--version-1)).
- **The caller's trailing children are COUNTED, not walked.** They cross into
  the registered component's own React tree, and where that tree places them is
  React's business, so a server tree that showed them would invite an assertion
  on content the server never emits. This is the choice
  [§Client-only subtrees](#client-only-subtrees) makes about its client arm,
  for the same reason.
- **`:props` are the AUTHORED ordinary props**, recorded through the same rule
  every boundary uses: a declared carrier records as its opaque role marker, a
  function as the generic opaque marker, and a non-data value at any depth is
  refused. A `:map-props` adapter's OUTPUT is deliberately absent — the
  adapter exists to build values a portable tree cannot print — and its
  presence is reported instead, which states the loss without incurring it.

Host values, React elements, functions, refs, and third-party instances MUST NOT
be serialized.

`v/render-static` REFUSES a tree carrying a host crossing, on the same law it
refuses a `v/behavior` attachment: the static path emits no hydration payload,
so no client ever replaces the host's SSR projection and the page would ship the
stand-in as its final answer.

#### Compiled mode

The checker and the compiler MUST recognize the declared descriptor and its
finite callback and children positions. A compiled parent MAY cross the host
when lowering supports the same laws.

Until it does, the exact build REFUSES the crossing with a stable id, a source
location, an explanation, and a recovery. It MUST NOT accept the view and fail
only at runtime, and it MUST NOT secretly invoke the interpreted walker: the
first ships a view that compiles clean and hands React a Freehand value as an
element type, and the second ships a compiled view whose manifest and
diagnostics describe markup that is not what runs.

#### The raw React-element escape

Finished React elements remain legal opaque browser-only children where the
owning tree contract permits them. Their callbacks are ordinary captured-frame
closures. Freehand claims no site retirement, no structural inspection, no SSR
portability, and no compiled visibility for their internals. `v/event` and
`v/handler` are carriers rather than the functions they wrap, so a call on one
cannot **succeed**, and they are therefore NOT legal raw `createElement`
callbacks; use an ordinary captured-frame closure there, with none of the
declared-site identity or retirement claims. A carrier MUST implement its host's
call protocol solely in order to throw, on the same terms
[§A declared view cannot be called](#a-declared-view-cannot-be-called) sets for
the descriptor, and **wherever that protocol carries the call** the diagnostic MUST
name the roster form, this position and that recovery — a raw host call failure
names none of them, and this is the one position that produces it.

**That diagnostic reaches as far as the host's call protocol and no further, which
is why the closure is the contract and the message is a courtesy.** On the JVM
`clojure.lang.IFn` is the call protocol entire, so every caller reaches the
diagnostic. In ClojureScript `IFn` is a protocol rather than the language's call
mechanism: a carrier invoked from ClojureScript raises the diagnostic, while a
JavaScript caller performing a native `props.onPing(…)` finds an object with no
call slot and receives the host's own `TypeError`. A carrier MUST NOT be made
natively callable to close that gap — `fn?` is false on a carrier by design and
the nominal readers depend on it staying false. The guarantee at a raw
`createElement` prop is therefore that the call cannot **succeed**, on either host
and by either route, with the didactic message riding wherever the host's protocol
reaches.

This escape is deliberately weaker than `v/defhost`. It is not a second host
API.

**Conformance:**
[FH-REACT-006](conformance/freehand/conformance-index.md#fh-react--react-bridges),
FH-REACT-007, FH-REACT-008.

### Registered behaviors and commands

Some integrations are not honestly described by immutable props. A chart
renderer, a spreadsheet, a code editor, a resize observer, a measurement pass —
each creates opaque host state, mutates it in place, listens for host events, and
has to release what it took. A **behavior** is Freehand's answer: one registered
lifecycle over one node, and the only sanctioned imperative boundary in the
substrate.

```clojure
(v/defbehavior autosize
  {:timing     :layout
   :connect    (fn [{:keys [node config]}] (observe! node config))
   :update     (fn [{:keys [node config]}] (fit! node config))
   :disconnect (fn [{:keys [memory]}] (.disconnect memory))
   :commands   {:refit (fn [{:keys [node config]}] (fit! node config))}})

[v/behavior {:use autosize :target :composer/body :config {:max-rows 8}}
 [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]]
```

Three properties carry the design, and each one closes an escape hatch that would
otherwise make the substrate unanalysable: the timing is a CLOSED set, the
commands are a BOUNDED roster, and a command's target is a SEMANTIC ID the caller
authored. What follows fixes each.

The form is **common to both modes**. A compiled view attaches a behavior and
stays compiled: the compiled build lowers `[v/behavior {…} node]` to the same
boundary the interpreted walk mounts, so the registration roster and the use-site
grammar are one law rather than an interpreted-only one. On the structural host
both modes project the byte-identical inert marker (§The structural marker); in
the browser a compiled attachment reaches the same behavior runtime as its
interpreted twin and runs the same lifecycle — connect, `:update` on moved config,
its private memory, and disconnect — so the two agree by construction rather than
through a second implementation. A view that measures — an overlay, a virtualised
list, anything sizing itself to content through a `:layout` behavior — is
therefore no longer pinned to the interpreted tier for want of the form.

#### The registration and the closed timing set

`v/defbehavior` registers an implementation under a qualified id derived from the
declaring namespace and the declared name, and binds the var to **that id** — a
plain keyword, not the implementation. That split is what keeps a use site data:
the tree records an id, the registry holds the code, and nothing serializable
ever carries a function.

The definition roster is CLOSED to `:timing`, `:connect`, `:update`,
`:disconnect`, `:commands` and `:opaque`. A key outside it is refused at
registration rather than ignored, and a declaration carrying none of them at all
is refused too — a behavior that can never run is a declaration that means
something other than it says.

- **`:timing` is `:passive` or `:layout`, and there is no third value.**
  `:layout` runs before the browser paints, which is the only honest home for
  measure-then-place work; `:passive` (the default) runs after paint. An
  arbitrary "run whenever" hook is refused: the set of moments at which host
  state may move is part of the contract, not an implementation detail, and a
  substrate whose lifecycle moments are open cannot be reasoned about by a test,
  a tool, or a reader.
- **`:connect` runs once per committed connection**, with the live node in hand.
  Its return value becomes the connection's PRIVATE memory.
- **`:update` runs when the committed `:config` MOVES by `rf=`**, receiving
  `:prev-config` alongside the new one. Equal config is not movement, so a
  re-render that changes nothing touches no host state. It is called for its
  effect on the host, and its return is IGNORED.
- **`:disconnect` runs exactly once per committed connection.** The connection is
  released BEFORE it runs, so its context is inert and a teardown that throws
  still leaves nothing behind.
- **`:opaque`** declares that the behavior owns the node's descendants, which
  makes Freehand children on that node an error rather than content the host will
  silently overwrite.

Every entry receives ONE context map: `:node`, `:config`, `:memory`,
`:behavior`, `:target`, `:generation`, and `:dispatch`. There is no frame query
function and no subscription read — state a behavior needs arrives as `:config`,
so a host can never read application state at a moment nobody chose.

**`:connect` ESTABLISHES the memory and nothing else writes it.** `:update`, a
command and `:disconnect` receive it and their returns are discarded. The reason
is the shape of the libraries this boundary exists for: a host mutator normally
answers nothing at all — `map.setOptions(…)`, `workbook.setValue(…)`,
`chart.update(spec)`, `addEventListener` — so a lifecycle entry whose return
replaced the memory would have the FIRST such call erase the very instance
`:disconnect` has to release, silently, in the most ordinary integration there
is. An adapter whose host state genuinely EVOLVES returns a mutable cell from
`:connect` — an atom, a volatile, a JS object — and mutates it in place, which
is the honest shape for a boundary whose whole subject is imperative host state.

#### The use site is data

`[v/behavior {…} node]` is a declared boundary, mounted in a vector head and
never called. Its option roster is CLOSED to `:use`, `:target` and `:config`.

- **`:use`** names a registered behavior. An unregistered id is refused at the
  use site, on both hosts, rather than mounting a node nothing will ever connect
  to.
- **`:config` is data through and through.** A callback, a node, a ref, a cleanup
  function or a preconstructed host instance is refused at any depth. Something
  the host should report leaves as an ordinary event intent the behavior
  dispatches; something the host needs built is `:connect`'s to build. This is
  what makes a use site readable by a structural test and a tool without holding
  the implementation.
- **One node, exactly.** The boundary takes exactly one child and that child is
  ONE element. A declared view, a fragment, a presence boundary or text denotes a
  group or a value rather than a node, and there is nothing for the behavior to
  own. The behavior addresses the node the host hands it and has no way to reach
  any other — there is no selector, no document query, and no ref an application
  can hold.
- **`:target` is caller-authored.** It is the semantic id a command addresses,
  and Freehand derives it from nothing: not from render position, not from a key
  path, not from the DOM. A derived address would move when a view was sorted,
  renamed, extracted or virtualized; a domain address survives all of them. It is
  optional, because a behavior nothing commands needs none, and it must be unique
  among the live connections of ITS FRAME (§The bounded command channel).

#### Commit-only connection and total cleanup

A behavior connects from the host's own commit, never from a render. A candidate
the host abandons — a suspended transition, a superseded render — performs no
host work at all, which is the same law the atomic shell states for the reactive
bundle (§Passive render and atomic selection): building elements is free of
consequence, and only a SELECTED commit reaches the host.

Connect and disconnect may be REPLAYED — StrictMode, hot reload, a host
recovery — so an implementation tolerates a later fresh connection and must not
assume its first connection is its only one. Each connection carries a monotone
GENERATION, and every release names the exact generation it opened: a teardown
arriving after a replacement has connected cannot clear the replacement, and an
outward callback the host kept past a disconnect is inert rather than firing into
a successor.

Cleanup is TOTAL, and it is asserted as an absence: after the last behavior
unmounts, the substrate holds no connection record, no target claim, no node and
no memory. Lifecycle facts are tool evidence, not domain events — there is no
mount event, no unmount event, and no per-occurrence teardown slot on the public
surface.

**The absence is the SUBSTRATE's, and it is synchronous** — the table is empty at
the instant the last connection is released, which is what lets a test assert an
exact integer rather than trust a path. What `:disconnect` itself released is the
AUTHOR's, and a host is entitled to refuse a synchronous release: React will not
unmount a nested root from inside a render, so a behavior that opened one defers
the unmount and its second tree is still open at the moment the substrate's own
absence is already true. That gap belongs to the host, not to this contract, and
it cannot be closed by the substrate — nothing here can know what a `:disconnect`
handed to a library. So a leak check counting the substrate's connections asserts
at the instant of unmount, and one counting the author's own host resources waits
for whatever release the host imposed. A guide that teaches an integration whose
release is deferred teaches the wait alongside it.

#### The bounded command channel

Desired state normally flows through `:config` and `:update`. A one-shot
operation — export, focus a cell, print, scroll to a location — is different:
modelled as a sticky prop it would invent edge detection, acknowledgement and
replay rules. So a behavior may register a finite roster of named operations, and
an application reaches them through ONE reserved effect returned by an ordinary
event handler:

```clojure
{:fx [[:re-frame.freehand.host/command
       {:target :invoice/sheet :op :export :args {:filename "invoice.xlsx"}}]]}
```

- **It resolves against the LIVE index and runs synchronously**, against the
  currently committed connection, on the operation the behavior registered.
- **It resolves in the frame its event ran in.** A connection is committed under
  the frame its view was mounted in, and the frame is half a command's address —
  so a target live only in a sibling frame is as absent as one nothing ever
  mounted, and the refusal says so. This is the frame isolation the rest of the
  substrate already holds: a subscription does not reach into a sibling frame,
  and a command into a sibling frame's node would be the same breach with a
  host object on the end of it. Two frames mounting the same declaration
  therefore claim the same target legitimately — two addresses, not one
  ambiguity — and uniqueness is a claim about ONE frame.
- **It is never queued and never replayed.** A command that finds no live
  connection claiming its target is REFUSED. A future mount is driven by state
  and config, or by a fresh event — a retained imperative request would arrive at
  a node the application has since changed its mind about.
- **Every failure is typed and visible**: an absent target, an ambiguous target
  (two live connections claiming one id), an unregistered operation, and a
  malformed command each refuse with a stable diagnostic id and perform no host
  work.
- **It returns no handle, and its return is IGNORED.** A command is performed for
  its effect on the host, and it does not replace the connection's private memory
  either — only `:connect` writes that (§The registration and the closed timing
  set). A result that matters to domain state comes back as an event the behavior
  dispatches through its generation-fenced context.

A command is not a request/response layer and not a second event system. The
split is taught plainly: state changed → an event updates app state → the view
supplies new config → `:update` reconciles the host; the host emitted something →
the behavior dispatches a configured intent; perform this one-shot operation now
→ one data command; React owns the protocol → use a wrapper, not a behavior.

#### The tool plane

A behavior is the one place in the substrate where opaque host state lives, so
it is the one place a reader most needs to see into — and the one place a
careless inspection surface would hand out exactly what the contract refuses.
Both halves are settled by making the tool plane a pair of read-only
projections over the live table, published through the ONE public door as
`v/active-connections` and `v/command-log` at the `tooling` tier — a tool
projection a tool cannot reach without depending on an unsupported namespace is
not a tool projection:

- **the active connections** — one entry per live connection, carrying its
  generation, its registered behavior id, the frame it was committed under, the
  semantic target it claims and its public config; and
- **the command traffic** — a bounded window of the recent commands, each row
  carrying what the command named and what the channel decided (`:delivered` or
  `:refused`), plus the behavior and generation wherever the channel actually
  resolved a connection. Refusals are recorded as faithfully as deliveries: a
  projection that only saw the successes would be evidence for the one case
  nobody debugs.

Both answer VALUES. Neither answers a node, a private memory, or anything a
caller could reach one through — the omission is by construction, because a
projection is built from a connection's public half, not filtered out of its
whole. A tool that could be handed a host object would be the instance registry
the behavior contract exists to refuse, reached through the inspection door
instead of the front one.

Neither is an event stream. Lifecycle facts are tool evidence, so a tool ASKS
and is not called back: there is no mount event, no unmount event, no command
event, and nothing on this plane dispatches. The traffic window is bounded for
the same reason a trace ring is — an unbounded log is a retention leak dressed
up as evidence — and a delivered row is written before the operation runs, so a
command that crashes its host appears in the traffic rather than vanishing from
it. The BOUND is contract; the number is not, and there is no retention option,
no pagination and no filter, because a tool that wants less filters the value it
was handed.

Both are BROWSER-ONLY, and absent on the JVM exactly as the mount verbs and the
outward React bridge are. A structural render connects nothing, so a JVM arm
could only answer an eternal empty projection — present-and-lying where absence
is honest, and Freehand carries no tier of host operations that exist purely to
answer emptily on the server.

The plane is TWO reads and no more. A scalar count of the live connections, the
set of targets they claim and the traffic window's own bound are each derivable
from a projection in one form, so none of them is published: a supported surface
earns its place by being something a reader cannot compute for itself, and three
more names would be three more things to keep true.

#### The structural marker

The JVM has no live node, so a behavior there is an INERT MARKER and says so — and
it says so IDENTICALLY in both modes. The boundary renders as an ordinary
view-boundary node carrying `:view-id` `:re-frame.freehand/behavior` and recording
its `:use`, `:target` and `:config` as props, with the decorated element as its
child; an interpreted declaration and its compiled twin project that same node
byte for byte — the same view-id, the same public props, the same decorated
child — connecting nothing either way. Nothing connects, no memory
exists, and a command is refused with the same channel diagnostic rather than
pretending to reach a host. A structural test therefore asserts the command's
DATA — the effect an event handler returned — and the mounted tier proves the
host action.

#### Sharing one node

A behavior and a top-layer desired state are orthogonal in intent — one runs
bounded imperative work over a node, the other expresses a desired-state DOM
affordance — and nothing about either says they cannot describe the same
element. A measured popover is the ordinary case: the platform promotes it, and
a behavior places or sizes it.

Both mechanisms need the live node, and React holds ONE ref per element. An
implementation that wrote a bare ref would therefore not fail on such an element
— it would CLOBBER, silently, and the symptom would be a popover that never
opens or a behavior that never connects. A conforming implementation COMPOSES
instead: when more than one mechanism needs one node, every participant receives
it and every participant is released. Refusing the combination would forbid a
legitimate authoring shape and would have to be re-litigated the first time a
third mechanism wanted a node — refusal scales with the number of pairs,
composition does not.

**The order is defined, and it is inside-out.** Participants receive the node in
the order they were added, and they are added from the element outward: the
element's own intrinsics first, then each decorating boundary in the order it
encloses the node. Release runs in the exact reverse, as a stack unwinds. An
intrinsic is a property OF the element and a behavior is a decoration applied
OVER it, so this is the declaration's own containment order rather than a
convention — and leaving it to map iteration, or to whichever mechanism happened
to install first, would promote an implementation detail to a semantic one.

Composition orders registration on ONE element and nothing else. It is not the
mechanism that orders host operations ACROSS elements: the top layer's commit
batch owns that, in document order, precisely because ref order is bottom-up
(§Commit, order, and the declared tracking frequency). A chain that tried to be
both would re-introduce the nesting failure the batch exists to remove.

**Composing must not change any participant's attach frequency.** A mechanism
whose ref is deliberately fresh at each commit stays fresh, and one whose ref is
deliberately stable stays stable — a composition memoised into stability would
silence the first, and one allocated for a lone participant would make the
second tear its node down every commit.

**Release is total, and it is asserted as an absence.** Every participant is
released when the node goes, under whichever release protocol it uses, and after
teardown the substrate retains nothing for that element — measured against a
control mount carrying neither mechanism, so the host's own bookkeeping cannot
be mistaken for the substrate's.

None of this is new surface. There is no ref an author may declare, no verb for
composing one, and no way for an application to hold a node: composition is how
the substrate's own mechanisms coexist, and an author reaches it by writing the
two declarations that already exist.

### The DOM top layer

An overlay used to mean a pile of machinery: a portal to escape a clipping
ancestor, a z-index ladder, a document listener for outside dismissal, a focus
trap and its focus-return code, scroll and resize listeners, a measurement loop,
and a teardown path that is easy to get subtly wrong. Browsers now have a **top
layer**, and popovers and modal dialogs enter it natively — above every stacking
context, outside every clipping ancestor, with the platform's own dismissal,
focus and inertness. Almost all of that machinery can simply be deleted.

What cannot be deleted is the fact that the platform's door is imperative. There
is no attribute meaning "be open": a modal dialog opens through `showModal()`,
and a controlled popover through `showPopover()` / `hidePopover()`. So Freehand
recognises a **closed pair of qualified desired-state properties** whose value is
the state the browser should be in, and performs the matching idempotent call at
the selected commit. The contract is ruled by
[D015](../docs/design/freehand/decisions/D015-top-layer-overlays-and-portals.md).

```clojure
(v/defview account-menu [{:keys [open? on-open-change]}]
  [:div
   [:button {:popover-target "account-menu" :aria-expanded open?} "Account"]
   [:div {:id                 "account-menu"
          :popover            :auto
          ::web/popover-open? open?
          :on-toggle          (v/event [e]
                                (conj on-open-change (= "open" (.-newState e))))}
    "…"]])
```

Open/close is application or library state, exactly as it was. The substrate
contributes one mechanical host call and nothing else: no stacking policy, no
placement, no ARIA, no keyboard behaviour, no transition. A menu still needs
roles, an active option and arrow keys; a component library still owns all of it.

#### The closed pair, and where each half is legal

Two properties, under a DOM-platform namespace that says at the use site that
these are browser facts and not neutral substrate grammar:

- **`::web/popover-open?`** — legal only on an element carrying a valid
  `:popover` mode (`:auto`, `:manual`, `:hint`, or the bare attribute). It means
  `showPopover()` / `hidePopover()`, which the browser defines nowhere else.
- **`::web/modal-open?`** — legal only on `<dialog>`, and only about the MODAL
  axis. It means `showModal()` / `close()`. A non-modal dialog uses the
  platform's ordinary `:open` attribute and needs no intrinsic at all.

The value **is** the state: `true` and `false` are both declarations, and `nil`
expresses no desired state — the element is then an ordinary popover or dialog,
exactly as a nil attribute value drops its entry. There is no third state,
because there is no third browser operation to name. A non-boolean value, a
property on an element whose operation does not exist, and both properties on one
element are each `:rf.error/ui-tree-malformed` at render, on both hosts and in
both modes: recognition happens at the one shared canonicaliser, so an emitter
that accepted what the other refused is not a thing that can happen. A generic
`:open?` is deliberately absent — popover, non-modal dialog and modal dialog have
materially different browser operations, and one name over three of them would
hide the difference the author most needs to see.

#### Native behaviour is the whole point

What the pair buys is the platform's own behaviour, unmediated. A modal dialog
takes initial focus, makes the rest of the page inert, and returns focus on
close. Two `:auto` popovers are one-at-a-time. A popover nested inside another
**stacks**, and closing the ancestor closes the descendant with it. None of that
is Freehand's code, and none of it is re-implemented — which is the point, because
the hand-rolled versions of exactly these behaviours are where overlay bugs live.

Nesting is the case that needs the substrate to be careful, and it is the one
place the mechanism is not simply "call the method". The browser decides what an
opening popover closes from the DOM **as it stands at that instant**, and a React
commit attaches refs bottom-up — so a commit that opens a nested pair would show
the inner popover first and then close it by showing the outer one, collapsing
the pair to its outer half. A conforming implementation therefore performs one
commit's top-layer operations in **document order**, ancestor before descendant.

#### Commit, order, and the declared tracking frequency

Host work happens at a **selected commit** and only there. A render the host
abandons performs no host operation at all, so a desired state from an abandoned
candidate never reaches the browser — the same law the atomic shell states for
the reactive bundle (§Passive render and atomic selection), realised by the same
means rather than by a second mechanism. A superseded generation never acts on a
replacement node, and a node that has left the document is skipped rather than
asked to open.

Within a commit the operations are ordered and then performed at the microtask
checkpoint the commit opens — before the browser paints, so the ordering costs no
frame and no frame is ever painted in the wrong state.

An operation waiting in that checkpoint belongs to the **generation that queued
it**, and a generation that retires before the checkpoint runs withdraws it.
Ownership is the fence here, not connectivity: a commit that removes the
intrinsic without removing the element retires the generation while the node
stays in the document, and a checkpoint that read only the node's connectedness
would open a node nothing controls any more on behalf of a generation that had
already let go. Withdrawal is exact for the same reason — a successor's claim on
the same node has already replaced the entry, so a predecessor's retirement
cannot take it down with it, in whichever order the host runs attach and detach.

The **tracking frequency is the commit, and nothing between commits.** The
desired state is diffed against the node's LIVE state, so a repeated equal value
is a no-op however many renders pass, and the substrate arms nothing that
outlives the call: no document or window listener, no resize, intersection or
mutation observer, no animation-frame loop, no timer, no registry of open
overlays. Cleanup is therefore total by construction rather than by discipline,
and it is provable as an exact zero rather than asserted as an intention. That
absence is most of the value: a leaked observer on an overlay compounds once per
open/close cycle, which is precisely the failure the top layer exists to delete.

Positioning is a separate concern with a separate answer — CSS anchor
positioning where it suits, or a registered behavior with an explicit update
contract (§Registered behaviors and commands). The pair does not consume a node's
behavior slot, so measured placement and top-layer control coexist on one
element — the two refs compose, in the order §Sharing one node defines; and
nothing here implies per-frame tracking.

#### Browser dismissal, and what the substrate never does

Escape, a light dismiss, and a dialog's own close button all close the node
**without asking the application**. The substrate does not write application
state on their behalf and does not quietly adopt the new state as the desired
one: it has no second state machine, and a framework that guessed here would be
overriding the author's own store. The author reconciles through ordinary event
intent on the native events — `:on-toggle` and `:on-before-toggle` for popovers,
`:on-close` and `:on-cancel` for dialogs, which are ordinary event positions
needing no new grammar.

The consequence is worth stating plainly, because it is the one thing that
surprises: a controlled node dismissed by the browser and left unreconciled is
**re-opened by the next commit**, since the desired state still says open. A
development diagnostic identifies a controlled top-layer node with no handler for
its own dismissal. Handlers must consult committed state where acceptance
matters, rather than closing over a render-time guard — there is a brief, real
window between the browser dismissing and the author's event committing.

A host call the browser refuses — opening a disconnected node, promoting an
already-open non-modal dialog with `showModal()` — becomes development evidence
naming the recovery, not a swallowed exception and not a thrown one: the
operation is mechanical, the mistake is an authoring mistake the next render can
fix, and taking the page down for it would be the wrong trade.

Both advisories are **evidence, and evidence is published only from a selected
commit** — the same passive-render law everything else here obeys. Building an
element's props is not a commit: the host may restart or abandon that render, and
an advisory published from one names a candidate that never existed and accuses
an author of a mistake the page never made. The publication therefore rides the
committed occurrence — the callback ref for the unreconciled declaration, the
host call itself for a refusal — and constructing props publishes nothing at all.

They are **typed**, too. Each carries a stable, catalogued diagnostic id and a
`:recovery` on the development trace channel: the mechanism and the handlers that
reconcile it for an unreconciled declaration, and the host call, the element and
the browser's own reason for a refused one. A console sentence names the same
fact for the person reading it, but prose is not a contract — a tool, including
an AI reading a session, has to be able to see the fact without parsing English.
Both are development-only and are eliminated from a production build.

Retention while closed is a component's choice, not the framework's: conditional
children can remove the node entirely, and leaving it mounted but closed
preserves expensive host state. Enter/exit retention is §Presence — the top layer
starts no timer and delays no removal.

#### The structural projection

The properties are Freehand vocabulary, not attributes. Both emitters read an
attribute by its NAME and drop the namespace on the way to the DOM, so a property
that survived into the attribute space would reach the browser as a garbage prop
and the structural tree would carry a key no consumer could interpret. Each
therefore leaves `:attrs` and projects as the reserved diagnostic key
`:rf.ui/top-layer` on the semantic element ([004B §Reserved `:rf.ui/*`
keys](004B-UI-Tree-and-Conversion.md#reserved-rfui-keys--the-three-roles-required-gate-semantic-diagnostic)).

The JVM structural host emits the semantic element and the desired-state **fact**
— and makes no claim that anything was promoted to a top layer, because a server
has none. Hydration performs the first host operation after the client's first
commit. The projection is identical from an interpreted declaration and its
`{:compiled true}` twin: both modes reach one canonicaliser, so the intrinsics are
compiler-recognised common semantics rather than a quirk of one front end.

#### No neutral portal

A Freehand implementation MUST NOT provide a neutral portal — no `[v/portal {:to
target} child]`, no named-target registry, no re-parenting primitive in the common
grammar. The exclusion is the design, not an omission.

A portal's target is a live host node or container: not portable data, with no
honest structural or JVM representation, and a named-target registry would drag
in identity, lifetime, hydration and missing-target policy to replace it. Worse,
a portal solves almost none of what an overlay actually needs — it supplies no
dismissal, no focus management, no accessibility, no placement and no teardown
policy — while being general enough to re-parent anything anywhere. That
combination is the one primitive that can do anything, and it is exactly what
makes a substrate unanalysable. The top layer solves the problem a portal was
being reached for, natively and narrowly.

Real React portals stay behind explicit UIx/Helix wrappers, where they are
visibly React's protocol rather than the substrate's vocabulary.

**Conformance:** [FH-TOPLAYER-001](conformance/freehand/conformance-index.md#fh-toplayer--top-layer),
FH-TOPLAYER-002, FH-TOPLAYER-003, FH-TOPLAYER-004, FH-TOPLAYER-005.

### The outward React bridge

Every other verb on the door points inward: a Freehand tree mounts Freehand
boundaries. Some React libraries reverse the arrow and ask for a **component
value** rather than an element — a grid's `cellRenderer`, a drag overlay, a
virtual list's row component, a plugin API that takes a component and decides
for itself when to render it. `v/->react` is the one crossing that answers
them, and it is the outward half of the same host boundary `v/defhost` and the
registered behavior are the inward halves of, not another host shape.

```clojure
(def person-cell-react (v/->react person-cell))

;; then, in React-world
#js {:cellRenderer person-cell-react}
```

What comes back mounts the descriptor exactly as an ordinary Freehand parent
would. Events, subscriptions, error identity, evidence and selected-commit
fencing inside the exported subtree are the ones the view already had; nothing
about being reached from React changes what the view is.

#### Only a declared view crosses

The argument is a descriptor. A plain function, a hiccup vector, a view's id
keyword or a rendered form is refused, and the refusal names the two recoveries
that exist: export a declared view, or write an explicit React wrapper.

The restriction is what makes the exported component debuggable. A declared view
carries a qualified id, source coordinates and a lowering, so a failure inside
the foreign library's subtree names the view rather than stopping at an
anonymous wrapper, and a hot reload has something stable to reload *into*. A
bridge that accepted any function would hand React an opaque closure and throw
all of that away at the boundary where it is most needed.

The option roster is **closed** and holds exactly one key, `:map-props`, whose
value is a function. An unknown option is refused rather than ignored. The
closure is deliberate: prop schemas, child conversion, ref forwarding, callback
maps and lifecycle options would together amount to a second React component
model expressed as configuration, and a short wrapper is clearer and more
powerful than any of it.

**Conformance:** [FH-REACT-001](conformance/freehand/conformance-index.md#fh-react--react-bridges).

#### One shallow prop rule, or one explicit adapter

Without `:map-props`, every own enumerable property of the foreign props object
becomes an entry in the view's props map **by exact name**, with its value
carried across untouched: `"person-id"` is `:person-id` and `"acme/id"` is
`:acme/id`. There is no camelisation and no deep walk.

Deep conversion is refused because there is no honest general version of it. A
library like a data grid hands its renderer a large mutable parameter object
holding DOM nodes, service handles and callbacks; walking it would be expensive
and would produce a value that only looks like data. Such a library supplies the
one explicit adapter instead:

```clojure
(defn cell-props [params]
  {:person-id (.. params -data -id)
   :column-id (.. params -column getColId)})

(def person-cell-react (v/->react person-cell {:map-props cell-props}))
```

The adapter receives the raw foreign object and returns **the one props map**.
It is ordinary top-level code at the host edge, and that is the point: the
projection from foreign object to values is named, localised and testable on its
own, rather than hidden inside a conversion rule the substrate would have to
pretend was general. If a library's protocol needs hooks, refs, or an imperative
handle returned, the adapter is insufficient **by design** and a wrapper is the
unit.

Three property names belong to the bridge, and the view sees none of them:

- **`frame`** selects the frame (below). It is consumed, never forwarded.
- **`children`** is React's content slot, and it becomes the boundary's
  **trailing children** — the same slot, on the other side of the boundary. So
  React content nests inside an exported view, each element riding the ordinary
  child walk, which already carries a finished React element through untouched.
  The view's declared `:children-policy` still decides: a view that accepts no
  children refuses them here with its own diagnostic, not a bridge-specific one.
- **`ref`** is **refused**. Freehand has no ref protocol —
  [§Absent at the declaration and call surface](#absent-at-the-declaration-and-call-surface)
  retires the neutral ref and effect tier outright — and a `ref` that resolved
  silently to nothing would leave a foreign owner holding a handle that never
  fills. The refusal names the wrapper, or a registered behavior over one node,
  as the recovery.

Because `frame` is the bridge's own property name, `:frame` may not appear in
the props map the view is mounted with. In the shallow arm it cannot: the
property was consumed. In the adapter arm the adapter is authoring the map
itself, so a `:frame` key there is a genuine collision — the same name read two
ways, only one of which can happen — and it is refused naming the view.

**Conformance:** FH-REACT-002.

#### The exported component is stable

React reconciles on component **type**. Exporting the same view twice therefore
has to answer the identical object, or the foreign library unmounts and rebuilds
its subtree every time the exporting expression runs — which is precisely the
mistake a hand-written wrapper makes and this verb exists to stop.

Identity is keyed on the **view id** and the adapter's identity, not on the
descriptor object. A hot reload mints a fresh descriptor for the same view, so a
descriptor-keyed cache would make every reload a remount; keying on the id lets
a reload republish the redefined body through the component React has already
mounted. Two different adapters over one view are two different exports and two
different components, because they do different things.

Memoisation is an identity guarantee, not a promise about how often the foreign
library renders the component. That remains the library's business.

**Conformance:** FH-REACT-003.

#### A frame is selected, never created

An own `frame` prop — a frame-id keyword, or a live frame value — **scopes** an
already-live frame for the exported subtree. It creates nothing, refreshes
nothing and destroys nothing: frame creation belongs to the host application's
boot, and a bridge that quietly minted one would split an application into two
runtimes that agree about nothing.

**Own-property presence decides which arm runs, not truthiness.** An explicit
`frame={null}` is a stated target that names no frame, and it fails loud rather
than falling through to the ambient one; a malformed target and a target naming
no live frame each fail with their own diagnostic. Every one of those failures
is attributed to the bridge rather than to the shared frame provider, because
the bridge is the surface the caller used.

With **no** `frame` property the exported view resolves its frame by the
ordinary ambient chain, exactly as a view mounted anywhere else does — a foreign
frame boundary above it supplies one, and a subtree under no frame boundary at
all fails with the ordinary `:rf.error/no-frame-context`. There is no default
and no silent fallback in either direction.

**Conformance:** FH-REACT-004.

#### Host and server policy

The bridge is a **browser** verb, published under the same reader conditional as
`v/mount`, `v/hydrate-root` and `v/unmount!` and absent on the JVM. A React
component value has no meaning in a structural render, and Freehand does not
carry a tier of host operations that exist only to raise on the server: the
verb is honestly absent on the host that cannot support it rather than present
and throwing.

That absence **is** the server policy, and it is stated rather than inferred.
Freehand's server render is `v/render-static`, a structural fold with no React
in it ([011 §The server render on the Freehand paved path](011-SSR.md#the-server-render-on-the-freehand-paved-path)),
so nothing a server render can reach is an exported component. The bridge
therefore reads no server-renderer context internal to make one work, and a
foreign React tree that renders on a server is outside this contract: the
integration is client-side, and a use site that must appear in server output
supplies its own server-truthful fallback rather than expecting the bridge to
infer one from the foreign library.

**Conformance:** FH-REACT-005.

### Client-only subtrees

Some subtrees only a browser can render — a map canvas, an editor bound to a
host measurement, a widget that reads `window` before it can decide its own
shape. `v/client-only` is where an author says so, and says what stands in the
region's place everywhere else:

```clojure
(v/client-only {:fallback [:div.map-shell "Map loads in the browser"]}
  [live-map {:centre centre}])
```

- **The fallback is MANDATORY.** There is no arity that omits it and no
  default to supply. A browser-only subtree without a fallback is a hole in
  the server's output — a region that renders as nothing on the server and
  appears from nowhere on the client — and that hole is exactly what the
  boundary exists to prevent. An explicit `nil` fallback is a stated answer
  (the region renders nothing, deliberately) rather than an omission, because
  presence is what is checked.
- **The fallback is capability-free markup.** It is what the server, the
  structural render and a hydrating root's first render all put in this
  position, so it must stand without a runtime: no reactive read, no host
  object, no committed handler. The compiled tier checks that claim
  statically; the interpreted tier cannot, and does not pretend to — the
  fallback is the author's undertaking there, and the render that breaks it
  breaks it loudly at the site.
- **Phase decides which arm renders.** A root renders in one of two phases,
  `:server` or `:client` ([011 §Phase flip](011-SSR.md#phase-flip)). The
  structural render on either host is `:server` phase and produces the
  fallback; an ordinary mount is born `:client` and produces the client
  subtree on its first and only render; a hydrating root boots `:server` and
  flips once, swapping **every** client-only site in the root in the single
  update that one root-scoped write produces.
- **One spelling, two modes — but only one lowers it.** The site is written
  identically in either mode. In the interpreted mode it is an ordinary
  function call whose reserved-head result each walk intercepts, exactly as
  `v/presence` is. The compiled grammar **refuses** it, naming the browser-only
  subtree and the ladder out — extract the site into a declared child, or keep
  the view interpreted. A compiled body cannot see through the boundary, so
  admitting it would mean a manifest claiming a subtree's capabilities without
  ever having analysed them.
- **The structural tree records that a fallback is a fallback.** The
  `:server`-phase render wraps the fallback in the `:rf.ui/boundary
  :client-only` marker ([004B §Reserved
  members](004B-UI-Tree-and-Conversion.md)), so a structural test can assert
  *this region is showing its capability-free stand-in* rather than inferring
  it from markup that looks like any other markup. The client subtree is a
  value the structural walk never enters.
- **Both arms are ordinary expressions.** An interpreted body evaluates both
  and renders one — building markup is building a vector, and it is the walk
  that touches a host. A host call written into the argument itself, rather
  than inside the view the argument names, has been moved outside the boundary
  that exists to contain it.

**Conformance:** FH-ROOT-008.

### Structural rendering, roots, and SSR

**Not authored here.** This heading covers what the structural host
retains at a view boundary; root identity and mount are
[004C](004C-Roots-and-Mount.md) and server rendering is [011](011-SSR.md).

## Normative absences

Each absence below names the donor form its surface retires and the Freehand
replacement for it, so the absence is a stated contract rather than an omission.

### Absent at the declaration and call surface

A Freehand implementation MUST NOT provide:

- **callable view values.** The donor's JVM emitter produced view values that
  were invocable as functions and *worked*; they are replaced by the one shared
  descriptor, which cannot be successfully called on either host, in either mode.
  No callable compatibility layer survives. Preserving one would reintroduce the
  cross-host call mismatch
  [§A declared view cannot be called](#a-declared-view-cannot-be-called) closes.
- **bare-function boundaries.** A plain `defn` vector-called as a tracked
  boundary. Every internal boundary is declared; the replacement is `v/defview`.
- **dual-entry descriptors.** A declared view that is both a vector head and
  directly callable. Brackets mount; a declared view is never invoked.
- **a region DSL.** Render granularity is expressed by the forms that already
  exist — a plain helper is the coarse region, a declared keyed child the fine
  one.
- **positional view arguments.** A view takes one props map; there is no
  positional arity to declare or to call.
- **neutral hooks, refs, effects and lifecycle callbacks.** The donor's `local`,
  `ref`, `effect` and its compiled React hook tier do not cross. Bounded
  imperative work is a registered behavior over one node
  ([§Registered behaviors and commands](#registered-behaviors-and-commands)); a
  React-owned protocol lives behind an explicit wrapper. There is no neutral form
  in between, because a neutral form is one whose timing, ownership and cleanup
  the substrate cannot state.
- **host handles and instance registries.** No public value carries a live host
  object, and no lookup returns one. A behavior's memory is private to its
  connection, and an application reaches a live connection only through the
  bounded command channel, addressed by a semantic id it authored.

### Absent at the event surface

A Freehand implementation MUST NOT provide:

- **a payload arity on general dispatch.** `rf/dispatch` takes an event vector
  and an optional opts map. Projection is a Freehand event-site concern, and the
  replacement is `v/materialize-event` — the same function production and tests
  both run.
- **donor placeholder spellings.** The donor's compiled placeholder provenance —
  private object sentinels and their `:rf.ui/*` keyword spellings, recognised in
  literal vectors only — does not cross. The replacement is the
  `:re-frame.freehand/*` trio, which materializes identically in a literal, a
  forwarded and a computed vector, so the donor's "placeholder in a dynamic
  vector" advisory has nothing left to warn about.
- **a projection expression or selector language.** The `[::v/read <path>]` door
  reads one shallow scalar off a keyword path (`[::v/read [:target :scrollTop]]`)
  and nothing more: no indexing (`[:target :files 0 :name]`), no transforms, and
  no read that resolves to anything but a string, number, boolean or keyword. The
  replacement for the uncommon richer residue is `v/event`, which converts it
  honestly instead of growing a miniature expression language with its own
  validation, missing-value semantics and host coupling.
- **multi-intent handler vectors.** A site yields one event or none; the
  replacement for a multi-step reaction is one named semantic event whose
  re-frame handler returns the effects.
- **a generic dispatcher form.** No `v/dispatcher` appending raw callback
  arguments to a prefix. The replacement is `v/event`.
- **dependency arrays.** No callback declares what it depends on; committed
  publication supplies freshness, so there is nothing to declare and nothing to
  get wrong.
- **mount, unmount and lifecycle events.** They are tool facts. The replacement
  for per-mount work is the frame's `:initial-events` or an ordinary re-frame
  event.

## Resolved decisions

- **The product is Freehand.** One re-frame-native substrate published through
  `re-frame.freehand` (alias `v`), with no second public door. The interpreted mode
  is the paved path and the compiled mode is required.
- **Compilation is manual and evidence-guided.** There is no automatic promotion, no
  second compiler, and no hidden interpreted fallback inside compiled markup.
- **re-frame is the only reactive application-state system.** There is no
  view-local application-state tier, no public renderer-derived state handle, and no
  neutral hook/ref/effect/portal surface; React-owned protocols live behind explicit
  host boundaries.
- **Contract ownership is a migration, not a new family.** Freehand extends the
  existing canonical Specs; there is no `spec/0XX-Freehand` family, and the compiled
  grammar's home is fixed at [004D](004D-Freehand-Compiled-Grammar.md).
- **The donor was to be deleted at a gate, not a date.** `re-frame.ui` is
  donor-only and unpublished. With EP-0036 withdrawn that gate never opened: the
  standalone artifact stays in the tree, and no removal is scheduled.

The full rulings D001–D022 and their rationale are the Freehand decision register
(`docs/design/freehand/decisions/`); the programme topology, migration map, and
release gates are
[EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md).

## Cross-references

- [004D-Freehand-Compiled-Grammar](004D-Freehand-Compiled-Grammar.md) — the compiled
  tier: the finite grammar, the analyzer, both emitters, manifests and elision.
- [004B-UI-Tree-and-Conversion](004B-UI-Tree-and-Conversion.md) — the semantic tree
  ABI and the DOM conversion table.
- [004C-Roots-and-Mount](004C-Roots-and-Mount.md) — root identity, the Root
  Descriptor, mount, hydration, teardown.
- [002-Frames](002-Frames.md) — frame creation, identity, and preflight.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — the observation port the shared
  reactor consumes.
- [008-Testing](008-Testing.md) — structural and mounted testing, the host/mode
  matrix, cross-mode conformance.
- [009-Instrumentation](009-Instrumentation.md) — diagnostic ids, evidence retention,
  lifecycle facts, error egress.
- [011-SSR](011-SSR.md) — server rendering, hydration, and server-error projection.
- [012-Routing](012-Routing.md) — href and click semantics behind the route-link view.
- [Ownership](Ownership.md) — the corpus-wide surface-to-owner map.
- [EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md) — the
  programme, **withdrawn 2026-07-30**: topology, governing laws, migration, slices,
  and gates.
