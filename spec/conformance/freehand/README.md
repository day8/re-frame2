# Freehand Executable Laws

> **Type:** Reference
> This directory owns **how a Freehand law is named, cited, and indexed** — the
> `FH-<AREA>-<NNN>` addressing scheme and the index that binds each id to its
> canonical spec paragraph, its host/mode applicability, its fixture, and its
> status. It does not own how a fixture runs, and it never restates a law
> normatively.

Freehand is one view substrate with two modes — interpreted and compiled — and
one semantic model shared between them. Parity between the modes, and across the
React, JVM, and SSR hosts, is not asserted in prose: it is proven row by row. A
**law** is one such provable statement. Every law has a permanent id, and every
id resolves to exactly one paragraph of normative spec.

- [`conformance-index.md`](conformance-index.md) — the index: one row per law.
- `fixtures/` — the fixture values laws are proven by, added as laws land.

## The id scheme

```
FH-<AREA>-<NNN>
```

- `FH` — fixed prefix. It marks a Freehand view-substrate law and nothing else.
- `<AREA>` — one uppercase token from the roster below. The roster is closed.
- `<NNN>` — a three-digit zero-padded ordinal, unique within its area.

`FH-PROPS-007` is a **citation**, not a description: spec prose, fixture files,
test names, tool output, review conversation, and release evidence all name the
law by id. Ids are therefore permanent — they are never renumbered, never
reassigned, and never recycled. A law that is withdrawn keeps its id at status
`retired` so an old citation resolves to an honest answer instead of dangling.

### Area roster

The roster comes from the Freehand programme EP, which introduces it with
"Areas include …" — open, not closed. It is nonetheless closed at any given
moment: the validator rejects a section or an id whose area is not in the table
below, so an area is added by a recorded ruling in the change that needs it,
never by an author reaching for a token mid-allocation. A law is filed under the
area whose **canonical paragraph** owns it; when two areas could plausibly hold
it, the owning spec decides.

Adding an area is cheap before ids are allocated and expensive after, because
`FH` ids are permanent: a law filed in the wrong area either keeps a citation
that misdescribes it forever, or is retired and reissued. So the question is
settled when the first row in a candidate area lands.

| Token | Area | Scope |
|---|---|---|
| `CALL` | Calls | The declared boundary: vector-called descriptors, plain-helper direct calls, statically named children crossing the boundary, `:key`, occurrence identity and hot reload, and the rejected local/effect/ref declaration forms. |
| `PROPS` | Props | One props map: reserved `:children`, stripped `:key`, shared equality and conversion, and the optional schema's metadata and validation semantics. |
| `EVENT` | Events | The projection materializer, the closed listener-options grammar, site and proxy lifetime, and the atomic selected bundle. |
| `INPUT` | Controlled input | The one exact door predicate, the frame-scoped synchronous flush, and the real-browser contention matrix. |
| `SUB` | Subscriptions | Render-only subscription reads: value, resolution, invalidation, and commit safety; separately named one-shot reads; frame-context observation and the proof a compiled elision demands. |
| `CTRL` | Controllers | Props-only by default; ordinary frame data keyed by kind plus an explicit address; semantic transitions and owner cleanup. |
| `PRESENCE` | Presence | One keyed retention, override, timeout, accessibility, and test contract. |
| `TOPLAYER` | Top layer | The popover/modal desired-state pair, the commit and generation law, structural metadata, and the browser matrix. |
| `BEHAVIOR` | Behaviors | The id/config/timing/optional-command protocol, commit-only connection, explicit command target, private memory, and the JVM marker and fallback. |
| `REACT` | React bridges | The descriptor-only outward bridge, common frame and props semantics, the explicit mapper, the SSR policy, and the qualified host boundaries the three host shapes rest on. |
| `ERROR` | Errors | The boundary, reset, fallback, and safe-intent contract; private frame error egress; and the rule that a failed candidate publishes nothing. |
| `ROOT` | Roots and SSR | One Root Descriptor: preflight, identity, teardown, multi-root isolation, SSR emission, and hydration. |
| `ROUTELINK` | Routing link | The framework-supplied navigation anchor: a real element with a real href, the native-behaviour deferrals, the caller veto, the server shell, and the absent-routing diagnostic — all over the routing-owned href and click law. |
| `STRUCT` | Structure | The versioned semantic tree, the conversion table, and the explicit host policy. |
| `DIAG` | Diagnostics | The versioned occurrence schema (scope, basis, completeness, loss), stable ids, source and recovery, bounded retention, and provable-only static findings. |

