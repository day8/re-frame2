# 01 — Goals, non-goals, and the load-bearing invariants

**Status:** final · 2026-07-11

## Goals, ranked

1. **Correct under modern React** — concurrent rendering, Strict Mode, hydration,
   Activity, error recovery, HMR: no leaked owners, no speculative publication, no
   render-time domain events, no stale live callbacks. Correctness is not negotiable.
2. **re-frame2-native conceptual integrity** — frames carried, events as the transition
   boundary, subscriptions as reads; routes/resources/machines stay ordinary projections;
   no second state model.
3. **Excellent ergonomics / semantic economy** — one obvious spelling per job, orthogonal
   identities, precise didactic errors; no manual memoization, deps arrays, or
   frame-capture boilerplate; names match mechanics.
4. **Exceptional production efficiency** — no interpreter, no dev machinery, no
   *incidental* wrappers or per-render allocation on the compiled path; claims are CI
   gates, not adjectives.
5. **Causal debuggability** — a committed repaint joins to its exact causes (observation,
   prop slot, local, override, restore, HMR), source site, root, frame, and occurrence —
   emitted at the cause site, never reconstructed; via public React behavior only.
6. **Determinism and testability** — headless-first; equivalent inputs give equivalent
   normalized output across CLJS and JVM *for the defined structural subset*; identity,
   cleanup, and failure are reproducible; clocks and transitions controllable in tests.
7. **Web-platform correctness as a design input** — DOM property semantics, forms/IME,
   custom elements, trusted markup, focus/inert, CSP-safe payloads, and off-box debug
   egress are contracts here, not polish.

**Secondary goals (earned, bounded):**
- **Compile-time budget** — the compiler is the product; macroexpansion latency and
  watch-loop rebuild get a gate (07 §5 G-14) and a REPL-story paragraph (02 §8).
- **AI-agent authoring ergonomics** — this repo's spec is AI-targeted: stable site ids in
  diagnostics, machine-readable manifests, reverse indexes as a query surface, and a
  wave-2 editor/kondo layer (08 §3).
- **Accessibility posture** — semantics pass through; the substrate never eats focus;
  exiting presence nodes go inert; high-confidence compile diagnostics only
  (04 §6). A lint framework would be gold-plating.
- **Failure isolation** — root, frame payload, view, boundary, and tool each have a
  named, narrow blast radius (06 §2, 03 §4).
- **Evolvability** — versioned manifests and evidence schemas; no private-React
  dependency; lowering strategies replaceable without semantic change.

## Non-goals

- Backward compatibility with Reagent/UIx/Helix APIs.
- A second state model: no signals, ratoms, cursors, query caches, form runtimes, actor
  systems — one reactive grammar, subscriptions.
- Suspense as loading state; RSC; `startTransition` over app-db; general animation
  frameworks (bounded *presence* is in — 02 §7 — an animation system is not).
- Making arbitrary runtime markup fast (explicit, costed escape only — separate artifact).
- **Pre-hydration event replay / resumability machinery** — research-tier, post-alpha,
  demand-gated (06 §4). The *serializability property* of data handlers is kept; the
  platform built on it is not v1.
- Non-React emitters in v1 (the option is preserved as an AST-shape gate, not a
  maintained implementation — 08 §1).

## The invariants

Each names the bug class it deletes. Cited as I-1 … I-16. **These are unconditional** —
the push-ownership model is committed; the pull alternative survives only as a
falsification benchmark (07 §5 G-13; spike S-2 in 08 §1), not as a live fork.

**I-1 · Render is speculative and pure.** A render may run, restart, or be abandoned; it
reads values and builds a local capture; it may not acquire ownership, dispatch, mutate
committed slots, publish debug state — **or create/seed frames** (frame ENSURE is host
preflight, 03 §8). *(Deletes: abandoned-render leaks; StrictMode breakage; double-seeded
frames.)*

**I-2 · Only the committed render owns dependencies.** The layout reconciler applies only
the committed capture: acquires new targets **before** releasing dropped ones, verifies
render-time evidence, corrects before paint. *(Deletes: interrupted-update corruption;
zero-owner disposal races; render→commit tears.)*

**I-3 · One React bridge per reactive view.** N reads are N observation targets but one
`useSyncExternalStore`, one scalar snapshot, one notification per epoch.

