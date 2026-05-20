# Template — API

> The consolidated public surface. Every invocation form, every
> argument, every supported flag.

## Invocation

The template is invoked via deps-new's `-Tnew create`:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name <coord> \
        [:substrate <kw>] \
        [:include-story? <bool>]
```

Where:

- `<coord>` is a group-qualified Clojure name — e.g. `acme/my-app`.
  `:name` is required.
- `<kw>` is one of `:reagent` `:uix` `:helix`. Optional. Defaults
  to `:reagent`.

The `-Tnew` tool is the standard deps-new tool. See
[deps-new's README](https://github.com/seancorfield/deps-new#installation)
for one-time install.

## Examples

```bash
# Reagent — canonical substrate (default)
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app

# UIx
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix

# Helix
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :helix

# Reagent with the Story playground scaffold
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :include-story? true

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
| `:substrate` | no | One of `:reagent` `:uix` `:helix`. | `:reagent` |
| `:include-story?` | no | When `true`, scaffolds the Story playground alongside the live app — adds the `day8/re-frame2-story` coord, emits `src/.../stories.cljs`, and swaps the entry `core.cljs` for the hash-routing `core_with_stories.cljs` variant. **Reagent only in v1** — non-Reagent substrates throw a clear error (UIx + Helix follow once those variants' app-shells catch up to Reagent's). | `false` |

`:substrate` accepts a keyword, a string (with or without leading
`:`), or a symbol. The template coerces to a keyword internally.
Anything not in the valid set throws.

`:include-story?` accepts `true` / `false` / `nil`; anything else
throws. The branching exception is justified in
[`000-Vision.md`](000-Vision.md) §Non-goals and
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §No-Story-yet — flags
are permitted when they enable optional shared scaffolding whose
absence would force the user into hand-wiring known idioms.

Future toggles (`:css`, `:include-ssr?`) slot in here. The v1 set
is locked at three flags total (`:include-story?`, `:css`,
`:include-ssr?`); the latter two are gated on rf2-gthro (Tailwind
v4 verification) and rf2-0m5ea (SSR validation) respectively.
Resist further proliferation — every additional flag requires
explicit DESIGN-RATIONALE justification.

## Top-level k/v vs `:edn-args`

deps-new takes template arguments as **top-level k/v pairs** after
`:name`. There is no `:edn-args` pass-through bag — the clj-new-era
plumbing rationale (see [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)
§Retired §`:edn-args`-not-top-level) is obsolete.

`data-fn` in
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)
reads the args directly off the `data` map deps-new hands it.

## Local-development invocation

Until the dedicated `day8/re-frame2-template` repo is published
(rf2-dolpf §4 — repo split), use the `:local/root` route to exercise
the template from a checkout of this repo:

```bash
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
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
Generated a re-frame2 application acme/my-app (reagent substrate).
Next steps:
  cd my-app
  npm install
  npx shadow-cljs watch app
Then open http://localhost:8280
```

The substrate name and an optional `(with Story playground)` tag
mirror the chosen `:substrate` + `:include-story?` flags.

## Errors

| Condition | Behaviour |
|---|---|
| `:substrate` not one of `#{:reagent :uix :helix}` | `ex-info` thrown; message names the valid set; `ex-data` carries `{:substrate <bad-value> :valid #{...}}`. |
| `:include-story?` not `true` / `false` / `nil` | `ex-info` thrown; message gives the offending value. |
| `:include-story? true` with non-Reagent substrate | `ex-info` thrown; message says "Reagent-only in v1" and names the chosen substrate. |
| `:name` missing | deps-new's harness rejects before template is invoked. |
| `:name` not group-qualified | deps-new's harness rejects (`acme/my-app`, not `my-app`). |
| Target directory already exists | deps-new's harness aborts to avoid clobbering (unless `:overwrite` is passed). |

## Cross-references

- [000-Vision.md](000-Vision.md) — what the tool is for.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — what
  each substrate variant looks like.
- [002-Generated-Shape.md](002-Generated-Shape.md) — what file
  tree gets emitted.
- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) —
  the migration plan that established the current shape.
- [Principles.md §P4](Principles.md#p4--substrate-selection-via-top-level-kv)
  — the substrate-selector design.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY this surface
  shape.
