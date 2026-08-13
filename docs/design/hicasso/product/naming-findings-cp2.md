# Naming findings — Checkpoint 2 fragment

Every naming question Checkpoint 2 (`rf2-hic-026`) met, written here rather than into
[`naming-ledger.md`](naming-ledger.md) so that concurrent checkpoints cannot collide in one table.
**`rf2-hic-065` consolidates fragments into the ledger and publishes the packet**; nothing here is
applied, and prototype spellings stay in use everywhere until that sitting.

Read with [`facade-freeze.md`](facade-freeze.md), which freezes the ordinary surface's **laws** and
deliberately freezes no name, and [`checkpoint-2-slice.md`](checkpoint-2-slice.md), which is the
evidence both pages read.

## Questions

| # | Surface | Question | Checkpoint 2's recommendation |
|---|---|---|---|
| C2-1 | where a `h/reg-state` concern is **declared** | `reg-state` mints a subscription **and** an event under one keyword, so the keyword has no home in the two-namespace split every ordinary re-frame2 application uses. Declare it in `subs` and every write reads `[::subs/draft id text]`; declare it in `events` and every read reads `(h/sub [::events/draft id])`. Half the call sites read wrong either way. **The two witness applications answered differently**: the slice declares `tags-open?` in `subs` and writes to it as `[::subs/tags-open? slug (not open?)]` (`slice/views.cljs:120`); the Todo class declares it in `db` and both sides read correctly (`todo/db.cljs`, Todo report N2). | Declare it where the **place** lives — `db` — because the keyword names an address, not an action, and that is the only one of the three that reads correctly on both sides. One sentence of convention, no mechanism. **Hold**: [`naming-ledger.md`](naming-ledger.md) row 3 recommends removing `h/reg-state` from the adaptor core, which would dissolve the question. Settle row 3 first. |
| C2-2 | a name for the `:ui` root, or a `db`-level clear | An event handler is on neither side of `reg-state`'s pair. The Todo class's commit handler reads `(get-in db [:ui db/draft id])` by hand because the door exports no name for the `:ui` root, and clears via `:fx [[:dispatch [::h/clear …]]]` because the only public clear is an event (Todo report N3). Candidate mints: `h/state-path`, or a pure reader/clear pair over a `db` value. | **Hold, and do not mint** until row 3 decides `reg-state`'s fate. `impl.state` already carries `ui-root` and `clear-event-id` as named vars, so a mint here is an export decision rather than a design one. Recorded so the sitting sees the demand: two applications, one hand-written literal path each. Note the shape cost is real and the latency cost is not — `dispatch-sync!` drains a seed handler's `:fx` to fixed point, measured in both tiers by the Todo witness. |
| C2-3 | the L3 verb for *work is enqueued in the router; let it land* | The finding both witness applications confirmed has no door. `rf2-jljf` repaired the record; the door itself is `rf2-6m4w`, an open `[OPERATOR-DECISION]`. Candidate spellings named by both authoring reports: `hm/drain!`, or a condition option on `hm/settle!`. | **No spelling recommended yet** — the door is not ruled, and a name for an unbuilt surface is what [`naming-ledger.md`](naming-ledger.md) row 40 calls a reservation rather than a mint. If `rf2-6m4w` rules one in, its bead appends the spelling. Its own analysis narrows the shape and therefore the name: `interop/next-tick` is a macrotask, so no synchronous `drain!` is possible and any honest door needs a **condition and a deadline** rather than a tick count — which is a condition door, not a drain. Note that a `:until` option on `settle!` avoids a new verb entirely and would collide with nothing; `drain!` is a fresh mint in a facade whose other verbs row 24 records as lane-pinned. |
| C2-4 | `::h/clear` in the reserved-data vocabulary | [`specification.md` §4](specification.md#4-target-programming-model) fixes the reserved vocabulary at four — event value, checked value, explicit prevention, controlled revision — and [`dispositions.md`](dispositions.md) HS-07 carries the same four. `::h/clear` is a fifth reserved `:re-frame.hicasso/…` keyword, reached by an ordinary application (the Todo class, twice) and named on the door's own marker list. | Same disposition as [`naming-ledger.md`](naming-ledger.md) row 35 gave `::h/navigate`: **add it to the reserved-vocabulary list rather than rename anything**. What it owes the sitting is a list entry. Unlike `::h/navigate` it is author-written, which is the stronger case for listing it — and it is conditional on row 3, since it exists only to serve `reg-state`. |

## Recorded, and not a naming question

- **`h/boundary` vs `h/error-boundary`.** [`authoring-report-slice.md`](authoring-report-slice.md)'s
  reached-doors table and its narrative both spell it `boundary`; the shipped facade exports
  `error-boundary` and the slice's own source uses `h/error-boundary`
  (`slice/views.cljs:304, 455`). [`naming-ledger.md`](naming-ledger.md) row 12 already settled the
  spelling as **keep**, so this is a stale spelling in a published report rather than an open
  question. Recorded so `rf2-hic-065`'s sweep does not read the report's table as a live candidate.
- **The prototype names on the door.** `hfn` (taught as `h/fn`) and `hframe` (taught as `h/frame`)
  were both reached by neither witness application, so Phase 2 produces no new evidence for
  [`naming-ledger.md`](naming-ledger.md) rows 1 and 18 and this fragment adds nothing to them.
  **That absence is itself the datum**: row 18's *retire* recommendation costs an ordinary
  application nothing, because no ordinary application reached it.
- **`root!`/`render!`/`unmount!`.** Reached by both applications under exactly those spellings.
  [`naming-ledger.md`](naming-ledger.md) row 13's open half (`root!`→`mount!`) is unaffected — two
  applications typing the current spelling is not evidence about the candidate, and this checkpoint
  does not offer it as any.
