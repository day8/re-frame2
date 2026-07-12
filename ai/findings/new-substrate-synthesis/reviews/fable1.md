# Adversarial review — the unified substrate synthesis (`new-substrate-synthesis/`)

**Written:** 2026-07-11 20:32 AUSEST · Reviewer: independent (Fable pass 1)
**Scope:** all 9 design docs + the 10-file `guide/`, checked against the three source
studies (`new-substrate-fable/`, `new-substrate-codex/`, `new-substrate-grok/`), the two
supporting analyses (`2026-07-11.new-substrate-codex-feedback.md`,
`2026-07-11.substrate-alternatives.md`), and ground truth: Spec 004, Spec 006 (incl. the
sub-override seam), Spec 011, Spec 002 §registrar, and `spine.cljs` concurrency history.

## Executive summary

The synthesis picked the right skeleton and, on the recorded divergences, picked the right
sides almost every time: split frame components per ruling, no cross-frame sub spelling,
placeholders canonical with `ui/event` as escape, conditional-`sub` legality, one joined
binding, demand bar, spec amendments as gated rulings. It is the right base to proceed
with. But it is weaker than it looks in three places. First, it is **forked at its
foundation**: the pull-pricing gate (03 §3) is presented as a live alternative while
I-3…I-6, G-3/G-4, the entire 04 cause taxonomy, and the Story override consultation all
presuppose push — if pull wins S-2, roughly a third of the suite is invalid and no pull
variant is specified. Second, the **flagship bet — handlers as data — has the most holes**:
dynamic handler expressions defeat the static story, `ui/event`/`ui/handler` in loops are
unaddressed, the bare-fn-in-loop workaround erodes the idiom, `:on-mount`/`:on-unmount`
semantics break under StrictMode as worded, and controlled-input dispatch synchrony (the
oldest re-frame view bug class) is gated (G-8) but never designed. Third, the suite
**leans on the Codex study for load-bearing detail by reference** (ownership walkthrough,
props ABI, site identity, refs policy) while understating R-2's real blast radius — it is
a Spec 004 rewrite, not a serializability-row amendment. None of this changes the verdict;
all of it should be fixed before Stage 0 fixes it for you at higher cost.

---

## 1. Completeness — what's missing for implementability

**F-1 · MAJOR · Handler-position expression classification is unspecified.** 02 §3's
decision table classifies by *written form*, but the grammar's own examples put
*expressions* in handler position: `(if in-cart? [:cart/remove id] [:cart/add id])`
(02 §1) and a prop-forwarded `{:on-click event}` (guide 04 §Forwarding). For non-literal
values the compiler cannot classify statically, so every such site needs runtime type
dispatch (vector vs map form vs `ui/event` object vs bare fn vs nil) — which is fine, but
it is nowhere stated, and it silently weakens the claims built on static classification:
04 §3's "Xray shows what a button does before it is clicked" and Story's
"full interaction surface as data" hold for literals and normalized branches only; for a
prop-valued handler the manifest can only say "dynamic". Specify: (a) which expression
forms are legal in `:on-*` position, (b) the runtime classification rule and its cost,
(c) how the manifest marks value-classified sites, (d) whether a *map* form
(`:prevent-default`) can arrive dynamically.

