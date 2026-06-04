# Story-MCP — Tool Registry

> The 20 tools the server exposes, across four categories — Dev (3),
> Docs (10), Testing (4), Write (3). One section per category, one
> paragraph per tool. The wire-shape for each tool (input schema,
> output shape) lives in [`API.md`](API.md); this document is the
> orientation read. The canonical 20-tool name list ships as the
> shared fixture `test/fixtures/tool-names.json` (rf2-36upq TE7); JVM
> and Node test corpora compare against it so the spec text and the
> running registry can't drift independently.

> These tools are the agent entry points over the same variant / plan /
> result / evidence model the human Story UI drives — not a second
> artifact model. See the human-UI / MCP / skill mirroring crosswalk in
> [`../../story/spec/020-Story-UI-Inspector-And-Xray.md`](../../story/spec/020-Story-UI-Inspector-And-Xray.md)
> §1.3, and the egress-redaction parity requirement in
> [`../../story/spec/022-Story-UI-Docs-And-Share.md`](../../story/spec/022-Story-UI-Docs-And-Share.md)
> §4.

Two deferred tool sections (`subscribe` / `unsubscribe` and
`evaluate-cljs`) appear at the end of the Docs category for forward-
visibility only; they are NOT in the shipped 20 and are explicitly
flagged "Status: deferred to a future drop."

## The unified run-result (run/read tools speak ONE result vocabulary)

The Testing run/read tools (`run-variant`, `read-failures`) and the Dev
`preview-variant` tool return the SAME unified run-result the human
Story UI reads (rf2-ba86n.17, spec/017 §Run result) — not a parallel
agent-only result vocabulary. The headline is the top-level `:status` ∈
`#{:pass :fail :cannot-run :error}`; the result also carries the unified
`:assertions` records (each with a derived `:status`), the `:checks`
groups, the `:consumed-selectors` agreement-floor set, and the evidence-
slot projections (`:schema-violations` / `:warnings` / `:effects` /
`:sub-runs` / `:renders` / `:narrative`). `story/run-variant` already
assembles this shape through `re-frame.story.result/run-result`; the MCP
handlers project its slots (scrubbing the value-bearing ones at egress)
rather than re-deriving a verdict.

**Frozen, schema-backed contract (rf2-3nbl5.6).** This unified shape is a
**frozen public contract** — the ONE result language spoken IDENTICALLY
across the CLJS test surface, the Story UI Test mode, and these MCP tools.
A result object crosses the MCP boundary with **NO semantic translation**:
the wire payload is the SAME `:status` / `:assertions` / `:checks` /
`:consumed-selectors` shape the CLJS boundary minted, validated by the
SAME Malli schema (`re-frame.story.result/RunResult`, re-exported as
`story/run-result-schema`). The cross-surface round-trip
(CLJS → Story UI → MCP) is gated by
`re-frame.story-mcp.run-result-roundtrip-test`.

**Clean break (pre-alpha, Mike 2026-05-31).** The pre-unification flat
shape (`:passing?` boolean, the `:lifecycle` *verdict*, a flat
`:assertions` vector with no `:status`) is REMOVED outright — NO compat
alias. `:status` is the one verdict, and only it can express the distinct
`:cannot-run` third outcome (the runner could not even attempt the plan),
which an agent must handle as 'not runnable here', not as a fail.
(`preview-variant` keeps `:lifecycle` as the loader-lifecycle STATE
`:ready` / `:error` — that is not the run verdict.)

