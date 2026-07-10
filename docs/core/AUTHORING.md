# Guide authoring brief

> **Who this is for.** Anyone writing or revising pages under `/docs` — the core guide and the machines, async, resources, routing, and ssr corpora. Readers never land here (not in the site nav). Start learning at [the guide](introduction.md).

This is a **taste brief**, not a process manual. Non-negotiables below; craft notes after that. Prefer judgment over form compliance.

> **If a page can't state its one job, it's two pages.**

## Non-negotiables

1. **One job, one Diátaxis mode.** Each page is *start / tutorial / how-to / explanation / index / reference* all the way down. Opening states the reader's problem in a sentence or two. Tutorials don't catalogue alternatives; how-tos don't re-teach concepts; explanations don't carry task steps; indexes route with judgment (never restate the left nav).

2. **Standalone corpus.** Never link into `spec/`. Repeat what the reader needs, with a CLJS flavour, on the page that owns the topic. Exact shapes → [API reference](../api/README.md); definitions → [glossary](glossary.md). Contributors still *verify* against the spec and implementation; the rule is about reader-facing links.

3. **True snippets.** Readers copy-paste. Every example is complete, idiomatic, and would pass review — never a pedagogical anti-pattern. Prefer adapting from `examples/` with a source comment (`;; cf. examples/...`). Verify every taught API symbol and every `:rf.error/*` / `:rf.warning/*` id against `implementation/` (and `spec/API.md` for authors) before teaching it. Async callbacks carry a frame — bare `dispatch` from a timeout raises `:rf.error/no-frame-context`.

4. **Reader-first order.** Goal → working code → explanation. Never open with internals. Examples and gotchas sit beside the step they serve.

5. **No navigation ceremony.** MkDocs already supplies the left nav and prev/next. No "What's next" / "You can now" footers. Useful cross-links go inline where relevant.

## Voice

One persona: a **friendly senior developer**, simple and direct. Register shifts by page kind; the person doesn't:

| Register | Where |
|---|---|
| Warm teaching | start, concepts, tutorials |
| Tight recipe | how-to, testing |
| Argument | explanation shelf |
| Terse reference | glossaries, `docs/api/` — boring on purpose |

The test: would it sound natural said aloud, and could a tired engineer follow it at 11pm? The reader knows React or Redux; meet them as a colleague.

Owned opinions are fine ("two copies of one truth is two chances to disagree"); invented personal biography is not. Explain a short *why* after instructions. One committed metaphor per concept. Humor is seasoning in teaching registers only — escalate-then-deflate, then straight prose; the sentence must survive the joke's deletion. **Never trade precision for charm.**

Gloss load-bearing terms (`app-db`, frame, coeffect, …) at first use in a few plain words. Don't pack the reader off mid-flow for a basic word.

## Structure of the corpus

Diátaxis with reference first-class: guide pages teach; when a page accumulates option tables and precedence rules, move that weight to the API or glossary.

**Core track (flat under `docs/core/`):** introduction → app-db (complete counter + one-map doctrine) → concept pages in growth order (subscriptions, views, effects, coeffects, run-to-completion, flows, frames, interceptors, errors, images, observability) → how-to → testing → explanation → migration → glossary.

**Canon apps.** Counter for the model pages (no server). RealWorld once server data enters. Domain corpora keep their own anchors (XState / login+websocket; TanStack Query + RealWorld nouns; same nouns for async, routing, SSR). No throwaway narrative apps.

**Nav.** Order pages so each leans only on what came before. Descriptive labels help (`"Frames: isolated worlds"` beats `"Frames"`); numbered sequential tracks help when the track is long. Open each page by placing it in the thread.

**Delta teaching.** Domain pages name the ecosystem anchor (Redux, TanStack Query, XState, re-frame v1, …) and the deliberate divergences. Persona deltas are collapsed `??? info` callouts — short, never load-bearing. A v1 callout is earned only where the v1 instinct *misleads*; most pages need zero. Retired v1 surfaces (`inject-cofx`, `:rf.world/inputs`) appear **only** on the [migration page](25-from-re-frame-v1.md), marked superseded — never as live teaching elsewhere.

## Callouts and takeaways

Use MkDocs admonitions, not bold-lead blockquotes, for asides:

- Gotchas → expanded `!!! warning`
- Notes → expanded `!!! note`
- Experiments beside live cells → `!!! tip "Try it"`
- Persona / v1 deltas and optional depth → collapsed `??? info` / `??? note`

Budget them. Three boxes in a row means the prose is hiding structure. A page that is more box than prose has inverted itself.

Teaching pages benefit from **one** load-bearing takeaway a reader could quote later. Prefer a single `> **…**` pull-quote near the open. That's craft, not a lint rule — better one clear sentence in prose than a forced slogan.

## Reference

**Glossary:** one term, standalone definition first, short code when the spelling matters, *See* line to the teaching page. Defines and routes; never teaches.

**API (`docs/api/`):** every public symbol; fixed fields Kind / Signature / Description / Example. Description is the contract (including error ids), not the motivation.

## Live cells

Use ` ```cljs-rf2 ` when editing teaches more than reading. Keep cells small and self-contained; RealWorld stays static with a link to the example. Prefer: why → cell → a Try-it tip.

Rules that bite: top-level forms only (no wrapping `do`); last form renderable hiccup; seed with `dispatch-sync` or `:initial-events` so the first paint isn't a race; one frame per cell; stay on surfaces already proven in shipped cells, or click a new surface in a browser before merging. Full cell-environment details live with the playground tooling — don't re-encode a second runtime spec here.

## Honesty

Say when *not* to use a feature. Mark deferred surfaces and CLIENT-ONLY paths. No marketing voice. No bead ids in prose. Pages state current truth.

## What CI enforces

On docs PRs: `mkdocs build --strict`, the link/anchor validator (`scripts/check_doc_slugs.py`), and residue scans (retired `inject-cofx` / `:rf.world/inputs`, retired composition vocabulary, retired image keys) in `.github/workflows/docs.yml`.

Everything else is human judgment: feature PRs update the affected guide page (or say why not); click live cells you touched; prefer cited `examples/` sources so the examples compile gate covers them.

## Before you open a PR

- [ ] One job, clear open; no `spec/` links
- [ ] Snippets and error ids checked against the implementation
- [ ] Callouts not a wall of boxes
- [ ] New/changed live cells clicked in a browser
- [ ] Nav row if the page is new; `mkdocs build --strict` and `python scripts/check_doc_slugs.py` green
