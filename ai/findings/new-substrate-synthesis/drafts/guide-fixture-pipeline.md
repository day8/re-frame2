# DRAFT — The guide-fixture pipeline (making the README promise real at scale)

> **Status: DRAFT · 2026-07-12 · re-based 2026-07-13** to two changed facts: the
> synthesis tree (guide pages included) has been **force-tracked in git since #5800**,
> so "CI cannot read the pages" is no longer true; and ⟨guide/README ¶1⟩ now states
> *exact* fixture coverage (guide 09 Tier-1 today, growing with the stages) rather
> than a universal claim. The universal every-example-runs promise is this pipeline's
> **target**, not the current state. The normative sources are
> ⟨07-testing §7 — the guide as a test surface⟩ and the G-14 honest remainder
> (⟨implementation/ui/test/re_frame/ui/g14_compile_budget_jvm_test.clj docstring⟩:
> *"guide-fixtures CI cost needs the guide-examples corpus (08 §3)"*, landed via #5703).
> One fixture exists as precedent:
> ⟨implementation/ui/test/re_frame/ui/test_guide09_fixture_jvm_test.clj⟩ (the S1d
> worker's hand-lift of guide 09 §Tier 1). A completeness pass is expanding the guide
> now — more chapters, more examples, stage markers on not-yet-shipped surfaces — so
> this design targets that shape, not today's snapshot. Open items are tagged
> **[S3-CONFIRM]**.

## 0. The promise and the three constraints

Three facts shape everything below; any model that ignores one of them is wrong for
this repo.

1. **The guide is tracked working material, not yet docs.** It lives under
   `ai/findings/…/guide/`, and since #5800 that synthesis subtree is **force-tracked in
   git** (⟨.gitignore⟩ carries the temporary exception; the surrounding `ai/` tree
   stays local-only ⟨CLAUDE.md §Local working files⟩), so CI *can* read the pages
   today. What has not happened is the S6 docs move (W2/W3) ⟨12 §3 S6 epic⟩: the pages
   are a working artefact that dissolves into `docs/` at S6 and no extractor exists —
   building one before S6 is speculative machinery the program has declined. So during
   S2–S5, what runs in CI stays checked-in test source; the tracked pages are available
   to any local script (and to a future gate) but nothing derives fixtures from them
   yet.
2. **Fences come in dialects.** Census (originally 52/51 on 2026-07-12; re-censused
   after the guide completeness pass the same day and re-verified by the correctness
   pass — the elided and wave-2 sets below unchanged): **51 fences across the 11
   pages**, of which **50** are ` ```clojure ` and 1 is an unlanguaged output block
   (guide 06's Xray timeline). Of the 50 Clojure fences, at least four are deliberately illustrative
   fragments carrying elisions — guide 01's `deps.edn` stub (`{…}`), guide 03's
   conditional-read one-liner (`… ✓`), guide 05's `media-bridge` body (`…)`), guide
   08's `render-static` call (`{…}`) — and one (guide 02's `data/render`) names a
   **wave-2** artefact that does not exist in v1 ⟨12 §2 blessed table⟩. "Every example
   compiles" cannot naively mean "every fence compiles"; the contract must make the
   exceptions *visible on the page* or the promise rots silently. (As of 2026-07-13
   those five fences carry their `;; guide:no-fixture` markers on the page — the §2
   mandatory set, added ahead of the S6 move.)
3. **Stage markers are load-bearing.** The README's stage-honesty convention —
   unmarked = Stage 1, `*(lands S2 — reactive subs)*` etc. — annotates whole chapters
   (stage notes), sections (header markers), and single bullets ⟨guide/README §Stage
   honesty; grep evidence across all 10 chapters⟩. A fixture for an `*(lands S2)*`
   example must **activate with S2, not fail before it** — and must not be silently
   absent either, or "everything green" means "nothing ran". The S1d precedent handled
   this by *adapting*: sub reads became props, `dispatch!` usage was reshaped to the S1
   structural subset, and the docstring recorded each adaptation
   ⟨test_guide09_fixture_jvm_test.clj lines 2–10, 34–40⟩. That adaptation ledger is the
   seed of the stage-gating design in §3.

## 1. Extraction model — recommendation

Three candidate models, one recommendation.

| Model | Mechanism | Strengths | Why it fails as the *whole* answer here |
|---|---|---|---|
| **(A) Literate extraction** | a script parses guide `.md` fences into generated test namespaces | single source of truth; page↔fixture drift impossible by construction | the extractor is S6 work by plan (constraint 1 — the pages are CI-readable, but no extractor exists before the docs move); a bare fence lacks everything the S1d worker had to invent — ns preamble, requires, the fixture app surface (`fixture-catalog`, event registrations, the reset-runtime fixture), and assertions for non-test fences — so "parse fences" alone under-specifies the pipeline |
| **(B) Mirrored fixtures** | hand-maintained fixture files 1:1 with chapters, drift-checked against the page | works **today**, needing nothing beyond the tracked pages, with human judgment for stage adaptation — this is exactly the S1d precedent | at guide scale (51 fences and growing) hand-mirroring is a standing tax; the drift gate is structurally weak while adaptations are legitimate (an adapted fixture *should* differ from its fence, so byte-comparison either fails spuriously or gets normalised into meaninglessness); and no comparison harness exists before S6 to make the check mechanical |
| **(C) Annotation-driven extraction** | fence-level markers (eligibility, stage, target, fixture id) drive a generator; per-chapter scaffolds supply context; assertions live in companion files | keeps the page the source of truth **and** makes the exceptions visible in the page source; stage markers become machine-readable; the manifest falls out for free | still lands at S6 with the docs move (constraint 1); needs marker + scaffold conventions defined up front or the completeness pass authors fences the extractor can't consume |

**Recommendation: (C) annotation-driven extraction is the pipeline, landing at S6 with
W2/W3 — with (B) hand-mirrored fixtures as the S2–S5 bridge, per the S1d precedent.**
One model, two phases; the bridge is explicitly temporary and each bridge fixture is
retired chapter-by-chapter as extraction takes over at S6.

Why (C) over pure (A): the annotations are not optional sugar — they are how the page
declares the facts extraction cannot infer (illustrative-fragment status, stage
overrides, JVM-vs-DOM target), and they keep the README promise **honest by default**:
a fence is fixture-eligible unless it visibly opts out, so a reader auditing the
promise can see every waiver in the page source (§2). Why (C) over indefinite (B): the
drift-gate problem dissolves when the generated namespace *is* produced from the fence
at gate time — there is no second copy to drift (§5) — and the standing mirroring tax
disappears exactly when the guide's growth rate is highest (post-completeness-pass,
post-adoption).

Why the bridge must exist at all: constraint 1 — the extractor is S6 work, and the
alternative of holding all guide fixtures until S6 breaks the per-stage proof column of
⟨12 §2b⟩ (surface rows name guide-shaped fixtures as their stage proof) and abandons
the S1d precedent that already ships. The bridge fixtures are the stage proofs; the S6
pipeline is the scale answer.

**Authoring rule for the completeness pass, effective now:** write new fences as if the
extractor already existed — complete forms, marker comments where §2 requires them,
stage markers per the README convention. This costs nothing today and makes the S6
cut-over mechanical instead of archaeological.

## 2. The fence contract

What makes a fence fixture-eligible, and how a fence declines. Eligibility is
**opt-out**: the default state of a ` ```clojure ` fence is *eligible*, so the README
promise holds unless a fence visibly waives it, on the page, with a reason.

**Eligible by default** — a fence is consumed by the pipeline iff all of:

1. **Language-tagged `clojure`.** Unlanguaged fences (output blocks, timelines, HTML
   sketches) are never eligible — free structural filtering; the guide 06 Xray timeline
   needs no marker.
2. **Complete forms, no elisions.** The fence body reads as a sequence of complete
   top-level forms (or one complete expression). Any code-position elision — the
   typographic `…` or `...` outside strings and comments — makes the fence
   **ineligible-until-marked**: the extractor *rejects* it (loudly, naming the fence)
   rather than skipping it, forcing the author to either complete the example or mark
   it. This is the honesty ratchet: an unmarked fragment is a build error of the docs
   pipeline, not a silent coverage hole.
3. **v1 surface only.** A fence exercising a wave-2 name (`data/render`, `ui/element`,
   `ui/view`, `ui/portal` ⟨12 §2⟩) must carry the no-fixture marker — wave-2 does not
   ship, so nothing can compile it. The extractor cross-checks fence symbols against
   the §2 blessed table's wave-2 rows and rejects an unmarked wave-2 fence.

**The marker vocabulary** (first line(s) of the fence body, ordinary Clojure comments —
visible in the page source and in the rendered page, which is the point):

| Marker | Meaning |
|---|---|
| `;; guide:no-fixture — <reason>` | this fence is illustrative; it is *not* a CI fixture. Reason is mandatory and renders on the page. Expected uses: elided install stubs (01 `deps.edn`), deliberate fragments kept fragmentary for prose flow, wave-2 surfaces (02 `data/render`: `;; guide:no-fixture — wave-2, does not ship in v1`) |
| `;; guide:stage S<N>` | stage override when the fence's stage differs from what the enclosing section/chapter markers imply. Default inference: nearest enclosing header marker `*(lands S<N>…)*`, else the chapter stage note, else S1 (the README convention). Most fences need no marker — the prose markers are already machine-readable |
| `;; guide:target dom` | route this fence to the DOM-target generated namespace (browser tier) instead of the default JVM tier — mount/hydrate fences that touch `js/document` (§4). Default target is `jvm` |
| `;; guide:fixture <id>` | optional stable id for a fence, for companion-assertion reference and manifest rows. Default id: `<chapter>-<ordinal>` |

Nothing else. The vocabulary is closed, like every other grammar in this program;
a fence carrying an unknown `guide:` marker is an extractor error.

**Scope note — inline code spans are out.** The promise binds *fenced examples*. Prose
one-liners and inline spans (guide 09's selector bullets, decision-table cells) are
covered opportunistically — the S1d precedent lifted two of them as extra deftests, and
bridge fixtures may keep doing so — but they are not counted, not manifest rows, and
their absence is not a promise violation. Trying to extract inline spans is how
literate pipelines drown.

**README amendment at S6** (one sentence, honesty): once every chapter is active, the
README's exact-coverage statement (re-based 2026-07-13 — it names guide 09's fixture as
the only one live today) becomes the full promise, gaining "— except the handful marked
`guide:no-fixture` on the page, each with its reason", plus the marker convention in
the stage-honesty paragraph. Until then coverage is stated exactly on the README and
counted per chapter by the manifest (§3).

## 3. Stage gating: activate with the stage, park loudly before it

The invariant, both phases: **a fixture for an `*(lands S<N>)*` example is parked until
S<N> ships, active from the wave in which S<N> ships, and the parking is manifest data
that CI asserts — skipped-not-silent.**

**The manifest.** One checked-in EDN value (a var in
`re-frame.ui.guide-fixture-manifest`, test tree of `implementation/ui/`), rows per
chapter:

```clojure
{:chapter "09-testing"
 :fixture-ns 're-frame.ui.test-guide09-fixture-jvm-test
 :status :active                       ; :active | :parked
 :stage :s1                            ; the stage whose shipping activates/activated it
 :fences {:eligible 3 :waived 0}       ; census at last lift
 :adapted [{:section "Tier 1" :what "sub reads → props" :until :s2}
           {:section "Tier 1" :what "dispatch!-driven loop → structural subset" :until :s2}]
 :lifted "2026-07-12"}
```

`:adapted` is the S1d docstring ledger promoted to data: every stage-driven deviation
from the page is enumerable, so "de-adapt guide 09" at S2 is a mechanical checklist,
not a re-read.

**The manifest gate** (one JVM deftest beside the manifest, discovered by the ordinary
cognitect runner — zero new CI wiring) asserts:

1. every `:active` row's namespace exists and was discovered this run;
2. every `:parked` row's `:stage` is **not** in the shipped-stages set — i.e. *a
   shipped stage may have no parked rows*. This is the activation forcer: when the S2
   epic flips shipped-stages to `#{:s1 :s2}`, the gate goes red until every
   `:stage :s2` row is activated (fixture landed, adaptations removed). Activation
   rides the stage's own wave because the gate makes anything else red;
3. every `:adapted` entry's `:until` stage is not shipped (same forcer, finer grain —
   guide 09 is `:active` at S1 but its two adaptations park on S2);
