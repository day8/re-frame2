# Replayable view capsules — criteria (pre-registered)

[Specification §11](specification.md#11-innovation-portfolio) carries *replayable view capsules* as a **spike after L2**, with one deciding rule written beside it: *one-shot, commit-owned, redacted; stop if representative views are mostly opaque.* This document turns that rule into something a measurement can fail, and it is written **before** anything is measured. Criteria chosen after the numbers are a rationalisation of whatever the numbers happen to say.

Nothing here was measured. Every line below is derived from the specification row above, the [left-field lane](lanes/left-field-ideas.md#replayable-view-capsules)'s design paragraph, the testing ladder `re-frame.hicasso.test/ladder` publishes as data, and four facts this programme has already landed witnesses for (see [C4](#c4--the-landed-facts-are-not-quietly-violated)). No capsule had been recorded when it was written, and no existing result was consulted to place a line.

## Pre-registration record

| Field | Value |
|---|---|
| Subject | Replayable view capsules, the [specification §11](specification.md#11-innovation-portfolio) spike-after-L2 row |
| Registered by | `rf2-hic-082`, on the specification and the lane alone |
| Frozen | 2026-08-12 19:58 AUSEST, before any capsule was recorded |
| Evidence source | `re-frame.hicasso.capsule-spike-cljs-test`, and nothing else |
| Applied by | `rf2-hic-082`, in `capsule-replay-verdict.md` (not yet written when this froze) |
| Default verdict | STOP. ADOPT requires every criterion met on published, re-checkable evidence |
| Amendment | Prospective only, and this file re-freezes when a criterion moves |

**The pre-registration proof is that this file lands in its own commit, before the witness exists.** The verdict cites that commit. A verdict that cannot cite a criteria commit predating its evidence has not been pre-registered, whatever its dates claim.

## What is being decided

In scope: whether a **capsule** — a one-shot record of one committed boundary's world, replayable through the L2 harness as a regression seed — is worth building as a product surface.

The capsule's stated axis, fixed here so the verdict cannot widen or narrow it afterwards, is **the structural tree one boundary body produced** ([Spec 004B](../../../../spec/004B-UI-Tree-and-Conversion.md) version 1, the value `re-frame.hicasso.test/tree` answers). Intent vectors ride inside it, because an intent is an attribute value. Nothing else is claimed: not markup, not lifecycle, not React identity, not commit, not hydration, not paint.

Out of scope, and not evidence for or against: continuous retention of any kind; any capsule that snapshots `app-db`; the mounted tier's own replay, if one is ever wanted; anything about performance, which the [performance contract](specification.md#6-performance-contract) owns separately.

## The criteria

### C1 — Opacity, both readings, thresholded before it is measured

The specification's deciding rule is *stop if representative views are mostly opaque*, and "mostly opaque" has two defensible readings. Both are registered, because picking the flattering one afterwards is the failure this document exists to prevent.

**Population.** Every `h/defview` boundary in the package's example corpus — `implementation/hicasso/test/re_frame/hicasso/examples/*/views.cljs` — counted from source. The population is taken whole; no view is selected in or out.

**C1a — view-level opacity.** A view is CAPSULE-OPAQUE when a capsule cannot be recorded and replayed for it at all: the L2 walk refuses its rendering (a `defhost` crossing, a raw React escape, a raw element, an unforced `delay`), or its body cannot be reached without React. **STOP if more than half the population is capsule-opaque.**

**C1b — content-level opacity.** For each view a capsule *can* hold, its opacity is the fraction of its recorded tree's nodes that the capsule does not see through: view-boundary nodes, whose child rendering is a call rather than a rendering, plus values recorded as `{:rf.ui/opaque :fn}`. **STOP if the population median exceeds one half.**

Both figures are published as counts, per view, so the ratio is re-checkable without re-running anything.

### C2 — The divergence control, both directions

A capsule that replays only what it recorded proves nothing. The claim is that a replay is **indistinguishable from the original run on the stated axis**, and the only way to earn it is a control that moves.

**Direction A — it must diverge.** Change something the capsule is responsible for, and the replay must differ from the recorded expectation, or refuse. At least two classes, each demonstrated:

| Class | The change | Required outcome |
|---|---|---|
| The world moved | One recorded read value differs | Replay ≠ expectation |
| The body changed | The same capsule, replayed against a mutated body | Replay ≠ expectation |
| The body's read set grew | The body reads a key the capsule does not answer | Replay REFUSES, naming the key |

**Direction B — it must not diverge.** Change something the capsule is deliberately not responsible for, and the replay must be **equal**, on the compared value:

| Class | The change | Required outcome |
|---|---|---|
| Frame identity | The replay runs on a different frame from the recording | Replay = expectation |
| Unread state | State the body never read moves between two recordings | The two expectations are equal |
| Tree position | The body is replayed at a different position | Replay = expectation |

**STOP if either direction fails**, and STOP if direction A passes only because the comparison is the capsule against itself rather than against a re-run body.

### C3 — One-shot and commit-owned, demonstrated

The design says *one-shot, commit-owned*, finalised only on the render whose layout effect commits, with no continuous retention and no `app-db` snapshot. That is a property with a control available in the node lane: `react-dom/server` runs bodies for real and then throws the tree away, calling `getServerSnapshot` and never `subscribe`, which is a genuine never-committed render performed by React itself.

**STOP unless** an armed recorder finalises no capsule across a never-committed render, finalises exactly one across a committed one, and disarms itself afterwards — and unless the record's contents are the read set the body actually resolved rather than any wider projection of state.

### C4 — The landed facts are not quietly violated

Four facts this programme has landed witnesses for are named here, in advance, so the verdict cannot discover a convenient subset:

1. **Frame incarnation.** A frame's public keyword names an address, not an object; where a delayed operation lands is fixed when it is minted, never when it is invoked, and an operation minted against a dead incarnation writes nothing and refuses with `:rf.error/frame-destroyed` ([`invariants.md`](invariants.md), and `re-frame.hicasso.reincarnation-routing-cljs-test`).
2. **Suspense.** A fallback leaves the primary tree's passive effects mounted, so a `useSyncExternalStore` subscription survives and the retry's registration is `identical?` to the pre-suspension one (`re-frame.hicasso.activity-suspense-dom-cljs-test`).
3. **`useId`.** React derives an id from the prefix *and* from tree position, so a capsule that records or replays one is recording a value of the tree it was recorded in (`re-frame.hicasso.identifier-prefix-ssr-dom-cljs-test`).
4. **The substrate is the React-hook spine**, and I9 freezes an ordinary boundary's shell at exactly two React hooks ([`substrate-decision.md`](substrate-decision.md)).

**STOP if the capsule records or replays any value these facts make non-reproducible without declaring it in the capsule's own opacity record.** A capsule may be silent about a thing; it may not be wrong about it.

### C5 — The cost fence

A spike that ships an API is not a spike. **STOP if a capsule needs** a new public export, a third hook on the boundary shell, an npm dependency, a hot-zone file, or a `:source-paths` entry.

## Amendment rule

Prospective only. A criterion may be corrected or added **before** the witness it governs produces its evidence; the file then re-freezes at the landed revision and the verdict cites that revision instead. A criterion may never be moved after the evidence exists — the threshold that was in force is the one that decides.

Recording provenance (back-filling a commit hash into the table above) touches no criterion and does not re-freeze the file.
