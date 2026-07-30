# Hicasso — decisions (HD-001 … HD-021)

Every design decision for the Hicasso programme, resolved. Each record carries the
ruling, the decisive rationale, and the condition under which it reopens. These
were resolved under delegated authority (operator-overturnable, like every ruling
in this repo); the measured claims cite the studio/bench record. Companion pages:
[charter.md](charter.md), [architecture.md](architecture.md),
[validation.md](validation.md), [authoring.md](authoring.md).

---

## HD-001 — Name, namespace, alias

**Ruling.** The product is **Hicasso**; namespace `re-frame.hicasso`; artifact
`io.github.day8/re-frame2-hicasso`; conventional alias `h`. Single-c spelling.
**Rationale.** Hiccup + Picasso one letter apart; the wordplay carries the product
claim (single-line freehand drawing = one-pass interpretation; Cubism =
decomposition into data primitives). Verified unclaimed on Clojars/GitHub/npm.
Alias `h` avoids inheriting Freehand's `v` (whose surfaces are deleted on a win,
HD-018) and `ui`.
**Reopens** never — names are permanent after first publish.

## HD-002 — The sub-read mechanism: grouped default, collector challenger, scalar comparator

**Ruling.** Three tiers, one product:

1. **Scalar per-read hooks** (the raw UIx spine) are a **comparator arm only** —
   the measured floor, exempt from the product hook budget because they are the
   control, never the product. (They cannot be the product: N reads = N hooks
   violates HD-020's ≤2-hook budget on the census's seven-read archetype, and
   hook rules forbid conditional reads.)
2. **Grouped `use-subs` is the product default** — one fixed hook receiving the
   complete query collection before the body; query values may vary while hook
   count and order stay fixed; the body reads the returned snapshot. This is the
   only budget-compliant hook-fixed product surface, and it is dogfooded **from
   P1 start** (its canonical spelling pre-declared; the dogfood screen is
   written in three renderings — collector, grouped, raw UIx — with the grouped
   rendering riding the comparator spine, not a third runtime).
3. **The ambient collector** — `(sub q)` as a plain tracked read anywhere in the
   body (conditionals, loops, helpers), one fixed runtime hook, edge-diff after
   the body — is the **challenger, ridden hardest in P1**: it replaces grouped
   as the product mechanism only by winning the per-read instrumentation against
   the standard bead's P0 number under the adjudication clauses below. If both
   product mechanisms fail their gates, the outcome is **null** — never a fourth
   read model.

**Adjudication clauses (pinned on the standard bead before P1 code):**
(a) a render/commit **ownership state machine** — how candidate reads survive a
winning render and disappear after an abandoned render, replay, or teardown; if
correctness requires the forbidden candidate ledger, the tripwire fires
immediately; (b) the **exact allowed edge-diff operation**, distinguished from
the ledger class that trips the kill rule; (c) **two pre-registered strategy
hypotheses**, each counted only by a benchmarked commit, never by tuning passes;
(d) the **survival metric**: steady-state allocation slope across 1/3/7/20 reads
after collector capacity is warm, plus zero retained per-occurrence objects
after commit/teardown.

**Authoring consequence, stated now**: the read-anywhere surface
(`(sub …)` in conditionals/helpers) ships only on a collector win. Under the
grouped default, conditional needs are met by conditional child boundaries and
conditionally-constructed query values at fixed sites — a genuinely different
authoring surface, which is exactly why grouped is dogfooded from day one rather
than discovered as a mid-clock rewrite. A collector loss promotes a *known,
already-scored* surface; the transition is an API choice made on evidence, never
"an internal swap."

**The index is shared by both product tiers** (grouped declares its edges;
collector records them); the scalar comparator does not use it.

**Rationale.** This refines the locked hook-fixed-default ruling to its only
self-consistent form: "hook-fixed" lands on grouped (scalar was never a viable
product), and the collector — the same mechanism class as the predecessor's
per-read ledger, the single largest measured killer — must win its place under
pre-registered, correctness-first adjudication. The collector is still ridden
hardest because conditional-reads-legal is the census's tier-1 shape and most of
the authoring differentiation vs raw UIx.
**Reopens** via its own adjudication — the P1 instrumentation is the decision
procedure.

