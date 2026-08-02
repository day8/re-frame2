# Hicasso — decisions (HD-001 … HD-026)

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

## HD-007 — Two arms, equal class — **SUPERSEDED 2026-07-31**

> **Superseded by operator ruling, 2026-07-31.** Mike ruled that Hicasso is "an
> adaptor for React that is optimised for re-frame2, user ergonomics and
> performance" and dropped Arm 2. **There is one arm: lean-React, and it is the
> product line rather than a contender.** The decision below is superseded on
> **product direction, not on measurement** — PATCH *met* its hard gate
> (controlled-restore on the 100-cell grid in real Chromium, 920 tests / 5,743
> assertions / 0 failures) and its bench tree was retired afterwards
> (`rf2-m6if4`), not because it lost. Its rationale was never refuted; the
> operator chose not to own a renderer. Recorded on `rf2-2rtt6`.

**Ruling (superseded).** P1 runs **two** kill-bounded Hicasso arms under the one
product name: lean-React (leading) and PATCH (own renderer, React at islands).
PATCH is an equal-class spike, not a contingency reserve.
**Rationale.** Lean-React is the smallest falsifiable bet but may ceiling at
UIx-parity; PATCH is the only family that can beat the frontier on bulk and
memory. Running both against one witness set is the only way the P2 ruling is
made on numbers rather than priors.
**Reopens** n/a — superseded ahead of P2 by the ruling above.

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

