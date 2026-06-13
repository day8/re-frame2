# Guide authoring contract

> **Who this is for.** Contributors writing or revising pages under `docs/guide/`. This page is excluded from the site nav — readers never land here. If you came to *learn* re-frame2, start at [the guide](README.md). This page's one job: state the contract every guide page is held to, and the gates that enforce it.

The guide is organised by **Diátaxis** — tutorial / how-to / explanation / reference — with two deliberate divergences. First, **reference is not a guide mode at all**: the normative reference lives in `spec/` (AI-targeted, exhaustive, boring on purpose); the guide carries only [a thin map](reference.md) pointing down into it. A guide page that starts accumulating option tables and precedence rules is absorbing weight the spec already owns — move the weight, don't polish it. Second, the mode discipline is enforced **per page**, not per section: a page is one mode all the way down.

Which yields the rule everything else here serves:

> **If a page can't state its one job, it's two pages.**

## The page contract

Every guide page commits to all five:

1. **One job, one mode.** The page is exactly one of *start / tutorial / how-to / explanation / index*, and its opening states the reader's problem — what brought them here, what they can do afterward — in a sentence or two.
2. **One load-bearing takeaway** a reader could quote from memory a week later (this page's is the blockquote above). One per page; a page with three slogans has none.
3. **Reader-first ordering.** Goal → working code → explanation → spec link. Result before mechanism, mechanism before theory, theory before citation. Never open with internals.
4. **Adjacency.** Examples sit beside the explanation they serve; warnings sit beside the risky step; the common mistake appears where the reader is about to make it — not in a pitfalls appendix.
5. **The footer.** A **"You can now:"** bullet list (capabilities, not topics covered) plus **at most two** onward links, each with a reason to follow it.

Mode rules, stated as what each mode *forbids*: a **tutorial** page never catalogues alternatives ("you could also…" is a how-to's job); a **how-to** page assumes competence and never teaches a concept beyond a link; an **explanation** page carries no task steps; an **index** page routes and never teaches. Quality bar for foundational pages: a concrete reader problem, a complete listing, a walkthrough, a common mistake, and a checkpoint the reader can run or reason through.

## Tiers and modes

| Where | Tier | Mode | Job |
|---|---|---|---|
| `README.md` | start | index | Route readers; teach nothing |
| `quickstart.md` | start | start | Pixels in five minutes |
| `tutorial/` | tutorial | tutorial | Build RealWorld end to end |
| `concepts/`, `where-state-lives.md` | concepts | explanation | The mental model, one piece per page |
| `how-to/` | how-to | how-to | One task, complete code, done |
| `explanation/`, `derivations-and-algebra-views.md` | explanation | explanation | The why — for the curious, not the blocked |
| `25-from-re-frame-v1.md` | migration | migration | v1 deltas, not basics |
| `reference.md` | reference | index | The thin map down into `spec/` |
| `AUTHORING.md` | meta | index | This contract |

A new page must name its tier and mode before drafting starts. If it doesn't fit one row, it's two pages.

## The two-app canon

The guide orbits exactly two applications, and the boundary between them is deliberate:

- **The counter** (with its odd/even badge and last-clicked timestamp) carries the start tier and the concepts pages, for as long as it can stretch. It's the ecosystem's shared hello-world — Elm's counter, the Redux counter — so 100% of the reader's attention goes to *our* idioms, not the domain.
- **RealWorld** (conduit: articles, feeds, favorites, auth) carries the tutorial and the how-tos. **The switch point is the moment server data enters the picture** — the counter has no server, and faking one would teach a shape we'd reject in review.

Concepts pages whose domain is inherently server-shaped (resources, HTTP, routing) borrow RealWorld's nouns rather than inventing a third domain. Small inline examples are fine anywhere; the *narrative* belongs to the canon. No throwaway example apps.

## Spec links

The guide teaches the model and the happy path, then **links down** into spec for completeness — it never duplicates spec content in friendlier voice (that's how 74KB chapters happen). The link placement rule: **teach first, link after**. A spec link carries completeness, never the lesson.

A spec link is wrong when it's a "see the spec for the full story" dodge replacing an explanation the page owes the reader, or a parenthetical citation ("(per Spec 010)") — citations belong in the spec.

**Link form** — write the GitHub-correct relative path; the build hook rewrites it for the staged site, so never hand-write the staged path:

- From a **depth-3** page (`tutorial/`, `concepts/`, `how-to/`, `explanation/`), a spec link's target is `../../../spec/008-Testing.md`.
- From a **depth-2** page (`docs/guide/X.md`), it's `../../spec/API.md`.
- Same rule for `migration/` and `examples/` targets at the matching depth (examples links become GitHub blob URLs — examples are not staged into the site).
- Guide → guide links are plain sibling-relative and encouraged.

## Delta teaching

Every domain page **opens by naming its ecosystem anchor** — TanStack Query, XState v5, Redux, Storybook, React Router, re-frame v1 — and the deliberate divergences. The reader arrives with a mental model; teach the difference, not the basics.

Per-persona callouts are bold-lead blockquotes, **one sentence each, at most two per section, never load-bearing** (the page must read complete with every callout skipped):

> **Coming from TanStack Query?** A resource is your query — except reads are subscriptions and fetches are caused by routes and events, never by render.

The **v1 delta callout** (`> **Coming from re-frame v1?** …`) is the standing instance of this device: one sentence on what moved, linking to [From re-frame v1](25-from-re-frame-v1.md) for the full delta. Retired v1 surfaces (`inject-cofx`, `:rf.world/inputs`) appear *only* on that migration page, marked superseded — never in teaching prose elsewhere.

## Snippets are production code

Readers copy-paste; nobody reads the disclaimer. So every snippet is complete, idiomatic, and runnable — **never simplified into an anti-pattern for pedagogy**. If the simplification would fail review, the example is wrong even with a caveat beside it.

- **Source by inclusion.** Prefer adapting snippets from `examples/` files, citing the source in a first-line comment: `;; cf. examples/reagent/realworld/articles.cljs`. The named file is the compile gate's anchor (see [Gates](#the-gates)); an uncited hand-written snippet is a snippet CI can't defend.
- **Verify every API symbol** against `spec/API.md` and `implementation/core/src/re_frame/core.cljc` (`git grep` both) before teaching it. Not found there → not taught.
- **Async snippets carry the frame.** Never a bare `rf/dispatch` from a `js/setTimeout` / promise callback — that raises `:rf.error/no-frame-context`. Use the frame-carrying idiom from the examples.

## Live cells

Pages may embed editable, in-browser code via two fences (the info string is the only difference from a static block): ` ```cljs ` evaluates forms and prints the last value — pure ClojureScript teaching only, no re-frame; ` ```cljs-rf2 ` **mounts the last form as a live component** against re-frame2's real public API. For guide pages you almost always want `cljs-rf2`.

Use them **sparingly** — one or two per page, only where editing the code teaches more than reading it. Section shape: a sentence of *why* → the cell → a named *what-to-try* edit with its expected outcome. A live cell with no suggested experiment is a slow screenshot.

The `cljs-rf2` rules, each of which bites the first time:

- **Standard preamble:** `(require '[reagent2.core :as r] '[re-frame.core :as rf])` — the `reg-*` family and `dispatch` / `dispatch-sync` / `subscribe` all resolve as functions.
- **Top-level forms only** — never wrap the cell in `(do …)`; it breaks the require's alias resolution for everything inside.
- **The last form must be renderable hiccup** (`[counter]` or a literal `[:div …]`). There is no plain-eval path; anything else renders blank. To show a computed value, wrap it in a tiny display view.
- **Seed app-db with `rf/dispatch-sync`** before the final view form — plain `dispatch` races the first render.
- **Cell dialect is plain `defn` views with explicit `rf/dispatch` / `rf/subscribe`** — the cell environment is functions-only, so macro sugar like `reg-view` is unavailable. Don't re-explain this; the reader-facing statement is the equivalence section in [Views: pure functions of data](concepts/views.md#the-defn--reg-view-equivalence) — link it when a cell and a static listing differ.
- **One shared registry and app-db per page**, across all cells. Write each cell self-contained (require, registrations, seed, view) and namespace ids (`:demo-a/inc`) when cells must be independent.
- **Name the eval shortcut once per page** — `Ctrl-Enter` (`Cmd-Enter` on macOS) — the first time you ask for an evaluation; after that, "re-evaluate".
- The rf2 bundle loads on demand, so a page's first cell may take a moment to come alive. After authoring, build and click every cell in a browser — a cell that compiles but doesn't mount is worse than a static block.

## Standing per-page devices

- **Do → observe → explain.** Any page whose example dispatches something closes the loop with an observation step: *"Open Xray: the submit's event row shows the validation branch — no request left."* Phrase affordances generally (the event row, the epoch ledger, the app-db view); do not invent Xray UI names — verify in `tools/xray/spec/` before using a specific one.
- **The war story.** Where a feature exists because a production failure class exists, tell it as a lived story — concrete, second-person, ending in the mechanism that makes the failure *structurally impossible* (canonical instance: the test that's green every afternoon and red the one time CI crosses midnight, until the clock becomes a recorded fact). At most one per page; it motivates, the mechanism teaches.
- **"For the categorically curious."** re-frame2's category-theory grounding is a design tool, not teaching vocabulary — *translate, don't transplant*. The deeper frame goes only in a collapsed `<details markdown="1"><summary>For the categorically curious</summary>…</details>` block: always skippable, never load-bearing, at most one per concept, ~6 lines, every term glossed in the same breath.
- **Asides are bold-lead blockquotes** (`> **Heads-up.** …`), not `!!!` admonitions — match the corpus.
- **No bead references in prose.** Pages state current truth, not the decision trail; `(rf2-xxx)` citations belong in the tracker. Spec section anchors and migration-rule ids are fine — those are normative, not historical.
- **Honesty.** Say when *not* to use a feature; mark deferred things (optimistic rollback) and CLIENT-ONLY paths; no marketing voice. The test for every paragraph: could a tired engineer act on it at 11pm mid-incident?

## The gates

Four gates keep the contract from rotting — the guide is maintained like product code:

1. **Per-PR staleness rule.** Every feature-touching PR updates the affected guide page(s) or states explicitly in the PR why the guide is unchanged — the same discipline already enforced for `tools/xray/spec/`, extended to the guide.
2. **The cold-agent test.** A fresh AI agent, given only the **quickstart + tutorial** and an empty project, must produce a working app. Every stall or hallucination is a documentation bug *with a repro*. Concepts and how-tos are deliberately out of scope — the agent reaching for them is itself a tutorial-tier finding.
3. **Time-to-pixels.** From setup start to first render in under five minutes, measured against the setup section of [the tutorial's index](tutorial/index.md). The quickstart's live cells are zero-install and don't count toward the budget.
4. **Snippets compile in CI.** Snippets cited from `examples/` files compile because their sources do; the citation comment is what lets the gate map a page to its compiled source. Hand-written snippets without a source file get the symbol-verification rule as their floor — prefer promoting them into an example.

## Mechanics

Write Markdown for MkDocs Material. Pages live under `docs/guide/`; the nav is explicit in `mkdocs.yml` and a new page must be added there by hand, next to its siblings (this page stays out of the nav). Build locally with `mkdocs build --strict` before opening a PR; if the page has live cells, load it and click them.

---

**You can now:**

- name a new page's tier and mode before drafting, and split it when it can't state one job
- write a page that passes the contract: reader-problem opening, one quotable takeaway, adjacency, a you-can-now footer with ≤2 links
- pick the right canon app (counter until server data appears, RealWorld after), source snippets from named `examples/` files, and link down into spec at the right depth
- author a `cljs-rf2` cell that mounts, deploy the standing devices, and know which of the four gates will check your work

**Next:** [The re-frame2 Guide](README.md) — the reader-facing shape this contract produces · [Build RealWorld — what you'll make, and setup](tutorial/index.md) — the tier the cold-agent and time-to-pixels gates measure.
