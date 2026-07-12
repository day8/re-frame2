# Adversarial review — v2 (ratified) substrate synthesis (`new-substrate-synthesis/`)

**Written:** 2026-07-11 22:49:26 AUSEST · Reviewer: independent (Fable pass 2)
**Scope:** the REVISED + RATIFIED suite — README, docs 01–10, guide/ (10 files) — verified
against `reviews/fable1.md`, `reviews/codex1.md`, the 09 disposition log, the ratified
08 §5 table, and ground truth: Spec 004 (current law), Spec 006 (adapter contract,
sub-override seam, source-coord annotation), and React 19 public semantics. Mandate:
verify the revision, not re-litigate the ratified decisions.

## Executive summary

The v2 revision is real, not cosmetic: every architecture-level disposition claim I
spot-checked (push commitment, roots≠frames + root manifest, preflight ENSURE, four-state
lifecycle, observation targets, `:on-mount` removal, resumability demotion, JVM subset,
error boundary, handler law, presence, `ui/html`, HMR, migration doc) is actually landed
in the text, and the ratified R-numbering is used consistently — no stale old-numbering
citations survive anywhere. The suite's weak zone is now the **guide**: five places where
guide text still teaches v1 contracts the design docs corrected (nested prop-cause paths,
"no `#js` ever", the heatmap-as-shipped, restored-epoch attribution, a `query` fn outside
the `ui.test` contract) — two of which falsify explicit "the guide is corrected" claims
in 07 §2 / 09. The unreviewed v2 additions are mostly sound (the synchrony law is
implementable on public React 19; the lifecycle table, root manifest, JVM subset, and
`ui.test` table are coherent) but carry two genuine design gaps — the undefined
"controlled" predicate and the undefined "render pass" lifetime — plus one ratified-law
contradiction: the guide's only ref example uses a bare fn that R-4 explicitly excludes.
No blockers. All findings are paper-fixable without reopening any ratified decision.

---

## Findings

**F2-1 · MAJOR · The suite's only ref example contradicts ratified R-4, and no refs
contract exists to adjudicate it.** Guide 03 §Effects teaches
`[:canvas {:ref set-node}]` — a bare fn (`local`'s setter) in `:ref` position. R-4
(08 §5) and I-9 (01) both say bare fns are legal **only** in known native event
properties — "not refs, not arbitrary fn-valued props" — and the 02 §3 decision table has
no row for refs at all. Meanwhile 09's Still-open list says the refs policy is
"inherited from Codex 07 by reference," i.e. the one construct the guide's canonical
effect example depends on has no in-suite contract and its only spelling is one the
ratified law reads as excluding. This is not a re-litigation: either refs get their own
explicit row (a `:ref`-specific rule, e.g. a compiled ref form or a sanctioned bare-fn
carve-out with stated invocation phase) or the guide example must change. As written, a
conforming compiler rejects the guide's chart view. Fix before S-1 codegen freezes the
prop grammar. *(guide/03-state.md §Effects; 08 §5 R-4; 01 I-9; 02 §3; 09 §Still open.)*

**F2-2 · MAJOR · The synchrony law's trigger — "controlled" — is undefined.** 02 §3 keys
the sync door to "`:on-input`/`:on-change`/`:on-before-input` sites on **controlled**
DOM elements," and the 08 §4 risk row promises "the door is keyed to controlled-input
sites only" with a fixture asserting non-input dispatches still batch. But nothing
defines how a site is classified controlled: literal `:value`/`:checked` present is the
easy case; a `:value` arriving via `ui/spread` or a conditional/dynamic props position is
not classifiable at compile time, and the docs don't say whether classification is
static, runtime-per-commit, or per-event. Also unstated: whether the door covers
dispatches produced by `ui/event` bodies and bare-fn handlers at those sites (via
`dispatch-fn`), or only literal-vector handlers. The *mechanics* are fine — draining
inside the DOM handler and advancing the `useSyncExternalStore` snapshot before React's
discrete-event flush is implementable on public React 19 (uSES updates de-opt to sync
rendering; discrete updates flush before paint), and it does not contradict I-6 (the
epoch still closes once and notifies once — it just closes earlier than the microtask
alignment, which 03 §3 correctly labels the exception). The gap is the predicate, and
S-5's brief should carry it as an explicit question. *(02 §3, 03 §3, 08 §4 row 2, G-8.)*

**F2-3 · MAJOR · The render-pass-scoped probe memo table has an undefined lifetime under
exactly the concurrency regime the suite is about.** 03 §3: probes "share a
render-pass-scoped pure memo table … the table dies with the pass." React exposes no
public "pass" boundary: concurrent rendering time-slices, yields, restarts, and abandons
work, and an epoch can advance (an event drains) *between* slices of one logical pass —
at which point surviving memo entries are stale for the remainder of that pass. The
correctness rescue exists (the I-4 two-guard argument: uSES snapshot re-check + commit
evidence comparison catch the staleness and correct before paint) but the text never
connects it to the memo table, and "no cache pollution" is only true if "dies with the
pass" has an implementable definition (per-epoch? per-microtask? per-synchronous work
chunk?). S-3 does carry "pass-scoped probe memo" as spike scope and 08 §4 has the leak
fixture — good — but the paper contract should state the intended boundary and the
staleness-is-corrected argument so the spike tests a design rather than discovering one.
*(03 §3; 05 §3; 07 §3 fixture; 08 §4 row 3.)*

