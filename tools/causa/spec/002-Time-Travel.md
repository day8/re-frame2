# 002-Time-Travel

The time-travel scrubber is pinned to the bottom rail. Its job is to
let the programmer walk backward and forward through history *without
disturbing the live app*. Rewinding the runtime is an explicit,
confirmed action — not a side-effect of scrubbing.

This is the key inversion from re-frame-10x v1. v1's
`app-db-follows-events?` mode rewound the app as the user scrubbed;
users accidentally rewound and were surprised. Causa's posture:
**inspection is the default, rewind is opt-in.**

## Substrate

Causa consumes Tool-Pair's epoch-history surface (per
[Tool-Pair §Time-travel](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo)):

- `(rf/epoch-history frame-id)` — vector of `:rf/epoch-record` values,
  oldest-first, bounded by `(rf/configure :epoch-history {:depth N})`
  (default 50).
- `(rf/restore-epoch frame-id epoch-id)` — rewinds `app-db` to the
  named epoch's `:db-after`. Emits `:rf.epoch/restored`.
- `(rf/reset-frame-db! frame-id db-value)` — bypasses cascade,
  schema-validates against current schemas. Used for "try anyway"
  when restore would fail.

No new instrumentation; the scrubber renders what the framework
already records.

## UI shape

The bottom rail (40px on cosy density):

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ◀◀ ─────────────────●──── ▶▶   :app/main   11/11 epochs   8.2KB   ⚠ 0   ●●●    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **Track** — horizontal, one tick per epoch in the current frame's
  `epoch-history`. Each tick is 4px wide on cosy density.
- **Current position** — a violet filled circle. Slides with drag.
- **`◀◀` / `▶▶`** — jump to oldest / newest.
- **Right side** — frame name, epoch counter (`current/depth`),
  scrubber buffer size, issues count, machine-activity dots.

The track is **draggable**. Dragging is a **passive** action — see
below.

## The passive-scrubbing rule

Dragging the scrubber rebases every panel's view of history. The
event-detail panel rebases to the dragged-to epoch. The causality
graph highlights the selected node. The app-db panel shows the
historical snapshot.

But `(rf/get-frame-db ...)` continues to return the live value. The
live app behind Causa does **not** rebase visually. The user's mouse
clicks against the app still hit the live state.

**To actually rewind:** a contextual `Rewind here` button appears
200ms after a drag ends. Clicking it calls `(rf/restore-epoch frame-id
target-epoch-id)`. Only then does the framework's `app-db` actually
move.

The same separation holds for keyboard navigation:

| Key | Action |
|---|---|
| `[` | Step the view back one epoch (rebases panels; does **not** rewind). |
| `]` | Step the view forward. |
| `Shift+[` / `Shift+]` | Previous / next cascade root. |
| `0` | Jump view to oldest epoch. |
| `$` | Jump view to newest. |
| `Space` | Toggle play (auto-step at 500ms intervals; passive). |
| `r` (on a focused event) | Rewind to before this event — calls `restore-epoch`. |
| `Shift+r` | Hard rewind with the six failure modes surfaced as a modal. |
| `*` | Pin a labelled snapshot at current epoch. |

The mnemonic split: walk = passive, rewind = explicit. `r` does the
destructive thing; arrow keys / `[` / `]` do not.

## Restore failure modes

`restore-epoch` has six named failure modes (per Tool-Pair §Time-travel).
Causa surfaces them structurally:

| Failure | `:operation` | UI surface |
|---|---|---|
| **Unknown frame** | `:rf.error/no-such-handler` (`:kind :frame`) | Inline error in the rewind modal; "frame `<id>` is not registered." |
| **Unknown epoch** | `:rf.epoch/restore-unknown-epoch` | "Epoch aged out of history. Increase `:epoch-history :depth` in Settings." |
| **Schema mismatch** | `:rf.epoch/restore-schema-mismatch` | "This rewind would break because schema `:auth` tightened since the snapshot." Lists `:failing-paths`. Offers `Try anyway` (calls `reset-frame-db!` which bypasses cascade and re-validates against current schemas). |
| **Missing handler** | `:rf.epoch/restore-missing-handler` | "The recorded app-db references handlers that are no longer registered." Lists missing `{:kind :id}` pairs. |
| **Version mismatch** | `:rf.epoch/restore-version-mismatch` | "A machine definition moved forward since this snapshot." Names the machine + recorded vs current version. |
| **Concurrent-drain rejection** | `:rf.epoch/restore-during-drain` | "Wait for the current cascade to settle." Auto-retries once after 100ms. |

All six are surfaced as a modal **before** the user confirms the
rewind, with a `Cancel` and (where applicable) a `Try anyway` button.
The modal cites the `:operation` keyword so the user can search the
spec.