**Ruling.** **`defhost` is the door, and the only form taught**: a one-line
declaration with strong defaults (shallow camelCase props, hiccup children →
elements, functions pass through, SSR placeholder), policy overrides on the
declaration, and a migration codemod for Reagent's `[:>]` sites. **`[:> Component
…]` is not that door** — it survives only as the explicitly secondary raw escape
recorded below, for the cases a static declaration cannot express. Identity-keyed
auto-hosting of a raw JS component in head position was held in reserve; the
escape supersedes that reserve, leaving bare-head auto-hosting rejected.
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
so React commits the echo in the same turn. `flushSync` is **never the general
default**. The lean-React controlled-**text** converge is the single
evidence-gated exception: it may flush the synchronous door's pending commit
before React's controlled restore so value and caret are correct when the
discrete event returns. That exception is exactly one audited call site —
`front.controlled/converge!`, reached from the element path
(`front.codec/native-element`), once per keystroke, on controlled text entry
only (an `input` of a caret-bearing type or a `textarea`; non-nil `:value`; no
authored `defaultValue`) and inert otherwise. A second call site, or any reach
into another element or event path, needs fresh evidence and its own ruling;
everywhere else "never the default" binds unchanged. The
rejected/unchanged-model path leans on React's own controlled restore for the
**value** (it reasserts at end of the discrete event) but **not** for the
**caret**, which the arm takes itself in the element path — `rf2-n3dxw`
measured the gap, `rf2-fki5d` closed it. Resets are by **explicit caller
revision, never value equality** (the
predecessor's ruled reset law, kept — `docs/design/freehand/decisions/D016-buffered-and-revision-controls.md`). The browser witnesses that prove the door
are the 100-cell grid rows: same-turn echo, mid-string caret, selection, IME
composition (`composing?`/keyCode-229), unchanged-model rejection, async
normalisation. On a PATCH back end the restore obligation transfers to the
renderer (architecture.md, hard gate).
**Rationale.** The harness's trap table is explicit: any design where the input
value round-trips through an external store must *name* its synchronous door.
This names it, and the first controlled-input commit cannot stall on an
undecided mechanism. The `flushSync` exception is named for the same reason —
an unnamed one is taste — and it is granted on measurement, not convenience.
The flush is not what makes the echo land: dispatch and the store notification
are already synchronous, so the value arrives without it. The flush exists to
make the per-instance last-rendered record current *before* React's
end-of-discrete-event controlled restore runs, because React's outer wrapper
flushes pending sync work only in the `finally` immediately preceding that
restore (verified against pinned React DOM 19.2.0), so a converge running
inside the handler would otherwise read the previous commit's record. Removing
only the `flushSync` reds four assertions with every caret reading `[5 5]`,
**including the ordinary accepted keystroke**: the record goes one render
stale, the converge writes the wrong value, and React's own restore repairs the
value while throwing the caret away. That an *ordinary* keystroke breaks is
what makes this an exception rather than an erosion (`rf2-ncn5p`, on the
evidence recorded by `rf2-fki5d`).
**Reopens** only if the witnesses fail on both mechanisms — which is K4. The
`flushSync` exception additionally reopens if its named live-tree invariant row
goes red — React mirroring controlled text into `defaultValue` is what makes
the node-held record valid, and it is not a public React guarantee — or if any
candidate second call site appears.

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

---

The four records below (HD-022 … HD-025) come from one adversarial design review
of the shipped predecessor's authoring surface, taken under the operator's
2026-07-31 direction to spend the programme's effort on *"adversarial design
reviews and performance improvement"* and to *"look at their data oriented
structure. Improve upon that."* Each one is a **deletion with a data
replacement**, because the unsolved problem the review found is
[K5](validation.md) — concept count — and K5 is a count. Each is demonstrated on
a census-real screen rather than argued; where the diff did not come out better,
the record says so.

## HD-022 — `:ref`'s vector value-space is reserved now, and refused loudly in v0

**Ruling.** `:ref` accepts **a function** — HD-003's escape hatch and HD-016's
callback-refs-only rule, both unchanged. A **vector** (`{:ref [registered-id
config]}`) is **RESERVED**: v0 refuses it with `:rf.error/hicasso-ref-vector-reserved`
rather than passing it to React as an opaque value. That is the whole ruling —
one refusal branch and one error id. **Not in scope, explicitly:** a behaviors
registry, a `:timing` option, a commands roster, or any host-ownership subsystem
in v0.
The refusal is taken **at the canonical ref slot**, not at the key `:ref`
(HD-023(c′)): the codec accepts string, symbol and namespaced prop spellings and
emits them all under React's one `ref`, so a check that reads `(:ref props)` is a
check `"ref"` and `:x/ref` walk past — carrying the opaque array to React, which
ignores it in silence, which is the ref-that-never-fires the reservation exists to
replace. The ref position's exclusion from callback lowering is taken on the same
slot, for the same reason and at the same cost: the walk has the value already.
**Rationale.** `:ref` is the one place Hicasso is planned to be *less*
data-oriented than the substrate it replaces: the predecessor's registered
behaviors keep the use site as data (`[v/behavior {:use autosize :target … :config
{…}}]`) and put the code in a registry, with `:config` refusing a callback, a
node, a ref or a host instance. Reserving the value-space costs one branch and
keeps `{:ref [::autosize {:max-rows 8}]}` reachable without minting a second
attribute name later. The census pays for the reservation and not for the
subsystem: **one `:ref` in 85 idiomatic files**, and charter.md defers the
component-library tier past v0.
**Why the later spelling is mechanically sound** (react.dev,
`react-dom/components/common`, verified 2026-07-31): React 19 added cleanup
functions for ref callbacks; the callback is called with the DOM node on attach
and its returned cleanup on detach; React calls the callback again whenever a
*different* callback is passed; StrictMode runs one extra development-only
setup+cleanup cycle. So a runtime-minted ref callback is a complete attach/detach
pair, with the node, in the commit phase, and no ref object or effect ever
appears in user code. Three obligations follow and must be recorded when the
later bead lands: the minted callback's identity must be **stable per site** or
React detaches and reattaches every render; connect/disconnect must be
**idempotent** because StrictMode adds a cycle; and timing is
**commit-before-paint only**, so the predecessor's `:passive` (after paint) has
no ref-callback equivalent.
**And the real gap, stated so nobody rediscovers it.** A ref callback fires on
**attach** and **detach**, never on **config change**. The predecessor's
`:update` — "only when the committed `:config` moves by `rf=`, receiving
`:prev-config` alongside" — has no equivalent without either changing the
callback identity (which detaches and reattaches the node's owner: wrong for a
map or chart instance) or spending an effect. The recommended later shape is
therefore **attach/detach only, config immutable for the connection's life, and
steady-state change routed through a command effect**, which is already data.
This limit is taught in the guide
([Interop](draft-guide/05-interop.md#the-reserved-vector-and-the-gap-it-does-not-close))
rather than left to be discovered.
**Reopens** when the component-library tier is chartered — the reservation exists
to make that reopening non-breaking.

## HD-023 — One attribute merge: a reserved `:&` key, owned-literal law unconditional

**Ruling.** Hicasso has **one** attribute merge, spelled as data in the attribute
map, with one unconditional law.

```clojure
[:input      {:& caller-attrs :value v :on-input ev}]
[:div        {:& base :class "own"}]
[date-picker {:& caller-attrs :selected d}]
```

(a) `:&` is a **reserved attribute key carrying a runtime map**. It is data, not a
call, so a forwarded remainder survives into a structural test and into tooling,
and it cannot collide with a real DOM attribute.
(b) **The law: the literal keys written in the map always win over `:&`.** This is
HD-010(a)'s owned-literal merge law applied to *every* merge rather than only under
theming.
(c) `:key` and `:ref` are **never taken from a `:&` map**. They address the element
the caller wrote, not the one it is forwarding onto; a remainder is about
attributes.
(c′) **Both halves of the law are enforced on the CANONICAL SLOT, never on the map
key.** The codec accepts a prop key written as a keyword, a string, a symbol or a
namespaced keyword, in kebab or in camel, and emits them all under one React name —
so `"key"`, `:x/key` and `'key` are all React's key, and `:onInput` and `:on-input`
are one handler. A deny written against the raw key denies one spelling and lets the
rest through: the structural slots become reachable from a remainder, and an owned
literal shares its slot with an alias that survived the merge as a second map entry,
leaving *which one React sees* to the order the props map happens to iterate in. An
unconditional law cannot be map-order-dependent, so the deny set is the two
structural slots seeded with the slot of every literal the element writes, and the
resolver is the codec's own emitted prop name (`canonical-slot`) — the thing a deny
asks is the thing the emitter will do. That resolver must be a pure function of the
key, which is why a string prop name does not share the prop-name cache with the
keyword of the same name.
(d) The **same key and the same law hold at a crossing** (a Hicasso view head, a
`defhost` head, `[:>]`). `:&` is merged *before* any conversion, and the conversion
that follows is the position's own — so a forwarded `:className` crosses under the
name it was written as. One rule covers both positions.
(e) A non-map at `:&` is `:rf.error/hicasso-merge-not-a-map`.
**Consequence:** two public concepts (the predecessor's `spread` and `spread-safe`)
become **zero**. An attribute key is not a concept in the K5 sense.

**Rationale.** The predecessor makes an author choose between three merge forms
depending on where the target is, and the penalty for choosing wrong is **silent**:
`spread-safe` "preserves the controlled-input door", `spread` "does not claim the
door", and neither is legal onto a declared foreign head (a spread canonicalises
`:className` into the `:class` slot, so the component never sees the prop it
reads). The cost is recorded twice as a wall with no error attached — "Dynamic map
on controlled input without `spread-safe` | forfeits door proof" — and re-frame.ui
carries the same split and admits it: "General (`ui/spread` base overrides) remains
the visible-cost escape and **still forfeits the sync door**." Making the law
unconditional deletes the whole class: the controlled-input door cannot be
forfeited by a merge at all, because a merge cannot reach an owned literal. The
case where a caller override *should* win is spelled by **not writing the literal**,
which is the honest way round — the dangerous default is the other one. Spelling
precedent: UIx already uses `:&` for exactly this in this repo
(`examples/substrates/uix/login/core.cljs:148`).
**Class composition needs no exception.** An element's own classes are written on
the **tag** (`[:input.form-control {:& caller}]`), which is not a literal attribute
key, so the shorthand merge composes them with whatever the remainder brought. A
literal `:class` still wins outright, because it is a literal.
**Demonstrated, not asserted.** The RealWorld article editor's four form fields
(`examples/real-apps/realworld_resources/article_editor.cljs:496-522`) are ported
both ways in `front/census_article_editor_cljs_test`, with the produced elements
and the dispatched intents asserted identical between the renderings. The
authoring result: four repeated attribute maps become one `field` helper and four
call sites, and — the part that matters — the helper does not have to defend
itself against what a caller forwards.
**Cost, stated.** `:&` is an addition to the codec this programme took from
reagent-slim, and the ruling that Hicasso authors no codec carries a caveat that
such additions be measured rather than assumed. Structurally this one is a single
`contains?` per attribute map, returning the map by identity when the key is
absent, so it allocates nothing on an element that does not use it — but its clock
cost is **unmeasured** and is named here rather than claimed away.
**Reopens** if a witness shows a merge the law cannot express without an escape.

