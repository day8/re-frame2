# The dogfood preference case — the two live renderings, side by side

The charter's v0 gate reads: *"a dogfood list+form screen preferred over raw
UIx by its authors"* ([charter.md](../charter.md) §Use cases). The preference
verdict belongs to the authors — the operator included — and **this page is not
it**. What no verdict could honestly be taken without is a structured
comparison: two renderings proved to be the same screen, measured on the
authoring rather than described, with the losses stated beside the wins. That
is what this page carries, and it ends with the question rather than the
answer.

Bead **`rf2-2rtt6.67`**, under the EP-0038 epic. The three-rendering mechanism
record is
[arm1-lean-react-dogfood-judgement.md](arm1-lean-react-dogfood-judgement.md)
§4; this page supersedes nothing there — it narrows the comparison to the two
renderings the charter's gate actually names, and adds the halves that page
did not have: an intent-parity witness and the authoring counted.

> **This page publishes no timing row.** Mount and update clocks for these
> surfaces live on [the census-real clock rows](census-real-clock-rows.md) and
> their successors, and the surviving mount-gap question is already owned by
> `rf2-6c237`. Nothing below is a clock, and nothing below may be quoted as
> one.

## Provenance

| | |
|---|---|
| **Bead** | `rf2-2rtt6.67` (parent `rf2-2rtt6`) |
| **Branch** | `worker/dogfood-pref` |
| **The two renderings** | `implementation/freehand/test/re_frame/bench/hicasso/arm1/dogfood_collector.cljs` (Hicasso, the ruled Surface B) · `implementation/freehand/test/re_frame/bench/hicasso/arm1/dogfood_uix.cljs` (raw UIx, the control) |
| **The shared state layer** | `implementation/freehand/test/re_frame/bench/hicasso/front/dogfood.cljs` — one app-db shape, one event set, one subscription set under both renderings, so the comparison is about the view layer and nothing else |
| **The witness** | `implementation/freehand/test/re_frame/bench/hicasso/arm1/dogfood_dom_cljs_test.cljs` — canonical-DOM parity at mount, intent parity through a 12-step interaction script, DOM parity again after the script |
| **Reproduction** | `cd implementation && npm run test:cljs` (node half; DOM claims degrade to stated skips) and `npm run test:browser` (the real-DOM half) |
| **Line counts** | taken on this branch's files by counting non-blank, non-comment, non-docstring lines per top-level form; the arithmetic is restated in §3 so a reader can re-take it |

## 1. The two renderings, brought current first

A preference judged between a stale candidate and a stale control measures
drift, not design. Both files were audited against everything that has landed
since they were written (`rf2-2rtt6.35` `h/fn`, HD-023 `:&`, HD-026
`::h/prevent`, `rf2-2rtt6.54` `::h/navigate`, HD-025 presence-as-data, and the
retirement of the prevent metadata in `rf2-2rtt6.57`).

**The collector rendering needed no code change.** Every event position takes
a literal intent vector, nothing forwards a props remainder, no anchor acts as
a button (the one submit position takes the census-weighted auto-prevent),
nothing routes, and nothing animates an exit. So none of the four collapsed
spellings is *used* — which repeats, on this screen, exactly what the tier-1
roster found on the spine shapes (`rf2-2rtt6.57`): three of the four never
came up there either. **Absence-of-need is evidence, and it is stated as an
absence rather than a pass**: the dogfood screen exercises the tier-1 core
(reads, intents, a controlled field, a keyed list) and does not touch the
escape hatches, which is what the census predicted a spine screen would look
like.

**The raw-UIx rendering needed two changes to be the control it claims to
be**, and one of them is itself a finding:

1. **It is now written the way this repo's own UIx examples teach**
   (`examples/substrates/uix/login/core.cljs`): `:on-change` on controlled
   inputs rather than `:on-input`, shorthand closures at one-expression event
   positions, and the IME keydown gate factored into one helper the way a
   working author would factor it. The control must be as good as its authors
   can make it — a strawman UIx rendering would invalidate the whole exercise.
