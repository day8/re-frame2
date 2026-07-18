# 12 — How it works

The rest of the guide states contracts: `sub` is the value, props memoise by value,
the browser and JVM-tree outputs stay aligned under one conversion contract and parity
gates, production carries nothing it does not use. This page explains the machinery
that keeps those promises — read it when you want to *trust* the claims, not just use
them. Nothing here is required to build apps; everything here is why the other pages
can be short.

## The compiler in one picture

`defview` does **not** produce a function that *interprets* hiccup at runtime. At
macro-expand / compile time it runs a pipeline:

```text
          defview body (hiccup + control forms)
                one source, compiled twice
          ┌──────────────┴───────────────┐
     CLJS build                      JVM build
          │                              │
       analyze                        analyze        ← the same analyzer code
          ▼                              ▼
     template AST                   template AST     ← one per build, not one shared
          │                              │
     ┌────┴─────┐                  ┌─────┴────┐
     ▼          ▼                  ▼          ▼
 emit-cljs   manifest /        emit-jvm   manifest /
(jsx / jsxs) fingerprint      (tree nodes) fingerprint
     │                              │
     └────────  parity corpus ──────┘
        compares normalized output
```

**One analyzer, one conversion contract, two emitters.** A build runs the analyzer over
the source and hands the resulting AST to exactly one emitter — `emit-cljs` or
`emit-jvm`, never both. Within a build the discipline is strict, and it is worth
stating precisely because it is easy to overread: an emitter consumes the AST, never
raw source and never the other host's output. That is a rule about *one* compilation,
and it does not reach across hosts. The analysis is itself parameterized by host — a
symbol resolving to `cljs.core/map` here and `clojure.core/map` there lands differently
in the tree — so the two ASTs are not guaranteed to be equal values, let alone one
value. The hosts never meet as ASTs at all.

What holds them together is a written conversion contract plus a check. A parity corpus
compiles one fixture set through both emitters and compares the results once each side
is fully lowered and projected into a common semantic space, so divergence is *detected*
rather than prevented.

Contrast the stock-Reagent path: `reg-view` is registration and frame injection
only — the body is spliced **verbatim** and still interpreted by Reagent. It is not
"the same compiler under another name". The migration map is
[13 — From other worlds](13-from-other-worlds.md).

Because the interesting work happens at compile time, your production bundle contains
no hiccup walker, no tag parser, no general runtime prop camelizer on compiled paths —
there is no interpreter to pay for, which is most of [10](10-performance.md)'s table.

## What the compiler does to hiccup

Take a view you already know from the main track:

```clojure
(defview counter []
  (let [n (sub [:value])]
    [:div.card
     [:span n]
     [:button {:on-click [:inc]} "+"]]))
```

Here is what happens to that template, step by step.

### 1. Accept or reject the grammar

The body is not "any Clojure that returns vectors." It is a **closed dialect**. The
analyzer walks forms and either places them into a fixed set of AST node kinds or
fails the build with a didactic id (see [14](14-compile-time-limits.md)).

| You write | Becomes |
|---|---|
| `[:div.card {…} kids…]` | **element** — tag, class/id sugar, attrs, children |
| `[child-view {…}]` | **view** call (Var resolved at compile time) |
| `[ForeignComp {…}]` | **foreign** React component boundary |
| `[:<> …]` | **fragment** |
| `(for […] [row {:key k} …])` | **for** — keyed list → array of children |
| `(if …)` / `when` / `cond` / `let` / … | **control nodes** in the AST (not opaque blobs) |
| strings / numbers / `nil` / `false` | **text** or **nothing** |
| `(ui/html s)` / `(ui/raw …)` | explicit escape nodes |

Rejected examples: dynamic tag heads, markup-returning `map`, unkeyed list items, raw
lazy seqs as children, `sub` / `lease` inside a loop without a child view, unaudited
macros that could hide reactive sites.

