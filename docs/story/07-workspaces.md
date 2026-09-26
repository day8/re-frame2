# Workspaces

A workspace shows several variants at once: a grid of states for review, a
strip of tabs, or a page of prose with live variants between the paragraphs.
[Chapter 2](02-every-state-side-by-side.md#workspaces) introduced the explicit
grid. This page covers every layout.

Each `:layout` needs its own slot:

| Layout | Needs | Use it when |
|---|---|---|
| `:grid` | `:variants` | You want explicit variants in a specific order. |
| `:variants-grid` | nothing, `:for` or `:variants` | You want every variant under a story parent, or a curated list of them. |
| `:tabs` | `:variants` | You want one variant visible at a time. |
| `:prose` | `:content` | You want prose interleaved with rendered variants. |

A missing slot, or a slot the body does not accept, throws
`:rf.error/workspace-shape` at registration. Most projects start with `:grid`
and `:variants-grid`, and add `:tabs` or `:prose` when a component or workflow
needs real documentation.

## Grid

```clojure
(rf.story/reg-workspace :Workspace.login/all-states
  {:doc      "The five login states side by side."
   :layout   :grid
   :variants [:story.login/idle
              :story.login/submitting
              :story.login/error
              :story.login/submitting-retry
              :story.login/authenticated]
   :columns  3})
```

`:columns` fixes the column count of a `:grid` or `:variants-grid`; without it
the grid fits as many columns as the width allows. A view wider than its cell
scrolls inside the cell. A grid renders its first 100 cells and offers
**+N more** for the rest, up to 400.

## Variants grid

`:variants-grid` lists every variant under a parent story for you:

```clojure
(rf.story/reg-workspace :Workspace.login/auto-grid
  {:layout  :variants-grid
   :for     :story.login
   :columns 3})
```

Use an explicit grid when the order is part of the story you want to tell. Use
`:variants-grid` when you want every variant under a parent to appear without
maintaining the list by hand. The cells come in variant-id order. `:for` names
the story; without it, the workspace id does, so `:Workspace.login/auto-grid`
enumerates `:story.login`. Give it `:variants` instead of `:for`, and it
renders exactly those variants.

A `:variants-grid` mounts every cell at once, each in its own frame. A view
that creates its own frame provider internally defeats that, because its cells
end up sharing state. For such a view, `:isolation :shared` mounts one cell at
a time, with previous and next buttons to move between them.

## Tabs

`:tabs` mounts one variant at a time, under a strip of tabs:

```clojure
(rf.story/reg-workspace :Workspace.login/tabs
  {:layout   :tabs
   :variants [:story.login/idle :story.login/error]})
```

## Prose

`:prose` interleaves Markdown with variants, in order:

```clojure
(rf.story/reg-workspace :Workspace.login/guide
  {:layout  :prose
   :content [{:type :prose   :body "## Signing in\nThe form starts empty."}
             {:type :variant :id   :story.login/idle}
             {:type :prose   :body "A rejected password re-enables the form."}
             {:type :variant :id   :story.login/error}]})
```

A variant's [Docs mode](07-docs-mode.md) page also shows the prose of any
`:prose` workspace that includes it.

## What the cells share

Each cell's frame has its own app-db, event queue, subscription cache and
epoch history, while every frame runs the same registered handlers
([What a frame is](../core/frames.md#what-a-frame-is)). If a cell dispatches
an event, it changes that cell's frame and no other.

A frame isolates state, not the page. Every cell renders into the one page
that hosts the shell:

- that page's stylesheets reach every cell;
- only one element on the page can hold focus;
- a modal that a view portals into `document.body` lands on the shared page,
  outside its cell.

Text colour and font are reset at the cell boundary, so a view renders in the
browser's default text styles rather than the shell's. A view that relies on
text styles inherited from its app's shell must set them itself, for example
in a [decorator](07-decorators.md).

Storybook renders stories in a preview iframe, although its docs pages can
render them inline in the page itself. Story's canvas and workspaces have no
iframe mode, so a job such as checking that a design system's CSS holds up
without the host page's stylesheets around it needs a tool that provides an
iframe boundary.

The background picker's colour sits behind each cell's view as well as behind
the canvas ([Modes, viewports and backgrounds](07-modes-and-viewports.md#viewports-and-backgrounds)).