4. every guide chapter has a row (the completeness pass adds chapters; a chapter with
   no row is red). Bridge-era source for "every chapter": the manifest itself carries
   the chapter list — the tracked pages are CI-readable, but with no extractor before
   S6 the list is maintained by hand and verified against the pages by the local drift
   script (§5); from S6 the extractor generates it.

**Skipped-not-silent under the quiet-tests policy.** Green output stays quiet
⟨docs/quiet-tests.md via TESTING.md⟩, so the "N fixtures parked per stage" report is
not a console banner — it is the committed manifest itself (reviewable, diffable,
greppable), and the gate makes every dishonest state red: a parked row whose stage
shipped, an adaptation that outlived its stage, a chapter with no row. "Everything
green" therefore *cannot* mean "nothing ran": green means "everything the manifest
says is active ran, and nothing the manifest says is parked should have been active".

**Shipped-stages anchor.** The gate needs one authoritative "which stages have shipped"
value. Recommend a single set declared beside the manifest, bumped by the stage epic's
gate-wiring bead (the ⟨12 §3⟩ standing rule — every stage's gates wire in-stage —
already creates that bead). **[S3-CONFIRM]** whether the S2 epic prefers deriving this
from an existing artefact-level stage var instead of a test-tree set; either works, one
must be named authoritative.

