# Spec 011 — compiled-substrate amendment: `emit-ui-tree`, root manifests, multi-root hydration, `render-static`

> **DRAFT — merges at S5.** Drafted 2026-07-12 09:22 AUSEST, while the S1 contracts
> are fresh; it does NOT merge now. Per [12 §3](../12-implementation-plan.md) the S5
> epic's Spec-011 edits are **sequenced behind whichever open PR owns Spec-011 edits at
> dispatch time** — verify none is in flight before dispatching, and re-verify every
> anchor below against the then-current revision (011 is a hot-zone file and will have
> moved by S5).

**Status:** DRAFT · 2026-07-12.
**Target file:** `spec/011-SSR.md` (revision read for this draft: main at commit
`1b3d7e4795`, 1230 lines; all anchors verified against that revision).
**Anchor verification:** five old→new pairs. Every Old block below was checked against
the target file and **each occurs exactly once** (counts restated per pair). Pair 3's
Old block is multi-line because the bare heading string `### Per-request frame teardown
contract` occurs twice in the file (the section heading at 1127 and the Resolved-decisions
heading `### Per-request frame teardown contract added` at 1186); the heading + first
sentence together occur exactly once.
**Basis:**
⟨[jvm-tree-and-conversion-contract](jvm-tree-and-conversion-contract.md) §The SSR
consumption boundary — owns the tree schema, normalization `N`, and the seam signature;
this amendment cites it and adds only the 011-owned halves⟩
⟨[root-identity-and-mount](root-identity-and-mount.md) §2 (Root Manifest v1), §4
(element locators + the deferred script convention, its `[S1-CONFIRM]` item 2), §7
(three-layer duplicate detection — Layer 2 lands here), §10 (stage placement)⟩
⟨[06 §2/§3](../06-ssr-islands.md) — roots/frames identities, hydration contract, failure
scopes, explicit static-root policy⟩
⟨[05 §1](../05-production.md) — packaging: the existing `re-frame2-ssr` artefact consumes
the JVM emitter; no second server product⟩
⟨merged S1 code ground truth: `implementation/ui/src/re_frame/ui/tree.cljc`
(`tree-version` = `1`; `render` stamps `:rf.ui/tree-version` on the root boundary node)
and `implementation/ui/src/re_frame/ui/compiler/emit_jvm.cljc` (AST → JVM forms building
the versioned tree via `re-frame.ui.tree`)⟩
⟨009 catalogue ground truth on main: `:rf.error/ui-tree-malformed` row already names the
S5 sibling — "the SSR seam's version-gate sibling `:rf.error/ssr-ui-tree-version-unsupported`
lands with the S5 serialiser"; the four root/payload rows carry their S1 arms and
forecast their S5 arms⟩