That closedness is load-bearing. Manifests, memo slots, dual-host parity, and Xray all
assume the compiler **saw the whole tree**. The loud compile errors are not taste;
they are the price of the machinery on this page.

### 2. Normalize into a private AST

Hiccup vectors of keywords are not left as data for later. They become an internal
graph with a closed op set (element, fragment, view, foreign, if, let, for, text, expr,
raw, html, …). Three moves matter for reading the counter:

**Control forms fold into the AST.** The `let` is not "run some Clojure, then interpret
whatever vector comes out." It is a binder node whose body is still template structure.
Both arms of an `if`, every `cond` clause, the body of a `for` — analyzers and both
emitters see through them.

**Tag and prop conversion are total and compile-time** (static cases) under **one rule
table**:

- `:div.card` → tag `div`, class `card` → React `className`
- `:on-click` → `onClick` (hyphenated spelling in source; camelCase only after conversion)
- `:style` maps, `:class` string / vector / flag-map, custom-element property vs attribute
  rules

Static prop objects can hoist to **module-level constants**. Dynamic maps go through
the single known conversion path — not ad-hoc runtime camelizing.

**Event vectors stay intent, not closures you wrote.**
`{:on-click [:inc]}` is recognized as a **literal vector in a known native event
property**. The AST records an event site: the vector is data (manifest, JVM tree, tools);
the client emitter installs a **stable per-site** callback that dispatches that intent
into the committed frame when the browser fires. Placeholders (`:rf.ui/value` and friends)
are compiled into that path — which is why they only work in literal vectors.

**Reactive sites are indexed.** `(sub [:value])` becomes a **finite, compile-indexed
site** (site 0 on this view), not an open-ended "call a hook here." That is why `sub`
may sit in a branch but must not free-float inside a loop: the set of sites must be
finite and known at compile time. Sub-free views can elide the reactive wrapper entirely
in production.

**Static subtrees hoist.** Markup the compiler proves inert becomes module constants —
built once at load. The counter cannot fully hoist (the sub and `n` vary), but a pure
`[:footer "© 2026"]` can.

### 3. Emit — one host per build

**Browser (CLJS).** The AST lowers to direct React construction — conceptually
`jsx` / `jsxs` (the same family as hand-written JSX), with props already converted.
There is no `[:div.card …]` vector left at runtime.

**JVM.** Compiling the same view as Clojure runs the analyzer again and lowers *that*
build's AST to versioned `re-frame.ui.tree` structural data (tag / attrs / events /
children …) under the same specified conversion contract. That tree is what headless
`ui.test` and (at S5) SSR consumption see. Neither this emit nor the parity gate
produces HTML by itself; HTML serialization is a later, separate step
(`re-frame.ssr/emit-ui-tree` at S5).

The two emitters are separate implementations that a shared analyzer and a written
contract keep aligned — so they *can* diverge, and the contract is what makes
divergence a bug rather than a surprise. Parity is defined as normalized structural
equivalence over a common semantic space, not byte-identical output, and the corpus
records the places the hosts legitimately differ (React's own server renderer treats
custom-element property props differently, so the comparator excludes them). This is
why [11](11-ssr.md) says the conversion contract and parity gates detect drift: they
are a check on two implementations, not proof that only one exists.

### 4. Side products from the same analysis

From the same walk the compiler also contributes:

- a **registry / manifest** row (view id, source coordinates, template fingerprint,
  capability bits)
- **site indexes** (subs, events, presence, trusted-html, …) for tooling and HMR
  identity
- a contribution to the **build digest** (whole-app summary used by later hydration
  and debug gates)

So "compiling hiccup" is also **indexing**, not only codegen.

## If you had written JSX by hand

A readable mental model of the counter — **not** the public API, and **not** a promise
of identical emitted source:

```jsx
// Readable shape only — you do not write this; the compiler does the real lowering.
function Counter() {
  // (sub [:value]) — one reactive site on this view (see ViewCell below)
  const n = readSub([:value]);

  return (
    <div className="card">
      <span>{n}</span>
      <button onClick={/* stable handler for [:inc] */}>+</button>
    </div>
  );
}
// + memo wrapper with rf= over declared props (none here)
```

