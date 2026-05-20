# Template — Design Rationale

> WHY each major decision was made. The audit trail behind the
> [000-Vision](000-Vision.md) / [001-Substrate-Variants](001-Substrate-Variants.md)
> / [002-Generated-Shape](002-Generated-Shape.md) / [Principles](Principles.md)
> / [API](API.md) shape. Beads referenced as rf2-xxxx.

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

**Why deps-new (over clj-new).**

- **Programmatic body matches the v1 locked flag set.** Three opt-in
  flags (`:include-story?`, `:css`, `:include-ssr?`) plus a three-way
  substrate axis. Each adds conditional file selection (different
  `deps.edn`, different `core.cljs`). deps-new's `:template-fn` is a
  plain Clojure fn that builds the `:transform` vector at runtime —
  the `case` / `cond` lives in CLJ source, where it can be read,
  refactored, and tested directly. clj-new's Mustache
  `{{#flag}}…{{/flag}}` blocks express the same shape with less
  visibility (the branching is scattered across template resources
  instead of localised in one fn).
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
- **Independent release cadence.** A template-only doc polish or a
  new opt-in flag doesn't warrant republishing the framework. The
  template's own `tools/template/VERSION` file drives the
  `template-v…` tag sequence independently of the framework-wide
  repo-root `VERSION` that drives the `v…` tag sequence (see
  [`.github/workflows/template-release.yml`](../../../.github/workflows/template-release.yml)).

**Why not a bb-based wizard (option C).** More flexible for
branching choices ("include Story? include 10x?") but:

- Heavier-weight for the user (extra runtime, extra prompts).
- Outside Clojure norms — the community expectation for "scaffold
  a new project" is deps-new / clj-new / lein new, not a bespoke
  script.
- v1's branching surface is **three flags** (substrate +
  include-story? + the two deferred flags). deps-new's programmatic
  template-fn is more than adequate.

Option C remains reserved for the day branching choices materially
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
- **CI exercises all three.** The JVM test suite under
  `test/day8/re_frame2_template/` runs every substrate end-to-end
  across template-shape, static-parse, and behavioural slices, so
  adding a substrate has a low maintenance tax.

Reagent remains the default — it's the canonical substrate every
re-frame example targets first. UIx and Helix are equal citizens,
not afterthoughts.

## §3 — Counter as the canonical example

**Decision.** Every variant emits a counter. Not a TodoMVC, not a
hello-world, not a routing-demo.

**Why.**

- **rf2-2kzw throughline.** [Guide chapter 03 — Your first
  app](../../../docs/guide/03-your-first-app.md) walks through a
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

## §4 — No-Story-yet (default), opt-in via `:include-story?` (rf2-t009p)

**Decision.** The template does **not** pre-wire
[`tools/story/`](../../story/) on the default path. The
`:include-story?` flag is the opt-in on-ramp — Reagent-only in
v1; UIx + Helix variants follow once their adapter coverage
matches Reagent's.

**Why default-off.**

- **Story is post-1.0 but still moving.** Stage 8 (rf2-c9mm)
  landed; subsequent stages tune the authoring surface. Default-on
  would force every scaffolded app to track every change to the
  seven `reg-*` macros and the variant id grammar.
- **Cost of an unwanted dep.** Users who don't reach for Story
  shouldn't pay for it in their `deps.edn`, their bundle
  isolation grep set, or their docs surface.

**Why opt-in shape.**

- **The exemplar exists.** [`tools/story/testbeds/counter_with_stories/`](../../../tools/story/testbeds/counter_with_stories/)
  is the canonical shape — the scaffold mirrors its file layout
  (entry-fn hash-routes between `#/` and `#/stories`, dedicated
  `stories.cljs` that fires the `reg-*` calls).
- **Hand-wiring it from cold is friction.** A first-time Story
  user has to read the exemplar end-to-end, port the routing
  shape, decide which `reg-*` macros to invoke, and remember the
  `day8/re-frame2-story` coord. One flag collapses that into a
  scaffolded baseline they can edit.
- **Reagent first.** v1 ships Reagent only — matching Story's own
  UI-shell substrate and the exemplar's substrate. UIx + Helix
  variants land when Story's substrate-agnostic seams (spec/007
  §Substrate constraints) cover them end-to-end.

