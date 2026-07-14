# Spec 004C — Root identity and mount — the descriptor/manifest contract

> Status: v1-required. The Stage-1 mount grammar, root identity, Root
> Descriptor v1, and the three-layer fail-loud duplicate/conflict detection
> that [004-Views.md](004-Views.md) §Roots and mounting references and never
> restates. Owns the descriptor/manifest schema family (`:rf.root/*`); the
> Stage-5 Root Manifest is its additive extension (co-owned with
> [011](011-SSR.md)). The ratified identity model is preserved exactly — a
> **root** is one React DOM render/hydration unit, a **frame** is one re-frame2
> state world, roots ↔ frames are many-to-many, and mount position is never
> identity. `[S1-CONFIRM]` marks a conservative contract written where the
> synthesis is silent — confirm before the surface hardens; not an open hole.

## 1. Root identity — required, host-authored, derivable

**Every root has a `root-id`. It is REQUIRED identity** — the root descriptor, the root
manifest, instance records (04 §1), and duplicate detection all key on it. It is
**host-authored** (`:root-id` in the root opts map, §3), with a **derivation default**
so the single-root common case stays one-liner clean:

1. **Authored wins.** A `:root-id` in the opts map is the root-id, verbatim. Legal
   shapes: a qualified keyword (canonical: `:page/shop`) or a vector of a qualified
   keyword plus scalar disambiguators. Anything else is a **compile error** (identity
   opts are literals, §3, so the shape is always statically checkable — the diagnostic
   joins the compile-error roster, not the runtime catalogue).
2. **Derived otherwise.** When `:root-id` is absent, the root-id derives from the
   **mounted view's registered id**:
   - no `:disambiguator` → root-id = the view id itself (e.g. `:shop/app`). A
     **single-root page may omit the disambiguator** — this is the guide-01 counter
     path, zero ceremony.
   - `:disambiguator d` (scalar: keyword, string, or integer) → root-id =
     `[view-id d]` (e.g. `[:shop/product-panel :left]`). Required whenever the same
     view mounts twice on one page and neither site authors `:root-id`.
3. **The mounted view** (derivation source) is defined precisely: the extractor walks
   the root form's static top region (§6) and requires **exactly one internal view**
   there. Zero internal views (bare DOM root, foreign-component root) or more than one
   (fragment of two views) make derivation impossible → compile error with the
   didactic message *"root form has no single mounted view — author `:root-id`"*.
4. **Provenance is recorded** (dev only): the descriptor carries
   `:root-id-provenance :authored | :derived` so duplicate diagnostics can say *"both
   ids derived from `:shop/app` — add `:disambiguator` or author `:root-id`"*. Stripped
   from shipped manifests.

