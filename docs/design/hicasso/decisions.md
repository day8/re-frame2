# Hicasso — decisions (HD-001 … HD-028)

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

## HD-002 — The sub-read mechanism: grouped default, collector challenger, scalar comparator — **SUPERSEDED 2026-07-31**

> **Superseded by operator ruling, 2026-07-31.** The operator ruled that the
> ambient collector — `sub` as a plain call, legal anywhere in the body — is
> **the only read surface acceptable on ergonomics**, and that grouped
> `use-subs` sits **below the usability bar**. The ruling decided on
> **ergonomics, not on the benchmarked-win condition this record set up**: no
> bar row for the survival metric exists yet (H1 is implemented, H2 untried),
> so grouped is superseded without having lost a bench it was never run
> against. **There is one product read surface: `sub`.** Grouped is kept —
> and kept working — only as the comparator rendering the collector is
> measured against; it is not a fallback, and had the collector failed
> correctness the outcome would have been null, never a promotion of
> grouped. The correctness gates ((a)–(d) below) were not waived by this
> ruling; [the dogfood judgement](studio/arm1-lean-react-dogfood-judgement.md)
> §2 is their clause-by-clause discharge, and
> `implementation/freehand/test/re_frame/bench/hicasso/arm1/runtime.cljs`
> carries the same ruling in the code that implements it. Recorded on
> `rf2-2rtt6`.

**Ruling (superseded).** Three tiers, one product:

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
**Reopens** n/a — the ruling above is this decision's resolution, not a bypass
of it; a future change needs a new operator ruling, not a rerun of the P1
instrumentation.

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

## HD-006 — Memoization defaults — **AMENDED 2026-08-02**

> **Its reopen condition fired, and the amendment is what that condition was for.**
> This ruling pre-registered "keyed-row / broad witness evidence demanding it" as
> the one thing that would overturn it, and the tier-1 shape roster produced
> exactly that: a page-chrome write re-ran the page body once and **300 of 300
> card bodies**, with every card's props and every card's subscription values
> value-equal. A **value-equality bail-out is now the boundary default**; see
> [HD-028](#hd-028--value-equality-is-the-boundary-default). The rest of this
> entry stands as the position that was overturned, and by what.

**Ruling (amended).** No default `=`/argv memoization. React semantics stand (a
child may render with its parent); narrow updates come from boundary placement;
`React.memo` is an opt-in escape.
**Rationale.** Do not recreate Reagent's equality semantics from taste; every
default comparison is a cost every render pays.
**Reopens** only on keyed-row / broad witness evidence demanding it. **It did.**

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

> **Addendum, 2026-08-04 — the sugar and the reusable-widget instance-key
> convention move INTO v0, and the sugar is ruled: `h/reg-state`
> (`rf2-2rtt6.100`).** Operator ruling (Mike, 2026-08-04): both move from
> post-v0 into v0 scope, amending this entry. The same-day design programme ran
> three independent designs (explicit-key / auto-structural / ident) through
> three adversarial reviews: explicit 0 fatal / 5 major, ident 0 fatal /
> 8 major, auto **1 FATAL** — ordinal identity is strictly *less* stable than
> React's own (a conditional sibling shifts ordinals at runtime and state
> teleports between instances, silently), and the only repair collapses it
> into the explicit design. All three attackers' forced choices converged:
> **build explicit, amended.**
>
> **What is superseded, and what stands.** This entry's "v0 ships nothing and
> pre-commits to nothing beyond 'sugar, not a state system'" sentence — and
> with it the "unfrozen until the evidence exists" deferral of the sugar's
> shape and tier — is superseded: the evidence now exists, and the shape is
> ruled below. The no-`local` core, the **named**-setter-event law, and
> "never a generic `ui/set`" stand unchanged; the ruled design is that sugar,
> designed. "Sugar, not a state system" also survives inspection: `reg-state`
> holds no runtime state, no hooks, no context — it registers ordinary core
> artefacts.
>
> **The ruled design.** `(h/reg-state ::concern {:default v})` — a plain
> `.cljc` function, not a macro; one namespace-qualified *concern* keyword;
> `{:default v}` the only option; unknown options and a non-namespaced
> concern refused loudly at registration. It mints a parametric sub
> `(sub [::concern ikey])`, a concern-named setter event `[::concern ikey v]`,
> and the documented path `[:ui ::concern ikey]`.
>
> **The tier, unanimously adjudicated.** ONE app-space conventional root,
> concern-first: `[:ui <concern> <instance-key>]`. No `:rf/*` app-db root
> exists or may be minted for it — Conventions' two-partition doctrine — and a
> framework-squatted qualified root of the `:ui/state` kind is likewise
> rejected. Per-frame isolation costs nothing, because app-db is per-frame.
> Durable persistence excludes the tier by convention.
>
> **Clear is a framework-named EVENT, not a value sentinel.**
> `[::h/clear ::concern ikey]` restores the default by removing the entry
> (empty concern maps pruned). The `::h/clear`-in-value-position variant is
> rejected on the record: a keyword-valued concern could silently dissoc
> where app data flows.
>
> **Refusals are loud.** A nil or malformed instance key refuses at read and
> at write with `:rf.error/hicasso-state-bad-key`, naming the concern —
> converting the guide's silent every-instance-shares pitfall into an error.
> Nesting composes by one pure helper, `h/child-key`
> (`(if (vector? k) (conj k part) [k part])`), so every key is by induction a
> flat vector of authored data.
>
> **Four rules are taught, not policed.** (a) Domain ids first, and
> entity-qualified id *values* (`[:order/id 42]`) when one widget serves more
> than one entity type — bare ids let order 42 and invoice 42 collide
> silently. (b) Placement-like concerns (`expanded?`, `open?`, active tab)
> key by placement; value-like concerns (drafts, favourites, in-flight
> status) key by entity — a master list and a detail pane sharing one draft
> of order 42 is correct, sharing one `expanded?` is a bug. (c) `h/child-key`
> for widget-in-widget nesting. (d) Determinism: *"if it would be a good
> React `:key`, it is a good instance key."*
>
> **The SSR payload obligation (unanimous adjudication).** The payload policy
> is fail-closed, and therefore an allowlist MUST name `:ui` whenever
> server-side events write render-affecting instance state; strip the tier
> only if the server never writes it. The obligation is witnessed green AND
> red-by-design by `rf2-2rtt6.99` — a deliberate omission producing the
> hydration mismatch. The payload is render-consistency, not persistence.
>
> **The `useId` autopsy is preserved here so the question is never
> relitigated.** React's `useId` is disqualified for app-db-resident keys:
> fiber-position hydration ids diverge under the arm's own hydration root,
> and counter-based ids are non-remount-stable outside hydration — state
> keyed by them is orphaned on every remount, and app-db snapshots, event
> logs and tests stop being comparable across boot modes.
>
> **The pre-registered response class is now the ambient door.** If dogfooding
> shows the *explicit threading tax itself* failing the preference test, the
> escalation is the rejected auto design's ambient/auto mechanism — a
> recorded door, not ad-hoc invention, and still never a state system. That
> updates this entry's Reopens shape in place.
>
> **Status.** `h/reg-state`, `[::h/clear …]`, `h/child-key` and the refusal
> surface have since landed on the now-closed bead `rf2-2rtt6.98`; the guide
> teaches the convention in the same tense.

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

