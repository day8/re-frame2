# 09 — Decision log and provenance

History lives here; every other document states final decisions and state only.

**2026-07-11.** How the v2 revision handled every substantive finding from
`reviews/fable1.md` (F-*) and `reviews/codex1.md` (A-*/sections). Mike's directives
folded in: spec amendments welcome (pre-alpha), trust-the-programmer on security, HMR
must be excellent, migration doc added.

## Adopted — architecture level

| Finding | Disposition |
|---|---|
| codex identity model (root ≠ frame; five identities; root manifest; never mount-position) | **Adopted wholesale** — I-8 rewritten; 06 §2 rewritten; "island" retired for "root"; duplicate root ids = build error; frame payloads idempotent/shared |
| codex A1 (ENSURE cannot run from render) | **Adopted** — frame ENSURE is host preflight (03 §8); conditional/list `frame-root` = compile error; I-1 extended; fixtures added; doubles as idempotent payload install for SSR |
| codex A2 + fable F-13 (override carriage / one captured observation target) | **Adopted** — observation-target protocol (03 §3): resolve once at render (real node *or* override), commit acquires the captured target; `:owned? false` honesty; JVM overrides are an explicit `ui.test/render` option, not a pretended same-mechanism |
| codex A3 + fable F-11-adjacent (lifecycle states) | **Adopted** — four states: connected / activity-disconnected / unmounted / dead (03 §4); Activity ≠ unmount everywhere incl. Xray connection facts. **Superseded 2026-07-12 by codex2 F6 disposition: three observed states + qualified retroactive labels.** |
| fable F-14/F-20/F-24 + codex (push/pull fork) | **Resolved: push committed** — invariants unconditional; S-2/G-13 reframed as falsification benchmarks whose failure reopens 03; pull conditionals removed from 03/05 |
| codex A4 + fable F-11 (`:on-mount`/`:on-unmount`) | **Removed from v1** — domain events can't ride mechanical lifecycle; host sync = effects, domain visibility = route/domain events; `(effect :connect …)` named honestly (no "once"/"mount") |
| codex A5 + fable F-29/F-37/F-5/F-9/F-16 (resumability) | **Demoted to post-alpha research** with graduation criteria (06 §4); event vectors stay data in manifest/JVM tree; client lowers to normal handlers; no HTML handler attributes; placeholder vocab shrunk to 3 scalars (`form-data`/`event` → `ui/event`) |
| codex A6 (static-island inference unsound) | **Adopted** — transitive `requires-client-runtime?` capability + explicit host `render-static` policy; no silent elision (06 §3) |
| codex A7 (JVM contract for host-bearing views) | **Adopted** — the subset table (06 §1); `local` initial-value-only + typed setter error; effects metadata-only; byte-compatible → normalized structural equivalence; `ui.test` enforces (07 §2) |
| codex A8 + fable F-6/F-47 (error boundary) | **Adopted** — explicit `ui/error-boundary {:fallback :reset-key :on-error}` with phase semantics (02 §6); `:catch` option removed |
| codex A9 + fable F-1/F-15 (event boundary law) | **Adopted, synthesized** — full-column decision table (invoker/phase/identity/capture/serializability); dynamic handlers classify at runtime by type, placeholders literal-only + dev warning; capture-free vectors legal in loops, loop-capturing vectors compile-error, bare-fn-in-loop dev nudge; bare fn on native DOM = `ui/handler` shorthand, explicit form required at foreign boundaries; `ui/raw-fn` replaces `raw-handler`; `:on-key-down` spelling pinned + listener options vocabulary |
| fable F-10 + codex forms concern (controlled inputs) | **Designed** — the synchrony law: controlled-input sites drain synchronously within the DOM event (02 §3, 03 §3); G-8 reordered correctness-first; S-5 spike added |
| codex presence capability | **Adopted, bounded** — `ui/presence` (02 §7): keyed enter/exit retention, inert exits, reduced-motion, fake-clock tests, JVM `:present`; named consumers (toasts/modals); explicitly not an animation system |
| codex trusted markup | **Adopted, low-friction per Mike** — `(ui/html string)`: the visible call is the contract; no token ceremony; both emitters agree; manifests record sites |
| Mike: HMR excellence | **Consolidated contract** — 03 §10: stable shells, signature hash, site identity, frame non-reseed, sub replacement, Fast Refresh, REPL=HMR path; fixtures moved to Stage 2 (fable F-36) |
| Mike: migration doc | **Added** — [10-migration-from-reagent.md](10-migration-from-reagent.md) with the mechanicalness verdict (~80–90% scriptable) |