**F2-4 · MAJOR · Guide 06 teaches the prop-cause contract 04 §2 explicitly disclaims.**
Guide 06 shows `:cause {:kind :prop :path [:product :price]}` — a **nested** path — as
the default emission, while 04 §2's `:prop` row (a direct codex adoption: "bounded prop
precision") promises **changed top-level slots** only, with nested paths as an on-demand
dev diff view precisely because retained-prior-props deep diff "is not free." The guide
teaches the expensive contract the design refuses. Same-page compounding: guide 06 says
restore repaints "attribute to the *restored* epoch," which is the exact attribution
model 04 §2 corrects (`:epoch-restore` = restore **operation token** + target epoch;
never attributed to the old epoch record). Both are one-sentence fixes. *(guide/
06-debugging.md §Why did this view render; 04 §2 `:prop` and `:epoch-restore` rows.)*

**F2-5 · MAJOR · `query` in guide 09 falsifies 07 §2's own "naming is uniform
everywhere" claim.** Guide 09's Tier-3 example asserts
`(.-value (query root "input"))` — bare `query`, with a CSS-string selector, appears in
no `ui.test` contract row (07 §2 has `find`/`find-all` with structural selectors, and
`simulate!`; nothing named `query`, nothing taking a string selector). 07 §2 states "v1
drift … is gone; the guide is corrected," and the 09 disposition row for F-2/F-21 claims
the same — this is the one surviving counterexample, and it's in the flagship Tier-3
example. Either add a DOM-query fn to the contract table (Tier-3 needs one — that's a
real gap the example exposes: `find` is documented against structural *trees*, not
mounted DOM) or rewrite the example onto `simulate!`/`find`. *(guide/09-testing.md
Tier 3; 07 §2; 09 disposition F-2/F-21 row.)*

**F2-6 · MINOR · The front door still calls ratified rulings "open."** README line ~65:
"brand naming is open ruling R-3" — R-3 is ratified in the very table the README's own
status block cites as final (`re-frame.ui`, `day8/re-frame2-ui`, Facet internal-only).
README's doc table row for 08 says "open rulings R-1…R-7," and 08's own title line still
reads "…risks, open rulings" above a §5 headed "RATIFIED … (the final record)." Stale v1
framing contradicting the ratification on the highest-visibility page. *(README §Naming,
§Documents; 08 title.)*

