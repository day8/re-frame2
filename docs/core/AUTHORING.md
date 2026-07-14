# Guide authoring brief

> **Who this is for.** Anyone writing or revising pages under `/docs` — the core
> guide and the machines, async, resources, routing, and ssr corpora. Readers never
> land here (not in the site nav). Start learning at [the guide](introduction.md).

This is a **taste brief**, not a process manual. Non-negotiables first; craft and
pacing after that. Prefer judgment over form compliance — but if you skip a rule
below, know *why*.

> **If a page can't state its one job, it's two pages.**

## Non-negotiables

1. **One job, one Diátaxis mode.** Each page is *start / tutorial / how-to /
   explanation / index / reference* all the way down. Opening states the reader's
   problem in a sentence or two. Tutorials don't catalogue alternatives; how-tos
   don't re-teach concepts; explanations don't carry task steps; indexes route with
   judgment (never restate the left nav).

2. **Standalone corpus.** Never link into `spec/`. Repeat what the reader needs,
   with a CLJS flavour, on the page that owns the topic. Exact shapes →
   [API reference](../api/README.md); definitions → [glossary](glossary.md).
   Contributors still *verify* against the spec and implementation; the rule is
   about reader-facing links.

3. **True snippets.** Readers copy-paste. Every example is complete, idiomatic, and
   would pass review — never a pedagogical anti-pattern presented as the way. Prefer
   adapting from `examples/` with a source comment (`;; cf. examples/...`). Verify
   every taught API symbol and every `:rf.error/*` / `:rf.warning/*` id against
   `implementation/` (and `spec/API.md` for authors) before teaching it. Async
   callbacks carry a frame — bare `dispatch` from a timeout raises
   `:rf.error/no-frame-context`.

4. **Reader-first order.** Goal → working code → explanation. Never open with
   internals. Examples and gotchas sit beside the step they serve.

5. **No navigation ceremony.** The guide is built and published with
   **[MkDocs](https://www.mkdocs.org/)** (Material theme; config in repo-root
   `mkdocs.yml`). Readers already get a left sidebar, section nesting, and
   prev/next from that build — do **not** re-implement navigation on the page.
   No "What's next" / "You can now" footers; no mini-TOCs that restate the left
   nav. Useful cross-links go inline where relevant. A **day-one checklist** (what
   the reader can do *now*) is not ceremony; a list of later chapters is.

## Voice

One persona: a **friendly senior developer**, simple and direct. Register shifts by
page kind; the person doesn't:

| Register | Where |
|---|---|
| Warm teaching | start, concepts, tutorials |
| Tight recipe | how-to, testing |
| Argument | explanation shelf |
| Terse reference | glossaries, `docs/api/` — boring on purpose |

The test: would it sound natural said aloud, and could a tired engineer follow it at
11pm? The reader knows React or Redux; meet them as a colleague.

Owned opinions are fine ("two copies of one truth is two chances to disagree");
invented personal biography is not. Explain a short *why* after instructions. One
committed metaphor per concept. Humor is seasoning in teaching registers only —
escalate-then-deflate, then straight prose; the sentence must survive the joke's
deletion. **Never trade precision for charm.**

Gloss load-bearing terms (`app-db`, frame, coeffect, …) at first use in a few plain
words. Don't pack the reader off mid-flow for a basic word.

## What a concept page must carry

Concept pages (the main track under Core, and *concepts* / model pages in domain
corpora) are incomplete until they have all five:

| Piece | Role |
|---|---|
| **Problem open** | One or two sentences: what pain, what job |
| **Happy path** | Working code first (live cell when it teaches more than reading) |
| **Day-one stop** | Checklist or hard "Going further" break — reader may ship here |
| **Unhappy path** | Short table: symptom → named error/recovery → fix |
| **When *not*** | Situations where another tool is better (or "this is rare") |

Missing "when not" and missing named errors are the two most common ways a page
looks finished and still strands the reader at 11pm.

**Do not** bury the day-one payload under migration notes, full config maps, or
optional forms. Collapse those (`??? note` / `??? info`) or put them after the stop.

## Progressive pacing

