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

## Wire-egress privacy posture (which payload classes scrub vs. cross public)

Every Story-MCP payload crosses the AI/off-box boundary through the MCP
jar's egress. The wire-elision boundary defined in
[`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md)
§Wire-elision boundary is single-sourced here as the per-tool
classification — the threat model
([`spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md))
scopes the marks to the OBSERVED runtime, not authored registration
data, so each payload is one of two classes:

| Class | Tools / slots | Egress |
|---|---|---|
| **Runtime / captured VALUE** (scrubbed by default) | `preview-variant` / `run-variant` / `read-failures` (`:app-db`, `:rendered-hiccup`, `:snapshot`, evidence slots, assertion records); `read-a11y-violations` (`:violations` — axe-core node `:html` is rendered runtime DOM); `explain-variant` (`:effective-args` / `:args` / `:substitutions` / `:network` / `:db-seed` / `:sub-overrides` override values / `:setup-order` + `:script-order` step payloads); `record-as-variant` (`:captured` + `:play-snippet`) | path-based `elide-wire-value` for `:app-db`; value-based `egress/scrub-frame-value` for derived / non-live trees — on BOTH egress axes (EP-0015 peer axes, rf2-9o5ixx): a leaf equal to a declared-`:sensitive?` value becomes `:rf/redacted`, a leaf equal to a declared-`:large` value becomes the `:rf.size/large-elided` marker (sensitive wins where both apply; the derived-slot large markers feed the `:elided-large` count). `:sub-overrides` / `:setup-order` / `:script-order` carry resolved arg VALUES (the SAME `substitute-args` that feeds `:substitutions`); the value-only scrub redacts/elides the embedded values while preserving their public step structure — leaving them raw would be a clean bypass of the `:substitutions` scrub (rf2-q8ebq.1). `--allow-sensitive-reads` + per-call `:include-sensitive` is the one opt-in (covers both axes). |
| **Author-published STATIC metadata** (intentionally public) | `get-story` / `get-variant` / `variant->edn` bodies; `list-stories` / `list-modes` / `list-decorators` / `list-tags` / `list-assertions`; `get-docs-markdown`; `explain`'s plan-STRUCTURE slots (`:source-chain` / `:parent-chain` / `:compose` / `:merge` / `:strict-conflicts` / `:tags` / `:platforms` / …) | none — registration-time authoring prose, not runtime/user state; scrubbing would only degrade the discovery UX without protecting a secret. NOTE: `:setup-order` / `:script-order` are NOT here — their step structure is discovery metadata but `substitute-args` injects resolved arg values into the step payloads, so the post-substitution sequences are value-bearing and scrubbed (above, rf2-q8ebq.1). Registry-wide enumerations (modes/decorators) are not frame-keyed and carry no runtime values; their `:args` / `:app-db-patch` / `:response` slots are the author's own published fixtures. |

The value-bearing tools (`preview-variant` / `run-variant` /
`read-failures` / `explain-variant` / `record-as-variant`) advertise the
`:include-sensitive` opt-in slot in `tools/list` only when the
`--allow-sensitive-reads` gate is open; the docs-discovery tools never
advertise it (they have no value-bearing slot to gate). The shared
`egress/scrub-frame-value` step keeps the live and non-live scrubs
byte-identical — a declared-sensitive value redacts and a declared-large
value elides identically (i.e. neither crosses raw, by default) whether it
reaches the wire via a live derived tree, a plan-resolved arg, or a
captured event.

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
`:cell-overrides`, `:base-url`, `:timeout-ms`), runs the canvas pipeline
and returns the post-pipeline state plus a sharable URL.

`preview-variant` blocks on the SAME `story/run-variant` lifecycle as
`run-variant`, so it accepts the SAME tunable `:timeout-ms` blocking
ceiling (rf2-ovmc5e): default 10 s, hard ceiling 30 s (matches
`:rf.http/timeout-ms` per rf2-it1cd), caller values above the ceiling
clamp DOWN rather than reject. The MCP request loop is single-threaded
so an unbounded blocking deref would park unrelated calls; both
lifecycle tools resolve the slot through the shared
`tools.args/resolve-timeout-ms` helper (advertised via
`schemas/with-timeout-ms`) so their blocking policy cannot drift by
copy-paste.

