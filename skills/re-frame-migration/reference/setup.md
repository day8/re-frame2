# setup

Operational detail for **M-0 — the dep-coord swap**. This is the precondition for every other rule. Apply this leaf's content first; verify the project compiles; *then* sweep for breakage.

## Contents

- The coord swap (M-0)
- Per-build-tool shapes
- Picking the substrate-adapter artefact
- Discovering the current VERSION
- The pay-as-you-go artefact split (M-27 through M-32)
- Edge cases

---

## The coord swap (M-0)

v1 ships as `re-frame/re-frame`. v2 ships as a **pair** of artefacts at the same VERSION:

- `day8/re-frame2` — the core (registry, drain, dispatch, subscribe, fx, the substrate-adapter contract).
- `day8/re-frame2-<substrate>` — the substrate adapter (`-reagent`, `-uix`, or `-helix`). v1 codebases use Reagent universally, so default to `day8/re-frame2-reagent`.

The two artefacts ship in lockstep — every adapter artefact is versioned identically to core. Mixing versions across them is unsupported.

The `re-frame.core` namespace name and your `(:require [re-frame.core :as rf])` lines are **unchanged**. Only the dep coord moves.

## Per-build-tool shapes

### `deps.edn` (tools.deps)

```clojure
;; Before
{:paths ["src"]
 :deps  {re-frame/re-frame {:mvn/version "1.4.5"}}}

;; After
{:paths ["src"]
 :deps  {day8/re-frame2         {:mvn/version "<VERSION>"}
         day8/re-frame2-reagent {:mvn/version "<VERSION>"}}}
```

### `project.clj` (Leiningen)

```clojure
;; Before
:dependencies [[re-frame "1.4.5"]]

;; After
:dependencies [[day8/re-frame2         "<VERSION>"]
               [day8/re-frame2-reagent "<VERSION>"]]
```

### `shadow-cljs.edn`

```clojure
;; Before
{:dependencies [[re-frame/re-frame "1.4.5"]]}

;; After
{:dependencies [[day8/re-frame2         "<VERSION>"]
                [day8/re-frame2-reagent "<VERSION>"]]}
```

If the project's `shadow-cljs.edn` reads deps from `deps.edn` (the default; `:deps true` or unspecified), edit `deps.edn` only — `shadow-cljs.edn` will pick up the change.

### `bb.edn` (Babashka)

```clojure
;; Before
{:deps {re-frame/re-frame {:mvn/version "1.4.5"}}}

;; After
{:deps {day8/re-frame2         {:mvn/version "<VERSION>"}
        day8/re-frame2-reagent {:mvn/version "<VERSION>"}}}
```

**Both `day8/re-frame2-*` lines get the same `<VERSION>` value.** The lockstep contract.

## Coords to detect

v1 has shipped under three coord forms over time — match any of them:

```clojure
re-frame/re-frame {:mvn/version "1.x.x"}     ; deps.edn / shadow-cljs.edn — current canonical form
re-frame          {:mvn/version "1.x.x"}     ; deps.edn / shadow-cljs.edn — older shorter form
[re-frame "1.x.x"]                            ; project.clj — Lein vector form
```

All three become `day8/re-frame2` + the matching adapter artefact.

## Picking the substrate-adapter artefact

| Codebase shape | Adapter to add |
|---|---|
| `:require [reagent.core ...]` anywhere in the source tree | `day8/re-frame2-reagent` |
| `:require [uix.core ...]` and Reagent has been removed | `day8/re-frame2-uix` |
| `:require [helix.core ...]` and Reagent has been removed | `day8/re-frame2-helix` |
| No view layer (a backend-only re-frame app, server-side only) | `day8/re-frame2-reagent` is still the safe default; the adapter is lightweight |

If the codebase has **both** Reagent and UIx requires (a phased substrate migration), pick whichever one drives the React root and add only that adapter — the other substrate's views become broken at runtime but that's a separate migration the author has to drive.

## Discovering the current VERSION

Three sources, in order of authority:

