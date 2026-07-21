# S5 view-substrate conformance profile

**Status:** Reference · owned by `rf2-vxgfnd.97.3` (the S5 conformance gate).

This is the single authoritative catalogue of the frozen S5 surface of
`re-frame.ui` — the compiled-view substrate's **server-rendered roots** stage.
It fixes *what* S5 froze (the Root Manifest and its discovery, hydration
preflight and idempotent adoption, failed-root isolation, the structural-tree →
HTML serialiser, and the root-scoped `client-only` phase flip) and names, for
each row, the suite that proves it. The runtime behaviour is proven by those
homes (browser / JVM / node suites), not re-asserted in prose here.

## 0. Inheritance — this profile is cumulative and rows only the S5 delta

**"S5-conforming" means S4-conforming plus everything below.** S1–S4 are
inherited **by exact reference** to the frozen S4 profile,
[`spec/conformance/S4-view-conformance-profile.md`](S4-view-conformance-profile.md),
which (through its own §0) remains the sole authority for the ten compiler-owned
verbs, the `route-link` view, the React interop tier, presence, custom elements,
the a11y roster, and the foreign boundary. This document **does not restate any
of it** — restating a frozen roster is how two profiles drift apart. It rows
only the S5 **delta**:

- the **Root Manifest v1** (the additive per-root wire) and its positional
  **manifest discovery**;
- **hydration** — preflight and idempotent payload **adoption**;
- **failed-root isolation** — one root's whole boot is the isolation unit;
- the structural-tree → HTML serialiser (`re-frame.ssr/emit-ui-tree`);
- the root-scoped **`client-only` phase flip**.

S5 adds **one** new public name to the `re-frame.ui` facade — `render-static` —
**shipped as a macro** (ruled `fn`→macro 2026-07-20); §1 rows it as the sixth
frozen surface, §3 gives its positive treatment, and §6 declares the stage
conforming. The mount-surface verbs `hydrate-root` / `create-root` /
`render!` / `mount` / `unmount!` and the `client-only` macro were **exported
earlier** for symbol resolution; the stage completes their server/hydration
*behaviour* over an already-blessed surface.

The frozen surface and the proof references are kept honest by an executable
drift guard,
`implementation/ui/test/re_frame/ui/s5_conformance_profile_jvm_test.clj`: its
`frozen-s5-surface` and `blessed-s5-facade-names` maps are the source of truth
this document restates. The guard **composes with** the S3 and S4 guards rather
than duplicating them — it reuses their published row-binding machinery
(`repo-root` / `table-row-for` / `section-between`) and their frozen rosters,
and fails loudly on a **broken inheritance link**, a **restated S1–S4 row**, a
**moved or deleted proof home**, or — the fail-closed **merge precondition** —
a **blessed S5 public name with no implementation site**. Every §1 row is bound
row by row (keyed on the row's first table cell, so a prose mention elsewhere
does not satisfy a row), and every named proof home must be a real test
namespace on disk. Deleting a row, hollowing one out, or swapping a proof home
between rows is red.

## 1. Frozen S5 surface

