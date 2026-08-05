# The Reagent `[:>]` migration codemod — adjudicated (rf2-2rtt6.106)

**This page is not a first design.** A design pass and an adversarial pass were both run
on 2026-08-04 and the operator recorded a ruled synthesis on the bead the same evening
(2 FATAL / 5 MAJOR / 7 MINOR, verdict *build with the named repairs*). Both documents live
in the gitignored `ai/findings/` tree, so nothing outside the machine that produced them
can read them, and the bead comment that carries the verdict cites them by paths no
maintainer has.

So this page does three things. It **promotes** what survives into the tracked record. It
**adjudicates** that record against `main` as it stands today — five codec landings and two
guide landings later, and every line citation in the original already rotted. And it
**re-opens one question the original never asked**, because the adjudication turned the
answer over: the tool the synthesis specifies is two tools welded together, one of which
carries all the value and none of the machinery.

**No number about the runtime is published here.** The tool is unbuilt and its destination
is unbuilt. The one measurement this page does publish is a **count over source text** —
every `[:>]` site in this repository, classified — and §4 states its instrument, its
denominator and its bias.

---

## 1 · Provenance, and what each input is worth

| Input | Status | What it contributes |
|---|---|---|
| `ai/findings/2026-08-04.codemod-design.md` | local-only, gitignored | 13 verified donor/codec facts, six rewrites, the report roster, the residence argument, the corpus-as-spec discipline |
| `ai/findings/2026-08-04.codemod-attack.md` | local-only, gitignored | 2 FATAL / 5 MAJOR / 7 MINOR; the governing-law repair that makes the rewrites properties of the crossing rather than of the hoist |
| The bead's 12:52 synthesis comment | **binding on shape** | The ruled tool, its residence, its governing law, its dedupe and report rules |
| [The `[:>]` synthesized spec](raw-escape-spec.md) | tracked, **binding on the destination** | What `[:>]` actually is when it lands — and it is not what the codemod design assumed |
| This page (2026-08-06) | tracked | Promotion, re-verification, and the candidate question the original did not put |

*(3 columns; 5 body rows; hand-counted.)*

Where this page and the bead comment differ on **shape**, the bead comment governs and this
page records the dissent as a dissent. Where they differ on **fact**, this page governs:
every fact below was re-derived against `main` at `5a08b14a29` on 2026-08-06, and §6 lists
the ones the synthesis carries that have since become false.

**Citation discipline.** Everything below cites **by symbol name**. The 2026-08-04 pair cite
by line, roughly sixty times, and the sample re-checked today had moved by up to **462
lines in 48 hours** (§6.5). An implementer briefing from those citations reads the wrong
code.

---

## 2 · The destination does not exist, and that is the design's spine

`rf2-2rtt6.103` is **open**. `[:>]` is ruled and unbuilt: `hiccup-tag?` in `front/codec.cljs`
still accepts any keyword that is not `:<>`, so `vec->element` routes `[:> Foo {}]` to
`native-element` and asks React for an element literally named `<>`. The escape's spec
merged; the escape did not.

Three consequences, and the design has to take all three rather than sequence around them.

**(a) A tool whose output includes `[:> …]` cannot be validated end to end today.** The
bead's synthesis handles this by sequencing implementation after `.103` merges. That is
correct and insufficient: sequencing protects the *build*, not the *design*. A corpus
written today pins a **paper** destination, and `raw-escape-spec.md` §8 lists six facts
"that will bite" the implementer of `[:>]` itself. If any of them moves the crossing's
conduct, the corpus is wrong and the tool that passes it is wrong.

**(b) But only half the tool depends on that.** The rewrites that repair Reagent's props
dialect are arguments about **two landed conversion tables** — reagent 2.0.1's
`convert-props`/`convert-prop-value`, and Hicasso's `host-entry`/`host-prop-value`. Both are
code today. Those rewrites are correct at a `defhost` call site *now*, and correct at a
`[:>]` site under the escape's own ruling that its props take *"the landed unclaimed-slot
conduct of `host-entry` … with no branch of its own"* ([raw-escape-spec.md](raw-escape-spec.md)
§3). The **hoist** — mint a declaration, name it, place it, inject the require — is the half
whose equivalence argument runs through machinery that does not exist.