Wire-egress posture: `:app-db` is routed through
`re-frame.core/elide-wire-value` against the variant frame's
`[:rf.runtime/elision]` runtime-db registry; declared-sensitive paths land
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

**Annotation (rf2-8h778, rf2-e6knrq).** `preview-variant` carries
`:destructiveHint true` **and `:openWorldHint true`** — the same
annotations as `run-variant`. Both tools invoke the same
`(story/run-variant vk opts)` lifecycle under the covers; they dispatch
the variant author's events into the variant's frame and accumulate
assertions. The semantic split (`preview-variant` returns the share URL
+ rendered view; `run-variant` is the headline run/verdict call) does
not change the side-effect surface, and the annotation must reflect that
side-effect surface (agent hosts that auto-approve `readOnlyHint true`
would otherwise auto-approve a call that mutates the frame).

The **open-world** hint (rf2-e6knrq finding 2) is load-bearing: these
two tools run the author's `:setup` / `:script` events through the live
re-frame2 dispatch+fx pipeline. Story's fx-stubbing (`:fx-overrides`,
the `:force-fx-stub` decorator, `:network`) is an **opt-in authoring
surface, not a universal default** — a variant that does not stub a
given effect fires the **real** fx handler, so a run **can** emit HTTP,
analytics, websocket, storage, navigation, or any app-registered effect
into the outside world. Marking these tools closed-world would tell a
host the call is contained on-box and let it under-gate a call that
reaches external systems. **Every other tool stays closed-world**
(`:openWorldHint false`): the read tools, the registry write tools
(`register-variant` / `unregister-variant`), the static docs tools, and
`record-as-variant` (which records an externally-driven canvas and
optionally writes the captured snippet to the on-box registry — it never
runs the variant lifecycle itself). The split is pinned by the JVM
`annotations-on-every-tool` open-world matrix and the
`tools/mcp-conformance` classification ratchet
(`story-classifications.json` `closed-world` list + the open-world
value assertion in `end-to-end-story.cjs`).

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

Canonical + project-custom tags split. The `:canonical` set is the
bounded 12-entry vector — the seven spec/007 inclusion tags
(`:dev :docs :test :screenshot :experimental :internal :agent`) plus
the five rf2-k1k87 `:state/*` magnitude tags
(`:state/empty :state/small :state/medium :state/large :state/special`).

### `list-modes`

`(story/registrations :mode)` enumeration.

### `list-decorators` (rf2-mqp1u)