2. **Its IME gate read the synthetic event, and that gate was dead on its
   modern half.** React's synthetic keyboard event does not carry
   `isComposing` — the property is not in React's `KeyboardEventInterface`, so
   the previous spelling answered `undefined` however plainly the browser set
   it, and a composing Enter would have committed a half-typed draft on every
   IME whose browser doesn't send the legacy keyCode-229 signal. The gate now
   reads the native event. The data key-map on the Hicasso side gets the same
   law from `front.intent/composing?`, centrally, and the trap is measured at
   `arm1_controlled_grid_dom_cljs_test/reacts-synthetic-keyboard-event-drops-is-composing`.
   The point is not that a UIx author *cannot* write this — it is that every
   UIx author must, per app, and the first cut of this very control got it
   wrong.

**The grouped rendering is out of the comparison.** The operator ruled the
grouped `use-subs` surface below the usability bar (2026-07-31), so the
charter-v0 preference question is collector vs raw UIx. `dogfood_grouped.cljs`
stays in the tree solely as the measured control for the collector's
conditional read
(`the-collectors-conditional-read-costs-fewer-edges-than-the-declaration`) and
its file now says so.

## 2. The same screen, proved — elements AND intents

A comparison of screens that behave differently measures nothing, so
equivalence is asserted on both halves of "behave":

- **Elements.** All renderings build the identical canonical DOM (attribute
  names sorted) at mount, with a control proving the comparison can answer
  false (`the-three-renderings-build-the-same-page`).
- **Intents.** One 12-step interaction script — a toggle click, the filter
  there and back, typing into the new-item field, two composing Enters, a
  real Enter, typing into a row draft, an Escape, a remove click, typing
  again, and a real form submission through the submit button — is driven at
  each rendering through real DOM events (a real `click`, a
  prototype-setter keystroke plus `input`, a real `keydown` with the
  composition signals on the native event). The frame's processed events are
  captured at the substrate's own `:events` listener stream (Spec 009 — the
  public observation port, so the witness holds no hook into either
  rendering), and **both captures are asserted equal to the script's stated
  expectation** — ten named intent vectors, in order, with the two composing
  keystrokes expected to dispatch nothing. Stating the expectation rather
  than only comparing the two captures is what lets the gate answer false
  against a drift both renderings share. After the script, the two pages are
  asserted canonically identical **again**, so parity is known to hold
  through the interactions and not only at mount.
  (`the-two-live-renderings-dispatch-the-same-intents-on-the-same-interactions`.)

The mutation rows for the intent witness are in the PR's quality-gates
section: making the raw-UIx rendering's Escape dispatch a commit instead of a
cancel reds the witness naming the drifted rendering, and restoring the
synthetic-event `isComposing` read reds it on the composing probe — then both
green on restore. A witness that has never been watched failing is a claim,
not a gate.

## 3. The authoring, measured

Everything in this section is countable on the two files as they stand, and
the counting rule is stated with each number. The state layer is shared and
identical, so nothing below is about events, subscriptions or app-db — it is
the view layer only, which is the only thing the two files disagree about.

### 3.1 Lines, by form

Counting rule: non-blank, non-comment, non-docstring lines per top-level
form. The UIx `root` form (its `frame-provider` wrapper) is excluded from the
total because the collector's equivalent lives in the shared mount door
(`arm1/mount.cljs`) — including it would charge UIx for plumbing both sides
need; stated, so a reader can put it back.

| Form | Collector | Raw UIx |
|---|---:|---:|
| `head` | 5 | 5 |
| `new-item` | 8 | 15 |
| `filter-button` | 7 | 6 |
| `filters` | 5 | 7 |
| `row` | 13 | 20 |
| `todo-list` | 4 | 5 |
| `screen` | 6 | 6 |
| `ime-gated` (UIx only) | — | 8 |
| **Total** | **48** | **72** |

48 against 72 is exactly a 1.5× ratio, and it is worth saying immediately
that **line count on its own would not decide anything** — the
three-rendering judgement said the same when collector and grouped came
within a handful of lines of each other. What the per-form column shows is
*where* the difference lives: the two forms with a controlled input plus a
key law (`new-item`, `row`) carry almost all of it, and `ime-gated` is pure
apparatus with no collector counterpart. The chrome forms (`head`,
`screen`, `todo-list`, `filters`) are within a line or two either way —
markup is markup on both sides.

