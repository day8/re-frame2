# setup

Operational detail for two early steps: the **React-19 / Reagent-2 floor gate** (the pre-flight that runs *before* any dep edit) and **M-0 — the dep-coord swap** (the precondition for every other rule). Clear the gate first; then apply the coord swap; then verify the project compiles; *then* sweep for breakage.

## Contents

- The React-19 / Reagent-2 floor gate (pre-flight — run before M-0)
- The coord swap (M-0)
- Per-build-tool shapes
- Coords to detect
- Picking the substrate-adapter artefact
- Pin the migration corpus before reading it
- Discovering the current VERSION
- The pay-as-you-go artefact split (M-27 through M-32)
- Edge cases

---

## The React-19 / Reagent-2 floor gate (pre-flight — run before M-0)

> **This is a go/no-go gate, not a footnote.** Run all five checks below *before* you touch any dep coord. For a codebase already on React 19 *and* a current build toolchain it is a fast pass — read the checks, confirm each, move on. For the rest of the v1 population (overwhelmingly React 17/18 + Reagent 1.x, often on a years-old shadow-cljs) this gate is the single largest and riskiest part of the whole migration, and the blocking cases — the component-library blocker (Check 2) and the toolchain-skew compile failure (Check 4) — must be discovered here, not deep inside a failed compile loop after the coord swap.

**Why this comes first.** re-frame2's substrate adapters target React 19. The `day8/re-frame2-reagent` bridge runs on Reagent 2.x, which ships against React 19 (it loads `reagent.dom.client` — the `createRoot` path); Reagent 1.x is no longer supported. UIx and Helix hit the same floor — all three substrates target React 19. So a v1 codebase on React 17/18 must clear a forced React → 19 (and, for Reagent, Reagent → 2.x) upgrade *and the cascade it drags in* — the component library, React-coupled JS deps, and any hand-rolled render call site — before the coord swap can succeed. If `package.json` still pins `react`/`react-dom` to 17 or 18 when the swap lands, the build either fails at module-resolve time or compiles and then crashes on first render against React-19-only API surfaces.

The five checks, in order (Checks 1–3 are the React side; Check 4 is the ClojureScript-toolchain side; Check 5 is the go/no-go):

### Check 1 — Downstream React-lib compatibility audit

Enumerate every `package.json` dependency that declares a `react` or `react-dom` **peer dependency** — these are the JS libraries that will break if React's major version moves under them. Typical culprits in a view-heavy app: animation, toast/notification, portal/modal, drag-and-drop, virtualised-list, date-picker, and chart libraries.

For each one, check its current React-19 support (its published `peerDependencies` range, its release notes, or its changelog). Produce a list with three buckets:

- **Already React-19-compatible** — peer range admits `19` (e.g. `^18 || ^19`, `>=18`). No action.
- **Needs a bump** — a newer release of the *same* library supports React 19. Note the target version.
- **Needs a replacement / has no React-19 release** — the library is abandoned or has not shipped React-19 support. This is a blocker dimension; surface it to the author for a decision (upgrade path, replacement library, or hold the migration).