**(c) The escape's acceptance set is deliberately narrower than the door's**, which breaks
one sentence the design leans on. `raw-escape-spec.md` §5 rules a **pinned component roster
refused at the crossing** for `[:>]`; `mint-host!` refuses only `nil`. So the hoist moves a
value from the narrow door to the wide one. The design's R1 states its equivalence as the
shared walk *"refusals included"* — true at prop positions, **false at the head**. Nothing in
Reagent input trips it (Reagent accepted everything at that slot too), but it cannot be used
as the safety argument, and §7.2 records what it is replaced by.

---

## 3 · The migrator's problem, from the migrator's chair

They have a Reagent namespace containing `[:> Component {…} …]` forms. Under Hicasso, once
`[:>]` lands, **that form is legal and keeps its shape.** So the migration is not a
port — the head, the props map and the children all survive verbatim. What does *not*
survive is a set of conversions Reagent performed silently on the way through, and which
Hicasso deliberately does not.

| What the author wrote | Reagent 2.0.1 delivered | Hicasso delivers (host walk) | How it fails |
|---|---|---|---|
| `{:style {:font-size 12}}` | `{fontSize: 12}` — `kv-conv` recurses through map chains via `cached-prop-name` | `{"font-size": 12}` — `host-prop-value`'s `coll?` arm is `clj->js`, and its docstring says so in terms | **silent.** React ignores hyphenated style keys; the element is simply unstyled |
| `{:variant :contained}` | `"contained"` — `convert-prop-value`'s `(named? x) (name x)` arm, at every prop of every element including `:>` | the Keyword object — `host-prop-value` keeps a named value except at `html-attr-slots` | **silent.** The library reads an object where it expected a string |
| `^{:key (:id x)} [:> C …]` | live — `native-element` sets `.-key` from `(meta argv)` *after* `convert-props`, so metadata even beats a props `:key` | dead — no Hicasso path reads `(meta argv)` | **silent, and worse than silent** (§6.3) |
| `{:on-select (r/partial f a)}` | callable — `convert-prop-value`'s `ifn?` arm wraps it fresh per conversion | an inert object — neither walk has an `ifn?` arm | **silent.** A working handler stops firing |
| `[:> "input" …]` | Reagent's controlled-input wrapper — `input-component?` matches on the `:>` path | refused: the ruled `[:>]` does not accept strings | **loud**, at the crossing |

*(4 columns; 5 body rows; hand-counted.)*

**Two of those five are already taught.** `05-interop.md`'s troubleshooting table carries
*"Prop arrives as `on-change` and the library ignores it"* and *"The library ignored a
keyword prop value"*, each with the mechanical fix. They are taught as **troubleshooting** —
which is to say, they are taught to the reader who has already shipped the bug and come
looking. That gap between "documented" and "prevented" is the entire case for building
anything.

And the manual recipe is already written, in the same page: *"A hand migration from Reagent
is three moves per namespace: collect `[:> X …]`, emit `defhost`, rewrite call sites."*
Note what those three moves are: they are **the hoist**. The recipe does not mention a
single one of the five rows above. **The documented manual path covers the half a machine
does not need to do, and is silent on the half a machine is uniquely good at.**

---

## 4 · What a machine can decide — measured, with the instrument stated

The 2026-08-04 design names its own weakest point as an unmeasured thesis: *"the 'most sites
are literal' value thesis is unmeasured against any real corpus; if it is false, the tool is
mostly a reporter."* It is measurable here, and it was not measured.