Closer to the *intent* of the emitted code:

```jsx
function Counter() {
  // One ViewCell per reactive view — not a public hook you call by name
  const cell = /* compiler-chosen wrapper: useRef + useSyncExternalStore + … */;

  const n = cell.readSite(0, [:value]);           // (sub [:value])
  const onInc = cell.eventHandler(1, [:inc]);     // {:on-click [:inc]}

  return jsx("div", {
    className: "card",
    children: [
      jsx("span", { children: n }),
      jsx("button", { onClick: onInc, children: "+" }),
    ],
  });
}
```

What casual React would get wrong if you imitated the surface without the protocol:

| Casual hand-write | What the compiler actually aims for |
|---|---|
| `onClick={() => dispatch([:inc])}` every render | **Stable** per-site handler; vector is data; frame is the committed one |
| `useContext` or N hooks for N subs | **One** external-store bridge; sites are indexed slots |
| Leave `className` / camelCase to habit | Conversion done **before** emit from one rule table |
| Return nested arrays of props as "virtual DOM data" | Return **already constructed** React elements (or build them via `jsx`) |

### ViewCell is ours; `useSyncExternalStore` is React's

**ViewCell** is a *re-frame.ui* name for the per-view reactive unit (design docs and
implementation). It is **not** a standard React API. You will not import `useViewCell`
in app code. The compiler selects a thin production wrapper by capability
(`render-subs`, `render-leases`, …) that sits on React primitives:

- **`useSyncExternalStore`** — the well-known React 18 bridge (one per reactive view)
- `useRef` / layout effects — capture, commit reconcile, connect/disconnect

App code stays `(sub [:value])`. The name "ViewCell" is the ownership and indexing
story; the portable React fact underneath is one external-store subscription and a
scalar revision snapshot.

## The reactive core

A view with five `sub` sites does not have five subscriptions in React's eyes. All of
a view's read sites share one **ViewCell** — a single `useSyncExternalStore` bridge
with a single integer revision as its snapshot. That is the "one React bridge per
view" line in [10](10-performance.md), and it is why `sub` may sit in a branch: sites
are compile-indexed slots on the cell, not hooks in a positional list.

The lifecycle of a read is split in two:

- **During render** the site *resolves* what it is reading (frame + query) and
  *probes* it — a pure read of value and version. Rendering acquires nothing and owns
  nothing, so an abandoned render, a StrictMode replay, or a time-sliced pass can
  never leak a subscription.
- **At commit** — when React has decided this render is real — the cell acquires
  ownership of exactly the identities the committed render read, compares what it
  acquired against what the render probed, and if anything moved in the gap, corrects
  *before paint*. New sites are acquired before old ones release, so a shared
  subscription never falls through a zero-owner gap during retargeting.

That render/commit split answers a family of questions the other pages wave at: why
parametric queries switch atomically, why you never see one frame of order A's data
against order B's id, why Activity hide releases everything and reveal reacquires and
corrects, and why frame destruction can mark cells dead loudly.

On the write side, every dispatched event runs the ordinary re-frame2
run-to-completion drain. Only when the drain reaches quiescence does each dirty cell
advance its revision — once, no matter how many of its sites changed — and React gets
one render batch for the views whose values actually moved. Unchanged derivations
return identical references, so child memo comparators short-circuit. One drain, one
notification per affected view, one commit per root: that is
[10](10-performance.md)'s model, mechanically.

## Memoisation that is correct, not heuristic

Every view compiles with a straight-line comparator over its declared prop slots. The
comparator is `rf=`: identity first, value equality for CLJS data, honest fallback to
identity for host values. It can be *correct* because the inputs are values by
construction: props are CLJS data, handlers are vectors, children are realised
elements. This is why the guide keeps insisting on data handlers: a closure prop does
not break memoisation mechanically; it breaks the *idiom* that makes memoisation mean
something.

