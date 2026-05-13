# Principles

The load-bearing principles for Causa-MCP. When a design call
has two reasonable options, these are the tie-breakers.
Implementers and contributors should be able to read this doc
and reach the same answers Causa-MCP already reached.

These are downstream of the framework's
[Principles](../../../spec/Principles.md) and of Causa's
[Principles](../../causa/spec/Principles.md) (Causa-MCP is
Causa's agent face — the panel's principles apply transitively
to anything Causa-MCP does). The principles below are what
*Causa-MCP adds* on top.

## Tool consumes the framework; doesn't extend it

Causa-MCP is a **downstream consumer** of re-frame2's existing
surfaces. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

The eighteen tools route through the existing
`re-frame2-causa-mcp.runtime` namespace via `cljs-eval`. Nothing
new is registered against the framework; nothing new is
introduced into a consumer app's runtime.

This is the
[downstream-EPs-consume-foundation rule](../../../spec/Principles.md)
applied to tools: tools observe and exercise what the framework
emits and registers; they do not invent new substrates.

Concretely: when implementation surfaces a runtime gap (e.g.
"I want to inspect cross-frame causality but the trace bus
doesn't expose `:parent-frame`"), file a `bd` bead against
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
or the relevant Causa spec. Don't bolt a parallel surface onto
the MCP server.

## Origin tagging is the convention, not a suggestion

Every Causa-MCP-driven side-effect on the trace bus carries
`:tags :origin :causa-mcp`. The tag is set at the entry point of
each mutating tool (`dispatch`, `reset-frame-db`,
`restore-epoch`) and at the boundary of `eval-cljs` (any
dispatch the eval'd form triggers inherits the tag — the
runtime install hook reads
`re-frame2-causa-mcp.runtime/current-origin` for the dynamic
extent of the eval call).

This is the **load-bearing differentiator** against generalist
agent surfaces (Chrome DevTools MCP's `evaluate_script` is
untagged — an agent hand-rolling `js/window.dispatch(...)` is
indistinguishable from a real user click). It costs nothing at
the call site and the audit-trail payoff is substantial:

| Question | Trace filter |
|---|---|
| What did the agent do this session? | `get-trace-buffer {:filter {:origin :causa-mcp}}` |
| Did the agent or the user fire `:cart/checkout`? | `get-trace-buffer {:filter {:event-id :cart/checkout}}` then read `:tags :origin` on each match |
| Stream just my own mutations as they happen | `subscribe {:topic :trace :filter {:origin :causa-mcp}}` |

The full origin vocabulary (`:app` / `:pair` / `:causa-mcp` /
`:story` / `:test`) is the
[Spec 009 §Origin axis](../../../spec/009-Instrumentation.md);
Causa-MCP's job is to honour it.

Captured as Lock #4 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## EDN canonical; JSON the wire

Causa-MCP's args and returns travel as MCP-shaped JSON but
**the canonical form is EDN-encoded strings inside the JSON
text payload**. The catalogue ships with EDN-encoded `filter`
maps, EDN-encoded `path` arguments, EDN-encoded return values.

Why: re-frame2's runtime speaks EDN. A `:filter` map of
`{:operation :event :origin :causa-mcp :since-ms 5000}` cannot
round-trip through JSON without losing keyword shapes. Encoding
the filter as an EDN string inside the JSON payload preserves
the runtime's vocabulary; the server parses with
`cljs.reader/read-string` at the runtime boundary.

The convention is the same shape pair2-mcp uses; see
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md`](../../pair2-mcp/spec/003-Tool-Catalogue.md)
for the wire-format details.

## MCP, not an IDE plugin

The agent-host integration contract is **Model Context Protocol
over stdio**, not a per-editor extension.

By implementing MCP, this artefact works with every MCP-capable
host (Claude Code, Cursor, Copilot, and whatever lands next)
without per-host plumbing. The cost of one stdio JSON-RPC server
is paid once; the alternative — N editor extensions, each
re-implementing the same tool surface — pays the cost N times
and ages worse.

A custom WebSocket protocol was considered and rejected for the
same reason in pair2-mcp; Causa-MCP inherits the lock.

Captured as Lock #2 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Single persistent nREPL socket

One TCP socket to the shadow-cljs nREPL is opened on first need
and held for the lifetime of the session. Subsequent ops reuse
the socket without reconnecting.

Same break-even calculus as pair2-mcp: per-op latency drops from
~700ms (bash startup + babashka startup + fresh nREPL connect
per call) to ~5–50ms (one bencode round-trip on the open socket).
A typical Causa-MCP session fires dozens to hundreds of ops.

Captured as Lock #3 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Stage-marker-independent

Causa-MCP must work against any conforming re-frame2 runtime. It
does not depend on a specific shadow-cljs build configuration, a
specific stage marker, or a specific re-frame2 release line.

Concretely:

- The `build` argument defaults to `"app"` but is configurable
  on every op (and via the `SHADOW_CLJS_BUILD_ID` env var).
- Port discovery walks `$SHADOW_CLJS_NREPL_PORT` → standard
  shadow paths → `.nrepl-port`. Any of them satisfy the contract.
- Runtime presence is marker-detected (the runtime exposes
  `re-frame2-causa-mcp.runtime/session-id`); a missing sentinel
  triggers automatic re-injection on the next op rather than a
  failed boot.
- The runtime contract is the shape of the eighteen tools, not
  a specific framework version.

A project that adopts a non-default build id, a custom nREPL
port, or a slightly different shadow layout still gets a working
Causa-MCP without code changes.

## Degraded boot, not failed boot

If the nREPL port can't be resolved at startup (no port file, no
env var, shadow-cljs not running yet), the server still boots
and answers `tools/list`. Every `tools/call` returns a
structured `:ok? false :reason :nrepl-port-not-found` error so
the agent host can surface the problem and the user can start
shadow-cljs without restarting the server.

If the runtime hasn't been preloaded, the first mutating /
inspecting tool call returns
`:ok? false :reason :runtime-not-preloaded` with a setup hint
pointing at the consumer's `shadow-cljs.edn` `:preloads` block.

The agent-host workflow is *don't make the user restart
anything*. The server's job is to keep working through the gaps
and surface a useful error when the runtime isn't ready.

Same posture as pair2-mcp.

## Read-mostly catalogue; mutate via the in-panel-equivalent gates

The eighteen tools split 9 read / 3 mutate / 2 stream / 1 escape
hatch / 2 meta. The mutating tools (`restore-epoch`,
`reset-frame-db`, `dispatch`) mirror the in-panel right-click
affordances the human user already has — Causa-MCP doesn't
introduce a *new* mutation surface, it gives the agent the same
surface the human has.

This is the symmetry the privacy + consent model rests on: the
user enabling the MCP server is the user *delegating their
in-panel rights* to the agent. The audit trail (origin tagging,
above) makes the delegation visible.

The escape hatch (`eval-cljs`) is the deliberate exception — it
lets agents reach for surfaces the catalogue doesn't yet cover.
Any side-effect the eval'd form triggers inherits the
`:origin :causa-mcp` tag (the runtime install hook handles the
dynamic-extent rebind). If a particular `eval-cljs` call becomes
recurrent, that's the signal to promote it to its own catalogue
entry.

## Closed-set tool catalogue, deliberate escape valve

The catalogue is **closed-set on purpose**: eighteen named
surfaces map to eighteen Causa panels. Adding tools is a
deliberate act (a `bd` bead, a discussion, a Lock entry in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)). Drift is
prevented by the discipline that every new tool maps to an
existing Causa surface — the framework's instrumentation
contract is the rate-limit.

The `eval-cljs` escape valve absorbs the long tail (per the
section above). Refusing to ship an escape hatch would force
agents through Chrome MCP's `evaluate_script` (which loses the
`:origin` tag) — a worse outcome for the audit trail.

Captured as Lock #5 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).

## Privacy: default-drop `:sensitive?` events at the MCP boundary

Per [spec/009 §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)
(rf2-a32kd):

> Framework-published listener integrations (Sentry/Honeybadger
> forwarders, pair2 server, Causa-MCP server) MUST default-suppress
> `:sensitive? true`.

This is a normative MUST for Causa-MCP. When implementation lands,
every tool that surfaces trace-stream-shaped payloads — the canonical
list is `get-trace-buffer`, `subscribe`, and any tool whose return
includes raw `:rf/trace-event` items or epoch-records carrying the
flag (`get-epoch-history`, `get-causality-graph` when it walks raw
traces) — MUST apply the default-suppress filter at the MCP boundary
before any data crosses into the agent surface.

The contract, identical to `tools/pair2-mcp/`'s implementation
(rf2-zq0n1) and `tools/story-mcp/`'s `re-frame.story-mcp.sensitive`
helper:

- **Default**: events with `:sensitive? true` at the top level are
  dropped. Dropped count surfaces as `:dropped-sensitive` on the
  result (or on each `notifications/progress` payload for streaming
  tools) when non-zero.
- **Opt-in**: per-call `:include-sensitive? true` MCP arg disables
  the gate. The arg name is fixed cross-server (pair2-mcp + story-mcp
  + causa-mcp) so an agent that learns the slot on one server gets
  the same slot on the others.
- **Scope**: only the trace-stream surface. Mutating ops (`dispatch`,
  `restore-epoch`, `reset-frame-db`) and read-mostly per-frame state
  ops (`get-app-db`, `app-db-diff`, `get-machine-snapshot`) don't
  carry `:sensitive?` stamps — payload redaction in those slots is
  the `with-redacted` interceptor's job, not the forwarder's.

The wiring pattern (when implementation lands) mirrors
[`tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)'s
`strip-sensitive` helper + `:include-sensitive?` arg + descriptor
slot on each affected tool. Cross-server symmetry is load-bearing —
an agent that knows the slot on pair2-mcp gets the same slot on
causa-mcp.

