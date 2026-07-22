# D016 — Buffered and revision controls

Status: **Ruled**
Ruling: **Use one generation-fenced buffered controller with required
`:reset-key`; editing begins on first edit and cancel semantically clears it.**

Horizon: **Upcoming** — implement during the component-library pilot; the portable
claim remains gated on its browser and JVM evidence

## Decision

What exact state machine and public props should a reusable commit-on-blur control
use, especially when a caller must reject an edit by reasserting the same value?

A simple fully controlled input has no issue:

```clojure
[:input {:value amount-text
         :on-input [:invoice/amount-edited invoice-id ::v/value]}]
```

But some controls must let the user edit a draft and commit on Enter or blur. A
caller may validate asynchronously, transform the value, or reject it. Equality of
the model value cannot communicate every decision. If the accepted value was
`"10"`, the user types `"bad"`, and the caller rejects it by retaining `"10"`, the
control must still know that `"10"` is a *new baseline decision*. Comparing values
cannot reveal that.

The old re-com solution accumulated twin ratoms, same-value blindness,
render-phase resets, forced-reset flicker, and a completion callback protocol. The
absorbed design explicitly deletes `re-frame.ui/local`; this decision must solve
the use case as a re-frame event protocol rather than restore hidden state.

## Settled constraints

1. Fully controlled value-in/intent-out remains the first recommendation.
2. Buffered state, if used, is re-frame controller data as decided by D003; it is
   not a ratom, hook, closure slot, or resurrected `local`.
3. The caller supplies semantic controller identity as decided by D004.
4. A caller revision/reset key is distinct from the model value. It must be able to
   change even when the value is equal.
5. Edit, commit, and cancel intents carry the relevant generation. Acceptance is
   decided against committed state at event time.
6. A view-side event site produces exactly one event vector or `nil`. A controller
   event may return several re-frame effects as one causal transition.
7. Controlled input echo uses the shared synchronous drain law. The protocol must
   survive caret, selection, and IME behavior in a real browser.
8. Enter and blur use the same commit transition. Cancel followed by a racing blur
   cannot resurrect the cancelled draft.
9. Unmount does not commit, cancel, seed, or clear controller data.
10. The contract is identical in interpreted and compiled views and structurally
    testable on the JVM.

## Concrete scenarios

### Ordinary commit

1. External value is `"10"`, reset key is `4`.
2. Focus starts an edit at generation `4` with draft `"10"`.
3. Input changes the draft to `"12"` synchronously.
4. Blur dispatches one commit intent carrying address and generation `4`.
5. The event handler reads the live record at dispatch, emits the caller's commit
   with `"12"`, and ends the edit.

### Same-value rejection

1. The user drafts `"bad"` and commits.
2. The caller rejects it, retains external value `"10"`, and increments reset key
   from `4` to `5`.
3. The control immediately displays external `"10"`, even though it equals the
   baseline from before the edit.
4. A late blur carrying generation `4` is a no-op.

### Escape racing blur

The Escape event marks the edit cancelled/inactive. A subsequent native blur may
already be queued, but its commit handler consults committed state and finds no live
edit. It produces no caller intent.

### External update during editing

If the caller changes `:value` while keeping the same reset key, the current draft
continues. If the caller intends to replace/reject the edit, it must change the
reset key. This distinction must be explicit; otherwise the component guesses.

## Options

### Option A — Two unrelated components

Ship a simple `buffered-field` based on `:editing?` and `:draft`, plus a separate
`revision-field` that stores the caller reset key.

**Consequences**

- The simple component has fewer required props.
- The revision component makes the hard guarantee explicit.
- Two subtly different state machines, event families, docs, and test matrices can
  drift.
- Programmers may choose the simpler component and discover same-value rejection
  only after production behavior fails.

### Option B — One component with optional reset key

Use one state machine. `:reset-key` is optional; when absent, the control provides
ordinary buffering but cannot promise caller-forced same-value resets.

**Consequences**

- There is one implementation and a low-friction entry point.
- The semantics of omission must be very prominent. “Works until the caller
  rejects/transforms” is a dangerous default for a reusable field.
- An optional sentinel can be mistaken for a real revision, weakening stale-event
  fencing.

### Option C — One generation-fenced protocol; reset key required

Every buffered control uses the revision-capable state machine. The public prop is
required. Callers that do not need resets may pass a stable literal such as `0`,
while callers that accept/reject edits increment a meaningful revision.

```clojure
[buffered-field
 {:control [:invoice invoice-id :amount]
  :value amount-text
  :reset-key amount-revision
  :on-commit [:invoice/amount-committed invoice-id]
  :validate parse-amount}]
```

An illustrative controller record is:

```clojure
{:reset-key 12
 :phase :editing
 :draft "19."
 :baseline "18.50"}
```

**Consequences**

- There is one state machine and one acceptance suite.
- Same-value rejection and delayed-event fencing are always representable.
- Every caller must understand one extra prop, even for uncomplicated buffering.
- A stable literal deliberately means “do not externally reset an active edit,”
  which is teachable and testable.

### Option D — Caller-owned draft protocol

Do not ship a buffered controller. Provide examples showing applications how to
store and transition drafts themselves.

**Consequences**

- Freehand and its component library own less code.
- Every application must reproduce generation fencing, IME handling, cancel/blur
  races, and reset semantics.
- A principal re-com problem class remains unsolved by the ergonomic substrate.

### Option E — Host-local/uncontrolled buffering

