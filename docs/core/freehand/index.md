# Freehand

Freehand is re-frame2’s native view layer — it does the same job as Reagent or
UIx. Turn events, app-db, and subscriptions into something on the screen. It is
not a second framework. Upstream re-frame2 stays as it is; Freehand only spells
the **last mile**.

That last mile is **re-frame-native**. Reagent and UIx are excellent React tools
that *can* host re-frame. Freehand is a view contract that **assumes** re-frame —
so state, intent, tests, and tools stay in one system.

> **Same re-frame pipeline. A view layer designed for it.**

```clojure
(v/defview cart-badge [_]
  [:button {:on-click [:cart/open]}
   "Cart (" (v/sub [:cart/count]) ")"])
```

No `@`, no reaction object, no dispatch closure on the paved path — and “what does
this button do?” is a value you can assert on the JVM.

!!! warning "Pre-alpha"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**. You resolve it with `:local/root` from a checkout — see
    [Install](get-running/install.md). The public surface is deliberately still open: verbs
    can change while we learn from real apps.

New to re-frame2? Learn events, app-db, subscriptions, and frames first. Freehand
only changes the **view layer**.

## Peer of Reagent and UIx

```text
                 re-frame2 (events, app-db, subs, fx, frames)
                                   |
       ----------------------------+----------------------------
       |                           |                           |
  Reagent adapter              Freehand                   UIx adapter
```

All three fill the **view-layer** slot. Freehand is the re-frame-native choice;
adapters stay permanent first-class siblings — not a fallback and not scheduled
for deletion.

| Term | Meaning |
|---|---|
| **View layer** | What you write for the screen (this guide’s default word) |
| **Adapter / substrate** | How a view layer plugs into re-frame2 (mount, frame, host) |

## What stays the same

Everything upstream of the view is ordinary re-frame2: events, app-db,
subscriptions, effects, frames, resources, machines. You still use `rf/reg-event`
and `rf/reg-sub`. A Freehand view *consumes* those subscriptions and *dispatches*
those events. It does not invent a second reactive model.

## Why teams pick Freehand

| Payoff | In practice |
|---|---|
| **Intent as data** | `[:button {:on-click [:cart/add id]} "Add"]` — readable, tool-visible, assertable without a click |
| **Subs without ceremony** | `(v/sub [:cart/count])` **is** the value; Freehand owns the dependency |
| **One place for app state** | No ratoms/hooks for product truth; host machinery stays behind boundaries |
| **Tests match the model** | Structural tree on the JVM; browser only for real browser laws |
| **Tools and AI** | Handlers and structure are values; build-time findings carry source coordinates |
| **Perf later, same views** | Interpreted by default; `{:compiled true}` on the declaration when a boundary is hot |

Outside a view render, one-shot reads use `rf/subscribe-once` — deliberately not
`v/sub`, so reactive reads and probes stay distinct.

## Who it is for

| If you are… | Freehand helps because… |
|---|---|
| Building a **re-frame2 app** | The view matches events, subs, and frames |
| Serious about **UI tests** | Intent can be equality on a tree, not only E2E |
| Shipping **reusable controls** | Props-only leaves, clear event prefixes, optional field protocols |
| Using **AI assistants** heavily | Small paved vocabulary, data handlers, checkable findings |

## When another view layer is better

Reagent and UIx remain first-class. Prefer them when:

- you need a **published coordinate today** — Freehand is pre-alpha and resolves
  from a checkout, while the adapters ship as ordinary Maven artefacts;
- you have a large **Reagent / re-com** investment and are not migrating yet;
- the product is **mostly React**, with re-frame as a thin data layer;
- you want a **whole-root, subscription-free** renderer (closer to Replicant).

Choosing another layer is a supported choice, not a failure. Freehand is the
**re-frame-native** bet when you want the UI inside that architecture. Incremental
landing next to an existing app is under [Adoption](operate/adoption.md); a Reagent delta
map is under [Coming from Reagent](operate/vs-reagent.md).

## Shape at a glance

| Idea | Meaning |
|---|---|
| `v/defview` | one way to declare a mounted boundary |
| `[view props]` vs `(helper …)` | vector = boundary; parens = inline helper |
| `(v/sub …)` | plain value inside a render; render-only |
| Event vectors | paved-path handlers; `::v/value` and friends fill live scalars |
| Interpreted by default | full Clojure; promote with `{:compiled true}` only when measured |
| Host edge | `v/defhost` (React in), behaviors (imperative node), `v/->react` (Freehand out) |
| Roots | `v/mount`; the root owns or scopes its frame |

Call sites and structural tests do not change when you compile a declaration.
Freehand never auto-promotes or silently demotes.

## This guide

The left nav is the path. **Install → mental model → build a view**, then authoring
(events, state, forms), then the host edge only when you leave pure Freehand DOM.
Semantic controllers and compilation are **rare**; skip them until you need them.
Operate (test, debug, adopt) when you ship.
