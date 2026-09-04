# Template — Design Rationale

> WHY each major decision was made. The audit trail behind the
> [000-Vision](000-Vision.md) / [001-Substrate-Variants](001-Substrate-Variants.md)
> / [002-Generated-Shape](002-Generated-Shape.md) / [Principles](Principles.md)
> / [API](API.md) shape. Beads referenced as rf2-xxxx. Superseded
> decisions stay, marked as such, because the reasoning behind them is
> the record of what was tried.

## §1 — deps-new over clj-new

**Decision.** This template is a [deps-new](https://github.com/seancorfield/deps-new)
template with a programmatic body, distributed via git-coord. Not a
[clj-new](https://github.com/seancorfield/clj-new) template, not a
Clojars-published artefact.

**History.** v1 of this template (through 2026-05) was a clj-new
template published to Clojars as `day8/clj-template.re-frame2`. The
template walkthrough Q2 lock (2026-05-12, rf2-dolpf) flipped both
decisions: deps-new over clj-new, git-coord over Clojars. The
migration landed across rf2-cwvnj (§2.1 spike) / rf2-c2770 (§2.2-2.4
full body + flags) / rf2-40vmd (§2.5 clj-new drop) / rf2-h0w5y
(§3 release pipeline). The pre-flip rationale survives as
[§Retired §clj-new-over-deps-new](#retired--clj-new-over-deps-new)
below for the historical record.

**Alternatives considered (current decision).**

| Option | What it is | Outcome |
|---|---|---|
| A — deps-new + git-coord | Programmatic template body via `:data-fn` / `:template-fn` / `:post-process-fn` hooks; tagged-commit distribution. | **Selected.** |
| B — clj-new + Clojars | The v1 shape. Declarative Mustache resources + a `:project/new` alias; Clojars-published. | Rejected (see migration rationale below). |
| C — bb-based CLI / wizard | A bb-based wizard ("include Story? include 10x? Reagent or UIx or Helix?"). | Rejected for v1 (still the right answer for branching choices that materially exceed deps-new's substitution capability). |

> **Amended (rf2-zq34m, 2026-09-02).** The three-flag set the first
> bullet below reasons from is gone — see [§10](#10--one-selector-one-manifest-rf2-zq34m).
> deps-new stays for the reasons that survive it: the programmatic body
> carries the per-substrate `case` where it can be read and tested,
> top-level k/v args replace the `:edn-args` workaround, and the tagged
> commit is the artefact.

**Why deps-new (over clj-new).**

- **Programmatic body matches the v1 locked flag set.** Three opt-in
  flags (Story, Tailwind, SSR) plus a three-way substrate axis. Each
  added conditional file selection (different `deps.edn`, different
  `core.cljs`). deps-new's `:template-fn` is a plain Clojure fn that
  builds the `:transform` vector at runtime — the `case` / `cond` lives
  in CLJ source, where it can be read, refactored, and tested directly.
  clj-new's Mustache `{{#flag}}…{{/flag}}` blocks express the same shape
  with less visibility (the branching is scattered across template
  resources instead of localised in one fn).
- **Top-level k/v args.** deps-new takes template arguments directly
  as top-level k/v after `:name`. clj-new's harness strips unknown
  top-level args; the v1 template plumbed `:substrate` through
  `:edn-args` to work around that (see
  [§Retired §`:edn-args`-not-top-level](#retired--edn-args-not-top-level)
  below). deps-new removes the workaround.
- **2026 default.** deps-new is the seancorfield/clojure-tooling
  default for new templates going forward. clj-new is in maintenance
  mode; new templates target deps-new.
- **Tooling-team portability.** deps-new is maintained by the same
  author as clj-new (sean-corfield); the artefact is a known
  quantity for downstream consumers.

**Why git-coord (over Clojars).**

- **Build-time-only artefact doesn't need Maven.** The template is
  never on a consumer's runtime classpath (Principles §P1). Maven
  packaging adds release machinery (jar build, pom generation,
  Clojars deploy token, version-yanking-not-supported recovery
  procedure) without buying anything for a tool that consumers
  invoke once at scaffold time. deps-new natively resolves
  `:template io.github.day8/re-frame2-template[#tag]` by cloning
  the tagged commit — the tag IS the artefact.
- **Tag-pinning is first-class.** Consumers who want a specific
  release pin via `:template io.github.day8/re-frame2-template#template-v0.0.1.alpha`.
  No "couldn't find artifact" error window between deploy + Clojars
  index refresh — `git clone <tag>` is the operation, and it's
  available the moment the tag is pushed.
- **Independent release cadence.** A template-only doc polish
  doesn't warrant republishing the framework. The template's own
  `tools/template/VERSION` file drives the `template-v…` tag sequence
  independently of the framework-wide repo-root `VERSION` that drives
  the `v…` tag sequence (see
  [`.github/workflows/template-release.yml`](../../../.github/workflows/template-release.yml)).

**Why not a bb-based wizard (option C).** More flexible for
branching choices ("include Story? include 10x?") but:

- Heavier-weight for the user (extra runtime, extra prompts).
- Outside Clojure norms — the community expectation for "scaffold
  a new project" is deps-new / clj-new / lein new, not a bespoke
  script.
- The branching surface never needed it, and since §10 there is none
  beyond the substrate.

Option C remains reserved for the day when branching choices materially
exceed deps-new's substitution capability.

## §2 — Three substrates in v1 (Reagent / UIx / Helix)

**Decision.** Ship Reagent, UIx, and Helix variants together in
the first cut.

**Alternatives considered.**

| Option | What it is | Outcome |
|---|---|---|
| A — Reagent only, defer UIx / Helix | Match v1's surface (Reagent only). | Rejected. |
| B — Reagent + UIx | Two substrates; defer Helix. | Rejected. |
| C — Reagent + UIx + Helix | Three substrates from day one. | **Selected.** |

**Why all three.**

- **Substrate-portability is a re-frame2 design assertion.** Spec
  006 (Reactive Substrate) and the per-adapter jars
  (`implementation/adapters/{reagent,uix,helix}`) commit re-frame2
  to substrate-agnostic business logic and substrate-specific
  render edges. The template, as the front door, should demonstrate
  this from day one. Shipping Reagent only would imply Reagent is
  privileged.
- **Cost is marginal.** The shared shell (`events.cljs`,
  `subs.cljs`, host HTML) is identical across substrates. Only
  `core.cljs`, `views.cljs`, `deps.edn`, and the npm pins
  differ. The per-substrate resource sub-tree is ~5 files.
- **CI exercises every variant.** The JVM test suite under
  `test/day8/re_frame2_template/` runs every substrate end-to-end
  across template-shape, static-parse, and behavioural slices, so
  adding a substrate has a low maintenance tax.

Reagent remains the default — it's the canonical substrate every
re-frame example targets first. UIx is an equal citizen, not an
afterthought.

> **Amended at S7/W13 (rf2-d6epb, 2026-07-22).** The Helix adapter was
> removed framework-wide, and the template's `:helix` variant went with
> it.
>
> **Amended again (rf2-qmvep, 2026-07-25).** The EXPERIMENTAL `:ui`
> compiled-view variant (added under the 2026-07-19 template-menu
> ruling) is retired. It emitted a `day8/re-frame2-ui` Maven
> coordinate, and Mike's 2026-07-22 ruling is that the artefact is
> never published — so every external consumer who picked `:ui` got a
> `deps.edn` whose first `clojure -M…` failed on an unresolvable
> dependency. The in-repo gates were green only because they rewrote
> that coordinate to `:local/root`, an affordance no consumer has. A
> scaffold for a substrate that will not exist is not worth
> maintaining.
>
> The menu is now `:reagent` (default) and `:uix`; the decision record
> above is kept as history.

## §3 — Counter as the canonical example

**Decision.** Every variant emits a counter. Not a TodoMVC, not a
hello-world, not a routing-demo.

**Why.**

- **rf2-2kzw throughline.** [The Guide introduction — a tiny counter
  application](../../../docs/core/introduction.md) walks through a
  counter. The reference `examples/<substrate>/counter*/` apps
  are counters. The template emits a counter. A developer who
  runs the template and then opens the guide sees the same code
  in both places.
- **Smallest meaningful surface.** A counter exercises the full
  cycle: init event, action event, sub, view that dispatches.
  Nothing smaller covers the cycle; nothing larger is justified
  for a scaffolding tool.
- **No domain.** A counter doesn't tie the template to a problem
  domain (todo lists, login forms, dashboards). The user replaces
  it with their actual feature on first edit.

A TodoMVC or login-form variant would be a richer example, but
they are richer examples of re-frame2, not richer scaffolds. The
template is for getting started; the per-substrate `examples/`
trees are for studying complete patterns.

## §4 — No-Story-yet (default), opt-in via a Story flag (rf2-t009p)

> **SUPERSEDED 2026-09-02 by rf2-zq34m — see
> [§10](#10--one-selector-one-manifest-rf2-zq34m).** The default-off
> half of this decision stands and is now the whole of it: the template
> pre-wires nothing for Story, and the opt-in flag that scaffolded the
> playground is gone. Story attaches to a generated app through
> [`docs/story/index.md`](../../../docs/story/index.md) — a dev-alias
> coord, a require and a mount — and the generated README's next-steps
> section links that page. The record below is why the flag existed.

**Decision (superseded).** The template did **not** pre-wire
[`tools/story/`](../../story/) on the default path. An opt-in Story
flag was the on-ramp — Reagent-only, with a UIx variant to follow once
that adapter's coverage matched Reagent's.

**Why default-off.**

- **Story is post-1.0 but still moving.** Stage 8 (rf2-c9mm)
  landed; subsequent stages tune the authoring surface. Default-on
  would force every scaffolded app to track every change to the
  seven `reg-*` macros and the variant id grammar.
- **Cost of an unwanted dep.** Users who don't reach for Story
  shouldn't pay for it in their `deps.edn`, their bundle
  isolation grep set, or their docs surface.

**Why the opt-in shape was chosen.**

- **The exemplar existed.** [`tools/story/testbeds/counter_with_stories/`](../../../tools/story/testbeds/counter_with_stories/)
  is the canonical shape — the scaffold mirrored its file layout
  (entry-fn hash-routes between `#/` and `#/stories`, dedicated
  `stories.cljs` that fires the `reg-*` calls).
- **Hand-wiring it from cold was judged friction.** A first-time
  Story user has to read the exemplar, port the routing shape, decide
  which `reg-*` macros to invoke, and remember the
  `day8/re-frame2-story` coord. One flag collapsed that into a
  scaffolded baseline they could edit.

**Why it was superseded.** The flag selected a 123-line routing and
lifecycle entry point, a second `deps.edn` carrying a git-pinned
tools coordinate plus a resolution pin for the library both tools
share, and a 76-line registration example — and, to exist at all, it
required the known-keys gate, the coercers, the capability matrix and
an error vocabulary. That machinery cost every consumer, to save three
pasted forms that the Story page already shows. Story also hard-depends
on Reagent, machines, HTTP and Xray, which made it the weakest
candidate for the one surviving flag.

## §5 — Pin source-of-truth in one place

**Decision.** `:rf2-version`, `:shadow-version`, `:react-version`
are defined as inline literals in
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)'s
`data-fn` — not in an EDN data file, not in a Clojars-fetched
manifest, not derived from anything at runtime.

**Why.**

- **Releasability.** Bumping the template is a one-file edit. The
  git-coord release pipeline (`.github/workflows/template-release.yml`)
  cuts a `template-v…` tag; the new defaults flow to consumers on
  next invocation.
- **Visibility.** A reader of `data-fn` sees the pins inline,
  alongside the substrate decode and the file emit list. No
  indirection through "go check this EDN data file."
- **Lockstep with `implementation/package.json` and the repo-root
  `VERSION`.** The pins match what the reference implementation's
  CI smoke-tests. The in-template lockstep guard
  ([`test/day8/re_frame2_template/version_lockstep_test.clj`](../test/day8/re_frame2_template/version_lockstep_test.clj))
  asserts:
    - the template's emitted `:rf2-version` matches repo-root `VERSION`;
    - the template's emitted shadow-cljs pin matches `implementation/package.json` :devDependencies/shadow-cljs;
    - the template's emitted react / react-dom pins match `implementation/package.json` :devDependencies/react.
  Drift fails the test → blocks the `template-v…` tag release.

The alternative — deriving pins at template-build-time from
`implementation/package.json` — was considered and rejected. It
would tie the template's build to the implementation tree's
state at template-publish time, with no win in correctness over
"manually bump in lockstep, gate the drift via the lockstep test."

## §6 — Resource tree shape (`root/` + `_shared/` + per-substrate)

**Decision.** The template's resource tree splits into:

```
resources/day8/re_frame2_template/
├── template.edn                ; declarative entrypoint
├── root/                       ; bulk-copied, no rename
├── _shared/                    ; substrate-agnostic; rename + emit only on select
├── _reagent/                   ; substrate-specific
└── _uix/                       ; substrate-specific
```

Not a flat layout with substrate-prefixed filenames, not a
per-variant top-level tree (no shared tree at all).

**Why.**

- **Substrate-agnostic shell is the right cut.** Events, subs, the
  test, host HTML, README, .gitignore, and the build configs
  (`shadow-cljs.edn`, `package.json`) are substrate-portable by
  design — the React substrate is chosen in `deps.edn` + `core.cljs`,
  never in the build configs. Splitting them out under `_shared/`
  makes that visible and keeps each build config as one source rather
  than two byte-identical copies (the lone per-variant difference is
  a display name, filled by `{{substrate-label}}`). A reader of
  `resources/day8/re_frame2_template/` sees the architecture
  immediately: only `core.cljs`, `views.cljs`, and `deps.edn` live
  under the per-substrate dirs.
- **Adding a substrate is "drop a sub-tree."** Future substrates
  plug in by adding a sibling of `_reagent/` and `_uix/`, one
  registry entry and one `case` arm. Nothing in `_shared/` needs to
  change.
- **The `root/` vs underscore-prefix split mirrors deps-new's
  contract.** `root/` is bulk-copied with default placement (no
  rename, no `:only`); the underscore-prefixed dirs are picked up
  by `template-fn`'s `:transform` entries with `:only` set, so
  they emit conditionally and with renames.

## §7 — JVM test (no `-Tnew` shell-out in the test path)

**Decision.** The JVM tests
([`test/day8/re_frame2_template/*.clj`](../test/day8/re_frame2_template/))
invoke `org.corfield.new/create` directly in-process, bypassing
the `clojure -Tnew create` shell harness. The layered shape:
generate to a tmp dir, pin the emitted manifest, parse-and-audit the
emitted cljs against the framework surface, and optionally compile,
run and boot the emitted app.

**Why.**

- **Fast.** No subprocess spawn, no Maven resolution from
  cold, no harness setup beyond JVM start. Each test runs in
  seconds.
- **Hermetic.** Doesn't depend on the test machine having
  deps-new wired into a `~/.clojure/deps.edn` alias. The `:test`
  alias in `tools/template/deps.edn` pulls deps-new in directly.
- **The `-Tnew` harness layer is tested by deps-new itself.**
  What the template is responsible for is its hooks' behaviour:
  emit the right files, with the right substitutions, throw on
  bad input. The test exercises that surface — `data-fn` runs,
  `template-fn` builds the right `:transform`, `post-process-fn`
  prints the right message, and the emitted tree matches the
  expected shape.

The behavioural compile+run+boot slice (`emitted_test_run_test.clj`) is
opt-in via `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` (CI sets this; local
fast-loop default-off). It is the tier that proves the scaffold a
newcomer opens actually paints — and, since rf2-eiev, that the
`:advanced` bundle they then deploy paints too, for every substrate; the
manifest equality, the static-parse audit and the lockstep guards are the
cheap contract underneath it.

## §8 — Strict substrate coercion (keyword-only)

**Decision.** `coerce-substrate` accepts only a keyword (or nil,
which defaults to `:reagent`). String, symbol, and any other shape
throws with a clear message naming the valid set.

**History.** This tightened from a forgiving-input posture
(kw/string/symbol all accepted) to keyword-only in rf2-h0imw, on the
back of the rf2-c8tmc first-principles audit (Finding #3 / §D5). The
earlier forgiving posture was defensive against shell quoting
variance that — in deps-new's actual k/v contract — does not happen:
deps-new passes top-level args through as Clojure values, so a
keyword on the CLI reaches `data-fn` as a keyword. The
multi-form branch was guarding against a problem the contract
already rules out.

**Why keyword-only.**

- **deps-new's contract is sharp.** `clojure -Tnew create
  :substrate :uix` arrives at `data-fn`'s `data` map as `{:substrate
  :uix}` — a keyword. The forgiving branches existed for a clj-new-era
  invocation shape that no longer applies. (No string/symbol input
  has ever been observed in the deps-new tests; the branches were
  dead defensiveness.)
- **Pre-alpha SOTA-masterpiece posture.** Narrow contracts catch
  registration errors earlier and read more honestly. The contract
  is now: pass a keyword, get a result; pass anything else, get a
  clear error.
- **Strict on the value set is unchanged.** Any keyword outside
  `#{:reagent :uix}` throws with a clear message naming the
  valid set. The user sees the typo and fixes it.

**Why not keep the forgiving posture.** The inverse — strict on
value set, forgiving on input shape — sounded ergonomic in
principle, but in practice deps-new never delivers a non-keyword,
so the forgiving paths were untested code that papered over no real
input. Removing them aligns the code with the actual contract.

## §9 — Xray on by default (rf2-y9zqc)

> **SUPERSEDED 2026-09-02 by rf2-zq34m — see
> [§10](#10--one-selector-one-manifest-rf2-zq34m).** The scaffold no
> longer wires Xray: no coordinate, no preload, no layout host, no
> host CSS, no npm packages. Xray attaches to a generated app through
> [`docs/xray/01-installation.md`](../../../docs/xray/01-installation.md)
> — the dev-alias coord, the host, the preload, the two npm packages
> — and the generated README's next-steps section links that page.
> The record below is why it was default-on, and what that cost.

**Decision (superseded).** Every generated app shipped [Xray](../../xray/)
— the in-app devtools panel — wired on by default for development: the
`day8/re-frame2-xray` runtime coord in `deps.edn`, the
`day8.re-frame2-xray.preload` in `shadow-cljs.edn`'s `:app`
`:devtools {:preloads …}`, and the `[data-rf-xray-host]` layout
column in `index.html` / `app.css`.

**Amendment (rf2-p6f6u ruling, 2026-07-22).** The decision's scope
narrowed to the **Reagent** scaffold. Xray's panel shell mounts
through the ratom-family substrates; on the element-shaped React
substrates the mount verbs refuse cleanly (rf2-qgfo4), so a `:uix`
scaffold wired for Xray promised a panel that could never open. The
`:uix` variant shipped no Xray pieces at all and its README noted the
devtools story honestly.

**Alternatives considered (at the time).**

| Option | What it is | Outcome |
|---|---|---|
| A — Xray on by default | Wired into every scaffold; auto-mounts in dev. | Selected, later superseded. |
| B — Xray behind an opt-in flag | A feature flag like Story's. | Rejected. |
| C — No Xray; document how to add it | A README note pointing at the Xray coord. | Rejected then; the shape §10 lands on. |

**Why default-on was chosen.**

- **Xray is the scaffold's primary feedback surface.** The
  headline promise is "save and see it live." Xray's dispatch
  log, app-db diff, causality graph, and time-travel scrubber are
  exactly the "what just happened" feedback a first-run user needs;
  it is to re-frame2 what the browser devtools are to a web app.
- **Zero production cost.** shadow honours the `:devtools` key only
  under `watch` / `compile`, never `release`, so the preload is
  automatically absent from the production bundle.
- **Stable, panel-complete surface.** Xray's panel set and host
  contract are settled, so default-on doesn't force every scaffold
  to track a churning API.

**Why it was superseded.** "No production bundle cost" is narrower
than "no scaffold cost". Default-on Xray put in every newborn Reagent
app a sha-pinned git coordinate into this monorepo, two npm packages
for a canvas the user may never open, a preload, a layout host, host
CSS, a policy qualification, README material, and the lockstep tests
that kept those pins honest — and it kept the two substrates
permanently asymmetric, since Xray cannot mount on UIx. That is the
class of coupling the surface review exists to cut, and it is
reversible in a one-line PR if the evidence ever says otherwise.

## §10 — One selector, one manifest (rf2-zq34m)

**Decision (2026-09-02, ruled by delegation from Mike).** The template
is one deliberate contract: `:name` plus `:substrate` (`:reagent`
default | `:uix`), emitting the same twelve-file runnable counter SPA
for every substrate. The three feature flags (Story, SSR, Tailwind)
are deleted from the hook API, the resources, the error vocabulary,
the tests, this spec and every public description, and fail as
unknown keys. The generated CI workflow, Lefthook, cljfmt, clj-kondo,
`.editorconfig`, `dev/` REPL scaffolding, the whole-app-db schema, the
error-sink and HTTP tutorials, the security policy and the Xray
wiring all go. A future substrate is a new VALUE of `:substrate` —
one registry entry, one `_<substrate>/` tree of three files, one
`case` arm — never a second key, and never an authoring-model
selector orthogonal to it.

**What the record showed.** The default Reagent scaffold had grown to
twenty files, 79,715 bytes and 1,641 lines of resources — a 719-line
README, a 232-line `events.cljs` for two tiny handlers, a 76-line
`deps.edn` — and installed schemas, a git-pinned tool with two npm
packages, a trace sink, a commented HTTP handler, a content-security
policy, GitHub Actions, Lefthook, cljfmt, clj-kondo and a REPL scratch
namespace before the reader had replaced the counter. `hooks.clj` was
774 lines, the tests 5,885 lines, the spec 3,326 lines, and most of
the suite pinned prose, badges and variant combinations rather than
the first-run contract. History showed accretion, one useful recipe at
a time, with no product pass re-asking what belongs in a starter.

**Alternatives considered.**

| Option | What it is | Outcome |
|---|---|---|
| A — strip both | No Xray preload, no Story flag; `:substrate` is the only selector. | **Selected.** |
| B — keep the Xray preload only | A neutral starter that still opens the devtools panel on first save. | Rejected: keeps a sha-pinned monorepo coordinate in every newborn app, ~35 KB of npm surface, the substitution slots and lockstep pins, and a permanent Reagent/UIx asymmetry; the coherent fallback only if the front door were a showcase. |
| C — keep the Story flag only | One surviving flag as the playground's on-ramp. | Rejected: one flag preserves the whole genus — the known-keys gate, coercion, capability matrix, error vocabulary, variant resources, matrix cells and an option-system spec — to save three pasted forms the Story page already shows. |
| D — repair pass, keep both | Fix the drift and size findings in place. | Rejected: leaves the accretion the whole record reads as accretion. |
| An `:include-xray?` replacement flag, a `:minimal?` switch, profiles, a wizard, a second template | Ways to keep the option surface. | Rejected: the option surface is the thing being deleted. |

**Why.** A starter's job is to be read, run and replaced in one
sitting. Everything the scaffold had accumulated was a decision the app
had not made, and each one had a better home: a docs page that teaches
the tool at the moment the reader wants it, reached from a next-steps
list rather than installed in advance. Scaffold cost is not bundle
cost. The front door is a neutral starter; the showcase is the docs,
the testbeds and the setup skill. Posture applies in full: pre-alpha,
no back-compat shims, trust the programmer to paste four lines when
they want a panel.

**The acknowledged loss, and the revisit condition.** A new Reagent
user no longer sees the project's most distinctive devtools
automatically, and some users will never follow a next-steps link.
That is a product-marketing loss, not a runtime-correctness loss, and
it is recorded rather than hidden behind the word "minimal". Default-on
Xray is reconsidered only on first-run evidence that auto-opening the
panel materially improves onboarding, only once Xray has a stable
published coordinate (`xray-v*`), and only if it can mount on every
default substrate — today it cannot mount on UIx (rf2-p6f6u), and its
Reagent-only preload is exactly the asymmetry that would force the
manifest contract into exception clauses.

## Retired — clj-new-over-deps-new

> This was the v1 decision (through 2026-05). Superseded by §1 above
> on 2026-05-12 per Mike's template walkthrough Q2 lock (rf2-dolpf).
> Retained for historical record; do not act on it.

**Decision (retired).** The template was a clj-new template, not a
deps-new template.

**Why clj-new (at the time).**

- Lineage: v1's `day8/re-frame-template` was lein-template; clj-new
  was its direct successor — same declarative Mustache shape, same
  audience, same "one command, working app" promise.
- Maturity: clj-new was mature; the Mustache substitution model was
  widely understood; resource templates were easy to inspect.
- Tooling-team portability: clj-new was maintained by sean-corfield;
  the artefact was a known quantity.

**Why the flip.** See §1 above. In short: the locked v1 flag set
(substrate × Story × Tailwind × SSR) materially exceeded what
Mustache-only conditional emission expresses cleanly, and deps-new's
programmatic `:template-fn` was the cleaner fit. The git-coord
distribution decision is independent of deps-new-vs-clj-new but was
bundled into the same migration for release-machinery simplification.
(The flag set has since been retired — §10 — which leaves the
programmatic body carrying the substrate arm alone.)

## Retired — `:edn-args`-not-top-level

> This was a v1 implementation finding (clj-new era). Superseded by
> deps-new's native top-level k/v args (see §1 / API.md §Top-level
> k/v vs `:edn-args`). Retained for historical record; do not act
> on it.

**Decision (retired).** Under clj-new, the substrate selector rode
on `:edn-args`:

```bash
# clj-new era — superseded
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'
```

Not the more obvious top-level form, because clj-new's harness
stripped unknown top-level args before classloading the template's
entry fn.

**Why retired.** deps-new does not strip top-level args. The
`:edn-args` workaround is gone; substrate selection is a direct
top-level k/v argument (`:substrate :uix`) under the current
shape. The v1 finding was a clj-new-specific harness constraint
that no longer applies.

## Cross-references

- [000-Vision.md](000-Vision.md) — goals and non-goals (one selector,
  nothing pre-wired, TypeScript port reserved).
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the
  current shipped variants + how a substrate is added.
- [002-Generated-Shape.md](002-Generated-Shape.md) — the manifest,
  the resource tree and the substitution variables.
- [Principles.md](Principles.md) — the design principles these
  decisions implement.
- [API.md](API.md) — the consolidated public surface.
- [`tools/README.md`](../../README.md) — the per-tool spec/ folder
  convention (rf2-bfax).
