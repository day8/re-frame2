# Modes, viewports and backgrounds

A toolbar mode renders every variant under a different set of args, such as a
dark theme or another locale. The viewport and background pickers change the
canvas around the variant instead. None of these edits a variant's source.

Three things in Story are called modes:

- **mode tabs** are Canvas, Docs and Tests above the canvas, and change how the
  selected variant is presented;
- **toolbar modes** are registered sets of args, and change the args the
  variant renders with;
- the **viewport** and **background** pickers change the canvas's size and
  colour, and are not registered with `reg-mode`.

## Toolbar modes

A toolbar mode is a named set of args:

```clojure
(rf.story/reg-mode :Mode.app/light
  {:doc  "Light theme."
   :axis :theme
   :args {:theme :light}})

(rf.story/reg-mode :Mode.app/dark
  {:doc  "Dark theme."
   :axis :theme
   :args {:theme :dark}})
```

Modes with the same `:axis` are mutually exclusive. Turning on dark turns off
light. The active mode args sit in the args precedence chain above the story's
args and below the variant's:

```text
global < story < mode < variant < live control override
```

That is Storybook's globals, written as registered data.

The toolbar's Modes cluster shows one labelled group per `:axis`, where
choosing a mode deselects the others in its group. Modes without an `:axis`
sit together after the groups, and any number of them can be on at once;
when two active modes set the same arg, the later one wins. **reset** appears
while any mode is active and turns them all off. The active modes persist in
the browser across reloads, and they apply to every variant and workspace
cell.

A mode's args join the variant's effective args, so the view receives them like
any other arg, and a `:hiccup` decorator's `:wrap` function receives them as its
second argument, which is where a theme wrapper reads them
([Decorators](07-decorators.md)). A test can run a variant under a mode by
passing it:

```clojure
(rf.story/is :story.login/error {:active-modes [:Mode.app/dark]})
```

## Viewports and backgrounds

The View cluster's two pickers resize the canvas and change what sits behind
it, and the background also sits behind each view in a workspace's cells. The
viewport presets are Full, Mobile portrait (375×667), Mobile landscape
(667×375), Tablet (768×1024), Desktop (1280×800) and Desktop wide (1920×1080),
plus a custom width and height. The background presets are Light (`#ffffff`),
Dark (`#1a1a1a`), Paper (`#f9f9f9`), Midnight (`#0a0a0a`) and Transparent, a
checkerboard, plus a custom colour.

A story or variant can fix either one:

```clojure
(rf.story/reg-variant :story.login/phone
  {:extends    :story.login/idle
   :viewport   :mobile-portrait        ; or {:width 390 :height 844}
   :background :paper})                ; or a CSS colour such as "#fafafa"
```

The preset ids are `:full`, `:mobile-portrait`, `:mobile-landscape`, `:tablet`,
`:desktop` and `:desktop-wide` for the viewport, and `:light`, `:dark`,
`:paper`, `:midnight` and `:transparent` for the background. A body's value
beats the toolbar's choice, so the picker has no effect on a variant that fixes
its own. An id Story does not know falls back to Full, or to Light.
