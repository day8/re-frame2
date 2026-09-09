# Tool-consumer census — Xray, Story and Pair against the donor surfaces

**rf2-hic-076**, 2026-08-11 05:37:14 AUSEST; row **X13** added and the counts carried through by
`rf2-kqls`, 2026-08-11 07:13:28 AUSEST; **Pair re-measured against merged `main` and the totals
carried through** by `rf2-hic-076` again, 2026-08-11 09:01:41 AUSEST; row **S11** added and the
counts carried through by `rf2-gj0a`, 2026-08-11 10:05:04 AUSEST. The census `rf2-hic-062` cites
before it disposes of the donor tool surfaces.

**Why the third pass, because it is the failure mode this document is most exposed to.** The first
two passes were authored against a tree that moved underneath them. Between the authoring of the
`rf2-kqls` branch and its landing, `rf2-n3mb` merged and took all five of Pair's view tools off the
donor wire and onto `re-frame.hicasso.tool`. The patch rebased cleanly — every hunk applied
unchanged — and that is precisely the danger: **a census is a claim about the tree at a moment, and
a clean rebase preserves the text while silently invalidating the claim.** Nothing in a diff review
can catch it. The only defence is to re-run the measurement, which is why the search command and its
count are now recorded in the document rather than in a commit message.

