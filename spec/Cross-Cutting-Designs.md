# Cross-Cutting Designs

> **Type:** Reference
> A non-normative index of design surfaces that span multiple Specs, multiple tool artefacts, or multiple skills — each with one canonical home that downstream consumers cite. Each entry names the design problem, the canonical home, the consumers, and the resulting shape.

The re-frame2 corpus has five design surfaces where the same shape recurs across artefacts that would otherwise re-derive it locally. Pre-alpha is the time when these patterns are still visible to a single investigator; once the codebase grows, each instance evolves independently and the shared shape is lost.

This doc is an **inventory**, not a redefinition. Every entry below cites an owning artefact (Spec section, Convention, or skill leaf) where the canonical shape lives. If a downstream artefact disagrees with the home, the home wins; this doc is wrong. **Drift rule** mirrors [Ownership.md](Ownership.md): a second normative definition of any surface listed here is a corpus bug; collapse back to the listed home.

> **What this is not.** A redefinition of any cross-cutting surface. Not a substitute for [Ownership.md](Ownership.md), which is the contract-surface → owning-Spec table — that doc lists every contract surface; this one lists only the five recurring *designs* that span multiple artefacts. The two are complementary: Ownership answers "where does X live?"; Cross-Cutting Designs answers "what shared shape do these five things follow?"

## 1. Wire elision

**Design problem.** Some values must not (or should not) appear on the wire between the runtime and a tool consumer: very-large `app-db` paths whose serialisation would drown the agent's response budget; sensitive values (passwords, tokens, PII) whose appearance in a trace event or pair-tool response would leak past the boundary. The framework needs one elision walker, one wire-marker vocabulary, and one composition rule covering both cases — applied uniformly anywhere a value crosses the wire (trace listener payloads, `get-path` returns, `snapshot` slices, schema-validation failure traces).

