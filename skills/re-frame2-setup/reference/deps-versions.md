# deps-versions

How to choose **which** re-frame2 artefacts to depend on, and **what version** to pin them at.

## Contents

- The lockstep contract
- The ten artefacts (and which two a greenfield project needs)
- Discovering the current VERSION
- `deps.edn` shape
- `package.json` shape
- When to add the optional per-feature artefacts

---

## The lockstep contract

re-frame2 ships **ten Maven artefacts in lockstep**: every artefact at the same VERSION, every release. Mixing versions across artefacts is unsupported — the runtime contract between core, adapters, and the per-feature surfaces is checked at boot time and bound to a single coordinated VERSION.

Picking a re-frame2 VERSION for your project means picking it once and using it everywhere a `day8/re-frame2-*` coordinate appears.

## The ten artefacts

| Artefact | Tier | When to add |
|---|---|---|
| `day8/re-frame2` | core | **Always.** Registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract. |
| `day8/re-frame2-reagent` | substrate | **Always (for a Reagent app).** The Reagent adapter map. |
| `day8/re-frame2-uix` | substrate | Instead of `-reagent` if you target UIx. |
| `day8/re-frame2-helix` | substrate | Instead of `-reagent` if you target Helix. |
| `day8/re-frame2-schemas` | per-feature | When you call `reg-app-schema` or `reg-event-schema`. |
| `day8/re-frame2-machines` | per-feature | When you call `reg-machine` or `create-machine-handler`. |
| `day8/re-frame2-routing` | per-feature | When you dispatch `:rf.route/*` events or register routes. |
| `day8/re-frame2-flows` | per-feature | When you call `reg-flow`. |
| `day8/re-frame2-http` | per-feature | When you dispatch `:rf.http/managed`. |
| `day8/re-frame2-ssr` | per-feature | When you call `render-to-string` server-side. |
| `day8/re-frame2-epoch` | per-feature | When you call `epoch-history` or `restore-epoch` (also pulled in transitively by `re-frame-pair2`). |

**Greenfield day-one minimum: just `day8/re-frame2` + `day8/re-frame2-reagent`.** Resist adding the others until the author writes code that actually uses them — they're optional by design so apps that don't use them don't pay the classpath cost.

## Discovering the current VERSION

re-frame2 versions look like `0.0.1.alpha`, `0.0.2.alpha`, `1.0.0`, etc. Three places to look, in order of authority:

1. **The repo's `VERSION` file** — `https://github.com/day8/re-frame2/blob/main/VERSION` is the single source of truth that release tags are cut from. The string in this file is the canonical VERSION for the next release.
2. **`CHANGELOG.md`** — `https://github.com/day8/re-frame2/blob/main/CHANGELOG.md` lists the released VERSIONs with summaries. Use the latest non-unreleased entry.
3. **The GitHub releases page** — `https://github.com/day8/re-frame2/releases` shows the tags. The latest tag is `v<VERSION>`.

If the author wants the latest released version, read `CHANGELOG.md` and pick the most recent versioned heading that isn't `Unreleased`. If they want the bleeding edge (e.g. they're chasing a fix that hasn't released yet), they may need to use a Git coordinate via `:git/url` + `:git/sha` instead of `:mvn/version`. That's a niche case; default to released `:mvn/version`.

**Never invent a version.** If you can't reach the network to look it up, ask the author to paste the current VERSION rather than guess.

## `deps.edn` shape

A minimal `deps.edn` for a greenfield re-frame2 project:

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure       {:mvn/version "1.11.1"}
         org.clojure/clojurescript {:mvn/version "1.11.132"}
         day8/re-frame2            {:mvn/version "<VERSION>"}
         day8/re-frame2-reagent    {:mvn/version "<VERSION>"}}}
```

Replace `<VERSION>` with the VERSION you discovered above. **Both `day8/re-frame2-*` lines get the same value.**

Notes on the Clojure / ClojureScript versions:
- The Clojure / ClojureScript versions in the example are what the re-frame2 repo's own examples build against (see `implementation/core/deps.edn`). You can use newer versions if shadow-cljs and Reagent support them; you can probably use slightly older ones too. Start with these.
- Reagent itself is **already declared as a dep of `day8/re-frame2-reagent`**, so you don't need to add `reagent/reagent` to your `:deps`. It'll be pulled in transitively at whatever version the adapter's `deps.edn` pins.

## `package.json` shape

re-frame2 itself ships no npm code — but Reagent depends on React, and shadow-cljs is the build tool. Minimum `package.json`:

```json
{
  "name": "your-app",
  "version": "0.0.0",
  "private": true,
  "devDependencies": {
    "shadow-cljs": "<latest-2.x>",
    "react": "<latest-18.x>",
    "react-dom": "<latest-18.x>"
  }
}
```

Discover the latest `shadow-cljs` / `react` / `react-dom` from npm: `npm view shadow-cljs version`, `npm view react version`, `npm view react-dom version`. As a sanity check, the re-frame2 repo's own `implementation/package.json` pins specific versions of each — those are known-good and a reasonable floor if `npm view` returns something newer that gives you trouble.

Reagent 2.x requires React 18+. Don't go below `react@18`.

Then `npm install`.

## When to add the optional per-feature artefacts

The seven per-feature artefacts are pay-as-you-go. Add them **at the moment** the author writes code that calls into them — not before.

| If the author writes... | Add to `deps.edn`... |
|---|---|
| `(rf/reg-app-schema ...)` or any `:schema` key in a `reg-*` opts map | `day8/re-frame2-schemas` |
| `(rf/reg-machine ...)` or `(rf/create-machine-handler ...)` | `day8/re-frame2-machines` |
| `(rf/reg-route ...)` or dispatches `:rf.route/handle-url-change` | `day8/re-frame2-routing` |
| `(rf/reg-flow ...)` | `day8/re-frame2-flows` |
| `[:rf.http/managed ...]` as an `:fx` entry | `day8/re-frame2-http` |
| Server-side `render-to-string` for SSR | `day8/re-frame2-ssr` |
| `(rf/epoch-history ...)` or `(rf/restore-epoch ...)` directly | `day8/re-frame2-epoch` |

(The `re-frame-pair2` skill pulls `-epoch` in transitively so live-inspection time-travel works; if the app uses `re-frame-pair2` but doesn't call the epoch surface itself, the author still doesn't need to add `-epoch` to their own `deps.edn` — the skill injects its runtime over nREPL.)

Each artefact registers its own load-time hooks on require, so the only extra step beyond the dep is a `:require` of the artefact's primary namespace from your entry ns or wherever you first call into its API. See the leaf for each feature in the main `re-frame2` skill for the canonical require shape.