### Allocation rule

1. Choose the area whose canonical paragraph owns the law.
2. Take the next number after the highest already allocated **in that area** —
   including retired ones. Numbers within an area are dense and ascending;
   gaps are only ever created by a mistake, never by a deletion.
3. Append the row to that area's section in the index. One section per area
   keeps concurrent slices out of each other's diffs.
4. Land the row in the same change as the spec paragraph it cites. A row whose
   anchor does not yet exist fails the validator, which is the point: an id is
   an address, and an address to nowhere is not one.

## The index

Every row has six columns, and the validator enforces all six.

| Column | Contract |
|---|---|
| Id | `FH-<AREA>-<NNN>`, unique, ascending within its section, area matching its section. |
| Law | One line, stating what is proven — enough to recognise the row, never enough to replace the spec. |
| Canonical paragraph | A markdown link into `spec/`, **with an anchor**. This is the normative owner; the row is its address, not a second copy of it. |
| Applicability | Which modes and hosts the law binds (grammar below). |
| Fixture | The fixture path when the row is `active`; `—` otherwise. |
| Status | `planned`, `active`, or `retired`. |

### Applicability grammar

Two axes, space-separated in one cell — for example `common jvm browser ssr`,
`compiled browser`, or `common host:vega`.

- **Mode axis — exactly one token.** `common` (the law binds identically in both
  modes), `interpreted`, or `compiled`.
- **Host axis — one or more tokens.** `jvm`, `browser`, `ssr`, or
  `host:<name>` for a law that binds only at a named qualified host boundary
  (`host:vega`, `host:ag-grid`, …).

A law is proven for every mode/host combination its cell names, and for no
others. Narrowing the cell narrows the claim, so a narrow cell needs a reason.

### Status vocabulary

| Status | Meaning | Fixture cell |
|---|---|---|
| `planned` | The law is fixed and addressed; its fixture is not written yet. | `—` |
| `active` | The fixture exists and the law is in force. | the fixture path |
| `retired` | The id is burned so citations stay honest. The law no longer binds. | `—` |

Rows do not carry a pass/fail column. Whether an `active` law is currently green
is a fact about a run, not about the index; the harness reports it.

### Fixture convention

A fixture path is written repo-relative in backticks, and the filename mirrors
the id in lower case:

```
spec/conformance/freehand/fixtures/fh-props-007.edn
```

The validator only requires that the named file exists. What the file contains,
which hosts load it, and how results are reported are the testing spec's
concern (`008-Testing.md`) and the harness's — this directory owns addressing,
not execution.

## The validator

```bash
python scripts/check_freehand_conformance_index.py            # validate the index
python scripts/check_freehand_conformance_index.py --self-test  # prove the gate has teeth
python scripts/check_freehand_conformance_index.py --report     # the applicable-arm table
```

Two guards, and the difference between them is the difference between an index
that is well-formed and one that is true.

**The structural check** reads the index as a document. It fails on a duplicate
or ill-formed id, a row filed under the wrong area, an out-of-order id, a *gap*
in an area's ordinals, a citation whose spec file or anchor does not exist, a
citation that points outside `spec/`, a missing or misplaced fixture, an unknown
applicability token or a broken axis, an unknown status, and a section roster
that no longer matches the area roster above.

**The execution census** reads the index against the suites that run it. A row
naming a real fixture nobody reads passes the structural check and proves
nothing, so the census derives — from the `(conf/fixture :FH-…)` sites in
`implementation/freehand/test/` and the lane each test file runs in — which rows
are actually proven and where. It fails on a row no test reads, a row whose id is
written where no assertion can reach it, a row asserted only from lanes that do
not serve the mode/host cells its applicability cell claims, a `compiled` row
whose proving assertion never reaches the compiled tier, a test that
reads a fixture for a row the index no longer carries, a fixture file left on
disk by a row that was deleted, a roster area holding no `active` row at all —
the roster carries an area because a law lives there, and an index of bare
section headers satisfies every structural rule above trivially — and an
`FH-<AREA>-<NNN>` cited anywhere under `spec/` that the index carries no row
for, at any status.