Teach like a good tutorial, not like a reference dump with anecdotes.

1. **Happy path first.** One growing example (counter for pure Core; domain noun
   for batteries) that adds exactly one idea per step.
2. **Two speeds on long pages.** Near the open: *"Day one: … Going further: … Open
   a section only when a need appears."* Then a real structural break
   (`## Day-one checklist` / `---` / `## Going further`), not a polite promise.
3. **Unhappy paths as vocabulary tables**, not essays. Prefer
   `:rf.error/no-such-handler` over "things might fail quietly." Recovery belongs
   in the same row as the error.
4. **Depth is earned.** Argument-dependent input-fns, Form-2/3, full frame-config,
   mint policies, interceptor chain algebra — after the stop, or collapsed.
5. **Length is a smell, not a rule.** If day-one is still not visible after a scroll
   of essay, the page is two pages or one page with a missing break.

### Slim model vs catalogue

Guide pages teach. When a page accumulates option tables, precedence rules, or every
keyword of a map, **move that weight to the API** (or glossary). Keep a pointer.
The model page stays quotable; the API stays complete.

Same split for domain corpora: a *concepts* page is the flat grammar; growth pages
each add one capability; operate pages own production failure modes.

## Structure of the corpus

Diátaxis with reference first-class.

**Core track** (nav nested by arc): introduction (the loop) → **pure loop**
(events → app-db → subscriptions → views) → **impurity** (effects, including
run-to-completion teaching; coeffects) → **structure** (frames → flows →
interceptors) → **operations** (errors → observability) → **advanced** (images;
run-to-completion operational detail) → how-to → testing → explanation → migration →
glossary.

### Technique owners (do not invent a second home)

One technique, one concept owner. Recipes and tests *use* the technique; they do
not re-teach it.

| Technique | Owner |
|---|---|
| Event vocabulary, `dispatch` | [Events](events.md) |
| `{:db}`, facts vs conclusions, seed-as-event | [app-db](app-db.md) |
| Named derivation, layers, `=` gate | [Subscriptions](subscriptions.md) |
| Hiccup, `reg-view`, thin views | [Views](views.md) |
| `:fx` grammar, RTC *idea*, `reg-fx` | [Effects](effects.md) |
| Managed HTTP depth / retry / abort | [async](../async/http.md) (not Core) |
| `:rf.cofx/requires`, grades, `reg-cofx` | [Coeffects](coeffects.md) |
| Isolation, carry, ensure vs scope | [Frames](frames.md) |
| `init!`, hot reload, host listeners | [Boot how-to](how-to/boot-and-mount-an-app.md) |
| Materialise for handlers | [Flows](flows.md) |
| Four homes (which tool) | [Where state lives](where-state-lives.md) |
| Cross-cutting chain (rare) | [Interceptors](interceptors.md) |
| Registration sets (rare) | [Images](images.md) |
| Dossiers / recovery verbs | [Errors](errors.md) |
| Trace wire; classification *why* | [Observability](observability.md) |
| Classification *recipe* | [Secrets how-to](how-to/keep-secrets-out-of-traces.md) |
| Machines / resources / routing / SSR | Their battery corpora — pointer only from Core |

Interceptors and images are **structure but rare** — open with when-not; do not
imply every app needs them on day two.

**Domain corpora** (machines, resources, async, routing, ssr): index with **Start
here** (tutorial first when one exists, then model, then growth in dependency
order) → tutorial → model/concepts → growth pages → operate / migrate. Index also
states **prerequisites** and **when not to use this artefact**.

**Canon apps.** Counter for Core model pages (no server). RealWorld once server data
enters. Domain corpora keep their own anchors (XState / login+websocket; TanStack
Query + RealWorld nouns; same nouns for async, routing, SSR). No throwaway narrative
apps invented for one page.

**Nav.** Order pages so each leans only on what came before. Descriptive labels help
(`"Frames: isolated worlds"` beats `"Frames"`). Numbered sequential tracks help when
the track is long. Open each page by placing it in the thread — one clause, not a
mini-TOC of the whole guide.

