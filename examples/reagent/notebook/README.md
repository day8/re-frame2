# notebook — Reagent design-led example

A three-pane editorial layout: documents tree, markdown editor, live
preview. Proves re-frame2 + Reagent can build a substantive UI, not
just a counter.

## What this demonstrates

- **`reg-view` at every layer** — the whole UI is registered views.
  Layout, document list, editor, preview, document tree row — each is
  its own `reg-view` Var, individually inspectable.
- **Six-dominoes dataflow at a non-trivial scale** —
  selecting a document dispatches `[:notebook/select id]`; editing the
  body dispatches `[:notebook/edit-body text]`; the body sub derives
  parsed HTML from the markdown; the preview pane subscribes to that
  derivation. The same six dominoes that drive the counter, just
  more of them.
- **Pure CLJS markdown parser** — headings, bold, italic, links,
  paragraphs, lists in a tiny pure-CLJS parser. Keeps the bundle
  small and the example free of an extra npm dependency.
- **Shared visual identity** — "Editorial Warm" from
  [`examples/_shared/css/style.css`](../../_shared/css/style.css),
  one identity across all three substrates.

## Why this shape

Design-led examples exist to prove polished visuals + interaction,
not to replay platform features other examples already cover — no
state machines, no HTTP, no routing. The Reagent member of the
three-substrate design-led trio:

| Substrate | Example | Shape |
|---|---|---|
| Reagent | `notebook` (this) | Three-pane editor |
| UIx | [`dashboard_uix`](../../uix/dashboard_uix/) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../helix/process_monitor_helix/) | Terminal log viewer |

Three different substantive UIs, one per substrate; same shared
identity. Use the trio to see how each substrate's idiom feels at
non-trivial scale.

Substrate: **stock Reagent** (not reagent-slim) — keeps the trio on
the reference substrate for each adapter.

## Files

```
notebook/
  core.cljs    — events, subs, markdown parser, views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/notebook
```

The watch build emits `main.js` into `out/examples/notebook/`; copy
this folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/notebook/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free
per [`examples/README.md`](../../README.md).

## Cross-references

- [`spec/004-Views.md` §`reg-view`](../../../spec/004-Views.md) — the registered-view convention.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the Reagent reactive substrate.
- [`examples/uix/dashboard_uix/`](../../uix/dashboard_uix/) + [`examples/helix/process_monitor_helix/`](../../helix/process_monitor_helix/) — the other two design-led trio members.