The trust boundary is the MCP stdio channel: data that crosses it
reaches the agent host, and from there potentially the LLM provider.
The `:sensitive?` flag is the registrar's "do not ship this
unredacted across that boundary" signal; Causa-MCP honours it.

## Bash-shim back-compat is *not* a goal

Unlike pair2-mcp (which mirrors a pre-existing bash-shim
catalogue under `skills/re-frame-pair2/scripts/`), Causa-MCP
starts greenfield. There is no shim chain to deprecate, no
"side-by-side cadence" lock, no transition period. The MCP
surface *is* the agent-driven surface for Causa.

This is a clean break by accident, not by design: pair2
predated MCP and had to absorb the migration; Causa launches
with MCP-the-pattern already established by pair2-mcp. The
historical context is in
[`tools/pair2-mcp/spec/DESIGN-RATIONALE.md`](../../pair2-mcp/spec/DESIGN-RATIONALE.md)
Lock #6.

## Tight token budget per response

Each MCP tool response is bounded at **≤ 5,000 tokens** by
default. The cap is normative, not aspirational: a tool that
cannot answer inside the budget MUST trim, summarise, slice,
paginate, or dedupe rather than over-spend.

The motivation is the 2026 trend axis. Microsoft's April 2026
recommendation (Playwright CLI **over** Playwright MCP for
coding agents) was driven by MCP responses being roughly 4×
larger in tokens than the equivalent CLI output. Anthropic's
own router-SKILL guidance lands at the same ~5k ceiling. An
agent host with a 200k context window can absorb a handful of
20k tool returns, but the realistic working session fires
dozens of tool calls — Causa-MCP is the most exposed of the
triplet because its catalogue includes
`get-trace-buffer`, `get-epoch-history`, `app-db-diff`, and
the `subscribe` stream, all of which return payloads whose
size scales with runtime state.

