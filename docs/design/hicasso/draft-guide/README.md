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
| [Views and reads](02-views-and-reads.md) | Write a view; read subscriptions where you use them |
| [Events as data](03-events-as-data.md) | Put an event vector in an attribute and let the runtime build the callback — clicks, keys, prevention, and links |
| [Controlled inputs](04-controlled-inputs.md) | Type into a text field whose value round-trips through app-db, and keep your caret |
| [Interop](05-interop.md) | Use a React component from npm |
| [Theming](06-theming.md) | Style a component library without a context API |
| [Ephemeral state](07-ephemeral-state.md) | Decide where "is this dropdown open?" lives, given there is no `local` — and where "it left but is still fading out" lives, given app-db cannot hold it |
| [Testing](08-testing.md) | Assert a view's output without a browser, and know when you still need one |

Eight pages is not an accident. K5 in [validation.md](../validation.md) kills the
programme at "more than ~8 public concepts or ~8 guide pages to ship CRUD", so the
guide deliberately spends its whole budget and no more. If it ever needs a ninth
page, that is a signal about the design, not about the writing.

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