**Root-id slug** (one deterministic, **injective** function, used by the
identifier-prefix default and synthesised locators — distinct valid root-ids ALWAYS
yield distinct slugs). It is a decodable canonical form over the DOM-safe alphabet
`[A-Za-z0-9_-]`: `_` is the sole metacharacter — every character outside `[A-Za-z0-9-]`
(**including `_` itself**) is reversibly escaped `_<lowercase-hex-code-unit>_`, and each
structural boundary carries an uppercase `_`-tag the escape never emits (`_S` keyword
namespace/name separator; `_V` vector lead-in; `_K`/`_T`/`_I` a vector element's
keyword/string/integer type). A keyword root encodes to `enc(namespace) _S enc(name)`
(namespace absent → `enc(name)`); a vector root to `_V` followed by its type-tagged,
escaped elements. `:page/shop` → `"page_Sshop"`; `[:shop/app :left]` →
`"_V_Kshop_Sapp_Kleft"`. Because every boundary is *marked* rather than inferred from a
data character, the mapping is losslessly decodable and thus injective — no two distinct
root-ids can alias to one slug (the earlier lossy "normalise every disallowed char to
`-`" transform could: `:a/b-c` and `:a-b/c` both flattened to `a-b-c`).

## 2. The Root Descriptor v1 — the named, versioned S1 subset

The Stage-1 "root descriptor" is hereby named: **Root Descriptor v1**, key family
`:rf.root/*`, versioned by `:rf.root/schema-version 1`. It is the **compile-time
static subset of the Stage-5 Root Manifest** — same schema family, same version field,
one compatibility rule (below). The compiler can emit it from a mount site alone; no
server, no render, no reactivity.

```clojure
{:rf.root/schema-version 1
 :root-id              :page/shop                 ; canonical, post-derivation (§1)
 :root-id-provenance   :authored                  ; dev only; never in shipped manifests
 :view-id              :shop/app                  ; the mounted view's registered id (§1.3)
 :props-shape          :literal                   ; :literal | :dynamic
 :static-props         {:promo :spring}           ; present iff :props-shape :literal (§5)
 :frame-plans          [{:frame-id :shop
                         :config-fingerprint "…"}] ; extracted ENSURE plans (§6)
 :template-fingerprint "…"                        ; over the compiled root template
 :build-digest         "…"}                       ; build identity — a READ-TIME projection, NOT baked at expansion (§2.1)
```

`:build-digest` is the one field that is **not** a per-root static fact and is
**never baked** into the emitted descriptor at expansion — see §2.1 for where it
lives and why.

**Fingerprint/digest algorithms — ownership.** `:render-fingerprint` and the semantic
normalization `N` it hashes are owned by
[004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md)
(§Semantic normalization). The `:template-fingerprint`, `:build-digest`, and
hook-signature-hash algorithms are defined by the S1 compiler-slice PR (rf2-vxgfnd.2);
this draft pins only the fields and their comparison semantics.

**Root Manifest v1 (Stage 5)** = Root Descriptor v1 (minus dev-only
`:root-id-provenance`) **plus the render-time extension keys**, exactly the
hydration-salient fields 06 §2 shows:

| Extension key | Meaning | When produced |
|---|---|---|
| `:element-locator` | `{:id "shop-root"}` — §4 | server render |
| `:props` | actual serialised props values (Spec 011 EDN-safe encoder) | server render |
| `:frame-payload-ids` | full referenced payload set observed at render — plans ∪ provider-scoped frames (§6) | server render |
| `:render-fingerprint` | over the rendered structural output | server render |
| `:identifier-prefix` | resolved prefix the server actually used (§3) | server render |
| `:phase` | `:server` (the only v1 value; the field exists so a future phase is additive) | server render |

**The compatibility rule (no churn):**

1. The manifest is a **strict superset** of the descriptor: every descriptor key
   appears in the manifest with identical name, type, and meaning. No key is renamed,
   retyped, or re-semanticised between S1 and S5.
2. **Readers MUST ignore unknown keys.** S5 tooling reads S1 descriptors; S1-era
   tooling reads S5 manifests and simply sees no extension keys.
3. **Additive keys do not bump `:rf.root/schema-version`.** Only a breaking change to
   an existing key bumps the integer; none is planned between S1 and S5 — the S5
   manifest is additive by construction.
4. One version field governs the family: a manifest declares the same
   `:rf.root/schema-version` as the descriptor it extends. Version incompatibility at
   hydration is `:rf.error/root-manifest-invalid` (§7).

This split resolves the 12-§3-postpones-S5 / 08-§2-requires-S1 tension without moving
either stage: S1 ships the descriptor (compiler artefact, `ui.test`/Xray consumer); S5
ships the manifest as its extension.

### 2.1 The static core, the whole-build digest, and the read-time projection

`:build-digest` is a **whole-build aggregate** — a hash over *every* view in the build,
**identical for every root**. It is therefore **not a per-root static fact** and, unlike
every other descriptor key, **cannot be baked at the mount site's expansion**: at the
moment a `ui/mount` / `ui/render!` / `ui/hydrate-root` form macroexpands, the build is
still compiling and only the views seen so far are known — baking the digest there makes
it *compile-order dependent*, and it goes *stale* the instant a view's file recompiles
without the mount site re-expanding (an ordinary view-only hot-reload edit). The per-build
compiler-state authority does not change this: the candidate digest is finalized only
after every source has compiled, strictly *after* every mount-site macroexpansion, so no
expansion-time value can ever be the finalized identity. The candidate becomes accepted
only when the configured build/watch pipeline succeeds (§2.1.1).

Root Descriptor v1 is therefore realised as **two named shapes, one contract:**

- **The static core** — the per-root static facts above **minus** `:build-digest`. This is
  what the compiler bakes at expansion into the emitted client code and what rides each
  live-root registry entry (its `:descriptor`) and the compiler's build-emitted descriptor
  index. It is `:rf.root/schema-version 1` and schema-valid; the digest is simply supplied
  by the projection, not the core.
- **The complete descriptor** — the static core **plus** the finalized `:build-digest`,
  assembled by a **read-time projection** at each consumer. Any value handed out *as* a
  complete "Root Descriptor v1 / Root Manifest v1" carries the digest.

The digest is projected — never baked — at read on **both hosts**, from the **same accepted
compiler snapshot**, so the two projections **agree byte-for-byte** for a given build:

- **Compiler / JVM** — the retained build-state carries one accepted
  `{registries,digest,version}` snapshot per build-id. JVM tooling passes that explicit
  build-state/compiler-env to `re-frame.ui.compiler.root/descriptor-index`; there is no
  process-global "latest build". The projection stamps the snapshot's already-computed
  digest. An open, failed, or interleaved build cannot change the retained snapshot.
- **Client / dev runtime** — `re-frame.ui.client/descriptor-index` (and per-root
  `re-frame.ui.client/descriptor`) reads one compiler-projected scalar from a dev-only
  carrier in O(1). It **never recomputes identity from runtime-registered or currently
  loaded views**. The compiler includes every build member, including an unexecuted lazy
  module, so runtime module-loading order cannot change identity. A successful view-only
  hot reload evaluates the refreshed carrier and updates what already-mounted roots read
  without re-expanding their mount sites.

**Home of the build identity, made explicit:** the finalized whole-build `:build-digest`
lives in the **accepted build snapshot plus its read-time projections**, not in the static
core or runtime registrar. Clean, incremental and watch paths converge on the same scalar.
Direct unsaved no-pass REPL evaluation may replace a live view body and exercise the same
generation/remount machinery as HMR, but it **does not change `:build-digest`**. Saving the
source and completing the next configured build/watch pass publishes the new identity.
Macroexpand-only, never-evaluated and runtime-failed REPL forms therefore cannot become
digest members or pollute a later build.

#### 2.1.1 The accepted-build transaction

The build adapter uses the build tool's retained **functional build-state** as the
transaction boundary:

1. At compile prepare, disposable scratch is seeded from the incoming accepted snapshot;
   isolated no-pass REPL bookkeeping and abandoned scratch are cleared. On a version-zero
   daemon pass, retained output for every `re-frame.ui` cache-blocker-covered CLJS source is
   removed before scheduling, so current registry macros reconstruct the initial accepted
   snapshot instead of silently inheriting output whose macro side effects did not run.
   Every source the build tool will actually compile is pre-touched: removing a source's
   final UI declaration therefore evicts its prior rows even though no registry macro runs,
   while output-present warm cache hits retain their accepted rows.
2. Macro expansion writes scratch only. At compile finish, the adapter reconciles scratch
   against the authoritative whole build graph, computes the digest once, validates and
   patches exactly one fixed-width dev carrier in the returned output state, and carries
   the candidate snapshot in the returned compiler-env.
3. The build tool retains that returned state only if all later configured
   optimize/check/flush/watch work succeeds. A downstream failure discards the candidate;
   the next attempt seeds from the prior accepted snapshot. No external commit/rollback
   atom and no private build-tool completion callback are part of the contract.

The accepted build-state and the active HMR runtime are last-known-good. A build tool may
have partially rewritten its raw output directory before reporting a late failure; this
contract does not claim filesystem rollback for that directory. Consumers activate only
successful build output.

For Shadow 3.4.10 the build hook and top-level
`:cache-blockers #{re-frame.ui}` are load-bearing: the hook supplies the transaction,
version-zero retained-output invalidation and carrier projection; the blocker prevents
those invalidated sources from being reloaded from stale disk cache. Together they ensure
all macro-contributed members are present after a warm daemon start. A configured dev build
without either fails loudly rather than publishing a plausible partial identity.

Dev-only: the carrier, sentinel, digest literal, view manifests and descriptor/digest
projections are `goog.DEBUG`-guarded and absent from advanced production output, so the
mechanism adds no production bytes or runtime work.

## 3. The mount grammar and the host signature set

**The literal mount grammar:**

```clojure
(ui/mount root-form dom-node)         ; opts {}
(ui/mount root-form dom-node opts)
```

`mount` remains a **macro over a literal root form** (ratified — the compiler must see
the root to keep the AST closed and extract frame plans; a runtime-assembled vector is
a compile error pointing at `ui/view`/`ui/element`). The third argument is the **root
opts map** — this is where root identity rides; the "nowhere to provide a root
id" gap is closed here:

| Opt | Tier | Contract |
|---|---|---|
| `:root-id` | identity | authored root-id (§1.1); immutable for the root's lifetime |
| `:disambiguator` | identity | scalar; only meaningful when `:root-id` is absent (§1.2) |
| `:identifier-prefix` | rendering | string fed to React's `identifierPrefix` (`use-id`). Default: `"rf2-" + root-id-slug + "-"` (§1) — `:page/shop` → `"rf2-page_Sshop-"`. (06 §2's `"rf2-shop-"` example is an authored value, not the derived default.) |
| `:on-uncaught-error` `:on-caught-error` `:on-recoverable-error` | host | plain CLJS fns passed to the React root options. Host-tier option maps are **not** template positions — the §02 §3 handler boundary law does not apply here. Invoked by React outside the re-frame2 commit path; to dispatch they must go through a live frame handle. |

