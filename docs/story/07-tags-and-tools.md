# Tags and shell tools

Tags sort a growing variant library: the sidebar's tag filter narrows the tree
to the variants you care about, and the `:test` tag decides which variants the
test suite runs. This page also covers the shell's working tools: the Dispatch
console, Inspect, and opening source in your editor.

## Tags

Story registers seven inclusion tags, `:dev`, `:docs`, `:test`, `:screenshot`,
`:experimental`, `:internal` and `:agent`, and five size tags,
`:state/empty`, `:state/small`, `:state/medium`, `:state/large` and
`:state/special`, which say how much data a variant's state holds. The shell
acts on one of them: a `:test` variant joins the sidebar's Tests widget
([chapter 4](04-the-variant-is-a-test.md#the-tests-widget)).

Any other tag must be registered before a variant uses it, or registration
throws `:rf.error/unknown-tag`:

```clojure
(rf.story/reg-tag :status/beta
  {:doc  "Shipped behind a flag."
   :axis :status})
```

The sidebar's tag filter groups tags by `:axis`, one row per axis, with the
tags that have none in a row headed OTHER. Selecting tags narrows the tree to
variants carrying any of them; selecting none shows everything. The convention
is to name a tag after its axis, as in `:status/beta`, `:team/checkout` or
`:feature/payments`. A tag registered with `:default-filter :exclude` hides
its variants from the sidebar until you select it in the filter, which suits
variants most people do not need to see.

A variant that declares no `:tags` takes its story's. A variant that
`:extends` another unions its tags with its parent's. To drop an inherited
tag, write it with a `!`: `#{:!dev}` removes `:dev`, and the marker itself
never shows up as a tag.

## The Dispatch console

**Dispatch** in the toolbar opens a console in the right rail for the selected
variant. Type an event id, which autocompletes from the registered events, and
an EDN payload, then press **Dispatch** or **Dispatch-sync**; the event lands in
the variant's frame exactly as the app's own would. When the event requires
coeffects, the console names them and takes them in a `cofx` field. A history
of the last 20 dispatches per variant, kept in the browser, replays any one of
them on a click. A story or variant with `:dispatch-console? true` opens the
console by default.

## Inspect and open in editor

**Inspect** turns on a picker: hover the canvas to highlight elements, and
click one to open the source of the view that rendered it. The **open** link
beside the variant's title opens its registration. Both open your editor, and
two `configure!` keys point them at it:

```clojure
(rf.story/configure! {:rf.story/editor       :cursor   ; :vscode (default), :cursor, :idea, or {:custom "<template>"}
                      :rf.story/project-root "/path/to/my-app"})
```

`:rf.story/project-root` is the directory that source paths are relative to;
Xray's source links use it too.
