# DRAFT — Guide → docs move plan (S6, W2/W3): landing shape, transforms, PR sequencing

> **Status: DRAFT · 2026-07-12 · re-based 2026-07-13.** The concrete migration plan for
> moving the re-frame.ui user guide (11 files, ⟨guide/⟩ — completeness + correctness
> passed 2026-07-12) out of the synthesis tree into the repo's human-facing docs at S6,
> per ⟨11 W2/W3⟩ and ⟨12 §3 S6 epic⟩. Note the tree's tracking state: the synthesis
> subtree has been **force-tracked in git since #5800** (the surrounding `ai/` tree
> stays local-only), so the pages are already in-repo and CI-readable — the S6 move is
> about giving them their `docs/` home and reader-facing shape, not about making them
> visible. The fixture pipeline (⟨drafts/guide-fixture-pipeline.md⟩) depends on this
> landing shape for its S6 phase; §3 below is the handshake. Open items are tagged
> **[S6-CONFIRM]**.

## 0. Facts the plan stands on

1. **The docs system.** MkDocs Material, `docs_dir: docs`, top-level nav tabs (Home /
   Core / API / Machines / Resources / Async / Routing / SSR / Story / Xray / Skills),
   `navigation.tabs` + `navigation.indexes` (a section's index page IS the clickable
   tab label), `mkdocs build --strict` from repo root after staging `spec/` +
   `migration/` copies under `docs/` ⟨mkdocs.yml; docs.yml §Stage spec/⟩.
2. **The gates.** `.github/workflows/docs.yml` fires on `docs/**` + `mkdocs.yml` (push
   path filter; PR-side the `detect` job classifies the diff): link/anchor validator
   (`scripts/check_doc_slugs.py`, fail-loud), EP status-sync, four residue gates,
   playground-bundle rebuild + sanity, `mkdocs build --strict`, and the always-present
   `Docs required` aggregator that blocks merge on failure ⟨docs.yml⟩. **No workflow
   edit is needed for the move** — `docs/**` and `mkdocs.yml` are already on every
   filter.
3. **Hot-zone status.** `mkdocs.yml` is **not** on the fixed hot-zone list (spec/\*
   named files + top-level `implementation/deps.edn` + `shadow-cljs.edn` +
   `.github/workflows/*`) ⟨CLAUDE.md §Workflow⟩. It is, however, a high-traffic file
   (most docs PRs touch the nav) — treat the nav edit as sequence-with-care, not formal
   hot-zone: don't run a second nav-touching docs PR in parallel with the move.
4. **House style.** Diátaxis one-job pages; never link into `spec/` from reader-facing
   docs ("standalone corpus"); MkDocs admonitions (`!!! note`, collapsed `??? info`),
   not bold-lead blockquotes; British spelling (`behaviour`, `memoised`,
   `serialisable` — uniform across docs/core, verified by grep); no bead ids or ruling
   dates in prose — "pages state current truth" ⟨docs/core/AUTHORING.md §§2, Callouts,
   Honesty; docs/core grep 2026-07-12⟩.
5. **The pages as they stand.** All markdown links are intra-guide same-directory
   relative links (`[02](02-views.md)` etc. — zero links out of `guide/`, verified);
   one prose reference points outside ("design rationale lives one directory up",
   ⟨guide/README ¶1⟩); ~18 inline stage markers + 8 stage notes (correctness-pass
   re-census — the completeness pass added chapter-top notes) + the README
   stage-honesty paragraph; the **five mandatory `;; guide:no-fixture` markers** exist
   on the pages (added 2026-07-13 with the stage-truth reconciliation — the §2.4 set);
   zero images ⟨guide/ greps 2026-07-12/13⟩.

## 1. Landing location + nav

**Recommendation: a new top-level tab — `docs/ui/` — labelled "UI", inserted between
Core and API.** Not folded into `docs/core/`.

Why a tab, not Core integration:

- **During S6 both worlds coexist.** Core's view surfaces (views.md, frames.md, the
  introduction counter, the how-tos) teach `reg-view` — the tier that is *shipping and
  supported* until S7. Folding `defview` pages into Core mid-stage would force every
  Core page to disambiguate two view APIs while one of them is still the default.
  A separate tab keeps both corpora truthful for the whole transition window.
