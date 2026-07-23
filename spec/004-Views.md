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
> **This document is a skeleton.**
> [EP-0036](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md) cut the
> ownership so that no two Specs claim one surface, and it delivers the substrate in
> vertical slices rather than as one prose waterfall. Each semantic heading below
> carries a **Lands in** marker naming the slice that authors it. A marked heading
> with no body is a **declared vacancy**, not an undocumented contract: until that
> slice lands, the ratified target is described by the Freehand design record
> (`docs/design/freehand/`) and the donor-era shipped behaviour continues to be
> described by the Spec that shipped it. No slice may author its surface anywhere
> but under its own heading here, and none may leave two owners standing.

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
They bind every section of this Spec, every clause of
[004D](004D-Freehand-Compiled-Grammar.md), and every Freehand slice. A slice that
cannot satisfy one of them amends the law explicitly; it does not ship a second
answer beside it.

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
(`docs/design/freehand/decisions/`, D001–D021). They are all ruled; a slice cites
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
  named. That holds for a **reserved option whose owning slice has not landed**:
  the props-schema options raise until the schema surface exists, the way
  `{:compiled true}` raised until the compiled tier landed. A silently-ignored
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

**The props-schema seam.** A declaration MAY carry a props schema; it is optional
in `:re-frame.freehand/v1`, including for compiled application views, and
required by build and catalogue policy for shipped reusable library views and
wherever a report claims generated coverage. `:key` is outside the schema, and
children policy is descriptor metadata rather than a schema slot. The schema's
grammar, validation timing, elision and generation dependencies land with the
compiled tier.

