# Raw escape, design B — derived backwards from the obligations

**Design only. No implementation is proposed here as code, and nothing in this
page is ruled.** It is one of three independent designs for `rf2-2rtt6.103`'s
five open edges, written 2026-08-04 21:33 AUSEST against `origin/main` at
`22c6a769ea`. Three adversarial reviews follow, then a synthesized spec on the
bead, then implementation. HD-011's core lowering is already ruled and is not
reopened below.

The lens this design was written through: **start from what must not break,
enumerate it, and then design the escape that cannot break it.** Where a
guarantee can only be held by refusing, the design refuses. Where a failure can
be made unrepresentable rather than merely tested against, it is — and where it
cannot, the page says so in those words rather than proposing a test and moving
on.

## What is already ruled

Six clauses, read off the bead, restated here as the fixed frame this design
works inside rather than as anything it decides:

1. `[:> Component props & children]` is legal and lowers through **the same
   foreign path as `defhost`**, with the same default conversions — shallow
   camelCase props, hiccup children → elements, functions pass through.
   `.cljs`-only at that node; **reduced structural identity** is the honest cost
   (HD-011, [`decisions.md`](../decisions.md) ~:412–421).
2. `defhost` remains the taught, policy-bearing, tooling-identified form. Bare-head
   auto-hosting stays rejected.
3. HD-016: `:ref` is legal at the crossing, **callback refs only** (~:513).
4. The `:&` law holds at the crossing, merged **before** conversion (HD-023
   clause (d), ~:914–918).
5. A non-component value in Component position **refuses loudly**; unknown or
   invalid shapes never silently render.
6. There is no declaration to carry an `:ssr` policy, so server-side the crossing
   **renders nothing**.

## The obligation ledger

Everything below is derived from this table. Each row names what must not break,
where it is recorded, and the shape of the change that would break it — because
naming the breaking shape is what lets a design forbid it structurally instead of
testing for it afterwards.

| # | Obligation | Recorded in | The change that breaks it |
|---|---|---|---|
| **O1** | Server tree and the client's **hydration first pass** are the same tree — zero recoverable errors, every node still the server's own | [`ssr-spike-witness.md`](ssr-spike-witness.md) X2; `rf2-2rtt6.87` | any environment predicate (`server?`, `node?`, `js/window`) on the crossing's lowering path |
| **O2** | The ≤2-hook **shell** budget | HD-020(b) | a hook added to a boundary shell — the gate is not a shell, and must stay not one |
| **O3** | The ordinary render path byte-for-byte unchanged: no extra fiber, no extra passive effect, nothing new in `retained-inventory` | `arm1/mount.cljs` `tree` docstring; `rf2-2rtt6.94`'s close | a per-element cost paid by elements that are not crossings |
| **O4** | `:&` merged **before** conversion, one law at every position | HD-023(d) | a second merge site, or a merge after conversion |
| **O5** | `:ref` at the crossing is a callback ref; a vector is refused | HD-016, HD-022 | a `:ref` path that does not run `check-ref!` |
| **O6** | The adoption window and presence's born-present behaviour, honoured identically by the server render and the client's | `rf2-2rtt6.84`, `rf2-2rtt6.94` | content rendered during the window that the server did not render |
| **O7** | No fail-open instrument: a witness a producer cannot make red is not a witness | this week's twenty-four; `rf2-2rtt6.91`; `rf2-2rtt6.97` | a canned value or a fixture asserting what the real path cannot emit |

Two instrument limits constrain what any argument here may rest on. **`:rf/render-hash`
is degenerate for an interpreted root** (`rf2-2rtt6.91`): the dogfood screen and a
~1,200-element Conduit feed both hash `83b865f8`, so no hydration claim in this design
leans on it, and none below does. And **a console capture whose window shuts when
`hydrateRoot` returns is shut across the only part that can complain** — the SSR spike
found three rows vacuous for exactly that reason and repaired them; every witness
proposed here inherits the repaired window.

