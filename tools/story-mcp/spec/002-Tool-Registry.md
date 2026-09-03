# Story-MCP — Tool Registry

> The 19 tools the server exposes, across four categories — Dev (3),
> Docs (10), Testing (4), Write (2). One section per category, one
> paragraph per tool. The wire-shape for each tool (input schema,
> output shape) lives in [`API.md`](API.md); this document is the
> orientation read. The canonical 19-tool name list ships as the
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

## Host execution model

The stdio server calls Story's public API directly in the same JVM. It
does not attach to a browser through nREPL or the Tool-Pair contract.
The registry, frames, and headless runs are therefore those loaded in
the server process. Browser-only surfaces are capability-gated: because
the JVM server cannot read the CLJS substrate registry or a11y-violations
atom, `list-substrates` and `read-a11y-violations` return a
machine-readable capability-unavailable error (`isError true`, `:rf.error
:rf.error/story-mcp-capability-unavailable`) rather than a false-empty
`[]`/`{:violations []}` success. `[]`/`#{}` is reserved for a REACHED
provider that genuinely holds nothing — capability absence is not answered
emptiness (rf2-3fc89f.21). An explicit `:substrate` on
`run-variant` / `preview-variant` / `snapshot-identity` is likewise
validated, not silently dropped: unreachable registry → the same
capability-unavailable error; reached-but-unknown id →
`:rf.error/story-mcp-unknown-substrate`.

### Running a variant needs an installed adapter (rf2-c9t52)