**F2-7 · MINOR · Guide 02 keeps the exact `#js` contradiction codex flagged.** "**No
`#js`, no camelCase, ever**" (guide 02 §Templates) sits 45 lines above the guide's own
interop example `(ui/raw (SomeReactLib. #js {:prop 1}))`. The design doc got the scoped
wording (02 §2: "no `#js` on compiled DOM/internal paths"); the disposition row ("scoped
to compiled paths (02 §2)") claims the fix without the guide, where the original finding
pointed. Change "ever" to "on template paths — interop hands JS values through."
*(guide/02-views.md §Templates + §Interop; 09 disposition "no #js ever" row.)*

**F2-8 · MINOR · The guide sells the heatmap as a shipped overlay; 04 gates it behind an
IA review.** Guide 06 ("The **heatmap overlay** tints views…"), guide 02 ("the dev
heatmap points at it"), and guide 07 (twice) present the heatmap as an existing surface.
04 §5 (v2, per codex): new panels — explicitly including the heatmap — come **only after**
an information-architecture review; the v1 emit obligation is the schema, not panels.
Either the guide hedges ("the causes timeline — and the heatmap overlay, where enabled")
or 04 promotes the heatmap out of the gated list. As written the user manual promises
what the design doc declines to commit. *(guide/06 §Why did this view render, guide/02
§Props discipline, guide/07 §3 + §Measuring; 04 §5.)*

**F2-9 · MINOR · The 02 §3 decision table is structurally broken and the `ui/raw-fn` row
is orphaned.** The strict-lint paragraph is interposed between the table's bare-fn rows
and the final `| (ui/raw-fn f) | … |` row, so in rendered Markdown the raw-fn row is not
part of the table (it renders as a stray one-row fragment). This is the suite's normative
handler table; the lint paragraph belongs after the complete table. *(02 §3 decision
table.)*

**F2-10 · MINOR · The error taxonomy misses the JVM typed errors the suite promises
elsewhere, and one warning/error tier conflict.** 06 §1, 07 §2, and guide 09 all promise
a "typed error" for invoking a `local` setter (or expecting effects) in a Tier-1 JVM
render — no such id exists in 03 §11 (nothing in the table covers test/JVM-tier errors).
Separately, 03 §11 files `:rf.warning/render-phase-dispatch` as a dev *warning* while
04 §6 and guide 06 list render-time dispatch under compile-time *errors* and I-1 makes it
an invariant violation — presumably statically-provable sites error at build and dynamic
ones warn at dev runtime, but no doc says which tier owns which, and a warning for an
I-1 violation is arguably too soft. State the split. *(03 §11; 06 §1; 07 §2; 04 §6;
guide/09.)*

**F2-11 · MINOR · Ratified Budget row is silent on S-4.** B-lite funds S-1 and S-3 (with
S-2/S-5 as riders) and gates production Stage 1 on "both spikes pass," but 08 §1's spike
table lists **five** Stage-0 spikes — S-4 (dual host / hydration parity) has no budget
slot, no rider assignment, and no mention in the Stage-1 gate. Either S-4 is deferred
past the gate (say so) or it rides one of the funded harnesses (say which). This is an
internal-coherence gap in the ratified record itself, not a challenge to it. *(08 §1
spike table vs 08 §5 Budget row.)*

**F2-12 · MINOR · Two half-landed disposition claims around F-22 and the "Still open"
list.** (a) 01's AI-ergonomics goal cites "a wave-2 editor/kondo layer (08 §3)" but
08 §3's wave-2 candidate list (`ui/element`, `ui/view`, `ui/spread`, `ui/portal`,
`->react`, `data/render`) does not contain it — the exact README-claims-it/08-doesn't
mismatch F-22 flagged, one level down. (b) 09's Still-open list retains "Presence syntax
(`ui/presence` wrapper vs reserved nodes) — Stage 0 ruling input," which the ratified
table has since resolved ("**`ui/presence` wrapper, no reserved nodes**") — the log
wasn't updated post-ratification. (c) Completeness nit: fable F-7 (dual frame-config
surfaces) appears nowhere in the disposition log, though its fix did land (02 §6 makes
root opts host-mechanics-only, "frame wiring is the template's job"). *(01 §Secondary
goals; 08 §3; 09 §Still open; 02 §6.)*

**F2-13 · MINOR · `:facet`-free in the `ui.test` contract table.** 07 §2 describes
`find` selectors as "(tag, view id, `:facet`-free attr predicates)" — an unexplained
term that reads as residue of the internal codename R-3 rules must never surface in
public vocabulary. Whatever it means (probably "plain attr predicates"), the public
contract table can't carry it. *(07 §2 `find` row; 08 §5 R-3.)*

