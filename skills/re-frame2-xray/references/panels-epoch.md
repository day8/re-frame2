# panels-epoch — one dispatch: the Epoch cascade, Trace rows, and where issues surface

The "what happened?" family: the **Epoch** tab (the readable cascade),
the **Trace** tab (the raw rows underneath it), and the three inline
channels that carry issues. All focused-epoch surfaces — pick the frame,
click the event row in the L2 list, and they rebind. Inventory + scope
matrix: [panels.md](panels.md).

## Epoch — the focused dispatch's cascade

Question: **What happened in this epoch?** Default landing tab.

A **numbered vertical cascade**, top-to-bottom — a faithful projection of
the epoch's trace stream. Each step is **conditional**: it renders iff
its driving trace events surfaced this epoch, and steps are numbered
dynamically 1..N, so an absent optional step consumes no number (absence
is conveyed by omission, not an empty-state row). The step order:

1. **DISPATCH** — always present. Event vector, origin tag, call-site
 (open-in-editor).
2. **RECORDABLE COEFFECTS** — conditional: the dispatch envelope's
 recordable cofx leaves (privacy-summarized), filtered to the handler's
 *declared* recordable cofx ids. Omitted when the envelope carried none.
3. **COEFFECT** — one numbered step per handler-declared coeffect
 (framework defaults are filtered out, so the lens shows only declared
 leaves). A cofx supplier that threw gets a synthesised placeholder step
 so its exception card has a home.
4. **INTERCEPTORS** — conditional: the *authored* interceptor chain read
 from the registry, rendered whenever the event carries authored
 (non-default) interceptor refs — clean or throwing. Rows carry resolved
 metadata, per-dispatch override substitutions, and missing-ref rows.
5. **INTERCEPTOR** — conditional, exception-only: one row per *throwing*
 interceptor (id + phase chip + the shared Exception card),
 **phase-split** around the handler — a `:before` throw renders BEFORE
 the handler, an `:after` throw AFTER it.
6. **EVENT HANDLER** — always present; the body adapts to **what the
 handler returned** (there is one public `reg-event`, no `:db`-vs-`:fx`
 registrar flavour): only `:db` → a `:db` diff · `:db` + `:fx` → diff +
 per-fx · a `reg-machine` event → the time-ordered machine cascade.
 Rendered **SKIPPED** (⊘) when an upstream `:before`-chain throw aborted
 the cascade before the handler ran (NOT "ran, returned no :db").
7. **FLOW** — one numbered step per flow that fired (the reshape as the
 flow's own `:db` diff). Only when flows fired.
8. **EFFECT HANDLERS** — the flat per-effect ledger (below). Only when a
 side effect occurred; equally SKIPPED on an upstream abort.
9. **SUBSCRIPTIONS** — only when subs recomputed.
10. **VIEWS** — only when views re-rendered.

**Per-step status + inline exceptions.** Every step header carries `✓`
(ok) / `✗` (error) / `⊘` (skipped). A handler / interceptor / coeffect /
fx / flow exception renders UNDER the step where it occurred via the
shared **"Exception Thrown"** card; the epoch's outcome reads error
whenever any step settled error. Schema violations attach inline the same
way.

**Open when:** "what did this event do?", "where did the cascade fail?",
"what fx fired?", "did the flow recompute?"

Spec: [`021-Dynamic-Panel-Designs.md` §9.1](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/021-Dynamic-Panel-Designs.md).

### The EFFECT HANDLERS step — flat per-effect ledger

One row per effect, in execution order, no group headers:

- Each row leads with a status glyph: `✓` ran ok · `✗` threw /
 no-such-fx / `:db` schema-fail rollback · `↺` fx override applied · `–`
 skipped-on-platform or dropped (neutral — never trips the badge).
- A single AND-of-rows badge after the step label: TICK when every
 present row succeeded, CROSS when any failed; skipped rows are neutral.
- **The `:db` row** leads the ledger when a `:db` commit was attempted;
 its args slot is the clickable **"→ app-db"** destination marker (the
 diff itself lives in the app-db panel). Absent when the handler
 returned only `:fx` / nothing / threw — no phantom `:db`.
- fx exceptions attach to the owning row; a `:db` schema-fail rollback
 paints the `:db` row ✗ with the reason box, and a rollback means `:fx`
 never walked (Spec 002 atomicity) — the ledger then carries only the
 red `:db` row.

## Trace — the raw rows underneath

Question: **What raw trace events fired during this epoch?**

The stream the Epoch and Views tabs summarise, **focused-epoch scoped**,
rendered as a single flat oldest-first row list. Each row carries a stage
column (DISPATCH · COEFFECT · EVENT HANDLER · FLOW · EFFECT HANDLERS ·
SUBSCRIPTIONS · VIEWS) + a colour-coded left edge that match the Epoch
cascade's step model, so the two tabs tell one story. Two usage facts:

- **No filtering UI** — the focused epoch IS the scope; the only
 drill-down is per-row click, which expands the row's raw trace-event
 map inline. (Spec 009's programmatic trace-buffer filter vocabulary is
 real for the API but is not Trace-panel UI.)
- **No film-strip header** — the L2 events list owns spine navigation;
 this tab opts out of the shared `[◀ Prev] [Next ▶]` header.

**Open when:** "show me every raw op in this epoch", "is `:rf.fx/*`
firing as expected?", "what order did these emit in?"

Spec: [`023-Trace-Panel.md` §3](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/023-Trace-Panel.md).

## The L2 timeline grammar

The L2 event spine above the panels carries the cross-epoch signal:

- **Dispatch-origin prefix glyph** per row — `:router` (R) · `:http`
 (🌐) · `:ssr` (💧) · `:fx-emit` (⚡) · `:timer` (⏲) · `:test` (T) ·
 `:tool` (🔧) · `:machine-spawn` (i); app-code origins render no prefix
 (the common case).
- **Activity badge cluster** per row — `⚠` issue · `◆` machine
 transition · `🌐` HTTP activity · `⚡` fx-emit child dispatch · `⏲`
 timer-triggered. High-contrast remap is automatic (colour is never the
 only signal).
- **Issue pink-wash** per row — a cascade carrying an issue washes its
 whole L2 row pink. Together with the Epoch cascade's per-step ✓/✗ this
 is the primary "which epochs are broken?" signal.

## Issues — inline, not a tab

There is **no dedicated Issues tab** and no session-wide triage list.
"What's wrong?" is answered through three always-on channels:

1. **Inline in the Epoch cascade** — per-step ✓/✗, the "Exception
 Thrown" card under the throwing step, the `:db` rollback row. Errors,
 warnings, schema violations, and hydration mismatches each surface
 against the step where they occurred.
2. **The L2 pink-wash** — "which epochs are broken?" at a glance.
3. **The always-on issues-ribbon signal** — drives the
 auto-open-on-error watcher; the cross-epoch "something is wrong" cue.

Route "anything broken in this epoch?" to the **Epoch tab**; "which
epochs are broken?" to the **L2 pink-wash**. (A11y is not an Xray
surface — it is Story's domain.)