The obligation is one sentence of the specification
([§12 Phase 6](specification.md#phase-6--adoption-and-release)):

> Move every live Xray/Story/Pair consumer onto the adapter-neutral Hicasso evidence provider before
> disposing of the experimental donor tool surfaces. Fixtures may retain explicit compatibility
> coverage, but production and primary tooling may not retain a hidden dependency.

That sentence has two halves, and this census answers them separately. **Every item is named exactly
once**, with a verdict and the reason for it.

## The three tiers, because "the donor" is two things

| tier | namespace | the reads it publishes | status |
|---|---|---|---|
| donor 1 | `re-frame.ui.tool` | `view-manifest`, `view-dependencies`, `view-event-sites`, `mounted-views`, `explain-render` | EXPERIMENTAL (`implementation/ui/`) |
| donor 2 | `re-frame.freehand.tool` | `read-view-manifest`, `read-view-dependencies`, `read-view-event-sites`, `read-mounted-views`, `explain-render` | donor tree (`implementation/freehand/`) |
| **target** | **`re-frame.hicasso.tool`** | `read-mounted-boundaries`, `read-read-attribution`, `read-intents`, `explain-render` | the adapter-neutral provider (rf2-hic-023) |

Four columns, four cells per row, checked by hand.

**Donor 1 → donor 2 was a rename; donor 2 → the target is not.** The first two rows publish the same
five reads under different names, which is why the earlier crossing cost the consumers only their
`:require` lines. The target publishes **four reads, of which only `explain-render` shares a name**,
and the missing four have no counterpart by design rather than by omission: Hicasso's runtime mints
no boundary identity, so there is no view registry to read a manifest from, and
`re-frame.hicasso.tool` states `:view` and `:source` as
`re-frame.hicasso.evidence/unknown` under an `:opaque` naming projection. A boundary there is keyed
by its **read set**, because that is the only identity the runtime retains.

This is the single most consequential fact in the census, and it is why the still-live rows below
mostly do **not** get a migration in this bead: moving Xray's Views panel onto the target is a
**re-authoring against a different question set**, not a rename. Per the `rf2-6ync` precedent, a
smaller honest set of migrations plus accurate follow-ups beats a sweep.

**Pair is now the worked example of what that re-authoring costs**, and it is the reason the
paragraph above is a statement about provider shape rather than an excuse. `rf2-n3mb` did the work:
five donor tools became **three**, because the three the target cannot answer were retired rather
than shipped answering a question with a fabricated emptiness. That is the honest outcome of a
different question set, and it is available as precedent to every cluster still standing.

## Verdicts, defined once

| verdict | meaning |
|---|---|
| **MIGRATED** | the consumer reads the adapter-neutral provider (`re-frame.hicasso.tool` / `re-frame.hicasso.evidence`). No donor name sits in a load-bearing position. |
| **FIXTURE-ONLY** | the donor is named only from the artefact's `test/` tree, on a `:test`-alias classpath, and its purpose there is compatibility evidence. It executes on every PR — that is the point of a witness — but no path a user or an agent reaches passes through it, so removing the donor costs the witness and nothing else. This is the coverage the specification sentence expressly permits. |
| **STILL-LIVE** | a shipped tool path reaches the donor: an artefact-level `:deps` coordinate, a panel a user sees, an MCP tool an agent calls, a gated browser scenario, or a test whose subject is one of those. |

## How the census was taken, and what the counts answer

**The search, exactly as run — re-run it rather than trusting the number below.** It is
case-sensitive, and it is run from the repository root:

```
git grep -l -E "freehand|re[-_]frame[./]ui" -- tools
```

**The pattern matches artefact names as well as donor namespaces.** `freehand-views` — the staged
deck's own shadow-cljs build id and namespace prefix — is matched by `freehand` and names no donor;
row X7 is a file that enters the result that way and no other.

At commit `e1b4cae5d0` (merged `main`, 2026-08-11) it returns **43 files**. It returned 43 at
`964d71d9f2` as well, so the figure survived a rebase across the intervening commits — which is
worth one line, given what happened last time.

It returned **48** when this census was first taken, and the difference is the whole reason for the
third pass. Eight files left the result and three entered it, a net **−5**, all of them Pair's:
`rf2-n3mb` retired `view_tool.cljs` and `view_tool_test.cljs` outright, and took the donor name out
of `descriptors_data.cljs`, `tool-descriptors.edn`, `conformance_test.cljs`, Pair's
`tools/registry.cljs`, `closed_world_test.cljs` and
`tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs`. Entering are `hicasso_tool.cljs`,
`hicasso_tool_test.cljs` and `hicasso_wire_test.cljs` — each of which names a donor exactly once,
and none of which *depends* on one. See [Pair](#pair--6-rows-6-migrated).

That number is not a dependency count and must never be quoted as one: **a grep returns requires,
docstrings, comments and prose identically**, and in this tree the prose dominates. Xray's
`registry.cljs` alone contributes ten hits, four of which exist specifically to record that the file
has *no* `re-frame.ui` dependency. Eight of the ten sit in docstrings and comments; the other two are
inside one runtime `js/console.warn` string, which is why every hit had to be read rather than
classified by shape — those two are row X13.

The count moves in **both** directions, and neither direction is safe to read off the grep. A
`[[wiki-link]]` or a historical note inside a docstring reads as a dependency and **over**-counts;
so, more sharply, does a **prohibition** — Pair's two new test hits name the donor precisely to
assert it is absent, and a grep cannot tell an assertion apart from a call. All three of Pair's
current hits are one of those two shapes. In the other direction, a wire-protocol string is
invisible to every static tool and **under**-counts, which is the dangerous direction, because
under-counting is what makes a *deletion* look safe. **Story's S11 is the sharpest instance of that
direction, because the search above cannot see it at all**: the pattern is case-sensitive and
lowercase, and the string reads "renders Freehand views" with a capital F, so
`play/runner_events.cljc` enters the 43 solely through two lowercase docstring hits while its one
load-bearing mention returns nothing. Every hit is therefore classified from the consumer code,
never from the grep.

**A third species has no direction at all: an over-count by NAME COLLISION** (`rf2-lkps`). The
wiki-link over-count and the runtime-string under-count both turn on what the text *does*; this one
turns on what it is *about*. `tools/xray/testbeds/freehand_views/index.html` enters the 43 on two
hits — its `<title>` and one comment — and both name the deck's own `freehand-views` prefix, not
`re-frame.freehand`. The prose is real and load-bearing, and simply is not about the donor. It is
still row X7, because the deck's host page is load-bearing for the cluster; but it is a row on the
`#app` mount target and the button ids, not on a donor name it does not carry.

So each hit was read. A consumer row is a file that names a donor **in a load-bearing position** — a
`:require`, a classpath coordinate, a runtime string or keyword the code actually uses, or a build /
scenario wiring — or that reaches the **target's** evidence door in one of those same positions. The
`ns` forms were additionally re-parsed by paren balancing with docstrings and comments stripped,
which caught two requires a line-oriented regex missed
(`tools/story/test/re_frame/story/view_tool.cljc`, and the second require in
`tools/xray/src/day8/re_frame2_xray/mounted_views.cljs`).

The counts, each with the question it answers:

- **43 files name a donor** under `tools/` — the grep surface, prose included.
- **23 of those 43 carry a consumer row**; the other **20 name a donor only in a comment, a
  docstring or a spec sentence** and are listed in [Named, not consumed](#named-not-consumed) so no
  later reader mistakes them for dependencies.
- **30 consumer rows** — the 23 row-bearing files, plus one extra row because
  `tools/story/deps.edn` carries two independent donor coordinates with different verdicts, plus the
  **6 target-only MIGRATED files** that name **no** donor at all and therefore never appear in the
  grep (Xray's X9, X10, X11; Pair's P2, P3, P5).

Written as arithmetic so a later reader can check it in one line: **43 = 23 + 20**, and
**30 = 23 + 1 + 6**.

**30 rows = 15 STILL-LIVE · 5 FIXTURE-ONLY · 10 MIGRATED.** By tool: Xray 13 (9 · 0 · 4), Story 11
(6 · 5 · 0), Pair 6 (0 · 0 · 6).

**Those are the figures AS MEASURED, and so is every by-tool figure and section heading below** —
a record of the tree at commit `e1b4cae5d0`, 2026-08-11, not a statement about today. Eighteen of
the 30 rows have since been discharged by beads that landed, each recorded in its own verdict cell,
and the page's convention is to leave a row as taken rather than rewrite it. That convention is
right, and it has one cost: a roll-up written once at measurement time reads as a status to anyone
who does not scroll to the table. So both readings are given, here and at each of the three other
roll-up sites, and neither is allowed to stand alone.

**STANDING TODAY, re-derived at commit `861c59f85b`, 2026-08-18: 30 rows = 2 STILL-LIVE ·
0 FIXTURE-ONLY · 10 MIGRATED · 18 DISCHARGED.** By tool: Xray 13 (2 · 0 · 4, 7 discharged), Story 11
(0 · 0 · 0, 11 discharged), Pair 6 (0 · 0 · 6). Written as arithmetic on the same footing as the
line above: **30 = 2 + 0 + 10 + 18**, where the 18 are Xray's X1, X2, X4, X5, X6, X7 and X8
(`rf2-l86mm`, `rf2-0yp7w`) and all eleven Story rows (`rf2-5gka`, `rf2-2og6s`); and per tool
**13 = 2 + 0 + 4 + 7**, **11 = 0 + 0 + 0 + 11**, **6 = 0 + 0 + 6 + 0**. "Discharged" is the word the
ACTIONED banners already use, not a fourth verdict: each discharged row carries its own
RETIRED / SUBSTRATE-FREE / REWORDED disposition and the bead that landed it.

**The only two rows still live on a donor tier are X3 and X13**, and neither is a migration — both
were re-verified at source rather than carried forward, and both are dispositioned in
[Disposition of the still-live rows](#disposition-of-the-still-live-rows).

Liveness was established per row, not assumed from the presence of a `:require` — the "how" column
below says which. A require alone does not make a path live, and a path can be live through a
fixture.

---

## Xray — 13 rows (9 STILL-LIVE, 4 MIGRATED)

Xray is the tool that stands on **both** tiers at once, and that is now what distinguishes it: the
Hicasso tab reads `re-frame.hicasso.tool`, while the Reactive panel's "Mounted Views" section still
reads `re-frame.freehand.tool`. Both ship in the same build. (Since `rf2-n3mb`, Pair is on the
target too — but wholly, with nothing left behind on the donor.)

> **ACTIONED, 2026-08-18 09:53:02 AUSEST (rf2-p4h2t).** Seven of the nine STILL-LIVE rows above —
> X1, X2, X4, X5, X6, X7 and X8 — are discharged, and the verdict column records how. The rows and
> the "how" column are left as taken, on the same footing as the Story cluster below: a census is a
> record of what was found, not of what is true now. **The paragraph immediately above is part of
> that record and its present tense no longer holds** — the Reactive panel's Mounted Views and
> Declared View Sites sections were retired by `rf2-l86mm`, so Xray stands on the target tier only.
>
> **It is seven, not the six the bead was filed for.** X5 was the row `rf2-p4h2t` recorded as
> unmeasured, and completing that measurement moved it: its file survives with 632 lines and 29
> `deftest` forms, and carries zero occurrences of `freehand` case-insensitively, against 55 for
> `panel` in the same file. The two literal schema assertions the row cites are both gone.
>
> **Two rows were re-verified and STAND: X3 and X13**, each still at two occurrences of the token
> its cell names. That is the interesting half of this pass, not a null result — live Xray code
> references a substrate removed on 2026-08-15, and in both cases deliberately. X3's membership is a
> defensive denylist entry the source documents in place, on the footing `:rf.adapter/helix` has
> held since its own adapter was removed (`rf2-d6epb`); `rf2-wtznc` closed on that reading and
> `rf2-0yp7w.9` lists the set under DO NOT DELETE. X13's `warn-donor-ownership-resident!` is
> genuinely live — called on the schema-migration path — and it is the message CONTENT that is
> stale, filed separately rather than fixed here because `tools/xray/**` was fenced off this pass.
>
> **Provenance, so no later reader re-derives it.** `73eb268be2` (2026-08-14, `rf2-l86mm`) dropped
> X1's coordinate and deleted X2 and X4; `47e1e891a5` (2026-08-14, `rf2-l86mm`) took X5's schema
> assertions and X8's scenario; `c951808b47` (2026-08-15, `rf2-0yp7w`.6) deleted the whole
> `tools/xray/testbeds/freehand_views/` deck, X6 and X7 with it.
>
> **Why this was its own dispatch rather than a correction in passing.** `rf2-ps7ia` asked for X1
> alone. Striking one row and leaving six siblings asserting STILL-LIVE over files that do not exist
> would have made the page more self-contradictory, not less — the convention worn once and not by
> its siblings, which is the defect pattern that reopened `rf2-hic-090`.

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| X1 | `tools/xray/deps.edn` | `day8/re-frame2-freehand {:local/root "../../implementation/freehand"}` at **top-level `:deps`** (L53), not a `:test` alias — an artefact-level dependency of the shipped tool | ~~STILL-LIVE~~ → **RETIRED** (rf2-l86mm — the coordinate is gone; the file's only `freehand` token is the comment recording its removal) |
| X2 | `tools/xray/src/day8/re_frame2_xray/mounted_views.cljs` | `:require` of `re-frame.freehand.evidence` + `re-frame.freehand.tool`; pins `consumed-evidence-schema :re-frame.freehand.evidence/v1`; calls `tool/read-mounted-views` at L118 and L182 | ~~STILL-LIVE~~ → **RETIRED** (rf2-l86mm — file deleted) |
| X3 | `tools/xray/src/day8/re_frame2_xray/mount.cljs` | `:rf.adapter/freehand` and `:rf.adapter/ui` are members of a live set of React-element-shaped adapter ids (L216) — data the mount path reads, not a comment | STILL-LIVE |
| X4 | `tools/xray/test/day8/re_frame2_xray/mounted_views_cljs_test.cljs` | seven `re-frame.freehand.*` requires (`.cell`, `.evidence`, `.occurrences`, `.test`, `.tool`, `.release-app`, and the door) — the suite of X2 | ~~STILL-LIVE~~ → **RETIRED** (rf2-l86mm — file deleted with X2) |
| X5 | `tools/xray/test/day8/re_frame2_xray/panels/reactive_panel_view_cljs_test.cljs` | no require; asserts on the literal schema values `:re-frame.freehand.evidence/v9` (L451, the mismatch banner) and `/v1` (L466, the supported case) — the banner contract of the live panel | ~~STILL-LIVE~~ → **SUBSTRATE-FREE** (rf2-l86mm — the file survives; only its Freehand schema arms went) |
| X6 | `tools/xray/testbeds/freehand_views/core.cljs` | `:require` of `re-frame.freehand` (L97) — the one staged deck whose views are Freehand views | ~~STILL-LIVE~~ → **RETIRED** (rf2-0yp7w — the whole `freehand_views/` deck deleted) |
| X7 | `tools/xray/testbeds/freehand_views/index.html` | **names no donor** — its two hits (`<title>` L5, a comment L29) name the deck's own `freehand-views` prefix. It is a row as the deck's host page: `#app` (L26) is the mount target X6's `run` takes, and `fh-mount` / `fh-unmount` (L33-34) are the ids X8's scenario drives. `freehand-views.core/run` is bound by `:init-fn` in top-level `implementation/shadow-cljs.edn` L2042, not here | ~~STILL-LIVE~~ → **RETIRED** (rf2-0yp7w — deleted with X6's deck) |
| X8 | `tools/xray/testbeds/feature_matrix/scenarios.cjs` | the `freehand-views populated Views roster` scenario (L3819) plus its `build` / `bundleDir` / `html` / `servedPath` wiring (L184-187) — a gated browser scenario | ~~STILL-LIVE~~ → **SUBSTRATE-FREE** (rf2-l86mm — the file survives; the scenario and its wiring went) |
| X9 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_reads.cljs` | `:require [re-frame.hicasso.tool :as tool]` (L41) — the live read seam behind the Hicasso tab | MIGRATED |
| X10 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_helpers.cljc` | pins `:re-frame.hicasso.evidence/v2` (L66) as the consumer-owned schema literal | MIGRATED |
| X11 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_cljs_test.cljs` | requires `re-frame.hicasso`, `.evidence`, `.impl.collector`, `.tool` | MIGRATED |
| X12 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_helpers_cljs_test.cljc` | names `:re-frame/freehand` once (L175) **only to assert `supported?` returns false for it** — the donor appears as the rejected case | MIGRATED |
| X13 | `tools/xray/src/day8/re_frame2_xray/registry.cljs` | the schema-4 reload warning (`js/console.warn`, L268-277, in `warn-donor-ownership-resident!` at L260) names `re-frame.ui` (L270, L273) and `re-frame.freehand.tool` (L273) in the sentence a developer reads when it fires — a runtime string the code actually uses, load-bearing on the same footing as the shipped `:description` prose of Pair's descriptor rows. The file's other eight donor hits are docstrings and comments | STILL-LIVE |

**How X2's liveness was established** — not from its `:require`, which proves nothing on its own. The
chain is: `panels/reactive_panel_subs.cljs` L131 requires `day8.re-frame2-xray.mounted-views` and
registers `:rf.xray/mounted-views`, `:rf.xray/mounted-views-schema` and the view-sites sub against
it (L689-716); `panels/reactive_panel_view.cljs` L72 requires the same namespace and renders
`mounted-views-section` into the panel body (L1050, L1060); and `registry.cljs` L353 calls
`reactive-panel/install-mounted-views-subs!` at boot. Three files, none of which *requires* a donor
— `registry.cljs` names one in X13's warning and in comments, the other two only in comments — which
is exactly why liveness had to be traced through Xray's own namespaces rather than read off the
grep.

**X1 is the load-bearing one for `rf2-hic-062`.** Every other Xray row is downstream of it: the
Freehand artefact is on the shipped tool's classpath at top level, so Xray does not merely tolerate
Freehand's absence, it is built against its presence.

> **SPENT, and the two paragraphs above are the measurement rather than the state (rf2-p4h2t).**
> Both describe X1 and X2 in the present tense and neither holds: `rf2-l86mm` deleted
> `mounted_views.cljs`, `install-mounted-views-subs!` and the panel section that rendered it, and
> dropped the coordinate. The trace is kept because it is the reasoning that made X2's liveness
> checkable at all — liveness had to be followed through Xray's own namespaces rather than read off
> the grep — and that method outlives its subject. `rf2-hic-062`, the bead X1 was load-bearing for,
> is itself closed.

**Why X13 counts, and why it is the row most easily lost** (`rf2-kqls`). A `js/console.warn`
argument is a runtime string the code actually uses, which the verdict table already calls
load-bearing, and Pair's descriptor prose was the same species one tool over — a tier name shipped
as prose a human or an agent reads. What makes X13 the dangerous one is the direction its absence
errs in: no require, no coordinate, so a `:require` scan and clj-kondo both pronounce the file
clean, and unlike every other Xray row it *survives* the deletion of every cluster in the
disposition table — which is precisely the moment someone consults this census to learn what is
left. Under-counting here is what would make that deletion look safe. The census filed it as "ten
mentions, all comments" until `rf2-kqls`.

**Pair's old donor wire string was the same species again, and its migration is the standing
counter-example.** That coupling was invisible to every static tool too, and it was caught only
because this census read the consumer code rather than the grep. `hicasso_wire_test.cljs` (row P6)
is the guard that shape earned — it now fails if a donor name returns to Pair's `src/`. Xray's X13
has no equivalent, which is why it is written down here instead.

## Story — 11 rows (6 STILL-LIVE, 5 FIXTURE-ONLY)

Story is the tool the specification sentence already describes correctly: its published jar depends
on neither donor, and both donor coordinates sit under `:aliases {:test {:extra-deps …}}`. The split
is clean — everything touching `re-frame.ui` is a witness; everything touching Freehand is the
presence bridge.

> **ACTIONED, 2026-08-14 (rf2-5gka).** Every Freehand row above — S2-S6 and S11 — is discharged, and
> the verdict column records how. The measurement itself held: the rows are left as taken rather
> than rewritten, because a census is a record of what was found, not of what is true now.
>
> The migration resolved as **retire with the donor**, not as a port. Hicasso publishes no
> presence-advance verb to port onto: `motion/presence` is a component, `impl/presence` is private,
> and the facade carries no clock, flush or presence verb. The `advance-clock!` in
> `hicasso/test_kit` is a page-wide fake-timer facility bound to a `{:clock true}` mount handle —
> a different affordance that happens to share a name. So the bridge, its two Freehand-only witness
> suites and the `day8/re-frame2-freehand` coordinate are gone; the presence RUNG stays and is
> substrate-neutral, a host installing its own advance through `install-presence-flush!`.
>
> **`tools/story` now names Freehand nowhere except in prose recording this removal.** The
> `re-frame.ui` rows (S1, S7-S10) are untouched and remain open — see rf2-2og6s.

> **ACTIONED, 2026-08-14 18:18:29 AUSEST (rf2-2og6s).** The remaining four rows — S1 and S7-S10,
> the `re-frame.ui` half — are discharged, and `tools/story` now names neither donor outside prose
> recording the removals. The rows below are again left as taken.
>
> **This overturns the "need no action at all" verdict two sections down, and the premise is what
> changed rather than the measurement.** That verdict was correct while `implementation/ui/` was
> going to be *archived*: fixture-only compatibility evidence is exactly what the specification
> sentence permits a `test/` tree to retain. Mike's ruling of 2026-08-14 is *removal* (`rf2-0yp7w`),
> and a fixture compiled against a deleted tree is not retained evidence — it is a broken build. So
> the four consumers and the `day8/re-frame2-ui` coordinate that armed them are gone.
>
> The migration resolved as **retire with the donor**, the third instance of the refusal `rf2-jkdy`
> reached for Xray's Views panel and `rf2-l86mm` re-confirmed for its tool-door reads — and it is
> the *same* refusal, because Story's consumer asks the donor's questions verbatim. Three of the
> five projections it shapes (`view-manifest`, `view-dependencies`, `view-event-sites`) are static
> questions about **a view named by its id**, and Hicasso mints no boundary identity to name: a
> boundary is keyed by its READ SET, with `:view` and `:source` projected `unknown` under the
> `:opaque` naming projection. The fourth, `mounted-views`, degrades for the same reason — Story
> filters it by `:view-id`, which the target's roster does not carry. The JVM half has no target at
> all: `re-frame.ui.test/render` returns a versioned structural tree (`:rf.ui/tree-version`), and
> outside the two retiring donors nothing in the repository produces one — `re-frame.hicasso.tool`
> is `.cljs`-only and Hicasso ships no headless render.
>
> **What is deliberately lost, named rather than left silent.** Two claims, and they are not equally
> replaceable.
>
> The first — that a third-party consumer can shape a compiled-view tool tier honestly, absence and
> version boundary included — has **no surviving subject**. There is no compiled-view tool tier for
> a consumer to shape once the donors go, and the paragraph above is why there is no target to
> re-author against. That loss is total and is the point of the row.
>
> The second is S10's: a foreign substrate registered at runtime hosts a **real** app's view and its
> deck plays green through the existing shell. Its mechanism survives — `story/register-substrate!`
> and `multi/unregister-substrate!` are exercised substrate-neutrally by
> `test/re_frame/story_multi_substrate_cljs_test.cljs` (`register-and-unregister`,
> `public-register-substrate-on-story`), and `test/re_frame/story_substrate_isolation_test.clj`
> pins the invariant that makes the seam opt-in. What goes with S10 is narrower and real: the only
> caller of `multi/render-view` against a **non-`:reagent`** substrate, and the only end-to-end arm
> in which a foreign substrate's real app view is played by the runner. Re-acquiring it means a
> Hicasso or UIx deck standing where the `re-frame.ui` counter stood — a product decision, not this
> bead's, and tracked as its own bead rather than assumed.

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| S1 | `tools/story/deps.edn` → `day8/re-frame2-ui` | `:test`-alias `:extra-deps` only (L92); the comment states the published jar must not depend on it, and every consumer of it is row S7-S10 | ~~FIXTURE-ONLY~~ → **RETIRED** (rf2-2og6s) |
| S2 | `tools/story/deps.edn` → `day8/re-frame2-freehand` | `:test`-alias `:extra-deps` only (L110), but it exists to compile S3, which ships in Story's jar | ~~STILL-LIVE~~ → **RETIRED** (rf2-5gka) |
| S3 | `tools/story/src/re_frame/story/play/presence_host.cljc` | requires `re-frame.freehand.presence-runtime` unconditionally and `re-frame.freehand` under `#?@(:cljs …)`; reads `(:flush-render! v/adapter)` and calls `fh-presence/advance-clock!` | ~~STILL-LIVE~~ → **RETIRED** (rf2-5gka) |
| S4 | `tools/story/test/re_frame/story/play/presence_cljs_test.cljc` | requires `re-frame.freehand.presence-runtime` — the suite of S3 | ~~STILL-LIVE~~ → **SUBSTRATE-FREE** (rf2-5gka — the file survives; only its bridge arms went) |
| S5 | `tools/story/test/re_frame/story/play/presence_freehand_dom_cljs_test.cljs` | requires `re-frame.freehand` + `.presence-runtime` — the DOM arm of S3 | ~~STILL-LIVE~~ → **RETIRED** (rf2-5gka) |
| S6 | `tools/story/test/re_frame/story/play/presence_real_clock_cljs_test.cljs` | requires `re-frame.freehand.presence-runtime` — the real-clock arm of S3 | ~~STILL-LIVE~~ → **RETIRED** (rf2-5gka) |
| S7 | `tools/story/test/re_frame/story/view_tool.cljc` | requires `re-frame.ui.tool`; shapes the five donor-1 projections for a Story variant | ~~FIXTURE-ONLY~~ → **RETIRED** (rf2-2og6s) |
| S8 | `tools/story/test/re_frame/story/view_tool_cljs_test.cljc` | requires `re-frame.ui.reactive`, `.tool`, `.tool.evidence` — the CLJS suite of S7 | ~~FIXTURE-ONLY~~ → **RETIRED** (rf2-2og6s) |
| S9 | `tools/story/test/re_frame/story/view_tool_tree_jvm_test.clj` | requires `re-frame.ui` (`:refer [defview sub]`) + `re-frame.ui.test` — the JVM suite of S7 | ~~FIXTURE-ONLY~~ → **RETIRED** (rf2-2og6s) |
| S10 | `tools/story/test/re_frame/story/realworld_ui_consumer_cljs_test.cljs` | requires `re-frame.ui` + `re-frame.freehand.presence-runtime`; hosts the realworld `re-frame.ui` app as a foreign substrate through `story/register-substrate!` | ~~FIXTURE-ONLY~~ → **RETIRED** (rf2-5gka took its Freehand half; rf2-2og6s took the rest) |
| S11 | `tools/story/src/re_frame/story/play/runner_events.cljc` | the `:no-presence-host` refusal message in `presence-step-result` (L1064; the `:message` `str` at L1077-1084) tells the user their app is one "that renders Freehand views" (L1080) — a runtime string the code actually uses, load-bearing on the same footing as X13. The file's other three donor hits are docstrings | ~~STILL-LIVE~~ → **REWORDED** (rf2-5gka — the refusal is substrate-neutral, with a test pinning that it names none) |

**Why S3 is STILL-LIVE despite living behind a late-bind hook.** `re-frame.story.play.presence`
holds the `:flush-presence!` seam and deliberately does not require the substrate; Story itself
never loads S3. But S3 ships **in Story's `src/` tree**, its ns docstring documents the one-`:require`
integration a consuming app performs at boot, and with the bridge absent `[:flush-presence]` refuses
with `:cannot-run` rather than degrading. It is a published, taught integration path that compiles
only against Freehand. A user reaches it, so it is live.

**Why S7-S10 are FIXTURE-ONLY and not still-live.** They execute on every PR — `*_cljs_test`
namespaces match the node-test selector and S9 runs under `clojure -M:test` — but their subject *is*
the compatibility claim. S10 says so in its own first line: Story is a **consumer** there, proving
`re-frame.ui` can enter Story's substrate roster through the sanctioned seam; the tool UI is not
written on `defview`. Delete the donor and what is lost is the evidence, not a Story feature.

**Why S11 counts, and why the earlier passes missed it** (`rf2-gj0a`). It is X13's species one tool
over — a donor name shipped inside a runtime string rather than a `:require` — but it errs harder in
the under-count direction than X13 does, in two ways. X13's two hits at least *appear* in the grep,
so a reader auditing `registry.cljs`'s ten hits could reach them; S11's does not appear at all,
because the census's pattern is lowercase and the message says `Freehand`. And where X13's
`js/console.warn` is developer-facing, this string reaches a **user**: it is the `:message` a Story
author reads when their `[:flush-presence]` step refuses, so a stale one sends them to require a
namespace nobody ships. That is the failure this row exists to prevent — a reader disposing of
Story's donor rows on this census's authority would leave that sentence standing.

**And unlike X13, this one now has the guard.** The census notes above that Pair's P6 is the guard
its wire string earned and that X13 has no equivalent; S11's equivalent is one assertion, added by
`rf2-gj0a` to S4 (`presence_cljs_test.cljc`, L310 in
`presence-step-with-no-host-refuses-cannot-run`), pinning the substrate word in the refusal
message. It is deliberately a **separate** `is` rather than a third branch of the neighbouring
`#"install-presence-flush!|presence-host"` alternation, because `re-find` is satisfied by any one
branch — widening that regex would have *weakened* the install-path claim it already makes. When
`rf2-5gka` moves the bridge off Freehand, the assertion fails and the message is reworded in the
same change instead of going stale silently.

## Pair — 6 rows (6 MIGRATED)

Pair was the sharpest cluster in the census and the easiest to mis-count. **Pair has no classpath
dependency on either tier**: no `deps.edn` coordinate, no `:require`, nothing. Its coupling is a
**wire protocol** — it composes CLJS source as a *string*, sends it over nREPL to an arbitrary
running app, and reads back an envelope. So a dependency scan that only looks at requires finds Pair
clean, and until `rf2-n3mb` it was not.

**`rf2-n3mb` landed (PR #7848), and Pair is now wholly on the target.** The five donor view tools
were not renamed onto `re-frame.hicasso.tool` — they were re-authored into **three**, and the
arithmetic of that is the finding, not a shortfall. Freehand published a view registry and a
compiler manifest, so it could answer `read-view-manifest`, `read-view-dependencies` and
`read-view-event-sites`: static questions about a view named by its declared id. Hicasso mints no
boundary identity and keeps no registry, so those three have no counterpart and were **retired
rather than shipped answering with a fabricated emptiness**. `re-frame.hicasso.tool/read-intents` was
deliberately not taken as a fourth tool either: it folds Spec 009's retained event ring, which Pair
already answers under richer projection as `trace-window`.

| row | consumer | tier surface named, and how | verdict |
|---|---|---|---|
| P1 | `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/hicasso_tool.cljs` | `(def tier-ns "re-frame.hicasso.tool")` (L132) is interpolated into every emitted eval form; `tier-reads` (L140) names the three reads called; `consumed-evidence-schema` pins `:re-frame.hicasso.evidence/v2` (L165) and gates every reply. Its one donor mention (L30) is a docstring sentence recording what it replaced | MIGRATED |
| P2 | `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs` | the three MCP tool descriptions shipped to the agent name `re-frame.hicasso.tool/<read>` (L1288, L1323, L1351) and `:re-frame.hicasso.evidence/v2` (L1305, L1336, L1369) in their `:description` prose. Names **no** donor — outside the grep | MIGRATED |
| P3 | `tools/re-frame2-pair-mcp/tool-descriptors.edn` | the checked-in descriptor export carrying the same three descriptions (L14, L25, L26) — derived from P2, so the two move together. Names **no** donor — outside the grep | MIGRATED |
| P4 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/hicasso_tool_test.cljs` | the suite of P1: form composition, the door-resolution guard (`cljs.core/exists?` at this census; `cljs.core/find-ns-obj` since `rf2-t2ec` made the absent-door rung reachable), and the schema gate. Names a donor once (L106) **only to assert `(not (str/includes? form "freehand"))`** — the donor as the rejected case, X12's species | MIGRATED |
| P5 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/conformance_test.cljs` | the conformance fixtures stub nREPL replies keyed by the exact `re-frame.hicasso.tool/<read>` form string and assert `:schema :re-frame.hicasso.evidence/v2` throughout (L1778-1975). Names **no** donor — outside the grep | MIGRATED |
| P6 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/hicasso_wire_test.cljs` | **new with `rf2-n3mb`; the donor era had no counterpart.** Reads the provider's own source and asserts every emitted read is a public `defn` there, that `consumed-evidence-schema` equals the stamp `re-frame.hicasso.evidence/schema` carries, and (L166-181) that **no donor namespace survives in a callable position anywhere in Pair's shipped `src/`**. Its two donor names (L175) are the prohibition list | MIGRATED |

**`rf2-hic-023`'s deliverable now holds, and it did not before.** That deliverable reads "Xray and
Pair consume the same projected schema byte-for-byte". Xray's Hicasso tab consumes
`:re-frame.hicasso.evidence/v2` (X10) and Pair's `consumed-evidence-schema` is the same literal (P1)
— same schema, same producer, same question set, and P6 asserts the second half of that against the
producer's source on every run. The census's previous pass recorded this sentence as **false**; it
is now true, and a reader coming to this document for the old verdict should know the tree moved
rather than the claim.

**The row that matters most for `rf2-hic-062` is P6, and it is worth saying why.** The thing that
made Pair dangerous was never the donor name — it was that the coupling is a string, so a classpath
scan, a `:require` grep and the compiler all pronounced Pair clean while every tool called the
donor. Migrating the string fixes today; **P6 is what stops the wire from being quietly
re-acquired**, because a copied form that reintroduces `re-frame.freehand/…` or `re-frame.ui.tool/…`
into `src/` now fails a test rather than a runtime in someone else's process. That standing guard is
a fact `rf2-hic-062` can rely on: after the donor trees are gone, nothing in Pair can reach for
them.

---

## Named, not consumed

These **20 files** name a donor only in a comment, a docstring or a spec sentence. They are listed so
that a later reader who repeats the grep can reconcile 43 against 23 without re-deriving this
distinction, and so that none of them is ever counted as a dependency.

`registry.cljs` was on this list until `rf2-kqls` and is now row **X13**: eight of its ten hits are
comments, but two are a runtime `js/console.warn` string. The mistake is an easy one to repeat — a
reader running the grep sees ten hits in a file carrying no `:require`, which is precisely what this
census concluded before the correction.

**`tools/story/src/re_frame/story/play/runner_events.cljc` left the list the same way under
`rf2-gj0a`** and is now row **S11**: three of its four donor hits are docstrings, but the fourth is
the runtime `:message` a user reads on a `[:flush-presence]` refusal. It is the harder of the two to
catch, because that hit is not in the grep at all — the file's membership in the 43 comes entirely
from its docstrings, so re-reading the returned lines could never have found it.

**Three files left this list entirely with `rf2-n3mb`**, and they are named here because a reader
comparing against the previous pass will look for them. Pair's
`src/re_frame2_pair_mcp/tools/registry.cljs` (which now requires `hicasso-tool` and wires the three
target handlers), its `test/re_frame2_pair_mcp/closed_world_test.cljs`, and
`tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs` no longer name a donor **at all**. They
are not rows: each reaches the target only transitively through P1, exactly as Pair's
`tools/registry.cljs` previously reached the donor transitively through the old P1. A file that
names no donor and holds no tier surface of its own needs no entry on either list.

One entry deserves a note because its prose is load-bearing *documentation of a live row* rather
than incidental:

- `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md` — the normative catalogue entry for Pair's
  evidence tools, rewritten by `rf2-n3mb` for the three target reads. It carried twelve donor
  mentions and now carries **one** (L2573), a sentence recording that this family replaced five
  tools aimed at `re-frame.freehand.tool`. Spec prose, not a dependency.

The full list:

- `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`
- `tools/story/spec/017-Testing-Story.md`
- `tools/story/src/re_frame/story/late_bind.cljc`
- `tools/story/src/re_frame/story/play/presence.cljc`
- `tools/story/src/re_frame/story/play/runner.cljc`
- `tools/xray/spec/011-Launch-Modes.md`
- `tools/xray/spec/014-Registry-Catalogue.md`
- `tools/xray/spec/017-Test-Coverage-Matrix.md`
- `tools/xray/spec/021-Dynamic-Panel-Designs.md`
- `tools/xray/spec/027-Hicasso-Evidence.md`
- `tools/xray/spec/Principles.md`
- `tools/xray/src/day8/re_frame2_xray/core.cljs`
- `tools/xray/src/day8/re_frame2_xray/panels/reactive_panel_subs.cljs`
- `tools/xray/src/day8/re_frame2_xray/panels/reactive_panel_view.cljs`
- `tools/xray/src/day8/re_frame2_xray/preload.cljs`
- `tools/xray/src/day8/re_frame2_xray/settings/effects.cljs`
- `tools/xray/src/day8/re_frame2_xray/test_support.cljs`
- `tools/xray/test/day8/re_frame2_xray/core_cljs_test.cljs`
- `tools/xray/test/day8/re_frame2_xray/coverage_matrix_metadata_test.clj`
- `tools/xray/test/day8/re_frame2_xray/registry_cljs_test.cljs`

---

## Disposition of the still-live rows

**Thirteen** of the 15 STILL-LIVE rows resolve into **three clusters**, not thirteen independent
problems. The remaining two, **X3** and **X13**, are not migrations at all: one is an
adapter-identity cleanup and the other a developer-facing string, and both fold into `rf2-hic-062`
itself. Thirteen plus X3 plus X13 is the whole still-live set.

**Read the disposition column, not the cluster count.** Two of the three clusters are still pending
work; the third is not, and one former cluster is gone entirely. A closed bead in this table means
the question has been answered, **not** that work is waiting — mistaking one for the other is how
completed work gets re-done.

> **AS MEASURED ABOVE, STANDING BELOW (rf2-p4h2t, 2026-08-18).** Both paragraphs are the
> 2026-08-11 reading. **No cluster is pending work now**: `rf2-u5b4` and `rf2-5gka` both closed,
> `rf2-jkdy` and `rf2-n3mb` were already closed when the census was taken, and the disposition
> column below is corrected row by row. **All thirteen clustered rows are discharged.** The whole
> still-live set is therefore **X3 and X13**, the two that were never in a cluster — so the
> sentence above holds under a different arithmetic than the one it was written for:
> `13 discharged + X3 + X13`, with 2 standing rather than 15. Both fold into `rf2-hic-062`, which
> has itself closed since; X13 acquired its own follow-up in this pass, and X3 was answered by
> `rf2-wtznc` and is on `rf2-0yp7w.9`'s DO NOT DELETE list.

| cluster | rows | what moving it costs | disposition |
|---|---|---|---|
| Xray Views panel on donor 2 | X1, X2, X4, X5 | re-authoring against four different reads; the panel's whole question ("which *views* are mounted") is one the target cannot answer, because Hicasso keys boundaries by read set and has no view registry | `rf2-jkdy` **CLOSED — the verdict was a refusal.** Of the panel's eight questions two carry across, one degrades structurally and five have no answer at all, so there is no migration to perform; the eight-row mapping is `tools/xray/spec/021-Dynamic-Panel-Designs.md` §3.4.3. The retire-or-keep call, and X1's coordinate with it, is a product decision recorded on `rf2-hic-062`. **SETTLED — the call was RETIRE, and `rf2-l86mm` executed it** (`73eb268be2`, `47e1e891a5`, both 2026-08-14): the panel's Mounted Views and Declared View Sites sections, `mounted_views.cljs` and its suite, X5's schema assertions and X1's coordinate are all gone. All four rows discharged; `rf2-hic-062` has since closed |
| The staged Freehand deck | X6, X7, X8 | the deck's shadow-cljs build id and `:dev-http` port live in top-level `implementation/shadow-cljs.edn` — hot zone, and fenced out of this bead | follow-up bead `rf2-u5b4` — ~~open~~ **CLOSED**. Resolved as retire, not migrate: `rf2-0yp7w`.6 (`c951808b47`, 2026-08-15) deleted the whole `tools/xray/testbeds/freehand_views/` deck, and `rf2-l86mm` had already taken the feature-matrix scenario. All three rows discharged |
| Story presence bridge | S2, S3, S4, S5, S6, S11 | Hicasso's presence surface is `re-frame.hicasso.impl.presence` / `.presence-react`, not a published `presence-runtime` door with `advance-clock!`; the bridge needs a target-side verb that does not exist yet. S11 is the cheap row of the six — the refusal message just names whatever replaces Freehand — and S4's assertion fails until it does | follow-up bead `rf2-5gka` — ~~open~~ **CLOSED**. Resolved as retire with the donor, not as a port; see the ACTIONED banner in [Story](#story--11-rows-6-still-live-5-fixture-only). All six rows discharged |
| ~~Pair's five view tools~~ | *(was P1-P5)* | same four-reads problem as the Xray cluster, plus a regenerated `tool-descriptors.edn` and a spec-catalogue rewrite | `rf2-n3mb` **CLOSED and LANDED** (PR #7848). The five tools became three on `re-frame.hicasso.tool`; the rows are now MIGRATED P1-P6 and no longer still-live. **Not pending work** |
| Xray adapter-id set | X3 | `:rf.adapter/ui` and `:rf.adapter/freehand` are adapter identities, not evidence reads; they retire when the adapters do, under `rf2-hic-062` itself | fold into `rf2-hic-062` — no follow-up bead. **STANDS, re-verified 2026-08-18 at 2 occurrences, and the fold was overtaken by a decision to KEEP**: `rf2-wtznc` closed on the reading that the member is a defensive denylist entry, on the footing `:rf.adapter/helix` has held since its own adapter was removed (`rf2-d6epb`), and `rf2-0yp7w.9` lists the set under DO NOT DELETE. `docs/design/retirement/donor-surfaces.md` reaches the same disposition independently |
| Xray's schema-4 reload warning | X13 | nothing to migrate: the donor names are prose inside one `js/console.warn` about a schema-3 residue. With the tiers gone the message names namespaces that no longer exist, so the cost is a stale developer-facing string, not a broken read | fold into `rf2-hic-062` — reword or drop the warning with the tiers; no follow-up bead. **STANDS, re-verified 2026-08-18 at 2 occurrences, and it now HAS a follow-up bead: `rf2-0ucyg`.** The predicted cost arrived early and by a different route — the message says schema 4 "reads views through `re-frame.freehand.tool`", which stopped being true at `rf2-l86mm` on 2026-08-14, a day BEFORE the tree was deleted. `rf2-hic-062` closed without it, and `rf2-0yp7w.9` has the file only as "comments and docstrings", which is not what this is |

**No migration was made in this bead, and that is the finding rather than a shortfall.** Every
cluster failed the same test: the target does not publish the read the consumer needs, so the change
is a re-authoring of what the panel or the tool *asks*, not a rename of what it calls. Most of them
additionally required edits under `implementation/` or in the hot-zone `shadow-cljs.edn`, both of
which this bead is fenced out of. Attempting any of them here would have produced a half-migration
with a broken panel and no census.

**What the two settled clusters proved is that both honest answers exist.** `rf2-n3mb` re-authored
Pair's family and shipped fewer, better tools. `rf2-jkdy` looked at the same problem for Xray's
Views panel and refused, because a mounted roster that can name no view, rendered beside a Hicasso
tab already showing those envelopes whole, would be worse than nothing. Neither is a shortfall, and
`rf2-hic-062` inherits two decisions rather than two open questions.

The 5 FIXTURE-ONLY rows need no action at all: they are precisely the "explicit compatibility
coverage" the specification sentence permits fixtures to retain. The 10 MIGRATED rows need none
either — 4 in Xray's Hicasso tab, 6 in Pair.

> **OVERTAKEN for the 5 FIXTURE-ONLY rows, 2026-08-14 (rf2-2og6s) — and by a change of premise,
> not of measurement.** The sentence above holds only while the donor trees are *archived*. Mike
> ruled on 2026-08-14 that `re-frame.freehand` and `re-frame.ui` are **removed** (`rf2-0yp7w`),
> and a fixture compiled against a deleted tree is not retained compatibility coverage — it is a
> build failure. All five (S1, S7-S10) are therefore retired; see the ACTIONED note in
> [Story](#story--11-rows-6-still-live-5-fixture-only) for the reasoning and the losses. The 10
> MIGRATED rows are unaffected: they read the target.

## The proof statement

There are two, and a reader owes both. The second is the measurement, preserved verbatim because
that is what a census is for; the first is what it says today. **Three of the measurement's
operative claims have been overtaken** — the still-live count, the two clusters it calls genuinely
open, and above all its closing refusal to dispose of the Freehand tree, which has since been
disposed of. Quoting the 2026-08-11 block alone is therefore the one misuse this page cannot
survive.

### The standing statement, 2026-08-18

> **Re-derived at commit `861c59f85b` (merged `main`, 2026-08-18) by `rf2-p4h2t`**, per row rather
> than by repeating the grep — the pattern that produced the 43 is the wrong instrument for a
> liveness question, which is the whole lesson of the section above.
>
> **The primary tool paths ARE donor-free.** Of 30 tool-consumer rows across Xray, Story and Pair,
> **10 are MIGRATED** to the adapter-neutral Hicasso provider (4 in Xray's Hicasso tab, all 6 of
> Pair's), **18 are DISCHARGED** — retired, made substrate-free or reworded, each with the bead that
> landed it in its verdict cell — and **2 are STILL-LIVE**. Zero remain FIXTURE-ONLY. The three
> clusters are all settled: `rf2-jkdy` and `rf2-u5b4` by retirement, `rf2-5gka` and `rf2-2og6s` by
> retire-with-the-donor, `rf2-n3mb` by re-authoring Pair's five tools into three.
>
> **The two still-live rows are X3 and X13, and both are deliberate rather than residual.** X3 is
> `:rf.adapter/freehand` in `mount.cljs`'s `react-element-render-kinds` — a defensive denylist entry
> whose docstring gives the reasoning in place, kept on the footing `:rf.adapter/helix` has held
> since its own adapter was removed, and listed under DO NOT DELETE by `rf2-0yp7w.9`. X13 is the
> schema-4 reload warning in `registry.cljs`, which is live code on a rarely-reached path; what is
> wrong there is the message's CONTENT, filed as `rf2-0ucyg`. **Neither is a migration, and X3 is
> not a defect at all** — a later reader should not "fix" it.
>
> **So the Freehand tree HAS been disposed of, and this census no longer withholds it.**
> `git ls-files implementation/freehand` returns 0, controlled against 464 for
> `implementation/hicasso` in the same command. Xray's top-level coordinate went with `rf2-l86mm`;
> its Views panel and staged deck are deleted; Story's bridge is retired. The paragraph in the
> measurement below that refuses disposal was correct when written and is spent.

### The measurement it replaces, as taken 2026-08-11

For `rf2-hic-062` to cite:

> **Measured at commit `e1b4cae5d0` (merged `main`, 2026-08-11) by
> `git grep -l -E "freehand|re[-_]frame[./]ui" -- tools`, which returns 43 files.** Re-run it before
> acting on this paragraph: the previous pass of this census was authored against a 48-file tree and
> rebased cleanly onto a 43-file one, which changed nothing in the text and everything in the claim.
>
> **Primary tool paths are not yet donor-free, but Pair now is.** Of 30 tool-consumer rows across
> Xray, Story and Pair, **10 are MIGRATED** to the adapter-neutral Hicasso provider (4 in Xray's
> Hicasso tab, and all 6 of Pair's), 5 are FIXTURE-ONLY compatibility evidence in Story's `test/`
> tree on a `:test`-alias classpath, and **15 are STILL-LIVE on a donor tier**.
>
> **Thirteen of those 15 sit in three clusters, and only two of the three are pending work.** Xray's
> Reactive-panel Views section and its `tools/xray/deps.edn` top-level `day8/re-frame2-freehand`
> coordinate belong to `rf2-jkdy`, which is **closed with a refusal**: the target cannot answer five
> of that panel's eight questions, so there is no migration, and the retire-or-keep call falls to
> `rf2-hic-062`. Genuinely open are the staged `freehand-views` browser deck and its feature-matrix
> scenario (`rf2-u5b4`, 3 rows) and Story's shipped `presence-host` bridge (`rf2-5gka`, 6 rows).
>
> **`rf2-n3mb` is CLOSED and landed, and Pair is not a blocking cluster.** Its five view tools were
> re-authored into three on `re-frame.hicasso.tool` (PR #7848) — the three the target cannot answer
> were retired rather than shipped answering with a fabricated emptiness. Pair holds no donor
> coordinate, no donor `:require` and no donor wire string; `hicasso_wire_test.cljs` asserts that
> last point against Pair's whole shipped `src/` on every run, so the wire cannot be quietly
> re-acquired. **Any plan that still treats Pair as pending migration work is reading a superseded
> pass of this census.**
>
> The remaining two still-live rows are **X3**, the live set of React-element-shaped adapter ids in
> `tools/xray/src/day8/re_frame2_xray/mount.cljs`, and **X13**, the schema-4 `js/console.warn` in
> `tools/xray/src/day8/re_frame2_xray/registry.cljs` that names both `re-frame.ui` and
> `re-frame.freehand.tool` in the sentence a developer reads. Neither is a migration and neither has
> a follow-up bead: `:rf.adapter/ui` and `:rf.adapter/freehand` are adapter *identities* rather than
> evidence reads, and X13's donor names are prose inside a runtime string — both retire when the
> adapters do, which makes them cleanups **folded into `rf2-hic-062` itself**. X13 is also the one
> still-live row that survives the deletion of every cluster, and — together with Story's S11, which
> does not survive it, because S11 sits inside the presence-bridge cluster — one of only two rows
> that no `:require` scan can find. Anything acting on the clusters alone leaves X3 and X13
> unaccounted for.
>
> **The blocker is not effort, it is question shape.** `re-frame.ui.tool` and
> `re-frame.freehand.tool` publish the same five reads, so donor 1 → donor 2 was a rename.
> `re-frame.hicasso.tool` publishes four reads of which only `explain-render` shares a name; the
> other four donor reads are manifest- and view-registry-shaped, and Hicasso mints no boundary
> identity, projecting `:view` and `:source` as `unknown` under an `:opaque` naming projection. So
> every remaining migration is a re-authoring against a different question set — which `rf2-n3mb`
> answered by shipping fewer tools and `rf2-jkdy` answered by shipping none. Both are valid
> outcomes; neither is a port.
>
> `rf2-hic-062` may therefore **archive or remove `implementation/ui/` as far as the tools are
> concerned** — no tool has a live `re-frame.ui` dependency; Story's four `re-frame.ui` witnesses are
> fixture-only, Xray's `registry.cljs` records that it may not acquire such a dependency, and Pair's
> wire witness now fails if `re-frame.ui.tool` reappears in its source. The one residue is X13: that
> same Xray file names `re-frame.ui` in a shipped warning, which has to be reworded rather than left
> pointing at a deleted namespace.
> **It may not yet dispose of the Freehand tree**: Xray depends on it at top-level `:deps`, its
> Views panel and staged deck are built against it, and Story's shipped bridge compiles against it.
> Pair no longer holds it back.