## HD-003 — Hooks in view bodies

**Ruling.** One placement rule, taught not policed: **semantic application state
belongs in app-db; component mechanics (composition, measurement, focus,
animation, SDK handles) may use ordinary React hooks at the honest escape
hatch.** A `defview` body *is* an honest React function component — hooks
physically work, and an advanced author using one takes on React's hook rules
themselves. The taught tier-1 surface uses no hooks; the guide says so; there is
no lint police in v0, and "behaviors" (a predecessor product concept) is **not
v0 vocabulary** — host-edge React is the v0 answer for imperative mechanics,
with any richer host-ownership pattern deferred to product phase.
**Rationale.** Trust the programmer: an honest host model with one placement
rule beats both extremes (pretending hooks don't exist, or teaching them as
tier-1 and importing hook-rules confusion into every view). The census grounds
the app-db half; it is an app corpus, not evidence that library authors never
need local mechanics — so the escape hatch is legitimate, not shameful.
**Reopens** if dogfooding shows the placement rule confusing in practice.

## HD-004 — Interpreter accelerants are arm-scoped

**Ruling.** In the lean-React arm, only **codec-work caching** (parsed tag names,
prop-name conversion, cached stable component heads — same elements, same
behaviour, safe invalidation, no public template hints). **Template extraction
with data-encoded hole plans belongs to the PATCH arm** (with the sexp
equality-cutoff as its fallback tier). Any lean-arm optimization that wants node
references, subscription-addressed holes, or direct DOM writes *is* the
own-renderer strategy and must be classified as such.
**Rationale.** Extraction takes DOM ownership away from React; blurring that
boundary inside the lean arm recreates the two-owner seam that killed the GRAFT
family.
**Reopens** never as a blur; a new arm may be chartered explicitly.

## HD-005 — Evidence: a seam, not a subsystem

**Ruling.** v0 ships no evidence/manifest/registry subsystem. The index carries a
~3-line nil-checked evidence sink seam so dev tooling can attach later with no
redesign; detached cost is zero.
**Rationale.** Lovable slice first (the predecessors' tooling-first instinct
produced machinery before product); but the one thing that cannot be retrofitted
cheaply — a tap point on the dependency index — is three lines now.
**Reopens** at product phase, where the tooling story (explain-render, manifests)
is a stated goal.

## HD-006 — Memoization defaults

**Ruling.** No default `=`/argv memoization. React semantics stand (a child may
render with its parent); narrow updates come from boundary placement; `React.memo`
is an opt-in escape.
**Rationale.** Do not recreate Reagent's equality semantics from taste; every
default comparison is a cost every render pays.
**Reopens** only on keyed-row / broad witness evidence demanding it.

## HD-007 — Two arms, equal class

**Ruling.** P1 runs **two** kill-bounded Hicasso arms under the one product name:
lean-React (leading) and PATCH (own renderer, React at islands). PATCH is an
equal-class spike, not a contingency reserve.
**Rationale.** Lean-React is the smallest falsifiable bet but may ceiling at
UIx-parity; PATCH is the only family that can beat the frontier on bulk and
memory. Running both against one witness set is the only way the P2 ruling is
made on numbers rather than priors.
**Reopens** n/a — P2 consumes this decision.

## HD-008 — The composed donor arm is a stop-gate

**Ruling.** Before any API is designed, the composed donor arm — reagent-slim's
`:f>` function-component path plus the existing UIx `use-subscribe` spine, two
rungs (markup+reactivity; + frame-context hook and event-vector lowering) — must
clearly beat both Reagent paths and stay acceptably close to direct UIx on the
witness shapes. Failing that, the programme stops; adapters + sugar is the
recorded successful outcome.
**Rationale.** The central hypothesis is testable from parts already in the repo
for 1–3 days of work; designing an API before running it would repeat the
predecessors' pattern of building before measuring.
**Reopens** n/a — it is a gate, consumed when run.

