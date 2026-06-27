# Helix — examples

Three small re-frame2 apps you can run in your browser, each rendered with Helix: a **counter** with +/- buttons, a **login** form, and a **process monitor** with a two-pane layout. The counter and login are the Reagent examples of the same name, rendered through Helix instead; the process monitor is an original screen, here to show Helix can carry a polished, real-looking layout — not just a widget.

Here is the one point worth taking away: **your dataflow doesn't care which substrate you pick.** The counter and login reuse the [app-db](../../docs/guide/glossary.md#app-db), [events](../../docs/guide/glossary.md#event), and [subscriptions](../../docs/guide/glossary.md#subscription) of their Reagent siblings unchanged — only the [view](../../docs/guide/glossary.md#view) layer is rewritten for Helix. When the same dataflow drives a Helix view exactly as it drives a Reagent one, the [adapter](../../docs/guide/glossary.md#adapter) — the small value that wires re-frame2 to Helix — is doing its job.

[Helix](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) is re-frame2's third browser [substrate](../../docs/guide/glossary.md#substrate), beside Reagent and UIx. It's hooks and `defnc` all the way down — closer to writing raw React than to Reagent's reactive atoms. And because it's React-plus-hooks like UIx, the UIx design choices carry straight over: there was no new ground to break, just a second hooks substrate honouring the same contract. The Helix adapter even uses the same `re-frame.adapter.context` React Context the Reagent and UIx adapters expose, so a single page could in principle mix substrates. In practice, pick one substrate per app and stay there — treat that as the default.

That is why this directory is **not** a 1:1 mirror of the Reagent set, on purpose. A non-canonical adapter only needs a representative **smoke subset** — enough to show the contract holds, not the whole worked-example corpus again. For Helix that subset is **counter + login**. The pair earns its place because it shares its dataflow with the Reagent siblings exactly — the same events, subscriptions, schemas, state machine, and managed-HTTP stub — so getting the pair to run *is* the proof that the Helix adapter satisfies the substrate contract. The full Reagent realworld scaffold is thick with Reagent-flavoured idioms; porting it would show nothing the smoke pair hasn't, so it waits until a real Helix user wants it.

That leaves **`process_monitor_helix`**, which is here for a different reason. It's design-led: its job is to show Helix can drive a polished multi-pane layout, not just a counter and a form. It is a demonstration of design, not a conformance test.

## Layout

```
helix/
  counter_helix/          <-- the Reagent counter dataflow rendered through Helix
  login_helix/            <-- the Reagent login example through Helix
  process_monitor_helix/  <-- design-led example proving multi-pane layout on Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`.

**The `_helix` suffixes on the folder names are load-bearing, not decoration.** Every substrate tree lands on the *same* shadow-cljs classpath, so the namespaces have to stay distinct. `counter-helix.core` and `login-helix.core` must not collide with their Reagent siblings (`counter.core`, `login.core`) or UIx siblings (`counter-uix.core`, `login-uix.core`). Two namespaces with the same name would collide.

**The dataflow is identical to the siblings — only the view layer moves.** The events, subscriptions, schemas, state machine, and managed-HTTP stub are **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/). Helix views are written as `defnc` and read their subscriptions through the `use-subscribe` hook. To make the seam easy to find, each `core.cljs` draws a `SUBSTRATE BOUNDARY` divider through the file. Above the line is the substrate-agnostic layer (events, subscriptions, schemas, state machine, effects); below it is the only code that knows it's Helix at all (the `defnc` views and the mount). Scroll to the line and you can see, literally, where re-frame2 ends and React begins.

**The duplication is deliberate — it *is* the parity demonstration.** Copying that layer into three substrate trees looks like copy-paste waiting to rot. It isn't: the byte-for-byte id match is the whole point. The same `:counter/*` and `:auth.login/*` ids driving a Reagent `reg-view`, a UIx `defui`, and a Helix `defnc` is the proof — visible in the diff — that the adapter contract is the whole story and nothing leaks across the seam. So the layer is pointedly **not** hoisted into one shared namespace. Each substrate example is a self-contained `:browser` build, and a shared model required into all three would quietly defeat the parity claim.

One substrate-specific note: the `reg-view` macro is Reagent-only, so Helix users write `defnc` directly. They pull `dispatch` off a `(rf/capture-frame)` for click handlers and call `dispatch` and `use-subscribe` explicitly — there is no auto-injection of those bindings the way `reg-view` does it.

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
