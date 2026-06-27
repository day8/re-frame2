# Helix — examples

[Helix](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) is one of the React-family rendering libraries re-frame2 can render through — its third browser [substrate](../../docs/guide/glossary.md#substrate), beside Reagent and UIx. These examples make one point visible: the same [app-db](../../docs/guide/glossary.md#app-db), [events](../../docs/guide/glossary.md#event), and [subscriptions](../../docs/guide/glossary.md#subscription) drive a Helix [view](../../docs/guide/glossary.md#view) exactly as they drive a Reagent one. When they do, the [adapter](../../docs/guide/glossary.md#adapter) — the small value that wires re-frame2 to Helix — is doing its job.

Helix is hooks and `defnc` all the way down, closer to writing raw React than to Reagent's reactive atoms. It is also React-plus-hooks like UIx, so the UIx design choices carry straight over — there was no new ground to break, just a second hooks substrate to honour the same contract. The Helix adapter even uses the same `re-frame.adapter.context` React Context the Reagent and UIx adapters expose, so a single page could in principle mix substrates. In practice, pick one substrate per app and stay there — treat that as the default.

This directory is **not** a 1:1 mirror of the Reagent set, on purpose. A non-canonical adapter only needs a representative **smoke subset** — enough to show the contract holds, not the whole worked-example corpus again. For Helix that subset is **counter + login**. The pair earns its place because it shares its dataflow with the Reagent siblings exactly — the same events, subscriptions, schemas, state machine, and managed-HTTP stub. So getting the pair to run *is* the proof that the Helix adapter satisfies the substrate contract. The full Reagent realworld scaffold is thick with Reagent-flavoured idioms; porting it shows nothing the smoke pair hasn't, so it waits until a real Helix user wants it.

Alongside the smoke pair this directory also ships **`process_monitor_helix`**, a design-led example. Its job is to show Helix can drive a polished multi-pane layout, not just a counter and a form. It is a demonstration of design, not a conformance test.

## Layout

```
helix/
  counter_helix/          <-- the Reagent counter dataflow rendered through Helix
  login_helix/            <-- the Reagent login example through Helix
  process_monitor_helix/  <-- design-led example proving multi-pane layout on Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. The `_helix` suffixes on the folder names are load-bearing, not decoration. The CLJS namespaces (`counter-helix.core`, `login-helix.core`) must stay distinct from their Reagent siblings (`counter.core`, `login.core`) and UIx siblings (`counter-uix.core`, `login-uix.core`), because every substrate tree lands on the *same* shadow-cljs classpath — two namespaces with the same name would collide.

Here is the part worth slowing down for. The dataflow — events, subscriptions, schemas, state machine, managed-HTTP stub — is **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/). Only the view layer moves. Helix views are written as `defnc` and read their subscriptions through the `use-subscribe` hook. To make the seam easy to find, each `core.cljs` draws a `SUBSTRATE BOUNDARY` divider through the file. Above the line is the substrate-agnostic layer (events, subscriptions, schemas, state machine, effects); below it is the only code that knows it's Helix at all (the `defnc` views and the mount). Scroll to the line and you can see, literally, where re-frame2 ends and React begins.

Copying that layer into three substrate trees looks like copy-paste waiting to rot. It isn't — the duplication is deliberate, and it is the intended v2 style. The byte-for-byte id match is not an accident to refactor away; it *is* the parity demonstration. The same `:counter/*` and `:auth.login/*` ids driving a Reagent `reg-view`, a UIx `defui`, and a Helix `defnc` is the proof — visible in the diff — that the adapter contract is the whole story and nothing leaks across the seam. So the layer is pointedly **not** hoisted into one shared namespace. Each substrate example is a self-contained `:browser` build, and a shared model required into all three would quietly defeat the parity claim.

One substrate-specific note. The `reg-view` macro is Reagent-only, so Helix users write `defnc` directly. They pull `dispatch` off a `(rf/capture-frame)` for click handlers and call `dispatch` and `use-subscribe` explicitly — there is no auto-injection of those bindings the way `reg-view` does it.

## What each example demonstrates

- **`helix/counter_helix/`** (build id `examples/counter-helix`)
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons with a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`helix/login_helix/`** (build id `examples/login-helix`)
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`/`:locked-out`), same Malli schemas, same `:auth.login.demo/managed-stub` stub effect, and the same byte-identical Pattern-Forms slice, events, and subscriptions as the Reagent and UIx login examples. The view is a Helix `defnc` form with **controlled inputs**: the email/password draft lives in app-db at `[:auth :login-form]` (read via the `:auth.login/draft` subscription, changed via `:auth.login/edit-field`) — no `use-state` for input state. Entering credentials and submitting drives the machine to `:authed`, and the welcome banner appears.

- **`helix/process_monitor_helix/`** (build id `examples/process-monitor-helix`)
  The design-led example: Helix driving a real multi-pane layout, not just a widget. It wears the same `_shared/css/style.css` "Editorial Warm" identity as the Reagent notebook and UIx dashboard. On the desktop it's the two-pane shell — a process pane beside a log pane. It also holds up on a phone: narrow viewports get a real responsive path (panes stack ≤900px; summary tiles wrap and the row/log tracks collapse ≤560px), so the declared `width=device-width` viewport renders without horizontal overflow on tablets and phones. No state machines and no HTTP here, on purpose — a design-led example proves polished visuals and interaction, not platform features the other examples already cover.

## How to run

From `implementation/`, boot any Helix example in one command (swap the build id for `examples/login-helix` or `examples/process-monitor-helix`):

```bash
shadow-cljs watch examples/counter-helix
```
