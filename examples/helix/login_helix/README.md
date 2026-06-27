# A login form, drawn with Helix

This is a login form. You type an email and a password, hit **Sign in**, and one of three things happens: you're signed in, you get an error and can try again, or — after four wrong tries — the account locks and the form is replaced by a locked-out panel. There's no server to set up: a small stub answers the login request right in the page, so you just start it and click.

It is the same feature as [`examples/reagent/login/`](../../reagent/login/) — the same machine, the same schemas, the same HTTP stub — only the rendering library differs. The login flow itself is taught in full at that reference example. What this twin shows is what changes when you swap that library, and that is the idea worth taking away:

> **Swap the rendering library and almost nothing else moves.**

You can see exactly how little moves in `core.cljs` itself, which draws the dividing line as a literal `SUBSTRATE BOUNDARY` comment. Above it is the half that knows nothing about what renders the app — identical, id for id, to its Reagent and UIx siblings. Below it is the only code that does the rendering: the Helix part, `defnc` components that read [subscriptions](../../../docs/guide/glossary.md#subscription) through a hook. That split between the two halves of the app is what makes re-frame2 substrate-agnostic. Read the three side by side and you see exactly which layers move when you switch [substrate](../../../docs/guide/glossary.md#substrate). Almost none of them do.

## What this demonstrates

- **Views are plain `defnc` and read subs through a hook.** Reagent hides its
  reactivity behind a reactive atom: you deref a
  [subscription](../../../docs/guide/glossary.md#subscription) and the
  [view](../../../docs/guide/glossary.md#view) re-renders. Helix is plain React,
  with no reactive atom to lean on. Instead `use-subscribe` is a React hook that
  subscribes on mount and re-renders the component when the value changes.
  `busy?`, the error string, the authenticated and locked flags — each is one
  `(use-subscribe [...])` call inside a `defnc`. Same derived state, read the
  React way.

- **The wiring is explicit — no auto-injection.** Under Reagent, `reg-view`
  hands each view a `dispatch` and `subscribe` already bound to the right
  [frame](../../../docs/guide/glossary.md#frame). Helix views skip `reg-view`
  (it stays Reagent-only), so here the binding is done by hand: grab a
  [capture-frame](../../../docs/guide/glossary.md#capture-frame) with
  `(rf/capture-frame)` at render time and pull `dispatch` off it. That is one
  extra line per component — the honest cost of crossing the boundary. The
  layer beneath never notices.

- **The machine, the schemas, and the HTTP run unchanged.** This is the payoff.
  The [state machine](../../../docs/machines/glossary.md#machine), the Malli
  [schemas](../../../docs/guide/glossary.md#schema), and the managed-HTTP
  [effect](../../../docs/guide/glossary.md#effect) are the same registrations as
  the Reagent example: same `:auth.login/*` ids, same transition table, same
  canned stub. The login flow dispatches the same wrapped
  [event](../../../docs/guide/glossary.md#event) into the machine
  (`[:auth.login/flow [:auth.login/submit creds]]`), the machine computes the
  same [transition](../../../docs/machines/glossary.md#transition), and the
  managed-HTTP reply lands back as just another event. The substrate swapped;
  the model did not.

- **Tags carry across the boundary too.** Three machine states wear
  [state tags](../../../docs/machines/glossary.md#state-tag) — `:auth/busy` on
  `:submitting`, `:auth/authenticated` on `:authed`, `:auth/locked` on
  `:locked-out`. The Helix views ask the framework
  `[:rf/machine-has-tag? :auth.login/flow :auth/busy]` — "is it busy?" — to
  disable the inputs and re-label the button while a request is in flight,
  rather than naming exact states. The terminal `:locked-out` state — reached
  after the fourth failed submit, once the `:under-retry-limit` guard finally
  fails — has no way out, so the view swaps the form for a non-interactive
  locked-account panel. *Ask, don't tell* works the same whether the view comes
  from `reg-view` or a `defnc`; only the hook idiom differs.

## Why this shape

This example exists to prove substrate parity, and it does so by being a
deliberate, near-exact copy. Pair it with
[`examples/reagent/login/`](../../reagent/login/) (the reference) and
[`examples/uix/login_uix/`](../../uix/login_uix/) (the UIx twin) and the three
make an apples-to-apples comparison: identical registries, three view layers,
one moving part.

### The substrate boundary — same model, three view layers

`core.cljs` carries the `SUBSTRATE BOUNDARY` divider, and it is worth reading
literally. *Above* the line is the **substrate-agnostic artefact layer**: the
Malli schemas, the `:auth.login.demo/managed-stub` effect, the
`:auth.login/flow` state machine (a named `auth-login-machine` def passed to
`reg-machine` — the exact shape all three substrates use), and the named subs.
None of it names a substrate. None of it could tell you whether Reagent, UIx, or
Helix sits downstream. *Below* the line is the **only** substrate-specific code
in the file: the Helix `defnc` views and the mount.

That artefact layer is **duplicated** across all three login examples. The
duplication is **deliberate**, not copy-paste drift — it is the intended v2
style. The shared ids *are* the demonstration: the same machine, schemas, and
HTTP stub driving Reagent `reg-view`, UIx `defui`, and Helix `defnc` is what
proves the [Spec 005 machine](../../../spec/005-StateMachines.md),
[Spec 010 schemas](../../../spec/010-Schemas.md), and
[Spec 014 managed-HTTP](../../../spec/014-HTTPRequests.md) surfaces are
substrate-agnostic. Each substrate login is a self-contained `:browser` build
carrying no other substrate's code. Hoisting the shared model into one namespace
would look tidier but would quietly destroy the claim — the parity demonstration
needs the three to stand alone.

## Files

```
login_helix/
  core.cljs    — schema + events + subs + machine + defnc views + mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-helix
```

It compiles, serves on a free local port, and prints the URL to open; edits
recompile live. No backend ships — the login runs against the canned HTTP stub
in `core.cljs`.
