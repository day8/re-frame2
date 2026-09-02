# Template — API

> The consolidated public surface. The invocation, every argument,
> every error.

## Invocation

The template is invoked via deps-new's `-Tnew create`. Its arguments
(`:name`, `:substrate`) are identical across the pre-split and
post-split paths; only the `:template` coord + the surrounding
resolution differ.

> **Pre-split status (current):** the dedicated
> `github.com/day8/re-frame2-template` repo does not exist yet, so the
> published `io.github.day8/re-frame2-template` git-coord form is **not
> a viable path** (deps-new would clone the nonexistent external repo
> and fail to find the template body — see
> [005-Repo-Split.md §4](005-Repo-Split.md)). The working invocation
> today is the `:local/root` route — see
> [Local-development invocation](#local-development-invocation) below.
> The `io.github.*` shape documented here is the **post-split steady
> state**.

Post-split steady-state form:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name <coord> \
        [:substrate <kw>]
```

Where:

- `<coord>` is a group-qualified Clojure name — e.g. `acme/my-app`.
  `:name` is required.
- `:substrate <kw>` is one of `:reagent` `:uix`. Optional. Defaults to
  `:reagent`.

That is the whole template-specific surface: one optional selector.

The `-Tnew` tool is the standard deps-new tool. See
[deps-new's README](https://github.com/seancorfield/deps-new#installation)
for one-time install.

## Examples

```bash
# Reagent — the default
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app

# UIx
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix

# Pinned to a specific release tag
clojure -Tnew create \
        :template io.github.day8/re-frame2-template#template-v0.0.1.alpha \
        :name acme/my-app
```

## Args reference

### Top-level (deps-new harness)

| Arg | Required | Meaning |
|---|---|---|
| `:template` | yes | The template git-coord. The canonical value is `io.github.day8/re-frame2-template`. May carry a tag suffix (`…#template-v0.0.1.alpha`) to pin a specific release. |
| `:name` | yes | Group-qualified project coord. |
| `:target-dir` | no | Override the directory deps-new creates the project in. Defaults to a sibling of CWD named after `:name`'s artefact (e.g. `:name acme/my-app` → `./my-app/`). |

### Template-specific (top-level k/v)

| Arg | Required | Meaning | Default |
|---|---|---|---|
| `:substrate` | no | One of `:reagent` `:uix`. Selects the view library the emitted `deps.edn` / `core.cljs` / `views.cljs` target. | `:reagent` |

`:substrate` accepts only a keyword (or omission, which defaults
to `:reagent`). Anything else — string, symbol, number, … — throws
with a clear message naming the valid set. See
[DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for why inputs fail closed.

Any other template key — a typo of `:substrate`, or one of the three
retired feature flags (Story, SSR, Tailwind CSS) — is **unknown** and
fails closed with `:rf.error/template-unknown-flag` before any file is
written. There are no aliases, deprecation warnings or compatibility
paths for the retired flags: git history is the migration record. The
gate distinguishes template keys from deps-new's own harness keys via a
pinned allowlist (the deps-new coord is pinned in the template's
`deps.edn`).

## Top-level k/v vs `:edn-args`

deps-new takes template arguments as **top-level k/v pairs** after
`:name`. There is no `:edn-args` pass-through bag — the clj-new-era
plumbing rationale (see [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)
§Retired §`:edn-args`-not-top-level) is obsolete.

`data-fn` in
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)
reads the args directly off the `data` map deps-new hands it.

## Local-development invocation

Until the dedicated `day8/re-frame2-template` repo is published, use the
`:local/root` route against a checkout of this repo. A `:local/root` is
resolved against the COMMAND's working directory, so name the checkout
absolutely — the relative `"tools/template"` form only works from the
monorepo root, and from anywhere else fails with `Local lib
day8/re-frame2-template not found`:

```bash
# <RE_FRAME2> = absolute path of your re-frame2 checkout,
# e.g. /home/you/code/re-frame2 or C:/Users/you/code/re-frame2
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "<RE_FRAME2>/tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
                     :name acme/my-app
```

Note: `day8/re-frame2-template` (not `io.github.day8/re-frame2-template`)
because the `io.github.*` prefix triggers deps-new's auto-git-clone
before classpath lookup, and the published repo doesn't exist yet.
The §4 split flips the on-disk path so the steady-state invocation
(`:template io.github.day8/re-frame2-template`) resolves via the
git-clone.

## Outputs

What lands in the user's directory: see
[002-Generated-Shape.md §What lands in the user's directory](002-Generated-Shape.md#what-lands-in-the-users-directory).

What console output to expect:

```
Generated a re-frame2 application acme/my-app (Reagent).
Next steps:
  cd my-app
  npm install
  npx shadow-cljs watch app
Then open http://localhost:8280
Until day8/re-frame2 is published, point its coordinates in deps.edn at a checkout with :local/root before the first watch.
```

The parenthetical is the substrate's display name. The last line is
the pre-publish caveat: the emitted `day8/re-frame2*` coordinates name
a release that is not on Clojars yet, so the first `watch` resolves
only once they are rewritten to `:local/root` paths into a checkout —
the same rewrite the template's own behavioural tier performs.

## Errors

| Condition | Error keyword | Behaviour |
|---|---|---|
| `:substrate` not one of `#{:reagent :uix}` | `:rf.error/template-substrate-must-be-one-of` (keyword arg outside the valid set) / `:rf.error/template-substrate-must-be-keyword` (non-keyword shape — string, symbol, number, …) | `ex-info` thrown; message names the valid set; `ex-data` carries `{:substrate <bad-value> :valid #{...}}`. |
| Unknown template key — a typo, or one of the three retired feature flags (Story, SSR, Tailwind CSS) | `:rf.error/template-unknown-flag` | `ex-info` thrown before any file is written; message lists the unknown key(s) and the accepted set (`#{:substrate}`); `ex-data` carries `:unknown` and `:accepted`. |
| The artefact segment of `:name` is not an npm package name once lowercased (a leading `.` or `_`, a character outside `a-z 0-9 - _ . ~`, or over 214 characters) | `:rf.error/template-npm-name-invalid` | `ex-info` thrown before any file is written; message names the segment and the rule; `ex-data` carries `:main` and `:npm-name`. |
| `:name` missing | n/a (deps-new) | deps-new's harness rejects before the template is invoked. |
| `:name` not group-qualified | n/a (deps-new) | **Not rejected.** deps-new doubles a bare name (`my-app` → the symbol `my-app/my-app`), so the generated namespace is `my-app.my-app` and the source nests under `src/my_app/my_app/`. Always pass a group-qualified `:name` (`acme/my-app`); an unqualified one scaffolds, but with a doubled, almost-certainly-unintended namespace. |
| Target directory already exists | n/a (deps-new) | deps-new's harness aborts to avoid clobbering (unless `:overwrite` is passed). |

## Cross-references

- [000-Vision.md](000-Vision.md) — what the tool is for.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — what
  each substrate variant looks like.
- [002-Generated-Shape.md](002-Generated-Shape.md) — what file
  tree gets emitted.
- [Principles.md §P4](Principles.md#p4--substrate-selection-via-top-level-kv)
  — the substrate-selector design.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY this surface
  shape.