**Delta teaching.** Domain pages name the ecosystem anchor (Redux, TanStack Query,
XState, re-frame v1, …) and the deliberate divergences. Persona deltas are collapsed
`??? info` callouts — short, never load-bearing. A v1 callout is earned only where
the v1 instinct *misleads*; most pages need zero. Retired v1 surfaces
(`inject-cofx`, `:rf.world/inputs`) appear **only** on the
[migration page](25-from-re-frame-v1.md), marked superseded — never as live teaching
elsewhere.

## Live cells and demo shapes

Use ` ```cljs-rf2 ` when editing teaches more than reading. Keep cells small and
self-contained; RealWorld stays static with a link to the example. Prefer:
why → cell → optional `!!! tip "Try it"`.

**Shapes that stay true:**

| Context | Prefer | Avoid as "the way" |
|---|---|---|
| Pure-loop Core demos | `frame-root` + named initialise event + `reg-view` | Bare `defn` that `subscribe`s under a provider (raises `:rf.error/no-frame-context`) |
| Multi-frame / frames *are* the topic | `make-frame` + `frame-provider`, or two `frame-root`s | Inventing a second boot story earlier than Frames |
| Seed state | Named `reg-event` in `:initial-events` | Implying a `:db` config key exists; leading with `:rf/set-db` before the named-event pattern |
| Anti-patterns | Labeled `;; Don't` / before-after | Working cells that silently teach the anti-pattern |

Rules that bite: top-level forms only (no wrapping `do`); last form renderable
hiccup; seed with `:initial-events` (or `dispatch-sync` where appropriate) so the
first paint isn't a race; one frame per cell unless the lesson *is* multi-frame;
stay on surfaces already proven in shipped cells, or click a new surface in a
browser before merging. Full cell-environment details live with the playground
tooling — don't re-encode a second runtime spec here.

## Callouts and takeaways

Use MkDocs admonitions, not bold-lead blockquotes, for asides:

- Gotchas → expanded `!!! warning`
- Notes → expanded `!!! note`
- Experiments beside live cells → `!!! tip "Try it"`
- Persona / v1 deltas and optional depth → collapsed `??? info` / `??? note`

Budget them. Three boxes in a row means the prose is hiding structure. A page that
is more box than prose has inverted itself.

Teaching pages benefit from **one** load-bearing takeaway a reader could quote
later. Prefer a single `> **…**` pull-quote near the open. That's craft, not a lint
rule — better one clear sentence in prose than a forced slogan.

## Reference

**Glossary:** one term, standalone definition first, short code when the spelling
matters, *See* line to the teaching page. Defines and routes; never teaches.

**API (`docs/api/`):** every public symbol; fixed fields Kind / Signature /
Description / Example. Description is the contract (including error ids), not the
motivation.

## Honesty

Say when *not* to use a feature. Mark deferred surfaces and CLIENT-ONLY paths. No
marketing voice. No bead ids in prose. Pages state current truth.

## What CI enforces

On docs PRs: `mkdocs build --strict`, the link/anchor validator
(`scripts/check_doc_slugs.py`), and residue scans (retired `inject-cofx` /
`:rf.world/inputs`, retired composition vocabulary, retired image keys) in
`.github/workflows/docs.yml`.

Everything else is human judgment: feature PRs update the affected guide page (or
say why not); click live cells you touched; prefer cited `examples/` sources so the
examples compile gate covers them.

## Before you open a PR

- [ ] One job, clear open; no `spec/` links
- [ ] Happy path works (snippets true); anti-patterns labeled, not demoed as success
- [ ] Day-one stop present on concept pages; going-further is actually after it
- [ ] Unhappy-path table (or equivalent) with verified `:rf.error/*` / recovery ids
- [ ] "When not" stated where a feature can be misapplied
- [ ] Callouts not a wall of boxes; catalogue weight pushed to API if the page bloats
- [ ] Pure-loop cells: `frame-root` + `reg-view` + named initialise (unless Frames is the topic)
- [ ] New/changed live cells clicked in a browser
- [ ] Nav row if the page is new; descriptive nav label; `mkdocs build --strict` and `python scripts/check_doc_slugs.py` green
