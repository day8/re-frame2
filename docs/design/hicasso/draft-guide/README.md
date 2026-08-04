# Hicasso — draft user guide

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Hicasso is re-frame2's native view layer: interpreted Hiccup on a React
function-component host. This guide answers one question — **what does a
programmer actually type?**

| Page | Its one job |
|---|---|
| [Getting started](01-getting-started.md) | Why Hicasso, then put one view on screen and take it down again |
| [Views and reads](02-views-and-reads.md) | Write a view; read subscriptions where you use them; write its attributes |
| [Events as data](03-events-as-data.md) | Put an event vector in an attribute and let the runtime build the callback — clicks, keys, prevention, and links |
| [Controlled inputs](04-controlled-inputs.md) | Type into a text field whose value round-trips through app-db, and keep your caret |
| [Interop](05-interop.md) | Use a React component from npm |
| [Theming](06-theming.md) | Theme with CSS tokens and app-db — without a context API (part maps for libraries are still open) |
| [Ephemeral state](07-ephemeral-state.md) | Decide where "is this dropdown open?" lives, given there is no `local`; where "it left but is still fading out" lives, given app-db cannot hold it; and where the jobs you wanted `:on-mount` for actually go |
| [Testing](08-testing.md) | Assert intents and trees as data today; know when you still need a browser |
| [When a view throws](09-when-a-view-throws.md) | Keep one broken view from taking the page down with it |
| [Server-side rendering](10-server-side-rendering.md) | Serve a page rendered from a db snapshot and adopt it live in the browser |

## How to read the markers

**[unfrozen]** after a name means the *behaviour* is fixed but the *spelling*
may still change. Prefer the behaviour over the token.

Each page ends with **Not settled yet** — short open questions for that page.
Not a design backlog; just what is still open for a reader of that topic.

## What this draft is not

- **Not published.** `design/hicasso/` is excluded from the MkDocs site; read it
  in the source tree or on GitHub. Banners are blockquotes, not admonitions.
- **Not an API freeze.** Names marked **[unfrozen]** may still change.