- **The house pattern.** Cohesive sub-domains earn their own tab (Machines, Resources,
  Routing, SSR, Story, Xray) rather than piling into an existing one; the guide is a
  coherent 10-chapter learning track with its own index — the same shape as the Story
  and Xray tabs ⟨mkdocs.yml nav; standing tab-per-sub-domain ruling⟩.
- **Post-S7 end state.** After the deletion wave (W13) the substrate IS the views
  layer. The end state is **two surfaces with different jobs**: `docs/core/` view
  pages get the downstream re-teach ⟨drafts/spec-004-rewrite-draft.md L882: "docs/core/
  views.md, guide pages, examples, skills — downstream re-teach (Stage-6…)"⟩ — the
  growth-order Core track keeps a Views concept page, rewritten over
  `defview`/`sub`/data handlers — while the UI tab remains the view-layer *depth*
  corpus (presence, interop, SSR roots, testing tiers, the worked app), exactly as
  Machines is depth beside Core's app-db page. The tab is not a transition artefact
  that later dissolves; it is the permanent home. Specifically at the re-teach:
  - `docs/core/views.md` — **rewritten, not deleted** (the Core track needs its Views
    page); `reg-view` teaching replaced by `defview`; live cells ride the rebuilt
    playground; a collapsed `??? info` compat note routes frozen-tier users to
    `docs/ui/migration.md`.
  - `docs/core/how-to/use-uix-helix-or-slim.md` — **deleted** (nav row removed; link
    sweep; no redirects plugin is installed, so inbound links must be swept in the
    same PR) ⟨11 W3 names the deletion explicitly⟩.
  - `docs/core/how-to/boot-and-mount-an-app.md`, `build-a-form.md`,
    `fix-a-slow-view.md`, `keep-secrets-out-of-traces.md`, `introduction.md`,
    `frames.md`, `glossary.md`, `docs/core/testing/views.md` — re-taught over the new
    surface (16 files in docs/core currently mention `reg-view`; the re-teach wave
    sweeps all of them) ⟨grep docs/core 2026-07-12⟩.
  - `reg-view` as *live teaching* survives only on the compat/migration surface:
    `docs/ui/migration.md` (§4 below), the `25-from-re-frame-v1.md` track, and — spec
    side, not docs side — Spec 004A when it promotes at S7
    ⟨drafts/spec-004A-reagent-compat-appendix.md status⟩.

**File mapping** (11 guide files + the migration doc, §4):

| Source (ai tree) | Lands as |
|---|---|
| `guide/README.md` | `docs/ui/index.md` (navigation.indexes: this IS the "UI" tab label/landing) |
| `guide/01-getting-started.md` … `guide/10-worked-app.md` | `docs/ui/01-getting-started.md` … `docs/ui/10-worked-app.md` (names unchanged) |
| `10-migration-from-reagent.md` (suite doc, one directory up) | `docs/ui/migration.md` (unnumbered — resolves the "10" collision, §4) |

**mkdocs.yml nav diff sketch** (inserted after the Core block, before API; labels
follow the Story/Xray "N. Title" convention):

```yaml
  - UI:
    # Section label IS the UI landing page (navigation.indexes).
    - ui/index.md
    - "1. Getting started": ui/01-getting-started.md
    - "2. Views": ui/02-views.md
    - "3. State": ui/03-state.md
    - "4. Events": ui/04-events.md
    - "5. Frames": ui/05-frames.md
    - "6. Debugging": ui/06-debugging.md
    - "7. Performance": ui/07-performance.md
    - "8. Server rendering": ui/08-ssr.md
    - "9. Testing": ui/09-testing.md
    - "10. A worked app": ui/10-worked-app.md
    - "Migrating from Reagent": ui/migration.md
```

The migration page sits last in the tab — the house slot occupied by "Coming from
XState" / "Coming from TanStack Query" / "Coming from Next.js" in the other tabs.