## Pinned snapshots

At any epoch, the user can **pin a snapshot** — a named reference to
that point in history — via `*` (per [`007-UX-IA.md`](./007-UX-IA.md)
§Scrubber) or the pin icon on the scrubber. Pins are how the
programmer marks "here is the point I want to come back to": before a
login flow, immediately after a checkout submits, the epoch the bug
first reproduces, the cascade I want to re-run from.

This affordance is peer-landscape minimum-table-stakes: Reactime's
snapshot graph and fulcro-inspect's named snapshots both ship it.
Causa's version stays inside Lock #4 (no session export — see below).

### What a pin captures

A pin is the **4-tuple** `(epoch-id × frame-db-value × dispatch-id × user-label)`:

| Slot | Source | Why it's pinned |
|---|---|---|
| `:epoch-id` | `:rf/epoch-record :epoch-id` ([Spec-Schemas §`:rf/epoch-record`](../../../spec/Spec-Schemas.md#rfepoch-record)) | The opaque history key the scrubber and `restore-epoch` both address. |
| `:frame-db` | `:rf/epoch-record :db-after` | A direct handle to the value the user marked — survives if `epoch-history` ages the slot out of the ring buffer. |
| `:dispatch-id` | `:rf/epoch-record`'s cascade root (`:tags :dispatch-id` on the trigger event, per [Spec 009 §Dispatch correlation](../../../spec/009-Instrumentation.md#dispatch-correlation-dispatch-id--parent-dispatch-id)) | Links the pin to the cascade that produced the epoch, so "Open in Causality graph" stays correct after deeper history scrolls past. |
| `:label` | User-supplied string (the prompt that opens when `*` fires) | What the programmer reads on the scrubber. Defaults to `pin-<n>` (incrementing per session) if the user dismisses the prompt. |

The capture is **eager**: pinning copies the four slots into Causa's
in-memory pin store at pin time. Pins survive the ring-buffer
ageing-out the underlying epoch — the pin retains
`:frame-db` so "Reset to pinned" still works after the epoch itself
has dropped off the scrubber.

The `:frame-db` snapshot is the same `app-db` value the framework
already retained for `:db-after`; pinning takes a fresh reference,
not a copy. Cost is one map entry plus a string per pin.

### Pins on the scrubber

Pinned snapshots appear as labelled chips on the scrubber:

```
◀◀ ──[before-login]─────[cart-full]──●──── ▶▶
```

If the underlying epoch has aged out of `epoch-history`, the chip
renders **detached** (no tick mark on the track):

```
◀◀ ──[before-login]·         [cart-full]──●──── ▶▶
       (aged-out)
```

The detached chip is still clickable; the pin's `:frame-db` is still
restorable via "Reset to pinned" (below). The detached marker is the
visible signal that the pin out-lives the ring buffer.

### Pin actions

- **Click a pin chip** → the **view** rebases to that pin's epoch.
  Passive — per §The passive-scrubbing rule, the live `app-db`
  does not move. The detail panel, App-DB Diff panel, and Causality
  graph all rebase to the pin's `:epoch-id`.
- **Right-click a pin chip** → action menu:
  - **Reset to pinned** — confirmed rewind. Calls
    `(rf/reset-frame-db! frame-id pin.frame-db)` (per [Tool-Pair
    §Pair-tool writes — state
    injection](../../../spec/Tool-Pair.md#pair-tool-writes--state-injection);
    schema-validates against the **current** schema set). The pin's
    label is included in the confirmation modal so the user reads
    "Reset to `before-login`?" — not a bare hash.
  - **Rename pin** — inline edit of the label. The 4-tuple's other
    slots are immutable; rename rewrites only `:label`.
  - **Remove pin** — drops the pin from the in-memory store. No undo
    (pins are cheap to recreate).
  - **Copy pin reference** — copies the 4-tuple as edn to the
    clipboard, for pasting into a `bd` bead or co-pilot question.

`Reset to pinned` is the **only** pin action that writes to `app-db`.
Click-and-right-click `Reset to this pin` is intentionally a
two-action affordance — per [`Principles.md`](./Principles.md)
§Read-only by default, mutate by confirmation. Single-click on a pin
is the inspection mode; the destructive action requires the
right-click → menu path.

### Why `reset-frame-db!` not `restore-epoch`

"Reset to pinned" calls `reset-frame-db!`, not `restore-epoch`,
because the pin's `:frame-db` is **value-direct**: there is no
epoch-id lookup against `epoch-history` that could miss (the pin
holds the value directly, so it works even after age-out).
`reset-frame-db!` bypasses the cascade, schema-validates against
the current schemas, and records a synthetic epoch ([Tool-Pair
§Pair-tool writes — state
injection](../../../spec/Tool-Pair.md#pair-tool-writes--state-injection))
so subsequent scrubbing distinguishes the pin-reset from a regular
cascade.

`restore-epoch` is the right call for the **arrow-key / `r`** path,
where the user is rewinding to an epoch the ring buffer still
holds; that path replays through the framework's epoch-lookup machinery
and surfaces all six restore failure modes (per §Restore failure
modes). The pin path skips that machinery deliberately — pins are
"the value I marked, fetched from my memory of the run, not from
the ring buffer."

A schema-mismatch on `reset-frame-db!` surfaces as a structured
error trace the same way; the failure modes are the
`reset-frame-db!`-side modes (per [Tool-Pair §Pair-tool writes — state injection](../../../spec/Tool-Pair.md#pair-tool-writes--state-injection)),
not the `restore-epoch` six. The modal cites the operation kind so
the user can search the spec.

### Session-scoped — pins do not survive reload

Pins are **session-local**. They live in Causa's in-memory pin
store, **not** in localStorage, **not** in `app-db`, **not** on
disk. Reload clears them.

This is intentional and load-bearing — Lock #4 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md): **Sessions are
session-scoped. No export, no import.** Persisting pins across
reloads would be a partial session-export — and partial-export is
the worst kind: the snapshot reference survives but the
machine-registry, the active flows, the trace buffer do not.
"Reset to pinned" against a stale registry produces a corrupted
runtime; refusing to persist sidesteps the corruption surface.

The pin store is per **Causa instance** — pop-out windows
([`011-Launch-Modes.md`](./011-Launch-Modes.md)) share the
parent's pin store; standalone-via-MCP attachments are a separate
instance with their own pin store.

When the user reloads, the scrubber renders without pin chips and
the pin store starts empty. The empty-state hint on the scrubber's
first frame after reload reads "Pins are session-local; press `*`
to pin the current epoch."

### Pin store capacity

Bounded at **32 pins per frame**, configurable via Settings →
`pin-store-capacity`. Adding a 33rd pin drops the oldest pin with a
toast notification ("Pin 'before-login' aged out — pin store
full"). 32 was chosen empirically — sessions with more than ~10
pins surface a UI density problem; 32 is the cliff at which we say
"the user is using pins as session-export, which Lock #4 says
**no**."

Per-frame, not global: each frame's scrubber has its own pin
store. Switching frames switches the pin set (consistent with the
scrubber's per-frame binding — per §Cross-frame scrubbing below).

## Buffer fill indicator

The right side of the bottom rail shows `11/50 epochs` — current
buffer fill versus configured depth. As the fill approaches the cap,
the indicator goes amber. Hover → tooltip: "Older epochs will age out.
Increase depth in Settings."

The default depth is 50 (per Tool-Pair). Settings → Performance →
`epoch-history-depth` lets the user bump to 200 or 1000 for deep
sessions. The setting calls `(rf/configure :epoch-history {:depth
N})` live; the new depth takes effect on the next epoch.

## The read-only constraint

Causa never writes to `app-db` outside of explicit user-confirmed
rewinds. Specifically:

- **No in-place app-db editing.** The app-db panel is read-only (lock
  #3). The runtime is the source of truth.
- **No silent rebases.** Scrubbing the view never moves the runtime.
- **No automatic rewinds.** Causa never says "I see you scrolled —
  let me rewind for you."

The only writes Causa issues are:

1. `(rf/restore-epoch ...)` — confirmed via `r` shortcut or `Rewind
   here` button.
2. `(rf/reset-frame-db! ...)` — confirmed via `Try anyway` in the
   schema-mismatch modal.
3. `(rf/dispatch ev opts)` with `:origin :causa` — confirmed via
   right-click → "Re-dispatch" on a graph node or event-log row.

Every write fires a synthetic visible trace event (`:origin :causa`),
so subsequent inspection clearly distinguishes Causa-issued state
changes from app-issued ones.

## Cross-frame scrubbing

The scrubber is **per-frame**. Switching the frame picker re-binds the
scrubber to the new frame's `epoch-history` and resets the position to
that frame's newest epoch.

Cross-frame cascades (where dispatch in frame A triggers dispatch in
frame B) still render in the causality graph regardless of which
frame's scrubber is active. The graph spans frames; the scrubber does
not.

## Production elision

Per Spec 009 §Production builds and Tool-Pair §Time-travel, the
epoch-history machinery is gated on `re-frame.interop/debug-enabled?`
(aliased to `goog.DEBUG`). Production builds elide the entire surface;
the scrubber renders an empty state ("no epoch history available; this
is a production build") if mistakenly loaded.

CI's `npm run test:elision` job verifies the contract.
