# Hicasso — draft user guide

> **Pre-implementation draft — Hicasso does not exist yet.** These pages describe the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-025).

Hicasso is re-frame2's native view layer: interpreted Hiccup on a React
function-component host, optimised for re-frame2. The
[charter](../charter.md) argues why it should exist and
[validation](../validation.md) says what would kill it. This draft answers a
narrower question — **what would a programmer actually type?** — and hands the
operator something readable before a line of runtime code is written.

It is a first cut in the literal sense. It was written from the design record
alone, so every claim here is a *designed* claim: nothing on these pages has been
compiled, run, or measured.

| Page | Its one job |
|---|---|
| [Getting started](01-getting-started.md) | Put one Hicasso view on screen inside a frame, and take it down again |
| [Views and reads](02-views-and-reads.md) | Write a view; read subscriptions from it — in **both** the surfaces still under adjudication |
| [Events as data](03-events-as-data.md) | Put an event vector in an attribute and let the runtime build the callback |
| [Controlled inputs](04-controlled-inputs.md) | Type into a text field whose value round-trips through app-db, and keep your caret |
| [Interop](05-interop.md) | Use a React component from npm |
| [Theming](06-theming.md) | Style a component library without a context API |
| [Ephemeral state](07-ephemeral-state.md) | Decide where "is this dropdown open?" lives, given there is no `local` — and where "it left but is still fading out" lives, given app-db cannot hold it |
| [Testing](08-testing.md) | Assert a view's output without a browser, and know when you still need one |

Eight pages is not an accident. K5 in [validation.md](../validation.md) kills the
programme at "more than ~8 public concepts or ~8 guide pages to ship CRUD", so the
draft deliberately spends its whole budget and no more. If the real guide needs a
ninth page, that is a signal about the design, not about the writing.

## How to read the markers

**[unfrozen]** after a name means the *semantics* are ruled but the *spelling* is a
placeholder. Two things drive that. HD-021 pins the root operation's behaviour and
says outright that its name waits for the donor spike; and
[authoring.md](../authoring.md) puts a blanket hold on declaration spellings until
the tournament has measured. So assume every symbol below is provisional, and treat
the marked ones as *known* to be provisional.

Each page ends with **Not settled yet** — a table of the questions that page raised
and the design record does not answer. Those tables are the most useful part of this
draft. A guide that reads as finished when the design is not is worse than one that
says where the floor is missing.

Examples deliberately alternate between the two candidate read surfaces rather than
settling on one, and every page that shows a read says which surface it is using.
[Views and reads](02-views-and-reads.md) is where the fork is explained; the real
guide will have exactly one spelling and will be much shorter for it.

## What this draft is not

- **Not published.** `design/hicasso/` is in `exclude_docs` in `mkdocs.yml`, so this
  subtree never reaches the site and is never in the nav. Read it in the source tree
  or on GitHub.
- **Not gated.** No fenced block here is digest-pinned by
  `samples_coverage_jvm_test.clj` — that test scans `docs/core/freehand/` only. Edit
  freely; nothing downstream breaks.
- **Not an API freeze.** [authoring.md](../authoring.md) is explicit that nothing in
  the design record licenses freezing an API early, and this guide inherits that.

Three deviations from the [guide authoring brief](../../../AUTHORING.md), taken
knowingly. Banners and asides are blockquotes rather than MkDocs admonitions,
because an excluded tree is read as raw Markdown and `!!! warning` would render as
literal punctuation. There are no `cljs-rf2` live cells, because there is nothing to
run. And the troubleshooting tables name symptoms and fixes but carry no
`:rf.error/*` ids, because no Hicasso error ids have been minted — inventing
plausible ones would be the single most damaging thing this draft could do.

## Where the vocabulary comes from

[decisions.md](../decisions.md) governs; [validation.md](../validation.md) is next.
[authoring.md](../authoring.md) holds the canonical spellings that do exist, and
where this guide shows a symbol that authoring.md does not, the symbol is invented
here and marked. [hd-002-adjudication.md](../hd-002-adjudication.md) is a delegated
advisory on the read fork — it settles what the collector may do and what kills it,
and explicitly does **not** decide which surface ships. The programme is sequenced by
[EP-0038](../../../EP/EP-0038-the-hicasso-view-layer-programme.md).
