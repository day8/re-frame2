# 12 — Implementation plan: handoff-ready program state

**Status:** final · 2026-07-11 · codex2 fold-in applied 2026-07-12 (§2b added; stage
fixes per the binding disposition in 09; volatile operator state replaced by semantic
dependencies). Purpose: when the repo is handed over for implementation, the program
*files and dispatches* rather than being re-derived. Four parts: where the code lands,
the consolidated public surface + demand-bar audit (before Stage 1), the authoritative
surface matrix (§2b), and the stage→bead plan with hot-zone sequencing.

## 1. Where the artifact lands

- **`implementation/ui/`** — new artefact beside `core/` and the retained `adapters/`:
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

## 2. Public surface + demand-bar audit → RELOCATED (§2/§2b graduated to spec/API.md)

**Relocated 2026-07-18 (rf2-mgy7pz, at the S3→S4 boundary).** The blessed public-surface
table (§2), its component-library delta record (deltas #1–#5+), and the authoritative
surface matrix (§2b, name → stage / owner / proof / spec home) — together with the freeze
provenance and the row-level delta protocol — **graduated to the tracked spec home**
`spec/API.md` §"re-frame.ui — blessed public-surface freeze". That section is now the
authoritative copy; the freeze (blessed 2026-07-12 00:39 AUSEST) and its delta protocol
govern there. The demand-bar table (WHAT the substrate exposes) and the per-name
stage/owner/proof/spec-home matrix (WHEN it lands) are unchanged — only their home moved.

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
  benchmarks vs the legacy adapters (W11). *RealWorld-resources full app + Story + Xray
  green = the proof.* Examples/templates/docs teach `re-frame.ui` as a new experimental
  option alongside the retained Reagent/UIx/reagent-slim adapters.
- **S7 epic — Helix-removal wave**: the soak gates (two green
  nightlies + one week no-fallback) → **only Helix deleted**; stock Reagent + the
  `reg-view` family, UIx, and reagent-slim live on as first-class, actively-supported
  adapters; tag cut; meta-docs (W12/W13). Retain `day8/re-frame2-uix`, its public exports,
  pinned compatibility suite + one smoke, classpath probe, changed-surface classifier arm,
  release test/deploy leaf, and the compatibility reference. The import-deletion grep
  covers only Helix. Reagent's live view contract moves to the Reagent-specific 004A
  appendix; UIx remains primarily owned by Spec 006/API/Conventions/Ownership.

**Adapter-selection invariant through S7.** v1 keeps exactly one installed adapter per
process. The surviving browser boot choices are `re-frame.ui`, stock Reagent, UIx, and
reagent-slim; there is no per-frame or within-frame selector. Foreign React-component boundaries
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
<!-- DURABLE ANCHOR — S0 coverage-pass disposition (one-time; does not drift).
     Guarded by scripts/check_synthesis_plan_authority.py, which pins the checked
     box, the "S0 COVERAGE PASS (2026-07-12) — SHIP" disposition + its epic
     rf2-vxgfnd authority, and the named implementer-question → bead mappings below.
     Do NOT delete or uncheck this item. Live stage/progress state is owned by epic
     rf2-vxgfnd and its children, NOT by this plan — do not restate it here. -->
- [x] **S0 COVERAGE PASS (2026-07-12) — SHIP verdict** — durable disposition recorded here;
  authority: this checklist item is itself the durable record (the post-fold coverage pass ran
  2026-07-12 during PR #6090's review), and the epic `rf2-vxgfnd` owns the S1–S7 program and
  records the same-dated pass in its description. The post-fold coverage
  pass ran over drafts + 11/12 and found every codex2 finding and implementer question
  (Q1–Q61) resolving to a named artifact or an explicitly-filed S1/S2 contract item. (The S0
  correctness-review obligation itself was executed by the codex2 review + its binding
  disposition in 09 + the 2026-07-12 fold-in; this coverage check confirmed the residue and
  dispatched cosmetic cleanup separately.) The still-open implementer questions were the S2
  obligations pinned onto named beads when the S2 children were filed at the S1 boundary:
  Q49 (ENSURE retry-after-preflight-failure) → rf2-vxgfnd.9; Q51 (`flush!` scope + nesting /
  reentrancy / act semantics) → rf2-vxgfnd.10; the four [S2-CONFIRM] observation-port items
  → rf2-vxgfnd.7; the static-override-lease Tier-3 fixture → rf2-vxgfnd.8 / .12; the
  `:activity-hidden` retroactive-annotation evidence schema (name unified 2026-07-12 — 03 §4
  spelling wins) → rf2-vxgfnd.8 (S2b ViewCell, deliverable item 4: the three-state lifecycle
  facts + qualified retroactive annotations emitted per 03 §4; merged as PR #5708).
- [x] Spike outcomes folded into 03 (target=evidence-not-handle; lease=owner-token; slice-scoped memo token; three observable states + retroactive tool labels) and 07 (G-1 estimator + emitted-JS golden test)
- [x] This §2 table blessed by Mike (it is the API freeze for v1) — **BLESSED as-is 2026-07-12 00:39 AUSEST** (option a). Protocol: any finding from the S0 correctness pass (codex2 disposition + fold-in + coverage pass, executed 2026-07-12) that touches the table returns to Mike as a row-level delta for re-ruling; the freeze itself is not reopened.
- [ ] Ruled-bead waves touching S1/S2 surfaces: merged or explicitly sequenced against
  those surfaces **at dispatch time** (a dispatch-time verification, not a state
  snapshot recorded here)
