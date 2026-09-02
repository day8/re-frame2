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
  core.cljs    — the Hicasso HALF: h/defview views + adapter init + frame + boot.
  server.cljs  — the SERVER bundle: the entry table the ssr-node sidecar loads.
  host.clj     — the JVM half: one ssr-handler wired to the Node renderer.
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

## Rendering it on the server

The same views render on a server, and the interesting part is *which* server.
Hicasso interprets Hiccup at runtime through React, so there is no JVM string
emitter to render it with — the page's body is rendered by **Node**, running
this very application's compiled bundle, while a JVM Ring handler owns
everything else. That split is the whole shape of it:

| The JVM (`host.clj`) | Node (`server.cljs`) |
|---|---|
| the request frame, the boot-event drain, the settle | — |
| the `<head>`, the shell, `__rf_payload`, status, headers, cookies, redirects | — |
| error projection and frame teardown | — |
| — | the body markup, and nothing else |

Node is handed a **projection** of the settled frame — not the frame — under a
policy the host declares, and it hands back a string. Nothing else crosses in
either direction.

### The two policies

There are two allowlists, and reading them as one is the mistake worth
avoiding. `:payload` answers *what may the browser see?*; `:render-state`
answers *what does the render need?* They differ in both directions:

```clojure
:payload      [:auth]                                   ;; the browser's
:render-state {:app-db     [:auth :auth.login/server-notice]
               :runtime-db [:rf.runtime/machines]}      ;; the render's
```

`:rf.runtime/machines` is in the render's list and not the browser's *shape*
because the two partitions are different places: the `:auth.login/flow`
machine's snapshot lives in runtime-db, and it is what decides whether the page
shows the form, the welcome or the locked panel. Leave it out and the server
renders a signed-in visitor a login form.

`:auth.login/server-notice` runs the other way: a deployment notice the host
resolves per request, which the render may read and the browser never receives.
**A key in that position must not change the markup.** The hydrating client
renders from the payload, so a node the two halves disagree about is a node
React recovers by re-rendering — one recoverable error, measured rather than
asserted in `re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test`. So
`host.clj` ships with no notice in app-db, and the key is there to make the
distinction between the two policies concrete rather than theoretical.

The draft password shows the third case. It is classified `:sensitive` by the
shared model, so BOTH projections redact it before either wire: the render
cannot print a secret it was never handed, and the input comes back reading
`redacted` on a server-rendered page.

### Build it and run it

```bash
# From implementation/ — the server bundle and the client bundle.
npx shadow-cljs compile :examples/login-hicasso-server
npx shadow-cljs compile :examples/login-hicasso

# The sidecar, pointed at the server bundle. It prints ONE JSON line on
# stdout when it is listening; read the `url` out of that.
node ssr-node/bin/serve.cjs --module out/examples/login-hicasso-server/server.js
```

For a deployment, stamp a real build identity into the bundle and give the JVM
host the same string — the sidecar refuses a request that names a different
one, and the adapter refuses an answer that comes back with one:

```bash
npx shadow-cljs release examples/login-hicasso-server   --config-merge '{:closure-defines {hicasso.login.server/build-id "2026-09-02-a1b2c3"}}'
```

Then serve `host.clj`'s `handler` from any Ring adapter, with
`LOGIN_HICASSO_SSR_NODE` pointing at the sidecar's URL, and open the page. The
client boots through the same `core.cljs` either way: it looks for
`__rf_payload`, and hydrates when it finds one instead of mounting.

**One thing is not done yet, and the JVM host cannot run without it.** A JVM
host has to hold the application's state, so `login.model` — the owner of every
`auth.login` schema, fx, machine, event and sub — has to be loadable from
Clojure, and it is `model.cljs` today. The single line keeping it there is a
`localStorage` write in the demo session effect. Making it `.cljc` is a change
to a file all three login arms share and is not made here; until it is,
`host.clj` is the wiring rather than a running server. The crossing itself is
exercised end to end, against the real sidecar contract, by
`implementation/hicasso/test/re_frame/hicasso/login_server_crossing_ssr_dom_cljs_test.cljs`.

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