The discipline rests on **six normative mechanisms**, each
catalogued below. Every catalogue entry (when
`003-Tool-Catalogue.md` lands) MUST declare which of the six
apply to that tool, with a **typical-token** hint
(`~1.2k`, `~3k under :sample`) and a **cap-reached** behaviour
note. The hints surface in `list-tools` so the agent can plan
ahead. The cap is enforced at the runtime boundary, not just
documented.

The wording below aligns deliberately with
[`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
§Tight token budget per response so that an agent learning the
slot on one server gets the same slot on the others.

### 1. Token budget cap

The **5,000-token default** is the per-response budget. Every
tool that returns to the agent MUST measure the rendered
payload (post-EDN-encoding, post-JSON-wrap) against the cap
before returning. Each call MAY override via a `:max-tokens`
integer argument (server clamps to `[1, 50000]`).

A tool that would exceed the cap MUST NOT silently truncate.
Instead it MUST return a structured overflow marker at the top
of the payload:

```clojure
{:rf.mcp/overflow {:cap         5000
                   :would-be    ~12400
                   :hint        :switch-mode  ; or :paginate, :slice, :narrow-filter
                   :continuation {:cursor "opaque…" :next-args {...}}}
 …trimmed-payload…}
```

The `:rf.mcp/overflow` key is reserved cross-server (pair2-mcp,
story-mcp, causa-mcp use the same shape) so an agent that
recognises it once recognises it everywhere.

### 2. Path slicing

Tools returning rich nested values (`get-app-db`,
`get-machine-snapshot`, `get-epoch`, `get-causality-graph`)
MUST accept an optional `:path` argument — an EDN-encoded
vector of keys (e.g. `"[:cart :items 3 :sku]"`) addressing a
subtree.

The default behaviour **without** a `:path` argument MUST be a
tree-summary (per mechanism 4), not the full payload. With
`:path`, the tool returns the addressed subtree subject to the
remaining mechanisms (still budgeted, still summarised at the
leaf if rich). Out-of-range paths return
`:ok? false :reason :path-not-found` with the deepest valid
prefix attached so the agent can re-aim. This is the same
slicing convention pair2-mcp's `snapshot` op already uses.

### 3. Cursor pagination

Sequence-returning tools (`get-trace-buffer`,
`get-epoch-history`, `list-subscriptions`,
`get-causality-graph` when walking trace events, and any read
tool whose return size is a function of trace-bus depth) MUST
accept `:cursor` (opaque server-managed string, omitted on the
first call) and `:limit` (integer, default chosen so the
response fits the cap).

Responses MUST carry `:next-cursor` (an opaque string for the
next page, or `nil` when exhausted) and `:remaining` (count or
estimate). Cursors are server-managed — the agent does not
inspect them. The `:since-ms` and `:filter` arguments are NOT
substitutes for pagination; an active app can blow the budget
inside a 5-second window. No unbounded list responses; no
"best-effort" omission of pagination.

### 4. Lazy summary (default mode for rich values)

The **default response mode** for any tool returning a rich
nested value is a **summary**, not the full payload. A summary
declares the shape without committing the budget:

```clojure
{:rf.mcp/summary {:type   :map        ; :map | :vector | :set | :scalar
                  :keys   [:cart :user :ui :…]
                  :counts {:cart 47 :user 3 :ui 12 :…}
                  :bytes  ~12400}}
```

Tools MUST expose a `:mode` argument with at least `:summary`
(default), `:sample` (bounded prefix or stratified sample with
sizes attached), and `:full` (paginated complete payload).
Agents drill down via `:path` (mechanism 2) or `:cursor`
(mechanism 3); `:full` is opt-in for the cases where the agent
genuinely needs everything. `app-db-diff` in particular MUST
default to changed-paths-with-cardinalities, not the nested
diff.

### 5. Structural dedup (trace burst compaction)

The wire format for sequence-returning tools whose items can
repeat structural prefixes (trace bursts where many events
share the same `:event-id` / `:handler-id` / `:source-coord`
backbone) MAY apply **structural dedup** before counting
tokens: shared subtrees are emitted once and referenced by an
integer id; the wire payload carries
`{:rf.mcp/dedup-table {1 {...} 2 {...}} :items [{:rf.mcp/ref 1 …} …]}`.

The dedup algorithm is the
[`day8/de-dupe`](https://github.com/day8/de-dupe) substitution
table — proven on re-frame-10x's epoch payloads, where it
typically compresses trace bursts 3-5× without semantic loss.
The agent reconstructs the full structure with a one-pass walk
substituting refs against the table. Dedup is **opt-out** per
call (`:dedup? false`); on by default for trace-shaped
sequences and off for everything else.

### 6. Size elision (`:rf.size/large-elided` marker)

Mechanisms 1-5 cap the **top-level** response shape; the sixth
mechanism substitutes **per-value** inside any tree-typed
payload. A single large slot — say a 100KB base64 PDF on
`[:user :uploaded-pdf]`, a 5MB cached fetch response on
`[:net :last-payload]` — would otherwise ride the wire
verbatim and either trip the cap (forcing the agent to re-aim)
or, worse, slip through under cap and burn the agent host's
context budget. The framework's size-elision walker
(`re-frame.core/elide-wire-value`, per
[`spec/API.md` §`elide-wire-value`](../../../spec/API.md#elide-wire-value-the-wire-boundary-walker))
substitutes such slots with a canonical marker carrying a
fetch handle.

**Wire shape**. The marker is normative across the MCP triplet
(pair2-mcp, story-mcp, causa-mcp) and is catalogued at
[`spec/Spec-Schemas.md §:rf/elision-marker`](../../../spec/Spec-Schemas.md#rfelision-marker)
and [`spec/009-Instrumentation.md §Size elision in traces`](../../../spec/009-Instrumentation.md#size-elision-in-traces):

```clojure
{:rf.size/large-elided
 {:path   [<segment>...]            ; absolute path inside the slice's root
  :bytes  <int>                     ; pr-str byte count, exact when known
  :type   :map | :vector | :set | :scalar | :string
  :reason :declared | :schema | :runtime-flagged
  :hint   <string-or-nil>           ; copied verbatim from the registry entry
  :digest <"sha256:hex">            ; OPTIONAL; gated on :rf.size/include-digests?
  :handle [:rf.elision/at <path>]}} ; EDN handle passable to get-path
```

The marker is a **substitution at the elided slot**, not a
wrapper around the response. A 1MB app-db with a 100KB `:large?`
slot at `[:user :uploaded-pdf]` returns the small siblings
verbatim and the marker at the elided slot. The `:rf.elision/at`
handle is a normal EDN vector (no reader hook needed); agents
pattern-match on the leading keyword. The marker keyword and
handle-namespace are reserved in
[`spec/Conventions.md §Reserved namespaces`](../../../spec/Conventions.md#reserved-namespaces-framework-owned).

**Composition with `:sensitive?` — sensitive wins**. The walker
operates downstream of the privacy filter (§"Privacy:
default-drop `:sensitive?` events at the MCP boundary" above)
and the predicate cascade is:

```clojure
(cond
  (and sensitive? large?)  ::drop                  ; no marker; sensitive wins
  sensitive?               ::redact-or-drop        ; :rf/redacted sentinel
  large?                   ::elide-with-marker     ; :rf.size/large-elided
  :else                    ::pass-through)
```

A value matching both predicates is dropped/redacted **without**
emitting a marker — the marker itself carries `:path` /
`:bytes` / `:digest` slots that would leak signal an audit
mustn't see. The two axes (drop-and-forget for `:sensitive?`,
elide-with-fetch-handle for `:large?`) compose into a single
predictable cascade. Per
[`spec/009-Instrumentation.md §Composition`](../../../spec/009-Instrumentation.md#size-elision-in-traces).

**When this fires**. Any tool emitting a tree-typed payload —
the canonical Causa-MCP set is `get-app-db`, `app-db-diff`,
`get-epoch`, `get-epoch-history` (per-record `:db-before` /
`:db-after`), `get-machine-snapshot`, `get-causality-graph`,
and `subscribe` payloads carrying trace events with rich
coeffect / effect slots. Each runs the elided slice through
the walker inside the eval form sent over nREPL (the registry
lives in app-db; the Node process can't reach it directly).
The walker is the **single normative emission site** — per-tool
reimplementation is prohibited.

**Where in the pipeline**. Elision runs **first**. The downstream
mechanisms (mechanisms 1-5) operate on the post-elision
payload: the cap measures post-elision bytes, summary /
sample / full modes see markers in place of large values,
dedup pools across already-elided slices. A single declared
`:large?` slot can no longer blow the cap on its own; the cap
stays a backstop, not the primary mechanism for the common case.

**Per-call opt-out**. Every tool whose return walks a tree-typed
payload accepts an `:include-large?` boolean argument (default
`false` — markers ride; large values stay home). Passing
`:include-large? true` bypasses the walker entirely — useful
for the rare workflow where the agent has explicit permission
and budget to fetch the full payload (e.g. debugging a
declared-large slot itself, or a session running with a
purpose-built large-context model). The argument slot is
cross-server identical (`:include-large?` is the same key on
pair2-mcp's `snapshot` / `get-path` tools, on story-mcp, and
on causa-mcp's catalogue when impl lands).

**Per-call digest opt-in**. The `:rf.size/include-digests?`
slot defaults `false`; setting `true` computes a `sha256:<hex>`
content digest per elided value. The digest forces a full walk
of each elided value, which negates the cost-saving — opt in
deliberately for integrity-check workflows (compare digests
across turns to detect change-without-fetch).

**Indicator field**. Tool responses that walk tree-typed
payloads carry an `:elided-large` count alongside the existing
`:dropped-sensitive` count — one per consumer-facing tool. The
two counts surface together so an agent sees both axes
("trimmed 3 sensitive events; elided 2 large values") on the
same envelope.

**Cross-MCP consumer state**. pair2-mcp already consumes the
marker through its `snapshot` and `get-path` tools (rf2-urjnc,
per
[pair2-mcp Principles §"Size-elision wire markers"](../../pair2-mcp/spec/Principles.md#size-elision-wire-markers-rf2-urjnc));
the marker shape is identical on the wire. Causa-MCP's
catalogue (`003-Tool-Catalogue.md`, when it lands) MUST declare
the `:include-large?` slot, the `:elided-large` indicator
field, and the default elision-policy on every tool emitting
tree-typed payloads. Impl alignment is tracked separately —
this section is the spec-side normative pin; the
implementation pass lands against the catalogue once
`tools/causa-mcp/src/` exists.

The six mechanisms together are the load-bearing budget
posture for Causa-MCP's agent-host workflow: keep the per-op
cost predictable, push the agent to ask for what it actually
needs, and never let a single op blow the session. Causa-MCP's
value over a generalist surface (Chrome DevTools MCP's
`evaluate_script`) collapses if the agent can't afford to call
the tools.

The **catalogue-entry contract** (binding on
`003-Tool-Catalogue.md` when it lands): every tool entry MUST
declare which mechanisms apply, the **typical-token** hint, the
**cap-reached** behaviour, and the default `:mode` /
`:limit` / `:dedup?` values. No tool ships without these slots.

### Streaming over batch (cross-cutting)

The `subscribe` stream returns one event per JSON-RPC
`notifications/progress`, not a buffered batch. The cap applies
per notification; the agent host meters consumption. A
`subscribe` topic whose individual events can exceed the cap
(a trace event with a large coeffect payload) relies on
mechanism 6 (size elision) running per-notification — the
walker substitutes the canonical `:rf.size/large-elided` marker
at each elided slot (body shape per
[spec/Spec-Schemas §`:rf/elision-marker`](../../../spec/Spec-Schemas.md#rfelision-marker),
fetch-handle `[:rf.elision/at <path>]`) — and surfaces a
follow-up `get-trace-buffer` cursor when the trimmed
notification still trips the cap. Mechanisms 1, 4, 5, and 6
apply per-notification; mechanisms 2 and 3 are inapplicable
inside a single notification but DO apply to the `subscribe`
call's own arguments (e.g., a `:path` filter on the topic, a
`:limit` on total notifications before auto-unsubscribe).

## Backed by Causa's and the framework's principles

When in doubt, defer to the principles upstream:

- **Causa's [Principles](../../causa/spec/Principles.md)** —
  "Read-only by default, mutate by confirmation" applies to the
  MCP surface too; the mutation tools mirror the in-panel
  right-click affordances, and the user's consent model is the
  same (enabling the MCP server is enabling the mutations).
- **Framework's [Principles](../../../spec/Principles.md)** —
  regularity over cleverness, named things over anonymous
  things, public query surfaces, deterministic execution, no
  core.async (the async return-shape is JavaScript Promises;
  the Node host's native async substrate).

Causa-MCP is a downstream artefact of Causa's design and the
framework's AI-first discipline. The principles above are what
*Causa-MCP adds* on top of both baselines; everything below is
inherited.
