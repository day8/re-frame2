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

At any epoch, the user can pin a labelled snapshot via `*` or the pin
icon. Pinned snapshots appear as labelled chips on the scrubber:

```
◀◀ ──[before-login]─────[cart-full]──●──── ▶▶
```

Click a pin → view rebases to that epoch. Right-click → "Rewind to
this pin" or "Remove pin."

Pins are **session-local**. They live in Causa's in-memory state, not
in localStorage, not in `app-db`. Reload clears them. (See lock #4
in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).)

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
