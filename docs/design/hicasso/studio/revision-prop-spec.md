# The revision prop on a controlled element — the adjudicated spec (rf2-2rtt6.107)

**This page is not a new design.** The design pass and its adversarial pass were
both run on 2026-08-04 and the operator recorded a ruled synthesis on the bead the
same day (0 FATAL / 3 MAJOR / 7 MINOR, verdict *build with the named repairs*).
Those two documents live in the local-only `ai/findings/` tree, which is
gitignored: nothing outside the machine that produced them can read them, and the
bead comment that carries the verdict cites them by paths no maintainer has.

So this page does two things and no third. It **promotes** the ruled shape into the
tracked record, in the form an implementer builds from. And it **adjudicates** that
shape against `main` as it stands today — because both passes were verified against
the tree at the `0bfd309b67` era, and two codec changes have landed since, one of
them at 03:23 the following morning, ninety minutes after the adversarial pass
closed. Re-verification found the mechanism intact and the cite apparatus already
rotting.

**No number is published here.** The prop is unbuilt; this is a spec, not evidence.

---

## 1 · Provenance, and what each input is worth

| Input | Status | What it contributes |
|---|---|---|
| The design pass (2026-08-04 21:42) | local-only, gitignored | The mechanism, the effect table, the scope fence, the honest losses |
| The adversarial pass (2026-08-04 21:59) | local-only, gitignored | 0 FATAL / 3 MAJOR / 7 MINOR; the three repairs that make the design shippable |
| The operator's synthesis (2026-08-04 12:03, bead comment) | **binding** | The ruled shape, seven numbered points plus three mandatory repairs |
| This page (2026-08-05) | tracked | Promotion, plus re-verification against current `main` |

*Three columns; four body rows; hand-counted.*

Where this page and the bead comment differ, **the bead comment governs the
shape and this page governs the cites** — every line reference below was
re-derived today, and §6 lists the ones the synthesis carries that have already
moved.

---

## 2 · Step zero — the law, quoted and anchored by text

The bead required the pass find the fixed reset law before designing around it.
It is found, and it is a ruling body rather than an addendum. From HD-019 in
`docs/design/hicasso/decisions.md`:

> Resets are by **explicit caller revision, never value equality** (the
> predecessor's ruled reset law, kept — `docs/design/freehand/decisions/D016-buffered-and-revision-controls.md`).

The predecessor's constraint that sentence rests on, from
`D016-buffered-and-revision-controls.md`:

> 4. A caller revision/reset key is distinct from the model value. It must be able
>    to change even when the value is equal.

and its explicitness clause:

> If the caller changes `:value` while keeping the same reset key, the current
> draft continues. If the caller intends to replace/reject the edit, it must change
> the reset key. This distinction must be explicit; otherwise the component
> guesses.

and the single caret clause the law carries, which is **permission and not
obligation**:

> - A transformed accepted value may move the caret only when the caller's new
>   reset key establishes a new baseline.

**Anchored by text, deliberately.** The synthesis cites this as
`decisions.md:668-670`. It is not there any more — see §6.1. A quotation survives
an edit above it; a line number does not.

### What the law fixes, and what it leaves open

Fixed and immovable: **the trigger**. A reset fires on an explicit caller revision
change and never on value equality; the revision is distinct from the value and
must be able to change while the value is equal; a value change under an unchanged
revision continues the draft rather than resetting it.

Not fixed, and correctly not claimed by the guide's own not-settled row: the
reset's **effect table on a raw element** — where the caret lands, what happens to
a live composition, what hydration does, and the spelling. D016 is the *buffered
controller's* protocol, and that controller is unbuilt and deferred past v0 by the
charter. HD-019 kept its trigger sentence for the element path and nothing else.

Everything in §3 beyond the trigger is therefore design, and is marked as such.
This is not a stop-finding: the bead instructed that where the law is silent the
design proposes and marks the proposal.

---

## 3 · The ruled shape

Seven points, as the operator recorded them, with today's verification noted
inline.

**3.1 Spelling.** `::h/revision` = `:re-frame.hicasso/revision`, **[unfrozen]**,
matched as the exact namespaced keyword — never slot-claimed. Roster
re-checked today: the `::h/*` data vocabulary is `::h/value`, `::h/checked`,
`::h/prevent`, `::h/navigate`, `::h/mounting`, `::h/unmounting`. No `revision`, no
collision. The reserved *attribute keys* are two today — `:&` and `:ref`, under the
codec docstring's heading "Two reserved attribute keys, and nothing else" — and
this makes them three, which is a doc delta the codec docstring and `authoring.md`
must carry rather than a surprise a reader discovers.

**Freeze flag, escalated.** Three reset-ish names will exist: this element
revision, `h/boundary`'s `:reset-key` (which *remounts*), and D016's ladder
`:reset-key` if the controller ever ships under its own spelling. Two of those three
would be the same word for opposite conduct. The API freeze must face the trio
together; nothing here needs deciding now.

**3.2 Semantics.** A revision change (CLJS `=`) re-baselines the field to the model
**without remount** — the node is kept, focus is kept, and the caret lands at
end-of-model on the commit that carries the reset. That destination is the
platform's own conduct for a `value` assignment, not a choice the design
implements, and D016's caret clause licenses it. Equal-but-fresh revision values
are inert, by `=`. The authored-data rule the guide should teach is the instance
key's, transplanted: *if it would be a good instance key, it is a good revision
value* — a domain fact written by events, never a render-order index, never a
counter minted in render, never `random-uuid`.

**3.3 Mechanism — zero new machinery.** The transport is React's own per-commit
controlled re-assert, already firing on every re-render of every Hicasso controlled
element. The codec mints a fresh props object per element per render (HD-004
refuses prop-object caching); React marks a host update on props *identity*, not
value; the commit runs `updateInput` unconditionally for an `<input>`; and
`updateInput` assigns only when the DOM disagrees. So the whole delivery is: the
revision is a value the body reads, its change re-runs the body, the re-run
re-commits the element, and the commit re-asserts the model against the DOM.

No hook, no ref, no comparison record, no keyed re-render, and **no third
`flushSync` site**. In-turn resets ride the keystroke converge; deferred resets
ride the `compositionend` site; out-of-band resets ride ordinary commits.

**A named dependency, stated so it cannot be optimised away.** None of those three
React behaviours is a public contract — the same class as the `defaultValue` mirror
dependency HD-019's Reopens clause already names. The design pins them with one
invariant row, and promotes HD-004's no-caching posture from a
measurement-honesty stance to a **correctness dependency of the reset transport**.
Any future prop-object memoization must exclude controlled text elements or
re-design this delivery.

**3.4 IME.** A revision change arriving mid-composition **defers to the exchange's
close**. This is not a new deferral — it is the carve-out's own, inherited, with no
new machinery and no revision-comparison state. The argument is mechanical before
it is philosophical: an immediate write mid-composition silently aborts the
composition (no `compositionend` fires, the next IME update mints a fresh
`compositionstart`), and on a normalising field that abort corrupts the commit —
the measured `SSHSH` row. There is no cancel primitive to build an immediate
variant from; the only writes available are exactly the ones the carve-out exists
to suppress. Every release path — `compositionend`, a non-composing change, **blur**,
unmount — converges the field to the then-current model.

**3.5 Refusal.** `::h/revision` on a non-controlled element is a loud refusal:
`:rf.error/hicasso-revision-not-controlled`, per-render, in the codec's existing
error shape, on the same cost shape as `check-ref!`. The acceptance predicate is
`controlled-text-tag?` — the predicate that already chooses the shadow component,
reused rather than duplicated.

State the consequence honestly rather than overstating the coverage: because that
predicate is deliberately type-blind (tag ∈ {input, textarea} plus a non-nil emitted
`value`), a checkbox written idiomatically with `:checked` and no `:value` is
refused, but **a checkbox carrying a form-submission `value="yes"` is accepted, and
the revision is simply inert there**. An `<input type="number">` is likewise
accepted, with caret semantics that do not apply. "A checkbox is refused" is the
wrong sentence for the guide; "a value-less checkbox is refused" is the right one.

**3.6 SSR and hydration.** The strip is codec-level and consumed before emission,
and the SSR entry runs the same codec under `renderToString` — one renderer in two
places — so the server bytes cannot carry a `revision` attribute by construction
rather than by a server-side special case. Server HTML carries the model value, as
the existing hydrated-controlled-input witness already asserts, and the
`defaultValue` invariant row extends to the hydrated node.

**3.7 Scope.** The single prop **only**. No generation fencing, no commit/cancel
intents, no `:control` addressing, no controller record, no acknowledgement signal,
no per-element opt-outs, no caret-policy knobs, no second reserved key. The post-v0
ladder **consumes** this trigger; it never extends the prop.

---

## 4 · The three mandatory repairs

These came out of the adversarial pass and the operator ruled them binding. Each
carries its own witness, and none forces a redesign.

**R1 — the `:&` door.** *The big one.* The design as briefed matched
`::h/revision` in `convert-entry`, which walks the **merged** map. `merge-caller`
denies a remainder only the structural slots plus the slots owned by literals the
element wrote, so `{:& {::h/revision r}}` survives the merge, reaches the walk, and
would arm a live reset from the hostile-remainder door — a remainder forcing field
re-baselines the element's author never wrote.

The fix is the `:key` precedent, taken whole rather than half. `native-element`
reads `:key` off the author's **own pre-merge map**, and the revision read goes in
the same place; `convert-entry` gets a skip-or-refuse rather than a stash, so a
literal never emits as an attribute and a remainder's is never armed. Take the
refusal — it is the lane's posture. **Witness: `{:& {::h/revision r}}` never
resets, and the literal wins when both are present.**

Re-verified today on current `main`: the premise still holds exactly.
`structural-slots` is still `#{"key" "ref"}`; `denied-slots` is still those two
seeded with each owned literal's slot; and `native-element` still holds the raw
pre-merge props while `convert-props` does the merging internally. **The landing
site R1 needs is available and unchanged.**

**R2 — the overclaim.** Strike "the reset cannot be lost". On an *accepting* field
the deferral does not protect the reset from ordinary event order: while the shadow
is held the model keeps receiving every composing update, so after a mid-composition
reset the very next composing update — including the commit's own final input event
— dispatches the field's text, and the model takes it. The `compositionend` converge
then lands the then-current model, not the model the reset produced, and discarded
pre-reset content can ride back in through the draft echo.

What is true, and what the prose must say instead: **the deferral cannot strand the
field.** Every exit converges to the then-current model; a post-bump dispatch
supersedes the reset by ordinary event order, exactly as it would at rest. The
witness row must pin that honest conduct — either by giving the reset field a
refusing model policy, or by asserting the then-current model with the trajectory
recorded — because as originally specified the row reds by construction.

**R3 — mid-adoption.** The claim that a reset during hydration adoption "lands on
the first post-adoption commit on the server's node" is the one mechanism claim in
the design asserted with no source cite, and the failure mode if it is wrong is
**node loss** — a deopt discards the server node, which is the exact
remount-destroys-focus class the whole design exists to avoid. Demote it from
assertion to **witness-gated intent**. Its row runs *first* in the hydration suite,
as a design-validation witness rather than a regression row, and the fallback is
pre-committed rather than improvised: if React deopts, the documented conduct
becomes that the reset defers past adoption through the same deferral shape as IME,
and the row pins that instead. **Never ship the claim unpinned.**

---

## 5 · Witnesses

The design's ten rows, plus the four the adversarial pass added.

| Row | Claim |
|---|---|
| a | A revision change discards the draft and resets to the model, keeping focus, without remount |
| b | An unchanged revision never resets — **scoped to "at rest"** (see §6.4) |
| c | Equal-but-fresh revision values never reset, by `=`; and the memo half bails |
| d | The equal-value reset repairs foreign drift — **this is the named invariant row** |
| e | The refusal row: a `:div`, a value-less checkbox, a `select`, a value-less input |
| f | Never a DOM attribute: no `revision` in rendered `outerHTML`, none in SSR bytes |
| g | The hydration row — **runs first**, per R3 |
| h | The IME row on the real composition harness, both value-equal and value-changed |
| i | The memo row, and the per-controlled-field hook count still 1 |
| j | In-turn ordering: both `flushSync` sites unmoved |
| R1 | `{:& {::h/revision r}}` arms nothing; the literal wins when both are present |
| R2 | Reset-during-composition pins the actual conduct at the close, not the overclaim |
| R3 | The blur-mid-composition variant — bump, then move focus, assert the reset model |
| R4 | The invariant row runs on `<textarea>` too — its chain is parallel and otherwise unpinned |

*Two columns; fourteen body rows; hand-counted.*

Bench lane; the composition harness for the IME rows; the existing hydration
harness for the hydration rows.

---

## 6 · The adjudication — what re-verification on today's `main` found

This is the part that did not exist before. Both passes were verified against the
`0bfd309b67` era. Since then `rf2-vrvv9` landed at 03:23 and `rf2-2rtt6.119` at
04:29 the following morning — **both after the adversarial pass closed at 21:59** —
and both landed in `front/codec.cljs`, which is one of the two files this bead's
implementation touches.

**6.1 — The synthesis's law cite is already stale.** The ruled comment cites the
law as `decisions.md:668-670`. It is now at `742-743`; the guide's not-settled row,
cited as `04:159`, is now at `164`. Nothing about the law changed — the text is
verbatim identical — but an implementer following the cite lands seventy-four lines
short. **This is why §2 quotes rather than points**, and why the implementation
brief in §7 is anchored on symbol names rather than line numbers. A design that
outlives its cites is the normal case in this tree, not the exceptional one.

**6.2 — Both passes pre-date the two codec landings, and the doctrine they
reinforce.** The adversarial pass logged as MINOR that exact-keyword matching is
"the codec's second doctrine exception" — the codec's charter being *one canonical
slot, and every rule asks it, never the key it was written as*. That framing was
fair when written. It is now weaker, because the two most recent changes to that
file both went the other way and said so by name: `rf2-vrvv9`'s rule is written
against **slots rather than keys**, in terms ("a rule written against the spelling
is a rule the other spellings walk past"), and `rf2-2rtt6.119`'s fix is that the
class slot is *a position at the crossing too*.

**This does not kill the spelling**, and I am not reopening it. `:key` is genuine
precedent, and §3.1's collision argument stands: claiming the slot `"revision"` for
the *trigger* would make bare `:revision`, `"revision"` and `:x/revision` all mean a
reset in some positions and an ordinary attribute in others, which is worse. But the
point worth carrying into the freeze is that `:key` has **both halves** — an exact
match in the walk *and* a canonical-slot denial in `structural-slots` — and the
revision as ruled takes only the first. R1's skip-or-refuse closes the same gap by a
different route and is sufficient, so no change is required; it is the *reasoning*
that should be recorded, because the next reader of that file will arrive with
vrvv9's doctrine fresh and should find the exception argued rather than assumed.

**6.3 — `rf2-vrvv9` narrowed the foreign crossing only; the native walk still
stringifies.** `host-prop-value` now hands a keyword across by identity, with
stringification narrowed to the `className`/`id`/`role` slots plus the `data-*` and
`aria-*` families. The **native** walk's `convert-prop-value` was not narrowed: it
still answers `(name v)` for every keyword and symbol.

This does not touch the prop when it is spelled correctly, because a correctly
spelled revision never reaches the native walk. It makes one *honest loss* worse
than the design states. The design records that a misspelled bare `:revision` is
silent and becomes an ordinary DOM attribute. Post-vrvv9 the sharper statement is
that a misspelled bare `:revision` carrying the most natural revision value there
is — a namespaced keyword — emits `revision="rev-3"` with the namespace deleted, so
two distinct revisions collapse to one attribute value. It is still a documented
limit rather than a defect, but the guide's troubleshooting line should describe
what the author will actually see in devtools.

**6.4 — The MINOR repairs the synthesis compressed, restated so none is lost.** The
operator's comment carries R1–R3 explicitly and folds the rest into "the design's
ten rows plus…". For the implementer's benefit: scope witness b's claim to *at
rest*, because an unchanged revision plus foreign drift plus any unrelated
re-render **does** rewrite the field — a controlled field's DOM is re-asserted on
every commit, and the revision *guarantees* a commit through memo walls rather than
being the only source of one. Document the caret split as chosen: a reset carried by
the field's own keystroke behaves as a normalisation and lands offset-from-end,
while a reset from anywhere else lands caret-at-end. Fix the checkbox wording per
§3.5. Run the invariant row on `<textarea>` as well as `<input>`. And record in the
codec docstring that an element writing a literal `::h/revision` silently denies a
remainder's ordinary bare `revision` attribute, by the owned-literal law — correct
conduct, but a surprise if undocumented.

**6.5 — `front/controlled.cljs` is unchanged, and every cite into it holds.** No
commit has touched it since the passes ran. `converge-to!`, `converge!`,
`controlled-text-tag?`, `install!`, the blur release path and the shadow's props
copy are all where the design says they are. The second caret read — the one that
exists because a handler re-rendering the same input from a text type to a number
type leaves the wrapper against a caretless element, where `setSelectionRange`
raises `InvalidStateError` — is present and is **not** a third `flushSync` site. It
is the same element inside the same exchange, and HD-019's exception is granted to
an *audited mechanism* rather than to a count, with its addendum auditing exactly
that second read. The design's §6 audit statement is compatible; it should be
phrased as the mechanism clause rather than as a site tally, so the next reader does
not have to re-derive that the two are not in tension.

**6.6 — `rf2-2rtt6.122` moves the guide's worked example, not the design.** The
ambient refusal withdraws the ambient *find* and never the *carrying*, so the
revision read in a body via Hicasso's own frame-carrying subscription form is
unaffected, and an intent vector at an event position still lowers with the frame
carried. What changes is the shape of the example the guide should print for the
revert half of a reset: a handler that calls `rf/dispatch` as a function will not
find a frame ambiently, so the worked example must use the intent-vector form (which
is the idiomatic spelling anyway) rather than a closure calling `rf/dispatch`. That
is a note for `rf2-2rtt6.113`, which teaches the prop after the implementation
merges, not a change to this spec.

**6.7 — `rf2-2rtt6.116` is not a dependency, and this bead does not wait on it.**
The open operator-owned bug concerns an `h/fn` at an unclaimed `defhost` prop slot —
`host-entry`'s routing at a foreign crossing. The revision prop is a native-element
concern read in `native-element` before the merge, and it claims nothing across
HD-011's fence: at a `defhost` crossing `::h/revision` is the foreign component's
own prop, shallow-converted like any other. There is no overlap in file region, in
predicate, or in ruling. **This bead is unblocked.**

---

## 7 · The implementation brief, re-anchored

Anchored on symbols rather than line numbers, per §6.1.

1. **Codec** (`front/codec.cljs`). Read `::h/revision` in `native-element` off the
   author's own pre-merge props map — the same expression shape that reads `:key`
   there — and stash it on the emitted object under a private slot. In
   `convert-entry`, add a `keyword-identical?` **skip-or-refuse** beside the
   existing literal `:key` skip, so neither a literal nor a remainder's copy ever
   emits as an attribute. Only the exact namespaced keyword; every other spelling
   flows on as an ordinary attribute. The boundary and host walks are untouched.
2. **Controlled** (`front/controlled.cljs`). In `install!`, one `unchecked-get` for
   the marker on every native element: absent is today's behaviour at the cost of
   the read; present means delete the marker, and refuse in the codec's error shape
   if the element is not a `controlled-text-tag?`. **No other change** — no hook, no
   ref, no comparison, no shadow change, no converge change, no `flushSync` change.
3. **Witnesses**: the fourteen rows of §5, with **g first** (R3) and **R1 not
   optional**.
4. **Docs in-PR**: the codec docstring's "Two reserved attribute keys" heading and
   table become three; its canonical-slot doctrine paragraph gains the second
   counter-example with §6.2's reasoning; the `controlled.cljs` namespace docstring
   gains a short revision section; a dated HD-019 addendum records the trigger law
   applied at the element, the deferral, the audit statement per §6.5, and §3.3's
   named React dependency; `authoring.md`'s reserved-key paragraph moves two to
   three. Anchors and table columns verified by hand and said so in the PR body.
5. **Sequencing.** `front/codec.cljs` now carries three beads — this one, the
   `[:>]` raw escape, and the key warning. The mayor sequences; they do not go in
   parallel.

Guide 04's not-settled row resolves under `rf2-2rtt6.113`, **after** the
implementation merges, with the ladder boundary stated: the revision prop is not
the ladder.

---

## 8 · The honest losses, as they stand today

1. **No immediate mid-composition reset.** Between the bump and the exchange's
   close the glass shows the composition. A "clear this field now" caller is not
   served until `compositionend`, blur, or the next non-composing input. The model
   is correct throughout; the glass is not. The alternative is worse by measurement
   rather than by taste.
2. **A post-bump edit supersedes the reset** on an accepting field, including the
   composition's own echo at its close. This is ordinary event order and it is the
   correct semantics, but it is a real outcome and R2 exists so the prose says so.
3. **No selection or range restoration** on out-of-band resets — the caret lands at
   the end and a range collapses. The standing range residue extends to resets,
   unfixed by choice.
4. **A misspelled bare `:revision` is silent**, and post-vrvv9 it is silent *and*
   lossy on a namespaced value (§6.3).
5. **No reset acknowledgement.** A caller cannot observe that the reset landed.
   The ladder's completion protocol is post-v0.
6. **Chromium-only composition evidence**, which is the harness's standing limit
   and covers the new rows too.

---

## 9 · What needs a ruling

**Nothing, for this bead.** The trigger law is ruled and quoted; the effect table
is proposed where the law is silent, which the bead licensed; the shape was
adjudicated by the operator on 2026-08-04 with three named repairs; and
`rf2-2rtt6.116` is not a dependency (§6.7).

Two items are **flagged forward** rather than escalated, because neither blocks
the build:

- **The freeze sitting owes the reset-name trio a decision** (§3.1) — element
  revision, boundary `:reset-key`, and the ladder's `:reset-key`. Two of the three
  are the same word for opposite conduct. This rides the general API freeze; it does
  not need a sitting of its own, and the bead's own reasoning that the name question
  dissolves into the freeze is confirmed.
- **The canonical-slot doctrine now has a second exception** (§6.2), argued rather
  than assumed. If a third arrives, the doctrine should be restated with its
  exceptions enumerated rather than accumulating them one commit at a time.