### What counts as a proof

A proof site is not a string. The census reads **forms**, and then it reads the
**assertions**. A site counts only when an *asserting statement* of a `deftest`
reaches it — written in one, or bound to a name such a statement uses, directly
or through a helper it calls. A statement that asserts nothing contributes
nothing, and a `deftest` whose statements never assert proves nothing at all.

Ten shapes name a law and prove nothing:

```clojure
;; (def props-003 (conf/fixture :FH-PROPS-003))   a comment
#_(def props-003 (conf/fixture :FH-PROPS-003))    a reader-discarded form
(ns x "see (conf/fixture :FH-PROPS-003)")         an id written in prose
(def props-003 (conf/fixture :FH-PROPS-003))      a def no deftest ever reads
(deftest t (is true) (comment props-003))         a comment FORM, inside a test
(deftest t (is true) (uses-it))                   a helper whose only read sits
                                                  in a branch that never runs
(defn check [x] (identity x))                     a helper that asserts nothing,
(deftest t (check props-003))                     sharing a NAME with one that
                                                  does, in another namespace
(defn check [x] #?(:clj (is (seq x)) :cljs x))    a helper that asserts on ONE
(deftest t (check props-003))                     platform, credited on both
(def props-003 (conf/fixture :FH-PROPS-003))      a KEYWORD spelled like the
(deftest t (is (= :props-003 1)))                 binding, read as the Var
(def props-003 (conf/fixture :FH-PROPS-003))      the binding QUOTED — data the
(deftest t (is (= `props-003 1)))                 assertion compares against
```

The first four are invisible to a scan that reads characters. The next two are
invisible to a scan that reads forms but seeds from the whole `deftest`: the id
is mentioned where a test can see it and where nothing evaluates it, so the suite
stays green with the fixture broken — which is the one thing the row's green is
supposed to rule out. The next two are invisible to a scan that reads assertions
but not the *names* carrying them. The ninth is invisible to one that reads names
but not their *boundaries*: a keyword is a run of symbol characters behind a `:`,
so a scan starting wherever such a character appears reads `props-003` out of
`:props-003` and resolves the datum to the Var. The tenth is invisible to a
boundary as well, because `` `props-003 `` genuinely *is* the token — what it is
not is a reference to the Var. Each is a way to delete a law's proof and leave
its row standing, so each is a `DEAD PROOF SITE` — or, where the lost assertion
is one platform's, an `UNEXECUTED CELL` on the lane that platform serves.

What this establishes is bounded, and the bound is worth stating: the assertion
is *co-located* with the fixture read, in one statement. It is not a proof that
the assertion would fail if the fixture changed — that is mutation testing, and
it needs the suite to run.

An assertion may be the helper's rather than the test's. `(sup/expect-tree fx)`
asserts, because `expect-tree` does, and a rule that recognised only an `is`
written in the `deftest` itself would red most of the suites here. But it asserts
because *that* `expect-tree` does — the one
`re-frame.freehand.support` defines, reached through the alias this file gave it.
A name is resolved in the namespace that wrote it, through its own `:as` and
`:refer`; matching bare words in one global pool lets any file's asserting helper
vouch for every same-named helper anywhere, which is a suite being credited with
a stranger's `is`.

And a name is read from a **token boundary**, because a keyword is a run of symbol
characters behind a `:`. A scan that begins wherever such a character appears is
reading the tails of tokens, not tokens: `(is (= :panel (first fx)))` yields
`panel`, and with a `(v/defview panel {:compiled true} …)` in scope that datum
resolves to the Var and witnesses a compiled tier the assertion never enters. A
keyword is data; a Var reference is a symbol; the character *before* the token is
what separates them.

