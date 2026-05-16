# 004-Wire-Pipeline

The **wire-pipeline** for Causa-MCP: the load-bearing rules that
govern what crosses the MCP stdio channel from the runtime to the
agent host. Privacy filtering, the six-mechanism token-budget
cascade, and the streaming-over-batch cross-cut all live here.

This is the canonical home for the cross-server contracts an agent
"learns once and recognises everywhere" — the same `:sensitive?`
default-drop, the same `:rf.mcp/overflow` marker, the same
`:rf.size/large-elided` marker, the same `:max-tokens` slot. The
wording aligns deliberately with
[`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
§"Tight token budget per response" so that an agent learning the
slot on one server gets the same slot on the others.

[`Principles.md`](./Principles.md) carries the tie-breaker design
principles for Causa-MCP (origin tagging, EDN canonical, closed-set
catalogue, two-namespace split, etc.); the wire-pipeline rules
below were split out into this file (rf2-erimb) because the section
grew large enough to deserve its own scaffold — mirroring the
pair2-mcp-shape capability files. The tool catalogue itself ships
in [`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md).

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
flag (`get-epoch-history`) — MUST apply the default-suppress filter
at the MCP boundary before any data crosses into the agent surface.

The contract, identical to `tools/pair2-mcp/`'s implementation
(rf2-zq0n1) and `tools/story-mcp/`'s `re-frame.story-mcp.sensitive`
helper:

- **Default**: events with `:sensitive? true` at the top level are
  dropped. Dropped count surfaces as `:dropped-sensitive` on the
  result (or on each `notifications/progress` payload for streaming
  tools) when non-zero.
- **Opt-in**: per-call `:include-sensitive? true` MCP arg disables
  the gate. The arg name is fixed cross-server (pair2-mcp + story-mcp
  + causa-mcp) per the cross-server symmetry posture above.
- **Scope**: this default-drop applies to the **trace-stream
  surface only**. Mutating ops (`dispatch`, `restore-epoch`,
  `reset-frame-db`) don't carry `:sensitive?` stamps — they don't
  ride the trace bus. **Direct-read tools** (`get-app-db`,
  `get-app-db-diff`, `get-machine-state`, sub-cache reads, direct
  epoch slices) are a *separate* normative privacy contract — see
  the **direct-read MUST** immediately below.

**Direct-read privacy — normative MUST.** Direct-read tools
(`get-app-db`, `get-app-db-diff`, `get-machine-state`, sub-cache
reads, direct epoch slices) **MUST** route every returned value
through `rf/elide-wire-value` before the value crosses the MCP
stdio egress. Both `:include-sensitive?` and `:include-large?`
**MUST** default `false` off-box; the walker is the mandatory
wire-egress mechanism on the read side, not an opt-in policy.
This is the cross-MCP contract pinned at
[`spec/Tool-Pair.md` §Direct-read privacy posture](../../../spec/Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path)
(L569-573) and tracked by row #19 of
[`findings/MUST-inventory.md`](./findings/MUST-inventory.md);
the walker's wire shape, composition cascade ("sensitive wins"),
and per-call opt-outs are catalogued in §6 ("Size elision")
below.

The two contracts compose but do not substitute: the
trace-stream default-drop above protects the *stream* surface;
the direct-read MUST here protects the *live-value* surface.
Both surfaces share the cross-server `:include-sensitive?` /
`:include-large?` opt-in slots so an agent learns one posture
everywhere.

**What `with-redacted` does and does not displace.** The
`with-redacted` interceptor is the **writing-handler** interceptor
that shapes the trace's `:db-before` / `:db-after` slots — it
redacts the trace stream's projection of `app-db` at write time.
It does **not** scrub a subsequent direct read of live `app-db`,
the live sub-cache, or any tree-typed surface; a direct read
returns the live value untransformed unless the wire-egress
walker scrubs it. The two mechanisms are **defence in depth**:
`with-redacted` keeps sensitive paths out of the *trace*
projection; the `rf/elide-wire-value` walker (the direct-read
MUST above) keeps them out of the *direct-read* wire. Apps that
need fine-grained app-db privacy continue to use `with-redacted`
on writing handlers alongside the walker — neither displaces the
other.

