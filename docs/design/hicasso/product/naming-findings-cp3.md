# Naming findings — Checkpoint 3 fragment

> **Currency, 2026-09-04 (`rf2-ekxx`). Every surface this fragment asks a naming question
> about has been deleted, so nothing below is a live question for the sitting.** The
> questions and their recommendations are kept as the record of what Checkpoint 3 met on
> 2026-08-14; they are not withdrawn and not re-scored, because a recommendation that was
> sound about a surface stays sound about that surface.
>
> **The event this fragment predates.** On 2026-08-29, `aa01f0e8a6` (`rf2-6c12m.31`, wave 2
> of ruling `rf2-6c12m.3` Option A) shrank `re-frame.hicasso.native` from 1,116 lines to 82.
> **The whole public surface that survives is `use-sub` and `use-frame`.** So C3-1's six
> unrostered vars — `n/component`, `n/marker`, `n/prop-slots`, `n/props*`, `n/el` and
> `n/declared-server` — are gone, and with them C3-1's *classify before the packet* question
> and C3-2 entirely: there is no `n/declared-server` left to be surface or not, and the
> sentence below saying it *is still public* is false at tip. The taught names the headline
> lists as already rostered — `n/$` (row 7), `n/props` (row 8), `n/defcomponent` (row 9),
> `n/memo`/`n/lazy` (row 29) and the `:server` policy option (row 21) — went in the same
> commit; only row 10's `n/use-sub`/`n/use-frame` still names anything.
>
> **The witnesses too.** The eight `native_*` suites were deleted in that commit, so
> `native_abi_dom_cljs_test.cljs`, `native_grammar_cljs_test.cljs` and
> `native_ssr_dom_cljs_test.cljs`, cited below in the present tense, name no file at tip.
> The C3-1 line numbers into `native.cljc` are likewise historical — the 2026-08-14 note
> already says they are deliberately left as its own reading found them.
>
> **What a reader should carry forward.** Checkpoint 3 froze no name, and the reason it gave
> is now overtaken by a stronger one: the Phase 3 exit it was conditioned on is not met, and
> the tier it would have frozen no longer ships. [`checkpoint-3-native.md`](checkpoint-3-native.md)
> — the evidence this page reads — carries its own currency banner recording the same event.
> [`naming-packet.md`](naming-packet.md) does **not** yet, and its native rows still cite
> these suites; that is filed as `rf2-pvmy` rather than repaired here.

Every naming question Checkpoint 3 (`rf2-hic-038`) met, written here rather than into
[`naming-ledger.md`](naming-ledger.md) so that concurrent checkpoints cannot collide in one table.
**`rf2-hic-065` consolidates fragments into the ledger and publishes the packet**; nothing here is
applied, and prototype spellings stay in use everywhere until that sitting.

Read with [`checkpoint-3-native.md`](checkpoint-3-native.md), which is the evidence this page reads,
and with [`naming-findings-cp2.md`](naming-findings-cp2.md), the sibling fragment. Checkpoint 3
freezes no name — and, unlike Checkpoint 2, it freezes no law either, because the Phase 3 exit its
freeze is conditioned on is not met.

## The headline, and it is an absence

The native tier's **taught** names all have ledger rows already — `n/$` (row 7), `n/props` (row 8),
`n/defcomponent` (row 9), `n/use-sub`/`n/use-frame` (row 10), `n/memo`/`n/lazy` (row 29), and the
`:server` policy option (row 21, applied). Every one reads *keep*. Checkpoint 3 met no evidence
against any of them and proposes no change to any.

What it did meet is the **other half of the namespace**, which no row covers at all.

## Questions

