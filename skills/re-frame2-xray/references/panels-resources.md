# panels-resources — server state: the Resources tab

The server-state family: the **Resources** tab, Xray's surface for
re-frame2's declarative server state (Spec 016). **Mixed scope** — a
process-global resource registry plus the observed frame's live
cache/ledger (the live sections follow the **L1 frame picker**, not the
focused epoch), with per-epoch mutation evidence drawn from the trace
stream. Inventory + scope matrix: [panels.md](panels.md).

## What it shows

Question: **Where is my server state — what owns it, and is it stale?**

The sections, top → bottom:

- **STATIC RESOURCE REGISTRY** — every registered resource + scope,
 stale-after, GC-after, and the routes that activate it.
- **LIVE INSTANCES** (per frame) — each scoped cache entry with state,
 generation, owner count, and freshness.
- **WORK LEDGER** — live fetch attempts (running · cancellable ·
 deadline).
- **ROUTE / RESOURCE GRAPH** — blocking activations (the SSR wait
 points), the lifecycle timeline, cache growth.
- **SCOPE RESOLUTION TIMELINE** — which named scope resolver ran, its
 inputs, the resolved scope — including fail-closed nil evidence (a
 scope-requiring site that got nil and produced NO global fallback).
- **MUTATION CONTINUATIONS + SCOPED INVALIDATION** — the surface that
 makes "`:reply-to` is for workflow; populate/patch/invalidate are for
 cache" visible: did the accepted reply continue into app workflow, and
 which scopes a write resolved, refetched, or left stale — fail-closed,
 never an implicit global blast.
- **SCOPE AUDIT** — every `:rf.scope/global` use + lints.

**The absence-is-evidence rule.** A continuation / refetch the runtime
*suppresses* (a stale or superseded reply, an `ensure` that skips a fresh
read) surfaces as its own suppression op, not as a missing row — so "my
`:reply-to` didn't fire" / "my read didn't refetch" is answered by the
*presence of the suppression evidence*. The op vocabulary is normative in
[`024-Resources-Panel.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/024-Resources-Panel.md)
+ [`spec/016-Resources.md`](https://github.com/day8/re-frame2/blob/main/spec/016-Resources.md); cite those
rather than re-deriving field-by-field detail.

## Posture

- **Read-only** — opening the panel pins nothing: it dispatches no
 ensure, attaches no owner, refetches nothing, extends no GC.
- **Privacy** — params, scopes, AND data all get the same
 summarize-and-redact treatment: every value is a bounded,
 redaction-aware preview, never the raw value.
- **Decoupled** — Xray does not `:require` the optional resources
 artefact; the panel reads the registry via the registrar query API and
 the live cache/ledger from the runtime state the spine already
 publishes, so it renders cleanly even when the host wired no resources.

**Open when:** "where's my server state?", "what's in flight?", "is this
resource stale?", "what owns this cache entry?", "did my mutation's
`:reply-to` continuation fire?", "which scopes did this write
invalidate?", "why didn't this read refetch?"