## HD-024 — One callback form; the position selects the contract

**Ruling.** Hicasso ships **one** callback form — `h/fn` (spelling unfrozen) — and
it is **an ordinary function**. The contract comes from the **position**, because
the runtime already knows every position it walks:

| Position | Contract |
|---|---|
| a native `:on-*` prop | **event** — a returned VECTOR is dispatched; any other return is ignored |
| a `defhost` `:callbacks` entry | as **declared** (`:event` or `:handler`), never inferred from an `on*` name |
| any other walked prop position (a slot, a foreign render prop) | **render** — pure; the return is render output and is not dispatched, and dispatching from inside is `:rf.error/hicasso-dispatch-in-render-position`, **naming the position** |
| `:ref` | React's own: commit phase, node in, cleanup out. Excluded from lowering |
| anywhere Hicasso does not walk (a raw `#js` prop) | it is a plain function; it runs, and its return is ignored |

A Hicasso **view's** props map is not a position — it is data in transit, exactly
as an intent vector is. The view puts the value on an element and *that* position
lowers it.
**The event wrapper forwards every argument its invoker passes.** A native DOM
event position calls with one argument and that is the overwhelming case, but the
same wrapper serves a `defhost` `:callbacks` entry declared `:event`, and a foreign
component's live invoker calls with whatever its own contract says —
`(on-change value event)`, `(on-select item index)`. A wrapper fixed at one
argument would silently drop the rest, or raise an arity error naming nothing the
author wrote, against a form whose parameter vector is arbitrary by construction.
**`raw-fn` is NOT v0**, and costs nothing to omit: the codec already passes
functions to React by identity, deliberately, so that `React.memo` and every
downstream bail-out comparing handler identity keep working. The behaviour the
predecessor spells as a fourth roster form is the default here.

