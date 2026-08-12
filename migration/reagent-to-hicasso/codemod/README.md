# The Reagent → Hicasso migration reporter, and the `[:>]` fixer

A source-text tool that reads a consumer's Reagent `.cljs` namespaces and repairs
the props dialect at every foreign crossing, so that a codebase whose `[:> …]`
sites are about to be interpreted by Hicasso **keeps behaving the way Reagent
made it behave**.

It mints no declaration, hoists nothing, and edits no `ns` form. It writes whole
files through [rewrite-clj](https://github.com/clj-commons/rewrite-clj) — a
zipper over the node tree, so formatting and comments survive — skips any file
it cannot parse, and emits a report.

The design is `docs/design/hicasso/studio/reagent-codemod-against-the-landed-escape.md`,
ratified as `rf2-2rtt6.106`. This artefact implements it as amended by
`rf2-2rtt6.143`.

## Running it

```bash
cd migration/reagent-to-hicasso/codemod

clojure -M:run src/                            # scan: report only, touch nothing
clojure -M:run --rewrite src/                  # dry run: what would change
clojure -M:run --rewrite --write src/          # apply it
clojure -M:run --report out.edn src/           # choose where the report goes
```

The exit code is `0` for any run that completed and `1` only when a file could
not be read or written. **A migration tool that fails a build because a
consumer's code needs a human decision is a tool that gets run once.**

## The report has two halves, and they answer different questions

Everything below this section is about the **fixer**, whose population is the
`[:>]`-family crossing. That population is not a Reagent codebase, and the
difference is not small. Run over this repository's own 81-file example corpus
the fixer reports **zero entries** — the corpus crosses into React nowhere — and
a migrator reading that report sees eighty-one files that are not mentioned.

So the same run also produces a **census**, under `:census` in the report, whose
population is the **Reagent API call site**: `r/atom`, `r/with-let`,
`r/create-class`, `r/as-element`, `r/cursor`, `r/reactify-component`, root
mounting, and the rest of the roster in
`src/re_frame/migration/hicasso/census.clj`. On that same corpus it reports
**62 sites across 28 files**, one of them the `with-let` whose teardown no
mechanical edit can carry.

| Half | Population | Addressed at | Verdicts |
|---|---|---|---|
| fixer (`:entries`) | `[:>]`-family crossing sites | the site | rewrote / refused, in the classes below |
| census (`:census`) | Reagent API call sites | the call | `:mechanical`, `:human-decision`, `:runtime-blocker` |

*(4 columns; 2 body rows.)*

**They are different estimands and neither is a denominator for the other.** The
report names both, and the census summary always emits all three verdict
buckets — including `:mechanical 0`, which is a measurement rather than an
omission: every mechanical rewrite this tool family knows is a W-rule, and every
W-rule sits at a crossing.

**A reader-conditional require binds.** `#?(:cljs [reagent.core :as r])` is the
only legal way to require Reagent from a `.cljc` file, and the tool reads it —
both the plain shape and the splicing `#?@`, and a conditional around the whole
`(:require …)` clause too. It did not always: `ns-context` used to read the `ns`
form through one `sexpr`, which throws on a reader-conditional node, so the whole
form went unreadable and every alias came back empty. Such a file was silently a
file with no Reagent in it — W4 and W5 dead, every API in it unnamed, and the
report non-empty enough (`:>` needs no alias) that nothing looked wrong. Three
files under `examples/capabilities/ssr/` are real instances (rf2-m4hm).

**What the census still cannot resolve, it reports.** A namespace that SPELLS a
Reagent name without being Reagent's — a project vendoring it under an inlined
name, such as `day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core` — is
`:unresolved-reagent-require` at the `ns` form, with every qualified
roster-named call in the file reported as `:unresolved-alias`. The tool does not
guess that such a copy is `reagent.core`: a wrong binding rewrites working code,
which is worse than naming what it cannot resolve.

**What it cannot name, it does not count.** A Form-2 component is a `defn`
returning a `fn` and nothing else marks it; a structural test for that would
report every higher-order function in the corpus. The `r/atom` such a component
closes over is on the roster, and the shape itself is left uncounted and said
so. A confident wrong number is worse than a stated silence.

## The two laws

**Law 1 — decidability.** A rewrite fires only where the donor's answer and the
destination's answer are both computable from the source text, and they differ.

**Law 2 — no blanking.** No rewrite introduces a function whose return value
differs from the value the donor's conversion produced.

A corollary of Law 1 does most of the work: **a prop's spelling may only ever
make the tool do less.** Where a name-shaped test says "yes", the answer is
always *skip this rewrite* or *refuse this site* — never *apply this rewrite*.
That is what keeps the tool away from the failure that killed an earlier design:
Fluent's `onRender*` family and Ant's `onRow` are event-*spelled* render props,
so a tool that reads an `on*` name as "this is an event position" and wraps the
value blanks them silently. Here an event-spelled name can only ever make the
tool more conservative.

**Silent behaviour change is the only fatal class.** `[:>]` is legal in Hicasso,
so *leave it as `[:>]`* is a valid output for any site the rewrite cannot make
behaviour-preserving. A refused site still works; the report is what turns a
refusal from silence into an instruction.

## What it rewrites

| Id | Matches | Writes | Preserves |
|---|---|---|---|
| W1 | `^{:key k}` metadata on a `[:> …]` vector | `:key k` in the props map, minting the map if absent | Reagent's live key; without it the key is inert |
| W2 | a literal map value, reached from the props map through map values only | the same map with each literal key respelled by Reagent's `cached-prop-name` rule | Reagent's deep camelCasing |
| W3 | a literal keyword, or a **quoted** symbol, at a prop value | its `name` as a string | Reagent's `(named? x) (name x)` arm |
| W4 | a literal `(r/partial f a …)` at a prop value | a hygienic `let` capturing the callee and every non-literal argument once, then Reagent's own wrapper | Reagent's `ifn?` wrapper, return and **evaluation time** alike |
| W5 | `[(r/adapt-react-class X) props & kids]` in head position | `[:> X props & kids]` | Reagent's `NativeWrapper` path is the same `native-element` |
| W6 | `[:> "tag" props & kids]` with a plain tag string | `[:tag props & kids]` | Reagent's `input-component?` wrapper becomes Hicasso's controlled door |

*(4 columns; 6 body rows.)*

**A bare symbol is never a named value.** `handler` in `{:on-click handler}` is a
*variable reference*, not a symbol value, and reading it as one would replace a
live handler with the string `"handler"`. Only a keyword literal and a quoted
symbol reach W3. Everything arriving through a symbol is invisible to a source
reader and is reported as `:computed-value`.

**Every output is outside its own rewrite's input language**, so a second run is
a no-op by construction, and the corpus asserts that byte for byte.

## What it refuses

Refusals are the design's load-bearing half, not its residue. They fall into
three kinds, and the kind tells you how urgent the line is.

**Undecidable from the source text** — `:computed-props`, `:computed-value`,
`:computed-nested-key`, `:adapt-def-site`, `:cljc-site`, `:parse-error`.

**Decidable, but the rewrite would itself change behaviour** —
`:event-carrier-goes-live`, `:key-conflict`, `:string-tag-unparseable`,
`:normalized-key-collision`, `:css-var-repair`, `:named-ref`, `:amp-key`.

**Decidable, and the destination refuses at runtime** — these are the migration
blockers, and the site needs a person rather than a rewrite:
`:intent-needs-a-declaration`, `:dangerous-html`, `:r>-site`, `:f>-site`,
`:as-element-island`, `:reagent-api-residue`.

## The report

One EDN file, deterministic ordering, written on every run including a clean
one. It is exhaustive over the sites it touched or refused and carries a count
of the sites left alone, so "not in the report" is never ambiguous. Every entry
carries the source form with file, line and column — addressed against the
**input** file, so a scan and a rewrite of the same tree report the same
coordinates — and every refusal names the fix in a sentence a person can act on.

It also carries the component name, and **it is the only thing in the system
that can**: a `[:>]` refusal at runtime prints the constant `"[:>]"` beside an
empty declared set, because the escape has no name of its own. The report read
the head.

The suggestions block is labelled as guesses, and its header carries the
Fluent/Ant counter-example. The tool never synthesizes a `:callbacks` map and
there is no flag to make it. The `defhost` sketch it prints is therefore a
SCAFFOLD — the positions listed inside an empty `:callbacks` map as comments,
with `:event`, `:handler` and `:render` named above them — which is acceptable
to the door verbatim. Paste it, uncomment a row, type a contract.
`sketch_test.clj` round-trips every sketch the corpus emits and keeps it that
way.

## The three amendments

`rf2-2rtt6.143` ratified three corrections to the landed design, and this
artefact implements the design **as amended**.

**(A) W4 captures at prop-evaluation time.** The design's stated rewrite,
`(fn [& args] (apply f a … args))`, re-evaluates the callee and every argument
on *each* callback invocation; Reagent's `make-partial-fn` evaluated them once,
at construction. So `(r/partial f @snapshot)` stopped being a snapshot and
`(r/partial f (next-id!))` started minting an id per click. What is written
instead is a hygienic `let`:

```clojure
[:> Btn {:on-pick (r/partial handler @cart)}]
;; =>
[:> Btn {:on-pick (let [f__rf2 handler a0__rf2 @cart]
                    (fn [& args__rf2] (apply f__rf2 a0__rf2 args__rf2)))}]
```

Self-evaluating arguments are inlined rather than bound; symbols are bound,
because re-reading a var per invocation picks up a redefinition the donor's
captured value never saw.

The names are *hygienic* in the load-bearing sense: `__rf2` is a convention, so
the generated set is checked against every symbol the site spells and the whole
family is bumped — `f__rf2__1`, `a0__rf2__1`, `args__rf2__1` — until it is
fresh. Without that check a consumer's own `f__rf2` gets shadowed, and because
`let` binds sequentially the shadow reaches every *later* initializer:
`(r/partial vector :first f__rf2)` inside an outer `f__rf2` answered
`[:first vector]` where Reagent answered `[:first :outer]`. The names stay
deterministic — that is what lets the corpus assert output byte for byte — and
generation 0 is the bare names, which is what a site free of the suffix gets.

**(B) W2 refuses normalized-key collisions.** Reagent's `kv-conv` writes each
nested-map key under `cached-prop-name`, so `:foo-bar`/`:fooBar`,
`:class`/`:className` and `:foo-bar`/`"fooBar"` siblings each collapse onto one
JS property with an iteration-order winner. Respelling both would mint a
duplicate literal map key or pick a winner the rewrite cannot know. The whole
map is refused and every colliding source key is named.

**(C) The key slot carries a dedicated report entry.** Excluding `:key` from W3
is ratified — `:foo` and `"foo"` sibling keys collided under Reagent and are
distinct under Hicasso, so rewriting would restore a defect. But the cost of
*not* rewriting is understated as "one remount": React reconciles a changed key
by unmounting the subtree and mounting a fresh one, **discarding its state**.
Every key-slot site says so.

## Testing

```bash
cd migration/reagent-to-hicasso/codemod && clojure -M:test
```

**The golden-file corpus is the spec.** Each directory under `test/corpus/`
holds an input namespace, the expected output and the expected report, and every
case asserts the rewrite byte for byte, the report including its full `:note`
prose, idempotence, and determinism. Set `RF2_CODEMOD_REGEN=1` to author a new
case; regenerating is never how one is fixed.

The fixtures are pinned to LF in the repo's `.gitattributes`, because a golden
file whose bytes depend on who checked the tree out is not golden. The fixer
itself preserves whichever convention it finds — rewrite-clj normalizes every
newline it parses to LF, so without that the tool rewrote every line of every
CRLF file it touched — and that path is witnessed by `newlines_test.clj`, which
builds its CRLF sources in memory so they run on every platform.

Two suites sit beside it. `amendment_a_test.clj` **executes** W4's output —
plain `let`/`fn`/`apply`, with no Reagent left in it — and asserts the capture
semantics directly, running the design's stated shape alongside to show the
amendment is load-bearing. `shared_rule_test.clj` asserts that the slot rule
this tool asks is the shared one and not a copy.

`census_test.clj` gates the census on **both** ways a census fails — answering
nothing, and answering too much. The second control is not decoration: it caught
two live over-reports while the namespace was being written. An `ns` DOCSTRING
discussing `reagent.ratom/run!` in prose made five clean example files read as
five migration blockers, and `clojure.core`'s `(atom nil)`, one line above the
`rdc/render` that is the genuine finding in the SSR examples, read as a second
finding. A third defect surfaced from the other direction, by cross-checking the
census against the text it is supposed to outperform: `^:cljstyle/ignore (ns …)`
was not being read as an `ns` form at all, so **the whole file's aliases went
unbound** — W4 and W5 dead in it, every Reagent API in it unnamed, and nothing
looking wrong.

## One dependency, deliberately

The tool is otherwise self-contained — it never loads, requires or executes
re-frame2, so it runs against any Reagent corpus on a bare JVM. The exception is
`re-frame.bench.hicasso.front.slot`, the `.cljc` carrying the canonical slot
rule, which is on `:paths`.

That is the whole point of it. The design's own adversarial pass raised "the
tool reimplements `prop-name`, and nothing pins the two together" and left it
**unrepaired**: a corpus pins the tool, a DOM suite pins the runtime, and
nothing pins them equal, with the drift silent in both directions. Extracting
the rule to one shared file was the only structural answer, and `rf2-ani6y` took
it. Transcribing it back in here to "remove a dependency" would reproduce, inside
the tool, the exact defect the tool exists to delete — so a test asserts the rule
came out of the shared file.

Reagent's *own* key function is a second rule and does live here, in
`donor.clj` — but written as the shared rule plus its two named deltas (a string
key is verbatim under the donor; a `--custom-property` is mangled), never as a
second copy of the camel algorithm.

## Scope

**The hoist is not here.** The declare-what-you-use-twice `defhost` hoist with
dedupe is demand-gated by the same ratification: it gets built when a real
migrator reports the manual declarations as the pain.

This is not a general Reagent migrator. It looks at `[:>]` sites and nothing
else.
