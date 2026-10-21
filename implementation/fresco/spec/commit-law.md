# Fresco commit law

What a Fresco render may do, what its commit owes, and when a render batch closes: the
reactive commit law of `re-frame.fresco`. **This file governs the commit**, as
[`authoring-contract.md`](authoring-contract.md) governs the authoring surface. Where a
docstring, a guide chapter or a page under `docs/design/fresco/` differs from it, this
file is right and the other text is the defect.

It sits on root [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the
sub-cache, its ref-counting and its invalidation algorithm — which keeps the three
obligations this law places on core: the ref-count attach that does not re-resolve
context (invariant 2), the frame install epoch read as evidence (invariant 5), and the
open-drain guard on a synchronous registry-flush. The six invariants here are a separate,
numbered list from the fifteen product invariants in [`invariants.md`](invariants.md).

## The six frozen invariants

> **Status: normative.** These are the reactive-ownership invariants Fresco's view
> substrate satisfies — the render/commit split [§The atomic shell](#the-atomic-shell)
> realises. They are numbered, and they are **cited by ordinal** from root `spec/`, from
> core source and from Fresco's own, so the numbering is part of the contract: an
> invariant is never renumbered, only re-worded.

These are normative. Each names the bug class it deletes.

1. **Render resolves and probes without ownership.** A render pass may resolve targets
   and probe their values; it MUST NOT increment a ref-count, register a watch or
   callback, or materialise a cache node that outlives the pass. *(Deletes:
   abandoned-render leaks; StrictMode double-render breakage; speculative publication —
   per I-1.)*
2. **Commit acquires the exact captured target.** The layout commit acquires the targets
   recorded in the committed capture — the captured *identity*, with no re-resolution of
   context (overrides, pins, ambient frame). The canonical *node* is re-resolved by
   `(frame, query)` at acquire; node identity lives only in evidence. *(Deletes:
   render→commit context tears — two lookups that could disagree; pinned disposed nodes
   under HMR; per I-2.)*
3. **Acquire before release.** On retarget or dependency change, commit acquires every
   newly-observed or retargeted target **before** releasing anything, so a shared node
   can never fall through its zero-owner disposal edge ([Spec 006 §Reference counting and
   disposal](../../../spec/006-ReactiveSubstrate.md#reference-counting-and-disposal)) mid-reconciliation. *(Deletes:
   zero-owner disposal churn — dispose-then-rebuild of a node both old and new sets
   use.)*
4. **Release is synchronous and idempotent.** Releasing a handle detaches ownership
   in-tick (the 1 → 0 edge disposes synchronously, per the cache contract), and a second
   release of the same handle is a no-op. *(Deletes: deferred-release windows; cleanup
   paths that double-release under error recovery.)*
5. **Moved evidence corrects before paint.** At commit, **each acquired node** — both
   the handles **retained** from the prior committed set and the newly **staged** ones —
   has its identity (`:node-key`), version, and the frame/registry epochs compared against
   the render's probe evidence; any movement in the render→commit gap — including a same-id
   frame **reincarnation** the `:node-key` axis catches when version + epochs coincide —
   advances the cell's revision and notifies, and the host corrects **before paint**. Two
   cases make that comparison load-bearing, and the second is the stronger one. On a
   **non-watchable headless host** a retained site has no value-movement watch, so this
   comparison is its *only* correction — a staged-only comparison would leave a retained
   site's headless movement caught by nothing. On a **watchable** host a newly **staged**
   site has no watch either, because `acquire!` installs the change watch *during* the
   commit that needed it — the movement the comparison exists to catch has already happened
   by the time that watch is in place. The two failures are not symmetric. A retained site
   whose commit skipped the comparison publishes stale and then self-heals one window later,
   when its earlier-installed watch fires; a **staged** site publishes stale and **nothing
   ever corrects it**, because a dependency's *first* render has no earlier channel to heal
   through. That is what a panel mounting exactly as a permission drops, a user switches, or
   a record is redacted looks like: a value wrong on arrival and wrong for as long as it is
   shown. The comparison is therefore not a headless-only concession — it is a staged site's
   only correction on **every** host, watchable ones included. The staged case — over a
   real sub-cache, on a watchable browser host — is pinned by
   `bench/fresco/src/re_frame/bench/fresco/arm1/staged_read_tear_cljs_test.cljs`;
   no shipped row pins the retained case, and the invariant binds it either way.
   *(Deletes: painting a frame computed from stale reads; a coincident-version
   reincarnation misread as unchanged; a retained headless site that self-corrects through
   no channel; a staged site that paints stale on a dependency's first render and never
   heals.)*
6. **One notification per cell per render batch — the boundary is the host checkpoint, not
   drain quiescence and not epoch close.** A **render batch** is the pending read/render
   window that ends at the **earliest** host checkpoint to reach it — the next CLJS host
   microtask checkpoint, a host-scheduled render checkpoint a synchronous host commit can
   see, or an explicit headless/test flush ([§Render-batch
   finalization](#render-batch-finalization--the-host-checkpoint-boundary) enumerates all
   three, and states why an earlier close moves none of the four guarantees); the UI
   scheduler has no hook from router drain finalization and observes no drain boundary at
   all. An event/frame **epoch** is a
   commit-phase + diagnostic-evidence unit (one per dequeued event — per
   [Spec 002 §Drain versus event](../../../spec/002-Frames.md#drain-versus-event--the-epoch-unit)); it is
   **not** a React render boundary. Source-side notification is constant work — mark the
   cell stale with target/version/epoch/cause evidence, never execute a prop-dependent
   query (per I-5) — carrying the moving epoch as **cause evidence only**. A single
   run-to-completion drain may settle several queued events, each committing its own epoch
   record; a dirty cell coalesces every epoch that marked it before the checkpoint and is
   flushed **exactly once** when the window closes (exact coalescing at the checkpoint,
   keyed on the cell's pending state — never on the epoch tag, never debounce-by-time; per
   I-3/I-6). A synchronous drain therefore cannot be split across batches, and N epochs
   settled in one drain share one batch — but the converse does not hold: several drains
   finishing before the same checkpoint may share a batch, and only a real host yield
   separates renders ([§Render-batch
   finalization](#render-batch-finalization--the-host-checkpoint-boundary) states all four
   guarantees). **No render count may be inferred from the number of event/frame epochs,
   nor from the number of drains** — "one render batch per router drain" is **not**
   normative; it is only the common case, true exactly when callers yield between
   drains.
   *(Deletes: zombie children; N-notifications-per-event fan-out; the false
   N-epochs⇒N-renders equation; the false drain-quiescence render boundary.)*

## Render-batch finalization — the host-checkpoint boundary

On Fresco, the invalidation algorithm's Phase 3
([Spec 006 §Invalidation algorithm](../../../spec/006-ReactiveSubstrate.md#invalidation-algorithm) — "notify subscribers") is realised
as constant-work stale-marking (invariant 6). The commit sequence gains an
**adapter-internal final phase — the host-checkpoint render batch**. A **render batch** is
the pending read/render window that ends at the **earliest** host checkpoint to reach it:
the next CLJS host microtask checkpoint, a host-scheduled render checkpoint a synchronous
host commit can see ([§The second closer](#the-second-closer--a-synchronous-host-commit-must-be-able-to-close-the-window)),
or an explicit headless/test flush. The window is armed by the **first** dirty mark, not by
the start of a drain, and it closes at the **host's** checkpoint, not at the end of a
drain: this scheduler has no hook from router drain finalization and observes no drain
boundary at all. A run-to-completion drain may settle several queued
events, each settling its derivations (Phases 1–2) and marking dirty cells (Phase 3) as it
commits its **own** epoch record; when the window closes, each dirty ViewCell is flushed
**once** into the host scheduler and React performs **one read/render batch** over every
epoch that marked it. The moving epoch rides the stale-mark as **cause evidence only** —
coalescing keys on the cell's pending state, never on the epoch tag.

Four guarantees follow, and they are the whole contract:

1. A synchronous run-to-completion drain **cannot** be split across batches — the window
   cannot close while the stack is still unwinding.
2. N epochs settled within **one** drain coalesce into **one** batch.
3. Several drains — or a listener re-entering after a completed batch — that finish before
   the **same** host checkpoint **may share** one batch. Two back-to-back `dispatch-sync!`
   calls in one JavaScript stack render once, not twice; so do nested cross-frame
   synchronous drains.
4. Drains separated by a real **host yield** render separately.

"One render batch per router drain" is **not** normative. It is the common
case — true exactly when callers yield between drains (guarantee 4) — and it is not a rule
this scheduler enforces, or could enforce without a drain-finalization seam that
deliberately does not exist. Render separation follows host checkpoints, never the epoch
count and never the drain count (see invariant 6).

On CLJS the flush rides a **true host microtask** — `js/queueMicrotask` (a resolved-`Promise`
job where absent), deliberately **not** `goog.async.nextTick`, which is a **macrotask**
(`setImmediate`/`MessageChannel`/`setTimeout`) that yields to the event loop and could let
a torn frame paint before the correction runs. A single microtask, armed by the first
mark of the window, cannot run until the synchronous stack unwinds, so it fires strictly
**after** that stack completes — at the event loop's microtask checkpoint, which runs
**before** the next paint — never between two queued events of the same drain, and always
before a torn frame can show. That microtask is the window's **guaranteed**
closer, and outside a synchronous host flush it is still the only one that ever runs;
[§The second closer](#the-second-closer--a-synchronous-host-commit-must-be-able-to-close-the-window)
below covers the one that runs inside one. The third closer in the enumeration above — the
explicit headless/test flush — is a caller's door, and the doors that exist are narrow
ones. The headless (plain-atom / SSR) adapters have no live host commit and so ship no
flush at all: `render-to-string` is their only render path
([Spec 006 §`flush-render!`](../../../spec/006-ReactiveSubstrate.md#flush-render-f--nil)). Tests reach the React adapters' commit through
`re-frame.test-support/flush-views!`, a test-scoped boundary around direct React 19 `act`
that returns nil and wraps the production `flush-render!`.

A substrate that publishes a synchronous **registry-flush** MUST reject it while a
frame's run-to-completion event drain is still open. That guard is core's rather than
Fresco's, and
[Spec 006 §Render-batch finalization](../../../spec/006-ReactiveSubstrate.md#render-batch-finalization--the-host-checkpoint-boundary)
states it.

### The second closer — a synchronous host commit must be able to close the window

A dirty mark is **constant work**, and it schedules no host render work at all: it records
the cause, enrols the cell in the open pending window, and arms the microtask. Nothing a
React scheduler can see has happened. `react-dom/flushSync` returns as soon as the work
scheduled *inside its callback* has committed — so with the microtask as the only closer it
would commit nothing, report nothing, and leave the DOM showing the old value of a state
write made inside it. The caller who reaches for a synchronous flush is exactly the caller
about to measure layout, move focus, set a caret, or hand off to imperative third-party
code, and that caller would get a stale page in silence.

**The microtask closer stays, and must.** Making a mark notify synchronously
would recompute and re-render inside the source write — the trap invariant 6 exists to
forbid — and would dissolve the coalescing every batch here rests on. The remedy is a
**second closer**, not a different first one.

On a host that owns a render scheduler the window is therefore armed with **two** closers,
both by the same first mark, both running the **same idempotent flush**. Whichever the host
runs first closes the window; the other finds it empty and does nothing. The third item in
the enumeration above — the explicit headless/test flush — is a caller's door rather than a
scheduled checkpoint, and nothing here touches it.

1. **The host microtask** — the guaranteed closer, armed first, and the owner of the
   ordinary batch.
2. **The host render checkpoint** — a render the host's own scheduler performs, whose
   *synchronous* commit phase closes the window. The CLJS realisation renders one
   nil-returning sentinel into a detached
   React root and closes the window from a **layout** effect: `flushSync` always runs
   layout effects synchronously, which is a documented React guarantee, whereas its
   flushing of *passive* effects is a React-19 implementation detail and not one.

Lane behaviour does the arbitration, which is what lets two closers coexist without a rule
to order them. **Inside a synchronous flush** the sentinel's update is issued during the
callback and takes the synchronous lane, so the host renders it, runs its layout effect,
closes the window, and flushes the cells' resulting updates before the flush returns — the
DOM is current on the next line. **Outside one** that update takes an ordinary lane and the
host reaches it later, by which time the microtask closer — armed first, and therefore
first out of a FIFO queue — has already closed the window and the sentinel's effect finds
nothing pending. Batching outside a synchronous flush is bit-for-bit what the microtask
alone would do.

Two ordering rules make that safe rather than merely true, and both are normative:

- The guaranteed microtask closer is armed **first**, and the host closer is
  **best-effort**, armed inside a guard. A mark is a notification sink with no caller to
  report a scheduling failure to, so a host closer that throws must never cost the window
  its guaranteed one.
- A host with no render scheduler installs **no** second closer and is unaffected. The
  headless (JVM/SSR) host is that case: it closes its window explicitly.

None of the four guarantees moves. Neither closer can run while the marking stack is still
unwinding — both are armed by the mark, and a synchronous flush runs its callback to
completion before it flushes anything — so a run-to-completion drain still cannot be split
across batches (guarantee 1) and N epochs settled in one drain still coalesce into one
batch (guarantee 2). Guarantee 3 is **permissive**: drains finishing before the same host
checkpoint *may* share a batch, so an earlier close spends latitude the guarantee already
grants rather than breaching it. Guarantee 4 is untouched — drains separated by a real host
yield still render separately. The second closer changes only *which* checkpoint may close
a window a **synchronous commit** is waiting on.

The consumer-facing consequence is the whole point of the mechanism: **a state write made
inside a synchronous host flush is committed to the DOM before that flush returns.** The
next line may measure layout, move focus, set a caret, or assert on the DOM, and read the
value it just wrote.

## The atomic shell

> **Status: normative.** Fresco's commit: everything
> [§The six frozen invariants](#the-six-frozen-invariants) forbid a render to do, the
> shell is what does at commit. Fresco's declaration, authoring and semantic surface is
> [`authoring-contract.md`](authoring-contract.md)'s; this section owns only the
> reactive commit.

A **mounted boundary occurrence** owns one **cell**. A render produces a **candidate** —
a value the renderer holds — and the candidate is the unit of everything a render might
later publish.

### The selected render bundle

A render against one exact frame incarnation produces a candidate bundle:

```clojure
{:body-revision      revision
 :frame-incarnation  frame
 :dependencies       candidate-dependencies
 :events             candidate-event-sites
 :evidence           candidate-evidence}
```

The bundle is published **entirely or not at all**, and only when the render is SELECTED
for commit. Publication is one synchronous step with no intervening host yield, so no
observer can see a boundary whose dependencies came from one render and whose event
bodies came from another. Subscription ownership, callback targets, and evidence are
therefore always from the same generation of the same body.

Commit **acquires before it releases** (invariant 3 above), so a node that both the prior
and the new dependency set use never falls through its zero-owner disposal edge. After
publication, and only then, the superseded handles are released.

The candidate is a **value the renderer holds**, never an ambient slot the commit reads
back. That is a structural requirement, not an implementation preference: a render is
abandoned by dropping its candidate, so an abandoned render is unable to publish rather
than prevented from publishing. A "current render" module global or thread-local would
make abandonment a flag somebody has to clear correctly, and would put the JVM and
ClojureScript hosts on two different mechanisms for one law.

### Abandoned and failed candidates

A candidate that is never selected publishes **nothing at all** — no ref-count, no watch,
no cache node, no live event site, no evidence. This holds however many speculative
renders precede the selected one, which is what makes concurrent rendering, StrictMode's
double render, and a time-sliced tear-off ordinary rather than special.

A candidate whose reconcile **fails** publishes nothing either. Acquisition failure
unwinds **transactionally**: the staged handles are released in reverse acquisition order,
the prior committed set stays installed
with its published values, and the typed error propagates. The same rollback covers a
failure in the **commit-side evidence reads**: a commit-side read is fail-loud and
may re-enter application subscription code, so it runs inside the pre-publication
transaction, and a read that throws before the publish swap releases every handle this
candidate freshly acquired — reverse acquisition order — leaves the prior committed set
untouched, and rethrows the original failure unchanged. A partly-owned cell — one whose
dependencies came from two different renders — is not a state the shell can reach.

### Body authority across a live cell

A candidate rendered against a body revision the cell has since replaced is **`:abandoned`**
and publishes nothing. Authority is checked at **two** points, and a substrate's hot-reload
fence MUST fix both: once at commit entry, before any ownership is touched, and once again at the
narrowest publication boundary — after the callback-capable staging work, with nothing
callback-capable between the check and the publish. A candidate abandoned at the second
point releases **only** what its own staging acquired.

The live cell the fence protects is the **compatible** reload, and it is the ordinary
case. A declared view is a descriptor VALUE, so an edit mints a fresh descriptor object;
but the emitter keys its boundary on the qualified view id, not on that object, and hands
the host the component type it already mounted. The cell and everything below it survive,
and only the published body revision advances. That surviving cell is what makes a
candidate rendered against the superseded revision reachable at all — abandoning it is the
fence's whole job.

A reload sidesteps the fence only when the redefinition is **incompatible** — when it
moves the boundary's hook skeleton, the ordered set of host hooks its shell owns. Host
hook state is positional, so reusing the boundary across a moved skeleton would let the
new shell read the old occurrence's slots; the redefinition mints a new boundary
instead, so the host remounts once, cleanly, and the old cell's teardown releases
exactly what the old body owned.

### Frame binding and retarget

A view is bound to its frame through the shared frame context, and the **shell observes
that context unconditionally**. A provider retarget from frame A to frame B must
rebind a child even when the child's own props are equal, so the shell must be a real
context CONSUMER — a private-slot read resolves the right value but subscribes to nothing,
and the host correctly bails a non-consumer whose props did not move, leaving its
dependencies locked to A.

Retarget is exactly a re-commit against a different frame. The whole bundle moves: the
dependencies are re-resolved and acquired against B before A's are released, and the
committed event destination becomes B — without changing one callback identity, because
identity belongs to the site and the destination belongs to the commit.

### Occurrence identity across a reorder

Each occurrence owns its own cell, and a commit publishes into that cell and no other.
Two sibling occurrences carrying equal props and equal event intent still own two
independent bundles, so their dependencies, callback identities, and per-site state never
merge.

A **keyed reorder moves occurrences rather than rebuilding them**: the boundary, its cell,
and its ownership travel with the key. Within one boundary, a reorder that makes its own
sites trade targets disposes neither node, because the reconcile's acquire-before-release
covers the whole site set at once.

### Disconnect and replay

Disconnect deactivates exactly what the selected commit owned by that exact generation:
every dependency released synchronously (so each node reaches its zero-owner edge), every
published callback retired to inert, and the cell removed from the pending notification
window.

Disconnect is **not terminal**. A host may tear an effect down and run it again for the
same committed render — StrictMode's mount/cleanup/mount, and a hidden subtree later
revealed — and that replay must RECONNECT. It reconnects from a clean slate: fresh
acquisition against a freshly resolved node, never resurrection of a released handle.
What keeps a genuinely dead boundary from re-acquiring is that a host runs no commit for
a boundary it has torn down, which is structural and needs no flag.

### Same-render-thread capture

A render's reads are recorded on the render's own thread and on no other. A read reached
through `future`, `pmap`, `bound-fn`, or any other conveyed child thread **fails before it
probes**, so a forked read performs no observation work and records nothing.

The rule is enforced rather than engineered around because the alternative is silent: a
conveyed capture is mutated concurrently, sites are lost, and a capture with sites missing
reaches commit as MISSING OWNERSHIP. Refusing an unsupported parallel render body is
strictly better than losing dependencies quietly. The owning thread travels INSIDE the
captured value, because conveyance is precisely the mechanism at fault — a check held in
a dynamic var or a thread-local would be conveyed along with the capture and agree with
itself.

A read outside any active render is refused for the same reason: it has no owner, so
nothing would ever release it. Non-reactive callers use the frame-explicit one-shot read
([Spec 006 §`subscribe-once`](../../../spec/006-ReactiveSubstrate.md#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value)),
which resolves, probes, returns, and releases without installing a dependency.