## Adopted — contract/spec level

| Finding | Disposition |
|---|---|
| fable F-3/F-41 (lean-by-reference on Codex) | Commit algorithm (8 steps), props ABI, site-identity scheme restated in-suite (03 §3, 02 §1, I-8); refs policy remains to restate in a 02 v3 pass — flagged |
| fable F-4 (error-id inventory) | Runtime error taxonomy table added (03 §11) |
| fable F-2/F-21 (ui.test unspecified; naming drift) | `ui.test` contract table (07 §2); naming unified; `.cljc` constraint stated; guide corrected |
| fable F-12 (probe fan-out) | Render-pass-scoped pure memo table (03 §3) + fixture + risk row |
| codex runtime-only commit wording | Fixed (03 §9): runtime projections do notify |
| codex cross-frame honesty | I-10 + 03 §8 reworded: no spelling ≠ impossible; carried-op misuse gets a dev diagnostic |
| codex `local` framing | Frame-resident reservation withdrawn; `local` is host-local, full stop (02 §5) — fable F-35's reservation lost to codex's semantic argument |
| fable F-23 / codex effect deps | `rf=` everywhere; cost documented; StrictMode replay + cleanup semantics stated (03 §6) |
| codex capability vocabulary + production honesty | 05 §1 expanded (16 bits); event-only commit cost, allocation/wrapper claims scoped; memo scope stated; `:memo false` removed (no consumer) |
| codex packaging contradiction | One packaging paragraph (05 §1): `.cljc` source artifact, browser scan-gated, existing SSR artifact consumes the JVM emitter |
| codex G-7 powerset | Per-shape + pairwise + high-risk triples; StrictMode wording fixed (fable F-42) |
| codex generative scope | Props generable; app-db needs supplied generators (07 §4) |
| codex Xray section + fable F-40 | 04 rewritten: causes as vector, enrich-existing-surfaces-first (panels need IA review), occurrence identity, loss accounting, bounded prop precision, override honesty, restore = operation token + target epoch, production-weight explanation |
| fable F-44/F-45/F-46 (new goals) | Adopted: G-14 compile budget + REPL story (02 §8); AI-agent ergonomics goal (01); a11y posture + high-confidence diagnostics (01, 04 §6) |
| fable F-33 + codex (toy DOM emitter) | Downgraded to AST-shape gate + archived spike (08 §1) |
| fable F-39 (demand-bar timing) | Table before Stage 1 (01, 08 §3) |
| fable F-8 (island identity either/or) | Resolved by root manifest; duplicates = build error |
| fable F-17 (lease/Activity retention) | Resource-layer freshness policy governs reveal cost (03 §4) |
| fable F-18 (guide registrar wording) | Fixed: process-global + lazy-registration caveat (02 §3, guide) |
| fable F-19 (one-revision argument) | Two-guard argument recorded (03 §2, I-4) |
| fable F-22 (README A7 claim) | Editor/kondo layer is a named wave-2 row under the AI-ergonomics goal (01) |
| fable F-25 (`:key` double duty) | `:key` reserved on internal views, app prop named `:key` illegal (02 §1); keyboard placeholder stays `:rf.ui/key` (contexts don't collide: props map vs event vector) |
| fable F-26 (kernel presupposes delegation) | Kernel roster reworded (05 §1) — delegation gone from v1 anyway |
| fable F-31 (memo overclaim) | Guide 07 gains the dynamic-children row; 05 scopes the claim |
| fable F-43 (mixed local+dispatch) | One-pass coalescing + sync-door interaction stated (03 §3) |
| codex `ui/mount` A10 | `mount` is a macro over a literal root form (02 §6) |
| codex custom elements / head | Bounded custom-element rule (02 §2); head ownership paragraph (06 §5) |
| codex "no #js ever" | Scoped to compiled paths (02 §2) |
| codex "scenes mount by render-key" | Fixed: view id (02 §1, 04 §5) |
| codex nyea0r reconciliation | R-7 added (08 §5) |
| fable R-2 honest scope + Mike pre-alpha directive | R-1/R-2 committed-now framing with honest Spec-004-rewrite scope (08 §5) |

## Declined / modified, with reasons

| Finding | Disposition |
|---|---|
| codex: security contracts around payloads/replay (auth, CSP matrices) | **Mooted/declined** — replay is deferred; and per Mike: trust the programmer, reduce friction — `ui/html` is a visible door, not a security ceremony; existing Spec 011 encoder covers payloads |
| codex: full a11y/presence ARIA policy surface | **Bounded instead** — posture + high-confidence diagnostics only; inert-by-default exits kept |
| fable F-28's "keep bare fns everywhere" vs codex A9.2's "reject everywhere" | **Split the difference** — DOM-native shorthand legal (phase known), foreign boundary explicit; both reviews' core concerns honored |
| fable F-35 (frame-resident `local` reservation) | **Withdrawn** per codex — a future frame-resident feature would be a new name; keeping `local` a substrate form still preserves flexibility without promising semantics |
| codex Stage renumbering verbatim | **Adopted in substance, compressed** — rulings-first Stage 0, debugging-as-consumer in Stage 3, SSR at 5, real-app migration gate at 6 |
| fable F-48 (incremental adoption as a goal) | **Partially** — not a goal; repo-migration workstream budgeted (08 §6) + the new migration doc |

## codex2 disposition (ruled 2026-07-12 00:59 AUSEST; per-finding, binding)

Mike delegated accept/reject per point. Spot-verification confirmed codex2's factual
claims before ruling (port-shape triple mismatch, guide/table conflicts, four-state
residue, search-box/local-law conflict). Dispositions below are binding direction for
the fold-in agents; each names its owning artifact.

| # | Finding | Disposition |
|---|---|---|
| 1 | BLOCKER port ABI (3 shapes) | **Accepted in full.** Spike §5 target/evidence/lease model = sole shape source. `resolve-target` + `current?` + static override leases + transactional multi-acquire rollback (stage all new leases; any failure ⇒ release newly acquired synchronously, prior committed set stays) + callback/reentrancy rules + internal-fail-loud vs public-nil split with ONE catalogue id (`:rf.error/no-such-sub` — the checked-in public id wins; internal port throws it too, no second id) + named cross-artifact seam. 03 §3 and the 006 amendment are rewritten to it. |
| 2 | BLOCKER JVM tree ABI | **Accepted in full.** New contract draft (`drafts/jvm-tree-and-conversion-contract.md`): versioned public tree schema promoted from the spike shape (`:tag`/`:attrs`/`:events`/`:children` + fragment/view-boundary/trusted-HTML/text nodes), conversion table promoted from spike rows, fingerprint/normalization inputs, SSR consumption boundary. Node reading ruled: nodes are plain maps; attrs/events live under their own keys (no direct `(:on-click node)` attr lookup); `ui.test/attrs` returns the merged projection. Selector draft reconciled; OPEN-1 resolved via the view-boundary slot on the tree contract (fragment/nil-rooted views: view-id selectors match their boundary marker, not a root element). |
| 3 | BLOCKER root identity | **Accepted in full.** New contract draft (`drafts/root-identity-and-mount.md`): literal mount grammar incl. root-id (required, host-authored, with a derivation default), locator rules, duplicate/conflict detection, frame-plan extraction, client-only defaults. S1 "root descriptor" defined as a named, versioned subset of the S5 manifest. |
| 4 | BLOCKER staged conformance + reg-view home | **Accepted.** Rewrite gains a per-stage conformance profile matrix (section ↔ stage; "Stage-1-conforming" = the S1 rows only) rather than splitting into serial amendments (preserves ratified R-1 atomic framing). Frozen stock Reagent gets a **live compatibility appendix** (addressable contract + retained API/facade rows), not git-history provenance. |
| 5 | BLOCKER doc-12 not bead-ready | **Accepted with per-bullet rulings.** custom-element: already resolved (delta #1, 2026-07-12). Existence conflicts: the blessed table WINS on what exists (spread/element/->react stay wave-2; guide/rewrite get WAVE-2 marks). Stage conflicts: 08 §2 wins on WHEN v1 items land (ui/html → Stage 1 per 08 — fix 12; ui.test → S1 — fix 11 W9; foreign callbacks/components + client-only → added to 12 S3 per 08; 08's portal mention qualified wave-2). `.react/*` stays v1 (blessed) with corrected consumer citation + call-shape spec listed as an S1 contract item; if the Stage-1 demand-bar audit finds no consumer it returns as a Mike delta. Authoritative name→stage/owner/fixture matrix added to 12. Leaf S1/S2 beads remain undispatalchable until findings 1–4 artifacts exist (epic + contract beads only) — **corrects the earlier "S1 could start overnight" read.** |
| 6 | MAJOR lifecycle labels | **Accepted (evidence-driven refinement, not a re-open).** Immediate emitted fact = 3-state with `:disconnected {:reason :unknown}`; Activity vs unmounted become qualified retroactive annotations (reconnect proves hide; explicit teardown proves unmount; GC inference, if kept, is best-effort/no-timestamp with bounded non-retaining tombstone). 03/04/07/08/README/rewrite/009-ripple updated to distinguish runtime state vs tool label vs historical inference. Flagged for Mike's morning awareness since 08's ratified wording said "four-state". |
| 7 | MAJOR Reagent tier gaps | **Accepted.** New `drafts/reagent-compat-boundary.md`: both nesting directions, frame propagation, supported plain-fn classes (doc-10 step-1 claim narrowed to match the checked-in `contextType` constraint), root ownership/teardown, SSR/HMR limits, named retained CI suites (new-UI conformance suite ≠ frozen-Reagent compat suite + smoke). The outward `defview`→React bridge: **RULED under Mike's 2026-07-12 "nothing gates on me, you decide" delegation — `->react` promoted to v1 (delta #2, lands S6 with the migration wave)**; the original wave-2 premise ("none in guide") was factually wrong (guide 02 uses it) and doc 10's per-subtree migration structurally requires an outward bridge. Likewise **`spread` promoted to v1 (delta #3)** — the rewrite's conversion architecture names it as the single dynamic-map conversion path (02 §2) and guides 02/07 teach it. `element` stays wave-2 (no v1 dependency; `ui/raw` covers the escape). |
| 8 | MAJOR local placement law | **Accepted; narrow law ruled.** Binding text: a `local` value MAY be read by same-view committed handlers (handlers read committed slots — local ephemera included; the guide's search-box seam is canonical and conforming). `local` is FORBIDDEN when the value needs cross-view observation, replay/persistence, schema or tool inspection, durable navigation semantics, or subscription-derived computation — those belong in app-db. Rewrite, 02 §3/§5, and guide 03 aligned to this one wording. |
| 9 | MAJOR spike overstatement | **Accepted.** All five gates relabeled "feasibility PASS"; unexecuted parts stay as named gates (S-4 root-manifest hydration + failed-root isolation; real sub-cache graft conformance; G-8 real-browser input matrix; G-1 rerun under the revised alternating-rounds estimator). Obsolete "provisional pending S-5" wording removed (predicate confirmed sufficient). |
| 10 | MINOR stale prose | **Accepted.** W7a `04 §5b` repaired; 09 lifecycle/provisional rows marked superseded (done below); README/08 four-state wording updated with F6; doc-12 volatile operator state replaced by semantic dependencies ("after the frame split merges"); 11 W7b's `j538f7.34` replaced with its semantic content. |
| — | Draft audit + verdict (b) | **Accepted.** Interim Spec-004 amendment is merge-ready (9/9 anchors verified) → bead filed for the mayor to dispatch now. Rewrite/006-amendment/selector drafts blocked on findings above, as codex says. |
| — | Verdict (a) | **Accepted.** No Stage-1/2 product beads until findings 1–5 artifacts land. Program epic + contract-reconciliation beads may file. |

**The 61 implementer questions** are coverage probes, not separate rulings; ownership
map: Q1–9, 17–22, 28–32 → rewrite grammar/conformance sections (+ compile-error roster,
an S1 contract item); Q10–16 → `jvm-tree-and-conversion-contract.md` (+ tonight's
custom-element ruling for Q16); Q23–27 → `root-identity-and-mount.md` (+ SSR boundary in
the tree contract for Q23); Q33–52 → the rewritten 03 §3 / 006 amendment (+ ViewCell and
ENSURE rows land as S2 contract items); Q53–61 → the doc-12 matrix, compat-boundary
draft, and stage profiles. The coverage check verifies every question resolves to a
named artifact or an explicitly-filed S1/S2 contract item.

## Still open (updated post-ratification + fable2 paper pass)

- ~~Refs policy~~ — **resolved** (02 §3: `:ref` reserved; object refs preferred; callback
  refs explicit `ui/raw-fn`; guide 03 corrected).
- ~~Presence syntax~~ — **resolved by ratification** (wrapper; `:timeout-ms`;
  phase-outside-boundary `:present`).
- The `ui.test/find`/`query` selector language still needs its one page before Stage 1
  (fable2 F2-5 added `query` to the contract; the selector grammar itself is unspecified).
- G-10's relative bundle baselines need the reproducible shared-chunk methodology
  written down before they can gate.
- ~~Controlled-input trigger predicate and probe-memo lifetime~~ — **confirmed by
  S-5/S-3** (2026-07-11): the predicate is sufficient as specified; the probe-memo
  lifetime is slice-scoped. No longer provisional. The remaining gates are the real
  sub-cache graft conformance and the G-8 real-browser input matrix (fable2
  F2-2/F2-3 provenance).
