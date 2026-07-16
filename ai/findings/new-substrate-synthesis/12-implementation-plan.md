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
| `event` `handler` `render-fn` | macros | guide 04 table + guide 03 refs | v1 *(render-fn widened 2026-07-16: also the value internal `slot` accepts — delta #4)* |
| `slot` | macro (compiler-owned invocation of a `render-fn` value at an internal seam) | component-library readiness — re-com renderer injection (v-table ×9, selection-list, parts), plus grids/design systems generally; `drafts/component-library-readiness.md` P0-3 | v1 (lands S3→S4 compiler surface) *(delta #4, DIRECTED by Mike 2026-07-16 — row-level delta per the freeze protocol)* |
| `raw-fn` | fn | guide 04 table (callback-ref) + guide 03 refs | v1 |
| `frame-root` `frame-provider` | components | guide 01/05, every mount | v1 |
| `presence` + `presence-phase` | macro + body form | guide 02 (toasts) | v1 |
| `error-boundary` | component | guide 02 (route shells) | v1 |
| `html` | fn | guide 02 (markdown/CMS) | v1 |
| `raw` | fn | guide 02 interop | v1 |
| `custom-element` | macro (declaration; `{:properties #{…}}` closed grammar) | guide 02 interop — property-accepting web components | v1 (lands with S4) *(delta #1, ruled + blessed 2026-07-12)* |
| `client-only` | macro | guide 08 (Mapbox) | v1 |
| `mount` | macro | guide 01 | v1 |
| `create-root` `render!` `hydrate-root` `unmount!` | macros, except `unmount!` (a fn) — `create-root`/`render!`/`hydrate-root` are compile-time macros (root identity/forms are literal); per `spec/004C-Roots-and-Mount.md` | guide 08 hydration + testbeds | v1 (host tier) |
| `render-static` | fn | guide 08 (static footer) | v1 (SSR tier) |
| `adapter` | var | `rf/init!` everywhere | v1 |
| `->react` | fn | guide 02 §interop (view exported to a React codebase); doc 10 per-subtree migration; W1 migrator | v1 (lands S6, migration wave) *(delta #2, ruled 2026-07-12 under delegated authority — original "none in guide" premise was wrong)* |
| `spread` | fn | 02 §2 conversion architecture (the single dynamic-map conversion); guide 02 interop + guide 07 visible-cost escapes | v1 *(delta #3, ruled 2026-07-12 under delegated authority; **delta #5, 2026-07-16**: a literal safe-policy form joins it — owned-key deny in every build, controlled proof preserved; readiness P1-4; exact spelling at spec landing)* |
| `element` `view` `portal` | — | none load-bearing in guide (`ui/raw` covers runtime-chosen heads) | **wave-2** (view needs prod-registry design first) *(2026-07-16: named triggers recorded — `view` graduates only on the parts-ceiling ruling or a native-table need; `portal` only on post-parity overlay evidence; re-com is the demand-bar consumer of record — readiness §4)* |
| `re-frame.ui.data/render` | fn | none | **wave-2, separate artefact** |
| `re-frame.ui.react/*` (use-ref, use-effect, use-layout-effect, use-effect-event, use-context, use-id, lazy) | fns | interop/migration tier — foreign-React embedding (citation corrected 2026-07-12 per codex2 F5: guide 03's chart uses `local`/`effect`/`ui/raw-fn`, NOT these hooks); call shapes = S1 contract item; the S1 demand-bar audit confirms a consumer or returns this row as a Mike delta | v1 (interop tier) |
| `re-frame.ui.test/*` (render, find, find-all, query, text, attrs, frame, dispatch!, with-root, flush!, flush-presence!) | fns + `with-root` macro; CLJS `with-root` and zero/thunk `flush!` return Promises, JVM `flush!` is synchronous/nil | guide 09 + 07 fixtures | v1 (test tier) |
| `re-frame.ui.tool/*` (view-manifest, mounted-views, explain-render, view-dependencies, view-event-sites) | fns | Xray/Pair (04 §5) | v1 (tool tier, dev-only) |

Editor/kondo diagnostics layer: wave-2 (08 §3). Anything not in this table does
not exist.

**Delta record — component-library readiness (DIRECTED by Mike 2026-07-16, in-session;
owning doc `drafts/component-library-readiness.md`; consensus provenance
`ai/findings/re-com-port/synthesis.{fable,codex,grok}.md`).** Row-level deltas #4
(`slot` + `render-fn` internal widening) and #5 (`spread` safe-policy form) above;
plus three non-row contract deltas: `local` returns `[value set! update!]` (P0-1,
02 §5 / 03 §5); the sync door widens to compiler-known `ui/event` vector outcomes at
proven-controlled sites (P0-2, 02 §3); the view manifest gains a versioned docs/slot
projection (P1-5, 04 §1). Trigger-gated candidates that are NOT rows until their
triggers fire: registered `ui/view` (parts-ceiling ruling), `ui/portal` (post-parity
overlay evidence), `defview-alias` (façade-prototype proof), reset-key `local`
(controlled/commit split), lexical `ui/tpl` (W1/W3 checkpoint) — beads carry the
triggers. The freeze itself is not reopened; these enter under the blessed table's own
delta protocol.

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
| `sub` · `frame` | S2 ⟨aligned to 08 §2⟩ (grammar parses at S1; reactive/frame semantics S2) | body forms | S2 ownership fixtures; `rf=` stabilization fixtures; G-3/G-5 | rewritten 03 §3 → `spec/006-ReactiveSubstrate.md` §The internal observation port; Spec 004 rewrite — reactive reads |
| `local` · `effect` | S3 ⟨aligned to 08 §2⟩ | body forms | narrow-placement-law fixtures (F8 ruling); StrictMode replay/cleanup; JVM initial-value-only + typed setter error (07 §2); **batched two-writer atomic-`update!` matrix + fn-value `set!` fixtures (readiness P0-1, 2026-07-16)** | Spec 004 rewrite — local placement law (`[value set! update!]`) + effects |
| `lease` | **S2** (the Resources ownership family lands with the S2 slice) → view-level resource-lease semantics **confirm at S3** (marked) | body form | S2 `resource-lease-reconcile` fixtures — closed descriptor validation (`lease-descriptor` / `lease-compiler`); render/abandonment owns nothing; independent framework-minted owner per lexical site with `rf=`-equal retention + fresh-owner retarget; complete desired-set prevalidation; deterministic ensures-before-releases per frame over the ordinary FIFO drain (no global/cross-frame rollback of dispatched ensures — transactional multi-acquire rollback is compiled `sub` observation acquisition's law, per `spec/006-ReactiveSubstrate.md` §The internal observation port, not public `lease`); disconnect/teardown/frame- and root-destroy/Activity/StrictMode/HMR retention-release; S3 resource-lease confirmation fixture | Spec 004 rewrite — leases (the Resources ownership family; observation-acquisition rollback stays with rewritten 03 §3 / `spec/006-ReactiveSubstrate.md` §The internal observation port) |
| `dispatch-fn` | S3 ⟨aligned to 08 §2⟩ | fn | event-boundary decision-table fixtures | Spec 004 rewrite — handler boundary law |
| `event` `handler` `render-fn` | S3 ⟨aligned to 08 §2⟩ (grammar recognized at S1; committed behavior + sync door S3) | macros | sync-input-door fixture (S-5 predicate **+ the 2026-07-16 `ui/event` vector-outcome arm**); G-8 real-browser matrix **through a reusable event-prefix component** (readiness P0-2); foreign-boundary fixtures; internal fn-prop ruling fixtures | Spec 004 rewrite — event projections + handler boundary law |
| `slot` | **S3 → S4** (compiler surface; delta #4, directed 2026-07-16) | macro (internal render-slot invocation) | client/JVM slotted-output parity; keyed reorder under slots; purity diagnostics (`sub`/`lease`/dispatch/hooks inside rejected); manifest slot sites; headless Tier-1 slotted trees (readiness P0-3) | Spec 004 rewrite — template/interop (render slots) + `spec/004B` tree representation |
| `raw-fn` | S3 ⟨aligned to 08 §2⟩ (callback-ref form; grammar recognized at S1) | fn | foreign-boundary fixtures | Spec 004 rewrite — event projections (callback-ref form) |
| `frame-root` `frame-provider` | S2 ⟨aligned to 08 §2⟩ | components | preflight-ENSURE fixtures; G-4/G-6 | Spec-006 amendment (ENSURE preflight, 03 §8); Spec 004 rewrite — roots/frames |
| `presence` + `presence-phase` | S4 | macro + body form | fake-clock enter/exit fixtures; JVM `:present`; W14 corpus | Spec 004 rewrite — presence (02 §7) |
| `error-boundary` | S3 | component | phase-semantics fixtures (02 §6) | Spec 004 rewrite — error boundary |
| `html` | **S1** (escaping + trusted-HTML node) → S4 hardens head policy + sanitization guidance | fn | dual-emitter agreement fixture; manifest site recording | `spec/004B-UI-Tree-and-Conversion.md` §Children, text, and escaping (trusted-HTML node) → Spec 004 rewrite — trusted markup |
| `raw` | **S1** compile-time form → foreign-boundary behavior hardens S4 | fn | S1 grammar fixture; S4 boundary corpus (W14) | Spec 004 rewrite — interop/foreign heads |
| `custom-element` | S4 (blessed delta #1) | macro (declaration) | W14 custom-element fixtures; conversion-table property rows | `spec/004B-UI-Tree-and-Conversion.md` §Custom elements (Q16 ruling) → Spec 004 rewrite |
| `client-only` | S3 (per 08 §2 Stage 3) → SSR phase flip completes S5 | macro | S3 client-gate fixture; S5 phase-flip hydration fixture | Spec 004 rewrite; Spec 011 (phase flip) |
| `mount` | S1 ⟨aligned to 08 §2: root descriptor⟩ → hydration completes S5 | macro (literal root form) | root-descriptor fixtures; duplicate-root-id build error | `spec/004C-Roots-and-Mount.md` §3 (mount grammar) → Spec 004 rewrite — roots and mounting |
| `create-root` `render!` `hydrate-root` `unmount!` | **S1** signatures + client mount → hydration behavior completes S5 | macros; `unmount!` a fn (host tier) | S1 client-mount smoke; S5 multi-root hydration + failed-root isolation fixtures | `spec/004C-Roots-and-Mount.md` → Spec 004 rewrite — roots; Spec 011 |
| `render-static` | S5 | fn (SSR tier; JVM emitter consumed by `re-frame2-ssr`) | explicit static-root policy + no-silent-elision fixtures (06 §3) | Spec 011; Spec 004 rewrite — roots |
| `adapter` | **S2** — it is the observation-port consumer (`rf/init!`) | var | init smoke; G-12 artifact isolation | Spec 006 (adapter contract; port stays outside the ten-fn map) |
| `->react` | **S6** (delta #2 — migration wave) | fn | compat-boundary fixtures, both nesting directions | `drafts/reagent-compat-boundary.md` → live Reagent compatibility appendix |
| `spread` | **S1** — part of the conversion architecture (delta #3); **safe-policy form lands S3 (delta #5, 2026-07-16)** | fn | conversion-table fixtures; parity-corpus dynamic-map cases; **owned-key deny-in-every-build + controlled-proof-preserved fixtures (readiness P1-4)** | `spec/004B-UI-Tree-and-Conversion.md` §The DOM conversion table → Spec 004 rewrite — conversion architecture (02 §2) |
| `element` `view` `portal` | — (wave-2; no v1 stage — any Stage-3 portal mention reads as wave-2) | — | — | — |
| `re-frame.ui.data/render` | — (wave-2; separate artefact) | — | — | — |
| `re-frame.ui.react/*` (7 wrappers) | **S3** with the events/debugging consumer work; **call-shape spec is an S1 contract item** | `re-frame.ui.react` (interop tier) | hook-signature-hash + HMR-contribution fixtures; SSR/JVM behavior fixtures; **native-component-library measure-before-paint blessing: guide recipe + StrictMode/reconnect/HMR/JVM fixtures for `use-ref`+`use-layout-effect` (readiness C-6, 2026-07-16 — no new spelling)** | Spec 004 rewrite — interop (call shapes authored as an S1 contract item) |
| `re-frame.ui.test/*` | **S1 core** (render/find/find-all/text/attrs/frame over JVM trees; `query` enforces the tier split) + **S2 mounted semantics** (`dispatch!`; Promise-backed `with-root`; `query`; Promise-backed zero/thunk `flush!` on CLJS, synchronous nil on JVM; platform APIs for already-host-owned DOM mechanics) + **S3** compiled event-vector delivery through native events + **S4** `flush-presence!` | `re-frame.ui.test` (in-artifact, dev/test scope) | selector-grammar fixtures; JVM-subset enforcement; real React mount/query/total-teardown/open-drain/forgotten-await fixtures; drain-quiescent recursive framework/React fixed point; pass-scoped-memo leak fixture (07 §2) | `spec/004D-UI-Test-Selectors.md` (selector grammar) + `spec/004B-UI-Tree-and-Conversion.md` (tree/node reading) → the 07 §2 contract's home (Spec 008) |
| `re-frame.ui.tool/*` | **S3** with Xray consumption (W7a) | `re-frame.ui.tool` (tool tier, dev-only) | Xray consumption fixtures; G-7/G-11 (tool tier absent from production); **versioned docs/slot manifest projection — props schema + per-prop docs/defaults + slot metadata in one stable dev/test shape, production absence proven (readiness P1-5, 2026-07-16)** | Spec 009 (instrumentation + catalogue rows) |

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

- **S0 (paper) — complete** — drafts (R-1 interim + rewrite, R-2 amendment, selector
  grammar, plus the two contract drafts named under S1) reviewed via the S0 correctness
  pass — executed by the codex2 review + its binding disposition (09) + the 2026-07-12
  fold-in; the post-fold coverage pass ran 2026-07-12 (SHIP verdict — §4); spike reports
  folded into 03; the §2 table blessed. *Gate: both spike reports feasibility-PASS;
  drafts reviewed — MET.*
- **S1 epic — compiler slice**: (1) **hot-zone build-wiring PR first** (deps/shadow/
  package.json); (2) AST + analyzer + CLJS emitter + JVM emitter (no reactivity;
  includes the `spread` conversion path and `ui/html` + escaping — moved here from S4,
  08 §2 wins); (3) compile-error roster + didactic messages; (4) `ui.test`
  render/find/text over JVM trees; (5) parity corpus v0; (6) **interim Spec-004
  amendment merges here**, full 004 rewrite merges **atomically with the slice's
  conformance fixtures**; (7) G-1/G-14 gates wired. *Contracts in place:* the public
  JVM-tree schema, conversion table, fingerprint/normalization inputs, and SSR consumption
  boundary are frozen in `spec/004B-UI-Tree-and-Conversion.md`, and the mount grammar,
  root identity, and locator rules in `spec/004C-Roots-and-Mount.md` (both promoted from
  the S0 drafts); the S1 **root descriptor** is the named, versioned subset of the S5 root
  manifest defined in `spec/004C-Roots-and-Mount.md` §2; the `.react/*` call-shape spec is
  authored here as an S1 contract item. The codex2 verdict (a) contract-first gate is
  **satisfied** — those artifacts have landed, so leaf S1 product beads implement against
  the live specs. *Single long-lived worker for the compiler; satellite beads for gates.*
- **S2 epic — ownership + frames + HMR**: observation port per
  `spec/006-ReactiveSubstrate.md` §The internal observation port ABI (promoted from the
  rewritten 03 §3 / S0 amendment draft) — the target/evidence/lease model with
  `resolve-target`, `current?`, static override leases, and transactional multi-acquire
  rollback (**now normative in Spec 006**); ViewCell + commit algorithm; `sub`
  stabilization; preflight ENSURE + frame-root/provider *in the new library* (the
  existing-adapter split is separate ruled work — sequence this after the frame-chain
  split merges (rf2-nyea0r → rf2-h1vqa4); coordinate, don't duplicate); epoch
  coalescing + `flush!` (the `ui.test` flush semantics land here); **full HMR matrix
  (Stage 2)**; G-3/4/5/6/13. *Contract in place:* the ABI is now normative in
  `spec/006-ReactiveSubstrate.md` §The internal observation port (promoted from the
  rewritten 03 §3 / S0 amendment draft), so leaf S2 product beads implement against that
  live spec (codex2 verdict (a) gate satisfied).
- **S3 epic — events + debugging-as-consumer**: data handlers + placeholders + sync-input
  door (S-5 predicate); `local`/`effect`/`dispatch-fn`; foreign callbacks/components +
  `client-only` ⟨aligned to 08 §2 Stage 3⟩ (portals stay **wave-2** — read any Stage-3
  portal mention as wave-2; no v1 portal ships); error-boundary; the `.react/*` tier
  (call shapes frozen at S1); manifests + instance records + causes → **Xray consumption
  (W7a)** incl. `ui.tool/*`; elision gates G-7/G-11; **one RealWorld-resources vertical
  page (the Stage-3 rider)**. *Counter + dashboard must feel complete, hot reload
  included.* **[AMENDED 2026-07-16 — component-library readiness (directed; owning doc
  `drafts/component-library-readiness.md`):** the stage additionally carries the P0
  triad — `local` `[value set! update!]` (on `.95.2`), the `ui/event` vector-outcome
  sync-door arm (follow-up child to `.95.1`, pre-conformance), internal render slots
  (`ui/slot`, its own child) — plus the safe-spread policy child, the interop-tier
  native-library blessing (on `.95.4`), the docs/slot manifest projection (on `.95.6`),
  and the widened gates + component-library proof pack (on `.95.10`). Spikes
  (reset-key `local`, `ui/tpl`, portal, registered `ui/view`, `defview-alias`) are
  trigger-gated beads on the program epic, **not** S3 scope.]**
- **S4 epic — presence + web boundaries**: presence (+ `ui.test/flush-presence!`),
  custom elements, head policy + trusted-markup hardening (`ui/html` itself lands S1 —
  08 §2 wins; S4 hardens head policy + sanitization guidance and the `raw`
  foreign-boundary corpus), a11y diagnostics; conformance corpus additions (W14).
- **S5 epic — SSR roots**: root manifests (the full form of which the S1 root descriptor
  is the named, versioned subset — `spec/004C-Roots-and-Mount.md`) + multi-root
  hydration + failure isolation + `render-static`; ssr/ssr-ring integration (W10);
  **Spec-011 edits sequenced behind whichever open PR owns Spec-011 edits at dispatch
  time (verify none is in flight before dispatching)**.
- **S6 epic — repo adoption**: migrator (W1) → examples (W4) → tools' own UIs (W7b) →
  template (W8) → docs/guide + skills (W2/W3/W6) → CI matrix rewrite (W9) → one-time
  benchmarks vs the legacy trio (W11). *RealWorld-resources full app + Story + Xray
  green = the proof.* Primary examples/templates/docs teach only `re-frame.ui`; a
  minimum compatibility reference remains for Reagent/UIx.
- **S7 epic — compatibility freeze + deletion wave**: the soak gates (two green
  nightlies + one week no-fallback) → Helix/reagent-slim deleted; stock Reagent + the
  `reg-view` family and UIx frozen as compatibility adapters; tag cut; meta-docs
  (W12/W13). Retain `day8/re-frame2-uix`, its public exports, pinned compatibility
  suite + one smoke, classpath probe, changed-surface classifier arm, release test/deploy
  leaf, and minimum compatibility reference. The import-deletion grep covers only
  Helix/reagent-slim. Reagent's live view contract moves to the Reagent-specific 004A
  appendix; UIx remains primarily owned by Spec 006/API/Conventions/Ownership.

**Adapter-selection invariant through S7.** v1 keeps exactly one installed adapter per
process. The surviving browser boot choices are `re-frame.ui`, stock Reagent, and UIx;
there is no per-frame or within-frame selector. Foreign React-component boundaries
(`ui/raw`, `ui/->react`, and ordinary React elements) do not install or select another
adapter.

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
- [x] Post-fold coverage pass over drafts + 11/12 — **ran 2026-07-12, SHIP verdict**
  (durable record in the epic `rf2-vxgfnd` NOTES, "S0 COVERAGE PASS (2026-07-12)"): every
  codex2 finding and implementer question (Q1–Q61) resolves to a named artifact or an
  explicitly-filed S1/S2 contract item. (The S0 correctness-review obligation itself was
  executed by the codex2 review + its binding disposition in 09 + the 2026-07-12 fold-in;
  this coverage check confirmed the residue and dispatched cosmetic cleanup separately.)
  The still-open implementer questions were the S2 obligations pinned onto named beads
  when the S2 children were filed at the S1 boundary: Q49 (ENSURE
  retry-after-preflight-failure) → rf2-vxgfnd.9; Q51 (`flush!` scope + nesting / reentrancy
  / act semantics) → rf2-vxgfnd.10; the four [S2-CONFIRM] observation-port items →
  rf2-vxgfnd.7; the static-override-lease Tier-3 fixture → rf2-vxgfnd.8 / .12; the
  `:activity-hidden` retroactive-annotation evidence schema (name unified 2026-07-12 —
  03 §4 spelling wins) → the S2 evidence/Xray slice. All have since landed with S2: S1 is
  complete and S2 core is verified S3-ready under the rf2-vxgfnd.22 boundary review
  (punch-list landing). S3–S7 not started.
- [x] Spike outcomes folded into 03 (target=evidence-not-handle; lease=owner-token; slice-scoped memo token; three observable states + retroactive tool labels) and 07 (G-1 estimator + emitted-JS golden test)
- [x] This §2 table blessed by Mike (it is the API freeze for v1) — **BLESSED as-is 2026-07-12 00:39 AUSEST** (option a). Protocol: any finding from the S0 correctness pass (codex2 disposition + fold-in + coverage pass, executed 2026-07-12) that touches the table returns to Mike as a row-level delta for re-ruling; the freeze itself is not reopened.
- [ ] Ruled-bead waves touching S1/S2 surfaces: merged or explicitly sequenced against
  those surfaces **at dispatch time** (a dispatch-time verification, not a state
  snapshot recorded here)
