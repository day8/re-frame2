# EP-0032: re-frame.ui Reactivity and Ownership

Status: final
Type: standards-track

> This EP records the reactive substrate under compiled views: how a
> `re-frame.ui` view's subscription reads become React updates without leaks,
> tears, or over-rendering. Normative home: `spec/006-ReactiveSubstrate.md`,
> with the ViewCell consumer contract owned by the Spec 004 rewrite.
>
> **Graduated with the S2 slice** (flip to `final` recorded 2026-07-16; the two
> normative homes govern — `spec/006` since S2a, and the ViewCell consumer
> contract since the Spec 004 rewrite merge, R-1 fired via `rf2-vxgfnd.13`).
> Residual build gaps are tracked in the §Implementation-Errata Ledger below
> (the EP-0005 pattern): *final means the decisions are settled, not that the
> build is gap-free.*
>
> **Graduation banner.** The core of this EP has already landed:
> [`spec/006-ReactiveSubstrate.md` §The internal observation port](../../spec/006-ReactiveSubstrate.md)
> is **normative since the S2 slice** (amendment merged with S2a, PR #5706,
> 2026-07-12) — per EP-0009, where this EP and the spec differ, **the spec
> governs**. Design provenance: the `03-reactivity-and-ownership.md` study and the
> `drafts/spec-006-observation-port-amendment.md` amendment graduated into that normative
> `spec/006-ReactiveSubstrate.md` section (both tombstoned in the synthesis tree per
> rf2-mgy7pz); the S-3 feasibility evidence lives on at
> `ai/findings/new-substrate-synthesis/spikes/s3-ownership-report.md` (a durable
> synthesis-tree survivor).

## Abstract

Every compiled view reads app state through compile-indexed `(sub …)` sites
sharing **one** ViewCell — one `useSyncExternalStore` bridge, one scalar
revision — per view. Under concurrent React, *render* may run, restart, or be
abandoned, so rendering only **resolves and probes** (ownership-free); *commit*
alone **owns**, through a six-operation observation port over a strict
target/evidence/lease split. Notification is **push**: one constant-work mark
per dirty cell per run-to-completion drain, committed after the pull
alternative was falsified by benchmark (4.0×–6.5× worse, gap growing with
scale). Frames are created at **host preflight**, never from render; hot reload
is a designed contract (stable shells, hook-signature hash, a two-point commit
fence) rather than a hope.

## Motivation

