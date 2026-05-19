# Cross-MCP token-budget posture

Source: rf2-ll0yq.

The re-frame2 MCP pair — `tools/re-frame2-pair-mcp/` and
`tools/story-mcp/` — shares a normative **token-budget posture**: a
5,000-token-per-response cap, a single per-call override slot
(`max-tokens`), and a single cross-server overflow marker
(`:rf.mcp/overflow`). An agent host that attaches both sees one
disciplined surface, not two dialects of "I'm too big".

(Historical: a third server `causa-mcp` was envisaged in this contract;
it was dropped per rf2-hvl1g. AI agent access to Causa state already
flows via `re-frame2-pair-mcp` against the framework-published Causa
runtime API, so a dedicated causa-mcp is unnecessary.)

This doc is the canonical pin. Each server's `Principles.md` carries
the per-server expansion (mechanism inventory, pipeline order,
implementation notes); this doc carries the **cross-server contract**
— what is shared verbatim, where deliberate divergence lives today,
and how the budget interacts when an agent chains multiple servers in
one session.

The peer cross-server docs are:

- [`NAMING.md`](./NAMING.md) — verb vocabulary (catalogue surface).
- [`wire-vocab/README.md`](./wire-vocab/README.md) — the `:rf.mcp/*` /
  `:rf.size/*` keyword namespaces (wire payload surface).
- **This doc** — the budget posture (response-size discipline).

## Why a cap exists

Microsoft's April 2026 recommendation — **Playwright CLI over
Playwright MCP for coding agents** — was driven by MCP responses being
~4× larger in tokens than the equivalent CLI output. Anthropic's own
router-SKILL guidance lands at the same ~5k ceiling. An agent host
with a 200k-token context window absorbs a handful of 20k tool returns
fine, but the realistic working session fires dozens of tool calls. A
single oversized response burns the budget the agent needs for the
next ten ops.

The pair's exposed surfaces are exactly the SOTA-flagged shapes:

| Server | Exposed surfaces | Why exposed |
|---|---|---|
| `re-frame2-pair-mcp` | `snapshot`, `subscribe`, `watch-epochs`, `trace-window` | Mega-op spans 5 registries; subscribe batches scale with traffic; epochs carry full app-db pairs. |
| `story-mcp` | `run-variant`, `list-stories` on populous libraries | Variant output carries assertions + rendered hiccup + snapshot; list-* size is a function of registry size. |

Cap-first design pushes each server to **bound its egress by
construction** (path-slicing, lazy summary, cursor pagination,
diff-encoding, structural dedup, size elision) rather than relying on
the cap as a backstop. The cap is the safety net; the per-tool
mechanisms are the load-bearing budget posture.

## The shared contract

These slots are **identical across both servers**. An agent that
learns them once recognises them everywhere.

### Default cap

**5,000 tokens per response.** Every tool that returns to the agent
MUST measure its rendered payload against this cap before egress.
"Token" here is the Anthropic rule-of-thumb estimate: `(quot (count
serialised-text) 4)` — a cheap character→token approximation, not a
precise per-token meter. The goal is a bounded wire payload, not
exact metering.

The cap applies to the sum of every `:text` slot in the assembled MCP
`{:content [{:type "text" :text ...} ...]}` result. Multi-part
responses share one cumulative budget rather than per-key.

### Per-call override slot

**Argument name**: `max-tokens` (integer).

- Absent or non-numeric ⇒ server defaults to 5,000.
- `0` ⇒ cap disabled. Escape hatch for callers that have already
  paginated, applied filters, or genuinely need the full payload (e.g.
  a debug session inspecting an elided slot itself, or a session
  running with a large-context model).
- Positive integer ⇒ that cap applies for this call.

The slot surfaces in `tools/list` on every tool descriptor so clients
discover it automatically. Story-mcp's spec'd shape uses the same
`:max-tokens` keyword and the same defaults — when its
implementation passes lands the wire shape is identical.

### Overflow marker (cross-server reserved key)

A tool that would exceed the cap MUST NOT silently truncate. The
payload is replaced with a structured marker the agent host
recognises as a retry signal:

```clojure
{:rf.mcp/overflow {... slot vocabulary varies (see "Divergence" below) ...}}
```

The reserved keyword `:rf.mcp/overflow` is pinned in
[`spec/Conventions.md` §Reserved namespaces (framework-owned)](../../spec/Conventions.md)
and gated by the conformance test in
[`wire-vocab/`](./wire-vocab/). Cross-server reserved-keyword
absence ≠ free divergence — every server's overflow shape MUST
validate against the canonical Malli schema pinned in
`wire-vocab/wire_vocab_test.clj`.

**The agent-host contract**: a result whose first key is
`:rf.mcp/overflow` is a structured retry signal, not an error. The
agent narrows args (drop slices, pass a tighter `:path`, tighter
`:filter`, smaller `:limit`) or passes `max-tokens 0` if the full
payload is genuinely needed. `:isError` stays `false` on overflow —
the call succeeded; the response is a budget signal.

