# setup

Operational detail for **M-0 — the dep-coord swap**, the precondition for every other rule. Clear the [React-19 / Reagent-2 floor gate](floor-gate.md) first; then apply the coord swap; then verify the project compiles; *then* sweep for breakage.

## Contents

- The coord swap (M-0)
- Per-build-tool shapes
- Coords to detect
- Picking the substrate-adapter artefact
- Pin the migration corpus before reading it
- Discovering the current VERSION
- The consumability done-gate
- The pay-as-you-go artefact split — the principle
- Edge cases

The two gates that bracket this one are their own leaves, loaded at their own phases: the Phase-0b pre-flight in [`floor-gate.md`](floor-gate.md), and the Phase-4 optimized / release compile gate in [`release-compile-gate.md`](release-compile-gate.md).

---

## The coord swap (M-0)

Run this only after the [floor gate](floor-gate.md) returns **GO**. Carry every GO-state edit the gate identified — the dep-coord swap, the React/Reagent bump, any component-lib bumps, and the shadow-cljs/CLJS toolchain bump (Check 4) — into this one dep-file pass, so the post-M-0 compile runs against the fully-current toolchain (an older shadow-cljs detonates the first compile with the cryptic `NoSuchFieldError`, Check 4). The skill makes the `package.json` edits and runs the install (cardinal rule 5).

### The swap itself

v1 ships as `re-frame/re-frame`. v2 ships as a **pair** of artefacts at the same VERSION:

- `day8/re-frame2` — the core (registry, drain, dispatch, subscribe, fx, the substrate-adapter contract).
- `day8/re-frame2-<substrate>` — the substrate adapter (`-reagent` or `-uix`). v1 codebases use Reagent universally, so default to `day8/re-frame2-reagent`.

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
| No view layer (backend-only / headless / a test harness) | **No adapter artefact.** The headless plain-atom adapter ships *inside* `day8/re-frame2`: `(:require [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])`, then `(rf/init! rf.substrate.plain-atom/adapter)`. Phase-0b's React floor does not apply |

The canonical adapter inventory — every adapter's `:kind`, published namespace, and Maven coordinate — is [`spec/006-ReactiveSubstrate.md` §CLJS reference scope](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md#cljs-reference-scope). Do **not** add `day8/re-frame2-reagent` to an app with no view layer: it declares stock Reagent, which drags the React 19 npm peers into a project that never renders, and makes the Phase-0b floor gate apply to a build that has no floor to clear.

### Mixed-substrate projects — the compile-closure rule (not "add only one")

If the codebase has **both** Reagent and UIx requires — a phased substrate migration mid-flight — the rule is **NOT "add only the root-driving adapter."** Each adapter's `re-frame.adapter.<substrate>` namespace lives in its **own artefact** (`day8/re-frame2-reagent` / `-uix` are separate coords — per [`spec/Conventions.md` §adapter packaging](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md)), and a wrong-substrate adapter ns is **structurally absent** from the classpath unless its artefact is present. So adding only one adapter leaves any namespace that `:require`s the *other* substrate's `re-frame.adapter.*` ns **failing to resolve at compile/load** — that is not a smallest-correct migration, it is an untracked compile break.

Two things are different and must be tracked **separately** in the report:

1. **Artefacts needed for compilation** — add **every** adapter artefact whose `re-frame.adapter.<substrate>` namespace is `:require`d by source that **stays on the build classpath**. Compute the closure: grep the surviving source for `re-frame.adapter.reagent` / `.uix` requires and add the matching artefact for each. (UIx adapter-specific surfaces live in `re-frame.adapter.uix` and are NOT re-exported from `re-frame.core` — per [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md) — so a require of it is real and must resolve.)
2. **Adapter chosen for boot** — install **exactly one** active adapter at each `rf/init!` site (the boot contract allows mixed-substrate *imports* while installing one active adapter; per [`spec/006-ReactiveSubstrate.md` §boot](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md)). Pick whichever substrate drives the React root; pass that adapter's var to `init!`.

