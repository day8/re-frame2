# Template — Substrate Variants

> Capability doc. The template ships two view-substrate variants; this
> file documents each, the invocation form, substrate coercion, and how
> a substrate is added.

## The variants

| Substrate | Default? | View library | Generated `core.cljs` shape |
|---|---|---|---|
| `:reagent` | yes | Reagent | `rf/frame-root` rendered through the adapter's `client-root` |
| `:uix` | no | UIx | `uix-adapter/frame-root` rendered through `uix.dom` |

Reagent is the default — the substrate every re-frame and re-frame2
example targets first. UIx is equally supported; the choice is the
developer's, surfaced via the `:substrate` top-level k/v argument.

`:substrate` is the template's **one and only selector**. Every value
of it emits the same twelve-file manifest
([002-Generated-Shape.md](002-Generated-Shape.md)); the value swaps
exactly three of those files.

## Invocation form

```bash
# Reagent — the default
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app

# UIx
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix
```

deps-new passes the args through to the template's `data-fn` directly
as a Clojure map — no `:edn-args` pass-through bag, no Mustache
nesting. `data-fn` reads `:substrate` off the data map and threads
the keyword through to `template-fn`'s `case` on the substrate.

## Substrate coercion

The template requires the substrate arg as a keyword. deps-new's
top-level k/v contract guarantees the value reaches `data-fn` as a
keyword, so the template does not accept stringified or symbol forms
— passing one is a registration error and throws with a clear
message naming the valid set:

```clojure
(coerce-substrate :uix)        ;; => :uix
(coerce-substrate :unknown)    ;; => throws — clear message
(coerce-substrate nil)         ;; => :reagent (default)
(coerce-substrate "uix")       ;; => throws — must be a keyword
(coerce-substrate 'uix)        ;; => throws — must be a keyword
```

Anything not in `#{:reagent :uix}` (or anything not a keyword) throws
an `ex-info` with the offending value and the set of valid substrates
in `ex-data`. The retired `:helix` and `:ui` values take the same path
as any other unknown keyword. See
[DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for the rationale.

## What each variant emits

Both variants emit the same project shape. The substrate choice swaps:

- `deps.edn` — the adapter coordinate (`day8/re-frame2-reagent` or
  `day8/re-frame2-uix`) and the view library (`reagent/reagent`, or
  `com.pitch/uix.core` + `com.pitch/uix.dom`). `day8/re-frame2` (core)
  rides both.
- `core.cljs` — the entry point, and the one place the two substrates
  differ on who owns the React root. Reagent holds an inert
  `reagent-adapter/client-root` handle and renders `[rf/frame-root …]`
  through `reagent-adapter/render!`: the adapter creates (or hydrates)
  the underlying React Root on that first render, reuses it across hot
  reloads, and releases it from `rf/destroy-adapter!` — direct
  `reagent.dom.client` construction is encapsulated, so the emitted
  scaffold never names it. UIx owns its root itself, with
  `uix.dom/create-root` + `render-root` around
  `($ uix-adapter/frame-root …)`. Both carry the same
  `^:dev/after-load mount!` hook and the same `init`.
- `views.cljs` — the counter view. Reagent uses `rf/reg-view` and
  hiccup; UIx uses `defui` with `$`, `use-subscribe` and `use-frame`.

The other nine files are substrate-invariant: `events.cljs`,
`subs.cljs`, `events_test.cljs`, `shadow-cljs.edn`, `package.json` and
`.gitignore` come from `_shared/`; `README.md`, `index.html` and
`app.css` are bulk-copied from `root/`. Their one per-substrate delta is
the display name, filled by the `{{substrate-label}}` substitution.

## The counter throughline

Every variant emits a working counter. The counter is the same
shape the developer reads about in:

- [Guide — app-db](../../../docs/core/app-db.md)
  — the friendly walkthrough.
- [`examples/core/counter/`](../../../examples/core/counter/) —
  the canonical Reagent counter.
- [`examples/substrates/uix/counter/`](../../../examples/substrates/uix/counter/) —
  the UIx counter.

What the template emits is what the guide walks through.

## Adding a substrate

A new substrate is a new VALUE of `:substrate` — never a second
argument, and never an authoring-model selector orthogonal to it. It
takes three edits in
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)
and the resource tree:

1. An entry in `substrate-registry` carrying its `:label`.
   `valid-substrates` is derived from that map.
2. A resource sub-tree at
   `resources/day8/re_frame2_template/_<substrate>/` holding its
   `deps.edn`, `core.cljs` and `views.cljs`.
3. One arm in `template-fn`'s `case`, naming that tree.

Then run the suite: the shape, static-parse and behavioural tests are
written per substrate, so the new value is exercised by adding it to
their substrate lists. The `_shared/` and `root/` trees are reused as
they are.

## Future substrates

Reserved, not implemented:

- **Hicasso** (`day8/re-frame2-hicasso`) — a third value of
  `:substrate`, gated on the repository's first `v*` tag so its
  coordinate resolves before a scaffold names it. Owned by rf2-8urba.
- **reagent-slim** — gated on reagent-slim's first published artefact.
- **A TypeScript port.** Per Spec 000 — re-frame2 is a pattern, not a
  CLJS library. A `create-re-frame2-app` style npm template is
  reserved for a future iteration.
