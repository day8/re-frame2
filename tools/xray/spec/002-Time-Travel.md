# 002-Time-Travel

> **Tombstone — this panel was removed (rf2-qy0nu).** Xray no longer
> ships a standalone Time-Travel panel (the scrubber rail, pin chips,
> the failure-modal flow, and `:rf.xray/last-restore-failure` described
> by the old draft are gone). This file is retained as a redirect so
> existing links still land somewhere useful; do **not** read it as a
> live panel spec. The surviving epoch / time-travel primitives now
> live at three homes:
>
> - **Runtime contract** — [Tool-Pair §Time-travel: epoch snapshots and undo](../../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo).
>   The per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the
>   `(rf/configure! {:epoch-history {:depth N}})` knob, the
>   `register-epoch-listener!` / `unregister-epoch-listener!` listener
>   API, the `restore-epoch` rewind with its documented failure modes,
>   and the redaction hook — all shipped in `day8/re-frame2-epoch`.
> - **Xray id catalogue** — [014-Registry-Catalogue.md §Time-travel scrubber](./014-Registry-Catalogue.md#time-travel-scrubber).
>   The surviving Xray-side ids: `:rf.xray/selected-epoch-record`,
>   `:rf.xray/select-epoch` (passive scrub), `:rf.xray/reset-to-epoch`
>   (confirmed rewind), `:rf.xray/sync-epoch-history`, and the
>   `:rf.xray.fx/restore-epoch` effect.
> - **Where it surfaces in the UI** — [018-Event-Spine.md](./018-Event-Spine.md).
>   The walk-history-without-disturbing-the-live-app affordance is now
>   the event spine: passive epoch focus through the Layer-2 event list
>   and the spine binding (`:rf.xray/focus`), with the per-epoch lens in
>   the §5.1 Epoch panel and the confirmed-rewind affordance on the
>   tab-ribbon.

The design intent that motivated this file — **inspection is the
default, rewind is opt-in** (the key inversion from re-frame-10x v1's
`app-db-follows-events?` mode) — survives in the spine: scrubbing the
event list rebases every panel's view passively, while
`(rf/restore-epoch! …)` is reached only through an explicit, confirmed
affordance. See the three homes above for the current, normative shape.
