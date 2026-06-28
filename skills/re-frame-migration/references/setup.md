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
- The optimized / release compile gate (`-Xss` for the StackOverflow class)

---

> **Delegated / orchestrated / CI execution.** Every "the **author** runs it" below is the *interactive* default — the skill prints the `npm install` / compile / classpath-check command and waits for the pasted result. Under delegated / orchestrated / CI execution the executor is a *sandboxed autonomous worker* (an isolated git worktree) or a *CI runner*: it runs the printed command **itself, inside its own sandbox** and posts command + result for human ratification at PR review. The trust boundary — *arbitrary-code execution* — is unchanged; the sandbox stands in for the human's machine. See [`SKILL.md` cardinal rule 5 — the sandboxed-executor exception](../SKILL.md).

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

**Verify a candidate component-lib branch by its pins, not its name** — the same *identity-by-structure* trap as the corpus-pin step below. A branch named `feature/reagent-upgrade` (or `react-19`, `next`, …) is **not** thereby a React-19 / Reagent-2 build; the name is a label someone typed, often onto an **abandoned spike** that still pins old `react` / `reagent` and was last touched months ago. Confirm the candidate by its **actual `react` / `reagent` pins** (read the branch's `package.json` / `deps.edn` at that ref) **and its last-commit recency** (`git log -1`), never by the branch name. A stale-pinned, long-untouched "upgrade" branch is a NO-GO dressed as a GO.

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

**The JVM Clojure floor rides with the same bump.** shadow-cljs 3.x runs on the JVM against `org.clojure/clojure`, and it requires **Clojure 1.12+**. A project that bumps shadow-cljs to the reference version but leaves an older `org.clojure/clojure` on the JVM does **not** get a clean "needs Clojure 1.12" message — the build detonates with the cryptic reader/Closure error `Invalid token: byte/1`, which reads like a corrupt source file or a migration bug, not a dep-floor problem. So whenever you carry the shadow-cljs bump, confirm `org.clojure/clojure` is at the reference floor too: read the actual pin re-frame2 builds against in the pinned checkout — `implementation/core/deps.edn` (the `org.clojure/clojure` coord; `implementation/deps.edn` is the top-level coordinator) — rather than hard-coding a number, since it moves release to release (it is **1.12.x** at the time of writing). If the project's Clojure predates that floor, bump it in the same dep-file pass as shadow-cljs; a too-old Clojure surfaces as that same misleading `Invalid token: byte/1`, never as a re-frame error.

**A green one-shot `compile` does not prove the dev `watch` server starts.** The `byte/1` reader form lives in shadow-cljs 3.4.x's **watch-server** namespace, which a one-shot `shadow-cljs compile` never loads — so the compile can pass on a too-old (1.11) JVM Clojure and the `watch` / release server then dies at startup with the same `Invalid token: byte/1`. Verify the toolchain with an actual `watch` (the boot-smoke needs a dev server anyway), not just a one-shot compile.

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

**Expect `npm install` to ERESOLVE-fail against any JS dep still pinned to a React `<19` peer.** The moment `react`/`react-dom` move to `^19`, npm's resolver rejects the whole tree if *any* remaining JS dependency still declares a `react` peer below 19 — a categorically common case in a view-heavy app (an animation library, a UI-component kit, a date-picker, a chart or drag-and-drop lib, etc., still pinned to `^18`). The install aborts with `npm error ERESOLVE could not resolve` / `peer react@"^18..." from <lib>`, and **the CLJS compile cannot even start without `node_modules`** — so this blocks the post-M-0 compile gate *before any re-frame rule runs*, and it reads like a setup failure rather than the dependency-floor issue it is.

The **interim unblock to reach the compile gate** is `npm install --legacy-peer-deps` (or `--force`): it installs the tree despite the unmet React peer so the build can proceed and the migration sweep can continue. Treat that flag as **scaffolding, not a destination** — the peer-pinned JS deps it papers over are a **real to-do, not resolved**: bump each to a release that admits React 19 (this is the same Check-1 / Check-2 bump work above), so that `npm install` resolves *cleanly without the flag* **and** the library is actually runtime-safe on React 19. Do not leave `--legacy-peer-deps` as the project's permanent state — it silences the resolver, it does not make a React-`<19` library work under React 19; a lib that only *installs* under the flag can still mis-render or throw at runtime. Surface the still-pinned deps as a tracked follow-up alongside the GO decision.

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

> **This is HALF ONE of a single mandatory swap — not a standalone step.** For a 10x app the 10x → Xray swap is a **REQUIRED deliverable** (detected in the Phase-0a inventory; done-state = the app *on Xray*). It is **one swap with two halves straddling the sweep**: (1) **here, at M-0** — neutralize the dead preload so the compile gate is reachable; (2) **post-M-40** — mount Xray once `init!` exists ([`xray-replaces-10x.md` §The swap](xray-replaces-10x.md#the-swap-dep--preload)). Landing half one without half two **leaves the author worse off than they started** (10x gone, no replacement). Track both halves as **one item** through to the app-on-Xray done-state; do not check this off as complete until half two has landed.

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

[`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) is the contract for every rewrite, so it must be **pinned**, not fetched live. Load it from a **local checkout of `day8/re-frame2` pinned to a specific commit or tag** — an unpinned remote fetch makes every migration depend on whatever happens to be on `main` that minute, and a non-reproducible corpus is a non-reproducible migration.

> **A branch or remote NAME is not an IDENTITY — verify by structure and pins, never by name.** That a checkout's `origin` is named `day8/re-frame2`, or that its branch/tag carries the expected name, does **not** prove it is the re-frame2 you mean. Names are cheap and collide; the only proof is the commit it resolves to **and the structure that commit carries**.

Before reading the corpus, verify the checkout — commit, remote, **and layout**:

```bash
git -C <path-to-re-frame2> rev-parse HEAD                                                    # the pinned commit
git -C <path-to-re-frame2> remote get-url origin                                             # confirm origin is day8/re-frame2 (NAME only)
git -C <path-to-re-frame2> ls-tree <SHA> implementation/core/deps.edn implementation/adapters  # STRUCTURE: the multi-artifact layout exists at this ref
```

These three are **read-only provenance checks the skill runs itself** — allow-listed (the scoped `Bash(git -C * rev-parse *)` / `Bash(git -C * remote get-url *)` / `Bash(git -C * ls-tree *)` entries in `SKILL.md`'s `allowed-tools`), on the same read-only side of the trust boundary as `rg`, not the author-runs-it compile/test/install/smoke class ([`SKILL.md` cardinal rule 5](../SKILL.md)). The first two prove the commit and the remote NAME; the third proves **identity by structure** — a `git ls-tree` / path-existence read, not project-code execution. Confirm at the pinned ref that the **multi-artifact monorepo** layout is actually present: `implementation/core/deps.edn` (the core artefact), `implementation/adapters/<substrate>` (`reagent` / `uix` / `helix`), and the per-feature `implementation/<feature>` dirs the app pulls (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`, `resources` — each its own artefact dir). Confirm too that the substrate floor is **Reagent 2 / React 19** by reading `implementation/package.json` at that same ref (`react` / `react-dom` pinned to `19`). **Why this matters:** the two NAME probes pass even for an **old single-artifact ancestor** — a pre-monorepo-split commit, branch, or tag under the very same `day8/re-frame2` origin — where `implementation/` does not yet exist in this shape and the per-feature artefacts the migration depends on are simply absent at that ref. Pre-publish, several re-frame2 source checkouts on disk are **normal**, so this name-collision is common, not exotic: without the structure read the migration silently reads an **obsolete corpus** and plans against artefacts that do not exist at the pinned ref. Do **not** fetch `MIGRATION.md` from GitHub at runtime. **Record the pinned hash in the migration report** ([`output-format.md`](output-format.md)) alongside the chosen `<v2-version>` (next section) — both pin the migration to a reproducible point.

## Discovering the current VERSION

**The author picks the target VERSION; the skill never auto-selects "latest".** The kickoff prompt names a specific `<v2-version>` string — that's the contract. If `<v2-version>` is unset (the author left a placeholder), **stop and ask** before editing any dep file.

For the author's reference (so they can pick), three sources of authoritative version info:

1. **`VERSION` file** in the local pinned `day8/re-frame2` checkout (`<path-to-re-frame2>/VERSION`) — the string used for the next release.
2. **`CHANGELOG.md`** in the pinned checkout — released versions with summaries; the most recent non-Unreleased entry is the latest released version.
3. **GitHub releases page** (`https://github.com/day8/re-frame2/releases`) — for cross-referencing tags, but the local pinned checkout is the authoritative source for *this* migration.

If the author wants the bleeding edge, they can use a `:git/url` + `:git/sha` coord instead of `:mvn/version` — but they still type the SHA into the kickoff prompt; the skill does not pick. Niche; default to released `:mvn/version`. If the author is migrating against an **unpublished** re-frame2 from a **sibling checkout** (a `:local/root` coord per artefact), the re-frame2-setup skill carries the copy-pasteable recipe with the verified per-artefact paths: [`deps-versions.md` §The `:local/root` sibling-checkout dev route](../../re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish).

**Never invent a version; never silently pick `latest`** — newly published packages may be broken or malicious, and unpinned coords make the migration non-reproducible. Record the chosen `<v2-version>` in the migration report.

**If nothing is published to Clojars yet** (pre-publication): the migration is still fully doable — a first release is **not** a precondition. When no `:mvn/version` resolves, the author consumes re-frame2 via a **`:local/root`** sibling-checkout coord ([`deps-versions.md` §The `:local/root` sibling-checkout dev route](../../re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish)) or a **`:git/url` + `:git/sha`** coord, and the migration proceeds normally — apply every M/O-rule exactly as you would against a published target. Do **not** leave the dep alone, and do **not** stop and wait for a release. The guardrails are unchanged: never invent a version, never silently pick `latest`, and the author still supplies the pin or route — the skill never picks it for them. "Stop and ask" applies only when the author has supplied **no consumption route at all** (no `:mvn/version`, no `:git/sha`, no `:local/root`), *not* merely because nothing is on Clojars yet. Record the chosen route — the sibling-checkout path or the pinned SHA — in the migration report, exactly as a `<v2-version>` would be recorded.

## The consumability done-gate

The `:local/root` sibling-checkout route above (and in [`deps-versions.md` §The `:local/root` sibling-checkout dev route](../../re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish)) is a **dev convenience, not a shippable coordinate** — that route is the SETUP half; this is the **UNWIRE** half that complements it. A `:local/root` coord resolves to an **absolute path on the author's own disk**: `{:local/root "../re-frame2/implementation/core"}` names *this* machine's sibling checkout, nothing a clean runner can find. So a migration that consumes pre-publish re-frame2 (the monorepo modules + Xray) and any forked upstream entirely through `:local/root` paths can compile 0/0, boot, and pass the boot smoke-test **locally** — looking "done" — while **every CI run is red from the first step**, because the runner has no such paths:

```
Error building classpath. Local lib day8/re-frame2-reagent not found: ...
```

**The done-gate:** before the migration is "done", repin **every** re-frame2 (and forked-upstream) dep to a coordinate a **clean runner can resolve**:

- **`:git/url` + `:git/sha` pinned to a PUSHED commit** — the pragmatic pre-publish coord (no Maven release required). One coord per artefact, each a `{:git/url … :git/sha … :deps/root "implementation/<subdir>"}` git-subdir coord — **one `:deps/root` per monorepo module**. Shapes in [`deps-versions.md` §Choosing the coordinate](../../re-frame2-setup/references/deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape).
- **`:mvn/version`** — once the artefacts are published to a registry the runner can reach.

**Any forked or extended upstream MUST be pushed.** A `:git/sha` that exists only in a local commit is no more resolvable than a `:local/root` path — CI cannot fetch an unpushed SHA. Push the fork's branch so its SHA is fetchable, then pin to it.

The real done-signal is therefore **CI green on a clean checkout**, not a green local build — see [`runtime-smoke-test.md` §The done-bar is more than the local dev build](runtime-smoke-test.md#the-done-bar-is-more-than-the-local-dev-build). Record the final clean-runner-resolvable coords in the migration report, exactly as the chosen `<v2-version>` / route is recorded above.

## The pay-as-you-go artefact split (M-27 through M-32)

re-frame2 splits seven per-feature artefacts out of core. **Add them only when the codebase actually uses the feature.** Do not add them defensively.

| Artefact | Add when codebase uses... |
|---|---|
| `day8/re-frame2-schemas` | `reg-app-schema`, or `:schema` keys in registration metadata (incl. `:schema` on `reg-event-*` for event-payload schemas — the key is `:schema` post-M-54, was `:spec` pre-M-54) (M-27) |
| `day8/re-frame2-machines` | `reg-machine` (M-28) |
| `day8/re-frame2-routing` | `reg-route` or dispatches `:rf.route/*` events (M-29) |
| `day8/re-frame2-flows` | `reg-flow` (M-30) — also where v1's `on-changes` interceptor migrates to. M-30 also carries the v1→v2 flow-map conversion (Type-B `:live?` re-home) — see [`breaking-changes.md` §M-30](breaking-changes.md#m-30-also-carries-the-flow-map-conversion) |
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

## The optimized / release compile gate — raise `-Xss` for the StackOverflow class

The post-M-0 compile gate and the Phase-4 done-bar are **two compiles, not one**: the dev (`:none`) build that every other gate runs (the convergence compile, the `watch` the boot smoke-test boots under, the unit suite), and the **optimized / release compile** (`:simple` / `:advanced` — `shadow-cljs release`, or whatever command CI runs). Only the optimized compile runs Closure's whole-program passes, so it is a **separate done-bar gate** — see [`runtime-smoke-test.md` §The dev compile is NOT the optimized compile](runtime-smoke-test.md#the-dev-compile-is-not-the-optimized-compile--the-fourth-done-bar-gate) for why a migration is the build most likely to fail it (the artefact-split + `reg-view` enlargement deepens the AST until a recursive Closure pass like `RemoveUnusedCode` overruns the JVM compile thread's stack and throws `StackOverflowError`).

**The remedy: raise the compile JVM's thread stack.** Give the JVM that runs the optimized compile a larger `-Xss` (thread stack size) — start at `8m`, go to `16m` if it still overruns. That buys headroom for the deeper recursion the enlarged AST drives; it changes not a byte of the emitted output.

**The shadow-cljs `:jvm-opts` placement gotcha — get this exactly right, or the `-Xss` silently does not apply.** *Where* you set `-Xss` decides whether it reaches the compile JVM at all, because shadow-cljs compiles via two different JVM-launch paths:

- **`shadow-cljs.edn`'s `:jvm-opts`** applies **only** when the **node launcher** (`npx shadow-cljs …`) starts the JVM.
- It does **not** apply when the compile runs through the **Clojure CLI** (`clojure -M… -m shadow.cljs.devtools.cli compile|release`): there the JVM is launched by `clojure`, which reads its `-Xss` from the **`deps.edn` alias `:jvm-opts`** instead (or from `JAVA_TOOL_OPTIONS`, or a `-J-Xss…` flag on the command line).

**CI commonly drives the optimized compile through the Clojure-CLI path**, so an `-Xss` set only in `shadow-cljs.edn` silently has no effect there — the optimized compile keeps overflowing while the config *looks* correct. Set `-Xss` on **whichever launcher actually starts the compile**: the `shadow-cljs.edn` `:jvm-opts` for the `npx shadow-cljs` path, the consuming alias's `deps.edn` `:jvm-opts` (or `JAVA_TOOL_OPTIONS` / a `-J-Xss…` flag) for the `clojure -M` path. When unsure which path CI uses, set both.

(This is a stack-**depth** `StackOverflowError` in the **optimized** compile of the migrated *app's own* code, cured by `-Xss` headroom — a distinct failure from any dev-build transpile-down error, which `-Xss` would not touch.)

---

**Stop after M-0.** Do not start sweeping for other M-rules until you've tried a compile and seen what — if anything — breaks. The expected result for most codebases is that the dep swap is the entire migration. Verify that before sweeping.