### Cap-aware planning slots in tool descriptors

Every catalogue entry (per re-frame2-pair-mcp's
[`003-Tool-Catalogue.md`](../re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)
and story-mcp's
[`002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md))
carries:

- A **typical-token** hint (`~1.2k`, `~3k under :sample`,
  `~0.8k`) — surfaces in `list-tools` so the agent plans before
  calling.
- A **cap-reached** behaviour note — usually the overflow-marker hint
  string the wrapper emits, optionally tool-specific (re-frame2-pair-mcp's
  `snapshot` recommends path-slicing; `subscribe` recommends tighter
  filter; etc.).

The hints are part of the descriptor, not a separate registry — an
agent's `tools/list` walk pulls them automatically.

## Per-server posture today

### re-frame2-pair-mcp — enforced, 8 mechanisms

The cap is **enforced at the wire boundary** in
`tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools.cljs` (`apply-cap` at
the `invoke` boundary, rf2-rvyzy). Per-tool functions emit the same
shapes they always did; the cap is a property of egress, not of each
tool's internals.

The discipline rests on **eight mechanisms**, running in pipeline
order:

1. **Size elision** — `:rf.size/large-elided` substitution at
   declared-large slots, BEFORE everything else (rf2-urjnc).
2. **Diff-encoded `:db-after`** — `:rf.mcp/diff-from` path-headed
   cluster sections in epoch slices (rf2-1wdzp + rf2-qeous; each
   `:section-path`-headed cluster bundles the relevant patches).
3. **Structural dedup** — `:rf.mcp/dedup-table` cross-record
   substitution table (rf2-obpa9).
4. **Path slicing** — `:path` arg defaults to tree-summary on rich
   slices (rf2-tygdv).
5. **Lazy summary** — `:rf.mcp/summary` mode for rich payloads
   (rf2-u2029).
6. **Cursor pagination** — `:cursor` + `:limit` on unbounded
   surfaces.
7. **Streaming budget** — runtime-side OR-combined event-count +
   byte caps on `subscribe` queue (rf2-ho4ve).
8. **Wire-boundary cap** — final 5,000-token check, swap with
   overflow marker if over (rf2-rvyzy).

Per-session response cache (`:rf.mcp/cache-hit`, rf2-3rt1f) layers on
top: byte-identical second reads collapse to a sub-100-byte marker.

Full per-mechanism documentation:
[`tools/re-frame2-pair-mcp/spec/Principles.md` §"Tight token budget per response"](../re-frame2-pair-mcp/spec/Principles.md#tight-token-budget-per-response).

### story-mcp — normative, enforcement pending

The cap is **normative in spec** — every catalogue entry in
[`002-Tool-Registry.md`](../story-mcp/spec/002-Tool-Registry.md) must
respect it — but the runtime wire-boundary check is not yet wired in
`tools/story-mcp/src/`. The exposed surfaces (`run-variant` output,
`list-*` enumerations on populous libraries) currently stay inside
the cap by construction via:

- **Pagination / cursor** on `list-variants`, `list-modes`,
  `list-assertions`, `list-stories`, `list-substrates`, `list-tags`.
- **Summarisation modes** (`:count` / `:sample` / `:full`) on
  `run-variant`, `snapshot-identity`, `variant->edn`, with `:sample`
  as the default for rich payloads.

The self-healing loop (run → read-failures → fix) naturally biases
towards failure-only payloads — `:sample` mode under a failure filter
keeps the typical workflow well inside the cap.

Full posture text:
[`tools/story-mcp/spec/Principles.md` §"Tight token budget per response"](../story-mcp/spec/Principles.md#tight-token-budget-per-response).

When the wire-boundary check lands, it follows re-frame2-pair-mcp's shape
(`max-tokens` arg, `:rf.mcp/overflow` marker) — the keyword namespace
is reserved cross-server.

## Chained budgets — when agents attach multiple servers

The realistic agent host attaches **both servers in one session**
(re-frame2-pair-mcp dispatching events + inspecting trace causality,
story-mcp running variants). The 5,000-token cap is **per response**,
not per session — the agent host's total context budget absorbs the
sum.

The pair's design assumes the agent meters consumption:

- **Each server's responses are bounded.** No server can blow the
  session on its own.
- **No cross-server budget coordination.** Servers don't know about
  each other's outputs; the agent host is the metering authority.
- **Per-server `max-tokens` overrides compose additively.** An agent
  that needs a full re-frame2-pair-mcp `snapshot` payload AND a full
  story-mcp `run-variant` payload passes `max-tokens 0` to both —
  total cost is the sum.

The agent-host workflow Mike's skills assume:

1. **Start with summaries.** Default `:summary` / `:sample` modes
   keep the first read cheap (~1-3k tokens).
2. **Drill down with `:path`.** Subsequent reads target the addressed
   subtree — the cap stays a backstop, not the primary mechanism for
   the common case.
3. **Paginate when summarising won't help.** Trace bursts, epoch
   history, populous list-* surfaces all carry `:cursor` + `:limit`.
4. **Override only when you have to.** `max-tokens 0` is the escape
   hatch; the convention is to narrow args first.

## Divergence — what differs cross-server today

The contract above is shared. These differences exist today and are
deliberate or pending alignment.

### Overflow-marker slot vocabulary

| Server | Slot shape |
|---|---|
| `re-frame2-pair-mcp` (implemented) | `{:rf.mcp/overflow {:limit :reached :token-count <int> :cap-tokens <int> :tool <str> :hint <str>}}` |
| `story-mcp` (spec'd, generic) | `:isError true` with `:reason :budget-exceeded` + hint string (legacy path; converges to `:rf.mcp/overflow` when wire-boundary check lands) |

The **reserved keyword** (`:rf.mcp/overflow`) and the **retry-signal
contract** are identical today; only the slot vocabulary differs.

### Streaming-overflow surface

re-frame2-pair-mcp ships an **upstream-queue budget**
(`:max-buffered-events` + `:max-buffered-bytes`, OR-combined) per
subscription, with `:overflow-reason` reported per drain. story-mcp
doesn't ship streaming.

### Enforcement surface

| Server | Enforcement | Note |
|---|---|---|
| `re-frame2-pair-mcp` | Runtime — `apply-cap` at `invoke` boundary | Live in `tools.cljs`. |
| `story-mcp` | Spec-only — pagination + summary modes are normative but no wire-boundary check yet | Cap-violating tools would currently ship. Pending wiring. |

The shared **default cap (5,000)**, the **shared override slot name
(`max-tokens`)**, and the **shared overflow keyword
(`:rf.mcp/overflow`)** are pinned cross-server even where enforcement
hasn't landed.

## Recommended client-side override convention

Agent hosts and skills that drive the triplet follow one
override-pattern convention:

1. **First call: defaults.** No `max-tokens` arg. The server's default
   (5,000) applies. The agent learns the rough size from the
   typical-token hint in `tools/list`.
2. **Cap tripped: narrow first.** On `:rf.mcp/overflow`, the agent
   narrows args (`:path`, `:filter`, `:limit`, `:mode :sample`)
   before raising the cap.
3. **Cap raised: deliberate.** When the agent genuinely needs the
   full payload, it passes `max-tokens 0` (disable) — not `100000`
   (raise). The disable-form signals "I am taking responsibility for
   the budget" rather than "I am guessing at a bigger number".
4. **Per-server, not per-session.** Each call carries its own
   `max-tokens` slot. Servers don't coordinate; the agent host meters
   consumption across the session.

The skill catalogue
([`skills/re-frame-pair/`](../../skills/re-frame-pair/) and
[`skills/re-frame-story/`](../../skills/re-frame-story/)) encodes this
pattern; agents attaching ad-hoc are encouraged to follow it.

## When this convention changes

The 5,000-token default, the `max-tokens` override slot name, the
`:rf.mcp/overflow` reserved keyword, and the agent-host retry
contract are **stable cross-server pins**. Changing any of them
requires:

1. A Lock entry in the relevant server's `DESIGN-RATIONALE.md`
   recording the question / options / pick / why / date.
2. A return-trip to this doc to revise the cross-server contract.
3. A conformance-test update in [`wire-vocab/`](./wire-vocab/) if the
   change touches the marker shape.
4. Cross-server alignment — a default-cap change in one server
   without the others creates a per-server-budget surprise the agent
   has to special-case.

Don't change silently. Agents pattern-match on the slot shapes the
table above pins; silent divergence forces per-server
special-casing and erodes the "one signal, one meaning" property the
cross-server contract exists to enforce.

## See also

- [`NAMING.md`](./NAMING.md) — cross-MCP verb vocabulary.
- [`wire-vocab/README.md`](./wire-vocab/README.md) — `:rf.mcp/*` /
  `:rf.size/*` keyword conformance.
- [`tools/re-frame2-pair-mcp/spec/Principles.md` §"Tight token budget per response"](../re-frame2-pair-mcp/spec/Principles.md#tight-token-budget-per-response)
  — re-frame2-pair-mcp's eight mechanisms, in pipeline order.
- [`tools/story-mcp/spec/Principles.md` §"Tight token budget per response"](../story-mcp/spec/Principles.md#tight-token-budget-per-response)
  — story-mcp's three-axis discipline.
- [`spec/Conventions.md` §"Reserved namespaces (framework-owned)"](../../spec/Conventions.md)
  — the `:rf.mcp/*` / `:rf.size/*` keyword discipline this contract
  inherits.
