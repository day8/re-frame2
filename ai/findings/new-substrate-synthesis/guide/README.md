# The re-frame2 UI Guide

re-frame2 UI is the view layer for re-frame2: views are hiccup, handlers are event
vectors, and the compiler ships code with no interpreter, no hooks ceremony, and no manual
memoization. This guide teaches the library as a user; design rationale lives one
directory up. Every example on these pages compiles and runs as a CI fixture — if a guide
example needs internals explained, that's an API bug, not a documentation problem.

| Chapter | You'll learn |
|---|---|
| [01 — Getting started](01-getting-started.md) | Install, mount, first interactive view, first headless test |
| [02 — Views](02-views.md) | `defview`, templates, props, children, lists, presence (exit animations), custom elements |
| [03 — State](03-state.md) | `sub` (including conditional reads), `local`, `effect`, `lease` |
| [04 — Events](04-events.md) | Event vectors, placeholders, `ui/event`, forms & controlled inputs, the callback decision table |
| [05 — Frames](05-frames.md) | `frame-root`, `frame-provider`, multi-frame pages, holds |
| [06 — Debugging](06-debugging.md) | Render causes, the interaction surface, Xray navigation |
| [07 — Performance](07-performance.md) | What you never do, the little you do |
| [08 — Server rendering](08-ssr.md) | JVM rendering, roots and frames, root identity, the host tier, static output |
| [09 — Testing](09-testing.md) | Headless view tests, selectors, `flush!`, Story |
| [10 — A worked app](10-worked-app.md) | The counter grown into a dashboard: state shape, tiles, narrow subs, a live tile, tests |

**Stage honesty.** The library ships in stages (S1–S7). Everything unmarked on these
pages is shipped on main today: the Stage-1 compiler slice — compiled templates, props,
roots and mounting, `ui/raw` / `ui/html` / `ui/spread`, the Tier-1 test core — plus the
landed Stage-2 reactive core — `sub`-driven repaints, Tier-1 `sub` reads, `frame-root`'s
runtime ENSURE preflight, the `ui/adapter` you hand `rf/init!`, and the mounted Tier-3
test surface (`with-root`, `query`, `flush!`). A compact marker like
*(lands S3 — committed handlers)* flags a surface whose contract is final but whose
implementation lands in a later stage; the spelling and semantics shown are the ruled
contract either way. *(lands S2)* now marks only the S2 remainder still to land —
`lease` and the `(frame)` ops map. Wave-2 names (`ui/element`, `ui/view`, `ui/portal`,
`re-frame.ui.data/render`) are not v1 and only ever appear with that qualifier.

**Coming from Reagent?** Your hiccup transfers whole. Reads are `(sub [:q])` — a value,
nothing to deref. Handlers are usually vectors, not closures. One component form, no
ratoms: app state lives in app-db, keystroke ephemera in `local`.

**Coming from React / UIx / Helix?** Components, memo, context, and external stores are
all here — the compiler writes them. You will not write a hook, a deps array, a
`useCallback`, or a `memo` wrapper again. (And unlike hooks, `sub` may sit in a branch.)
