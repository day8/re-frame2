# Tool-consumer census — Xray, Story and Pair against the donor surfaces

**rf2-hic-076**, 2026-08-11 05:37:14 AUSEST; row **X13** added and the counts carried through by
`rf2-kqls`, 2026-08-11 07:13:28 AUSEST; **Pair re-measured against merged `main` and the totals
carried through** by `rf2-hic-076` again, 2026-08-11 09:01:41 AUSEST. The census `rf2-hic-062` cites
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

At commit `964d71d9f2` (merged `main`, 2026-08-11 09:01:41 AUSEST) it returns **43 files**.

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
`[[wiki-link]]` or a historical note inside a docstring reads as a dependency and **over**-counts —
all three of Pair's new hits are that shape. A wire-protocol string is invisible to every static
tool and **under**-counts, which is the dangerous direction, because under-counting is what makes a
*deletion* look safe. Every hit is therefore classified from the consumer code, never from the grep.

So each hit was read. A consumer row is a file that names a donor **in a load-bearing position** — a
`:require`, a classpath coordinate, a runtime string or keyword the code actually uses, or a build /
scenario wiring — or that reaches the **target's** evidence door in one of those same positions. The
`ns` forms were additionally re-parsed by paren balancing with docstrings and comments stripped,
which caught two requires a line-oriented regex missed
(`tools/story/test/re_frame/story/view_tool.cljc`, and the second require in
`tools/xray/src/day8/re_frame2_xray/mounted_views.cljs`).

The counts, each with the question it answers:

- **43 files name a donor** under `tools/` — the grep surface, prose included.
- **22 of those 43 are consumer rows**; the other **21 name a donor only in a comment, a docstring
  or a spec sentence** and are listed in [Named, not consumed](#named-not-consumed) so no later
  reader mistakes them for dependencies.
- **29 consumer rows** — the 22 row-bearing files, plus one extra row because
  `tools/story/deps.edn` carries two independent donor coordinates with different verdicts, plus the
  **6 target-only MIGRATED files** that name **no** donor at all and therefore never appear in the
  grep (Xray's X9, X10, X11; Pair's P2, P3, P5).

Written as arithmetic so a later reader can check it in one line: **43 = 22 + 21**, and
**29 = 22 + 1 + 6**.

**29 rows = 14 STILL-LIVE · 5 FIXTURE-ONLY · 10 MIGRATED.** By tool: Xray 13 (9 · 0 · 4), Story 10
(5 · 5 · 0), Pair 6 (0 · 0 · 6).

Liveness was established per row, not assumed from the presence of a `:require` — the "how" column
below says which. A require alone does not make a path live, and a path can be live through a
fixture.

---

## Xray — 13 rows (9 STILL-LIVE, 4 MIGRATED)

Xray is the tool that stands on **both** tiers at once, and that is now what distinguishes it: the
Hicasso tab reads `re-frame.hicasso.tool`, while the Reactive panel's "Mounted Views" section still
reads `re-frame.freehand.tool`. Both ship in the same build. (Since `rf2-n3mb`, Pair is on the
target too — but wholly, with nothing left behind on the donor.)

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| X1 | `tools/xray/deps.edn` | `day8/re-frame2-freehand {:local/root "../../implementation/freehand"}` at **top-level `:deps`** (L53), not a `:test` alias — an artefact-level dependency of the shipped tool | STILL-LIVE |
| X2 | `tools/xray/src/day8/re_frame2_xray/mounted_views.cljs` | `:require` of `re-frame.freehand.evidence` + `re-frame.freehand.tool`; pins `consumed-evidence-schema :re-frame.freehand.evidence/v1`; calls `tool/read-mounted-views` at L118 and L182 | STILL-LIVE |
| X3 | `tools/xray/src/day8/re_frame2_xray/mount.cljs` | `:rf.adapter/freehand` and `:rf.adapter/ui` are members of a live set of React-element-shaped adapter ids (L216) — data the mount path reads, not a comment | STILL-LIVE |
| X4 | `tools/xray/test/day8/re_frame2_xray/mounted_views_cljs_test.cljs` | seven `re-frame.freehand.*` requires (`.cell`, `.evidence`, `.occurrences`, `.test`, `.tool`, `.release-app`, and the door) — the suite of X2 | STILL-LIVE |
| X5 | `tools/xray/test/day8/re_frame2_xray/panels/reactive_panel_view_cljs_test.cljs` | no require; asserts on the literal schema values `:re-frame.freehand.evidence/v9` (L451, the mismatch banner) and `/v1` (L466, the supported case) — the banner contract of the live panel | STILL-LIVE |
| X6 | `tools/xray/testbeds/freehand_views/core.cljs` | `:require` of `re-frame.freehand` (L97) — the one staged deck whose views are Freehand views | STILL-LIVE |
| X7 | `tools/xray/testbeds/freehand_views/index.html` | the deck's host page, wired from `freehand-views.core/run` | STILL-LIVE |
| X8 | `tools/xray/testbeds/feature_matrix/scenarios.cjs` | the `freehand-views populated Views roster` scenario (L3819) plus its `build` / `bundleDir` / `html` / `servedPath` wiring (L184-187) — a gated browser scenario | STILL-LIVE |
| X9 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_reads.cljs` | `:require [re-frame.hicasso.tool :as tool]` (L41) — the live read seam behind the Hicasso tab | MIGRATED |
| X10 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_helpers.cljc` | pins `:re-frame.hicasso.evidence/v2` (L66) as the consumer-owned schema literal | MIGRATED |
| X11 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_cljs_test.cljs` | requires `re-frame.hicasso`, `.evidence`, `.impl.collector`, `.tool` | MIGRATED |
| X12 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_helpers_cljs_test.cljc` | names `:re-frame/freehand` once (L175) **only to assert `supported?` returns false for it** — the donor appears as the rejected case | MIGRATED |
| X13 | `tools/xray/src/day8/re_frame2_xray/registry.cljs` | the schema-4 reload warning (`js/console.warn`, L268-277, in `warn-donor-ownership-resident!` at L260) names `re-frame.ui` (L270, L273) and `re-frame.freehand.tool` (L273) in the sentence a developer reads when it fires — a runtime string the code actually uses, load-bearing on the same footing as P2's shipped `:description` prose. The file's other eight donor hits are docstrings and comments | STILL-LIVE |

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

**Why X13 counts, and why it is the row most easily lost** (`rf2-kqls`). A `js/console.warn`
argument is a runtime string the code actually uses, which the verdict table already calls
load-bearing, and P2 is the same species one tool over — a donor name shipped as prose a human or an
agent reads. What makes X13 the dangerous one is the direction its absence errs in: no require, no
coordinate, so a `:require` scan and clj-kondo both pronounce the file clean, and unlike every other
Xray row it *survives* the deletion of every cluster in the disposition table — which is precisely
the moment someone consults this census to learn what is left. Under-counting here is what would
make that deletion look safe. The census filed it as "ten mentions, all comments" until `rf2-kqls`.

**P1 was the same species one tool over, and its migration is the standing counter-example.** Pair's
donor coupling was a wire string too, invisible to every static tool, and it was fixed only because
this census read the consumer code rather than the grep. `hicasso_wire_test.cljs` (P6) is the guard
that shape earned; Xray's X13 has no equivalent, which is why it is written down here instead.

## Story — 10 rows (5 STILL-LIVE, 5 FIXTURE-ONLY)

Story is the tool the specification sentence already describes correctly: its published jar depends
on neither donor, and both donor coordinates sit under `:aliases {:test {:extra-deps …}}`. The split
is clean — everything touching `re-frame.ui` is a witness; everything touching Freehand is the
presence bridge.

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| S1 | `tools/story/deps.edn` → `day8/re-frame2-ui` | `:test`-alias `:extra-deps` only (L92); the comment states the published jar must not depend on it, and every consumer of it is row S7-S10 | FIXTURE-ONLY |
| S2 | `tools/story/deps.edn` → `day8/re-frame2-freehand` | `:test`-alias `:extra-deps` only (L110), but it exists to compile S3, which ships in Story's jar | STILL-LIVE |
| S3 | `tools/story/src/re_frame/story/play/presence_host.cljc` | requires `re-frame.freehand.presence-runtime` unconditionally and `re-frame.freehand` under `#?@(:cljs …)`; reads `(:flush-render! v/adapter)` and calls `fh-presence/advance-clock!` | STILL-LIVE |
| S4 | `tools/story/test/re_frame/story/play/presence_cljs_test.cljc` | requires `re-frame.freehand.presence-runtime` — the suite of S3 | STILL-LIVE |
| S5 | `tools/story/test/re_frame/story/play/presence_freehand_dom_cljs_test.cljs` | requires `re-frame.freehand` + `.presence-runtime` — the DOM arm of S3 | STILL-LIVE |
| S6 | `tools/story/test/re_frame/story/play/presence_real_clock_cljs_test.cljs` | requires `re-frame.freehand.presence-runtime` — the real-clock arm of S3 | STILL-LIVE |
| S7 | `tools/story/test/re_frame/story/view_tool.cljc` | requires `re-frame.ui.tool`; shapes the five donor-1 projections for a Story variant | FIXTURE-ONLY |
| S8 | `tools/story/test/re_frame/story/view_tool_cljs_test.cljc` | requires `re-frame.ui.reactive`, `.tool`, `.tool.evidence` — the CLJS suite of S7 | FIXTURE-ONLY |
| S9 | `tools/story/test/re_frame/story/view_tool_tree_jvm_test.clj` | requires `re-frame.ui` (`:refer [defview sub]`) + `re-frame.ui.test` — the JVM suite of S7 | FIXTURE-ONLY |
| S10 | `tools/story/test/re_frame/story/realworld_ui_consumer_cljs_test.cljs` | requires `re-frame.ui` + `re-frame.freehand.presence-runtime`; hosts the realworld `re-frame.ui` app as a foreign substrate through `story/register-substrate!` | FIXTURE-ONLY |

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
| P4 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/hicasso_tool_test.cljs` | the suite of P1: form composition, the `cljs.core/exists?` guard, and the schema gate. Names a donor once (L106) **only to assert `(not (str/includes? form "freehand"))`** — the donor as the rejected case, X12's species | MIGRATED |
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

These **21 files** name a donor only in a comment, a docstring or a spec sentence. They are listed so
that a later reader who repeats the grep can reconcile 43 against 22 without re-deriving this
distinction, and so that none of them is ever counted as a dependency.

`registry.cljs` was on this list until `rf2-kqls` and is now row **X13**: eight of its ten hits are
comments, but two are a runtime `js/console.warn` string. The mistake is an easy one to repeat — a
reader running the grep sees ten hits in a file carrying no `:require`, which is precisely what this
census concluded before the correction.

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
- `tools/story/src/re_frame/story/play/runner_events.cljc`
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

**Twelve** of the 14 STILL-LIVE rows resolve into **three clusters**, not twelve independent
problems. The remaining two, **X3** and **X13**, are not migrations at all: one is an
adapter-identity cleanup and the other a developer-facing string, and both fold into `rf2-hic-062`
itself. Twelve plus X3 plus X13 is the whole still-live set.

**Read the disposition column, not the cluster count.** Two of the three clusters are still pending
work; the third is not, and one former cluster is gone entirely. A closed bead in this table means
the question has been answered, **not** that work is waiting — mistaking one for the other is how
completed work gets re-done.

| cluster | rows | what moving it costs | disposition |
|---|---|---|---|
| Xray Views panel on donor 2 | X1, X2, X4, X5 | re-authoring against four different reads; the panel's whole question ("which *views* are mounted") is one the target cannot answer, because Hicasso keys boundaries by read set and has no view registry | `rf2-jkdy` **CLOSED — the verdict was a refusal.** Of the panel's eight questions two carry across, one degrades structurally and five have no answer at all, so there is no migration to perform. The retire-or-keep call, and X1's coordinate with it, is a product decision recorded on `rf2-hic-062` |
| The staged Freehand deck | X6, X7, X8 | the deck's shadow-cljs build id and `:dev-http` port live in top-level `implementation/shadow-cljs.edn` — hot zone, and fenced out of this bead | follow-up bead `rf2-u5b4` — **open** |
| Story presence bridge | S2, S3, S4, S5, S6 | Hicasso's presence surface is `re-frame.hicasso.impl.presence` / `.presence-react`, not a published `presence-runtime` door with `advance-clock!`; the bridge needs a target-side verb that does not exist yet | follow-up bead `rf2-5gka` — **open** |
| ~~Pair's five view tools~~ | *(was P1-P5)* | same four-reads problem as the Xray cluster, plus a regenerated `tool-descriptors.edn` and a spec-catalogue rewrite | `rf2-n3mb` **CLOSED and LANDED** (PR #7848). The five tools became three on `re-frame.hicasso.tool`; the rows are now MIGRATED P1-P6 and no longer still-live. **Not pending work** |
| Xray adapter-id set | X3 | `:rf.adapter/ui` and `:rf.adapter/freehand` are adapter identities, not evidence reads; they retire when the adapters do, under `rf2-hic-062` itself | fold into `rf2-hic-062` — no follow-up bead |
| Xray's schema-4 reload warning | X13 | nothing to migrate: the donor names are prose inside one `js/console.warn` about a schema-3 residue. With the tiers gone the message names namespaces that no longer exist, so the cost is a stale developer-facing string, not a broken read | fold into `rf2-hic-062` — reword or drop the warning with the tiers; no follow-up bead |

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

## The proof statement

For `rf2-hic-062` to cite:

> **Measured at commit `964d71d9f2` (merged `main`, 2026-08-11) by
> `git grep -l -E "freehand|re[-_]frame[./]ui" -- tools`, which returns 43 files.** Re-run it before
> acting on this paragraph: the previous pass of this census was authored against a 48-file tree and
> rebased cleanly onto a 43-file one, which changed nothing in the text and everything in the claim.
>
> **Primary tool paths are not yet donor-free, but Pair now is.** Of 29 tool-consumer rows across
> Xray, Story and Pair, **10 are MIGRATED** to the adapter-neutral Hicasso provider (4 in Xray's
> Hicasso tab, and all 6 of Pair's), 5 are FIXTURE-ONLY compatibility evidence in Story's `test/`
> tree on a `:test`-alias classpath, and **14 are STILL-LIVE on a donor tier**.
>
> **Twelve of those 14 sit in three clusters, and only two of the three are pending work.** Xray's
> Reactive-panel Views section and its `tools/xray/deps.edn` top-level `day8/re-frame2-freehand`
> coordinate belong to `rf2-jkdy`, which is **closed with a refusal**: the target cannot answer five
> of that panel's eight questions, so there is no migration, and the retire-or-keep call falls to
> `rf2-hic-062`. Genuinely open are the staged `freehand-views` browser deck and its feature-matrix
> scenario (`rf2-u5b4`, 3 rows) and Story's shipped `presence-host` bridge (`rf2-5gka`, 5 rows).
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
> still-live row that no `:require` scan can find and that survives the deletion of every cluster.
> Anything acting on the clusters alone leaves X3 and X13 unaccounted for.
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
