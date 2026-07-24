# Guide authoring brief

> **Who this is for.** Anyone writing or revising pages under `/docs` — core,
> machines, async, resources, routing, ssr, and the other guide corpora. Readers
> never land here (not in the site nav). Start learning at
> [the guide](core/introduction.md).

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
   [API reference](api/README.md); definitions → [glossary](core/glossary.md).
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
   nav; no **`## Start here`** (or similar) reading-order lists — MkDocs already
   puts those headings in the page TOC and they only restate the sidebar. Useful
   cross-links go inline where relevant. A short "you can ship with this" recap is
   not ceremony; a list of later chapters is.

## Voice

One persona: a **friendly senior developer** — simple, direct, a little Yegge on
teaching pages. Tight prose, not terse telegram. Register shifts by page kind; the
person doesn't:

| Register | Where |
|---|---|
| Warm teaching | start, concepts, tutorials |
| Tight recipe | how-to, testing |
| Argument | explanation shelf |
| Terse reference | glossaries, `docs/api/` — boring on purpose |

**Why "senior".** Not status — a **vocabulary filter**. Write the words a working
senior would actually say in a PR review, stand-up, or whiteboard session. Domain
terms and ordinary engineering English are in (`app-db`, coeffect, handler, queue,
pure, race). Abstract coinages and consultant/AI-ish intensifiers are out when
they aren't common human usage for that idea. If a tired colleague would raise an
eyebrow at the *word choice* (not the concept), rephrase with the plain mechanism.

The test: say it aloud at 11pm. Would a peer nod, or ask what you meant by the
metaphor? The reader knows React or Redux; meet them as a colleague.