## Events without closures *(committed callback wiring lands S3)*

A literal vector in `:on-click` compiles to a *site*: the vector is retained as data
(in the manifest, on the JVM tree, in dev tooling), and the client emits one stable
per-site callback that reads its **committed** slot values when the event fires.
Placeholders (`:rf.ui/value` and friends) splice at dispatch time — which is why they
only work in literal vectors: they are compiled, not interpreted.

Controlled inputs add the one deliberate exception to batching. Where the compiler
can prove an element controlled — a literal `:value`/`:checked` beside a vector
handler — dispatches from that site drain *synchronously inside the DOM event*:
browser input event → synchronous drain/epoch commit → ViewCell snapshot advance →
React's discrete render observes the matching value. React performs no restorative DOM
write, so caret and IME state remain stable. The S3 G-8 real-browser fixture is the
executable proof. Nothing about this ordering is configurable because it is a proof
obligation, not a preference.

## One template, two structural emitters

The compiler's AST feeds direct React element construction for the browser and a
versioned `re-frame.ui.tree` structural-data emitter for the JVM. They share a
conversion contract — the same rules decide what `:style {:cursor :pointer}` means —
and parity gates compare the two outputs so conversion drift fails loudly.

Neither JVM emission nor that parity gate produces HTML. At S5,
`re-frame.ssr/emit-ui-tree` is the separate serializer that turns the versioned tree
into HTML; its conversion/escaping contract and parity gates control drift across that
boundary. Until then, `render-to-string` remains the Reagent/hiccup compatibility
route. At S5 a fingerprint in the root manifest lets hydration check the delivered
structure rather than hope.

## The build digest and the registry

Every `defview` registers the view — its source coordinates, template fingerprint,
and capability profile — and the compiler maintains one whole-build summary of all of
them: the *build digest*. S3 debugging surfaces trust it; S5 hydration checks it.

The two `shadow-cljs.edn` settings from [01](01-getting-started.md) exist to keep the
digest truthful across a build daemon's lifetime: the build hook clears retained
output for macro consumers on a daemon's first pass and carries the candidate digest
through the build; the cache blocker stops a stale disk cache from resurrecting a
pre-restart view of the world. An unsaved REPL evaluation can replace a view's live
body without changing build identity: the body is live immediately; the digest
advances when you save and the next pass completes.

## Hot reload, mechanically

`defview` exports a stable component shell keyed by the view's id; the registry holds
the current body. Re-evaluating a namespace replaces the body and bumps a generation;
the shell's identity never changes, so React state and cell identity survive. The
compiler hashes each view's ordered hook signature: same hash, the mounted view
renders the new body in place; different hash, that subtree remounts deliberately —
never a corrupted hook order. Frames sit outside all of this: ENSURE runs at host
preflight, finds the frame live on reload, and does not re-seed — which is exactly
the "your state survives edits" behaviour [01](01-getting-started.md) promises. The
Pair's hot-swap is this same mechanism, invoked over nREPL.

## The dev/prod split *(S3 adds causes/manifests and their elision gates)*

Dev and prod run the same semantics with different amounts of evidence. In dev, every
view carries its manifest, every commit its causes, every element its source
coordinates — that is [09](09-debugging.md). In production, each component carries
only the machinery its own source implies: the compiler records a capability profile
per view and specialises the output, so a props-only view is a memoised function
making direct element calls and nothing else. The debug tier is not flagged off; it
is *absent*, and CI proves the absence. The kernel that remains is budgeted at
≤ 4 KB gzipped over React, and the budget is a gate, not a hope.

## Where to go next

- Walls, fixes, escapes: [14 — What the compiler forbids](14-compile-time-limits.md)
- Performance consequences of no interpreter: [10](10-performance.md)
- Reagent `reg-view` vs this compiler: [13](13-from-other-worlds.md)
- Design contracts one directory up — written for people changing the library rather
  than using it. If a claim on this page seems too good, that is where its proof
  obligations live.
