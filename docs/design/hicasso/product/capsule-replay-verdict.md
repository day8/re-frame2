# Replayable view capsules — verdict

**STOP. Do not adopt.** A capsule that replays one committed boundary is buildable, cheap, and works — and it does not survive the criterion it was most likely to fail. A capsule's recorded tree is **not address-free**: any view that renders an `h/route-link` bakes the recording frame's keyword into the anchor's navigate intent, so the replay, which necessarily runs on another frame, does not reproduce the tree that was recorded. Two of the slice's six boundaries fail on that alone, and nothing in the record distinguishes an address from application data spelled the same way.

The criteria this applies were frozen before any capsule existed: [`capsule-replay-criteria.md`](capsule-replay-criteria.md). Read them first. Their default verdict is STOP, and four of five are met; the one that is not is decisive on its own terms.

## Provenance

| Field | Value |
|---|---|
| Bead | `rf2-hic-082`, the [specification §11](specification.md#11-innovation-portfolio) spike-after-L2 row |
| Criteria commit (authored) | `e8903cd993c5f6e53f17b6d5652bbfd235cb9fd7` — pre-registration, landing with this PR; the tree it was written and measured against is `3deaf2890a394cb606898cfda4783d56f7aaa7ef`, which is on main |
| Witness | `implementation/hicasso/test/re_frame/hicasso/capsule_spike_cljs_test.cljs` |
| Lane | `npm run test:cljs` — the always-on `:node-test` build, whose `cljs-test$` ns-regexp selects the witness. No new build, no `:source-paths` entry |
| Scope of the claim | The Spec 004B structural tree one boundary body produced. Nothing else |

## What a capsule records, and what it cannot

A capsule is `{:view :props :reads :expectation :build :opacity}` — the props the call site passed, the read set the render *resolved* with each key's value, the tree the body produced on the live frame, and a record of what could not be held.

Three things it **cannot** hold, each found rather than assumed:

- **Read order.** The lane's design says *ordered read values*. The published seam does not carry one: `collector/reads-of` answers a set, and the ordered scratch is module-private. The capsule records `:read-order :unrecoverable` rather than implying an order it does not have.
- **A build hash.** Nothing in the runtime carries one, so a capsule cannot say which bundle produced it. `:build` records the two facts a replay's correctness actually turns on — the tree-schema version, and the shell's hook ledger, whose movement means the substrate under the recording is not the substrate under the replay.
- **Anything behind a React crossing.** A raw escape refuses the L2 walk with `:rf.error/hicasso-test-react-is-opaque`, so no capsule exists for a view containing one.

## C1 — opacity: PASSES, on both registered readings

Captured from `npm run test:cljs` (exit 0):

| View | Nodes | Boundary nodes | Opaque values | Reads | Content opacity |
|---|---|---|---|---|---|
| `chrome` | 10 | 0 | 0 | 7 | 0 |
| `article-row` | 4 | 0 | 0 | 2 | 0 |
| `feed-page` | 6 | 3 | 0 | 2 | 0.5 |
| `editor` | 11 | 0 | 0 | 10 | 0 |
| `article-page` | 3 | 0 | 0 | 4 | 0 |
| `app` | 4 | 3 | 0 | 4 | 0.75 |

**C1a — capsule-opaque views: 0 of 6.** The L2 walk refused none of them. **C1b — median content opacity: 0**, against a STOP threshold of one half. The two views that are mostly holes are the two composition shells, and that is the tier being honest rather than failing: a child boundary records the CALL, and its own capsule records its rendering.

The corpus beyond the slice agrees, and it is cited rather than re-derived. The seven example applications declare **37** `h/defview` boundaries between them, and **none** contains a `defhost` crossing or a raw `[:> …]` escape — the classes that make a view capsule-opaque. Five of the seven carry a landed `l2_cljs_test.cljs` that already runs their bodies under injected fixtures, which is the same property C1a measures.

**So the specification's own deciding rule does not stop this spike.** Representative views are not mostly opaque. What stops it is something the rule did not anticipate.

## C2 — the divergence control: direction A passes, direction B FAILS

### Direction A — it diverges when it should

| Class | Change | Outcome |
|---|---|---|
| The world moved | One recorded read value differs | Replay ≠ expectation |
| The body changed | The same capsule against a body differing by one element | Replay ≠ expectation |
| The read set grew | The body reads a key the capsule does not answer | REFUSES with `:rf.error/hicasso-test-missing-read-fixture`, naming `[::tone]` |

The third row is the one that answers the *proves nothing* objection directly. The capsule is not compared against itself: the body is re-run, and the fixture map is the read set, so a body that grows a read cannot pass quietly. The second row is the regression-seed claim proper — the capsule holds the world, and the code is what is under test.

### Direction B — it must not diverge, and it does

| Class | Change | Required | Measured |
|---|---|---|---|
| Unread state | State the body never read moves between two recordings | equal | **equal** |
| Tree position | The body replayed at a different position | equal | **not decidable at this tier** — see below |
| Frame identity | The replay runs on a different frame from the recording | equal | **FAILS for 2 of 6 views** |

Captured, same run:

```
chrome        exact=true   modulo-address=true  expectation-names-the-frame=false
article-row   exact=false  modulo-address=true  expectation-names-the-frame=true
feed-page     exact=true   modulo-address=true  expectation-names-the-frame=false
editor        exact=true   modulo-address=true  expectation-names-the-frame=false
article-page  exact=false  modulo-address=true  expectation-names-the-frame=true
app           exact=true   modulo-address=true  expectation-names-the-frame=false
exact = 4/6
```

The two sets coincide exactly: a view's expectation fails to replay **if and only if** the recording frame's keyword is somewhere inside it. Both offenders call `h/route-link`, whose own docstring states the mechanism — *the frame is captured at RENDER (a click fires after the render scope has unwound)* — so the anchor's `[::h/navigate {:frame …}]` vector carries the recording frame's keyword as ordinary data. Modulo that one slot every replay is exact, which is what makes this a finding rather than noise.

**The tree-position row is a stated skip, and the refusal is the reason.** L2 runs one body and always at the root: a body reached as a child is either a boundary CALL, whose body does not run, or a plain function in head position, which the runtime and the kit both refuse (`:rf.error/hicasso-test-plain-fn-head`). There is no position to vary at this tier. What decides the row is landed and cited rather than re-derived — `re-frame.hicasso.identifier-prefix-ssr-dom-cljs-test` measures a `useId` moving with tree position and not with prefix — and it never reaches a capsule anyway, because a component spending its own React hook reaches a Hicasso view only through a crossing, and every crossing is capsule-opaque.

## C3 — one-shot and commit-owned: PASSES

The recorder buffers an in-flight render and finalises nothing until a commit lands, at `collector/commit-boundary!` — the same `subscribe` closure `useSyncExternalStore` would call, whose cleanup is invoked before the recorder returns so no reader is left on a cell.

The control is React's own conduct, not a stand-in for it: a `react-dom/server` render runs every body for real (the markup contains `slice-chrome`, and `collector/body-runs` counted them) and then throws the tree away, calling `getServerSnapshot` and never `subscribe`. **An armed recorder finalises no capsule across it, and is still armed afterwards.** A committed render finalises exactly one and spends the recorder: a second render buffers nothing and mints no second capsule.

The record is the read set the render resolved and not a projection of state — the panel's capsule holds one key, and `::tone`, which is registered, live and readable, is not in it.

## C4 — the landed facts: one of them is violated, and the violation is not declarable

1. **Frame incarnation — VIOLATED.** This is the same finding as C2's direction B, read against the law rather than against the threshold. A frame's public keyword names an address, not an object, and *where a delayed operation lands is fixed when it is minted, never when it is invoked*. A `route-link` mints exactly such an operation at render time, and the capsule records it. C4 allows a capsule to be *silent* about a thing but not *wrong* about it, and the silence is not available here: `{:frame ::app}` is indistinguishable, from inside the record, from application data spelled the same way. **A capsule cannot mechanically declare its own addresses**, so the opacity record cannot rescue it.
2. **Suspense — not reached.** The fallback law bears on a recorder that finalises across a suspension. This recorder finalises at the commit seam and the node lane does not exercise a Suspense retry; the row is not claimed either way.
3. **`useId` — held off by construction.** See the tree-position paragraph above.
4. **The React-hook spine and I9 — intact.** `collector/shell-hook-ledger` is asserted unmoved at `[:use-context/frame :use-sync-external-store/subscription-epoch]`. The recorder is entirely outside the shell: it drives `render-body` and `commit-boundary!`, the two doors the shell itself uses between its hooks.

## C5 — the cost fence: PASSES

No public export, no new namespace, no third hook, no npm dependency, no hot-zone file, no `:source-paths` entry. The whole experiment is one test namespace, and deleting it deletes the experiment. `npm run test:hicasso-invariants` re-confirms the spec §7 floor unmoved (exit 0): *motion, overlay, native, forms unreachable from the public door*.

## The verdict, and what would move it

**STOP.** C2 fails in direction B and C4 records the same failure as a law violation, and either is sufficient under the criteria as frozen. The cost of the failure is not marginal: `route-link` is a tier-1 shape — the charter's census counts 106 of them across 85 idiomatic files — so the defect lands on the most common navigation shape in an ordinary application, not on a corner.

What is worth keeping is the **negative result itself**, because it is not obvious and it is general: *the tree a Hicasso body returns is not pure data with respect to the frame it ran in.* Any mechanism that records a rendered tree and replays it elsewhere — a capsule, a shadow comparison, a golden-file suite that renders under one frame and asserts under another — inherits this, and the existing L2 suites do not notice it only because they never compare across frames.

Three conditions would justify reopening, and none is this bead's to take:

- A **canonical address projection** in the runtime, so a rendered intent's frame slot is identifiable as an address rather than guessed at. That is a runtime surface, not a test-kit one.
- A capsule scoped to views that mint **no delayed operation** — which is a real population (4 of the slice's 6) but not one an author can recognise at the call site, and a seed that silently covers two-thirds of a page is worse than none.
- A decision that the replay may be compared **modulo addresses**, which is a different and weaker claim than the one registered here and should be registered as such before it is measured.

Nothing is retained from this spike beyond the record and its witness, per the bead's own surface line.