### 3.2 Event positions: eight sites of data against eight closures

The screen has eight distinct handler sites (submit, new-item change,
new-item keys, filter click, toggle, draft change, draft keys, remove).

- **Collector:** all eight carry **data** — six intent vectors and two
  key-maps. Zero hand-written closures, zero `preventDefault` calls, zero
  event-object interop, zero IME handling. The runtime writes the closures,
  the submit position auto-prevents (census-weighted default), and the
  key-maps inherit the composition law centrally.
- **Raw UIx:** all eight are **hand-written closures** — six reaching into
  the event (`(.. % -target -value)`) or dispatching directly, one calling
  `.preventDefault`, and two built by the file's own `ime-gated` helper,
  which the author had to know to write (§1's finding: the first cut got it
  wrong).

Each closure is also a place the wrong event id can be typed with nothing to
notice; an intent vector is a value a structural test can assert with `=`
(the HD-026 school — behaviour in the vector where equality can see it). On
the other side of the same coin: a closure can do *anything* the event
affords, with no further concept — see §4.

### 3.3 Prop threads: two values threaded against none

- **Raw UIx** threads `current` and `dispatch` into `filter-button` (two
  extra parameters, three call sites), and destructures `dispatch` from
  `use-frame` in three components — a hook may not run inside a plain
  helper, so a helper consumes what its caller reads. This is the same
  shape, at screen scale, as the roster port's finding at real-app scale:
  the census threads `current-user` into every comment card because a
  hook-shaped read must sit at a fixed site, and the collector port deleted
  the prop from the call site and the argument vector
  (`shapes/ordinary.cljs`, port change 2).
- **Collector:** zero threaded values. `filter-button` reads the filter
  where it compares it; its read is donated to the enclosing boundary
  (HD-016). `dispatch` appears nowhere in the file — intents are data, and
  the runtime owns delivery.

### 3.4 Reads: conditional against unconditional

The collector's `row` reads the draft **only when the row is editable** — a
completed row holds one edge, an editable row two, and the page-level
consequence is measured (fewer edges than the declared-read control on the
same page, `the-collectors-conditional-read-costs-fewer-edges-than-the-declaration`).
The UIx `row` reads the draft on every render including a completed row's,
because a hook may not sit inside a `when`; its cost scales in hooks — the
dispatcher-level ledger (`arm1_hook_ledger_dom_cljs_test`) counts the whole
Hicasso shell at two hooks whatever the read count, against a per-read hook
count on the UIx spine that rises with every `use-subscribe`.

The honest converse is in §4: a static hook set never changes identity, so
raw UIx never pays a re-subscribe on a branch flip, where a collector
boundary whose control flow changes its read set replaces its whole edge
set (priced in [the judgement page](arm1-lean-react-dogfood-judgement.md)
§2(b)).

### 3.5 What an author must know, per side

Named framework forms actually used by each file (counting rule: forms and
reserved spellings appearing in the file or in the shared intents it puts on
props; the shared state layer is identical on both sides and excluded):

| | Collector | Raw UIx |
|---|---|---|
| **Forms used** | `defview`, `sub`, intent vectors at event positions, the `::h/value` marker, the data key-map | `defui`, `$`, `use-subscribe`, `use-frame`, `frame-provider` |
| **Count** | 5 | 5 |
| **Hand-carried responsibilities** | none on this screen | `preventDefault` on submit; event-object interop; the IME composition law *including* the native-event trap; the hooks discipline (no read in a `when`, a `for`, or a helper — so threading instead) |
| **Escape hatches needed** | none — `h/fn`, `:&`, `::h/prevent`, `::h/navigate`, presence all unused (§1) | none — the event object is already in hand |

The form counts tie at five, and that is an honest tie — Hicasso does not
win this screen by having fewer names. The difference is the second row:
what the names *leave the author to carry*. The collector's five forms close
over the submit default, the composition law and read placement; raw UIx's
five leave each of those as author-written code on this screen, and §3.1's
24-line difference is that row made countable.

