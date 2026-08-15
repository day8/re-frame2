# The release policy — what Hicasso ships, and what an upgrade costs

**Published by `rf2-hic-061`.** This page owns three things
[`specification.md` §12 Phase 6](specification.md#phase-6--adoption-and-release) states and
[§13's first bullet](specification.md#13-definition-of-done) requires — *the package … has a documented
compatibility/release policy*: what the artefact is, which combinations it is compatible with, and what an
upgrade costs the consumer who takes one. Where a claim here and its owning document disagree, the owner
governs and this page is the defect.

**Everything below is measured, and the measurement is dated.** The counts in §3 and §4 were taken on
2026-08-15 against `origin/main`, each with the command beside it, because a compatibility statement that
cannot be re-derived is a compatibility statement nobody can check. The two things this page deliberately
does **not** do are assert a version number and list the public door by name: neither is stable, and §1 and
§3 say why.

## Where each fact lives

| Fact | Owner |
|---|---|
| Which artefacts this repository publishes, and the lockstep rule they publish under | [`spec/Conventions.md` §Packaging conventions](../../../../spec/Conventions.md#packaging-conventions) |
| How a release is actually cut — deploy DAG, recovery, pre-flight | [`docs/release-process.md`](../../../release-process.md) |
| Which public surfaces exist, and their server/hydration disposition | [`dispositions.md` §2.1](dispositions.md#21-surface-inventory-and-dispositions) and [§2.2](dispositions.md#22-public-surfaces-with-no-server-render-behavior) |
| What each React-version obligation is, per surface class | [`lanes/react-compatibility-notes.md`](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) |
| Which refusal ids exist and what stability they carry | [`complaints.md` §The stability rule](complaints.md#the-stability-rule) |
| Which spellings changed, and under whose ruling | [`naming-packet.md`](naming-packet.md#21-operator-rulings-mike-2026-08-11), [`naming-ledger.md`](naming-ledger.md) |
| Which laws the ordinary facade is frozen under — and that names are not among them | [`facade-freeze.md`](facade-freeze.md#1-the-membership-and-how-it-was-decided) |
| What ships as an artefact, and what does not | this file |

## 1. What the artefact is

The coordinate is spelled **`day8/re-frame2-hicasso`**, and it is spelled in two places that agree:
[`implementation/hicasso/deps.edn`](../../../../implementation/hicasso/deps.edn)'s opening line, and the
`TOOLS_LOCAL_ROOTS` inventory in
[`.github/scripts/verify-version-lockstep.sh`](../../../../.github/scripts/verify-version-lockstep.sh),
where Xray declares its dependency on it.

**It IS published, as of `rf2-gra70`** — this section read *"it is not published, and that is deliberate
rather than pending"* until that bead landed, and each of the three measurements it cited has been inverted
by the same change:

1. `implementation/hicasso/deps.edn` carries a **`:clein/build` alias** naming `day8/re-frame2-hicasso`,
   with `:version "../../VERSION"` and `:src-dirs ["src" "resources"]` — the second entry because the
   clj-kondo export reaches a consumer over the classpath.
2. It is **in the lockstep inventory**. `verify-version-lockstep.sh`'s `ARTEFACTS` array now names fourteen
   artefacts and Hicasso is one of them. The array is not decorative: the same script fails the build when a
   directory declares a `:clein/build` without a matching inventory entry, so the presence is enforced in
   both directions.
3. It is **in the release workflow**, in a stage of its own — `deploy-hicasso`, gated on `deploy-core` and
   the whole `deploy-leaf` matrix, for the reason §2 gives.

So Hicasso is consumable from a released coordinate, and `:local/root` from this repository is now the
in-tree development path rather than the only one. The knock-on for Xray went with it: Hicasso was **the one
coordinate `release-xray.yml` deliberately left at `:local/root`** while rewriting the other nine, and
`preflight-xray-package.sh` refused an `xray-v*` deploy over it. All ten are rewritten now, and what remains
is tag ORDER — `v*` before `xray-v*`, at the same VERSION.

**No version has been cut, of anything.** The repo-root `VERSION` file reads `0.0.1.alpha`, and
`git ls-remote --tags origin` returns nothing at all — this repository has never carried a release tag. That
is why §2 describes a scheme and names no current version: a number written on this page today would be
stale before the page was read.

**The consequence for `rf2-hic-061`'s own acceptance criterion, stated plainly.** That criterion reads *"a
tagged release installable by the tiny consumer app from the artefact (not the repo)"*. It is **executable
now and still unexecuted**: `rf2-gra70` built the automation the criterion needs, but cutting a tag is the
operator's act, not a worker's, and this repository has still never carried one. §6 records what is left.

## 2. The versioning scheme

Through 1.0 every published artefact ships at the **same version**, sourced from the repo-root `VERSION`
file. The mechanism is structural rather than procedural: each publishable artefact's `:clein/build`
declares the relative path to that one file, and every in-repo dependency is written `:local/root` in the
committed tree and rewritten to `:mvn/version` on a throwaway checkout at deploy time.
[`spec/Conventions.md` §Lockstep versioning through 1.0](../../../../spec/Conventions.md#lockstep-versioning-through-10)
is normative and [`docs/release-process.md`](../../../release-process.md) is the operational doc; neither is
restated here.

**Joining that train took four edits, three of them hot-zone**, and they are all in the tree now
(`rf2-gra70`): a `:clein/build` alias in `implementation/hicasso/deps.edn`; entries in
`verify-version-lockstep.sh`'s arrays; a deploy stage in `release.yml`; and the release-notes row. The
lockstep verifier refuses the first without the rest, which is the right failure direction and is why none
of it could be done piecemeal.

**Hicasso does not fit the leaf matrix, and the resolution was to not put it there.** Its published `:deps`
name a **second in-repo artefact besides core** — `day8/re-frame2-ssr`, for the `re-frame.hicasso.server`
module — and that is precisely the property that put `ssr-ring` in a stage of its own after the matrix
instead of inside it. Under the matrix's `fail-fast: false`, a leaf with such a dependency can publish a pom
that does not resolve while the leaf it names is red. So Hicasso ships from `deploy-hicasso`, a post-matrix
stage **beside** `deploy-ssr-ring` rather than inside it — a separate job, not a second value of ssr-ring's
matrix, because two values of one matrix cannot be ordered against each other and a stage that cannot be
ordered is the shape this whole split exists to avoid. The two are siblings: neither names the other in its
published pom, so they run in parallel. `implementation/scripts/_release-dag-policy.test.cjs` asserts the
ordering from the workflow's real job graph, with its own acceptance and teeth cases for this stage.

## 3. The compatibility surface — the enumeration, and where it lives

**This page names no public door, on purpose.** The door moves: [`naming-packet.md`](naming-packet.md#21-operator-rulings-mike-2026-08-11)
row 13 renames the two root-lifecycle constructors under an operator ruling, and that sweep is in flight
while this page is being written. A list of names copied here would be wrong within days *and* would
disagree with the gate that already enumerates them — two records saying different things about one surface,
which is worse than one record and no copy. A compatibility statement needs the **enumeration's address**,
not its contents.

Every row below points at a record that is machine-checked, so the surface cannot grow, shrink or be
renamed without something going red.

| Surface | Where it is enumerated | The gate that keeps it honest | Measured 2026-08-15 |
|---|---|---|---|
| The ordinary authoring door, `re-frame.hicasso` | [`dispositions.md` §2.1](dispositions.md#21-surface-inventory-and-dispositions) and [§2.2](dispositions.md#22-public-surfaces-with-no-server-render-behavior) | `implementation/hicasso/scripts/check_facade_inventory.py`, CI job `hicasso-facade-inventory` | **16 names on the door, 43 inventory rows** — 13 attributed by name, 3 by declaration |
| The native tier, `re-frame.hicasso.native` | the `native.cljc` namespace docstring, rowed in [`naming-ledger.md`](naming-ledger.md) | `native_surface_cljs_test.cljs` on the CLJS lane | **10 SURFACE + 4 INTERNAL** public vars |
| Refusal ids | [`complaints.md`](complaints.md#the-stability-rule) | `implementation/hicasso/scripts/check_complaint_catalogue.py`, CI job `hicasso-complaint-catalogue` | **76 live, 6 reserved, 1 pending retirement, 1 retired** |
| Optional modules — forms, motion, overlay, server | the `MODULES` roster in the script beside it | `implementation/hicasso/scripts/check_optional_module_reachability.py` | four modules, each proving zero reachable production code when absent |
| The testing kit, `re-frame.hicasso.test` | `implementation/hicasso/test_kit/src`, deliberately outside the artefact's `:paths` | the kit's own witnesses on the CLJS lane | reachable only from a test, by packaging rather than convention |
| Server/hydration policy, per surface | [`lanes/react-compatibility-notes.md`](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) | each `dispositions.md` inventory id points at its policy row | see §4 — the Phase 4 exit this rests on is **NOT MET** |

Reproduce the first and third rows with:

```sh
python implementation/hicasso/scripts/check_facade_inventory.py
python implementation/hicasso/scripts/check_complaint_catalogue.py
```

Both print their own counts, which is where the numbers above came from. If a figure on this page and a
figure that script prints ever disagree, **the script is right**: it reads the door, and this page only
reports what it read.

## 4. The compatibility matrix

Every row carries either the CI job that backs it or an explicit **untested-but-expected** label, per
`rf2-hic-061`'s acceptance. A row with neither would be a claim, and this page makes none.

| Combination | Pinned where | Backed by | Status |
|---|---|---|---|
| React 19.2.0 and react-dom 19.2.0 | `implementation/package.json` | the CLJS node lane in job `cljs`; `cljs-hicasso-controlled`; `cljs-hicasso-hmr` | **TESTED** — every Hicasso claim in this repository is a claim about this pair |
| Any other React 19.x | — | nothing | **UNTESTED-BUT-EXPECTED** for the boundary shell and the two-hook contract, which write no version-conditional code. Not expected below 19.2 for the `Activity` lifecycle rows, whose subject shipped in 19.2 |
| React 18 or earlier | — | nothing | **NOT SUPPORTED** |
| Chromium 147.0.7727.15, Firefox 148.0.2 and WebKit 26.4, on Playwright 1.59.1 | `implementation/package.json`; the runner prints the triple it launched | `cljs-hicasso-controlled` — three real engines, per PR, comparing recorded rows across engines rather than running one spec three times; `cljs-hicasso-hmr` — three engines, real shadow reloads | **TESTED** |
| Any other engine or engine version | — | nothing | **UNTESTED-BUT-EXPECTED** — the substrate targets React's DOM contract, not a browser's |
| `day8/re-frame2` and `day8/re-frame2-ssr` **at the same commit** | `implementation/hicasso/deps.edn`, both at `:local/root` | `jvm-hicasso`; the CLJS node lane in `cljs` | **TESTED**, and the only supported combination there is — see the row below |
| Hicasso against any *released* core version | — | nothing yet | **NOT APPLICABLE, PENDING A TAG** — `rf2-gra70` landed the wiring, so both sides of the pair are now publishable coordinates on one lockstep train (§1). What is still missing is a released version to name: this repository has never carried a release tag. The row goes green on the first `v*` tag and its install witness, not on the wiring |
| ClojureScript 1.12.145 and shadow-cljs 3.4.10 | `implementation/core/deps.edn`; `implementation/package.json` | every CLJS job | **TESTED** |
| Production build, `:advanced` with `goog.DEBUG` false | the `hicasso-release` build id in `implementation/shadow-cljs.edn` | the `build:hicasso-release` step in job `cljs`, which chains production-erasure and bundle-isolation checks | **TESTED** |
| The optional Node service, `re-frame.hicasso.server` | `implementation/hicasso/deps.edn`'s one `day8/re-frame2-ssr` entry | `server_render_ssr_dom_cljs_test.cljs` on the CLJS lane | **TESTED as a render witness.** The per-surface SSR/hydration policy it serves is the next row |
| Every inventoried public surface against its SSR/hydration policy row | [`lanes/react-compatibility-notes.md`](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) | the witnesses each policy row names | **OPEN.** [`checkpoint-4-coverage.md`](checkpoint-4-coverage.md) returned **NOT MET** on the Phase 4 exit, and nothing here softens that. This row is not green and must not be read as green |

### 4.1 The recheck obligation, per supported React version

[`lanes/react-compatibility-notes.md`](lanes/react-compatibility-notes.md) opens with the rule this section
exists to make operational: *"Recheck them against the supported React version at each release."*

Concretely, **bumping the react pin in `implementation/package.json` is a release-gate event**, not a
dependency update. The set to re-run is the matrix's Render and Client-only rows, the Activity lifecycle
witness, and the three-engine controlled gate. A React bump that does not re-run those leaves every row in
§4 asserted about a version nobody tested — and because the pin is one line and the gates are green either
way, nothing would announce it. That is the whole reason this obligation is written down rather than
assumed.

## 5. The upgrade policy — no shims, and what that buys

**The project ships no back-compat shims, and the record shows it running that way rather than merely
saying so.** [`naming-packet.md` §2.1](naming-packet.md#21-operator-rulings-mike-2026-08-11) carries five
operator rulings, and `rf2-hic-066` applied them as one mechanical sweep: the callback macro was renamed,
both root-lifecycle constructors were renamed, and the mounted test facade's re-render verb was renamed to
stop it colliding with the product door. A sixth door came off outright — `release!` was removed by a
correctness route rather than a naming one, because its page-wide reset emptied the runtime under every
other root.

**Not one of them left an alias, a deprecation window, or a compatibility namespace behind.**

That is the upgrade this policy describes, because it is the upgrade this project has actually run. It is
not a deprecation cycle, and writing one here would describe a process that has never happened.

**What no shims buys, and it is the reason the policy is worth having.** A removed or renamed door is a
**compile error at the consumer's own call site**, located and immediate. A shimmed one is a warning nobody
reads, or worse, working code that quietly means something else. So the cost of an upgrade is bounded and
visible: recompile, and the compiler enumerates every site that has to move. Nothing is silently carried
forward, which is exactly what a shim does carry forward.

### 5.1 What is stable today — the honest list

**One thing, and it is genuinely promised: refusal ids.**
[`complaints.md` §The stability rule](complaints.md#the-stability-rule) states it in four rules — an id never
changes meaning, an id never changes spelling, a retired id is tombstoned and never reused, a reserved id
means only the sentence in its row — and rules 3 and 4 are mechanised, so a reserved or retired id that
acquires an emitter reds the gate. A consumer's stored errors, a monitor's grouping rule and a page of prose
all outlive the code, and that is what the promise is for. This page does not restate those rules; it
records that they are the compatibility promise Hicasso currently makes.

**Everything else on the door is provisional, and the documents say so in their own words.**
[`specification.md` §4](specification.md#4-target-programming-model) opens by calling the names *"a
provisional facade"*. [`facade-freeze.md`](facade-freeze.md#1-the-membership-and-how-it-was-decided) freezes
the ordinary surface's **laws and semantics** — fourteen of fourteen — and freezes **no name at all**, which
it states in its second paragraph. A reader who takes that page as a naming freeze has read it backwards.

That distinction is the compatibility statement, so it is worth putting plainly: **the semantics are frozen
and the spellings are not.** An upgrade may cost you a rename. It should not cost you a rethink.

### 5.2 What a release may change, and what it owes when it does

Any public spelling may change in any release before 1.0. What such a change owes is not a courtesy — every
item below is enforced by something that runs:

- **A row recording the question and its disposition**, in [`naming-ledger.md`](naming-ledger.md) or the
  packet that consolidates it. Nobody renames mid-flow; the ledger's own header rule is what stops it.
- **The sweep applied to the whole corpus in the same change.** The guide's verb use-sites are resolved
  against the sources by `check_guide_samples.py` (CI job `hicasso-guide-samples`), so a rename that misses
  a guide page reds rather than shipping a page that teaches a name nobody can type.
- **An inventory row for any surface added or renamed.** `check_facade_inventory.py` refuses a door name
  with no row in `dispositions.md`, in either direction, so the surface cannot grow silently.
- **A Spec 009 row for any new refusal id, and a tombstone for any retired one** — §5.1's rules, mechanised.
- **Nothing else.** In particular: no alias, no deprecation shim, no compatibility namespace, no
  version-conditional branch in the runtime.

### 5.3 What 1.0 changes, and what this page will not pre-write

The lockstep rule in §2 is scoped *through* 1.0, and Conventions says independent versioning is revisited
after it. A minor-versus-major distinction only becomes meaningful on the far side of that, so this page
does not invent one now — a promise written a year before it can be kept is a promise nobody is bound by.

What can be said now is the **shape** such a promise would take, and it is a short list because §3 already
built it. The records a semver promise would be made *about* all exist and all have gates: the door
inventory, the native roster, the refusal catalogue. So on the day the project is ready to promise
compatibility, the promise is checkable on that same day rather than aspirational — which is the whole
reason those gates were written before there was anything to release.

## 6. What is owed, and where it is filed

**The release wiring landed under `rf2-gra70`.** It touched `.github/workflows/release.yml`,
`.github/workflows/release-xray.yml`, `.github/scripts/verify-version-lockstep.sh`,
`implementation/shadow-cljs.edn` and `spec/Conventions.md` — hot-zone and sequential — and it answered the
design question §2 names: Hicasso's second in-repo dependency keeps it out of the `deploy-leaf` matrix, so
it ships from a post-matrix stage of its own beside `ssr-ring`.

Three consequences, and this page states them rather than working around them:

- `rf2-hic-061`'s acceptance criterion — a tagged release installable from the artefact — is now
  **executable and unexecuted**. Cutting a `v*` tag is the operator's act; no worker does it, and nothing on
  this page should be read as a claim that a release has happened.
- §4's *any released core version* row stays **NOT APPLICABLE** until that tag exists, for want of a version
  rather than for want of wiring.
- Xray's publishability is no longer blocked. Hicasso was the one coordinate `release-xray.yml` left at
  `:local/root`; all ten are rewritten now, and `preflight-xray-package.sh` refuses nothing — what is left
  is tag ORDER, `v*` before `xray-v*` at the same VERSION.

The other open item is not this bead's to close either: §4's last row rests on a Phase 4 exit that
[`checkpoint-4-coverage.md`](checkpoint-4-coverage.md) returned **NOT MET**. A compatibility matrix cannot
promise a green its own evidence page declines to give, so it does not.