**End-state stage gating (S6).** The extractor reads the page's own stage markers
(chapter stage notes, header markers, `;; guide:stage` overrides), consults
shipped-stages, and routes each fence: shipped → generated namespace; unshipped →
manifest `:parked` row. Post-S6 all of S1–S5 has shipped, so parking then only concerns
S6/S7-marked content (`->react` *(lands S6)*, Story *(rides S6)*) and future-stage
markers the completeness pass adds — the mechanism outlives the bridge.

## 4. Assertion shape: the default per example kind

Guide prose states outcomes ("renders", "the button carries the intent", "is a compile
error with the fix in the message"). Following the S1d precedent's adaptations, pin one
default assertion per fence kind — companions add more, never less:

| Fence kind | Default assertion | Precedent / source |
|---|---|---|
| **`deftest` fence** (01 §Prove-it, 04 §plan-select, 09 §Tier 1, 10 §Tests) | self-asserting — lift verbatim; the fence *is* the fixture | S1d `add-button-carries-intent` is guide 09's block at verbatim shape |
| **View/dataflow definition fence** (`defview` / `reg-event` / `reg-sub` bodies: 02, 03, 04 §form, 10 §state-shape) | compiles + registers + **render-succeeds**: `ui.test/render` against a scaffold-supplied frame and props returns a tree, and `(ui.test/find tree <view-id>)` finds the boundary node | the scaffold's render-cases table is the generalisation of S1d's invented `fixture-catalog`; view-id findability per ⟨drafts/ui-test-selector-grammar.md §view-sel⟩ |
| **Intent example** ("what does this button do") | `find`/`attrs` equality on the event vector, read through the projections, never keyword lookup on the node | S1d `intent-assertion-respelled-through-attrs`; ⟨guide 09 §Tier 1 ground rules⟩ |
| **State-drive example** (`dispatch!` → re-render → assert) | dispatch on a real frame, re-render, assert the new tree — *(S2; parked/adapted until then per §3)* | S1d `drive-state-with-real-events` (adapted form) |
| **Didactic-error example** (02 `:key`-reserved, 03 sub-in-loop, 04 loop-captured vector, 06's build-time roster, 08 duplicate-root) | **compile-error-with-id**: macroexpansion throws, and the error carries the catalogued id — asserting the guide's *claim*, not a message string | the ui suite's `analyze_reject` / `error_roster` shape; Spec 009 catalogue discipline ⟨guide 06 §Loud, early, didactic⟩ |
| **Mount/host fence** (`js/document…`: 01 §whole-app, 05 §frame-root, 08 §host tier, 10 §Mount) | JVM tier takes the fence's non-host forms (the S1d move); the mount form itself routes `;; guide:target dom` → generated `*_dom_cljs_test.cljs`, default assertion mount-succeeds + unmount-total | S1d lifted the defviews and skipped the mount; DOM suffix convention per ⟨TESTING.md §Kinds — browser unit⟩ |
| **Config/infra fence** (01 `deps.edn`) | none — `guide:no-fixture` | install surface, not API |

Two rules ride along from the precedent, verbatim into the pipeline contract: reads go
through `ui.test/attrs` / `ui.test/text` (a fixture keyword-looking-up a node field is
a review reject — it "silently misses" ⟨guide 09⟩), and every fixture app surface the
scaffold invents must be `.cljc`-honest (the guide's own Tier-1 ground rule).

**Scaffolds.** Per chapter, one hand-maintained file in the test tree
(`guide_scaffold/chNN.clj[c]`): ns preamble + requires, the reset-runtime fixture, the
fixture app surface (catalogues, registrations), and the render-cases table (view →
props) that the render-succeeds default consumes. Scaffolds are the *permanent* hand
component of model (C) — the judgment lives there; the fences stay clean. A fence
using a name the scaffold doesn't supply is a compile error of the generated
namespace: self-checking, no separate lint.

## 5. Drift gate

Per phase, because the mechanism differs:

**End-state (S6+): drift is impossible where it matters, gated where it remains.**
Generated namespaces are produced from the page at gate time and are **not
committed** — the page is the single source; there is no second copy to drift.
Residual drift surfaces and their gates:

1. **Scaffold ↔ page**: a renamed view or event in a fence breaks the generated
   namespace's compile — self-checking, red on the PR that edits the page (the
   classifier must fire the gate on guide edits — §6).
2. **Extractor correctness**: self-tests in the script harness (the
   `test:script-helpers` pattern) over a golden corpus — md in, expected extraction
   out, including every marker, every rejection (unmarked ellipsis, unknown marker,
   unmarked wave-2 symbol).
3. **Manifest freshness**: the gate regenerates the manifest from the pages and
   **byte-compares** with the committed manifest — the deterministic-output freshness
   pattern the playground gates already use ⟨TESTING.md §Kinds — docs/cljs
   playground⟩.
4. **Any surviving hand-adapted region** (post-S6 there should be almost none):
   delimited `;; guide:begin <chapter>#<fence-id>` / `;; guide:end` blocks in the hand
   file, compared to the fence body under exactly three normalisations — CRLF→LF, strip
   `;; guide:*` marker lines, trim trailing blank lines. **Nothing else is
   normalised**: the page shows real code and the quoted region must be verbatim.
   Token-level or whitespace-insensitive comparison is rejected — it licenses cosmetic
   divergence, and cosmetic divergence is how "the page is the contract" dies.

**Bridge era (S2–S5): procedural + local-script, honestly not a CI gate.** The tracked
pages are CI-readable, but no extraction/comparison harness exists before S6
(constraint 1), so page↔fixture comparison does not gate a PR during the bridge — the
check stays procedural. Instead:

- every bridge fixture carries the `guide:begin/end` delimiters and the manifest's
  `:lifted` date + `:adapted` ledger from day one (S1d's docstring, promoted to the
  §3 data shape);
- a local-only script (`scripts/check-guide-fixture-drift.cjs` — Node, cross-platform
  per the repo's script policy) compares delimited regions against the local pages
  under the same three normalisations, honouring `:adapted` rows as expected-divergent.
  Workers touching a chapter run it pre-PR; it is *not* wired into workflows until S6;
- the stage activation forcer (§3) bounds staleness structurally: every stage landing
  forces a re-lift of that stage's chapters, so no bridge fixture can silently trail
  the page by more than one stage of guide evolution.

This is the deliberate weak spot of the bridge phase — named here rather than papered
over. It is acceptable because the bridge corpus is small (one file per chapter), every
divergence is enumerated in the manifest, and the S6 cut-over deletes the whole
category.

## 6. Ownership + sequencing

**What rides which stage.** Hand-mirrored fixtures continue S2–S5, one bead per chapter
activation, riding the owning stage's epic (they are that stage's proof rows per
⟨12 §2b⟩; one chapter per bead per the split-don't-bundle rule):

| Stage | Guide fixture work riding it |
|---|---|
| **now (S1 remainder)** | manifest + manifest gate land (S1-scoped, parked rows for everything unshipped); S1-eligible chapters mirrored: 01 (subless subset), 02 (templates, spread, `ui/html`, compile-error roster claims), 08 (structural/JVM-tree subset). Guide 09 already shipped (S1d) — its manifest row gains the `:adapted` ledger |
| **S2** | activate/de-adapt: 01 (full counter — sub path), 03 §sub + §lease, 05 (frames runtime), 09 (de-adapt: `dispatch!`, drive-state), 10 (tiles, `dispatch!` tests), 07 (narrow-read economics fences, if the completeness pass adds any) |
| **S3** | 03 §local + §effect, 04 (whole chapter: placeholders, `ui/event`, sync door, decision table, loop rules), 02 §error-boundary + `.react` tier, 06 (runtime-warning ids + `ui.tool` fences), 10 §risky-part |
| **S4** | 02 §presence + §custom-elements; `flush-presence!` examples in 09 |
| **S5** | 08 §hydration + §render-static + §client-only flip |
| **S6 (W2/W3, with W9 for CI wiring)** | **the pipeline**: extractor + marker grammar enforcement + scaffolds (converted from bridge fixtures) + generated-namespace wiring + manifest generation/freshness gate + drift gate for surviving adapted regions + README amendment; bridge fixtures retired chapter-by-chapter as extraction takes over (verbatim-lifted deftests move to page-extracted namespaces; invented app surfaces become scaffolds; hand files die). Plus content that itself lands S6: 02 `->react`, 09 §Story |

**CI wiring shape.** Bridge era: zero new wiring — fixtures are ordinary
`test_guideNN_fixture_jvm_test.clj` files under `implementation/ui/test/`, discovered
by the cognitect runner via the existing `:test` alias ⟨implementation/ui/deps.edn⟩,
riding the `jvm-ui` job under the existing `implementation_jvm` / `ui_gates` classifier
outputs ⟨TESTING.md §Changed-surface classifier⟩. DOM-target fixtures (few: the mount
fences) ride `test:browser` under `cljs_browser`. **[S3-CONFIRM]** whether mount fences
justify the browser tier at all during the bridge, or stay compile-only until S6 — the
adapter smokes already prove mount+dispatch+assert per adapter, and a guide-mount
browser fixture may duplicate that signal (⟨TESTING.md §Placement — unique signal⟩
argues compile-only).

S6 additions: a `test:guide-fixtures` npm script (extract → generate into a
non-committed dir on the test classpath → run) folded into the `jvm-ui` job; a
TESTING.md Kinds row; and **one deliberate divergence from the spec-impl-pair
convention to name explicitly**: top-level `spec/*.md` has no classifier rule, but
guide pages, once fences are executable, are *impl-adjacent* — a guide edit can break a
gate — so the guide's docs path gets its own classifier output (`guide_fixtures`)
firing `jvm-ui` + the extractor self-tests. That is the opposite default from
`spec/*.md`, on purpose, and should be recorded in TESTING.md's spec-impl-pair section
when W9 lands it.

**Cost (the G-14 remainder).** The G-14 row "guide-fixtures CI cost bounded"
⟨07-testing §5⟩ becomes dischargeable once the bridge corpus exists at S2 scale: add a
wall-clock budget assertion over the guide-fixture namespaces to the ui JVM suite
(suggest ≤ 10 s total at S2 scale, revisited per stage — **[S3-CONFIRM]** the number
with the S2 corpus in hand). The fixtures are Tier-1 JVM renders — "milliseconds,
never flake on timing" ⟨guide 09⟩ — so the budget is a pathology tripwire in the G-14
style, not a performance target.

**Demand-bar coupling (free win).** ⟨07-testing §7⟩: "The examples corpus feeds the
demand-bar table (08 §3)". The manifest rows already enumerate which public names each
chapter's fixtures exercise once the extractor emits symbol usage per fence — making
the ⟨12 §2⟩ consumer column *auditable by grep* instead of by assertion. Emit it as a
manifest field at S6; no extra machinery.

## 7. Open items

- **[S3-CONFIRM]** shipped-stages anchor: test-tree set beside the manifest vs an
  artefact-level stage var (§3).
- **[S3-CONFIRM]** DOM-target mount fixtures during the bridge: browser tier vs
  compile-only, given adapter-smoke overlap (§6).
- **[S3-CONFIRM]** the G-14 guide-fixture wall-clock budget, once the S2 corpus exists
  (§6).
- **[S3-CONFIRM → owner reassigned]** extractor implementation language: Node `.cjs`
  per the script-harness precedent (self-testable via the `test:script-helpers`
  pattern, cross-platform) is the default assumption here. This is an **S6 decision,
  not an S3 one** — owned by the docs-move plan's PR-B extractor bead
  (⟨drafts/guide-docs-move-plan.md §3⟩), which confirms or overrides the default;
  nothing at S3 depends on it (reassigned by the completeness pass, 2026-07-12).
- **[S3-CONFIRM]** whether the manifest gate lands now (recommended: yes, S1-scoped,
  parked rows for 02–10) or with the S2 epic — landing now makes the completeness
  pass's new chapters immediately accountable (§3 rule 4).