Identity opts must be **compile-time literals** at `mount`/`create-root` sites — they feed
the descriptor and build-time duplicate detection; host-behaviour opts may be runtime
values. `:disambiguator` is a **`mount`-only** derivation opt — it modifies how the root-id
is derived from the mounted view. `create-root` fixes identity with **no root form to
derive from**, so its identity set is `:root-id` / `:identifier-prefix` only; supplying
`:disambiguator` there is a compile error (`:rf.ui.compile/bad-root-opts`).

**The full host-tier signature set:**

```clojure
(ui/create-root dom-node opts)        ; ⇒ Root. Identity fixed here for the Root's lifetime; authored :root-id REQUIRED (no root form to derive from).
(ui/render! root root-form)           ; render/re-render the literal root form into the Root.
(ui/hydrate-root dom-node root-form)  ; ⇒ Root. Hydrating mount; identity comes FROM the manifest (§4).
(ui/hydrate-root dom-node root-form opts)  ; opts: host-behaviour tier only (error callbacks).
(ui/unmount! root)                    ; total teardown; unregisters the root-id (§7).
(ui/render-static root-form)          ; S5; proves + emits inert HTML; participates in identity (§7), emits no manifest/payload.
```

- `mount` ≡ `create-root` + frame preflight + `render!`, one-shot, and is **idempotent
  per root**: calling it again with the same root-id and the same container re-renders
  the existing Root (the guide-01 reload path — frames found live, no re-seed). Same
  root-id on a *different* container, or a container already owned by a different live
  root, fail loud (§7).
