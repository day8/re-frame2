Closes rf2-hic-022. Also carries item 3(b) of the **rf2-djxr** operator ruling.

Six clj-kondo checks for the Hicasso authoring surface, published as an export
from the artefact so a consumer picks them up with `--copy-configs`. Three
errors mirror refusals the runtime already raises, so lint only says them
sooner; three are warnings.

The bead's phrase is **"syntactic facts only, no implied whole-program
analysis"**, and that is the design rather than a caveat. clj-kondo sees one
form at a time and does not know your program, so every check here is written
to be *always right about a narrow thing* rather than *usually right about a
broad one*, and each goes silent the moment the form stops being decidable.
The checks that are **not** here are listed below and in the export's README,
because the next person will have the same ideas.

## What existed already, and what was reused

`.clj-kondo/config.edn` + `.clj-kondo/hooks/re_frame/core.clj` at repo root
(the `reg-view` / `with-frame` / `with-new-frame` hooks) are the repo's
established shape, and this export copies it: same `hooks.<ns>` naming, same
`api/list-node` rewrite style, same "rewrite to the core form and let kondo do
the analysis" approach. The clj-kondo pin `2026.04.15` and the
`clojure -M -m clj-kondo.main` invocation are taken from the two places the
repo already states them — `lint.yml`'s installer step and the deps-new
template's `:clj-kondo` alias. No new mechanism was invented.

**One thing that turned out to be missing rather than present.** Repo-root
`:lint-as` covers only the *retired* bench spelling
`re-frame.bench.hicasso.arm1.lang/*`. The moved package's `re-frame.hicasso/*`
had no entry at all, so since rf2-hic-001 every `defview` name and every
destructured prop under `implementation/hicasso/` has read as `Unresolved
symbol` in CI and in editors. The export's shape hooks fix that as a side
effect — measured below.

## The six checks

Every one has a positive fixture that trips it and a negative fixture of
correct code that resembles it and does not.

| Check | Level | Positive case | Negative case (the one that matters) |
|---|---|---|---|
| `merge-not-a-map` | error | `{:& [:a :b]}`, `{:& "s"}`, `{:& :kw}` | `{:& {:class "c"}}`, `{:& attrs}`, `{:& (merge a b)}`, `{:& nil}` |
| `deferred-read` | error | `h/sub` and `h/use-subs` inside `(h/hfn …)` | reads inside a `(fn …)` handed to `mapv` / `for` — those run **during** the body and are legal; a callback that only dispatches; a read taken at the top of the body and closed over |
| `function-in-head-position` | error | `[:div [(fn …)]]`, `[:div [#(…)]]`, `[:div [(h/hfn …)]]` | `[(fn [] :a) (fn [] :b)]` bound in a `let` — an ordinary vector of functions; fn literals at `:ref` / `:on-change` / `:on-click`; `[row-view {…}]`, `[:<> …]`, `[:> Foo …]` |
| `parked-read` | warning | `(reset! r (delay (h/sub …)))`, `(reset! r (fn [] (h/sub …)))`, `(vreset! r (delay (h/use-subs …)))` | `reset!` of a plain read *value*; `reset!` of a delay or closure that reads nothing |
| `unkeyed-mapped-child` | warning | `for` with props lacking `:key`, `for` provably without props, `map` and `mapv` over `(fn …)` | keyed `for` and `map`; a `for` whose body is a **call**; `[:li item]` where position 1 is a symbol; `#(…)` as the mapping fn; a mapped seq that is not children |
| `nameless-interactive-element` | warning | `[:button {:class …}]`, `[:a {:href …}]`, `[:button.icon#close {…}]` | text child; `:aria-label`; `:aria-labelledby`; `:title`; a child we cannot judge; dynamic props; **event vectors** — see below |

### The negative fixture earned its keep immediately

`[:button {:on-click [:a]} "Save"]` — the event vector `[:a]` and the anchor
`[:a]` are the same three characters, and the first draft read every
`:on-click [:a]` in the corpus as an unnamed anchor. Position is the only
honest discriminator, so no element check now looks inside a props map
(`element-subforms`). The `:&` check deliberately keeps the full walk, since a
merge key lives in a props map by definition.

## Confirmed failing before the rules existed