**Conformance:** [FH-PROPS-001](conformance/freehand/conformance-index.md#fh-props--props),
FH-PROPS-002, FH-PROPS-003.

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
arm keys off a reserved marker, so the classification stays total rather than
duck-typed.

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

**Lands in:** F1 — paved-path spine. Carries mounted occurrence identity, the
internal revision/generation vocabulary, and the compatible-shell versus clean-remount
hot-reload contract.

### Selecting the compiled mode

**Lands in:** F3 — compiled absorption. Carries the `{:compiled true}` selection, the
checker-first workflow that precedes it, and the interpreted↔compiled crossing rules;
the grammar itself is [004D](004D-Freehand-Compiled-Grammar.md).

## Common semantics

### Passive render and atomic selection

**Lands in:** F2 — reactive intent. Carries the candidate bundle, the selection and
publication rule, acquire-before-release, and the fate of an abandoned render.

### The ambient frame

**Lands in:** F2 — reactive intent. Carries frame observation and rebinding at a view
boundary, and the loud failure when no live frame is in scope.

### Reactive reads — `v/sub`

**Lands in:** F2 — reactive intent. Carries the render-only law, same-render-thread
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

[:canvas {:on-pointer-down (v/event [e]
                             [:canvas/pressed (point-in-canvas e)])}]
```

Because the intent is a vector rather than a closure, a reusable control can
**forward** it. `(conj on-change ::v/value)` turns a caller's
`[:account/email-edited]` into `[:account/email-edited ::v/value]` without the
control knowing anything about the caller's domain, and the whole site stays
inspectable — and structurally testable — before anything mounts.

**The closed scalar projections.** The reserved markers are `::v/value`,
`::v/checked` and `::v/key`, and that roster is closed. Adding a fourth is a
grammar decision, not an implementation detail; anything richer than a shallow
scalar read is `v/event`'s job, and a nested path language is a
[normative absence](#absent-at-the-event-surface).

**One materializer, at firing time.** At firing time the native or qualified host
adapter obtains the live scalar payload, and one pure materializer —
`v/materialize-event` — replaces the markers before the resulting plain vector
reaches ordinary re-frame dispatch. Its rules are deliberately small:

1. **Position zero may not be a marker.** An event id is a name, not a projection.
2. **Only top-level argument positions are replaced.** Projection is shallow and
   by value: a marker nested inside a map, a vector or any other value is
   ordinary application data and survives untouched.
3. **Every occurrence is replaced**, not merely the first.
4. **A requested but unavailable payload is a typed error, and nothing is
   dispatched.** A malformed event reaching a handler is worse than no event; a
   silently `nil` argument is worse still.
5. **The result is a plain vector.** An event carrying no marker is returned
   unchanged, so an unprojected site allocates nothing.

The marker and the payload key are **the same keyword** — a site asks for
`::v/value` and the adapter supplies `::v/value` — so "did this callback offer
what this event asked for?" is one lookup and there is no second vocabulary to
keep in step. Projection reads the payload the *callback* supplies, never a
render-captured value, so the same intent vector materializes differently on
successive keystrokes without being rebuilt.

Every path runs through that one function: a literal vector, a forwarded prefix,
an options map's `:event`, a `v/event` body's result, interpreted, compiled,
production and test. Both hosts share it, so a JVM structural test supplies a
literal payload and asserts the exact dispatched vector without a browser — the
production mechanism, not a test-only splice convention.

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
does nothing is an event site that looks correct and is not. The exact-key
condition map for `:on-key-down` / `:on-key-up` is a **separate** closed form
with its own section; it is not a variant of this map, and mixing the two is an
error. Native attachment for `:passive` or `:once` is internal event-adapter
lifecycle, not a public effect system. The structural host runs no browser
mechanics because it fires no native event; the options still normalize and
still ride the site plan, so both hosts read one shape.

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

**Lands in:** F2 — reactive intent. Carries the controlled-node predicate, the door
event props, the synchronous same-tick scheduling guarantee, and the forwarding rules
that preserve it.

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
  writable — a props-only view asks for nothing and pays nothing.
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
- **The structural projection.** The JVM structural host has no lifecycle, so it
  renders every retained child `:present` and exposes the presence fact
  structurally as a fragment carrying `:rf.ui/presence {:phase :present
  :timeout-ms n}` — a droppable reserved marker, so a consumer that does not
  read it sees a plain fragment of `:present` children.
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

**Lands in:** F4 — data and host lifecycle. Carries the occurrence-keyed evidence
model and the scope/basis/completeness/loss statement; the ids and retention axis are
[009](009-Instrumentation.md).

## Composition

### Children, compound children, and parameterized content

**Lands in:** F5 — composition and integration. Carries the default child region,
compound child views, and the pure parameterized-render pair.

### Props forwarding

**Lands in:** F5 — composition and integration. Carries the safe-forwarding form, its
denied-prop set and class composition, and the visible open-props escape at a foreign
boundary.

### Theming and semantic parts

**Lands in:** F5 — composition and integration. Carries the bounded per-instance part
override and the styling/structural plane split.

### Framework-supplied views

**Lands in:** F5 — composition and integration. Carries the rule that framework views
are ordinary descriptors, and the route-link view over the [012](012-Routing.md) seam.

## The host boundary

### Qualified host leaves

**Lands in:** F4 — data and host lifecycle. Carries the value-in/callback-out foreign
component boundary and its structural/SSR policy.

### Registered behaviors and commands

**Lands in:** F4 — data and host lifecycle. Carries the connect/update/disconnect
protocol, the closed timing set, node opacity, the bounded command map, and the
structural marker.

### The DOM top layer

**Lands in:** F4 — data and host lifecycle. Carries the closed qualified
desired-state properties, commit-time host reconciliation, and the structural
projection.

### The outward React bridge

**Lands in:** F5 — composition and integration. Carries descriptor-only acceptance,
prop mapping, caching and frame selection, and the structural-host failure mode.

### Structural rendering, roots, and SSR

**Lands in:** F5 — composition and integration. Carries what the structural host
retains at a view boundary; root identity and mount are
[004C](004C-Roots-and-Mount.md) and server rendering is [011](011-SSR.md).

## Normative absences

**Lands in:** F1–F6, incrementally; consolidated at F6 — proof and retirement. Each
slice records the donor forms its surface retires and the Freehand replacement for
each, so the absence is a stated contract rather than an omission.

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
- **a projection path or expression language.** No `[:target :files 0 :name]`,
  no transforms. The replacement is `v/event`, which converts the uncommon
  residue honestly instead of growing a miniature expression language with its
  own validation, missing-value semantics and host coupling.
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
- **The donor is deleted at a gate, not a date.** `re-frame.ui` is donor-only; its
  standalone artifact is removed when internal conformance, the pilots, and consumer
  migration are complete.

The full rulings D001–D021 and their rationale are the Freehand decision register
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
  ratified programme: topology, governing laws, migration, slices, and gates.