**Rationale.** The predecessor requires an author who cannot use a bare event
vector to pick from a roster of **four** forms with four different contracts —
`v/event` returns one vector or nil, `v/handler`'s "return is ignored",
`v/render-fn` is pure and runs during a foreign render, `v/raw-fn` passes identity
through — and then adds a **fifth** rule about *where the roster reaches*. Outside
that reach the failure is not even the library's: a roster carrier handed to a raw
`createElement` `#js` prop is a non-callable marker object, so the author gets the
engine's own `TypeError` — `props.onPing is not a function` — "naming nothing you
wrote". Making the one form an ordinary function deletes the fifth rule outright,
because there is nothing that can fail to be callable; making the position select
the contract deletes the other three, because the role is already declared exactly
once per crossing and Hicasso already walks the position. Four concepts collapse to
one: **half the K5 budget back**. The census supports the trade — keyboard-condition
handlers appear 3 times in 85 idiomatic files, `stopPropagation` 0 times, and
foreign React components 0 times, so the roster is priced for a component-library
tier charter.md defers past v0.
**Diagnostics name the position, never the form** — under one form the form can
never be the answer to "what did I get wrong?". The render-position refusal is
enforced by poisoning the ambient dispatch for the call's dynamic extent, so an
intent lowered inside the call and a direct dispatch land on the same error id.
**Fence.** This is a **runtime classification at positions the codec already
walks**, not a build-time pass over body forms. No compiler, no analyzer.
**Reopens** if the component-library tier finds a contract the four rows cannot
express — in which case it is a new row, not a new form.