**Addendum, 2026-08-05 — `rf2-2rtt6.91` has closed (PR #7510); the first instrument
limit is now a stronger one, not a weaker one.** The paragraph above stands and its
figure still reproduces: the witness rows that chose byte digests were rewired to
take the hash from `ssr-hash/render-tree-hash` directly, so `83b865f8` remains
measured. Two corrections. The limit is no longer "the value is degenerate for an
interpreted root" but **"this tier carries no such value"** — Spec 011 tiers
hydration-mismatch detection by render-tree representation rather than by adapter
brand, and a root React adopts (a compiled `re-frame.ui` root, a native UIx root, or
a Freehand root) now emits no `:rf/render-hash` and stamps no marker at either end.
And `83b865f8` was never a fact about the dogfood screen or the Conduit feed: it is
the FNV-1a-32 of the canonical EDN `[#fn[] {}]`, so **any** unresolved `[<fn> {}]`
root takes it. This *strengthens* both places the fact is used — O7's fail-open
obligation, and the §"blind" row below, where the middle column's blindness is
attributed to canonical EDN rendering every function identically. That attribution
was, and remains, the correct one; it is the whole mechanism.

## The thesis: the escape is a head-minting question

Every obligation in the ledger is **already held** by code that exists:
`host-element` holds O4 and O5, `mint-host-gate!` holds O1 and O6, and the fact
that neither is a boundary holds O2. The escape stands next to all of them.

So the design is not "how does `[:>]` lower" — that question is answered by
HD-011 and by the code. It is: **where does the head come from when nobody
declared one?**

> `[:> Component props & children]` builds the *same head shape* `mint-host!`
> builds — component, gate, callbacks, ssr, displayName — with an **empty
> callbacks map** and the **`:client-only`** policy, and hands it to the
> **same `host-element`**. Nothing downstream of the head is new.

This is not a stylistic preference; it is the load-bearing correctness move.
The bead proposes witnessing conversion parity with `defhost` "same fixtures,
asserted equal". A paired fixture detects drift **after** it happens. Sharing
the function means there is no second implementation to drift from: **conversion
parity stops being a claim and becomes an identity.** Keep the paired fixture
anyway — as a tripwire against a future refactor that forks the path — but state
in the PR that it is a regression detector and not the proof, or the witness will
be read as carrying more than it does.

Three things follow immediately and are worth stating because a design that did
*not* share the path would have to re-derive each of them and could get any of
them wrong:

- **O4 is free.** `host-element` calls `merge-caller` once, on the attr map,
  before a single prop is converted. There is no second merge site to add,
  because the escape adds no site.
- **O5 is free.** `host-entry` routes the canonical `ref` slot through
  `check-ref!` — on the *slot*, not on the key `:ref`, so `"ref"` and `:x/ref`
  are covered too. The escape inherits that, including HD-022's vector refusal.
- **`:key` behaviour is free.** It is extracted off the attr map and set on the
  gate's element, never reaching the component.

## Where the head comes from: gate identity

`mint-host-gate!` mints one gate **per declaration**, at declaration time. The
escape has no declaration time. So the gate must be obtained at lowering time,
and the question that decides the design is: **from what, and how stably?**

Stability is not a nicety. React compares element types by identity. A gate minted
fresh per render is a fresh element *type* every render, and React unmounts and
remounts the whole subtree beneath it — the exact hazard `memoize-boundary!`'s
docstring already names for boundary memo wrappers. A video that restarts on every
render, an input that loses focus on every keystroke. This is the single most
consequential decision on the page.

### Decision

**A module-level `WeakMap` from component value to gate, minted lazily through
`mint-host-gate!` with a `nil` fallback.** The escape's gate is therefore the
declared door's gate, produced by the same function, differing only in that it can
never carry a placeholder.

Four properties, in the order they matter:

1. **The head shape is identical to `mint-host!`'s**, so `host-element` is reused
   without a parameter, a branch or a second props contract. This is what makes
   the thesis above true rather than approximately true.
2. **Gate stability equals component stability** — which is the honest ceiling.
   `[:>]` cannot be more stable than the value the author hands it; an author who
   writes `[:> (react/memo Foo) …]` inside a body has already told React to remount,
   gate or no gate. The design does not paper over that, and could not.
3. **Steady-state cost is one `WeakMap` lookup per crossing**, on a path that is
   already off the ordinary one. No allocation after a component's first sighting.
4. **It does not mutate the author's value.** An expando on the component would
   have been cheaper still and is rejected: it writes into an object a foreign
   library owns, and it fails outright on a frozen or proxied component.

The `WeakMap` belongs under `retained-inventory`'s `:shared`, alongside the codec's
tag and prop caches, worded as *one gate per distinct component value ever crossed*.
It is not a per-boundary token and adds no row to `:per-boundary`. Entries collect
with their components: ECMA-262 specifies `WeakMap` with ephemeron semantics, so the
gate's closure over the component — value referencing key — does not retain the entry.
That sentence is the one an adversarial reviewer should check; it is a claim about
the language, and it is false on a hand-rolled `WeakMap` shim, which is not on our
targets.

### The alternative that was priced and rejected

**One universal gate for the whole program**, taking the component through its own
props. It has a genuinely attractive property — the gate is a module constant, so
gate-identity churn becomes *unrepresentable* rather than author-dependent, which is
exactly the move this design otherwise prefers.

It is rejected because the property is illusory and the cost is real. Illusory:
when the author's component value churns, (D) does not prevent a remount, it moves
it one fiber down — the inner `createElement` sees a new type and React unmounts the
inner subtree. The observable outcome is the same. Real: the component has to travel
in props, so the crossing must build a wrapper props object — a second props contract,
one allocation per crossing, and a fork in `host-element`. **Paying a fork of the
shared path to buy a guarantee that does not change any outcome is the wrong trade**,
and the fork is precisely what would turn conversion parity back into a claim.

### The gate's name

`mint-host-gate!` stamps `displayName`. The escape has no author-chosen name, and
this design proposes `[:> Foo]` — the escape spelling wrapped around the component's
own `displayName`, or `name`, or `?` when it has neither (a `lazy` product has
neither).

The reason is not cosmetic. Naming the gate after the component alone would make an
escape **indistinguishable in DevTools from a declared host of the same component**,
which hands the escape the door's tooling identity — the one thing HD-011 says it
does not have. `[:> Foo]` announces what it is, names what it wraps, and gives a
census something to count. See [needs a ruling](#what-needs-a-ruling) — this is the
low-stakes item on that list.

### Dispatch placement, and the cost it must not add

Today `:>` is **not an error**. `hiccup-tag?` accepts any keyword that is not `:<>`,
so `[:> Foo {}]` currently parses `>` as a tag name and asks React to create a `<>`
element. Any branch for the escape must therefore sit **before** `hiccup-tag?` in
`vec->element`'s `cond`, and must compare with `=` and never `identical?` — for the
reason `fragment-head?`'s docstring already gives, that keyword literals are shared
constants only when the build interns them, so an identity test works under
`:advanced` and silently routes every escape into the native-tag path everywhere else.
A branch placed after `hiccup-tag?` is dead code that compiles.

And O3 bites here, harder than it looks. `vec->element`'s `cond` runs **once per
element**. A naive `(raw-head? head)` branch inserted before `hiccup-tag?` charges
one extra `=` to every native tag on the page. That is not beneath this codec's
notice: `rf2-2rtt6.63` re-ordered `as-element`'s branches for 22.5 ns/child → 8.9
ns/child over a 1,908-child roster, and `rf2-2rtt6.32` costed a `keyword?`
short-circuit at ±51–67% of a walk. So the constraint is stated rather than waved
at:

> **The escape's head test must cost nothing for a head that is neither `:<>` nor
> `:>`** — both are keyword comparisons against a literal and the reserved-head
> question is already being asked once. If the implementer cannot get that, the
> alternative is not "it is only one `=`": it is to **measure the walk before and
> after on the census page's child roster and publish the delta in the PR body.**

Argument indices shift by one and are worth writing down once, because an off-by-one
here is silent rather than loud: the component is at index 1; the attr map is at
index 2 when `(map? (nth argv 2 nil))`; children run from 3, or from 2 when there is
no attr map. `[:>]` with no component is an arity refusal, not a `nil` refusal.

## The five edges

### Edge 1 — SSR and hydration

**Decision.** The escape takes `mint-host-gate!` with `fallback` `nil`, i.e.
`:client-only`, and **there is no second mechanism.** No policy is consulted, no
environment is asked, no flag is read. The server renders nothing at the node
because the gate's `useSyncExternalStore` returns its **server snapshot**; the
client's hydration first pass renders nothing at the node because React reads that
**same server snapshot** on that pass; the component appears when React re-renders
with the client snapshot after adoption; and a fresh `createRoot` mount renders the
component on its first pass with no placeholder flash, because it never consults a
server snapshot at all.

**The obligation it protects.** O1, and it protects it by construction rather than by
agreement. Server-absent and client-first-pass-absent are not two facts kept in
step — they are **one fact, and React chooses it, not us.** There is no flag we can
set wrong, no ordering we can get backwards, and no code path where the server and
the client disagree about what to do, because there is only one path and it does not
know which it is on.

**How it could still fail.** Exactly one way, and it is a shape rather than a bug:
if an implementer "optimises" the escape by asking whether this is a server render —
`(if server? nil (createElement …))` — the client's hydration pass takes the other
branch and X2 breaks on every crossing. The design makes that detectable by grep:
**any environment predicate on the crossing path is a violation of this design**, and
a reviewer should treat one as a red flag without needing to reason about React.

Second, subtler, and worth the most care on this page: **the escape does not preserve
X2's body-run equality, and must not be expected to.** X2(c) reads "boundary bodies
run to adopt, 13; boundary bodies run by the server for the same screen, 13". Put a
`:client-only` crossing containing Hicasso boundaries on that page and the numbers
part company — the server runs none of the crossing's boundary bodies, the hydration
pass runs none of them either (equal, and that is the invariant that matters), and
the **post-adoption** re-render runs each of them once, on the client only. That is
`:client-only` working. It is not a regression, and a gate asserting the equality
*after* adoption settles would go red on a correct implementation. The precise
statement the synthesized spec should carry:

> The invariant is *server render ≡ client hydration first pass*. Body-run equality
> is X2's proxy for it on a page every one of whose nodes is present in both. A
> `:client-only` crossing is absent from both, so the proxy holds at the hydration
> pass and diverges only after the adoption window has closed.

This applies to `defhost` `:client-only` today, identically; the escape merely makes
it common. If any existing row asserts the equality post-adoption, it wants finding
before the escape lands.

**Presence and the adoption window (O6).** Children inside the crossing first render
*after* adoption. `adoption-window-closer` rides as the **first** Fragment child of a
hydrated root, so its passive effect closes the window ahead of the app subtree's, and
React's post-hydration `useSyncExternalStore` re-render is scheduled from that same
passive flush and executes after it. So the crossing's children are born **not
present** and animate in — which is the correct answer, because content that was never
on the server is genuinely appearing for the first time. This is a *derivation about
React's scheduling*, not a measurement, and it is flagged as such: it is the one claim
on this page that a witness should confirm rather than assume.

**Witness.** One server render and one hydration of a page carrying a `[:>]` crossing,
asserting all four of: the crossing's DOM **absent** from the server bytes; zero
`:rf.ssr/hydration-mismatch` and zero uncaught `pageerror` across the whole adoption,
through a capture window opened before `hydrateRoot` and closed after the adoption
resolves; every element outside the crossing still carrying the server's expando; and
the crossing's DOM **present** after `(rt/adopting?)` answers false.

**What would make it vacuous.** A component that renders nothing, or a page with
nothing else on it, asserts nothing at all — "absent from the server bytes" is
satisfied by a component that is absent everywhere. The fixture component must render
a **distinctive, non-empty** subtree, and the row must assert both halves: absent
then, present now. The mutation proof is the shape X2's own is: hydrate the page
against server bytes that *do* contain the crossing's markup, and see the complaint
counter move. If it does not move, the capture window is shut across the render again.

### Edge 2 — intent vectors and `h/fn` in declaration-less props

**Decision.** Inherit `host-entry` with an empty declaration map, exactly and without
a branch. Four consequences follow mechanically, and none of them is invented here:

| Written at a `[:>]` prop | What happens | Why |
|---|---|---|
| intent vector or key-map at an **event-spelled** slot (`on-x` / `onX`) | `:rf.error/hicasso-host-undeclared-callback` — **loud refusal** | `refuse-undeclared-host-event!`; no contract is ever inferred from an `on*` name |
| intent vector at a **non-event-spelled** slot | `clj->js` — crosses as data | the position is the only discriminator; see below |
| `h/fn` | crosses by identity, as an ordinary callable function | `host-prop-value` passes `fn?` through; `h/fn` *is* the function |
| `:ref` | `check-ref!` — callback ref, vector refused | O5, on the canonical slot |

The refusal's existing recovery text — "declare it in `:callbacks`" — is exactly right
for the escape, because at `[:>]` the recovery **is** `defhost`.

**The obligation it protects.** O7. The class of failure being kept out is an author's
intent crossing to a library as an inert array — the codec's own words for the defect
every loud error in it exists to delete.

**How it could still fail, and why the design does not widen it.** A vector at
`{:submitHandler [:task/save]}` becomes `["task/save"]` and the handler is silently
dead. This is a real fail-open. It is **inherited, not introduced** — `defhost` has it
at the same slot — but the escape does raise its *rate*, because at `defhost` the author
who needs an intent at a non-`on*` prop declares it and it works, and at `[:>]` there
is nowhere to go.

It cannot be closed by refusing intent-shaped vectors, and this is worth stating
plainly so a reviewer does not propose it: **the value shape is not a discriminator.**
`[:task/save 3]` and `[:a :b]` are the same shape, and the second is legitimate data a
library wants as an array. Any heuristic — namespaced head keyword, first-element type —
is right most of the time, and an instrument that is right most of the time at a
silent-failure site is worse than none, because it teaches authors to trust it. The
position is all there is, and the position rule is already drawn where the record draws
it. **The design's claim here is bounded and honest: it does not widen the gap, and the
guide states it as the escape's second cost.**

**The one thing that could be closed, and it needs a ruling.** An `h/fn` at a slot no
declaration claims is a **marked callback whose mark nothing reads**. The author has
explicitly asked for a contract and the codec cannot give one; at `[:>]` it never can.
The trap is concrete: `(h/fn [] [:row/pick id])` at a `[:>]` prop looks like the
`:event` contract's "a returned vector dispatches", crosses raw, gets called, returns a
vector, and the library discards it. Silently.

This is refusable — one branch in `host-entry`'s else arm, `(when (callback? v) …)` —
and the refusal is **derivable from the codec's own stated principle** rather than
invented: `mint-host!` refuses an unknown option because "a policy could be written and
never applied", and "the silent-ignore was its own defect". An `h/fn` whose contract is
never selected is a policy written and never applied.

It is not taken unilaterally because **it changes `defhost` too** — the same branch fires
at an undeclared `defhost` slot, where the behaviour today is to accept. See
[needs a ruling](#what-needs-a-ruling). The fallback, if the operator prefers not to move
`defhost`: accept, and state in the guide that at a `[:>]` crossing `h/fn` and a plain
`fn` are the same value, because no declaration reads the mark.

**Witness.** A refusal row asserting the **id** `:rf.error/hicasso-host-undeclared-callback`
for a vector at `:on-pick`; a data row proving a vector at a non-event slot arrives as a
JS array (the fail-open, positively asserted so it is a documented fact rather than a
surprise); an `h/fn` row proving the function is called by the library and its return
ignored.

**What would make it vacuous.** A refusal row that asserts only "throws" passes on any
throw — including a typo in the fixture. Assert the `:rf.error/id` and the `:where`. And
the `h/fn` row is vacuous if the fixture component never calls the prop.

### Edge 3 — what "reduced structural identity" concretely means

**Decision.** Three surfaces, and the reduction is not uniform across them. Saying so
precisely is the whole of this edge.

| Surface | At `defhost` | At `[:>]` |
|---|---|---|
| **Hiccup / canonical-EDN structural comparison** | the head is a named marked value; a matcher can name the crossing | **blind** — the component sits in argument position and canonical-EDN renders every function identically |
| **Runtime / React DevTools** | the declaration's own name | reduced but **present** — the gate reads `[:> Foo]` |
| **Canonical DOM** | full | **full** — the crossing's output is DOM and is compared exactly like anything else |

The middle column's blindness is **measured, not asserted**: it is the same fact
`rf2-2rtt6.91` recorded when the dogfood screen and the Conduit feed both hashed
`83b865f8` because "canonical-edn renders every function identically". A structural
comparison cannot tell `[:> A {}]` from `[:> B {}]`.

**The obligation it protects.** O7, by naming a blindness rather than leaving a test
suite to believe it can see. The sharpest consequence, and the one the guide must
carry: **the escape's headline use case — a runtime-selected component — is precisely
the case structural testing cannot verify.** Choosing between `A` and `B` at runtime is
invisible to a structural assertion at that node, by construction.

**How it could still fail.** By an author believing a green structural test covers the
crossing. The design's answer is a stated rule rather than a mechanism, because there is
no mechanism available: **assert `[:>]` crossings at the DOM; assert `defhost` crossings
structurally.** That is a second, independent reason for "declare what you use twice",
and it is stronger than the ergonomic one.

**Witness.** The pair *is* the definition, and the bead asks for it in the structural
test's own terms: `[:> A {}]` and `[:> B {}]` compare **equal** under the lane's
structural comparator, and the same pair rendered compares **different** under
`lane/canonical`. One row, two assertions, and it states the fact in both directions.

**What would make it vacuous.** If `A` and `B` render the same DOM, the second half
asserts nothing — they must render distinguishable subtrees. And the first half is
vacuous if the comparator is one that stringifies by identity: it must be the comparator
the structural rows actually use, not a fixture-local one written for this test.

### Edge 4 — the legal Component-value boundary

**Decision.** Refuse the values a Hicasso author plausibly writes that are definitely not
components. Pass everything else to React and let React judge.

| Value in Component position | Answer |
|---|---|
| absent (`[:>]`, `[:> ]`) | refuse — arity |
| `nil` | refuse, naming the cause `mint-host!` already names: a `:default` import against a library with no default export |
| string, keyword, or symbol | refuse — "you meant a native tag; write `[:div …]`" |
| number, boolean, map, vector, seq | refuse — not a component |
| a `defview` product (`boundary-head?`) | refuse — "that is a view; put it in head position" |
| a `defhost` product (`host-head?`) | refuse — "that is a declared host; put it in head position" |
| any other function or object | **pass through** |

That last row is the decision, and it is deliberate: **no `$$typeof` allowlist.** Three
reasons, in order. React's roster grows, so an allowlist keyed on React's internals is a
fail-closed cage that rots and starts refusing legal components on a version bump — the
opposite of safety. HD-011 explicitly names *providers an ecosystem library hands you*
as a use case, and a provider's shape has moved between React versions within living
memory. And the residue — a genuinely bad object — produces React's own error, which is
acceptable **once the author-caused cases are intercepted**, which is what the rows above
do.

So `memo`, `lazy`, `forwardRef`, a class, a context object, a provider, `Suspense`, and
whatever React ships next all cross without the codec having an opinion. The two Hicasso
refusals are the interesting ones: they are mistakes a reasonable programmer **will**
make, they cost one own-property read each behind a nil guard (`boundary-head?` and
`host-head?` are already exactly that shape), and React's error for either would name
nothing the author wrote.

**The obligation it protects.** Clause 5 and O7. The refusal line is drawn where a
refusal can *name the author's mistake*; past that line, guessing would be friction, not
safety.

**How it could still fail.** A plain ClojureScript function that returns hiccup instead
of an element. React calls it and reports "Objects are not valid as a React child", which
is a poor error and there is no way to name it better without calling the function.
Stated as the hatch's honest cost — and it is exactly why the escape needs the explicit
`:>` head rather than the bare-head auto-hosting HD-011 rejected: **`[:>]` is where the
"a plain function in head position is a loud error" rule is deliberately suspended**, and
a rule you suspend needs a place where the suspension is visible in the source.

Second: reading `displayName`/`name` off the component to build the gate's name touches a
foreign object, and a Proxy could throw on the get. It throws at the author's crossing
with the author's stack, which is the right place; it is not worth a guard.

**Witness.** One refusal row per refused shape, each asserting the `:rf.error/id` and the
`:where`; one pass-through row per legal shape — plain function, class, `memo`, `lazy`
under `Suspense`, `forwardRef`, context provider — each rendering real, asserted DOM.

**What would make it vacuous.** A refusal row asserting only that something threw. Assert
the id. And a pass-through row that renders nothing proves the crossing did not throw, not
that it worked — each must assert the component's own DOM.

### Edge 5 — children lowering, dispatch and frame capture

**Decision.** Children are lowered by the same `make-element` walk, at the crossing,
inside the render window of the boundary that wrote it — indices shifted by one. Nothing
about the children path is new. Four facts follow, and the fourth is the one this edge
exists for:

1. An intent written inside a `[:>]` child is lowered inside the caller's window, so it
   captures that boundary's frame-locked dispatch and dispatches to the right frame.
2. A `[:>]` written outside any boundary raises
   `:rf.error/hicasso-intent-outside-boundary` at its first intent, unchanged.
3. The crossing's children do not run on the server (edge 1's body-run divergence).
4. **The escape cannot carry a `:render` contract, and that is fail-closed rather than
   fail-open.** `rf2-2rtt6.74` made render-position enforcement invocation-scoped, with
   the arming gate set in a `finally` and ownership resolved to the supplying boundary's
   frame — but that machinery is installed by `render-callback`, which is installed by a
   **declared** `:render` contract. `[:>]` declares nothing, so no wrapper is installed,
   so the gate is never armed. A function prop supplied through `[:>]` that converts an
   interactive row to an element is therefore lowering with **no ambient frame**, and
   raises `:rf.error/hicasso-intent-outside-boundary`.

That fourth fact is loud, not silent, and it lands at the library's call rather than at a
user's click — which is the fail-closed side of the .74 defect rather than a return of it.
And the recovery is precisely `defhost` with a `:render` declaration, which is what .74
built.

**The obligation it protects.** O7, and the generalisation this design would most like the
synthesis to keep:

> **The escape carries a dynamic *component*. It does not carry a dynamic *contract*.**
> Every one of HD-011's five named use cases is dynamic in the component and static in the
> prop surface. A crossing whose *callbacks* need policy is a crossing with policy, and
> policy lives on a declaration.

**How it could still fail.** If the author's function prop happens never to lower an
intent, it works, and the author generalises from it — the failure is postponed to the
first interactive render prop. That is a teaching problem, not a mechanism problem, and it
belongs in the guide's troubleshooting row beside the error id.

**Witness.** Two rows. First, a **two-frame** page — the shape `rf2-2rtt6.74` used — where
an intent inside a `[:>]` child dispatches into the writing boundary's frame and **not**
into the other. Second, a hiccup→element conversion inside a function prop at a `[:>]`
crossing raising `:rf.error/hicasso-intent-outside-boundary`, asserted by id.

**The second row cannot be written from the authoring surface today, and that is a
finding rather than a fixture detail (`rf2-2rtt6.120`).** What raises is the *lowering*,
and lowering only happens if something converts the row. The only conversion is
`codec/as-element`, which is internal — there is no `h/as-element` (this section named one
until 2026-08-05). A fixture may reach for the internal var and the row is then honest
about what it proves; what it must not do is assert an author-facing recovery, because
without the conversion the body returns a bare vector, nothing lowers, and the failure is
React's *"Objects are not valid as a React child"* rather than this design's error id — a
different row entirely.

**What would make it vacuous.** A single-frame page cannot distinguish "the right frame"
from "any frame" — that is why the first row needs two, and why it must assert the negative
half as well as the positive one. The second row is vacuous if the fixture never invokes the
prop, and misleading if it invokes it *during* the crossing's own lowering, which is still
inside the window.

## Unrepresentable versus tested

The distinction this design was asked to draw, drawn honestly. "Unrepresentable" here means
*there is no code path that could express the failure*, not *we tested hard*.

| Failure | Status | Why |
|---|---|---|
| Conversion drift between `[:>]` and `defhost` | **unrepresentable** | one function lowers both; there is no second implementation to drift |
| Server tree ≠ client hydration first pass at the crossing | **unrepresentable** | React chooses the snapshot; no environment predicate exists on the path, and adding one is a grep-visible violation |
| A second `:&` merge, or a merge after conversion | **unrepresentable** | `merge-caller` is called once, in `host-element`, before any conversion |
| A `:ref` reaching React unchecked | **unrepresentable** | `check-ref!` sits on the canonical slot, not on the key |
| A fallback smuggled into a `[:>]` crossing | **unrepresentable** | the escape passes `nil` literally; there is no options map that could carry one |
| A hook added to a boundary shell | **unrepresentable** | the escape touches no shell; the gate is not a boundary |
| Gate identity churn | **tested** | bounded by the author's component value being stable — and no design can do better |
| An intent at a non-`on*` slot crossing as data | **tested and documented** | the value shape is not a discriminator; the design does not widen the gap |
| A non-component object reaching React | **partly tested** | the author-caused shapes are refused by id; the residue gets React's own error |
| Presence born-not-present inside the crossing | **tested** | it is a derivation about React's passive-effect ordering, and derivations get witnesses |

## What needs a ruling

Four items this design will not settle for the operator.

**R1 — should an `h/fn` at a slot no declaration claims be refused?** Recommended: yes,
one shared branch in `host-entry`, derived from `mint-host!`'s own "a policy could be
written and never applied". It closes a silent-dead-handler class. It is escalated
because it **changes `defhost`**, where today the value is accepted. The fallback is to
accept and document. Filed independently of the escape as **`rf2-2rtt6.116`**, because it
is true on `main` today with no `[:>]` anywhere.

**R2 — should the escape's gate announce itself in DevTools?** Recommended: yes, as
`[:> Foo]`. Naming it `Foo` alone gives the escape the declared door's tooling identity,
which HD-011 says it does not have. Low stakes; the synthesizer could take it.

**R3 — should `[:>]` crossings be counted?** The codec's own comment records "the census
counts none" as a fact about the current state. Once the escape exists, a census row makes
"declare what you use twice" enforceable by a number instead of by exhortation.
Recommended: yes — and note `rf2-2rtt6.87`'s lesson that a `goog.DEBUG`-gated counter is
dead code under `:advanced`, so if the number is ever to be read off a release build the
counter must be unconditional, as `bodyRuns` deliberately is.

**R4 — the X2 body-run restatement.** Not a new ruling so much as a correction that should
be recorded before the escape lands: post-adoption body-run counts diverge from the
server's at any `:client-only` crossing, by design. The row this reaches is concrete —
`arm1/hydrate_dom_cljs_test.cljs:414`, `(is (= boundary-count (rt/body-runs)))`, read after
the adoption resolves. It is green today because no page it drives carries a `:client-only`
crossing, and it goes red the moment one is added — whether through `[:>]` or through a
`defhost` declared `:client-only`. Nothing on `main` is broken; what is needed is that
whoever adds the first such crossing to that fixture knows the count is *supposed* to move,
and by exactly the number of boundaries inside the crossing.

## Costs, stated rather than claimed away

- **One fiber and one hook per crossing** — the gate. Identical to `defhost` since
  `rf2-2rtt6.85`, and O2 is untouched because the gate is not a boundary: it holds no
  subscription, reads no frame and runs no body.
- **One `WeakMap` entry per distinct component value ever crossed**, `:shared`, collected
  with its component.
- **Nothing on the ordinary path**, subject to the head-test constraint above being met or
  measured.
- **A page whose content comes through `[:>]` renders empty on the server.** There is no
  declaration, so there is no fallback, so there is nothing. For an SSR application this is
  the strongest argument for `defhost` there is, and the guide should say it in those
  terms rather than leaving it as an implication of clause 6.

## What this design does not decide

It does not touch HD-011's core lowering, which is ruled. It proposes no guide prose — the
bead mandates a guide true-up in the implementation PR at five named sites, and the
implementer should note the live collision on `draft-guide/02-views-and-reads.md` recorded
on the bead. It writes no code and no test. And it takes no position on the two sibling
designs, which were deliberately not read.
