# floor gate

The Phase-0b pre-flight, in operational detail: the six React-19 / Reagent-2 checks that run **before any dep edit**, and the explicit go/no-go they produce. Clear this gate first — the M-0 coord swap and everything downstream of it live in [`setup.md`](setup.md).

---

## The React-19 / Reagent-2 floor gate (pre-flight — run before M-0)

> **This is a go/no-go gate, not a footnote.** Run all six checks below *before* you touch any dep coord. For a codebase already on React 19 *and* a current build toolchain it is a fast pass — read the checks, confirm each, move on. For the rest of the v1 population (overwhelmingly React 17/18 + Reagent 1.x, often on a years-old shadow-cljs) this gate is the single largest and riskiest part of the whole migration, and the blocking cases — the component-library blocker (Check 2) and the toolchain-skew compile failure (Check 4) — must be discovered here, not deep inside a failed compile loop after the coord swap.

**Why this comes first.** re-frame2's substrate adapters target React 19. The `day8/re-frame2-reagent` bridge runs on Reagent 2.x, which ships against React 19 (it loads `reagent.dom.client` — the `createRoot` path); Reagent 1.x is no longer supported. UIx hits the same floor — both substrates target React 19. So a v1 codebase on React 17/18 must clear a forced React → 19 (and, for Reagent, Reagent → 2.x) upgrade *and the cascade it drags in* — the component library, React-coupled JS deps, and any hand-rolled render call site — before the coord swap can succeed. If `package.json` still pins `react`/`react-dom` to 17 or 18 when the swap lands, the build either fails at module-resolve time or compiles and then crashes on first render against React-19-only API surfaces.

The six checks, in order (Checks 1–3 are the React side; Check 4 is the ClojureScript-toolchain side; Check 5 is the CI/test-runner browser floor; Check 6 is the go/no-go):

### Check 1 — Downstream React-lib compatibility audit

Enumerate every `package.json` dependency that declares a `react` or `react-dom` **peer dependency** — these are the JS libraries that will break if React's major version moves under them. Typical culprits in a view-heavy app: animation, toast/notification, portal/modal, drag-and-drop, virtualised-list, date-picker, and chart libraries.

For each one, check its current React-19 support (its published `peerDependencies` range, its release notes, or its changelog). Produce a list with three buckets:

- **Already React-19-compatible** — peer range admits `19` (e.g. `^18 || ^19`, `>=18`). No action.
- **Needs a bump** — a newer release of the *same* library supports React 19. Note the target version.
- **Needs a replacement / has no React-19 release** — the library is abandoned or has not shipped React-19 support. This is a blocker dimension; surface it to the author for a decision (upgrade path, replacement library, or hold the migration).

