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

re-frame2 ships **eleven Maven artefacts in lockstep** (core + 7 per-feature + 3 per-adapter; see [`spec/Conventions.md` §Packaging conventions](../../../spec/Conventions.md)): every artefact at the same VERSION, every release, and `day8/re-frame2-xray` rides the same line. Mixing versions across `day8/re-frame2-*` coordinates is **unsupported and undefined** — the runtime contract is bound to a single coordinated VERSION.

**Lockstep is a build/dependency discipline, not a boot-time runtime check.** `rf/init!` only checks you handed it an adapter spec map (nil / non-map rejected); the spec carries a `:kind` discriminator, **not** a VERSION, so the runtime never compares per-artefact versions at boot. The enforcement that *does* exist is **build-time**: `tools/template/test/day8/re_frame2_template/version_lockstep_test.clj` fails if the template's pinned `:rf2-version` / `:shadow-version` / `:react-version` literals drift from their sources of truth. Keep every coordinate at one VERSION because a mixed set is undefined, not because a guard will catch it.

**Validate lockstep yourself.** Grep your `deps.edn` for `day8/re-frame2-` coordinates and confirm every pin is identical — pre-publish every `:git/sha` must match; post-publish every `:mvn/version` must match:

```bash
# every printed SHA (pre-publish) / version (post-publish) must be the same string:
grep -oE 'day8/re-frame2[a-z-]* *\{:git/url[^}]*:git/sha "[^"]+"' deps.edn
```

## The eleven artefacts

| Artefact | Tier | When to add |
|---|---|---|
| `day8/re-frame2` | core | **Always.** Registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract. |
| `day8/re-frame2-reagent` | substrate | **Always (for a Reagent app).** The Reagent adapter map. |
| `day8/re-frame2-uix` | substrate | Instead of `-reagent` if you target UIx. |
| `day8/re-frame2-helix` | substrate | Instead of `-reagent` if you target Helix. |
| `day8/re-frame2-schemas` | per-feature | **Day one** (the template attaches a whole-app-db schema). **Required** whenever you call `reg-app-schema` / `reg-app-schemas`, and you must `:require` `re-frame.schemas` first — without the artefact those calls throw `:rf.error/schemas-artefact-missing` (loud, NOT a silent soft-pass). Requiring `re-frame.schemas` wires Malli automatically, so a registered schema validates (Spec 010). |
| `day8/re-frame2-machines` | per-feature | When you call `reg-machine` or `make-machine-handler`. |
| `day8/re-frame2-routing` | per-feature | When you dispatch `:rf.route/*` events or register routes. |
| `day8/re-frame2-flows` | per-feature | When you call `reg-flow`. |
| `day8/re-frame2-http` | per-feature | When you dispatch `:rf.http/managed`. |
| `day8/re-frame2-ssr` | per-feature | When you call `render-to-string` server-side. |
| `day8/re-frame2-epoch` | per-feature | When you call `epoch-history` or `restore-epoch!` — or when you want `re-frame2-pair`'s live time-travel, which reads `epoch-history` and so needs this artefact on your app's own classpath. |

