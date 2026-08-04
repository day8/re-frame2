# Hicasso — draft user guide

> **Draft ahead of the product artefact.** These pages teach the *landed* authoring
> surface: ruled in [decisions.md](../decisions.md) (HD-001…HD-028) and witnessed by
> the bench arm's tests under
> `implementation/freehand/test/re_frame/bench/hicasso/`. No
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze — but everything a page shows without that
> marker has run, in tests, against a real React.

Hicasso is re-frame2's native view layer: interpreted Hiccup on a React
function-component host, optimised for re-frame2. The
[charter](../charter.md) argues why it should exist and
[validation](../validation.md) says what would kill it. This guide answers a
narrower question — **what does a programmer actually type?**

| Page | Its one job |
|---|---|
| [Getting started](01-getting-started.md) | Put one Hicasso view on screen inside a frame, and take it down again |
| [Views and reads](02-views-and-reads.md) | Write a view; read subscriptions where you use them; write its attributes |
| [Events as data](03-events-as-data.md) | Put an event vector in an attribute and let the runtime build the callback — clicks, keys, prevention, and links |
| [Controlled inputs](04-controlled-inputs.md) | Type into a text field whose value round-trips through app-db, and keep your caret |
| [Interop](05-interop.md) | Use a React component from npm |
| [Theming](06-theming.md) | Style a component library without a context API |
| [Ephemeral state](07-ephemeral-state.md) | Decide where "is this dropdown open?" lives, given there is no `local`; where "it left but is still fading out" lives, given app-db cannot hold it; and where the jobs you wanted `:on-mount` for actually go |
| [Testing](08-testing.md) | Assert a view's output without a browser, and know when you still need one |
| [When a view throws](09-when-a-view-throws.md) | Keep one broken view from taking the page down with it |
| [Server-side rendering](10-server-side-rendering.md) | Serve a page rendered from a db snapshot and adopt it live in the browser — what exists, what is landing, and how to write an app so SSR is free |

The page count is editorial judgement now. It used to be a budget: K5 in
[validation.md](../validation.md) killed the programme at "more than ~8 public
concepts or ~8 guide pages to ship CRUD", and this guide was written to spend
exactly that and no more. **The operator removed K5 as a kill criterion on
2026-08-04**, so a page is added when a reader genuinely needs one and not
otherwise. The pressure to stay short did not go away with the number — it just
stopped being a rule.

## How to read the markers

**[unfrozen]** after a name means the *semantics* are ruled but the *spelling* is a
placeholder — HD-021 pins the root operation's behaviour and says outright that its
name waits, and [authoring.md](../authoring.md) holds every declaration spelling
unfrozen until the freeze. Each page ends with **Not settled yet** — the questions
that page raises which the record does not yet answer. Far fewer remain than when
this draft was first written; the ones left are real.

## What this draft is not

- **Not published.** `design/hicasso/` is in `exclude_docs` in `mkdocs.yml`, so this
  subtree never reaches the site and is never in the nav. Read it in the source tree
  or on GitHub. Because it is read as raw Markdown, banners and asides are
  blockquotes rather than MkDocs admonitions.
- **Not gated.** No fenced block here is digest-pinned by
  `samples_coverage_jvm_test.clj` — that test scans `docs/core/freehand/` only.
- **Not an API freeze.** [authoring.md](../authoring.md) is explicit that nothing in
  the design record licenses freezing an API early, and this guide inherits that.

## Where the vocabulary comes from

[decisions.md](../decisions.md) governs; [validation.md](../validation.md) is next;
[authoring.md](../authoring.md) holds the canonical spellings. The read surface was
ruled by the operator on 2026-07-31 — the ambient collector, on ergonomics, with
[hd-002-adjudication.md](../hd-002-adjudication.md)'s correctness gates standing —
recorded in the
[dogfood judgement](../studio/arm1-lean-react-dogfood-judgement.md). The programme
is sequenced by
[EP-0038](../../../EP/EP-0038-the-hicasso-view-layer-programme.md).
