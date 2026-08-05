# The `[:>]` raw escape — the synthesized spec

**This is the artefact `rf2-2rtt6.103`'s design-phase note promises** ("implementation
re-dispatches from the synthesized spec"). `rf2-2rtt6.109` reads it too. It is a spec, not
a survey: where the designs agree it says the agreement is genuine and cites it, where they
conflict it decides and gives the reason, and where nothing had been attacked it attacks.

**Genre.** [The studio README](README.md#what-a-page-in-here-owes-its-reader) sets four
obligations on a page that publishes a number. **This page publishes none.** Its executable
claims are three, each carrying its command and its runtime in place.

**Scope.** HD-011's core lowering is ruled and is not reopened
([decisions.md](../decisions.md) ~:412–421): `[:> Component props & children]` is legal,
lowers through the same foreign path as `defhost` with the same default conversions, is
`.cljs`-only at that node, carries reduced structural identity, and is explicitly
*secondary* — "declare what you use twice", bare-head auto-hosting stays rejected. The
subject is the five edges the 2026-08-04 operator note left open.

---

## 0. What this synthesizes, and the two hazards in the material

**Ten inputs, not three.** Two design programmes ran concurrently for the same operator
instruction and neither knew about the other until both had landed.

| Input | Where | Lens |
|---|---|---|
| [Design A](raw-escape-design-a-minimal.md) | tracked | minimal surface |
| [Design B](raw-escape-design-b-correctness.md) | tracked | derived backwards from the obligations |
| [Design C](raw-escape-design-c-ergonomics.md) | tracked | the programmer's experience |
| `2026-08-04.rawescape-design-parity.md` | `ai/` — local only | maximal parity with the door |
| `2026-08-04.rawescape-design-minimal.md` | `ai/` — local only | the deliberately dumber hatch |
| `2026-08-04.rawescape-design-migration.md` | `ai/` — local only | the Reagent refugee's bridge |
| `2026-08-04.rawescape-attack-parity.md` | `ai/` — local only | 0 fatal / 5 major |
| `2026-08-04.rawescape-attack-minimal.md` | `ai/` — local only | 0 fatal / 5 major |
| `2026-08-04.rawescape-attack-migration.md` | `ai/` — local only | 1 fatal / 3 major |
| The bead's 12:06 comment | `rf2-2rtt6.103` | a synthesis of the six local documents |

*(10 rows, 3 columns.)*

**The `ai/` tree is gitignored**, so six of those ten do not survive a fresh clone and no
other machine can read them. Everything this spec relies on from that set is quoted here or
restated in its own terms; nothing below requires a reader to open a local-only file.

### Hazard 1 — one convergence is not two votes

Design A's page records that a research subagent surfaced the parity design and quoted its
§E3 back to it. A did not open the file, and reached two of the overlapping conclusions —
**the shared module-level gate** and **`displayName` as the constant `"[:>]"`** — by its own
chain. Agreement between Design A and the parity design on those two points is **one vote,
not two**, and this page says so wherever it leans on either. Both survive anyway, because
neither rests on that pair: the shared gate carries four further independent votes (C,
minimal, migration, and B's dissent killed on evidence in §2), and the `displayName`
constant is independently preferred by C and by the attack on the minimal design.

### Hazard 2 — the brief that dispatched this page was itself behind the bead

The bead's newest item is not a note but the **12:06 comment**, which is already a synthesis
— of the local six only. It never read A, B or C. This page's job is therefore narrower and
sharper than "synthesize six designs": **reconcile that comment against the tracked three,
and carry what it dropped.** Where this spec departs from it, §6 says so and why. Two of its
omissions are load-bearing and are restored in §5: the X2 body-run restatement, and the
`intent-outside-boundary` message repair.

---

## 1. The model, in one sentence

> **`[:>]` is `defhost` with the declaration erased — and what erasing the declaration
> costs you is exactly what the declaration carried.**

Design A and Design C reached that sentence independently and in nearly the same words; the
parity design's is a third phrasing of it. That is genuine three-way agreement and it is the
spec's spine, because it makes the escape **strictly weaker than the door by construction**,
which is the only way "declare what you use twice" has teeth.

| What a declaration carries | `defhost` | `[:>]` |
|---|---|---|
| An author-chosen crossing name | yes, on the gate's `displayName` | no — one constant, `"[:>]"` |
| `:callbacks` contracts | yes, exact, per slot | none — every prop is unclaimed |
| An `:ssr` policy | yes, author's choice | fixed `:client-only`, unspellable |
| A mint-time site for refusals | yes — the author's stack, once | render-time, every render |
| A crossing identity for tooling | minted once, per crossing | one generic marker for all crossings |
| A `.cljc` quarantine for the JS require | yes, in one host namespace | no — the require lands in the view ns |

*(6 rows, 3 columns.)*

Every remedy for every loss below is `defhost`. That is the design, not a coincidence.

---

## 2. Edge 1 — SSR and hydration

**Ruled: hard `:client-only`, through ONE module-level shared gate. No fallback spelling of
any kind — not an option, not a prop, not a metadata key.**

The gate is a function component whose single `useSyncExternalStore` reuses the landed
module-level snapshot triple `gate-no-subscribe` / `gate-adopted` / `gate-unadopted`
(`front/codec.cljs:1035-1037`) — the same three `mint-host-gate!` uses, with `placeholder`
fixed at `nil`, which is what `:client-only` already compiles to at the door
(`codec.cljs:1059`).

**Why this discharges the hard half by construction.** The operator note's binding clause is
that a server-absent node must *also* be absent from the client's hydration first pass, or
X2's zero-mismatch breaks. React reads the server snapshot under `renderToString` **and
again on hydration's first client pass**, then re-renders with the client snapshot once
adoption completes. Server-absent and first-pass-absent are therefore not two facts kept in
step — they are **one fact, and React chooses it**. There is no flag to set wrong, no
ordering to get backwards, and no branch on which server and client can disagree. A fresh
`createRoot` mount never consults a server snapshot at all, so it renders the component on
its first pass with no placeholder flash.

**This is the one unanimous edge.** All six designs and all three attacks land on it, and
the attack on the parity design closes it in terms: *"This edge needs no further adversarial
pass; the synthesis can record it as settled."* Recorded as settled.

**The carrier: two slots on the gate's own props, children on the outer element.** The
component and the converted props ride as `#js {"c" component "p" props}` — the shape
`boundary-element` already uses for `rfProps` — and children ride on the **outer** gate
element via `make-element`'s existing arms, forwarded by the gate as `createElement`'s third
argument.

Design A is the sole dissent here, carrying the component as a `hicassoRawComponent` prop
*beside* the converted props and stripping it inside the gate. **Rejected on A's own
pricing**: the strip costs one shallow copy per crossing per render, and A itself writes that
a leak of the internal key is "a React warning the author cannot explain" and must be
witnessed. The two-slot carrier makes that leak *unrepresentable* rather than witnessed,
which is the better trade by the standard A applies everywhere else. Children on the outer
element rather than inside `p` is the attack-on-migration's preference and the reason is
mechanical: it reuses `make-element`'s three arms verbatim, adding no code. React 19.2's
`validateChildKeys` only *marks* elements validated, so the gate's re-forwarding is
dev-warning-neutral — checked independently by two of the three attacks.

### Design B's mechanism is dead, on a fact and on a contradiction

Design B alone rejects the shared gate, taking instead **a module-level `WeakMap` from
component value to a lazily minted gate**. It does not work, for two independent reasons,
and neither was ever put to it — the adversarial wave ran only on the local three.

**(a) A component-keyed weak cache cannot key React's built-in wrapper types.** Verified on
this repo's pinned React:

```bash
$ cd implementation && node -e "new WeakMap().set(require('react').Suspense, 1)"
TypeError: Invalid value used as weak map key
```

*(React 19.2.0, Node v24.13.0. `Fragment`, `Suspense`, `StrictMode` and `Profiler` are all
`Symbol.for(…)` — **registered** symbols, which ES2024's symbols-as-WeakMap-keys rule
excludes by design, because a registered symbol is never collectable.)* Design A found this
first and its page carries the proof; it is re-run here because it decides the edge and
because A's page is the only durable record of it.

**(b) Design B contradicts itself on exactly the values that fact turns on.** Its edge-4
table rules *"string, keyword, or symbol → refuse"*, and four paragraphs later its edge-4
prose says *"`memo`, `lazy`, `forwardRef`, a class, a context object, a provider,
`Suspense`, and whatever React ships next all cross"*. `Suspense` is a symbol. Whichever half
one keeps, the pair is unsound: keep the prose and the `WeakMap` throws; keep the table and
the escape refuses `Suspense` while accepting `React.lazy`, which **requires a `Suspense`
ancestor** — the very pairing HD-011 names as a reason the escape exists.

The shared gate has no cache, no registry, and nothing that grows: two module-level
constants, the shape the landed snapshot triple already established next door. Its type is
constant, so it is stable for every component including symbols, and a runtime component swap
at one site keeps the gate's fiber and remounts only the inner subtree — which is the correct
grain for HD-011's first named use case.

### What it costs, stated

One fiber and one hook per crossing — identical to the door since `rf2-2rtt6.85`, and
HD-020(b)'s ≤2-hook budget is untouched because the gate is not a boundary: no frame, no
subscription, no body. **No fallback is spellable**, which is the sharpest cost: a
layout-sensitive foreign widget leaves a hole in the server HTML and pops in after adoption.
Children lower eagerly and are then discarded on the server; small, and it buys a refusal
that fires early rather than on the client.

**A per-site placeholder is nonetheless reachable with zero new escape surface**, and this
is worth teaching rather than conceding — it is the one thing the attack on the local
minimal design found *in that design's favour*:

```clojure
(defhost skeleton-slot (fn [p] (.-children p)) {:ssr {:fallback [:div.skeleton]}})
;; then, at any site, dynamic head included:
[skeleton-slot {} [:> Dyn {…}]]
```

`mint-host!` accepts any non-nil component; the wrapper's gate renders the declared fallback
while unadopted and the children after adoption. Policy stays on a declaration, which is the
whole argument. **Say precisely what it buys**: a *placeholder*, not server-rendered content.
Server-rendered content under a crossing is `rf2-l0wfx`'s question (§7).

---

## 3. Edge 2 — intent carriers in declaration-less props

**Ruled: `[:>]` props take the landed unclaimed-slot conduct of `host-entry` with an empty
declared map — exactly, and with no branch of its own.**

| Written at a `[:>]` prop | Behaviour |
|---|---|
| intent vector or key-map at an **event-spelled** slot | **loud refusal** — no contract is ever inferred from an `on*` name |
| intent vector or key-map at any other slot | data — `clj->js`, the position's own conversion |
| marked `h/fn`, any slot | crosses **by identity**; it runs, and its return is ignored |
| plain `fn` | crosses by identity — memo bail-outs keep working |
| `:ref` | `check-ref!` on the canonical slot — callback ref through, vector refused |
| `:&` | `merge-caller` **before** conversion, per HD-023(d) |
| `:key` | extracted onto the gate's outer element, never an attribute |

*(7 rows, 2 columns.)*

The refusal's message must **not** copy the door's "or hand a function", because at a
crossing with no declaration an `h/fn`'s returned intent does not dispatch. It names
`defhost`'s `:callbacks` as the door and states the `h/fn` return fact pre-emptively, so the
author who tries the taught spelling first is told the whole story before they can fall into
the other half of it.

### The rejected reading, and why C falls with it

Designs C and migration both rule the opposite: **the prop's spelling selects the contract**,
so an intent vector at `:on-change` lowers and an `h/fn` at a non-event slot takes the render
contract. C calls this its "single most consequential open question" and asks the reviews to
attack it directly. **Two independent attacks did attack it — in migration's spelling — and
one rated it FATAL.** Neither was run against C. Running them:

1. **`event-callback` returns `nil` to its invoker unconditionally.** So an *event-spelled
   render prop* — Fluent UI's `onRenderCell`, `onRenderRow`, `onRenderItemColumn`, all
   matching `^on[A-Z]` — hands the library `nil` and renders a **silently blank region**, in
   production and in dev alike. **C concedes this case in its own §use-case-3** and calls it
   "the one shape the escape genuinely gets wrong". C's showcase for the mechanism is
   `renderRow`, which is the spelling that survives; the ecosystem spelling that does not
   survive is the one C concedes.
2. **Return-reading props lose their answer.** Ant Design's `onRow`/`onCell` are
   `(record) → props-map` factories whose return the library spreads; cancel patterns
   (`onBeforeClose → false`) read the return as a decision. The wrapper's unconditional `nil`
   silently deletes both.
3. **A charge neither attack made, because neither was aimed at C.** C routes an `h/fn` at
   a *non-event* slot to the **render** contract, which mints a fresh wrapper per lowering.
   That destroys the function identity `host-prop-value` preserves deliberately — HD-024's
   own Rationale says so in terms: the codec passes functions to React by identity
   *"deliberately, so that `React.memo` and every downstream bail-out that compares handler
   identity keep working"*. C never prices this, and it is a cost paid on every render of
   every crossing that carries one.

The escape becoming *more* magical than the door also inverts "explicitly secondary", and
makes the system's answer to "is `on*` a contract?" positional — no at `defhost`, yes at
`[:>]` — a two-sentence rule where the record wants one.

**What survives from C and migration on this edge:** the position table stands as the
**codemod's static-analysis vocabulary** — a report over collected sites — never as runtime
`[:>]` semantics. That is `rf2-2rtt6.106`'s input, and it is the correct home for it.

### The decisive argument, which only one document states in full

Design A and the local minimal design both take the other fork — **refuse a marked `h/fn` at
a `[:>]` prop**, since the marker requests a contract the position cannot give. It is a
serious position: it is the only stance with no silent cell, and A prices it honestly. It is
rejected here on a consequence neither of them considers and only the attack on the migration
design states:

> **If the escape refuses what the door accepts, `[:> X …] → (defhost x X {})` is no longer
> behaviour-preserving — and that rewrite is the whole theorem of `rf2-2rtt6.106`'s codemod.**

The hoist is mechanical precisely because both spellings run the *same walk*. A
`[:>]`-only refusal forks that walk, and the fork lands on the one transformation the
migration story is built on. A refusal that is right in isolation and wrong in composition is
wrong. **Whatever is ruled, the escape does what the door does.**

### The diagnostic, pinned to the door rather than forked from it

The residue is real: a marked `h/fn` whose returned intent dies silently, on the taught path
— the guide teaches `h/fn` *specifically* for value-first invokers. `rf2-2rtt6.116` files
exactly this, **true on `main` today with no `[:>]` anywhere**, and `rf2-2rtt6.117` asks
whether the door gets the same diagnostic the escape would. This spec therefore does not pick
a diagnostic; it pins one:

**The escape's conduct at an unclaimed prop is whatever `rf2-2rtt6.116` rules for the door,
and its diagnostic is whatever `rf2-2rtt6.117` rules for the door.** Refuse at both, warn at
both, or leave both — in all three branches the zero-fork invariant holds and no line of the
escape's mechanism changes. This is a departure from the bead's 12:06 comment, which ships a
**dev-only warning at `[:>]` only** while the same programme files `.117` asking whether that
fork should exist. A fork at the one edge whose entire argument is zero-fork is the wrong
default; deferring to the door costs nothing and cannot be wrong in any branch.

> **Answered 2026-08-05 (`rf2-2rtt6.117`) — no, and the question inverted before it could be
> put.** `.117` was spun out as a *parity* question: the escape had just been given a dev-only
> warning, so does the door get the same one? The pin above retracts that grant. There is now
> no warning at `[:>]` to be at parity with — `[:>]` is unbuilt, and by this section's ruling
> it will never carry a diagnostic the door lacks — so the answer to the question as asked is
> **no**, on the premise rather than on the merits. The second horn the bead offers ("or is the
> door's declaration story sufficient signal?") is not the reason either; it was never reached.
>
> What survives of `.117` is not a second decision. It is the **warn** arm of the one
> three-way ruling §7 R1 states as *refuse, warn, or leave* — the ruling `rf2-2rtt6.116`
> escalates, on the ground that it turns a shipped accept at a declared door into something
> else. That is the operator's, and nothing here anticipates it: if the door refuses, a warning
> is moot; if the door leaves the crossing alone, warn-versus-leave is live and is still one
> question, not two. Whichever way it goes, the escape does what the door does and no line of
> the mechanism above changes.

---

## 4. Edge 3 — what "reduced structural identity" concretely means

**Ruled: four statements, ordered from the surface that exists today to the one that does
not.** All four are assertable except the last, which is a recorded forward obligation.

**1. Canonical DOM is not reduced at all.** A `[:>]` and a `defhost` on the same component
with the same props produce the **same DOM in every phase** — nothing before adoption, the
component's own DOM after — because the gate contributes no node of its own. This is provable
rather than asserted: `lane/canonical` serialises element and text nodes and ignores
comments, so a nil-rendering gate is invisible to it. It is the strongest and most useful
thing the spec can say, and it gets the load-bearing witness.

**2. The hiccup data lane is reduced at exactly one slot.** `[:> C {…} …]` is an ordinary
vector: `:>` is a plain keyword, `=` compares it structurally, and the reduction is that slot
1 holds a JS value compared by **identity**. `(= [:> C {:a 1}] [:> C {:a 1}])` is true; a
different component is false. Total everywhere else in the vector. **That is the whole of the
ruled phrase**, and all six designs converge on it.

**3. The fiber lane carries one shared gate type with a constant name, and extracts
nothing.** `displayName` is the literal `"[:>]"`. **No name is derived from the component**,
and the reason is verified and decisive: React 19.2 resolves a type's name as
`displayName || name || null`, Closure renames `.name` under `:advanced`, and foreign
production bundles routinely ship without `displayName` — so any derived identity is
*build-dependent*. `defhost` has no such problem because its name is **authored data**, a
string captured by the macro and written as a string-keyed property that Closure does not
rename. That contrast **is** the design: the door is tooling-identified because its identity
is authored; the escape extracts nothing because everything it could extract lies under
minification.

Deriving is also redundant. The foreign component's own fiber sits directly beneath the gate
and React names it there, so `defhost` reads `<my.ns/date-picker>` → `<DatePicker>` and the
escape reads `<[:>]>` → `<DatePicker>`: one greppable frame naming *the form the author
wrote*, and the component naming itself one level down, at zero cost.

Design B is the sole dissent, proposing `[:> Foo]` — the spelling wrapped around the
component's own name. **Rejected** on the minification fact above: it reads `[:> Ab]` or
`[:> ?]` depending on the build, and a build-dependent name looks authored and is not. B's
own reason for wanting it — that naming the gate `Foo` alone would hand the escape the door's
tooling identity — is served by the constant equally well and without the defect. A name
*resolver* for refusal prose only is permitted (§5); its output is diagnostic text, never
asserted on and never serialised into a golden.

**4. The forward obligation, recorded and not built.** Design A alone establishes the two
facts that reframe this edge, and both come from the guide's own testing chapter: Hicasso's
headless structural render **does not exist** (`08-testing.md:16` calls it a sketch — "nothing
in the tree implements this yet"), and **the foreign region is already out of its scope for
both forms** (`08-testing.md:44`). So what `defhost` buys structurally is the crossing node's
identity, not visibility into what the component renders — *neither form gives you that*. The
cost today is therefore close to zero, and saying so honestly matters more than sounding
careful. When that render lands, a `defhost` crossing has a declared name to project and a
`[:>]` node has none. **The projection must be a host node with an absent identity, never an
omitted node** — `spec/004B` already records why the second is dangerous: a host node falling
through to the fragment arm answers `{}`, "a total, harmless-looking, wrong answer, delivered
silently because the fragment arm is documented total rather than an error."

**And the loss a person actually meets first is none of the above.** Design C's contribution,
kept because it is the practical answer: it is the **`.cljc` quarantine**. `defhost` puts the
JS require in one host namespace; `[:>]` names the component at the call site, so the require
lands in the view namespace and that namespace stops loading on the JVM. The symptom is not
"my structural test cannot match a node" — it is "my test namespace will not load."

---

## 5. Edges 4 and 5 — the value boundary, and children

### Edge 4 — the legal Component value

**Ruled: a pinned roster, refused AT THE CROSSING in the owner's render.**

The accepted set is exactly what React 19.2's reconciler mints a fiber for: functions
(function and class components alike), the built-in `Symbol.for` exotics (`Fragment`,
`Suspense`, `StrictMode`, `Profiler`, `SuspenseList`, `Activity`), and objects whose
`$$typeof` is one of context, consumer, `forwardRef`, `memo` or `lazy` — **minus two
deliberate narrowings**, `nil` and strings.

**Why refuse at the crossing rather than let React refuse.** This is the edge where the
attacks changed a design's answer, and the evidence is one-directional. React's own
"Element type is invalid" is minted at **fiber creation**, and behind the adoption gate that
moment is **post-adoption, client-only** — never on the server, never at first paint, and
topped by the gate rather than by the author's line. Worse, for a ClojureScript value the
message reads *"got: object"*, because it names `typeof type`: a keyword, map, vector, set or
record all arrive as "object", naming **nothing the author wrote**. Refusing at `as-element`
time in the owner's render, server included, with the author's stack, is strictly better on
every axis. Designs B, C and the parity design all delegate to React; all three are wrong
here, and parity's own stated principle — *"the refusals we DO add are the ones React cannot
give a good error for"* — selects the roster by its own test.

The roster's one real risk is that it is a replica: a React upgrade could move the boundary
and the roster would start false-refusing a legal component, which is the worst failure a
hatch can have. **The mitigation is the load-bearing part of this edge and is not optional:**
a *pinning witness* that mounts one value of every accepted category and asserts that the
refused set is exactly what React throws on, so drift turns a row red instead of shipping
silently.

**Strings are refused, on donor evidence.** Only the migration design accepts them, and it
loses on its own fidelity bar: Reagent's `[:> "input" …]` takes the **controlled-input
wrapper** — `input-component?` matches the strings `"input"` and `"textarea"` on exactly the
`:>` path — so accepting strings would silently drop caret protection at a site a migrator
ports verbatim. The grammar owns tags; a dynamic tag is a computed **keyword** head, which
keeps the parse and the controlled door. Five of six designs refuse strings.

**Refusal messages, one per confusion**, each naming the value and the spelling to use
instead: `nil` gets the door's own broken-import diagnosis (`:default` against a library with
no default export); a string or keyword is told to write the tag; a Hicasso **view** head is
told `[my-view …]`; a `defhost` head is told `[my-host …]`; a React **element** is told an
element is a legal child, not a type. Two error ids carry them:
`:rf.error/hicasso-raw-no-component` for the nil/absent case and
`:rf.error/hicasso-raw-not-a-component` for the rest, with the discriminating reason in the
message and the shape in `ex-data`.

`forwardRef` needs no special handling: HD-016's callback-refs-only rule is enforced by
`check-ref!` at the crossing exactly as at a `defhost`, and React 19 carries `ref` as an
ordinary prop through the gate. Clause 3 is satisfied by reuse, not by new code.

### Edge 5 — children lowering and frame capture

**Ruled: nothing new. Children lower eagerly at the crossing, inside the writing boundary's
render window, through the same `make-element` arms.**

`make-element` calls `as-element` on each trailing form **synchronously, inside the writing
body's render**, where `*frame*` and `*dispatch*` are bound — so a child's intent closure
captures the owner's frame-locked dispatch at lowering time and fires into the right frame
however much later the foreign component renders it: a portal, a virtualised window, a
`Suspense` fallback. A `[:>]` written outside any boundary raises the existing
`:rf.error/hicasso-intent-outside-boundary` at its first intent. A Hicasso boundary *beneath*
a crossing gets its frame from React context, which ignores the JS call stack, so a foreign
component in the middle is transparent — already witnessed for the door.

**This edge closes with zero new code and it is unanimous across all six designs.**

**Render-prop *props* on the raw node are out of scope**; render-prop-**supplied** components
are in scope. A `[:>]` built inside a declared `:render` callback composes with
`rf2-2rtt6.74`'s arming gate by construction, because the gate is installed by the
declaration above it. A function prop *on the crossing itself* gets no wrapper, so a body
that lowers an intent does so with no ambient frame and raises
`:rf.error/hicasso-intent-outside-boundary` at the library's call — loud, never silently
inert, and late.

**And that diagnostic is currently written for the wrong reading.** Design A's finding, and
the single cheapest thing on this page that the implementation must not ship without:
`:rf.error/hicasso-intent-outside-boundary` says event vectors are only legal inside a
boundary's render, with recovery `:lower-intents-inside-a-boundary-render`. **At `[:>]` the
author *is* inside a boundary's render as far as they can see** — they wrote the crossing in a
body — so the message will read as a framework bug. The runtime cannot tell the two causes
apart from `*dispatch*` = `nil` alone. **The message must offer both readings and name
`defhost` with `:callbacks {… :render}` as the second.** Nothing in the local set has this,
and the bead's 12:06 comment does not carry it.

### Are edges 2 and 5 one decision?

Design C argues they are: if `[:>]` cannot give a prop a contract, there is no render
contract at the crossing and edge 5's frame-capture story is false. **Adjudicated: one and a
half, and the halves must be stated separately.**

- **Edge 5's children half is invariant under every candidate answer to edge 2.** Edge 2
  governs *props on the `[:>]` node*; children carry their own positions inside hiccup the
  author owns, and every one of the six designs states the children rule identically
  regardless of its E2. The spec therefore states it independently, and it is safe to build
  before edge 2 is finally ruled.
- **Edge 5's render-prop-prop half is wholly determined by edge 2**, and C is right about it.
  Under the no-contract ruling it collapses to a refusal plus a diagnostic — which is exactly
  why the message repair above stops being a nicety and becomes the edge's deliverable.

C's claim is therefore correct about the half it cares about and overstated as a claim about
edge 5 entire. Treating it as one decision would have made the children rule hostage to
`rf2-2rtt6.116`; treating them as five independent answers would have shipped C's
frame-capture prose under a contract that does not exist.

---

## 6. What this page attacked that nothing had

The adversarial wave ran on the local three only. These are aimed at the tracked three, at
the 12:06 comment, and at the material as a whole.

**(a) The head-test cost is net zero, and the constraint stated as unsatisfiable is exactly
satisfiable.** Design B rules that "the escape's head test must cost nothing for a head that
is neither `:<>` nor `:>`", and then offers no mechanism that meets it, falling back on "or
measure the walk and publish the delta". It is meetable, and the reason is a redundancy
nobody in ten documents noticed:

> `hiccup-tag?` has **exactly one caller** — `vec->element`'s `cond` — and it sits *after*
> the `fragment-head?` arm. Its body nonetheless re-asks `(not (fragment-head? head))`. **So
> every native tag on every page pays the fragment `=` twice today.**

Drop the redundant test (provably dead, given the single caller and the cond order) and add
`raw-head?` as a cond arm before `hiccup-tag?`. The accounting is exact: today a keyword tag
pays `fragment-head?`, one type predicate, and `fragment-head?` again — two `=` and one
predicate. After, it pays `fragment-head?`, `raw-head?`, and one type predicate — **two `=`
and one predicate.** Identical. This matters because this codec has costed a `keyword?`
short-circuit at ±51–67% of a walk, so "it is only one equality check" is not an argument on
this surface — and now it does not have to be.

**(b) Design B's edge-1 mechanism and edge-4 table are jointly unsound** — §2, verified on
React 19.2.0 / Node v24.13.0.

**(c) Design C's edge 2 falls to the two attacks that killed migration's, plus a charge
neither made** — §3, the per-lowering wrapper destroying memo-bail-out identity.

**(d) The 12:06 comment ships a fork at the one edge whose argument is zero-fork**, while
the same programme files `rf2-2rtt6.117` asking whether that fork should exist — §3.

**(e) `h/as-element` was named as the recovery for the two sharpest silent traps at this
crossing, and it does not exist in the taught surface.** Designs A (hiccup in a prop), C
(diagnostics rows 8 and 9, *and its entire render-prop worked example*), B (an edge-5
witness) and the parity design (E5) all named it. Verified: **zero occurrences of
`as-element` anywhere under `docs/design/hicasso/draft-guide/`**; the guide's taught `h/`
roster is `h/hicasso`, `h/root`, `h/fn`, `h/render`, `h/defhost`, `h/presence`,
`h/child-key` and `h/boundary`; `arm1/lang.clj` exports three macros — `defview`, `hfn`,
`defhost` — and no `as-element`. The *mechanism* is public as `codec/as-element`; the
*taught name* is not. **An error message naming a spelling the reader cannot call is worse
than no message.** Either the arm exports the taught spelling in the same PR — one line — or
every refusal message and guide row names what the arm actually exposes. Filed as
`rf2-2rtt6.120`; the escape's PR must not be where this is discovered.

> **Where it came from, and what has been done about it (2026-08-05, `rf2-2rtt6.120`).**
> Four independent designs converging on a function that does not exist is not four
> coincidences. The source is `front/intent/render-callback`'s **own docstring**, which
> illustrated the row a `renderRow` prop exists to build with
> `(h/as-element [:li {:on-click [:row/pick (:id row)]} (:title row)])` — so a designer
> reading the function to understand the crossing reasonably concluded the spelling
> existed. **The implementation named a nonexistent function to explain itself**, and every
> downstream document inherited it. Nothing gates the prose in a docstring against what its
> module exports, which is how this reached four design records and one guide page before
> anyone checked.
>
> Since corrected: the docstring (this PR), the guide's `05-interop.md` (PR #7523, which
> also removed a `:render` hiccup return the page called "fine" — `render-callback` ends in
> a bare `(apply f args)`, so the return crosses **unconverted** and a vector is refused by
> React), and A, B, C and `decisions.md` above (this PR). **The arm ruling this clause asks
> for — export a spelling, or rule a different one — is still unmade**, so the records now
> name the gap rather than a function; none of them invents a replacement. The parity
> design is local-only and outside the tracked tree.

**(f) The 12:06 comment drops two load-bearing carries**, both restored above: the X2
body-run restatement (§8) and the `intent-outside-boundary` message repair (§5). A dispatcher
briefing from that comment alone would ship without either.

---

## 7. Rulings this spec depends on — the operator's, not this page's

Stated with the recommendation and its cost. **None is decided here.** In every case the
spec is written so that no line of the escape's mechanism changes with the answer.

**R1 — `h/fn` at an unclaimed prop slot: refuse, warn, or leave? (`rf2-2rtt6.116`, P2; the
door's diagnostic is `rf2-2rtt6.117`, P3.)** True on `main` today with no `[:>]` anywhere: a
marked callback nothing reads. *Recommendation:* rule it for the **door**, and let the escape
inherit — §3's pin means all three answers preserve the zero-fork invariant and the codemod's
hoist theorem. *Cost of refusing:* it chips HD-024's cleanest claim, that the marked form is
callable everywhere and degrades to "a defensible contract rather than a crash". *Cost of
leaving:* a silent dead return on the guide's own taught path for value-first invokers.
*Cost of warning:* a diagnostic surface the record has to justify against the anti-nag
posture. **The escape works under all three.**

**R2 — `defhost`'s `:ssr` has no value that renders children (`rf2-l0wfx`, P1).**
`mint-host-gate!` returns a single pre-walked placeholder when unadopted and never consults
`props.children`, so an unadopted crossing **drops its subtree**. For a leaf widget that is
right; for a **provider** — one of HD-011's five named use cases, and the guide's own example
at `05-interop.md:120` — it deletes the application from the server response. *Recommendation
(Design C's):* a third `:ssr` value meaning "render the children in place of the component",
because it is the honest reading of what a transparent wrapper is, it costs one row in a
table that exists, and without it the provider use case has no recovery at all. *A second
route, from the attack on the local minimal design:* a **Context-head carve-out** — a React 19
Context is detectable at the crossing by `$$typeof`, and its server render is React's own
context machinery, deterministic and with no `window` risk, so the "nobody can vouch for the
server render" defence is **false for providers specifically**. *Cost of neither:* providers
are ruled out of SSR and the guide must say so. **This is the door's question and the escape
only inherits the answer — the escape must never grow an `:ssr` spelling of its own.**

**R3 — refusing a Hicasso view or host head in Component position exceeds clause 5's
letter.** Both are values React accepts. *Recommendation:* refuse. A `defview` head is
`fn?`-true, so a bare "is it a function" test accepts `[:> my-view …]` and mounts the shell
raw — the body reads `rfProps`, gets `undefined`, and receives nil props. That is
silent breakage, and it is the kind a migration produces. *Cost:* two own-property reads per
crossing render, and a refusal of something React would have accepted. **Removable without
touching the mechanism** — it is a predicate and two message flavours.

**R4 — `rf2-2rtt6.115`'s record correction must land before or with the escape.** Three
independent adjudications concur that the **code** side is authoritative: HD-024's row 3 at
`decisions.md:1015` over-records, and the door's unclaimed-slot identity crossing was
deliberate. The repair is a dated scope-note on row 3 plus the currently-missing witness
pinning a *marked* `h/fn` at an unclaimed door slot (today only a *plain* fn is pinned). Edge
2 rests on that resolution; it is `decisions.md`, outside this page's fence, and it is named
here so the implementation PR carries it rather than discovering it.

---

## 8. What the implementer must carry

Six facts that will bite, each verified against `main` on 2026-08-04.

**1. `:>` is not currently an error.** `hiccup-tag?` (`codec.cljs:1673-1675`) accepts any
keyword that is not `:<>`, and `vec->element` (`:1689`) routes it to `native-element` — so
`[:> Foo {}]` today asks React for an element literally named `<>`. The new branch must
**precede** `hiccup-tag?` and compare with **`=`, never `identical?`**, for the reason
`fragment-head?`'s own docstring gives: keyword literals are shared constants only when the
build interns them, so an identity test works under `:advanced` and silently routes every
escape into the native path everywhere else. **A branch placed after `hiccup-tag?` is dead
code that compiles cleanly** — the worst shape available.

**2. The head test costs nothing** if the redundant second `fragment-head?` is removed at the
same time — §6(a). If the implementer cannot get the accounting to come out at parity, the
fallback is not "it is only one `=`": it is to measure the walk on the census page's child
roster before and after and publish the delta in the PR body.

**3. X2's body-run equality is deliberately broken by any `:client-only` crossing.**
`arm1/hydrate_dom_cljs_test.cljs:414` asserts `(= boundary-count (rt/body-runs))`, read after
adoption resolves. It is green today **only** because no page it drives carries a client-only
crossing, and it goes **red on correct code** the moment the first one is added — via `[:>]`
**or** via a `defhost` `:ssr :client-only`, so this is not specific to the escape. The two
obvious readings when it fires — "my change is wrong" and "this test is wrong" — are **both
wrong**: the count is *supposed* to move, because a client-only crossing legitimately runs a
body the server never ran. **Do not delete the assertion and do not loosen it to a range.**
Restate it as boundary count **plus** the number of client-only crossings on the page, so it
still fails if a body runs twice or a boundary is skipped.

**4. The argv indices shift by one.** The component is at 1; the attribute map is at 2 when
`(map? (nth argv 2 nil))`; children run from 3, or from 2 when there is no attribute map.
`[:> Component]` with no props and no children is legal. Bare `[:>]` reaches index 1 as
`nil` and takes the no-component refusal, whose message covers both misspellings.

**5. `check-ref!` refuses only a vector**, so an object ref passes.
**Do not add a refusal at `[:>]` that `defhost` does not have** — a rule enforced at one
crossing and not the other is worse than a rule enforced at neither, and conversion parity is
the ruling.

> **Settled 2026-08-05 (`rf2-d03av`), and it settles the way this paragraph wanted.** The
> rule turned out to be narrower than it read, not the check: HD-022 rules that the whole of
> the `:ref` value-space claim is *"one refusal branch and one error id"*, so an object ref is
> **untaught rather than illegal** at every position. HD-016 carries a dated note saying so
> and the guide's interop page teaches the distinction. Nothing changes at `[:>]`, and parity
> holds for free — which is exactly the outcome this item asked for. Pinned by
> `front/codec_cljs_test` → `an-object-ref-crosses-by-identity-at-both-positions`.

**6. `host-entry` has no class branch**, so `{:class ["btn" nil "on"]}` at any foreign
crossing crosses as a `clj->js` array and React renders `class="btn,,on"` — while the codec's
own slot law says the class slot is a position coerced by `class-names`. This is the door's
deviation, **inherited, not introduced**, and it is filed as `rf2-2rtt6.119`. Note the trap
for the witness plan: **edge 3's canonical-parity row cannot catch it**, because both forms
are identically wrong.

> **Fixed 2026-08-05 (`rf2-2rtt6.119`) — at the DOOR, so the escape inherits it.** `host-entry`
> now carries a `class?` arm that coerces and composes through `class-names`, exactly as
> `convert-entry` does at a native tag, and it sits **below** the declared-contract arm so a
> declaration still decides what a declared position means. Because §3 rules that `[:>]` props
> take *"the landed unclaimed-slot conduct of `host-entry` … with no branch of its own"*, the
> escape gets the repair by construction and the parity trap above never arms. The observation
> that edge 3's canonical-parity row cannot catch it stands and is the reason the witness is
> written as *native answer* vs *crossing answer* rather than as two spellings of the crossing.

---

## 9. Witnesses the implementation owes

The bead's own list stands. These are the rows whose absence would let a defect ship, and
each names what would make it vacuous.

| Row | Vacuous if |
|---|---|
| **Canonical-DOM parity** — same component, same props, via `[:>]` and via `defhost`, byte-equal in both phases | the fixture component renders nothing, or the two render indistinguishable subtrees |
| **SSR absent + hydration first pass** — absent from the server bytes, zero complaints across the whole adoption, present after adoption | the capture window closes when `hydrateRoot` returns; it must open before `hydrate-root!` and close after `adopted!`, and the mutation proof is hydrating against bytes that *do* contain the markup |
| **The E4 pinning roster** — one accept row per accepted category, one refusal row per named confusion | a refusal row asserts only "it threw"; assert the `:rf.error/id` **and** the discriminating reason |
| **Conversion parity with the door** on one prop corpus, prop-for-prop | read as the proof rather than as a regression detector — one walk serves both, so parity is an identity, and the row is a tripwire against a future fork |
| **Children capture on a two-frame page** — a child intent dispatches into the writing boundary's frame and **not** the other | the page has one frame; it cannot distinguish "the right frame" from "any frame" |
| **`:&` at the crossing, `:key` extracted, callback ref through, vector ref refused** | asserted on the hiccup rather than on what the foreign component received |
| **The carrier never leaks** — the foreign component's received props carry neither internal slot | asserted on the gate's props rather than the component's |
| **The `:>` mis-parse regression** — `[:> Foo {}]` no longer asks React for `<>` | — |

*(8 rows, 2 columns.)*

---

## 10. Sources

- The bead `rf2-2rtt6.103`, read bottom-up — its six description clauses, the 11:04
  design-phase comment naming the five edges, the booby-trap and six-designs notes, and the
  **12:06 synthesis comment**, which is the newest item on it.
- The three tracked designs: [A](raw-escape-design-a-minimal.md),
  [B](raw-escape-design-b-correctness.md), [C](raw-escape-design-c-ergonomics.md).
- The six local-only documents listed in §0, quoted rather than cited wherever load-bearing.
- [decisions.md](../decisions.md) — HD-011 and its `:ssr` addendum, HD-016, HD-020(b),
  HD-023(d), HD-024 and its Rationale.
- [The SSR spike witness](ssr-spike-witness.md) — X1–X5 and X2's zero-mismatch obligation.
- `implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs` — the snapshot
  triple and `mint-host-gate!`, `host-entry`, `check-ref!`, `hiccup-tag?`, `vec->element`,
  `as-element`. **Cited by name, not by line.** This bullet carried line numbers until
  2026-08-05 and three of the five were wrong by then, including the `as-element` citation
  that clause (e) turns on; the file moves faster than a record of it can be trued up.
- `arm1/hydrate_dom_cljs_test.cljs:414`; `arm1/lang.clj`; `docs/design/hicasso/draft-guide/`.
- Beads: `rf2-2rtt6.106`, `.109`, `.115`, `.116`, `.117`, `.118`, `.119`, `.120`,
  `rf2-l0wfx`, `rf2-d03av`, `rf2-vrvv9`.