**Canonical homes.**
- [009-Instrumentation.md §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) — the `rf/elide-wire-value` walker, the `:rf.size/large-elided` marker shape, the per-call `:rf.size/elision-policy` map, the predicate cascade with sensitivity precedence, and the declarative `:rf.size/declare-large` fx.
- [010-Schemas.md §`:large?`](010-Schemas.md) and [010-Schemas.md §`:sensitive?` — privacy in schema-validation error traces (rf2-kj51z)](010-Schemas.md) — schema-driven nominations: per-slot Malli props that feed the unified elision registry at `app-db [:rf/elision :declarations]` / `app-db [:rf/elision :sensitive-declarations]`.
- [009 §The `with-redacted` interceptor](009-Instrumentation.md) — handler-side drop-on-trace surface for sensitive event vector keys and downstream cofx.
- [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — `:rf.size/*` and `:rf.elision/*` reserved-namespace rows.
- [Spec-Schemas.md §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker) — the marker's Malli shape.

**Consumers.**
- [Tool-Pair.md](Tool-Pair.md) — pair-shaped tools consume the walker at the wire boundary; the `:rf.size/large-elided` marker is the sixth of six normative wire-protocol markers catalogued in [`tools/mcp-conformance/wire-vocab/`](../tools/mcp-conformance/wire-vocab/README.md) and pinned in [`tools/causa-mcp/spec/DESIGN-RATIONALE.md` §Lock #10](../tools/causa-mcp/spec/DESIGN-RATIONALE.md) — the four MCP-side response shapes (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/dedup-table`, `:rf.mcp/diff-from`), the `:rf.size/large-elided` per-value elision marker, and the `:rf.elision/at` fetch-handle tag that pairs with it.
- `tools/pair2-mcp/` — applies the walker in `tools.cljs` invoke pipeline; the `elision_test.cljs` suite pins the wire shape.
- `tools/causa/` — on-box trace listener panels default `:rf.size/include-large?` to `false`; the `[● ELIDED N]` indicator surfaces the marker.
- `tools/story/` — variant snapshots and trace scrubbers consume the same walker.
- `implementation/schemas/` — the `frame-sensitive-declarations` / `populate-sensitive-declarations` feeder ships per-slot props through the late-bind hook table into the unified registry.

**The result.** One walker, one marker vocabulary, one composition order (`sensitive? > redacted > large? > pass-through`). A consumer that wants to elide a value of either kind picks `rf/elide-wire-value` and never reinvents the predicate or the marker shape. Production builds elide the walker's wire surface along with the rest of the trace surface; the underlying declaration registry survives.

## 2. Retro protocol

**Design problem.** Multiple skills produce structured critiques of a body of evidence — `re-frame-pair-retro2` retrospects on a `re-frame-pair2` session; `re-frame2-improver` critiques a body of re-frame2 source code; future skills will follow (error-trace retros, schema-violation post-mortems). Each shares the same workflow shape — read evidence, classify against a catalogue, route findings to the layer where the fix lives, offer fixes / draft beads only with opt-in — but each carries a *different domain catalogue*. The protocol must be extracted once so new retro-style skills inherit the discipline without re-deriving it.

**Canonical home.**
- [`skills/shared/retro-protocol.md`](../skills/shared/retro-protocol.md) — the seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules (consuming tool / upstream re-frame2 / the author's code / both), opt-in bead protocol, and output shape (seven slots: `Goal/Scope`, `Observed`, `Causes`, `Improvements`, `Bolder ideas`, `Bead candidates`, `Other possibilities`).

**Consumers.**
- [`skills/re-frame-pair-retro2/`](../skills/re-frame-pair-retro2/) — session-shaped consumer; supplies its own catalogues at `references/analysis-lenses.md` and `references/known-frictions.md`.
- [`skills/re-frame2-improver/`](../skills/re-frame2-improver/) — code-shaped consumer; supplies its own catalogue under `references/`.

**The result.** Two skills (with more anticipated) share one workflow leaf. Adding a third retro-shaped skill is one new SKILL.md plus a domain catalogue — the diagnosis-first cadence, layer-routing rules, evidence discipline, and bead-opt-in conventions all come from the shared leaf for free. Extracted under rf2-dhe9v from the locked decisions originally embedded in `re-frame-pair-retro2/spec/design.md`.

## 3. Token budgets

**Design problem.** MCP tool responses are expensive in the agent's context window — anywhere from 5x to 10x larger in tokens than the equivalent CLI output. A single oversized response burns the budget the agent needs across the whole task. Multiple MCP servers (pair2-mcp, causa-mcp, story-mcp) each face the same set of decisions: where in the stack does the cap live; how is it configured per-call; what shape does an over-budget response take; how do per-tool trim mechanisms (pagination, lazy summary, path slicing, diff encoding, dedup, size elision) compose with the cap.

**Canonical homes.**
- [`tools/pair2-mcp/spec/Principles.md` §Tight token budget per response](../tools/pair2-mcp/spec/Principles.md) — the 5,000-token default, the per-call `max-tokens` override slot, the `{:rf.mcp/overflow ...}` over-budget shape, the egress-centralised enforcement decision, and the eight mechanisms (wire-boundary cap → path slicing → per-tool budget → diff encoding → dedup → size elision → cursor pagination → streaming subscribe byte+event budget) in order.
- [`tools/pair2-mcp/spec/DESIGN-RATIONALE.md` §Lock #7 — Wire-boundary token cap](../tools/pair2-mcp/spec/DESIGN-RATIONALE.md) — the locked decision record (rf2-rvyzy): egress-centralised, pluggable strategy, truncate-with-marker, default 5K, per-tool override, cumulative across multi-content responses.
- [`tools/causa-mcp/spec/004-Wire-Pipeline.md` §Tight token budget per response](../tools/causa-mcp/spec/004-Wire-Pipeline.md) — sibling lock with the same shape; causa-mcp inherits the cap, the `:max-tokens` override, and the overflow marker keyword from pair2-mcp's design rationale.

**Consumers.**
- `tools/pair2-mcp/` — enforces the cap in `tools.cljs` at the `invoke` boundary; ten tools each declare their typical-token hint and cap-reached behaviour in their tool spec.
- `tools/causa-mcp/` (planned impl) — adopts the same cap, the same override slot, the same overflow shape per its Principles lock #1.
- `tools/story-mcp/` — enforces the cap in `tools.cljc` at the `invoke-tool` egress (rf2-zavp5); seventeen tools each declare their typical-token hint and inherit the `:max-tokens` per-call override.

**The result.** Cross-MCP, the token-cap shape is one decision applied uniformly. New MCP tools land against the catalogued cap (5K default), the catalogued override slot (`:max-tokens` per-call, `0` to disable), and the catalogued overflow marker shape (`{:rf.mcp/overflow {:limit :reached :token-count … :cap-tokens … :tool … :hint …}}`). The mechanisms above the cap (path slicing, lazy summary, dedup, pagination) shape the response so the cap rarely trips; the cap stays the backstop.

## 4. Naming (verbs across MCP tools)

**Design problem.** The re-frame2 MCP triplet (pair2-mcp, story-mcp, causa-mcp) exposes ~40 tools today, trending towards ~50. An agent host with two or three servers attached sees the union as one surface. The verb a tool uses is the first signal the agent parses; verb drift across siblings (`snapshot` in pair2 vs `snapshot-identity` in story; `read-` in story vs `get-` in causa) makes that signal lossy and pushes the agent towards trial-and-error rather than pattern-match.

**Canonical home.**
- [`tools/mcp-conformance/NAMING.md`](../tools/mcp-conformance/NAMING.md) — the cross-MCP verb table with semantics and examples per verb (`get-` / `list-` / `read-` / `discover-` / `dispatch` / `eval-cljs` / `restore-` / `reset-` / `register-` / `unregister-` / `run-` / `preview-` / `record-as-` / `subscribe` / `unsubscribe` / `tail-` / bare-name mega-ops); the explicitly-rejected verbs (`fetch-`, `query-`, `find-`, `lookup-`, `update-`, `set-`, `enumerate-`, `call-`, `invoke-`, `stream-`, `observe-`); the catalogued bare-noun and `->edn` exceptions; and a per-server audit table.

**Consumers.**
- `tools/pair2-mcp/` — 10 tools, audited as fully conformant.
- `tools/story-mcp/` — 17 tools, audited; two named deviations (`variant->edn`, `snapshot-identity`) catalogued as accepted exceptions.
- `tools/causa-mcp/` (planned impl) — ~17 tools per `tools/causa/spec/010-MCP-Server.md`, audited against the table as a design check before impl lands.
- [`tools/mcp-conformance/wire-vocab/`](../tools/mcp-conformance/wire-vocab/) — sibling harness that pins the *payload* vocabulary (`:rf.mcp/*` keys). NAMING.md covers the catalogue surface; wire-vocab covers the wire shape.

**The result.** Verb drift is detected at PR review, not at agent runtime. New tools land against an existing verb; novel verbs require a Lock entry in the server's `DESIGN-RATIONALE.md` and a return-trip to NAMING.md to extend the table. Cross-server, the same verb means the same thing — `get-` is single-entity read, `list-` is enumeration, `subscribe` is streaming pair, `dispatch` is the bare event-fire — so an agent that knows one server's grammar reads the others.

## 5. Origin tagging

**Design problem.** When multiple actors dispatch events into a frame — the user's application, a pair tool, a story runner, the REPL, the SSR boot path — every dispatch becomes indistinguishable downstream. Post-mortem trace views need to answer "show me only the dispatches the pair tool issued during this session" and "did this transition come from `:rf/router` or from user code?" The runtime needs one keyword convention, one carrying slot, and one lifting rule so every consumer (10x panel, causa trace filter, pair-tool's own filter, story scrubber) reads it the same way.

**Canonical homes.**
- [002-Frames.md §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging) — the `:origin` opt accepted on the dispatch envelope; open-vocabulary default `:app`; framework-reserved values (`:rf/router`, `:rf/ssr`, etc.); the distinction from `:source` (trigger-kind axis).
- [009-Instrumentation.md §Origin tagging: `:origin`](009-Instrumentation.md) — the trace lift: the runtime promotes the dispatch opt onto every `:event/dispatched` event under `:tags :origin`; example values (`:pair`, `:claude`, `:story`, `:test`); the filter axis it enables.

**Consumers.**
- `tools/pair2-mcp/` — tags every dispatch / eval-cljs / restore-epoch / reset-frame-db with `:origin :pair2-mcp` (per its NAMING.md row).
- `tools/causa-mcp/` (planned impl) — same shape, value `:causa-mcp`.
- `tools/story-mcp/` — does not ship `dispatch` directly, but its `register-variant` / `record-as-variant` writes carry `:origin :story-mcp`.
- `tools/causa/` — trace panel filter axis; one of the catalogued filter facets in `010-MCP-Server.md`.
- Framework boot paths (router, SSR, machine timer) — set `:origin` to a runtime-reserved `:rf/*` value where the post-mortem distinction is useful.

**The result.** One `:origin` keyword, one default (`:app`), one open vocabulary. Every dispatching surface — application, framework, tool — picks a value; every consuming surface filters on `(get-in trace-event [:tags :origin])`. Adding a sixth dispatching actor is one keyword choice and zero framework changes. The pair- and story-mcp catalogues both explicitly call out their `:origin` value so post-mortem "who dispatched this?" filters are one-key lookups.

## Process

When a new cross-cutting concern appears — a sixth recurring shape across artefacts, or an extension to one of the five above — file a `meta` bead to extend this inventory. Before designing a new local implementation of a cross-cutting concern, consult this file: if the design exists, cite the home; if it doesn't, the bead writes both the home and the inventory entry.

## Cross-references

- [Ownership.md](Ownership.md) — contract-surface → owning-Spec table (every contract surface).
- [Conventions.md](Conventions.md) — locked runtime conventions (reserved namespaces, fx-ids, app-db keys).
- [Cross-Spec-Interactions.md](Cross-Spec-Interactions.md) — edge cases at boundaries between Specs.
- Source bead: rf2-i7bvy (META filing); upstream session-13 investigations rf2-vnmt6, rf2-ok47g, rf2-dhe9v, rf2-ll0yq, rf2-fvy7o, rf2-mzf1r.