## HD-025 — Presence: phase as a prop, and `::h/mounting` / `::h/unmounting` as data

**Ruling.** Two changes, both of them data.

**(1) `::h/mounting` and `::h/unmounting` are attribute OVERRIDE MAPS on a native
node.** The presence boundary merges them into that node's attributes while the
child is in that phase:

```clojure
(h/presence {:timeout-ms 300}
  (for [t (sub [:toasts/visible])]
    [:div.toast {:key (:id t)
                 ::h/unmounting {:class "toast toast--exit"
                                 :inert true :aria-hidden true}}
     (:message t)]))
```

The override wins over the node's own literals (that is what an override is), and
`:key` and `:ref` are never taken from it — the same law `:&` carries, through the
same canonical-slot filter (HD-023(c′)). Sharing that filter is the point rather
than a convenience: an override carrying `"key"` or `:x/key` survives a raw
`#{:key :ref}` dissoc and canonicalises onto React's key, which would remount the
very node presence exists to retain, at the one moment it must not be — mid-exit,
mid-animation. Retained key identity is therefore pinned by construction: the only
`:key` in the merged attributes is the one the child is retained under, because
nothing else can reach that slot in any spelling.

**(2) When the presence child IS a boundary, the phase arrives as an ORDINARY
PROP** — `[toast-card {:key id :toast t :rf/phase :unmounting}]`. An attribute
override written on a view head is `:rf.error/hicasso-presence-override-on-a-view`,
naming `:rf/phase`, because the boundary cannot see inside an opaque child and a
silently dropped override map is the class of failure this ruling exists to delete.

**Consequence: `presence-phase` has no Hicasso equivalent — one fewer public
concept against K5.**

