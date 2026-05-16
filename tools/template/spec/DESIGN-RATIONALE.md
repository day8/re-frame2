# Template — Design Rationale

> WHY each major decision was made. The audit trail behind the
> [000-Vision](000-Vision.md) / [001-Substrate-Variants](001-Substrate-Variants.md)
> / [002-Generated-Shape](002-Generated-Shape.md) / [Principles](Principles.md)
> / [API](API.md) shape. Beads referenced as rf2-xxxx.

## §1 — clj-new over deps-new

**Decision.** This template is a [clj-new](https://github.com/seancorfield/clj-new)
template, not a [deps-new](https://github.com/seancorfield/deps-new)
template.

**Alternatives considered.**

| Option | What it is | Outcome |
|---|---|---|
| A — clj-new | The modern successor to lein-template; resource templates with Mustache substitution; consumed via `clojure -X:project/new`. | **Selected.** |
| B — deps-new | Newer than clj-new; similar shape but with a different config style and a slightly more programmatic surface. | Rejected. |
| C — Small CLI / `bb` script with interactive prompts | A bb-based wizard ("include Story? include 10x? Reagent or UIx or Helix?"). | Rejected for v1. |

**Why clj-new.**

- **Lineage.** v1's `day8/re-frame-template` was lein-template;
  clj-new is its direct successor — same declarative Mustache shape,
  same audience, same "one command, working app" promise. A v1
  user knows the workflow.
- **Maturity.** clj-new is mature; the Mustache substitution model
  is widely understood by the Clojure community; resource
  templates are easy to inspect and edit.
- **Tooling-team portability.** The Clojure tooling team
  (sean-corfield) maintains clj-new. The artefact is a known
  quantity for downstream consumers.

**Why not deps-new.** Equally viable on a technical axis. clj-new
wins on familiarity: more prior art, the v1 template's audience
knows the workflow. There is no second-class capability gap that
would favour deps-new here.

**Why not a bb-based wizard (option C).** More flexible for
branching choices ("include Story? include 10x?") but:

- Heavier-weight for the user (extra runtime, extra prompts).
- Outside Clojure norms — the community expectation for "scaffold
  a new project" is clj-new / lein new, not a bespoke script.
- v1's branching surface is **one flag** (substrate). clj-new's
  substitution capability is more than adequate.

Option C is reserved for the day branching choices materially
exceed clj-new's substitution capability — see the "future
toggles" comment in `src/clj/new/re_frame2.clj` and
[001-Substrate-Variants.md §Future variants](001-Substrate-Variants.md#future-variants).

## §2 — `:edn-args` over a top-level `:substrate` key

**Decision.** The substrate selector rides on `:edn-args`:

```bash
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'
```

Not the more obvious:

```bash
# Doesn't work — clj-new strips unknown top-level args
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :substrate :uix
```

**Why.**

This was an **implementation finding**. The first cut of the
template wired `:substrate` as a top-level arg. When tested
end-to-end via `clojure -X:project/new`, the substrate arg never
reached the template's entry fn — clj-new's `create` was stripping
it.

Reading clj-new's harness confirmed: `create` strips unknown
top-level args before classloading the template. The documented
pass-through bag is `:edn-args`, which clj-new hands through to
the template's entry fn as `& args`. The template wraps the args
in `apply hash-map` and reads `:substrate` from there.

**The capture in spec form.** This is documented in clj-new's
README but is non-obvious from the template-author side until you
hit it. Filing it in DESIGN-RATIONALE (rather than just fixing the
code) makes the constraint visible to the next person who reads
the spec — and signals that any future `:substrate`-adjacent flag
must go through the same `:edn-args` plumbing.

**Forgiving coercion.** Once the selector reaches the template,
the value is coerced from keyword / string / symbol uniformly
(see [001-Substrate-Variants.md §Substrate coercion](001-Substrate-Variants.md#substrate-coercion)).
The clj-new ecosystem hands keywords through inconsistently across
shells; the template tolerates the variants.

## §3 — Three substrates in v1 (Reagent / UIx / Helix)

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
- **CI exercises all three.** The three-file JVM test suite
  (`test/clj/new/re_frame2_test.clj` plus the
  `template_emission_test.clj` static-parse and
  `emitted_test_run_test.clj` behavioural slices) runs every
  substrate end-to-end, so adding a substrate has a low maintenance
  tax.

Reagent remains the default — it's the canonical substrate every
re-frame example targets first. UIx and Helix are equal citizens,
not afterthoughts.

## §4 — Counter as the canonical example

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

## §5 — No-Story-yet (default), opt-in via `:include-story?` (rf2-t009p)

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
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:include-story? true]'
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
should absorb. No other branching flags currently exist.

## §6 — Pin source-of-truth in one place

**Decision.** `:rf2-version`, `:shadow-version`, `:react-version`
are defined as inline literals in `src/clj/new/re_frame2.clj`'s
entry fn — not in an EDN data file, not in a Clojars-fetched
manifest, not derived from anything.

**Why.**

- **Releasability.** Bumping the template is a one-file edit. CI
  cuts a new template jar; the new defaults flow to consumers on
  next invocation.
- **Visibility.** A reader of the entry fn sees the pins inline,
  alongside the substrate decode and the file emit list. No
  indirection through "go check this EDN data file."
- **Lockstep with `implementation/package.json`.** The pins match
  what the reference implementation's CI smoke-tests. Bumping
  shadow-cljs in `implementation/package.json` triggers a
  matching bump here — part of the per-release checklist.

The alternative — deriving pins at template-build-time from
`implementation/package.json` — was considered and rejected. It
would tie the template's build to the implementation tree's
state at template-publish time, with no win in correctness over
"manually bump in lockstep."

## §7 — Resource tree shape (shared/ + per-substrate/)

**Decision.** The template's resource tree splits into:

```
src/clj/new/re_frame2/
├── shared/          ; identical across substrates
└── <substrate>/     ; substrate-specific
```

Not a flat layout with substrate-prefixed filenames, not a
per-variant top-level tree (no shared tree at all).

**Why.**

- **Substrate-agnostic shell is the right cut.** Events, subs,
  host HTML, README, .gitignore are substrate-portable by design.
  Splitting them out into `shared/` makes that visible. A reader
  of `tools/template/src/clj/new/re_frame2/` sees the architecture
  immediately.
- **Adding a substrate is "drop a sub-tree."** Future substrates
  (reagent-slim, SSR variants) plug in by adding a sibling of
  `reagent/`, `uix/`, `helix/`. Nothing in `shared/` needs to
  change.
- **clj-new's renderer doesn't care.** clj-new's `renderer` keys
  off the top-level template name and treats internal slashes as
  classpath subpaths. The shape is purely organisational.

## §8 — JVM test (no clj-new harness in the test path)

**Decision.** The JVM tests (`test/clj/new/re_frame2_test.clj`
and its two siblings, `template_emission_test.clj` and
`emitted_test_run_test.clj`) invoke the template's entry fn
directly, bypassing clj-new's `create` harness. The layered shape:
generate to a tmp dir, walk the file tree, parse-and-audit the
emitted cljs against framework surface, and optionally compile +
run the emitted tests via shadow-cljs.

**Why.**

- **Fast.** No classloading clj-new, no Maven resolution of
  template coords, no harness setup. The test runs in seconds.
- **Hermetic.** Doesn't depend on the test machine having a
  particular `:project/new` alias configured.
- **The clj-new harness layer is tested by clj-new itself.** What
  the template is responsible for is the entry fn's behaviour:
  emit the right files, with the right substitutions, throw on
  bad input. The test exercises that surface.

The `clojure -P` deps-parse is a "best-effort" check — it confirms
the generated `deps.edn` is well-formed and that re-frame2 coords
resolve. It is gated behind `RF2_TEMPLATE_DEPS_RESOLVE=1` (CI sets
this; local fast-loop default-off so a missing alpha publish on
Clojars doesn't fail every run). The behavioural compile+run slice
(`emitted_test_run_test.clj`) is similarly opt-in via
`RF2_TEMPLATE_RUN_EMITTED_TESTS=1`. Deps-parse and behavioural-run
are nice-to-have signals; the harder contract is the file-tree
shape + the static-parse framework-surface audit.

## §9 — Forgiving substrate coercion

**Decision.** `coerce-substrate` accepts keyword, string (with or
without leading `:`), symbol; throws on anything else.

**Why.**

- **The Clojure CLI ecosystem hands keywords through
  inconsistently across shells.** On some shells / OSes,
  `:substrate :uix` reaches the entry fn as the keyword `:uix`;
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

## Cross-references

- [000-Vision.md](000-Vision.md) — non-goals (Story bundling, SSR,
  TypeScript port).
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the
  current shipped variants + future variants surface.
- [002-Generated-Shape.md](002-Generated-Shape.md) — resource tree
  + substitution variables.
- [Principles.md](Principles.md) — the design principles these
  decisions implement.
- [API.md](API.md) — the consolidated public surface.
- [`tools/README.md`](../../README.md) — the per-tool spec/ folder
  convention (rf2-bfax).