> **Addendum, 2026-08-06 — the bridge from layer 3 to layer 1 is a RENDERED
> scope, and the attribute is PER-ROOT (`rf2-2rtt6.108`).** The ruling below
> fixes the three layers and leaves unnamed the thing that joins the app-db
> choice to the `data-theme` the cascade keys off; the guide carried both
> candidates as open questions. A delegated design pass and an adversarial pass
> ran on 2026-08-04 (0 FATAL / 4 MAJOR / 5 MINOR, verdict *ratify with the named
> repairs*), and the shape was re-verified against `main` and priced on
> 2026-08-06 in
> [the studio dossier](studio/108-the-theming-taught-default-priced.md).
> Operator-overturnable. Nothing below is withdrawn: the three layers, the two
> laws and the no-context ruling stand unamended.
>
> **Option A is the taught default.** One view reads the theme sub and renders
> `data-theme` on its own element; below it the value-equality bail-out keeps the
> tree quiet. Its case is derivation (a rewind, a restored snapshot and a test
> fixture that writes the theme all agree with the screen), SSR (the same
> renderer emits the attribute from the same snapshot), boot coherence,
> testability as an ordinary `:db` write, and Xray visibility. **Option B — an
> app-owned fx flipping the attribute — stays documented rather than
> deprecated**, as the zero-render alternative; what it costs is derivation, plus
> a server response that carries no attribute at all, so an SSR'd page flashes
> the wrong theme until the client boots.
>
> **The scope is per-root, and it is entailed rather than chosen.** A view
> renders its own element and nothing above it, and `spec/011-SSR.md`
> §*Head/meta contract* records that there is no DOM-head reconciler — so an
> A-shaped per-document bridge is not a design that lost, it is not writable. The
> space is three points, not four. Per-root is also what `spec/004-Views.md`
> §*Theming and semantic parts* already rules — *"an ancestor scope the
> application renders once"* — and what the shipped browser witness
> `pilot_theming_dom_cljs_test.cljs` already does, setting `data-theme` on the
> mount container and never on `documentElement`. The guide's second open
> question therefore retires as **answered by the spec**, not as newly decided
> here, which is the smaller and truer claim.
>
> **Page chrome is a projection, not a second carrier.** Document-level chrome —
> the scrollbar, the `<body>` canvas, `theme-color` — sits above every frame, so
> an app that wants it themed echoes the same db fact document-level *outside*
> this contract: `:html-attrs` on the server, one app-owned fx on the client,
> under an attribute name distinct from the scope selector. One source, two
> projections, deliberately asymmetric — the rendered root attribute stays the
> app's only theme carrier and the document attribute is a redundant cosmetic
> copy, which is why doubling it is not the incoherence that doubling a carrier
> would be.
>
> **The multi-frame cost question closes by argument. No measurement, no
> quiet-box row.** A theme switch is a rare, user-initiated action, and per
> switch per frame the cost is one small boundary body plus one `=` compare —
> *a fortiori* the page-chrome shape
> [HD-028](#hd-028--value-equality-is-the-boundary-default) already measured at
> 300 bodies plain versus 0 memoized. **Conditioning clause:** that arithmetic
> rides HD-028's *default* bail-out, whose own `Reopens` clause is live as of its
> 2026-08-04 amendment. If the default is ever revised, option A's quiet tree is
> re-derived on the successor mechanism — one explicit opt-in at the single
> boundary below the scope — and this addendum survives either disposition. Only
> the mechanism sentence moves.
>
> **Three 2026-08-05 landings neither pass could have seen, all folded into the
> guide.** The memo default narrowed to the heads `defview` mints
> (`rf2-2rtt6.102`, `rf2-u09ay`), so A's precondition is *what* sits below the
> scope rather than *where* — a native-tag subtree, a fragment or a `defhost`
> crossing carries no wrapper, and that is a second cause of the guide's
> whole-app re-render row. A's SSR divergence is **attribute-only**, exactly the
> class this adoption tier never reports, so A trades B's loud transient failure
> for a silent persistent one, conditional on the payload carrying the choice;
> "no flash by construction" is withdrawn and the guide carries a troubleshooting
> row instead. And a scope below a `defhost` crossing is deleted from the server
> response silently under `:client-only` and under `{:fallback …}`
> (`rf2-l0wfx`, `rf2-nv07k`), which is why per-root **at the top** is the
> load-bearing form. `::backdrop` is scope-neutral on every engine generation —
> Chromium 122 / Firefox 120 / Safari 17.4 and later inherit it from the
> originating element, and earlier engines stranded it under per-document too —
> so per-root cannot strand the top layer.
>
> **No new public concept is minted by taking this ruling**, and no code, no new
> fx and no measurement follow from it.

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

> **Addendum, 2026-08-06 — an `h/fn` at a prop slot NOTHING CLAIMED is refused
> at the crossing (`rf2-2rtt6.116`).** The door already refuses an intent
> *vector* at an event-spelled slot the declaration does not name, on the stated
> ground that the alternative is the author's intent crossing as inert data —
> *"the silently dead handler class every loud error in this codec exists to
> delete"*. This extends that refusal to the same defect one level of
> indirection down, and it changes a shipped ACCEPT, which is why it was
> escalated rather than simply fixed.
>
> **What was accepted, and why it was a hole.** `host-entry` routes a prop four
> ways — the `ref` slot, the class slot, a slot the declaration NAMES, and
> everything else — and the last of those passed `fn?` through by identity.
> `h/fn` marks a function and returns *that function* (HD-024's whole deletion),
> so a marked callback at a slot no position claimed crossed as an ordinary
> function. It is callable, so nothing threw. But the author wrote `h/fn` to
> ASK FOR A CONTRACT and nothing selected one, and the `:event` contract's own
> convenience makes that concrete: `[my-host {:on-pick (h/fn [x] [:row/pick
> x])}]`, with `:on-pick` absent from `:callbacks`, is called by the library,
> returns the intent, has the return discarded, and dispatches nothing. The
> user's click does nothing, in production, with no diagnostic at all.
>
> **It is derived, not new policy.** `mint-host!` refuses an option it does not
> know (`:rf.error/hicasso-host-unknown-option`) on exactly this reasoning,
> recorded in this decision's addendum below: a policy could be written and
> never applied, and *the silent-ignore was its own defect*. An `h/fn` whose
> contract is never selected **is** a policy written and never applied.
> `spec/Conventions.md` states the same law repo-wide: a recognised input that
> cannot be honoured is signalled, never swallowed.
>
> **A dev-only WARNING was the other live arm, and it loses on its own terms.**
> A warning fires under `goog.DEBUG` and is gone in production, which is exactly
> where the dead click happens — it converts a silent production failure into a
> silent production failure with a development courtesy. There is no `[:>]`
> warning to be at parity with, either: the synthesized `[:>]` spec retracted
> that grant and pins the escape's conduct to the door's. Against the refusal,
> the counter-case — an author who writes `h/fn` from habit at a prop that needs
> no contract — costs a five-second loud fix at their own stack, and `h/fn` is
> taught as the ONE callback form, i.e. the form you write when the position
> should impose a contract.
>
> **The shape.** One branch in `host-entry`'s `:else` arm, beside the
> event-shaped-data refusal and ahead of `host-prop-value`:
> `:rf.error/hicasso-host-unclaimed-callback`, naming the host, the position,
> the declared roster and the recovery (*declare the slot in `:callbacks`, or
> hand a plain function*). The id is deliberately a PAIR with the sibling's —
> `undeclared-callback` is intent DATA at an event-spelled slot,
> `unclaimed-callback` is the marked form at any unclaimed slot. Cost is a
> `fn?` test plus one own-property read, in the host walk's `:else` arm only;
> no claimed slot pays it, and the native walk is not on that path.
>
> **The fence.** A PLAIN function at an unclaimed slot is untouched and stays
> legal — it is a value handed to a foreign API rather than a position, and it
> asked for nothing. The marked form stays legal at every CLAIMED slot: a
> declared `:event`, `:handler` or `:render`, and React's own `:ref`. No warning
> tier, no dedup, no config knob, no invocation-time wrapper, and no inference
> from an `on*` name — the mark is the trigger, never the spelling. **`[:>]`,
> when it is built, flows through this same branch**: its roster is empty, so
> every slot at that crossing is unclaimed, and the escape inherits the door's
> conduct with no fork of its own.

> **Addendum, 2026-08-05 — there is a THIRD `:ssr` value, `:render`, and a
> declared fallback is inert markup by enforcement (`rf2-l0wfx`, `rf2-nv07k`,
> ruled together).** This amends the 2026-08-04 addendum immediately below,
> which stands as the dated record it is; what it got wrong it got wrong for a
> reason worth keeping, which is that the provider witness did not yet exist.
>
> **What the two-value ruling could not express.** Both of its values answer
> *"what stands in for this component until the client takes over"*, and both
> return something that is not the component — so `props.children` is dropped
> on that arm. For a leaf widget that is correct; there are no children. For a
> **transparent wrapper** it deletes the application. A context provider —
> *"providers an ecosystem library hands you"*, one of this decision's five
> named use cases — contributes no markup of its own and exists solely to carry
> a subtree, so a provider at a crossing takes every descendant out of the
> server response. Measured through `renderToString` on the arm1 bench: the
> page's `<h1>` sibling present, `<p class="body">` and the whole subtree under
> it simply gone. And **silent by construction** — the gate's server snapshot
> is what hydration's first client pass reads too, so the two agree, no
> mismatch is reported and nothing reaches the diagnostic bus.
>
> **The third value is `:ssr :render`, and it is an assertion**: *this
> component is safe to render on the server*. For that policy `mint-host!`
> mints **no gate** — the head's `gate` slot carries the foreign component
> itself, which is this decision's original zero-wrapper, zero-fiber, zero-hook
> shape restored for the hosts that can take it. Same component, same props,
> same children, on the server, on hydration's first pass and on a fresh
> `createRoot` mount: **one tree everywhere**, so zero mismatch by identity
> rather than by a snapshot pair agreeing, no adoption event, and no remount.
> For the case that filed it the assertion is trivially true — a
> `Context.Provider` is React's own component and the server renderer supports
> context fully, so consumers below read the **declared** value in the server
> HTML.
>
> **Why not `:ssr :children`** — render `props.children` in place of the
> component — which is the reading most authors reach for and what the design
> record recommended. Three defects, the first measured: it restores the markup
> and **not the value**, so every consumer below reads the context DEFAULT
> server-side (`unset` against the provider's `dark`) and silent-absent becomes
> silent-wrong; it **remounts at adoption**, because React reconciles a
> position by element type and the position's type goes from the children to
> the Provider, destroying and rebuilding the subtree it just hydrated; and on
> a non-transparent wrapper it emits HTML structurally unlike the client render
> while both passes agree, so nothing reports it. `:children`, `:transparent`,
> `:passthrough` and `:server` all stay refused. The spelling `:render` names
> both the conduct (the component renders) and the assertion (it is safe to).
>
> **`:client-only` remains the DEFAULT** and the conservative reason below
> stands unamended: a foreign React component is exactly the node whose render
> may reach for `window`, and the door cannot know. `:render` is the author
> saying, which is a different thing from the door guessing — a policy that
> inferred the answer from whether a call site passed children was rejected on
> exactly that law.
>
> **The fallback half (`rf2-nv07k`).** The addendum below says a fallback is
> walked once at the declaration; the corollary the guide teaches from that —
> *a fallback is inert markup* — was **stated and not enforced**. What the walk
> refused was what it could EVALUATE: an intent vector, a `sub` call in the
> form, hiccup that is not hiccup. A boundary head is none of those — it is an
> element whose body runs later — so the walk never looked inside it, and the
> "placeholder" was a live boundary reading subscriptions in the server
> response. Two measurements made that a defect rather than a narrow rule:
> **the declared placeholder was not a value** (one declaration rendering
> `ALPHA`, `BRAVO` and `ALPHA-TWO` — the walk-once law's own justification
> falsified by what it permitted), and **it did not survive this arm's other
> boundary variant** (a frame-fed head bakes a `nil` frame at mint and throws
> mid-server-render, so whether it worked at all depended on which mint the
> head came from). The mint now walks a declared fallback **structurally** and
> refuses a `defview` or `defhost` head at any position —
> `:rf.error/hicasso-host-fallback-boundary-head`, naming the host, the head's
> `displayName` and its index route into the form. Walk-scoped, so it covers
> the frame-fed variant without knowing it exists.
>
> The workaround this deletes — writing a provider's subtree a second time as
> the declaration's fallback, which was `rf2-l0wfx`'s only recovery — is
> **superseded**, not merely removed: `:render` renders the real subtree, with
> the real context value, once.
>
> **What `:render` does NOT solve, recorded rather than glossed.** A provider
> whose *value* is genuinely client-only — `window`-derived — still has no
> server story: that value is computed in the caller's body, so such a host
> stays `:client-only` and everything below it is client-only too. No candidate
> solved that case, and `:children` would have solved it wrongly by rendering
> the subtree under the default value.
>
> **`[:>]` is unchanged.** The escape carries no declaration, so it carries no
> server-safety assertion either: it renders nothing server-side, and it must
> never grow an `:ssr` spelling of its own. The answer to *"my provider
> vanished server-side"* is now "declare it, with `{:ssr :render}`" — which
> finally works.
>
> Witnessed in `arm1/host_ssr_dom_cljs_test` (the refusal roster, the
> server-render children and context rows, and the hydration row that counts
> mounts), `arm1/fallback_contents_cljs_test` (the refusal, both directions,
> with every legitimate fallback position proven individually) and
> `ssr/entry_cljs_test` (the corpus row, at a handed-in and a nested position).

> **Addendum, 2026-08-04 — the SSR placeholder listed among the defaults below
> is BUILT, and it is a declaration option with two values (`rf2-2rtt6.85`).**
> HD-020(d) held this default "declared policy for later phases, inert in v0";
> the operator's same-date SSR ruling (HD-020's addendum, `rf2-2rtt6.83`) makes
> SSR required scope, so it is activated here. The 2026-08-04 runtime audit
> found it was not inert but **absent**: `mint-host!` read `:callbacks` and
> silently ignored every other option, so a policy could be written and never
> applied.
>
> **The spelling.** `defhost`'s `opts` carry `:ssr`, whose value is either
> `:client-only` — the **default**, applied when `:ssr` is absent — or
> `{:fallback <hiccup>}`. `:client-only` renders nothing where the host sits
> until the client has adopted the markup; `{:fallback …}` renders that markup
> there instead. There is no third value. The conservative default is the
> honest one: a foreign React component is exactly the node whose render may
> reach for `window`, and a declaration that names only the component tells the
> door nothing about that.
>
> **Everything is refused at the declaration**, where this record already puts
> every other host refusal. A third `:ssr` value, an explicit `nil`, an empty
> or multi-key fallback map, and a fallback that is not hiccup all raise
> `:rf.error/hicasso-host-bad-ssr-policy` or the walk's own error at mint —
> the fallback is walked once, there. **And an option `defhost` does not know
> is now refused rather than ignored** (`:rf.error/hicasso-host-unknown-option`,
> roster `#{:callbacks :ssr}`): the silent-ignore was its own defect, of the
> same class as an intent crossing to a library as inert data.
>
> **One mechanism, three places.** A declaration mints one gate — a component
> whose single `useSyncExternalStore` answers `false` from its **server**
> snapshot and `true` from its client one. React reads the server snapshot
> under `renderToString` **and again on hydration's first client pass**, then
> re-renders with the client snapshot once adoption completes. So the server
> render honours the policy by rendering rather than by consulting it, the
> first client pass produces what the server produced (there is no mismatch to
> reconcile, which is asserted against React's own `onRecoverableError` rather
> than by reading the settled DOM), and a fresh `createRoot` mount — which
> never consults a server snapshot — renders the foreign component on its first
> pass with no placeholder flash. A server walk therefore needs no policy
> branch of its own.
>
> **The cost, because it changed a property this record claimed.** The door
> used to mint no wrapper, no fiber and no hook: the foreign component was the
> element's own type. It now mints one gate per declaration, so a crossing
> costs **one fiber and one hook**. HD-020(b)'s ≤2-hook budget is untouched and
> still means what it said — it is a statement about Hicasso's **boundary**
> shells, `shell-hook-ledger` still declares two, and the gate is not a
> boundary: it holds no subscription, reads no frame and runs no body. The
> hosted-page hook census was updated to state the new truth rather than
> deleted, and now reads the shell's two, then the door's one, then nothing
> that is not the hosted component's own roster. Witnessed in
> `arm1/host_ssr_dom_cljs_test` (declaration, server render, fresh mount,
> hydration) and `arm1/host_hatch_dom_cljs_test` (the hook census).

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

> **Addendum, 2026-08-05 — the bar is PER-AXIS: the mount denominator moved to
> UIx, the bulk denominator did not (`rf2-hyd50`).** The ruling below states
> mount and bulk under a single Reagent-denominated number. That is superseded
> **on the mount axis only**. The operative bar, as the operator-owned standard
> `rf2-2rtt6.1` carries it:
>
> - **Mount** — **≤ 1.10× direct UIx-on-subs**, canonical `M1`,
>   floor-normalised, on the clock of record (raw `TaskDuration`, script **and**
>   frame). Reagent-on-subs stays co-instrumented and is **reported beside the
>   mount row, not gating it**.
> - **Bulk** — **≤ 1.0× Reagent-on-subs, like-for-like** — unchanged.
>
> **What stands, so the conflict cannot be re-derived.** Everything else in the
> ruling below: the clock-only ship *number*, browser-only figures, memory
> governing through the kill rules rather than the ship number, UIx as the
> mandatory co-instrumented comparator, the 1.5× bulk architecture-kill
> tripwire, K3, the heap gates and the red-zone ratios. Only the mount
> denominator moved; every other number stands as written, Reagent-denominated
> where written.
>
> **Provenance.** The operator relaxed "as fast as Reagent on mount" on
> **2026-08-01** (`rf2-2rtt6.1`): "`mount M1 ≤ 1.0× Reagent-on-subs` is no
> longer the bar". The **2026-08-02** mount-gate amendment set the replacement
> as one line and **retired** the old pair rather than restating it. `rf2-hyd50`
> adjudicated the two texts on **2026-08-05** — delegated and
> **operator-overturnable**; if that ruling is overturned, this addendum reverts
> with it.

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
fallback is a lint, not a third convention. Note: helper-donated reads are
settled rather than contingent. HD-002's ruling makes the ambient collector the
one product read surface, so a `sub` performed inside a helper is an ordinary
read that lands in the calling boundary's window.

> **Addendum, 2026-08-04 (`rf2-2rtt6.105`) — the `for`-lowering sugar is ruled
> out, and the door it retires behind is named.** The details list above pins
> that the sugar "is **not** v0". That clause is not wrong; it was incomplete,
> because a reader could learn the sugar was out of v0 without learning it was
> subsequently ruled out for v-anything. A design pass plus an adversarial review
> briefed to *force* the build case returned **DO-NOT-BUILD** — recorded at the
> adjudicated strength and no stronger: ruled out **on today's evidence**,
> **operator-overturnable**, with a pre-agreed response class. Never "never".
>
> **The door, by name.** The shelved shape is a **scalar-refusing `h/for`** —
> `(h/for [id ids] [row {:id id}])`, with a loud refusal on a non-scalar binding
> value, the refusal itself being the lesson. Its trigger is the dogfood
> preference test failing **specifically on list ceremony**, which is HD-009's own
> reopen shape. Until that gate fires authors write `:key` themselves, and that is
> the taught answer rather than a gap waiting on sugar. The charter's by-name
> pre-permission of the sugar is retired behind this same door rather than
> deleted; [charter.md](charter.md)'s "No analyzer" bullet carries the matching
> amendment, and the two records are meant to be read as one.
>
> **What the verdict rests on.** The cost case, and it survives independently of
> how often the sugar would serve. The sugar would be the first control form in a
> collector grammar whose *absence* of control forms is a marketed virtue, and it
> could never retire the explicit `:key`, so every list would carry two spellings
> forever. The binding value is only *sometimes* the identity: a destructured or
> entity-valued binding is string-coerced by React through CLJS `toString` into
> content-derived identity, so editing a row silently remounts it — and every
> repair for that (refuse entities, add a `:key-fn` arm) collapses back toward the
> explicit spelling, which is the instance-key programme's arc re-run. The
> `defview`-macro-rewrite route is dead on the no-analyzer fence, where partial
> coverage would be a silent-inconsistency factory. Against all of that, the
> ceremony saved is roughly a dozen characters per list site. The dominating
> repair the pass identified — a dev warning on a **non-primitive `:key` value**,
> closing the same hazard in the explicit spelling at zero concept cost — is
> designed but **not landed** (`rf2-2rtt6.104`), and this verdict deliberately
> does not lean on it.
>
> **Corpus correction, binding on any record of this ruling.** The decisive
> framing is **not** "wrong at two-thirds" — that ratio belongs to the v1-idiom
> examples corpus. The governing Hicasso-idiom corpus, the charter's own `shapes/`
> tree included, runs **~70% scalar binding-as-key**, which reads *for* the sugar
> rather than against it. The verdict took that correction and stood, because it
> never rested on serve rate.

> **Note, 2026-08-05 (rf2-d03av): "callback refs only" states what v0 TEACHES,
> not what it refuses.** The clause above reads as a prohibition, and it is not
> enforced as one: `front.codec/check-ref!` refuses exactly one value — the
> reserved **vector** — and passes everything else through. That is not a gap
> in the check, it is [HD-022](#hd-022--refs-vector-value-space-is-reserved-now-and-refused-loudly-in-v0)
> in as many words: *"That is the whole ruling — one refusal branch and one
> error id."* HD-022 is later than this clause and is the ruling actually about
> `:ref`'s value space, so where the two are read as disagreeing, HD-022 governs.
>
> The consequence, stated so it is a decision rather than an oversight: an
> **object ref** (`(react/createRef)`) is legal at a native tag and at a
> `defhost`/`[:>]` crossing. React 19 carries `ref` as an ordinary prop, so it
> attaches and detaches exactly as React documents; nothing is silently dropped
> and nothing needs repairing. It is **untaught, not illegal** — the guide
> teaches the callback ref because attach and teardown are one thing there
> (React 19 returns the cleanup from the callback), and that is an ergonomic
> recommendation the codec does not police. Refusing a spelling that works
> correctly would be friction rather than safety, and pre-alpha this project
> trusts the programmer.
>
> Read the clause as: *`:ref` is legal on native tags and `defhost`/`[:>]`
> crossings; the callback ref is the taught form; the reserved vector spelling
> is refused; `:ref` is not a v0 surface on Hicasso views (use ids).*
>
> Design C's parity rule is what makes this the cheap answer as well as the
> right one: whatever is decided applies to **both** crossing forms and to
> native tags, and a rule enforced at one crossing and not the other is worse
> than a rule enforced at neither. Doing nothing satisfies parity for free.
> Pinned by `front/codec_cljs_test` →
> `an-object-ref-crosses-by-identity-at-both-positions`, so a later refusal
> cannot land silently.

## HD-017 — Code residence and graduation

> **Note, 2026-07-31.** This entry's graduation clause was written as "the P2
> ruling graduates exactly one arm into a tracked `implementation/hicasso/`
> artefact" — phrasing that assumed P2 would pick a winner between two live
> arms. The 2026-07-31 product ruling (HD-007, superseded) settled *which* arm
> ahead of P2: Arm 2 (PATCH) was dropped on product direction, not on
> measurement, so P2 is now the surviving arm (lean-React) versus null, not a
> choice among arms. The residence-and-graduation mechanism below is otherwise
> unchanged: runtime skeletons stay disposable until the surviving arm
> graduates into a tracked `implementation/hicasso/` artefact on a P2 "go"
> (HD-018); on a stop, nothing graduates and Hicasso's spike code is archived.
> See also [architecture.md's Code residence section](architecture.md#code-residence-hd-017).

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

> **Addendum, 2026-08-03 — the composition carve-out is IN (`rf2-digtt`).** The
> ruling below left one question open and named the bead that would settle it:
> whether to suspend convergence until `compositionend`. It is settled, in
> favour of the carve-out, and this record's "not proven, and not to be written
> as though it were" clause about the value path through a live composition is
> superseded by what follows. Everything else in the ruling stands unchanged.
>
> **What was ruled.** Controlled-text convergence is suppressed while an IME
> composition is live; the field converges **once**, at `compositionend`,
> against the then-current model. Automatic for any `:value`/`:on-input` pair —
> no public option and no config knob. Under the old conduct a CJK/IME user on a
> refusing or normalising field had in-flight kana destroyed mid-word with no
> feedback at all; under the carve-out the composition survives and the refusal
> lands whole and visibly at the commit. A composition is a browser-owned draft
> of the user's, and destroying it silently is not a parity target worth
> keeping.
>
> **This is a deliberate divergence from plain React, and it is claimed as
> one.** It is scoped precisely to Hicasso's converge: the model still refuses
> or normalises every intermediate composition state exactly as before, and on
> the refusing field the exchange ends with the same model and the same field as
> the React baseline reaches. `ime_run.cjs` asserts the divergence and its scope
> comparatively, in one run, on one model. The re-run turned up one thing the
> scope claim does **not** extend to, and it is pinned rather than smoothed
> over: on a *normalising* field the baseline does not merely lose the
> composition, it corrupts what the exchange commits — each aborted draft is
> written back and the IME's next composition composes on top of it, so
> `s`/`sh`/commit ends at model `"SSHSH"` where the carve-out commits the `"SH"`
> that was typed.
>
> **The mechanism is two halves, because two writes destroy the exchange and
> only one is ours.** (a) The converge declines to run when the change event
> arrived mid-composition — one reading of the native event's `isComposing`.
> (b) React's own end-of-discrete-event restore is not ours to skip, so an
> internal single-`useState` component holds **the value React sees at the live
> DOM draft** while a composition runs, and React's own `element.value !== value`
> guard does the skipping. A bare "skip the converge" is proven insufficient by
> this bead's measured matrix: plain React, which has no converge in the loop,
> aborts the exchange anyway. Nothing reaches into React's internals, mutates
> props React holds, or intercepts a value setter.
>
> **The second `flushSync` call site is audited here, as this record requires.**
> `front.controlled/converge!` is now reached from two places in its own
> namespace: the end of a change handler that is *not* composing, and once at
> `compositionend`. Same element, same door, at most one per event, and the
> composition path is the keystroke path with its convergence deferred to the
> end of the exchange rather than a second mechanism. The grant stands on that
> reading; a call site outside this namespace still needs its own ruling.
>
> **The price, paid in public.** One React fiber and one `useState` cell per
> controlled `input`/`textarea`, plus — on a convergeable one, per render — one
> props copy and four closures: the three wrapped handlers and the release they
> share. **HD-020(b)'s ≤2-hook budget is untouched**:
> the shadow is not a boundary shell, and `shapes/hook_budget_dom_cljs_test`
> now separates the two counts — the shell sequence is still the declared pair
> once per boundary, the shadow's hook is counted per controlled text field, and
> no hook is ever interleaved into a shell's pair. A page with no controlled
> input pays nothing. The shadow's component deliberately does **not** read
> `:type`, because an element type that changed under a live field would remount
> it; the `:type` question is asked inside, one render later, where a wrong
> answer costs nothing.
>
> **Release is unconditional**, which is the safety rider: `compositionend`, any
> change event that is not composing (the recovery path for a composition some
> other write aborted silently, which fires nothing), `blur`, and unmount for
> free — the shadow is the component's own state and cannot outlive the element.
> The hold or release also runs **before** the author's handler at every slot,
> so a handler that throws cannot strand one either. The worst degradation
> available is a converge, which is today's conduct. All four paths are
> witnessed in a live React tree by `arm1_controlled_grid_dom_cljs_test` §7.
>
> **Witness scope: Chromium only.** `Input.imeSetComposition` is a CDP method and
> CDP is Chromium's protocol, so every composition claim here is witnessed on
> Chromium and nowhere else. WebKit has had composition/key-ordering defects of
> its own; this harness cannot drive it, and misconduct there is a new bug bead
> rather than a known limitation.
>
> **K4's IME half is no longer open on this question.** `rf2-dfz6f` rewrites the
> fitness harness's R-A2 wording separately.

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
are the 100-cell grid rows: same-turn echo, mid-string caret, selection,
unchanged-model rejection, async normalisation. **The IME witness is narrower
than the other five, and is recorded as such.** What is proven is the *commit*
fence — a composing Enter commits nothing, on the native event's `composing?`
and on the legacy keyCode-229 signal, each independently — witnessed in-page on
the grid and established against the browser's own composition machinery by
`ime_run.cjs` (`rf2-o27h3`: CDP-driven trusted composition, on this arm's
element path, plain React and the UIx port alike). What is **not** proven, and
is not to be written as though it were, is the *value* path through a live
composition: the same harness measured a refused or normalised value being
written back mid-composition, and such a write silently aborts the exchange —
this arm's converge in turn, plain React in turn through its own restore, the
UIx port a frame later. The converge is therefore nowhere worse than the React
baseline, but "composition survives value reassertion" is false for every
implementation the moment the model disagrees. Whether to suspend convergence
until `compositionend` is a behavioural choice inside this exception's scope and
is unruled (`rf2-digtt`); until it is settled, K4's IME half is open rather than
green. On a PATCH back end the restore obligation transfers to the renderer
(architecture.md, hard gate).
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
candidate second call site appears. The composition value path reopens on
`rf2-digtt`'s ruling, whichever way it goes.

## HD-020 — v0 host mechanics: frame plumbing, hook ledger, error boundary, SSR posture

> **Addendum, 2026-08-04 — clause (d) is reopened and reversed: SSR + hydration
> is REQUIRED Hicasso scope (`rf2-2rtt6.83`).** This entry's own Reopens line
> says "at product phase (SSR, richer boundary API) by ordinary ruling"; this
> is that ruling, taken by the operator. **Mike, 2026-08-04, verbatim:** *"SSR
> is an important part of re-frame2. If hicasso is to be the re-frame native
> view layer then it has to be used with SSR"* — and, earlier the same day:
> *"hicasso is useless unless it does SSR."*
>
> **What is ruled.** SSR + hydration moves from out-of-v0 to required Hicasso
> scope. Hicasso participates in re-frame2's **existing** SSR story — Spec 011
> (`spec/011-SSR.md`): the payload policy, the `#__rf_payload` EDN embed, the
> `hydrate!` boot helper and the reserved `:rf/hydrate` db adoption before
> first render, the hydration-mismatch machinery, and `ssr-ring` as the HTTP
> host — **never a parallel Hicasso-only mechanism** (requirement R0). The full
> requirement set R0–R8, the non-goals, and the P2-sitting linkage are recorded
> in the same-date addendum in
> `docs/EP/EP-0038-the-hicasso-view-layer-programme.md`.
>
> **What does not move.** SSR *speed* stays off the bar: HD-012 and
> validation.md's "never SSR or test-lane speed" line stand unchanged. Clauses
> (a)–(c) of the ruling below — frame plumbing, the hook ledger, the error
> boundary — stand as written.
>
> **Status.** Four pieces have since landed on the now-closed beads
> `rf2-2rtt6.84`–`.87`: the hydration door, the `defhost` `:ssr` policy, the
> Node render entry, and the X1–X5 spike witness.

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
for later phases, inert in v0. *(Superseded 2026-08-04: the operator's SSR
ruling makes SSR required scope, and `defhost`'s `:ssr` is built — see HD-011's
2026-08-04 addendum, and its 2026-08-05 one for the third value. The clause is
kept as the dated record of what v0 was scoped to.)*
**Rationale.** Each is a decision a v0 implementer would otherwise have to make
ad hoc mid-spike; none is reversible for free once witnesses pin behaviour.
**(a) reaches further than a boundary shell, and (c) is what proved it**
(rf2-uo9di). A `h/boundary`'s `:fallback` and its children are hiccup written in
the *parent's* body and walked by the codec inside the **class's own render**,
one render later — so `intent/*dispatch*` was unbound when the codec reached
them, and an intent at an event position on either raised
`:rf.error/hicasso-intent-outside-boundary`. The fallback half made (c)'s own
worked example unwritable: `:fallback` ships beside `:reset-key` precisely so
that "the retry is the caller's to schedule", and the control that schedules it
is a button whose `:on-click` is an intent. Worse, a fallback that throws while
rendering takes the *next* boundary up, so an application's error path became an
application-wide failure. The class therefore re-binds its frame (a) around that
one crossing. **It costs no hook** — the frame was already reaching it through
`contextType`, which is a property of the component rather than a dispatcher
read, so unlike `arm1/presence`'s identical repair (HD-025) this one is
invisible to (b)'s ledger. No frame in scope stays legal — the class reads
nothing — and an intent written under a frameless boundary remains the same loud
error, naming the intent. Witnessed, retry button and all, in
`arm1/boundary_intent_dom_cljs_test`.
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

**K5 was removed as a kill criterion by operator ruling on 2026-08-04**
([validation.md](validation.md)). The rationales that cite it — this paragraph,
HD-023, HD-024, HD-025 and HD-026 — were written while it stood and are left
exactly as argued: they record why each shape was chosen at the time, and the
deletions they justify are shipped. Read every "against K5" below as historical.

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
(c″) **The tag's `#id`/`.class` shorthand is folded on the emitted slot too.** The
rule — an explicit id wins over `#tag`, and `.foo` composes with a declared class —
was stated over the props *map*, where it read `:id`, `:class` and `:className` and
therefore saw three of the spellings the codec accepts. Every other spelling
survived as a second map key landing on the same React slot, so
`[:div#tag.foo {:& {"id" "caller" "className" "bar"}}]` let the explicit id lose to
`#tag` and the caller's class replace `.foo` instead of composing with it — map-order
dependent again, and through `:&`'s own door, where the author of the element never
sees the key. The shorthand is folded onto the object the walk EMITS instead: every
spelling has already been through the canonical slot on its way into that object, so
there is no key left to miss and nothing left to resolve. The class slot is a
position in the same sense `ref` is — its value is coerced by the class rule rather
than by the generic prop conversion, and two spellings of it compose rather than the
last write silently winning. The fold also deletes the map surgery the shorthand
merge performed on every element carrying a shorthand; that is a structural change,
and its clock effect is **unmeasured** like the rest of `:&`.
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
(`examples/substrates/uix/login/core.cljs` — `mount!`'s `frame-root` props).
**Class composition needs no exception.** An element's own classes are written on
the **tag** (`[:input.form-control {:& caller}]`), which is not a literal attribute
key, so the shorthand merge composes them with whatever the remainder brought —
however the remainder spelled it (c″). A literal `:class` still wins outright,
because it is a literal, and what composes is what SURVIVED the merge: an alias at a
slot an owned literal claims never gets that far.
**Demonstrated, not asserted.** The RealWorld article editor's four form fields
(`examples/real-apps/realworld_resources/article_editor.cljs` — `editor-page`'s four
`fieldset.form-group` blocks) are ported
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

> **Addendum, 2026-08-06 — the position table's row 3 OVER-RECORDS: it never
> covered a `defhost` slot the declaration left unclaimed, and that slot now
> REFUSES the marked form (`rf2-2rtt6.115`, `rf2-2rtt6.116`).** Two beads, one
> row, and they must be read together or the row goes on being wrong.
>
> **The scope correction (`rf2-2rtt6.115`).** Row 3 reads *"any other walked
> prop position (**a slot**, a foreign render prop) → render"*. The parenthesis
> over-reaches by one position. A **native** non-event prop does render-wrap the
> marked form, and a slot a `defhost` declaration gave the `:render` contract
> does too — those are the row. But an **unclaimed `defhost` slot** never
> did: `host-entry`'s `:else` arm handed it to `host-prop-value`, which passes
> `fn?` through by identity. Three independent adjudications concur that the
> CODE side was authoritative and the crossing deliberate — both tables landed
> in one commit, and the walk that implemented identity the next day carried a
> reasoned docstring saying so. The row is a record of what was ruled and stays
> below unedited; this is its scope, which it never stated. **The "align
> `defhost` with row 3" repair direction is struck as adverse** — it would have
> installed the render-position purity gate at a foreign crossing and broken the
> `React.memo` identity that `host-prop-value` preserves on purpose.
>
> **What the position does now (`rf2-2rtt6.116`, ruled 2026-08-06).** Identity
> was the right answer to *"is this render-wrapped?"* and the wrong answer to
> *"is a marked callback nothing reads acceptable?"*. It is not: at an unclaimed
> slot the mark asks for a contract no position selected, so the crossing now
> **refuses** — `:rf.error/hicasso-host-unclaimed-callback`. HD-011's dated
> addendum carries the full ruling, its grounds, and its fence, because the DOOR
> owns crossing conduct. A **plain** function at that slot is untouched and
> still crosses by identity, which is the part of the row's deletion clause that
> was always true of it.
>
> **So the record correction `rf2-2rtt6.115` was holding is discharged here,
> and its recorded repair is superseded in one half.** That repair was *"a dated
> scope-note on row 3 plus the witness pinning a marked `h/fn` at an unclaimed
> door slot"* (carried as R4 of the synthesized `[:>]` spec). The scope-note is
> above. The witness cannot pin identity for a marked `h/fn` any more, because
> the marked form no longer crosses there — it is the RED refusal row in
> `arm1/host_hatch_dom_cljs_test`, and the identity witness it names is the
> PLAIN-function row beside it.

> **Addendum, 2026-08-03 — render-position enforcement is INVOCATION-scoped, and
> the owner forwards (`rf2-2rtt6.74`).** Both laws below stand: a `:render`
> position is pure, and dispatching from inside the call is
> `:rf.error/hicasso-dispatch-in-render-position`, naming the position. What is
> superseded is the Rationale's mechanism sentence — "enforced by poisoning the
> ambient dispatch for the call's dynamic extent" — insofar as it read as though
> handlers *lowered* inside the call stayed poisoned forever. They do not; the
> sentence stays below as the record of what was ruled first.
>
> **The defect.** Lowering captures the same var the poison replaced, so the row a
> `renderRow` prop exists to build — `[:li {:on-click [:row/pick id]} …]` — closed
> over the poison and raised at the USER'S click, for a click:
> a legitimate event position that merely happened to be lowered during a render.
> A render prop producing a non-interactive row worked; one producing an
> interactive row did not — which is most of what render props are for — and the
> failure landed a phase and a component away from the author's site, the worst
> failure geometry available.
>
> **What was ruled.** Enforcement is scoped to the INVOCATION, not to everything
> the invocation lowered: poison while the call is running, forward to the owner
> once it has returned. The wrapper captures the ambient dispatch and frame at
> LOWERING time, and each invocation mints a fresh gate over them, binds it as the
> ambient dispatch for the call, and arms it in a `finally`. The discrimination is
> TEMPORAL — call-active versus call-complete — which is the law's own line, and
> it is why a synchronous lower-and-fire-NOW inside the body still raises while a
> lower-now/fire-later row no longer does. A second "capturable" binding would
> instead have let the synchronous case dispatch silently, weakening law 1 to
> preserve a mechanism.
>
> **The owner is the SUPPLYING boundary's frame** — the boundary whose codec walk
> lowered the `:render` prop. It is the only candidate: the wrapper is minted
> inside that boundary's `with-frame` extent, the foreign component has no frame
> of its own, and frames-as-isolated-contexts forbids any other. The ambient frame
> is rebound to it for the invocation as well, so a `route-link` written in a row
> body pins its navigation there rather than failing loudly. Where no owner was in
> scope at wrapper creation, a handler lowered inside raises the ordinary
> `:rf.error/hicasso-intent-outside-boundary` when it fires — loud, never silent,
> and never a new error id.
>
> **Fence.** No new API, no config knob, no new error id, nothing about
> scheduling, and no auto-conversion of what a render body returns. Witnessed by a
> two-frame ownership row at the real declared-`:render` crossing
> (`arm1/host_hatch_dom_cljs_test`), by its closure-level twin and the no-owner
> edge (`front/intent_cljs_test`); the two purity edges — a direct dispatch inside
> the call, and a synchronous lower-and-fire — are unmodified and still raise.
>
> **What the fence leaves open, stated rather than implied (`rf2-2rtt6.120`).**
> "No auto-conversion" means the return crosses UNCONVERTED — `render-callback`
> ends in a bare `(apply f args)`, so a string renders and a vector reaches React
> and is refused there. The author therefore has to make the element, and
> `codec/as-element` is the conversion that would do it — but it is INTERNAL, and nothing on
> the taught `h/` roster reaches it. **So the recovery has no spelling an author
> can write today.** That is an open gap, not a missing sentence, and it is what
> `rf2-2rtt6.120` holds. In particular **there is no `h/as-element`** — this
> addendum illustrated the row with one until 2026-08-05, copied from
> `front/intent/render-callback`'s own docstring, and so did four of the `[:>]`
> design records under `studio/`. The docstring is corrected; the spelling was
> never real.

**Ruling.** Hicasso ships **one** callback form — `h/fn` (spelling unfrozen) — and
it is **an ordinary function**. The contract comes from the **position**, because
the runtime already knows every position it walks:

| Position | Contract |
|---|---|
| a native `:on-*` prop | **event** — a returned VECTOR is dispatched; any other return is ignored |
| a `defhost` `:callbacks` entry | as **declared** (`:event`, `:handler` or `:render`), never inferred from an `on*` name |
| any other walked prop position (a slot, a foreign render prop — but NOT an unclaimed `defhost` slot, which refuses; see the 2026-08-06 addendum) | **render** — pure; the return is render output and is not dispatched, and dispatching from inside is `:rf.error/hicasso-dispatch-in-render-position`, **naming the position** |
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
**The declaration governs EVERY carrier at its position, not only the one form.**
A declared position accepts four carriers — the one form, an intent vector, a
key-map, and an ordinary value — and the row above is a law about the POSITION,
so the contract is the outer question and the carrier the inner one. `:event`
keeps the vector and key-map conveniences, because dispatching is exactly what
that contract means; at `:handler` and at `:render` both are **refused**
(`:rf.error/hicasso-intent-at-a-non-event-contract`, naming the position), because
each of those carriers is a dispatch and nothing else while neither contract
dispatches. Reading the value first is how a declaration that says `:handler`
comes to dispatch a bare intent silently, and how a `:render` position comes to
dispatch during the foreign component's own render — the VALUE selecting the
contract, which is the defect this ruling exists to delete. An ordinary unmarked
function crosses untouched at every contract.
**The vector spelling is EVENT-FIRST.** `::h/prevent`, `::h/value`,
`::h/checked`, `:on-submit`'s auto-prevent and a key-map's key lookup all read
the DOM event, and all read it from argument **one** — what every native position
hands them, and what an event-first foreign contract (`(on-draft event)`) hands
them too. A value-first invoker (`(on-pick value event)`) has no event there, and
nothing guesses which of a library's arguments is one: inference at that seam is
what HD-011 forbids in the first place. The refusal is
`:rf.error/hicasso-intent-needs-the-event`, naming the position and pointing at
`h/fn` — the one form, which receives every argument in order — rather than
leaving the author `value.preventDefault is not a function`, the engine's own
`TypeError` naming nothing they wrote. An intent carrying neither a marker nor a
decorator never reads its argument at all, so it is correct under any invoker
contract and pays no law.

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
The React component that drives them (`arm1/presence`) spends **three hooks —
`useContext`, `useState` and `useEffect` — in its own component**. That is not a
shell-budget breach: HD-020(b)'s ≤2 is the *boundary shell's*, and presence reads no
subscription, mounts no registration and takes no cell; the dispatcher-level ledger
still counts exactly two in `runtime/shell`. The two lifecycle hooks are legitimate
under HD-003's placement rule rather than in spite of it — animation lifecycle is
component mechanics by that rule's own list — and they are a library mechanic paid
once, not an application one paid per view. The machine is adjusted **during
render** rather than in an effect (`step` is idempotent, so the comparison
converges), because an effect there would cost a paint with the wrong tree in it.
**The frame hook is the third, and it was bought by a defect rather than by a
design** (rf2-2rtt6.66). A presence child is hiccup written in the parent's body
and **lowered inside presence's own render**, so `intent/*dispatch*` was unbound
when the codec walked it and *any* intent on *any* presence child raised
`:rf.error/hicasso-intent-outside-boundary` — the inline dismiss button this
ruling is sold on could not be written. Presence therefore resolves the frame once
from the substrate's single internal context and re-binds it (HD-020(a)) around the
one `as-element` call, so a child lowers exactly as it would have in the parent's
body. No frame in scope stays legal — presence reads nothing — and an intent
written under a frameless tray remains the same loud error, naming the intent.
**Demonstrated, not asserted.** The predecessor's own worked toast tray is ported
both ways in `front/presence_cljs_test` with the rendered attributes asserted
identical, driven through React and a real DOM in `arm1/presence_dom_cljs_test`,
and clicked — both intent shapes, live and retained, against a second live frame —
in `arm1/presence_intent_dom_cljs_test`.
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

**Cost, stated.** One reserved head and one extra pair of brackets. Against K5 —
since removed ([validation.md](validation.md), 2026-08-04) — it is a swap rather
than a growth: the metadata *mechanism* is deleted — one fewer
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

## HD-027 — `route-link`: a plain function over routing's link seam, and a second reserved head

**Ruling.** The fifth tier-1 shape's Hicasso spelling is **`route-link`, a plain
function** (`front/route_link.cljs` in the bench arm): the author writes
`(route-link {:to :conduit.profile/show :params {:username u}} u)` and never sees
a URL. It renders ONE real `<a>` whose `:href` is routing's own synthesis and
whose `:on-click` carries the click decision **as data**, under the SECOND
reserved head:

```clojure
[:a {:href     "/profile/jane"
     :on-click [::h/navigate {:frame    :app/frame     ; captured at render
                              :payload  [:rf.route/url-requested {…}]
                              :native?  false           ; target/download verdict
                              :veto     nil}]}          ; the caller's :on-click
 "jane"]
```

The grammar is **closed, and this is all of it**: `[::h/navigate MAP]` — exactly
two forms, the map carrying `:frame` (keyword), `:payload` (non-empty vector),
`:native?` (boolean) and `:veto` — **those four keys and no others**, asserted as
the exact key SET rather than as four presence tests, because a check that
validates only the keys it knows admits both a map missing `:veto` (an
uncancelable navigation where a cancelable one was promised) and a map carrying a
fifth key the lowering then silently drops. Everything else is
`:rf.error/hicasso-malformed-navigate`, naming the position; decorators do not
nest in either order. Classified and lowered once per render, beside the prevent
head, in `front/intent.cljs`.

**How far into routing it reaches — no further than the published seam.** The
href, payload and `:native?` come from `:routing/link-model` at render; the click
runs `:routing/activate-link!` — the substrate-neutral late-bound seams routing
publishes for exactly this consumer class, already consumed by `rf/route-link`,
`ui/route-link` and Freehand's `v/route-link`. Hicasso restates **none** of the
click law (caller veto first, modifier/auxiliary and native-anchor deferral,
`preventDefault` + dispatch to the render-captured frame with `:source :router`),
and the packaging graph stays `hicasso → core late-bind ← routing`. A missing
routing artefact fails at RENDER with `:rf.error/routing-artefact-missing` naming
the link's `:to`; a hook that vanished between render and click (dev hot-reload)
degrades to native navigation after the veto runs, never a throw at a detached
click.

**Composition with `::h/prevent` — the existing grammar is the veto.** The
caller's `:on-click` rides the `:veto` slot, and its roster is closed: nil,
`[::h/prevent [:app/event]]`, `h/fn`, or a plain fn. The prevent form lowers to
the ordinary prevent closure; routing runs it FIRST and stands down on
`defaultPrevented` — the navigation is cancelled and the app intent dispatched
instead, which is precisely the cancelable-navigation case HD-026 named. A BARE
intent vector is refused at render AND at lowering (the route click already
produces the one routing intent; an un-prevented second intent would be one user
action yielding two semantic events — Freehand's route-link law, kept). No new
composition machinery exists: `activate-link!` already honours `defaultPrevented`,
and the prevent head already sets it.

**Not a boundary, deliberately.** Freehand's `v/route-link` is a `defview`; here
that citation is declined. A Hicasso boundary costs two hooks and a row in every
boundary count, and the census's 106 links live INSIDE rows that are already
boundaries — an author byline is not a unit of re-render. `route-link` is a plain
function like the card: it inlines, mints no boundary, adds no hook, and reads no
subscription (the link model is a pure calculation), so the ≤2-hook budget and
every roster page's boundary/read arithmetic are untouched by links. The frame is
captured at render from the ambient binding the arm establishes
(`intent/*frame*`, bound by the 3-arity `with-frame` beside the ambient
dispatch), because a click fires after the render's dynamic extent has unwound —
Freehand's `require-current-frame!` precedent, in the collector's ambient idiom.

**Prior art, cited.** **Routing (taken whole):** the two-seam split and the one
click law. **Freehand (taken):** render-time frame capture; render-time
artefact-missing refusal; the one-intent-per-click law at the veto position.
**(Declined:** its fn-only `:on-click` roster — Freehand has no in-band spelling
for "cancel-and-replace"; Hicasso does, and admits exactly it.**)**
**Replicant via HD-026 (taken):** behaviour as a namespaced-keyword-headed vector
`=` can see — two renders of one link are equal data, and a structural test reads
the click decision off the tree. **(Declined:** Replicant's global body-level
click interceptor for routing — ambient behaviour no vector carries is what the
in-band school exists to avoid.**)** **Also declined for v0:** the
`:prefetch :intent` trio routing publishes and Freehand consumes — the census
counts no prefetch site, and the opt-in is sugar over an event a Hicasso author
can already spell at an ordinary intent position. **Declined means REFUSED, not
ignored**: any present `:prefetch` fails at render with
`:rf.error/hicasso-route-link-prefetch-declined`, `:intent` included. Routing's
`link-model` validates and ACCEPTS `:intent` while Hicasso installs none of the
three handlers behind the opt-in, so a key that merely fell through would leave a
link that prefetches nothing, says so nowhere, and carries a stray `prefetch`
attribute on the anchor.

**Rejected.** (b) A boundary (`defview`) route-link — costs two hooks per link
and pollutes every boundary count for a form that appears 106 times per census
(see "Not a boundary"). (c) A closure at `:on-click` (Freehand's own anchor
shape) — loses hiccup equality exactly where the census's most-repeated tier-1
form lives; HD-026's axis, again. (d) A codec-recognised element head
(`[::h/route-link …]`) — puts routing knowledge in the codec, which is the one
shared surface the school keeps semantics-free, and makes the link a special form
rather than a view.

**Cost, stated — and since MEASURED.** One reserved head joins the
roster-as-list (two members now), one `=` per lowered event position to classify
it, and three `route-url` syntheses per card per render on the roster pages —
priced on the routing artefact's own render path, the same cost every other link
surface pays.

`rf2-6c237`'s clock re-take put a number on that last clause and it was bigger
than the sentence implies: **8.21 µs per link at mount**, 207 links on the
acceptance page and 900 on the feed, enough to move both census rows back above
the line. `rf2-cno31` then decomposed it
([the route-link term page](studio/the-route-link-render-term-priced.md)) and
the ruling survives the measurement intact, for two reasons the profile
establishes:

- **77% of the term was routing's, not Hicasso's** — `route-url` synthesis
  (66.6%) plus the render-time strategy consult (10.2%). "The same cost every
  other link surface pays" was exactly right, and it is why the remedy landed in
  routing rather than behind `route-link`: cheapening it there would have left
  `rf/route-link`, `ui/route-link` and `v/route-link` paying the whole bill.
- **`route-link` is still a plain function.** Nothing was added to it — no memo,
  no cache, no hook, no boundary, no subscription read. The seam call it makes
  now costs **0.48×** what it did (7.07 → 3.38 µs measured in one session), from
  six specialisations inside routing that remember nothing.

A per-frame memo on the seam call was considered and **declined on the
evidence**: the regression is a MOUNT regression and a memo is empty at mount,
and this bench draws its bylines from four authors, so a memo's apparent hit
rate would have been a property of the fixture rather than of Conduit.

**Demonstrated, not asserted.** Equality and grammar refusals:
`front/route_link_cljs_test` (two renders `=`, the decision readable off the
tree, every malformed form loud, the veto composition mechanical). The browser
half: `shapes/route_link_dom_cljs_test` — the ported census card's three anchors
mount as real routed anchors, a real `MouseEvent` click installs the destination
through the routing cascade and the `[:rf/route]`-reading boundary re-renders,
a modifier click moves nothing, and the prevent veto cancels the navigation while
its unvetoed sibling (the mutation control) still navigates. **Reopens** if
dogfooding surfaces a real site needing `:prefetch`, or if the census's
zero-prefetch count stops describing the corpus.

## HD-028 — Value equality is the boundary default

**Amends [HD-006](#hd-006--memoization-defaults--amended-2026-08-02). This is the
evidence-driven overturn HD-006 itself provided for, not a change of taste.**

**Ruling.** A **value-equality bail-out is the DEFAULT at every minted Hicasso
boundary** — CLJS `=` over the complete `rfProps` value — implemented as a
**codec-level stable memo wrapper**.

**(a) The wrapper is internal, and the head stays a function.** `React.memo`
returns a memo *object*, and both the codec and the runtime's tests require a
minted head to BE a function. So `mint-view!`'s return value does not change:
`codec/memoize-boundary!` attaches one stable wrapper to the head and hands the
head back, and `boundary-element` creates elements from the wrapper. **No memo
object escapes as the public representation.** One wrapper per head, minted at
definition — a wrapper minted per element would be a fresh React element *type*
every render, and React would unmount and remount the subtree it was meant to
bail out of.

**(b) The comparator is `=` over the whole props value**, every prop included.
Function-valued props therefore compare conservatively unequal, which is correct:
distinct functions must not bail out. It fails **open** — `=` over an app-owned
value can throw, and this runs inside React's comparator where an escaping throw
is a render crash rather than a slow render (reagent-slim ruled the same polarity
on the same comparison, `rf2-5al9d7`).

**(c) Memoization may not outrank the boundary's own invalidation.** `useContext`
and `useSyncExternalStore` updates still re-render, per React's documented memo
contract: a commit hands the boundary's fiber its own `onStoreChange`, and React
tests `checkScheduledUpdateOrContext` *before* it consults the comparator — so a
boundary whose reads moved re-renders and the comparator is never asked. **Donor
precedent:** reagent-slim's default update check is argv `=`, and reactive
invalidation bypasses it via `forceUpdate`. Same shape here, by design.

**(d) Bodies stay pure and re-runnable.** Memoization is a scheduling
optimization, **never observable semantics**.

**(e) No public `:memo false` in v1.** A boundary that intentionally wants
parent-driven re-runs receives an explicit changing revision prop. No element
caches and no per-call-site switches; an opt-out is added only if a concrete case
proves the revision prop insufficient.

**Rationale.** HD-006 reasoned that narrow updates come from boundary placement
and that every default comparison is a cost every render pays. The first half
turned out to hold only for writes that do not touch a page boundary. The roster's
own instrument
(`shapes/narrow_dom_cljs_test/a-page-chrome-write-re-renders-no-unchanged-row`)
measured the exception and it is not exotic — it is tabs above a list, a filter, a
sort, a route param, the most ordinary page shape there is. On that shape the page
re-rendered and React re-rendered all 300 card boundaries beneath it, with every
card's inputs value-equal, on **precisely the axis HD-012's ≤ 1.0× Reagent bulk row
is set on** — and Reagent's default `shouldComponentUpdate` stops that cascade. A
framework that knows the right default and makes every programmer find it has
inverted its own posture.

**Prior art, mined rather than reinvented** (verified against the jars in the
dependency cache, 2026-08-02). **Reagent 2.0.1** has run this exact bail-out for a
decade: `reagent.impl.component/functional-render-memo-fn` is a `React.memo`
`areEqual` doing `=` over the whole argv, wrapped in a `try` that returns **false**
on a throw — i.e. re-render — and the memo is minted **once per component and
cached** (`cached-react-class`; the source comment reads *"the memo wrap is
required"*). Every structural choice above is therefore the incumbent's: `=` over
the complete value, a custom comparator because shallow identity cannot work, one
stable wrapper per head, and fail-open. **UIx 1.4.4**'s `uix.core/memo` defaults to
`=` over `argv` *plus* `:children` when present — Hicasso's `rfProps` already
carries `:children` as realized hiccup, so the compared value matches UIx's
without a special case, and hiccup children compare structurally where React
elements would only compare by identity.

**Declined, with reasons.** *Reagent's `*always-update*` dynamic escape* — its
comparator consults a dynamic var so `force-update-all` can bypass the bail-out for
hot reload. Hicasso does not need it: re-evaluating a `defview` re-mints the head
*and* its wrapper, which is a new React element **type**, so HMR replaces the
subtree rather than needing to force through a comparison. *Reagent's class-path
polarity* — stock `shouldComponentUpdate` catches a comparison throw and returns
`false`, which on that path means **skip**, i.e. fail closed; we take the
functional path's polarity instead, which is also the one reagent-slim ruled
(`rf2-5al9d7`). *UIx's opt-in posture* — `^:memo` on `defui` makes memoization a
per-component decision by the author; that is the posture this ruling overturns.
*UIx's unguarded comparator* — it has no `try`, so a throwing `-equiv` escapes
into React's render.

**Cost, priced rather than assumed.** The comparator spends **no React hook**, so
HD-020(b)'s ≤2-hook shell budget is untouched. It does spend **one extra Fiber per
boundary**: a memo carrying a custom comparator takes the full `MemoComponent`
path rather than collapsing into React's `SimpleMemoComponent`. That is recorded
in `runtime/retained-inventory` under `:react/memo-fiber` rather than left for a
heap ladder to find, and it was **measured before this ruling landed** —
[the page-chrome row](studio/the-page-chrome-row-and-what-the-bail-out-costs.md),
`:advanced`, `goog.DEBUG=false`, two runs:

| | plain | memo | |
|---|---:|---:|---|
| page-chrome write, 300 mounted rows | **300 card bodies** | **0 card bodies** | the repair |
| per-boundary retained heap | 11,018 / 11,004 B | 11,213 / 11,224 B | **+195 B, +220 B** |
| mount, 301 boundaries | — | — | 1.0089× / 1.0756×, ranges overlap |
| bulk / narrow / props | — | — | the two runs disagree on sign — indistinguishable |

The heap delta reproduces at **~200 B per boundary**, which is a Fiber. The clock
rows do not separate from run-to-run noise at this round count, so the mount and
bulk cost is stated as a **bound (≲10%)** rather than a figure. On the card-shaped
boundary actually measured, 200 B is **+1.8%**. Against the **R=0 shell** — the
figure validation.md's paper line is stated against, which the ladder put at
1,143 B at the time, already over the 1 KB line — the same 200 B would be ≈ +17%.
Both readings are true, they are not interchangeable, and the shell one had to be
re-taken on the ladder's own instrument. *(Both figures in this paragraph are the
2026-08-02 state; the shell has since moved and the re-take below is the current
one.)*

**The shell re-take has since been done twice.** The first pair
(`rf2-2rtt6.58`, 2026-08-02) was taken on `worker/cascade-2rtt6-52`, and its
audit found the blobs did not match the post-#7390 landed implementation, so it
does not stand as the current price. **The pair below was re-taken on
2026-08-04 against `origin/main` `81321da3fe`** — `A → B → A`, one session, box
verified quiet before each run, exit 0 on all three
([the rows](studio/reads-per-boundary-heap-ladder.md#the-memo-wrapper-re-taken-on-the-tree-that-ships-rf2-2rtt658-re-take)):

| R=0 shell | no wrapper | wrapper | delta | vs the 1 KB line |
|---|---:|---:|---:|---|
| Hicasso, Reagent segment | 994 B [985–1,003] | **1,099.5 B** [1,088–1,112] | **+105.5 B, +10.6%** | 0.99× → **1.10×** |
| Hicasso, UIx segment | 992 B [985–998] | **1,097 B** [1,092–1,105] | **+105.0 B, +10.6%** | 0.99× → **1.10×** |

*(The 2026-08-02 pair read 1,141 → 1,247 B and 1,138 → 1,236 B: +106 B / +9.3%
and +98 B / +8.6%. The **delta reproduced to within 1 B on both segments**; the
base fell ~140 B under `rf2-aqgr2` and `rf2-dabt3`, which is where the
percentage and the disposition move.)*

**≈ +100 B, not ≈ +200 B — the ≈ +17% projection above over-estimated it by
about a factor of two.** The A/B ranges are disjoint, the donors do not move, and
the per-read slope is **identical to the byte** with and without the wrapper
(1,278 and 2,115 B/read on the 2026-08-04 tree; 1,447 and 2,289 on the
2026-08-02 one), confirming this ruling's claim that the cost is
constant in R and lands in the shell. ~~The two instruments differ because the
ladder mounts and *holds* while the page-chrome rig writes first, and an updated
component retains an `alternate` fiber a held one does not — offered as the
leading explanation, not as a counted result.~~

**Amended 2026-08-03 (`rf2-2rtt6.61`): that explanation is withdrawn, and it was
never a counted result.** Its premise was that the page-chrome rig writes before
it reads heap, and it does not — `chrome_run.cjs` runs its whole heap half first
on a quiet page (line 243) and does not touch the four write ops until the clock
half at line 264, with the same ordering at `cb179b6b3c`, the commit that
published the +195/+220 rows. **Both instruments mount and hold**, so no
`alternate` fiber exists on either, and the rule it implied — one fiber held, two
updated — is not adopted. Both deltas stand as measured against the shape each was
taken on; what separates them is open as `rf2-2rtt6.79`. The ruling itself is
untouched: the wrapper is a per-boundary constant in R, and **a Fiber is not a
fixed byte count — the shape it sits on decides what it costs.**

**What remains open is the disposition, and it is the operator's.** Whether
+10.6% is "pushing retained heap meaningfully farther past the bar" is not
answered by any text: the clause is unquantified here, in validation.md and in
the heap-regime ruling.

**Amended 2026-08-04 (`rf2-2rtt6.58` re-take): the argument this paragraph used
to make no longer holds.** It read *"the Reopens clause below is worded as
failing the bar — which this shell did at 1,141 B before any wrapper existed, so
the wrapper widens a pre-existing failure rather than causing one."* On the tree
that ships, the shell without the wrapper reads **994 / 992 B** — not over the
line — and with it **1,099.5 / 1,097 B**, which is. `rf2-aqgr2` and `rf2-dabt3`
took ~140 B out of the shell in between, and **the wrapper is now the thing that
carries it across.** Two cautions belong with that, both in the operator's
favour rather than the ruling's: 994 B is 6 B under 1,000 and its own per-round
band straddles the line, so the no-wrapper arm is *at* the line and not
distinguishable from it, while the wrapper arm clears the line by more than
validation.md's ~75 B shape sensitivity in **every** measured round. The
`Reopens` clause is therefore live in a way it was not on 2026-08-02, and
**this ruling's own pre-registered fallback is the one on the table**: retain
HD-006 and ship the same comparator as an explicit boundary-level opt-in. No
candidate-bar row has been written into validation.md either way.

Still one thing the measurement does **not** settle:
whether the mount row carries a real few-per-cent regression, which needs
`clock_run.cjs`'s adjudicated M1 row.

**Rejected.** *Keep HD-006 and teach `React.memo` + `areEqual` as an opt-in
pattern* — inverts the posture and ships a known 300× re-render cascade on the
most ordinary page shape there is. *Element-identity caching at the call sites* —
fights React's grain with exactly the caching machinery HD-004's anti-fence exists
to refuse, and makes every `for` site a performance decision.

**Reopens** if the priced cost moves: if the extra Fiber is later shown to fail the
retained-heap bar on a shape that matters, the same comparator ships as an explicit
boundary-level opt-in and HD-006 is restored as the default.
