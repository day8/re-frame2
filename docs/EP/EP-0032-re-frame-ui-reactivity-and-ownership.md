# EP-0032: re-frame.ui Reactivity and Ownership

Status: final
Type: standards-track
Created: 2026-07-16
Resolution: final 2026-07-16

> **Historical record — 2026-07-30.** This EP's programme
> ([EP-0030](EP-0030-the-compiled-view-substrate-program.md)) was absorbed into
> Freehand, and Freehand
> ([EP-0036](EP-0036-the-freehand-view-substrate-programme.md)) is now
> **withdrawn**; the EP is kept as the record of the donor programme and no work
> is in flight under it. The observation-port contract it graduated into
> [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) is
> unaffected and still governs — the 2026-07-30 ruling measured invariant 5's
> tear check and retained it.

## Abstract

This EP records the reactive substrate under compiled views: how a
`re-frame.ui` view's subscription reads become React updates without leaks,
tears, or over-rendering. Every compiled view reads app state through
compile-indexed `(sub …)` sites sharing **one** ViewCell — one
`useSyncExternalStore` bridge, one scalar revision — per view. Under concurrent
React, *render* may run, restart, or be abandoned, so rendering only **resolves
and probes** (ownership-free); *commit* alone **owns**, through a six-operation
observation port over a strict target/evidence/handle split. Notification is
**push** — constant-work source-side marking, with each dirty cell flushed once
per render batch at the host checkpoint; the pull alternative stands falsified
by benchmark (4.0×–6.5× worse, the gap growing with scale). Frames
are created at **host preflight**, never from render; hot reload is a designed
contract (stable shells, hook-signature hash, a two-point commit fence) rather
than a hope.

