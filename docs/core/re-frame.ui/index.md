# re-frame.ui

`re-frame.ui` is the first-party **compiled-view substrate**. Where the
[Reagent, reagent-slim, and UIx adapters](../how-to/use-uix-or-slim.md)
interpret your hiccup at runtime, `re-frame.ui` reads your templates at *compile*
time and lowers them to direct React construction in the browser and a versioned
structural tree on the JVM — there is no hiccup interpreter in the production
bundle. Views are written with one macro, `defview`; subscriptions read as plain
values; and the frame a view runs under is explicit rather than ambient magic.

> **Same pipeline, a compiled view layer.**

This section teaches you how to *build views* with it. It is a guide, not a
catalogue — for the exact shape of every function the
[`re-frame.ui` API reference](../../api/re-frame.ui.md) is the complete surface,
and the [Install re-frame.ui and configure Shadow](../how-to/install-re-frame-ui.md)
recipe is the one-time setup.

Pairing with an AI agent to write views? The
[re-frame2-ui skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-ui)
is the canonical agent-facing authoring guide — teaching prose over the valid
`defview` forms, the state/subscription/event/effect decision table, and the
foreign-boundary rules, with the full compile-rejection roster behind it as an
on-demand reference. The roster is generated from the compiler's own error
messages, so it never drifts from what the compiler actually enforces.

!!! warning "re-frame.ui is experimental"

    It is pre-alpha, delivered in staged slices, and some surfaces are still
    landing. The Maven coordinates aren't on Clojars yet. Treat it as a track you
    can *trial* today, not one to bet a shipping product on. The
    [adapters](../how-to/use-uix-or-slim.md) are the stable path.

## What stays the same

The important thing first: `re-frame.ui` changes only the **view layer**.
Everything upstream of the view — [events](../events.md), [app-db](../app-db.md),
[subscriptions](../subscriptions.md), [effects](../effects.md),
[frames](../frames.md) — is the *same* re-frame2 you already know. You register
events with `rf/reg-event`, derive values with `rf/reg-sub`, and reason about the
[event pipeline](../introduction.md) exactly as before. A `defview` reads those
subscriptions and dispatches those events; it just does so as compiled code
rather than an interpreted tree.

So this is not a new framework. It is a different way to spell the last stage of
the one you're already using.

## When to use it

`re-frame.ui` is an **opt-in, additional** view substrate — offered *alongside*
the adapters, never as a mandated replacement. **Reagent, reagent-slim, and UIx
remain first-class and actively supported.** You require `re-frame.ui`
explicitly and install its adapter at boot; nothing pulls it in for you.

Reach for it when you specifically want:

- **Compiled views** — no runtime hiccup walker, no wrapper components, no
  per-render interpretation. What ships is React construction the compiler wrote.
- **Value-comparing memo by default** — every view is memoized on its props, and
  ClojureScript data compares by value, so a parent re-render doesn't re-run a
  child whose inputs didn't actually change. No manual `React.memo`, no deps
  arrays.
- **A view layer designed around the re-frame2 model** — frames carried
  explicitly, [events as data](mental-model.md#handlers-are-data), subscriptions
  as the one reactive grammar, no second state model (no ratoms, cursors, or
  reactions).
- **Headless-testable views** — a compiled view renders to a structural tree on
  the JVM, so you can assert on it without a browser.

Stay on the adapters when you need stability now, when you have an existing
Reagent or re-com application (a migration wave lands later), or when you'd
rather not ride pre-alpha churn. Choosing an adapter is a supported, first-class
choice — not a consolation prize.

## This section

Read the first three in order — they teach the model on a growing counter. The rest
are depth: open each when the need appears.

| Page | What it covers |
|---|---|
| [Mental model](mental-model.md) | The three shifts from Reagent: views compile, `defview` is the one form, and `(sub …)` reads as a value. |
| [Build a view](build-a-view.md) | A worked walkthrough — `defview`, `sub`, an event, local state, and the mount — built up one step at a time. |
| [Reactivity and ownership](reactivity-and-ownership.md) | How a compiled view stays reactive, what re-computes when, and why subscriptions never leak. |
| [State: the three inputs](state.md) | `sub`, props, and `local` — the whole state surface, and where each value belongs. |
| [Events and handlers](events-and-handlers.md) | Handlers as data, the placeholder vocabulary, controlled inputs, and when to escape to `ui/event` / `ui/handler`. |
| [Custom elements](custom-elements.md) | Web components in templates — the property-vs-attribute declaration. |
| [Presence: exit animations](presence.md) | `ui/presence` — bounded enter/exit retention, DOM-agnostic, your CSS animates. |
| [Testing with ui.test](testing.md) | Headless-first view tests on the JVM, mounted tests when the DOM is the point. |
| [SSR and hydration](ssr.md) | The same views on the server: roots, hydration, the phase flip, `render-static`. |
| [Interop and the closed grammar](interop-and-limits.md) | Foreign React, the compile-time walls and their escapes, and when *not* to use this substrate. |

New to re-frame2 entirely? Read the [introduction](../introduction.md) and the
concept pages ([events](../events.md) through [views](../views.md)) first — they
teach the pipeline this substrate plugs into, using the default adapter. Then
come back here for the compiled-view spelling.