Six closed rows: the **Root Manifest** and its **discovery**, hydration
**adoption**, **failed-root isolation**, the **emit-ui-tree** serialiser, the
**phase flip**, and **`render-static`** (S5's new macro). Each names the small
exact contract fragments its row must carry and the suite that proves its real
runtime behaviour. `render-static` shipped as a macro (its runtime repairs
landed in #6557/#6624) and §3 gives its positive treatment.

| Surface | Kind | Contract | Loud failure | Proven by |
|---|---|---|---|---|
| `Root Manifest v1` | additive per-root wire (data) | Root Descriptor v1 **plus six optional render-time keys** (`:element-locator` / `:props` / `:frame-payload-ids` / `:render-fingerprint` / `:identifier-prefix` / `:phase`); **every extension key is OPTIONAL** and the only required key is `:rf.root/schema-version` = `1`, so **an unmodified S1 descriptor is already a valid manifest** (a strict superset, no migration); the wire is one EDN `<script data-rf-root>` per root that **round-trips exactly** | a non-`1` schema version, a bad locator, or an unserialisable prop is `:rf.error/root-manifest-invalid`; the hydrate content-digest arm is `:rf.error/frame-payload-conflict` | JVM/node `re-frame.ssr.root-manifest-cljs-test` |
| `manifest discovery` | positional late-bind seam | `ui/hydrate-root` finds its manifest **positionally** — the adjacent `<script data-rf-root>` sibling of its `dom-node` — through the `:ssr/discover-root-manifest` hook (a `ui → ssr` require is forbidden, so discovery is late-bound); root identity (root-id + `:identifier-prefix`) then comes **from the manifest**, not from client opts | discovery finding nothing adjacent is `:rf.error/root-manifest-invalid` with ex-data `{:missing :manifest}` | browser `re-frame.ssr.hydrate-root-seam-dom-cljs-test` |
| `hydration preflight + adoption` | two-call client boot | `re-frame.ssr/hydrate!` installs the payload/state **first**, then `ui/hydrate-root` adopts the server DOM; adoption is **idempotent** — a client `:server`-phase fallback tree byte-identical to the server DOM is **adopted cleanly with no re-render**; a divergence surfaces during React's adoption window as the `:rf.ssr/hydration-mismatch` diagnostic (tier-discriminated `:where`), never a silent repaint | an adoption divergence emits `:rf.ssr/hydration-mismatch`; a missing manifest fails loud per the discovery row | browser `re-frame.ssr.client-only-adoption-verification-dom-cljs-test`; browser `re-frame.ssr.multi-root-hydration-dom-cljs-test` |
| `failed-root isolation` | per-root fault boundary | **one root's whole boot is the isolation unit**: a root that throws while booting is contained, and its **siblings still hydrate** (they hold the server slice) **and stay interactive** (a dispatch reaches them); a failed sibling neither delays nor blocks a good root — there is **no page-wide barrier** | the failing root takes its own client-fresh / fail path; siblings are unaffected | JVM/node `re-frame.ssr.failed-root-isolation-cljs-test`; browser `re-frame.ssr.failed-root-isolation-dom-cljs-test` |
| `re-frame.ssr/emit-ui-tree` | pure tree → HTML serialiser | folds an **already-rendered** version-1 structural tree (the value `re-frame.ui.tree/render` / `ui.test/render` produced) to an HTML string; **it calls nothing** — no view, no sub, no frame — and is deterministic to the byte; it emits **one root's markup only** and neither reads nor writes manifests, payloads, or the response; the root `:rf.ui/tree-version` is validated **first, before any emission** | a missing / non-integer / unsupported version is `:rf.error/ssr-ui-tree-version-unsupported` (`{:got .. :supported #{1}}`); a malformed node **past** the gate is the shared `:rf.error/ui-tree-malformed` | JVM/node `re-frame.ssr.emit-ui-tree-cljs-test` |
| `client-only phase flip` | root-scoped one-shot phase write | a hydrating root **boots in `:server` phase** (renders `ui/client-only` **fallbacks**), and after the adoption mismatch check a **single root-scoped write** moves it to `:client` phase, swapping **every** `client-only` site to its client subtree in **one update**; per-site flip state is **non-conforming**; a **failed root never flips**, and **multi-root** roots flip **independently** (no page-wide barrier); the mismatch check runs over the `:server`-phase tree and the flip **MUST NOT** run before it | a phase that booted `:client` would fire `onRecoverableError` during adoption — the falsifier the proof drives | browser `re-frame.ssr.phase-flip-hydration-dom-cljs-test` |
| `render-static` | macro (JVM static-root emitter) | `(ui/render-static root-form)` proves a root is static and emits pure `:server`-phase **inert HTML** with **no manifest**, no payload, and **no phase flip** (its `client-only` sites render the fallback and stop there); it enforces the **literal root form** at the call site — a runtime-assembled vector is rejected exactly as at `mount` / `render!` / `hydrate-root` — and permits **no silent capability elision**: a server-reachable committed handler, an authored `:ref`, or an interactive dependency is **loud**, never dropped to inert markup; it reaches `emit-ui-tree` by **late resolution**, so `re-frame.ui` never statically requires `re-frame2-ssr`, and it participates in root identity (§7) and registers its root-site | a runtime-assembled root is `:rf.ui.compile/runtime-root-form`; a server-reachable runtime capability is `:rf.ui.compile/static-root-requires-runtime`; an unprovable dependency is `:rf.ui.compile/static-root-unproven-dependency`; an absent SSR artefact is `:rf.error/ssr-artefact-missing` | JVM `re-frame.ssr.render-static-jvm-test` |

The mount-surface verbs S5 completes — `ui/hydrate-root`, `ui/create-root`,
`ui/render!`, `ui/mount`, `ui/unmount!` — keep their S1 signatures unchanged and
are catalogued in [`spec/004C-Roots-and-Mount.md`](../004C-Roots-and-Mount.md);
`ui/client-only`'s S3 authoring contract is the S3 profile's. What froze at S5 is
the server/hydration *behaviour* over that surface, rowed above.

## 2. Host behaviour

**Server render (`:server` phase, JVM).** A root renders through the one JVM
structural emitter to a version-1 tree, in `:server` phase — so every
`ui/client-only` site renders its capability-free **fallback**, never the client
subtree. `re-frame.ssr/emit-ui-tree` folds that tree to HTML deterministically;
the Root Manifest is assembled as pure data alongside and rides its own adjacent
`<script data-rf-root>`. The server never runs a hydration timer, a phase flip,
or a client effect.

**Client hydrate (adoption, then flip).** `re-frame.ssr/hydrate!` installs the
payload and state, then `ui/hydrate-root` discovers the manifest positionally,
adopts identity from it, and hydrates the **`:server`-phase** tree — the
fallbacks — so React adopts markup structurally identical to what the server
produced. That adoption **is** the hydration verification: a byte-identical tree
adopts with no repaint, and a divergence surfaces as `:rf.ssr/hydration-mismatch`
through the root's `onRecoverableError`. Only after that check does the single
root-scoped phase write flip the root to `:client`, swapping all `client-only`
sites in one update. A root that fails its boot is isolated and never flips;
sibling roots proceed independently.

**Cross-host.** The structural output both the client emitter and
`re-frame.ssr/emit-ui-tree` must agree on is normalised structural equivalence
(the S4 profile's §4 space, unchanged) — byte-identical HTML is not the
contract. The root-hydration half of W14 is the residual named gate rowed in §5.

## 3. `render-static` — the static-root surface (shipped: S5's new macro)

`render-static` is a **blessed v1 public name** of `re-frame.ui`
([API.md §2](../API.md), row `render-static`; stage **S5** in the §2b matrix) —
the explicit static-root surface: `(ui/render-static root-form)` proves a root is
static and emits pure `:server`-phase output, with **no manifest and no payload**
and **no phase flip**, so its `client-only` sites render the fallback and stop
there ([004C §3](../004C-Roots-and-Mount.md#3-the-mount-grammar-and-the-host-signature-set),
[011 §Phase flip](../011-SSR.md#phase-flip)).

**It shipped as a macro.** `render-static` was ruled a **macro** (`fn`→macro,
2026-07-20) — not a fn — because it must enforce the **literal root form at the
call site**: the same compile error that rejects a runtime-assembled vector at
`mount` / `render!` / `hydrate-root` rejects it here, which a fn cannot do, and
the other root entry points are macros. Its runtime behaviour landed with the
gate (#6511) and its correctness repairs in #6557/#6624, and it is rowed in §1
as the sixth frozen surface bound to `re-frame.ssr.render-static-jvm-test`.

**It permits no silent capability elision.** A pure `:server` static render cannot
honour a live-runtime capability, so a **server-reachable** committed handler, an
authored `:ref`, or an interactive dependency is caught **loud** at expansion
(`:rf.ui.compile/static-root-requires-runtime` / `-unproven-dependency`), never
dropped to inert HTML — the guarantee the render-static repairs hardened. A capability-free `client-only` island renders only its fallback and is
legal. `render-static` reaches `emit-ui-tree` by **late resolution**
([004B §The SSR consumption boundary](../004B-UI-Tree-and-Conversion.md)), so
`re-frame.ui` never statically requires `re-frame2-ssr`; an absent SSR artefact is
the typed `:rf.error/ssr-artefact-missing`, not a raw class-load failure.

**The merge precondition is fail-closed, and now met.** Every S5 public name the
`spec/API.md` `re-frame.ui` blessed-surface freeze claims must have a real
implementation site with a green named proof. The drift guard enforces this by
resolving each blessed S5 facade name; `render-static` **resolves** as a macro
var and its named proof is green, so the precondition is satisfied. The check
stays as the **fail-closed tooth**: any future blessed S5 name that lacks an
implementation site reds the gate — the **names-without-implementations** failure
mode the S5 epic's own audit caught. The facade classification for the new public
is owned by the S3/S4 facade exact-set guards (§0's *does not restate* rule keeps
this profile from editing them).

## 4. Non-surfaces (the S5 wall)

S5 deliberately does **not** ship, and a consumer that appears to need one gets an
API redesign or a named boundary, never a substrate exception:

- **no version-negotiation protocol and no migration mechanism** for the Root
  Manifest — v1 is the first version, additive keys never bump
  `:rf.root/schema-version`, and readers ignore unknown keys;
- **no mandatory extension key** — making any manifest extension key required
  would silently fork the superset into a second schema;
- **no page-wide hydration barrier** — a failed root is isolated, and sibling
  roots hydrate and flip independently;
- **no per-site phase state** — the phase is one root-scoped value written once;
  a per-site flip is non-conforming even though it would appear to satisfy the
  authoring promise;
- **no compiled-ui hydration verification without both hashes** — `verify-hydration!`
  intentionally does **not** fire for a compiled-ui root absent **both** the
  plan-fingerprint and content-digest hashes: there is no live wrong-hash case to
  surface, and forcing an `onRecoverableError` there was rejected as gold-plating
  a non-firing path. This intentional non-firing is **S5-conforming**, not a gap
  (`rf2-6z1i2`, ruled ACCEPT-CURRENT / no-fix 2026-07-21);
- **no second server product** — `re-frame.ssr` consumes the one JVM emitter;
  `emit-ui-tree` invokes no view and resolves no subscription (a second render
  against a moved frame would be unsound), and it owns no manifest, payload, or
  response;
- **no `ui → ssr` static require** — discovery, the render-tree hash, and the
  payload install are late-bound seams, so production `re-frame.ssr` never pulls
  the compiler onto the classpath;
- **no RSC and no streaming sugar** — the JVM SSR path is the canonical server
  story and streaming policy stays host-side (per Spec 011 / EP-0034 §2).

## 5. The gate roster (S5 arms)

The full G-1..G-18 roster lives in `docs/EP/EP-0034` §4 and the 07 §5 gate roster.
S5 introduces **no new G-numbered gate**; it discharges the **residual named
gate** the stage plan carried and grows one existing arm:

| Gate | S5 arm |
|---|---|
| **root-manifest hydration + failed-root isolation** | the residual named gate from the stage plan (`docs/EP/EP-0034` §2 SSR), discharged by the focused suites named in §1 — Root Manifest, discovery, adoption, isolation, phase flip — riding the existing required `jvm-ssr` (JVM) + node (`test:cljs`) + browser jobs, **no new dedicated CI job** (the S3 gate #6182 and the S4 gate #6320 added none either — the drift guard rides `jvm-ui`) |
| **G-7** | dev↔prod structural equivalence grows the **root-hydration half of W14** (the `:server`-phase adoption tree), bounded to S5 shapes; the mounted flip/adoption behaviour rides the focused browser suites above, not a new G-arm |

Everything else S5 froze is proven by the named suites in §1, not by a gate arm.
`render-static` (§1, §3) carries **no** new G-arm either — it is proven by its
named JVM suite `re-frame.ssr.render-static-jvm-test`, riding the existing
required `jvm-ui` job.

## 6. Conformance grading

An S5-conforming host emits, for the frozen surface above: the Root Manifest v1
superset with its optional-key contract and exact round-trip (§1); positional
manifest discovery and manifest-authored identity (§1); idempotent adoption with
`:rf.ssr/hydration-mismatch` on divergence (§1); per-root failed-root isolation
with interactive siblings (§1); the pure `emit-ui-tree` serialiser with its
version gate (§1); the root-scoped one-update phase flip after the mismatch
check (§1); and the `render-static` static-root macro with its no-silent-elision
guarantee and late-resolved emit seam (§1, §3). It must also be
**S4-conforming** — §0 inherits S1–S4 by reference, and a host that fails the S4
profile fails this one. The gate arms in §5 and the suites named throughout are
the executable acceptance; `scripts/test-fast-pr.sh` plus the S5 CI matrix run
them.

**S5 IS DECLARED CONFORMING.** All six shipped surfaces of §1 are green,
including the blessed `render-static` macro (§3): it resolves as a `re-frame.ui`
var and its named proof `re-frame.ssr.render-static-jvm-test` passes, so the
fail-closed merge precondition is **met** and the drift guard is green. This
gate's merge is therefore the ruled S6 entry token (`rf2-vxgfnd.98`) — the double
epic close of S5 (`rf2-vxgfnd.97`) and S6 (`rf2-vxgfnd.98`). The one intentional
non-firing (compiled-ui hydration verification without both hashes, §4) is
by-design and S5-conforming, not a gap (`rf2-6z1i2`, ruled ACCEPT-CURRENT).
