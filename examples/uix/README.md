# UIx — examples

UIx is the second substrate re-frame2 learned to render through (see
[Spec 006 §Adapter shipping convention](../../spec/006-ReactiveSubstrate.md)),
and the examples here exist to make one claim visible: the dataflow
does not care which one you pick. UIx renders through React hooks
rather than Reagent's reactive atoms, yet it plugs into the *same*
`re-frame.adapter.context` React Context the Reagent adapter exposes
(Decision 2). In principle a single app could even mix the two —
though the sane default is to choose one substrate and stay there.

This directory is **not** a 1:1 mirror of the Reagent set, and that's
by design. A non-canonical adapter doesn't have to re-prove the whole
catalogue — it ships a representative **curated example subset**. For
UIx that subset is **counter + login** — the curated pair whose
dataflow is shared verbatim with the Reagent siblings
(substrate-agnostic events, subs, schemas, machine, managed-HTTP stub).
That pair is the load-bearing one: get the same events and subs flowing
through `defui` components and you've shown the UIx adapter honours the
substrate contract. The Reagent realworld scaffold is thick with
Reagent-flavoured idioms, so it stays deferred until a UIx user
actually asks for it.

Riding alongside the curated pair is **`dashboard_uix`** — a
design-led example, there to prove UIx can drive a polished multi-pane
layout rather than just a counter and a login form. It is a documented
example but deliberately **not** part of the curated subset: the UIx
subset is counter + login only, and `dashboard_uix` makes no contract
claim.

## Layout

```
uix/
  counter_uix/    <-- the Reagent counter dataflow rendered through UIx
  login_uix/      <-- the Reagent login example through UIx
  dashboard_uix/  <-- design-led example proving multi-pane layout on UIx
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`.

Now the part worth dwelling on: the dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent siblings under [`../reagent/`](../reagent/). Not similar, identical. The only thing that moves is the view layer, where UIx components are written as `defui` and pull subs in through the `use-subscribe` hook (Decision 1, UIx-idiomatic) instead of dereferencing a reactive atom. Put a Reagent example and its UIx twin side by side and the seam is unmistakable — everything above the view is the same code; only the rendering changes.

### Shared registration ids — deliberate

When I said the dataflow is identical, I meant it down to the names. `counter_uix` and `login_uix` register the **same registration ids** as their Reagent (and Helix) siblings — the `:counter/*` event + sub ids, the `:auth.login/flow` machine event, the `:auth.login.demo/managed-stub` fx, the `:auth.login/state` / `:auth.login/error` subs, and the `:auth.login/flow` machine's `[:schemas :data]`. This is **byte-for-byte id reuse on purpose** — the id-identity *is* the cross-substrate parity demonstration.

This sounds alarming until you remember where ids actually live. A registration id is scoped to the **image** a frame resolves against, not to one process-global registry — so the *same* `:counter/inc` may legitimately exist in two different images meaning two entirely different things (see [Images](../../docs/guide/concepts/images.md) and [Frames](../../docs/guide/concepts/frames.md)). What these examples share is the spelling of the ids; what a runtime *does* with a given id is decided by the image the frame runs against. The **canonical statement** of this carve-out lives in [`examples/TESTING.md` §Exception 2 — the cross-substrate Reagent/UIx/Helix id share](../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share), alongside its sibling [§Exception 1 — the stock/slim counter `:counter/*` share](../TESTING.md#exception-1--the-stockslim-counter-counter-id-share). It is a bounded exception to the example-id-prefix convention, not an oversight: each example is a separate standalone build that never shares a JS runtime with its twin, so the identical ids never have to resolve inside one image. (Co-load two byte-identical twins into one default image and frame-creation refuses the assembly with `:rf.error/image-duplicate-id`, naming both source namespaces — there is no silent last-write-wins.)

Renaming the UIx ids to a `:counter-uix/*` / `:auth.login-uix/*` stem would tick the prefix-convention box, but it would also quietly dissolve the very parity these examples exist to demonstrate — so we leave the ids alone and take the documented exception instead. It's the same trade-off the slim-counter carve-out resolves the same way.

## What each example demonstrates

- **`uix/counter_uix/`** ([build id `examples/counter-uix`](../../implementation/shadow-cljs.edn))
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`uix/login_uix/`** ([build id `examples/login-uix`](../../implementation/shadow-cljs.edn))
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`), same Malli schemas, same `:auth.login.demo/managed-stub` stub fx as the Reagent login example. The view layer is a UIx `defui` form. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears on success.

- **`uix/dashboard_uix/`** ([build id `examples/dashboard-uix`](../../implementation/shadow-cljs.edn))
  Design-led example proving UIx can drive a substantive multi-pane layout. Shares the "Editorial Warm" visual identity with the Reagent notebook and Helix process-monitor counterparts. No state machines, no HTTP — design-led examples exist to prove polished visuals + interaction, not to replay platform features other examples already cover.

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