## HD-009 — Ephemeral state: no `local`, ever

**Ruling.** No component-local reactive cell (`local`, ratom-equivalent, or
`useState` for app state) exists in Hicasso. In order: CSS for hover/focus;
platform-carried state (the top layer owns open/dismiss; resources/mutations own
async status; the controls kit owns drafts/revisions); host-private React state
at host edges for geometry/composition; app-db for everything semantically
meaningful. If dogfood evidence shows residual ceremony registering, the
pre-agreed *response class* is one-declaration sugar (a `defstate`-style helper
minting a parametric sub + a **named** setter event — never a generic `ui/set`);
its concrete shape — including whether a declared app-db tier is involved and
that tier's frame/persistence scope — stays **unfrozen until the evidence
exists**. v0 ships nothing and pre-commits to nothing beyond "sugar, not a
state system".
**Rationale.** The census is decisive — 85 idiomatic files, every classic hard
case, zero local cells — and most of Reagent's local-state demand was
manufactured by machinery Hicasso deletes structurally. A second reactive system
is the top item on the anti-regression fence. The tax is per-concern, not
per-instance.
**Reopens** only if dogfooders fail the preference test specifically on state
ceremony *after* the sugar ships. **Adversarial position, partially adopted**:
one peer review rejected the `useState` fence and any pre-designed tier;
adopted in part — the tier pre-commitment is withdrawn (unfrozen, above) and
there is no lint police (HD-003's placement rule is taught, not enforced); the
no-`local` core stands on the census evidence and the one-state pin. A v0
dogfood failure *on state ceremony* triggers the sugar iteration, not a raw
kill: HD-009 deliberately ships nothing in v0 while the controls kit that
carries drafts is also post-v0, so ceremony complaints in v0 are expected
signal, not verdicts.

## HD-010 — Theming; no native context API

**Ruling.** Hicasso ships **no context abstraction of its own**, and its native
theming uses none; the substrate keeps exactly one internal context (frame
identity). Ordinary React context remains available to advanced authors at the
HD-003 escape hatch (a compound-component contract, a provider an ecosystem
library expects) — this ruling bans a *Hicasso* context API and context-based
*theming*, not React itself. Theming is three layers: design
tokens as CSS custom properties (the cascade is the scoping mechanism; a theme
switch is one attribute flip, zero React work); parts as data addresses with
theme as a pure, equality-testable tree transform merged
`base < app-theme < instance-props`; app-db + `sub` for the theme choice only.
Foreign providers a hosted library demands are declared through `defhost`.
Two laws bound the mechanism (law (a) and the structure-through-slots half of
(b) inherited from the predecessor's ruled theming decision,
`docs/design/freehand/decisions/D018-theming-and-parts.md`; the boot-static
part→class rule is a Hicasso strengthening): **(a) the owned-literal merge law** —
`:key`, `:ref`, controlled `:value`/`:checked`, and owned event handlers are
unoverridable by theme or parts; a theme can style a field, never rewrite its
controlled contract; **(b) the static-map law** — anything runtime-switchable
lives in CSS variables; part→class maps are boot-static per app; structural
replacement goes through children/slots, never through parts. The
"theme switch is zero React work" claim holds for the token layer; a change to a
part→class *map* is an app rebuild, by design.
**Rationale.** Context is a side-channel invisible to the data tree (killing
headless testing and SSR simplicity), costs a hook per consumer, re-renders all
consumers on change, and is a second dependency-injection channel. The platform
cascade and the data tree each do the job better on every axis the charter
measures; the predecessor library's real usage (global theme + per-instance
overrides) never needed subtree context. The two laws close the hole the
predecessor's ruling documented: an unrestricted part override could otherwise
clobber a controlled input's contract by styling.
**Reopens** if a witness demonstrates a theming need neither the cascade nor
instance props can express.

## HD-011 — The interop door