The repo's config as it stands on `origin/main`, over the positive fixture,
clj-kondo 2026.04.15. Verbatim, first and last lines plus the tally:

```
implementation/hicasso/lint-fixtures/positive.cljs:22:12: warning: Unresolved symbol: merge-carrying-a-vector
implementation/hicasso/lint-fixtures/positive.cljs:22:37: warning: Unresolved symbol: _
...
implementation/hicasso/lint-fixtures/positive.cljs:102:12: warning: Unresolved symbol: callback-form-in-head
linting took 262ms, errors: 0, warnings: 22
```

**Zero errors, and not one of the six checks fires.** Every warning is an
`Unresolved symbol` — which is the pre-existing shape gap described above,
visible in the same output. After the export: `errors: 8, warnings: 10`, 18
findings across all six checks.

## Checks deliberately NOT added — they need whole-program knowledge

| Wanted | Why not |
|---|---|
| **Direct invocation of a `defview`** — `(todo-row {…})` rather than `[todo-row {…}]` | Requires knowing a symbol resolves to a var minted by `defview`, usually in another namespace. `clj-kondo.hooks-api/ns-analysis` *can* answer it from the analysis cache — which is worse than not answering: the check would fire in a full CI run and stay silent in an editor linting one file. A rule that fires *sometimes* trains people to ignore it. |
| **Plain function in head position, by symbol** — `[helper {…}]` | Same knowledge, same answer. The literal-`fn` slice is the decidable part and is shipped. |
| **Deferred `sub` in general** — the bead's "fn literal inside a body" | An `fn` literal is **not** evidence of deferral: `(mapv (fn [id] … (h/sub …)) ids)` runs during the body and is legal, and a helper may donate reads to its caller. Only `hfn` is deferred *by construction*. The bead's own worker note says read-extent proof is the runtime's law (hic-011), and this is that line drawn. |
| **`:&` forwarding a non-map value** | Only literals are decidable; `{:& attrs}` is the ordinary spelling. |
| **`sub` naming an unregistered query id** | Registration is whole-program, and ids are often built rather than written. |
| **`:input` without a label** | The accessible name comes from a sibling `<label for=…>` — tree knowledge, not form knowledge. Deferred to hic-043 with the rest of a11y. |
| **`reset!` of a thunk reached through a binding** (rf2-djxr) | `(let [d (delay (h/sub …))] (reset! r d))` and `(reset! r (make-thunk))` need bindings followed across forms. The ruling's own words forbid building that: *"do not build new analysis machinery for it."* The literal shape is what shipped. |
| **Hiccup in an ordinary `defn` helper** | A limit, not a decision: hooks fire only at registered call sites, so all checks see hiccup inside `defview` / `defhost` / `hfn` and nowhere else. Stated in the README. |

## rf2-djxr item 3(b)

The ruling is Option 3 — ratify the limit, trust the programmer, assistance
and guardrails, **no enforcement machinery**. So `parked-read` is a
**warning**, it fires only on a thunk written out in full at the `reset!` /
`vreset!`, and it builds nothing new: it reuses the same walk and the same
read-resolution the other checks use. Its message says the conduct is
*undefined*, not illegal. Nothing in this PR touches the runtime,
`realize-deep`, `invariants.md`, the read-extent tests, or the draft guide.

## Where the checks are gated

`.clj-kondo/config.edn` now points `:config-paths` at the artefact's export, so
**this repo is the export's first consumer** rather than a second, divergent
description of the same three macros — and the already-required `clj-kondo`
job is what proves the export loads and stays quiet on real code.

Measured against `origin/main`'s config over CI's exact lint surface, pinned
clj-kondo 2026.04.15 (fixtures excluded from both sides):

```
errors        0 -> 0
new findings  0
removed      50   all `Unresolved symbol`, all under implementation/hicasso/
```

Two silences, both stated rather than sprinkled through source:

- `re-frame.hicasso.read-extent-cljs-test` turns `deferred-read` off by
  namespace. Its two hits are **true positives** — the suite's job is to write
  `h/sub` inside `h/hfn` and prove the runtime refuses it (the I7 legality
  matrix). Silenced by `:config-in-ns` so that file, which another worker owns,
  is untouched.