| # | Surface | Question | Checkpoint 3's recommendation |
|---|---|---|---|
| C3-1 | the unrostered public vars of `re-frame.hicasso.native` | Seven vars are public in the namespace and appear in no ledger row: `n/component`, `n/marker`, `n/prop-slots`, `n/props*`, `n/el`, `n/check-child!`, `n/declared-server` (`native.cljc:194, 242, 299, 316, 357, 470, 512`). They are not uniform in kind. `n/component` and `n/marker` are reached by name from the tier's own witnesses — `native_abi_dom_cljs_test.cljs` mints `(n/component "app/hot-cell" :client-only island-body)` directly, and `n/marker` is the seam every ABI helper and both embedding directions read. `n/el` says of itself "reached from an `$` expansion". `n/prop-slots` is asserted **as a public equality** against the macro's own emission (`native_grammar_cljs_test.cljs`, *the-macro-and-the-runtime-share-one-rule-rather-than-two-copies*). So the set spans doors, seams and expansion targets with nothing marking which is which. | **Classify before the packet, do not rename here.** The question this fragment puts to the sitting is not *what should these be called* but *which of them are surface at all* — and that is prior to naming, because [`specification.md` §12](specification.md#12-action-programme) has Phase 3 freeze "the grammar and ABI", and a freeze cannot be deterministic over a surface whose membership is unstated. The repository already applies this rule elsewhere: a facade export is classified and justified when it lands. Recommended disposition: `n/component` and `n/marker` are **surface** (a witness reaches them and the ABI helpers are defined in terms of `marker`); `n/el`, `n/check-child!`, `n/props*` and `n/prop-slots` are **expansion targets and seams**, public only because a macro expansion must be able to name them, and belong behind the same `impl` convention the rest of the package uses. Filed as `rf2-hic-038`'s quality row rather than actioned, because a checkpoint that edited the surface it audits could not certify it. |
| C3-2 | `n/declared-server` | Public, and it is the validator behind `n/defcomponent`'s `:server` option — the option row 21 already **applied** (`:server`, not `:ssr`). Row 21 settled the *option key*; nothing settled the *validator's own name*, which reads as a predicate ("declared server?") but is a parser returning the declared policy. | **Rename is premature; the classification in C3-1 probably dissolves it.** If it lands behind `impl` it needs no ledger row. If the sitting rules it surface, `declared-policy` states what it returns where `declared-server` states what it was asked. Recorded so the sweep does not read C3-1's disposition as covering the spelling too. |

**[Amended 2026-08-14, by this checkpoint's re-dispatch.]** C3-1's roster is now one var out of
date, and the packet should read it that way rather than re-deriving it. `rf2-e0d2` closed and
landed on `main` as `0b7985af24`: `native.cljc` gained a *The public surface, classified* section,
and the classification was **applied to one member** — `check-child!` is now private, its docstring
recording that this is *a classification rather than a narrowing*, both callers being in the same
file so nothing outside had ever reached it. So the unrostered set is **six**, not seven, and one of
C3-1's four proposed demotions is already taken.

Nothing else in this fragment moves. The recommendation stands for the remaining three demotion
candidates — `n/el`, `n/props*`, `n/prop-slots` — and for the two the fragment calls surface,
`n/component` and `n/marker`; the line numbers in C3-1's cell are the ones its own reading found and
are deliberately left as written. C3-2 is untouched: `n/declared-server` is still public, so the
question of whether it is surface at all, and only then how it is spelled, still goes to the sitting.

## Recorded, and not a naming question

- **No native name changed under Checkpoint 3, and none should.** Rows 7–10 and 29 all read *keep*
  and this checkpoint produces no evidence against them. That is worth stating positively: the
  native tier is the one surface where the prototype spellings were ratified against a real
  three-route corpus (`three_way_parity_cljs_test.cljs` writes nine subjects three ways), and the
  names survived it without a single authoring complaint recorded in the tier's own witnesses.
- **`n/$`'s grammar is not a naming question and is not offered as one.** Checkpoint 3 files
  correctness and coverage findings against the grammar row's *evidence* — the missing
  macro-expansion fixtures and the unwitnessed source-located refusal — and none of them is about
  a spelling. `rf2-hic-065` should read them in [`correction-ledger.md`](correction-ledger.md),
  not here.
- **The `:server` value vocabulary (`:render` / `:client-only`) is settled and this checkpoint
  re-met it without friction.** Row 21 records it applied; the native tier's SSR witness
  (`native_ssr_dom_cljs_test.cljs`) drives both values through real server bytes. No question.