Catalogue reads work on a bare launch. RUNNING one does not: `run-variant`
and `preview-variant` allocate a variant frame, and a frame takes its
state substrate from an installed re-frame adapter. The server
deliberately installs none — per
[`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md)
the substrate choice belongs to the app — so a consuming project that
wants headless runs installs one in the namespace its launch alias
preloads (`(rf/init! plain-atom/adapter)`, the renderer-free choice).

With none installed, both lifecycle tools REFUSE before any lifecycle
work, returning `isError true` with `:rf.error
:rf.error/no-adapter-installed` — core's own id for this condition — plus
`:tool` and a `:recovery` naming the boot. They never return a
`:status`. The refusal exists because the alternative is a
success-shaped NON-RUN: with no substrate the setup dispatches reach
nothing and the script plays nothing, yet the run settles the ordinary
`:status :pass` envelope over `{}` and `[]`, indistinguishable on the
wire from a genuine green.

This does NOT alter Story's rule that an actually-EXECUTED
assertion-free variant is vacuously `:pass` (spec/017 §Run result).
Executed-and-silent and never-executed are different states; only the
first is green. `snapshot-identity` is deliberately unguarded — it hashes
the declared tuple and runs no lifecycle.

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

**Clean break.** The pre-unification flat
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

## Egress indicator counts (`:dropped-sensitive` / `:elided-large`)

Three tools filter a tree-typed payload at the wire egress:
`preview-variant`, `run-variant`, and `read-failures`. `preview-variant`
and `run-variant` do BOTH — they drop `:sensitive? true` records AND
replace over-threshold / schema-`:large?` `:app-db` leaves with the
`:rf.size/large-elided` marker. `read-failures` carries no `:app-db`
(nor a derived tree), so it ONLY drops `:sensitive? true` assertion
records; it never runs per-leaf elision, so its `:elided-large` count is
structurally always 0 and — being omit-when-zero — that slot is always
absent. `read-failures` relies on the wire-boundary overflow cap, not
per-leaf elision, for size bounding. Per
[`spec/Conventions.md` §Cross-MCP indicator-field vocabulary][conv]
(MUST-level) and [Spec 009 §Indicator field on tool responses][s009],
each of these tools MUST carry a scalar summary of how much the egress
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
| **Runtime / captured VALUE** (scrubbed by default) | `preview-variant` / `run-variant` / `read-failures` (`:app-db`, `:rendered-hiccup`, `:snapshot`, evidence slots, assertion records); `read-a11y-violations` (`:violations` — axe-core node `:html` is rendered runtime DOM) | path-based `elide-wire-value` for `:app-db`; the derived / non-live trees project through the SINGLE record-level boundary `re-frame.core/project-egress` — the `:rf.observe/derived-tree` record kind (EP-0025 B4, rf2-ojp8pi) — naming the off-box `:rf.egress/profile`, which resolves to the egress floor and PATH-walks against the variant frame's classification on BOTH egress axes (EP-0015 peer axes, rf2-9o5ixx): a value AT a declared-`:sensitive?` path becomes `:rf/redacted`, a value AT a declared-`:large` path becomes the `:rf.size/large-elided` marker (sensitive wins where both apply; the derived-slot large markers feed the `:elided-large` count). `project-egress` reads the SAME per-frame classification registry the path walker reads — frame- AND EP-0025-commit-plane-effect-sourced declarations (`:effect` / `:flow` / subsystem), unioned at lookup. **EP-0025 FAIL-OPEN:** a value AT a classified path within a slot whose shape mirrors the app-db (a `:db-seed`, an `:effective-args {:token …}` with `[:token]` classified) redacts, but a value RE-KEYED to a non-matching position (a token at a hiccup leaf, a snapshot nested under `:db`, a `:network` reply) ships RAW — value-match was removed; classify the app-db PATH to redact a value before a derived tree re-surfaces it. `--allow-sensitive-reads` + per-call `:include-sensitive` (the `:rf.egress/local-raw` boundary) is the one opt-in (covers both axes). |
| **Author-published STATIC metadata** (intentionally public) | `get-story` / `get-variant` / `variant->edn` bodies; `list-stories` / `list-modes` / `list-decorators` / `list-tags` / `list-assertions`; `get-docs-markdown`; `explain-variant`'s ENTIRE `:explain` map — plan-STRUCTURE (`:source-chain` / `:parent-chain` / `:compose` / `:merge` / `:strict-conflicts` / `:tags` / `:platforms` / …) AND the plan-RESOLVED value slots (`:effective-args` / `:args` / `:substitutions` / `:network` / `:db-seed` / `:sub-overrides` / `:setup-order` / `:script-order`) | none — registration-time authoring prose, not runtime/user state; scrubbing would only degrade the discovery UX without protecting a secret. `explain-variant` is a NO-RUN projection over the registry side-table (rf2-7k5mce, Mike 2026-07-08): even its plan-RESOLVED value slots are static author data resolved from the variant's own registration, so the WHOLE `:explain` map ships raw like `get-variant` / `variant->edn` — the over-redaction of resolved args / setup-order / network stubs to `:rf/redacted` on the common no-run inspection path is retired. Registry-wide enumerations (modes/decorators) are not frame-keyed and carry no runtime values; their `:args` / `:app-db-patch` / `:response` slots are the author's own published fixtures. |

The value-bearing tools (`preview-variant` / `run-variant` /
`read-failures` / `read-a11y-violations`) advertise
the `:include-sensitive` opt-in slot in `tools/list` only when the
`--allow-sensitive-reads` gate is open; the docs-discovery tools —
`explain-variant` included, since it ships author data raw (rf2-7k5mce) —
never advertise it (they have no value-bearing slot to gate). Within the
runtime/captured-VALUE class the egress scrubs are uniform — a
declared-sensitive value redacts and a declared-large value elides
identically (i.e. neither crosses raw, by default) across every derived
tree those four tools return. **Classification marks do NOT reach the
author-metadata class**, and `explain-variant` is the surface where that
matters: its plan-RESOLVED value slots are static author data, so a
`:sensitive?` declaration on a matching app-db path does **not** redact
them — the whole `:explain` map ships raw, and there is no
`:include-sensitive` escape hatch because there is nothing gated to
release (rf2-7k5mce). A secret that must not cross the wire must not be
written into a variant's registration. **Non-live posture:** one surface
(`read-a11y-violations`'s axe
DOM nodes) carries an inherently RE-KEYED runtime payload whose path-scrub is a
no-op even live; it takes the **named, narrow re-keyed-runtime egress
exception** (`egress/scrub-re-keyed-runtime`) — live ⇒ PATH-project; non-live
⇒ raw under the documented carve-out (zero leak-delta, since the live case
already ships these re-keyed copies raw). This is **not** a broad
`:rf.egress/local-raw` profile. The framework `project-egress` boundary stays
fail-closed.

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
`run-variant`, so it carries the SAME host prerequisite — with no
installed re-frame adapter it refuses with
`:rf.error/no-adapter-installed`, per
[§Running a variant needs an installed adapter](#running-a-variant-needs-an-installed-adapter-rf2-c9t52)
— and it accepts the SAME tunable `:timeout-ms` end-to-end
deadline (rf2-ovmc5e): default 10 s, hard ceiling 30 s (matches
`:rf.http/timeout-ms` per rf2-it1cd), caller values above the ceiling
clamp DOWN rather than reject. The MCP request loop is single-threaded
so an unbounded lifecycle call would park unrelated calls; both
lifecycle tools resolve the slot through the shared
`tools.args/resolve-timeout-ms` helper (advertised via
`schemas/with-timeout-ms`) so their blocking policy cannot drift by
copy-paste.

The deadline bounds the SYNCHRONOUS Story work, not just the
post-return dereference (rf2-j538f7.31). On the JVM `story/run-variant`
executes synchronously (a `[:wait ms]` step is an inline `Thread/sleep`)
and returns an already-settled future, so the lifecycle owner runs the
whole invoke-and-settle on a bounded worker future and waits at most
`:timeout-ms`. When the deadline elapses the worker is cancelled — its
`Thread/sleep` interrupts, the scheduled post-wait continuation never
fires, and the stdio loop is freed at the ceiling — and the call returns
the canonical `:status :error` run-result. An over-budget run is
therefore bounded and reports an honest deadline-exceeded outcome, never
a false `:pass`.

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
(`register-variant` / `unregister-variant`), and the static docs
tools. The split is pinned by the JVM
`annotations-on-every-tool` open-world matrix and the
`tools/mcp-conformance` classification ratchet
(`story-classifications.json` `closed-world` list + the open-world
value assertion in `end-to-end-story.cjs`).

### `list-substrates`

Returns the set registered via
`re-frame.story/register-substrate!` (Reagent canonical; UIx
opt-in per host). The JVM stdio server has no bridge to that CLJS
registry, so it returns the machine-readable capability-unavailable
error (`isError true`, `:rf.error
:rf.error/story-mcp-capability-unavailable`) — never an empty list. An
empty `:substrates` vec is reserved for a REACHED registry that
genuinely holds nothing: EMPTY means the registry answered and held
none, UNAVAILABLE means the host could not look, and a reader who takes
the first for the second concludes no substrates are registered when
nothing ever consulted the registry. Read the set from a browser-local
Story host (rf2-3fc89f.21).

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

The canonical ten `:rf.assert/*` declarations (the seven dispatched plus
the three tape-evaluated: `:rf.assert/schema-error` and the causal pair
`:rf.assert/caused` / `:rf.assert/no-cascade-rerender`) with arity +
semantics docs, returned in the `:canonical` slot. The causal pair expose
their `:observed-cause-count` diagnostic and premise semantics —
`:rf.assert/no-cascade-rerender` requires its cause be observed by default
(an unobserved cause → `:cannot-run`, never a vacuous `[0,0]` pass), with
`{:require-cause? false}` the one opt-out (`:require-cause?` is rejected on
`:rf.assert/caused`). The `:registered` slot carries
the FULL vocabulary the Story plan compiler accepts
(`re-frame.story.assertions/known-assertion-ids`, rf2-4sgak) — the ten
canonical ids PLUS the browser-tier families the canonical doc-vec does
not cover: the DOM family (`:rf.assert/dom-visible|dom-hidden|dom-text`)
and the visual / a11y oracles (`:rf.assert/visual-snapshot`,
`:rf.assert/a11y`, `:rf.assert/a11y-structural`). This
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

Plan-derived data — no run, no live `:app-db` slice. AUTHOR DATA: the
WHOLE `:explain` map ships RAW, exactly like `get-variant` / `variant->edn`.
`explain-variant` is a NO-RUN tool —
`re-frame.story/explain` is a pure projection over the registry side-table
and allocates no frame — so every slot it returns, INCLUDING the
plan-RESOLVED value slots (`:effective-args` / `:args` / `:substitutions` /
`:network` route replies / `:db-seed` / `:sub-overrides` override values /
`:setup-order` + `:script-order` step payloads), is static author data
resolved from the variant's own registration, not observed user runtime. The
threat model ([`spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md))
scopes the `:sensitive` / `:large` marks to the OBSERVED runtime, not
authored registration data, so there is nothing runtime-sensitive to redact
and no live user frame to leak. This matches (a) `get-variant` /
`variant->edn`, which ship the SAME author data raw, and (b) the human
Explain panel, which renders every slot raw — and `explain-variant` is the
agent mirror of that panel. Routing the value slots through the fail-closed
live-frame egress boundary over-redacted the tool's most useful output
(resolved args, final setup/script order, network stubs) to `:rf/redacted`
on the common no-run inspection path; that boundary is retired for
`explain-variant`. The `:extends`-resolved variant body is already public via
`get-variant` / `variant->edn`; this adds the plan compiler's
source/merge/lowering reasoning on top. No `:include-sensitive` knob — like
`get-variant`, there is no sensitive value slot to gate. `:readOnlyHint true`.

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

Host prerequisite: with no re-frame adapter installed in the server JVM
this REFUSES up front with `:rf.error/no-adapter-installed` and no
`:status` at all — see
[§Running a variant needs an installed adapter](#running-a-variant-needs-an-installed-adapter-rf2-c9t52).

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

READS the axe-core violations an in-browser a11y panel has already
accumulated when invoked in a runtime that can access
`re-frame.story.ui.a11y/violations-by-frame`. It does NOT execute
axe-core, so it sits
in the `read-` no-recompute family alongside `read-failures`, not the
`run-` execute-and-report family. Calling it neither runs a fresh check
nor proves the variant accessible. The JVM stdio server cannot access the
browser panel, so it returns the machine-readable capability-unavailable
error (`isError true`, `:rf.error
:rf.error/story-mcp-capability-unavailable`) — never an empty list. An
empty `:violations` vec is reserved for a REACHED panel that genuinely
recorded no findings: EMPTY means nothing was observed, UNAVAILABLE means
the host could not look, and a reader who takes the first for the second
concludes a component is accessible when nothing inspected it.

Wire-egress posture (rf2-q8ebq.2): the `:violations` vec is LIVE RUNTIME
observed state — the rendered DOM of the variant frame, normalised from
axe-core's JS violation objects. Each violation NODE carries `:html` (the
violating element's outerHTML), `:target` (CSS selectors) and
`:failureSummary`; a sensitive value rendered into the DOM (e.g.
`<input value="<token>">`, a `data-*` attribute, a PII text node) lands
verbatim in node `:html`. axe DOM nodes are an inherently RE-KEYED runtime
payload class, so `:violations` route through the named
`egress/scrub-re-keyed-runtime` exception (rf2-jwggld). Under a LIVE variant frame
**EP-0025 FAIL-OPEN** holds: a sensitive value rendered into a node `:html`
is a RE-KEYED DOM position the classification path cannot reach, so it ships
RAW — value-match was removed; classify the app-db PATH to redact a value
before it reaches the DOM. Under a NON-LIVE frame (common in the JVM tool
process) the nodes ship raw under the documented carve-out (path-scrub is a
no-op even live, so fail-closing would destroy the tool with zero leak-delta).
The public finding structure (`:id` / `:impact` / `:help` / `:target`) is
always preserved. `:include-sensitive true` (gated by
`--allow-sensitive-reads`) opts out. `read-a11y-violations` is
`:readOnlyHint true` (agent hosts auto-approve it).

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

The tools that surface live observed frame VALUES
(`preview-variant`, `run-variant`, `read-failures`,
`read-a11y-violations`) all accept a per-call
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
  schemas of every affected tool — the four that surface live observed
  frame VALUES (`preview-variant`, `run-variant`,
  `read-failures`, `read-a11y-violations`),
  i.e. every descriptor that carries the slot. (`explain-variant` is NOT
  among them — it ships author data raw, rf2-7k5mce.) Agents never see an
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

Two write tools. `register-variant` and `unregister-variant` are
both gated behind `re-frame.story-mcp.config/allow-writes?` per
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md).

### `register-variant`

Invokes `re-frame.story/reg-variant*` (the public programmatic
helper). Input `:body` may be a map (preferred) or an EDN-encoded
string.

### `unregister-variant`

Invokes `re-frame.story/unregister! :variant <id>`. Symmetric to
`register-variant`. Same gate.

## What's NOT in the registry

Each of these is a deliberate omission:

- **No `record-as-variant`** — RETIRED (rf2-5saz7, 2026-09-01). The
  blocking recorder bridge advertised a successful capture while making
  the actor that must produce the events unreachable: its handler ran
  `start-recording!`, slept the server's ONLY stdio dispatch loop for
  `:duration-ms`, then `stop-recording!` — so no MCP client could drive
  a dispatch during the window, the shipped headless JVM has no
  JVM-to-browser bridge, and the tool could only ever return a green
  EMPTY capture. That violates the tool-boundary invariant (every
  advertised success path must be exercisable through the published
  transport). The recorder primitives stay in `tools/story/` for their
  in-process/browser consumers; interactive canvas recording is
  performed through Pair in the attached CLJS runtime. A `tools/call`
  naming the retired tool takes the server's existing `-32601
  method-not-found` path — no tombstone, no alias. A future headless
  capture surface requires an executable SDK witness with a
  transport-reachable event producer first.
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
- [`tools/story/spec/005-SOTA-Features.md`](../../story/spec/005-SOTA-Features.md) §Test Codegen — the Story recorder primitives (in-process/browser recording; their MCP bridge was retired under rf2-5saz7).
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the read/write primitives.
