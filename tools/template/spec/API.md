# Template — API

> The consolidated public surface. Every invocation form, every
> argument, every supported flag.

## Invocation

The template is invoked via clj-new's `create`:

```bash
clojure -X:project/new :template re-frame2 :name <coord> \
        [:edn-args '[:substrate <kw>]']
```

Where:

- `<coord>` is a group-qualified Clojure name — e.g. `acme/my-app`.
  `name` is required.
- `<kw>` is one of `:reagent` `:uix` `:helix`. Optional. Defaults
  to `:reagent`.

The `:project/new` alias is the standard clj-new alias. If it isn't
wired in `~/.clojure/deps.edn`, see [§Inline invocation](#inline-invocation)
below.

## Examples

```bash
# Reagent — canonical substrate (default)
clojure -X:project/new :template re-frame2 :name acme/my-app

# UIx
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'

# Helix
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :helix]'
```

## Args reference

### Top-level (clj-new harness)

| Arg | Required | Meaning |
|---|---|---|
| `:template` | yes | The template name. Must be `re-frame2` to invoke this template. |
| `:name` | yes | Group-qualified project coord. |
| `:edn-args` | no | Pass-through bag for template-specific args. See below. |
| `:to-dir` | no | Override the directory clj-new creates the project in. Defaults to a sibling of CWD named after `:name`'s artefact. |

### Template-specific (inside `:edn-args`)

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

Future toggles (`:include-10x?`, `:include-routing?`) would slot in
as additional `:edn-args` entries. None ship in v1.

## Why `:edn-args`

clj-new's `create` strips unknown top-level args before
classloading the template's entry fn — only its own well-known
keys (`:template`, `:name`, `:to-dir`, etc.) pass through directly.
`:edn-args` is the documented pass-through bag: its contents are
handed to the template's entry fn as `& args`, a flat
alternating-key-value sequence the template wraps in
`apply hash-map`.

This is documented in clj-new's README but surfaced in this
template as an implementation finding — see
[DESIGN-RATIONALE](DESIGN-RATIONALE.md) §edn-args-not-top-level.

## Standard `:project/new` alias

The invocation form assumes the standard clj-new alias is wired in
`~/.clojure/deps.edn` per
[clj-new's README](https://github.com/seancorfield/clj-new):

```clojure
{:aliases
 {:project/new {:extra-deps {com.github.seancorfield/clj-new
                             {:mvn/version "1.3.415"}}
                :exec-fn    clj-new/create
                :exec-args  {:template "app"}}}}
```

The `:exec-args {:template "app"}` default is harmless — `:template
re-frame2` on the invocation line overrides it.

## Inline invocation

If the `:project/new` alias isn't available, the equivalent inline
form is:

```bash
clojure -Sdeps '{:deps {com.github.seancorfield/clj-new
                        {:mvn/version "1.3.415"}}}' \
        -X clj-new/create :template re-frame2 :name acme/my-app
```

The same `:edn-args '[:substrate :uix]'` style applies.

## Local-development invocation

Until the alpha publish lands on Clojars, use the `:local/root`
route to exercise the template from a checkout of this repo:

```bash
clojure -Sdeps '{:deps {day8/clj-template.re-frame2
                        {:local/root "tools/template"}}}' \
        -X clj-new/create :template re-frame2 :name acme/my-app
```

This is the path the template's own JVM tests
(`test/clj/new/re_frame2_test.clj` and siblings) take internally —
they invoke the entry fn directly without the clj-new harness.

## Outputs

What lands in the user's directory: see
[002-Generated-Shape.md §What lands in the user's directory](002-Generated-Shape.md#what-lands-in-the-users-directory).

What console output to expect:

```
Generating a re-frame2 project called my-app — reagent substrate.
```

Or `— uix substrate.` / `— helix substrate.` per the selector.

## Errors

| Condition | Behaviour |
|---|---|
| `:substrate` not one of `#{:reagent :uix :helix}` | `ex-info` thrown; message names the valid set; `ex-data` carries `{:substrate <bad-value> :valid #{...}}`. |
| `:name` missing | clj-new's harness rejects before template is invoked. |
| `:name` not group-qualified | clj-new's harness rejects (`acme/my-app`, not `my-app`). |
| Target directory already exists | clj-new's harness aborts to avoid clobbering. |

## Cross-references

- [000-Vision.md](000-Vision.md) — what the tool is for.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — what
  each substrate variant looks like.
- [002-Generated-Shape.md](002-Generated-Shape.md) — what file
  tree gets emitted.
- [Principles.md §P4](Principles.md#p4--substrate-selection-via-edn-args)
  — the `:edn-args` selector design.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY this surface
  shape.