You can list the peer-dependency declarations with a read-only search over the lockfile / installed packages — e.g. `rg -l '"react"' node_modules/*/package.json` (or the package manager's own `why`/`ls` for `react`). The skill enumerates and reports; the **author** runs any `npm install` / upgrade command (cardinal rule 5).

### Check 2 — Component / substrate-library check (the go/no-go BLOCKER)

If the project leans on a **UI component library** — any Reagent-based or React-based component kit (a design-system wrapper, a Reagent component suite, a React component library consumed from CLJS) — confirm it has a release compatible with **React 19** (and, if it is a Reagent component lib, **Reagent 2.x**).

- If it does: note the target version; it joins the Check-1 bump list.
- **If it does not (no declared React-19 / Reagent-2 release): STOP. This is a go/no-go blocker.** Do not touch any dep coord. Surface it to the operator/author as an explicit decision among **four** options:
  1. **Wait** for a React-19 / Reagent-2 release of the component library.
  2. **Replace** it with a React-19-compatible alternative.
  3. **Vendor / patch** it to run on React 19.
  4. **Force React 19 at the app level and verify the component library empirically at runtime.** A library's *declared* peer range is a lower bound on what its maintainers tested, not a hard ceiling — a component kit that pins `^18` often runs fine on React 19 in practice (React 19 kept most of the 18 surface; the breaks are concentrated in the legacy `ReactDOM.render` path and a few removed APIs, per Check 3). So bump `react`/`react-dom` to `^19` anyway, install, and **exercise the app's real component-using screens at runtime** — mount each surface that renders the library's components, click through the interactions, watch the console for React errors/warnings. If it renders and behaves, the declared range was conservative and the library is usable as-is (record this as an *empirically-verified* GO, with the screens tested). If it throws or mis-renders, fall back to option 1–3. This is the **escape hatch from an over-conservative peer range** — but it is a *verified* GO, never an assumed one: an unverified "probably fine" force is exactly the half-migrated-tree trap this gate exists to prevent.

  A Reagent component library with no Reagent-2 / React-19 build **and** no empirical pass cannot be carried across the floor, and discovering that *after* the coord swap means unwinding a half-migrated tree. Discover it here.

This is the one check most likely to turn a "cheap coord swap" into a multi-week project — which is exactly why it runs before any edit.

### Check 3 — Legacy React-API scan

Scan the source for surviving hand-rolled React-DOM **legacy** call sites — chiefly `ReactDOM.render` (and `ReactDOM.hydrate` / `ReactDOM.unmountComponentAtNode`). These were removed in React 18 and remain gone in React 19; any survivor must migrate to the `createRoot` API (`react-dom/client`) in the same pass.

Most v1 codebases route their root mount through Reagent and never call `ReactDOM.render` directly, so this is usually empty — but a flag here, found pre-flight, is far cheaper than a runtime crash. A read-only search such as `rg -n 'ReactDOM\.(render|hydrate|unmountComponentAtNode)'` over the source tree surfaces them; flag each for the author.

### Check 4 — CLJS / shadow-cljs / Closure-compiler toolchain-skew check (hits almost every older shadow-cljs app)

Checks 1–3 cover the **React side** of the floor. This check covers the **ClojureScript side** — the `ClojureScript ↔ shadow-cljs ↔ Closure-compiler` coupling — and it is just as much a pre-flight blocker, because it surfaces as a *compile* failure that **looks like a migration bug but is pure toolchain skew**.

**The coupling.** re-frame2 core pins a recent ClojureScript, which in turn carries a recent **Google Closure Compiler**. shadow-cljs invokes the Closure compiler through internal classes whose field/method signatures track the Closure version. If the project's shadow-cljs predates the Closure that re-frame2's CLJS pin drags in, the **older shadow-cljs calls a Closure internal that has changed shape** — and the build dies with a cryptic JVM-level error, *not* a re-frame error:

```
java.lang.NoSuchFieldError: ... shadow.build.closure.JsInspector ... (e.g. a Node / PARSE_RESULTS field)
```

This is **not** a migration bug, a coord conflict, or anything in the app's own source. It is shadow-cljs and the Closure compiler being out of lockstep — the older shadow-cljs binary was compiled against an older Closure API surface than the one now on the classpath via the newer CLJS.

**What to check.** Compare the project's `shadow-cljs` version (in `package.json` `devDependencies`, or `shadow-cljs.edn`'s own dep) and its ClojureScript pin against the versions re-frame2 builds with. The authoritative reference is **re-frame2's own `implementation/package.json` (the `shadow-cljs` pin) and `implementation/core/deps.edn` (the `org.clojure/clojurescript` pin)** in the pinned checkout — read the actual values there rather than hard-coding a number, since they move release to release. (At the time of writing the reference builds against **shadow-cljs `3.4.10`** and **ClojureScript `1.12.145`**.)

