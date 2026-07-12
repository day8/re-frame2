# 04 — Debugging: the glass cockpit

**Status:** final · 2026-07-11. Dev-only, erased from production by proven absence (05 §4).
Design rule (rf2-6j0knp lineage): **attribution is emitted at the cause site, never
reconstructed.** One authoritative, versioned evidence schema is shared by Xray, Story,
compiler diagnostics, and tests — tools consume the schema, not private React state.

## 1. Two evidence layers

**Compiler manifest — what *can* happen.** Per view, dev: source coords, prop slots +
schema, template fingerprint, hook signature, capability bits, and every site (subs with
query shapes; events with event shapes + a `:serializable?`/`:dynamic` flag; leases;
effects; presence sites) with source + template path. No runtime values; useful before
mount — to Xray, Story, editors, and agents (the AI-ergonomics goal rides this surface).

**Committed instance record — what *did* happen.** Published only at connected commit;
speculative renders publish nothing. Shape (versioned):

```clojure
{:render-key 1042  :parent-render-key 1039
 :root-id :page/shop  :frame-id :shop  :view-id :cart/row
 :generation 3  :connection :connected
 :observations [{:kind :subscription :query [:cart/item 17]
                 :target-id 88 :version 12 :owned? true}]
 :rf.view/causes [{:kind :subscription :target-id 88 :from 11 :to 12}]}
```

- `parent-render-key` gives direct hierarchy — no Fiber or DOM walking (legacy adapters
  keep their fallback; this substrate never needs it).
- **Occurrence identity:** DOM annotations, event provenance, and presence records
  carry `occurrence-path` (e.g. `[{:site 17 :key order-id}]`) so keyed rows rendered by
  one instance are distinguishable — render-key alone is insufficient.
- **Story overrides display honestly:** `{:kind :story-override … :owned? false}` — a
  visual override, never evidence that a subscription computed the value.
- **Connection is recorded as observed (03 §4):** the runtime emits three states —
  `:connected` / `:disconnected` / `:dead` — and the immediate cleanup fact is
  `:disconnected {:reason :unknown}` (public React gives no cleanup-time signal
  distinguishing an Activity hide from an unmount). `:activity-hidden` and
  `:unmounted` are qualified **retroactive annotations** of the *prior interval*,
  never runtime states: a reconnect proves the preceding interval was a hide;
  explicit host/root teardown proves unmount; GC-based unmount inference (if
  enabled) is best-effort/eventual — no exact timestamp, a bounded tombstone that
  never retains the cell. Records and tools distinguish runtime state vs current
  tool label vs historical inference; transitions update the record without
  fabricating renders.

## 2. Render causes

**`:rf.view/causes` is a vector** — a commit can have several causes; the header
summarizes ("3 dependencies changed in `::refresh-complete`").

| Cause | Evidence |
|---|---|
| `:mount` | first connected commit |
| `:subscription` | target, query, version from→to, epoch, upstream event/sub |
| `:story-override` | override id, from→to |
| `:prop` | **changed top-level slots** (the sound cheap promise; nested paths are a dev *diff view* on demand, not a default emit — retained-prior-props deep-diff is not free) |
| `:local-state` | setter site |
| `:frame` / `:context` | old → new |
| `:resource` | linked to the resource trace family |
| `:hmr` / `:hmr-remount` | generation; signature-change remounts say so |
| `:hydration-correction` | server/client/probe disagreement |
| `:reconnect-correction` | Activity reveal found newer evidence |
| `:epoch-restore` | **the restore operation token + target epoch** — the repaint is caused by the restore *operation*; old epoch records are never rewritten or back-filled |
| `:foreign-or-react` | honest fallback — never fabricate precision |

**Loss accounting:** every bounded buffer reports `total` / `retained` / `dropped`.
The heatmap and counts are labeled exact only when `dropped` = 0 — no silent truncation
presenting as completeness.

## 3. The static interaction surface