**I-4 · Snapshots are cached scalars.** The cell snapshot is a revision integer; values
live in derivation nodes and are read during render. Why one integer suffices: React's
own snapshot re-check guards watched sites mid-pass, and commit-time evidence comparison
guards newly-observed sites — two independent guards, no third needed.

**I-5 · Notifications never compute.** A source callback marks the cell stale with
target/version/epoch evidence; prop-dependent queries run only in the view's own render.
*(Deletes: zombie children.)*

**I-6 · One notification per cell per epoch.** Exact coalescing at the transaction
boundary; never debounce-by-time.

**I-7 · Client markup is compiled, never interpreted.** Literal templates lower to
`jsx`/`jsxs`; conversion is compile-time; static subtrees hoist. No walker, tag parser,
camelizer, or component-shape detector ships.

**I-8 · Sites and identities are explicit and independent.** Five identities, never
conflated (06 §2): **root-id** (one React DOM render/hydration unit), **frame-id** (one
re-frame2 state world; roots↔frames are many-to-many), **render-key** (one committed view
instance), **occurrence-path** (a keyed repetition inside one instance), and
**observation-target** (the exact value source captured at render — real node or
override). Sites get compile-time indexes + source anchors; identity under HMR is source
anchor + structural path + generation, released/remounted on ambiguity. *(Deletes:
hydration identity confusion; dependency churn; HMR mis-association.)*

**I-9 · Handlers observe committed values; render callbacks observe the render.** Event
callbacks are per-site stable, reading committed slots + committed frame. Foreign
render-phase callbacks (`render-fn`) see the current render, no identity promise. One
callback never serves both phases. A bare fn in a **known native event property** is
shorthand for a committed handler (the invoker and phase are known — event props only,
never refs or arbitrary fn-valued props); at a **foreign-component** boundary, callbacks
must choose an explicit form. *(Deletes: stale-closure dispatch; speculative retargeting;
phase ambiguity at interop.)*

**I-10 · Frame identity is carried, never guessed.** Resolution: explicit pin → dynamic
binding → React context → loud `:rf.error/no-frame-context`. No default-frame fallback.
The substrate offers **no cross-frame read spelling**; a carried ops map can be *misused*
across frames — that is doctrine enforced by dev diagnostics (a carried `subscribe` used
under a different ambient frame warns), not falsely claimed impossible.

**I-11 · Loading is explicit state.** `sub` never fetches; `lease` declares liveness and
acts only after connected commit; routes/events/machines are the preferred causal owners.

**I-12 · Dev facts vanish from production — provably.** Compile-time defines + bundle-scan
gates. Always-on Spec 009 error contracts remain.

**I-13 · One template AST controls every emitter.** Client and JVM generate from one
normalized AST; parity is **normalized structural equivalence** (fingerprinted), not
byte-identical HTML. The JVM emitter renders the defined structural subset; host-bearing
features have explicit documented fallbacks (06 §1).

**I-14 · Escape hatches advertise their cost.** Dynamic components, prop spreads, raw
elements, opaque callbacks, trusted markup, and the data-tree interpreter are explicit
spellings visible to bundle/profiler tooling.

**I-15 · Capability-specific output.** Production components carry exactly the machinery
their source implies, over the full capability vocabulary (05 §1). Dev builds use a fixed
full hook skeleton (HMR-safe); dev/prod behavioral equivalence is gated per shape +
pairwise combinations (07 §5 G-7).

**I-16 · Every mutation has a named phase.** Preflight (frame ENSURE, payload install) ·
event drain · layout commit (ownership, handler slots) · passive effect (leases, host
sync) · teardown. Nothing happens in an ambiguous "lifecycle" moment. *(Deletes: the
whole when-does-this-run ambiguity class that `:on-mount`-style options carry.)*

## The decision rule (anti-over-engineering)

A feature enters the core only when it (a) removes recurring author bookkeeping while
preserving explicit semantics, (b) structurally eliminates a bug class, (c) removes
measured production work or bytes, or (d) exposes a re-frame2 fact tools consume
directly. Every v1 surface must pass the **demand-bar audit** (08 §3) — a named consumer
in the repo's examples, tools, or guide fixtures — **and the audit table is produced
before Stage 1, not Stage 5**. Guide examples authored by this project do not count as
independent demand for platform-scale features (this rule is why resumability is
research-tier).
