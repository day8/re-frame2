# Pricing the production server arm — JVM structural walk vs Node sidecar

**Ruling prep for the P2/HD-013 sitting (`rf2-2rtt6.88`). No verdict, and no
recommendation.** The decider is the operator, at the sitting, and this page
exists so that the two arms arrive there priced rather than argued. Where one
arm is plainly better on an axis, the row says so and stops; where the evidence
is absent, the row says *that*, because an honest gap is worth more at a sitting
than a confident guess.

Written 2026-08-04 against `origin/main` at `a40da23a51`. Every fact below is
either a named symbol in this tree or a figure from the X1–X5 spike witness
(`rf2-2rtt6.87`, [studio/ssr-spike-witness.md](studio/ssr-spike-witness.md));
nothing is estimated except the two clearly-labelled pricing tables at the end.

**Amended 2026-08-05 at `a9aa3e43cb`**, from the merged-PR audit that reopened
`rf2-2rtt6.88` against PR #7488. Three things moved and each is marked where it
lands: §4's census (two counts of one thing, neither stating its rule), §5's
render contract and topology arrows (the sidecar was priced against a crossing
the shipped Node entry does not have), and §10's Arm B table (repriced from that
contract). Nothing else changed, and the page still draws no conclusion.

**Amended 2026-08-11 (`rf2-t2ba`), from the `rf2-2rtt6` ruling's packet-hygiene
item.** Two places moved, in two passes. §9's root-naming row let the walk arm
read as though a Hicasso root were already a Var the JVM could call, while its
sidecar counterpart carried the absence clause; `rf2-2rtt6.88`'s ruling comment
establishes that **neither host has a callable Hicasso root today** — which is
what §5 has said all along — and the row now says so on both sides. The
merged-PR audit on that pass then found the same premise earlier and less
guarded: §1's paragraph introducing job one's two inputs had a db snapshot and a
root form both in hand where a JVM walk would run. That paragraph now says what
§5 and §9 say — **neither arm has both inputs in the form job one requires** —
and names what each is missing.

Two facts of status travel with that correction, so that a later reader is not
misled about what this page is. `rf2-2rtt6.88` closed as **ruled on 2026-08-08:
the production server arm is the Node sidecar**, so the choice this page was
written to inform has since been made. And ruling is not start — the pricing
below still prices unbuilt work, and `ssr-ring`'s render call is still the
hard-wired `ssr/render-to-string` line §2 quotes. No figure, band or argument
moved; the page still draws no conclusion of its own, and the ruling is recorded
here rather than re-argued.