**Ruling.** No `[:> Component …]` form. One-line `defhost` with strong defaults
(shallow camelCase props, hiccup children → elements, functions pass through,
SSR placeholder), policy overrides on the declaration, and a migration codemod
for Reagent's `[:>]` sites. Identity-keyed auto-hosting of a raw JS component in
head position is held in reserve, gated on dogfood evidence and the concept
budget.
**Rationale.** A raw JS value in data position kills `.cljc` purity, structural
testing, and tooling identity at that node, and `[:>]`'s implicit conversions are
a documented support burden. The one-line declaration amortizes to zero while
keeping every payoff; the codemod removes the migration argument. The expensive
half of leaving Reagent was always `r/atom`, not `[:>]`.
**The one raw escape (adversarial review, adopted)**: `[:> Component props &
children]` is legal, lowering through the same foreign path with the same
default conversions, `.cljs`-only at that node, with reduced structural
identity — the honest hatch for the cases a static declaration cannot express
(runtime-selected components, `memo`/`lazy` values, render-prop-supplied
components, providers an ecosystem library hands you, one-off migration sites).
`defhost` remains the taught, policy-bearing, tooling-identified form; the
guide's rule is "declare what you use twice." Bare-head auto-hosting (a raw JS
value in head position with identity-keyed caching) stays **rejected** — one
sentinel, not two shortcuts.
**Reopens** n/a — the escape supersedes the earlier auto-hosting reserve.
**Overruled position, recorded**: one peer charter preferred a component
adapter *registry*; overruled for the one-declaration-one-identity grain.

## HD-012 — The bar, and UIx's role in it

**Ruling.** Ship bar: mount and bulk view-work **≤ 1.0× Reagent on the clock**,
like-for-like on re-frame2 subscriptions — the ship *number* is clock only,
matching the locked ruling. **Every bar and kill number is a browser number** —
real browser, `:advanced` build, the harness's binding method; JVM/Node figures
are diagnostic-only and never quotable against the bar (the record shows the
JVM ranking diverging from the browser truth). Fast *applications* are the
goal; SSR speed and test-lane speed are explicitly not what the bar measures. **Memory is first-class and co-instrumented but
governs through the kill rules, not the ship number**: K3 (per-boundary heap
worse than Reagent with no paper path down) and the UIx material-cost rule.
UIx is the mandatory co-instrumented comparator, wired into the **kill rules**
(material latency or memory cost against direct UIx without a commensurate
ergonomic win kills the candidate) but not a ship number. Architecture-kill
tripwire: bulk > 1.5× after two serious iterations.
**Rationale.** Reagent is the incumbent being replaced (the ship claim); UIx is
the efficiency frontier (the honesty check). Hardening memory into the ship
number would put the floor exactly at the paper target with zero margin and
contradict the locked two-number ruling; K3 plus the UIx rule already prevent a
memory disaster from shipping. Making UIx a hard ship number would demand
dominance the lean arm may structurally not have while the ergonomic win is
real.
**Reopens** at P2 with the P0 numbers on the table.

## HD-013 — Governance

**Ruling.** The bar, budgets, kill criteria, clock, and decider protocol live in
an **operator-owned standard bead** (the lbs3y pattern) created at boot. **The P2
fork ruling's decider is the operator**, per the locked ruling; one adversarial
and one creative review pass over the P0/P1 evidence are prepared and recorded
on the standard bead **to advise that ruling**, not to make it. If the operator
chooses to delegate the ruling, that delegation is granted explicitly on the
standard bead at the time — never asserted in a design document. **The
donor-gate stop/continue ruling (HD-008) is a delegated advisory ruling** — one
adversarial and one creative pass over the donor numbers against the published
P0 baseline, recorded on the standard bead, operator-overturnable: the lock
reserved the P2 *fork* for the operator, not the earlier evidence gates, and
the programme must run without pausing for operator input before P2.
**Rationale.** Both predecessor programmes carried prose gates and disposed of
them by ruling; beads survive, and governance is the one place the record says
never to soften. The advisory-review pattern is this repo's proven instrument;
the decider stays where the lock put it.
**Reopens** n/a.