**F2-14 · MINOR · Pre-hydration fingerprint validation has no stated client-side
mechanism.** 06 §2's hydration order — "validate build digest **+ render fingerprint** →
install payloads → `hydrate-root`" — implies the client checks the render fingerprint
*before* hydrating. But the fingerprint is over the normalized structural tree, and the
browser bundle provably excludes the JVM/structural renderer (05 §1 absence roster,
G-12), so the client has nothing to compute a comparison fingerprint *with*
pre-hydration. Likely resolution: digest validates pre-hydration; fingerprint mismatch
manifests as React hydration mismatch mapped to `:rf.error/root-hydration-mismatch` —
but then the stated ordering is wrong. One sentence fixes it. *(06 §2; 05 §1; 03 §11.)*

**F2-15 · MINOR · `frame-root` placement legality is underspecified.** 03 §8 extracts
"unconditional `frame-root` plans **from the root form**," yet the compile-error list
("conditional, reactive, or list-generated sites") implies deeper unconditional placement
is legal, and guide 05's multi-frame example has two `frame-root`s under `[:body …]`.
Unstated: may `frame-root` appear inside a `defview` body (extraction would have to walk
the statically-resolved view graph), and what happens at a `ui/view`/dynamic boundary
where the walk can't see through? Preflight extraction is only sound over a closed static
region; name the region. *(03 §8; 02 §6; guide/05 §Multi-frame pages.)*

**F2-16 · MINOR · R-4's "not arbitrary fn-valued props" vs guide 02's "a raw fn in props
still works."** Guide 02 §Props discipline asserts a raw fn in (internal-view) props is
legal and merely identity-compared; guide 07 and 05 §1 likewise treat fn props as an
existing cost. R-4/I-9 read as prohibiting bare fns outside native event properties. The
reconcilable reading — a fn is a legal opaque *value* through internal props and is
classified only where it lands (runtime type dispatch at the eventual DOM/foreign site,
per 02 §3's dynamic rules) — is nowhere stated, and an internal view that *calls* a fn
prop during its own render (comparator, formatter) has no declared form at all. One
paragraph in 02 §3 scoping the law to *classification sites* would close it. Flagging
the ambiguity, not re-litigating the law. *(guide/02 §Props discipline; 08 §5 R-4; 01
I-9; 02 §3.)*

**F2-17 · MINOR · The mixed `local`+dispatch "one host render pass" claim is
order-dependent and self-contradicting.** 03 §3: "yields one host render pass: the sync
drain commits first, the host batches the rest." If the handler dispatches *before*
calling `set!`, the sync drain's forced flush renders mid-handler and the `set!` flushes
at discrete-event end — two passes; only the set!-then-dispatch order coalesces (pending
host state riding the forced sync flush). Either scope the claim to that order, or state
the intended guarantee ("at most two, both pre-paint") — S-5 should pin whichever.
*(03 §3 synchrony exception; fable-1 F-43 disposition.)*

**F2-18 · POLISH · Assorted.** (a) 03 §3 "the target examples **below**" — the examples
are above. (b) 05 §5 "patched React/React DOM 19.2.4+" reads as "we patch React"; say
"current patch release." (c) The filename `06-ssr-islands.md` preserves the term the doc
itself retires. (d) 03 §4's `:unmounted` row says "late callbacks no-op" two lines from
"`dispatch-fn` fails in every non-connected state" — say "no-op or fail per their
contract; `dispatch-fn` fails." (e) Guide 01's `reload!` re-runs `(rf/init! ui/adapter)`
every hot reload; `init!` idempotence under an installed adapter is never stated.
(f) Guide 07's headline "the entire manual-memoization folklore is deleted" is
contradicted by its own item 3 (hoist fns, narrow rebuilds — that *is* the folklore);
"deleted" → "almost entirely deleted" matches the honest row F-31 bought. (g) 08 §2's
stage list wasn't updated with the ratified riders (the Stage-3 RealWorld vertical page
lives only in the 08 §5 Proof-app row). (h) Migration doc: "render-phase side effects —
compile-errors here" is partly dev-runtime (see F2-10); and "the migrator tool itself is
a Stage-6 deliverable (08 §2)" — 08 §2 names no migrator; 08 §6 budgets the repo
migration (cite that instead).