**F-2 · MAJOR · `ui.test` — the daily-driver surface — is the least-specified in the
suite.** 07 §2 uses bare `find-node` and `text`; guide 09 uses `ui.test/find` and
`ui.test/text`; guide 09 additionally uses `test-frame`, `simulate-input!`, `query`, and
`dispatch!` with no namespace or contract; guide 08 introduces `ui/render-to-tree` for
what 07/guide 09 call `ui.test/render`. There is no enumerated `ui.test` namespace
anywhere (compare Codex 09's full API table for `re-frame.ui`). For a suite whose "very
testable" goal is headline, the test API needs the same one-page treatment the handler
table got. Also unstated: tier-1 JVM tests require the app's events/subs used by the view
to be `.cljc` — a real authoring constraint guide 09 should say out loud.

**F-3 · MAJOR · Load-bearing detail lives only in the Codex study.** 03 opens by
declaring Codex `04-view-cell-reactivity.md` "the reference specification" and its
scenario walkthrough "normative for the implementation's fixtures". The same dependency
holds silently for: the props ABI encoding (keyword→JS property scheme, `:key`/`ref`/
`children` collision rules — Codex 03 §Props ABI; the synthesis never restates it), the
site-identity scheme under HMR (source anchor + structural path + generation — Codex 09
risk register; synthesis says only "compile-time site index + source anchor", I-8), the
refs policy (callback refs invoked pre-layout-publication — Codex 07; absent from the
synthesis entirely, even though guide 03's chart example uses `:ref set-node`), and the
commit-ordering algorithm (Codex 04 §Commit algorithm 8-step list). A findings-tier doc
from a parallel study is a fragile normative anchor; when this suite is promoted toward
`spec/`, these must be restated in-suite. Until then, implementers must read two suites
to build one artifact.

**F-4 · MAJOR · No error-id inventory.** The design introduces at least: dead-cell
dispatch, `dispatch-fn`-after-disconnect, frame-destroyed-under-mounted-view (03 §4),
re-entrant `flushSync` rejection (03 §3), unknown-view-id (`ui/view`, 02 §6), island
digest mismatch / hydration failure (06 §2), unregistered-event-id warning (02 §3), and
the full compile-error roster (04 §6). Only `:rf.error/no-frame-context` is named. Per
Spec 009 every error id needs a catalogue row (04 §5b acknowledges this for *trace*
shapes only). An error taxonomy table — id, payload shape, always-on vs dev — is required
before Stage 3 and is cheap to write now.

**F-5 · MINOR · Placeholder semantics are under-specified for the two hard members.**
`:rf.ui/form-data`: key type (string names vs keywords?), multi-value fields, file
inputs, number coercion — unstated (02 §3, guide 04). `:rf.ui/event`: it is a vector
containing a raw-event placeholder, so by 06 §4's rule it *counts as serializable data*,
but its payload cannot meaningfully replay from the resumability queue (the event object
is dead by drain time) — the resumability report needs to classify `:rf.ui/event` sites
as non-resumable, and nothing says so. Also unstated: whether placeholders splice only at
top level of the vector or nested (e.g. inside a map argument).

**F-6 · MINOR · The `:catch` option's shape and recovery semantics are missing.** 02 §1
says `:catch` is "an event vector dispatched on subtree throw **and** a `:fallback` view
rendered" — but `:fallback` is not in the closed options list, so is `:catch` a map? And
the Codex design it supersedes had `:reset-key` (Codex 09 §Namespace surface) — the
synthesis drops any story for *recovering* a failed subtree (React boundaries need a
state reset or key change to retry). Error-recovery UX is a one-paragraph fix; today it
is zero paragraphs.

**F-7 · MINOR · Dual frame-config surfaces.** 02 §6 gives roots "frame config in the
frame-root/frame-provider shapes" as *root opts* while the same configuration exists as
template components (`[ui/frame-root {...} ...]`, used everywhere in the guide). Two
places to say the same thing violates the suite's own collapse-surface rule; pick one
(the template component, presumably) and make root opts host-mechanics only.

**F-8 · MINOR · Island identity is an unresolved either/or.** 06 §2: "the payload
carries the stable root id **or** binds by mount position". Those are different
protocols with different failure modes (duplicate frame ids on one page — legal under
ENSURE semantics? two `frame-root {:id :shop}` islands would *share* a frame — guide 05's
"mount one app N times" implies distinct ids but nothing rejects duplicates, and hydration
binding breaks silently under them). Pick the binding rule; specify duplicate-id behavior.

**F-9 · MINOR · Queued events against a failed island.** 06 §2 specifies failure
isolation; 06 §4 specifies the queue; nothing specifies their intersection — an island
that fails digest validation and renders client-fresh has a queue of interactions
resolved against server DOM. Drop them? Replay into the fresh frame? Either is defensible;
silence is not.

## 2. Correctness problems

**F-10 · MAJOR · Controlled inputs vs the async event queue — the mechanism is missing.**
The canonical product-state input is
`[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]`
(guide 03 §Local state). That is a controlled input whose value round-trips through
dispatch → queue → epoch drain → cell notify → React render. React controlled inputs are
only glitch-free when the value update is applied synchronously (or at least
before-paint-in-the-same-discrete-event); an async drain produces the classic
caret-jump / dropped-character / IME-breakage class — the exact reason re-frame v1 apps
learned to `dispatch-sync` on input. G-8 gates the outcome ("IME/caret correctness
first") but no doc specifies the mechanism: are input-event dispatches drained
synchronously within the DOM event? Is the microtask alignment (03 §3) sufficient before
React's discrete-event re-render? This needs a designed answer, not a benchmark hope —
it interacts with I-6 (no time-based batching) and with delegation (S-4), where the
delegated listener would own the synchrony decision.

**F-11 · MAJOR · `:on-mount`/`:on-unmount` are broken under StrictMode as worded.**
02 §1: dispatched "at first connected commit / at disconnect". StrictMode dev runs
connect → disconnect → reconnect. Per the wording, `:on-unmount` fires at the simulated
disconnect (every disconnect) but `:on-mount` does not fire again (not "first") — dev
apps end the StrictMode cycle having observed mount, unmount, and no re-mount: state
seeded by `:on-mount` and torn down by `:on-unmount` is net-missing. Activity has the
same question: hide dispatches `:on-unmount` ("at disconnect") for a view that is not
unmounting — an `[:analytics/modal-closed]` fires on tab-hide. Ownership idempotence
(03 §4) does not rescue event dispatch, which is not idempotent. Specify a symmetric
transition rule (e.g. dispatch on every connect/disconnect pair, StrictMode replay
accepted as a dev artifact and excluded from G-7 comparison; or dedupe both directions
per instance generation) and say what Activity hide/reveal does. This is a gap the
synthesis *introduced* — Codex B2 proposed the feature without the StrictMode analysis,
and the synthesis adopted it verbatim.

**F-12 · MAJOR · First-mount probe fan-out recomputes shared derivation chains N times.**
probe-sub "places no zero-owner node in the global cache" (03 §3; Codex 04 §Probe).
Acquisition happens only at layout commit, which runs *after the whole render pass*. So
100 sibling rows first-mounting, each probing `[:orders/by-id id]`, recompute the shared
layer-2 parents once *per probe* — O(rows × chain) where today's render-phase-caching
adapters pay O(chain + rows). Codex's risk register names "probe computes twice on first
mount"; the sibling multiplication is worse than twice and is exactly the shape of a big
list mount. The mitigation space (a render-pass-scoped pure memo table; epoch-bounded
zero-owner cache entries) trades against the protocol's no-cache-pollution brag and needs
a decision, or at minimum a named fixture in S-2/S-3 and a row in the risk register.

**F-13 · MAJOR · The Story override seam has no carriage path through the port.**
03 §3 requires `probe-sub` and `acquire-sub!` to "consult the override resolution hook
*identically*" — good, it closes Codex gap A2. But the port signatures are
`(probe-sub frame-id query-v)` / `(acquire-sub! frame-id query-v owner on-change)`, and
per Spec 006 §The sub-override subscribe seam, overrides are carried by **React context**
(they must survive into a descendant's deferred render; a dynamic var does not). Context
is readable during render (probe: fine, via the capture) but *not* in a layout effect
(acquire: runs post-render). So the override map must be captured at render and passed to
commit-time acquire — the port needs an override (or capture-context) parameter, or the
contract must state the capture carries it. As specified, the seam the synthesis
advertises closing cannot be implemented against its own signatures.

**F-14 · MAJOR · The pull fallback is not actually compatible with the rest of the
suite** (see also F-20). Under epoch-versioned pull: every reactive view's snapshot bumps
per epoch, so G-4 ("`rf=` results ⇒ zero revisions, zero renders") fails *by
construction*; G-3's "one notification per epoch" is vacuous; I-3/I-5/I-6 describe
machinery that no longer exists; 04 §2's `:subscription` cause ("site, query, version,
epoch, upstream cause") has no emission site — it would have to be *reconstructed*,
violating 04's own headline rule ("emitted at the cause site, never reconstructed",
rf2-6j0knp); and if dev keeps push for attribution while prod runs pull (the alternatives
doc's own suggestion, A2), G-7's dev/prod equivalence gate is structurally unsatisfiable
on ownership. 03 §3 and 05 §3 carry one-sentence conditionals; 04 and 07 carry none. The
honest options: (a) commit to push and demote S-2/G-13 to a falsification benchmark whose
*failure* triggers a redesign, or (b) specify the pull variant's debugging/testing
contract to decision grade. Option (a) matches how the suite is actually written.

**F-15 · MINOR · Loop rejection omits `ui/event`/`ui/handler` and admits the bare-fn
workaround.** 02 §2 rejects "`sub` / `lease` / event-vector handlers inside `for`
bodies". `ui/event` and `ui/handler` compile to committed-slot *sites* too (02 §3), so
they must be equally finite — presumably rejected, but unstated. Meanwhile a bare fn in a
loop body is legal (only vectors are named), so the path of least resistance for a list
row is `(let [d (ui/dispatch-fn)] (for [t ts] [:li {:on-click #(d [::open (:id t)])}]))`
— fully legal, opaque, non-serializable, per-item closures: it defeats the data-handler
idiom more cheaply than the sanctioned extract-a-child-view fix. Either extend the loop
diagnostic to nudge bare fns too, or accept openly that S-4 delegation is the real fix
and raise its priority.

**F-16 · MINOR · Non-delegation branch reintroduces a dual-implementation drift class.**
06 §4: placeholder resolution is "one implementation shared with the live dispatcher
(and if the delegation spike lands … literally the same mechanism)". If S-4 *fails*
(08 §4 risk: "revert to per-element props; never both mechanisms in prod"), the ~1 KB
bootstrap and the per-element live path are two implementations of placeholder
resolution and dispatch ordering — exactly the two-emitters-one-rule-table problem the
suite kills elsewhere (06 §1). The risk register should carry this: delegation failure
does not cost "no regret" (08 §1 S-4), it costs a standing parity obligation between
bootstrap and live dispatch.

**F-17 · MINOR · Lease release on Activity hide makes reveal expensive and is
unreconciled with the resource layer.** 03 §4/§7: hide releases leases (last-owner-out
lets the resource wind down); reveal reacquires → potential full refetch of everything a
hidden tab showed. That may be the right posture, but it partially defeats Activity's
purpose (cheap re-show), and the retention policy that would make it cheap
("fresh caches don't refetch" appears only in the hydration path, 03 §7) is unspecified
for reveal. One paragraph on resource-layer retention vs lease-count-zero is needed.

**F-18 · MINOR · Wrong model in guide 04: "the frame's registrar".** Guide 04
§Safety nets: "Dev builds check every data handler's event id against the frame's
registrar". Spec 002 §325/§336: the registrar is **process-global**; frames isolate
state, not behaviour. A guide that will become docs should not teach a per-frame
registrar. (Also: render-time registrar checks will false-positive under lazy-loaded
modules that register events after first render — worth a note on timing.)

**F-19 · POLISH · One-revision-per-view is sound — record why.** I hunted the joined
binding + conditional reads interaction and could not break it: per-component
`useSyncExternalStore` tear checks cover mid-pass epoch movement for *watched* sites; the
commit reconciler's evidence comparison covers *unwatched-new* sites; dropped-site
changes cause at most a harmless extra render; disconnected cells refuse dirty marks and
correct on reconnect (03 §3–4, Codex 04 §Correctness scenarios). The suite should state
the two-guard argument (React's snapshot check + commit evidence) explicitly — it is the
best answer to "why is one integer enough" and currently lives only in Codex prose.

## 3. Internal consistency

**F-20 · MAJOR · The pull gate contradicts the invariants it sits under** — detailed in
F-14. The suite reads as a committed push design with a disclaimer bolted onto two of
eight docs. Consistency requires the disclaimer to appear in 01 (I-3…I-6 are
"subject to S-2"), 04 (cause taxonomy), and 07 (G-3/G-4) — or, better, to disappear into
a falsification-only framing.

**F-21 · MINOR · `ui/render-to-tree` vs `ui.test/render`; `find-node` vs `ui.test/find`.**
Guide 08 §Rendering a page uses `ui/render-to-tree` with the annotation "(tests)"; 07 §2
and guide 09 use `ui.test/render` for the same operation. 07 §2 asserts with `find-node`
/ `text`; guide 09 with `ui.test/find` / `ui.test/text`. Same function, three spellings
across three docs (rolled up into F-2's fix).

**F-22 · MINOR · README's inheritance table claims A7 editor diagnostics; nothing
downstream carries it.** README §Division of inheritance takes "editor diagnostics +
generative parity (A7)" from the alternatives doc; generative parity landed (07 §4) but
editor diagnostics (clj-kondo/LSP layer) appear in no stage, spike, or demand-bar row in
08. Either add the wave-2 row or fix the README claim.

**F-23 · MINOR · `=` vs `rf=` for effect deps.** 02 §5 comment: "value deps, = compared";
guide 03: "VALUE deps, compared by ="; 03 §6: "value deps compared by `rf=`". `rf=` is a
defined spec relation (Spec 006 §rf=); pick it and say it once.

**F-24 · MINOR · S-2 vs G-13 — two decision points for one question.** 08 §1 has S-2
(Stage 0) "pick the winner"; 07 §5 G-13 runs "before Stage 2 hardening"; 08 §2 Stage 2
gate says "G-13 decided". If S-2 decides at Stage 0, G-13 is a re-run confirmation; if
the decision can slip to Stage 2, the ownership protocol is being built while its fate is
open. State which.

**F-25 · POLISH · `:key` double duty.** `:key` is list identity (02 §2 grammar,
guide 02) while `:rf.ui/key` is the keyboard-key placeholder (02 §3, guide 04). Same
word, two meanings, adjacent contexts (`{:key … :on-keydown [:editor/key :rf.ui/key]}`).
Also unstated: `:key` is presumably reserved on internal-view props maps (it feeds
React's key slot per Codex 03) — the synthesis never says an app prop named `:key` is
illegal.

**F-26 · POLISH · Kernel roster presupposes delegation.** 05 §1 defines the ≤4 KB kernel
as "cell + **delegation/dispatcher** + dynamic-prop converter + frame context" while
delegation is pending S-4 (R-5). If S-4 fails, the kernel roster and maybe the budget
change; word it conditionally.

## 4. Design judgment — challenging the big bets

**F-27 · Placeholders-canonical: right call, and it stands without islands.** The
serializability payoff is the marketed reason, but the load-bearing reasons are memo
honesty, static inspection, and JVM-testability — all of which survive even if
resumability (F-29) is deferred. The closed five-member vocabulary will be pressured
immediately by pointer/drag payloads (`:rf.ui/coords` etc.); the demand bar is the right
governor. Keep.

**F-28 · Bare fns legal: right call, softly held.** The `local` seam (guide 03
search-box) needs them constantly; Codex-strict rejection would wrap every local setter
in ceremony. Note the semantics are actually benign (a bare fn attached at commit closes
over the committed render's values — the stale-closure risk the taxonomy guards against
is about *identity across commits*, not phase), which the suite never says; saying it
would de-mystify R-4. Keep, with F-15's loop nudge.

**F-29 · MAJOR · Resumability should be demand-gated, not core.** Islands-as-frames
(identity, per-island payloads, failure isolation, static-island inertness — 06 §2) is
cheap and earned: it falls out of frames and closes real bug classes (rf2-4i115b). The
**pre-hydration queue-and-replay machinery** (06 §4: bootstrap, side tables, per-island
resumability reports, replay ordering) is a different animal: it has the F-5/F-9/F-16
open semantics, a standing bootstrap↔live parity obligation, and — decisive under the
suite's own decision rule (01 §Decision rule: "a named consumer in the repo's examples,
tools, or guide fixtures") — **no consumer**. No repo example needs interactions captured
before hydration; it is a headline for a marketing page, not a bug class. The suite
applies the demand bar to `ui/portal` but exempts its own favorite feature — exactly what
08 §3 says it won't do ("no exemptions for ideas this spec is fond of"). Recommend: keep
the *serializability property* (it's free — vectors are data), emit the resumability
report (cheap, static), and move the bootstrap/queue to a wave-2 ruling with a named
consumer as its gate.

**F-30 · One joined binding: right.** Three-study convergence plus Codex's granularity
argument (N leases / one snapshot / one notification) is sound, and F-19 confirms the
tear story holds. Per-site bindings would resurrect the per-hook scaffolding tax
documented in `spine.cljs` (the rf2-sqhjtu/naz09e/879fe lineage).

**F-31 · Memo-by-default on `rf=`: right, with one honesty gap.** The identity pipeline
(05 §2) handles the produced-value side; the remaining worst case — a parent that
*rebuilds* a large equal collection in its own body per render — walks the structure in
every child comparator, and the only lever is per-view `:memo false`. Dev diagnostics
(04 §2's equal-but-fresh note) are the right mitigation; a per-prop opt-out would be
surface creep. Accept. But guide 07's "the entire manual-memoization folklore is deleted"
overclaims: dynamic children defeat memo exactly like fn props (the codex-feedback doc
conceded this class has "no design remedy"), and guide 07 §The little you do lists fn
props but not children. Add the row.

**F-32 · Loops restriction: right, contingent on F-15.** Finite sites is the real
constraint; extract-a-keyed-child is the right factoring and the guide teaches it well
(guide 03 §Subscriptions). The restriction is only stable if the bare-fn workaround is
also nudged.

**F-33 · React-as-host: right; the standing toy direct-DOM emitter is over-commitment.**
Keeping the A3 option *tested* ("a toy direct-DOM emitter kept green in CI", 08 §1) means
maintaining a second reconciler-shaped artifact forever against every parity-fixture
change — real recurring cost for an option with no consumer and no exercise date. A
cheaper reservation achieves the same: an AST-shape gate asserting the IR still carries
edit-list-sufficient information (static/dynamic split, slot paths), plus one archived
spike. Downgrade the standing invariant to that, or give the toy emitter a named review
date.

**F-34 · `lease` in v1: earned.** Named consumers exist (dashboard tiles, hover cards;
today's adapters ship `use-resource-lease`), and re-frame2's resource layer already
exists — omitting it would regress parity. Keep, but it is the first candidate to slip a
stage if Stage 4 crowds.

**F-35 · Frame-resident `local` reservation: right and cheap.** Making `local` a
substrate form so an A5 implementation can slot behind the name later (03 §5) costs
nothing now and preserves the time-travel-fidelity option. Correct application of the
reserve-don't-build rule.

**F-36 · MINOR · HMR lands too late in the delivery plan.** Guide 01 sells hot reload as
"the default development loop"; 08 §2 defers "full SSR/hydration/HMR" to Stage 4 while
Stage 3's exit test is that the counter/dashboard "feel complete". A dev loop without HMR
does not feel complete; pull HMR-same-signature-preserve into Stage 2/3 (the registrar
hot-swap path, 04 §5, is already Stage 3).

## 5. Over-engineering audit

Mechanisms with a named consumer/bug-class — earned, no action: probe/acquire (spine.cljs
lineage: rf2-sqhjtu, rf2-879fe, rf2-naz09e, rf2-geq98x), compiled prop conversion
(rf2-0ej0b5 keyword-CSS), AST-closed escaping (rf2-4i115b), `:epoch-restore` cause
(rf2-6j0knp), split frame components (rf2-nyea0r), capture-frame hold (rf2-y6dz8t),
one-catalogue trace rows (rf2-cs0kd1), G-12 dependency gate (rf2-0ej0b5 lesson),
demand-bar audit itself.

Unearned or over-committed:

- **F-37 · MAJOR** — resumability bootstrap/queue: no named consumer (see F-29).
- **F-38 · MINOR** — standing toy direct-DOM emitter in CI: priced option maintained at
  a recurring cost with no exercise trigger (see F-33).
- **F-39 · MINOR** — `ui/portal`, `ui/element`, `ui/view`, `ui/spread`, `->react`,
  `data/render`: already flagged vulnerable by 08 §3 — good; the audit table itself
  ("name → consumer | wave-2") does not exist yet, and Stage 1 starts building surface.
  Produce the table *before* Stage 1, not before Stage 5.
- **F-40 · POLISH** — the dev heatmap overlay (04 §2) has no consumer commitment on the
  Xray side (04 §5b prioritizes it fifth); fine as staged, but it should not count as a
  v1 emit obligation.

Under-engineering (hand-waves where Codex specified properly):

- **F-41 · MAJOR** — the suite's 03 states the ownership *contract* but not the commit
  ordering, generation validation, or reconnect-capture rules (Codex 04 §Commit
  algorithm, §Connection/death, §Hot reload) — incorporated only by reference (see F-3).
- **F-42 · MINOR** — dev full-hook-skeleton is asserted (I-15) without Codex 04's
  rationale detail (inert unused operations; why the skeleton is fixed); G-7's wording
  should also note StrictMode is dev-only, so the "dev vs prod under StrictMode" cell is
  vacuous — equivalence there means "dev-with-StrictMode settles to the same committed
  DOM/ownership as prod-without", which is what the fixtures should actually assert.
- **F-43 · MINOR** — mixed `local` set! + dispatch in one `ui/event` body: two update
  sources (host state + external store) in one discrete event; whether they coalesce
  into one commit is host-batching-dependent and unstated (G-8 gates the pure-dispatch
  path only).

## 6. Goals the suite should have (each judged)

- **F-44 · Compile-time budget / build speed — adopt (earned).** The compiler is the
  product: normalized AST, two emitters, manifests, generative corpora. Nothing in 01/05/07
  budgets macroexpansion latency, incremental rebuild, or REPL redefinition (the Prism
  cross-review's "eval story" gap, `09-prism-cross-review.md` divergence 2, was never
  answered). One gate (e.g. `defview` expansion p95; watch-loop delta on the dashboard
  fixture) and one REPL paragraph. Cheap, and it protects the "guide examples are
  fixtures" policy from making CI unaffordable.
- **F-45 · AI-agent authoring ergonomics — adopt (earned, this repo).** The spec tree is
  explicitly AI-targeted; the suite's didactic errors and manifests are accidentally
  excellent for agents but never framed as a goal: machine-readable diagnostics (stable
  site ids in build output — already promised in 04 §6), manifest reverse indexes as an
  agent query surface, and the A7 editor layer (claimed in README, dropped in 08 — F-22)
  are the concrete deliverables. One goals bullet in 01 + the A7 wave-2 row.
- **F-46 · Accessibility posture — adopt a posture, not a subsystem (earned at one
  paragraph).** The substrate makes a11y-relevant choices: delegation (S-4) must preserve
  focus/keyboard semantics; queued replay (if kept) fires events after focus moved;
  `client-only` fallbacks swap in one commit (focus loss); ARIA props must pass the
  conversion table. A stated posture ("semantics pass through; the substrate never eats
  focus; delegation spike includes a keyboard/focus matrix") is cheap. A lint suite would
  be gold-plating — agree with the codex-feedback doc's exclusion.
- **F-47 · Error-recovery UX — adopt (earned, folds into F-6).** `:catch` making failure
  an app-db fact is the right instinct; without reset semantics it is half a feature.
- **F-48 · Incremental adoption path — decline as a goal (pre-alpha).** 08 §6's
  co-mounting line suffices for the framework; but the *repo's own* migration (examples,
  testbeds, Story/Xray scenes, three adapters' guides) is unbudgeted work that Stage 5
  silently assumes — add it to the delivery plan as a workstream, parallel to 04 §5b's
  Xray delta table (which is the model for how to do this honestly).
- **Security posture — decline.** Spec 011's payload policy + the EDN-safe encoder cover
  the new surfaces (the interaction side table rides the same encoder); a threat-model
  doc now would be gold-plating.

## 7. Verdict

**Proceed on this base.** The synthesis is the strongest document set on the table: it
took the right pieces from each study (Codex's rigor, Facet's surface and data-handler
bet, Prism's economics and packaging), corrected all three studies' ruling drift
(frame grammar, cross-frame reads, loop-site gap, multi-island, amendment gating), and
its invariant-plus-gate discipline is exactly how this repo's spec culture works. The
failures found are concentrated, not diffuse: a foundation-level equivocation (push vs
pull), an under-specified flagship (the handler/interaction surface), a testing surface
that doesn't match its own guide, one feature that escaped the suite's own demand bar
(resumability), and a systematic lean on the Codex study for text that must become
first-party. All are fixable on paper, before Stage 0 spends anything.

### Top 5 changes before a Stage-0 prototype

1. **Resolve the push/pull posture (F-14/F-20/F-24).** Commit to push; reframe S-2/G-13
   as a falsification benchmark whose failure reopens the design — or write the pull
   variant's debugging/testing/ownership contract to equal depth. Do not start S-3
   (concurrency spike) while the suite is forked underneath it.
2. **Finish the interaction surface (F-1, F-10, F-11, F-15, F-5).** Specify dynamic
   handler classification, controlled-input dispatch synchrony, `:on-mount`/`:on-unmount`
   transition rules under StrictMode/Activity, loop legality for `ui/event`/`ui/handler`
   + the bare-fn nudge, and `:rf.ui/form-data`/`:rf.ui/event` semantics. This is the
   design's biggest bet; it currently has the most holes.
3. **Make the suite self-sufficient where it is load-bearing (F-3, F-13, F-12, F-41,
   F-4).** Restate the commit algorithm, site-identity scheme, props ABI, and refs policy
   in-suite; add the override-carriage parameter to the port; add the first-mount
   probe-fan-out mitigation (or fixture + risk row); write the error-id table. State
   R-2's honest scope: a Spec 004 rewrite (reg-view, forms, lanes, positional args — not
   one serializability row) with ripples into 002/009/Conventions.
4. **Specify `ui.test` as a first-class contract (F-2, F-21).** One namespace table
   (render / find / text / with-root / flush! / test-frame / dispatch! / simulate-input!),
   reconcile guide 08/09 naming, state the `.cljc` authoring constraint.
5. **Right-size SSR (F-29, F-8, F-9, F-16).** Keep islands-as-frames; demote the
   bootstrap/queue resumability machinery to a demand-gated wave-2 ruling; resolve island
   identity binding and duplicate-id semantics; record the bootstrap↔live parity
   obligation as a risk if the queue survives.
