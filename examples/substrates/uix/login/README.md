# A login form, rendered with UIx

This is a login form. You type an email and a password, hit **Sign in**, and
one of three things happens: you're signed in, you get an error and can try
again, or — after four wrong tries — the account locks and a locked-out panel
takes the form's place. There's no real server to set up: a small stub answers
the login right in the page, so you just start it and click.

The one twist is the renderer. This is the
[`examples/core/login/`](../../../core/login/) twin, with one thing
changed — the [substrate](../../../../docs/core/glossary.md#substrate) that draws
the pixels. Here that's **UIx** instead of Reagent.

Everything below the [view](../../../../docs/core/glossary.md#view) layer stays
the same — the same schemas, the same machine, the same named subscriptions,
the same canned HTTP stub — because it is *literally* the same source. All of it
lives in one substrate-free namespace, `login.model`
([`examples/core/login/model.cljs`](../../../core/login/model.cljs)), which this
example `:require`s and the Reagent twin imports unchanged. Only the
views are written differently. That's the idea worth taking away: swapping the
renderer changes almost nothing. So this is a clear place to see where the
substrate boundary falls, and how little sits on the substrate side of it.

That is the whole point of re-frame2's
[adapter](../../../../docs/core/glossary.md#adapter) design: your events,
subscriptions, app-db, machine, and effects don't know — or care — which
React-family library renders them. Same model, swap the renderer, get UIx.

## What this demonstrates

- **The substrate boundary, drawn as a file split.** The substrate-agnostic
  model lives in its own namespace, `login.model`: Malli
  [schemas](../../../../docs/core/glossary.md#schema) on the login credentials,
  the event vector, and the machine's `:data` slot; the `:auth.login/flow`
  [machine](../../../../docs/machines/glossary.md#machine); the form-slice events
  and five named [subscriptions](../../../../docs/core/glossary.md#subscription);
  and the demo HTTP stub. None of it names a substrate. This `core.cljs` holds
  the *only* substrate-specific code: the
  [views](../../../../docs/core/glossary.md#view) and the mount. The Reagent
  twin `:require`s the identical `login.model` — literally the same source,
  different renderer.

- **`defui` + the `use-subscribe` hook, in place of Reagent's deref.** A Reagent
  view reads a subscription by dereferencing it (`@(subscribe …)`) and
  re-renders off that read. A UIx view is a plain `defui` component that reads
  the same value through a React hook —
  `(use-subscribe [:auth.login/error])`. Different idiom, same
  [subscription](../../../../docs/core/glossary.md#subscription) underneath.
  (`reg-view` stays a Reagent-only convenience; under UIx you write ordinary
  components.)

- **A login machine doing the real work.** The login lifecycle isn't a pile of
  boolean flags. It's a five-state
  [machine](../../../../docs/machines/glossary.md#machine): `:idle` →
  `:submitting` → `:authed`, with `:error-shown` and `:locked-out` off the
  failure path. Submitting validates the draft and fires the (sensitive) HTTP
  request from `submit-form`, then nudges the machine with a credential-free
  signal — the machine never sees the password. Success folds the session token
  away. Failure records the error and
  shows it — while the `:under-retry-limit` guard still passes — or, on the
  fourth failed attempt, lands the machine in `:locked-out`. The view never
  rebuilds that logic; it just reads the
  [snapshot](../../../../docs/machines/glossary.md#snapshot).

- **Tags as the view's question, not exact-state matching.** Each state carries
  [state tags](../../../../docs/machines/glossary.md#state-tag) — `:auth/busy`,
  `:auth/authenticated`, `:auth/locked`. The views ask the framework's
  `[:rf.machine/has-tag? …]` predicate sub *is it busy?*, rather than listing
  every exact state. `:auth/busy` disables the inputs and relabels the button to
  "Signing in…" while the request is in flight. `:auth/locked` swaps the form
  out for a non-interactive locked-account panel, so a terminal lockout looks
  dead instead of like a live form that silently eats clicks. This is the same
  tag idiom the
  [state-machines walkthrough](../../../../docs/machines/concepts.md) teaches; only
  the hook changes.

- **No magic, no auto-injection.** A UIx component reads `dispatch` off the
  `use-frame` hook (the
  [`capture-frame`](../../../../docs/core/glossary.md#capture-frame) frame api
  in hook position) and calls
  `use-subscribe` itself. Nothing threads state into your components behind your
  back — the read and the dispatch are right there in the function body. The
  view layer is explicit; the model beneath it is shared by all three
  substrates.

## Why this shape

This is a parity demonstration. Parity shows best when you hold everything
constant except the one thing under test. Read this side by side with its
sibling — [`examples/core/login/`](../../../core/login/) is the reference. The
schemas, the machine, the subs, and the HTTP stub are the same in both; the
view layer is the only thing that differs. Two renderers, one model.

"One model" is meant literally: the two logins **share** the one substrate-free
`login.model` namespace, rather than each carrying its own copy. That is the
strongest form of parity — the comparison holds the model constant not by keeping
two copies in step, but by there being only one. It also removes the drift risk
of duplication: there is no second copy to diverge. The bundle-isolation gate
(`npm run test:bundle-isolation`) now scans the login builds too and proves each
carries **only its own substrate** — the UIx login `main.js` has no Reagent
code — which is exactly what proves the shared `login.model` drags in no
renderer.

One mechanical note: the *view* namespace here is `uix.login.core`, not
`login.core` — the `substrates/uix/` folder becomes the namespace prefix, so this
example's views don't collide with `examples/core/login/` on the classpath. The
shared model, by contrast, is one namespace (`login.model`) all three import; only
the substrate-specific view/mount namespaces need distinct prefixes to sit on one
classpath together.

## Files

```
login/
  core.cljs    — the UIx HALF: defui views + use-subscribe + mount.
  index.html   — minimal host page.
```

The substrate-free half — schemas, machine, events, subs, HTTP stub, frame
config — is not in this folder: it is the shared
[`login.model`](../../../core/login/model.cljs) namespace this `core.cljs`
`:require`s.

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-uix
```

One command. It starts `shadow-cljs watch` (edits recompile live), serves the
example on a free local port, and prints the URL to open. Add `--no-watch` for a
one-shot compile-and-serve.

No backend ships. The login runs against the canned HTTP stub in
[`core.cljs`](core.cljs), so the password `correct-horse` succeeds and anything
else fails the way the machine expects.
