# login_uix — UIx substrate login

This is a controlled experiment with one variable. Take the canonical login
feature — its schemas, its state machine, its named subscriptions, its
canned HTTP stub — and render it through the **UIx**
[substrate](../../../docs/guide/glossary.md#substrate) instead of Reagent.
Everything *below* the view layer keeps the same ids and the same registered
shapes as [`examples/reagent/login/`](../../reagent/login/); the only thing
that moves is how the components are written. That makes this README the place to see,
concretely, exactly where the substrate boundary falls — and how little sits
on the substrate side of it.

The whole point of re-frame2's [adapter](../../../docs/guide/glossary.md#adapter)
design is that your events, subscriptions, app-db, machine, and effects don't
know — or care — which React-family library is drawing the pixels. This
example is that promise, cashed: same model, swap one wire, get UIx.

## What this demonstrates

- **The substrate seam, drawn in one file.** Above the boundary is the
  substrate-agnostic model — a Malli [schema](../../../docs/guide/glossary.md#schema)
  on the login credentials and the event vector, the `:auth.login/flow`
  [machine](../../../docs/machines/glossary.md#machine), two named
  [subscriptions](../../../docs/guide/glossary.md#subscription), and the
  demo HTTP stub. None of it names a substrate. Below the boundary is the
  *only* substrate-specific code: the [views](../../../docs/guide/glossary.md#view)
  and the mount. The identical model in the Reagent twin is what proves the
  claim — same ids, same shapes, different renderer.

- **`defui` + the `use-subscribe` hook, in place of Reagent's deref.** Where a
  Reagent view dereferences a subscription (`@(subscribe …)`) and re-renders
  off that reactive read, a UIx component is a plain `defui` that pulls the
  same derived value through a React hook —
  `(use-subscribe [:auth.login/error])`. Different idiom, same
  [subscription](../../../docs/guide/glossary.md#subscription) underneath. (And
  yes: `reg-view` stays a Reagent-only convenience; under UIx you write
  ordinary components.)

- **A login machine doing the real work.** The lifecycle here isn't a pile of
  boolean flags — it's a five-state
  [machine](../../../docs/machines/glossary.md#machine): `:idle` →
  `:submitting` → `:authed`, with `:error-shown` and `:locked-out` off the
  failure path. Submit fires the HTTP request from the `:submitting` entry
  action; a success folds the session token away; a failure either records
  the error and shows it (while a `:under-retry-limit` guard still passes) or,
  on the fourth failed attempt, drops into `:locked-out` for good. The view
  never reconstructs that logic — it just reads the snapshot.

- **Tags as the view's question, not exact-state matching.** The machine's
  states carry [state tags](../../../docs/machines/glossary.md#state-tag) —
  `:auth/busy`, `:auth/authenticated`, `:auth/locked` — and the views ask the
  framework `[:rf/machine-has-tag? …]` predicate sub *is it busy?* rather than
  enumerating which exact state we're in. `:auth/busy` disables the inputs and
  relabels the button to "Signing in…" while the request is in flight;
  `:auth/locked` swaps the whole form out for a non-interactive
  locked-account panel — a terminal lockout should be visibly dead, not a
  live-looking form that silently eats clicks. This is the same tag idiom the
  [state-machines walkthrough](../../../docs/machines/concepts.md) teaches;
  only the hook changes.

- **No magic, no auto-injection.** A UIx component grabs `dispatch` off a
  [`frame-handle`](../../../docs/guide/glossary.md#frame-handle) and calls
  `use-subscribe` itself. There's no hidden wiring threading state into your
  components behind your back — the read and the dispatch are right there in
  the function body. The component layer is explicit; the model beneath it is
  the one shared by all three substrates.

## Why this shape

It's a parity demonstration, and parity is best shown by holding everything
constant except the one thing under test. Read this side by side with its two
siblings — [`examples/reagent/login/`](../../reagent/login/) is the reference,
[`examples/helix/login_helix/`](../../helix/login_helix/) is the Helix twin —
and the experiment resolves: the schemas, the machine, the subs, and the HTTP
stub are the same in all three; the view layer is the only thing that differs.
Three renderers, one model.

That the model is *duplicated* across the three login examples rather than
hoisted into a shared namespace is deliberate, not copy-paste drift. Each
substrate login is a self-contained build, and the bundle-isolation gate
proves a UIx `main.js` carries no Reagent code (and vice versa) — a shared
model required into all three would defeat exactly the isolation the parity
claim rests on.

One small mechanical note: the folder carries the `_uix` suffix so its
top-level namespace doesn't collide with `examples/reagent/login/` on the
classpath — three examples sharing the same `:auth.login/*` ids need
distinct namespaces to live together in one build.

## Files

```
login_uix/
  core.cljs    — schema + events + subs + machine + defui views + mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-uix
```

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/login-uix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/uix/README.md`](../README.md).) Examples are test-free
per [`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/login-uix`
emits `main.js` into `out/examples/login-uix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output
dir yourself.

</details>

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/helix/login_helix/`](../../helix/login_helix/) — the Helix twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the UIx adapter satisfies.
- [`implementation/adapters/uix/`](../../../implementation/adapters/uix/) — the adapter implementation.
