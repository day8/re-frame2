# 12 — Implementation plan: handoff-ready program state

**Status:** final · 2026-07-11 · codex2 fold-in applied 2026-07-12 (§2b added; stage
fixes per the binding disposition in 09; volatile operator state replaced by semantic
dependencies). Purpose: when the repo is handed over for implementation, the program
*files and dispatches* rather than being re-derived. Four parts: where the code lands,
the consolidated public surface + demand-bar audit (before Stage 1), the authoritative
surface matrix (§2b), and the stage→bead plan with hot-zone sequencing.

## 1. Where the artifact lands

- **`implementation/ui/`** — new artefact beside `core/` and (for now) `adapters/`:
  `src/re_frame/ui/*.cljc` (compiler — runs on JVM during CLJS compilation), `*.cljs`
  (client kernel), `test/` per repo convention, own `deps.edn` with a `:test` alias.
- **Hot-zone touches at landing** (sequence, never parallel): top-level
  `implementation/deps.edn` + `shadow-cljs.edn` (new build ids + test builds),
  `implementation/package.json` (scripts). One dedicated PR, first in Stage 1.
- `re-frame.ui.test` ships inside the artefact (dev/test scope); `re-frame.ui.tool` also
  in-artefact, tool tier; `re-frame.ui.data` (interpreter) is a **separate artefact**,
  wave-2. JVM emitter is `.cljc` source in this artefact, *consumed by* `ssr` (packaging
  per 05 §1).
- Spike branches (`spike/ui-s1-codegen`, `spike/ui-s3-ownership`) are evidence, never
  merged; Stage 1 starts clean from the drafts + reports.

## 2. Public surface + demand-bar audit (pre-Stage-1 obligation)

`re-frame.ui` (v1 unless marked). Consumer = guide fixture/example/tool that exists in
this suite today. **Wave-2 = does not ship in v1.**