House style: British "serialisable" in all New text (verbatim Old text keeps its own
spelling); `[S5-CONFIRM]` marks a recommendation Mike confirms before/at the S5 merge —
written conservatively, not an open hole. **Roster ownership:** the S5 epic's Spec-011
bead (filed at the S4 boundary per the epic's stage-boundary filing rule) owns resolving
the six-item `[S5-CONFIRM]` roster below and re-verifying every anchor — no other venue
carries them.

---

## What this amendment does

Spec 011 today knows one server-renderable tree: the hiccup render-tree consumed by
`render-to-string`. The compiled substrate (rf2-vxgfnd) adds a second, versioned tree —
the public JVM structural tree (node schema v1) — and S5 integrates it with SSR: a new
consumption entry (`emit-ui-tree`) with a fail-loud version gate, the Root Manifest v1
wire-encoding rows (resolving the root contract's deferred `[S1-CONFIRM]` item 2),
multi-root page assembly (the Layer-2 server page registry, per-root failure isolation,
the hydration ledger), and `render-static` under the explicit static-root policy.

Scope discipline: the tree schema, discrimination order, conversion table, and
normalization `N` are owned by the tree contract (promoted into the Spec 004 rewrite) —
this amendment **cites** them and adds only what 011 owns: the SSR seam, the wire
encodings, the page-assembly semantics, and the hash algorithm/encoding. Root identity,
the mount grammar, and the three-layer duplicate roster are owned by the root contract
(likewise promoted into the 004 rewrite) — this amendment lands only Layer 2 and the
manifest's wire form, which the root contract explicitly deferred "to pin with the
Spec 011 payload-encoding rows".

## The amendment — exact old → new pairs

Each pair is an exact-string replacement (Edit-ready) against the revision named above.

### Pair 1 — Abstract, artefact roster (Old occurs once)

The `day8/re-frame2-ssr` surface enumeration gains the two seam fns. (Ripple: the
`day8/re-frame2-ssr` row of the Conventions packaging table mirrors this sentence and
must gain the same clause in the same PR — see §Cross-spec ripples.)

**Old:**

```
the pure hiccup → HTML emitter (`render-to-string`), the FNV-1a structural render-tree hash (`render-tree-hash`), the `:rf/hydrate` event with `:replace-frame-state` semantics,
```

**New:**

```
the pure hiccup → HTML emitter (`render-to-string`), the compiled-substrate structural-tree emitter (`emit-ui-tree`) and its fingerprint companion (`ui-tree-fingerprint`) per [§The compiled-substrate tree entry](#the-compiled-substrate-tree-entry--emit-ui-tree), the FNV-1a structural render-tree hash (`render-tree-hash`), the `:rf/hydrate` event with `:replace-frame-state` semantics,
```

### Pair 2 — §The render-tree → HTML emitter: the new consumption boundary (Old occurs once)

Inserts the `emit-ui-tree` subsection immediately after the streaming pointer line
(before `#### XSS at output boundaries`, which binds both entry points).

**Old:**

```
Streaming/chunked emission ships as the `:rf/suspense-boundary` primitive — see [§Streaming SSR](#streaming-ssr) under Detailed design.
```

**New:**

```
Streaming/chunked emission ships as the `:rf/suspense-boundary` primitive — see [§Streaming SSR](#streaming-ssr) under Detailed design.

#### The compiled-substrate tree entry — `emit-ui-tree`

> **Status: lands at S5** (rf2-vxgfnd W10). The input's node schema, discrimination
> order, canonical form, conversion table, and semantic normalization `N` are owned by
> [004 §The JVM structural subset](004-Views.md) (the compiled-substrate tree contract);
> this section owns only the SSR seam — signature, version gate, hash
> algorithm/encoding, and the relationship to the hiccup path.

The compiled substrate's views do not produce hiccup: their JVM realisation builds the
**versioned public structural tree** (node schema v1 — `re-frame.ui.tree`, root stamped
`:rf.ui/tree-version 1`). `re-frame.ssr` therefore ships a second emission entry
alongside `render-to-string`:

- **`(re-frame.ssr/emit-ui-tree tree opts) → HTML string`** — consumes a version-1
  structural tree; applies the serialisation half of the conversion table (final
  attribute names, boolean emission classes, property-only omission, escaping, void
  handling — all per the owning contract's rows); erases view-boundary nodes (the dev
  source-coord annotation policy stays owned by [§Source-coord annotation under
  SSR](#source-coord-annotation-under-ssr)); writes trusted-HTML nodes verbatim (the
  single escaping bypass, identical on both emitters). `opts` mirrors the emitter
  family — `:doctype?` and `:emit-hash?` compose exactly as for `render-to-string`,
  with the render-hash landing on the first DOM-tag element of the tree per
  [§Hydration-mismatch detection](#hydration-mismatch-detection).
- **`(re-frame.ssr/ui-tree-fingerprint tree) → digest`** — hashes the canonical-EDN
  serialisation of `N(tree)`. The normalization `N` (the semantic-node space) is owned
  by the tree contract; the hash **algorithm and digest encoding are owned here** —
  FNV-1a 32-bit / lowercase hex, the same commitment as
  [§Hydration-mismatch detection](#hydration-mismatch-detection), and the value the
  Root Manifest's `:render-fingerprint` carries.

(Final facade naming rides the diff-time facade-export rule; the recommended names above
are the tree contract's.)

**Version gate — BEFORE any emission.** `emit-ui-tree` validates `:rf.ui/tree-version`
first. A missing field, a non-integer, or an unsupported version throws
`:rf.error/ssr-ui-tree-version-unsupported` with ex-data `{:got … :supported #{1}}` —
fail-loud at the boundary, the same construction-time posture as
`:rf.error/ssr-missing-payload-policy`. Nothing is emitted past a failed gate. Malformed
nodes *past* the gate throw `:rf.error/ui-tree-malformed` (the shared tree-consumer
category — one id across `find`, the fingerprint fn, and this serialiser). Both are
catalogued in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).

**Relationship to the hiccup path — frozen, no shim.** `render-to-string` continues to
consume the hiccup render-tree contract and **freezes with the stock-Reagent
compatibility tier [TRANSITION]** — there is **no adapter shim between the two tree
shapes**: a hiccup tree is never converted into a structural tree, nor a structural tree
into hiccup. The compiled root render pipeline is: JVM emitter → structural tree →
`emit-ui-tree`. Everything downstream is unchanged `re-frame2-ssr` surface — the
response accumulator, error projection, hydration-payload machinery, and the
[§XSS at output boundaries](#xss-at-output-boundaries) escaping discipline bind this
entry point identically (the conversion table's escaping rows are the same
three-position rules; closed node variants mean no unknown-node fallback arm exists).
```

### Pair 3 — new §Multi-root SSR section (Old occurs once; insertion before the teardown contract)

**Old:**

```
### Per-request frame teardown contract

Per [§Server flow](#server-flow-per-request) every per-request server frame ends with `destroy-frame!`.
```

**New:**

```
### Multi-root SSR — root manifests, page assembly, and static roots

> **Status: lands at S5** (rf2-vxgfnd W10). A **root** is one React DOM
> render/hydration unit; a **frame** is one re-frame2 state world; roots ↔ frames are
> many-to-many and mount position is never identity. Root identity, the mount grammar,
> Root Descriptor v1, and the three-layer duplicate roster are owned by
> [004 §Roots and mounting](004-Views.md) (the root-identity-and-mount contract); this
> section owns the S5 halves that contract defers here: the Root Manifest v1 **wire
> encoding**, the **Layer-2 server page registry**, **per-root failure isolation**, the
> **hydration ledger**, and `render-static` under the explicit static-root policy.
> Everything here is additive to the single-root flow above — a page with one classic
> hiccup root and the `__rf_payload` boot helper is unchanged.

#### Root Manifest v1 — wire encoding

Root Manifest v1 = Root Descriptor v1 (minus the dev-only `:root-id-provenance`) plus
the six render-time extension keys (`:element-locator`, `:props`, `:frame-payload-ids`,
`:render-fingerprint`, `:identifier-prefix`, `:phase`), versioned by
`:rf.root/schema-version 1` per [Conventions §Reserved namespaces](Conventions.md) —
strict-superset compatibility, readers ignore unknown keys, additive keys never bump the
integer. The encoding rows (pinning the convention the root contract deferred here):

| Row | Rule |
|---|---|
| element | `<script type="application/edn" data-rf2-root-manifest="1">…manifest-edn…</script>`, emitted as the **immediately-following sibling** of the root's container element |
| body | the `pr-str`'d Root Manifest v1 map, escaped through the EDN-aware script-body encoder (`escape-edn-script-body`) — string content cannot close the script element; `</` / `<!` token breakouts fail loud; bare EDN tokens containing `<` round-trip unchanged. The same encoder discipline as the `__rf_payload` script and the streaming delta chunks — one encoder, every EDN script body |
| charset | the document's — the always-present shell hardcodes `<meta charset="utf-8">` as the first `<head>` byte; no per-script charset attribute exists |
| discovery | **positional**: `hydrate-root` reads the immediately-following sibling of its `dom-node`. The `data-rf2-root-manifest="1"` marker + `type="application/edn"` are discovery **verification** (and tooling enumeration) only — an adjacent script missing either is "no manifest" (`:rf.error/root-manifest-invalid`, data `{:missing :manifest}`), never a guessed parse |
| identity | **content-borne**: root-id and identifier-prefix come from the manifest body, never from an attribute and never from client opts. The marker attribute deliberately carries no root-id — one identity source; an attribute copy would be a second source that could drift |
| frame payloads | each referenced frame payload rides its own document-scoped `<script type="application/edn" data-rf2-frame-payload="…">` element, the attribute carrying `(escape-attr (str payload-id))` — attribute-borne id because payloads are shared across roots and discovered **by id**, not positionally (the `data-rf2-suspense-hydrate` precedent); body through the same EDN encoder; one element per payload id per page **[S5-CONFIRM]** |

A dedicated MIME type (`application/rf2-root+edn`) was considered and is **not**
recommended: every EDN script body 011 ships uses `type="application/edn"` + a
`data-rf2-*` attribute (`__rf_payload` by pinned id; suspense deltas by attribute-borne
id), and a second type string would fork the single-encoder discipline for zero extra
discrimination the marker attribute does not already provide. **[S5-CONFIRM]** — the
house-pattern alignment over the dedicated-MIME spelling.

Manifest **emission** is fail-loud, never truncated: a prop value the EDN-safe encoder
cannot carry fails that root's render (`:rf.error/root-manifest-invalid`, data
`{:unserialisable-prop <k>}`); a host-authored container without an id fails it too
(data `{:missing :container-id}`); an emitter-synthesised container takes the
deterministic id `"rf2-root-" + root-id-slug`.

#### The server page registry (Layer 2)

Page assembly registers every root of the response — manifest-bearing roots **and**
`render-static` roots (static roots hold identity too; a static and a live root can
never claim one id) — in a **per-response registry**:

- A second registration with an equal root-id fails the render:
  `:rf.error/duplicate-root-id` (server tier), projected per
  [§Drain-time error classification](#drain-time-error-classification--the-pre-commit-projected-status-arm).
  This is the layer that catches **independently rendered page fragments** composed
  into one response — the case build-time indexing (Layer 1) cannot see and the client
  registry (Layer 3) sees only after the wire bytes shipped.
- The same registry asserts **identifier-prefix uniqueness** across the page's roots
  (`:rf.error/root-manifest-invalid`, data `{:conflict :identifier-prefix}`) — two
  roots sharing a prefix would collide `use-id` output.

The registry is per-request transient state and follows the
[§Response storage substrate](#response-storage-substrate) contract verbatim: a
framework-private side-channel keyed by frame-id, never an `app-db` path, released on
frame teardown via the `:ssr/on-frame-destroyed` hook.

#### Per-root failure isolation

One root's render throw does **not** fail the page — the multi-root parallel of
[§Failure semantics — inline fallback](#failure-semantics--inline-fallback):

1. The throwable is caught at that root's render step.
2. A `:rf.ssr/root-render-failed` trace fires (per
   [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) with
   `{:root-id … :exception t :recovery :static-fallback}`.
3. That root's container position ships the host-declared static fallback when the
   root's mount site declared one, else the deterministic error comment
   `<!-- rf2-root-failed: <root-id-slug> -->`. The comment carries the slug only —
   never exception detail; internal detail rides the trace, the wire stays clean
   (§Where sanitisation happens applies unchanged).
4. **No manifest script and no frame payloads are emitted for the failed root** —
   there is nothing to hydrate, so the client bootstrap (which iterates discovered
   manifests) never attempts that root; a direct `hydrate-root` against its container
   finds no manifest and fails exactly that root (`:rf.error/root-manifest-invalid`,
   `{:missing :manifest}`).
5. A hydration-ledger row records the failure (`:outcome :failed` + the category).
6. **Sibling roots are unaffected** — their renders, manifests, payloads, and
   hydration proceed; frame payloads referenced only by the failed root are not
   installed; payloads shared with surviving roots ship normally. A multi-root fixture
   pins sibling isolation.

The isolation boundary stops at the root. A throw in the **page envelope/shell**
(outside any root's render), a per-request frame setup throw, or a projected 5xx
discovered before commit stays on the existing
[§Drain-time error classification](#drain-time-error-classification--the-pre-commit-projected-status-arm)
arm — the envelope is the response's structural foundation, exactly as the streaming
shell walk sits outside the continuation contract. Client-side, the same scoping holds:
a fingerprint/digest disagreement fails that root loudly
(`:rf.error/root-hydration-mismatch`); a frame payload already installed with a
different digest — or a plan-fingerprint conflict — fails exactly the arriving root
(`:rf.error/frame-payload-conflict`, runtime preflight arm), and the installed frame
plus the roots already using it are untouched. No `suppressHydrationWarning`-style
escape exists.

#### The hydration ledger

The per-response, serialisable record of every root the page registry accepted — one
row per root:

```clojure
{:root-id            :page/shop
 :outcome            :rendered            ;; :rendered | :failed | :static
 :element-locator    {:id "shop-root"}
 :frame-payload-ids  [:frame/shop :frame/session]   ;; absent for :static
 :render-fingerprint "…"                            ;; absent for :failed
 :error              :rf.ssr/root-render-failed     ;; :failed rows only
 :manifest-bytes     412                            ;; wire size of the manifest script
 :payload-bytes      1834}                          ;; wire size of payloads FIRST-installed by this root
```

The byte fields make the ledger the wire-artefact size accounting the production
budgets consume — root manifests, frame payloads, and hydration digests are measured
here, on wire artefacts, not on browser JS modules. The ledger is per-request transient
state in the same framework-private side-channel family as the response accumulator
(keyed by frame-id, never `app-db`, released via `:ssr/on-frame-destroyed`); the host
adapter reads it after the drain via `re-frame.ssr/get-hydration-ledger`
**[S5-CONFIRM]** (naming rides the diff-time facade rule; field roster above is v1 and
additive-only). Consumers: host diagnostics, the multi-root isolation fixtures, Xray's
root/instance surfaces, and the bundle/wire size gates.

#### `render-static` — the explicit static-root policy

`(ui/render-static root-form)` is the S5 static-root entry: **prove + declare, emit
inert HTML, no manifest, no payload** — but full identity participation.

| Row | Rule |
|---|---|
| the proof | the compiler computes a transitive `requires-client-runtime?` capability over the full capability vocabulary — subs / handlers / leases **and** `local`, effects, refs, context, portals, error boundaries, presence, `client-only`, custom-element properties, foreign components |
| the declaration | hydration is elided **only when** the compiler proves no client capability **and** the host declares the root static (a `render-static` entry, or root-manifest policy). "No subs, no handlers" alone NEVER silently strips a root's runtime — **no silent elision** |
| failed proof | a declared-static root whose proof fails throws `:rf.error/ssr-static-root-requires-runtime` **[S5-CONFIRM]** naming the blocking capability bits in ex-data — never an inert-shipped root that looks interactive |
| identity | the static root registers its root-id in the Layer-2 page registry (and in Layer-1 build indexing) — a static and a live root can never claim one id |
| wire | inert HTML only: no manifest script, no frame payloads, no hydration; ledger row `:outcome :static` |

#### Multi-root hydration flow (client)

Per root, in order (the root contract owns the signatures; restated here as the SSR
handshake): positional manifest discovery → validate (`:rf.root/schema-version`, build
digest, render fingerprint — failures are `:rf.error/root-manifest-invalid` /
`:rf.error/root-hydration-mismatch`, scoped to that root) → install referenced frame
payloads (idempotent and order-independent; the first root referencing a payload
installs it, later roots find it live; conflict is `:rf.error/frame-payload-conflict`
failing exactly the arriving root) → `hydrate-root` (identity FROM the manifest;
client-supplied identity opts are rejected) → the root phase-flip swaps `client-only`
fallbacks in one update → first connected commits acquire ownership (fresh caches do
not refetch). A locator resolving to no element is `:rf.error/root-container-missing`,
scoped to that root.

### Per-request frame teardown contract

Per [§Server flow](#server-flow-per-request) every per-request server frame ends with `destroy-frame!`.
```

### Pair 4 — teardown table: the ledger + page-registry slot (Old occurs once)

The new side-channel state joins the release table (the side-channel-atom contract
already mandates a cleanup hook for every frame-id-keyed atom).

**Old:**

```
| pending error-trace buffer           | `re-frame.ssr`             | `defonce` atom keyed by frame-id, side-channel   | the `:ssr/on-frame-destroyed` hook drops the slot |
```

**New:**

```
| pending error-trace buffer           | `re-frame.ssr`             | `defonce` atom keyed by frame-id, side-channel   | the `:ssr/on-frame-destroyed` hook drops the slot |
| page registry + hydration ledger ([§Multi-root SSR](#multi-root-ssr--root-manifests-page-assembly-and-static-roots)) | `re-frame.ssr`             | `defonce` atom keyed by frame-id, side-channel   | the `:ssr/on-frame-destroyed` hook drops the slot |
```

### Pair 5 — Cross-references (Old occurs once)

**Old:**

```
- [009-Instrumentation.md](009-Instrumentation.md) — hydration-mismatch trace events.
```

**New:**

```
- [009-Instrumentation.md](009-Instrumentation.md) — hydration-mismatch trace events.
- [004-Views.md](004-Views.md) §The JVM structural subset — the compiled-substrate tree contract `emit-ui-tree` consumes: node schema v1, discrimination order, canonical form, the DOM conversion table, and the semantic normalization `N` (011 owns only the seam, the version gate, and the hash algorithm/encoding).
- [004-Views.md](004-Views.md) §Roots and mounting — root identity, the mount grammar, Root Descriptor v1, and the three-layer duplicate roster (011 owns Layer 2, the manifest wire encoding, per-root failure isolation, the hydration ledger, and the static-root policy rows).
```

## Cross-spec ripples

### 009 §Error event catalogue (hot-zone; rows land in small batches with their feature)

**New rows needed at S5** (none exists on main — verified against the checked-in
catalogue at the revision read):

| Id | Notes |
|---|---|
| `:rf.error/ssr-ui-tree-version-unsupported` | Already **promised by the shipped catalogue**: the `:rf.error/ui-tree-malformed` row on main says "the SSR seam's version-gate sibling `:rf.error/ssr-ui-tree-version-unsupported` lands with the S5 serialiser". Emitted by `re-frame.ssr/emit-ui-tree` (and `ui-tree-fingerprint`), pre-emission; ex-data `{:got … :supported #{1}}`; recovery `:no-recovery` — re-emit from a supported tree version or upgrade the ssr artefact. |
| `:rf.ssr/root-render-failed` | Per-root failure-isolation trace (§Per-root failure isolation); `{:root-id :exception :recovery :static-fallback}`; the multi-root sibling of `:rf.ssr/suspense-boundary-failed`. Spelling **[S5-CONFIRM]**. Row pre-drafted in [spec-009-ui-catalogue-rows.md](spec-009-ui-catalogue-rows.md) §2.4. |
| `:rf.error/ssr-static-root-requires-runtime` | Declared-static root failed the transitive `requires-client-runtime?` proof; carries the blocking capability bits. Spelling **[S5-CONFIRM]**. Row pre-drafted in [spec-009-ui-catalogue-rows.md](spec-009-ui-catalogue-rows.md) §2.4. |
| `:rf.error/root-hydration-mismatch` | The hydration fingerprint/digest disagreement this amendment's Pair-3 text uses twice. **Zero occurrences in the checked-in catalogue** (anchor-verified; the root contract's "existing row" phrasing refers to the 03 §11 taxonomy doc, not 009 — see [spec-009-ui-catalogue-rows.md](spec-009-ui-catalogue-rows.md) §1 anchor findings + §3) — so it is a NEW always-on S5 row, pre-drafted there in §2.4, not an amendment. |

**Existing rows extended** (all verified present on main; each row's text already
forecasts its S5 arm, so these are arm-extensions, not new categories):

| Id | S5 arm to add |
|---|---|
| `:rf.error/root-manifest-invalid` | manifest missing/unreadable at hydrate, `:rf.root/schema-version` incompatibility, unserialisable props at server emit, identifier-prefix conflict, `{:missing :container-id}` at server render (the row on main ships the S1 `{:missing :manifest}` arm and names these S5 arms as landing "with their stage"). |
| `:rf.error/duplicate-root-id` | the Layer-2 **server tier** (per-response page registry; the row on main says "The Layer-2 server page registry lands S5"). |
| `:rf.error/root-container-missing` | the S5 hydration arm — manifest locator resolving to no element (the row on main names it as landing "with server rendering"). |
| `:rf.error/frame-payload-conflict` | the runtime **preflight arm** — payload id installed with a different digest / plan-fingerprint mismatch, failing exactly the arriving root (the row on main says it "lands S5 with hydrate preflight"). |
| `:rf.error/ui-tree-malformed` | row-text touch only: the emitted-by list gains `re-frame.ssr` (the serialiser is one more shared-gate consumer). |

(Correctness pass 2026-07-12: an earlier revision claimed `:rf.error/root-hydration-mismatch`
"needs no change — the existing row". That was wrong — the id has no row in checked-in 009;
it is the fourth NEW row above.)

### Conventions

- **No new keyword namespace is reserved.** Manifest keys ride `:rf.root/*` — its row
  exists on main and already carries the descriptor/manifest family, the
  strict-superset rule, and `:rf.error/root-manifest-invalid` at hydration. The tree
  version key rides `:rf.ui/*` — its row exists and reserves `:rf.ui/tree-version`.
- **No new Conventions row for the wire attributes.** `data-rf2-root-manifest` (and
  `data-rf2-frame-payload`, if confirmed) follow the established practice for
  011-owned `data-rf2-*` wire attributes (`data-rf2-source-coord`,
  `data-rf2-suspense-*`): attribute vocabulary lives in the owning spec's encoding
  rows, not in the Conventions keyword tables.
- **Packaging table**: the `day8/re-frame2-ssr` artefact row mirrors the 011 Abstract
  roster and must gain the `emit-ui-tree` / `ui-tree-fingerprint` clause in the same PR
  as Pair 1 (the two sentences are kept in lockstep).

### Spec-Schemas

Candidate registration of Root Manifest v1 as `:rf/root-manifest` (with the
`:rf.root/schema-version` discriminator), alongside `:rf/hydration-payload` /
`:rf/response` / `:rf/head-model`. **[S5-CONFIRM]** — alternatively the schema family
stays 004-rewrite-owned and 011 owns only the encoding rows; either way there is
exactly one normative schema home.

### API.md

Facade rows for `emit-ui-tree`, `ui-tree-fingerprint`, and `get-hydration-ledger` —
each classified + justified at land time per the standing diff-time facade-export rule.

## [S5-CONFIRM] roster

1. **Manifest script convention** — this draft pins the house pattern
   (`type="application/edn"` + `data-rf2-root-manifest="1"` marker, positional
   discovery, content-borne identity, `escape-edn-script-body`) over the floated
   dedicated MIME type `application/rf2-root+edn`. 011's existing encoder discipline is
   uniform `application/edn` + `data-rf2-*` attributes, so the house pattern is
   recommended; confirm the alignment.
2. **Frame-payload script convention** — document-scoped
   `<script type="application/edn" data-rf2-frame-payload="<wire-id>">`, attribute-borne
   id (payloads are shared and discovered by id — the suspense-delta precedent), one
   element per payload id per page.
3. **Failed-root wire artefact** — the error-comment shape
   `<!-- rf2-root-failed: <root-id-slug> -->` and the optional host-supplied per-root
   static-fallback opt (default absent: comment only).
4. **Hydration-ledger surface** — the v1 row field roster and the reader name
   (`re-frame.ssr/get-hydration-ledger`); naming rides the diff-time facade rule.
5. **New 009 id spellings** — `:rf.ssr/root-render-failed` and
   `:rf.error/ssr-static-root-requires-runtime`.
6. **Spec-Schemas home for Root Manifest v1** — register `:rf/root-manifest` in
   Spec-Schemas, or leave the schema family 004-rewrite-owned with 011 owning only the
   encoding rows.

This draft also **resolves** the root contract's `[S1-CONFIRM]` register item 2 (the
manifest script attribute/type convention, deferred there "to pin … with the Spec 011
payload-encoding rows") — subject to roster items 1–2 above.