- For **hydrating mounts, identity is manifest-authored**: `hydrate-root` reads root-id
  and identifier-prefix from the manifest (§4); client opts MUST NOT carry identity
  fields — supplying `:root-id`/`:identifier-prefix` to `hydrate-root` is an error
  (`:rf.error/root-manifest-invalid` data names the conflicting key). The client must
  use the server's prefix or `use-id` hydration breaks.
- **Every root-form-accepting entry point requires the literal root form at the call
  site** — `mount`, `render!`, `hydrate-root`, `render-static`, and `ui.test/render`
  (§8) alike; the same compile error rejects runtime-assembled vectors everywhere.
- **Verb kinds (ratified, shipped S1).** `mount`, `create-root`, `render!`, and
  `hydrate-root` are **macros**; `unmount!` is a plain **function**. The four macros must
  see their argument shape at compile time — `mount`/`render!`/`hydrate-root` keep the
  literal root form's AST closed and extract the static frame plans, and `create-root`
  fixes literal identity opts (feeding the descriptor and build-time duplicate detection).
  `unmount!` takes a live Root value, has no form to compile, and stays a function. This
  is the shipped surface — `re-frame.ui`'s var metadata and the generated public manifest
  classify each verb exactly so — and it settles the earlier `[S1-CONFIRM]` kind-label
  question; the stale "all fns" labelling and its route-to-Mike delta are retired. The
  compile-time contract is locked by the frozen roster: an unauthored `create-root`
  identity is `:rf.ui.compile/missing-root-id`, and an out-of-grammar opt (a stray
  `:disambiguator` among them) is `:rf.ui.compile/bad-root-opts`.
