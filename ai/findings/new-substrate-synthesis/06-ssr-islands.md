# 06 — SSR: one analyzer, one emitter per build; roots, frames, and hydration

**Status:** final · 2026-07-11. **A React hydration root is not a re-frame2 frame** —
a DOM/render unit and a state world are distinct identities (I-8), and they are
many-to-many. The term is **root**; "island" is not part of the vocabulary.

## 1. One compiled form; the honest JVM contract

`defview` is `.cljc`: each host build runs the shared analyzer over the source and hands
that build's own AST to exactly one emitter — CLJS → direct JSX; JVM → the canonical
serializable render tree consumed by the existing `re-frame2-ssr` artifact (no second
server product; packaging in 05 §1). The hosts never meet as ASTs: analysis is
host-parameterized, so the two AST values are not guaranteed equal, let alone one value.
One contextual conversion/escaping rule table serves both emitters and a parity corpus
compares their normalized output, so divergence is **detected** rather than prevented.
Parity is **normalized structural equivalence** over semantic nodes
(tag/ns, attr names+values, child order, escaping, keyed order, void/boolean, fragments,
fallbacks) — fingerprinted, generatively tested (07 §4) — not byte-identical HTML.

**The JVM subset, stated:**

| Feature | JVM structural render |
|---|---|
| structure, props, subs, branches, lists, event intent, `ui/html` | full semantics (subs via the pure snapshot path — no ownership, no watches) |
| `local` | contributes its **initial value**; the setter is absent — invoking it in a JVM test is a typed error |
| `effect` | does not run; recorded as capability metadata |
| refs | absent |
| `portal` / `client-only` | explicit deterministic fallbacks |
| `error-boundary` | server failure policy (project error / status per Spec 011), not client recovery |
| `presence` | renders `:present`; phase metadata exposed structurally |

Mounted (Tier-3) tests are required for state transitions, effects, refs, focus, portals,
presence timing, and error recovery — guide 09 says this out loud. "If the browser
renders it, the server renders it" is scoped to the structural subset above.

Escaping is structural: closed AST node types mean no unknown-node fallback arm
(rf2-4i115b class deleted at the IR level). `ui/html` is the one explicit bypass — same
contract on both emitters (02 §6). Payload embedding uses the existing EDN-safe encoder.
Server lifecycle is standard Spec 011: per-request frames, drain, render, project errors,
teardown.

## 2. Roots, frames, and the hydration contract

**Identities (I-8):** a **root** is one React DOM render/hydration unit; a **frame** is
one re-frame2 state world. A page has N roots; roots reference frames via payload ids;
several roots may share one installed frame; one root may scope several frames via
nested providers.

**The root manifest** — each independently hydratable unit ships:

```clojure
{:root-id            :page/shop
 :element-locator    {:id "shop-root"}
 :view-id            :shop/app
 :props              {…}
 :frame-payload-ids  [:frame/shop :frame/session]
 :render-fingerprint "…"
 :build-digest       "…"
 :identifier-prefix  "rf2-shop-"
 :phase              :server}
```

Mount position is **never** identity. Duplicate root ids on one page are a build error.
Frame payloads install **idempotently and order-independently** — the first root
referencing `:frame/session` installs it; later roots find it live (ENSURE preflight
semantics, 03 §8, doing double duty).

**Hydration per root:** the root manifest rides a script element adjacent to the root's
container (same EDN-safe encoding as payloads) and is read + validated **before**
`hydrate-root` is called: validate build digest + render fingerprint → install referenced
frame payloads (idempotent) → `hydrate-root` → root phase-flip swaps `client-only`
fallbacks in one update → first connected commits acquire ownership (fresh caches don't
refetch).

**Failure scopes are precise:** a root fingerprint/DOM mismatch fails **that root**
loudly (client-fresh render or its error view; source-located dev diagnostics); a bad
frame payload affects exactly the roots referencing it; an envelope/build incompatibility
may reject the page's hydration set; a broken dev annotation never changes app
semantics. A multi-root fixture pins sibling isolation. No
`suppressHydrationWarning`-style escape exists.

## 3. Static output and `client-only`

**Static roots are an explicit policy, not an inference:** the compiler computes a
transitive `requires-client-runtime?` capability (subs/handlers/leases *and* local,
effects, refs, context, portals, boundaries, presence, client-only, custom-element
properties, foreign components), but hydration is elided only when the compiler proves
no client capability **and** the host declares the root static (`render-static` entry or
root-manifest policy) — "no subs, no handlers" alone never silently strips a root's
runtime. The footer example becomes: prove + declare, emit inert HTML, no payload.

`client-only` fallbacks must be capability-free (compiler-checked); the hydrating client
first renders the same fallback, then one root flip swaps all sites in a single update.
Foreign React components on SSR paths sit inside `client-only`; no foreign execution on
the JVM.

## 4. Event vectors on the server; resumability is research-tier

Pre-hydration replay — a ~1 KB bootstrap, queued interaction replay, per-root side
tables — is **not in v1**: it has no named consumer, and a correct browser contract
(capture/bubble order, passive listeners, default actions, controlled-input interplay,
replay-into-changed-DOM, exactly-once) is a conformance project of its own. It is a
**post-alpha research spike** with hard graduation criteria (independent consumer,
browser conformance matrix, measured benefit over plain hydration, size budget).

What ships: event vectors are retained **as data in the manifest and
the JVM tree** (headless tests assert intent; Xray reads the static surface); on the
client they lower to normal React handlers; no executable handler attributes are emitted
into HTML. The serializability *property* of data handlers is intact — it simply isn't
carrying a replay platform yet.

## 5. Head, streaming, RSC

- **Document head:** re-frame2's existing head model is authoritative. Substrate views do
  not hoist metadata through React's head/resource mechanisms (one head owner; silent
  double ownership diverges under SSR). Foreign head needs are explicit interop.
- **Streaming:** the tree emitter is chunkable; streaming policy stays host-side per
  Spec 011 (`:rf/suspense-boundary` remains the low-level marker; no authoring sugar
  before a dual-host parity proof + consumer).
- **RSC:** out of scope; the JVM SSR path is the canonical server story.
