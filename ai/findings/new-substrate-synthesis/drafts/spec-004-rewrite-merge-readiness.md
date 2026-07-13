# Spec-004 rewrite — merge-readiness audit

**Audited:** 2026-07-12 12:26 AUSEST · read-only audit against `main` @ `16c62a95ac`
(post-#5703, post-#5704, S2 wave dispatched, zero open PRs at audit time).
**Subject:** `drafts/spec-004-rewrite-draft.md` (903 lines) vs the ratified R-1 merge
condition — *"merges atomically with the first conforming Stage-1 slice; 'conforming'
means every row tagged S1 in §Stage conformance profiles passes its named assertions."*
**Stage-1 PRs verified merged:** #5692 (build wiring) · #5697 (compiler/emitters/rules/
Q2–Q6) · #5699 (error roster) · #5700 (ui.test Tier-1) · #5701 (roots/mount) · #5703
(parity corpus + G-1/G-14 CI gates) · plus the post-#5701 main sweep `6eaa34d22c`
(S1c/S1d ids folded into the frozen S1e roster). Latest full CI runs on `main`: success;
`jvm-ui` and `cljs-ui-g1` are wired as required jobs in the `test.yml` aggregate
(lines 555/600/3555-3556).

---

## VERDICT: **READY-WITH-ITEMS**

The R-1 merge condition is satisfied for 11 of 12 S1-tagged profile rows outright; the
12th (`ui/html`) passes two of its three named assertions — the "manifest site
recording" sub-assertion is neither implemented as a site record nor asserted (a
capability bit exists, unasserted; est. ~15-line fix + fixture). Fold that fix into the
merge PR itself and the condition is literally true at merge time. Everything else
blocking is mechanical merge-PR content (draft-link promotion, provenance-residue
normalisation, the S1 ripple set, inbound-anchor hygiene) plus two explicit
carry-or-retire decisions on current-004 content (§4).

---

## 1. S1 profile-row verification (12 rows)

Every row tagged **S1** in the draft's §Stage conformance profiles, against merged code
under `implementation/ui/` on main and the CI gates from #5703.

