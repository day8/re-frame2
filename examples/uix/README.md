# UIx — examples

These examples run re-frame2 on **UIx**, one of the [substrates](../../docs/guide/glossary.md#substrate) it can render through. They make one point: your dataflow doesn't care which substrate you pick.

UIx renders through React hooks, not Reagent's reactive atoms. But it plugs into the *same* `re-frame.adapter.context` React Context that the Reagent [adapter](../../docs/guide/glossary.md#adapter) exposes (Decision 2). A single app could even mix the two — though the sane default is to pick one substrate and stay with it.

This directory is **not** a 1:1 mirror of the Reagent set, and that's on purpose. UIx is a non-canonical adapter, so it doesn't re-prove the whole catalogue. It ships a representative **curated example subset** instead. For UIx that subset is **counter + login**. These two share their dataflow verbatim with their Reagent siblings — the same [events](../../docs/guide/glossary.md#event), [subscriptions](../../docs/guide/glossary.md#subscription), [schemas](../../docs/guide/glossary.md#schema), [machine](../../docs/machines/glossary.md#machine), and managed-HTTP stub. That makes them the load-bearing pair: run the same events and subs through `defui` components and you've shown the UIx adapter honours the substrate contract. (The Reagent realworld scaffold is thick with Reagent idioms, so it waits until a UIx user asks for it.)

Alongside the curated pair sits **`dashboard_uix`** — a design-led example. Its job is to show UIx can drive a polished multi-pane layout, not just a counter and a login form. It's documented, but deliberately **not** part of the curated subset (the subset is counter + login only), and it makes no contract claim.

## Layout

```
uix/
  counter_uix/    <-- the Reagent counter dataflow rendered through UIx
  login_uix/      <-- the Reagent login example through UIx
  dashboard_uix/  <-- design-led example proving multi-pane layout on UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`.

The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/). Not similar, identical. Only the view layer changes: UIx [views](../../docs/guide/glossary.md#view) are written as `defui` and pull subs in through the `use-subscribe` hook (Decision 1, UIx-idiomatic) rather than dereferencing a reactive atom. Put a Reagent example beside its UIx twin and the seam is clear — everything above the view is the same code; only the rendering differs.

### Shared registration ids — deliberate

The dataflow is identical down to the names. `counter_uix` and `login_uix` register the **same registration ids** as their Reagent (and Helix) siblings — the `:counter/*` event + sub ids, the `:auth.login/flow` machine event, the `:auth.login.demo/managed-stub` fx, the `:auth.login/state` / `:auth.login/error` subs, and the `:auth.login/flow` machine's `[:schemas :data]`. This is **byte-for-byte id reuse on purpose**: the shared id *is* the cross-substrate parity demonstration.

That's safe because of where ids actually live. A registration id is scoped to the [image](../../docs/guide/glossary.md#image) a [frame](../../docs/guide/glossary.md#frame) resolves against, not to one process-global registry. So the *same* `:counter/inc` can legitimately mean two different things in two different images (see [Images](../../docs/guide/concepts/images.md) and [Frames](../../docs/guide/concepts/frames.md)). These examples share the *spelling* of the ids; the image a frame runs against decides what each id *does*.

The **canonical statement** of this carve-out lives in [`examples/TESTING.md` §Exception 2 — the cross-substrate Reagent/UIx/Helix id share](../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share), beside its sibling [§Exception 1 — the stock/slim counter `:counter/*` share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share). It is a bounded exception to the example-id-prefix convention, not an oversight. Each example is a separate standalone build that never shares a JS runtime with its twin, so the shared ids never resolve inside one image.

If you *did* co-load two byte-identical twins into one default image, frame-creation refuses the assembly with `:rf.error/image-duplicate-id` and names both source namespaces — there is no silent last-write-wins.

Renaming the UIx ids to a `:counter-uix/*` / `:auth.login-uix/*` stem would tick the prefix-convention box, but it would also dissolve the very parity these examples exist to show. So we leave the ids alone and take the documented exception — the same trade-off the slim-counter carve-out resolves the same way.

## What each example demonstrates

- **`uix/counter_uix/`** ([build id `examples/counter-uix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`uix/login_uix/`** ([build id `examples/login-uix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`), same Malli schemas, same `:auth.login.demo/managed-stub` stub fx as the Reagent login example. The view layer is a UIx `defui` form. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears on success.

- **`uix/dashboard_uix/`** ([build id `examples/dashboard-uix`](../../implementation/shadow-cljs.edn))
  Design-led example proving UIx can drive a substantive multi-pane layout. Shares the "Editorial Warm" visual identity with the Reagent notebook and Helix process-monitor counterparts. No state machines, no HTTP — design-led examples prove polished visuals + interaction, not platform features other examples already cover.

## How to run

To run one UIx example interactively in a browser, from `implementation/`:

```bash
npm run dev:example -- examples/counter-uix
```

That stages the example, starts `shadow-cljs watch` (edits recompile live), serves it on a free local port, and prints the URL to open. Swap in `examples/login-uix` or `examples/dashboard-uix` for the others.

## Cross-references

- [`spec/006-ReactiveSubstrate.md` §CLJS reference: UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate) — the substrate contract that the UIx adapter implements.
- [`examples/reagent/counter/`](../reagent/counter/) and [`examples/reagent/login/`](../reagent/login/) — the canonical Reagent counterparts (same dataflow; different view layer).
- [`examples/reagent/notebook/`](../reagent/notebook/) — the Reagent design-led sibling of `dashboard_uix`; same "Editorial Warm" identity, different substrate.