Keep the draft in the DOM or a React wrapper and report only commit.

**Consequences**

- It reduces per-keystroke re-frame work and can be the correct explicit choice for
  extremely dense editors.
- The draft is not in snapshots, time travel, JVM tests, or re-frame evidence.
- Remount, virtualization, HMR, and caller reset behavior become host protocol.
- It cannot be Freehand's portable buffered-control claim.

## Recommendation

Adopt **Option C** for the portable library control: one generation-fenced state
machine with a required `:control`, `:value`, `:reset-key`, and `:on-commit`.
Retain fully controlled input as the page-one form and Option E as an explicitly
qualified performance escape.

The recommended protocol is:

### Public values

| Prop | Meaning |
|---|---|
| `:control` | stable semantic address for this control |
| `:value` | caller's current committed baseline |
| `:reset-key` | caller-controlled baseline revision; equality with `:value` is irrelevant |
| `:on-commit` | caller event prefix receiving the accepted draft candidate |
| `:validate` | optional pure advisory validation for display; not commit authority |
| `:on-cancel` | optional caller intent if cancellation is domain-significant |

Names remain candidates until the pilot; semantics matter more than spelling.

### Controller facts

The minimum live record is:

```clojure
{:reset-key reset-key
 :draft draft}
```

The external baseline need not be copied unless measurement or error reporting
demonstrates a need. A matching record means editing and renders `:draft`; absence
or a reset-key mismatch renders `:value`. A new reset key therefore exposes the
external baseline immediately. The next edit atomically replaces any stale record.

### Transitions

| Transition | Required behavior |
|---|---|
| edit | atomically create or replace `{reset-key draft}` from the live input value; synchronously commit the frame for controlled echo |
| commit | consult committed state; only a matching record may dispatch caller intent, then clear it |
| cancel | clear the matching record before any later blur; optionally emit caller cancellation |
| external reset | no render-time dispatch; changed reset key makes old draft ineligible immediately |
| disconnect | remove host listeners/joins only; do not perform a semantic transition |

The library's commit event should produce the caller dispatch and controller-state
update as effects of one re-frame event, preserving one epoch and one trace cause.

### Browser details that belong in the contract

- `compositionstart`/`compositionend` must prevent Enter from committing a partial
  IME composition.
- Controlled echo must not replace the input node or reset selection.
- A transformed accepted value may move the caret only when the caller's new
  reset key establishes a new baseline.
- Focus alone creates no controller state; the first actual edit begins the session.
- Blur and Enter are aliases for the same semantic commit operation.
- Repeated commit, cancel, or stale-generation events are idempotent no-ops.

## Consequences of the recommendation

**Benefits**

- It removes the same-value ambiguity by construction.
- The race rules live once in a headlessly testable event protocol.
- Interpreted and compiled controls share the same data and event forms.
- The rendered tree carries a complete, inspectable intent.
- Library consumers do not reproduce re-com's closure and callback stack.

**Costs and risks**

- The reset key is a real caller obligation and needs excellent naming and examples.
- Per-keystroke re-frame epochs may be too costly for dense grids.
- Persisted drafts can accumulate if causal owners never clear them.
- Some input types have browser-specific selection and composition behavior; JVM
  parity cannot replace the mounted browser matrix.
- Validation policy can grow uncontrollably. Keep `:validate` advisory; domain
  acceptance belongs to the caller's event logic.

## Acceptance matrix

The ruling fixes the API; its portable product claim remains gated on this pilot:

| Area | Required case |
|---|---|
| equality | caller rejects by reasserting an equal value with a new reset key |
| stale events | cancel-then-blur, reset-then-blur, and repeated Enter |
| editing | insert, delete, paste, selection replacement, caret in middle |
| IME | composition start/update/end and Enter during composition |
| concurrency | unrelated heavy sibling remains dirty while typing |
| multiplicity | many independent fields and duplicate-address diagnostics |
| lifecycle | temporary absence, virtualization, reconnect, and explicit owner clear |
| development | HMR during an edit and demotion from compiled to interpreted |
| frames | alternate frame isolation and retarget fencing |
| testing | JVM state-machine tests plus real-browser mounted tests |
| performance | p95/p99 event-to-commit latency and allocation per keystroke |
| parity | equal semantic tree and intent in both execution modes |

If this matrix fails on correctness, the protocol must change. If it fails only on
measured dense-editor cost, retain the portable controller for ordinary forms and
document the uncontrolled/wrapper escape rather than reintroducing `local` globally.

## Dependencies and unlocks

- **Depends on:** [D003](D003-reusable-control-state-model.md) and
  [D004](D004-state-identity-and-addressing.md).
- **Interacts with:** [D017](D017-framework-control-and-policy-vocabulary.md), which
  decides who owns the event ids and whether the controller ships with Freehand.
- **Unlocks:** buffered-field release acceptance, re-com input pilots, the
  controlled-contention benchmark, and deletion-gate evidence for the retired
  `local` use case.
- **Does not decide:** generic state addressing, dropdown/typeahead semantics, or
  a framework-wide validation DSL.

## Sources

- [Codex design](../codex-design.md) — “State ownership”, “Event law”, “Controlled
  inputs”, “Re-implementing re-com”, and “Release acceptance”.
- [Fable design](../fable-design.md) — §2.4 “The consult-state commit law”, §4.1
  “Controlled input with validation — and the buffered/revision fields”, §5.2
  requirements R-A, and §7.3's `revision-field` acceptance assumption.