**A third status fact, added 2026-08-13 (`rf2-2rtt6.144`): there is no sitting,
and this page's tense is the only thing that changes.** The P2 fork was ruled by
the operator directly in chat on 2026-08-13 — *Hicasso graduates, as a success*
— which pre-empts the 2026-08-25 packet freeze and the 2026-08-27 sitting; the
record is [`decisions.md` HD-029](decisions.md#hd-029--the-p2-fork-hicasso-graduates-as-a-success)
and [validation.md's graduation section](validation.md#the-kill-table-at-graduation--the-p2-ruling-of-2026-08-13).
So **read every "at the sitting" and "a sitting will want" below as the forum
this page was written for rather than one still ahead** — including the header
above, which named the sitting as where the decider would rule. Nothing else is
affected: the arm choice was already ruled on 2026-08-08 by `rf2-2rtt6.88` and
is untouched by the fork ruling, the JVM-root correction above stands as
written, and no figure, band, table or argument on this page moves. The pricing
still prices unbuilt work, and the five start gates `rf2-2rtt6.88` named still
govern when Arm B implementation begins — graduation did not start it.

**A fourth status fact, added 2026-08-13 (`rf2-k6bv`, from the merged-PR audit
of #8029): the last sentence above was already false when it was written, and
Arm B is half-built.** The status fact it corrects is the *only* thing that
moves; the sentence is left standing so the chronology is legible. **Graduation
still did not start this arm** — but a *separate and earlier* ruling did.
`rf2-xpq9` (operator, in session, 2026-08-12 17:36 AUSEST) made every Phase-5
optional product v0 scope and removed `rf2-hic-056`'s *"when a named caller
activates it"* condition, and **PR #8028 landed the bounded Node/React service
at `implementation/ssr-node/`** at 2026-08-12T21:29:24Z — twelve seconds before
the PR carrying the third status fact merged (#8029, 21:29:36Z). So the correct
reading of the third fact is: *graduation* did not start the arm, and by the
time it said so the arm had already been started by something else.

What that does **not** change, stated so the page is not over-read in the other
direction:

- **The arm choice is untouched**, as the third fact says. `rf2-2rtt6.88` ruled
  the Node sidecar on 2026-08-08 and nothing since has reopened it.
- **The service is the Node half only.** It returns body markup and nothing
  else, by its own refusal list; `ssr-ring` remains the HTTP host and still
  hard-calls `ssr/render-to-string`, exactly as the 2026-08-11 fact above
  records, and the render seam at `build-full-response*` is unwritten. That
  fact's *"prices unbuilt work"* half is what has aged; its `ssr-ring` half has
  not.
- **Two of the five start gates are undischarged**, and they are the two the
  crossing owns: gate 3's per-application **state projector** with its
  round-trip corpus and its negative fixture — the JVM produces that projection,
  and a service that allowlists what arrives is not a projector — and gate 5's
  end-to-end **`JVM → Node → JVM` witness**, which has no JVM leg to witness.
  Gates 1, 2 and 4 are answered inside the package and witnessed by its own
  suite, which is not the same as witnessed across the crossing.
- **§3's closing sentence and §5's table still hold where it counts.** The
  compliant shape — JVM host, JVM payload, JVM shell, Node returning a body
  string across a contract — now has its Node end built and its JVM end not, and
  the crossing itself remains **unmeasured**, so *"the single largest unmeasured
  thing on this page"* stands. §5's table is a reading taken while this page was
  written and its column now says so; the rows the service changes are *"a
  callable Node process"* and *"process supervision, pooling, restart"*, and the
  rows naming the JVM↔Node contract, the JVM-written payload and shell, and the
  production host in front of it are unchanged.
- **No figure, band, table or argument moves**, on the same terms the third fact
  set. The pricing below still prices the work that is still unbuilt, and it is
  not re-costed here against what shipped.

## What this document deliberately does not contain, and the tension that produced it

`rf2-2rtt6.88`'s description asks for "both branches priced in beads/weeks,
named tripwires for the default, **and the recommendation stated**". The
dispatching brief, quoting the operator later the same day, says the opposite:
*"the no-verdict dossier … The RULING happens at the sitting, by Mike"*, and
adds **"do not recommend"**.

Both texts are on the record and they disagree. This page follows the later one
— it prices, names tripwires, and draws no conclusion — and flags the
disagreement here rather than resolving it silently, because which of the two
the operator meant is itself the operator's to say. If a recommendation is
wanted, it is one paragraph on top of this page and not a rewrite of it.

The other fences observed: nothing under `decisions.md`, `validation.md` or
`rf2-2rtt6.1`'s own notes is touched, no runtime code changes, no gate is added,
and no spec text is edited.

## 1. The question, and the three jobs both arms must do

Hicasso graduates with SSR or it does not graduate — that is HD-020's addendum
and EP-0038's, both taken by the operator on 2026-08-04. The remaining question
is *what produces the markup on a production server*.

Whatever produces it must do three jobs, and the arms differ on all three:

| Job | What it means |
|---|---|
| **Render** | turn a db snapshot plus a root form into HTML that React will adopt |
| **Host** | terminate HTTP, drain the boot events, own status/headers/cookies/redirects, assemble the page |
| **Hand over** | write the `#__rf_payload` EDN embed the client boots from, and whatever mismatch signal the tier carries |

R0 settles the second and third jobs for both arms: the host is `ssr-ring`, the
payload is Spec 011's, the mismatch machinery is Spec 011's, and neither arm may
mint a parallel Hicasso-only mechanism. **So the arms are a choice about job one
only** — and the interesting costs are what job one drags behind it.

Job one's two inputs are not equally available to the two arms, and **neither
arm has both of them in the form job one requires**. Where a JVM walk would run
the state is in hand — `ssr-ring` holds the post-drain request frame, and
`compute-sub` is `.cljc`, so the JVM can read against it — but the root form is
not: a Hicasso root's head is a JavaScript function `defview` mints at namespace
load, with no JVM referent, and no registry maps a name back to it on either
host. Where a sidecar would run the root form can be built, because the bundle
that renders it holds the codec — but the JVM has no name to ask for one by, and
the state has to arrive as a projection under a policy nobody has written. What
each arm is missing, and what supplying it would cost, is §4 and §5.

## 2. The floor both arms stand on — what is already built

Neither arm is priced from zero, and the amount already shipped is easy to
under-count from the bead text alone.

| Piece | Where | State |
|---|---|---|
| Payload policy, EDN embed, egress projection | `implementation/ssr/src/re_frame/ssr/payload_policy.cljc` | shipped, `.cljc` (JVM **and** CLJS) |
| Render-tree hash, HTML escaping, wire constants | `implementation/ssr/src/re_frame/ssr/{hash,html_helpers,constants}.cljc` | shipped, `.cljc` |
| HTTP host — handler, middleware, response accumulator, cookies, redirects, head model, error projection | `implementation/ssr-ring/src/re_frame/ssr/ring.clj` and siblings | shipped, **JVM-only `.clj`** |
| Structural tree → HTML, react-dom 19.2.0-pinned | `implementation/ssr/src/re_frame/ssr/ui_tree.cljc` (1,040 lines) | shipped; pure, deterministic, JVM-runnable |
| A live two-sided emitter parity apparatus | `implementation/ui/test/re_frame/ui/parity_fixtures.cljc` (643 lines, 66 cases) + `parity_corpus_cljs_test.cljs` + `parity_html.cljc` | shipped and running |
| An interpreted structural walk that runs on the JVM | `implementation/freehand/src/re_frame/freehand/tree.cljc` (678) + `node.cljc` (1,438) + `conversion.cljc` (906) | shipped, cross-host |
| Hicasso's hydration door, `defhost` `:ssr` policy (three values, incl. `:render`), Node render entry | `rf2-2rtt6.84` / `.85` / `.86` / `rf2-l0wfx` | shipped in the bench lane |
| Five hydration correctness rows on the dogfood screen | `rf2-2rtt6.87` | published |

Two entries in that table carry more weight than their line counts suggest.

**`emit-ui-tree` is shipped and final.** Spec 004B calls it "the shipped, final
contract" and states its properties as contract: pure (no React, no DOM, no JS
runtime), deterministic to the byte, JVM-runnable, and applying the
serialisation half of one conversion table pinned to react-dom 19.2.0 — the
version string appears four times in the source. The JVM back half of the walk
arm is therefore not a thing to be built; it is a thing to be called.

**A JVM interpreted structural walk already exists, for Freehand.**
`re-frame.freehand.tree`'s own docstring describes it as "the INTERPRETED
structural walk — unrestricted Hiccup from a view body in, the versioned
semantic structural tree out … On the JVM it is the whole render." It reaches
`emit-ui-tree` through `tree.cljc`'s `emit-static-html` (via
`resolve-ssr-emitter`), late-resolved so the Independence wall holds, and the
shipped `v/render-static` macro drives it. `implementation/freehand/deps.edn`'s
header comment records that this was deliberate from F1: *"The entry namespace
is `.cljc` FROM THE OUTSET because the F1 spine needs both emitters."*

It is worth being precise about what that proves and what it does not. It proves
a `.cljc` interpreted walk to the v1 tree is buildable, shippable and
conformance-testable, and that the pattern is not novel in this repo. It proves
nothing about Hicasso's codec, which is different code with different semantics
— `front/codec.cljs` requires `["react" :as react]` and calls
`react/createElement` / `react/memo` / `react/useSyncExternalStore` directly,
with no structural-tree stage anywhere in it. And Freehand's JVM walk does not
reach reads at all: it is a pure function of its argument, and `v/render-static`
fails a `v/sub` loud with `:rf.error/view-read-outside-render` rather than
resolving one. **Reads against a request snapshot are the part of the walk arm
that has no precedent in this tree.**

### The one seam neither arm has

`ssr-ring`'s render call is a single hard-wired line:

```clojure
;; implementation/ssr-ring/src/re_frame/ssr/ring/pipeline.clj
;; — inside `build-full-response*`
body-html (ssr/render-to-string
            hiccup
            {:doctype?    false
             :emit-hash?  emit-hash?
             :render-hash (when emit-hash? hash-str)})
```

`re-frame.ssr/render-to-string` is the hiccup emitter — the tier Spec 004B
freezes with stock-Reagent compatibility. **Both arms need a new render seam at
that call site**, and neither has one today. The cost is symmetric and it is
small; it is named here so that neither arm gets to look cheaper by having it
quietly assumed.

## 3. How R0 bites each arm

R0 is the binding constraint: Hicasso rides re-frame2's *existing* Spec 011
mechanism — payload policy, `#__rf_payload`, `ssr/hydrate!`, `:rf/hydrate`, the
mismatch machinery, `ssr-ring` as HTTP host — **never a parallel Hicasso-only
mechanism**.

**For the JVM walk, R0 is nearly free.** The arm is in-process on the JVM, so it
inherits `ssr-ring`'s host wholesale: the pipeline drains events, assembles the
page, writes the payload script, and calls one function differently. The
payload, the manifest, the response accumulator and the error projection are
untouched. The arm adds a second *emitter* of Hicasso semantics, which is not
what R0 forbids — Spec 004B blesses exactly that shape ("two modes, two
emitters, one tree"; "the emitters are separate implementations on purpose …
divergence is *detected, not prevented*"). What is genuinely open is that 004B
writes that blessing for **Freehand**, and extending the contract to cover a
Hicasso structural emitter is a spec question this page is fenced from
answering. It belongs in the sitting's output, not in its inputs.

**For the Node sidecar, R0 is the binding shape of the design, and the spike
does not have it.** R0 puts `ssr-ring` at the HTTP host, and the adversarial
review's host-fork finding rejects Node-serving-as-reference-host on the record.
So a compliant sidecar keeps the JVM in charge of everything except the body
markup string. But `ssr-ring` is **JVM-only `.clj` in every file** — the shell,
the payload script tag, the head model, cookies, redirects, the response
accumulator. None of it is reachable from Node, which is precisely why the
spike's Node entry hand-writes its own:

- the entry's `payload-script` re-spells the `#__rf_payload` script tag rather
  than calling `re-frame.ssr.ring.shell/payload-script-tag`, with a docstring
  obliging it to stay byte-identical to that function's output;
- the entry's `document` hand-assembles the whole `<!DOCTYPE html>…` envelope,
  explicitly modelled on `ssr-ring`'s non-streaming shell and explicitly not it.

The entry's own namespace docstring says so plainly — *"Not a production host …
no file under `implementation/ssr` or `implementation/ssr-ring` is touched by
this bead"* — and the driver's `serve` says the same. **The spike therefore
built the Node arm's render, not the Node arm's topology.** The sidecar's
compliant shape — JVM host, JVM payload, JVM shell, Node returning a body string
across a contract — has not been built and has not been measured. That is the
single largest unmeasured thing on this page.

## 4. Arm A — the JVM structural walk

### What has to be built

A `.cljc` twin of the Hicasso codec's pure analysis rules, emitting the 004B v1
structural tree, with reads resolving through `re-frame.subs/compute-sub`
(`implementation/core/src/re_frame/subs.cljc` — already `.cljc`, already
public, `[query-v db]`, needing no frame and no reactive runtime, and accepting
either a bare app-db map or a whole frame-state value so a mixed `:db` /
runtime-db graph computes coherently in one call). A walk-scoped memo is already
reachable too: the same namespace's `^:no-doc compute-sub-with-memo` takes a
caller-supplied memo atom, so sharing one across a page's reads is a wiring
question rather than a new mechanism.

The codec is `implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs`
— **1,789 lines, 77 top-level definitions, `.cljs` only**, measured at this
page's base commit. It is not the only file with a server answer to give:

| File | Lines | The server question it raises |
|---|---|---|
| `front/codec.cljs` | 1,789 | the walk itself — heads, attrs, `.class#id` sugar, children, keys, the emitted slot |
| `front/intent.cljs` | 960 | event intents must reach the tree's `:events` and never the markup |
| `front/controlled.cljs` | 620 | the controlled door's server projection — `:value` vs a text child |
| `front/presence.cljs` | 365 | what a presence declaration renders server-side |
| `front/route_link.cljs` | 217 | href derivation without a live router |
| `front/state.cljc` | 309 | **already `.cljc`** — `h/reg-state`'s instance keys |
| `arm1/runtime.cljs` | 1,985 | the reactive runtime, which a JVM walk replaces rather than ports |

**On the bead's "~35–40 rule-bearing sites" — enumerated, and it holds.**
`codec.cljs` carries **77 top-level definitions**: 18 `def`, 26 `defn`, 31
`defn-`, and 2 `deftype` (`ParsedTag`, `PropSlot`).

**The two counts, reconciled (2026-08-05).** An earlier draft of this section
said "75 top-level forms" in one sentence and "77 top-level `def*` forms" in the
next. Both were arithmetically right and neither stated its rule: 75 counts
`def`/`defn`/`defn-`, and 77 adds the two `deftype`s. **77 is the figure this
page uses**, under the rule *every top-level definition*, because a `deftype` in
this file is a rule-bearing thing and not scaffolding — `PropSlot` is the
one-slot-per-spelling decision made concrete. The `defn`/`defn-` subtotal is 57
under either rule.

Separating the rules from the scaffolding (the caches, `fail!`, the slot
minters, `make-element`'s three `createElement` arms) leaves **35–40 definitions
that encode a normalization or classification decision**, among them:
`reserved-name?`; `parse-tag` (the `#id.class` shorthand); `prop-name`
(kebab→camel with the `aria`/`data`/`--custom-property` exemptions); the
one-slot-per-spelling family `canonical-slot` / `structural-slot?` /
`without-slots` / `denied-slots`; `class-names`; `fold-shorthand!`;
`convert-prop-value`; `merge-caller` (HD-023's `:&` owned-literal law);
`check-ref!` (HD-022's vector reservation); `convert-entry` / `convert-props`;
the head marks `mark-boundary!` / `mark-frame-prop!`; `boundary-props=` /
`memoize-boundary!`; `mint-host!` / `declared-ssr` / `mint-host-gate!` /
`refuse-deferring-heads-in-fallback!` (HD-011, plus `rf2-2rtt6.85`'s `:ssr`
policy and `rf2-l0wfx` / `rf2-nv07k`'s third value and fallback refusal); the
lazy-seq forcing walk `realize-children` / `realize-deep` / `refuse-deferred!`;
the emission dispatch `expand-seq` / `native-element` / `boundary-element` /
`host-element` / `fragment-element`; `vec->element`; `as-element`'s ten-way
value classification; and `root-element`.

**Cited by symbol, deliberately.** An earlier draft gave a line range for every
entry in that roster. Line numbers in this file rot faster than a design page
can be re-read — `root-element` moved 144 lines in the day after this page's
base commit, and the `mint-host!` block above moved twice while the page was
being written — so the roster names symbols instead, and
`grep -n '^(def' codec.cljs` places every one of them in whatever the file is
today. The same rule is applied to the rest of this page.

**The bead's number is right and its framing matters**: this is a grammar, not a
handful of rules, and every one of those definitions is a place two emitters can
disagree.

### The churn risk row, measured

The bead's second concern is that the codec was in its highest-churn phase. It
was, and it still is:

| Date | commits to `codec.cljs` | commits to `front/` | commits to `arm1/` |
|---|---|---|---|
| 2026-07-31 | 1 — **the file is created** | 3 | 8 |
| 2026-08-01 | 6 | 11 | 17 |
| 2026-08-02 | 8 | 17 | 17 |
| 2026-08-03 | 6 | 19 | 26 |
| 2026-08-04 (partial) | 2 | 4 | 13 |

**The codec is four days old and has taken 23 commits**, every day since it was
minted (`67305d87b2`, `rf2-2rtt6.8`), and they are not tidy-ups: the tag
shorthand moved to the emitted slot, the frame became a codec-supplied prop, a
composition shadow carved live IME out of the converge, and `defhost` grew an
`:ssr` gate. **A twin built at the start of that week would have needed four
semantic amendments in four days.** That is a scheduling fact rather than an
architectural one — it argues about *when*, not *whether* — but it is the
sharpest number on this page and it is the one a sitting can act on immediately.

**Re-measured twice while this page was being amended, and the rate held both
times.** `codec.cljs` was 1,789 lines and 77 definitions at this page's base
commit; **1,933 and 79** twenty-four hours later; and **2,092 lines and 82
definitions** at `ca072f852d` a few hours after that, when `rf2-l0wfx`'s third
`:ssr` value and `rf2-nv07k`'s fallback refusal landed in it. **That is +303
lines and +5 rule-bearing definitions in two days**, and the second of those
landings changed the authoring surface rather than tidying it — see §11. The
census above is pinned to the base commit for internal consistency; this is why
it is dated rather than standing.

### The text-separator row, and why the spike does not close it

Spec 004B's conversion table (`spec/004B-UI-Tree-and-Conversion.md`, the
*adjacent text* row) states the problem exactly:

> adjacent text | coalesced in the tree (canonical form); **the serialiser's
> hydration text-separator behaviour (`<!-- -->` between originally-distinct
> dynamic text runs) is an open 011-owned row** — React hydration distinguishes
> text-node boundaries, `renderToStaticMarkup` does not; a hydration fixture
> must settle what our emitter writes **[S1-CONFIRM]**

So `[:div "Count: " n]` mismatches by construction: the tree coalesces the two
runs, the JVM emitter writes one text node, and React's hydration expects two
separated by a comment.

**Where the coalescing actually is, because it is easy to look in the wrong
place.** It is not in the serialiser. `emit-ui-tree` receives children that are
*already* coalesced and only joins them — `ui_tree.cljc`'s `emit-children` is a
`str/join` over `map-indexed`, and `sole-newline-content`'s own docstring says
its single content entry arrives "already text-coalesced". Coalescing happens at
**tree-build** time, in three
independent `.cljc` implementations of one algorithm: `re-frame.ui.tree/children`
(`implementation/ui/src/re_frame/ui/tree.cljc`),
`re-frame.ui.semantic/coalesce`
(`implementation/ui/src/re_frame/ui/semantic.cljc`), and
`re-frame.freehand.node/conj-text`
(`implementation/freehand/src/re_frame/freehand/node.cljc`). **A Hicasso
JVM twin would need a fourth**, because the CLJS codec has none at all — it
hands children to `createElement` as authored, which is exactly why React's own
render is separator-correct and a structural tree's is not.

The row is 011-owned and carries no bead. The one place it is discussed is the
merged-PR audit `rf2-7uil4`, which records it as "already an explicitly open
011-owned row … **not rediscovered here**" — deliberately left unfiled. So it is
open, unowned by a work item, and inherited whole by the walk arm.

**X1(b) does not rescue it.** The witness reports that
"React's SSR text separators are a non-issue here rather than a tolerance the
row had to be given: the comparator skips comment nodes by construction" — but
that is a property of `lane/canonical`, the *comparator*, and React's hydration
reconciler is not that comparator. X1(b) was also judging bytes that
`react-dom/server` itself produced, so the separators were present and correct.
Neither fact transfers to a JVM emitter that has not written any.

Worse for the apparatus: the existing parity corpus renders its React side with
`renderToStaticMarkup` (`parity_corpus_cljs_test.cljs`'s `render-case`), which by
React's own behaviour emits no separators. **The gate that exists cannot see this
row.**
Closing it means a `renderToString`-authored hydration fixture, which is new
apparatus rather than an extension of the old.

### The parity apparatus, and what a react-dom bump actually costs

The bead calls the conformance apparatus "an eternal maintenance tax re-opened
at every react-dom bump" and asks for it priced honestly. Honestly, then: **the
apparatus exists, it runs, and the walk arm extends it rather than founding it**
— which is materially cheaper than the phrase suggests, and still a standing
obligation.

What is there today, and how it is anchored:

- `implementation/ui/test/re_frame/ui/parity_fixtures.cljc` — 643 lines, 66
  cases; `parity_corpus_cljs_test.cljs` renders each through real
  `react-dom/server` and compares against a JVM side computed at CLJS **compile
  time** and embedded as literals. No checked-in goldens, live cross-emitter
  parity on every run.
- `implementation/ssr/test/re_frame/ssr/emit_ui_tree_cljs_test.cljc` (602 lines)
  pins the serialiser's conversion-table copies **byte-for-byte against their
  `re-frame.ui.rules` source**, on both hosts.
- `implementation/ui/dev/react-conform-probe.cjs` is the tool that talks to a
  real react-dom to derive and verify those tables.

Two consequences the sitting should hear. First, the bump tax has a **named
home** — a probe, a table, a pin — rather than being folk knowledge, so it is
bounded work rather than open-ended vigilance. Second, and less comfortably:
that pin anchors the serialiser to **the UI compiler's** rule table. A Hicasso
JVM twin has no such anchor, so the walk arm owes not only corpus cases but a
second pinning relationship, and that is new apparatus rather than an extension.

### The divergence class the instrument cannot see

This is the arm's structural risk, and it is worth stating flat because it
composes two documents neither of which mentions the other.

Spec 004B, on two emitters: *"Divergence between the two emitters is detected,
not prevented: they are separate code by design, and this contract's job is to
give them one table to be separate against."*

Spec 011, on what React-native adoption reports: an **attribute-only** mismatch
— a stale `class`, `style`, or ARIA value on an element whose tag and text still
match — is *"deliberately not in that set"*, because React documents that
*"there are no guarantees that attribute differences will be patched up"*. It
takes React's development-only warning path and calls **neither**
`onRecoverableError` **nor** any production equivalent.

Put together: the JVM walk's most likely divergence — an attribute the JVM
conversion table spells differently from react-dom's client emitter — is exactly
the class the runtime instrument is documented as unable to report. The
detection has to come from the parity corpus, in CI, ahead of time, or it does
not come at all. **The Node sidecar cannot have this class**: one emitter
produces both sides, so there is nothing for two tables to disagree about.

### What the arm buys

- **One production runtime.** A JVM Ring application, end to end, is what a
  re-frame2 application already is. Nothing new is deployed, nothing new is
  supervised, and nothing has to be version-matched across a process boundary.
- **The strongest R0 fit.** `ssr-ring` keeps the host, the payload, the head
  model and the response contract; one function call changes.
- **HD-021(a)'s headless door, co-discharged.** The headless testing door is
  specified as "a structural render returns the hiccup tree as data — intent
  vectors assertable by equality, sub reads overridable through a pure read
  resolver, no browser". That is the same structural-render core with a
  different resolver: a snapshot for SSR, an override map for tests. The
  co-discharge is real, and the door is genuinely absent —
  `draft-guide/08-testing.md` (the draft-era testing chapter, since replaced
  by the end-state `draft-guide/15-testing.md`) said so three times
  over: *"Not built yet — the sketch below is the intended shape, not a call you
  can make today"*, *"Headless (sketch — not built)"*, and of the resolver
  itself *"This half is the headless path's, and it does not exist yet."*
  **Its demand is not measured** — no open bead in the `rf2-2rtt6` family
  requires the door, so the value of getting it free is a value nobody has
  priced.
- **A server render that is data before it is a string.** Structural tests,
  caching and golden files all become available at the tree, and the deferred
  `ui-tree-fingerprint` candidate becomes cheap on the server side. (Not on the
  client side — see §6.)

### What would have to be true for it to be wrong

- If the codec keeps changing at this week's rate through P2, the twin is a
  moving target and each amendment is two files instead of one.
- If the rule-bearing surface is materially larger than 40 sites, the parity
  corpus grows with it and the react-dom-bump tax grows with the corpus.
- If the text-separator row cannot be settled without changing the tree's
  canonical form, the change reaches Spec 004B and every existing consumer of
  the v1 tree, not just Hicasso.
- If attribute-level divergences turn out to be common in practice, the arm's
  correctness rests entirely on a corpus that has to be complete, and corpus
  completeness is not a thing anyone can assert.

## 5. Arm B — the Node sidecar

### What has to be built

The render exists and is witnessed. What does not exist is everything around it.

| Piece | State when this page was written |
|---|---|
| The render itself | **built** — the entry's `render`, `renderToString` over the same runtime/mount/codec the browser uses |
| A root the JVM can name | **no** — `render` takes `:hiccup`, and a Hicasso root is `[<minted head> {props}]` whose head is a JavaScript function with no JVM referent. See below |
| A JVM↔Node contract | **does not exist** — the spike is Node-only end to end |
| A callable Node process | **no** — the build is a `:node-script` publishing on `globalThis.HICASSO_SSR`; the driver `require`s the bundle in-process, and there is no module export and no IPC |
| A production host in front of it | **explicitly not** — `driver.cjs`'s `serve` disclaims the response accumulator, cookies, redirects and CRLF fail-fast, and `/main.js` returns a stub |
| Payload/shell written by the JVM | **no** — the spike writes both in Node, re-spelling `ssr-ring`'s shapes by hand |
| Process supervision, pooling, restart | **not started** |

### The contract the entry actually has

**Corrected 2026-08-05, from the merged-PR audit on `rf2-2rtt6.88`.** The first
version of this section wrote the crossing as *"the JVM serialises the request's
db snapshot plus the root form as EDN"*, and §10 priced B1 as an *"EDN render
contract — snapshot + root form out"*. **Neither half survives contact with the
shipped entry**, and §6 of this page already contained the refutation of one of
them. What follows replaces that sentence; the repricing it forces is in §10.

**The root form is not an EDN value and has no JVM referent.**
`re-frame.bench.hicasso.ssr.entry/render` takes `:hiccup` — its own docstring
says *"REQUIRED. The root hiccup form"* — and hands it straight to
`codec/root-element`. A Hicasso root form is `[<minted head> {props}]`:
`defview` expands to a `def` of `(runtime/mint-view! "<ns>/<sym>" (fn …))`, and
`mint-view!`'s docstring is explicit that **the returned value is still the
function**. So the head is a JavaScript function created at namespace load,
inside the CLJS bundle. `arm1/lang.clj` states the JVM half in as many words —
it is a `.clj` and not a `.cljc` because *"the runtime it names is CLJS-only …
and a `.cljc` would invite a JVM-side implementation this arm does not have"*.
§6 depends on exactly this fact from the other side: canonical EDN renders every
function as the identity-free `#fn[]`, which is why `[#fn[] {}]` hashed one
constant for every page in the lane.

**A reference is not a tree, and neither crosses a process boundary as itself.**
The framework already carries the milder version of this. `ssr-ring`'s
`resolve-root-view` documents that a `:root-view` resolving to
`[(rf/view :app/root)]` renders identically to the outer-call form but hands the
hash channel *"a reference to the page rather than the page"*. That reference at
least **resolves**, because the JVM can call the Var in-process. The sidecar's
case is the harder one: the head is not a reference the JVM can resolve — on the
JVM it does not exist at all. (The consequence *there* was a lost hash channel,
and that consequence does **not** transfer here: `rf2-q1b96` and `rf2-2rtt6.91`
have since settled that this tier carries no `:rf/render-hash` at either end —
see §6. The transferable part is only the first clause.)

**So the contract is a name, not a form.** A viable sidecar needs an
**application-defined stable entry identifier** that both hosts agree on ahead of
time — opaque to the JVM, **resolved inside the per-app Node bundle** against a
table that bundle publishes — plus **root arguments in a stated serialization
domain**. The shape is not an invention: core already registers views under
stable ids and looks them up with `rf/view` (`registrar/lookup :view id`), which
is the *"other explicit cross-host registry"* the audit allows for. What is
absent is Hicasso participating in any such registry. `defview` mints a `def`,
and the name it does capture at expansion — `"<ns>/<sym>"`, stamped by
`mint-view!` as `displayName` — is carried **on** the head and is not a key **to**
it. The inverse map exists in neither host.

**And the snapshot needs a domain that is not the payload's.** The other half of
the retracted sentence assumed arbitrary app state crosses EDN. Two facts
against it:

- **The tree states a portable domain, but for a smaller thing.**
  Spec-Schemas states the round-trip rule for machine snapshots —
  `(read-string (pr-str x))` returns an `=`-equal value, *"no functions, atoms,
  JS objects"*. That is the right rule in the right shape. It has never been
  stated for `app-db` at large, and nothing validates it there.
- **The projection that exists runs a different errand and is deliberately too
  narrow.** `payload-policy/apply-policy`, `project-app-db-egress` and
  `project-runtime-db` are the framework's serialization projection, and they
  exist *because* deciding which app-db keys leave the process is a security
  decision — fail-closed, allowlist-shaped, `:sensitive` paths redacted. The
  sidecar cannot reuse them. The payload is what the **client** may see; the
  snapshot is what the **views must read**, and the second is a superset of the
  first by construction — a server that renders from state it does not ship is
  the entire point of an allowlist. A render projection is therefore a
  **second, larger egress with no policy written for it**. And the failure modes
  are not symmetric: a payload allowlist that is too narrow costs the client a
  recompute, while **a render projection that is too narrow is a silently wrong
  page** — the view reads a key that did not cross and renders as though it were
  nil.

**One more arrow the retracted sentence hid.** In the compliant topology
`ssr-ring` drains the boot events on the JVM; Node draining them itself is the
host fork the adversarial review rejected. So the Node side must **not** run
`:initial-events`, and everything the views read has to arrive as `:snapshot`.
The entry's snapshot door is `:rf/set-db`, which per Spec 002 seeds **app-db
only** — the frame's *runtime-db*, the route slice and machine snapshots that
Spec 011 carries as its own payload partition, has **no inbound door on this
path at all**. The framework's one existing "install a whole frame-state from
serialised EDN" event is `:rf/hydrate`, and it is the client's.

**The compliant topology, restated arrow by arrow.** Every arrow is new; the
ones the retracted sentence did not have are marked.

1. `ssr-ring` drains the boot events and holds the request frame — JVM.
2. The JVM sends **a stable entry identifier** (**new** — this read "the root
   form"), **root arguments in the stated serialization domain** (**new**), and
   **an EDN projection of the post-drain frame-state under a render-visibility
   policy that does not exist** (**restated** — this read "the request's db
   snapshot").
3. Node **resolves the identifier against the table its own per-app bundle
   publishes** (**new**), rebuilds the root form *there*, seeds a per-request
   frame from the projection, and renders under `react-dom/server`.
4. Node returns the body markup, and nothing else — or the rejected host fork
   has arrived by increments (§11).
5. The JVM assembles the page, writes the payload script from **its own**
   app-db, and returns the Ring response.

Nothing in that list has been built or measured, and two of the arrows are
contract design rather than plumbing. **The alternative to arrow 1 is worse and
is already rejected**: having Node drain the events itself forks the event
drain, which is the host-fork finding the adversarial review rejected on the
record.

### What the arm buys

- **Zero parity tax, forever.** There is one emitter — React's own — so there is
  no conversion table to keep two implementations honest against, no corpus to
  extend, and no react-dom bump that re-opens a maintenance obligation. This is
  the arm's whole case and it is a strong one.
- **The text-separator row evaporates.** `renderToString` writes the separators
  React's hydration expects, because it is the same code that expects them.
- **The attribute-divergence class evaporates too**, for the same reason.
- **Codec churn stops mattering.** The Node process renders whatever the codec
  currently is; there is no twin to keep in step. Against this week's numbers
  (§4) that is not a small thing.
- **Direct evidence, already published.** X1(b) canonical-DOM parity, X2's zero
  complaints with every element still the server's own node and 13 body runs for
  13 boundaries on both sides, X4's 15 intents at the hydrated screen, X5's zero
  residue — all of it was produced through this exact render path.

### What it costs

- **A second production runtime.** A re-frame2 application that deploys a JVM
  today deploys a JVM *and* a Node process, supervised, health-checked,
  restarted, and scaled. This is a real cost to a real user, and the stance's
  "trust the programmer" does not make an operator's pager quieter.
- **Two artefacts in lockstep.** The Node bundle contains the application's own
  compiled views. It must be rebuilt and redeployed with every view change, in
  the same release as the JVM host, or the two render different applications.
  Nothing detects that skew today.
- **A build step the programmer owns.** The sidecar bundle is per-application:
  the app's own shadow-cljs build has to produce a server bundle. The spike's is
  a `:node-script` produced by `--config-merge` over `:freehand-bench-node`,
  which is a bench convenience and not a shape an application can copy. **And it
  is not only a build**: the bundle has to publish the entry table the JVM's
  identifiers resolve against, so the artefact has a named public surface rather
  than just a `main`.
- **A named entry surface kept honest in two places.** The identifiers the JVM
  is configured with and the table the bundle publishes are two artefacts that
  have to agree. This is the one place the corrected contract makes the arm
  *cheaper* rather than dearer: an identifier the bundle does not have is a
  concrete, detectable disagreement, where "the two bundles were built from
  different views" previously had nothing to compare (§10, B5).
- **A per-request serialisation cost, unmeasured — and a projection rather than
  a `pr-str`.** The post-drain frame-state crosses in a domain and under a
  policy that must be designed first (above), so the per-request cost is not
  wire bytes alone; it is a policy-bearing walk on the model of
  `project-app-db-egress`, once per request, over whatever the render can see.

### What would have to be true for it to be wrong

- If the snapshot serialisation is expensive at real app-db sizes, every request
  pays it and no measurement currently exists to say whether that matters.
  (SSR speed is off the bar — see §7 — so nobody is obliged to measure it, which
  is a reason the number will not appear on its own.)
- If the render-visibility projection cannot be made narrower than "the whole
  app-db", then every request ships the whole of the application's server state
  to a second process. That is precisely the decision `payload-policy` exists to
  make explicit and fail-closed for the client wire, and the sidecar wire would
  have no equivalent contract.
- If a second runtime is unacceptable to the audience Hicasso is for, the arm is
  disqualified on deployment rather than on engineering, and no amount of parity
  saving buys it back.
- If the render contract ends up needing to carry request context, cofx, or the
  head model, the "Node returns a body string" line stops being thin and the
  host-fork finding starts applying to it.

## 6. The two instrument facts, and where they actually land

### `:rf/render-hash` is degenerate — and it is the same degenerate for both arms

`rf2-2rtt6.91` records `:rf/render-hash` as degenerate for an interpreted root,
and the spike reproduced it: the dogfood screen and the ~1,200-element Conduit
feed both hash `83b865f8`, while their byte digests differ (`a510149b…` vs
`4308c288…`). The addendum's two payload rows take the same `83b865f8`.

**Nothing on this page leans on that hash.** Three observations, because the
sitting will want them:

1. **Neither arm repairs it.** The degeneracy is a *client-side* fact — the
   client's root hiccup is `[<minted head> {props}]` and canonical EDN renders
   every function identically. The JVM walk gives the *server* a deep tree it
   could hash, but a server hash the client cannot reproduce turns every page
   into a mismatch, which is exactly why `rf2-2rtt6.86` declined to invent one.
2. **The production call site takes the hash from the root hiccup, before the
   render** — `lifecycle/render-document-hash` in `pipeline.clj`'s
   `build-full-response*`, which is `rf/render-tree-hash` over the same
   degenerate form. So the
   degeneracy reaches production identically whichever arm renders.
3. **Spec 011 already has a stated posture for this case.** Its
   hydration-mismatch section is tiered by *client render-tree representation*,
   and both the compiled tier and native React-element roots "deliberately carry
   **no** such hash", verifying instead by React-native adoption through
   `onRecoverableError`. Hicasso is a React adapter minting React-element roots.
   On that reading `rf2-2rtt6.91`'s candidate (c) — accept the shallow hash, or
   carry none, and rely on adoption — is not a concession but the spec's own
   existing answer for Hicasso's tier, and `rf2-2rtt6.97` has already landed the
   `onRecoverableError` door that answer requires. This is an observation for
   `rf2-2rtt6.91`'s owner, not a ruling, and it does not change the pricing of
   either arm.

**Addendum, 2026-08-05 — `rf2-2rtt6.91` has closed (PR #7510), and Observation 3
was the ruling.** The section above stands as written: it is a dated record of a
measurement that is *still exactly reproducible*, and nothing on this page ever
leaned on the hash. Three corrections, none of which move a figure:

- **The status.** The hash is no longer live for this tier. The Hicasso SSR
  entry stopped emitting `:rf/render-hash` altogether, and Spec 011 now states
  the **server** end of the tier rule it already stated for the client: a root
  that verifies by React-native adoption — a compiled `re-frame.ui` root, a
  native UIx root, or a Freehand root — carries no `:rf/render-hash` in its
  payload and stamps no `data-rf-render-hash` marker on its root element.
  Observation 3 read the tiering correctly: it keys on **render-tree
  representation, not on adapter brand**, and candidate (c) was the spec's own
  existing answer for Hicasso's tier rather than a concession. The payload now
  **omits** the key rather than stamping a nil, because the schema slot is
  `{:optional true} :string` and not `[:maybe :string]` — a present-and-nil key
  is not a legal spelling of absence.
- **The render-tree hash `83b865f8` was never a Hicasso fact.** It is the
  FNV-1a-32 of the canonical EDN `[#fn[] {}]` — the *unresolved*
  `[<minted head> {props}]` root, in which canonical EDN renders every function
  identically. **Any** `[<fn> {}]` root takes the same value, which is exactly
  why the dogfood screen and the ~1,200-element Conduit feed agreed. The
  degeneracy is a fact about hashing an unresolved root form, not about either
  arm's renderer — which is also why it never separated the two arms and never
  could have.
- **The figures above remain exactly reproducible.** The repair deliberately
  kept the measurement live: the witness rows that *chose* byte digests now take
  the hash from `ssr-hash/render-tree-hash` directly rather than from the
  payload, so `83b865f8`, `a510149b…` and `4308c288…` all still reproduce after
  the entry stopped emitting.

**Observation 2 is unchanged and still live.** The production call site still
takes the hash from the root hiccup *before* the render
(`lifecycle/render-document-hash`, which is
`rf/render-tree-hash` over the same unresolved form), and the tier ruling did
not touch it; it remains a question for the production pipeline, tracked on its
own bead. So the degeneracy still reaches production identically whichever arm
renders — what changed is only the *reason* nothing here leans on the hash: no
longer "it is degenerate", but "this tier carries none, at either end".

The caveat that *does* change the pricing is §4's: adoption reporting has a
documented hole at attribute-only mismatches, and only one arm can produce them.

### The Node entry's byte accounting was UTF-16; it has been repaired, and no figure from it is used here

When this page was written, `driver.cjs` had exactly one commit in its history —
`9214396f73`, its creation — and the bake manifest's byte columns, the bake
console's ` B` column and the live server's size log were all
`String.prototype.length`, which counts UTF-16 code units rather than bytes.
That is why **no size figure from that manifest appears anywhere on this page**,
and the sentence stands as written: the pricing below never banked one.

`rf2-2rtt6.114` has since landed the repair prescribed by `rf2-2rtt6.86`'s audit
note. All five sites now measure `Buffer.byteLength(s, 'utf8')`, and the bake
checks each manifest column against `fs.statSync` of the file it just wrote, so
a column that disagrees with its own fixture now refuses instead of baking.

One thing never needed repair. The SHA-256 is computed with an explicit `'utf8'`
encoding in `driver.cjs`'s `sha256`, so every digest row here was always a
function of bytes.

The one byte count quoted from the corpus — X1(a)'s **3,060** — does **not**
come from the manifest: it comes from the spike witness's own `report!` payload,
which `rf2-2rtt6.114` left alone. **`rf2-2rtt6.121` has since repaired that
payload as well, so the UTF-16 caveat on this figure is discharged rather than
outstanding** (2026-08-06, `rf2-8smbe`). The witness now publishes
`lane/utf8-bytes`, a shared `TextEncoder` helper rather than a second
`Buffer.byteLength` site — those arms also compile to a browser build, where
`Buffer` is not reachable — with `fs.statSync` used at the sites that really are
Node. The figure moved **3,101 → 3,060** as it did so, and both halves of that
move are separated in the witness's own dated addendum: `+18` from the ruler, and
`−59` because the document itself drifted after `952a3f2024` (stranded by the
rebase; the patch landed on main as `b3947377e0`). It is still used here only as
an identity claim (render #1 equals render #2), never as a size.

## 7. What the spike corpus licenses, and what it does not

This is the section most likely to be mis-read at a sitting, so it is stated
flatly.

**Every X1–X5 row was produced by the Node path.** The bytes came from
`react-dom/server`; the client adopted them. That means the corpus splits cleanly
in two:

| Row | Evidence about the **client** half — transfers to both arms | Evidence about the **server** half — transfers to Node only |
|---|---|---|
| X1(a) determinism | — | the Node render is byte-deterministic across two per-request frames |
| X1(b) canonical parity | the hydrated DOM equals a cold client mount | the Node render produces bytes that satisfy that |
| X2 adoption is real | the hydration door adopts, preserves node identity, and runs each body once | the Node render's markup is what it adopts |
| X3 reactivity adopted | subscriptions come live on React's hydration schedule, refcounts `[1 1 1 1 1]` | — |
| X4 the screen is alive | the hydrated screen dispatches the stated 15 intents | — |
| X5 teardown clean | zero residue after unmounting a hydrated root | — |

So: **the client half of Hicasso SSR is witnessed and is arm-independent.** The
hydration door, the adoption, the reactivity, the liveness and the teardown hold
whatever produced the bytes, and that is a genuine and substantial input to the
sitting for both arms.

**The server half is witnessed for exactly one arm, and not in its compliant
shape.** X1(a) and X1(b) say the Node render is deterministic and adoptable; they
say nothing about a JVM emitter that has not been written, and — because the
spike is Node-only end to end — nothing about the sidecar topology R0 requires
either.

## 8. Unmeasured — the roster

Named explicitly, because at a sitting an honest gap outranks a guess.

1. **No JVM emitter of Hicasso semantics exists**, so there is no parity row, no
   byte comparison, and no idea how many of the 66 existing corpus cases would
   need Hicasso siblings.
2. **No JVM↔Node render contract exists**, so the sidecar has no latency,
   throughput, snapshot-serialisation or process-supervision figure. Since
   2026-08-05 this row is sharper than "unmeasured": per §5 there is no stable
   entry identifier, no entry table and no render-visibility projection either,
   and those three are **contract design rather than measurement**, so they are
   not gaps a further spike could close. §10's Arm B band is priced around them.
3. **Neither arm has a clock row, and neither is owed one.** SSR speed is off
   the bar by HD-012 and by `validation.md`'s "never SSR or test-lane speed"
   line. A sitting that asks for a speed comparison is asking for something the
   programme has ruled it does not measure.
4. **The cost of resolving reads through `compute-sub` on a real page.** The
   mechanism exists and a shared memo is reachable (`compute-sub-with-memo`),
   but nothing has measured what a Hicasso-shaped page costs when every read
   bypasses the reactive cache. The census's seven-read archetype is the obvious
   witness and nobody has run it.
5. **The text-separator row is unsettled and its fixture does not exist**, and
   the corpus that would host it renders with `renderToStaticMarkup`, which
   cannot see the row.
6. **The attribute-only divergence class has no instrument on either arm** — by
   React's documented behaviour, not by any gap here.
7. **HD-021(a)'s headless-door demand is unquantified.** No open bead requires
   it, so the walk arm's co-discharge is a benefit nobody has costed the absence
   of.
8. **How many of the 66 existing parity cases need Hicasso siblings** is
   unknown. The site roster in §4 is enumerated; the corpus mapping is not.
9. **Node's runtime pin is CI-only.** The workflows pin `node-version: '24'`;
   `implementation/package.json` carries no `engines` field and there is no
   `.nvmrc`. A production sidecar would need a real pin.

## 9. The asymmetries, side by side

One row per axis, and no column is a score.

| Axis | JVM structural walk | Node sidecar |
|---|---|---|
| Production runtimes | one (the JVM the app already has) | two, supervised and scaled independently |
| R0 fit | inherits `ssr-ring` wholesale; one call changes | compliant only if a JVM↔Node contract is built; the spike's shape is not it |
| Spec 011 reuse | payload, manifest, head, response, host — all reused | same on the JVM side; the spike's Node side re-spells the payload tag and shell by hand |
| Emitters of Hicasso semantics | two | one |
| Parity apparatus | extend a live one (66 cases today), re-opened at each react-dom bump | none, ever |
| Text-separator row | must be settled first, with new apparatus | does not arise |
| Attribute-only divergence | possible, and unreportable by React | impossible by construction |
| Codec churn exposure | a twin to keep in step; 3–19 commits/day into `front/` this week | none |
| How the host names the root | a Var the JVM calls in-process — the shape `ssr-ring` already uses for core roots, but no Hicasso root is one today: `defview` mints a JavaScript function with no JVM referent (§5) | a stable identifier resolved against a table the app's Node bundle publishes — neither the identifier nor the table exists (§5) |
| `defhost` `:ssr :render` (rf2-l0wfx, 2026-08-05) | not reachable — no React on the JVM; such a host must be written `:client-only` or `{:fallback …}`, so the arm constrains the authoring surface rather than failing | reachable — it is the same React that renders it in the browser |
| What crosses per request | nothing — one process | a render-visibility projection of the post-drain frame-state, in a domain and under a policy not yet written (§5); unmeasured |
| Deploy lockstep | none | two artefacts must ship together; the entry table is the one thing that would make the skew detectable at all |
| HD-021(a) headless door | co-discharged | untouched |
| Server render as data | yes — tree before string | no — a string |
| Spike evidence for its server half | none | X1(a), X1(b), X2 — all of the render, none of the topology |

## 10. Pricing — estimates, labelled as such

Neither table is a measurement. The bead asks for beads and weeks, so here they
are with their basis stated; anyone may re-derive them.

**Arm A — JVM structural walk.** The nearest landed analogue is Freehand's own
cross-host walk: `tree.cljc` + `node.cljc` + `conversion.cljc` = 3,022 shipped
lines plus its conformance corpus. Hicasso's twin is smaller in scope (no
compiled tier) and larger in surface (intents, controlled, presence, route-link).

| Bead | What it lands | Estimate |
|---|---|---|
| A1 | the `.cljc` structural twin of `front/codec.cljs`, tree-emitting, no reads | 1.5–2.5 weeks |
| A2 | the read resolver — `compute-sub` against the request snapshot, sharing one memo across the walk | 0.5 week — the mechanism exists |
| A3 | intents, controlled, presence, route-link server answers | 1–1.5 weeks |
| A4 | the text-separator settlement — a fourth coalescer, a `renderToString`-authored hydration fixture, and whatever 004B/011 change it forces | 0.5–1.5 weeks, **and it may reach the spec** |
| A5 | the parity corpus extension — Hicasso siblings for the corpus cases, **plus a pinning relationship the serialiser does not have for Hicasso today** | 1–2 weeks |
| A6 | the `ssr-ring` render seam and the end-to-end witness | 0.5 week |
| | **total** | **5–8.5 weeks, 6 beads** |

Two things widen that band rather than sit inside it: A4 may turn into a spec
change with its own review, and A1 rebases on every codec landing until the codec
settles.

**Arm B — Node sidecar. Repriced 2026-08-05** against the contract §5 restates.
The first version of this table priced B1 as one row — *"the EDN render contract
— snapshot + root form out"* — and that row was one line hiding three contracts,
none of which the shipped entry supports as written. It is split here rather
than inflated, because a sitting is better served by seeing what the audit found
than by a bigger number with the same shape. **Rows that moved are marked.**

| Bead | What it lands | Estimate |
|---|---|---|
| B1a | **the entry identifier** — an application-defined stable id, and the resolver inside the per-app bundle that turns it back into a root form (**new row**; the mechanism has a shape in core's `reg-view` / `rf/view`, so this is wiring, not invention) | 0.5–1 week |
| B1b | **the serialization domain** — what a root's arguments and a frame-state snapshot may contain, and the render-visibility policy governing what crosses (**new row**; the machine-snapshot round-trip rule is the precedent, the payload allowlist is explicitly *not* reusable) | 1–2 weeks, **and it may reach the spec** |
| B2 | a callable Node process — a real module/IPC surface replacing `globalThis`, with supervision and restart | 1–2 weeks (unchanged) |
| B3 | the JVM side — `ssr-ring` calls the sidecar, keeps payload/shell/head/response | 0.5–1 week (unchanged) |
| B4 | the application build story — how an app produces its own server bundle **and publishes the entry table the JVM's ids resolve against** (**raised** from 1–1.5) | 1.5–2.5 weeks |
| B5 | the skew detector — a deploy-time build-identity check **plus a per-request refusal of an id the bundle does not carry** (**raised** from 0.5, and the cheapest of the raises: the entry table is what gives skew something concrete to compare) | 0.5–1 week |
| B6 | the end-to-end witness in the compliant topology | 0.5 week (unchanged) |
| | **total** | **5.5–10 weeks, 7 beads** |

**What the reprice did and did not do.** It did not discover new work in the
render — the render is built and witnessed, and none of X1–X5 is disturbed. It
discovered that the *contract around* the render was priced against a crossing
that does not exist. Four rows carry that correction: B1a and B1b are new, B4
and B5 are raised. The one that widens the band most is B1b, and it does so
because it is **design with a security dimension** rather than plumbing — it
carries the same *"may reach the spec"* annotation as Arm A's A4, for a
comparable reason: naming the domain in which application state crosses a
process boundary is not obviously a bench-lane decision.

**How the two bands now read.** Arm A is 5–8.5 weeks over 6 beads; Arm B is
5.5–10 over 7. They still overlap across most of their range, so the earlier
reading survives in substance — **on build cost these arms are not far apart, and
the choice is still not a schedule choice.** What changed is the top of the
sidecar's band, which now sits above the walk's rather than below it.

Two observations about *why* the numbers moved, offered as a pair because each
one alone would lean. The sidecar's new uncertainty is *what contract to write*,
which further measurement would not shrink — a spike can witness a render but it
cannot decide a serialization domain. Against that: a contract is written once,
where the walk's band sits on top of a parity obligation that is re-opened at
every react-dom bump for as long as the arm exists (§4). Neither observation is
a recommendation, and the durable differences remain the ones in §9 — one
runtime against two, one emitter against two — both permanent where a build cost
is paid once.

## 11. Tripwires, for whichever arm is chosen

Named now so that neither arm gets to fail quietly later.

**If the walk is chosen:**

- A parity case that cannot be expressed in the corpus because the two emitters
  disagree about the *tree* rather than about the markup. That is a contract
  divergence, not an emitter bug, and it means 004B is under-specified for
  Hicasso.
- The text-separator settlement requiring a change to the tree's canonical form.
  Every existing v1-tree consumer is then in scope and the arm is no longer
  local to Hicasso.
- A rule that cannot be twinned because it needs a live React value at render
  time. The walk cannot reach that case, and a walk that cannot reach a case
  ships an application a hole. **This tripwire stopped being hypothetical on
  2026-08-05, while this page was being amended.** `rf2-l0wfx` landed `:ssr
  :render` as `defhost`'s third policy value — `declared-ssr`'s own words, *"the
  component itself is safe to run on the server and does"* — so a declared host
  can now render a **foreign React component** under `renderToString`. A JVM
  structural twin cannot: there is no React on the JVM, and the component is a
  library's, not the codec's. Stated precisely, because the size of this matters
  and it is easy to overstate: the walk arm does not *break* on such an
  application, it **constrains the authoring surface** — a `:render` host would
  have to be written `:client-only` or `{:fallback …}` instead, both of which a
  JVM twin can serve, and both of which were the only two values available the
  day before. What the walk arm cannot do is offer the third. That is a real
  asymmetry, it arrived by an ordinary week's ruling rather than by anything
  either arm did, and it is exactly the shape this tripwire was written to catch.
- A third emitter appearing for any reason. Two is the contract; three is a
  maintenance surface nobody signed up for.

**If the sidecar is chosen:**

- Anything beyond the body markup crossing the contract — request context, cofx,
  the head model, the response accumulator. That is the host fork the
  adversarial review rejected, arriving by increments.
- The sidecar needing to drain events to render correctly. Same finding, harder.
- A skew between the JVM's view code and the sidecar's reaching a deploy. If B5
  does not exist or does not fire, the arm's failure mode is two different
  applications answering one request.
- Snapshot serialisation appearing in a profile at real app-db sizes. Off the
  bar is not the same as free.
- **The entry table growing past one identifier per server-rendered root.** A
  table with per-route or per-component entries is the render contract turning
  into an RPC surface, and the host fork above arrives that way too.
- **A key that a view reads on the server and the render projection does not
  carry.** This is the one sidecar failure that is silent by construction — a
  wrong page rather than an error — so it needs a test rather than a guard, and
  the test has to be authored per application rather than provided.

## 12. What this page does not say

It does not recommend an arm, it does not rank them, and it does not weigh SSR
against anything. It prices two arms from the spike corpus and the tree, names
what neither arm has measured, and stops. The reading is the operator's.