- Frame preflight (ENSURE + `:initial-events` drain, exactly once, before React) runs
  before the first `render!` on a Root and before `hydrate-root`'s hydration — timing
  and semantics owned by [Spec 002](002-Frames.md); this draft only pins *what is extracted*
  (§6).

## 4. Element locators

**Locator vocabulary v1 is closed: `{:id string}`.** No CSS selectors, no XPath, no
positional locators — an id is stable under fragment reordering, which is the point
(mount position is never identity).

- **SSR, host-authored container** (the guide-08 shape — `[:div#shop-root]` in the page
  skeleton): the server render captures the container's id → `:element-locator
  {:id "shop-root"}`. A host-authored container without an id fails the server render
  (`:rf.error/root-manifest-invalid`, data `{:missing :container-id}`) — never a
  synthesised locator on a host-owned element.
- **SSR, emitter-synthesised container** (the server emitter is asked to produce the
  container itself): id is generated deterministically as
  `"rf2-root-" + root-id-slug` — unique per page because the slug is **injective** (§1),
  so distinct root-ids yield distinct slugs, and root-ids are unique per page (§7): two
  synthesised locators can therefore never collide.
- **Manifest placement:** the manifest rides a script element **adjacent** (immediately
  following sibling) to the root's container, EDN-safe-encoded per Spec 011.
  `hydrate-root` discovers the manifest *positionally* (adjacent to its `dom-node`) and
  takes identity from its *content*. `[S1-CONFIRM]` — the concrete script-element
  convention (`type`/`data-rf-root` attribute names) must be pinned in one place with
  the Spec 011 payload-encoding rows; this draft requires only adjacent-sibling
  discovery + content-borne identity.
- **Client-only mounts have no element-locator** — the host passes the DOM node
  directly; the descriptor never contains a locator (it is a manifest extension key,
  §2). Identity is root-id alone.
- Hydration with a locator that resolves to no element (manifest present, container
  gone — fragment composition bugs) is `:rf.error/root-container-missing` (§7), scoped
  to that root per the 06 §2 failure-isolation contract.

## 5. Extracting view-id and serialised props from a root form

- **`:view-id`** = the registered id of the mounted view (§1.3). One mounted view per
  root form is the invariant the extractor enforces; nested internal views inside it
  are ordinary template content, not root identity.
- **Props:** the mounted view's props map in the root form.
  - Every value a literal EDN datum → `:props-shape :literal`, recorded verbatim as
    `:static-props` in the descriptor.
  - Any non-literal expression → `:props-shape :dynamic`; no static props are recorded
    (no guessing).
  - Either way, the **manifest** `:props` records the *render-time values*, serialised
    through the Spec 011 EDN-safe encoder at server render. A value the encoder cannot
    carry fails the server render for that root: `:rf.error/root-manifest-invalid`,
    data `{:unserialisable-prop :chart-fn}` — fail-loud, never a silently truncated
    manifest. Hydration then applies the manifest's props (the server-rendered truth),
    and `:props-shape :dynamic` tells tools why descriptor and manifest may differ.

## 6. Frame-plan extraction and payload references

**What the extractor consumes** (the top-region *grammar* — which forms are legal
wrappers and their diagnostics — is owned by the Spec-004 rewrite): the **static top
region** of a root form is every node reachable from its root without crossing a
control form (`if`/`when`/`cond`/`case`/`for`/…), a dynamic expression, an internal
view boundary, a foreign component, `presence`, `client-only`, or `portal`. The walk
descends through DOM elements, fragments, `frame-root`, `frame-provider`, and
`error-boundary` — all unconditional, compile-extractable positions. ("Transitive"
frame extraction means exactly this: plans are collected through arbitrarily nested
top-region wrappers, in document order.)

- **`frame-root` plans:** each top-region `frame-root` contributes
  `{:frame-id … :config-fingerprint …}` to `:frame-plans`. Its `:id` MUST be a
  compile-time literal (compile error otherwise — plans are static identity). Its
  `:initial-events`/config *expressions* evaluate at preflight (runtime values are
  legal); the `:config-fingerprint` hashes the plan's **static source form** (id +
  config forms), which is what conflict detection compares (§7). A `frame-root`
  anywhere outside a root form's top region is already a compile error (03 §8); this
  draft adds nothing there.
- **`frame-provider` references are dynamic:** `frame-provider` scopes a live frame
  *handle* (per the rf2-nyea0r split) — handles are runtime values, so provider-scoped
  frames are **not statically extractable** and do not appear in `:frame-plans`.
  Instead, the **manifest's** `:frame-payload-ids` (render-time) records the full
  referenced set the server render actually scoped: plan ids ∪ provider-scoped frame
  ids. That is how 06 §2's `:page/shop` example lists `:frame/session` without a plan
  for it. Descriptor = static plans; manifest = full render-time reference set;
  additive per §2's compatibility rule.
- Payload install remains **idempotent and order-independent** (ratified): the first
  hydrating root referencing a payload installs it; later roots find it live and do
  not re-seed. Conflict is the exception, and it is fail-loud (§7).

## 7. Duplicate and conflict detection — fail-loud, three layers

All ids below follow the one-catalogue `:rf.error/*` scheme (Spec 009 rows land with
their stage per the 03 §11 posture); each carries a data map naming both parties with
source coordinates in dev.

**Layer 1 — build time (S1).** The compiler indexes every
`mount`/`render!`/`hydrate-root`/`render-static` site's statically resolved root-id.
Two sites with equal root-ids **reachable from one entry point's module closure** =
build error `:rf.error/duplicate-root-id` (build tier), data
`{:root-id … :provenance [:derived :derived] :sites [coord coord]}` with the didactic
fix (*"same view mounts twice — add `:disambiguator` or author `:root-id`"* when both
are derived). The entry-point closure is the build-time projection of "one page":
sites in disjoint entry closures never co-occur and may legally reuse a root-id.
`[S1-CONFIRM]` — confirm entry-closure scoping (vs. whole-build strictness) when the
first multi-entry consumer lands; entry-closure is the conservative reading that does
not break multi-page builds.

**Layer 2 — server render time (S5).** Page assembly registers each root (manifest
*and* `render-static` root — static roots hold identity too, so a static and a live
root can never claim one id) in a per-response registry. A second registration with an
equal root-id fails the render: `:rf.error/duplicate-root-id` (server tier, projected
per Spec 011). This is the layer that catches **independently rendered page
fragments** composed into one response — the case Layer 1 cannot see. The same
registry asserts **identifier-prefix uniqueness** across the page's roots
(`:rf.error/root-manifest-invalid`, data `{:conflict :identifier-prefix}`) — two roots
sharing a prefix would collide `use-id` output. The **default** prefix
`"rf2-" + root-id-slug + "-"` is already collision-free for distinct root-ids because the
slug is injective (§1); this check therefore backstops **authored** `:identifier-prefix`
opts, which can still collide.

**Layer 3 — client runtime (S1).** A per-document live-root registry: `create-root`,
`hydrate-root`, and `mount` register their root-id before any render; `unmount!`
unregisters. Registering an id already live in the document throws
`:rf.error/duplicate-root-id` (client tier) **before any render** — the existing root
is untouched (failure isolation). This is the last line, catching fragments composed
client-side by independently shipped bundles. It also asserts **identifier-prefix
uniqueness** across the document's live roots — the client-tier mirror of the Layer-2
server check — so two live roots that share an effective `identifierPrefix` (only
possible via an **authored** `:identifier-prefix`; the derived default is injective
over root-id, §1) fail loud on the second claim rather than colliding `use-id` output;
release frees the prefix. Two adjacent container/ownership faults and the
prefix-uniqueness backstop share the roster:

| Id | When |
|---|---|
| `:rf.error/duplicate-root-id` | equal root-id at any layer above |
| `:rf.error/root-container-missing` | hydration locator resolves to no element (§4) |
| `:rf.error/root-container-in-use` | `create-root`/`mount` on a node already owned by a different live root |
| `:rf.error/duplicate-identifier-prefix` | `create-root`/`mount` whose effective `identifierPrefix` is already claimed by a different live root — backstops authored `:identifier-prefix` aliasing (the derived default is injective over root-id, §1) |
| `:rf.error/root-not-live` | `render!` on a `Root` whose id is no longer live — `unmount!`ed or superseded by a newer root claiming the same id (guarded like `unmount!`, but fails loud rather than no-op, before any side effect) |
| `:rf.error/root-manifest-invalid` | manifest missing/unreadable at hydrate, schema-version incompatible, identity opts passed client-side, unserialisable props at emit, prefix conflict |
| `:rf.error/frame-payload-conflict` | below |
| `:rf.error/root-hydration-mismatch` | fingerprint/digest disagreement (existing 03 §11 row — unchanged) |

**Payload/frame-config conflict — fail-loud at preflight.** At any root's preflight
(hydration or client mount), before install/hydrate:

- a referenced **payload id already installed with a different content digest**, or
- a **frame plan whose `:config-fingerprint` differs** from the installed frame's
  recorded plan fingerprint **and was recorded by a *different* root**,

fails **that root** with `:rf.error/frame-payload-conflict`. The runtime plan-conflict
arm carries data `{:frame-id … :installed {:config-fingerprint … :installed-by root-id}
:arriving {:config-fingerprint … :root-id …}}` — `:installed` is the recorded install
record *verbatim*, so it may instead carry `:adopted-by`/`:adopted true` (a boot-
authoritative frame this root only scopes) or an extra `:mount-incomplete true` (a
sibling mount that threw mid-run). The installed frame and the roots already using it
are untouched — exactly 06 §2's failure scoping ("a bad frame payload affects exactly
the roots referencing it"). There is no first-wins silent merge and no last-wins
overwrite. A **same-root** re-declaration whose fingerprint differs is a surgical
**refresh** (an HMR config edit — durable state survives, `:initial-events` re-recorded
not replayed), **not** a conflict; a **matching** fingerprint is the ratified idempotent
no-op (no re-seed). Layer 1 additionally rejects at build time two *plans* for one
frame-id with differing config fingerprints inside one entry closure (compile error —
the didactic message points at boot/event infrastructure per 03 §8). (The S5 hydrate
arm — a referenced payload id already installed with a different **content** digest —
carries its own content-`:digest` slot when server rendering lands: the same error id,
a distinct conflict trigger.)

## 8. Client-only non-hydrating mounts — identity and defaults

The complete default story for `(ui/mount root-form dom-node)` with no opts and no
server in sight (guide 01):

| Aspect | Default |
|---|---|
| root-id | derived: the mounted view's id; single-root page needs no disambiguator (§1) |
| descriptor | emitted at compile time regardless — Xray/instance records key on it from S1; S5 is additive (§2) |
| element-locator | none — the host-supplied DOM node is the container; locator is a manifest-only key (§4) |
| identifier-prefix | `"rf2-" + root-id-slug + "-"` (§3) |
| manifest / digests / fingerprint validation | none — nothing to validate against; hydration errors cannot occur by construction |
| frame plans | extracted and preflighted identically to the SSR path (§6) — ENSURE semantics do not fork on mount kind |
| duplicate detection | Layers 1 and 3 (§7); Layer 2 does not exist without a server |
| phase | no `:phase` anywhere — phase is a manifest key; the 06 §3 `client-only` phase flip does not apply (there is no fallback pass) |

## 9. `ui.test/render` — accepted root-or-view forms

`(ui.test/render root-or-view opts)` accepts exactly two forms:

1. **A view reference** (compile-resolved Var/symbol of a `defview`). Props ride
   `{:props p}`. Frames ride `{:frame f}` XOR `{:app-db v}` (test frame minted); with
   neither, structural rendering proceeds and any `sub` raises
   `:rf.error/no-frame-context` — honest, not defaulted.
2. **A literal root form** — the same literal top-region grammar `mount` takes,
   wrappers included, tightened to exactly **one mounted view** per test root
   (§1.1's authored-`:root-id` multi-view allowance does not apply here; two views →
   `:rf.ui.compile/bad-test-root`, remedy: wrap the composition in one `defview`).
   Then:
   - `{:props p}` is **rejected** (didactic: props live in the form);
   - the form's `frame-root` plans run preflight ENSURE against the test registrar,
     minting fresh test frames from the plans;
   - `{:frame f}`/`{:app-db v}` alongside a plan-bearing root form is **rejected**
     (*"the root form owns its frames — pass a bare view to control the frame"*).
     `[S1-CONFIRM]` — 07 §2 is silent on this combination; rejection is the
     conservative contract (no ambiguity about which frame is ambient).

A runtime-assembled vector is the same compile error as at `mount` (§3). In both
forms, `{:sub-overrides {query value}}` combines freely — it is the explicit JVM
override door (03 §3), with `:owned? false` honesty unchanged. Registrations come from
the loaded namespaces (07 §2 `frame`).

**Test-scope identity:** each `ui.test/render` call is its own document scope — root
identity derives normally (so descriptor-shaped assertions work) but the duplicate
registry never spans two `render` calls. Tier-3 `with-root` mounts participate in the
real per-document registry of the jsdom/browser document, and its total teardown
unregisters (a leaked registration failing a later mount is a test-harness bug, fixture-pinned).

## 10. Stage placement

| Surface | Stage |
|---|---|
| Root Descriptor v1, mount grammar + identity opts, derivation + slug, Layer-1 + Layer-3 duplicate detection, client mount/`create-root`/`render!`/`unmount!`, `ui.test/render` forms, frame-plan-conflict preflight (the `:config-fingerprint` ENSURE arm — build tier S1c, client-mount/`render!` runtime tier S2c) | **S1** (the 08 §2 "root descriptor" row, now defined) |
| Root Manifest v1 extension keys, `hydrate-root` preflight (manifest discovery/validation → payload install → hydrate), locator generation, Layer-2 registry, payload-**content-digest** conflict preflight (the hydrate arm of `:rf.error/frame-payload-conflict` only — the plan-fingerprint arm ships at S1c/S2c, row above), `render-static` identity participation | **S5** (12 §3's "root manifests" row, now the additive extension of S1) |

## Q24–Q28 coverage

- **Q24** (render root-or-view forms; props/frames/registrations/overrides) → §9.
- **Q25** (where root-id is authored/derived; the full signature set) → §1, §3.
- **Q26** (S1 descriptor schema; churn-free evolution to the S5 manifest) → §2.
- **Q27** (locator generation SSR/client; duplicate detection across compilation
  units/page fragments) → §4, §7.
- **Q28** (frame-plan extraction) → §6 pins what the extractor consumes and the
  conflict ordering/diagnosis; the top-region syntactic grammar (which wrapper forms
  are legal and their compile diagnostics) is owned by the Spec-004 rewrite per the
  disposition's Q-ownership map.

## [S1-CONFIRM] register

1. **§4** — the manifest script element's concrete attribute/type convention; pin
   alongside the Spec 011 payload-encoding rows.
2. **§7** — entry-point-closure scoping as the build-time projection of "one page" for
   duplicate root-id detection (vs. whole-build strictness).
3. **§9** — rejection of `{:frame …}`/`{:app-db …}` combined with a plan-bearing root
   form in `ui.test/render`.
