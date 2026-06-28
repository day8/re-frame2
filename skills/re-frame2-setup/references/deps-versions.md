# Dependencies & versions

How to choose **which** re-frame2 artefacts to depend on, and **what version** to pin them at.

## Contents

- The lockstep contract
- The eleven artefacts (and which ones a greenfield project needs)
- Choosing the coordinate (publication state decides the shape) — including discovering the current VERSION
- `deps.edn` shape
- `package.json` shape
- When to add the optional per-feature artefacts

---

## The lockstep contract

re-frame2 ships **eleven Maven artefacts in lockstep** (core + 7 per-feature + 3 per-adapter; see [`spec/Conventions.md` §Packaging conventions](../../../spec/Conventions.md)): every artefact at the same VERSION, every release. The `day8/re-frame2-xray` devtools panel rides the same version line. Mixing versions across `day8/re-frame2-*` coordinates is **unsupported and undefined** — the runtime contract between core, adapters, and per-feature surfaces is bound to a single coordinated VERSION.

**Lockstep is a build/dependency discipline, not a boot-time runtime check.** `rf/init!` validates only that you handed it an adapter spec map (rejects nil / non-map) and installs it; the adapter spec carries a `:kind` discriminator, **not** a VERSION field, so the runtime never compares per-artefact versions at boot. (Verified: `implementation/core/src/re_frame/core.cljc` `init!` and `implementation/core/src/re_frame/substrate/adapter.cljc` `install-adapter!` carry no version metadata.) The enforcement that *does* exist is **build-time**: the generator template pins `:rf2-version` / `:shadow-version` / `:react-version`, and `tools/template/test/day8/re_frame2_template/version_lockstep_test.clj` fails if those literals drift from their sources of truth. Keep every coordinate at one VERSION because a mixed set is undefined, not because a guard will catch it.

**Validate lockstep yourself.** Grep your `deps.edn` for `day8/re-frame2-` coordinates and confirm every pin is identical: pre-publish (git coords) every `:git/sha` must match; post-publish (Maven coords) every `:mvn/version` must match.

```bash
# Pre-publish (git coords) — every printed SHA must be the same string:
grep -oE 'day8/re-frame2[a-z-]* *\{:git/url[^}]*:git/sha "[^"]+"' deps.edn
# Post-publish (Maven coords) — every printed version must be the same string:
grep -oE 'day8/re-frame2[a-z-]* *\{:mvn/version "[^"]+"' deps.edn
```

Picking a re-frame2 pin for your project means picking it once (one `:git/sha` today, one `:mvn/version` post-publish) and using it everywhere a `day8/re-frame2-*` coordinate appears.

## The eleven artefacts

| Artefact | Tier | When to add |
|---|---|---|
| `day8/re-frame2` | core | **Always.** Registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract. |
| `day8/re-frame2-reagent` | substrate | **Always (for a Reagent app).** The Reagent adapter map. |
| `day8/re-frame2-uix` | substrate | Instead of `-reagent` if you target UIx. |
| `day8/re-frame2-helix` | substrate | Instead of `-reagent` if you target Helix. |
| `day8/re-frame2-schemas` | per-feature | **Day one** (the template attaches a whole-app-db schema). **Required** whenever you call `reg-app-schema` / `reg-app-schemas` — and you must `:require` `re-frame.schemas` before the registrations. Without the artefact, those registration calls throw `:rf.error/schemas-artefact-missing` (a loud error, NOT a silent soft-pass). Requiring `re-frame.schemas` wires Malli automatically, so a registered schema validates (Spec 010 §Schema implies validation on CLJS). |
| `day8/re-frame2-machines` | per-feature | When you call `reg-machine` or `make-machine-handler`. |
| `day8/re-frame2-routing` | per-feature | When you dispatch `:rf.route/*` events or register routes. |
| `day8/re-frame2-flows` | per-feature | When you call `reg-flow`. |
| `day8/re-frame2-http` | per-feature | When you dispatch `:rf.http/managed`. |
| `day8/re-frame2-ssr` | per-feature | When you call `render-to-string` server-side. |
| `day8/re-frame2-epoch` | per-feature | When you call `epoch-history` or `restore-epoch!` (also pulled in transitively by `re-frame2-pair`). |