The toolset split borrows the Storybook MCP shape (Dev / Docs /
Testing) and adds the gated Write surface for the self-healing loop.
Per
[`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md)
the boundary between Story core and this jar is: Story core exposes
the read primitives; this jar packages them as MCP tools.

## Egress indicator counts (`:dropped-sensitive` / `:elided-large`, rf2-koq5m)

The three tools that walk a tree-typed payload — `preview-variant`,
`run-variant`, `read-failures` — drop `:sensitive? true` assertion
records and replace over-threshold / schema-`:large?` `:app-db` leaves
with the `:rf.size/large-elided` marker at the wire egress. Per
[`spec/Conventions.md` §Cross-MCP indicator-field vocabulary][conv]
(MUST-level) and [Spec 009 §Indicator field on tool responses][s009],
each of those tools MUST carry a scalar summary of how much the egress
filtered:

| Slot | Meaning |
|---|---|
| `:dropped-sensitive` | count of `:sensitive? true` assertion records dropped at egress |
| `:elided-large` | count of `:rf.size/large-elided` markers across the response payload |

Both keys are **unqualified** and ride alongside the tool's own
result slots. Each is **omitted entirely when its count is zero**
(omit-when-zero) — a clean read carries neither. The counts are spliced
by the centralised `egress/with-indicators` helper, which delegates to
the shared `re-frame.mcp-base.envelope/with-indicators`; the
`:elided-large` count is produced by `egress/count-elided` →
`re-frame.mcp-base.elision/count-elided-markers`. story-mcp reuses the
SAME mcp-base primitives the sibling `re-frame2-pair-mcp` wires across
its tree-walking tools, so the slot bytes stay byte-identical across the
pair and the cross-MCP conformance gate
(`tools/mcp-conformance/wire-vocab`) pins the parity. Without the scalar
summary an agent sees the inline `:rf/redacted` sentinels / vanished
assertions but no signal that the payload was filtered, or by how much —
the canonical silent-swallow failure mode this MUST closes.

[conv]: ../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters
[s009]: ../../../spec/009-Instrumentation.md#size-elision-in-traces

## Dev — for agents helping build new stories

Three tools that help an agent get its bearings before generating
new content.

### `get-story-instructions`

Returns the agent-onboarding text: how stories are authored, the
EDN-first constraint, the canonical variant body keys, the seven
canonical assertions, the four-phase lifecycle, the inclusion-tag
vocabulary. Mirrors Storybook MCP's `get-storybook-story-instructions`
in intent.

The text lives inline in the jar (single string, no external
resource read at boot) so the jar is self-contained.

### `preview-variant`

Given `:variant-id` (plus optional `:substrate`, `:active-modes`,
`:cell-overrides`, `:base-url`), runs the canvas pipeline and
returns the post-pipeline state plus a sharable URL.

Wire-egress posture: `:app-db` is routed through
`re-frame.core/elide-wire-value` against the variant frame's
`[:rf/runtime :elision]` registry; declared-sensitive paths land
`:rf/redacted` by default. Pass `:include-sensitive true` to opt
out — BUT the opt-in is honoured only when the server was started
with `--allow-sensitive-reads` (rf2-g9fje); when that gate is
closed (the default), the `:include-sensitive` slot is omitted
from the `tools/list` schema entirely and any caller-supplied
value is silently ignored at egress.

The wire-key shape (`:include-sensitive`, no `?`) satisfies the
Anthropic Messages API regex on tool input-schema property keys:
`^[a-zA-Z0-9_.-]{1,64}$` — the predicate-style trailing `?` is
rejected at the host. The predicate FUNCTION
`args/include-sensitive?` keeps its `?` (the Clojure idiom
belongs on the predicate, not on the data key whose wire form
disallows it).

```clojure
{:status         :pass | :fail | :cannot-run | :error   ; the unified verdict
 :lifecycle      :ready | :error                         ; loader STATE (not the verdict)
 :share-url      "..."
 :app-db         {...}
 :assertions     [...]   ; unified records, each with a derived :status
 :checks         [...]
 :rendered-hiccup [...]
 :snapshot       {...}
 :elapsed-ms     ...
 :effective-args {...}}
```

Differs from `run-variant` in EXTRA slots, not in result vocabulary:
`preview-variant` is the "show me what this would look like" call (it
adds `:share-url` / `:rendered-hiccup` / `:effective-args`); `run-variant`
(in the Testing category) is the "execute and report the verdict" call.
Both speak the same unified run-result — `preview-variant` does NOT ship a
third result dialect (rf2-ba86n.17).

**Annotation (rf2-8h778).** `preview-variant` carries
`:destructiveHint true` — the same annotation as `run-variant`.
Both tools invoke the same `(story/run-variant vk opts)` lifecycle
under the covers; they dispatch events into the variant's frame
and accumulate assertions. The semantic split (`preview-variant`
returns the share URL + rendered view; `run-variant` is the headline
run/verdict call) does not change the side-effect surface, and the
annotation must reflect that side-effect surface (agent hosts
that auto-approve `readOnlyHint true` would otherwise auto-approve
a call that mutates the frame).

### `list-substrates`

Returns the set registered via
`re-frame.story/register-substrate!` (Reagent canonical; UIx / Helix
opt-in per host). JVM-standalone hosts return `[]`.

## Docs — for agents reading the story library

Ten introspection tools — the seven core read primitives
(`list-stories`, `get-story`, `get-variant`, `list-tags`,
`list-modes`, `list-assertions`, `variant->edn`) plus
`list-decorators` (rf2-mqp1u), `get-docs-markdown` (rf2-i0kyy), and
`explain-variant` (rf2-ba86n.17 — the agent mirror of the human Explain
panel).

### `list-stories`

`(story/registrations :story)` enumeration, optionally filtered by tag-set
intersection (`{:tags [...]}`).

### `get-story`

Full story metadata + child variant ids.

### `get-variant`

Full variant body (as EDN, plus the `structuredContent` JSON
projection). The EDN form is the canonical artefact contract from
[`tools/story/spec/001-Authoring.md`](../../story/spec/001-Authoring.md)
§reg-variant.

### `list-tags`

Canonical + project-custom tags split.

### `list-modes`

`(story/registrations :mode)` enumeration.

### `list-decorators` (rf2-mqp1u)

Read-only `(story/registrations :decorator)` enumeration. Each entry carries
`:id`, `:kind`, `:doc` plus the kind-specific pure-data slots —
`:has-wrap?` for `:hiccup` decorators (the closure itself doesn't
transport over MCP); `:init` + `:app-db-patch` for `:frame-setup`;
`:fx-id` + `:response` for `:fx-override`. The read-only peer of the
deferred `register-decorator` write surface — closures don't
transport, so the write side stays out of scope at v1, but the read
side is cheap and lets an agent enumerate the decoration vocabulary
the same way it enumerates tags / modes / assertions. Optional
`:kind` arg narrows to one kind.

### `list-assertions`

The canonical seven `:rf.assert/*` events with arity + semantics
docs.

### `variant->edn`

Canonical EDN form, text-only result for byte-stable round-tripping
(content is text, not JSON, to avoid lossy JSON encoding of EDN).

### Deferred Docs tools (not part of the shipped 20)

The two sections below sketch additions that are NOT in the
shipped registry — they appear here for forward-visibility while the
implementation is pending.

### `subscribe` / `unsubscribe` (rf2-p8u13, deferred)

**Status: deferred to a future drop.** Pair2-MCP has streaming
`subscribe` / `unsubscribe` — long-running `tools/call` that emits
matching events as `notifications/progress` notifications (push
mode). Story-MCP's read tools are all pull-mode today. An agent
watching for "the next time variant X fails an assertion" or "the
next variant registered" has to poll `run-variant` / `list-stories`
repeatedly.

**v2 sketch (not implemented).** Add `subscribe` / `unsubscribe`
mirroring re-frame2-pair-mcp's shape — same wire-protocol slot
(`notifications/progress` correlated by the call's
`progressToken`), same idempotent `unsubscribe`, same
`list-subscriptions` peer for the "what streams are open?"
diagnostic (renamed from `subscription-info` in pair-mcp per
rf2-4y595).

Topics to expose:

- `:next-variant-failure` — fire when any variant assertion fails
  (filter by `:variant-id` to narrow to one).
- `:variant-registered` — fire on `reg-variant` (filter by
  `:story-id-prefix` to scope to one story or a subtree).
- `:mode-changed` — fire when the active mode set changes.
- `:story-reloaded` — fire on hot-reload (boundary aligns with
  `re-frame.story/clear-all!` + re-registration).

Open questions: how Story-side state changes (run-variant
assertions, registrar mutations) surface as observable events
without the runtime's epoch ring (re-frame2-pair-mcp's substrate is rich;
Story-MCP runs on the JVM with no equivalent today), whether the
streaming machinery shares an abstraction with re-frame2-pair-mcp's
`subscribe` (likely yes — extracting the queue + progress-callback
plumbing into a shared `tools/mcp-base/streaming` ns is the
implementation-first step), and what the wire-cap / dedup posture
looks like for these payloads (assertion records and variant
bodies are bounded, so the per-tick cap likely matches re-frame2-pair-mcp's
5,000-token default without further per-tool tuning).

### `evaluate-cljs` (rf2-vilu3, deferred)

**Status: deferred to a future drop.** Pair2-MCP has `eval-cljs`
(arbitrary form, evaluated in the connected CLJS runtime).
Story-MCP doesn't. An agent that needs to peek at a Story-side
slice the curated tool surface doesn't expose has no recourse but
to file an RFE.

**v2 sketch (not implemented).** Add `evaluate-cljs` /
`evaluate-cljs-in-story` MCP tool. Bridges the JVM-side story-mcp
through to a running CLJS Story session over the same nREPL
transport re-frame2-pair-mcp uses today. The same posture:

- Bounded — `max-tokens` cap, no `:tools/list` discoverability of
  the escape hatch in production deploys (gate on
  `:rf.story/expert-mode? true` in `config.cljc`).
- Posture TBD when implemented — re-frame2-pair-mcp's eval-cljs gate
  flipped to default-ON in rf2-a0z0h (the threat-model rationale: a
  default-OFF eval gate did not add a protection separable from
  `--allow-writes`, because eval expresses every write the writes-gate
  blocks). A future story-mcp `evaluate-cljs` should evaluate the same
  trade-off rather than auto-inheriting the older default-OFF stance.
- Tagged — every fired event / fx carries `:origin :story-mcp`
  so the runtime distinguishes agent-driven slices from user-driven
  ones.

Open questions: which Story session does the form attach to (the
implicit "active variant frame"? all frames? caller picks?), how
the JVM-standalone deploy degrades when no CLJS session is reachable
(today: `list-substrates` returns `[]`; `evaluate-cljs` would need
the same posture), and whether the existing re-frame2-pair-mcp `eval-cljs`
satisfies the need when a session co-installs both servers (likely
yes, which is the argument for keeping this deferred until the
single-server need materialises in the wild).

### `get-docs-markdown` (rf2-i0kyy)

GitHub-flavoured Markdown projection of a story's documentation —
the story `:doc` + per-variant `:doc` + args / argtypes / tags /
decorators composed into one paste-ready string. Sibling to
`get-story` (which returns the same content as EDN); the difference
is the egress shape an agent host wants when surfacing docs to a
human collaborator (issue tracker, chat, README excerpt). The
markdown rides both the wire-canonical `:content` text slot and a
structured `:markdown` slot for hosts that distinguish.

### `explain-variant` (rf2-ba86n.17)

The agent mirror of the human Explain panel — the variant-plan
`:explain` projection (spec/017 §Explain API), a thin wrapper over the
shipped `re-frame.story/explain` data API. The Explain panel was the
single biggest agent↔human divergence: humans had it, agents had no MCP
reach to it. Returns `{:variant-id <kw> :explain <map>}` where `:explain`
answers "why did the plan resolve this way": the `:extends`
`:source-chain` / `:parent-chain`, resolved `:compose` fragments/checks,
`:strict-conflicts` (winning + losing sources + the deciding rule), the
per-field `:merge` rules, `:args` / `:substitutions` / `:effective-args`,
view-arg schema + validation, `:network` route stubs + their lowered fx,
`:sub-overrides` + fidelity, the final `:setup-order` / `:script-order`,
`:checks` / `:assertions`, `:required-runner`, `:platforms`, `:tags`.

Pure plan-derived data — no run, no live frame state, so no egress scrub
(it carries no `:app-db` slice). The `:extends`-resolved variant body is
already public via `get-variant` / `variant->edn`; this adds the plan
compiler's source/merge/lowering reasoning on top. `:readOnlyHint true`.

## Testing — for agents running stories headlessly

Four execution tools.

### `run-variant`

Full lifecycle invocation; returns the unified run-result (see
§The unified run-result):

```clojure
{:status             :pass | :fail | :cannot-run | :error  ; the verdict
 :frame              <variant-id>
 :assertions         [...]   ; unified records, each with a derived :status
 :checks             [...]
 :consumed-selectors #{...}
 :schema-violations  [...]   ; evidence-slot projections (.4)
 :warnings           [...]
 :effects            [...]
 :sub-runs           [...]
 :renders            [...]
 :narrative          {...}
 :app-db             {...}
 :rendered-hiccup    [...]
 :snapshot           {...}
 :elapsed-ms         ...}
```

Inputs: `{:variant-id ... :substrate? ... :active-modes? ... :cell-overrides? ... :timeout-ms?}`.

The top-level `:status` is the headline "did this pass?" answer
(rf2-ba86n.17 clean break — the retired `:passing?` boolean is gone).
`:cannot-run` is the distinct third verdict the old boolean could not
express — the runner could not even attempt the plan; an agent handles it
as "not runnable here", NOT as a fail. `:cannot-run` refusals are surfaced
on a `:cannot-run` slot when present.

`:timeout-ms` is clamped DOWN to 30 s (rf2-g9fje); the MCP server's
stdio loop is single-threaded so an unbounded `:timeout-ms` would
park unrelated calls. 30 s matches the `:rf.http/timeout-ms`
baseline (rf2-it1cd) — one ceiling, one number an agent learns.

### `snapshot-identity`

Content hash of `(variant × args × decorators × loaders × substrate ×
modes)`. The agent uses this to skip cells unchanged since a
previous run, or to key downstream pixel-diff services.

### `run-a11y`

axe-core results (delegates to
`re-frame.story.ui.a11y/violations-by-frame`, the panel data from
Stage 6). JVM-standalone hosts return an empty list + a documented
hint that axe-core requires the in-browser panel.

### `read-failures`

Diagnostic for the variant's accumulated `:rf.story/assertions`
accumulator (no re-run). Useful for agents that want to inspect the
last-run state without paying the cost of a fresh `run-variant`.
Returns `{:variant-id <kw> :status <verdict> :total <int> :failures
[record …] :assertions [record …]}`.

Rides the SAME unified assertion records `run-variant` emits
(rf2-ba86n.17): each record is normalized to carry a derived `:status`,
`:status` is the aggregate verdict over the records, and `:failures` is
filtered to the genuine failure statuses (`:fail` / `:error`) — a
`:cannot-run` record is not a failure (the runner proved nothing) and
surfaces via the run-level `:status`. The retired `:passing?` boolean is
gone. This is a re-READ of the accumulator, not a re-run: it has no epoch
tape, so `:status` is the assertion-record aggregate only (it cannot apply
the agreement floor or the runner-refusal fold a fresh `run-variant`
does — re-run for the full run verdict).

Assertion records carrying `:sensitive? true` are dropped at egress
by default; `:include-sensitive true` opts back in subject to the
same `--allow-sensitive-reads` boot gate as `preview-variant` /
`run-variant`.

## Sensitive-read boot gate (`--allow-sensitive-reads`, rf2-g9fje)

The three tools that surface live frame state (`preview-variant`,
`run-variant`, `read-failures`) all accept a per-call
`:include-sensitive` boolean to opt out of the default redaction
posture (see [`tools/Tool-Pair.md`](../../../spec/Tool-Pair.md)
§Direct-read privacy posture). Per the rf2-uaymx (b) decision that
opt-in is itself gated by a server-side boot flag — the default-OFF
posture is the cross-MCP convention for privacy gates (raw reads can
pour the entire app-db into a wire log without the operator ever
typing the secret). (Cross-MCP note: re-frame2-pair-mcp's eval-cljs
gate took the opposite path in rf2-a0z0h — default ON with `--no-eval`
as the opt-out — because eval is the REPL primitive of a pair-debug
session and a default-OFF eval gate did not add a protection separable
from `--allow-writes`. Sensitive-reads here are NOT in that position.)

The wire-key shape (`:include-sensitive`, no `?`) is the form the
host accepts: the Anthropic Messages API enforces
`^[a-zA-Z0-9_.-]{1,64}$` on tool input-schema property keys, which
rejects the trailing `?` of the Clojure predicate-style boolean
convention. The predicate function `args/include-sensitive?`
retains the `?` — the idiom belongs on the predicate, not on the
data key.

| Path | Mechanism |
|---|---|
| CLI flag | `--allow-sensitive-reads` |
| JVM sysprop | `-Drf.story-mcp.allow-sensitive-reads=true` |
| Env var | `RF_STORY_MCP_ALLOW_SENSITIVE_READS=true` |

Closed by default. When closed:

- `tools/list` omits the `:include-sensitive` slot from the input
  schemas of the three affected tools — agents never see an opt-in
  they couldn't exercise.
- The wire-egress scrubbers silently ignore any caller-supplied
  `:include-sensitive true` — declared-sensitive `:app-db` paths
  remain `:rf/redacted`; assertion records stamped `:sensitive?
  true` remain dropped.
- The server logs one line at boot:
  `Sensitive reads: gated (default; pass --allow-sensitive-reads to opt in)`.

When open, the per-call `:include-sensitive true` is honoured as
documented — raw values cross the wire, the operator has signed
off on the egress posture by passing the flag.

## Write — v1.1, dev-only, gated

Three write tools. `register-variant` and `unregister-variant` are
both gated behind `re-frame.story-mcp.config/allow-writes?` per
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md);
`record-as-variant` is ungated for the recording path and gated only
when `:write-back` is set.

### `register-variant`

Invokes `re-frame.story/reg-variant*` (the public programmatic
helper). Input `:body` may be a map (preferred) or an EDN-encoded
string.

### `unregister-variant`

Invokes `re-frame.story/unregister! :variant <id>`. Symmetric to
`register-variant`. Same gate.

### `record-as-variant`

Bridges the recorder primitives (`start-recording!` → `stop-recording!`
→ `gen-play-snippet`) across the MCP boundary. The agent calls the tool
naming an existing variant id; the server starts a recording against
that variant's frame, blocks for `:duration-ms` while the agent (or
human-in-canvas) drives dispatches, stops the recording, and returns
the `(reg-variant ...)` snippet `gen-play-snippet` emits.

Filter layers are inherited verbatim from
`re-frame.story.recorder/recordable-event?` — op-type `:event/dispatched`,
frame scope match against the recording target, and an internal-namespace
skip (`:rf.assert/*`, `:rf.story/*`, `:re-frame.story.*`). The tool
does not expose a free-form filter knob; the recorder owns that
contract per
[`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md)
§Test Codegen.

Optional `:write-back` re-registers the source variant with the
captured recording translated to a live play body via
`reg-variant*` (preserving the existing `:component`, `:args`,
`:decorators`, etc.). The translation routes through
`re-frame.story/recording->play-script` — each captured event becomes
a `[:dispatch ...]` step under `:play-script {:script [...]}`. The
emitted `:play-script` is the transitional spelling the registrar
lowers; `:script` is the public phase-4 name (spec/017 §Public
vocabulary). The legacy `:play` slot was removed (rf2-0wrud) and no
runner executes it. This branch is gated behind the same
`allow-writes?` flag as `register-variant`; the read-only path
(snippet only) needs no gate.

Wire-key shape (rf2-pmwgn): the input-schema property key is
`:write-back` (no `?`) — the same Anthropic `^[a-zA-Z0-9_.-]{1,64}$`
constraint that mandates `:include-sensitive` (no `?`) applies here.
The response-payload key `:written-back?` retains the `?`: response
keys are NOT bound by the input-schema regex. The Clojure-idiomatic
`?` belongs on predicates and on response data, not on input-schema
property keys whose wire form disallows it.

`:new-variant-id` lets the write-back land under a different id (the
default is to overwrite the source). `:extends` defaults to the source
variant so the emitted snippet re-uses its `:component` / `:args` /
`:decorators` rather than duplicating them.

The agent's self-healing loop (write story → run → read failures →
fix) activates with the write surface; without it the loop is
read-only.

## What's NOT in the registry

Each of these is a deliberate omission:

- **No `register-story`** at v1.1. The agent registers a story by
  inference: it calls `register-variant` against a variant id whose
  `:story.<path>` parent doesn't yet exist; Story's reg-variant
  helper raises if the parent isn't there. The agent then *also*
  needs to land the parent story — which it does by registering its
  variants under the parent, in order, with `:doc` etc. attached to
  the first one. (When v1.1 ships and the loop matures, a
  `register-story` tool may follow.)
- **No `register-decorator`** at v1.1. Decorators carry closures
  (`:wrap` slot) which JSON-RPC can't transport. A future shape
  would invoke a registered re-frame.story.* helper by id.
- **No `register-tag`** / `register-mode`. Same reasoning: the
  registration ceremony is small enough that the dev landing them
  inline is fine. The agent's value-add is variant generation, not
  taxonomy.

## Cross-references

- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) — how each tool is
  invoked over the wire.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  how the Write category gates.
- [`API.md`](API.md) — per-tool input / output schemas.
- [`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md) §Test Codegen — the recorder primitives `record-as-variant` wraps.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the read/write primitives.