## HD-014 — The clock

**Ruling.** Six focused weeks, starting at **the first Hicasso-arm commit (lean
or PATCH) that mounts the dogfood list+form screen**. P0 instruments, paper
work, and the composed donor arm are instruments and do **not** start it. The
donor-arm stop ruling (HD-008) is issued only against the published P0 baseline
table — the gate's judgment requires the Reagent-on-subs numbers to exist.
**Rationale.** Bounded exploration with a dignified stop; the clock-start needed
one unambiguous definition (three had accumulated), and the strictest sensible
one — a product arm mounting the dogfood — is it.
**Reopens** only by explicit operator extension — never silently.

## HD-015 — Timing

**Ruling.** The programme starts immediately. P0 instruments and the donor arm
are ordinary bench-lane work and do not contend with the release train's
operator actions; the earlier "sequence after the alpha" note is superseded by
the operator's start order.
**Rationale.** Direct instruction; and the contention it guarded against does not
exist for bench-lane work.
**Reopens** n/a.

## HD-016 — View invocation and keys

**Ruling.** In hiccup, `[view-var props-map]` is a **boundary child** — a React
element with `:key` carried in the props map. A plain function call
`(helper args)` inlines into the enclosing boundary: no boundary of its own, and
any `sub` it performs donates its read upward. No metadata keys; no second
calling convention. `defview` bodies take a single props map.
**Rationale.** One visible distinction (vector = boundary, call = inline) is the
minimum that makes re-render granularity legible; keys-in-props matches the
React host honestly and deletes Reagent's `^{:key}` folklore.
Details pinned with it: native-element keys are also `:key` in the props
position; a bare seq of boundary children requires keys (dev warning); the
`for`-lowering sugar is **not** v0; a plain function in head position is a loud
error, never a silent embedding.
**The component ABI (adversarial review, adopted — pinned by the keyed
insert/delete/reorder witness):** children are trailing hiccup forms in the
vector, delivered to a view body as `(:children props)` — a realized,
predictably-flattened vector; `:key` is extracted before props reach the view
(React's own contract — not visible to the body); `nil` and `false` children
render nothing, `true` is an error; nested/lazy sequences are realized once and
flattened one level; an existing React element is a legal child (pass-through);
the fragment spelling is `[:<> …]`, and a view may return `nil`, one root, or a
fragment; `:ref` is legal on native tags and `defhost`/`[:>]` crossings
(callback refs only) and is **not** a v0 surface on Hicasso views (use ids).
**Reopens** if the dogfood shows the vector/call distinction confusing — the
fallback is a lint, not a third convention. Note: helper-donated reads within
this model are collector-contingent (HD-002's stated consequence).

## HD-017 — Code residence and graduation

**Ruling.** Instrument arms live in the existing tracked bench/test trees —
**the bench/test measurement lane is explicitly carved out of the donor-tree
freeze** (those trees, e.g. `implementation/freehand/test/re_frame/freehand/bench/`,
are the programme's measurement apparatus; instrument-only merges there are
legal; donor `src/` stays frozen). Results publish to their beads and to
**`docs/design/hicasso/studio/`** (minted by the first P0 worker; the frozen
Freehand studio is never extended).
Runtime skeletons stay disposable (spike branch or the local `ai/` tree) until
the P2 ruling graduates exactly one arm into a tracked
`implementation/hicasso/` artefact (deps.edn, test tree, and CI lanes chartered
at that point, not before). The spike-01 index model **graduates into the
tracked bench/test tree at P1** (its six-law algebra becomes the index's unit
tests); until then it is local-only evidence. Any arm needing a new build id or
dev-http port touches top-level `implementation/shadow-cljs.edn` — a hot-zone
file: those dispatches are sequenced, never parallel.
**Rationale.** Nothing merges to main that isn't an instrument until the
programme has earned a product; creating the tracked artefact early is the
programme-theatre fence item; and every results claim needs a durable, tracked
home because the exploration tree is untracked.
**Reopens** n/a — P2 executes it.

## HD-018 — End state

**Ruling.** On a win (P2 "go" + v0 bar green + dogfood preference): the public
`re-frame.freehand` and `re-frame.ui` surfaces are **deleted** — no absorption
programme, no donor inventory ledger, no dual teaching; small proven
micro-mechanics may be copied with attribution in the source. On a loss: the
adapters stand, the donors keep their status quo, and Hicasso's spike code is
archived. Never three living stories.
**Rationale.** The predecessor's absorption programme consumed enormous effort
tracking 200+ ledger rows; the honest lesson is that migration-as-success-metric
is the failure mode.
**Reopens** only by operator ruling.

## HD-019 — The synchronous controlled-input door (v0 mechanics)

**Ruling.** On the lean-React arm: a controlled element's event-vector lowering
dispatches **synchronously inside the discrete browser event** (the
dispatch-sync-class drain), and the spine's store notification runs synchronously
so React commits the echo in the same turn; `flushSync` is the evidence-gated
last resort, never the default. The rejected/unchanged-model path leans on
React's own controlled restore (the value reasserts at end of the discrete
event). Resets are by **explicit caller revision, never value equality** (the
predecessor's ruled reset law, kept — `docs/design/freehand/decisions/D016-buffered-and-revision-controls.md`). The browser witnesses that prove the door
are the 100-cell grid rows: same-turn echo, mid-string caret, selection, IME
composition (`composing?`/keyCode-229), unchanged-model rejection, async
normalisation. On a PATCH back end the restore obligation transfers to the
renderer (architecture.md, hard gate).
**Rationale.** The harness's trap table is explicit: any design where the input
value round-trips through an external store must *name* its synchronous door.
This names it, and the first controlled-input commit cannot stall on an
undecided mechanism.
**Reopens** only if the witnesses fail on both mechanisms — which is K4.

## HD-020 — v0 host mechanics: frame plumbing, hook ledger, error boundary, SSR posture

**Ruling.** (a) **Frame plumbing**: each boundary reads the frame once via the
substrate's single internal context, then binds it ambiently for the render's
dynamic extent so inlined helpers and generated callbacks resolve it without
hooks. (b) **The hook ledger**: the ≤2 budget is fully consumed by the
subscription/epoch hook and the frame-context hook; boundary shells use callback
refs, never `useRef` — a third hook in the shell is a budget breach. (c) **Error
boundary**: the runtime ships one internal class-based boundary exposed as
`h/boundary` (`:fallback`/`:reset-key`/`:on-error`); it is the P1 witness's
"real error boundary". (d) **SSR is out of v0**, explicitly: `defview` bodies
are `.cljc`-compatible by construction (no JS in tier-1 forms), but no JVM/SSR
render path ships in v0; `defhost`'s SSR-placeholder default is declared policy
for later phases, inert in v0.
**Rationale.** Each is a decision a v0 implementer would otherwise have to make
ad hoc mid-spike; none is reversible for free once witnesses pin behaviour.
**Reopens** at product phase (SSR, richer boundary API) by ordinary ruling.

## HD-021 — The v0 execution contract: root, HMR, headless

**Ruling.** (a) **Headless rendering** covers hook-free tier-1 bodies through a
pure read resolver (structural render as data, sub reads overridable); bodies
using hooks and foreign regions are mounted-test territory — **no fake hook
dispatcher, ever**. (b) **One root operation** associates a DOM node, a frame,
and initial events, and returns an idempotent teardown; the semantics are
pinned now, the names stay unfrozen until the donor spike. (c) **HMR minimum**:
root, frame, and app-db survive a body swap; the changed view body is used; no
subscriptions leak; preserving hook-local state is optional. The
zero-leaked-subscription-refcounts assertion is the standing teardown witness.
**Rationale.** Root/HMR/headless are the execution contract every wave-1 commit
touches; leaving them to taste mid-spike is how boot ceremony and leak classes
creep in. The harness measured ~30 lines of boot ceremony per app as the
baseline to beat.
**Reopens** at product phase (public API naming, richer HMR) by ordinary ruling.