The wiring pattern mirrors
[`tools/pair2-mcp/src/re_frame_pair2_mcp/tools.cljs`](../../pair2-mcp/src/re_frame_pair2_mcp/tools.cljs)'s
`strip-sensitive` helper + `:include-sensitive?` arg + descriptor
slot on each affected tool — per the cross-server symmetry posture
(file header).

The trust boundary is the MCP stdio channel: data that crosses it
reaches the agent host, and from there potentially the LLM provider.
The `:sensitive?` flag is the registrar's "do not ship this
unredacted across that boundary" signal; Causa-MCP honours it.

## Authority classes — named-mutation vs `eval-cljs`

The MCP-server's mutating surface is **two distinct authority
classes**, not one. The split is normative and load-bearing on
the published causa-mcp launch posture.

**Class 1 — named-mutation tools** (`dispatch`, `restore-epoch`,
`reset-frame-db`). These three mirror in-panel right-click
affordances Causa-the-panel already exposes to a human debugger
(per [`Principles.md` §Read-mostly catalogue](./Principles.md#read-mostly-catalogue-mutate-via-the-in-panel-equivalent-gates)).
The authority each surfaces is **bounded** by an existing,
human-visible Causa affordance — `dispatch` fires a registered
event, `restore-epoch` rewinds to a recorded epoch, `reset-frame-db`
re-injects a frame's `app-db`. Every side-effect carries
`:origin :causa-mcp` on the trace bus (per
[`Principles.md` §Origin tagging](./Principles.md#origin-tagging-is-the-convention-not-a-suggestion)),
so the audit trail is complete by construction.

Consent model: **enabling the MCP server is consent to invoke
these three tools.** No per-call gate; no extra approval prompt.
The pre-alpha posture is *low-friction default-safe* — a developer
who chose to launch causa-mcp is choosing to give the agent the
same surface they already give themselves through the panel.
Asking for confirmation again at every dispatch would degrade
the workflow without strengthening the trust boundary.

**Class 2 — `eval-cljs`** (the escape valve). Qualitatively
different: `eval-cljs` executes arbitrary ClojureScript in the
browser runtime, exceeding the bounded affordance set the named
tools mirror. The runtime has full app-db reach, full registrar
reach, and full reach into anything reachable from the eval
context. The synchronous-extent `:origin :causa-mcp` tagging
posture (per
[`Principles.md` §Boundary semantics of `eval-cljs` origin tagging](./Principles.md#boundary-semantics-of-eval-cljs-origin-tagging))
covers the *audit* axis but not the *authority* axis — the agent
can do things no panel right-click can do.

Consent model: **`eval-cljs` MUST be disabled by default in the
published causa-mcp**, and **MUST** require an explicit launch-time
opt-in flag (`--allow-eval` or equivalent) to enable. The flag is
processed at server boot — when not present, `eval-cljs` is
omitted from `tools/list`, and any `tools/call` against it returns
the structured error `{:ok? false :reason :eval-cljs-disabled}`
with a setup hint pointing at the launch flag. When the flag is
present, `eval-cljs` ships in the catalogue unchanged (origin tag,
synchronous-extent binding, etc.).

Rationale: a developer who *wants* the escape valve says so once
at server launch and pays the friction cost there; a developer
who doesn't want arbitrary-eval (the **majority case**: most
sessions only need the read-mostly catalogue and the three named
mutations) gets the reduced authority surface by default without
having to know what to opt out of. Default-safe, opt-in to
heightened authority.

The two classes share the trust boundary (the MCP stdio channel,
above) but split the per-tool authority posture. Published
causa-mcp ships seventeen tools enabled by default
(read-mostly + three named mutations + streaming + meta);
`eval-cljs` is the eighteenth, gated.

**What this is *not*.** This is not a multi-tier approval gate
on the three named-mutation tools. The pre-alpha posture
deliberately refuses per-call confirmation prompts for them —
that path leads to UI-friction-driven workflow degradation
(every `dispatch` becomes a yes/no modal) without raising the
trust-boundary bar. The named-mutation tools are bounded by
their panel-mirror property; arbitrary code execution is not,
and only the second class needs the extra gate.

**Cross-MCP applicability.** The same split applies to
[`tools/pair2-mcp/`](../../pair2-mcp/): pair2-mcp's named
mutations (`dispatch`, `snapshot`'s restore variants) need no
extra gate; pair2-mcp's `eval-cljs` (when its catalogue lands the
eval surface) inherits the same `--allow-eval` posture.
[`tools/story-mcp/`](../../story-mcp/) currently exposes no eval
surface; if a future catalogue addition lands one, it picks up
the same default-off posture. The launch-flag spelling is a
cross-server convention.

## Tight token budget per response

Each MCP tool response is bounded at **≤ 5,000 tokens** by
default. The cap is normative, not aspirational: a tool that
cannot answer inside the budget MUST trim, summarise, slice,
paginate, or dedupe rather than over-spend.

The cross-server contract — default cap, override slot name
(`max-tokens`), overflow marker key (`:rf.mcp/overflow`),
agent-host retry contract, and chained-budget rules when an agent
attaches the triplet in one session — lives at
[`tools/mcp-conformance/TOKEN-BUDGETS.md`](../../mcp-conformance/TOKEN-BUDGETS.md).
The six mechanisms below are causa-mcp's expansion of that
contract.

The motivation is the 2026 trend axis. Microsoft's April 2026
recommendation (Playwright CLI **over** Playwright MCP for
coding agents) was driven by MCP responses being roughly 4×
larger in tokens than the equivalent CLI output. Anthropic's
own router-SKILL guidance lands at the same ~5k ceiling. An
agent host with a 200k context window can absorb a handful of
20k tool returns, but the realistic working session fires
dozens of tool calls — Causa-MCP is the most exposed of the
triplet because its catalogue includes
`get-trace-buffer`, `get-epoch-history`, `get-app-db-diff`, and
the `subscribe` stream, all of which return payloads whose
size scales with runtime state.

The discipline rests on **six normative mechanisms**, each
catalogued below. Every catalogue entry in
[`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md) MUST declare
which of the six apply to that tool, with a **typical-token** hint
(`~1.2k`, `~3k under :sample`) and a **cap-reached** behaviour
note. The hints surface in `list-tools` so the agent can plan
ahead. The cap is enforced at the runtime boundary, not just
documented.

The wording below aligns deliberately with
[`tools/pair2-mcp/spec/Principles.md`](../../pair2-mcp/spec/Principles.md)
§Tight token budget per response — per the cross-server symmetry
posture (file header).

### 1. Token budget cap

The **5,000-token default** is the per-response budget. Every
tool that returns to the agent MUST measure the rendered
payload (post-EDN-encoding, post-JSON-wrap) against the cap
before returning. Each call MAY override via a `max-tokens`
integer JSON-RPC argument (server clamps to `[500, 50000]`;
the corresponding parsed CLJS keyword is `:max-tokens`).

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
`get-machine-state`, `get-epoch-history`)
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
`get-epoch-history`, `list-subscriptions`, and any read
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
genuinely needs everything. `get-app-db-diff` in particular MUST
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
table — originally proven on re-frame-10x's epoch payloads;
re-applied here to re-frame2's structurally-similar trace and
epoch shapes. The agent
reconstructs the full structure with a one-pass walk
substituting refs against the table. Dedup is **opt-out** per
call (`:dedup? false`); on by default for trace-shaped
sequences and off for everything else.

**Compression factor (measured)**. Pinned by
[`tools/pair2-mcp/test/re_frame_pair2_mcp/dedup_benchmark_test.cljs`](../../pair2-mcp/test/re_frame_pair2_mcp/dedup_benchmark_test.cljs)
(rf2-li2cw). Three regimes, three numbers — the agent budget hint
matches the call-site shape:

| Regime | Reduction | Ratio | When it applies |
|---|---|---|---|
| Raw trace bursts | 28-31% | **~1.4×** | `get-trace-buffer`, `subscribe` events vectors — per-event `:id` / `:time` / per-tag variance dominates the wire bytes; the only shareable subtree is the cascade-level `:rf.trace/trigger-handler` map and `:dispatch-id` backbone. |
| High-share replays | ~90% | **~10×** | A recurring cascade (timer tick, scheduled job, idempotent rerender) emitting structurally-identical event subtrees post lazy-summary projection — the deduper collapses one cache entry per distinct subtree across replays. |
| Epoch slices | 80-90% | **5-10×** | `get-epoch-history` and the `:epochs` slot of `snapshot`-shaped tools — whole `:db-before` reference shared across records. Pinned at 89.5% on the 10-epoch / 256-key shared-`:db-before` corpus by [`tools/pair2-mcp/test/re_frame_pair2_mcp/dedup_test.cljs reduction-ratio-shared-subtrees`](../../pair2-mcp/test/re_frame_pair2_mcp/dedup_test.cljs). |

The earlier "3-5×" range in this section was vibes; the measured
numbers above supersede it. Catalogue entries in
[`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md) MUST cite the
regime-appropriate factor when declaring `:typical-tokens` —
trace-bus-shaped tools use **~1.4×**, recurring-cascade tools
use **~10×**, epoch-slice tools use **5-10×**.

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
the canonical Causa-MCP set is `get-app-db`, `get-app-db-diff`,
`get-epoch-history` (per-record `:db-before` / `:db-after`),
`get-machine-state`, and `subscribe` payloads carrying trace
events with rich coeffect / effect slots. Each runs the elided
slice through the walker inside the eval form sent over nREPL
(the registry lives in app-db; the Node process can't reach it
directly).
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
on causa-mcp's catalogue).

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
catalogue ([`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md))
MUST declare the `:include-large?` slot, the `:elided-large`
indicator field, and the default elision-policy on every tool
emitting tree-typed payloads. Impl alignment lives in
[`tools/causa-mcp/src/`](../src/) — the spec-side normative pin
binds the catalogue and the implementation jointly.

The six mechanisms together are the load-bearing budget
posture for Causa-MCP's agent-host workflow: keep the per-op
cost predictable, push the agent to ask for what it actually
needs, and never let a single op blow the session. Causa-MCP's
value over a generalist surface (Chrome DevTools MCP's
`evaluate_script`) collapses if the agent can't afford to call
the tools.

The **catalogue-entry contract** (binding on
[`004-Tools-Catalogue.md`](./004-Tools-Catalogue.md)): every tool
entry MUST declare which mechanisms apply, the **typical-token**
hint, the **cap-reached** behaviour, and the default `:mode` /
`:limit` / `:dedup?` values. No tool ships without these slots.

## Streaming over batch (cross-cut)

The `subscribe` stream emits **one drain-batch per JSON-RPC
`notifications/progress`** — port the pair2-mcp/subscribe shape
verbatim (per
[`tools/pair2-mcp/spec/003-Tool-Catalogue.md` §subscribe](../../pair2-mcp/spec/003-Tool-Catalogue.md#subscribe)).
The single MCP wire-batching idiom across the tool suite
(`tools/pair2-mcp/` + `tools/causa-mcp/`) was decided in rf2-h95hl:
**per-drain-batch**, not per-event. The server polls the runtime's
drain at `poll-ms` cadence (default `100`) and emits a progress
notification per non-empty batch; each batch carries an `:events`
vector and the dedup table for that tick. Rationale: sub-100ms
drain windows are imperceptible in devtools UI, the per-tick wire
cost is bounded by the runtime-side queue caps
(`max-buffered-events` / `max-buffered-bytes`), and per-event
notifications under high trace volume (animation loops, rapid
scroll) would be markedly more expensive without observable
benefit. The cap applies per notification; the agent host meters
consumption.

A `subscribe` topic whose individual events can exceed the cap
(a trace event with a large coeffect payload) relies on
mechanism 6 (size elision) running per-notification — the
walker substitutes the canonical `:rf.size/large-elided` marker
at each elided slot (body shape per
[spec/Spec-Schemas §`:rf/elision-marker`](../../../spec/Spec-Schemas.md#rfelision-marker),
fetch-handle `[:rf.elision/at <path>]`) — and surfaces a
follow-up `get-trace-buffer` cursor when the trimmed
notification still trips the cap. Mechanisms 1, 4, 5, and 6
apply per-notification (the dedup table is per-tick — no
cross-tick refs, matching pair2-mcp's `dedup` posture);
mechanisms 2 and 3 are inapplicable inside a single
notification but DO apply to the `subscribe` call's own
arguments (e.g., a `:path` filter on the topic, a `:limit` on
total notifications before auto-unsubscribe).