These eleven are the **publishable** lockstep set. (Two niche local roots — `day8/reagent-slim` and `day8/re-frame2-ssr-ring` — ride the same version but aren't part of greenfield.) The `day8/re-frame2-xray` devtools panel is tooling, not one of the eleven library artefacts, but is a **day-one** dep (see the day-one shape below).

**Greenfield day-one shape.** This skill matches the [deps-new generator template](../README.md#relationship-to-the-generator-template) so the manual and one-command routes land on the same scaffold. The template ships **four** re-frame2 coords on day one — `day8/re-frame2` (core) + `day8/re-frame2-reagent` (adapter) + `day8/re-frame2-schemas` (the starter app attaches a whole-app-db schema; see the table row above for the `reg-app-schema` / `:rf.error/schemas-artefact-missing` contract) + `day8/re-frame2-xray` (in-app devtools, wired via `:devtools/preloads`, Xray-priority by default) — plus an explicit `reagent/reagent` pin.

The remaining per-feature artefacts (`-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) stay pay-as-you-go: resist adding them until the author writes code that actually uses them, so apps that don't use them don't pay the classpath cost.

## Choosing the coordinate (publication state decides the shape)

**Pre-publish reality (today): re-frame2 is NOT on Clojars/npm yet.** The repo's [`README.md`](../../../README.md) §Status confirms this and tells early users to add re-frame2 as a `:git/sha` coordinate. The `day8/re-frame2*` Maven coordinates **do not resolve** — a project that writes `{:mvn/version "<VERSION>"}` for the framework artefacts fails dependency resolution before it compiles. So the coordinate *shape* branches on publication state:

- **Before the first Clojars release (now):** use a **`:git/url` + `:git/sha`** coordinate for each `day8/re-frame2*` artefact, OR a **`:local/root`** coordinate against a reviewed checkout of the monorepo (worked recipe: [The `:local/root` sibling-checkout dev route](#the-localroot-sibling-checkout-dev-route-pre-publish)). This is the **only** working manual route today.
- **After publication (forward-correct):** switch each artefact to `{:mvn/version "<VERSION>"}`. The `:mvn/version` shape below is the post-publish destination; it is not usable until the coordinates resolve on Clojars.

**Two different "versions" — don't conflate them.** The repo `VERSION` / next-release string is what release *tags* are cut from; the *latest published Maven version* is whatever has actually shipped to Clojars (currently: none). Version discovery distinguishes them: a repo `VERSION` of `0.0.1.alpha` does **not** mean `{:mvn/version "0.0.1.alpha"}` resolves.

**The author picks the re-frame2 pin at kickoff; the skill never auto-selects.** Pinning makes the project reproducible. Sources for the author to pick a `:git/sha` today (or a `:mvn/version` once published), in order of authority:

1. **The repo's `VERSION` file** — `https://github.com/day8/re-frame2/blob/main/VERSION` is the single source of truth that release tags are cut from. The string here is the canonical VERSION for the **next** release — NOT a guarantee it is published.
2. **`CHANGELOG.md`** — `https://github.com/day8/re-frame2/blob/main/CHANGELOG.md` lists released VERSIONs with summaries.
3. **The GitHub releases page** — `https://github.com/day8/re-frame2/releases` shows the tags; a tag `v<VERSION>` gives the matching `:git/sha`.
4. **Clojars** — `https://clojars.org/day8/re-frame2` (and the `*-reagent` / `*-schemas` / `*-xray` siblings) is the authority for whether a `:mvn/version` actually resolves. **If the artefact 404s on Clojars, `:mvn/version` is not an option yet** — use `:git/sha` / `:local/root`.

(The generator template ships a **pinned baseline** — it hardcodes `:rf2-version`, `:shadow-version`, and `:react-version` in its `hooks.clj`. Note the template still **emits** `:mvn/version` framework coords and is gated/verified against a `:local/root` rewrite pre-publish, per [`tools/template/spec/005-Repo-Split.md`](../../../tools/template/spec/005-Repo-Split.md) — so the one-command route, like the manual route, is not yet a published-coord scaffold. See [`../README.md` §Pre-split / pre-release caveat](../README.md#relationship-to-the-generator-template).)

**Never invent a version. Never silently pick `latest`. Never write a `:mvn/version` framework coord that doesn't resolve on Clojars.** All three are accidents the policy exists to prevent. If the author hasn't supplied a pin, stop and ask before editing any dep file.

## `deps.edn` shape

A minimal `deps.edn` for a greenfield re-frame2 project. **Today (pre-publish), use the `:git/sha` shape** — the framework artefacts are not on Clojars:

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure       {:mvn/version "1.12.0"}
         org.clojure/clojurescript {:mvn/version "1.12.145"}

         ;; Pre-publish: every day8/re-frame2* artefact pinned to ONE reviewed SHA.
         ;; (deps.edn requires a per-coord :git/url; the four below share the same monorepo
         ;; repo + the same <SHA> the author pinned at kickoff — the lockstep discipline.)
         day8/re-frame2         {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/core"}
         day8/re-frame2-reagent {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/adapters/reagent"}
         day8/re-frame2-schemas {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/schemas"}
         day8/re-frame2-xray    {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "tools/xray"}

         reagent/reagent        {:mvn/version "2.0.1"}}

 :aliases
 {;; The build alias the paired shadow-cljs.edn names via {:deps {:aliases [:shadow]}}.
  ;; It supplies the JVM-side build deps (shadow-cljs + tools.namespace) and the
  ;; test/dev extra-paths. WITHOUT this alias, `npx shadow-cljs watch app` (whose
  ;; shadow-cljs.edn reads `{:deps {:aliases [:shadow]}}`) cannot resolve the build
  ;; classpath. Matches the generator template's deps.edn.
  :shadow
  {:extra-paths ["test" "dev"]
   :extra-deps  {thheller/shadow-cljs        {:mvn/version "<shadow-version>"}
                 org.clojure/tools.namespace {:mvn/version "1.5.0"}}
   :main-opts   ["-m" "shadow.cljs.devtools.cli"]}}}
```

Replace `<SHA>` with the reviewed commit the author pinned (**every `day8/re-frame2-*` line gets the same `<SHA>`** — that is how lockstep is held with git coords; confirm the `:deps/root` for each artefact against the monorepo layout) and `<shadow-version>` with the `shadow-cljs` version from the pinned `implementation/package.json` (the same version you write into `package.json` below — keep them in lockstep). Consuming an unpublished re-frame2 from a **sibling checkout** via `:local/root` is the equivalent pre-publish dev route — see [The `:local/root` sibling-checkout dev route](#the-localroot-sibling-checkout-dev-route-pre-publish) below for the copy-pasteable recipe and the per-artefact paths.

### The `:local/root` sibling-checkout dev route (pre-publish)

The second pre-publish route — **the same status as the `:git/sha` shape above**, a working manual route today but not a published-coord scaffold — points each `day8/re-frame2*` coordinate at a **reviewed re-frame2 checkout sitting beside your project**. Reach for it when you develop against an unpublished re-frame2 (often one you are also editing) instead of a pinned commit: edits in the sibling checkout are picked up on the next build with no re-pin.

`:local/root` takes a path **straight to the artefact's own directory** — the directory that holds *that artefact's* `deps.edn`. There is **no `:deps/root` sub-path navigation for `:local/root`** (unlike the `:git/sha` route, where `:deps/root` selects the artefact within the cloned repo), so each coordinate spells out the full monorepo sub-path itself. Assuming the re-frame2 monorepo is checked out next to your project as `../re-frame2`, only the four framework coordinates change from the `:git/sha` shape — swap each `{:git/url … :git/sha … :deps/root …}` for one `:local/root`:

```clojure
;; Pre-publish dev route — replaces the four :git/sha framework coords above.
;; Everything else in the deps.edn (:paths, the reagent pin, the :shadow alias)
;; is unchanged. Lockstep is automatic: all four resolve from ONE sibling
;; checkout, so they are always the same commit.
day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}
day8/re-frame2-schemas {:local/root "../re-frame2/implementation/schemas"}
day8/re-frame2-xray    {:local/root "../re-frame2/tools/xray"}
```

Those four are the day-one set (core + adapter + schemas + xray). The **pay-as-you-go per-feature artefacts** (and the alternate substrate adapters) take the same `:local/root` shape pointed at their own directory — add each one only when you call into it (see [When to add the optional per-feature artefacts](#when-to-add-the-optional-per-feature-artefacts)):

| Artefact | `:local/root` path (sibling checkout) |
|---|---|
| `day8/re-frame2-uix` (adapter; instead of `-reagent`) | `../re-frame2/implementation/adapters/uix` |
| `day8/re-frame2-helix` (adapter; instead of `-reagent`) | `../re-frame2/implementation/adapters/helix` |
| `day8/re-frame2-machines` | `../re-frame2/implementation/machines` |
| `day8/re-frame2-routing` | `../re-frame2/implementation/routing` |
| `day8/re-frame2-flows` | `../re-frame2/implementation/flows` |
| `day8/re-frame2-http` | `../re-frame2/implementation/http` |
| `day8/re-frame2-ssr` | `../re-frame2/implementation/ssr` |
| `day8/re-frame2-epoch` | `../re-frame2/implementation/epoch` |

Keep the route **in lockstep**: pull every `day8/re-frame2*` coordinate from the *same* sibling checkout so they share one commit — never mix one artefact from `../re-frame2` with another from a second clone. And like the `:git/sha` route this is a pre-publish dev shape, not a published-coord scaffold; the same [pre-split / pre-release caveat](../README.md#relationship-to-the-generator-template) applies.

**Post-publish shape (NOT usable until the coordinates resolve on Clojars).** Once `day8/re-frame2*` is published, each framework artefact switches to a single shared `:mvn/version`:

```clojure
;; AFTER PUBLICATION ONLY — these coords 404 on Clojars today.
day8/re-frame2         {:mvn/version "<VERSION>"}
day8/re-frame2-reagent {:mvn/version "<VERSION>"}
day8/re-frame2-schemas {:mvn/version "<VERSION>"}
day8/re-frame2-xray    {:mvn/version "<VERSION>"}
```

Replace `<VERSION>` with the published VERSION (**every `day8/re-frame2-*` line gets the same value**). Verify it resolves on Clojars first — see the version-discovery sources above.

Notes on the pins:
- The Clojure / ClojureScript versions match what the re-frame2 repo's own core artefact builds against (`implementation/core/deps.edn`) and what the generator template pins. You can use newer versions if shadow-cljs and Reagent support them; start with these.
- `reagent/reagent {:mvn/version "2.0.1"}` is pinned **explicitly**, matching the template. The Reagent adapter pulls Reagent in transitively, but pinning the substrate version yourself is the idiomatic choice — it stops a surprise transitive bump from changing your rendering substrate underneath you. Keep the version in lockstep with the adapter's `deps.edn`.
- The **`:shadow` alias is required**, not optional — the paired `shadow-cljs.edn` (see [`shadow-cljs.md`](shadow-cljs.md)) reads its build classpath from it via `{:deps {:aliases [:shadow]}}`. Omitting it is the most common first-`watch` failure on the manual route. The generator template ships this alias (plus `:cljfmt` / `:clj-kondo` lint aliases — out of scope here, but present in the template's `deps.edn` if you compare); the manual route needs at least `:shadow` for the build to resolve.

## `package.json` shape

re-frame2 itself ships no npm code — but Reagent depends on React, and shadow-cljs is the build tool. **Default to the versions the re-frame2 repo's own `implementation/package.json` pins at the author's pinned `day8/re-frame2` commit/tag** — those are known-good against the chosen re-frame2 VERSION:

```json
{
  "name": "your-app",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "watch":   "shadow-cljs watch app",
    "release": "shadow-cljs release app"
  },
  "devDependencies": {
    "shadow-cljs": "<from pinned implementation/package.json>"
  },
  "dependencies": {
    "react":     "<from pinned implementation/package.json>",
    "react-dom": "<from pinned implementation/package.json>"
  }
}
```

`shadow-cljs` is build-only → `devDependencies`. `react` / `react-dom` are runtime deps of the shipped app (Reagent renders against them) → `dependencies`. This matches the generator template's `package.json`. Read `<path-to-re-frame2>/implementation/package.json` (verified pinned checkout — see [`../SKILL.md`](../SKILL.md) cardinal rule 1) and copy the three versions verbatim.

**Latest-from-npm is opt-in only.** If the author explicitly asks for the newest versions, run `npm view shadow-cljs version` / `npm view react version` / `npm view react-dom version` and **show the result for confirmation before writing it into `package.json`**. Do not auto-substitute. Reagent 2.x requires React 19; flag any pick below 19 as a conflict and stop.

Then `npm install` (after the author has approved the resolved `package.json`).

## When to add the optional per-feature artefacts

`day8/re-frame2-schemas` is **already in the day-one shape** (the template attaches a whole-app-db schema). The remaining six per-feature artefacts are pay-as-you-go. Add them **at the moment** the author writes code that calls into them — not before.

| If the author writes... | Add to `deps.edn`... |
|---|---|
| `(rf/reg-machine ...)` or `(re-frame.machines/make-machine-handler ...)` | `day8/re-frame2-machines` |
| `(rf/reg-route ...)` or dispatches `:rf.route/handle-url-change` | `day8/re-frame2-routing` |
| `(rf/reg-flow ...)` | `day8/re-frame2-flows` |
| `[:rf.http/managed ...]` as an `:fx` entry | `day8/re-frame2-http` |
| Server-side `render-to-string` for SSR | `day8/re-frame2-ssr` |
| `(rf/epoch-history ...)` or `(rf/restore-epoch! ...)` directly | `day8/re-frame2-epoch` |

(The `re-frame2-pair` skill pulls `-epoch` in transitively so live-inspection time-travel works; if the app uses `re-frame2-pair` but doesn't call the epoch surface itself, the author still doesn't need to add `-epoch` to their own `deps.edn` — the skill injects its runtime over nREPL.)

Each artefact registers its own load-time hooks on require, so the only extra step beyond the dep is a `:require` of the artefact's primary namespace from your entry ns or wherever you first call into its API. See the leaf for each feature in the main `re-frame2` skill for the canonical require shape.