And a name is read only where it is **evaluated**, which a boundary cannot tell
you. `(quote fx)` and `` `fx `` really do contain the token `fx`; what neither
contains is a reference to the Var, because quoted data is data. So the census
performs three reductions before it reads anything, and each answers one question:

| Reduction | The question it answers | What it removes |
|---|---|---|
| `_strip` | what is not code | comments, strings, character literals, `#_` discards, `(comment …)` at any depth |
| `_read_as` | what *this platform* reads | every `#?` / `#?@` conditional but one branch |
| `_evaluated` | what **evaluates** | every quoted datum — `'x`, `` `x ``, `(quote x)` |

One reduction per question, applied once per file, so names, fixture calls,
assertion heads and the mode marker are all read off the same text and none can
drift from another: a quoted `(conf/fixture :FH-…)` is not a fixture read, and a
helper returning `'(is (seq fx))` returns a list rather than asserting.

And knowing *which* datum is quoted still leaves **how far it reaches**. All three
reductions ask one function — `#_` discards a datum, a reader conditional pairs its
arms as datums, a quote blanks one — so a miscount there is a miscount everywhere.
`^` is the shape that miscounts if it is filed with the prefixes that introduce one
datum, because it consumes its metadata *and* the target that metadata annotates:
read the short way, `'^{:audit true} [fx]` blanked the quote and the map and left
`[fx]` standing in code, so a `deftest` whose only mention of the fixture was
inside quoted data proved a row — and the assertion is true in Clojure with no
`fx` in the image at all. The same datum written `(quote ^{:audit true} [fx])` was
red throughout, because a list's extent needs no prefix arithmetic, and that
difference is what made this arithmetic rather than policy.

It is a narrowing, not a refusal. Inside a syntax quote `~x` and `~@x` *are*
evaluated, so those islands are read as code and a genuine reference stays one
however deep in a template it is written — including `~^{:audit true} fx`, whose
annotated datum is the pair. Two residues remain, and both are *missed* edges
rather than invented ones, so both cost a noisy defect on an honest row instead of
a silent green: a nested syntax quote raises the level, so the double unquote that
would climb back out of it (`` `(a `(b ~~c)) ``) is not modelled; and `#'x` is a
var quote — a real reference — that resolves to nothing, as it did before the
reduction existed.

The census also reads the **reader**. A `.cljc` suite is discovered by the JVM
runner *and* the node runner, but a `#?(:clj (deftest …))` inside it asserts in
only one of them — so the whole tree is read once per platform, and the lane a
proof reaches is the lane the *file* runs in crossed with the platforms whose
reading of that file contains the reaching assertion. A helper whose `is` lives
in a `#?(:clj …)` arm carries the same narrowing to its callers. Taking the lane
from the filename alone credits a row with a lane it never enters, which is how a
`common jvm browser` row can be green on a proof the browser column's structural
cell — the node runtime — never executes.

### The mode witness

The lane axis cannot see the mode axis. `interpreted jvm` and `compiled jvm` are
served by the same JVM lane, so a row relabelled from one to the other keeps
every cell it claims served — the mode token parsed, and evidenced by nothing.

`compiled` is the mode a declaration *opts into*, so it is the mode that can be
witnessed. A `compiled` row must be proven by an assertion that **reaches** the
compiled tier: a `{:compiled true}` declaration, or a
`re-frame.freehand.compiler.*` reference, in the asserting statement itself or in
a binding it names — across a `:refer`/`:as` hop into the support namespace where
a census of declarations lives. The second surface is not optional —
`FH-DIAG-001` is a compiled-mode law proven through the compile **checker**, over
declarations that deliberately carry no `{:compiled true}`, and a rule demanding
the declaration alone would red an honest row and push the next author into
writing a compiled twin for the gate's benefit.

*Reaches*, not *is present near*. A witness read off the file — a `:require` at
the top, a declaration anywhere below it — vouches for assertions that touch
neither, so keeping the require and asserting on the raw fixture instead kept the
row's `compiled` claim standing while nothing compiled ran. Presence is not
proof, in the same way and for the same reason that a fixture named in a file is
not a fixture a test reads. Reachability is only as sound as name resolution, so
the witness is reached by a symbol read from a token boundary and from an
*evaluated* position: `:panel` is a datum, not the declaration it is spelled like;
neither is `:check/findings` the alias; and neither are `` `panel `` or
`(quote panel)`, which name the declaration without evaluating it — nor
`'^{:audit true} [panel]`, whose annotation used to carry the quote away from the
vector it quoted. Both halves of
the witness are read off the same reduced text as the names, so a quoted
`{:compiled true}` and a quoted `re-frame.freehand.compiler.…` witness nothing
either. What stays textual, and is claimed as nothing more, is the marker within
what does evaluate — a statement mentioning `{:compiled true}` as a live map
literal still witnesses, whatever it does with it. Reachability is enforced;
telling a marker used as data from one used as code, inside evaluated code, is
not.