| # | Profile row (S1 assertion set) | Asserting fixture / gate on main | Verdict |
|---|---|---|---|
| 1 | **Portability law + template AST** — one AST → two emitters; normalized structural equivalence (parity corpus v0); serialisation boundary; closed node set; AST-shape gate | One AST/two emitters: `compiler/analyze.cljc` feeds both `emit_cljs.cljc` and `emit_jvm.cljc`; browser output asserted in `react_render_cljs_test.cljs`, JVM tree in `tree_jvm_test.clj`. Parity v0: `parity_fixtures.cljc` (29 cases: statics, class forms, text edges, branches, keyed lists + escaping, fragments, booleans, form controls, SVG, MathML, custom elements, spread ×2, trusted-HTML, handlers ×2, defaults, `:as`, children-flow, full page) + `parity_corpus_cljs_test.cljs` `corpus-covers-every-case` / `every-case-normalized-structural-equivalence` / `no-handler-attributes-ever` + `parity_corpus_jvm_test.clj` `every-case-renders-a-versioned-canonical-tree` / `normalization-produces-pure-semantic-space`. Serialisation boundary: `analyze_accept` `ast-serialisation-boundary` + `tree_jvm` `tree-serialisation-boundary`. Closed set/AST gate: `analyze_accept` `ast-shape-gate-closed-op-set` + `root_analysis` `frame-root-op-in-the-closed-set`. | **VERIFIED** |
| 2 | **`ui/defview`** — declaration arities + diagnostics; props ABI + `:key` reservation; registrar `:view` entries; ruled `rf=` comparator emitted + asserted against prop-driven re-render | `defview_grammar_jvm_test.clj` `declaration-arities` / `options-map-is-closed` (`:memo false`, `:on-mount`, `:catch` rejected with didactic strings) / `header-rules` (`:rf.ui.compile/key-prop-declared`, ":key is reserved — it feeds React's key slot") / `q3-slot-encoding-table` / `q2-declared-slots-and-closure` / `cljs-emission-wires-memo-registration-and-debug-gate`. Registrar: `react_render` `views-register-in-the-view-kind` + `tree_jvm` `registrar-entries-on-jvm`. `rf=`: `eq_cljs_test.cljc` (value/identity branches, NaN, ±0, dates/records) + `react_render` `memo-comparator-is-the-ruled-rf=` (the actual `.-compare` handed to `React.memo`: fresh-but-equal CLJS ⇒ equal, host identity fall-through, NaN-stable, −0/+0 equal, undeclared slots invisible) + `as-view-generic-comparator`. *Note:* the re-render assertion is comparator-level + memo-wiring-level, not a mounted repaint counter — adequate for S1 (the row itself defers subscription/local interplay to S2/S3). | **VERIFIED** |
| 3 | **Template grammar — forms, control forms, rejection roster** — table forms lower; compile-error roster with didactic messages | `analyze_accept_cljs_test.cljc` (`scalars-lower`, `element-sugar-both-orders`, `sugar-class-merges-before-explicit`, `flag-map-classes-lexicographic`, `fragments-lower`, `control-forms-normalize`, `q6-for-subgrammar`, `q5-head-classification`) + `analyze_reject_cljs_test.cljc` (heads, children-position, keyed-lists, finite-sites, loop-captured-handlers, handler-grammar, bare-fn-law, void-elements, statically-pure-bodies, fragment-props) + `error_roster_cljs_test.cljc` (`analyzer-tier-roster`, `roster-is-frozen-and-complete`, didactic vocabulary sweeps asserting escape-naming message text, `compile-errors-carry-source-anchor`). | **VERIFIED** |
| 4 | **Prop conversion (one rule table; `ui/spread`)** — conversion-table fixtures consumed by both emitters; spread dynamic-map cases | One table: `re-frame.ui.rules` (.cljc) is `:require`d by `emit_cljs.cljc`, `emit_jvm.cljc`, and `tree.cljc` — literally one table, both emitters. Fixtures: `rules_cljs_test.cljc` (attribute names, SVG aliases, xlink/xml, event names, `:style` px/unitless/React names, `:class` rows, JS-number rows, key string-coercion, boolean sets, voids, escaping, namespace contexts, custom-element rows) + `serialiser_rules_cljs_test.cljc` (JVM serialiser half) + `parity_corpus_jvm_test.clj` (`boolean-value-classes`, `form-control-special-forms`, `svg-and-mathml-namespace-rows`). Spread: `react_render` `spread-conversion`, `tree_jvm` `custom-element-and-spread-on-jvm`, parity cases `:spread-override`/`:spread-add`; direct call fails typed (`:rf.error/ui-spread-outside-template`, 009 row landed #5697). | **VERIFIED** |
| 5 | **Handlers — event vectors as structural data** — vectors/options-maps retained as data in tree + manifest; placeholder keywords retained as keywords | `tree_jvm` `full-tree-golden-with-events-as-data` + `fn-and-dynamic-handlers-classify` + manifest flags at `tree_jvm:270` (`:serializable?` per event site in `:rf.ui/manifest`); `parity_corpus_jvm` `events-are-data-in-the-tree-and-absent-from-n` (asserts `[:form/typed :email :rf.ui/value]` retained verbatim — placeholder kept as keyword); `analyze_accept` `handler-classification-table` / `event-sites-index-into-the-manifest` (`:serializable?` true/false per literal/opaque). | **VERIFIED** |
| 6 | **`ui/raw` (S1 part)** — compile form + opaque marker in the tree | `analyze_accept` `interop-forms-lower` + `raw-and-raw-fn-props-mark`; `tree_jvm` `opaque-markers-in-boundary-props` (`{:rf.ui/opaque <form>}` per the #5697 Conventions reservation); rendered raw child on JVM raises `:rf.error/jvm-host-op` (`tree_jvm` `host-ops-raise-typed-errors-lazily`; 009 row landed #5697). | **VERIFIED** |
| 7 | **`ui/html`** — dual-emitter agreement; the single escaping bypass; **manifest site recording** | Dual-emitter: parity case `:trusted` through both emitters + N. Escaping bypass: `react_render` `trusted-html-single-bypass`, `tree_jvm` `trusted-html-node`, `parity_jvm` `trusted-html-is-an-opaque-leaf`, `rules` `escaping-row`; non-string arg → `:rf.error/ui-tree-malformed`. **Manifest site recording: GAP** — the analyzer's site index is `{:events [] :subs [] :leases []}` (`compiler/env.cljc:48`); html is recorded only as an unasserted **capability bit** (`compiler.cljc:93`), no per-site record with source/template path, no fixture. | **PARTIAL — 2/3 sub-assertions; ~15-line fix (add `:htmls` site record + one fixture) should ride the merge PR** |
| 8 | **`ui/spread` (interop row)** | Same evidence as row 4. | **VERIFIED** |
| 9 | **Roots and mounting** — mount grammar, root identity, Root Descriptor v1, client host fns, duplicate Layers 1+3, static frame-plan extraction (the root-identity-and-mount §10 S1 row) | `root_analysis_cljs_test.cljc` (authored-id wins/shape rejections, derivation default + pinned impossible-derivation error, slug cases, identifier-prefix default, `descriptor-literal-props`/`-dynamic-props`/`-frame-plans-are-the-static-subset` = Root Descriptor v1, `frame-plans-extract-through-top-region-wrappers`, `frame-root-outside-the-top-region-rejected`, `runtime-root-forms-rejected`, config-fingerprint semantics, root-opts validation) + `root_mount_jvm_test.clj` (Layer-1: cross-file duplicate root-id fails the build, plan-fingerprint conflict/idempotence/replace, mount macros reject the JVM host, source anchors) + `root_registry_cljs_test.cljs` / `root_mount_dom_cljs_test.cljs` (Layer-3: duplicate rejected before any render, container-missing/-in-use, `create-root-then-render`, `unmount!` frees identity, `mount` smoke + idempotence, `hydrate-root` fails loud at S1 per contract, frame-root transparent + plans ride preflight). All six #5701 009 rows landed. | **VERIFIED** |
| 10 | **`ui.test` S1 structural core** — render/find/find-all/text/attrs/frame/dispatch!, plus `query`'s typed Tier-3 contrast on JVM/Tier-1 trees; selector-grammar fixtures; JVM-subset enforcement | `test.cljc` ships `render`/`find`/`find-all`/`query`/`text`/`attrs`/`frame`/`dispatch!`; `test_selectors_cljs_test.cljc` (tag/view-id/attr-map/pred-fn selectors, document order, typed Tier-3 CSS split, OPEN-2 vector form asserted NOT shipped, malformed nodes fail loud) + `test_projections_cljs_test.cljc` (attrs projection on elements/view-boundaries; field-vs-attribute law `keyword-lookup-reads-fields-never-attributes`; text projection) + `test_render_jvm_test.clj` (root forms, closed opts, sub-overrides, frame opts) + `test_guide09_fixture_jvm_test.clj` (the guide-09 Tier-1 fixture, incl. real `dispatch!` + drain). #5700's three 009 rows landed. `with-root`/`flush!`/`simulate!` are deliberately absent from this S1 merge: they are the S2 mounted/flush slice, not S1 gaps. | **VERIFIED** |
| 11 | **JVM structural subset (non-reactive rows)** — Tier-1 rendering against the tree contract + `:rf.error/jvm-host-op` | `tree_jvm_test.clj` (golden tree with events-as-data, canonical-form laws, fragment/nil-rooted views, children forwarding, defaults/`:as`, duplicate-key diagnosis with string coercion, host ops raise `:rf.error/jvm-host-op` lazily, namespace contexts, registrar entries) + `parity_corpus_jvm` (versioned canonical trees, `frame-root-is-transparent-in-the-jvm-tree`) + the explicit S1/S2 seam test `sub-grammar-compiles-reads-land-s2` (sub grammar compiles, executed reads raise the staging id `:rf.error/ui-sub-unavailable` — exactly the profile's Q32/Q22 answer). | **VERIFIED** |
| 12 | **Removed forms — the absences** — compile errors + export-surface checks from the first slice | `defview_grammar_jvm_test.clj` `export-surface-is-exactly-the-blessed-set` — `ns-publics` of `re-frame.ui` is exactly `#{defview custom-element sub lease raw html raw-fn spread mount create-root render! hydrate-root unmount! frame-root}` ("no reg-view family, no Form-1/2/3 helpers, no h macro, no view lookup, no ratom/cursor/reaction"); `declaration-arities` (no positional args), `options-map-is-closed` (`:memo`/`:on-mount`/`:catch` rejected), `q5-head-classification` + `analyze_reject` `heads` (keyword head never a registry lookup, dynamic heads rejected — rf2-n82bbu carried). | **VERIFIED** |

**Count: 11 VERIFIED / 1 PARTIAL (row 7, `ui/html` — the "manifest site recording"
sub-assertion) / 0 hard gaps.** S1-scoped fragments of later rows also verified:
sub-in-loop compile error (`analyze_reject` `finite-sites` → `:rf.ui.compile/sub-in-loop`),
compile-time site anchors from S1 (`compile-errors-carry-source-anchor`,
`mount-surface-compile-errors-carry-source-anchor`, site records carry `:path`), and the
`sub`-grammar-at-S1 seam (row 11 note).

## 2. Blocker checks

**(a) OPEN roster — nothing blocks.** Zero `[OPEN — needs ruling]` markers remain in the
draft body (grep-verified). Roster item 1 (`rf=`) RULED 2026-07-12 and implemented
exactly as ruled (row 2 evidence, incl. the −0/+0 divergence and NaN stability). Item 2
(`ui/custom-element` grammar) RULED; grammar registered + asserted at S1
(`custom-element-grammar`, classification tests both hosts); behaviour completes S4 as
tagged. Item 3 (selector grammar) drafted + reconciled; its residual OPEN-2/OPEN-3 are
declared demand-bar audit items, and OPEN-2's non-shipping is itself **asserted** by
`vector-selector-not-shipped-open-2`. Item 4 settled (predicate confirmed by S-5;
port shapes final). None blocks the merge.

**(b) [TRANSITION] markers — survive the merge by design, confirmed.** The draft says so
in three places: the header ("conditional until the adapter deletion wave"), the
§Removed-forms/[TRANSITION] block ("the markers, not git history, are the live contract
during the transition — the git tag is provenance only"), and the profile row
"§Removed forms — [TRANSITION] freeze + the 004A appendix → **S7**". One caveat the
merge PR must resolve: the block claims the adapters are "governed by the **carried
pre-rewrite contract text under these [TRANSITION] markers**", but the draft carries
only summaries, not the full pre-rewrite text — see §4 item D for what is actually
covered elsewhere and the small residue that isn't.

**(c) S2+ sections under the declared-not-yet-asserted device — clean.** The
merge-condition header states it explicitly: *"Later-tagged rows are
declared-not-yet-asserted; each subsequent stage's conformance slice asserts its rows
atomically with that stage's spec edits (the 12 §2b spec-landing rule — no intermediate
checked-in spec claims unimplemented behaviour)"* — and §Stage conformance profiles
"merges as part of the spec text and is the device that keeps an intermediate checked-in
spec honest." This matches 12 §2b's Q61 landing rule verbatim in substance. The device
is sound **provided** the profile table's draft-file citations are rewritten at merge
(§3) — a declared-not-yet-asserted row may claim future behaviour, but it may not link
to a gitignored file.

## 3. The atomic S1 ripple set

Per the draft's own **Ripple-row timing** paragraph: identity/naming/reservation rows →
S1; behaviour rows → their asserting stage (002→S2, 006→S2, 009→per-feature batches,
011→S5); [TRANSITION]/"moves to 004A" rows → S7.

### Already landed (do NOT re-edit — cross-checked against merged PR diffs)

| Ripple | Landed by | Status |
|---|---|---|
| 009 catalogue rows: `:rf.error/ui-tree-malformed`, `ui-duplicate-key`, `jvm-host-op`, staging `ui-sub-unavailable`/`ui-lease-unavailable`/`ui-dispatch-unwired`, `ui-spread-outside-template` | #5697 | ✔ applied |
| 009 rows: `ui-tree-malformed` extension + `ui-test-tier-mismatch`/`ui-test-bad-selector`/`ui-test-bad-opts` | #5700 | ✔ applied |
| 009 rows: `ui-frame-root-outside-root-form`, `duplicate-root-id`, `root-container-missing`, `root-container-in-use`, `root-manifest-invalid` (S1 arm), `frame-payload-conflict` (S1 arm) | #5701 (+ `6eaa34d` sweep) | ✔ applied |
| Conventions reserved namespaces: `:rf.ui/*` (incl. the closed placeholder vocabulary `:rf.ui/value`/`:rf.ui/checked`/`:rf.ui/key` + opaque marker), `:rf.ui.compile/*`, `:rf.ui.tree/*` | #5697 | ✔ applied — the draft's "reserve `:rf.ui/*` + placeholder vocabulary" ripple is DONE |
| Conventions reserved namespace: `:rf.root/*` (`:rf.root/schema-version`, descriptor/manifest family) | #5701 | ✔ applied |
| Interim 004 amendment (portability law in checked-in 004) | #5685 | ✔ applied — fully subsumed by the rewrite (supersession verified §4) |

**Consequence: the merge PR carries NO spec/009-Instrumentation.md edit at all.** The
S1-feature catalogue rows landed in small batches with their features, exactly per the
standing rule; the remaining 004-named ids (`dispatch-disconnected`, `view-not-found`,
`frame-payload-invalid`, `flush-in-open-epoch`, the warning family) are S2/S3 features
whose rows land with their stages.

### To land IN the atomic merge PR

| Target | Edit | Hot-zone? |
|---|---|---|
| `spec/004-Views.md` | Wholesale replacement with the rewrite body (draft lines 39–806): strip `⟨…⟩` tags, normalise residues (§5), rewrite draft citations (below), keep [TRANSITION]/[WAVE-2] markers + §Stage conformance profiles as merged spec text | **No** (004 is not on the fixed hot-zone list) |
| **Promote** `drafts/jvm-tree-and-conversion-contract.md` → a committed spec page (target name for Mike/worker to fix, e.g. `spec/004B-UI-Tree-and-Conversion.md`) | The rewrite cites it as the owning contract for S1-asserted surfaces (tree ABI v1, conversion table, normalization N, `emit-ui-tree` boundary); the drafts live under gitignored `ai/` — links from a tracked spec would be dead files and **fail `mkdocs build --strict`** (missing-file links warn → strict fails; no `validation:` override in mkdocs.yml) | No (new file) |
| **Promote** `drafts/root-identity-and-mount.md` → committed spec page | Same reasoning; its §10 S1 row is the named assertion source for profile row 9 | No (new file) |
| **Promote** `drafts/ui-test-selector-grammar.md` → committed home (own page or an 008 section) | Cited by the profile's `ui.test` row and §JVM structural subset | No (new file) |
| `spec/008-Testing.md` | Add the S1 `ui.test` contract rows (render/find/find-all/query/text/attrs + tier table + `.cljc` constraint), the selector-grammar home/pointer, **the G-1/G-14 gate rows, and the parity-corpus row** — 008 currently contains **zero** ui/parity/G-gate content, yet the merged 004 says "per [008] G-14", "generatively tested (per [008])", "`ui.test/flush!` is the only test flush (per [008])" | **No** (008 is not on the fixed hot-zone list) |
| `spec/Conventions.md` | Remaining S1 rows only: packaging/artifact registration for `day8/re-frame2-ui` (lockstep train per R-6; `re-frame.ui.data` reserved as future separate artefact); the `{:re-frame.ui/bare-handlers …}` lint-key reservation; `ui/defview` added to the per-kind registration-macro list (**additive** — `reg-view` stays until S7); the defview id-derivation stated (additive beside the existing §reg-view rule). Do NOT touch the four already-landed reservation rows | **YES — hot-zone; sequence** |
| `spec/API.md` | **Additive** `re-frame.ui` surface rows per the blessed 12 §2 table (with stage annotations). The reg-view/reg-view*/view re-status to `v1 (frozen — compat tier)` is **S7, not now** | **YES — hot-zone; sequence** |
| `spec/Ownership.md` | Row 39 rewrite (view contract → `ui/defview`/template grammar/portability law; artifact cell → `day8/re-frame2-ui`); row 40 re-word to the **F8 narrow law** (the current row restates the superseded strict "never read by any handler" test — leaving it contradicts merged 004) + fix its 004 anchor; NEW rows for the 004-owned S1-landing surfaces (presence/`ui/html`/`ui/error-boundary` as declared 004-owned; `ui.test` contract → 008). Defer: observation-port row (S2), root-manifest row (S5), adapter-row deletions (S7) | No |
| Inbound-link hygiene | 49 anchor references from 17 tracked files point into current-004 headings that all vanish (top offenders: `#plain-reagent-fns-no-frame-injection` ×5, `#reg-view-is-the-multi-frame-contract` ×3, `#regime-c--library-bridged-animations…` ×3, `#calling-a-registered-view` ×3, `#affordance-for-plain-fns-rfcapture-frame` ×3, `#where-ephemeral-view-state-lives…` ×3, `#view-antipatterns` ×3, Form-1/2/3 anchors ×8). Files: 000, 001, 002, 006, 009, AI-Audit, Conventions, Cross-Spec-Interactions, Ownership, Pattern-NineStates, Pattern-RemoteData, Pattern-StatefulComponents, Principles, Spec-Schemas, Tool-Pair, migration/from-re-frame-v1/README.md, docs/api/re-frame.core.md. Broken anchors pass `--strict` (anchors default to info) but are reader-facing debt; fix in the same PR. **Coordinate the 002 touches** against the in-flight h1vqa4 slice-3 worker at dispatch time | Mixed (002/006/009/Conventions/Spec-Schemas/Tool-Pair/migration-README are hot-zone — link-only edits, same PR, verify no in-flight owner at dispatch; zero open PRs at audit time) |
| De-linkify two stage-later citations inside the merged 004 body | `reagent-compat-boundary.md` links (§Interop `->react` row, §Removed forms, [TRANSITION] block, profile rows) → plain-prose citation ("the compat-boundary contract; promotes as `spec/004A-Reagent-Compat.md` at the deletion wave"); `spec-006-observation-port-amendment.md` link (§Reactive reads) → cite 006 + "the port amendment (merges with the S2 slice)". Their promoted homes land S7/S2 respectively — linking now = dead links | No (inside 004) |

### NOT in this merge (later stages, per the draft's own timing)

- **002-Frames.md** — all seven inventory rows → S2 (frame chain; coordinate with
  rf2-h1vqa4 which is actively refactoring this surface — slice 2 merged as #5704).
- **006-ReactiveSubstrate.md** — all rows incl. the NEW observation-port section → S2
  (the S2a obs worker just dispatched owns this lane).
- **009** — evidence-schema/`:rf.view/*` rows → S3; port rows → S2; compat-tier
  deletions → S7. (S1 rows: already landed, above.)
- **011-SSR.md** — all rows → S5.
- **Conventions** facade re-status (1187) + `*`-pair table (1341–51) → S7;
  **API.md**/**Cross-Spec-Interactions §21** re-status → S7; `spec/004A-Reagent-Compat.md` → S7.
- **Spec-Schemas** (`:rf/epoch-record` `:renders`, manifest/instance-record shapes) →
  follow their owners (S3+).
- **Construction-Prompts CP-4**, **migration README**, docs/guide/skills → S6.

## 4. Wholesale-replacement subsumption check (current 004 → draft)

The draft subsumes the interim-amended (#5685) portability-law text completely (Status
line, Abstract §3, §Serialisation boundary → §The portability law; verified clause by
clause). Content in checked-in 004 **not** (fully) represented in the draft — each needs
an explicit carry-or-retire on the merge PR:

- **A. The 8-host carrier table + "the pattern does NOT commit to" list**
  (§The render-tree shape). Deliberately superseded by the one-AST law (the draft PINS
  what the old text left open, e.g. DOM prop spelling), and the draft keeps the
  pattern-level 8-host framing via 000. **Recommend: retire, note in PR description.**
- **B. §Render-tree primitives detail** — `:render-key` tuple, token lifecycle
  (mint-at-mount / discard / process-scoped / replay-aware), anonymous fallback
  `[:rf.view/anonymous nil]`, the SA-4 note, production-elision gate. The draft carries
  only the [TRANSITION] wire-shape summary. **Covered elsewhere on main:** 009 §op-type
  (lines 269–292, 400, 1009) and Spec-Schemas §`:rf/epoch-record` `:renders` (line 4002
  carries the full token-lifecycle + anonymous-fallback semantics). **Recommend: retire
  from 004; point the [TRANSITION] marker at 009/Spec-Schemas as the transition homes.**
- **C. §Animations (Regimes A/B/C).** Dropped entirely. Regime B (RAF-as-fx) and C
  (library-bridged via effect+ref) survive in the draft's §Effects; the three-regime
  taxonomy and Regime-A doctrine do not. Pattern-StatefulComponents links to
  `004#regime-c…` and `004#animations`. **Recommend: re-home a condensed regimes
  section into Pattern-StatefulComponents (or docs/guide) and fix the links — decide
  explicitly, don't let it silently vanish.**
- **D. The reg-view-family contract text** (macro shape + compile-error table,
  `reg-view*`, two-lanes doctrine + authoring-lane rule, `(rf/view id)` lookup
  semantics, Form-1/2/3 sections, plain-fn/`capture-frame` affordance, hot-reload
  re-registration paragraph). These freeze into `spec/004A-Reagent-Compat.md` **at S7**,
  but the adapters ship until then, and the [TRANSITION] block's "carried pre-rewrite
  contract text under these markers" is not physically carried by the draft.
  **Transition coverage that already exists on main:** 002 §What `reg-view` injects +
  §View ergonomics + Form pointers (unchanged until S2/S7); Conventions §auto-id rule +
  §`*`-suffix pair (unchanged until S7); API.md rows 102/103/215 (reg-view /
  reg-view* / view, with shape + semantics notes — unchanged until S7); 009
  `:rf.registry/handler-replaced`. **Not covered anywhere after replacement:** the
  authoring-lane rule ("state-touching views MUST be reg-view" — arguably superseded in
  the new model but still the governing rule for the shipping adapters), the reg-view
  macro's bad-call error table detail, and `(rf/view id)` re-resolution-per-call
  hot-reload semantics beyond API.md's one-liner. **Recommend:** a short "Transition
  annex" note in merged 004's [TRANSITION] block naming the committed homes (002 /
  Conventions / API / 009 / Spec-Schemas) as the live carriers, plus carrying the
  authoring-lane rule sentence + view-lookup re-resolution sentence verbatim into that
  block. Cheap, and it makes "the markers are the live contract" true.
- **E. Loading-state rationale prose** (8 "why explicit wins" points, trade-offs,
  anti-patterns, composition table). Normative core (Suspense non-goal; app-db home;
  Pattern-RemoteData) fully carried, condensed. **Retire the rationale prose** (guide
  material) — fine, note it.
- **F. Ephemeral-placement strict test** ("will any handler, sub, schema, or tool ever
  read it?"). Deliberately superseded by the ruled F8 narrow law — documented inside the
  draft itself. Carried-with-supersession; requires the Ownership row-40 co-edit (§3).

Nothing else in checked-in 004 fails subsumption: view antipatterns → I-1 + §Effects +
Pattern-AsyncEffect cross-refs; source-coord attr contract → §View identity ("today's
attribute vocabulary") + 006 ownership; registry tooling surface → registrar `:view`
kind persists; bare-keyword rejection + h-macro absence + placement-rule ownership →
carried explicitly in §Resolved decisions.

## 5. Provenance-strip mechanics

A mechanical strip of `⟨…⟩` tags leaves clean prose — spot-checked in 5 sections:
§Abstract (trailing `⟨I-1⟩`-style tags terminate sentences; removal leaves complete
sentences), §The portability law (whole-line tag under the heading → blank line, clean),
§Template grammar (mid-table and end-of-para tags, clean), §Roots and mounting
(`⟨09 codex2 F3⟩` end-of-para, clean), §The JVM structural subset (`⟨09 codex2 F2
ruling⟩` whole-line, clean). Also confirmed: the draft's front-matter (lines 1–37,
containing the draft-file links and merge-condition apparatus) sits **above** the real
`# Spec 004 — Views` heading and does not merge.

**BUT a ⟨⟩-only strip is not sufficient.** Non-tag provenance residues in the body that
must be normalised by hand (keep the ruling content; drop the review-artefact
vocabulary or re-cite committed homes):

1. `**[RULED — Mike, 2026-07-12]**` ×2 (§defview `rf=`; §Template grammar
   custom-elements) — fold into normal normative prose (the content is final).
2. `(delta #2, ruled 2026-07-12)` / `(delta #3, ruled 2026-07-12)` in the §Interop
   table (`->react`, `spread` rows).
3. "confirmed sufficient by **S-5**" (§Handlers synchrony door) and "**S-3 §5** is the
   sole shape source per the binding **codex2 F1** disposition" (§Resolved decisions
   R-2) — spike/review ids; re-word ("confirmed by the input-door spike; the residual
   gate is G-8…").
4. "ruled per the **codex2 F8** disposition" (§Local state prose) and the italic
   editorial "*(Default-id derivation is the carried Conventions rule; **the synthesis**
   names only the `:id` override.)*" (§defview Registration).
5. §Stage conformance profiles: the `⟨09 codex2 F4 …⟩` tag strips fine, but the table's
   draft-file links must be rewritten to the promoted homes (§3), and "(the loop
   *rejection* is a compile error from S1)"-style content is fine as-is.
6. §Resolved decisions + §OPEN-roster tail: the OPEN roster and ripple inventory are
   below the `# Cross-spec ripple inventory (NOT part of the merged spec text)`
   divider — confirm the merge takes lines 39–806 only (through §Resolved decisions).

## 6. Verdict and recommended bead

**READY-WITH-ITEMS.** The ratified R-1 condition is met in substance tonight: Stage 1 is
complete on main, gates green, 11/12 S1 profile rows fully pass their named assertions,
and the 12th lacks only a ~15-line sub-assertion that belongs inside the merge PR
itself. No ruling is outstanding (OPEN roster clear); the declared-not-yet-asserted
device and the [TRANSITION] markers are stated cleanly in the text that merges. The
items are: the `ui/html` manifest-site fix (or a Mike-ruled profile-row correction), the
three contract-draft promotions + two de-linkifications (hard requirement — gitignored
link targets fail the strict docs gate), the S1 ripple set (008/Conventions/API/
Ownership + link hygiene), the residue normalisation, and two explicit carry-or-retire
decisions (Animations regimes; transition-annex pointer for the frozen-family text).

### Recommended bead (one worker)

> **Title:** spec(004): merge the full Spec-004 rewrite atomically with its S1 ripple
> set — R-1 condition met by the Stage-1 slice (#5692/#5697/#5699/#5700/#5701/#5703)
>
> **Authoritative sources:** `ai/findings/new-substrate-synthesis/drafts/
> spec-004-rewrite-draft.md` (body = lines 39–806 only; front matter and everything
> below the "Cross-spec ripple inventory" divider do NOT merge) + this audit
> (`drafts/spec-004-rewrite-merge-readiness.md`) for the ripple/residue checklists.
> The bead is authoritative over any summary.
>
> **One PR, in this order:**
> 1. `implementation/ui`: record `ui/html` sites in the compiler manifest
>    (`env.cljc` site kinds + `analyze.cljc` `:html` arm) + one fixture asserting the
>    site record — closes the last S1 sub-assertion (profile row `ui/html` →
>    "manifest site recording").
> 2. Promote the three owning contracts into `spec/` (jvm-tree-and-conversion,
>    root-identity-and-mount, ui-test-selector-grammar; propose names in the PR).
> 3. Replace `spec/004-Views.md` with the rewrite body: strip `⟨…⟩` tags; normalise the
>    §5 residue list; rewrite draft citations to the promoted pages; de-linkify the
>    compat-boundary + 006-amendment citations (homes land S7/S2); keep
>    [TRANSITION]/[WAVE-2] + §Stage conformance profiles; add the transition-annex
>    pointer sentence (002/Conventions/API/009/Spec-Schemas carry the frozen-family
>    text until S7) + carry the authoring-lane and view-lookup sentences into the
>    [TRANSITION] block.
> 4. `spec/008-Testing.md`: ui.test S1 contract + selector-grammar pointer + G-1/G-14
>    gate rows + parity-corpus row.
> 5. **Hot-zone riders (verify no in-flight PR owns them at dispatch):**
>    `spec/Conventions.md` — packaging row `day8/re-frame2-ui` (+`re-frame.ui.data`
>    reserved), `{:re-frame.ui/bare-handlers}` lint key, additive defview macro-list +
>    id-derivation rows; do NOT touch the landed `:rf.ui/*`/`:rf.ui.compile/*`/
>    `:rf.ui.tree/*`/`:rf.root/*` reservations. `spec/API.md` — additive `re-frame.ui`
>    rows per the blessed 12 §2 table; reg-view rows untouched (S7).
> 6. `spec/Ownership.md`: row 39 rewrite; row 40 → narrow law + anchor fix; new
>    004/008-owned S1 rows; defer port/manifest/adapter rows.
> 7. Link hygiene: re-point the 49 inbound 004-anchor refs (17 files; list in the
>    audit §3); re-home the Animations regimes (condensed) into
>    Pattern-StatefulComponents and fix its two links — or carry §Animations verbatim
>    if Mike prefers (flag in PR).
> 8. NO edits to 009 (S1 rows already landed in #5697/#5700/#5701), 002, 006, 011,
>    Spec-Schemas, Cross-Spec-Interactions, migration README (later stages).
> 9. Gates: `mkdocs build --strict` + fast spine + `clojure -M:test` in
>    `implementation/ui` (for item 1), in foreground.
>
> **Sequencing:** spec/004 + new pages + 008 + Ownership are not hot-zone; Conventions +
> API.md riders are — this PR must be the sole owner of both while open (zero open PRs
> at audit time; S2a-obs owns 006/009 and h1vqa4-s3 owns 002/core — no overlap, but
> re-verify at dispatch). Sized for one worker, ~0.5–1 day.

---
*Audit trail: profile rows read from draft lines 708–776; assertions verified against
`implementation/ui/{src,test}` on main + `.github/workflows/test.yml` (jvm-ui,
cljs-ui-g1) + merged PR file lists/diffs (#5685–#5703); ripple cross-check via
`gh pr diff` on #5697/#5700/#5701; anchor sweep via `git grep` on tracked files only.*