The adapter-tier view layers (Reagent's reaction graph in particular) carry
documented bug classes: sibling watch-callback clobber, cells pinning disposed
reactions after HMR, abandoned-mount leaks, recompute storms, zero-owner
disposal churn on retarget. Concurrent React makes these structural: any design
attaching ownership during render is wrong by construction once renders can be
speculative, time-sliced, or abandoned (StrictMode, Suspense, Activity).

The alternatives were genuine: pull versus push notification; captured node
handles versus re-resolved identities; eager versus transactional
multi-acquire; whether hide/unmount is distinguishable at cleanup. Each was
settled by ruling or spike evidence; the record belongs in one durable place.

## Goals / Non-Goals

Goals:

- one reactive bridge per view: compile-indexed sub sites sharing one ViewCell;
- render/commit ownership split as frozen invariants, with typed failure;
- the six-operation observation port as an adapter-internal seam;
- transactional dependency reconciliation (stage-acquire, rollback, publish);
- an honest observed lifecycle for cells (three states, retroactive labels);
- committed push economics with drain-quiescence batching and a test flush;
- frames at host preflight; an excellent HMR contract, fixture-pinned.

Non-goals:

- the view programming model itself — `defview`, templates, handlers, `local`,
  effects, presence — is EP-0031's surface; this EP takes it as given;
- the adapter decision (which view layers exist, are retained, or retire) is EP-0030's;
- no change to the closed public adapter API contract (the 11-key spec map) or
  to public `subscribe`/`subscribe-once` semantics;
- resource freshness under Activity reveal belongs to Spec 016, not the lease.

## Relationships

- **EP-0030** — the `re-frame.ui` substrate adoption decision this EP serves:
  the compiled-view runtime whose reads this EP makes safe.
- **EP-0031** — the compiled-view programming model. The one-reactive-grammar
  table (sub / props / `local` / `lease` / frame) is shared vocabulary, but
  `local`'s contract — including the `[value set! update!]` three-tuple as
  amended 2026-07-16 — is **owned by EP-0031 and EP-0035**, not here; this EP
  fixes only that `local` sits outside epochs and re-renders its own view.
- **EP-0035** — component-library readiness (the P0 amendments; delta doc
  `drafts/component-library-readiness.md`).
- **EP-0014** (derivation and process algebra) — this EP is its view-tier
  realization: sub sites are declared inputs, the ViewCell is the
  evaluation/lifecycle boundary, leases are the ownership leg.
- **EP-0002 / EP-0024** (frame target resolution; unified frame identity) —
  `resolve-target` is the view tier's single resolution point for the explicit
  frame-context rule; preflight ENSURE rides the EP-0024 lifecycle.
- **Specs:** `spec/006-ReactiveSubstrate.md` (normative home — port, cache
  contract, memo, epoch finalization); the Spec 004 rewrite (ViewCell/commit
  reconciler, view error boundary); `spec/009` (catalogue rows for every typed
  error named below).

## Specification

The decision surface, as ruled; where graduated, the spec is the authority.

### One ViewCell per view; rf= stabilization

Every lexical `(sub …)` is a compile-indexed site; all of a view's sites share
one ViewCell: one `useSyncExternalStore` over a scalar revision snapshot, one
notification per drain. Conditional reads are legal; loops are rejected (finite
sites). Stabilization is by the frozen `rf=` law: literal queries are module
constants, parametric sites reuse the prior query object while args are `rf=`,
and a site returns the prior exact value when the new read is `rf=`. One
revision integer suffices under the two-guard rule — React's own snapshot
re-check catches mid-pass movement of watched sites; the commit reconciler's
evidence comparison catches the rest — no third mechanism.

### The target/evidence/lease split and the six-operation port

Render resolves each site to an **observation target** via `resolve-target` —
the only resolution point (ambient frame, pins, Story override context all land
there; commit never re-consults context). A target is stable identity carrying
**no node handle** — under HMR a captured handle can be disposed by commit
time, so commit re-resolves the canonical node by `(frame, query)` at acquire.
Everything the render observed — value, node version/key, liveness, epochs — is
probe **evidence**, a separate map; cold probes (`:node-version nil`) are
first-class, falling back to `rf=` on value at the commit check.

The port is six functions in core's `re-frame.substrate.observation`, sole
consumer the `day8/re-frame2-ui` view runtime, outside the closed adapter map,
versioned by the R-6 lockstep train plus an integer `port-abi-version` load
guard (`:rf.error/observation-port-version-mismatch`):

```clojure
(resolve-target site-ctx)     ; render: the ONLY resolution point → target
(probe target ?slice-memo)    ; render: pure evidence read
(acquire! target on-change)   ; commit-only: re-resolves canonical node → lease
(current? lease target)       ; the commit kept-check, one predicate
(read lease)                  ; {:value v :version n}; typed error after release
(release! lease)              ; synchronous, idempotent
```

**The lease IS the owner token** — opaque, identity-equality, per-lease
callbacks — making the sibling-callback clobber structurally impossible and
StrictMode release/reacquire naturally balanced. A `:story-override` target
acquires a **static lease** (`:owned? false` honestly, pinned value, no
callback) under the split equality law (`:override-id` by `=`, `:version` by
`rf=`; NaN-to-NaN retains) — one uniform commit path.

**The six frozen invariants** (graduated verbatim; each deletes a bug class):
render resolves and probes without ownership · commit acquires the exact
captured target · acquire before release · release is synchronous and
idempotent · moved evidence corrects before paint · one notification per
dirty cell per drain (boundary at quiescence, never epoch close).

### Transactional multi-acquire

Commit stage-acquires every newly-observed or retargeted target **before
releasing anything**; staged leases are provisional. On any acquisition
failure, every staged lease is synchronously released in reverse acquisition
order, **the prior committed set remains installed**, the reconcile aborts, and
the typed error propagates — first failure safe by ordering, k-th by rollback;
partial acquisition can never leak or corrupt.

### The three-state lifecycle

Public React gives no cleanup-time signal distinguishing Activity-hide from
unmount, so the runtime implements exactly three observable states:
`:connected` (owns targets + leases), `:disconnected` (ownership released;
emitted fact is `{:reason :unknown}`), `:dead` (frame/adapter/root destroyed;
reconnection fails loudly). Hide vs unmount is only ever a **qualified
retroactive annotation of the prior interval** — a reconnect proves
`:activity-hidden {:proof :reconnect}`, explicit host teardown proves
`:unmounted {:proof :host-teardown}`, GC inference is best-effort, bounded, and
explicitly `:qualified` — and tools must not label beyond what is proven.
Hidden renders may probe, never acquire, and receive no invalidations;
`dispatch-fn` fails typed in every non-connected state.

### Push economics, epoch coalescing, flush!

Source-side notification is constant work (mark dirty with
target/version/epoch/cause — never compute). Every queued event commits its own
epoch record inside the run-to-completion drain; at quiescence each dirty cell
flushes exactly once and React performs one read/render batch for the whole
drain, on a true microtask (never a macrotask that could let a torn frame
paint). `flush!` is **per-root** (Q51, pinned); `ui.test/flush!` is the sole
public test flush — a Promise on CLJS whose thunk arity runs inside React 19
`act`, alternating drains and commits to joint quiescence; synchronous on the
JVM; called inside an open drain it throws `:rf.error/flush-in-open-epoch`
synchronously with frame + epoch evidence. The controlled-input sync door
(EP-0031's synchrony law) remains the one sanctioned exception. Probes share a
**slice-scoped pure memo table** (`?slice-memo`) — once-per-slice economy,
never an authority, dead with the slice (JVM: thread-local render scope; CLJS:
module holder cleared at the microtask checkpoint; both epoch-tagged).

### Preflight ENSURE for frames

ENSURE is host preflight, never render: the compiler extracts unconditional
`frame-root` plans from the root form, and the host ensures frames and drains
`:initial-events` exactly once **before** React runs — unaffected by abandoned
renders, StrictMode replay, HMR, or error recovery. The emitted `frame-root`
only scopes the already-live frame; conditional, reactive, or list-generated
`frame-root` sites are compile errors. `frame-provider` is pure SCOPE.
Preflight failure fails the mount loudly with the container untouched — no
auto-retry (Q49, ruled). Landed in `re-frame.ui` (#5711); R-7 carries the spec
promotion.

### The HMR matrix

- **Stable shells:** `defview` exports a shell keyed by view id; re-evaluation
  (shadow reload *or* REPL — one path, and the Pair's hot-swap is the same
  mechanism) replaces the descriptor and bumps its generation; React state and
  cell identity survive.
- **Hook-signature hash decides preserve vs remount:** the compiler hashes
  ordered user hook sites (`sub`/`lease`/event sites excluded — they reconcile
  through the cell); same signature preserves state, changed signature remounts
  deliberately — never a corrupted hook order.
- **Site identity** is source anchor + structural path + generation; ambiguity
  reports a remount/release rather than guessing.
- **Frames untouched:** preflight re-runs, finds frames live, never re-seeds.
- **`reg-sub` replacement:** dispose-then-notify-once, cause `:hmr`; no cell
  can pin a disposed node.
- **The two-point commit fence** (graduated, spec/006 §Body authority): commit
  verifies dual body authority — cell-local generation plus the registered view
  revision — at commit entry and again at the final publication boundary, so a
  re-registration landing anywhere in the render→commit→publish window can
  never publish a stale body; dev/HMR-only, constant-folds in production.

### Error taxonomy

Internally fail-loud, publicly recover-to-nil: the port throws typed
(`:rf.error/no-such-sub`, `:rf.error/frame-destroyed`,
`:rf.error/read-after-release`, `:rf.error/reentrant-graph-op`, the ABI guard)
while public `subscribe`/`subscribe-once` keep `:replaced-with-default`
recovery — one condition, one catalogue id, two emit surfaces; the ViewCell maps
port throws to the view error boundary. The graduated spec has since extended
the roster (malformed target/lease diagnostics, displacement retry and
`:rf.error/observation-retry-exhausted`, the entry-node fail-loud line) — the
spec governs; every id carries a Spec 009 catalogue row.

## Rationale

Push over pull is the load-bearing bet, so it was made falsifiable rather than
argued: G-13 frames pull as a standing falsification benchmark, and the S-2 run
answered decisively — 4.0× at 100 views, 6.5× at 500, pull O(N views) per epoch
by construction with ~60× the GC pressure. Target-not-handle and
lease-as-owner-token both came out of the spike the same way: the captured node
handle failed under HMR (fixture 8), and keying owners by lease identity made
the worst spine bug structurally impossible instead of merely tested-against.
Three lifecycle states with retroactive annotation is honesty over
convenience — the platform does not say hide-or-unmount at cleanup, so the
runtime never claims it. Transactional staging exists because acquire can fail
and commit must not corrupt: release-first demonstrably churns shared nodes
through their zero-owner edge.

## Backwards Compatibility

Pre-alpha; no shims. The public adapter contract is untouched — the port lives
outside the closed 11-key map, existing adapters implement none of it, and no
feature predicate is added. Public `subscribe`/`subscribe-once` semantics are
unchanged. The seam may change shape between lockstep releases; skew is a boot
error by the ABI guard.

## Bead Plan / Reference Implementation

Evidence and landing record (epic `rf2-vxgfnd`):

- **Spike S-3** (`spikes/s3-ownership-report.md`, 2026-07-11): 55/55
  ownership/concurrency fixtures on React 19.2 — 10k abandoned renders retain
  zero; S-2 push falsification PASS; S-5 sync door 24/24 in jsdom.
- **S2a** (`rf2-vxgfnd.7`, PR #5706): the port over the real sub-cache; Spec 006
  amendment merged; four `[S2-CONFIRM]` items resolved.
- **S2b** (`rf2-vxgfnd.8`, PR #5708): ViewCell + commit algorithm + stabilization.
- **S2c** (`rf2-vxgfnd.9`, PR #5711): preflight ENSURE + frame wiring (Q49 ruled).
- **S2d** (`rf2-vxgfnd.10`, PR #5713): epoch coalescing + `flush!` (Q51 pinned).
- **S2e** (`rf2-vxgfnd.11`): the full HMR matrix, fixture-pinned.

Guide impact: the new-substrate guide's reactivity chapters teach hot reload as
the default workflow and the three-state lifecycle as the tool vocabulary; no
legacy-adapter guide changes (those coexisting adapters are unaffected by this work; they
live on per EP-0030).

## Resolved Decisions

- **R-2, shapes final (2026-07-11 → 2026-07-12).** Semantics frozen 2026-07-11;
  spike S-3's §5 target/evidence/lease model ruled the sole ABI source
  2026-07-12; the amendment merged with S2a the same day. The port is outside
  the closed public adapter map.
- **Push ownership committed (2026-07-11).** The pull alternative survives only
  as the G-13 falsification benchmark; S-2 ran it and push held decisively. If
  a future run ever inverts, the design is rewritten, not toggled.
- **Target = evidence-not-handle; lease = owner token (2026-07-11, spike).**
  No node handle rides a target; owners keyed by lease identity.
- **Q49 (2026-07-12, `rf2-vxgfnd.9`).** Preflight ENSURE failure fails the
  mount loudly, container untouched, no auto-retry.
- **Q51 (2026-07-12, `rf2-vxgfnd.10`).** `flush!` is per-root; the global
  all-roots flush is the test-only `ui.test/flush!` spelling.
- **`[S2-CONFIRM]` items (2026-07-12, `rf2-vxgfnd.7`).** Three confirmed (no
  synchronous fan-out from acquire/release; HMR-disposal queue alignment;
  reverse-order rollback release); one **corrected** — a sub-body throw during
  a cold probe recovers to nil exactly like a live probe (probe temperature
  must not be observable); port-entry conditions stay fail-loud.

## Implementation-Errata Ledger

Per EP-0009, `final` asserts the decisions are settled — this ledger tracks the
known build gaps on this EP's surface. Rows cite live beads and are struck as
they close; the full S2 correction tail lives on the program epic
(`rf2-vxgfnd`).

| Live bead | Gap |
|---|---|
| `rf2-vxgfnd.166` | render batches must be defined by host checkpoint, not router drain (the G-5 boundary prose) |
| `rf2-vxgfnd.167` | per-epoch render-law sweep incomplete (force-tracked ai) |
| `rf2-vxgfnd.169` | empty root-incarnation entries not pruned after weak member collection |
| `rf2-vxgfnd.204` | CLJS SSR emitter not replayed when `re-frame.ui/adapter` is reinstalled |
| `rf2-vxgfnd.94.15`, `.94.17`–`.94.21` | compatible-HMR migration evidence/drift-guard family (`.94.16` already closed) |

## Recommendation

`Status: final` — recorded 2026-07-16. Both graduation conditions the draft
review named are met: `spec/006-ReactiveSubstrate.md` has been normative since
the S2 slice, and the Spec 004 rewrite (the ViewCell consumer contract's home)
merged when R-1 fired (`rf2-vxgfnd.13`, closed). Every open issue carries a
ruling (§Resolved Decisions); the residual correction tail is the errata
ledger's job, not a reason to hold the status.
