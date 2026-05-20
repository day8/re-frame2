# 4. Workspaces + args editor

> **What you'll build.** Two workspaces over your counter variants — a hand-listed `:grid` with four explicit cells, and an auto-enumerating `:variants-grid` that picks up every variant under `:story.counter` without further authoring. You'll also wire one `:Mode.app/dark` saved-tuple Mode and watch the workspace re-render through the chrome's theme toggle.
>
> **You should have working before you start.** Chapters 1 and 3 finished. You want at least three variants under one parent story so the workspace has something to lay out. Counter at zero, counter at seven, counter clicked three times — that's plenty.

So you've got a fistful of variants now. The sidebar's getting long. You realise that what you actually want is not to flip between them — you want to *see them at the same time*. Side-by-side, on one page, four cells in a 2×2 grid, so the design lead can squint at the whole set and tell you which one looks wrong.

This is the gesture component playgrounds have been working towards since Bret Victor's *Ladder of Abstraction*, and Storybook's `variants-grid` add-on figured out the rough UX years ago. A workspace is a layout that arranges variants on one page. We ship five layouts; two of them carry most of the workload.

> 📸 **Screenshot needed**: a 2×2 workspace mounting four variant states (e.g. `:empty`, `:loaded`, `:clicked-three-times`, `:save-stubbed`). Annotate (1) the workspace title bar with the layout selector, (2) each cell labelled with its variant id, (3) the per-cell args-override hint (small chip near the cell), (4) the *share this layout* button.
>
> Save as: `/docs/images/story/04-workspace-grid.png`

## The two shapes you'll use 90% of the time

### `:grid` — explicit list of variants

You name the variants, in the order you want them, in a grid:

```clojure
(story/reg-workspace :Workspace.counter/all-states
  {:doc      "Every named counter state, side-by-side."
   :layout   :grid
   :variants [:story.counter/empty
              :story.counter/loaded
              :story.counter/clicked-three-times
              :story.counter/save-stubbed]
   :columns  2
   :tags     #{:docs}})
```

The runtime allocates one frame per cell. Four cells = four frames, all independent. The `:empty` frame can't see the `:loaded` frame's `app-db`; the `:save-stubbed` frame's `force-fx-stub` decorator doesn't bleed into the `:clicked-three-times` frame. (We've laboured this point a few times already; we'll keep labouring it because the frame isolation is what makes the rest of this work.)

The `:grid` layout is the right reach when you have a curated set you want laid out in a particular order. Marketing screenshots; design-review canvases; the "four states the product manager cares about" view.

### `:variants-grid` — auto-enumerated

Sometimes you don't want to list variants by hand because the list changes. You add a new variant, you want it in the grid:

```clojure
(story/reg-workspace :Workspace.counter/auto-grid
  {:doc     "Every variant under :story.counter, auto-enumerated."
   :layout  :variants-grid
   :for     :story.counter
   :columns 2
   :tags    #{:docs}})
```

Now the workspace renders *every* variant under `:story.counter`. New variants land here without touching the workspace. Variants you delete vanish from the grid. The workspace is *layout-only*; it carries no state of its own.

This is the layout you'll use for the "show me everything" view. It's also the right shape for a `:test`-tagged workspace that lets the CI runner enumerate every variant in one place.

Three less-common layouts also ship:

- `:stack` — vertical stack of cells; useful when each variant is wide.
- `:tabs` — one cell per tab; useful when the variants are themselves long-scrolling and you don't want a 4×scroll page.
- `:custom` — pass a hiccup tree with `(story/variant-cell <id>)` placeholders. The escape hatch for "I have a specific layout in mind."
- `:prose` — alternating prose blocks and variant cells; this is the Story-side of Storybook's MDX surface (we mentioned it in chapter 2's Docs section).

## Per-cell args + modes

A subtle but useful trick: each variant cell in a workspace can carry **per-cell overrides** — args that apply only to *this* cell. So the same `:story.counter/loaded` variant can render with `{:n 7}` in one cell and `{:n 99}` in another:

```clojure
(story/reg-workspace :Workspace.counter/big-numbers
  {:layout  :grid
   :columns 3
   :cells   [{:variant :story.counter/loaded :args {:n 1}}
             {:variant :story.counter/loaded :args {:n 50}}
             {:variant :story.counter/loaded :args {:n 999}}]})
```

Three cells; same variant; three different `:n` values. The runtime allocates a separate frame per cell — three loaded-cells = three frames, fully independent. Per-cell time-travel (chapter 6) is per-frame, so you can rewind one cell without affecting the other two.

When would you reach for this instead of three separate variants? Mostly when the variants only differ in their args — when you're really showing the *same scenario at three magnitudes*. If they differ in their events, decorators, or play-scripts, those are properly separate variants. If they only differ in their inputs, one variant in three cells is the cleaner shape.

## Modes — Chromatic-style saved tuples

A **Mode** is a saved bundle of args that any variant can render against:

```clojure
(story/reg-mode :Mode.app/dark
  {:doc  "Dark theme."
   :axis :theme
   :args {:theme :dark :background "#1e1e1e" :foreground "#e0e0e0"}})

(story/reg-mode :Mode.app/light
  {:doc  "Light theme — the default."
   :axis :theme
   :args {:theme :light :background "#ffffff" :foreground "#1a1a1a"}})
```

When a variant renders against `:Mode.app/dark`, the mode's `:args` deep-merge into the variant's effective args between the global layer and the story layer (consult the four-layer chain we sketched in chapter 1; modes slot in *between* global and story).

