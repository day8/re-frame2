# 05 — Production: the clean room, with a falsifiable contract

**Status:** final · 2026-07-11. The honest baseline: UIx AOT already compiles pure DOM
close to plain JS — beating hand-written JSX is not the claim. The claims are absence,
one-bridge-per-view, identity stabilization, capability specialization, and proven debug
erasure. Budgets are hypotheses with kill-gates — **if a budget misses, optimize or prune
the feature; never weaken ownership or hydration semantics to make a number.**

## 1. Capability specialization (I-15)

The capability vocabulary covers **every feature that changes generated code, hydration,
ownership, or server behavior**:

```text
sub · local · effect · event-sites · lease · frame-scope
presence · error-boundary · portal · client-only · trusted-html
custom-element · foreign-react · dynamic-view · ui-event/handler · debug-site
```

Production components carry exactly the machinery their bits imply. The honest costs,
stated exactly:

- **An event-only view still pays a small commit step** — committed slots + frame must
  publish at layout so callbacks see current values. "No store hook" ≠ "free".
- **"No handler allocation" is scoped:** no *avoidable per-render* allocation on the
  compiled vector path; `ui/raw-fn`, bare fns, and render-fns allocate what they close
  over.
- **"No wrapper components" is scoped:** no *incidental* wrappers on ordinary DOM/internal
  paths; context providers, error boundaries, and presence are real (requested) nodes.
- **Memo scope:** `rf=`-equal props ⇒ no *prop-driven* repaint; subscription, local,
  and context changes still render (that's the point).

**The absence roster** — verified by bundle scan, not tree-shaking faith: hiccup walker ·
tag parser · generic camelizer · sequence flattener · form-detection · second scheduler ·
per-sub hook scaffolding · compatibility stubs · source-coord walkers · manifests, cause
vectors, histories, timings, warning text, `data-rf2-*` strings, project paths · schema
engines (when elided) · **the JVM renderer in any browser entry** · proxy/signal/atom/
query-cache runtimes.

**Packaging (one diagram):** the UI source
artifact is `.cljc` (compiler + both emitters as *source*); the **browser build** never
reaches JVM-renderer namespaces (scan-gated); the **existing `re-frame2-ssr` artifact**
consumes the JVM emitter — no second server product. The data-tree interpreter is its own
artifact. Kernel budget: **≤ 4 KB gz** over React (cell + event dispatcher + dynamic-prop
converter + frame context).

## 2. Render-path economics

| Cost | Reagent today | This design |
|---|---|---|
| element construction | interpret per render | direct calls; static subtrees are module constants |
| prop conversion | runtime, per render | compile-time; one fn for dynamic maps |
| handler allocation | fresh closures per render | none on the compiled vector path |
| re-render scope | argv-sCU heuristics | memo on `rf=` + honest scope above |
| subscription read | reaction bookkeeping | N site reads through one cell; scalar snapshot |
| React bridge | — | one hook per view; one notification per epoch |

**The identity pipeline** (I-8): unchanged fact → equal derivation output suppressed at
the node → site returns the prior exact reference → child comparator short-circuits →
no render. The compiler's whole job is refusing to break this chain.

**Static hoisting**: only provably-inert subtrees hoist (no locals/props/subs/context/
hooks/events/refs/keyed positions); static prop objects and child arrays hoist
independently; `jsx`/`jsxs` selected at compile time. No whole-program heroics.

## 3. Cost model

With V mounted reactive views, Dᵥ/Eᵥ/Lᵥ active sites, C cells touched by an epoch:
memory O(V) cells + O(ΣDᵥ) leases + O(ΣEᵥ) slots + O(ΣLᵥ) owners (compact JS shapes);
per epoch O(changed graph) + O(ΣKᵥ) constant marks + O(C) notify + React work for C
roots — never ΣKᵥ component renders, never timer batching. Per reactive render: one
compact capture + the dynamic elements. First-mount fan-out is bounded by the
slice-scoped memo table (03 §3). The push model is committed; G-13 exists to falsify
this section's economics, not to fork them.

## 4. Debug erasure is a proof

Advanced builds are scanned for the absence + debug rosters (04 §7). The gate is exact
absence. Correctness needs (event dispatch, minimal leases, revisions) live outside the
debug define. **Dev/prod equivalence is gated per generated shape + pairwise capability
combinations + targeted high-risk triples** (full powersets are not a practical
gate), comparing committed DOM, events, owners, cleanup, and hydration with debug
interventions disabled; StrictMode runs are dev-only by nature, so the fixture asserts
dev-with-StrictMode settles to prod's committed outcome.

## 5. Dependency and bundle discipline

re-frame2 core + patched React/React DOM **19.2.4+** peers. No Reagent/UIx/Helix/slim
anywhere in the graph — asserted at the Maven/npm level (G-12). Advanced-compilation
clean: no dynamic requires; registry ids compile to compact constants (production
`ui/view` requires explicit production registry entries — dev string ids don't secretly
serve prod lookup). Budgets as gates: kernel ≤ 4 KB gz; counter ≤ React + 6 KB gz;
relative targets vs UIx-adapter and reagent-slim baselines (symbol-reachability evidence
when chunk subtraction is noisy).

## 6. Scheduling posture

External-store updates: no `startTransition` wrapping, no per-sub priorities, no second
render queue. Exact work reduction only — plus the one sanctioned synchronous door for
controlled inputs (02 §3), which exists because caret/IME correctness outranks batching
purity. `flush-render!` is for deterministic tooling, not applications.
