# Spec 004C — Root identity and mount — the descriptor/manifest contract

> Status: v1-required. Root identity, Root Descriptor v1, element locators, render-time
> props, the hydrating-root boot sequence, and fail-loud conflict detection. Owns the
> descriptor/manifest schema family (`:rf.root/*`); the
> Stage-5 Root Manifest is its additive extension (co-owned with
> [011](011-SSR.md)). The identity model is exact — a
> **root** is one React DOM render/hydration unit, a **frame** is one re-frame2
> state world, roots ↔ frames are many-to-many, and mount position is never
> identity.

> **What realises this contract.** `re-frame.ssr.manifest` validates, assembles, emits
> and discovers Root Manifests (§2, §4, §5); `re-frame.ssr.install` runs a hydrating
> root's preflight and keeps the payload-install ledger (§3, §6, §7); and
> `re-frame.ssr/hydrate!` runs the boot sequence of §10. This Spec owns no mount verb:
> the client-root grammar is
> [006 §The client root](006-ReactiveSubstrate.md#the-client-root-adapter-owned-reusable),
> which every React view adapter publishes, `re-frame.fresco` included. No shipped
> substrate derives a Root Descriptor from a mount site, so a descriptor reaches
> `re-frame.ssr.manifest/manifest` from its caller.

## 1. Root identity — required, host-authored, derivable

**Every root has a `root-id`, and it is the root's identity.** The root descriptor and
manifest carry it as `:root-id`; a hydrating root reads it from its manifest's content
(§3), and the payload-install ledger attributes each claim to it (§6). A root-id is a
qualified keyword (canonical: `:page/shop`) or a vector of a qualified keyword plus
scalar disambiguators — keyword, string, or integer (`[:shop/product-panel :left]`).
The dev-only `:root-id-provenance` records how the root-id was arrived at, and never
rides a shipped manifest (§2).

**Root-id slug** (one deterministic, **injective** function, used by synthesised
locators (§4) — distinct valid root-ids ALWAYS
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
root-ids can alias to one slug (a lossy "normalise every disallowed char to
`-`" transform would: `:a/b-c` and `:a-b/c` would both flatten to `a-b-c`).

## 2. The Root Descriptor v1 — the named, versioned S1 subset

The Stage-1 "root descriptor" is **Root Descriptor v1**, key family
`:rf.root/*`, versioned by `:rf.root/schema-version 1`. It is the **static subset of
the Stage-5 Root Manifest** — same schema family, same version field, one
compatibility rule (below). Every field is a per-root static fact about the root's
source: no server, no render, no reactivity.

```clojure
{:rf.root/schema-version 1
 :root-id              :page/shop                 ; canonical (§1)
 :root-id-provenance   :authored                  ; dev only; never in shipped manifests
 :view-id              :shop/app                  ; the mounted view's registered id (§5)
 :props-shape          :literal                   ; :literal | :dynamic
 :static-props         {:promo :spring}           ; present iff :props-shape :literal (§5)
 :frame-plans          [{:frame-id :shop
                         :config-fingerprint "…"}] ; static ENSURE plans (§6)
 :template-fingerprint "…"}                       ; over the root template
```

**Fingerprint/digest algorithms — ownership.** `:render-fingerprint` and the semantic
normalization `N` it hashes are owned by
[004B-UI-Tree-and-Conversion.md](004B-UI-Tree-and-Conversion.md)
(§Semantic normalization). The `:template-fingerprint` and `:config-fingerprint`
algorithms belong to the descriptor's producer, not to this Spec;
this Spec pins only the fields and their comparison semantics.

**Root Manifest v1 (Stage 5)** = Root Descriptor v1 (minus dev-only
`:root-id-provenance`) **plus the render-time extension keys**, exactly the
hydration-salient fields carried by [011 §Root Manifest v1](011-SSR.md#root-manifest-v1):

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
2. **Readers MUST ignore unknown keys.** S5 tooling reads S1 descriptors; S1
   tooling reads S5 manifests and simply sees no extension keys.
3. **Additive keys do not bump `:rf.root/schema-version`.** Only a breaking change to
   an existing key bumps the integer; the S5 manifest is additive by construction.
4. One version field governs the family: a manifest declares the same
   `:rf.root/schema-version` as the descriptor it extends. Version incompatibility at
   hydration is `:rf.error/root-manifest-invalid` (§7).

The split lets the descriptor stand without a server: S1 is the descriptor, and S5
ships the manifest as its extension.

## 3. The mount grammar and the host signature set

This Spec owns no mount verb. The client-root grammar — `client-root`, `render!`,
`unmount!` — is [006 §The client root](006-ReactiveSubstrate.md#the-client-root-adapter-owned-reusable),
and `re-frame.fresco` realises it as `h/client-root`, `h/render!` and `h/unmount!`:
the first `render!` through a handle creates its root, or hydrates one under
`{:hydrate? true}`. What this section owns is the two rules a root keeps about identity
and ordering.

- **A hydrating root hydrates as the server rendered it.** Its root-id is the
  `:root-id` in the manifest adjacent to its container (§4), which
  `re-frame.ssr.install/preflight!` reads from the manifest's content. A container with
  no discoverable manifest fails loud with `:rf.error/root-manifest-invalid`, data
  `{:missing :manifest}`: a hydrating root never guesses its identity. Its
  `identifierPrefix` must be the one the server rendered under — the manifest's
  `:identifier-prefix`, where an omitted one is React's empty prefix `""` — or every
  `useId` resolves differently from the server's bytes.
- **Frame preflight runs before React.** A root door that ensures its frame does so
  before `createRoot`: ENSURE creates the frame if absent and drains its
  `:initial-events` synchronously, so the first paint is the seeded one, and a frame
  already live is joined as it stands — no re-seed, no config refresh.
  `re-frame.fresco.impl.mount/root!` keeps this order through `ensure-frame!`; Fresco's
  public door names no frame, because its tree does, on `h/frame-root` or
  `h/frame-provider`. A hydrating root's frame state arrives through
  `re-frame.ssr/hydrate!`, which seeds it before the host mounts (§10). What
  [`make-frame` construction](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar)
  and the synchronous, ordered `:initial-events` drain *do* is Spec 002's. The
  [`frame-root` two-pass contract](002-Frames.md#frame-root--the-ensure-component-cljs-reference)
  (empty first render → commit-phase `useLayoutEffect` ENSURE → populated second render)
  runs the *opposite* order and scopes a component subtree, not a host root.

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
  Discovery (`re-frame.ssr.manifest/discover`) finds the manifest *positionally*
  (adjacent to its container) and takes identity from its *content*. The script
  element's convention — `type="application/edn"` and the bare `data-rf-root` marker —
  is [011 §The wire form](011-SSR.md#the-wire-form).
- **Client-only mounts have no element-locator** — the host passes the DOM node
  directly; the descriptor never contains a locator (it is a manifest extension key,
  §2). Identity is root-id alone.

## 5. Extracting view-id and serialised props from a root form

- **`:view-id`** = the registered id of the root's mounted view. Nested views inside it
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

- **`:frame-plans`** records the root's static frame-ENSURE plans in the descriptor, one
  `{:frame-id … :config-fingerprint …}` per plan: the plan's literal frame id, and a
  fingerprint of its static config source.
- **`frame-provider` references are dynamic:** `frame-provider` scopes a live frame
  *handle* — handles are runtime values, so provider-scoped
  frames are **not statically extractable** and do not appear in `:frame-plans`.
  Instead, the **manifest's** `:frame-payload-ids` (render-time) records the full
  referenced set the server render actually scoped: plan ids ∪ provider-scoped frame
  ids. That is how a manifest can list a provider-scoped frame — say `:frame/session` —
  that no static plan declares. Descriptor = static plans; manifest = full render-time
  reference set;
  additive per §2's compatibility rule.
- Payload install is **idempotent and order-independent**: the first
  hydrating root referencing a payload installs it; later roots find it live and do
  not re-seed. Conflict is the exception, and it is fail-loud (§7). Install is the
  **state-boot** call's work, not the DOM-adoption call's: it is `re-frame.ssr/hydrate!`
  that reads the payload and installs it, and a view layer's hydrating root never does
  ([011 §Client-side hydration boot helper](011-SSR.md#client-side-hydration-boot-helper)).
  "The first hydrating root installs it" therefore describes the ORDER the two-call boot
  runs in across N roots on a page, not a step hidden inside the hydrating root.

## 7. Duplicate and conflict detection — fail-loud, three layers

All ids below follow the one-catalogue `:rf.error/*` scheme; each carries a data map
naming both parties.

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

| Id | When |
|---|---|
| `:rf.error/duplicate-root-id` | equal root-id in one response's page registry (Layer 2) |
| `:rf.error/root-manifest-invalid` | manifest missing or unreadable at hydrate, not a Root Manifest v1 (schema-version incompatible), a host-authored container without an id, unserialisable props at emit, prefix conflict (Layer 2) |
| `:rf.error/frame-payload-conflict` | below |
| `:rf.ssr/hydration-mismatch` | server↔client render-tree fingerprint/digest disagreement at hydration (a Spec 009 catalogue row this Spec does not own) |

**Payload conflict — fail-loud at hydration preflight.** At a hydrating root's
preflight, before any install, a referenced **payload id already installed with a
different content digest** fails **that root** with `:rf.error/frame-payload-conflict`
(`re-frame.ssr.install/payload-install-decision!`). Its data names both parties — the
`:payload-id`, the `:installed` record (`:digest`, `:installed-by`), and the
`:arriving` `{:digest … :root-id …}` — with recovery
`:render-the-page-from-one-response`. The installed payload and the roots already using
it are untouched — failure scoping is precise: **a bad frame payload affects exactly the
roots referencing it.** There is no first-wins silent merge and no last-wins
overwrite; an **equal** digest is the idempotent no-op of §6.

## Compatible shell versus clean remount

A redefinition is **compatible** when it does not move the boundary's *hook
skeleton* — the ordered set of host hooks the emitter's shell owns for that
declaration. Reusing a boundary across a moved skeleton is not conservative,
it is wrong: the host's hook state is positional, so the new shell would read
the old occurrence's slots.

In the interpreted mode the skeleton is not a function of the body at all. An
interpreted body is unrestricted Clojure that produces markup and calls no
host hooks; every hook an interpreted boundary owns belongs to its atomic shell,
in a fixed order. **So every interpreted body edit is compatible, however
large.** What moves the skeleton is a change of *lowering* — the compiled tier
renders through its own shell, and its capability-elision verdict omits the
ViewCell, and with it every hook above, for a view with no reactive site.
Promotion between modes (adding `{:compiled true}` and reloading) is therefore
the incompatible edit, and it earns exactly **one** clean remount: a new
boundary, the old occurrence disconnected, no attempt to carry state that the
new shell has nowhere to put. What is never permitted is the third outcome —
serving the promoted declaration the stale interpreted boundary, which would
render the pre-promotion body indefinitely with nothing to say so.

The publication seam carries a **body revision**, and it advances when a new
body is published — not when an unchanged tree is walked again. A render that
began against the previous body and reaches its commit afterwards is stale at
that revision and publishes nothing: no dependencies, no event sites, no
evidence (see [006 §The atomic shell](006-ReactiveSubstrate.md#the-atomic-shell)). The
host simply renders again at the new body.

## 10. Stage placement

| Surface | Stage |
|---|---|
| Root Descriptor v1, the root-id and its slug | **S1** (the S1 "root descriptor" deliverable, defined by §2 above; stage roster per [EP-0030 §Stages S1–S7](../docs/EP/EP-0030-the-compiled-view-substrate-program.md#stages-s1s7)) |
| Root Manifest v1 extension keys, the hydrating-root boot sequence (manifest discovery/validation → payload install → hydrate) — `re-frame.ssr/hydrate!` runs `re-frame.ssr.install/preflight!` (manifest discovery/validation and the install decision) and then `:rf/hydrate`, before the view layer's hydrating root adopts the DOM — locator generation, Layer-2 registry, payload-**content-digest** conflict preflight (`:rf.error/frame-payload-conflict`) | **S5** (the S5 "root manifests" deliverable — the additive extension of S1; see [011 §Root Manifest v1](011-SSR.md#root-manifest-v1)) |

## Q24–Q28 coverage

- **Q24** (render root-or-view forms; props/frames/registrations/overrides) → no Spec:
  there is no `ui.test/render`
  ([008 §The `ui.test` contract](008-Testing.md#the-uitest-contract--headless-testing-for-compiled-views)).
- **Q25** (where root-id is authored/derived; the full signature set) → §1, §3.
- **Q26** (S1 descriptor schema; churn-free evolution to the S5 manifest) → §2.
- **Q27** (locator generation SSR/client; duplicate detection across compilation
  units/page fragments) → §4, §7.
- **Q28** (frame-plan extraction) → §6 pins the `:frame-plans` record; **no Spec owns**
  extraction itself — the top-region syntactic grammar (which wrapper forms are legal
  and their compile diagnostics) — see [README](README.md) on the vacant 004 slot.