**What to do.** If the project's shadow-cljs (and/or its CLJS pin) predates re-frame2's, **bump shadow-cljs to the version re-frame2 builds with** (and let the newer CLJS ride in via the re-frame2 deps, or bump the project's explicit CLJS pin to match). This is a `package.json` `devDependencies` edit (`"shadow-cljs": "<reference-version>"`) plus a `npm install` — the **author** runs the install (cardinal rule 5); the skill identifies the skew and prints the target version. A shadow-cljs from the same era as the project's old CLJS will almost always be too old, so for any v1 app that has not touched its build toolchain recently, **expect this bump** — surface it in the gate rather than letting it detonate as a `NoSuchFieldError` mid-compile, where it reads like a migration failure and sends the operator chasing a non-existent re-frame bug.

(Distinct from the **classpath-collision** edge case below, which is a *coord conflict* over `re-frame/re-frame`. This is a *version-skew* between two build-tool components, not a duplicate dependency — different symptom, different fix.)

### Check 5 — Explicit go / no-go

Decide and record the gate outcome before proceeding:

- **GO** — React already at 19 (or cleanly bumpable), and every Check-1/Check-2 library has a React-19-compatible target **or an empirically-verified runtime pass** (Check-2 option 4), and Check-3 is empty or its call sites are slated for the `createRoot` rewrite, and Check-4's shadow-cljs/CLJS toolchain is current or slated to be bumped to the reference version. Carry the React/Reagent bump, any component-lib bumps, and the shadow-cljs bump into the M-0 pass below, then continue. (For a project already on React 19 with a React-19-ready component library and a current toolchain, this is the fast path — the gate adds minutes, not weeks.)
- **NO-GO** — any Check-2 component library (or a load-bearing Check-1 dep) has no React-19 release **and** no empirical runtime pass. **Stop here.** Do not edit any dep coord. Report the blocker and the options to the author; the migration resumes once the blocker is resolved. (Check-4 toolchain skew is **not** a NO-GO — it is a known, mechanical shadow-cljs bump carried into M-0; flag it so it isn't mistaken for a migration bug mid-compile.)

**The React/Reagent bump itself**, once the gate is GO. If `package.json` pins `react`/`react-dom` to 17 or 18, bump both to `^19` (Reagent users are simultaneously on Reagent 2.x — that rides in via the `day8/re-frame2-reagent` adapter, not a separate `package.json` pin unless the project pins Reagent directly):

```json
"dependencies": {
  "react":     "^19.0.0",
  "react-dom": "^19.0.0"
}
```

The author then runs `npm install` (or the project's package-manager equivalent) before the first dev build. If the project is already on React 19, leave it alone; do not downgrade.

---

## The coord swap (M-0)

Run this only after the floor gate above returns **GO**. Carry every GO-state edit the gate identified — the dep-coord swap, the React/Reagent bump, any component-lib bumps, and the shadow-cljs/CLJS toolchain bump (Check 4) — into this one dep-file pass, so the post-M-0 compile runs against the fully-current toolchain (an older shadow-cljs detonates the first compile with the cryptic `NoSuchFieldError`, Check 4). The **author runs the `npm install`** (cardinal rule 5); the skill makes the `package.json` edits and prints the install command.

### The swap itself

v1 ships as `re-frame/re-frame`. v2 ships as a **pair** of artefacts at the same VERSION:

- `day8/re-frame2` — the core (registry, drain, dispatch, subscribe, fx, the substrate-adapter contract).
- `day8/re-frame2-<substrate>` — the substrate adapter (`-reagent`, `-uix`, or `-helix`). v1 codebases use Reagent universally, so default to `day8/re-frame2-reagent`.

The two artefacts ship in lockstep — every adapter artefact is versioned identically to core. Mixing versions across them is unsupported.

The `re-frame.core` namespace name and your `(:require [re-frame.core :as rf])` lines are **unchanged**. Only the dep coord moves.

### Neutralize the re-frame-10x preload as part of M-0

If the project preloads `day8.re-frame-10x.preload` (or otherwise references re-frame-10x at build time — a `day8.re-frame/re-frame-10x` dev-dep coord, a `:preloads [day8.re-frame-10x.preload]` entry, or a `closure-defines` 10x flag), **neutralize that preload as part of M-0**. Excluding v1 `re-frame` (which M-0 forces, to stop the classpath collision) makes `day8.re-frame-10x.preload` uncompilable — it `:require`s v1 `re-frame` internals that no longer resolve. The dev build still references the preload, so the very first thing this skill asks you to do after M-0 — *"stop and compile, see what breaks"* — fails on the **dead preload**, not on your application code, before any real M-rule breakage can surface.

So, in the same M-0 dep-file edit:

- **Remove the `:preloads` entry** `day8.re-frame-10x.preload` (and any 10x dev-module / `closure-defines` 10x flag).
- The 10x **Maven dev-dep coord** itself (`day8.re-frame/re-frame-10x`) also goes — see [`xray-replaces-10x.md`](xray-replaces-10x.md) for the full dep+preload drop and the Xray replacement.

The point at M-0 is narrow: clear the dead 10x preload **now** so the post-M-0 compile gate is reachable — don't leave it blocking the immediate "stop and compile" step. The Xray restore is **post-M-40** (its preload auto-opens after `(rf/init!)`, so it can't mount until boot wiring is in place — a sequencing detail, not a downgrade to optional). For a 10x app the restore is a standard step whose done-state is the app on Xray; see [`xray-replaces-10x.md`](xray-replaces-10x.md) for the restore and the 10x-present/no-10x rule.

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

### Mixed-substrate projects — the compile-closure rule (not "add only one")

If the codebase has **both** Reagent and UIx (or Helix) requires — a phased substrate migration mid-flight — the rule is **NOT "add only the root-driving adapter."** Each adapter's `re-frame.adapter.<substrate>` namespace lives in its **own artefact** (`day8/re-frame2-reagent` / `-uix` / `-helix` are separate coords — per [`spec/Conventions.md` §adapter packaging](../../../spec/Conventions.md)), and a wrong-substrate adapter ns is **structurally absent** from the classpath unless its artefact is present. So adding only one adapter leaves any namespace that `:require`s the *other* substrate's `re-frame.adapter.*` ns **failing to resolve at compile/load** — that is not a smallest-correct migration, it is an untracked compile break.

Two things are different and must be tracked **separately** in the report:

1. **Artefacts needed for compilation** — add **every** adapter artefact whose `re-frame.adapter.<substrate>` namespace is `:require`d by source that **stays on the build classpath**. Compute the closure: grep the surviving source for `re-frame.adapter.reagent` / `.uix` / `.helix` requires and add the matching artefact for each. (UIx/Helix adapter-specific surfaces live in `re-frame.adapter.uix` / `re-frame.adapter.helix` and are NOT re-exported from `re-frame.core` — per [`spec/API.md`](../../../spec/API.md) — so a require of them is real and must resolve.)
2. **Adapter chosen for boot** — install **exactly one** active adapter at each `rf/init!` site (the boot contract allows mixed-substrate *imports* while installing one active adapter; per [`spec/006-ReactiveSubstrate.md` §boot](../../../spec/006-ReactiveSubstrate.md)). Pick whichever substrate drives the React root; pass that adapter's var to `init!`.

If the author wants the non-root substrate **out of scope** (a real, common choice), don't silently leave its views broken — either **isolate/exclude** the non-root substrate namespaces from the migrated build (so they're not on the classpath and need no adapter artefact), or **stop with a documented follow-up** naming the namespaces still on the other substrate. Knowingly shipping broken views while claiming the v1→v2 migration "succeeded" is the failure this rule prevents.

## Pin the migration corpus before reading it

[`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) is the contract for every rewrite, so it must be **pinned**, not fetched live. Load it from a **local checkout of `day8/re-frame2` pinned to a specific commit or tag** — an unpinned remote fetch makes every migration depend on whatever happens to be on `main` that minute, and a non-reproducible corpus is a non-reproducible migration. Before reading the corpus, verify the checkout:

```bash
git -C <path-to-re-frame2> rev-parse HEAD          # the pinned commit
git -C <path-to-re-frame2> remote get-url origin   # confirm it's day8/re-frame2
```

These two are **read-only provenance checks the skill runs itself** — allow-listed (the scoped `Bash(git -C * rev-parse *)` / `Bash(git -C * remote get-url *)` entries in `SKILL.md`'s `allowed-tools`), on the same read-only side of the trust boundary as `rg`, not the author-runs-it compile/test/install/smoke class ([`SKILL.md` cardinal rule 5](../SKILL.md)). Do **not** fetch `MIGRATION.md` from GitHub at runtime. **Record the pinned hash in the migration report** ([`output-format.md`](output-format.md)) alongside the chosen `<v2-version>` (next section) — both pin the migration to a reproducible point.

## Discovering the current VERSION

**The author picks the target VERSION; the skill never auto-selects "latest".** The kickoff prompt names a specific `<v2-version>` string — that's the contract. If `<v2-version>` is unset (the author left a placeholder), **stop and ask** before editing any dep file.

For the author's reference (so they can pick), three sources of authoritative version info:

1. **`VERSION` file** in the local pinned `day8/re-frame2` checkout (`<path-to-re-frame2>/VERSION`) — the string used for the next release.
2. **`CHANGELOG.md`** in the pinned checkout — released versions with summaries; the most recent non-Unreleased entry is the latest released version.
3. **GitHub releases page** (`https://github.com/day8/re-frame2/releases`) — for cross-referencing tags, but the local pinned checkout is the authoritative source for *this* migration.

If the author wants the bleeding edge, they can use a `:git/url` + `:git/sha` coord instead of `:mvn/version` — but they still type the SHA into the kickoff prompt; the skill does not pick. Niche; default to released `:mvn/version`.

**Never invent a version; never silently pick `latest`** — newly published packages may be broken or malicious, and unpinned coords make the migration non-reproducible. Record the chosen `<v2-version>` in the migration report.

**If no released v2 version exists yet** (pre-publication): leave the dep alone, do not apply any other migration rules, and flag the situation in the report — the author must update the coord manually once a release lands, then re-run the migration.

## The pay-as-you-go artefact split (M-27 through M-32)

re-frame2 splits seven per-feature artefacts out of core. **Add them only when the codebase actually uses the feature.** Do not add them defensively.

| Artefact | Add when codebase uses... |
|---|---|
| `day8/re-frame2-schemas` | `reg-app-schema`, or `:schema` keys in registration metadata (incl. `:schema` on `reg-event-*` for event-payload schemas — the key is `:schema` post-M-54, was `:spec` pre-M-54) (M-27) |
| `day8/re-frame2-machines` | `reg-machine` (M-28) |
| `day8/re-frame2-routing` | `reg-route` or dispatches `:rf.route/*` events (M-29) |
| `day8/re-frame2-flows` | `reg-flow` (M-30) — also where v1's `on-changes` interceptor migrates to |
| `day8/re-frame2-http` | `[:rf.http/managed ...]` as an `:fx` entry, or `:rf.http/managed` as a child machine (M-31) |
| `day8/re-frame2-ssr` | `render-to-string` server-side (M-32) |
| `day8/re-frame2-epoch` | `epoch-history`, `restore-epoch`, or transitively via `re-frame2-pair` (no M-rule; pull only if directly used) |
| `[re-frame.http.test-support]` (test-ns require, not a Maven dep) | managed-HTTP canned-stub fxs (`:rf.http/managed-canned-success` / `-canned-failure`, M-31a) or the stub macros (`with-managed-request-stubs` family, M-65) appear in test code — add the require or the `rf/<stub>` re-exports raise `:rf.error/http-artefact-missing` |

In practice: most v1 codebases use **none** of these, because none of these features exist in v1. State machines / flows / managed-HTTP / SSR are v2 additions. v1 codebases doing equivalent things by hand stay doing them by hand post-migration; you do **not** rewrite those into the new artefacts as part of the required migration. (Adopting them is opt-in; see the `O-N` rules.)

The one exception is `-flows` — v1's `on-changes` interceptor (one of the removed five, per M-21) migrates to `reg-flow`, so if the codebase used `on-changes`, add `day8/re-frame2-flows` at the same time you apply M-21.

## Edge cases

**`shadow-cljs.edn` with `:dependencies` AND `deps.edn` with `:deps` — which wins?** shadow-cljs reads from both; `:dependencies` in `shadow-cljs.edn` is additive. Update whichever currently holds the `re-frame/re-frame` coord — that's the one in scope. If both hold it (rare), update both.

**Lein with `:profiles` overlays.** If the project pins `re-frame` in `:dependencies` and overrides it in `:profiles {:dev {:dependencies ...}}`, update both — the profile override would otherwise shadow the swap silently.

**`re-frame` as a transitive of another lib (`re-frame-fx`, `day8/re-frame-async-flow-fx`, etc.).** v1-built libs depend on `re-frame/re-frame`; their classpath will trip a coord conflict with `day8/re-frame2`. Two options:

1. Upgrade the lib to a re-frame2-compatible version if one exists.
2. Exclude `re-frame/re-frame` from the transitive (`:exclusions` in Lein, `:exclusions` in deps.edn) and let `day8/re-frame2` provide `re-frame.core`. This works because v2 keeps the `re-frame.core` namespace; the lib's `:require [re-frame.core :as rf]` lines resolve against v2 instead.

Flag this case in the report — the author owns the decision about whether to upgrade the transitive lib or to exclude.

**The leak is not limited to Maven add-ons — *any* classpath entry can root a v1 `re-frame/re-frame`.** The named add-ons (`re-frame-fx`, `async-flow-fx`) are the obvious culprits, but a v1 `re-frame` can also arrive transitively through:

- a **git/source dependency** (a `:git/url` + `:git/sha` dep in deps.edn, or a `:git-dependencies` lib) that declares its own `re-frame/re-frame` in *its* deps;
- **vendored source** on `:paths` / `:extra-paths` that carries a bundled re-frame, or a `:local/root` dep whose own deps pull v1 in;
- a deeper transitive — a lib that depends on a lib that depends on `re-frame/re-frame` (a second- or third-order edge the obvious add-on exclusion never touches).

Excluding the *named* add-ons and re-compiling, only to still see `re-frame.core` resolving to v1, is the classic symptom: the obvious add-on was a red herring and the real root is a git/vendored/deep-transitive edge. Don't chase it by guessing — read the full dependency tree.

**Verification step — prove the classpath is clean before you compile.** After the exclusion sweep, run a tool-appropriate classpath check filtered for `re-frame` and confirm **no v1 `re-frame/re-frame` artefact remains** on the classpath, *before* attempting the post-M-0 compile. Don't trust the exclusion list — prove the classpath is clean. (Per cardinal rule 5 the **author** runs the command; the skill prints it.)

- **tools.deps (`deps.edn`):** `clojure -Stree` shows the full resolved tree — search the output for any `re-frame/re-frame` node (it should be absent; only `day8/re-frame2` + the adapter should appear). `clojure -Spath` prints the realised classpath — it must contain **no** `re-frame/re-frame` jar (look for a `re-frame/re-frame/<1.x.x>/…jar` Maven-cache path). For an `:aliases`-gated dev/test classpath, run the check **under the same aliases** the build uses (e.g. `clojure -A:dev -Stree`), because a leak can hide behind a profile/alias.
- **Leiningen (`project.clj`):** `lein deps :tree` (and `lein with-profile +dev deps :tree` for profile overlays) — confirm no `[re-frame "1.x.x"]` node survives.
- **shadow-cljs:** `npx shadow-cljs classpath` (or inspect the resolved deps it prints on build) — grep the output for `re-frame/re-frame`. If shadow reads deps from `deps.edn`, the `clojure -Stree`/`-Spath` checks above are authoritative.

Only once the check shows a single source of `re-frame.core` (v2) is it safe to compile. A surviving v1 jar means `re-frame.core` may resolve to v1 at compile/load time regardless of which coords you *think* you swapped.

**Per-feature artefact not yet published.** Same shape as M-0's "no v2 version" edge case: leave the dep alone, flag in the report, the author updates manually when the artefact lands.

---

**Stop after M-0.** Do not start sweeping for other M-rules until you've tried a compile and seen what — if anything — breaks. The expected result for most codebases is that the dep swap is the entire migration. Verify that before sweeping.
