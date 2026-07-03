# Template — API

> The consolidated public surface. Every invocation form, every
> argument, every supported flag.

## Invocation

The template is invoked via deps-new's `-Tnew create`. The template
arguments (`:name`, `:substrate`, `:include-story?`) are identical
across the pre-split and post-split paths; only the `:template` coord
+ the surrounding resolution differ.

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

The examples below use the post-split `io.github.day8/re-frame2-template`
coord. To run them against the **current** tree (pre-split), substitute
the `:local/root` invocation from
[Local-development invocation](#local-development-invocation) — the
template arguments (`:name`, `:substrate`, `:include-story?`) are the
same in both.

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

`:substrate` accepts only a keyword (or omission, which defaults
to `:reagent`). Anything else — string, symbol, number, … — throws
with a clear message naming the valid set. See
[DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for why the earlier
forgiving-input posture was tightened (rf2-h0imw).

`:include-story?` accepts `true` / `false` / `nil`; anything else
throws. The branching exception is justified in
[`000-Vision.md`](000-Vision.md) §Non-goals and
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §No-Story-yet — flags
are permitted when they enable optional shared scaffolding whose
absence would force the user into hand-wiring known idioms.

The v1 set is locked at three flags total (`:include-story?`,
`:css`, `:include-ssr?`) — all now live. `:css` (rf2-gthro
verification; wiring rf2-nxqcov) accepts `:tailwind` (Tailwind v4
scaffold) or nil (plain-CSS default); a bogus value fails closed
with `:rf.error/template-bad-css-flag`. `:include-ssr?` (rf2-0m5ea
validation; wiring rf2-675qdb) accepts `true` / `false` / `nil`.
There are no reserved-but-unimplemented flags today; the fail-closed
`reserved-flag-gates` list (empty) stays as the home for any future
one — passing a reserved flag would throw
`:rf.error/template-unsupported-flag` (the flag and its gating bead
named in the message). Any other unrecognised template key,
including a typo of a live flag (e.g. `:include-story` for
`:include-story?`), fails closed with
`:rf.error/template-unknown-flag`. The gate distinguishes template
keys from deps-new's own harness keys via a pinned allowlist (the
deps-new coord is pinned in the template's `deps.edn`).
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

| Condition | Error keyword | Behaviour |
|---|---|---|
| `:substrate` not one of `#{:reagent :uix :helix}` | `:rf.error/template-substrate-must-be-one-of` (keyword arg outside the valid set) / `:rf.error/template-substrate-must-be-keyword` (non-keyword shape — string, symbol, number, …) | `ex-info` thrown; message names the valid set; `ex-data` carries `{:substrate <bad-value> :valid #{...}}`. |
| `:include-story?` not `true` / `false` / `nil` | `:rf.error/template-bad-include-story-flag` | `ex-info` thrown; message gives the offending value. |
| `:include-story? true` with non-Reagent substrate | `:rf.error/template-include-story-reagent-only` | `ex-info` thrown; message says "Reagent-only in v1" and names the chosen substrate. |
| `:css` not `:tailwind` / `nil` | `:rf.error/template-bad-css-flag` | `ex-info` thrown; message names the valid set (`#{:tailwind}`, or omit for the plain-CSS default) and the offending value. Fails closed. |
| Reserved-but-unimplemented flag passed (none today) | `:rf.error/template-unsupported-flag` | `ex-info` thrown; message names the flag and its gating bead. Fails closed — does **not** silently scaffold a vanilla app. The v1 flags (`:include-story?` / `:css` / `:include-ssr?`) are all live; `reserved-flag-gates` (empty) is the home for any future reserved flag. |
| Unknown template flag (incl. typos like `:include-story`, `:include-stories?`) | `:rf.error/template-unknown-flag` | `ex-info` thrown; message lists the unknown key(s) and the accepted flag set. Distinguished from deps-new harness keys via a pinned allowlist. |
| `:name` missing | n/a (deps-new) | deps-new's harness rejects before template is invoked. |
| `:name` not group-qualified | n/a (deps-new) | **Not rejected.** deps-new doubles a bare name (`my-app` → the symbol `my-app/my-app`), so the generated namespace is `my-app.my-app` and the source nests under `src/my_app/my_app/`. Always pass a group-qualified `:name` (`acme/my-app`); an unqualified one scaffolds, but with a doubled, almost-certainly-unintended namespace. |
| Target directory already exists | n/a (deps-new) | deps-new's harness aborts to avoid clobbering (unless `:overwrite` is passed). |

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