You can list the peer-dependency declarations with a read-only search over the lockfile / installed packages — e.g. `rg -l '"react"' node_modules/*/package.json` (or the package manager's own `why`/`ls` for `react`). The skill enumerates and reports here; the bumps the gate approves ride into the M-0 pass, where the skill runs the install (cardinal rule 5).

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

Scan the source for **every** legacy mount call site — the Reagent-routed root mount as well as any surviving hand-rolled React-DOM one. Both migrate to the `createRoot` API in the same pass.

**The Reagent-routed mount is the common case, not the rare one — and it is the silent one.** Most v1 codebases mount through `reagent.dom/render` (or `reagent.core/render`), so this check normally *hits*; an empty result on a Reagent app means the pattern missed, not that there is nothing to do. On the classic bridge that Var still resolves, but React 19 removed the `react-dom/render` beneath it: it warns to the console and returns **without mounting**. The build is clean, the suite is green, and the page is blank — no throw, no `:rf.error/*` trace. (On the slim adapter the same call site fails loudly at compile time instead, as an unresolved var.) The hand-rolled `ReactDOM.render` / `ReactDOM.hydrate` / `ReactDOM.unmountComponentAtNode` sites are the rarer half — removed in React 18 and still gone in React 19 — but a survivor is a runtime crash, so sweep for both in one pass.

A read-only search over the source tree surfaces them:

```bash
rg -n 'ReactDOM\.(render|hydrate|unmountComponentAtNode)|reagent\.(dom|core)/render|\b(rdom|r|reagent)/render\b|unmount-component-at-node'
```

Flag each for the author, the test-harness mounts included. The rewrite itself — `create-root` + `render`, in the namespace keyed to whichever adapter M-0 commits — belongs to **M-42**: [`guided-handlers-state.md` §M-42](guided-handlers-state.md#m-42--react-19-removed-reagent-surfaces-bridge-and-slim). Don't re-derive it here.

### Check 4 — CLJS / shadow-cljs / Closure-compiler toolchain-skew check (hits almost every older shadow-cljs app)

Checks 1–3 cover the **React side** of the floor. This check covers the **ClojureScript side** — the `ClojureScript ↔ shadow-cljs ↔ Closure-compiler` coupling — and it is just as much a pre-flight blocker, because it surfaces as a *compile* failure that **looks like a migration bug but is pure toolchain skew**.

**The coupling.** re-frame2 core pins a recent ClojureScript, which in turn carries a recent **Google Closure Compiler**. shadow-cljs invokes the Closure compiler through internal classes whose field/method signatures track the Closure version. If the project's shadow-cljs predates the Closure that re-frame2's CLJS pin drags in, the **older shadow-cljs calls a Closure internal that has changed shape** — and the build dies with a cryptic JVM-level error, *not* a re-frame error:

```
java.lang.NoSuchFieldError: ... shadow.build.closure.JsInspector ... (e.g. a Node / PARSE_RESULTS field)
```

This is **not** a migration bug, a coord conflict, or anything in the app's own source. It is shadow-cljs and the Closure compiler being out of lockstep — the older shadow-cljs binary was compiled against an older Closure API surface than the one now on the classpath via the newer CLJS.

**What to check.** Compare the project's `shadow-cljs` version (in `package.json` `devDependencies`, or `shadow-cljs.edn`'s own dep) and its ClojureScript pin against the versions re-frame2 builds with. The authoritative reference is **re-frame2's own `implementation/package.json` (the `shadow-cljs` pin) and `implementation/core/deps.edn` (the `org.clojure/clojurescript` pin)** in the pinned checkout — read the actual values there rather than hard-coding a number, since they move release to release. (At the time of writing the reference builds against **shadow-cljs `3.4.10`** and **ClojureScript `1.12.145`**.)

**What to do.** If the project's shadow-cljs (and/or its CLJS pin) predates re-frame2's, **bump shadow-cljs to the version re-frame2 builds with** (and let the newer CLJS ride in via the re-frame2 deps, or bump the project's explicit CLJS pin to match). This is a `package.json` `devDependencies` edit (`"shadow-cljs": "<reference-version>"`) plus an `npm install`, which the skill runs (cardinal rule 5) once it has identified the skew and the target version. A shadow-cljs from the same era as the project's old CLJS will almost always be too old, so for any v1 app that has not touched its build toolchain recently, **expect this bump** — surface it in the gate rather than letting it detonate as a `NoSuchFieldError` mid-compile, where it reads like a migration failure and sends the operator chasing a non-existent re-frame bug.

**The JVM Clojure floor rides with the same bump.** shadow-cljs 3.x runs on the JVM against `org.clojure/clojure`, and it requires **Clojure 1.12+**. A project that bumps shadow-cljs to the reference version but leaves an older `org.clojure/clojure` on the JVM does **not** get a clean "needs Clojure 1.12" message — the build detonates with the cryptic reader/Closure error `Invalid token: byte/1`, which reads like a corrupt source file or a migration bug, not a dep-floor problem. So whenever you carry the shadow-cljs bump, confirm `org.clojure/clojure` is at the reference floor too: read the actual pin re-frame2 builds against in the pinned checkout — `implementation/core/deps.edn` (the `org.clojure/clojure` coord; `implementation/deps.edn` is the top-level coordinator) — rather than hard-coding a number, since it moves release to release (it is **1.12.x** at the time of writing). If the project's Clojure predates that floor, bump it in the same dep-file pass as shadow-cljs; a too-old Clojure surfaces as that same misleading `Invalid token: byte/1`, never as a re-frame error.

**A green one-shot `compile` does not prove the dev `watch` server starts.** The `byte/1` reader form lives in shadow-cljs 3.4.x's **watch-server** namespace, which a one-shot `shadow-cljs compile` never loads — so the compile can pass on a too-old (1.11) JVM Clojure and the `watch` / release server then dies at startup with the same `Invalid token: byte/1`. Verify the toolchain with an actual `watch` (the boot-smoke needs a dev server anyway), not just a one-shot compile.

(Distinct from the **classpath-collision** edge case below, which is a *coord conflict* over `re-frame/re-frame`. This is a *version-skew* between two build-tool components, not a duplicate dependency — different symptom, different fix.)

### Check 5 — CI / test-runner browser floor (the runtime browser-capability gate)

Checks 1–4 clear the React deps and the build toolchain; this check clears the **browser the CI test-runner executes the migrated bundle in**. re-frame2's modern runtime targets React 19, and **re-frame2 inherits React 19's minimum-browser floor** (~Chrome 90+ / current stable): the bundle calls modern JS APIs — `Array.prototype.at`, `Object.hasOwn`, `structuredClone`, and friends (these landed across roughly Chrome 92–98) — that an older browser does not have. **Audit your CI test-runner's PINNED browser version** (the karma launcher, `browser-actions/setup-chrome`, Playwright, a headless-Chrome snapshot) **and bump it to one React 19 supports** — current stable is the safe target. This is the *runtime* peer of Check-4's *build*-toolchain bump: a mechanical version bump in the CI config (the skill flags the stale pin and edits the CI config to the target; CI's next run is what proves it), **not** a NO-GO blocker.

**The symptom signature — recognise it fast, because it does not look like a browser problem.** The bundle **module-loads fine** on the old browser (the modern-API calls aren't evaluated at load), then **crashes the moment the tests EXECUTE and the first event dispatches** — the first `dispatch` is where the modern-API call lands. The headless runner never surfaces the browser console, so the visible failure is **"No test results found" / an early crash with NO stack, in seconds (not a timeout)**. And because a **local** run on a modern browser **passes**, it reads like a flaky runner or a deps problem rather than a browser-capability floor. (Field case: a CI job pinned to a Chromium snapshot of Chrome 87 — green locally, "No test results found" in ~5.5 s on CI; fixed by a one-line bump of the pinned test-browser to current stable.)

**Distinct from the install-time npm ERESOLVE React-19 peer item below — do not conflate the two.** That item is an `npm install`-time *dependency*-floor failure (an unmet `react@^19` peer aborts the install before the compile even starts); this is a *runtime browser-capability* floor — the install and the compile both succeed, and the failure surfaces only when an old browser executes the bundle. Bumping the JS peer-deps does nothing for a stale test-browser pin, and bumping the test-browser does nothing for an unmet npm peer; both bumps may be needed.

### Check 6 — Explicit go / no-go

Decide and record the gate outcome before proceeding:

- **GO** — React already at 19 (or cleanly bumpable), and every Check-1/Check-2 library has a React-19-compatible target **or an empirically-verified runtime pass** (Check-2 option 4), and Check-3 is empty or its call sites are slated for the `createRoot` rewrite, and Check-4's shadow-cljs/CLJS toolchain is current or slated to be bumped to the reference version, and Check-5's CI test-runner browser is at/above the React-19 floor or slated for a bump to current stable. Carry the React/Reagent bump, any component-lib bumps, and the shadow-cljs bump into the M-0 pass below — and bump the CI test-runner's pinned browser in the CI config alongside it — then continue. (For a project already on React 19 with a React-19-ready component library and a current toolchain, this is the fast path — the gate adds minutes, not weeks.)
- **NO-GO** — any Check-2 component library (or a load-bearing Check-1 dep) has no React-19 release **and** no empirical runtime pass. **Stop here.** Do not edit any dep coord. Report the blocker and the options to the author; the migration resumes once the blocker is resolved. (Neither Check-4 toolchain skew nor Check-5's stale CI-browser pin is a NO-GO — both are known, mechanical bumps (shadow-cljs carried into M-0; the CI-browser pin updated in the CI config); flag them so they aren't mistaken for a migration bug mid-compile or a flaky test-runner.)

**The React/Reagent bump itself**, once the gate is GO. If `package.json` pins `react`/`react-dom` to 17 or 18, bump both to `^19` (Reagent users are simultaneously on Reagent 2.x — that rides in via the `day8/re-frame2-reagent` adapter, not a separate `package.json` pin unless the project pins Reagent directly):

```json
"dependencies": {
  "react":     "^19.0.0",
  "react-dom": "^19.0.0"
}
```

Then run `npm install` (or the project's package-manager equivalent) before the first dev build. If the project is already on React 19, leave it alone; do not downgrade.

**Expect `npm install` to ERESOLVE-fail against any JS dep still pinned to a React `<19` peer.** The moment `react`/`react-dom` move to `^19`, npm's resolver rejects the whole tree if *any* remaining JS dependency still declares a `react` peer below 19 — a categorically common case in a view-heavy app (an animation library, a UI-component kit, a date-picker, a chart or drag-and-drop lib, etc., still pinned to `^18`). The install aborts with `npm error ERESOLVE could not resolve` / `peer react@"^18..." from <lib>`, and **the CLJS compile cannot even start without `node_modules`** — so this blocks the post-M-0 compile gate *before any re-frame rule runs*, and it reads like a setup failure rather than the dependency-floor issue it is.

The **interim unblock to reach the compile gate** is `npm install --legacy-peer-deps` (or `--force`): it installs the tree despite the unmet React peer so the build can proceed and the migration sweep can continue. Treat that flag as **scaffolding, not a destination** — the peer-pinned JS deps it papers over are a **real to-do, not resolved**: bump each to a release that admits React 19 (this is the same Check-1 / Check-2 bump work above), so that `npm install` resolves *cleanly without the flag* **and** the library is actually runtime-safe on React 19. Do not leave `--legacy-peer-deps` as the project's permanent state — it silences the resolver, it does not make a React-`<19` library work under React 19; a lib that only *installs* under the flag can still mis-render or throw at runtime. Surface the still-pinned deps as a tracked follow-up alongside the GO decision.
