# The Reagent codemod, re-derived against the landed escape (rf2-2rtt6.106)

**This page is a successor, not a replacement.** The adjudicated record is
[the codemod spec](reagent-codemod-spec.md); the operator's 2026-08-06 ruling on that page
is the charter — *fixer first, hoist chartered as the follow-on, dispatched after
`rf2-2rtt6.103`'s implementation merges*. Nothing here reopens either.

What changed is the one thing both documents said they could not settle. `rf2-2rtt6.103`
built and merged (PR #7635). The ruling made its dispatch conditional on a premise check —
*"escape props take `host-entry`'s landed conduct with no branch of their own; if `.103`'s
build forked, re-derive the corpus BEFORE building"* — and that check can now be run against
code instead of against a spec. §2 runs it.

So this page does three things the earlier one could not. It **discharges the premise
check**, and records how. It states the rewrite set as **proof obligations against two
landed conversion tables**, one of which did not exist when the earlier page was written. And
it states the **refusal set, the report, and the failure modes** as the first-class half of
the design, then attacks all three (§9).

**No runtime number is published here.** The studio's measurement obligations bind pages that
publish figures; this one publishes none. The single count it leans on — 28 live `[:>]` sites
in this repository, 13 of them in an application population — is the sibling page's
measurement, cited as theirs, with its stated bias intact.

**Citation discipline.** Symbols, never line numbers, and no commit SHAs. The sibling page
measured the codec drifting by up to 462 lines in 48 hours; every address in the 2026-08-04
record had rotted within two days of being written.

---

## 1 · The tool, in one paragraph

A source-text tool that reads a consumer's Reagent `.cljs` namespaces and repairs the props
dialect at every foreign crossing, so that a codebase whose `[:> …]` sites are about to be
interpreted by Hicasso keeps behaving the way Reagent made it behave. It mints no
declaration, hoists nothing, and edits no `ns` form. It writes whole files through
rewrite-clj, skips any file it cannot parse, and emits a report. Residence, CLI and corpus
discipline are unchanged from the sibling page's §9: `migration/reagent-to-hicasso/codemod/`,
cloned from the `migration/from-re-frame-v1/codemod/` skeleton, corpus-as-spec with asserted
idempotence.

**Two laws govern everything below.**

**Law 1 — the decidability law.** A rewrite fires only where the *donor's* answer and the
*destination's* answer are both computable from the source text, and they differ. Not
"probably differ", not "differ for the shapes we have seen": computable, from this text,
by reproducing two published functions. Everything else is a report line.

**Law 2 — the no-blanking law.** No rewrite introduces a function whose return value differs
from the value the donor's conversion produced. This is stated as a law rather than as a
consequence because it is the exact rule the rejected spelling-classifier broke, and §6 is
about why.

A corollary of Law 1 does most of the work in practice, and it is worth stating on its own:
**a prop's spelling may only ever make the tool do less.** Where a name-shaped test says
"yes", the answer is always *skip this rewrite* or *refuse this site* — never *apply this
rewrite*.

---

## 2 · The premise check, discharged

The escape's props walk is not a second walk. `raw-element` builds its emitted object with

```clojure
(reduce-kv (partial host-entry raw-crossing {}) #js {} props)
```

— `host-entry` itself, the door's own reducing function, against a module-level
`raw-crossing` stand-in carrying `displayName` `"[:>]"` and an empty `callbacks` roster.
There is no branch, no flag and no second table. **The premise holds, and it holds by
construction rather than by agreement.**

Three consequences follow directly, and each is what a rewrite argument needs:

1. **Every conversion the door performs, the escape performs identically.** The class slot's
   `class-names` coercion and composition, the `html-attr-slots` named-value exception, the
   `check-ref!` vector reservation, the `:key` lift onto the crossing's own element, the
   `clj->js` shallow arm for collections, function identity — all one code path.
2. **Every slot at the escape is unclaimed**, because the roster is empty. So
   `(contains? declared slot)` is false everywhere, and the two refusals that live in
   `host-entry`'s `:else` arm — `refuse-undeclared-host-event!` and
   `refuse-unclaimed-host-callback!` — are reachable at *every* `[:>]` prop.
3. **The escape's `displayName` is the constant `"[:>]"`**, so a refusal raised at a `[:>]`
   crossing names the form the author wrote and never the component. §7 turns that into the
   report's job.

This is not read off the source alone. `codec_cljs_test.cljs` carries a deftest named
`the-escape-takes-the-doors-unclaimed-slot-conduct-exactly`, whose own docstring says the
quiet part: *"That is what makes `[:> X …]` → `(defhost x X {})` behaviour-preserving, which
is the whole theorem of the migration codemod."* The premise the ruling made the dispatch
conditional on is discharged **by an executed witness that names this bead's tool**.

### 2.1 · Where the escape is *not* the door

At the head, and only at the head. `raw-component` refuses `nil`, strings, keywords, symbols,
`defview` and `defhost` products, React elements, and anything else React would not accept as
a type; `mint-host!` refuses only `nil`. The escape is the narrower of the two.

For the fixer this matters in exactly one place — the string-tag head (W6, §4.6) — and it
matters as a *loudness* fact rather than a correctness one: `[:> "input" …]` no longer
half-works, it throws at the crossing with a message that names the confusion. For the
deferred hoist it matters more, because a hoist moves a value from the narrow acceptance set
to the wide one; the sibling page already struck *"refusals included"* from the hoist's
equivalence argument, and the landed code confirms the strike was right.

---

## 3 · The two conversion tables, side by side

Everything in §4 and §5 is derived from these two functions and their callers. Reagent 2.0.1
routes `[:> C props & kids]` through `vec-to-elem`'s `:>` case into `native-element` with a
`HiccupTag` whose id, className and custom flag are all nil, and thence into `convert-props`
→ `convert-prop-value` → `kv-conv`. Hicasso routes it through `raw-element` → `host-entry` →
`host-prop-value`.

| Input at a `[:>]` prop | Reagent 2.0.1 emits | Hicasso emits | Class |
|---|---|---|---|
| a plain function | the function, by identity (`js-val?` is true of a function) | the function, by identity | agree |
| a string, number, boolean, `nil` | verbatim | verbatim | agree |
| a `#js {…}` literal | verbatim (falls to `clj->js`, which returns a foreign value unchanged) | verbatim | agree |
| a keyword or symbol at `className`/`id`/`role`/`data-*`/`aria-*` | its `name` | its `name` | agree |
| a collection at the class slot | `class-names`, but only when written as literal `:class` | `class-names` at the slot, every spelling, composing | Hicasso repairs |
| a keyword or symbol anywhere else | its `name` | the keyword or symbol itself | **silent divergence** |
| a nested map literal | keys recursively camelCased through `cached-prop-name` | `clj->js`: keys keep the spelling the author wrote | **silent divergence** |
| a nested map reached through a vector or set | `clj->js`, no camelCasing | `clj->js`, no camelCasing | agree |
| a non-fn `IFn` — `(r/partial f a)` and its kin | wrapped fresh: `(fn [& args] (apply x args))` | the object itself, opaque | **silent divergence** |
| a vector or key-map at an event-spelled prop | `clj->js` — an inert array, because `coll?` precedes `ifn?` | **refused loudly** by `refuse-undeclared-host-event!` | silent → loud |
| `dangerouslySetInnerHTML` | **deleted**, unless wrapped in `UnsafeHTML` | passed through | **silent resurrection** |
| a `^{:key k}` metadata key on the vector | read by `native-element` after conversion; beats a props `:key` | **nothing reads it** — the codec contains no `(meta …)` read at all | **silent divergence** |
| a `:key` in the props map | converted like any prop, so `:foo` becomes `"foo"` | lifted raw onto the crossing's element, so `:foo` becomes `":foo"` | divergence, one-time |
| a literal `:&` key | a prop literally named `"&"` | the one attribute merge, under the owned-literal law | **silent divergence** |

*(4 columns; 14 body rows; hand-counted.)*

Six rows say **silent divergence**, and those six are the whole reason a tool exists. Two of
them are already in the guide's troubleshooting table — which is to say they are taught to
the reader who has already shipped the bug.

---

## 4 · What the codemod rewrites, and why each is behaviour-preserving

Four prop rewrites and two head repairs. The prop rewrites apply at **every** crossing —
that is the governing law the earlier attack established, and the landed code is the reason
it holds: the rewrites are properties of `host-entry`, and `host-entry` runs at `[:>]` sites
and at `defhost` call sites alike.

| Id | Matches | Writes | Preserves |
|---|---|---|---|
| W1 | `^{:key k}` metadata on a `[:> …]` vector | `:key k` in the props map, minting the map if absent | Reagent's live key; without it the key is inert |
| W2 | a literal map value, reached from the props map through map values only | the same map with each literal key respelled by Reagent's `cached-prop-name` rule | Reagent's deep camelCasing |
| W3 | a literal keyword or symbol prop value | its `name` as a string | Reagent's `(named? x) (name x)` arm |
| W4 | a literal `(r/partial f a …)` call at a prop value | `(fn [& args] (apply f a … args))` | Reagent's `ifn?` wrapper, return and identity alike |
| W5 | `[(r/adapt-react-class X) props & kids]` in head position | `[:> X props & kids]` | Reagent's `NativeWrapper` path is the same `native-element` |
| W6 | `[:> "tag" props & kids]` with a plain tag string | `[:tag props & kids]` | Reagent's `input-component?` wrapper becomes Hicasso's controlled door |

*(4 columns; 6 body rows; hand-counted.)*

### 4.1 · W1 — the metadata key

**Why it is the highest-value rewrite.** `grep` for `(meta ` across `front/codec.cljs`
returns nothing. Not "no path at the escape" — no path at all. And `check-member-key!`, the
minted key warning `rf2-2rtt6.104` landed, reaches its message only under `(boundary-head? h)`;
a `[:>]` crossing is not one. So a migrated seq of keyed crossings loses every key, at every
build, with no diagnostic from Hicasso and only React's own parent-tag-deduped warning from
React. For a Reagent codebase this is the entire keying idiom going dead at once.

**Proof sketch.** Reagent's `native-element` sets `.-key` on the converted props object from
`(-> (meta argv) util/get-react-key)`, *after* `convert-props` has run, so metadata beats a
props `:key`. Hicasso's `raw-element` reads `(:key props)`. Moving the expression from
metadata position into the props map therefore lands it where the destination reads, and the
value is the same expression.

**The exception, and it is real.** Where both a metadata key and a props `:key` are present,
Reagent's metadata wins; W1 would have to overwrite the props key to preserve that, and an
overwrite is a decision. It refuses and reports (`:key-conflict`).

**The residual, recorded rather than argued away.** Moving `(f x)` out of metadata and into
the props map changes its evaluation order relative to the other prop expressions. For a pure
key expression — every key expression anyone writes — this is invisible. For a
side-effecting one it is a real behaviour change, and the tool cannot see the difference.
Accepted, bounded, and stated here because §9 would find it anyway.

### 4.2 · W2 — nested map keys

**Proof sketch.** Reagent's `convert-prop-value` has a `map?` arm that recurses via `kv-conv`,
respelling every key with `cached-prop-name` — which is the three seeded renames
(`class`→`className`, `for`→`htmlFor`, `charset`→`charSet`) over `dash-to-prop-name`'s
kebab→camel rule, with `aria`/`data` exempt and strings verbatim. Hicasso's `host-prop-value`
sends the same map to `clj->js`, whose keys are the author's own spelling. Applying Reagent's
key function to the literal keys, in the source, makes the two answers equal.

**Where the recursion stops, and why that is not a judgement call.** Reagent recurses through
the `map?` arm only. A map that sits inside a vector, list or set is reached by the `coll?`
arm, which is `clj->js` — no camelCasing. Hicasso's `clj->js` does the same. So W2 walks map
values through maps and **stops at the first non-map collection**, because that is exactly
where the donor stopped. It never descends into a `#js {…}` node, which both runtimes pass
through untouched.

**The one cell where preservation is refused.** A `--custom-property` key. Reagent's
`dash-to-prop-name` mangles `--brand-color` into `BrandColor`, which React writes to a style
property nothing reads; Hicasso's `prop-name` preserves it, and React's style handling routes
a `--`-prefixed key through `setProperty`, so it works. Preserving Reagent here would mean
writing a key that never worked. W2 leaves it and reports (`:css-var-repair`): the site
starts working, and the human is told that it did.

### 4.3 · W3 — named prop values

**Proof sketch.** Reagent's `convert-prop-value` answers `(name x)` for any keyword or symbol,
at every prop of every element, and `[:>]` reaches it through `native-element` like any other.
Hicasso's `host-prop-value` keeps the named value whole except at `html-attr-slots` — the
`rf2-vrvv9` narrowing, taken deliberately because `(name v)` collapsed `:theme/dark` and
`:other/dark` onto one string. Writing the `name` as a string literal in the source makes the
crossing hand the library what Reagent handed it, and a string is a fixpoint on both walks.

**Where it does not fire**, each because the two runtimes already agree or because firing
would restore a defect:

- **`className`, `id`, `role`, `data-*`, `aria-*`.** Both `name` there. Fixpoint; skipping
  keeps the diff small.
- **The class slot in any spelling.** `class-names` names it on the Hicasso side.
- **The `ref` slot.** Reagent's `(name :my-ref)` produced a *string ref*, which React 19
  removed and now throws on. Preservation would restore a crash. Reported, never written.
- **The `key` slot.** Recommended off — see §9.3, which argues it and hands the operator the
  choice.

**The report obligation.** A namespaced keyword — `:theme/dark` → `"dark"` — preserves Reagent
exactly and simultaneously bakes in the collision `rf2-vrvv9` was filed to remove. Both halves
are true and the law does not adjudicate between them, so the rewrite fires and the report
says what was lost (`:namespaced-named-value`). A tool that silently writes `"dark"` for
`:theme/dark` is a tool that hides a decision.

### 4.4 · W4 — the non-fn `IFn` literal

**Proof sketch.** Reagent's `convert-prop-value` ends with an `ifn?` arm that returns
`(fn [& args] (apply x args))`. Hicasso has no such arm on either walk, so the object crosses
opaque and a working handler stops firing with nothing thrown. The replacement is Reagent's
own wrapper, spelled in the source at the call site. It is **return-transparent** — whatever
`f` returned before, it returns now — which is Law 2, and it is why W4 cannot blank a render
prop even when the prop it sits at is a render prop (§9.4).

**Identity is preserved too, which is the non-obvious half.** Reagent minted a fresh wrapper
on every conversion, so the prop's identity already changed on every render and no downstream
`React.memo` bail-out was ever getting a stable value. A `(fn …)` literal in the props map is
fresh on every render as well. The rewrite does not make memoisation worse, because there was
none.

**Scope, stated honestly.** The only `IFn`-that-is-not-a-function a source reader can
recognise is a literal `(r/partial …)` call, alias-resolved from the `ns` form. Multimethods,
`IFn` records and anything arriving through a symbol are invisible; they fall to
`:computed-value` (§5).

### 4.5 · W5 — the inline `adapt-react-class` head

**Proof sketch.** Reagent's `vec-to-elem` sends a `NativeWrapper` head to
`(native-element tag v 1)`; its `:>` case sends `[:> C …]` to `(native-element … v 2)`. Same
function, same `convert-props`, same nil id/className/custom. The two spellings are one path
with the props slot at a different index, so respelling one as the other and shifting the
index preserves the donor's behaviour exactly — and it lands the site on the form Hicasso
accepts. Left alone, the head is an object Hicasso's `vec->element` answers with
`:rf.error/hicasso-bad-head`.

**Def sites are report-only** (`:adapt-def-site`). `(def Foo (r/adapt-react-class X))` with
`[Foo …]` call sites elsewhere cannot be rewritten by a fixer: rewriting the def changes
every call site's conversion regime, including call sites in other namespaces the tool may
not have been pointed at. The report names the def, and names the call sites it found.

### 4.6 · W6 — the string tag head

Under Reagent, `[:> "input" …]` took the controlled-input wrapper, because `input-component?`
matches `"input"` and `"textarea"` on exactly this path. Under Hicasso the head is refused at
the crossing with a message that says so. Rewriting to `[:input …]` lands the site on
Hicasso's own controlled door, which is the taught form.

**Two facts make W6 safe to combine with the prop rewrites.** At a native destination
Hicasso's `convert-prop-value` carries both the `(name v)` arm and the deep-camelCasing `map?`
arm — so **W2 and W3 are fixpoints there**, and applying them before or after W6, or not at
all, gives the same emitted props. W1 and W4 are still required, because the native walk reads
`(:key props)` and has no `ifn?` arm either.

**Two guards, and the first is the design's only genuinely fatal class.**

- **A literal vector or key-map at an event-spelled prop.** Inert under Reagent
  (`clj->js`, because `coll?` precedes `ifn?`); at a native Hicasso tag, `convert-entry`
  *lowers* it into a live dispatch. W6 would turn a handler that never fired into one that
  fires. Refused, reported (`:event-carrier-goes-live`), never rewritten.
- **A string that is not a plain tag name.** `[:> "div#id" …]` was already broken under
  Reagent — the `:>` path does not parse the shorthand, so `createElement("div#id")` asked
  React for a nonexistent element. Rewriting to `[:div#id …]` would parse it and the site
  would start working differently. Reported (`:string-tag-unparseable`).

### 4.7 · Idempotence

Every output is outside its own rewrite's input language, so a second run is a no-op by
construction, and the corpus asserts it byte-for-byte:

| Rewrite | Why running it twice changes nothing |
|---|---|
| W1 | after the first run the vector carries no metadata key |
| W2 | `cached-prop-name` is idempotent — `fontSize` contains no dash and hits no rename |
| W3 | a string is not a keyword or symbol |
| W4 | a `(fn …)` literal is not an `r/partial` call |
| W5 | the head is `:>`, not an `adapt-react-class` call |
| W6 | the head is a tag keyword, so the site is no longer a `[:>]` site |

*(2 columns; 6 body rows; hand-counted.)*

---

## 5 · What it refuses, and how it says so

Refusals are the design's load-bearing half, not its residue. A refused site still **works** —
`[:>]` is legal, so leaving it alone is a valid output — and the report is what turns a
refusal from silence into an instruction.

They fall into three kinds, and the kind is what tells a reader how urgent the line is.

### 5.1 · Undecidable from the source text

| Class | What it matches | What the report says |
|---|---|---|
| `:computed-props` | the slot after the head is not a literal map, and not obviously a child | which slot, and that the tool could not tell props from a child |
| `:computed-value` | a prop value that is not a literal, at a site otherwise rewritable | the prop names, and the three things a computed value may silently be: a keyword, a nested map, a non-fn `IFn` |
| `:computed-nested-key` | a non-literal key inside a map W2 rewrote | which map, and that its computed keys keep the author's spelling |
| `:adapt-def-site` | `(def x (r/adapt-react-class …))` | the def, its same-file call sites, and that cross-namespace ones are invisible |
| `:cljc-site` | a `[:>]` inside a `.cljc` file | that the crossing is `.cljs`-only at that node |
| `:parse-error` | rewrite-clj could not read the file | the file, untouched, and the reader's own message |

*(3 columns; 6 body rows; hand-counted.)*

### 5.2 · Decidable, but the rewrite would itself change behaviour

| Class | What it matches | Why it is not mechanical |
|---|---|---|
| `:event-carrier-goes-live` | a literal vector or key-map at an event-spelled prop, at a W6 candidate | inert under Reagent, live at a native Hicasso tag: the rewrite would make a dead handler fire |
| `:key-conflict` | both a metadata key and a props `:key`, differing | Reagent's metadata wins; W1 would have to overwrite a value the author wrote |
| `:string-tag-unparseable` | a `#`/`.`/whitespace-bearing string head | the shorthand was inert under Reagent and would parse at a native tag |
| `:css-var-repair` | a `--custom-property` key inside a W2 map | preservation would write a key that never worked |
| `:named-ref` | a literal keyword or symbol at the `ref` slot | Reagent produced a string ref, which React 19 throws on |
| `:amp-key` | a literal `:&` key in a props map | a prop named `"&"` under Reagent, the one attribute merge under Hicasso; intent is unrecoverable |

*(3 columns; 6 body rows; hand-counted.)*

### 5.3 · Decidable, and the destination refuses at runtime

These are the migration blockers. The site does not need a rewrite — it needs a person.

| Class | What it matches | What the human must do |
|---|---|---|
| `:intent-needs-a-declaration` | a literal vector or key-map at an event-spelled prop, at a site staying `[:>]` | declare the crossing with `defhost` and name the prop in `:callbacks`, or hand a plain function — and know the handler never fired under Reagent either |
| `:dangerous-html` | `dangerouslySetInnerHTML` at any `[:>]` site | decide deliberately: Reagent **deleted** this prop unless `UnsafeHTML`-wrapped, and Hicasso passes it through, so the migration resurrects it |
| `:r>-site` | `[:r> C props …]` | port by hand; Hicasso reads `:r>` as a tag keyword and renders an unknown element, which is not a loud failure |
| `:f>-site` | `[:f> C …]` | port by hand; same reading, same quietness |
| `:as-element-island` | `r/as-element` inside a callback body at a `[:>]` site | port by hand; the closure runs outside the owner's render window, so lowering inside it is not a text substitution |
| `:reagent-api-residue` | `r/atom`, `r/cursor`, form-2/form-3 shapes inside a rewritten site | out of the tool's fence entirely; named so the migrator is not surprised |

*(3 columns; 6 body rows; hand-counted.)*

`:intent-needs-a-declaration` deserves its own sentence, because it is the one place the
escape refuses something the donor accepted. `host-entry`'s `refuse-undeclared-host-event!`
fires at every `[:>]` prop, since the roster is empty by construction — so a Reagent
codebase that spelled a handler as an intent vector goes from *silently dead* to *loudly
refusing at render*. That is an improvement and it is also a page that now throws, possibly
in a branch the migration's smoke test never reaches. Finding it at migration time rather
than at render time is most of what the report is for.

### 5.4 · Divergences the tool deliberately does not repair

Named so nobody files them as gaps. Each is a place Hicasso is *better* than the donor, or
where the difference is invisible.

- **A class collection written as `:className`, `"class"` or `:x/class`.** Reagent read the
  literal `:class` key only, so any other spelling reached React as a `clj->js` array and was
  written to the DOM as `"a,b"`. Hicasso coerces and composes at the slot, in every spelling.
- **A nested class collection.** Reagent's `class-names` does not recurse; Hicasso's does.
- **Reserved emitted slots.** `__proto__`, `prototype` and `constructor` are dropped by
  Hicasso and were written by the donor.
- **A literal `nil` at the props slot.** Reagent counted it as an absent props map; Hicasso
  counts it as a child, and React renders nothing for it.
- **A keyword `:key` value**, if §9.3's recommendation is taken: `"foo"` becomes `":foo"`,
  which costs one remount at the migration and is stable thereafter.

---

## 6 · The static-analysis vocabulary, and why it is not the rejected classifier

`rf2-2rtt6.103` rejected migration's E2 as **fatal**, and the reason is precise: Fluent's
`onRender*` family and Ant's `onRow` are event-*spelled* render-props and return-readers, so a
design that reads an `on*` name as "this is an event position" and wraps the value in a
nil-returning event wrapper **blanks them silently**. The ruling kept the position table
alive with one sentence: *"the position table survives ONLY as the codemod's static-analysis
vocabulary."*

That sentence is a licence to use spelling as *language*, not as a *decision procedure*, and
the difference is enforceable rather than aspirational. Here is the whole vocabulary, with the
enforcement in the last column.

| Term | What it is asked | Can a "yes" add a rewrite? |
|---|---|---|
| **canonical slot** — `prop-name`'s rule, reproduced | which React slot does this key emit into? | No. It selects *which conversion-table row applies*, and both runtimes compute it identically from the key alone. |
| **`html-attr-slot?`** — `className`/`id`/`role`/`data-*`/`aria-*` | is this slot bound for an HTML attribute? | No. A yes means "both runtimes `name` here", so W3 **skips**. |
| **`dont-camel-case`** — `aria`/`data` | does the donor's key function leave this alone? | No. A yes means W2 **skips** that key. |
| **`event-prop?`** — `^on-[a-z]\|^on[A-Z]` | will `host-entry` refuse a carrier at this slot? | No. A yes is always a **refusal** (§5.2, §5.3) — never a wrap, never a rewrite. |
| **literalness** | can this form's value be read off the text? | Yes — and it is the *only* term that can. It is syntactic and has nothing to do with names. |

*(3 columns; 5 body rows; hand-counted.)*

**Read the last column downward and the distinction is structural.** Exactly one term in the
vocabulary can authorise a rewrite, and it is the one that asks about syntax rather than about
names. Every name-shaped term can only subtract: skip a rewrite, or refuse a site. That is the
corollary of Law 1, and it is why this design cannot reproduce E2's failure even by accident —
`onRenderCell` and `onRow` are `event-prop?`-true, and a `event-prop?`-true answer here does
nothing but make the tool *more* conservative at that prop.

**And `event-prop?` is not being asked what a prop means.** It is being asked what *our own
landed code* will do — `host-entry` runs that exact regex, through the `PropSlot` cache, at
every unclaimed slot, and throws when the value is a vector or a map. The tool is modelling a
refusal predicate it can read the source of, not guessing a library's contract. Where the
model says "this will throw", the answer is a report line naming `defhost` and `:callbacks`.

**The `:callbacks` suggestion is where the temptation lives, and it is refused.** The ruling
is that `:callbacks` synthesis is never mechanical — human, or an explicit flag. So the report
*suggests* a declaration and the tool never writes one; there is no `--promote` flag; and the
suggestion block carries the Fluent/Ant counter-example in its own header, so a migrator who
pastes a suggested `:event` contract onto `onRenderCell` has been told, in the artefact they
are reading, why that would blank their cell renderer.

---

## 7 · The report

The bead calls it first-class. Six properties make it so, and each is a thing the tool would
otherwise be free to do casually.

1. **It is an artefact, not console output.** One EDN file, deterministic ordering, written
   on every run including a clean one. The corpus asserts it byte-for-byte, so **a change to
   what the tool says is gated exactly as hard as a change to what it writes**. That is the
   property that makes the other five worth having.
2. **It is exhaustive over the sites it touched or refused, and honest about the rest.**
   Every rewritten site and every refused site has an entry; the summary block carries the
   count of sites left alone. "Not in the report" is never ambiguous.
3. **Every entry carries the source form as text, with file, line and column**, so a line is
   greppable and diffable against the file the migrator is looking at.
4. **Every refusal names the fix in the migrator's vocabulary**, not an error code. The
   `:note` is a sentence a person can act on.
5. **It carries the component name — and it is the only thing in the system that can.** A
   `[:>]` refusal at runtime prints `raw-crossing`'s `displayName`, which is the constant
   `"[:>]"`, together with an empty `:declared` set. The runtime cannot say *which* component
   refused. The report can, because it read the head.
6. **It carries the suggestions block, labelled as guesses.** Per component: the
   event-spelled slots and the fn-carrying slots, with a ready-to-paste `h/defhost` and a
   candidate `:callbacks` map — under a header that names why the human must check each row
   against the library's own documentation.

One entry, in the shape the sibling codemod's finding-maps already use:

```clojure
{:file   "src/app/views.cljs"
 :line   42
 :col    5
 :form   "[:> Btn {:variant :contained} \"Save\"]"
 :head   "Btn"
 :class  :named-value
 :action :rewrote
 :detail {:prop :variant :was :contained :now "contained"}
 :note   "Reagent named every keyword prop value; Hicasso keeps the keyword
          except at HTML-attribute slots."}
```

`:action` is one of `:rewrote`, `:refused` or `:skipped`. Exit code is 0 for any run that
completed, and 1 only when a file could not be read or written — a migration tool that fails a
build because a consumer's code needs a human decision is a tool that gets run once.

---

## 8 · Failure modes

What this could get wrong, and what the damage looks like in a consumer's codebase.

| Mode | What the consumer sees | Loud? |
|---|---|---|
| `host-entry` drifts after the corpus is written | the corpus stays green while the tool writes the wrong thing | **No** — §10's contract rows are the only tripwire |
| a `[:>]` produced by a macro | the tool never sees the site; the divergence survives the migration | **No** — and it survives at the sites a codebase repeats most |
| props built by a helper — `(merge base extra)` at the props slot | `:computed-props`, and the coverage thesis collapses on that codebase | Yes, in the report |
| W2 respells a map that was **data**, not options | the diff mangles a data map; the site behaves identically | Yes, in the diff — see §9.1 |
| W3 flattens a namespaced keyword | two distinct keywords now reach the library as one string | Yes, in the report |
| the tool's slot resolver diverges from `prop-name` | a prop is rewritten for the wrong slot | **No** — see §9.5 |
| a refused site is never revisited | the migration ships with a silently dead handler that was already dead | Yes, in the report; no, at runtime |
| a `:r>` or `:f>` site is left in place | Hicasso renders an unknown `<r>` element and nothing throws | **No** — reported, but quiet at runtime |

*(3 columns; 8 body rows; hand-counted.)*

The pattern worth naming: **every mode whose "loud?" column says No is a mode where the tool
did not see the site, or where two implementations of one rule drifted apart.** Neither is
fixed by adding rewrites. §10 says what each is fixed by.

---

## 9 · The adversarial pass

Briefed against this page: find the case where a mechanical rewrite changes behaviour, and
find anything that could silently blank a render prop. Six charges. Four survive as recorded
costs, one forces a recommendation the page cannot itself take, one is answered outright.

### 9.1 · "W2 preserves a bug, and publishes it into the source" — **survives as a cost**

The strongest charge, and it is not about correctness. Consider `{:defaults {:first-name "a"}}`
at a crossing. Reagent camelCased that nested key, so the library received `firstName` and the
author never saw it. W2 preserves that faithfully by writing `:firstName` into the source.

Two things follow. The first is fine: behaviour is preserved, which is the law. The second is
not: **the author now reads a diff that mangles a data map**, and the obvious reaction — tidy
it back to `:first-name` — silently changes what the library receives. The tool has moved a
latent conversion into visible source text and handed the author a rake.

It does not reverse W2, because the alternative is worse in both directions. Narrowing W2 to
the `style` slot would leave every genuine options map diverging silently, which is the class
the tool exists for; dropping W2 entirely does the same. The repair is that **W2's report line
must be the loudest one the tool emits**, naming the map and every key it respelled, with the
sentence *"Reagent camelCased these; Hicasso does not. If a key here is data rather than a
library option, this rewrite is the moment to notice."*

**The residual is real and is recorded, not solved:** a migrator who tidies a W2 diff breaks
their site, and no arrangement of a source tool can stop them.

### 9.2 · "W1 changes evaluation order" — **survives as a bounded cost**

True, and §4.1 states it rather than waiting to be caught: moving a key expression from
metadata into the props map reorders it against the other prop expressions. It is invisible
for pure expressions and observable for side-effecting ones. The alternative — refusing every
`^{:key}` site — would refuse the tool's highest-value rewrite over an input nobody writes.
Recorded, not reported per-site, because a per-site line for a class this rare is noise that
teaches migrators to skim the report.

### 9.3 · "W3 at the `key` slot re-creates a collision Hicasso had fixed" — **forces a recommendation**

The sibling page's §6.2 includes `:key` in the named-value rewrite, on the reasoning that
*"the rule is cheaper applied uniformly than excepted"*. Re-derived against the landed code,
that cell inverts.

Under Reagent, `{:key :foo}` went through `kv-conv` like any prop and reached React as
`"foo"`. Under Hicasso, `raw-element` lifts `(:key props)` raw and React coerces the keyword,
giving `":foo"`. So sibling elements keyed `:foo` and `"foo"` **collided under Reagent** and
are **distinct under Hicasso**. Rewriting `{:key :foo}` to `{:key "foo"}` restores the
collision — the same shape of defect `rf2-vrvv9` was filed to remove, one slot over.

Against that: not rewriting costs one full remount of the keyed list at the migration, after
which the keys are stable strings and everything is normal.

**Recommendation, and it is the operator's call because it amends an adjudicated page:
exclude the `key` slot from W3.** A one-time remount is cheap and self-healing; a duplicate
sibling key is a reconciliation defect that outlives the migration. Both readings are stated
so the choice can be made on the record rather than inherited from a cell.

### 9.4 · "Something here blanks a render prop" — **answered**

This is the charge the design was built to survive, so it gets the full audit. The question is
narrow: does any rewrite in §4 produce a value whose *return* differs from the donor's?

- **W1** moves a key expression. It touches no prop value.
- **W2** respells map keys. Values are untouched; a render prop is a function and is never a
  map key.
- **W3** turns a literal keyword into a string. A render prop is not a literal keyword.
- **W4 is the only rewrite that introduces a function**, and it introduces
  `(fn [& args] (apply f a … args))` — the donor's own wrapper, transcribed. Whatever `f`
  returned before, it returns now. A `(r/partial render-cell ctx)` at Fluent's `onRenderCell`
  keeps returning its element.
- **W5 and W6** change a head. Neither wraps a value.

**No rewrite in this set synthesizes an event wrapper, and none discards a return value.** That
is Law 2, and the audit above is its proof by exhaustion over a set of six.

Where a render prop *is* at risk, the tool refuses rather than rewrites. A callback body
containing `r/as-element` becomes `:as-element-island`: the closure runs when the library
calls it, outside the owner's render window, so replacing Reagent's lowering with Hicasso's is
not a text substitution and the tool does not attempt one. And `event-prop?` — the term that
killed E2 — can only ever refuse here (§6), so `onRenderCell` and `onRow` are exactly the
props at which this tool does the least.

**One residual, and it is not the tool's to fix.** A render prop returning Reagent hiccup
without `r/as-element` was already broken under Reagent, and stays broken. The tool neither
repairs nor reports it, because from the source text a returned vector is indistinguishable
from data.

### 9.5 · "The tool reimplements `prop-name`, and nothing pins the two together" — **survives, unrepaired**

The tool runs on the JVM over source text; `prop-name`, `dash-to-prop-name` and the three
seeded renames live in a `.cljs` codec it cannot load. So the slot resolver is a **second
implementation of a rule that already exists**, and every rewrite that consults a slot rests
on the two staying equal.

The corpus pins the tool's answers. The door's suite pins the runtime's. **Nothing pins them
equal**, and the failure is silent in both directions: a `prop-name` change that the codec's
own tests accept leaves the tool rewriting for a slot the runtime no longer emits into.

Partial mitigations exist — keep the resolver to one small pure function, comment it with the
symbol it mirrors, give the corpus one case per rule cell (the three renames, `aria`/`data`,
`--`, string verbatim). None of them is a pin. §10 carries the only structural answer, and it
is door-side work outside this page's fence.

### 9.6 · "Refusal is not free" — **survives as a cost, and the measurement is thin**

A tool that refuses a third of a codebase gets run once and abandoned. The defence is the
sibling page's count: on the only application corpus available, a machine decides the head and
the props slot at 28 of 28, and the undecidable class occurs zero times. That count is 13
application sites in one tool by one author against one library, and the sibling page says so.
If a consumer's codebase builds props through helpers, the report grows and the rewrites
shrink, and the tool becomes mostly a reporter.

**It stays worth building in that world**, which is the honest form of the defence rather than
a dismissal of the charge: a reporter that names six silent classes at exact line numbers is
still better than a troubleshooting table the migrator reads after shipping the bug. But the
value proposition changes, and a real consumer corpus would settle it.

### 9.7 · "The corpus can now be executed, so §9.5 is over-worry" — **withdrawn, and replaced**

I set out to argue that the escape landing makes the corpus executable end to end: the tool's
output is now runnable Hicasso, so a corpus case could render and assert.

It cannot, and the reason is structural. The corpus is a **JVM** harness over source text; the
destination is a **browser** runtime. Rendering an expected output means loading the codec,
React and a DOM, which is a different lane with a different build. The corpus pins text and
only text. The charge is withdrawn and what replaces it is §10's first item: the executed
evidence has to come from the door's own suite, and half of it must come from the donor's.

---

## 10 · What this page recommends, and what it leaves open

### 10.1 · The contract rows are two-sided, and one side already exists

The 2026-08-04 attack's M4 obligation — named `codemod-contract-*` rows in the door's suite —
is still the only executed evidence the rewrites are right, and `grep` finds no such row on
`main` today. But the landing changed its shape, and cheapened it.

**The Hicasso side is largely already there.** `conversion-parity-with-the-door-on-one-prop-corpus`
runs a sixteen-prop corpus through both `[a-host props]` and `[:> C props]` and asserts slot
for slot; it already covers keyword values at ordinary and HTML-attribute slots, the class
coercion across spellings, function identity, and the `:key` lift. What it does not do is name
the codemod, so a door PR that drifts it goes red without "codemod" in the failing test's name.
Renaming or aliasing is a one-line door-side change.

**The missing half is the donor side, and it is the half the rewrites actually rest on.** Every
proof sketch in §4 is a claim about *Reagent 2.0.1's* answer, and nothing in this repository
asserts one. `implementation/adapters/reagent/test/` has Reagent loaded. Six or eight rows
there — the same prop corpus through `[:> C props]` under Reagent, asserting the emitted object
— would pin the donor's side of every row in §3's table, and would go red when a consumer
upgrades Reagent under the tool.

**Both sides in the fixer's PR, per the ruling. Not optional, not deferrable.**

### 10.2 · Recommendations

1. **Exclude the `key` slot from W3** (§9.3). Amends an adjudicated cell; the operator's call.
2. **Make W2's report line the loudest the tool emits** (§9.1), with the data-versus-options
   sentence in it.
3. **Consider extracting the slot rule to `.cljc`** so the tool and the door share one
   implementation (§9.5). This is door-side work on `front/codec.cljs`, outside this page's
   fence, and it is the only structural answer to the drift. Recorded as a recommendation, not
   filed as work.

### 10.3 · The open question this page cannot close

**Nothing pins the tool's slot resolver to the runtime's** (§9.5), and short of 10.2's item 3
nothing can. Every mitigation available inside the codemod's own tree is a convention. The
failure is silent, it is in the class the whole design exists to delete, and I would rather
state it plainly than describe a corpus case as though it were a pin.

Two smaller things are open and are cheaper. Whether `check-member-key!`'s minted key warning
should reach host and `[:>]` children is still the door-side question the sibling page filed
(`rf2-2rtt6.134`); if it lands, W1's class gains a runtime net and stops being the tool's
highest-stakes rewrite. And the sibling page's coverage measurement is 13 application sites in
one repository (§9.6) — one real consumer corpus would either confirm the shape of this design
or move it decisively toward reporting.

---

## 11 · Sources

- The bead `rf2-2rtt6.106`, read bottom-up: its six design questions, the 2026-08-04 synthesis
  comment, the 2026-08-05 adjudication note, and the **2026-08-06 ruling** — *fixer first,
  after `.103` merges* — which is this page's charter.
- [The adjudicated codemod spec](reagent-codemod-spec.md) — the record this page succeeds:
  the five priced candidates, the coverage measurement and its stated bias, the residence and
  corpus discipline, and the report roster this page narrows.
- [The `[:>]` synthesized spec](raw-escape-spec.md#1-the-model-in-one-sentence) — the ruled
  destination, and `rf2-2rtt6.103`'s own build note recording where the implementation
  departed from it.
- `implementation/freehand/test/re_frame/bench/hicasso/front/codec.cljs` — `raw-element`,
  `raw-component`, `raw-crossing`, `raw-gate`, `raw-head?`, `host-entry`, `host-prop-value`,
  `host-element`, `convert-entry`, `convert-prop-value`, `nested-map->js`, `class-names`,
  `merge-caller`, `check-ref!`, `prop-name`, `cached-prop-name`, `html-attr-slot?`,
  `props-map?`, `make-element`, `check-member-key!`, `vec->element`. Cited by name, never by
  line.
- `implementation/freehand/test/re_frame/bench/hicasso/front/codec_cljs_test.cljs` —
  `the-escape-takes-the-doors-unclaimed-slot-conduct-exactly`,
  `conversion-parity-with-the-door-on-one-prop-corpus`,
  `the-carrier-never-leaks-and-key-rides-the-outer-element`, and the component-value roster.
- `implementation/freehand/test/re_frame/bench/hicasso/arm1/raw_escape_dom_cljs_test.cljs` —
  the SSR-absent, hydration-adoption, same-DOM and child-intent-capture rows.
- reagent 2.0.1, from the jar: `reagent.impl.template`'s `vec-to-elem`, `native-element`,
  `convert-props`, `convert-prop-value`, `kv-conv`, `cached-prop-name`, `adapt-react-class`
  and `raw-element`; `reagent.impl.util`'s `dash-to-prop-name`, `js-val?`, `class-names` and
  `get-react-key`; `reagent.impl.input`'s `input-component?`.
- `migration/from-re-frame-v1/codemod/` — the residence and skeleton precedent, unchanged.
- Beads: `rf2-2rtt6.103`, `.104`, `.112`, `.116`, `.119`, `.134`, `rf2-vrvv9`, `rf2-d03av`.