And the witness is only evidence where it *runs*. A compile-tier reference inside
a `#?(:cljs …)` branch vouches for the node lane and says nothing about the JVM,
so the witness is required in a lane serving the cell that claims it: a
`compiled jvm` row needs it on the JVM. A `compiled` cell with no witness in a
lane that serves it is an `UNWITNESSED MODE`, and it names the host.

`interpreted` and `common` carry no such witness, and the census claims none.
Interpreted is the *default* lowering — a declaration is interpreted by carrying
nothing — so there is no marker to demand, and a rule every proof satisfies would
dress a green up as evidence rather than supply any.

Every reading here is self-tested with the **defect kind** pinned, not merely the
count: a case that reds for the wrong reason is the same mistake the census
exists to catch, and a self-test that only counts makes it about itself. The
green cases are falsified too — remove the thing that makes one green and it must
red — because a green fixture that would pass either way pins nothing.

That last one is the only guard against a deletion at the **top** of an area.
Delete `FH-EVENT-005` when `FH-EVENT-004` is its neighbour and the ordinals stay
dense, so nothing in the index can tell the id was ever allocated — the
citations that outlive it are the sole surviving evidence. Retire the row
instead and the address answers, which is the whole reason the status exists.
The two documents that *define* the scheme, this file and the index, are
exempt: they speak in illustrative ids like `FH-PROPS-007`, which are examples
rather than citations.

The lane a file runs in is not asserted here; it is mirrored from where it is
configured — cognitect-test-runner's discovery for `clojure -M:test`, and the two
shadow-cljs `ns-regexp`s for `npm run test:freehand` and `npm run test:browser`.
Which lane serves which host comes from [008-Testing.md §The host/mode
matrix](../../008-Testing.md#the-hostmode-matrix): the `browser` column's
structural cell is the host-neutral tree proven in the node runtime, the `ssr`
column is the structural tree the JVM render already emits as the server shell,
and a qualified host is proven by connecting the real wrapper in a browser.

Each defect names the offending row and line. Both guards run on every pull
request; `--self-test` drives one deliberately broken mini-repo per defect shape
and fails if any of them passes.

## What this is not

**Not the donor stage profiles.** The stage profiles at
[`S3-view-conformance-profile.md`](../S3-view-conformance-profile.md),
[`S4-view-conformance-profile.md`](../S4-view-conformance-profile.md), and
[`S5-view-conformance-profile.md`](../S5-view-conformance-profile.md) catalogue
the frozen surface of the donor compiled-view substrate. They remain useful
**migration evidence** — they record what the donor froze and which gate proved
it, which is exactly what an absorption audit needs. They are **not a second
Freehand conformance authority**: no `FH-*` row is discharged by citing one, no
Freehand claim rests on one, and nothing in this directory defers to one. When a
donor obligation crosses into Freehand it arrives as an `FH-*` row citing a
Freehand-owned spec paragraph, or it does not cross at all.

**Not the cross-host conformance corpus.** The corpus indexed by
[`../README.md`](../README.md) proves that an implementation of the whole
framework, in any host language, is a faithful port. Its fixtures carry
`:fixture/id` keywords and answer a different question. Freehand ids address one
substrate's two-mode, multi-host parity contract, and the two schemes never
share an id space.

**Not a fixture runner.** Nothing here executes. Adding a row makes a law
addressable and citable; making it *run* is the harness's job. The census above
does not soften that: it reconciles the index with the suites that execute
fixtures, and executes none itself. "Every row is proven somewhere applicable" is
a fact the census establishes; "every row is green" is a fact about a run, and
the lanes report it.