**Invocation.**

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :include-story? true
```

Adds `day8/re-frame2-story` to `deps.edn` (lockstep with the core
coord version), emits `stories.cljs` next to `events.cljs` /
`subs.cljs` / `views.cljs`, swaps `core.cljs` for the hash-routing
variant, and wires `npm run story` as an alias for `shadow-cljs
watch app` — visit `#/stories` for the playground, `#/` for the
live app.

This is the branching-flag exception called out in
[000-Vision §Non-goals](000-Vision.md#non-goals): it's permitted
because the alternative (hand-wiring the seven `reg-*` calls and
the hash routing from the exemplar) is real friction the scaffold
should absorb. `:css :tailwind` (rf2-gthro) and `:include-ssr?`
(rf2-0m5ea) are the other two locked flags for the same reason;
all three sit behind their own gating beads.

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
├── _uix/                       ; substrate-specific
└── _helix/                     ; substrate-specific
```

Not a flat layout with substrate-prefixed filenames, not a
per-variant top-level tree (no shared tree at all).

**Why.**

- **Substrate-agnostic shell is the right cut.** Events, subs,
  host HTML, README, .gitignore are substrate-portable by design.
  Splitting them out under `_shared/` makes that visible. A reader
  of `resources/day8/re_frame2_template/` sees the architecture
  immediately.
- **Adding a substrate is "drop a sub-tree."** Future substrates
  (reagent-slim, SSR variants) plug in by adding a sibling of
  `_reagent/`, `_uix/`, `_helix/`. Nothing in `_shared/` needs to
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
generate to a tmp dir, walk the file tree, parse-and-audit the
emitted cljs against framework surface, and optionally compile +
run the emitted tests via shadow-cljs.

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

The `clojure -P` deps-parse smoke is a "best-effort" check — it
confirms the generated `deps.edn` is well-formed and that
re-frame2 coords resolve. It is gated behind
`RF2_TEMPLATE_DEPS_RESOLVE=1` (CI sets this; local fast-loop
default-off so a missing alpha publish on Clojars doesn't fail
every run). The behavioural compile+run slice
(`emitted_test_run_test.clj`) is similarly opt-in via
`RF2_TEMPLATE_RUN_EMITTED_TESTS=1`. Deps-parse and behavioural-run
are nice-to-have signals; the harder contract is the file-tree
shape + the static-parse framework-surface audit + the
version-lockstep guards.

## §8 — Forgiving substrate coercion

**Decision.** `coerce-substrate` accepts keyword, string (with or
without leading `:`), symbol; throws on anything else.

**Why.**

- **The Clojure CLI ecosystem hands keywords through
  inconsistently across shells.** On some shells / OSes,
  `:substrate :uix` reaches the template as the keyword `:uix`;
  on others, as the string `":uix"`; in some setups, with the
  leading colon stripped; occasionally as a symbol.
- **Coercion is cheap.** A six-line `cond` covers all four cases.
  No reason to make the user fight quoting.
- **Strict on the value set.** Any input that maps to a keyword
  outside `#{:reagent :uix :helix}` throws with a clear message
  naming the valid set. The user sees the typo and fixes it.

The inverse — strict on input shape, forgiving on value set
(silent fallback to default) — would be worse: a typo silently
delivers the Reagent variant instead of the requested UIx, and
the user finds out at `shadow-cljs watch app` time.

## Retired §clj-new-over-deps-new

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
(substrate × `:include-story?` × `:css` × `:include-ssr?`)
materially exceeds what Mustache-only conditional emission expresses
cleanly, and deps-new's programmatic `:template-fn` is the cleaner
fit. The git-coord distribution decision is independent of
deps-new-vs-clj-new but was bundled into the same migration for
release-machinery simplification.

## Retired §`:edn-args`-not-top-level

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

- [000-Vision.md](000-Vision.md) — non-goals (Story bundling default-off,
  SSR behind `:include-ssr?`, TypeScript port reserved).
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the
  current shipped variants + future variants surface.
- [002-Generated-Shape.md](002-Generated-Shape.md) — resource tree
  + substitution variables.
- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) — the
  migration plan that established the current shape (clj-new +
  Clojars → deps-new + git-coord).
- [Principles.md](Principles.md) — the design principles these
  decisions implement.
- [API.md](API.md) — the consolidated public surface.
- [`tools/README.md`](../../README.md) — the per-tool spec/ folder
  convention (rf2-bfax).