Read-only `(story/registrations :decorator)` enumeration. Each entry carries
`:id`, `:kind`, `:doc` plus the kind-specific pure-data slots —
`:has-wrap?` for `:hiccup` decorators (the closure itself doesn't
transport over MCP); `:init` + `:app-db-patch` for `:frame-setup`;
`:fx-id` + `:response` for `:fx-override`. There is no decorator
WRITE tool — decorators carry closures (the `:wrap` slot) that JSON-RPC
cannot transport (see §What's NOT in the registry). The read side is
cheap and lets an agent enumerate the decoration vocabulary the same
way it enumerates tags / modes / assertions. Optional `:kind` arg
narrows to one kind.

### `list-assertions`

The canonical eight `:rf.assert/*` events (the seven dispatched plus
the tape-evaluated `:rf.assert/schema-error`) with arity + semantics
docs, returned in the `:canonical` slot. The `:registered` slot carries
the FULL vocabulary the Story plan compiler accepts
(`re-frame.story.assertions/known-assertion-ids`, rf2-4sgak) — the eight
canonical ids PLUS the browser-tier families the canonical doc-vec does
not cover: the DOM family (`:rf.assert/dom-visible|dom-hidden|dom-text`),
the visual / a11y oracles (`:rf.assert/visual-snapshot`,
`:rf.assert/a11y`, `:rf.assert/a11y-structural`), and the reactive-count
assertions (`:rf.assert/caused`, `:rf.assert/no-cascade-rerender`). This
is the SAME set `plan.cljc` validates authored assertion atoms against,
so an agent that discovers ids here can rely on the plan compiler
accepting them (the richer-runner ids refuse with `:cannot-run` under a
headless runner — never a silent pass).

### `variant->edn`

Canonical EDN form in the wire-canonical `:content` text slot (the
byte-stable `pr-str` form, to avoid lossy JSON encoding of EDN) PLUS a
matching `structuredContent` carrying the same body map (rf2-vyacl).
The descriptor declares an `:outputSchema`; the official MCP SDK's
high-level `callTool` rejects an outputSchema-declaring tool that
returns no `structuredContent` (JSON-RPC -32600), so the structured
slot rides alongside. The text slot stays the byte-stable source of
truth for round-tripping.

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

Plan-derived data — no run, no live `:app-db` slice — but the plan
RESOLVES author args into runtime VALUES. The runtime-resolved value
slots (`:effective-args` / `:args` / `:substitutions` / `:network` route
replies / `:db-seed` / `:sub-overrides` override values / `:setup-order` +
`:script-order` step payloads) are value-scrubbed against the variant
frame's frame declarations at egress (rf2-12f2q, rf2-q8ebq.1, rf2-9o5ixx)
via the shared `egress/scrub-explain-values` step — on BOTH egress axes
(EP-0015 peer axes): a leaf equal to a declared-`:sensitive?` value becomes
`:rf/redacted`, a leaf equal to a declared-`:large` value becomes the
`:rf.size/large-elided` marker (sensitive wins where both apply) — the SAME
value-based scrub the live tools apply to their derived trees.
`:sub-overrides` / `:setup-order` / `:script-order` carry resolved arg
values (the SAME `substitute-args` that feeds `:substitutions`), so they
are scrubbed too; the value-only redaction preserves their public step
STRUCTURE — leaving them raw would be a clean bypass of the
`:substitutions` scrub. The remaining plan-STRUCTURE slots
(`:source-chain` / `:parent-chain` / `:compose` / `:merge` /
`:strict-conflicts` / `:tags` /
`:platforms` / …) are author-published discovery metadata and cross
unredacted. The `:extends`-resolved variant body is already public via
`get-variant` / `variant->edn`; this adds the plan compiler's
source/merge/lowering reasoning on top. Pass `:include-sensitive true`
(gated by `--allow-sensitive-reads`) to opt out. `:readOnlyHint true`.

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

### `read-a11y-violations`

READS the axe-core violations the in-browser a11y panel has already
accumulated (delegates to `re-frame.story.ui.a11y/violations-by-frame`,
the panel data from Stage 6) — it does NOT execute axe-core, so it sits
in the `read-` no-recompute family alongside `read-failures`, not the
`run-` execute-and-report family. Calling it neither runs a fresh check
nor proves the variant accessible. JVM-standalone hosts return an empty
list + a documented hint that axe-core requires the in-browser panel.

Wire-egress posture (rf2-q8ebq.2): the `:violations` vec is LIVE RUNTIME
observed state — the rendered DOM of the variant frame, normalised from
axe-core's JS violation objects. Each violation NODE carries `:html` (the
violating element's outerHTML), `:target` (CSS selectors) and
`:failureSummary`; a sensitive value rendered into the DOM (e.g.
`<input value="<token>">`, a `data-*` attribute, a PII text node) lands
verbatim in node `:html`. So `:violations` is value-scrubbed against the
variant frame's frame declarations via the shared
`egress/scrub-frame-value` step — on BOTH egress axes (rf2-9o5ixx): a leaf
equal to a declared-`:sensitive?` value becomes `:rf/redacted`, a leaf
equal to a declared-`:large` value becomes the `:rf.size/large-elided`
marker — the SAME value-based scrub `explain-variant` /
`record-as-variant` and the live tools apply. The
value-only scrub preserves the public finding structure (`:id` /
`:impact` / `:help` / `:target`) while scrubbing the embedded secret.
Fail-closed by default; `:include-sensitive true` (gated by
`--allow-sensitive-reads`) opts out. `read-a11y-violations` is `:readOnlyHint true`
(agent hosts auto-approve it), so an unscrubbed runtime read here would be
the wrong shape.

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

The tools that surface live or plan-resolved frame VALUES
(`preview-variant`, `run-variant`, `read-failures`, `read-a11y-violations`,
`explain-variant`, `record-as-variant`) all accept a per-call
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
  schemas of every affected tool — the six that surface live or
  plan-resolved frame VALUES (`preview-variant`, `run-variant`,
  `read-failures`, `read-a11y-violations`, `explain-variant`, `record-as-variant`),
  i.e. every descriptor that carries the slot. Agents never see an
  opt-in they couldn't exercise.
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

Wire-egress posture (rf2-12f2q): the captured event vectors cross the
AI/off-box boundary in BOTH the `:captured` slot and the `:play-snippet`
text. A recorded event can carry a declared-sensitive value in its
payload (a token, a PII field dispatched into the canvas), so the
captured events are value-redacted against the source variant frame's
declared-`:sensitive?` values via `egress/scrub-frame-value` before
egress — the SAME value-based redaction the live-state tools apply to
their derived trees — and the snippet is rendered FROM the scrubbed
events so the secret is absent from both wire slots. Pass
`:include-sensitive true` (gated by `--allow-sensitive-reads`) to opt
out. The WRITE-BACK path (below) re-registers the RAW events on-box for
replay fidelity — that is an operator-gated registration via
`--allow-writes`, not a wire egress.

**Recordable-coeffect replay fidelity (EP-0017).** Each captured
dispatch carries its flat `:rf.cofx` envelope — the framework-stamped
`:rf/time-ms` plus any provided recordable facts the dispatch supplied
(EP-0017 §3). The recorder reads that envelope off the same
`:rf.event/dispatched` trace event and threads it through both emission
surfaces: the rendered `:play-snippet` and the write-back `:script`
body render the matching dispatch step as `[:dispatch evec {:rf.cofx
…}]` (a 3-element step; the bare 2-element step is emitted only when
nothing was captured — zero ceremony for a no-coeffect recording). On
replay the runner re-supplies the recorded envelope via the dispatch
opts, so a handler that declares `:rf.cofx/requires` re-presents the
recorded value rather than restamping a fresh `:rf/time-ms` or failing
`:rf.error/missing-required-cofx` for a provided fact. The captured
`:rf.cofx` maps are value-bearing and follow the SAME egress posture as
the event payloads: each captured-coeffect leaf is value-redacted
against the source frame's declared-`:sensitive?` values before it
crosses the wire (in the snippet / `:captured` slots), while
`:rf/time-ms` is always safe to surface (EP-0017 §3) and passes through
verbatim. The write-back threads the RAW (unscrubbed) envelope on-box
for full replay fidelity (an operator-gated `--allow-writes`
registration, not a wire egress). No `:rf.world/inputs` naming is
introduced — the retired predecessor spelling does not appear on any
surface.

Optional `:write-back` re-registers the source variant with the
captured recording translated to a live play body via
`reg-variant*` (preserving the existing `:component`, `:args`,
`:decorators`, etc.). The translation routes through
`re-frame.story/recording->script-body` — each captured event becomes
a `[:dispatch ...]` step (carrying its captured `:rf.cofx` envelope as a
trailing opts map where one was recorded) — and the write-back assocs
the result under the PUBLIC `:script` authoring slot (rf2-7mj4z;
spec/017 §Public vocabulary), NOT the transitional `:play-script`
spelling. The two
store identically: `reg-variant*` lowers the public `:script` to the
shipping `:play-script` slot via `schemas/lower-public-vocabulary`, so
`variant->edn` of the stored body reads `:play-script` either way — the
public `:script` is an author-facing intent, the lowered shipping slot
is unchanged. The legacy `:play` slot was removed (rf2-0wrud) and no
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