If the author wants the non-root substrate **out of scope** (a real, common choice), don't silently leave its views broken — either **isolate/exclude** the non-root substrate namespaces from the migrated build (so they're not on the classpath and need no adapter artefact), or **stop with a documented follow-up** naming the namespaces still on the other substrate. Knowingly shipping broken views while claiming the v1→v2 migration "succeeded" is the failure this rule prevents.

## Pin the migration corpus before reading it

[`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md) is the contract for every rewrite, so it must be **pinned**, not fetched live. Load it from a **local checkout of `day8/re-frame2` pinned to a specific commit or tag** — an unpinned remote fetch makes every migration depend on whatever happens to be on `main` that minute, and a non-reproducible corpus is a non-reproducible migration.

> **A branch or remote NAME is not an IDENTITY — verify by structure and pins, never by name.** That a checkout's `origin` is named `day8/re-frame2`, or that its branch/tag carries the expected name, does **not** prove it is the re-frame2 you mean. Names are cheap and collide; the only proof is the commit it resolves to **and the structure that commit carries**.

Before reading the corpus, verify the checkout — commit, remote, **and layout**:

```bash
git -C <path-to-re-frame2> rev-parse HEAD                                                    # the pinned commit
git -C <path-to-re-frame2> remote get-url origin                                             # confirm origin is day8/re-frame2 (NAME only)
git -C <path-to-re-frame2> ls-tree <SHA> implementation/core/deps.edn implementation/adapters  # STRUCTURE: the multi-artifact layout exists at this ref
```

These three are **read-only provenance checks** — allow-listed (the scoped `Bash(git -C * rev-parse *)` / `Bash(git -C * remote get-url *)` / `Bash(git -C * ls-tree *)` entries in `SKILL.md`'s `allowed-tools`) alongside `rg` ([`SKILL.md` cardinal rule 5](../SKILL.md)). The first two prove the commit and the remote NAME; the third proves **identity by structure** — a `git ls-tree` / path-existence read, not project-code execution. Confirm at the pinned ref that the **multi-artifact monorepo** layout is actually present: `implementation/core/deps.edn` (the core artefact), `implementation/adapters/<substrate>` (`reagent` / `uix`), and the per-feature `implementation/<feature>` dirs the app pulls (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`, `resources` — each its own artefact dir). Confirm too that the substrate floor is **Reagent 2 / React 19** by reading `implementation/package.json` at that same ref (`react` / `react-dom` pinned to `19`). **Why this matters:** the two NAME probes pass even for an **old single-artifact ancestor** — a pre-monorepo-split commit, branch, or tag under the very same `day8/re-frame2` origin — where `implementation/` does not yet exist in this shape and the per-feature artefacts the migration depends on are simply absent at that ref. Pre-publish, several re-frame2 source checkouts on disk are **normal**, so this name-collision is common, not exotic: without the structure read the migration silently reads an **obsolete corpus** and plans against artefacts that do not exist at the pinned ref. Do **not** fetch `MIGRATION.md` from GitHub at runtime. **Record the pinned hash in the migration report** ([`output-format.md`](output-format.md)) alongside the chosen `<v2-version>` (next section) — both pin the migration to a reproducible point.

## Discovering the current VERSION

**The author picks the target VERSION; the skill never auto-selects "latest".** The kickoff prompt names a specific `<v2-version>` string — that's the contract. If `<v2-version>` is unset (the author left a placeholder), **stop and ask** before editing any dep file.

For the author's reference (so they can pick), three sources of authoritative version info:

1. **`VERSION` file** in the local pinned `day8/re-frame2` checkout (`<path-to-re-frame2>/VERSION`) — the string used for the next release.
2. **`CHANGELOG.md`** in the pinned checkout — released versions with summaries; the most recent non-Unreleased entry is the latest released version.
3. **GitHub releases page** (`https://github.com/day8/re-frame2/releases`) — for cross-referencing tags, but the local pinned checkout is the authoritative source for *this* migration.

If the author wants the bleeding edge, they can use a `:git/url` + `:git/sha` coord instead of `:mvn/version` — but they still type the SHA into the kickoff prompt; the skill does not pick. Niche; default to released `:mvn/version`. If the author is migrating against an **unpublished** re-frame2 from a **sibling checkout** (a `:local/root` coord per artefact), the re-frame2-setup skill carries the copy-pasteable recipe with the verified per-artefact paths: [`deps-versions.md` §The `:local/root` sibling-checkout dev route](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish).