**Instrument.** `git grep -n -- '\[:> '` over tracked `.cljs`/`.cljc` under
`implementation/`, `tools/`, `examples/` and `testbeds/` at `5a08b14a29`; 51 hits read by
hand and split into live call sites versus prose (docstrings, comments, `testing` strings)
and unrelated grammars (`schemas/walker`'s `[:> 10]` comparator). **28 live sites**, in two
populations that must not be pooled.

| Population | Sites | Head decidable | Props slot decidable | Site-local rewrites fired |
|---|---|---|---|---|
| `tools/machines-viz/` — a real Reagent app doing xyflow interop | 13 | 13 | 13 | **0** |
| `implementation/adapters/**` conversion tests + SSR tests | 15 | 15 | 15 | 9 |

*(5 columns; 2 body rows; hand-counted.)*

**The application population is the one that matters, and it says something uncomfortable.**
All thirteen sites are a bare var or a property chain at the head (`ReactFlow`, `Handle`,
`BaseEdge`, `EdgeLabelRenderer`, `Background`, `Controls`, `MiniMap`), and every index-1 slot
is a literal map, a hiccup vector, or absent. **Not one of the five site-local rewrites
fires anywhere in it**: no `^{:key}` metadata, no keyword prop values, no `r/partial`, no
`:class` collections, and its only nested map literals are `{:opacity 0}` and `#js {…}` —
the first a camelCase fixpoint, the second already a JS object on both walks. The dedupe is
real: `Handle` appears seven times across three namespaces, `[:>]` thirteen sites collapse
to six declarations.

The adapter population is **built to hit the conversion edges**, so it hits them — keyword
values twice, `^{:key}` metadata once, string tags four times, absent props twice. It is
evidence that the design's classes are the right classes. It is **not** evidence of their
frequency, and quoting it as such would be quoting a fixture.

**So the fraction, stated honestly.** On the only application corpus available, a machine
decides the head and the props slot at **28 of 28**, and the class the design feared —
a non-literal at index 1, undecidable because `props-map?` is a runtime `map?` — occurs
**zero times**. Human judgement is needed at **0 of 28** sites to keep them working, because
`[:>]` is legal and an undecided site is simply left alone. Where human judgement is needed
is the `:computed-values` class: 2 of 13 application sites carry symbol- or call-valued
props at slots that could hold maps, and a machine can only name them.

**Three ways that number could be wrong**, all of which the operator should weigh before
spending anything on it:

1. **N = 28, one repository, and `machines-viz` is a tool rather than a product.** A
   consumer app with a props-building helper (`(merge base extra)` at index 1) inverts the
   result and the design's weak point becomes binding.
2. **`machines-viz` is not migrating.** It is Reagent code that stays Reagent. Its shapes are
   the right shapes; its *author's intent* is not a migrator's.
3. **The two audits that would have settled this are gone.** `rf2-cgcv` (re-com,
   re-frame-10x) and `rf2-kfpf` (Dash8, rf8) inventoried exactly this surface across four
   real Reagent codebases and both were written to `ai/findings/` — gitignored, never in git
   history, **unrecoverable**. The only surviving datum is a code comment in
   `reagent-slim`'s `template.cljs`: *"The audit (rf2-cgcv + rf2-kfpf) showed a small number
   of legitimate non-HTML keyword props in production code."* That sentence is a measured
   claim about one of the five rows in §3, and it is all that is left of it.

---

## 5 · The candidates, priced

The design was written with one candidate. There are five, and the do-nothing arm is
serious.

### A · Do nothing; the recipe is already published

The three-move recipe is in `05-interop.md`; two of the five silent classes are in its
troubleshooting table.

*Costs the migrator:* three mechanical moves per namespace, plus a manual sweep for two
classes that produce **no error, no warning and no visible symptom** until someone notices
the styling is gone. *Costs us:* nothing. *Forecloses:* nothing.
*How it could be wrong:* it is wrong exactly to the extent that the silent classes are
common — and §4 measures them at zero in the application corpus and non-zero in the
conversion tests, which is a genuinely ambiguous reading.

### B · The dialect fixer — rewrites, no hoist

Apply the site-local rewrites and their guards at **every** `[:>]` crossing, rewrite string
tags to native tags, report everything unprovable, and **never touch the head, never mint a
declaration, never edit an `ns` form.**

*Costs the migrator:* one run, one report, and the hoist stays theirs — which is three moves
they were going to do anyway and can do while reading their own code. *Costs us:* one
artefact whose whole content is the **diff between two landed conversion tables**. It
deletes, outright, the four largest pieces of machinery in the synthesis: the binding
analysis (the attack's M1), the textual-order precondition (M2), the `ns`-form injector
(M5), and the collision roster with its embedded `cljs.core` name list (m5) — three of the
five MAJOR findings and both MINOR ordering findings go with them. The corpus drops from
~40 cases to ~15. *Forecloses:* nothing — the hoist is strictly additive, and the fixer's
output is exactly the hoister's input.
*How it could be wrong:* it concentrates all of the fatal-class risk and drops all of the
loud-failure machinery (§7.1). And if hand-hoisting is the pain the migrator actually
reports, B has solved the other half.

**B does not depend on `[:>]` being built.** It changes no head and mints no declaration, so
its output is exactly as runnable as its input, and its correctness argument runs entirely
through code that shipped. Its one residual dependency is a **ruling**, not a discovery:
that `[:>]` props take `host-entry`'s conduct with no branch of its own — which is the
`[:>]` spec's own spine, and which is separately true today at every `defhost` call site.

### C · The full tool — fixer plus hoister, as the synthesis specifies

*Costs the migrator:* the same run, and it produces the declarations. *Costs us:* everything
B costs, plus the `ns`-form surgery the attack calls *"the largest novel machinery … with no
sibling precedent"*, plus a naming and collision scheme, plus a per-site binding analysis
that the attack proved does **not** transfer from the sibling codemod. And a permanent
maintenance liability tracking **two** moving APIs — Reagent's and ours.
*Forecloses:* nothing, but it spends first.
*How it could be wrong:* on §4's measurement, the hoist buys 28 sites → 6 declarations, of
pure ergonomics, in code the author is reading anyway. The synthesis's own framing —
*"the codemod's job narrows to executing 'declare what you use twice'"* — is precisely the
job §4 says is cheapest to do by hand.

### D · A runtime dev warning instead — the `reagent-slim` precedent

The repository has already met this exact problem once. `reagent-slim` narrowed keyword
stringification underneath an installed Reagent codebase and shipped
`warn-once-keyword-prop!` as that migration's safety net — dev-only, once per
`[prop-name, value-name]` pair, informational rather than deprecating. It catches
**computed** values, which no source tool can reach.

**Refused, and not on my preference.** `host-prop-value`'s own docstring rules it out by
name: *"`reagent-slim` warns once per non-HTML keyword prop because it narrowed the rule
underneath an installed Reagent codebase … Hicasso has no such codebase to protect, and
after this change a keyword at a host prop is the CORRECT and taught spelling of HD-011's
flagship case — warning on the happy path is a nag, not a diagnostic."* That is a landed
ruling on the exact question, and it is right: the two cases differ in that `reagent-slim`
*is* the migration target and Hicasso is a different framework. A migration-scoped opt-in
flag would evade the ruling and is over-engineering.

### E · Report only, no writes

Cheaper than B by the cost of the writes. But the writes are where the value is: at a site
where the rewrite is provable, leaving it to a human is leaving the machine's one advantage
on the floor. E is B with the good part removed.

### Recommendation

**B, with A as the honest fallback and C's hoist deferred until someone asks for it.** The
reasoning is one sentence: the half of the tool that prevents silent breakage costs a fifth
of the machinery and can be built today, and the half that saves typing costs four fifths
and cannot be built until `[:>]` lands. **This is a recommendation and not a ruling** —
§8.1.

---

## 6 · The adjudication — what two days did to the 2026-08-04 record

Five codec commits and two guide commits landed between the design and this page.

### 6.1 · R5 is dead: `rf2-2rtt6.119` fixed it at the door

The design's fact 11 states that the host walk *"emits a `clj->js` ARRAY today (→ `"a,b"` in
the DOM)"*, and rewrite R5 pre-joins literal `:class` collections to answer it. The bead's
synthesis names it as governing-law item **(3)**: *"literal `:class` collections → the ruled
spelling (else 'a,b' strings)"*.

**False since `a00435fd33`.** `host-entry` now carries a `class?` arm that coerces and
composes through `class-names`, exactly as `convert-entry` does at a native tag, and it sits
below the declared-contract arm. `host-prop-value`'s docstring records the change: *"`className`
no longer arrives here at all (rf2-2rtt6.119)."* `raw-escape-spec.md` §8 carries the dated
correction and notes that the escape inherits the repair by construction.

**Consequence:** R5 becomes a rewrite whose output is byte-different and behaviour-identical
— i.e. churn — and the `:class-coll-computed` report class describes a defect that no longer
exists. **Delete both.** Governing-law item (3) has no referent.

### 6.2 · A sixth rewrite the design does not have — and it is the most common one

Neither the design's 13-fact table nor the attack's verification sweep has a row for
**top-level named prop values**. Re-derived from the jar and the codec today:

- reagent 2.0.1 `convert-prop-value` is `(cond (util/js-val? x) x (named? x) (name x) …)` —
  applied at every prop of every element, and the `:>` path reaches it through
  `native-element` → `convert-props` → `kv-conv` like any other.
- Hicasso's `host-prop-value` is `(cond (fn? v) v (or (keyword? v) (symbol? v)) (if
  (html-attr-slot? slot) (name v) v) (coll? v) (clj->js v) :else v)` — the named value
  crosses **whole** except at `className`, `id`, `role`, `data-*` and `aria-*`.

So `[:> Btn {:variant :contained}]` handed the library `"contained"` under Reagent and hands
it a Keyword object under Hicasso. This landed as `rf2-vrvv9` (`40f663edad`) on the same day
the design was written, and for the right reason — `(name v)` collapsed `:theme/dark` and
`:other/dark` onto one string. The migration cost is real all the same, and it is silent.

**R7, and it is mechanical:** a literal keyword or symbol at a prop value becomes its
`name` string, which is exactly what Reagent emitted. String output is a fixpoint, so
idempotence holds. Three cells the rule needs:

1. **Namespaced keywords rewrite to the bare name and take a report line.** `:theme/dark` →
   `"dark"` preserves Reagent exactly and simultaneously bakes in the collision `rf2-vrvv9`
   was filed to remove. Behaviour preservation is the law, so the rewrite fires; honesty is
   also the law, so the line says what was lost.
2. **`html-attr-slots` are fixpoints** — both systems `name` there — so the rule needs no
   slot table of its own.
3. **`:key` included.** Reagent ran `:key` through `kv-conv` like any prop, so
   `{:key :foo}` reached React as `"foo"`; Hicasso's `host-element` lifts `(:key props)`
   raw and React coerces the Keyword to `":foo"`. One-time and harmless, but the rule is
   cheaper applied uniformly than excepted.

**Nested** named values need nothing: `host-prop-value`'s `coll?` arm is `clj->js`, which
`name`s keywords inside. And **R3's destination needs nothing**: `convert-prop-value` at a
native tag *does* carry `(or (keyword? v) (symbol? v)) (name v)`, so the native walk agrees
with Reagent here. The narrowing is host-side only — a cell the design's fact 5 does not
cover, because fact 5 is about map *keys*.

### 6.3 · The `^{:key}` class has no net at all — `rf2-2rtt6.104` does not reach it

The attack's F1 witness reasons that a dropped metadata key leaves *"React dev-warns, prod is
silent; .104's minted warning is dev-only"* — reading `.104` as partial cover.

`.104` has since landed (`9523b6fbdb`, `fa3fe9dd29`, `589853f53d`). **It does not cover this
at all.** `check-member-key!` reaches its warning only under `(boundary-head? h)` — a
`defview` product. A minted host head is not one, and neither is `:>`. So a seq of migrated
crossings with dropped metadata keys gets **nothing** from Hicasso, at any build. The
warning's own text is the sharpest evidence that the class is known: *"a key written as
Reagent metadata is not read here"* — said to the author of a `defview` child, and to nobody
else.

**Consequence:** R2 (`^{:key k}` → props `:key`) is the tool's highest-value rewrite, not one
of five equals, and the "loud runtime net" argument the design leans on everywhere is at its
weakest precisely here. §8.2 files the door-side question.

### 6.4 · Two dependencies resolved, one still open

| Bead | 2026-08-04 disposition | Today | Effect on the tool |
|---|---|---|---|
| `rf2-2rtt6.119` | open; *"check at implementation time"* | **closed**, fixed at the door | R5 and `:class-coll-computed` deleted (§6.1) |
| `rf2-d03av` | open; *"if it lands enforcement, migrated sites refuse loudly"* | **closed** as *untaught but legal* — no enforcement landed | nothing to do; drop the contingency sentence |
| `rf2-2rtt6.103` | open, blocking | **still open** | §2 |

*(4 columns; 3 body rows; hand-counted.)*

### 6.5 · The citation apparatus has rotted

Sampled against `5a08b14a29`:

| Cited as | Today | Drift |
|---|---|---|
| `host-prop-value` at codec.cljs:1523-1540 | `host-prop-value` at :1985 | **+462** |
| `mint-host!` nil refusal at codec.cljs:1114-1121 | `mint-host!` at :1256 | +142 |
| `host-entry` at codec.cljs:1568-1613 | `host-entry` at :2077 | +509 |
| guide 05 *"A codemod is planned and not built"* at :164 | :235 | +71 |
| guide 05 *"Migration codemod \| Planned, unbuilt"* at :286 | :371 | +85 |

*(3 columns; 5 body rows; hand-counted.)*

`raw-escape-spec.md`'s own sources block already learned this and says so: *"This bullet
carried line numbers until 2026-08-05 and three of the five were wrong by then … the file
moves faster than a record of it can be trued up."* The 2026-08-04 codemod pair are the last
records in this programme still citing that way. Their **facts** re-verify; their
**addresses** do not.

### 6.6 · What survives untouched

The fence (`[:>]` sites only; no `r/atom`, no form-2/3, no view-body work). Corpus-as-spec
with byte-for-byte expected output and asserted idempotence. Report-never-synthesize —
`:callbacks` promotion stays a suggestion. Whole-file writes; a parse failure skips the file
entirely. The sibling skeleton at `migration/from-re-frame-v1/codemod/` — standalone
`deps.edn`, **rewrite-clj 1.1.49, already the repo's only rewrite-clj dependency**, cognitect
`:test`, `clojure -M:run`. The attack's governing law: the site-local rewrites are properties
of the **crossing**, not of the hoist. R2, R4 and R6 and their guards. And the residence
argument — `migration/`, not `tools/` (whose charter is Spec-009 consumers) and not
`scripts/` (repo-internal), noting that `migration/**` is staged under `docs/` by the
fast-PR spine, so the artefact's README enters the docs gates.

---

## 7 · The adversarial pass on this page

Briefed to refute §5's recommendation and §6's additions. Four charges; two survive as
recorded costs, one forces a repair, one is withdrawn.

### 7.1 · "B keeps the dangerous half and throws away the safe half" — **survives as a cost**

It is true and it is the strongest charge. The fixer **writes to props**; a wrong rewrite is
a silent behaviour change, which is the one fatal class. The hoister only moves a head and
adds a declaration: when it is wrong it is wrong *loudly* — a compile error, a `mint-host!`
refusal, a `:rf.error/hicasso-bad-head`. B therefore concentrates every fatal-class risk and
discards every loud-failure component.

It does not reverse the recommendation, because the answer to concentrated fatal risk is a
**smaller rule set with an exhaustive corpus**, not a larger tool with a safe half bolted on.
But it changes B's price in one specific way, and the change is recorded rather than argued
away: **the attack's M4 obligation gets bigger under B, not smaller.** M4 asks for ~6–8 named
`codemod-contract-*` rows in the door's own suite, pinning the conversion equivalences the
rewrites rely on, so that a door PR which drifts them goes red with "codemod" in the failing
test's name. Under C those rows are one tripwire among several. **Under B they are the only
executed evidence the tool is correct at all**, because a text corpus can only pin text. They
are not optional and they are not deferrable.

### 7.2 · "The hoist is behaviour-preserving, so §2(c) is pedantry" — **withdrawn, and replaced**

I set out to show the hoist changes React's reconciliation. `[:>]` mints **one shared
module-level gate** for every crossing on the page (`raw-escape-spec.md` §2); a hoisted
`defhost` mints a per-declaration gate. So at a position that alternates between two
components — `(if x [:> A {}] [:> B {}])` — the escape keeps one gate fiber and remounts only
the inner subtree, while the hoisted spelling sees two distinct types and remounts the whole
crossing.

**Checked, and it is observably equivalent.** In both readings the inner component remounts;
what differs is the gate fiber, and a gate carries no frame, no subscription and no body —
its only state is a `useSyncExternalStore` over three module-level constants that reads
`true` for the life of the page after first adoption. There is nothing to lose. The charge
is withdrawn.

What replaces it is smaller and real: **`displayName` changes from the constant `"[:>]"` to
the minted name**, and the acceptance set widens (§2(c)). Neither is a behaviour change; both
belong in the hoist's report line rather than in its equivalence argument, and the design's
*"refusals included"* sentence must be struck.

### 7.3 · "R7 is not behaviour-preserving for namespaced keywords" — **forces a repair**

`:theme/dark` → `"dark"` is what Reagent did, so the rewrite preserves behaviour and
simultaneously re-creates the collision `rf2-vrvv9` closed. Both halves are true, and the
governing law does not adjudicate between them. Repaired in §6.2 cell 1: **rewrite, and
report the loss by name.** A tool that silently writes `"dark"` for `:theme/dark` and says
nothing is a tool that hides a decision.

A second cell fell out of the same pass and is the reason this charge is worth its space:
**`:key` was a prop under Reagent and is structural under Hicasso** (`host-entry` skips it
in-loop; `host-element` lifts `(:key props)` raw), so a keyword `:key` reaches React as
`"foo"` before the migration and `":foo"` after. Nothing in the 2026-08-04 pair covers it.

### 7.4 · "R4 preserves behaviour" — **survives, with one cell corrected**

R4 respells nested kebab keys the way Reagent's `dash-to-prop-name` did, and the design
notes that `--`-prefixed custom properties are left as written because Reagent *mangled*
them. That is correct and it is not preservation: leaving them alone means the migrated site
**starts working**, where it never worked before. Under a governing law of "never silently
change behaviour", a repair is a change. It is the right repair — it must simply be a report
line rather than silence.

### 7.5 · Where this page's own weakest point is

**§4's measurement is one repository and 28 sites, and it is doing a lot of work.** It is the
basis for "the hoist is ergonomics" and therefore for the recommendation. Its application
half is thirteen sites in one tool by one author against one library. If a real consumer
corpus reads differently — particularly if computed props maps at index 1 are common — the
coverage argument moves and B's report grows relative to its rewrites. The mitigation is not
a bigger tool; it is that §4 states the instrument, the denominator and the bias, so the next
reader can refute it with one better corpus rather than inheriting it.

---

## 8 · What needs a ruling

**Nothing below is decided here.**

### 8.1 · Hoist, or no hoist (B versus C)

This is the operator's, and it is not a fact question — both arms are correct and buildable.
The record supports B: the hoist's machinery is four fifths of the tool and the measurement
says it buys six declarations. The record also supports C: the bead's synthesis is a ruled
shape, the migrator asked for "declare what you use twice", and B leaves them the typing.
**Recommendation: B**, with C revisited if a real migration reports the hoist as the pain.
Either way the rewrites are the same rewrites and the corpus rows for them are the same
rows, so **starting with B forecloses nothing** — which is the honest reason to prefer it.

### 8.2 · Should the minted key warning reach host and `[:>]` children? (door-side)

`check-member-key!` gates on `boundary-head?`. A seq of unkeyed **host** children carries the
identical hazard and gets no line (§6.3), and for a Reagent codebase that is the entire
keying idiom going dead at once. `.104`'s own rationale — that React's warning dedupes on the
parent tag name and goes silent at later sites — applies unchanged to host children. Against
it: this codec has costed a `keyword?` short-circuit at ±51–67% of a walk, so a fourth
predicate on the member path is not free, and `.104` already measured its check at ~150 ns
per member in a dev build. **Filed as a question, not proposed as work.**

### 8.3 · The build may move the ground

`raw-escape-spec.md` §8 lists six facts that will bite `[:>]`'s implementer, and §7 leaves
four rulings open (of which `rf2-2rtt6.116` is live and touches prop conduct). The rewrites
in §6 rest on `host-entry`'s landed conduct plus the ruled zero-fork, and that is as far as
the record reaches. **If `.103`'s build discovers that the escape's props take any branch of
their own, this design's premise moves and the corpus must be re-derived.** That cannot be
settled from the record and this page does not try.

---

## 9 · If it is built — the surviving spec

Stated for B; every clause is also a clause of C.

**Residence.** `migration/reagent-to-hicasso/codemod/`, cloned from
`migration/from-re-frame-v1/codemod/`: standalone `deps.edn` (clojure 1.12.4, rewrite-clj
1.1.49), cognitect `:test`, `clojure -M:run`. Source-text operation; re-frame2 never loaded.
Its README enters the docs gates, because the fast-PR spine stages `migration/` under
`docs/`.

**Input dialect.** `[:> …]` sites, plus `(r/adapt-react-class X)` in head position, alias-
resolved from the `ns` form. Under B a `(def b (r/adapt-react-class X))` **def site is
report-only** — the attack's F2 exists because rewriting a def changes every call site's
conversion regime, and B has no hoist to make that trade with. `:r>` (raw JS props), `:f>`
(a Reagent function component) and `r/as-element` islands are named report lines, never
rewrites.

**The rewrites.** R2 `^{:key k}` → props `:key`. R3 string tag → native tag. R4 nested literal
kebab keys → Reagent's own camel spelling, recursion stopping where Reagent's stopped (at any
vector, set or list) and not applied at R3 destinations, which convert deeply themselves.
R6 literal `(r/partial f a b)` → `(fn [& args] (apply f a b args))`. R7 literal named prop
values → their `name` string (§6.2). **R5 is deleted** (§6.1). Every one applies at **every**
crossing site, because each equivalence argument is about the crossing and never mentions
the head.

**The guards, which demote a site to a report rather than rewriting it.** `dangerouslySetInnerHTML`
anywhere in a rewritten site — Reagent *deleted* it unless `UnsafeHTML`-wrapped, in
`convert-props`, and both Hicasso walks resurrect it. A vector or key-map literal at an
event-spelled prop under R3 — inert under Reagent (`coll?` precedes `ifn?` in
`convert-prop-value`), live at a native position under Hicasso: the one truly fatal class,
reported and never rewritten. A literal `:&` key, which meant a prop named `"&"` under
Reagent and means the merge under Hicasso. A `:key` present in both metadata and props with
different forms.

**Report classes**, on the sibling's finding-map shape with `:action :flag`: `:dynamic-head`,
`:computed-props`, `:computed-values`, `:namespaced-keyword-value` (§7.3), `:css-var-repair`
(§7.4), `:adapt-def-site`, `:cljc-site`, `:macro-site`, `:r>-site`, `:f>-site`,
`:reagent-component-head`, `:reagent-api-residue`, `:dangerous-html`, `:amp-key`,
`:key-conflict`, `:string-tag-unparseable`, `:dynamic-string-head`, `:ifn-carrier`,
`:event-carrier-was-inert`, `:event-carrier-goes-live`, `:parse-error`. Plus the suggestions
block: per component, the event-spelled and fn-carrying slots with a ready-to-paste
`:callbacks` declaration — **suggested, never written.** No `--promote` flag.

**Witnesses.** `corpus/<case>/{input.cljs, expected.cljs, report.edn, post-report.edn}`, one
JVM harness deftest asserting text equality, report equality, and idempotence
(`rewrite(expected) == expected` with `post-report.edn`) for every case. A `.gitattributes`
`eol=lf` pin under `corpus/`, or byte-for-byte assertions break on a CRLF checkout. **Plus the
non-negotiable half (§7.1): ~6–8 named `codemod-contract-*` rows in the door's own suite**,
pinning deep-camel-stops-at-colls, the three seeded renames and the `aria-*`/`data-*`
exemption at depth, `class-names` composition, plain-fn identity at an event position, and
the named-value asymmetry between `host-prop-value` and `convert-prop-value`. Those rows are
the only executed evidence the rewrites are right.

**Docs.** `rf2-2rtt6.112` teaches it after the implementation PR merges. The guide's two
"planned, unbuilt" statements flip then. `migration/from-re-frame-v1/README.md` needs **no**
line — it is the v1→v2 axis and this is the Reagent-adapter→Hicasso axis — so there is no
hot-zone sequencing constraint, stated here so nobody batches one silently.

---

## 10 · Sources

- The bead `rf2-2rtt6.106`, read bottom-up — its six design questions and the **12:52
  synthesis comment**, which is the newest item on it and is binding on shape.
- `ai/findings/2026-08-04.codemod-design.md` and `…codemod-attack.md` — local-only and
  gitignored; everything load-bearing from them is restated here in its own terms.
- [The `[:>]` synthesized spec](raw-escape-spec.md) — the destination: the shared
  module-level gate, the pinned component roster, the unclaimed-slot conduct, and the
  dated `rf2-2rtt6.119` / `rf2-d03av` / `rf2-l0wfx` addenda.
- `implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs` — `host-entry`,
  `host-prop-value`, `host-element`, `mint-host!`, `mint-host-gate!`, `declared-ssr`,
  `refuse-undeclared-host-event!`, `check-member-key!`, `convert-prop-value`,
  `nested-map->js`, `class-names`, `props-map?`, `hiccup-tag?`, `vec->element`.
  **Cited by name, never by line** — see §6.5.
- reagent 2.0.1, sources extracted from `~/.m2/repository/reagent/reagent/2.0.1/reagent-2.0.1.jar`
  — `vec-to-elem`, `native-element`, `convert-props`, `convert-prop-value`, `kv-conv`,
  `raw-element`.
- `implementation/adapters/reagent-slim/src/reagent2/impl/template.cljs` —
  `warn-once-keyword-prop!`, `html-attr-name?`, and `convert-prop-value`'s two arities;
  the tracked precedent for candidate D, and the last surviving trace of the `rf2-cgcv` /
  `rf2-kfpf` audits.
- `migration/from-re-frame-v1/codemod/` — `deps.edn` and `reg_event_codemod.clj`, the
  residence and skeleton precedent.
- `docs/design/hicasso/draft-guide/05-interop.md` — the published manual recipe and the two
  troubleshooting rows that make candidate A serious.
- Beads: `rf2-2rtt6.103`, `.104`, `.112`, `.116`, `.119`, `rf2-d03av`, `rf2-l0wfx`,
  `rf2-nv07k`, `rf2-vrvv9`, `rf2-cgcv`, `rf2-kfpf`.
