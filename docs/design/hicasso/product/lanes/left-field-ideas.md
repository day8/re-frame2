# Left-field ideas

[`../specification.md`](../specification.md#11-innovation-portfolio) owns portfolio status and delivery order. This file defines the deciding protocols and kill conditions.

## Product workflow

Close the loop around the chosen architecture:

`observe a real occurrence -> attribute the pressure -> recommend the smallest remedy -> optionally extract one same-root native island -> prove parity and improvement`

This makes rare native React an actual product workflow. Most more radical runtime ideas recreate the standing dependency graph, scheduler, or second semantics that Hicasso is designed to avoid.

## Workflow protocol details

### Xray diagnosis plus manual same-root extraction

Do more than sort render durations. Classify whether pressure comes from application computation, read topology, Hiccup/prop lowering, React reconciliation, or DOM/layout. Native extraction is recommended only for the classes it can improve. The scaffold chooses the smallest credible route—direct `n/$`, a named Hicasso-native component, UIx, or a foreign host—and includes frame-aware reads, host declaration where needed, source/performance identity, DOM/intent parity, lifecycle tests and before/after evidence.

The native component remains in the same React tree and consumes the shared frame through public `n/use-frame`/`n/use-sub`, the equivalent UIx hooks, or the raw host bridge. UIx remains optional. Reject an extraction that needs another root/state owner, loses boundary-level diagnostics, or does not materially improve the named interaction.

### React-concurrency compatibility seam

Keep `useSyncExternalStore` behind an internal bridge and continuously test transitions, Suspense, Activity, hydration and frame retargeting. Do not invent a transition-tagged intent system or async store that React's public contract cannot support.

## Experiment protocols

### Capability receipts

Report mechanical attempt facts rather than pretend to allocate cost: body/fence runs, read calls, unique reads, cache hit, and codec nodes. Codec props, foreign hosts and intents were proposed alongside codec nodes and remain undecided — `rf2-hic-081` neither derived nor witnessed them, and nothing below should be read as graduating them. Distinguish attempts from commits; omit raw queries, values and text by default; prove attached-only allocation and production erasure. Kill the idea if instrumentation changes rankings or requires production retention.

The `rf2-hic-081` spike decided this row and retained no code: the counts graduate as a design, and self time is killed. Five fields need no tap of their own, because they are derivable at the end of an attempt from state the runtime already keeps for its own reasons. Read calls are the length of the read scratch, unique reads its distinct count, cache hits a re-check of the cell table for each scratched key, codec nodes a walk of the element the attempt returned, and attempts the count at the tap itself. The load-bearing step is that the cache-hit answer is stable across an attempt—a cold read retains nothing, and the generation fence makes all of a pass's reads observe one commit—so recomputing at the end returns the answer each read actually got rather than an approximation, and that is what removes the per-read counter. The receipt therefore costs exactly one tap, and that tap is per attempt: a sink deref and a nil test on the exit path of the body-run site that already carries the body-run counter, in the same cost class as the evidence seam's two existing tap points. There is no per-read tap and no per-node tap — the derivation above removes the first, and the arithmetic below refuses the second. Attempts and commits stay on two seams rather than becoming a flag on one record: the receipt counts attempts, so a fence re-run is two and a boundary React skipped is none, while the versioned evidence projection's commit event already counts commits.

An attempt is counted from the moment its body is entered rather than from the moment it returns, so a body that throws, a boundary that throws a Suspense promise, and a lowering that fails are all recorded attempts. This is a decision about what the receipt is for rather than a detail of where one line sits. The receipt is a diagnostic, and a diagnostic is opened precisely when something has gone wrong; a count that quietly omitted the failures would describe the healthy remainder of a sick boundary, and a reader could not tell an attempt that never happened from one that ended badly. Two further things settle it. The body-run counter beside it already bumps where the body is entered, so a receipt counting only completed attempts would disagree with the runtime's own number on exactly the attempts worth looking at — having been placed next to that counter on the argument that the two are the same cost class. And [the specification's evidence section](../specification.md#10-xray-and-runtime-evidence) already requires an attempt outcome among the fields the seam emits, and already says a render measure may be a retry, a throw, a StrictMode duplicate or abandoned work, so a completed-only receipt would contradict the section immediately above the row that adopts it. Suspense makes this concrete rather than hypothetical: a suspending boundary throws on every attempt until its data arrives, so the completed-only reading of a boundary that suspended three times and then rendered is one attempt, and the three that explain the wait are exactly the ones it drops.

Counting abrupt attempts costs no second tap, because the exit path it needs is already there. The body-run site ends in a `finally` it needs anyway for the frame reset and the lowering-owner clear, and that block's own reasoning already covers a throwing body, a thrown Suspense promise and StrictMode's double invoke; recording there relocates the one tap rather than adding another. The facts then divide by availability, and the division is not cosmetic. The read scratch is cleared at the start of an attempt rather than at its end, so an abrupt attempt's reads are still in it when the exit path runs, and read calls, unique reads and cache hits are derivable for an abrupt attempt exactly as they are for a completed one. Codec nodes is not, because there is no element to walk, and it must therefore be reported as the envelope's `unknown` with the reason in the loss record rather than as zero — zero states that the attempt lowered nothing, which is a different and false claim, and is the shape a reader is least equipped to catch.

Self time is killed as a decision rather than a deferral. It is the only underivable field and the only one that puts an instrument inside the body, and it fails the kill rule above on two independent grounds. Chrome clamps its timer to a 0.1 ms grain while the quantity is single-digit microseconds, the frozen clock standard's own marginal cost being 6.8 to 12.6 microseconds per cell, so a per-attempt interval reads either zero or one whole tick and a ranking built on it orders noise. Independently of resolution, two clock reads per attempt inflate a boundary in proportion to its attempt count rather than to its true cost, so two boundaries close in true self time and far apart in attempt count invert. The same arithmetic refuses the per-node codec counter the naive design would have reached for: lowering costs roughly 8.9 nanoseconds per child, so a deref and a nil test per node lands proportional to child count, which is the exact axis a hot-view ranking discriminates on. The post-hoc element walk avoids that, and is why the design above takes it.

Two witnesses are owed before any of this becomes code, and the spike took neither. The first covers the load-bearing step: mutate a cell mid-body and assert the derived hit count still equals the live one. Cache-hit stability is argued from the fence invariant and from the cold path retaining nothing, not witnessed adversarially, so nothing recorded here should be read as proving it. The second covers the abrupt outcomes above: drive a body that throws and a body that throws a Suspense promise, and assert each leaves a recorded attempt whose read facts match what the completed case would have derived and whose codec-node count reads `unknown` rather than zero. The spike's own placement fails that second witness and is why it is named here — it recorded only once the element was in hand, so a thrown body, a Suspense promise and a failed lowering each left no receipt at all. Unmeasured alongside them is the tap's cost on a real browser render path, every number behind this verdict having come from the Node lane or the release gate. Production erasure was measured during the spike and failed first; the rule that failure establishes governs any runtime tap, and belongs with the erasure gate rather than in this lane.

### Counterfactual topology advice

Use committed, redacted read edges and event windows to show membership savings, set stability, co-change frequency and likely coarse/fine/chunked trade-offs. Calibrate blindly against real implementations under correlated and independent changes. Retain a factual worksheet if outcome prediction is unreliable.

### Pull-shaped reads

Compare one declarative pull subscription with fine reads and a hand-written coarse view-model on the same form/list witness under correlated and independent churn. A pull is one invalidation unit; it cannot build a per-leaf dependency ledger. Graduate only if it approaches the coarse arm's clock/heap while materially preserving the local declaration ergonomics, with no independent-churn regression that makes the hand-coarse answer plainly better.

### Schema-driven state and intent generation

After the semantic assertion harness exists, derive bounded generators from the repository's registered schemas. Generate valid app-db states and intent sequences, assert domain invariants, stable complaints, keyed/accessible structure and renderability, and require useful shrinking. Run the first spike on the ordinary product witness. Graduate only if it finds a real defect or refusal gap that the hand-written corpus missed; otherwise retain the schemas and hand witnesses without a generator product.

### AI-pair runtime queries

Expose the smallest read-only questions through the existing Xray/Pair projection: mounted views, current reads, recent approved intents, complaints, source/DOM ownership and explain-render. Add no second graph or history buffer, apply the existing privacy projector, and return explicit loss/opacity. The deciding witness is a real diagnosis completed materially faster than source/log inspection. Protocol transport, including MCP, is an edge adapter over the versioned evidence schema rather than a new runtime subsystem.

### Migration shadowing

Mount the Reagent reference and Hicasso candidate against isolated copies of the same seeded frame, drive the same intent script, and compare canonical DOM plus intent streams at checkpoints. A mutation must make the comparison fail at the correct node or intent. Graduate when a representative real view can be set up in under ten minutes and the report distinguishes mechanical differences from explicit migration refusals.

### Replayable view capsules

After the restricted semantic harness exists, allow one-shot capture of a committed view's ordered read values, approved props, intents, semantic expectation, build identity and opacity/redaction record. Finalize only the render token whose layout effect commits. Never continuously retain raw values or snapshot all app-db. A capsule is a regression seed, not semantic proof.

### Shared read-set notification groups

Identical `(frame, ordered-read-set)` boundaries may be able to share cell membership and fan notification out, reducing approximately `B x R` memberships toward `R + B`. Census first. Benchmark singleton, shared and distinct-query populations, then stress retry, set replacement, notification cleanup, HMR, reincarnation and multiple roots. Stop if fewer than roughly 10% of real memberships coalesce or the common case regresses.

### Codec shape planning

Only profile-trigger this if classification/lowering exceeds 10% of hot-boundary self time. Require a meaningful codec-slice and whole-boundary improvement with bounded cache growth; current caches and the JavaScript engine may already capture the available win.

### Read-free shell declaration

Census real boundaries before implementation. A declaration may skip only the read/external-store portion that can be proven absent; it is not a K3 per-read lever, compiler mode, or hydration-free island. Development must execute a loud source-located guard if the body reads, and the optimized boundary must preserve props, context, component identity, HMR, SSR/hydration and intentional remount behavior. Graduate only when the population and whole-application saving are material and the do-nothing/read-free controls remain green.

## Guardrails and rejects

- A pull query may move traversal/equality/allocation rather than remove work; compare correlated and independent-churn cases and forbid a per-leaf ledger.
- React `<Profiler>` is an explicitly perturbing deep-diagnostic mode, not a wrapper around every boundary.
- Per-boundary self-time timing is rejected, not deferred; see [capability receipts](#capability-receipts).
- Read-free does not mean hydration-free.
- Admit no native helper outside the [native-boundary law](design-laws.md#native-boundary) and provisional public grammar unless a named witness justifies changing those owners and the canonical checklist is updated first.
- Reject signals/Adapton as a replacement reactive graph, compiled/JIT bodies, automatic observed-read-free specialization, worker/WASM interpretation, global element memoization, and a new generic causal/MCP subsystem parallel to Xray/Pair.