The `:axis` slot is a faceting hint. Modes with `:axis :theme` are mutually exclusive — toggling `:Mode.app/sepia` deactivates `:Mode.app/dark` automatically. Modes without an axis (or with different axes) compose freely. So `{:Mode.app/dark, :Mode.viewport/mobile, :Mode.locale/ja-JP}` is a coherent active mode-set — three different axes, one mode active per axis.

The chrome's toolbar exposes the registered modes; clicking the chip toggles. Snapshot identity (chapter 5) tracks the mode-set, so visual-regression diffs key off `(variant × mode-set)`.

Why is this called *Modes* rather than something domain-specific like *themes*? Because modes pivot any axis. *Dark* and *Light* are themes, but `:Mode.viewport/mobile` is a viewport mode, `:Mode.locale/ja-JP` is a locale mode, `:Mode.user/impersonating-admin` is a user-context mode. The primitive doesn't care what you're pivoting; you give it args, you give it an axis, the chrome gives you a chip. Generality earns its keep here.

## The args editor

In the right-side controls panel, every resolved arg gets an editor row. The editor row is auto-derived from the registered schema (or the `:argtypes` fallback for cases where the schema doesn't carry enough hints). Editing a value dispatches a `:rf.story/cell-override` event that adds a cell-local override; the canvas re-renders against the new effective args.

The editor row honours the schema's constraints. An `:int {:min 0 :max 99}` is a number-input clamped 0–99. An `:enum [:a :b :c]` is a radio group. A `:map` is a nested editor (with the path visible in the header so you don't lose track of where you are). A `:string` is a text input. A `:keyword` becomes a select if there's an enum hint, a text input otherwise. We didn't have to negotiate any of these mappings — the schema's already there.

The cell-overrides are *cell-local* — clicking on a different variant or workspace cell discards them. The point of cell-overrides is *experimentation*, not authoring. If you want the override to stick, click *save as new variant…* (we covered this in chapter 3) and the controls panel emits a `reg-variant` form with the current effective args baked in. Paste; commit; the experiment is now source.

## Sharing a workspace state

The (workspace + active modes + cell-overrides) tuple is **transit-shareable**. The *share this layout* button serialises the picked state into a URL fragment; whoever opens the URL sees the same grid in the same state. The URL is small; the embedding is forgiving.

This is the v1 sharing primitive — it's what lets you paste "look at this grid" into a chat message or an issue, and the reader sees exactly what you see. For the larger / off-network case, the QR-code share affordance (next chapter) does the same thing via a render-to-image step, scannable from a phone.

We didn't have to build a backend for this; the variant body's an EDN map, the active mode-set is a small set, the cell-overrides are a small map. Encode the lot in the URL fragment, you're done. The principle is: *if it's all data, you can share it as data.* re-frame2's bet pays off again — Storybook's per-cell state involves React render-tree introspection and somewhat ad-hoc serialisation; ours involves `pr-str`.

## Workspaces are layouts, not state

A workspace doesn't hold runtime state. It's just an ordered list of `(variant + per-cell args)` tuples. Consequences:

- New variant added under `:story.counter` → a `:variants-grid` workspace picks it up immediately.
- Workspace renamed → all the sidebar links auto-update; URL fragments break (this is correct: rename invalidates short-link URLs you shared, because the new name *is* a different thing).
- A variant deletion that leaves a workspace with a dangling reference → the cell renders a "this variant no longer exists" placeholder; the rest of the workspace renders normally. Story doesn't blow up the page because one cell's broken.

The contract is "workspaces are layouts over variants"; everything follows from that.

## You should now see

After working through this chapter:

- A `:Workspace.counter/all-states` entry in the sidebar; clicking it shows your four named counter states in a 2×2 grid.
- A `:Workspace.counter/auto-grid` entry that auto-enumerates every variant under `:story.counter`; adding a new variant lands it in the grid without touching the workspace registration.
- A `:Mode.app/dark` chip in the toolbar; toggling it re-renders the active variant or workspace with the dark args merged in.
- The controls panel shows editor rows for the variant's resolved args; editing a value re-renders the canvas live.
- The *share this layout* button copies a URL that round-trips the picked state.

## When it doesn't work

- **The workspace shows up empty.** A `:variants-grid` workspace pointed at a parent story with zero registered variants is empty by construction. Check the parent story id is correct and that the variants registered (look in the sidebar; they should appear under the parent).

- **Per-cell args don't seem to apply.** The runtime allocates a fresh frame per cell, so per-cell overrides should isolate cleanly. If you see contamination across cells, you've probably got a global side-effect somewhere — a sub that reaches into a different frame, a fx-handler that mutates module state, a Reagent ratom outside the frame's `app-db`. Story's frame isolation can't protect you from genuinely shared mutable state outside the frame; that's an architecture issue in the under-test code.

- **A mode toggle doesn't repaint the canvas.** The mode's `:args` aren't reaching your view. Check that your view reads from args (not from `app-db` directly for the mode-relevant values). Modes pivot the *args* axis; if the view's reading `:theme` from `app-db`, the mode toggle doesn't touch it.

- **Cell-overrides disappear when you click around.** They're meant to. Cell-overrides are intentionally ephemeral — for persisting changes, use *save as new variant…* or edit the `:args` slot in source.

- **The args editor shows no rows for a variant's args.** No schema and no `:argtypes`; the editor can't infer widgets. Add a `reg-view` schema or fall back to `:argtypes {:foo {:control :text}}` for the args you want editable.

## Where we go next

Chapter 5 covers **snapshot identity** — the content-hash that visual-regression services key off so you can rename variants freely without losing baselines. It's a story we couldn't have told in Storybook because Storybook's identity is slug-based; ours is content-based, and the difference matters when you're three months into a project and your design team wants to clean up everyone's names.

Next: [snapshot identity + QR sharing](05-snapshot-identity.md).