Handlers are data (02 §3): the inspector shows any element's event vector before it is
clicked, plus the registered handler's source/schema; unregistered ids warn at render
(process-global registrar; lazy-registration caveat noted). Sites classified `:dynamic`
say so — the static surface covers literal + normalized-branch sites and is honest about
the rest. `ui/event`/`ui/handler` show stable-but-opaque with a "body merely dispatches —
use a vector" suggestion where applicable; `render-fn` sites show render-phase.

## 4. Source ↔ DOM ↔ cause navigation

Compile-time `data-rf2-source-coord` + render-key (+ occurrence-path) annotation on
compiler-owned host roots — today's attribute vocabulary, so existing Xray click-to-source
works day one. Chains both directions: app-db path → subs → views → elements; element →
event → handler → effects → state diff → sub cascade → commits. React DevTools stays
independent (real names, CLJS-data props, no Fiber dependence); React Performance Tracks
correlate via render-key/epoch ids. **Production-weight explanation:** Xray answers "why
does this view carry a cell/presence/client runtime?" from capability bits + sites — the
absence story made inspectable.

## 5. Tool integration

- **Xray — enrich existing surfaces first.** The evidence lands in the surfaces
  Xray already has: Reactive view gains the causes vector + occurrence identity; Event
  view gains event-site provenance; Epoch timeline gains per-view commit rows and the
  `:epoch-restore` cause; Issues gains the new warning families. **New panels (mounted
  views, SSR/roots, heatmap) come only after an information-architecture review** shows
  an existing surface can't answer the question — the v1 emit obligation is the schema,
  not panels. Trace shapes are Spec 009 catalogue rows (one-catalogue rule, rf2-cs0kd1);
  every Xray PR updates `tools/xray/spec/*` (standing rule).
- **Story:** mounts scenes by **view id**; asserts on JVM structural trees + app-db
  (CLJS-unit-test shape per repo ruling); sub-overrides via the observation-target
  protocol (03 §3) with `:owned? false` honesty; JVM override injection is the explicit
  `ui.test/render` option — one mechanism per host, both named.
- **Pair:** hot-swap = the HMR path (03 §10) over nREPL; read-only projections
  (`view-manifest`, `mounted-views`, `explain-render`, `view-dependencies`,
  `view-event-sites`) in `re-frame.ui.tool` — the tool tier, never the authoring
  namespace.
- **Epochs:** restore causes carry the operation token (above) — the timeline stays
  truthful through time travel without rewriting history.

## 6. Fail-loud authoring checks

Compile-time errors: dynamic tag heads; markup-returning `map`; missing keys;
loop-capturing vector handlers, `sub`/`lease` in loops; bare fns at foreign boundaries;
hooks in branches; missing/non-literal deps; unknown literal DOM props; missing required
props; `frame-root` in conditional/list position; foreign/client-only SSR nodes without
fallback; render-time dispatch/IO. Dev-runtime: schema violations (element coords);
unregistered event ids; placeholder-in-dynamic-vector; keyword-in-child-position;
render-phase `set!`/dispatch; cross-frame carried ops. **Accessibility (high-confidence
only):** missing accessible names on obvious controls, invalid literal ARIA
names/values, click handlers on non-interactive literal elements, presence exits left
interactive — each warning suppressible with a reason. Every message: what, why, the
smallest idiomatic rewrite, with the stable site id shared between build logs and Xray.

## 7. Privacy and boundedness (Spec 009 conformance)

Manifests carry shapes and source, never live values; render values classify through
their owning sub/schema; sensitive/large values redact/elide before off-box egress
(override values, occurrence keys, query args, and event payloads route through the same
policy); histories bounded with loss accounting (§2); production keeps only the always-on
error payload; paths are project-relative.

## 8. Debug-quality gates (fixtures in 07 §6)

Every cause category producible and explained; two instances of one view distinguishable
through mount/update/HMR/unmount; occurrence-paths distinguish keyed rows; conditional
site attach/detach visible only after commit; a DOM click traceable end-to-end; override
records honest (`:owned? false`, real ownership absent); restore causes name the
operation + target without history rewrites; loss accounting present on every buffer;
sensitive values absent off-box; production scan finds none of the debug roster; no
monkey-patches anywhere.
