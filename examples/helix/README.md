# Helix — examples

[Helix](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) is the third browser substrate re-frame2 renders through, sitting beside Reagent and UIx. It is hooks-and-`defnc` all the way down — closer in spirit to writing raw React than to Reagent's reactive atoms — and that is exactly why it is worth having in the catalogue: if the same `app-db`, events, and subs drive a Helix view as faithfully as they drive a Reagent one, the [adapter](../../spec/006-ReactiveSubstrate.md#cljs-reference-helix-as-alternative-substrate) seam is doing its job. Because Helix and UIx are both React + hooks underneath, the UIx design choices carry over unchanged — there was no new ground to break, only a second hooks substrate to honour the same contract. The adapter even consumes the very same `re-frame.adapter.context` React Context object the Reagent and UIx adapters expose, so a single page could in principle mix substrates; the canonical pattern, though, is one substrate per app, and you should treat that as the default.

This directory is **not** a 1:1 mirror of the Reagent set, and deliberately so. A non-canonical adapter only owes a representative **smoke subset** — enough to demonstrate the contract holds, not the whole worked-example corpus over again. For Helix that subset is **counter + login**. The pair earns its keep because it shares its dataflow with the Reagent siblings down to the byte — substrate-agnostic events, subs, schemas, machine, and managed-HTTP stub — so getting the pair to run *is* the proof that the Helix adapter satisfies the substrate contract. The full Reagent realworld scaffold, by contrast, is thick with Reagent-flavoured idioms, and porting it earns nothing the smoke pair hasn't already shown; it waits until an actual Helix user wants it.

Alongside the smoke pair this directory also ships **`process_monitor_helix`**, a design-led example whose job is to prove Helix can drive a polished multi-pane layout rather than just a counter and a form. Think of it as a demonstration of taste rather than a notch on the conformance belt.

## Layout

```
helix/
  counter_helix/          <-- the Reagent counter dataflow rendered through Helix
  login_helix/            <-- the Reagent login example through Helix
  process_monitor_helix/  <-- design-led example proving multi-pane layout on Helix
```

Each example sits in its own folder with the CLJS source (`core.cljs`) and a hand-written `index.html`. Those `_helix` suffixes on the folder names are load-bearing, not decoration: the CLJS namespaces (`counter-helix.core`, `login-helix.core`) have to stay distinct from their Reagent siblings (`counter.core`, `login.core`) and their UIx siblings (`counter-uix.core`, `login-uix.core`), because every substrate tree lands on the *same* shadow-cljs classpath and two namespaces with the same name would collide.

Here is the part worth slowing down for. The dataflow — events, subs, schemas, machine, managed-HTTP stub — is **identical** to the Reagent and UIx siblings under [`../reagent/`](../reagent/) and [`../uix/`](../uix/); only the view layer moves. Helix components are written as `defnc` and read their subs through the `use-subscribe` hook. To make that seam impossible to miss, each `core.cljs` draws a `SUBSTRATE BOUNDARY` divider straight through the file: above the line is the substrate-agnostic artefact layer (events / subs / schemas / machine / fx), and below it is the only code that knows it's Helix at all (the `defnc` views plus the mount). Scroll to the line and you can see, literally, where re-frame2 ends and React begins.

The obvious objection writes itself: isn't copying that artefact layer into three substrate trees just copy-paste drift waiting to rot? No — the duplication is **deliberate, and it is the intended v2 style**. The byte-for-byte id-identity is not an accident to be refactored away; it *is* the cross-substrate parity demonstration. The same `:counter/*` and `:auth.login/*` ids driving a Reagent `reg-view`, a UIx `defui`, and a Helix `defnc` is the proof — visible in the diff — that the adapter contract really is the whole story and nothing leaks across the seam. So it is pointedly **not** hoisted into one shared model namespace: each substrate example is a self-contained `:browser` build, and a shared model required into all three builds would quietly defeat the parity claim it underwrites.

One more substrate-specific note. The `reg-view` macro stays Reagent-only, so Helix users write `defnc` directly and pull `dispatch` off a `(rf/frame-handle)` for their click handlers — Helix components call `dispatch` and `use-subscribe` explicitly, with no auto-injection of those bindings the way `reg-view` does it.

## What each example demonstrates

- **`helix/counter_helix/`** (build id `examples/counter-helix`)
  Same `:counter/initialise` / `:counter/inc` / `:counter/dec` events as the Reagent counter; the view renders +/- buttons and a count between them. The count seeds to `5` (via `:counter/initialise`) and moves as the buttons dispatch.

- **`helix/login_helix/`** (build id `examples/login-helix`)
  Same login state machine (`:idle -> :submitting -> :authed`/`:error-shown`/`:locked-out`), same Malli schemas, same `:auth.login.demo/managed-stub` stub fx, and the same byte-identical Pattern-Forms slice/events/subs as the Reagent and UIx login examples. The view layer is a Helix `defnc` form with **controlled inputs**: the email/password draft lives in app-db at `[:auth :login-form]` (read via the `:auth.login/draft` sub, mutated via `:auth.login/edit-field`) — no `use-state` for input state. Entering credentials and submitting drives the machine to `:authed` and the welcome banner appears.

- **`helix/process_monitor_helix/`** (build id `examples/process-monitor-helix`)
  The design-led example: Helix driving a substantive multi-pane layout, not just a widget. It wears the same `_shared/css/style.css` "Editorial Warm" identity as the Reagent notebook and UIx dashboard counterparts. On the desktop it's the canonical two-pane shell — a process pane beside a log pane — and it doesn't fall apart on a phone: narrow viewports get a real responsive path (panes stack ≤900px; summary tiles wrap and the row/log tracks collapse ≤560px), so the declared `width=device-width` viewport renders without horizontal overflow on tablets and phones. No state machines, no HTTP here on purpose — a design-led example exists to prove polished visuals and interaction hold up, not to replay platform features the other examples already cover.

## How to run

From `implementation/`, boot any Helix example in one command (swap the build id for `examples/login-helix` or `examples/process-monitor-helix`):

```bash
shadow-cljs watch examples/counter-helix
```