1. **`VERSION` file** in the re-frame2 repo (`https://github.com/day8/re-frame2/blob/main/VERSION`) — the string used for the next release.
2. **`CHANGELOG.md`** (`https://github.com/day8/re-frame2/blob/main/CHANGELOG.md`) — released versions with summaries; pick the most recent non-Unreleased entry.
3. **GitHub releases page** (`https://github.com/day8/re-frame2/releases`) — the latest tag is `v<VERSION>`.

If the author wants the bleeding edge, use a `:git/url` + `:git/sha` coord instead of `:mvn/version`. Niche; default to released `:mvn/version`.

**Never invent a version.** If the network is unreachable, ask the author to paste the current VERSION rather than guess.

**If no released v2 version exists yet** (pre-publication): leave the dep alone, do not apply any other migration rules, and flag the situation in the report — the author must update the coord manually once a release lands, then re-run the migration.

## The pay-as-you-go artefact split (M-27 through M-32)

re-frame2 splits seven per-feature artefacts out of core. **Add them only when the codebase actually uses the feature.** Do not add them defensively.

| Artefact | Add when codebase uses... |
|---|---|
| `day8/re-frame2-schemas` | `reg-app-schema`, `reg-event-schema`, or `:spec` keys in registration metadata (M-27) |
| `day8/re-frame2-machines` | `reg-machine` (M-28) |
| `day8/re-frame2-routing` | `reg-route` or dispatches `:rf.route/*` events (M-29) |
| `day8/re-frame2-flows` | `reg-flow` (M-30) — also where v1's `on-changes` interceptor migrates to |
| `day8/re-frame2-http` | `[:rf.http/managed ...]` as an `:fx` entry, or `:rf.http/managed` as a child machine (M-31) |
| `day8/re-frame2-ssr` | `render-to-string` server-side (M-32) |
| `day8/re-frame2-epoch` | `epoch-history`, `restore-epoch`, or transitively via `re-frame-pair2` (no M-rule; pull only if directly used) |

In practice: most v1 codebases use **none** of these, because none of these features exist in v1. State machines / flows / managed-HTTP / SSR are v2 additions. v1 codebases doing equivalent things by hand stay doing them by hand post-migration; you do **not** rewrite those into the new artefacts as part of the required migration. (Adopting them is opt-in; see the `O-N` rules.)

The one exception is `-flows` — v1's `on-changes` interceptor (one of the removed five, per M-21) migrates to `reg-flow`, so if the codebase used `on-changes`, add `day8/re-frame2-flows` at the same time you apply M-21.

## Edge cases

**`shadow-cljs.edn` with `:dependencies` AND `deps.edn` with `:deps` — which wins?** shadow-cljs reads from both; `:dependencies` in `shadow-cljs.edn` is additive. Update whichever currently holds the `re-frame/re-frame` coord — that's the one in scope. If both hold it (rare), update both.

**Lein with `:profiles` overlays.** If the project pins `re-frame` in `:dependencies` and overrides it in `:profiles {:dev {:dependencies ...}}`, update both — the profile override would otherwise shadow the swap silently.

**`re-frame` as a transitive of another lib (`re-frame-fx`, `day8/re-frame-async-flow-fx`, etc.).** v1-built libs depend on `re-frame/re-frame`; their classpath will trip a coord conflict with `day8/re-frame2`. Two options:

1. Upgrade the lib to a re-frame2-compatible version if one exists.
2. Exclude `re-frame/re-frame` from the transitive (`:exclusions` in Lein, `:exclusions` in deps.edn) and let `day8/re-frame2` provide `re-frame.core`. This works because v2 keeps the `re-frame.core` namespace; the lib's `:require [re-frame.core :as rf]` lines resolve against v2 instead.

Flag this case in the report — the author owns the decision about whether to upgrade the transitive lib or to exclude.

**Per-feature artefact not yet published.** Same shape as M-0's "no v2 version" edge case: leave the dep alone, flag in the report, the author updates manually when the artefact lands.

---

**Stop after M-0.** Do not start sweeping for other M-rules until you've tried a compile and seen what — if anything — breaks. The expected result for most codebases is that the dep swap is the entire migration. Verify that before sweeping.