| Name | Kind | Consumer | Verdict |
|---|---|---|---|
| `defview` | macro | every guide chapter | v1 |
| `sub` `local` `effect` `lease` `frame` | body forms | guide 03/05 | v1 |
| `dispatch-fn` | fn | guide 05 (media-bridge) | v1 |
| `event` `handler` `render-fn` `raw-fn` | macros | guide 04 table + guide 03 refs | v1 |
| `frame-root` `frame-provider` | components | guide 01/05, every mount | v1 |
| `presence` + `presence-phase` | macro + body form | guide 02 (toasts) | v1 |
| `error-boundary` | component | guide 02 (route shells) | v1 |
| `html` | fn | guide 02 (markdown/CMS) | v1 |
| `raw` | macro | guide 02 interop | v1 |
| `custom-element` | macro (declaration; `{:properties #{…}}` closed grammar) | guide 02 interop — property-accepting web components | v1 (lands with S4) *(delta #1, ruled + blessed 2026-07-12)* |
| `client-only` | macro | guide 08 (Mapbox) | v1 |
| `mount` | macro | guide 01 | v1 |
| `create-root` `render!` `hydrate-root` `unmount!` | fns (`render!`/`hydrate-root` realised as macros — root forms are literal; kind-label fix per `drafts/root-identity-and-mount.md`, 2026-07-12) | guide 08 hydration + testbeds | v1 (host tier) |
| `render-static` | fn | guide 08 (static footer) | v1 (SSR tier) |
| `adapter` | var | `rf/init!` everywhere | v1 |
| `->react` | fn | guide 02 §interop (view exported to a React codebase); doc 10 per-subtree migration; W1 migrator | v1 (lands S6, migration wave) *(delta #2, ruled 2026-07-12 under delegated authority — original "none in guide" premise was wrong)* |
| `spread` | fn | 02 §2 conversion architecture (the single dynamic-map conversion); guide 02 interop + guide 07 visible-cost escapes | v1 *(delta #3, ruled 2026-07-12 under delegated authority)* |
| `element` `view` `portal` | — | none load-bearing in guide (`ui/raw` covers runtime-chosen heads) | **wave-2** (view needs prod-registry design first) |
| `re-frame.ui.data/render` | fn | none | **wave-2, separate artefact** |
| `re-frame.ui.react/*` (use-ref, use-effect, use-layout-effect, use-effect-event, use-context, use-id, lazy) | fns | interop/migration tier — foreign-React embedding (citation corrected 2026-07-12 per codex2 F5: guide 03's chart uses `local`/`effect`/`ui/raw-fn`, NOT these hooks); call shapes = S1 contract item; the S1 demand-bar audit confirms a consumer or returns this row as a Mike delta | v1 (interop tier) |
| `re-frame.ui.test/*` (render, find, find-all, query, text, attrs, frame, dispatch!, with-root, flush!, flush-presence!) | fns + `with-root` macro | guide 09 + 07 fixtures | v1 (test tier) |
| `re-frame.ui.tool/*` (view-manifest, mounted-views, explain-render, view-dependencies, view-event-sites) | fns | Xray/Pair (04 §5) | v1 (tool tier, dev-only) |

Editor/kondo diagnostics layer: wave-2 (08 §3). Anything not in this table does
not exist.

## 2b — Authoritative surface matrix (name → stage / owner / proof / spec home)

*Additive, informational (codex2 Finding 5 fold-in, 2026-07-12). This matrix alters no
§2 verdict: §2 stays authoritative on WHAT exists; 08 §2 stays authoritative on WHEN v1
items land. Rows marked ⟨aligned to 08 §2⟩ are stage assignments derived from 08's stage
contents rather than an explicit per-name ruling. "→" in the Stage column splits where a
surface first ships from where later-stage behavior completes it. This table is the
per-name answer to codex2 Q53; the residual-gates and spec-landing notes below it own
Q60/Q61.*

Everything below ships in artifact `day8/re-frame2-ui`, namespace `re-frame.ui`, unless
a fuller namespace is shown (the wave-2 `re-frame.ui.data` interpreter is a separate
artefact — §1). Wave-2 rows carry no stage: they do not ship in v1.

| Name(s) | Stage (lands → completes) | Owner (kind/tier) | Proof (fixture / gate) | Spec home |
|---|---|---|---|---|
| `defview` | S1 | macro; compiler `.cljc` + client kernel | parity corpus v0; G-1; G-14; compile-error roster fixtures | Spec 004 rewrite — grammar/template + S1 conformance profile |
| `sub` · `frame` | S2 ⟨aligned to 08 §2⟩ (grammar parses at S1; reactive/frame semantics S2) | body forms | S2 ownership fixtures; `rf=` stabilization fixtures; G-3/G-5 | rewritten 03 §3 → Spec-006 amendment; Spec 004 rewrite — reactive reads |
| `local` · `effect` | S3 ⟨aligned to 08 §2⟩ | body forms | narrow-placement-law fixtures (F8 ruling); StrictMode replay/cleanup; JVM initial-value-only + typed setter error (07 §2) | Spec 004 rewrite — local placement law + effects |
| `lease` | **S2** (owner-token semantics land with the observation work) → view-level resource-lease semantics **confirm at S3** (marked) | body form | S2 transactional multi-acquire rollback fixtures; S3 resource-lease confirmation fixture | rewritten 03 §3 / Spec-006 amendment; Spec 004 rewrite — leases |
| `dispatch-fn` | S3 ⟨aligned to 08 §2⟩ | fn | event-boundary decision-table fixtures | Spec 004 rewrite — handler boundary law |
| `event` `handler` `render-fn` `raw-fn` | S3 ⟨aligned to 08 §2⟩ (grammar recognized at S1; committed behavior + sync door S3) | macros | sync-input-door fixture (S-5 predicate); G-8 real-browser matrix; foreign-boundary fixtures | Spec 004 rewrite — event projections + handler boundary law |
| `frame-root` `frame-provider` | S2 ⟨aligned to 08 §2⟩ | components | preflight-ENSURE fixtures; G-4/G-6 | Spec-006 amendment (ENSURE preflight, 03 §8); Spec 004 rewrite — roots/frames |
| `presence` + `presence-phase` | S4 | macro + body form | fake-clock enter/exit fixtures; JVM `:present`; W14 corpus | Spec 004 rewrite — presence (02 §7) |
| `error-boundary` | S3 | component | phase-semantics fixtures (02 §6) | Spec 004 rewrite — error boundary |
| `html` | **S1** (escaping + trusted-HTML node) → S4 hardens head policy + sanitization guidance | fn | dual-emitter agreement fixture; manifest site recording | `drafts/jvm-tree-and-conversion-contract.md` (trusted-HTML node) → Spec 004 rewrite — trusted markup |
| `raw` | **S1** compile-time form → foreign-boundary behavior hardens S4 | macro | S1 grammar fixture; S4 boundary corpus (W14) | Spec 004 rewrite — interop/foreign heads |
| `custom-element` | S4 (blessed delta #1) | macro (declaration) | W14 custom-element fixtures; conversion-table property rows | `drafts/jvm-tree-and-conversion-contract.md` (Q16 ruling) → Spec 004 rewrite |
| `client-only` | S3 (per 08 §2 Stage 3) → SSR phase flip completes S5 | macro | S3 client-gate fixture; S5 phase-flip hydration fixture | Spec 004 rewrite; Spec 011 (phase flip) |
| `mount` | S1 ⟨aligned to 08 §2: root descriptor⟩ → hydration completes S5 | macro (literal root form) | root-descriptor fixtures; duplicate-root-id build error | `drafts/root-identity-and-mount.md` → Spec 004 rewrite — roots and mounting |
| `create-root` `render!` `hydrate-root` `unmount!` | **S1** signatures + client mount → hydration behavior completes S5 | fns (host tier) | S1 client-mount smoke; S5 multi-root hydration + failed-root isolation fixtures | `drafts/root-identity-and-mount.md` → Spec 004 rewrite — roots; Spec 011 |
| `render-static` | S5 | fn (SSR tier; JVM emitter consumed by `re-frame2-ssr`) | explicit static-root policy + no-silent-elision fixtures (06 §3) | Spec 011; Spec 004 rewrite — roots |
| `adapter` | **S2** — it is the observation-port consumer (`rf/init!`) | var | init smoke; G-12 artifact isolation | Spec 006 (adapter contract; port stays outside the ten-fn map) |
| `->react` | **S6** (delta #2 — migration wave) | fn | compat-boundary fixtures, both nesting directions | `drafts/reagent-compat-boundary.md` → live Reagent compatibility appendix |
| `spread` | **S1** — part of the conversion architecture (delta #3) | fn | conversion-table fixtures; parity-corpus dynamic-map cases | `drafts/jvm-tree-and-conversion-contract.md` → Spec 004 rewrite — conversion architecture (02 §2) |
| `element` `view` `portal` | — (wave-2; no v1 stage — any Stage-3 portal mention reads as wave-2) | — | — | — |
| `re-frame.ui.data/render` | — (wave-2; separate artefact) | — | — | — |
| `re-frame.ui.react/*` (7 wrappers) | **S3** with the events/debugging consumer work; **call-shape spec is an S1 contract item** | `re-frame.ui.react` (interop tier) | hook-signature-hash + HMR-contribution fixtures; SSR/JVM behavior fixtures | Spec 004 rewrite — interop (call shapes authored as an S1 contract item) |
| `re-frame.ui.test/*` | **S1 core** (render/find/find-all/text/attrs/frame over JVM trees; `query` enforces the tier split) + **S2 mounted semantics** (dispatch!/with-root/query/flush!; native DOM events) + **S4** `flush-presence!` | `re-frame.ui.test` (in-artifact, dev/test scope) | selector-grammar fixtures; JVM-subset enforcement; real React mount/query/total-teardown/open-drain fixtures (07 §2); pass-scoped-memo leak fixture | `drafts/ui-test-selector-grammar.md` + tree contract → the 07 §2 contract's home in the rewrite |
| `re-frame.ui.tool/*` | **S3** with Xray consumption (W7a) | `re-frame.ui.tool` (tool tier, dev-only) | Xray consumption fixtures; G-7/G-11 (tool tier absent from production) | Spec 009 (instrumentation + catalogue rows) |

**Residual named gates (Q60)** — feasibility PASS ≠ these are done: real sub-cache graft
conformance (S2) · G-8 real-browser input matrix (S3) · root-manifest hydration +
failed-root isolation (S5) · G-1 rerun under the revised alternating-rounds estimator
(with the S1 gate wiring) · production elision G-7/G-11 (S3) and
absence/equivalence/budget gates (S6).

**Spec landing rule (Q61)** — each stage's spec edits merge atomically with that stage's
conformance slice (R-1 framing); the rewrite's per-stage conformance profile matrix
(codex2 F4 disposition) defines what "Stage-N-conforming" asserts, so no intermediate
checked-in spec claims unimplemented behavior.

## 3. Stage → bead plan (files at handoff; dependencies explicit)

Epic `EPIC: re-frame.ui program` with per-stage children. Sequencing constraints in
**bold** are hot-zone or ruled.

- **S0 (now, paper)** — drafts (R-1 interim + rewrite, R-2 amendment, selector grammar,
  plus the two contract drafts named under S1) reviewed via the S0 correctness pass —
  executed by the codex2 review + its binding disposition (09) + the 2026-07-12 fold-in;
  the post-fold coverage pass is the remaining §4 checkbox; spike reports folded into
  03; the §2 table blessed. *Gate: both spike reports feasibility-PASS; drafts reviewed.*
- **S1 epic — compiler slice**: (1) **hot-zone build-wiring PR first** (deps/shadow/
  package.json); (2) AST + analyzer + CLJS emitter + JVM emitter (no reactivity;
  includes the `spread` conversion path and `ui/html` + escaping — moved here from S4,
  08 §2 wins); (3) compile-error roster + didactic messages; (4) `ui.test`
  render/find/text over JVM trees; (5) parity corpus v0; (6) **interim Spec-004
  amendment merges here**, full 004 rewrite merges **atomically with the slice's
  conformance fixtures**; (7) G-1/G-14 gates wired. *Contracts first:* the epic's beads
  freeze `drafts/jvm-tree-and-conversion-contract.md` (public JVM-tree schema,
  conversion table, fingerprint/normalization inputs, SSR consumption boundary) and
  `drafts/root-identity-and-mount.md` (mount grammar, root identity, locator rules)
  before any emitter bead; the S1 **root descriptor** is the named, versioned subset of
  the S5 root manifest defined in `drafts/root-identity-and-mount.md`; the `.react/*`
  call-shape spec is authored here as an S1 contract item. **Leaf S1 product beads
  dispatch only after these contract artifacts are folded** (codex2 verdict (a),
  accepted) — until then, epic + contract-reconciliation beads only. *Single long-lived
  worker for the compiler; satellite beads for gates.*
- **S2 epic — ownership + frames + HMR**: observation port per the rewritten 03 §3 /
  Spec-006-amendment ABI — the target/evidence/lease model with `resolve-target`,
  `current?`, static override leases, and transactional multi-acquire rollback
  (**Spec-006 amendment merges here**); ViewCell + commit algorithm; `sub`
  stabilization; preflight ENSURE + frame-root/provider *in the new library* (the
  existing-adapter split is separate ruled work — sequence this after the frame-chain
  split merges (rf2-nyea0r → rf2-h1vqa4); coordinate, don't duplicate); epoch
  coalescing + `flush!` (the `ui.test` flush semantics land here); **full HMR matrix
  (Stage 2)**; G-3/4/5/6/13. *Contracts first:* the ABI source is the rewritten 03 §3 +
  Spec-006 amendment; **leaf S2 product beads dispatch only after that contract
  artifact is folded** (codex2 verdict (a), accepted).
- **S3 epic — events + debugging-as-consumer**: data handlers + placeholders + sync-input
  door (S-5 predicate); `local`/`effect`/`dispatch-fn`; foreign callbacks/components +
  `client-only` ⟨aligned to 08 §2 Stage 3⟩ (portals stay **wave-2** — read any Stage-3
  portal mention as wave-2; no v1 portal ships); error-boundary; the `.react/*` tier
  (call shapes frozen at S1); manifests + instance records + causes → **Xray consumption
  (W7a)** incl. `ui.tool/*`; elision gates G-7/G-11; **one RealWorld-resources vertical
  page (the Stage-3 rider)**. *Counter + dashboard must feel complete, hot reload
  included.*
- **S4 epic — presence + web boundaries**: presence (+ `ui.test/flush-presence!`),
  custom elements, head policy + trusted-markup hardening (`ui/html` itself lands S1 —
  08 §2 wins; S4 hardens head policy + sanitization guidance and the `raw`
  foreign-boundary corpus), a11y diagnostics; conformance corpus additions (W14).
- **S5 epic — SSR roots**: root manifests (the full form of which the S1 root descriptor
  is the named, versioned subset — `drafts/root-identity-and-mount.md`) + multi-root
  hydration + failure isolation + `render-static`; ssr/ssr-ring integration (W10);
  **Spec-011 edits sequenced behind whichever open PR owns Spec-011 edits at dispatch
  time (verify none is in flight before dispatching)**.
- **S6 epic — repo adoption**: migrator (W1) → examples (W4) → tools' own UIs (W7b) →
  template (W8) → docs/guide + skills (W2/W3/W6) → CI matrix rewrite (W9) → benchmarks
  vs trio (W11). *RealWorld-resources full app + Story + Xray green = the proof.*
- **S7 epic — deletion wave**: the soak gates (two green nightlies + one week
  no-fallback) → UIx/Helix/slim deleted, stock Reagent + the `reg-view` family frozen
  into the compat tier, tag cut, meta-docs (W12/W13).

**Standing coordination rules for the program:** hot-zone specs one-owner-at-a-time
(009 is the busiest — the catalogue rows land in small batches with their features);
`ui.test` and the parity corpus are critical-path (build before consumers); every stage's
gates wire into CI in that stage, not later; previously-ruled beads that touch a
program surface (rf2-uhk9ko router change, rf2-6gzobp, rf2-x76af2.17, the frame-chain
split rf2-nyea0r → rf2-h1vqa4) must be merged or explicitly sequenced against that
surface at dispatch time — verify their state then, not from this document.

## 4. Handoff checklist (what "ready" means)

- [x] Decision record (08 §5) + fable2 paper pass complete
- [x] S-1/S-4 spike report — **feasibility PASS** (parity 0.92–1.00 median, byte-identical markup; 11/11 dual-host parity; S-4 scoped to dual-host structural output only, 08 §1) — `spikes/s1-codegen-report.md`
- [x] S-3/S-2/S-5 spike report — **feasibility PASS ×3** (55/55 ownership fixtures; push 4–6.5× better than pull; sync door 24/24) + R-2 shapes — `spikes/s3-ownership-report.md`
- [x] drafts/: 004-interim (9 pairs), 004-rewrite, 006-amendment, selector grammar — written
- [ ] Post-fold coverage pass over drafts + 11/12 — verify every codex2 finding and
  implementer question (Q1–Q61) resolves to a named artifact or an explicitly-filed
  S1/S2 contract item. (The S0 correctness-review obligation itself was executed by the
  codex2 review + its binding disposition in 09 + the 2026-07-12 fold-in; this coverage
  check is what remains.)
- [x] Spike outcomes folded into 03 (target=evidence-not-handle; lease=owner-token; slice-scoped memo token; three observable states + retroactive tool labels) and 07 (G-1 estimator + emitted-JS golden test)
- [x] This §2 table blessed by Mike (it is the API freeze for v1) — **BLESSED as-is 2026-07-12 00:39 AUSEST** (option a). Protocol: any finding from the S0 correctness pass (codex2 disposition + fold-in + coverage pass, executed 2026-07-12) that touches the table returns to Mike as a row-level delta for re-ruling; the freeze itself is not reopened.
- [ ] Ruled-bead waves touching S1/S2 surfaces: merged or explicitly sequenced against
  those surfaces **at dispatch time** (a dispatch-time verification, not a state
  snapshot recorded here)
