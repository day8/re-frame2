# Tool-consumer census — Xray, Story and Pair against the donor surfaces

**rf2-hic-076**, 2026-08-11 05:37:14 AUSEST. The census `rf2-hic-062` cites before it disposes of
the donor tool surfaces.

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
mostly do **not** get a migration in this bead: moving Xray's Views panel or Pair's five view tools
onto the target is a **re-authoring against a different question set**, not a rename. Per the
`rf2-6ync` precedent, a smaller honest set of migrations plus accurate follow-ups beats a sweep.

## Verdicts, defined once

| verdict | meaning |
|---|---|
| **MIGRATED** | the consumer reads the adapter-neutral provider (`re-frame.hicasso.tool` / `re-frame.hicasso.evidence`). No donor name sits in a load-bearing position. |
| **FIXTURE-ONLY** | the donor is named only from the artefact's `test/` tree, on a `:test`-alias classpath, and its purpose there is compatibility evidence. It executes on every PR — that is the point of a witness — but no path a user or an agent reaches passes through it, so removing the donor costs the witness and nothing else. This is the coverage the specification sentence expressly permits. |
| **STILL-LIVE** | a shipped tool path reaches the donor: an artefact-level `:deps` coordinate, a panel a user sees, an MCP tool an agent calls, a gated browser scenario, or a test whose subject is one of those. |

## How the census was taken, and what the counts answer

`git grep -E "freehand|re[-_]frame[./]ui"` over `tools/` returns **48 files**. That number is not a
dependency count and must never be quoted as one: **a grep returns requires, docstrings, comments and
prose identically**, and in this tree the prose dominates. Xray's `registry.cljs` alone contributes
ten hits, every one of which is a comment, and four of them exist specifically to record that the
file has *no* `re-frame.ui` dependency.

So each hit was read. A consumer row is a file that names a donor **in a load-bearing position** — a
`:require`, a classpath coordinate, a runtime string or keyword the code actually uses, or a build /
scenario wiring. The `ns` forms were additionally re-parsed by paren balancing with docstrings and
comments stripped, which caught two requires a line-oriented regex missed
(`tools/story/test/re_frame/story/view_tool.cljc`, and the second require in
`tools/xray/src/day8/re_frame2_xray/mounted_views.cljs`).

The two counts, each with the question it answers:

- **48 files name a donor** under `tools/` — the grep surface, prose included.
- **23 of those 48 are load-bearing**; the other **25 name a donor only in a comment, a docstring or
  a spec sentence** and are listed in [Named, not consumed](#named-not-consumed) so no later reader
  mistakes them for dependencies.
- **27 consumer rows** — the 23 load-bearing files, plus one extra row because
  `tools/story/deps.edn` carries two independent donor coordinates with different verdicts, plus the
  3 MIGRATED files that name **no** donor at all and therefore never appear in the grep.

**27 rows = 18 STILL-LIVE · 5 FIXTURE-ONLY · 4 MIGRATED.** By tool: Xray 12 (8 · 0 · 4), Story 10
(5 · 5 · 0), Pair 5 (5 · 0 · 0).

Liveness was established per row, not assumed from the presence of a `:require` — the "how" column
below says which. A require alone does not make a path live, and a path can be live through a
fixture.

---

## Xray — 12 rows (8 STILL-LIVE, 4 MIGRATED)

Xray is the only tool that has any presence on the target at all, and it has it **alongside**, not
instead of, the donor: the Hicasso tab reads `re-frame.hicasso.tool`, while the Reactive panel's
"Mounted Views" section still reads `re-frame.freehand.tool`. Both ship in the same build.

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| X1 | `tools/xray/deps.edn` | `day8/re-frame2-freehand {:local/root "../../implementation/freehand"}` at **top-level `:deps`** (L53), not a `:test` alias — an artefact-level dependency of the shipped tool | STILL-LIVE |
| X2 | `tools/xray/src/day8/re_frame2_xray/mounted_views.cljs` | `:require` of `re-frame.freehand.evidence` + `re-frame.freehand.tool`; pins `consumed-evidence-schema :re-frame.freehand.evidence/v1`; calls `tool/read-mounted-views` at L118 and L182 | STILL-LIVE |
| X3 | `tools/xray/src/day8/re_frame2_xray/mount.cljs` | `:rf.adapter/freehand` and `:rf.adapter/ui` are members of a live set of React-element-shaped adapter ids (L216) — data the mount path reads, not a comment | STILL-LIVE |
| X4 | `tools/xray/test/day8/re_frame2_xray/mounted_views_cljs_test.cljs` | seven `re-frame.freehand.*` requires (`.cell`, `.evidence`, `.occurrences`, `.test`, `.tool`, `.release-app`, and the door) — the suite of X2 | STILL-LIVE |
| X5 | `tools/xray/test/day8/re_frame2_xray/panels/reactive_panel_view_cljs_test.cljs` | no require; asserts on the literal schema values `:re-frame.freehand.evidence/v1` and `/v9` (L451, L466) — the banner contract of the live panel | STILL-LIVE |
| X6 | `tools/xray/testbeds/freehand_views/core.cljs` | `:require` of `re-frame.freehand` (L97) — the one staged deck whose views are Freehand views | STILL-LIVE |
| X7 | `tools/xray/testbeds/freehand_views/index.html` | the deck's host page, wired from `freehand-views.core/run` | STILL-LIVE |
| X8 | `tools/xray/testbeds/feature_matrix/scenarios.cjs` | the `freehand-views populated Views roster` scenario (L3819) plus its `build` / `bundleDir` / `html` / `servedPath` wiring (L184-187) — a gated browser scenario | STILL-LIVE |
| X9 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_reads.cljs` | `:require [re-frame.hicasso.tool :as tool]` (L41) — the live read seam behind the Hicasso tab | MIGRATED |
| X10 | `tools/xray/src/day8/re_frame2_xray/panels/hicasso_helpers.cljc` | pins `:re-frame.hicasso.evidence/v2` (L66) as the consumer-owned schema literal | MIGRATED |
| X11 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_cljs_test.cljs` | requires `re-frame.hicasso`, `.evidence`, `.impl.collector`, `.tool` | MIGRATED |
| X12 | `tools/xray/test/day8/re_frame2_xray/panels/hicasso_helpers_cljs_test.cljc` | names `:re-frame/freehand` once (L175) **only to assert `supported?` returns false for it** — the donor appears as the rejected case | MIGRATED |

**How X2's liveness was established** — not from its `:require`, which proves nothing on its own. The
chain is: `panels/reactive_panel_subs.cljs` L131 requires `day8.re-frame2-xray.mounted-views` and
registers `:rf.xray/mounted-views`, `:rf.xray/mounted-views-schema` and the view-sites sub against
it (L689-716); `panels/reactive_panel_view.cljs` L72 requires the same namespace and renders
`mounted-views-section` into the panel body (L1050, L1060); and `registry.cljs` L353 calls
`reactive-panel/install-mounted-views-subs!` at boot. Three files, none of which names a donor
except in comments — which is exactly why liveness had to be traced through Xray's own namespaces
rather than read off the grep.

**X1 is the load-bearing one for `rf2-hic-062`.** Every other Xray row is downstream of it: the
Freehand artefact is on the shipped tool's classpath at top level, so Xray does not merely tolerate
Freehand's absence, it is built against its presence.

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

## Pair — 5 rows (5 STILL-LIVE)

Pair is the sharpest row in the census and the easiest to mis-count. **Pair has no classpath
dependency on either donor**: no `deps.edn` coordinate, no `:require`, nothing. Its coupling is a
**wire protocol** — it composes CLJS source as a *string*, sends it over nREPL to an arbitrary
running app, and reads back an envelope. So a dependency scan that only looks at requires finds Pair
clean, and it is not.

| row | consumer | donor surface named, and how | verdict |
|---|---|---|---|
| P1 | `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/view_tool.cljs` | `(def ^:private tier-ns "re-frame.freehand.tool")` (L128) is interpolated into every emitted eval form; `consumed-evidence-schema` pins `:re-frame.freehand.evidence/v1` (L147) and gates every reply | STILL-LIVE |
| P2 | `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs` | the five MCP tool descriptions shipped to the agent name `re-frame.freehand.tool/<read>` and the v1 schema in their `:description` prose | STILL-LIVE |
| P3 | `tools/re-frame2-pair-mcp/tool-descriptors.edn` | the checked-in descriptor export carrying the same five descriptions — derived from P2, so the two move together | STILL-LIVE |
| P4 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/view_tool_test.cljs` | asserts the emitted form contains the literal `re-frame.freehand.tool/<read>` (L52-115) and exercises the v1-vs-v99 mismatch gate (L152) | STILL-LIVE |
| P5 | `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/conformance_test.cljs` | the conformance fixtures stub nREPL replies keyed by the exact `re-frame.freehand.tool/<read>` form string, and assert `:schema :re-frame.freehand.evidence/v1` throughout (L1780-2043) | STILL-LIVE |

**Pair is the one place where the specification sentence is presently false**, and it is worth
stating plainly because `rf2-hic-062` depends on it. `rf2-hic-023`'s deliverable reads "Xray and Pair
consume the same projected schema byte-for-byte". Xray's Hicasso tab consumes
`:re-frame.hicasso.evidence/v2` (X10); Pair consumes `:re-frame.freehand.evidence/v1` (P1). They are
not the same schema, not the same producer, and not the same four questions. Pair has not been moved
to the target at all.

The mitigating fact, and the reason this is a follow-up rather than an emergency: `tier-ns` is a
single centralised `def` whose own docstring says it is "centralised so a framework rename is a
single edit". The **string** is a one-line change. What is not a one-line change is that four of
Pair's five reads have no counterpart on the target.

---

## Named, not consumed

These **25 files** name a donor only in a comment, a docstring or a spec sentence. They are listed so
that a later reader who repeats the grep can reconcile 48 against 23 without re-deriving this
distinction, and so that none of them is ever counted as a dependency.

Three deserve a note because their prose is load-bearing *documentation of a live row* rather than
incidental:

- `tools/xray/src/day8/re_frame2_xray/registry.cljs` — ten mentions, all comments, four of which
  exist to record that this build has **no** `re-frame.ui` dependency "nor may it acquire one". It
  is nonetheless where X2 is installed at boot (L353), which is how X2's liveness was proved.
- `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md` — twelve mentions; the normative catalogue
  entry for the five tools of P1. Spec prose, not a dependency, but it moves when P1 moves.
- `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs` — one comment; wires the five
  handlers to `view-tool/*`, so it reaches the donor transitively through P1 and names it nowhere.

The full list:

- `tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs`
- `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`
- `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/registry.cljs`
- `tools/re-frame2-pair-mcp/test/re_frame2_pair_mcp/closed_world_test.cljs`
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
- `tools/xray/src/day8/re_frame2_xray/registry.cljs`
- `tools/xray/src/day8/re_frame2_xray/settings/effects.cljs`
- `tools/xray/src/day8/re_frame2_xray/test_support.cljs`
- `tools/xray/test/day8/re_frame2_xray/core_cljs_test.cljs`
- `tools/xray/test/day8/re_frame2_xray/coverage_matrix_metadata_test.clj`
- `tools/xray/test/day8/re_frame2_xray/registry_cljs_test.cljs`

---

## Disposition of the still-live rows

The 18 STILL-LIVE rows resolve into **four clusters**, not eighteen independent problems.

| cluster | rows | what moving it costs | disposition |
|---|---|---|---|
| Xray Views panel on donor 2 | X1, X2, X4, X5 | re-authoring against four different reads; the panel's whole question ("which *views* are mounted") is one the target cannot answer, because Hicasso keys boundaries by read set and has no view registry | follow-up bead |
| The staged Freehand deck | X6, X7, X8 | the deck's shadow-cljs build id and `:dev-http` port live in top-level `implementation/shadow-cljs.edn` — hot zone, and fenced out of this bead | follow-up bead |
| Story presence bridge | S2, S3, S4, S5, S6 | Hicasso's presence surface is `re-frame.hicasso.impl.presence` / `.presence-react`, not a published `presence-runtime` door with `advance-clock!`; the bridge needs a target-side verb that does not exist yet | follow-up bead |
| Pair's five view tools | P1, P2, P3, P4, P5 | same four-reads problem as the Xray cluster, plus a regenerated `tool-descriptors.edn` and a spec-catalogue rewrite | follow-up bead |
| Xray adapter-id set | X3 | `:rf.adapter/ui` and `:rf.adapter/freehand` are adapter identities, not evidence reads; they retire when the adapters do, under `rf2-hic-062` itself | fold into rf2-hic-062 |

**No migration was made in this bead, and that is the finding rather than a shortfall.** Every
still-live cluster fails the same test: the target does not publish the read the consumer needs, so
the change is a re-authoring of what the panel or the tool *asks*, not a rename of what it calls.
Three of the four clusters would additionally require edits under `implementation/` or in the
hot-zone `shadow-cljs.edn`, both of which this bead is fenced out of. Attempting any of them here
would have produced a half-migration with a broken panel and no census.

The 5 FIXTURE-ONLY rows need no action at all: they are precisely the "explicit compatibility
coverage" the specification sentence permits fixtures to retain.

## The proof statement

For `rf2-hic-062` to cite:

> **Primary tool paths are not yet donor-free.** Of 27 tool-consumer rows across Xray, Story and
> Pair, 4 are MIGRATED to the adapter-neutral Hicasso provider (all in Xray's Hicasso tab), 5 are
> FIXTURE-ONLY compatibility evidence in Story's `test/` tree on a `:test`-alias classpath, and
> **18 are STILL-LIVE on a donor tier**. The 18 form four clusters: Xray's Reactive-panel Views
> section and its `tools/xray/deps.edn` top-level `day8/re-frame2-freehand` coordinate; the staged
> `freehand-views` browser deck and its feature-matrix scenario; Story's shipped `presence-host`
> bridge; and all five of Pair's view tools, which reach `re-frame.freehand.tool` by wire string
> rather than by `:require` and therefore do not appear in any classpath scan.
>
> **The blocker is not effort, it is question shape.** `re-frame.ui.tool` and
> `re-frame.freehand.tool` publish the same five reads, so donor 1 → donor 2 was a rename.
> `re-frame.hicasso.tool` publishes four reads of which only `explain-render` shares a name; the
> other four donor reads are manifest- and view-registry-shaped, and Hicasso mints no boundary
> identity, projecting `:view` and `:source` as `unknown` under an `:opaque` naming projection. So
> every remaining migration is a re-authoring against a different question set.
>
> `rf2-hic-062` may therefore **archive or remove `implementation/ui/` as far as the tools are
> concerned** — no tool has a live `re-frame.ui` dependency; Story's four `re-frame.ui` witnesses are
> fixture-only and Xray's `registry.cljs` records that it may not acquire such a dependency.
> **It may not yet dispose of the Freehand tree**: Xray depends on it at top-level `:deps`, Story's
> shipped bridge compiles against it, and every one of Pair's five view tools targets it.