Prefer operational claims ("X does Y / fails with Z") over status language ("this
is the foundational / load-bearing / pivotal …"). Name the thing: boundary, API,
handler, error id — not spine, seam, surface area, heart of the system, unless that
phrase is already a re-frame term of art.

**Yegge seasoning (teaching pages only).** Blunt, peer-to-peer, occasionally savage
about *bad ideas*. Humor earns its keep by making a real failure mode stick — not by
decorating. Shape: true claim → short rephrase with teeth → operational rule. One
jab, then back to business. Keep the attitude; drop digressions and war-story length.
If the sentence is only funny, cut it. A precise claim must survive if you delete the
joke. **Never trade precision for charm.**

Owned opinions are fine ("two copies of one truth is two chances to disagree");
invented personal biography is not. Explain a short *why* after instructions. One
committed metaphor per concept — only if a senior would use it unprompted.

Define key terms (`app-db`, frame, coeffect, …) at first use in a few plain words.
Don't pack the reader off mid-flow for a basic word.

## What a concept page must carry

Concept pages (the main track under Core, and *concepts* / model pages in domain
corpora) are incomplete until they have all five:

| Piece | Role |
|---|---|
| **Problem open** | One or two sentences: what pain, what job |
| **Required path** | Working code first (live cell when it teaches more than reading). This *is* the page — do not label it "basics" or "day one" |
| **Troubleshooting** | Short table: symptom → named error/recovery → fix. Prefer this heading over "When things go wrong" |
| **When *not*** | Situations where another tool is better (or "this is rare") |
| **Advanced (if any)** | Optional depth after the reader can ship — only when the page needs it. Heading: **`## Advanced`** (not "Going further", "Day one", or "Basics") |

Missing "when not" and missing named errors are the two most common ways a page
looks finished and still strands the reader at 11pm.

**Do not** bury what the reader must know under migration notes, full config maps,
or optional forms. Collapse those (`??? note` / `??? info`) or put them after the
required path — at the end under `## Advanced` if needed.

### Don't label the required path

The body of the page is what they have to know. No "Day one", "Basics",
"Essential", or "Day-one checklist" banner over material that is simply the job of
the page. If a short ship recap helps, write it as ordinary prose or bullets —
not a second curriculum track. Optional depth goes at the end under **`## Advanced`**
(or a specific topic heading). "Going further" is the old name for Advanced; use
**Advanced**.

### Standard headings (when present)

| Use | Not |
|---|---|
| `## Troubleshooting` | "When things go wrong", "Unhappy path" |
| `## Advanced` | "Going further", "Day one / Going further" two-speed openers |
| (no label) | "Basics", "Day-one checklist", "Essential" |
| (omit) | `## Start here`, on-page reading-order mini-TOCs |

## Progressive pacing

Teach like a good tutorial, not like a reference dump with anecdotes.

1. **Happy path first.** One growing example (counter for pure Core; domain noun
   for batteries) that adds exactly one idea per step.
2. **Required, then optional.** Long pages put what you need to ship first. Optional
   depth (full config maps, Form-2/3, chain algebra, mint policies) comes **after**
   under `## Advanced` — not a polite promise mid-page, not a "day one vs later
   career" frame. Skip two-speed openers ("Day one: … Going further: …"); the
   structure of the page does that job.
3. **Troubleshooting as tables**, not essays. Prefer
   `:rf.error/no-such-handler` over "things might fail quietly." Recovery belongs
   in the same row as the error. Heading: **Troubleshooting**.
4. **Depth is earned.** Argument-dependent input-fns, Form-2/3, full frame-config,
   mint policies, interceptor chain algebra — after the required path, or collapsed.
5. **Length is a smell, not a rule.** If the reader still cannot ship after a scroll
   of essay, the page is two pages or one page with optional depth mixed into the
   required path.

### Slim model vs catalogue

Guide pages teach. When a page accumulates option tables, precedence rules, or every
keyword of a map, **move that weight to the API** (or glossary). Keep a pointer.
The model page stays quotable; the API stays complete.

Same split for domain corpora: a *concepts* page is the flat grammar; growth pages
each add one capability; operate pages own production failure modes.

## Structure of the corpus

Diátaxis with reference first-class.

**Core track** (flat nav, learning order; the arc below is conceptual):
introduction (the **event pipeline**) → hiccup (the notation) →
**pure pipeline** (events → app-db → subscriptions → views) → **impurity**
(effects, including run-to-completion teaching; coeffects) → **structure**
(frames → flows → interceptors) → **operations** (errors → observability) →
**advanced** (images; run-to-completion operational detail) → how-to → testing →
explanation → migration → glossary.

Prefer **event pipeline** (and *pipeline run*) for the fixed stage sequence. Do
not call that structure "the loop" — a pipeline is linear per event; apps advance
as a *sequence of pipeline runs*, not a free-form cycle. "Pure pipeline" names the
first four pages (no impurity yet). Legitimate "loop" uses remain: drain loop,
dispatch cycle, `for`/loop index, "loop the render" as a bug.

### Technique owners (do not invent a second home)

One technique, one concept owner. Recipes and tests *use* the technique; they do
not re-teach it.

| Technique | Owner |
|---|---|
| Event vocabulary, `dispatch` | [Events](core/events.md) |
| `{:db}`, facts vs conclusions, seed-as-event | [app-db](core/app-db.md) |
| Named derivation, layers, `=` gate | [Subscriptions](core/subscriptions.md) |
| Hiccup notation, view-in-view composition | [Hiccup](core/hiccup.md) |
| `reg-view` semantics, thin views | [Views](core/views.md) |
| `:fx` grammar, RTC *idea*, `reg-fx` | [Effects](core/effects.md) |
| Managed HTTP depth / retry / abort | [async](async/http.md) (not Core) |
| `:rf.cofx/requires`, grades, `reg-cofx` | [Coeffects](core/coeffects.md) |
| Isolation, carry, ensure vs scope | [Frames](core/frames.md) |
| `init!`, hot reload, host listeners | [Boot how-to](core/how-to/boot-and-mount-an-app.md) |
| Materialise for handlers | [Flows](core/flows.md) |
| Four homes (which tool) | [Where state lives](core/where-state-lives.md) |
| Cross-cutting chain (rare) | [Interceptors](core/interceptors.md) |
| Registration sets (rare) | [Images](core/images.md) |
| Dossiers / recovery verbs | [Errors](core/errors.md) |
| Trace wire; classification *why* | [Observability](core/observability.md) |
| Classification *recipe* | [Secrets how-to](core/how-to/keep-secrets-out-of-traces.md) |
| Machines / resources / routing / SSR | Their battery corpora — pointer only from Core |

Interceptors and images are **structure but rare** — open with when-not; do not
imply every app needs them on day two.

**Domain corpora** (machines, resources, async, routing, ssr): **nav order** is the
reading order (tutorial first when one exists, then model, then growth in dependency
order) — do not restate it on the index under `## Start here`. Index states
**prerequisites** and **when not to use this artefact**, then the page's own job
(demo, scope table, …).

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
`??? info` callouts — short, never required reading. A v1 callout is earned only where
the v1 instinct *misleads*; most pages need zero. Retired v1 surfaces
(`inject-cofx`; the retired/renamed `:rf.world/inputs` → flat `:rf.cofx` map)
appear **only** on the [migration page](core/25-from-re-frame-v1.md), marked
superseded — never as live teaching elsewhere.

## Live cells and demo shapes

Use ` ```cljs-rf2 ` when editing teaches more than reading. Keep cells small and
self-contained; RealWorld stays static with a link to the example. Prefer:
why → cell → optional `!!! tip "Try it"`.

**Shapes that stay true:**

| Context | Prefer | Avoid as "the way" |
|---|---|---|
| Pure-pipeline Core demos | `frame-root` + named initialise event + `reg-view` | Bare `defn` that `subscribe`s under a provider (raises `:rf.error/no-frame-context`) |
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

Teaching pages benefit from **one** quotable takeaway — the sentence that sticks.
Prefer a single `> **…**` pull-quote near the open. That's craft, not a lint rule —
better one clear sentence in prose than a forced slogan.

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
- [ ] Required path is the unlabeled body; optional/advanced is after it (if any)
- [ ] Troubleshooting table (or equivalent) with verified `:rf.error/*` / recovery ids
- [ ] "When not" stated where a feature can be misapplied
- [ ] Callouts not a wall of boxes; catalogue weight pushed to API if the page bloats
- [ ] Pure-pipeline cells: `frame-root` + `reg-view` + named initialise (unless Frames is the topic)
- [ ] New/changed live cells clicked in a browser
- [ ] Nav row if the page is new; descriptive nav label; `mkdocs build --strict` and `python scripts/check_doc_slugs.py` green