**[S6-CONFIRM]** tab label: "UI" (terse, matches Machines/Xray register) vs
"Views (re-frame.ui)" (self-describing while Core's reg-view pages still exist). This
plan assumes "UI".

## 2. Mechanical transforms the move performs

The move PR is the **last page-shape edit** before the pages freeze as the extractor's
input. Every transform below happens in it, once.

### 2.1 Cross-reference fates (the guide must stand alone)

| Reference | Fate |
|---|---|
| Intra-guide chapter links (`[02](02-views.md)` — ~30 sites, all same-dir relative) | **Keep unchanged**; README→index rename doesn't affect them (no chapter links back to README) |
| ⟨guide/README ¶1⟩ "design rationale lives one directory up" | **Remove the clause.** The synthesis suite does not move; docs never link `spec/` ⟨AUTHORING §2⟩. The sentence becomes "This guide teaches the library as a user." |
| ⟨guide/README ¶1⟩ the fixture-coverage statement | **Update to the move-time truth.** Since 2026-07-13 the README states exact coverage (guide 09's fixture today, growing per stage); by S6 all S1–S5 chapters are active per the stage forcer, so the sentence becomes the full every-example promise. The "except fences marked `guide:no-fixture`" amendment lands with the extractor PR, not the move ⟨guide-fixture-pipeline §2 README amendment⟩ |
| ⟨10-migration doc⟩ links to `drafts/reagent-compat-boundary.md` (×3) | **Replace with inline prose.** The draft doesn't move; its normative content promotes spec-side (004A at S7), and docs never link `spec/`. The page inlines what the reader needs: the three co-mount granularities and the ownership/teardown one-liners it currently cites |
| ⟨10-migration doc⟩ synthesis-suite citations ("08 §2", "12 §2", "02 §3 table", "06 §1", "guide 03") | **Rewrite or drop.** "02 §3 table" → link `04-events.md` (the decision table's guide home); "guide 03" → `03-state.md`; "06 §1 subset check" → `08-ssr.md`; stage-plumbing citations ("08 §2", "12 §2", delta numbers) → **drop** — program management is not reader content |
| Provenance parentheticals: "Blessed-table verdict; qualifier added 2026-07-12" ⟨guide/02 L73–74⟩, "delta #2, ruled 2026-07-12; 12 §2" ⟨10-migration⟩, "(an Xray addition staged behind its integration review)" ⟨guide/06 L32⟩, the migration doc's **Status:** line | **Strip.** "No bead ids in prose. Pages state current truth" ⟨AUTHORING §Honesty⟩. The *facts* stay (wave-2 status, S7 freeze); the ruling ledger goes |

Acceptance grep (§5): no `findings/`, `new-substrate-synthesis`, `drafts/`, `⟨`, or
`ai/` reference survives anywhere under `docs/ui/`.

### 2.2 Stage markers: strip shipped, keep genuinely-future

Census at 2026-07-12 (correctness-pass re-count): ~18 inline `*(lands S<N> …)*` markers
across chapters 02/03/04/06/08/09/10 (+ the README convention example), and **eight**
stage notes — the 01 §The whole app blockquote, chapter-top italic notes on
04/05/06/07/08 and 10-worked-app, the 09 §Tier 1 list note — plus the README
stage-honesty paragraph.

By the docs-move point in the S6 epic order (migrator → examples → tools' UIs →
template → **docs/guide + skills** → CI → benchmarks ⟨12 §3 S6⟩), S1–S5 have shipped:

- **Strip every S1–S5 marker and all eight stage notes.** A marker for a shipped stage
  is noise at best and rot at worst. This includes the mid-sentence variants ("*(a
  re-render that reads `sub` sites rides S2's snapshot path)*" ⟨guide/09⟩, "*(Dispatch
  behaviour lands S3.)*" ⟨guide/10-worked-app⟩) — the sentences read correctly with the
  parenthetical deleted; where one doesn't, reword to present tense.
- **The two S6 markers** — `->react` *(lands S6)* ⟨guide/02 L144; 10-migration⟩ and
  Story *(rides the migration wave — S6)* ⟨guide/09 L88⟩ — strip **iff** their owning
  S6 beads have merged when the move PR opens (normally yes: W1/W7b precede W2/W3 in
  the epic order). If either hasn't, keep the marker and strip it in that bead's own
  PR. **[S6-CONFIRM]** at dispatch time — check merge state, don't assume.
- **Wave-2 qualifiers stay, verbatim.** `ui/element` / `ui/view` / `ui/portal` /
  `re-frame.ui.data/render` remain "wave-2 — does not ship in v1" ⟨guide/02 L73, L146;
  07 L49; 08 L17⟩. That's the honesty rule ("mark deferred surfaces" ⟨AUTHORING
  §Honesty⟩), and the pipeline's eligibility check depends on the wave-2 fence carrying
  its no-fixture marker (§2.4).
- **README stage-honesty paragraph** ⟨guide/README L22–28⟩: rewritten down to one
  sentence on the wave-2 names ("Wave-2 names (…) are not v1 and only ever appear with
  that qualifier."). The S1-default framing and the marker convention are obsolete once
  no S1–S5 markers remain.

### 2.3 British spelling + house admonitions

- **Spelling sweep** (docs/core is uniformly British — `behaviour` ×40+, `memoised`,
  `serialisation`; verified by grep): `memoization`/`memoized`/`memoizes` →
  `memoisation`/`memoised`/`memoises` (README, 02, 04, 07 — ~10 sites);
  `behavior`/`behavioral` → `behaviour`/`behavioural` (08 L19, 09 L52/L102);
  `sanitized` → `sanitised` (02 L158); `realized` → `realised` (02 L130);
  `artifact` → `artefact` (02 L66; the guide already uses `artefact` elsewhere).
  **Code and identifiers are exempt**: the `:center` prop in 08's Mapbox example, API
  names, fence contents generally — but the four prose sites above that sit *inside*
  fences as comments don't exist (all listed sites are prose). Spelling edits never
  touch fence bodies (§2.4 byte-stability).
- **Admonition conversion** ⟨AUTHORING §Callouts: "MkDocs admonitions, not bold-lead
  blockquotes"⟩: the `> **Stage note.**` blockquote (01) and the italic stage notes
  (04–08, 09, 10) are deleted outright by §2.2. Remaining bold-lead asides that survive as content
  convert to `!!! note` (expanded) or `??? note` (optional depth). The index page's
  "Coming from Reagent?" / "Coming from React / UIx / Helix?" paragraphs stay as prose
  — they are routing content on an index, not asides. **[S6-CONFIRM]** optionally
  convert them to collapsed `??? info` persona deltas per the house delta-teaching
  pattern; prose is the lighter default.
- **Pull-quote check**: chapters already carry their one-liner contracts in prose; no
  forced takeaway boxes added.

### 2.4 Fence markers: add the mandatory ones now, freeze the fences

The five mandatory markers are **already on the pages** (added 2026-07-13 with the
stage-truth reconciliation), so the extractor PR touches zero pages. The pipeline's
fence contract makes unmarked elisions and unmarked wave-2 fences an extractor *error*
⟨guide-fixture-pipeline §2⟩. **The move PR verifies the census** — any fence added
since must carry its marker; the set as of 2026-07-13:

| Fence | Marker |
|---|---|
| 01 `deps.edn` stub (`{…}`) | `;; guide:no-fixture — install stub, elided coordinates` |
| 03 conditional-read one-liner (`… ✓`) | `;; guide:no-fixture — illustrative fragment` |
| 05 `media-bridge` body (`…)`) | `;; guide:no-fixture — illustrative fragment, elided body` |
| 08 `render-static` call (`{…}`) | `;; guide:no-fixture — illustrative fragment` |
| 02 `data/render` | `;; guide:no-fixture — wave-2, does not ship in v1` |

(Exact census per ⟨guide-fixture-pipeline §0 constraint 2⟩; re-count at execution — the
completeness pass may have changed the set.) Everything else about fence bodies is
**byte-stable through the move**: the drift-comparison normalisations already strip
`;; guide:*` lines and CRLF, so added markers don't break bridge-fixture comparison
⟨guide-fixture-pipeline §5⟩, and any other fence edit would.

**Live cells: none.** Fences stay ` ```clojure ` — no ` ```cljs-rf2 ` conversion in the
move PR. Two reasons: the playground SCI bundle doesn't carry `re-frame.ui` until W3's
playground rebuild, and extractor eligibility keys on the `clojure` language tag — a
live-cell conversion would silently drop that fence from fixture coverage
⟨guide-fixture-pipeline §2 rule 1⟩. Pin the rule now: **converting any guide fence to a
live cell requires an extractor-eligibility ruling first** (either the extractor learns
`cljs-rf2`, or the fence takes an explicit no-fixture/waiver). **[S6-CONFIRM]** at the
playground-rebuild bead.

### 2.5 Assets

None — the guide has no images ⟨grep `![` = 0⟩. No asset policy needed. Future
diagrams use native ` ```mermaid ` fences ⟨mkdocs.yml superfences⟩; raster assets, if
ever, follow the existing `docs/images/` convention.

## 3. Fixture-pipeline handshake: move first, extractor second, forcer last

Per ⟨guide-fixture-pipeline §§5–6⟩ the S6 pipeline needs in-repo pages before any
page-derived gate can run. Sequencing, three PRs:

1. **PR-A — the move (this plan, §5).** Pure prose. Owns `docs/ui/**` + the
   `mkdocs.yml` nav row + every §2 transform. Gated by docs.yml only. **No
   implementation/, no workflows, no scripts.** After PR-A the pages are in-repo,
   final-shape, marker-complete — the extractor's input is frozen.
2. **PR-B — extractor + wiring.** Owns everything the pipeline's S6 row names
   ⟨guide-fixture-pipeline §6⟩ — including the extractor-language decision the
   pipeline's §7 reassigns here (default: Node `.cjs` per the script-harness
   precedent): the extractor script + golden-corpus self-tests,
   scaffolds (converted from bridge fixtures), generated-namespace wiring, the
   `test:guide-fixtures` npm script (`implementation/package.json` — **hot-zone**,
   sequence accordingly), the TESTING.md Kinds row + `guide_fixtures` classifier
   output, manifest *generation* + freshness gate, and the local drift script's
   repoint from `ai/…/guide/` to `docs/ui/` (the script enters the repo here). PR-B
   lands with the manifest in its **as-is posture** — active rows for what the bridge
   already proved, parked rows untouched — so it cannot go red on content.
   PR-B also carries the one-sentence README-promise amendment on `docs/ui/index.md`
   ("— except the handful marked `guide:no-fixture` on the page, each with its
   reason") — a one-file touch on PR-A's surface, safe because PR-A has merged.
3. **PR-C — the activation forcer arms.** Flips shipped-stages / arms the
   manifest-freshness + drift gates as required checks, and begins bridge-fixture
   retirement (one chapter per bead, split not bundled). Armed last so a red forcer
   can never block the prose or wiring merges; from here, a guide edit fires both the
   docs gates (docs.yml) and the `guide_fixtures` classifier (test.yml side) — the
   deliberate divergence from the spec-impl-pair default, recorded in TESTING.md when
   W9 lands it ⟨guide-fixture-pipeline §6⟩.

**Ownership summary:** PR-A owns mkdocs nav + page shape. PR-B owns pipeline wiring +
the classifier. PR-C owns activation. Nothing overlaps; only PR-B touches hot-zone.

**Residue-gate widening (small, separate):** docs.yml's residue scans root at
`docs/core` (+ spec/, skills/, docs/EP) ⟨docs.yml comments⟩ — `docs/ui/` is outside
their scan roots today. Widen the scan roots to include `docs/ui/` (script-side edit,
not hot-zone; docs.yml path filters already cover `docs/**`). Ride it with PR-B or as
a one-line follow-up bead. **[S6-CONFIRM]** — recommend yes; the guide teaches only
current API so the gates stay green, and future drift protection is free.

## 4. The migration doc and the compat tier

- **Landing: `docs/ui/migration.md`**, last row of the UI tab (§1 nav sketch), title
  "Migrating from Reagent". It plays two house roles at once: the tab's
  "Coming from X" slot (the Machines/XState, Resources/TanStack, SSR/Next.js pattern)
  and W1's companion doc — the two-step story (dataflow first, views per-subtree with
  the migrator), the M/D/R tiers, and the step-1 plain-fn contract
  ⟨10-migration-from-reagent.md⟩. W2's skill teaches the same tiers; the page is the
  reader-facing half.
- **Not** into the `migration/` tree: `migration/from-re-frame-v1/` is scoped to the
  v1→v2 path, is staged into docs separately, and its README is on the fixed hot-zone
  list — landing there buys conflicts and mis-scoping for nothing.
- **The 004A cross-link:** none, deliberately. Docs never link into `spec/`
  ⟨AUTHORING §2⟩, and at S6 the appendix is still a draft (it promotes to
  `spec/004A-Reagent-Compat.md` at S7 ⟨drafts/spec-004A-reagent-compat-appendix.md
  status⟩). The page states the frozen-tier guarantees **inline** — stock Reagent +
  the `reg-view` family stay supported, frozen, CI-covered, no sunset promised — which
  is what a reader needs; the normative cross-link (Spec 004 §Removed forms → 004A)
  is spec-side W5/W13 work. **[S6-CONFIRM]** only if Mike wants a reader-facing
  exception to the no-spec-links rule here; the default is prose.
- **Numbering collision resolved by de-numbering the migration page.** The synthesis
  suite's doc **10** (migration) and guide chapter **10** (worked app) collide only if
  the migration doc kept its suite number. It doesn't: chapters keep `01`–`10`
  (`10-worked-app.md` unchanged), the migration page lands unnumbered as
  `migration.md` — it is tab-level companion material, not chapter 11 of the learning
  track. No renumbering anywhere.
- **At S7 (W13's "one migration doc page"):** this same page absorbs the
  deletion-wave truths (adapters deleted, tier frozen, `->react` as the outward
  bridge). The move PR writes S6 truth only — it does not pre-write S7 state.

## 5. The PR shape (PR-A)

**One worker, one PR, pure prose.** ~12 files added (`docs/ui/index.md`, ten chapters,
`migration.md`), one modified (`mkdocs.yml`). No hot-zone (fixed list — §0.3), no
workflow edits (docs.yml paths already cover the surface), no implementation/ or
scripts/ touches. Well inside single-worker sizing; no bundling.

**Acceptance:**

1. Local: stage the spec/migration copies (`cp -r spec docs/spec && cp -r migration
   docs/migration` — the docs.yml local equivalent), then `mkdocs build --strict`
   green and `python scripts/check_doc_slugs.py` green. Foreground, per the
   stranded-worker rule.
2. CI: the `Docs required` aggregator green (docs.yml detect will light
   `docs_surface` on this diff).
3. **No synthesis-tree references survive** (the tree is tracked but is not
   reader-facing docs): grep over `docs/ui/` for `new-substrate-synthesis`,
   `findings/`, `drafts/`, `⟨`, `ai/` → zero hits.
4. **No shipped-stage markers survive:** grep `\(lands S[1-5]` and `Stage note` over
   `docs/ui/` → zero; wave-2 qualifiers preserved (grep `wave-2` ≥ 4 hits).
5. **Fence bodies byte-identical** to the source pages modulo the added `;; guide:*`
   marker lines (protects the bridge-fixture drift comparison and the extractor's
   future input).
6. British sweep complete: grep `memoiz|behavior[^u]|sanitiz|realiz|artifact` over
   `docs/ui/` prose → zero (code identifiers exempt).
7. No bead ids, ruling dates, or delta numbers in prose.
8. Nav renders: the UI tab appears between Core and API; `ui/index.md` is the tab
   label; prev/next footer runs 01→…→10→migration.

**Follow-up roster (pointers only, not this PR):**

- PR-B extractor + wiring, PR-C activation (§3) — with the residue-gate widening.
- docs/core downstream re-teach (W3): views.md, introduction.md counter, frames.md,
  the reg-view how-tos, **delete** `use-uix-helix-or-slim.md` + its nav row + link
  sweep, glossary, `docs/api/re-frame.ui*.md` reference pages.
- Playground rebuild + smoke (W3) — precondition for any guide live cells (§2.4 rule).
- Skills (W6): `re-frame2` authoring view sections, `re-frame2-pair` hot-swap/ui.tool,
  `re-frame2-xray` causes surface; migration skill (W2) alongside the migrator.
- Spec promotion (W5): synthesis→spec waves; 004A promotion + Ownership row at S7
  (W13), when `docs/ui/migration.md` gets its S7 update.

## 6. Open items

- **[S6-CONFIRM]** tab label "UI" vs "Views (re-frame.ui)" (§1).
- **[S6-CONFIRM]** the two S6 stage markers (`->react`, Story): strip in the move iff
  their beads merged — verify at dispatch (§2.2).
- **[S6-CONFIRM]** index-page "Coming from…" paragraphs: prose (default) vs `??? info`
  persona deltas (§2.3).
- **[S6-CONFIRM]** live-cell conversions need an extractor-eligibility ruling before
  any fence changes language tag (§2.4).
- **[S6-CONFIRM]** widen residue-gate scan roots to `docs/ui/` (§3) — recommended yes.
- **[S6-CONFIRM]** reader-facing 004A link exception (§4) — default is no link, prose
  only.
