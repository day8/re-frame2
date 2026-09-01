# A login form, rendered with Hicasso

This is a login form. You type an email and a password, hit **Sign in**, and one
of 3 things happens: you're signed in, you get an error and can try again, or —
after 4 wrong tries — the account locks and a locked-out panel takes the form's
place. There's no real server to set up: a small stub answers the login right in
the page, so you just start it and click.

The one twist is the view layer. This is the
[`examples/core/login/`](../../../core/login/) app with one thing changed — the
notation the views are written in. Here that's
[Hicasso](../../../../docs/core/hicasso/index.md), re-frame2's own native view
layer, instead of Reagent.

Everything below the view layer stays the same, because it is literally the same
source. The schemas, the five-state machine, the form-slice events, the named
subscriptions and the canned HTTP stub all live in one substrate-free namespace,
`login.model` ([`examples/core/login/model.cljs`](../../../core/login/model.cljs)),
which this example `:require`s and both twins import unchanged. Only the views
and the boot are written differently.

This is the third arm of a three-way comparison — see
[`examples/substrates/README.md`](../../README.md) for the other two and for
what each one holds constant.

## What this demonstrates

- **Handlers as data.** A Hicasso view states its intent rather than writing a
  callback: `{:on-change [:auth.login/edit-field :email ::h/value]}` *is* the
  handler. `::h/value` substitutes the event target's current value at dispatch
  time, so there is no `(fn [e] …)` and no `.. -target -value` in sight. That is
  the largest visible difference from either twin.

- **`h/defview` + `h/sub`, in place of a deref or a hook.** A Reagent view
  dereferences a subscription (`@(subscribe …)`); a UIx view reads one through
  the `use-subscribe` hook. A Hicasso view calls `(h/sub [:auth.login/error])`
  anywhere in the synchronous body — inside a `let`, a `when`, or an inlined
  helper — and the edge is recorded where the read happens. Different idiom,
  the same subscription underneath.

- **Where the data spelling stops.** Two handlers in
  [`core.cljs`](core.cljs) are `h/event` callbacks rather than intent vectors,
  and each says why in place. The password's keystrokes must ride a **map**
  payload — `[:auth.login/edit-password {:value …}]` — because that
  registration declares `:sensitive [[:value]]` and redaction is path-based, so
  flattening the secret into a positional intent would ship it raw to every
  trace. `::h/value` substitutes at the intent's top level only, by design, so
  building that map is exactly what the callback form is for. The submit
  handler is the other: an intent vector has nowhere to put "not while a
  request is in flight".

- **A login machine doing the real work.** The lifecycle isn't a pile of boolean
  flags. It's a 5-state
  [machine](../../../../docs/machines/glossary.md#machine): `:idle` →
  `:submitting` → `:authed`, with `:error-shown` and `:locked-out` off the
  failure path. Submitting validates the draft and fires the (sensitive) HTTP
  request from `submit-form`, then nudges the machine with a credential-free
  signal — the machine never sees the password.

- **Tags as the view's question.** The views ask
  `[:rf.machine/has-tag? :auth.login/flow :auth/busy]` rather than listing exact
  state names. `:auth/busy` disables the inputs and relabels the button;
  `:auth/locked` swaps the form out for a non-interactive panel, so a terminal
  lockout looks dead instead of like a live form that silently eats clicks.

- **No view-local state.** Each input's `:value` reads the draft from
  `:auth.login/draft`; the draft lives in app-db. There is no
  `h/reg-state` anywhere in this file, and nothing to keep in step.

## The boot, and why it is three lines rather than one

```clojure
(rf/init! substrate/adapter)                       ;; 1. seat an adapter
(rf/make-frame (merge {:id :rf/default …}          ;; 2. make the frame ONCE,
                      model/frame-config))         ;;    with the shared config
(h/mount! el {:frame :rf/default} [root-view])     ;; 3. join it and render
```

Hicasso is a view layer, not a
[substrate](../../../../docs/core/glossary.md#substrate): it owns Hiccup
interpretation and the render boundary, while the reactive container app-db
lives in comes from an [adapter](../../../../docs/core/glossary.md#adapter).
Hicasso ships its own in `re-frame.hicasso.substrate`, so line 1 costs no extra
coordinate — and it is not optional, since creating a frame asks the adapter for
a state container.

Line 2 is where this example differs from its twins, and the reason is worth
stating plainly: `h/mount!`'s config carries exactly three keys — `:frame`,
`:initial-events` and `:identifier-prefix` — and the shared
`model/frame-config` also needs `:fx-overrides` (the demo HTTP stub). So the
frame is made explicitly, with the shared config merged in, and line 3 **joins**
it: `h/mount!` ensures its frame, creating it when absent and joining the live
one otherwise. No shim was added to `h/mount!` for this example's convenience.

Hot reload re-renders that one retained root (`h/render!`) rather than building
a second one — calling `h/mount!` again would `createRoot` twice and discard
every node, subscription and scrap of component state.

## Files

```
login/
  core.cljs    — the Hicasso HALF: h/defview views + adapter init + frame + mount.
  index.html   — minimal host page.
```

The substrate-free half — schemas, machine, events, subs, HTTP stub, frame
config — is not in this folder: it is the shared
[`login.model`](../../../core/login/model.cljs) namespace this `core.cljs`
`:require`s.

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login-hicasso
```

One command. It starts `shadow-cljs watch` (edits recompile live), serves the
example on a free local port, and prints the URL to open. Add `--no-watch` for a
one-shot compile-and-serve.

No backend ships. The login runs against the canned HTTP stub in
[`login.model`](../../../core/login/model.cljs), so the password
`correct-horse` succeeds and anything else fails the way the machine expects.

## Copying this into your own app

Read [Installation](../../../../docs/core/hicasso/00-installation.md) first —
it names every file a Hicasso project needs, including the React pin (19.2 or
newer) and the `shadow-cljs` npm package the build will not work without.

One thing that chapter says and this README will not repeat differently:
**`day8/re-frame2-hicasso` is not published to Clojars, and there is no date at
which it will be.** Today you resolve it — and `day8/re-frame2` with it — from a
monorepo checkout with `:local/root`. There is no Maven version to quote here,
and quoting one would be an invention. In *this* repository the file compiles
against the aggregate build, which already carries the artefact, which is
exactly why the coordinate question is easy to miss on the way out.

There is also no Hicasso variant in the re-frame2 app template
(`tools/template` scaffolds `:reagent` and `:uix`). Build by hand from the
installation chapter; this example's `core.cljs` is the shape the result takes.
