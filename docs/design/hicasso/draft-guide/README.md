# Hicasso — draft user guide

> **Draft ahead of the product artefact.** No `implementation/hicasso/` ships yet.
> Spellings marked **[unfrozen]** are provisional until the API freeze. Behaviour
> shown here is witnessed under `implementation/freehand/test/re_frame/bench/hicasso/`.

Hicasso is re-frame2's native view layer: interpreted Hiccup on a React
function-component host, built for re-frame2. This guide answers one question —
**what does a programmer actually type?**

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
| [Server-side rendering](10-server-side-rendering.md) | Serve a page rendered from a db snapshot and adopt it live in the browser — what is witnessed, what is still open, and how to write an app so SSR is free |

## How to read the markers

**[unfrozen]** after a name means the *semantics* are pinned but the *spelling*
is a placeholder until the API freeze. Prefer the behaviour over the token.

Each page ends with **Not settled yet** — open questions that page raises which
are not yet answered. Those tables hold only the question and its status; they
are not a backlog of design history.

## What this draft is not

- **Not published.** `design/hicasso/` is in `exclude_docs` in `mkdocs.yml`, so
  this subtree never reaches the site and is never in the nav. Read it in the
  source tree or on GitHub. Because it is raw Markdown, banners and asides are
  blockquotes rather than MkDocs admonitions.
- **Not an API freeze.** Names marked **[unfrozen]** wait; landed behaviour is
  what the bench arm already runs.

The design record for Hicasso lives in the parent directory for contributors;
you do not need it to read this guide.
