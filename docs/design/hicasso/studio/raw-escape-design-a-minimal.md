# The `[:>]` raw escape — design A: minimal surface

**Status.** Design A of three independent designs for `rf2-2rtt6.103`, written to be
read against two siblings and then torn at by three adversarial reviews. Nothing
here is ruled and nothing here is built. The bead owes a *synthesized* spec; this
page is one input to it.

**This is a design page, not a measured one.** The four obligations in
[the studio README](README.md#what-a-page-in-here-owes-its-reader) — a producing SHA
per row, the runtime beside every figure, ranges across rounds, `N unverified of M`
— bind pages that publish numbers. This page publishes none. It makes exactly one
executable claim, in §"The three mechanical facts", and that one carries its command
and its runtime.

**Scope.** HD-011's core lowering is ruled and is not redesigned here: `[:> Component
props & children]` is legal, lowers through the same foreign path as `defhost` with
the same default conversions, is `.cljs`-only at that node, and carries reduced
structural identity ([decisions.md](../decisions.md), HD-011's escape paragraph). The
subject is the five edges the 2026-08-04 operator note left open — SSR and hydration,
intent carriers in declaration-less props, what "reduced structural identity"
concretely means, the legal Component-value boundary, and children lowering's frame
capture.

---

## The lens, stated so it can be argued with

This design starts from one question: **what is the smallest thing that honours
HD-011 and closes all five edges?** Three consequences follow, and they are choices,
not neutrality.

- **Reuse beats invention.** Where an existing mechanism can be pointed at an edge,
  it is, even when a purpose-built one would be tidier.
- **A refusal is a design answer.** A loud, well-named error that deletes a silent
  failure class costs one error id and one paragraph of guide. A feature covering the
  same case costs a concept every reader carries forever. At pre-alpha the refusal
  usually wins, and where this design takes one it prices it.
- **Every new keyword, option and concept is spent from a budget.** The guide came
  down from 2,633 lines to 1,875. `[:>]` is the *secondary* form; it does not get to
  be the interesting one.

The design's cost is concentrated in one place and stated up front: **§8** lists six
things this minimalism gives up and names who feels each.

---

## The whole design in one sentence

> **`[:>]` is `defhost` with the declaration omitted — and what omitting the
> declaration costs you is exactly what the declaration carried.**

Every edge below is that sentence applied. The escape is not a second path with its
own semantics; it is the *same* path entered without a declaration, and the five open
edges are five readings of what "without a declaration" means.

That framing does real work. It makes the escape **strictly weaker** than the door by
construction, which is the only way the guide's rule — "declare what you use twice" —
has teeth. A hatch that is merely *less pretty* than the door gets used first and
forever.

### What the declaration carried

| What a `defhost` declaration carries | `defhost` | `[:>]` |
|---|---|---|
| An author-chosen crossing name | yes, on the gate's `displayName` | no — one constant, `[:>]` |
| `:callbacks` contracts (`:event`/`:handler`/`:render`) | yes, exact, per slot | none — every prop is undeclared |
| An `:ssr` policy (`:client-only` or `{:fallback …}`) | yes, author's choice | fixed `:client-only`, unspellable |
| A mint-time site for refusals | yes — the author's own stack, once | render-time only, every render |
| A value a test can name and compare | yes, a `def` | no — the identity is the raw JS value |
| A crossing identity for tooling | yes, minted once, per crossing | one generic marker for all crossings |

*(6 rows, 3 columns.)*

### The three mechanical facts the shape rests on

Properties of the code as it stands today, each load-bearing below.

**(a) `:>` would currently route to the native path.** `vec->element`'s `cond` asks
`fragment-head?`, then `hiccup-tag?`, and `hiccup-tag?` accepts any keyword that is
not `:<>`. So `[:> Chart {}]` renders a `<>` element today. A `raw-head?` branch goes
*before* `hiccup-tag?`, and it must compare with `=` and never `identical?` — the
exact trap `fragment-head?`'s docstring already documents: ClojureScript keyword
literals are shared constants only when the build interns them, so an identity
comparison works under `:advanced` and silently routes every escape into the native
path everywhere else. This is the bug the implementation is most likely to ship.

**(b) The argv indices shift by one.** `[:> C props & children]` puts the component
at 1, the attribute map at 2, children from 3; `host-element` reads its head at 0 and
its attribute map at 1. The minimal factoring is one function taking the attribute
index — `crossing-element [head argv attr-index]` — with `host-element` passing 1 and
the escape passing 2. That is not a second path. It is the same path with an index,
which is what "same foreign path" has to mean in code if it means anything.

**(c) A fresh gate per render is fatal, and the obvious cache is impossible.** Since
`rf2-2rtt6.85` the element's *type* at a crossing is the declaration's gate. Mint a
gate per render and the element type changes every render, so React unmounts and
remounts the entire foreign subtree each time — destroying the foreign component's
state, refs, scroll position and uncommitted input. The escape therefore needs a
**stable type**.

The first design this page tried was a `WeakMap` from the component value to a minted
anonymous declaration — `[:>]` constructing a `defhost` on first use, which would have
made "same foreign path" true by literal code identity. **It does not work**, and the
reason is worth stating precisely because it is not obvious and it decides the shape:

```bash
$ node -e "new WeakMap().set(Symbol.for('react.suspense'), 1)"
TypeError: Invalid value used as weak map key
```
*(Node v24.13.0, this repo's `implementation/node_modules/react` at React 19. `Fragment`,
`Suspense`, `StrictMode` and `Profiler` are all `Symbol.for(…)` — **registered**
symbols, which the ES2024 symbols-as-WeakMap-keys rule excludes by design, because a
registered symbol is never collectable.)*

So a component-keyed weak cache cannot key React's built-in wrapper types. That is
not a corner: this design accepts `React.lazy` because HD-011 names `lazy` values as
a reason the escape exists, and a `lazy` component **requires a Suspense ancestor**.
Accepting `lazy` while being unable to host `Suspense` is incoherent. A strong `Map`
would work and would leak — an author writing `[:> (memo Foo) …]` inline mints a fresh
key every render — and a two-cache split (weak for objects, strong for symbols) is two
mechanisms where the budget allows none.

**The cache is therefore rejected, and the shape below has no cache at all.**

### The one shared gate

**Decision.** One module-level gate serves every raw crossing. It reads the component
off its own props and renders it behind the same `useSyncExternalStore` snapshot pair
`mint-host-gate!` uses. Beside it sits one module-level **constant pseudo-head** —
`displayName` `"[:>]"`, `callbacks` empty, `ssr` `:client-only` — so that
`host-entry`'s existing walk and its existing diagnostics work unchanged.

- **No state.** No cache, no mint, no registry, nothing that grows. Two module-level
  constants, which is the shape `gate-no-subscribe` / `gate-adopted` /
  `gate-unadopted` already established next door and for the same reason.
- **The type is constant**, so it is stable by construction for every component,
  including symbols. A runtime component swap at one site keeps the gate's fiber and
  remounts only the inner subtree — which is the correct behaviour and marginally
  less work than a per-component type.
- **The conversion walk is bit-identical to `defhost`'s.** `merge-caller`,
  `host-entry`, `check-ref!`, `host-prop-value` and `make-element` are the same
  functions with the same arguments. What differs is one line inside the gate: where
  the component comes from.

**The one wrinkle, priced.** The component travels to the gate as a prop —
`"hicassoRawComponent"`, following the codec's existing marker convention
(`hicassoHost`, `hicassoBoundary`, `hicassoFn`) — and the gate must remove it before
forwarding, or a DOM-spreading component turns it into a React unknown-prop warning at
exactly the boundary where the author is already confused. Removing it means one
shallow copy per crossing per render (`Object.assign` into a fresh object, then
delete the key). Mutating the props object in place would avoid the copy and is
rejected: React freezes element props in development, and mutating props is a React
sin regardless.

That copy is the price of having no declaration, it lands only on the escape path,
and **it must be witnessed** — a row asserting the foreign component's received props
do *not* contain the internal key, because a silent leak here is a warning the author
cannot explain.

---

## Edge 1 — SSR and hydration

**The obligation.** Clause 6 says the escape renders nothing server-side. The hard
half is the operator note's: *a server-absent `[:>]` node must also be absent from the
client's hydration first pass, or X2 zero-mismatch breaks.*

### Decision

**The shared gate uses the same `useSyncExternalStore` snapshot pair as
`mint-host-gate!`, with no fallback. `[:>]` has no `:ssr` spelling of any kind — not
an option, not a prop, not a metadata key. The policy is `:client-only`, always.**

### Reasoning

The gate already *is* the mechanism for exactly this obligation, and the codec says so
in terms: it is "the one mechanism that serves the server render, hydration's first
client pass and a fresh `createRoot` mount alike". Its `useSyncExternalStore` answers
`false` from the server snapshot and `true` from the client one; React reads the
server snapshot under `renderToString` **and again on hydration's first client pass**,
then re-renders with the client snapshot once adoption completes.

So the hard half is discharged **by construction, not by a second rule**. The server
renders nothing at the node. The client's first pass consults the same server snapshot
and also renders nothing. There is no DOM node on either side and therefore nothing to
reconcile — which is precisely the shape `arm1/host_ssr_dom_cljs_test`'s first-pass row
already asserts for the declared form, and it asserts it the only honest way: by asking
React through `onRecoverableError` and a console/window capture, never by reading the
settled DOM.

Two things this argument deliberately does **not** lean on:

- **`:rf/render-hash`.** `rf2-2rtt6.91` is open and shows the hash is degenerate for an
  interpreted root — the dogfood screen and the 1,200-element Conduit feed take the
  same value. A hydration argument built on it would agree vacuously. Every claim here
  rests on React's own reconciliation report.
- **A server-side policy branch.** The server walk never consults `:ssr`; it consults
  it by rendering. A branch for `[:>]` would be a second mechanism for the thing the
  gate exists to do once.

**Addendum, 2026-08-05 — `rf2-2rtt6.91` has closed (PR #7510); the instruction
stands and its reason is now stronger.** The first bullet's "is open" is stale, and
nothing else about it is: the measurement still reproduces exactly, because the
witness rows that chose byte digests were rewired to take the hash from
`ssr-hash/render-tree-hash` directly. What changed is *why* nothing here may lean on
the hash. It is no longer "the value is degenerate" but "this tier carries no such
value" — Spec 011 tiers hydration-mismatch detection by **render-tree
representation, not by adapter brand**, and a root React adopts (a compiled
`re-frame.ui` root, a native UIx root, or a Freehand root) now carries no
`:rf/render-hash` at either end. `83b865f8` was in any case never a fact about this
design: it is the FNV-1a-32 of the canonical EDN `[#fn[] {}]`, so **any** unresolved
`[<fn> {}]` root takes it. Every claim here still rests on React's own reconciliation
report, which is the door this design was already built to.

The `createRoot` case falls out unchanged: a fresh mount never consults a server
snapshot, so it renders the foreign component on its very first pass with no
placeholder flash.

### What it costs

1. **One fiber and one hook per crossing.** Identical to `defhost` since
   `rf2-2rtt6.85`. HD-020(b)'s ≤2-hook budget is a statement about Hicasso
   *boundaries* and is untouched: the gate holds no subscription, reads no frame, runs
   no body.
2. **No fallback is spellable.** The sharpest SSR cost. A layout-sensitive foreign
   widget — a chart, a map, a rich editor — leaves a hole in the server HTML and pops
   in after adoption. **Who feels it:** anyone server-rendering a page whose
   above-the-fold content crosses through the escape. **The remedy is one line of
   `defhost`**, which is the pressure working as designed rather than a gap.
3. **Children are lowered eagerly and then discarded on the server.** The crossing
   builds its child elements before the gate decides to render nothing. Small, and it
   buys something: a refusal in a child fires on the server, early, rather than on the
   client.

A cost this design *avoids* is worth naming, because the declared form carries it.
`rf2-2rtt6.85`'s merged-PR audit found `declared-ssr` accepts any one-key
`{:fallback …}` map whose value is merely non-nil, so `{:ssr {:fallback {}}}` mints and
then fails late inside React during a server render. **`[:>]` is immune by
construction**: with no fallback spelling there is no fallback to validate.

### How it could be wrong

- **A second renderer would break the argument.** "By construction" holds because
  `rf2-2rtt6.86` pinned R0 — the server engine is the existing runtime under Node
  `react-dom/server`, no second renderer anywhere. A JVM walk or a streaming path that
  renders without consulting the server snapshot would break this. It would break
  `defhost` identically, so the escape adds no new exposure, but the argument is
  conditional and should be read that way.
- **`React.lazy` never begins loading during hydration.** Under `:client-only` the gate
  renders nothing, so the import does not start until after adoption. A real
  waterfall; the declared form has it the same way.
- **"No DOM either side" needs a witness, not an assertion.** Whether the gate's `null`
  leaves any marker in `renderToString` output is a fact about React, and
  `rf2-2rtt6.87` found that a capture window closing when `hydrateRoot` returns sees 0
  of 1 complaints. A `[:>]` row taken through such a window would be vacuous. The
  window must open before `hydrate-root!` and close after `adopted!`.
- **Refusing `:ssr` at the escape may be too strict.** Against: an author with a
  runtime-selected component *and* a layout-shift problem must now declare each
  candidate. For: a policy written in a props map is invisible to tooling and
  unreusable at a second call site, which is the whole argument for a declaration.
  This design takes the strict reading, and the bead's clause 6 already marks it
  operator-overturnable — see **R2**.

---

## Edge 2 — intent vectors and `h/fn` in declaration-less props

### What the existing code already does with an empty declaration

`host-entry` is fully determined once `callbacks` is empty. This table is not a design
choice; it is what running the foreign path with no contracts produces today.

| Value at a `[:>]` prop | At an event-spelled slot (`:on-*`) | At any other slot |
|---|---|---|
| intent vector `[:evt …]` | refused — `:rf.error/hicasso-host-undeclared-callback` | `clj->js` — crosses as an inert array |
| key-map `{"Enter" [:evt]}` | refused — same id | `clj->js` — crosses as an inert object |
| `h/fn` | crosses by identity as a plain function | crosses by identity as a plain function |
| plain `fn` | crosses by identity | crosses by identity |
| keyword / symbol | `(name v)` | `(name v)` |
| hiccup vector | — | `clj->js` — an array, **not** an element |

*(6 rows, 3 columns.)*

### Decisions

**(a) The event-slot refusal is inherited, free, and satisfies clause 5's spirit.**
`refuse-undeclared-host-event!` already fires for the two dispatching carriers at an
event-spelled slot, and its reasoning transfers word for word: the alternative to
refusing is `clj->js` shipping the author's intent to the library as inert data, which
is the silently-dead-handler class every loud error in this codec exists to delete.
One rewording is needed — at `[:>]`, "declare it in `:callbacks`" is advice about a
declaration the author does not have, so the recovery is *write a `defhost`*.

**(b) An intent vector at a non-event-spelled slot crosses as `clj->js` data, and this
design does not fix it.** A library whose callback prop is spelled `:action` or
`:command` rather than `:on-*` receives `["evt" …]` and ignores it, silently. The
tempting fix is a heuristic — "a vector whose head is a qualified keyword looks like an
intent" — and it is refused, because HD-024's entire law is that a contract is **never
inferred** from a spelling, and a shape test is that inference in a different suit.
Note precisely what this is: **a property of the foreign path that `[:>]` inherits,
not one it introduces.** The same value at the same slot on an undeclared `defhost`
prop behaves identically. It belongs in a `defhost`-wide bead, not here.

**(c) An `h/fn` at any `[:>]` prop is REFUSED, loudly.** This is the one place this
design adds an error id, and the argument is that it *completes* an existing rule
rather than adding one. `h/fn`'s whole meaning, per `intent/callback`'s own docstring,
is to mark a function "so the position it is written at can impose a contract on it".
A `[:>]` prop is not a position — there is no declaration to name one. Handing a
contract-seeking carrier to a contract-less position is exactly what
`refuse-undeclared-host-event!` refuses in its other two spellings; the third spelling
is the one that currently gets through. Crossing by identity means an `h/fn` returning
`[:save]` hands the library a vector it discards, and nothing anywhere says so — the
silent class again, arriving through the marker that exists to prevent it.

Proposed id: `:rf.error/hicasso-raw-escape-callback-undeclared`. Recovery: hand a plain
`fn` if the function is a value the library calls, or declare the component with
`defhost` and name the slot's contract.

**(d) A plain `fn` crosses by identity, unwrapped. Unchanged, and load-bearing.**
`host-prop-value` preserves function identity deliberately so `React.memo` and every
downstream bail-out comparing handler identity keep working. Nothing here wraps a
function prop.

**(e) Hiccup in a *prop* is data, not an element — and the escape makes it visible.**
HD-011's conversions convert *children*, not prop values, so `{:fallback [:div "…"]}`
at a `[:> react/Suspense …]` becomes a JS array and React throws. This is existing
`defhost` behaviour, but the escape is where authors will meet it, because
`Suspense`-with-a-`:fallback` is one of the shapes the escape newly makes reachable.
The remedy would be a hiccup→element conversion at the call site, whose product is a
JS object that `host-prop-value` crosses raw — and **there is no such conversion an
author can call.** `codec/as-element` is the mechanism and it is internal; nothing on
the taught `h/` roster reaches it, and there is no `h/as-element` (an earlier revision
of this section named one; see `rf2-2rtt6.120`). So this owes **a ruling** — export a
spelling, or say in the guide that the position has no recovery — before it can owe a
troubleshooting row. What it does not owe is a mechanism: the conversion exists.

### What it costs

The `h/fn` refusal costs one error id and one guide sentence, and it taxes a real
migration move: someone porting a `defhost` call site to `[:>]`, or reaching for
`h/fn` out of habit, gets an error where a plain function would have worked. The fix
is one word.

It also **diverges from `defhost`'s behaviour at an undeclared slot**, where an `h/fn`
crosses by identity today. That divergence is why this decision sits in **R1** rather
than being settled here.

### How it could be wrong

- If authors write `h/fn` mostly as a convenience for "call me with the library's whole
  argument list" rather than as a contract request, refusing it is a pure ergonomic tax
  that buys nothing. The evidence against that reading is the marker's own docstring —
  but that is a reading of intent, not a measurement, and the census counts zero
  foreign components.
- Reading HD-011's "same foreign path with the same default conversions" strictly,
  `h/fn` handling might count as part of the path and a divergence might be out of
  bounds. This design reads `h/fn` as the *contract* layer rather than a default
  conversion — the conversions HD-011 enumerates are camelCase props, hiccup children
  and function pass-through — but a reviewer reading it the other way is not obviously
  wrong.
- Leaving (b) unfixed is defensible only if it really is inherited. If a reviewer shows
  the escape makes it materially more likely — because there is no declaration, and so
  no moment where the author enumerates the library's callback props — then a
  `[:>]`-specific refusal gets stronger and this design has under-priced it.

---

## Edge 3 — what "reduced structural identity" concretely means

The phrase appears once in the record (HD-011) and once in the guide's troubleshooting
table ("a `[:>]` node has reduced structural identity — prefer `defhost`, or assert
around the node"). It has never been cashed out, and cashing it out needs one fact
stated first, because it reframes the whole edge:

> **Hicasso's headless structural render does not exist.** `draft-guide/08-testing.md`
> line 16 calls the `h/render` block a sketch — *"Nothing in the tree implements this
> yet"* — and its names are marked `[unfrozen]`. The built structural surface in this
> repo is Freehand's (`re-frame.freehand.test`), whose node schema
> (`spec/004B-UI-Tree-and-Conversion.md`) carries a `:rf.ui/host` variant. Hicasso's is
> designed, not built.

And one more, which is the sharpest thing on this edge and cuts *against* the phrase's
apparent weight:

> **The foreign region is already out of headless scope for both forms.**
> `08-testing.md` line 44: *"If a body renders a foreign component through `defhost`
> (or the `[:>]` escape, once it is built), the foreign region is out."* So what
> `defhost` buys over `[:>]` structurally is **the crossing node's identity**, not
> visibility into what the component renders. Neither form gives you that.

### Decision

"Reduced structural identity" is four statements, ordered from the surface that exists
today to the one that does not. All four are assertable except the last, which is a
recorded forward obligation.

**1. Canonical DOM is not reduced at all.** A `[:>]` and a `defhost` on the same
component with the same props produce the **same DOM** in every phase — nothing before
adoption, the component's own DOM after — because the gate contributes no node of its
own. This is the strongest and most useful thing to be able to say. It is directly
assertable with the `lane/canonical` comparator X1(b) already uses, and it tells an
author what still works: every DOM-level test, every canonical-parity row, every
screen-level assertion.

**2. At the element altitude — the only Hicasso structural surface that exists today,
`front/codec_cljs_test`'s `.type` / `.props` / `.key` reads — the node is fully
assertable and the type is one constant.** Every raw crossing has the same `.type`
(the shared gate), so `.type` discriminates *raw from declared* but not *which
component*. The component is one own-property read away, on `.props`, under the
internal key — which a codec test may read and a product test may not.

**3. At the hiccup-data altitude the reduction is one slot wide.** `[:> C {…} …]` is an
ordinary vector: `=` compares it structurally, and the reduction is that slot 1 holds a
JS value compared by **identity**. `(= [:> C {:a 1}] [:> C {:a 1}])` is true; a
different component is false. Total everywhere else in the vector. What is genuinely
lost is that naming `C` at all requires the JS import, so any test that names the node
is `.cljs`-only and coupled to that import — which is HD-011's `.cljs`-only clause
restated at the test.

**4. The forward obligation, recorded and not built.** When Hicasso's headless render
lands, a `defhost` crossing has a declared name to project as a node identity — the
shape Freehand already ships as `{:rf.ui/host ::date-picker, :rf.ui/host-ssr
:client-only, :props {…}}`. **A `[:>]` node has no name to project.** Its props and
children can still appear; its identity cannot. Whoever builds that render must decide
between an identity-less host node and no node, and `spec/004B` already records why the
second is dangerous: a host node that falls through to the fragment arm answers `{}` —
*"a total, harmless-looking, wrong answer, delivered silently because the fragment arm
is documented total rather than an error."* **This design's position is that the
projection should be a host node with an absent identity, never an omitted node** — but
that is a note for a render that does not exist, not a mechanism this bead builds.

**And therefore the guide's advice is exact, not a hedge.** "Assert around the node"
means assert on the DOM the component produced or on the props you handed it, and do
not assert on the node's identity. That is a complete strategy today, because there is
no structural render to be reduced *in*.

### `displayName`: one constant, deliberately

The gate's `displayName` is the literal `"[:>]"` — not derived from the component.
Deriving would be redundant: the foreign component's **own fiber sits directly beneath
the gate** and React's `getComponentNameFromType` names it there already. So
`defhost` reads `<my.ns/date-picker>` → `<DatePicker>` in a stack or in DevTools, and
the escape reads `<[:>]>` → `<DatePicker>`. One greppable frame naming the *form the
author wrote*, and the component naming itself one level down, at zero cost. There is
no DOM marker on either form, and adding one to the escape would make it **more**
visible than the declared door and break statement 1.

### What it costs

The real cost is statement 4 and it is HD-011's, not this design's: when the headless
render lands, a view tree containing an escape will have one node the tree cannot name.
**Who feels it:** a team that writes structural view tests once and runs them
everywhere, and then adds one runtime-selected component.

Today the cost is close to zero, and saying so honestly matters more than sounding
careful: the surface that would be reduced is not built, and the foreign region is out
of its scope for the declared form too.

### How it could be wrong

- Statement 1 is the one most worth attacking. It holds only while the gate renders
  *exactly* the component or *exactly* nothing. If a future gate ever wraps output —
  an error boundary, a Suspense, a profiler — canonical parity with `defhost` survives
  only if both forms wrap identically.
- Statement 4 is a prediction about an unbuilt render. If that render instead adopts
  Freehand's map-node schema wholesale, `[:>]` may need an explicit refusal — the
  matcher declining to match a raw node with a message naming `defhost` — rather than
  the soft absence proposed here. That would be a strictly better answer and this
  design does not foreclose it.
- `spec/008-Testing.md` (~line 770) still says a foreign boundary is opaque to the
  structural render because "the v1 node set carries no host variant", which
  `spec/004B` and the shipped `node-kind` host arm contradict. That is outside this
  bead's fence and outside Hicasso, but it is a stale sentence a reader of this edge
  will trip over. Recorded as a finding, not actioned.

  > **Fixed 2026-08-05 (`rf2-whfte`) — the finding was right, and 008 now says so.**
  > The qualified-host bullet no longer claims the crossing is opaque. What is opaque
  > is what the foreign component *renders beneath* the crossing — a host node's
  > `:children` are the declared SSR projection, never the registered component's own
  > output — while the crossing itself (`:rf.ui/host`, its declared `:rf.ui/host-ssr`
  > policy, and its `:props`) is an ordinary headless read in the `jvm` and `browser`
  > structural cells. The bullet above stands as the record of what was true when this
  > page was written; the reader it warns about no longer trips.

---

## Edge 4 — the legal Component-value boundary, and where refusal falls

`mint-host!` refuses exactly one value: `nil`. It can afford to, because a declaration
is written once by an author reading the library's documentation. `[:>]` has no such
site — HD-011's first named use case is a component *selected at runtime* — so the
check happens at the crossing, and it has to name the confusions people actually make.

### Decision

**One predicate, one error id, several discriminating reasons.** Proposed id:
`:rf.error/hicasso-raw-escape-not-a-component`.

**Accept**, with no ceremony, because HD-011 names most of these as the reason the
escape exists:

- any `fn?` that is not a Hicasso head — function components, and classes, since a JS
  class is a function;
- any JS object carrying `$$typeof` that is not a React *element* — `memo`, `lazy`,
  `forwardRef`, context providers and consumers, and whatever React adds next;
- any **symbol** except `Fragment` — which is how `Suspense`, `StrictMode` and
  `Profiler` arrive, and accepting them is what makes accepting `lazy` coherent.

**Refuse**, each with its own sentence in the message:

| Value in Component position | What the message says |
|---|---|
| `nil` | the classic broken-import symptom — `:default` against a library with no default export |
| a string, keyword or number | that is a native tag — write `[:div …]` or `[:my-widget …]` |
| a Hicasso **view** head | write it in head position: `[my-view …]` |
| a Hicasso **host** head | that already *is* a declaration — write `[my-host …]` |
| a React **element** | an element is a legal *child*, not a type — pass it as a child |
| `React.Fragment` | the fragment spelling is `[:<> …]` |
| any other object without `$$typeof` | not a React element type |

*(7 rows, 2 columns.)*

The two Hicasso-head refusals earn their keep. A `defview` head is `fn?`-true, so a
bare "is it a function" test would accept `[:> my-view {…}]` and cross it as a foreign
component — silently losing the frame prop, the memo wrapper and the props map's shape.
That is a dangerous accept, and it is the kind a migration produces.

`forwardRef` needs no special handling. HD-016's callback-refs-only rule is enforced at
the crossing by `check-ref!` exactly as at a `defhost`, and React 19 carries `ref` as an
ordinary prop through the gate to the component. Clause 3 is satisfied by reuse, not by
new code.

The check fires **at render, inside the writing boundary's body**, so the author who
wrote the crossing sees their own stack. With no declaration there is nowhere earlier
to put it, and that is a real asymmetry with `defhost`: the declared form refuses once,
the escape refuses on every render.

### What it costs

- **`$$typeof` is a React internal.** Reading it is what every interop layer does and it
  has been stable for a decade, but it is undocumented. If React ships a renderable
  type without it, this predicate **false-refuses a legal escape** — the worst failure
  mode a hatch can have, because the hatch exists for the cases nothing else covers.
- A per-render predicate on the escape path: two or three type tests. Negligible, and
  the escape is the rare path by construction.

The looser alternative was seriously considered: refuse only the named confusions and
let everything else through to React, whose *"Element type is invalid: expected a
string or a class/function but got: object"* is already loud and already carries a
component stack. It **cannot false-refuse**, which is a real virtue here. It was
rejected because it gives up clause 5's named refusal for the plain-object case and
hands the author an error naming nothing they wrote. **This design takes the tighter
predicate and records the trade** — if the reviews want the looser one, the paragraph
above is the one to overturn.

### How it could be wrong

- Refusing a React element in Component position is over-strict for the author who
  deliberately wrote `[:> (build-thing)]` where the builder returns an element. The
  message names the fix, so the cost is a confused minute rather than a wall — but it
  is a refusal of something React would have accepted, which is the category to be
  careful in.
- The Hicasso-head refusals assume a view head should never be crossed as a foreign
  component. If someone genuinely wants a Hicasso view rendered *by* a foreign
  component through the escape, the refusal blocks it. The remedy is a native head, so
  the cost looks bounded — but "I did not imagine it" is exactly the claim a hatch
  design should be suspicious of.
- Accepting bare symbols means `[:> react/Suspense {:fallback …}]` is legal, and its
  `:fallback` is a **prop**, so hiccup there is `clj->js`-ed rather than converted —
  edge 2(e). The accept is right; the trap it opens is real and belongs in the guide.

---

## Edge 5 — children lowering's dispatch and frame capture at the crossing

### Decision

**Nothing new. Children lower eagerly at the crossing, under the writing boundary's
frame — and the render-prop case is a refusal, not a feature.**

### Reasoning

`make-element` calls `as-element` on each trailing form **synchronously, inside the
writing body's render**, where `intent/*frame*` and `intent/*dispatch*` are bound. So a
child's intent lowers against the boundary that *wrote* the `[:>]`, exactly as at a
native tag and exactly as at a `defhost`. A `[:>]` written outside any boundary raises
the existing `:rf.error/hicasso-intent-outside-boundary` when it lowers an intent. This
edge closes with zero new code, because the crossing reuses `host-element` and
`host-element` reuses `make-element`.

The `rf2-2rtt6.74` class — the arming gate the operator note points at — **cannot arise
through a declared contract at `[:>]`, because there are no declared contracts.** It
arises the other way, and the honest answer is a refusal:

> A plain function prop that the foreign component invokes as a **render prop**, and
> inside which the author calls the codec's hiccup→element conversion on hiccup
> carrying an intent, lowers with `*dispatch*` bound to `nil` — because React renders
> the foreign component in a later fiber pass, long after the writing body's `binding`
> extent has unwound. That is the existing loud
> `:rf.error/hicasso-intent-outside-boundary`, and at `[:>]` it stays loud.

PR #7449 fixed this for the *declared* form by making render-position enforcement
invocation-scoped and rebinding the supplying boundary's frame around the invocation.
That repair hangs off a declaration naming the slot `:render`. `[:>]` has no
declaration, so it gets no rebinding, and the diagnostic's recovery is `defhost` with
`:callbacks {… :render}`.

Two plausible-looking alternatives are rejected, and the second is recorded because it
is the fix a reader will reach for and it does nothing at all:

- **Wrapping every function prop at `[:>]` in the render wrapper by default.**
  Rejected: it changes function identity, which `host-prop-value` preserves
  deliberately so downstream `React.memo` comparisons keep working, and it imposes a
  contract on values that are not positions — a function handed to a foreign API is a
  value, not a slot.
- **Having the gate bind the frame around its `createElement` call.** Rejected:
  `createElement` does not render. It builds an element; React renders it later, in its
  own work loop, with the binding long unwound. The binding would cover nothing.

### What it costs

**The interactive render prop is not available through `[:>]`.** A headless table,
list, combobox or virtualiser's `renderRow`/`renderItem` — where the returned row
carries an `:on-click` intent — is one of the most common shapes in the React
ecosystem, and through the escape it errors.

**Who feels it:** a team migrating a page that uses any headless library's render
props, and anyone whose first reach for the escape is a component with a render-prop
API. That is a large population and it is this design's biggest functional hole.

**The price is paid deliberately.** The remedy is one line of `defhost`, and this is
precisely the case where a declaration earns its keep — the render-prop contract is
exactly the thing a declaration exists to name. Making the escape work here would mean
inferring a `:render` contract from a function's position, which is the inference
HD-024 deletes. See **R3**: whether that price is acceptable is a product call, not a
mechanical one.

### How it could be wrong

- **The diagnostic is written for the wrong reading.**
  `:rf.error/hicasso-intent-outside-boundary` currently says event vectors are only
  legal inside a boundary's render, recovery
  `:lower-intents-inside-a-boundary-render`. At `[:>]` the author *is* inside a
  boundary's render as far as they can see — the crossing is written in a body — so the
  message will read as a framework bug. The runtime cannot distinguish the two causes
  from `*dispatch*` = `nil` alone, so the message must offer both readings and name
  `defhost` + `:callbacks {… :render}` as the second. **A concrete, cheap finding the
  implementation should not ship without.**
- If the render-prop case is *most* of what the escape gets used for, refusing it makes
  the escape close to useless and HD-011's "render-prop-supplied components" use case
  reads as unserved. The distinction that keeps this design coherent: HD-011 names a
  component **supplied by** a render prop — the foreign API hands you a component and
  you cross it — which works fine here. A component **whose props include** a render
  prop returning interactive hiccup is the case that errors. Those are different, and a
  reviewer who conflates them will reach a different verdict; a reviewer who separates
  them may still judge the second common enough to change the answer.
- Eager child lowering means a child's refusal fires even when the gate will render
  nothing. Called a benefit in edge 1; it is also a way for a server render to fail on
  markup that would never have been sent.

---

## What this minimalism gives up, and who feels it

| Given up | Who feels it | Remedy |
|---|---|---|
| No SSR fallback — a hole above the fold | anyone SSR-ing a layout-sensitive foreign widget | `defhost` with `{:ssr {:fallback …}}` |
| `h/fn` refused at the crossing | migrators, and `defhost` habits | write a plain `fn`, or declare |
| Interactive render props error | headless-library users — a large population | `defhost` with `:callbacks {… :render}` |
| Intent at a non-`on*` slot crosses inert | anyone forwarding intents to oddly-named props | write a function |
| No crossing identity to project structurally | teams writing structural view tests, once the render exists | assert around the node, or declare |
| No author-chosen name in a stack or DevTools | anyone reading a component stack | `<[:>]>` above the component's own frame |

*(6 rows, 3 columns.)*

**Every remedy in that column is `defhost`, and that is the design, not a
coincidence.** The escape must be strictly weaker than the door or "declare what you
use twice" is advice with nothing behind it. A hatch matching the door on capability is
not a hatch; it is a second front door, and HD-011 spent its whole rationale on not
having one.

Where that reasoning is weakest is row 3. Rows 1, 5 and 6 are losses of *polish* — the
thing still works, less well. Row 3 is a loss of *capability*, and a reviewer arguing
the escape must serve interactive render props is arguing against the strongest version
of this design, not the weakest.

---

## What needs a ruling

Three. Named as findings rather than filled in, because the record does not settle
them and inventing an answer would be worse than naming the silence.

**R1 — `h/fn` at an undeclared crossing prop.** This design refuses it at `[:>]`
(edge 2(c)), which diverges from `defhost`, where an `h/fn` at an undeclared slot
crosses by identity today. Three coherent positions:

- refuse at `[:>]` only — the escape is stricter because it has no declaration to fall
  back on (**this design's recommendation**);
- refuse at both, as one completed rule — better, and it changes `defhost`, which
  HD-011 froze, so it is not this bead's to take;
- inherit at `[:>]` and accept the silence — the strictest reading of "same foreign
  path".

**R2 — the escape's SSR policy is unspellable. Confirm or overturn.** Clause 6 already
marks itself operator-overturnable, and HD-011 ruled the escape *before* SSR came into
scope on 2026-08-04, so the escape's SSR behaviour has never actually been ruled — only
inferred from "same foreign path". This design infers `:client-only`, always, with no
`:ssr` spelling (edge 1). The alternative is a props-map policy, which is a policy no
tool can see.

**R3 — does the escape owe interactive render props?** Edge 5 refuses them and prices
the refusal as this design's biggest hole. Serving them means either inferring a
`:render` contract from a function's position — which HD-024 deletes — or minting some
declaration-less way to name one, which is a new concept. The product question is
whether "reach for `defhost` when your component has a render prop" is an acceptable
rule.

---

## What an implementation would owe as witnesses

Not this bead's deliverable. The bead's own WITNESSES list stands; these are the
edge-specific additions this design implies.

- **Edge 1.** A server-render row (node absent) *and* a hydration row taken through an
  honest window — capture opened before `hydrate-root!`, closed after `adopted!` —
  asserting zero across the same three channels X2 uses, with a mutation proving the
  capture can speak. Plus a `createRoot` row showing no placeholder pass.
- **The gate's internal prop.** A row asserting the foreign component's received props
  do **not** contain `hicassoRawComponent`. A leak here is a React warning the author
  cannot explain.
- **Edge 2.** The event-slot refusal; the `h/fn` refusal with its named id; a plain
  `fn` crossing with **identity preserved** — assert `identical?`, not merely callable.
- **Edge 3.** Canonical-DOM equality between a `[:>]` and a `defhost` on the same
  component and props, in both phases. The strong claim, and the row most worth having.
- **Edge 4.** One accept row per accepted shape — function, class, `memo`, `lazy`,
  `forwardRef`, provider, `Suspense` — and one refusal row per named confusion, each
  asserting the discriminating reason and not merely the id.
- **Edge 5.** A child intent dispatching to the **writing** boundary's frame on a
  two-frame page — `rf2-2rtt6.74`'s ownership row's shape, taken at the escape.

---

## Rejected alternatives

**A component-keyed anonymous declaration cache.** `[:>]` minting a `defhost` on first
use and caching it weakly, which would have made "same foreign path" true by literal
code identity. Rejected on a hard mechanical fact, verified above: React's built-in
wrapper types are **registered** symbols and a `WeakMap` throws on them, so the cache
cannot key `Suspense` — which is incoherent beside accepting `lazy`. A strong `Map`
leaks on an inline `(memo Foo)`; a weak/strong split is two mechanisms. It also raised
an HD-004 question — a third cache, keyed by a runtime value rather than an author
literal — that the shared gate makes moot.

**Minting the head onto the component object.** `unchecked-set component
"hicassoRawHead"` would be cache-free and stable. Rejected: it mutates a third-party
value, fails on anything frozen, cannot touch a symbol at all, and `React.lazy`'s
product is React's object, not ours.

**Forwarding the gate's own props unstripped.** Saves the shallow copy and leaves an
internal `hicassoRawComponent` prop on the foreign component — a React unknown-prop
warning at any component that spreads onto a DOM node, at exactly the boundary where
the author is already confused.

**Setting `props.children` directly to avoid the copy.** Rejected: children arriving
through the config object rather than variadically skip React's dev key-check on child
arrays, silently losing a diagnostic HD-016 relies on.

**Mutating the props object in the gate.** Rejected: React freezes element props in
development, and mutating props is a React sin regardless.

**An `:ssr` option in the `[:>]` props map.** Rejected: a policy written in a props map
is invisible to tooling and unreusable at a second call site, which is the whole
argument for a declaration. See edge 1 and **R2**.

**A "looks like an intent" heuristic at undeclared slots.** Rejected: HD-024's law is
that a contract is never inferred from a spelling, and this is that inference wearing a
shape test instead of a name test.

**Wrapping function props at the crossing by default.** Rejected: destroys function
identity, which downstream `React.memo` bail-outs depend on. See edge 5.

**Binding the frame inside the gate.** Rejected: `createElement` does not render, so
the binding covers nothing. Recorded because it looks like it would work.

**Deriving the gate's `displayName` from the component.** Rejected as redundant: the
component's own fiber sits directly beneath the gate and React names it there. One
constant is smaller and says the more useful thing — *which form the author wrote*.

**A loose Component predicate with no `$$typeof` test.** Rejected on balance, with the
trade recorded in edge 4 — it cannot false-refuse, which is a real virtue, but it gives
up clause 5's named refusal for the plain-object case.

---

## Sources

- The bead: `rf2-2rtt6.103`, read bottom-up. Its six description clauses and the
  2026-08-04 design-phase comment are the brief this page answers.
- [decisions.md](../decisions.md) — HD-011 and its 2026-08-04 `:ssr` addendum, HD-016's
  component ABI (`:ref` at the crossing, callback refs only), HD-023's `:&` law and its
  clause (d), HD-020(b) and its reversed clause (d), HD-024.
- [The SSR spike witness](ssr-spike-witness.md) — X1–X5, and X2's zero-mismatch
  obligation, which edge 1 must not break.
- `implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs` — the "Left
  behind, by ruling and on purpose" docstring naming the escape as deliberately
  unbuilt, the Host-heads section, `mint-host!`, `mint-host-gate!`, `host-element`,
  `host-entry`, `host-prop-value`, `merge-caller`, `check-ref!`, `make-element`,
  `vec->element`.
- `implementation/freehand/test/re_frame/bench/hicasso/front/intent.cljs` — `callback` /
  `callback?`, `*frame*` / `*dispatch*`, `event-callback`, `render-callback`,
  `lower-declared-prop`.
- `implementation/freehand/test/re_frame/bench/hicasso/arm1/host_ssr_dom_cljs_test.cljs`
  — the declared form's first-pass hydration row, whose shape edge 1 borrows.
- `docs/design/hicasso/draft-guide/08-testing.md` — lines 16 and 44, which establish
  that Hicasso's headless render is a sketch and that the foreign region is out of its
  scope for both forms.
- `spec/004B-UI-Tree-and-Conversion.md` — the shipped node schema's `:rf.ui/host`
  variant, and the silent-fragment-arm warning edge 3 borrows.
- Beads `rf2-2rtt6.85` (the `:ssr` policy and its merged-PR audit), `rf2-2rtt6.86` (the
  Node render entry, R0), `rf2-2rtt6.87` (the spike), `rf2-2rtt6.91` (the degenerate
  render hash — leaned on by nothing here), `rf2-2rtt6.74` and PR #7449 (the
  render-position arming gate).