**Never invent a version; never silently pick `latest`** — newly published packages may be broken or malicious, and unpinned coords make the migration non-reproducible. Record the chosen `<v2-version>` in the migration report.

**If nothing is published to Clojars yet** (pre-publication): the migration is still fully doable — a first release is **not** a precondition. When no `:mvn/version` resolves, the author consumes re-frame2 via a **`:local/root`** sibling-checkout coord ([`deps-versions.md` §The `:local/root` sibling-checkout dev route](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish)) or a **`:git/url` + `:git/sha`** coord, and the migration proceeds normally — apply every M/O-rule exactly as you would against a published target. Do **not** leave the dep alone, and do **not** stop and wait for a release. The guardrails are unchanged: never invent a version, never silently pick `latest`, and the author still supplies the pin or route — the skill never picks it for them. "Stop and ask" applies only when the author has supplied **no consumption route at all** (no `:mvn/version`, no `:git/sha`, no `:local/root`), *not* merely because nothing is on Clojars yet. Record the chosen route — the sibling-checkout path or the pinned SHA — in the migration report, exactly as a `<v2-version>` would be recorded.

## The consumability done-gate

The `:local/root` sibling-checkout route above (and in [`deps-versions.md` §The `:local/root` sibling-checkout dev route](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/references/deps-versions.md#the-localroot-sibling-checkout-dev-route-pre-publish)) is a **dev convenience, not a shippable coordinate** — that route is the SETUP half; this is the **UNWIRE** half that complements it. A `:local/root` coord resolves to an **absolute path on the author's own disk**: `{:local/root "../re-frame2/implementation/core"}` names *this* machine's sibling checkout, nothing a clean runner can find. So a migration that consumes pre-publish re-frame2 (the monorepo modules + Xray) and any forked upstream entirely through `:local/root` paths can compile 0/0, boot, and pass the boot smoke-test **locally** — looking "done" — while **every CI run is red from the first step**, because the runner has no such paths:

```
Error building classpath. Local lib day8/re-frame2-reagent not found: ...
```

**The done-gate:** before the migration is "done", repin **every** re-frame2 (and forked-upstream) dep to a coordinate a **clean runner can resolve**:

- **`:git/url` + `:git/sha` pinned to a PUSHED commit** — the pragmatic pre-publish coord (no Maven release required). One coord per artefact, each a `{:git/url … :git/sha … :deps/root "implementation/<subdir>"}` git-subdir coord — **one `:deps/root` per monorepo module**. Shapes in [`deps-versions.md` §Choosing the coordinate](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/references/deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape).
- **`:mvn/version`** — once the artefacts are published to a registry the runner can reach.

**Any forked or extended upstream MUST be pushed.** A `:git/sha` that exists only in a local commit is no more resolvable than a `:local/root` path — CI cannot fetch an unpushed SHA. Push the fork's branch so its SHA is fetchable, then pin to it.

The real done-signal is therefore **CI green on a clean checkout**, not a green local build — see [`runtime-smoke-test.md` §The done-bar is more than the local dev build](runtime-smoke-test.md#the-done-bar-is-more-than-the-local-dev-build). Record the final clean-runner-resolvable coords in the migration report, exactly as the chosen `<v2-version>` / route is recorded above.

## The pay-as-you-go artefact split — the principle

re-frame2 splits nine per-feature artefacts out of core. Seven of them are the ones a v1 migration can trigger: `-schemas`, `-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`. The other two — `-ssr-ring` (the Ring host binding for SSR) and `-resources` (declarative server-state) — have no v1 counterpart at all, so no migration rule ever adds them; a codebase adopts them deliberately, after the migration, or not at all. **The rule is one line: add an artefact only when the codebase actually uses that feature. Never add one defensively.**

The full trigger→rule→dependency matrix — *which* v1 surface forces *which* artefact (M-27 through M-33, plus the HTTP test-support require, M-31a / M-65) — lives in **one place** and is not restated here: [`breaking-changes.md` §Required (M-rules) by trigger surface](breaking-changes.md#required-m-rules-by-trigger-surface), rows M-27–M-33. Read it there when you need the per-surface mapping. The per-artefact `:require` + load-hook + missing-artefact behavior is in [`auto-cross-cutting.md` §Per-feature artefact adds](auto-cross-cutting.md#per-feature-artefact-adds-m-27-through-m-33).

In practice most v1 codebases add **none** of these: state machines, flows, managed-HTTP, and SSR are v2 additions, so a v1 app has no trigger surface for them. v1 code doing equivalent things by hand stays hand-rolled post-migration; you do **not** rewrite it into the new artefacts as part of the required migration (adopting them is opt-in — see the `O-N` rules). The one exception is `-flows`: v1's `on-changes` interceptor (one of the five removed by M-21) migrates to `reg-flow`, so a codebase that used `on-changes` adds `day8/re-frame2-flows` when it applies M-21 (M-30 also carries the v1→v2 flow-map conversion — the Type-B `:live?` re-home — see [`breaking-changes.md` §M-30](breaking-changes.md#m-30-also-carries-the-flow-map-conversion)).

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

**Verification step — prove the classpath is clean before you compile.** After the exclusion sweep, run a tool-appropriate classpath check filtered for `re-frame` and confirm **no v1 `re-frame/re-frame` artefact remains** on the classpath, *before* attempting the post-M-0 compile. Don't trust the exclusion list — prove the classpath is clean (the skill runs the check; cardinal rule 5).

- **tools.deps (`deps.edn`):** `clojure -Stree` shows the full resolved tree — search the output for any `re-frame/re-frame` node (it should be absent; only `day8/re-frame2` + the adapter should appear). `clojure -Spath` prints the realised classpath — it must contain **no** `re-frame/re-frame` jar (look for a `re-frame/re-frame/<1.x.x>/…jar` Maven-cache path). For an `:aliases`-gated dev/test classpath, run the check **under the same aliases** the build uses (e.g. `clojure -A:dev -Stree`), because a leak can hide behind a profile/alias.
- **Leiningen (`project.clj`):** `lein deps :tree` (and `lein with-profile +dev deps :tree` for profile overlays) — confirm no `[re-frame "1.x.x"]` node survives.
- **shadow-cljs:** `npx shadow-cljs classpath` (or inspect the resolved deps it prints on build) — grep the output for `re-frame/re-frame`. If shadow reads deps from `deps.edn`, the `clojure -Stree`/`-Spath` checks above are authoritative.

Only once the check shows a single source of `re-frame.core` (v2) is it safe to compile. A surviving v1 jar means `re-frame.core` may resolve to v1 at compile/load time regardless of which coords you *think* you swapped.

**Per-feature artefact not yet published.** **Not an edge case — it is M-0's route applied again.** Nothing is published, so *every* `day8/re-frame2*` coordinate is in this state, core and adapter included; a per-feature artefact is not a second publication decision. When an M-27..M-33 rule triggers, add its artefact **in this migration**, at the **coordinate kind and author-supplied pin already chosen at M-0** ([§Discovering the current VERSION](#discovering-the-current-version)) — the same `:git/sha`, or the same checkout for a `:local/root` — differing only in its own `:deps/root` / `:local/root` sub-path, which is delegated rather than restated: [`deps-versions.md` §Choosing the coordinate](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/references/deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape). Do **not** leave the dep alone, defer it, or wait for the artefact to land: the rewrite and its artefact are one change, and a rewrite shipped without its module fails at the `:require` or at the first call ([`auto-cross-cutting.md` §Per-feature artefact adds](auto-cross-cutting.md#per-feature-artefact-adds-m-27-through-m-33)) — a report entry does not make the app run. Pay-as-you-go is unchanged: add an artefact only when a rule actually triggers it, never defensively.

---

**Stop after M-0.** Do not start sweeping for other M-rules until you've tried a compile and seen what — if anything — breaks. The expected result for most codebases is that the dep swap is the entire migration. Verify that before sweeping.