## R-numbering and cross-reference integrity

**R-numbering: clean.** Every R-N citation in the suite conforms to the ratified 08 §5
table — 02 §3 cites R-4 (bare fns ✓), 03 §3's port heading cites R-2 (Spec 006 ✓, and its
"six invariants" enumeration really is six, matching R-2's citation of them), 03 §8 and
README cite R-7 (frame chain ✓), 08 §4 and README cite R-1/R-2 as the Spec-004/Spec-006
amendments (✓). No survivor of the v1 numbering exists ("R-2 = Spec 004" and "R-5 =
delegation" appear nowhere except inside `reviews/fable1.md` itself, and 09's "fable R-2
honest scope" row, which correctly names the *finding's* v1 label while mapping it to the
new R-1 — acceptable as history, though a "(v1 numbering)" annotation would prevent
confusion). Spike numbering is likewise clean: v1's S-4-as-delegation is fully retired
(08 §1 names the delegation spike as removed; v2's S-4 is dual-host, referenced nowhere
else ambiguously). G-13/G-14 references all resolve.

**Failures found:** the three stale "open ruling(s)" framings (F2-6); 01's pull-benchmark
citation "(07 §5 G-13, **08 §2**)" — 08 §2's stage list never mentions S-2/G-13 (S-2 is
08 §1; G-13's Stage-2 run exists only in 08 §4's risk row) — the pointer should be 08 §1;
and 01's editor/kondo "(08 §3)" pointing at a wave-2 list that lacks the row (F2-12a).

**Ground-truth spot-checks (all pass):** R-2's "closed public ten-fn adapter map" matches
Spec 006 §Normative contract (six required + three optional + one lifecycle = ten, port
correctly placed outside it); the observation-target design (resolve overrides once at
render via public context, commit acquires the capture) is compatible with Spec 006's
sub-override seam, whose carriage is indeed React context and whose honesty boundary
(overrides never satisfy sub assertions) 04 §5/07 §3 correctly preserve; 04 §4's claim
that `data-rf2-source-coord` is "today's attribute vocabulary" is true (Spec 006
§Source-coord annotation, mandatory + elision-gated); and R-1's interim broadening is
genuinely conformable by both today's hiccup (Spec 004's "render-tree is serialisable
data") and the compiled design (the serializable representation moves to the template;
emitted values go host-native) — the staged-merge story is coherent.

**Verified-clean residue sweeps:** no pull-fork conditionals outside the labeled
falsification framing (01/03/05/07/README all consistent); `:on-mount`/`:on-unmount`
survive only as removal teachings; placeholder vocabulary is three scalars everywhere
(`form-data`/`:rf.ui/event` appear only in removal notes); `:timeout-ms` uniform (no bare
`:timeout`); "island" survives only in the retirement note and the filename;
"byte-compatible" is gone (only "not byte-identical" negations + R-7's withdrawn-clause
note); `:on-keydown` appears only as the banned spelling; the four-state lifecycle,
preflight ENSURE (including R-7's current-adapters-two-pass vs new-substrate-preflight
split), JVM subset, error boundary, and presence refinements are consistent across
design docs and guide.

## Verdict

**Ready — with a one-day paper pass first.** The v2 revision did what the disposition
log says it did; the log does not materially lie (its two overclaims — "the guide is
corrected" on ui.test naming, and the guide-side `#js`/prop-cause/heatmap residue — are
localized guide-sync failures, not design regressions). The ratified table is internally
coherent except the S-4 budget silence (F2-11). Nothing found reopens a ratified
decision. For the spikes specifically: **S-1 can start now** once F2-1 (refs) is
resolved, since codegen freezes the prop grammar refs sit in; **S-3 can start now** with
F2-3 (pass lifetime) and F2-17 written into its brief as explicit questions, and F2-2
(controlled predicate) into S-5's rider brief; the **R-1 draft can proceed immediately**
— none of the findings touch the portability law or the staged-merge plan. The guide
residue (F2-4/5/7/8) should be fixed before the guide-examples-as-fixtures policy turns
those pages into CI, at which point two of them would fail against the contracts the
design docs actually state.
