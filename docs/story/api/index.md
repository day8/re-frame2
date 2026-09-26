# Story API reference

These pages record the exact forms of Story's public surface: every
registration macro and body key, every script step and assertion, and the run
options and result keys. The tutorial teaches how to use Story; come here when
you know what you need and want its exact shape.

| Page | What it covers |
|---|---|
| [Registration](registration.md) | The nine `reg-*` macros and their `*` functions, id shapes, every body key, global args and decorators, and the built-in decorators. |
| [Scripts](script.md) | The `:script` step grammar, plays, the `:rf.assert/*` assertions, `:cannot-run`, and the recorder functions. |
| [Runtime](runtime.md) | The execution verbs, the variant lifecycle, the registry queries, `configure!`, substrates and the shell. |
| [MCP surface](mcp-surface.md) | What Story exposes to Story-MCP: running the server, the tool list, what is redacted on the wire, and the write gate. |
| [Reference](reference.md) | Every public symbol, one table per namespace, for looking up a signature by name. |

## Namespaces

Require `re-frame.story`, conventionally as `rf.story`. It carries every
registration macro, the execution verbs, the programmatic runtime, the
registry queries, the recorder, `configure!`, the built-in decorator ids, the
shell-mount functions (ClojureScript only) and `variant-share-url`.

A few sub-namespaces are public, but an author rarely requires them:

| Namespace | Holds |
|---|---|
| `re-frame.story.recorder.play-export` | The DOM-aware recorder translator: `recording->script-body`, which the facade re-exports, plus `render-script-body` and `render-variant-form`. |
| `re-frame.story.ui.xray-embed` | The right rail's Xray component, `mount-fn-for` and `popout-full-shell!`. The shell calls these. |
| `re-frame.story.xray-preset` | The bridge that passes `:rf.story/project-root` on to Xray. |
| `re-frame.story.theme.*` | The design tokens (`typography`, `colors`, `motion`, `depth`, `glyphs`), for authors of Story panels. |
| `re-frame.story.ui.keybindings` | The shell's keyboard shortcuts and their installer. |
| `re-frame.story.ui.url-state` | The shell's address-bar encoding. Call `variant-share-url` instead. |

## Macros and `*` functions

Each `reg-*` macro has a `*` function partner, as `reg-view` does in
`re-frame.core`: `(reg-variant id body)` expands to `(reg-variant* id body)`.
Write the macro in a stories namespace. Call the `*` function from code that
builds registrations at runtime, such as a test fixture, a hot-reload tool or
the Story-MCP write tools.

## What is not here

ClojureScript makes some of the shell's internals reachable, but they are not
part of the public surface and can change without notice: the URL-state
helpers, the panel-mount functions, the late-bind shims and the
`re-frame.story.config` atoms. Implementors will find them in Story's
[API spec](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md).