**Rationale.** The predecessor exposes phase as an AMBIENT READ, and its own guide
records the cost verbatim: *"Read the phase inside a DECLARED, KEYED CHILD VIEW…
Reading it in markup written inline in the parent is a trap: those props are
evaluated during the PARENT'S render, so the phase you get is the parent's, not the
per-child one you meant."* So a fading toast **cannot be written inline**: it must
be extracted into a view purely so a dynamic var resolves against the right child,
and getting it wrong yields the wrong phase silently. The a11y obligation then
costs three separate `(when exiting? …)` attributes on that child. A prop cannot be
read from the wrong render scope, appears in a structural test's props map, and can
be supplied by a headless test with no clock.
**Why the predecessor's rejection does not apply.** It rejected attribute overrides
and gave a reason — *"A boundary that stamped attributes would have to guess at a
node it never sees."* That is sound for a boundary stamping **by itself**. It does
not survive the AUTHOR writing the override on the node: the boundary already owns
the retained-children list (that is what retention *is*), so applying an override
the author wrote is a hiccup→hiccup transform performed before the codec runs.
React sees an ordinary element whose props changed — no wrapper node, no stamped
`data-*`, no ref, no effect and no ambient read in the merge.
**Only the attribute half of Replicant's mechanism is taken.** Replicant has two
(verified 2026-07-31, replicant.fun): `:replicant/mounting` / `:replicant/unmounting`
attribute overrides — *"allows you to specify overrides for any attributes during
mounting and unmounting… declaratively transition elements on mount and unmount"* —
and separately `:replicant/on-mount` / `:replicant/on-unmount` **callbacks**. The
callback half is less data-oriented than the predecessor's registered behaviors and
is **rejected**.
**Honest limits, recorded rather than discovered.** The override applies to a node
the boundary can SEE; an override inside an opaque child view is invisible to it,
which is what (2) is for. And **enter is the weak half** — the predecessor already
says why: *"driving enter purely as a `:mounting` → `:present` class flip can race
paint… An ANIMATION ON INSERTION (or modern `@starting-style`) is more reliable."*
`::h/mounting` ships; the guide teaches the CSS answer for enter.
**Inherited unchanged:** `:timeout-ms` is MANDATORY, and is both the retention
length and the hard terminal bound; re-entry cancels exit; keys are required on
every dynamic child; presence never dispatches domain mount/unmount events.
**Cost, stated.** The phase transform and the retention machine are **pure**
(`front/presence`), which is what makes the whole thing assertable with no clock.
The React component that drives them (`arm1/presence`) spends **two hooks —
`useState` and `useEffect` — in its own component**. That is not a shell-budget
breach: HD-020(b)'s ≤2 is the *boundary shell's*, and presence reads no
subscription, mounts no registration and takes no cell; the dispatcher-level ledger
still counts exactly two in `runtime/shell`. The hooks are legitimate under
HD-003's placement rule rather than in spite of it — animation lifecycle is
component mechanics by that rule's own list — and they are a library mechanic paid
once, not an application one paid per view. The machine is adjusted **during
render** rather than in an effect (`step` is idempotent, so the comparison
converges), because an effect there would cost a paint with the wrong tree in it.
**Demonstrated, not asserted.** The predecessor's own worked toast tray is ported
both ways in `front/presence_cljs_test` with the rendered attributes asserted
identical, and driven through React and a real DOM in
`arm1/presence_dom_cljs_test`.
**Reopens** if a witness needs a phase on a node the boundary genuinely cannot see
and `:rf/phase` cannot reach.

## HD-026 — `preventDefault` is opted in by a reserved HEAD, not by metadata

**Ruling.** The `^{::h/prevent? true}` metadata spelling is **retired**. Prevention
is opted in by **one reserved intent head** at an event position:

```clojure
[:a.nav-link {:href "#" :on-click [::h/prevent [:conduit/show-your-feed]]}]
```

The grammar is **closed, and this is all of it**: `[::h/prevent INTENT]` — exactly
two forms, the second a non-empty intent vector that is not itself a decorator.
Anything else is `:rf.error/hicasso-malformed-prevent`, **naming the position**.
The decorator is **classified and unwrapped at lowering time**, once per render,
*before* marker analysis — so `::h/value` and `::h/checked` compose inside a
prevented intent — and what reaches `dispatch` is the ordinary inner vector.
Key-map branches are accepted through the same call, because a branch is lowered by
the same code.

**Scope, fenced.** `:on-submit` auto-prevent is **unchanged**; the rare form that
wants a real submission opts out through the existing `h/fn` escape, because a
callback is handed the event and the event is the callback's (HD-024). There is
**no** `::h/allow-default`, **no** event-options map, and **no** modifier language;
a symmetric allow head is added only if dogfooding produces a real site needing
both native submission *and* equality-based structural testing. Auto-prevent cannot
absorb the click case in the other direction either: a click intent on a real link
must **not** prevent by default — modifier-click, open-in-new-tab — so the
anchor-acting-as-a-button genuinely needs an explicit opt-in, and this ruling only
picks its spelling.

**Rationale — one defect is disqualifying, and it is a correctness one.**
**Metadata does not participate in `=`.** HD-021's headless door returns the tree
as data and sells itself on *intent vectors assertable by equality*, and the
measured probe is unambiguous:

```clojure
(= [:app/go] (with-meta [:app/go] {::h/prevent? true})) ;=> true
(= [:app/go] [::h/prevent [:app/go]])                   ;=> false
```

So the one axis the annotation carried was exactly the axis a structural test could
not see — and neither could a hash-keyed lookup, a log line or a snapshot, since
metadata is omitted from printing unless `*print-meta*`. `preventDefault` changes
the event's `canceled` flag per the DOM standard: it is **behaviour**, not
annotation, and a spelling that hides behaviour from the product's own
structural-testing story contradicts that story. Two further defects stand
alongside and would not alone have forced the change: the metadata value **does not
fit on the attribute key's line at any sane indent**, so the one attribute carrying
behaviour is the one that looks like a mistake; and `^{…}` before a vector **reads
as a type hint**, invisible to a reader scanning for behaviour. The shape is not
exotic — the census's home page has three anchors-acting-as-buttons (feed tabs, tag
pills) and every Bootstrap-shaped nav has more, against ten such sites across the
two RealWorld implementations and 35 direct `preventDefault` calls in `examples/`.

**Prior art: Replicant, the most data-oriented neighbour** (verified 2026-08-02,
replicant.fun). **Taken:** its shape for *data that means behaviour* is a vector
with a namespaced-keyword head and positional payload — `{:replicant/on-render
[::update-map-places places]}` — which is the shape this ruling picks, arrived at
independently and confirmed against the library that has pushed the school
furthest. **Declined, deliberately:** Replicant carries listener modifiers as
sibling attribute keys (`:replicant.event/capture`, `:replicant.event/once`,
`:replicant.event/passive`), which is the sibling-attribute option this ruling
rejects — an intent in Hicasso **travels as a value** (a view may hand one to a
child, which places it on an element), and a policy written in a sibling key cannot
travel with it. **Also declined:** Replicant *infers no meaning* from handler data,
so it owes no diagnostic and offers none. Hicasso's roster is interpreted —
`::h/value`, `::h/checked`, and now `::h/prevent` — so it owes one, and the
refusal follows Freehand's house style instead: state the legal grammar, then what
was found, then the form to write (`events/event-plan`'s `:rf.error/view-bad-event`
is the model, down to the imperative `recovery` key).

**Rejected.** (b) A keyed attribute `{:intent [...] :prevent? true}` makes maps
legal at event positions — a second broad calling convention at every event key,
against the census's ~97%-literal-vector grain, and an invitation to the options
language HD-024's collapse deliberately deleted (it is precisely Freehand's
`{:event [...] :prevent-default true}`). (c) Keep the metadata and add a
headless-door lint — patches the equality defect with tooling that would have to
duplicate the semantic knowledge, while defects 1 and 3 stand and printing stays
blind. Also rejected: a sibling element attribute (separates the policy from the
intent it decorates, and cannot travel with it); and requiring `h/fn` for the
recurring click case (discards the data benefit exactly where a one-head wrapper
preserves it).

**Cost, stated.** One reserved head and one extra pair of brackets. Against K5 it
is a swap rather than a growth: the metadata *mechanism* is deleted — one fewer
kind of place for behaviour to hide — and one member joins a roster that is a
list rather than a convention. The event-time path acquires nothing: the
classification is one `=` against the head, taken once per lowered position per
render, and the closure that runs on the click is the same closure as before.

**Demonstrated, not asserted.** The equality property is asserted directly in
`front/intent_cljs_test/a-prevented-intent-is-assertable-by-equality` — including
the retired spelling's `=`, `hash` and `pr-str` blindness, stated as the defect it
was — and the browser half, which no equality can show, is a **real cancelable
click** on the census feed tab against an un-decorated control on the same page:
`shapes/large_template_dom_cljs_test/the-prevent-head-is-what-prevents`.
**Amends** HD-021: its equality promise now holds for the prevent axis at the spine
shapes. **Reopens** on the dogfooding condition named under Scope.
