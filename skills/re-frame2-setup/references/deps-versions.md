# Dependencies & versions

Which re-frame2 artefacts a project depends on, what version they are pinned at, and what coordinate shape resolves today. The default route needs none of this: the pins are already in [`first-counter.md`](first-counter.md)'s `deps.edn` and `package.json`. Read this leaf to override a pin, to give the framework coordinates a shape other than `:local/root`, or to add a per-feature artefact later.

## Contents

- The lockstep contract
- The artefacts a greenfield project may add
- The default pins
- Choosing the coordinate (publication state decides the shape)
- `package.json` and latest-from-npm
- When to add the optional per-feature artefacts

---

## The lockstep contract

Every `day8/re-frame2*` artefact ships at **one VERSION, in lockstep**, every release — the contract is [`spec/Conventions.md` §Lockstep versioning through 1.0](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md#lockstep-versioning-through-10), and the roster is the release workflow's, which this leaf deliberately does not count. Mixing versions across `day8/re-frame2-*` coordinates is **unsupported and undefined** — the runtime contract is bound to a single coordinated VERSION.

**Lockstep is a build/dependency discipline, not a boot-time runtime check.** `rf/init!` only checks you handed it an adapter spec map (nil / non-map rejected); the spec carries a `:kind` discriminator, **not** a VERSION, so the runtime never compares per-artefact versions at boot. The enforcement that *does* exist is **build-time**: `tools/template/test/day8/re_frame2_template/version_lockstep_test.clj` fails if the template's pinned `:rf2-version` / `:shadow-version` / `:react-version` literals drift from their sources of truth, and the derived scaffold in `first-counter.md` is drift-locked against the template. Keep every coordinate at one VERSION because a mixed set is undefined, not because a guard will catch it.

**Validate lockstep yourself.** Grep your `deps.edn` for `day8/re-frame2-` coordinates and confirm every pin is identical — pre-publish every `:git/sha` (or every `:local/root` checkout) must match; post-publish every `:mvn/version` must match:

```bash
# every printed version / SHA must be the same string:
grep -oE 'day8/re-frame2[a-z-]* *\{[^}]*(:mvn/version|:git/sha) "[^"]+"' deps.edn
```

**Same VERSION, different release trigger.** Framework artefacts ship on `v*` tags; Xray and Story ship separately on `xray-v*` and `story-v*` ([release process](https://github.com/day8/re-frame2/blob/main/docs/release-process.md)). A framework release does not publish either tool. Story is already in the scaffold's `:dev` alias: keep it on the reviewed checkout or commit until its matching Maven version resolves. Xray is attached separately when requested.

## The artefacts a greenfield project may add

| Artefact | Tier | When to add |
|---|---|---|
| `day8/re-frame2` | core | **Always.** Registry, drain, fx, dispatch, subscribe, frame-root, trace, the substrate-adapter contract. |
| `day8/re-frame2-reagent` | substrate | **Always (for a Reagent app).** The Reagent adapter map. |
| `day8/re-frame2-uix` | substrate | Instead of `-reagent` if you target UIx. |
| `day8/re-frame2-schemas` | per-feature | When you call `reg-app-schema` / `reg-app-schemas`, or put a `:schema` on a registration. `:require` `re-frame.schemas` first — it self-wires its Malli adapter (no separate `re-frame.schemas.malli` require) and a registered schema then validates (Spec 010); without the artefact those calls throw `:rf.error/schemas-artefact-missing` — loud, not a silent soft-pass. |
| `day8/re-frame2-machines` | per-feature | When you call `reg-machine` or `make-machine-handler`. |
| `day8/re-frame2-routing` | per-feature | When you dispatch `:rf.route/*` events or register routes. |
| `day8/re-frame2-flows` | per-feature | When you call `reg-flow`. |
| `day8/re-frame2-http` | per-feature | When you dispatch `:rf.http/managed`. |
| `day8/re-frame2-ssr` | per-feature | When you call `render-to-string` server-side. |
| `day8/re-frame2-epoch` | per-feature | When you call `epoch-history` or `restore-epoch!` — or when you want `re-frame2-pair`'s live time-travel, which reads `epoch-history` and so needs this artefact on your app's own classpath. |

This table is what a greenfield project may add, not the release roster. (Two niche local roots — `reagent-slim`, `re-frame2-ssr-ring` — ride the same version but aren't greenfield. `day8/re-frame2-resources`, Spec [016](https://github.com/day8/re-frame2/blob/main/spec/016-Resources.md) / EP-0003, is a post-v1 per-feature artefact; add it when you call `reg-resource`.) `day8/re-frame2-xray` is a tool, on its own tag (above).

**Greenfield day-one shape.** Matching the generator template, the day-one set is **two** re-frame2 coords — `day8/re-frame2` (core) + the substrate adapter (`day8/re-frame2-reagent`, or `day8/re-frame2-uix` on explicit request) — plus the view library the adapter renders through (`reagent/reagent`, or `com.pitch/uix.core` alone — the adapter's `client-root` / `render!` mint the React Root, so neither route pins a DOM-mount library), pinned explicitly so a surprise transitive bump cannot change your rendering substrate. Story is the one tool that rides day one: `day8/re-frame2-story` sits on the `:dev` alias (a `:local/root` into a re-frame2 checkout until it is published). The scaffold's global `:deps` aliases put Story on both watch and release classpaths; the app's dev-only entry keeps it out of the **release bundle**, not dependency resolution. Everything else — schemas, Xray, the per-feature artefacts — stays **pay-as-you-go**: add it at the moment the author writes code that calls into it, so apps that don't use it don't pay the classpath cost.

## The default pins

**The default pin is the generator template's baseline; an author-supplied pin overrides it.** The skill picks the default itself — a missing pin is never a reason to halt or interview the author. The one reviewed source of truth is the template's `hooks.clj` (`:rf2-version`, tracking the repo `VERSION` release tags are cut from; `:shadow-version` and `:react-version`, tracking `implementation/package.json`), drift-guarded by `version_lockstep_test.clj`; `first-counter.md`'s `deps.edn` and `package.json` carry those literals, rendered by derivation, so the normal path reads nothing outside the package. Read the leaf for the numbers — it is what the drift locks check, and this sentence is not.

An author who supplies a pin gets it on every `day8/re-frame2*` line (lockstep) — as a `:mvn/version` once it resolves on Clojars, or as one `:git/sha` today. Sources for choosing one, in order of authority:

1. **The repo's `VERSION` file** (`github.com/day8/re-frame2/blob/main/VERSION`) — what release tags are cut from; the VERSION for the **next** release, not a guarantee it is published.
2. **`CHANGELOG.md`** / **the GitHub releases page** — released VERSIONs; a tag `v<VERSION>` gives the matching `:git/sha`.
3. **Clojars** (`clojars.org/day8/re-frame2` and siblings) — the authority for whether a `:mvn/version` actually resolves. **If the artefact 404s on Clojars, `:mvn/version` is not an option yet.**

**Never invent a version. Never silently pick `latest`. Never write a `:mvn/version` framework coord that 404s on Clojars.** The template baseline is a *reviewed* pin, `latest` stays explicit opt-in, and publication state decides the coordinate shape below.

## Choosing the coordinate (publication state decides the shape)

**Pre-publish reality (today): re-frame2 is NOT on Clojars/npm yet.** The repo's [`README.md`](https://github.com/day8/re-frame2/blob/main/README.md) §Status confirms this. The `day8/re-frame2*` Maven coordinates **do not resolve** — the `{:mvn/version "…"}` lines the scaffold's `deps.edn` carries are forward-correct and fail resolution today with `Could not find artifact day8/re-frame2`. So the coordinate *shape* branches on publication state, and pointing the framework coords at something that resolves is `SKILL.md` step 2 on both routes:

- **Before the first Clojars release (now):** a **`:local/root`** against a reviewed monorepo checkout — the default, because the skill is installed by link from exactly such a checkout and resolves its absolute path itself — OR a **`:git/url` + `:git/sha`** coord for each artefact. This is the **only** working manual route today.
- **After publication (forward-correct):** leave the `{:mvn/version "<VERSION>"}` lines the scaffold ships — the post-publish destination, not usable until the coords resolve on Clojars.

**Two different "versions" — don't conflate them.** The repo `VERSION` is what release *tags* are cut from; the *published Maven version* is whatever has shipped to Clojars (currently: none). A repo `VERSION` of `0.0.1.alpha` does **not** mean `{:mvn/version "0.0.1.alpha"}` resolves.

### The `:local/root` sibling-checkout dev route (pre-publish)

The default pre-publish shape. `:local/root` takes a path **straight to the artefact's own directory**. With `<RE_FRAME2>` the absolute path of the reviewed checkout (forward slashes on every OS), the two day-one lines become:

```clojure
;; Pre-publish — replaces the two :mvn/version framework coords the scaffold ships.
;; Everything else (:paths, the reagent pin, the :shadow alias) is unchanged.
day8/re-frame2         {:local/root "<RE_FRAME2>/implementation/core"}
day8/re-frame2-reagent {:local/root "<RE_FRAME2>/implementation/adapters/reagent"}
```

Lockstep is automatic — both resolve from one checkout, one commit — and edits to the checkout are picked up on the next build. A relative path (`../re-frame2/implementation/core` for a sibling checkout) works the same way. The scaffold's own Story dep and every pay-as-you-go artefact take the same shape, pointed at their own directory:

| Artefact | `:local/root` path under `<RE_FRAME2>` |
|---|---|
| `day8/re-frame2-uix` (adapter; instead of `-reagent`) | `implementation/adapters/uix` |
| `day8/re-frame2-schemas` | `implementation/schemas` |
| `day8/re-frame2-machines` | `implementation/machines` |
| `day8/re-frame2-routing` | `implementation/routing` |
| `day8/re-frame2-flows` | `implementation/flows` |
| `day8/re-frame2-http` | `implementation/http` |
| `day8/re-frame2-ssr` | `implementation/ssr` |
| `day8/re-frame2-epoch` | `implementation/epoch` |
| `day8/re-frame2-story` (tool; on the scaffold's `:dev` alias) | `tools/story` |
| `day8/re-frame2-xray` (tool) | `tools/xray` |

Keep every `day8/re-frame2*` coordinate on the *same* checkout — never mix one artefact from one clone with another from a second.

### The `:git/sha` route (pre-publish, no checkout on disk)

Each artefact takes the monorepo's `:git/url` plus a `:deps/root` into its directory, with **one reviewed SHA on every line** — that is how lockstep holds with git coords. Replace these entries in the scaffold, preserving its other dependencies and aliases:

```clojure
;; In :deps:
day8/re-frame2         {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/core"}
day8/re-frame2-reagent {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "implementation/adapters/reagent"}

;; In :aliases :dev :extra-deps (replace the scaffold's sibling :local/root):
day8/re-frame2-story   {:git/url "https://github.com/day8/re-frame2.git" :git/sha "<SHA>" :deps/root "tools/story"}
```

`<SHA>` is the reviewed full commit SHA; resolve a selected tag to its commit first. For UIx, replace the Reagent coordinate with `day8/re-frame2-uix` and `:deps/root "implementation/adapters/uix"`. Per-feature artefacts use the table's paths. Leaving Story's `../re-frame2/tools/story` in `:dev` still requires a sibling checkout. Verify with `clojure -Stree -A:shadow:dev`; plain `clojure -Stree` does not resolve Story or shadow-cljs.

### Post-publish shape (NOT usable until the coordinates resolve on Clojars)

Once `day8/re-frame2*` is published, every framework artefact is a single shared `:mvn/version` — the shape the scaffold already ships:

```clojure
;; AFTER PUBLICATION ONLY — these coords 404 on Clojars today.
day8/re-frame2         {:mvn/version "<VERSION>"}
day8/re-frame2-reagent {:mvn/version "<VERSION>"}
```

Verify it resolves on Clojars first. The tools (`-xray`, `-story`) flip to `:mvn/version` on **different** tags, so check Clojars per artefact rather than assuming the framework release brought the tools with it. In particular, the scaffold's Story entry is a sibling `:local/root`, not a Maven coordinate: replace it with an absolute reviewed checkout path or the Git coordinate above until the matching Story version is published. Once it resolves, use `day8/re-frame2-story {:mvn/version "<VERSION>"}` in `:aliases :dev :extra-deps`.

## `package.json` and latest-from-npm

re-frame2 ships no npm code, so every npm package in the scaffold is there for the substrate, the build tool or Story. The scaffold's `package.json` declares **five**, all at the versions the pinned `implementation/package.json` ships, known-good against the chosen re-frame2 VERSION:

| Package | Where | Why it is day-one |
|---|---|---|
| `shadow-cljs` | `devDependencies` | the build tool itself |
| `react` | `dependencies` | the substrate's renderer |
| `react-dom` | `dependencies` | the substrate's DOM renderer |
| `@xyflow/react` | `devDependencies` | Story's embedded machine canvas |
| `elkjs` | `devDependencies` | the layout engine that canvas runs |

**The last two are not optional.** Story rides the `:dev` alias, Story pulls Xray, Xray pulls `machines-viz`, and `machines-viz`'s chart requires `@xyflow/react` and `elkjs/lib/elk.bundled.js` directly — so a `package.json` carrying only the first three compiles until the dev build loads Story and then fails with shadow's `The required JS dependency "@xyflow/react" is not available`. Restore all five when recovering a broken install. Then run `npm install` yourself; on the default baseline there is nothing to pause for.

**Latest-from-npm is opt-in only.** If the author explicitly asks for the newest, run `npm view <pkg> version` for each and **show the result for confirmation before writing it** — don't auto-substitute. Reagent 2.x requires React 19; flag any pick below 19 as a conflict and stop. Recovering a broken install goes back to the pinned versions, never to bare `npm install react react-dom` (which writes `latest`).

## When to add the optional per-feature artefacts

Add them **at the moment** the author writes code that calls into them — not before:

| If the author writes... | Add to `deps.edn`... |
|---|---|
| `(rf/reg-app-schema ...)` or a `:schema` on a registration | `day8/re-frame2-schemas` (and `:require [re-frame.schemas]`) |
| `(rf/reg-machine ...)` or `(re-frame.machines/make-machine-handler ...)` | `day8/re-frame2-machines` |
| `(rf/reg-route ...)` or dispatches `:rf.route/handle-url-change` | `day8/re-frame2-routing` |
| `(rf/reg-flow ...)` | `day8/re-frame2-flows` |
| `[:rf.http/managed ...]` as an `:fx` entry | `day8/re-frame2-http` |
| Server-side `render-to-string` for SSR | `day8/re-frame2-ssr` |
| `(rf/epoch-history ...)` or `(rf/restore-epoch! ...)` directly | `day8/re-frame2-epoch` |

(**`re-frame2-pair` does not add `-epoch` for you.** The pair skill's runtime helper ships into your app as a shadow-cljs `:devtools` preload, not as a Maven artefact injected over nREPL — a preload cannot put a jar on the classpath. So to get the pair skill's epoch history / `restore-epoch` time-travel, add `day8/re-frame2-epoch` to your own `deps.edn`; without it the pair runtime reports `:rf.error/epoch-artefact-missing` and time-travel is a no-op.)

Each artefact registers its load-time hooks on require, so beyond the dep the only step is a `:require` of its primary namespace from your entry ns. The main `re-frame2` skill's per-feature leaves give the canonical require shape.