- `implementation/hicasso/lint-fixtures/` is off the lint surface: it is wrong
  on purpose. It sits beside `src/` and `test/` rather than inside either, so
  nothing compiles it.

## The suite, and the lane it does not have

`re-frame.hicasso.lint-export-test` runs clj-kondo **in process** with the
shipped export as its `:config-dir` — how a consumer's copied config is
loaded — so a rule that works in the suite is the rule the artefact publishes.
4 tests, 14 assertions: every check fires at its exact fixture rows; the
negative fixture yields **no finding of any kind** (which covers the shape
rewrites too — an `Unresolved symbol` there would mean props stopped
resolving); the artefact's own testbeds are silent; and the export's
`:linters` roster must equal the suite's, so a check cannot ride in
unwitnessed.

Both controls were **run, not assumed**:

- deleting the nameless-element check from the walk → 2 failures naming it;
- pointing the element checks back at the full walk → the negative test reds
  with the three event vectors it would misread as anchors, runner exit 1.

`git diff --stat` was clean after reverting each probe.

**No automated lane runs this suite, and that is the honest state.** Adding
`implementation/hicasso` to `scripts/test-jvm-implementation.sh` was tried and
the spine refused it — `scripts/check_jvm_lane_rosters.py` (rf2-as6bg)
enforces a bijection with `test.yml`'s `jvm-*` jobs:

```
R1 implementation/hicasso: on scripts/test-jvm-implementation.sh, but no
.github/workflows/test.yml job runs `clojure -M:test` there -- its only lane
is somebody remembering to run the script
```

That is the gate being right. Completing the pair needs a `jvm-hicasso` job in
`test.yml`, which is **hot-zone and sequential** — a scheduling decision this
bead does not get to take, so it is reported rather than made. The roster entry
is reverted, the reason is recorded in the roster, in TESTING.md and in the
artefact's `deps.edn`, and the by-hand command is named. The `--probe` waiver
stays dropped: the artefact genuinely owns a JVM-runnable suite now, and
re-waiving the test-count floor to buy back a lane it does not have would be
the dishonest half of that trade.

**Suggested follow-up bead:** add a `jvm-hicasso` job to `test.yml` running
`clojure -M:test` in `implementation/hicasso`, and restore the roster entry in
the same PR (the bijection gate requires both halves together).

## Quality gates

| Gate | Exit | Notes |
|---|---|---|
| `sh scripts/test-fast-pr.sh` | `EXIT_FASTPR` | `gate root: /c/Users/miket/code/re-frame2-worktrees/lint-hic022` |
| `clj-kondo --parallel --fail-level error` over CI's 12 `--lint` paths | `0` | CI's exact command, at CI's exact pin `2026.04.15`. **This is the gate that genuinely covers this surface.** `errors: 0, warnings: 4542` |
| `python scripts/check_jvm_lane_rosters.py` | `0` | `33 rostered artefacts, 0 baselined` |
| `cd implementation/hicasso && clojure -M:test` | `0` | `Ran 4 tests containing 14 assertions. 0 failures, 0 errors.` |

The first spine run exited **1** on the bijection violation quoted above; the
run recorded here is after the fix.

**Required CI checks the local spine does not run**, per
`python scripts/check_fast_pr_gap.py --list` (not a hand-list): the spine has
lanes only in `test.yml`'s `All required checks passed`. `Lint required`
(`lint.yml` — `clj-kondo`, Splint, ESLint, the public-API manifest drift-check)
and `Docs required` (`docs.yml`) are required and are in no tier of the spine.
Of those, the `clj-kondo` job is the one that matters here and it was run
directly, at the pin, as the table records. The gap report's own caveat
applies and is why the pin was matched: *"A pass from a differently-versioned
local binary PROVES NOTHING."*

## Fences

No file under `implementation/hicasso/src/**` is touched — three workers are in
that tree. Nothing under `.github/workflows/**`, `implementation/deps.edn` or
`implementation/shadow-cljs.edn` is touched. `tools/xray/**` is untouched, so
**spec unchanged because this bead has no Xray surface**. Nothing under
`docs/design/hicasso/product/invariants.md`, the read-extent test files, or the
draft guide is touched — those are rf2-djxr's other worker's.