### 3.6 Imports

Three requires each — `[runtime :refer [sub]]` + shared model + the
`defview` macro on one side; `adapter.uix` + shared model + `uix.core` on
the other. No difference worth a preference.

## 4. What raw UIx does better here

The charter keeps a "Known losses coming from Reagent" section on principle;
this is the same section for the comparison actually being judged. Four
things are genuinely better on the raw-UIx side of this screen, and one is a
draw worth naming.

1. **Compile-time shape checking.** `$` is a macro: a malformed element
   head or props position fails at compile. Hiccup is interpreted — the
   collector's failures at the same positions are loud runtime errors with
   named recoveries, but runtime is later than compile, and no witness
   changes that ordering.
2. **The event is already in your hand.** Any interop with the live event —
   files, modifiers, geometry, non-standard members — is ordinary code in a
   closure the author already wrote. Under intent-as-data the same needs are
   one more concept (`h/fn`), unused on this screen but real the day the
   screen grows. The census says that day is rare (~97% of handler sites are
   pure data); it does not say never.
3. **Stable subscription identity by construction.** A hook site is fixed,
   so raw UIx never re-subscribes on a branch flip. The collector's edge set
   is a function of what the body did, and a boundary whose control flow
   changes its reads replaces its whole edge set at commit — correct,
   witnessed, and priced as not cheap
   ([judgement §2(b)](arm1-lean-react-dogfood-judgement.md)). On *this*
   screen the flip (a row completing) is rare and small; on a screen where
   it is neither, this loss grows.
4. **Maturity of the machinery underneath.** Raw UIx's correctness surface
   is React's own, plus a thin adapter that predates this programme. The
   collector's read surface needed its own fence corpus to be safe — the
   eager codec, the loud escaped-read guard, the boundary-crossing
   realisation, the deferred-read refusal (`rf2-2rtt6.45`, `rf2-2rtt6.32`)
   — precisely *because* `sub` is legal in places a hook is not. The
   apparatus is invisible to the author and witnessed, but a surface this
   permissive carries a risk budget a hook surface structurally cannot,
   and the honest statement is that permissiveness was paid for in runtime
   engineering, not obtained free.
5. **A draw, recorded because a reader would ask:** React DevTools names —
   `defview` stamps `displayName` at mint, so both sides read as named
   components; and the ambient-frame plumbing is the same substrate context
   under both renderings.

What is *not* on this list: performance, in either direction. Clock
questions live on the clock pages, and the one open mount question is
`rf2-6c237`'s.

## 5. The open question

The authors are being asked: **for the everyday spine screen — a list, a
controlled field, a filter — which of these two files do you want to write,
read and maintain?** The evidence on the table:

- Both files are proved to be the same screen — same canonical DOM at mount
  and after a 12-step script, same ten intents on the same interactions,
  same silences on the composing keystrokes (§2).
- The collector writes the screen in 48 lines to idiomatic UIx's 72, with
  the whole difference concentrated where events and reads live, not in the
  markup (§3.1); eight event positions carry data instead of eight
  hand-written closures (§3.2); nothing is threaded (§3.3); a completed
  row's read disappears with the branch that needed it (§3.4); and the two
  laws this screen actually exercises — submit prevention and IME
  composition — are the runtime's once, not the author's per app, which is
  the difference §1's control-side bug makes concrete.
- Raw UIx answers with compile-time checking, the event in hand, fixed
  subscription identity, and a smaller machinery bet (§4) — and it ties on
  concept count (§3.5) and imports (§3.6).
- Neither side needed an escape hatch: the four collapsed spellings stayed
  unused here exactly as they did on the spine shapes (§1), so the
  comparison is between each surface's *core*, which is the comparison the
  charter's gate wants.

What this page deliberately does not do is convert that table into a
verdict. The charter's gate is written as a preference held by people —
"preferred over raw UIx **by its authors**" — and the close of
`rf2-2rtt6.67` records that the preference call is the operator's. When it
is made, it should cite this page as the evidence it was made against, and
anything found missing from this page is a defect to file against it.