These eleven are the **publishable** lockstep set. (Two niche local roots — `reagent-slim`, `re-frame2-ssr-ring` — ride the same version but aren't greenfield.) `day8/re-frame2-xray` is tooling, not one of the eleven, but is a **day-one** dep (below).

**Greenfield day-one shape.** Matching the [generator template](../README.md#relationship-to-the-generator-template), the day-one set is **four** re-frame2 coords — `day8/re-frame2` (core) + `day8/re-frame2-reagent` (adapter) + `day8/re-frame2-schemas` (the starter app attaches a whole-app-db schema — see the table row for the `:rf.error/schemas-artefact-missing` contract) + `day8/re-frame2-xray` (in-app devtools via `:devtools/preloads`) — plus an explicit `reagent/reagent` pin.

The remaining per-feature artefacts (`-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) stay pay-as-you-go — add them only when the author writes code that uses them (§When to add, below), so apps that don't use them don't pay the classpath cost.

## Choosing the coordinate (publication state decides the shape)

**Pre-publish reality (today): re-frame2 is NOT on Clojars/npm yet.** The repo's [`README.md`](../../../README.md) §Status confirms this and tells early users to use a `:git/sha` coordinate. The `day8/re-frame2*` Maven coordinates **do not resolve** — a `{:mvn/version "<VERSION>"}` framework coord fails dependency resolution before it compiles. So the coordinate *shape* branches on publication state:

- **Before the first Clojars release (now):** a **`:git/url` + `:git/sha`** coord for each `day8/re-frame2*` artefact, OR a **`:local/root`** coord against a reviewed monorepo checkout ([recipe below](#the-localroot-sibling-checkout-dev-route-pre-publish)). This is the **only** working manual route today.
- **After publication (forward-correct):** switch each artefact to `{:mvn/version "<VERSION>"}` — the post-publish destination, not usable until the coords resolve on Clojars.

**Two different "versions" — don't conflate them.** The repo `VERSION` is what release *tags* are cut from; the *published Maven version* is whatever has shipped to Clojars (currently: none). A repo `VERSION` of `0.0.1.alpha` does **not** mean `{:mvn/version "0.0.1.alpha"}` resolves.

**The author picks the re-frame2 pin at kickoff; the skill never auto-selects.** Pinning makes the project reproducible. Sources for the author to pick a `:git/sha` today (or a `:mvn/version` once published), in order of authority:

1. **The repo's `VERSION` file** (`github.com/day8/re-frame2/blob/main/VERSION`) — the single source of truth release tags are cut from; the canonical VERSION for the **next** release, NOT a guarantee it is published.
2. **`CHANGELOG.md`** / **the GitHub releases page** — list released VERSIONs; a tag `v<VERSION>` gives the matching `:git/sha`.
3. **Clojars** (`clojars.org/day8/re-frame2` and siblings) — the authority for whether a `:mvn/version` actually resolves. **If the artefact 404s on Clojars, `:mvn/version` is not an option yet** — use `:git/sha` / `:local/root`.

(The generator template ships a **pinned baseline** in its `hooks.clj`. Like the manual route it is not yet a published-coord scaffold — it emits `:mvn/version` framework coords but is gated against a `:local/root` rewrite pre-publish; see [`../README.md` §Relationship to the generator template](../README.md#relationship-to-the-generator-template).)

**Never invent a version. Never silently pick `latest`. Never write a `:mvn/version` framework coord that 404s on Clojars.** If the author hasn't supplied a pin, stop and ask before editing any dep file.

## `deps.edn` shape

A minimal `deps.edn` for a greenfield re-frame2 project. **Today (pre-publish), use the `:git/sha` shape** — the framework artefacts are not on Clojars:

```clojure
{:paths ["src"]
 :deps  {org.clojure/clojure       {:mvn/version "1.12.0"}
         org.clojure/clojurescript {:mvn/version "1.12.145"}

         ;; Pre-publish: every day8/re-frame2* artefact pinned to ONE reviewed <SHA>
         ;; (per-coord :git/url; the four share one monorepo repo + SHA — lockstep).
         day8/re-frame2         {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/core"}
         day8/re-frame2-reagent {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/adapters/reagent"}
         day8/re-frame2-schemas {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/schemas"}
         day8/re-frame2-xray    {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "tools/xray"}

         reagent/reagent        {:mvn/version "2.0.1"}}

 :aliases
 {;; Build alias the paired shadow-cljs.edn names via {:deps {:aliases [:shadow]}};
  ;; supplies the JVM-side build deps + test/dev paths. Required — see notes below.
  :shadow
  {:extra-paths ["test" "dev"]
   :extra-deps  {thheller/shadow-cljs        {:mvn/version "<shadow-version>"}
                 org.clojure/tools.namespace {:mvn/version "1.5.0"}}
   :main-opts   ["-m" "shadow.cljs.devtools.cli"]}}}
```

Replace `<SHA>` with the reviewed commit (**every `day8/re-frame2-*` line gets the same `<SHA>`** — that is how lockstep holds with git coords) and `<shadow-version>` with the `shadow-cljs` version from the pinned `implementation/package.json` (keep it in lockstep with `package.json` below). A **sibling checkout** via `:local/root` is the equivalent pre-publish route — see [The `:local/root` sibling-checkout dev route](#the-localroot-sibling-checkout-dev-route-pre-publish) below.

### The `:local/root` sibling-checkout dev route (pre-publish)

The second pre-publish route points each `day8/re-frame2*` coordinate at a **reviewed re-frame2 checkout beside your project** — reach for it when you develop against an unpublished re-frame2 you're also editing (edits are picked up on the next build, no re-pin). `:local/root` takes a path **straight to the artefact's own directory** (no `:deps/root` sub-path navigation, unlike the `:git/sha` route). Assuming the monorepo is checked out as `../re-frame2`, only the four framework coords change — swap each `:git/sha` coord for one `:local/root`:

```clojure
;; Pre-publish dev route — replaces the four :git/sha framework coords above.
;; Everything else (:paths, reagent pin, :shadow alias) is unchanged. Lockstep
;; is automatic: all four resolve from ONE sibling checkout, same commit.
day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}
day8/re-frame2-schemas {:local/root "../re-frame2/implementation/schemas"}
day8/re-frame2-xray    {:local/root "../re-frame2/tools/xray"}
```

Those four are the day-one set. The **pay-as-you-go per-feature artefacts** (and the alternate substrate adapters) take the same `:local/root` shape pointed at their own directory — add each only when you call into it:

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

Keep the route **in lockstep**: pull every `day8/re-frame2*` coordinate from the *same* sibling checkout so they share one commit — never mix one artefact from `../re-frame2` with another from a second clone.

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
- The Clojure / ClojureScript versions match the re-frame2 repo's core artefact (`implementation/core/deps.edn`) and the template; start with these, bump only if shadow-cljs and Reagent support newer.
- `reagent/reagent {:mvn/version "2.0.1"}` is pinned **explicitly**, matching the template — the adapter pulls Reagent transitively, but pinning it yourself stops a surprise transitive bump changing your rendering substrate. Keep it in lockstep with the adapter's `deps.edn`.
- The **`:shadow` alias is required**, not optional — the paired `shadow-cljs.edn` reads its build classpath from it via `{:deps {:aliases [:shadow]}}` (see [`shadow-cljs.md`](shadow-cljs.md)). The template ships it alongside `:cljfmt` / `:clj-kondo` lint aliases (out of scope here); the manual route needs at least `:shadow`.

## `package.json` shape

re-frame2 ships no npm code — but Reagent depends on React, and shadow-cljs is the build tool. **Default to the versions the pinned `implementation/package.json` ships** — known-good against the chosen re-frame2 VERSION:

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

`shadow-cljs` is build-only → `devDependencies`; `react` / `react-dom` are runtime deps → `dependencies`. Matches the template's `package.json`. Read the pinned `implementation/package.json` (see [`../SKILL.md`](../SKILL.md) cardinal rule 1) and copy the three versions verbatim.

**Latest-from-npm is opt-in only.** If the author explicitly asks for the newest, run `npm view <pkg> version` for each and **show the result for confirmation before writing it** — don't auto-substitute. Reagent 2.x requires React 19; flag any pick below 19 as a conflict and stop.

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

(**`re-frame2-pair` does not add `-epoch` for you.** The pair skill's runtime helper ships into your app as a shadow-cljs `:devtools` preload, not as a Maven artefact injected over nREPL — a preload cannot put a jar on the classpath. So to get the pair skill's epoch history / `restore-epoch` time-travel, add `day8/re-frame2-epoch` to your own `deps.edn`; without it the pair runtime reports `:rf.error/epoch-artefact-missing` and time-travel is a no-op.)

Each artefact registers its load-time hooks on require, so beyond the dep the only step is a `:require` of its primary namespace from your entry ns. The main `re-frame2` skill's per-feature leaves give the canonical require shape.
