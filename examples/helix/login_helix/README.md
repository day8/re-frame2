# login_helix — Helix substrate login

This is the canonical login feature — the same machine, the same schemas,
the same HTTP stub as [`examples/reagent/login/`](../../reagent/login/) — but
rendered through **Helix** instead of Reagent. The point of reading it isn't
the login flow itself (that story is told in full at the reference example).
The point is the *seam*: a single divider in `core.cljs`, above which nothing
knows or cares what renders it, below which sits the only code that does. That
seam is what makes re-frame2 substrate-agnostic, and this is the example where
you can see it drawn as a literal line in the source.

So treat this as the Reagent example's twin, viewed from the rendering side.
Everything above the `SUBSTRATE BOUNDARY` comment is identical — id for id — to
its Reagent and UIx siblings; everything below it is the Helix-specific bit —
`defnc` components reading [subscriptions](../../../docs/guide/glossary.md#subscription)
through a hook. Reading the three side by side tells you exactly which layers
move when you switch [substrate](../../../docs/guide/glossary.md#substrate) and
which don't. (Spoiler: almost nothing moves.)

The example tree is test-free; the flow's coverage lives in the framework test
tree (see [How to run](#how-to-run)).

## What this demonstrates

- **Views are plain `defnc`, and they read subs through a hook.** Reagent
  hides its reactivity behind a reactive atom — you deref a
  [subscription](../../../docs/guide/glossary.md#subscription) and the
  [view](../../../docs/guide/glossary.md#view) just re-renders. Helix is honest
  React, so there's no RAtom magic to lean on; instead `use-subscribe` is a
  React hook that subscribes on mount and re-renders the component when the
  value moves. `busy?`, the error string, the authenticated/locked flags — each
  is one `(use-subscribe [...])` call inside a `defnc`. Same derived state, read
  the React-idiomatic way.

- **No auto-injection — the wiring is explicit here.** Under Reagent,
  `reg-view` quietly hands each view a `dispatch` and `subscribe` already bound
  to the right [frame](../../../docs/guide/glossary.md#frame). Helix views skip
  `reg-view` entirely (it stays Reagent-only), so this example does the binding
  by hand: it grabs a [frame-handle](../../../docs/guide/glossary.md#frame-handle)
  with `(rf/frame-handle)` at render time and pulls `dispatch` off it. That's
  one extra line per component — and it's the honest picture of what the
  substrate boundary costs you. The artefact layer beneath doesn't notice.

- **The machine, the schemas, and the HTTP all run unchanged.** Here's the
  payoff. The [state machine](../../../docs/machines/glossary.md#machine), the
  Malli [schemas](../../../docs/guide/glossary.md#schema), and the
  managed-HTTP [effect](../../../docs/guide/glossary.md#effect) are the same
  registrations as the Reagent example — same `:auth.login/*` ids, same
  transition table, same canned-stub seam. The login flow dispatches the same
  wrapped [event](../../../docs/guide/glossary.md#event) into the machine
  (`[:auth.login/flow [:auth.login/submit creds]]`), the machine computes the
  same [transition](../../../docs/machines/glossary.md#transition), and the
  managed-HTTP reply lands back inside it as just another event. The substrate
  swapped; the model didn't.

- **Tags carry across the boundary too.** Three machine states wear
  [state tags](../../../docs/machines/glossary.md#state-tag) — `:auth/busy` on
  `:submitting`, `:auth/authenticated` on `:authed`, `:auth/locked` on
  `:locked-out`. The Helix views ask the *predicate* question through the
  framework's `[:rf/machine-has-tag? :auth.login/flow :auth/busy]`
  subscription — "is it busy?" — to disable the inputs and re-label the button
  while a request is in flight, rather than naming exact states. The terminal
  `:locked-out` state — reached after the fourth failed submit, once the
  `:under-retry-limit` guard finally fails — is a sink with no way out, so the
  view swaps the form for a non-interactive locked-account panel rather than
  leaving a dead-but-enabled form on screen. *Ask, don't tell* works the same
  whether the hiccup comes from `reg-view` or from a `defnc`; only the hook
  idiom differs.

## Why this shape

This example exists to be a **substrate-parity demonstration**, and it earns
that claim by being a deliberate, almost-pedantic copy. Pair it with
[`examples/reagent/login/`](../../reagent/login/) (the reference) and
[`examples/uix/login_uix/`](../../uix/login_uix/) (the UIx twin) and the three
become an apples-to-apples comparison where the only moving part is the
rendering library: identical registries, three view layers.

### The substrate boundary — same model, three view layers

`core.cljs` carries a `SUBSTRATE BOUNDARY` divider, and it's worth taking it
literally. *Above* the line is the **substrate-agnostic artefact layer** — the
Malli schemas, the `:auth.login.demo/managed-stub` effect, the
`:auth.login/flow` state machine (a named `auth-login-machine` def passed to
`reg-machine`, the exact shape all three substrates use), and the named subs.
None of it names a substrate; none of it could tell you whether Reagent, UIx,
or Helix sits downstream. *Below* the line is the **only** substrate-specific
code in the file: the Helix `defnc` views and the mount.

Now the part that looks like a code smell but isn't. That artefact layer is
**duplicated** across all three login examples — and that duplication is
**deliberate and the intended v2 style**, not copy-paste drift. The
id-identity *is* the demonstration: the same machine + schemas + HTTP stub
driving Reagent `reg-view`, UIx `defui`, and Helix `defnc` is precisely what
proves the [Spec 005 machine](../../../spec/005-StateMachines.md),
[Spec 010 schemas](../../../spec/010-Schemas.md), and
[Spec 014 managed-HTTP](../../../spec/014-HTTPRequests.md) surfaces are
substrate-agnostic. Hoisting the shared model into one namespace would feel
tidier and would quietly destroy the claim: each substrate login is a
self-contained `:browser` build, and `npm run test:bundle-isolation` checks
that a Helix `main.js` carries no Reagent or UIx code (and vice versa). A
shared model required into all three builds would defeat that isolation — and
the parity claim it underwrites — in one stroke. The rationale and its four
bounding conditions are catalogued in
[`examples/TESTING.md` §Exception 2](../../TESTING.md#exception-2--the-cross-substrate-reagentuixhelix-id-share).

The folder name carries the `_helix` substrate suffix so the top-level
namespace doesn't collide with its Reagent or UIx siblings on the classpath.

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

One command: it stages this folder's hand-written
[`index.html`](index.html) + the shared `_shared/` assets next to the
compiled `main.js`, starts `shadow-cljs watch` (edits recompile live),
serves `out/examples/login-helix/` on a free local port, and prints the
URL to open. Add `--no-watch` for a one-shot compile-and-serve.

(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/helix/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md).

<details><summary>Advanced: raw <code>shadow-cljs watch</code></summary>

`npm run dev:example` wraps the raw watch + manual staging recipe. To
drive shadow-cljs directly: `shadow-cljs watch examples/login-helix`
emits `main.js` into `out/examples/login-helix/`; you then copy this
folder's [`index.html`](index.html) (and the shared assets under
[`../../_shared/`](../../_shared/)) alongside it and serve the output dir
yourself.

</details>

## Cross-references

- [`examples/reagent/login/`](../../reagent/login/) — the canonical Reagent reference.
- [`examples/uix/login_uix/`](../../uix/login_uix/) — the UIx twin.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md), [`spec/010-Schemas.md`](../../../spec/010-Schemas.md), [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the substrate-agnostic surfaces.
- [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) — the substrate contract the Helix adapter satisfies.
- [`implementation/adapters/helix/`](../../../implementation/adapters/helix/) — the adapter implementation.