The normative home is
[`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) — §The
internal observation port is normative there — with the ViewCell consumer
contract owned by [`spec/004D-Freehand-Compiled-Grammar.md`](../../spec/004D-Freehand-Compiled-Grammar.md); per EP-0009,
where this EP and the spec differ, **the spec governs**. `final` means the
decisions are settled, not that the build is gap-free.

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

## Specification

**Scope.** This EP fixes: one reactive bridge per view (compile-indexed sub
sites sharing one ViewCell); the render/commit ownership split as frozen
invariants with typed failure; the six-operation observation port as an
adapter-internal seam; transactional dependency reconciliation (stage-acquire,
rollback, publish); an honest observed lifecycle for cells (three states,
retroactive labels); committed push economics with host-checkpoint render
batching and a test flush; frames at host preflight; and a fixture-pinned HMR
contract. It does not own the view programming model — `defview`, templates,
handlers, `local`, effects are EP-0031's surface; the adapter
decision (which view layers exist and are retained) is EP-0030's; the closed
public adapter API contract (the 11-key spec map) and public
`subscribe`/`subscribe-once` semantics are unchanged; resource freshness under
Activity reveal belongs to Spec 016, not the observation handle. Where
graduated, the spec is the authority.

### One ViewCell per view; rf= stabilization

Every lexical `(sub …)` is a compile-indexed site; all of a view's sites share
one ViewCell: one `useSyncExternalStore` over a scalar revision snapshot, one
notification per render batch. Conditional reads are legal; loops are rejected
(finite sites). Stabilization is by the frozen `rf=` law: literal queries are
module constants, parametric sites reuse the prior query object while args are
`rf=`, and a site returns the prior exact value when the new read is `rf=`. One
revision integer suffices under the two-guard rule — React's own snapshot
re-check catches mid-pass movement of watched sites; the commit reconciler's
evidence comparison catches the rest — no third mechanism.

### The target/evidence/handle split and the six-operation port

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
(acquire! target on-change)   ; commit-only: re-resolves canonical node → handle
(current? handle target)      ; the commit kept-check, one predicate
(read handle)                 ; {:value v :version n}; typed error after release
(release! handle)             ; synchronous, idempotent
```

**The handle IS the owner token** — opaque, identity-equality, per-handle
callbacks — making the sibling-callback clobber structurally impossible and
StrictMode release/reacquire naturally balanced. A `:story-override` target
acquires a **static handle** (`:owned? false` honestly, pinned value, no
callback) under the split equality law (`:override-id` by `=`, `:version` by
`rf=`; NaN-to-NaN retains) — one uniform commit path.

**The six frozen invariants** (each deletes a bug class): render resolves and
probes without ownership · commit acquires the exact captured target · acquire
before release · release is synchronous and idempotent · moved evidence
corrects before paint · one notification per dirty cell per **render batch**
(the boundary is the host checkpoint — never epoch close, never drain
quiescence). The invariants are normative in `spec/006-ReactiveSubstrate.md`
(§The six frozen invariants; §Render-batch finalization — the host-checkpoint
boundary).

### Transactional multi-acquire

Commit stage-acquires every newly-observed or retargeted target **before
releasing anything**; staged handles are provisional. On any acquisition
failure, every staged handle is synchronously released in reverse acquisition
order, **the prior committed set remains installed**, the reconcile aborts, and
the typed error propagates — first failure safe by ordering, k-th by rollback;
partial acquisition can never leak or corrupt.

### The three-state lifecycle

Public React gives no cleanup-time signal distinguishing Activity-hide from
unmount, so the runtime implements exactly three observable states:
`:connected` (owns targets + handles), `:disconnected` (ownership released;
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
epoch record inside the run-to-completion drain; each dirty cell then flushes
exactly once per **render batch**, and React performs one read/render batch at
the host's next microtask checkpoint (never a macrotask that could let a torn
frame paint). The batch boundary is that checkpoint, not drain finalization —
the UI scheduler takes no hook from the router and observes no drain boundary
at all. A synchronous drain therefore can never be split across batches (N
epochs settled in one drain coalesce into one), while several drains — or
listener re-entry after a completed batch — reaching the same checkpoint may
share one; only a real host yield separates renders. "One batch per drain" is
the common case, exact whenever callers yield between drains, and is not
normative. `flush!` is **per-root** (ruled); `ui.test/flush!` is the sole
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
auto-retry (ruled). The staged frame-root/provider split is companion ruling
R-7 of the program record (EP-0030).

### The HMR matrix

- **Stable shells:** `defview` exports a shell keyed by view id; re-evaluation
  (shadow reload *or* REPL — one path, and the Pair's hot-swap is the same
  mechanism) replaces the descriptor and bumps its generation; React state and
  cell identity survive.
- **Hook-signature hash decides preserve vs remount:** the compiler hashes
  ordered user hook sites (`sub`/event sites excluded — they reconcile
  through the cell); same signature preserves state, changed signature remounts
  deliberately — never a corrupted hook order.
- **Site identity** is source anchor + structural path + generation; ambiguity
  reports a remount/release rather than guessing.
- **Frames untouched:** preflight re-runs, finds frames live, never re-seeds.
- **`reg-sub` replacement:** dispose-then-notify-once, cause `:hmr`; no cell
  can pin a disposed node.
- **The two-point commit fence** (spec/006 §Body authority): commit verifies
  dual body authority — cell-local generation plus the registered view
  revision — at commit entry and again at the final publication boundary, so a
  re-registration landing anywhere in the render→commit→publish window can
  never publish a stale body; dev/HMR-only, constant-folds in production.

### Error taxonomy

Internally fail-loud, publicly recover-to-nil: the port throws typed
(`:rf.error/no-such-sub`, `:rf.error/frame-destroyed`,
`:rf.error/read-after-release`, `:rf.error/reentrant-graph-op`, the ABI guard)
while public `subscribe`/`subscribe-once` keep `:replaced-with-default`
recovery — one condition, one catalogue id, two emit surfaces; the ViewCell
maps port throws to the view error boundary. The spec extends the roster
(malformed target/handle diagnostics, displacement retry and
`:rf.error/observation-retry-exhausted`, the entry-node fail-loud line) — the
spec governs; every id carries a Spec 009 catalogue row.

### Guide impact

The substrate guide's reactivity chapters teach hot reload as the default
workflow and the three-state lifecycle as the tool vocabulary. The retained
adapters' guides are unaffected — those adapters live on per EP-0030 and are
untouched by this work.

## Rationale

Push over pull is the load-bearing bet, so it was made falsifiable rather than
argued: G-13 frames pull as a standing falsification benchmark, and the
falsification run answered decisively — 4.0× at 100 views, 6.5× at 500, pull
O(N views) per epoch by construction with ~60× the GC pressure.
Target-not-handle and handle-as-owner-token both came out of the ownership spike
the same way: the captured node handle failed under HMR, and keying owners by
handle identity made the worst spine bug structurally impossible instead of
merely tested-against. Three lifecycle states with retroactive annotation is
honesty over convenience — the platform does not say hide-or-unmount at
cleanup, so the runtime never claims it. Transactional staging exists because
acquire can fail and commit must not corrupt: release-first demonstrably churns
shared nodes through their zero-owner edge.

## Backwards Compatibility

Pre-alpha; no shims. The public adapter contract is untouched — the port lives
outside the closed 11-key map, existing adapters implement none of it, and no
feature predicate is added. Public `subscribe`/`subscribe-once` semantics are
unchanged. The seam may change shape between lockstep releases; skew is a boot
error by the ABI guard.

## Resolved Decisions

- **R-2 — port shapes final (2026-07-11/12).** Observation-port semantics
  frozen ahead of implementation; the ownership spike's target/evidence/handle
  model is the sole ABI source; the port lives outside the closed public
  adapter map.
- **Push ownership committed (2026-07-11).** The pull alternative survives only
  as the G-13 falsification benchmark, which ran and confirmed push
  decisively. If a future run ever inverts, the design is rewritten, not
  toggled.
- **Target = evidence-not-handle; handle = owner token (2026-07-11).** No node
  handle rides a target; owners are keyed by handle identity.
- **Preflight ENSURE failure (2026-07-12).** A failed preflight fails the mount
  loudly, container untouched, no auto-retry.
- **`flush!` is per-root (2026-07-12).** The global all-roots flush is the
  test-only `ui.test/flush!` spelling.
- **Cold-probe recovery (2026-07-12).** A sub-body throw during a cold probe
  recovers to nil exactly like a live probe — probe temperature must not be
  observable; port-entry conditions stay fail-loud. Confirmed alongside: no
  synchronous fan-out from acquire/release; HMR-disposal queue alignment;
  reverse-order rollback release.
- **Invariant-6 boundary corrected (2026-07-19).** The notification boundary is
  the **host checkpoint** — one notification per dirty cell per render batch —
  not drain quiescence: the shipped scheduler takes no hook from router drain
  finalization. The corrected law is normative in
  `spec/006-ReactiveSubstrate.md`; this EP's text above states it as
  corrected. The other five invariants were never in question.
- **Original drain-quiescence wording preserved (2026-07-19, rf2-r7ahi).** The
  host-checkpoint correction reached five settled passages, not invariant 6
  alone: PR #6393 reworded the Goals bullet, §One ViewCell, and §Push economics
  in place from the original drain-quiescence phrasing, and the same drain →
  host-checkpoint change carries through the Abstract summary and invariant 6.
  Those rewrites read as current truth above, but the freeze-meaning ruling
  (rf2-r7ahi) requires the settled originals to stay visible, so all five are
  preserved here — `spec/006-ReactiveSubstrate.md` (host-checkpoint render
  batching) remains the current normative truth:
  - **§Abstract** originally read "Notification is **push**: *one constant-work
    mark per dirty cell per run-to-completion drain*, committed after the pull
    alternative was falsified by benchmark (4.0×–6.5× worse, gap growing with
    scale)".
  - **Goals** originally read "committed push economics with *drain-quiescence
    batching* and a test flush".
  - **§One ViewCell per view** originally read "one `useSyncExternalStore` over a
    scalar revision snapshot, one *notification per drain*".
  - **Invariant 6** originally read "one notification per dirty cell *per drain
    (boundary at quiescence, never epoch close)*".
  - **§Push economics** originally read "Every queued event commits its own epoch
    record inside the run-to-completion drain; *at quiescence each dirty cell
    flushes exactly once and React performs one read/render batch for the whole
    drain*, on a true microtask (never a macrotask that could let a torn frame
    paint)".

  The correction is factual — the shipped scheduler batches at the host
  checkpoint, never at drain quiescence — but per the ruling it is recorded, not
  applied by silently rewriting the settled prose.
- **One effect-dependency equality doctrine: `rf=` across native and interop
  tiers (2026-07-21, rf2-u53yy.6).** The native `ui/effect` compared deps by
  `rf=` (value equality) while the `re-frame.ui.react` interop wrappers
  (`use-effect` / `use-layout-effect`) compared per authored slot by `Object.is`
  — two equality doctrines one keystroke apart, plus a spec/implementation
  drift. Ruled (Mike, 2026-07-20): converge on `rf=` **everywhere**, native and
  interop. The interop tier now derives its effect token from the shared `rf=`
  comparator (`re-frame.ui.hooks/deps-token`), so a distinct-but-value-equal
  CLJS deps value no longer re-runs an effect in either tier — value semantics,
  stated plainly. The superseded per-slot `Object.is` interop behaviour (the
  ".95.12 correction") is retired; `spec/004-Views.md`'s react-tier deps law is
  the normative statement. Should a future foreign integration demonstrably
  require identity semantics, that is a new per-wrapper decision made and
  documented then — never a silent split.

## Open Issues

None. No open implementation gaps are tracked on this EP's surface.

## References

- Normative homes:
  [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) (the
  observation port, cache contract, memo, epoch finalization, the six frozen
  invariants) and [`spec/004D-Freehand-Compiled-Grammar.md`](../../spec/004D-Freehand-Compiled-Grammar.md) (the
  ViewCell/commit reconciler and the view error boundary);
  [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) carries a
  catalogue row for every typed error named above.
- [EP-0030](EP-0030-the-compiled-view-substrate-program.md) — the substrate
  adoption decision this EP serves.
- [EP-0031](EP-0031-re-frame-ui-programming-model.md) — the compiled-view
  programming model. The one-reactive-grammar table (sub / props / `local` /
  frame) is shared vocabulary, but `local`'s contract — including the
  `[value set! update!]` three-tuple — is owned by EP-0031 and EP-0035, not
  here; this EP fixes only that `local` sits outside epochs and re-renders its
  own view.
- [EP-0035](EP-0035-component-library-readiness.md) — the component-library
  readiness amendments.
- [EP-0014](EP-0014-derivation-and-process-algebra.md) — this EP is its
  view-tier realization: sub sites are declared inputs, the ViewCell is the
  evaluation/lifecycle boundary, handles are the ownership leg.
- [EP-0002](EP-0002-frame-target-resolution.md) /
  [EP-0024](EP-0024-unified-frame-identity-and-lifecycle.md) —
  `resolve-target` is the view tier's single resolution point for the explicit
  frame-context rule; preflight ENSURE rides the EP-0024 lifecycle.
- Evidence: the ownership-spike report at
  `ai/findings/new-substrate-synthesis/spikes/s3-ownership-report.md` (untracked
  at S4; archived in Git history at `41375ba940`, restore via
  `git show 41375ba940:<path>`) — 55/55 ownership/concurrency fixtures on
  React 19.2, 10k abandoned renders retaining zero, the push-falsification
  PASS, and the sync-door jsdom matrix.
