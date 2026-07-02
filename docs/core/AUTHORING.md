# Guide authoring contract

> **Who this is for.** Contributors writing or revising any page under `/docs` — the core guide (`docs/core/`) and the machines, async, resources, routing, and ssr corpora alike. They all hold to the same standards now: a simple, direct voice; a standalone corpus with no spec links; no ceremony footers; delta teaching; the live-cell rules. This page is excluded from the site nav — readers never land here. If you came to *learn* re-frame2, start at [the guide](README.md). This page's one job: state the contract every docs page — **teaching material and reference material alike** — is held to, and the gates that enforce it.

The corpus is organised by **Diátaxis** — tutorial / how-to / explanation / reference — with two deliberate refinements. First, **reference is separated, never slighted**: the exact API shape lives in the [API reference](../api/README.md) (`docs/api/`) and the vocabulary in the [glossaries](glossary.md), and both are authored to their own best-practice bar ([Reference pages](#reference-pages--glossaries-and-the-api)). A guide page that starts accumulating option tables and precedence rules is absorbing reference weight — move it there, don't polish it. Second, the mode discipline is enforced **per page**, not per section: a page is one mode all the way down.

Which yields the rule everything else here serves:

> **If a page can't state its one job, it's two pages.**

## The page contract

Every guide page commits to all five:

1. **One job, one mode.** The page is exactly one of *start / tutorial / how-to / explanation / index*, and its opening states the reader's problem — what brought them here, what they can do afterward — in a sentence or two.
2. **One load-bearing takeaway** a reader could quote from memory a week later, set as the page's single bold pull-quote blockquote (`> **…**` — this page's is above, and it's the only blockquote form left in the corpus). One per page; a page with three slogans has none.
3. **Reader-first ordering.** Goal → working code → explanation. Result before mechanism, mechanism before theory. Never open with internals.
4. **Adjacency.** Examples sit beside the explanation they serve; warnings sit beside the risky step; the common mistake appears where the reader is about to make it — not in a pitfalls appendix.
5. **No ceremony footer.** No "You can now" / "what you learned" checklist, no "In this section" preamble, no "What's next" paragraph — navigation is the left nav plus Material's prev/next buttons, and a genuinely useful cross-link belongs inline in the prose where it's relevant, not parked at the bottom.

Mode rules, stated as what each mode *forbids*: a **tutorial** page never catalogues alternatives ("you could also…" is a how-to's job); a **how-to** page assumes competence and never teaches a concept beyond a link; an **explanation** page carries no task steps; an **index** page routes and never teaches. Quality bar for foundational pages: a concrete reader problem, a complete listing, a walkthrough, a common mistake, and a checkpoint the reader can run or reason through.

## Voice

**One voice, four registers.** The whole corpus — tutorials and reference alike — speaks as a single author: **a friendly senior developer, good at writing technical documentation, in a simple, clear, direct voice.** What varies by document kind is the *register*, never the persona:

| Register | Where | Sounds like |
|---|---|---|
| **Warm teaching** | quickstart, tutorials, concepts | The most explanatory and conversational — the reader is learning. This is where the occasional flash of humor lives (below). |
| **Tight recipe** | how-to, testing | The reader is competent and in a hurry; warmth is a sentence, not a paragraph. |
| **Argument** | the explanation shelf | Makes a case and defends it — the essay voice, trimmed of excess. |
| **Terse reference** | glossaries, `docs/api/` | Exact, exhaustive within its surface, boring on purpose. No humor, no persona callouts, no narrative. |

The test in every register: would it sound natural said aloud, and could a tired engineer follow it at 11pm? The reader knows React or Redux but is new to re-frame2 — meet them as a knowledgeable colleague, not as a terse README and not as an essay.

- **Explain the why, not just the what.** After an instruction, a short *because* / *which means* / *so that* clause earns its keep. A senior dev tells you why a thing works, briefly.
- **Vary your rhythm and connect your ideas.** Mix sentence lengths; carry the reader with transitions ("Now that X works…", "Here's the catch…", "The reason this matters…"). Stacks of clipped four-word sentences read cold — that's the failure mode to avoid.
- **Be warm, and on the reader's side.** Acknowledge what trips people up, what they might expect, what they can ignore for now. That small gesture is most of what reads as friendly.
- **Trust the reader with the obvious.** Calm seniority, not anxious over-hedging: spend the words on what's genuinely hard, not on what they already know.
- **Humor is seasoning, Yegge-calibrated.** An occasional flash of conversational candor or a vivid concrete image in the *teaching* registers is welcome — the absurdity of a bug class, the honest confession of what everyone gets wrong the first time. The guardrails that keep it from rotting back into the essayist voice: the humor rises out of the material, never bolted on; the sentence must survive the joke's deletion; never in reference mode, never inside a gotcha; about one moment per page. When in doubt, cut it.
- **Never show off.** Still the standing guardrail: no aphorism-per-paragraph, no flourish for its own sake, no deep-cut analogies. Anchor to one tool the reader knows. One bolded takeaway per page, not one per paragraph.

**Two standing lens-callouts run terser than their page.** A callout is dense by design, so both families drop the warmth and compress:

- **The JS lens** — the persona deltas (`??? info "Coming from React?"`, `??? info "For JavaScript developers"`): a lens for seeing re-frame2 *through* a tool the reader already holds — one tight mapping plus the one deliberate divergence, never a second tutorial. (Form and budget: [Callouts](#callouts) and [Delta teaching](#delta-teaching).)
- **The FP / category-theory lens** — the `??? note "Going deeper"` asides and the "For the categorically curious" blocks: where the functional or categorical frame genuinely illuminates (a fold, an algebra, effects-as-data), state it tersely, gloss every term in the same breath, keep it skippable. It's a design tool, not teaching vocabulary.

## Define before use

A reader goes top to bottom. The first time a page uses a core term — `app-db`, event, handler, subscription, view, effect, coeffect, frame, resource, mutation — it carries a one-line plain gloss at or before that first use ("app-db, your app's single state map"). Never use a load-bearing term cold, and never pack the reader off to another page mid-flow for a basic word — gloss it inline in a few words. The guide's reading order (quickstart → the loop → tutorial → deeper concepts) exists so the fundamentals are taught before a real domain leans on them.

## Callouts

Callouts are **MkDocs admonitions** — never bold-lead blockquotes — and the expanded-vs-collapsed choice turns on how skippable the content is:

- **Footguns and gotchas** → `!!! warning "Gotcha — …"`, expanded, inline beside the risky step. A footgun the reader scrolls past unread has failed at its one job.
- **Notes, heads-ups, "why this matters", "when not to"** → `!!! note "…"`, expanded.
- **Suggested experiments** → `!!! tip "Try it"` — the what-to-try edit beside a live cell or worked example.
- **Persona deltas** (`??? info "Coming from React?"`, `??? info "For JavaScript developers"`) and **v1→v2 deltas** (`??? info "From re-frame v1"`) → **collapsed** `???` blocks. They're frequent, skippable, and reader-specific; collapsing keeps the page's vertical rhythm for every reader they don't address. A page must read complete with all of them collapsed.
- **Deep or optional asides** → collapsed `??? note "Going deeper…"`.

Give each admonition a title (what used to be the bold lead — trimmed to a line; when the lead is too long for a title, use the bare type and keep the lead bolded in the body). Bodies are indented four spaces with a blank line after the marker — mis-indentation breaks the build. The **one exception** that stays a plain `> **…**` blockquote is the page's single quotable takeaway: that's a pull quote, not a callout.

**Density budget.** Admonitions are seasoning, and they're cheap to mint — so budget them. A gotcha sits beside the one step it guards; two boxes may touch; **three in a row means the prose is hiding structure** — promote the content into the section body or split the section. A page that is more box than prose has inverted itself: the boxes have become the content, and the contract above no longer describes the page.

## Tiers and modes

Paths below are relative to `docs/`. This table maps the **core guide**; the domain corpora (`machines/`, `async/`, `resources/`) mirror the same concepts → tutorial → how-to → reference shape inside their own tab.

| Where | Tier | Mode | Job |
|---|---|---|---|
| `core/README.md` | start | index | Route readers; teach nothing |
| `core/quickstart.md` | start | start | Pixels in five minutes |
| `resources/tutorial/` (its own tab) | tutorial | tutorial | Build RealWorld end to end |
| `core/concepts/`, `core/where-state-lives.md` | concepts | explanation | The mental model, one piece per page |
| `core/how-to/` | how-to | how-to | One task, complete code, done |
| `core/testing/` | how-to | how-to | One test target, complete code, done |
| `core/explanation/`, `core/derivations-and-algebra-views.md` | explanation | explanation | The why — for the curious, not the blocked |
| `core/25-from-re-frame-v1.md` | migration | migration | v1 deltas, not basics |
| `core/glossary.md`, per-tab glossaries | reference | reference | One term, one standalone definition, one route |
| `docs/api/` | reference | reference | Every public callable: Kind / Signature / Description / Example |
| `core/AUTHORING.md` | meta | index | This contract |

A new page must name its tier and mode before drafting starts. If it doesn't fit one row, it's two pages.

## Reference pages — glossaries and the API

Reference is a **first-class authoring track** with its own best-practice bar, not an annex the guide points at. It runs in the terse-reference register — exact, exhaustive within its surface, no persona callouts, no humor, no narrative — and its quality test is the reader who arrives mid-task from a link or a search: land, read one entry, leave unblocked in under a minute.

**Glossary entries** (`core/glossary.md` and the per-tab glossaries):

- One term per entry under a bolded heading; group entries by role when the vocabulary has structure, never alphabetically for its own sake.
- Open with a one-to-two-sentence definition that **stands alone** — the reader may never scroll past it.
- Cross-link every load-bearing term the definition uses; include a short code sample when the term has a canonical spelling.
- End with a *See / Related* line to the page that teaches the term in depth. A glossary entry defines and routes; it never teaches.

**API pages** (`docs/api/`):

- One entry per public callable, with four fixed fields in order: **Kind** (function / macro / fx / sub / event), **Signature**, **Description**, **Example** (drawn from real usage).
- Exhaustive within the namespace: every public symbol appears, including the ones the guide never mentions.
- The description states the **contract** — arguments, return shape, the `:rf.error/*` ids it can raise — never the motivation. Motivation lives in the guide page that teaches the surface, linked once per page, not once per entry.

## Progressive structure

A learning track is read top-to-bottom in the left nav, so the nav itself must carry the progression:

- **Number the pages of a sequential track** — `"1. Events and the cascade"`, `"2. app-db: state in one place"`, … — so a reader always knows where they are and what's next. The loop pages and the tutorial parts do this; a future sequential track should too.
- **Give every nav label a descriptive tail** (`"Frames: isolated worlds"`, not `"Frames"`). The reader should be able to pick their page from the nav alone, without opening three wrong ones first.
- **Order pages so each leans only on what came before it**, and open each page by placing it: one sentence on what the reader just learned and what this page adds. The prev/next buttons carry the navigation; the opening sentence carries the *thread*.

## Canon apps — per corpus

Each corpus orbits a small, fixed cast of apps rather than inventing throwaways, and names its own ecosystem anchor in its opening (see [Delta teaching](#delta-teaching)). The **core guide** holds to a two-app canon; the domain corpora each carry their own:

- **Core guide — the counter, then RealWorld.** The **counter** (odd/even badge, last-clicked timestamp) carries the start tier and the concepts pages, for as long as it can stretch: it's the ecosystem's shared hello-world — Elm's counter, the Redux counter — so 100% of the reader's attention goes to *our* idioms, not the domain. **RealWorld** (conduit: articles, feeds, favorites, auth) carries the tutorial and the how-tos. **The switch point is the moment server data enters the picture** — the counter has no server, and faking one would teach a shape we'd reject in review.
- **Machines — XState, a login and a websocket.** Anchor on XState; the canonical machines are the **login** flow (idle → submitting → authed / error / locked-out) and the **websocket** lifecycle (connecting → connected → dropped → reconnecting).
- **Resources — TanStack Query, RealWorld.** Anchor on TanStack Query (RTK Query and SWR are the same family); reuse RealWorld's server nouns (articles, feeds, profiles) rather than inventing a third domain.
- **Async (HTTP) — RealWorld's server nouns.** The managed-request corpus continues RealWorld's articles and feeds as its running domain, taught against the managed-request model.

Routing and SSR likewise borrow RealWorld's nouns rather than inventing a domain. Small inline examples are fine anywhere; the *narrative* belongs to each corpus's canon. No throwaway example apps.

## A standalone corpus — no spec links

The guide is the documentation for the ClojureScript implementation, and it is designed to be **complete and readable on its own. Never link into `spec/`.** When a spec concept matters to a reader, repeat it here with a ClojureScript-implementation flavour, in the page that owns the topic — don't send the reader down. Where a page wants "the exact shape of every option," that weight belongs in the [API reference](../api/README.md); where it wants a definition, the [glossary](glossary.md).

Guide → guide links are plain sibling-relative and encouraged. Links to `examples/` targets become GitHub blob URLs (examples are not staged into the site).

Contributors still *verify* against the spec and the implementation — see [Snippets are production code](#snippets-are-production-code). The rule is about reader-facing links, not about where truth is checked.

## Delta teaching

Every domain page **opens by naming its ecosystem anchor** — TanStack Query, XState v5, Redux, Storybook, React Router, re-frame v1 — and the deliberate divergences. The reader arrives with a mental model; teach the difference, not the basics.

Per-persona callouts are collapsed `??? info` admonitions (see [Callouts](#callouts)), **short, at most two per section, never load-bearing** (the page must read complete with every one of them collapsed):

```markdown
??? info "Coming from TanStack Query?"

    A resource is your query — except reads are subscriptions and fetches
    are caused by routes and events, never by render.
```

The **v1 delta callout** (`??? info "From re-frame v1"`) is the standing instance of this device: what moved, linking to [From re-frame v1](25-from-re-frame-v1.md) for the full delta. Retired v1 surfaces (`inject-cofx`, `:rf.world/inputs`) appear *only* on that migration page, marked superseded — never in teaching prose elsewhere.

## Snippets are production code

Readers copy-paste; nobody reads the disclaimer. So every snippet is complete, idiomatic, and runnable — **never simplified into an anti-pattern for pedagogy**. If the simplification would fail review, the example is wrong even with a caveat beside it.

- **Source by inclusion.** Prefer adapting snippets from `examples/` files, citing the source in a first-line comment: `;; cf. examples/real-apps/realworld_http/articles.cljs`. The named file is the compile gate's anchor (see [Gates](#the-gates)); an uncited hand-written snippet is a snippet CI can't defend.
- **Verify every API symbol** against `spec/API.md` and `implementation/core/src/re_frame/core.cljc` (`git grep` both) before teaching it. Not found there → not taught.
- **Verify every error and warning id the same way.** A page asserting "fails loud with `:rf.error/x`" has made a machine-checkable claim — `git grep` the id in `implementation/` before teaching it. The `:rf.error/*` catalogue is the corpus's most-cited machine contract; an invented or stale id burns more trust than a broken link.
- **Async snippets carry the frame.** Never a bare `rf/dispatch` from a `js/setTimeout` / promise callback — that raises `:rf.error/no-frame-context`. Use the frame-carrying idiom from the examples.

## Live cells

Pages may embed editable, in-browser code via two fences (the info string is the only difference from a static block): ` ```cljs ` evaluates forms and prints the last value — pure ClojureScript teaching only, no re-frame; ` ```cljs-rf2 ` **mounts the last form as a live component** against re-frame2's real public API. For guide pages you almost always want `cljs-rf2`.

Reach for a live cell **wherever editing teaches more than reading** — the counter and its small variations, a subscription graph pruning, a run-to-completion drain, a single derivation. A concepts page that never lets the reader poke its idea is leaving teaching on the table; most loop and concept pages should carry at least one cell. The limits are real, though: keep each cell small and self-contained, stay on the proven cell surface (below), and keep a full app like RealWorld static with a link to the worked example. Section shape: a sentence of *why* → the cell → a `!!! tip "Try it"` naming an edit and its expected outcome. A live cell with no suggested experiment is a slow screenshot.

The `cljs-rf2` rules, each of which bites the first time:

- **Standard preamble:** `(require '[reagent2.core :as r] '[re-frame.core :as rf])` — the `reg-*` family and `dispatch` / `dispatch-sync` / `subscribe` all resolve as functions.
- **Top-level forms only** — never wrap the cell in `(do …)`; it breaks the require's alias resolution for everything inside.
- **The last form must be renderable hiccup** (`[counter]` or a literal `[:div …]`). There is no plain-eval path; anything else renders blank. To show a computed value, wrap it in a tiny display view.
- **Seed app-db with `rf/dispatch-sync`** before the final view form — plain `dispatch` races the first render.
- **Cell dialect is plain `defn` views with explicit `rf/dispatch` / `rf/subscribe`** — the cell environment is functions-only, so macro sugar like `reg-view` is unavailable. Don't re-explain this; the reader-facing statement is the plain-`defn` section in [Views: pure functions of data](concepts/views.md#plain-defn-views-and-when-they-break) — link it when a cell and a static listing differ.
- **One shared registry and app-db per page**, across all cells. Write each cell self-contained (require, registrations, seed, view) and namespace ids (`:demo-a/inc`) when cells must be independent.
- **Name the eval shortcut once per page** — `Ctrl-Enter` (`Cmd-Enter` on macOS) — the first time you ask for an evaluation; after that, "re-evaluate".
- **Stay on the proven cell surface** — what shipped cells already exercise: `reg-event`, `reg-sub` (including `:<-` chains), `dispatch` / `dispatch-sync` / `subscribe`, plain `defn` views, and `reg-machine` / `sub-machine`. Flows, interceptors, and routing are **unproven** in the cell environment — a cell reaching for a new surface is exactly the cell you must click in a browser before merging, and the first one that works earns its surface a place on this list.
- The rf2 bundle loads on demand, so a page's first cell may take a moment to come alive. After authoring, build and click every cell in a browser — a cell that compiles but doesn't mount is worse than a static block.

## Standing per-page devices

- **Do → observe → explain.** Any page whose example dispatches something closes the loop with an observation step: *"Open Xray: the submit's event row shows the validation branch — no request left."* Phrase affordances generally (the event row, the epoch ledger, the app-db view); do not invent Xray UI names — verify in `tools/xray/spec/` before using a specific one.
- **The war story.** Where a feature exists because a production failure class exists, tell it as a lived story — concrete, second-person, ending in the mechanism that makes the failure *structurally impossible* (canonical instance: the test that's green every afternoon and red the one time CI crosses midnight, until the clock becomes a recorded fact). At most one per page; it motivates, the mechanism teaches.
- **"For the categorically curious."** re-frame2's category-theory grounding is a design tool, not teaching vocabulary — *translate, don't transplant*. The deeper frame goes only in a collapsed `<details markdown="1"><summary>For the categorically curious</summary>…</details>` block: always skippable, never load-bearing, at most one per concept, ~6 lines, every term glossed in the same breath.
- **Asides, footguns, and persona deltas are admonitions** — see [Callouts](#callouts) for the expanded-vs-collapsed split. The only bold-lead blockquote left on a page is its single pull-quote takeaway.
- **No bead references in prose.** Pages state current truth, not the decision trail; tracker-id citations belong in the tracker, not in the page. Unlinked normative ids are fine — a migration-rule id, or a spec section id cited as *text* — because those are normative, not historical; but never a *hyperlink* into `spec/`, which the standalone-corpus rule forbids.
- **Honesty.** Say when *not* to use a feature; mark deferred things (timed polling, infinite feeds) and CLIENT-ONLY paths; no marketing voice. The test for every paragraph: could a tired engineer act on it at 11pm mid-incident?

## Reviewing the corpus

The writing contract keeps a *page* good; these five lenses keep the *corpus* good. Each runs on a trigger, not a calendar — and on a big sweep they run in this order, because the first two produce defects with repros, the next two produce prioritised gaps, and the last produces taste calls that are only valid once the defects are fixed:

1. **The cold-reader walkthrough** — *when the quickstart or a tutorial changes materially.* Follow a README door for real: a fresh agent (or contributor) gets only the quickstart + tutorial and an empty project and must produce a working app. Every stall, cold term, or wrong turn is a doc bug with a repro. (This is [gate 2](#the-gates), given its trigger.)
2. **Executable truth** — *when a page's cells, snippets, or error-id claims change.* Click every live cell in a browser; run the changed snippets; check newly-asserted `:rf.error/*` behaviour against the implementation.
3. **The rendered-site read** — *after any bulk form change* (a callout conversion, a nav restructure). Read the built site, not the markdown: vertical rhythm, box density against the [budget](#callouts), nav weight, page-length outliers, whether collapsed blocks hide anything a reader actually needs.
4. **Task arrival** — *periodically.* Probe the site search with realistic task and error-string queries ("debounce a search box", the text of `:rf.error/no-frame-context`). Every dead search is a routing or coverage gap.
5. **Compression** — *only once content is stable.* Per page: what no longer earns its place? Going-deeper asides and stacked callouts first. Run this after the defect lenses, never before.

## The gates

The contract's own honesty rule applies to this list too. What CI actually enforces on every docs PR isn't these four — it's the **`mkdocs build --strict` build**, the **link + anchor validator** (`scripts/check_doc_slugs.py`), and the **residue scans** (retired `inject-cofx` / `:rf.world/inputs`, retired composition vocabulary, retired image keys), all in `.github/workflows/docs.yml`. The four gates below are the *intent* that keeps the guide from rotting; three are human discipline and one is only partly machine-checked, marked honestly:

1. **Per-PR staleness rule** *(review discipline, not automated)*. Every feature-touching PR updates the affected guide page(s) or states explicitly in the PR why the guide is unchanged — the same convention already asked for `tools/xray/spec/`. No workflow fails a PR for stale docs; a reviewer holds the line.
2. **The cold-agent test** *(periodic manual audit, not automated)*. A fresh AI agent, given only the **quickstart + tutorial** and an empty project, must produce a working app. Every stall or hallucination is a documentation bug *with a repro*. Run by hand, not on every PR. Concepts and how-tos are deliberately out of scope — the agent reaching for them is itself a tutorial-tier finding.
3. **Time-to-pixels** *(manual measurement, not automated)*. From setup start to first render in under five minutes, measured by hand against the setup section of [the tutorial's index](../resources/tutorial/index.md). The quickstart's live cells are zero-install and don't count toward the budget.
4. **Snippets compile in CI** *(partly automated)*. The `examples/` sources a snippet is copied from *do* compile in CI (`npm run test:examples-compile`), so a cited snippet's source can't silently break. What CI does **not** do is extract the doc's copy and recompile it — keeping the page's copy in sync with its cited source, and verifying every hand-written symbol against `spec/API.md` + `core.cljc`, is still the author's job. Prefer promoting a hand-written snippet into an example so the compile gate covers it.

## Mechanics

Write Markdown for MkDocs Material. Pages live under `/docs` — the core guide (`docs/core/`) and the domain corpora (`docs/machines/`, `docs/async/`, `docs/resources/`, `docs/routing/`, `docs/ssr/`); the nav is explicit in `mkdocs.yml` and a new page must be added there by hand, next to its siblings (this page stays out of the nav). Renaming a heading changes its anchor, so sweep inbound links when you rename one — the slug gate catches stragglers at build time, but knowing why saves the debugging round. Build locally with `mkdocs build --strict` before opening a PR; if the page has live cells, load it and click them.

## Before you open a PR

The contract, made actionable at the moment it matters:

- [ ] The page states its one job in its opening, and carries exactly one pull-quote takeaway
- [ ] No links into `spec/`; sideways links go to the page that owns the idea
- [ ] Every callout is an admonition in its contract form, within the density budget
- [ ] Every load-bearing term is glossed at first use
- [ ] Every API symbol **and** every `:rf.error/*` / `:rf.warning/*` id verified against the implementation
- [ ] Live cells clicked in a browser; new cells stay on the proven surface (or extend it deliberately)
- [ ] Nav row added beside its siblings; `mkdocs build --strict` and `python scripts/check_doc_slugs.py` both green

