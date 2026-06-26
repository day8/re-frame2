# login — full feature scaffold

Most of the other examples in this tree show you one idea in isolation: a
subscription, a flow, a single effect. This one shows you a *feature* — the
whole stack wired together the way a real one is. It's a login flow, and it
pulls in a state machine, a couple of schemas, managed HTTP, and a clutch of
registered views, all as one coherent slice of the registry. If you've read
the other examples and you're wondering "yes, but how do the pieces fit?",
this is the example that answers that.

The slice is named `:auth.login/*`, and everything in it — the machine, the
events, the subscriptions, the views — shares that prefix. That's not just
tidiness; it's the unit you'd lift out and split into its own folder in a
real codebase (more on that below). Here it all lives in one file, kept
compact so you — or an AI reading it — can take in the entire feature in one
sitting.

The example tree is test-free; its flow coverage lives in the framework test
tree (see [How to run](#how-to-run)).

## What this demonstrates

The load-bearing idea is the **state machine**. A login isn't a value you
read — it's a *question*: are we idle, mid-submit, showing an error, signed
in, or locked out? Five named states, and a fixed set of arrows between them:

```
:idle → :submitting → {:error-shown | :authed | :locked-out}
```

The whole flow is written as one transition table and registered with
`reg-machine` under the id `:auth.login/flow`. Its live value — the current
state plus a small `:data` map (`:attempts` and `:error`) — is its
**snapshot**, which lives in runtime-db and is read through the framework's
`[:rf/machine :auth.login/flow]` subscription like any other derived state.
Everything else in the file hangs off that machine:

- **The machine is just an event handler.** Submitting a form dispatches a
  wrapped event into it — `[:auth.login/flow [:auth.login/submit creds]]` —
  and the machine computes the transition, writes the new snapshot, and
  returns the transition's effects. No actor object, no second messaging
  system; the same dispatch and the same event cascade as everything else.
  The terminal `:locked-out` state — reached after the fourth failed submit,
  once the `:under-retry-limit` guard finally fails — is a genuine sink with
  no way out, which is exactly why the view swaps the form for a
  non-interactive locked-account panel rather than leaving a dead-but-enabled
  form on screen.

- **Two schemas, two boundaries.** A Malli schema (`AuthLoginData`) is
  attached to the machine's `[:schemas :data]` slot and validates the
  snapshot's `:data` map — *just* that slot, not the whole `{:state … :data
  …}` snapshot — at the `:where :machine-data` boundary on every transition.
  A second schema (`Credentials`, riding `AuthLoginEvent`) validates the
  inbound event *vector* at the `:where :event` boundary, so a too-short
  password or a malformed email is rejected at the door and the handler never
  runs. (Snapshots are runtime-db, not app-db — which is why `reg-app-schema`,
  the surface for *app-db* paths, isn't the tool reached for here.)

- **Pure handlers, pure subscriptions.** The transition logic is pure
  functions of state and event; the two named subscriptions
  (`:auth.login/state`, `:auth.login/error`) are pure derivations off the
  machine snapshot.

- **Managed HTTP, no backend required.** The `:issue-request` action fires a
  `:rf.http/managed` request to `/api/login`. Since the example ships no
  server, a per-app demo stub is registered and `:rf.http/managed` is
  redirected to it on the default frame via `:fx-overrides` — it reads the
  request body and synthesises a success or failure reply through the
  framework's canned-response effects, preserving the real reply shape
  end-to-end. The quietly excellent part: that reply lands *back inside the
  machine* as just another event — `[:auth.login/flow [:auth.login/success]]`
  with the reply payload appended — with no glue code in between. That's the
  uniform reply at work.

- **Registered views, Var-reference style.** `login-form`, `locked-panel`,
  `login-banner`, and `root-view` are all registered with `reg-view`; the
  form is a Form-2 view holding its email/password in a component-local
  Reagent atom. (`reg-view` auto-injects `dispatch` and `subscribe` bound to
  the render-time frame, so they survive async callbacks.)

- **Tags, not boolean subs.** Three states carry tags — `:auth/busy` on
  `:submitting`, `:auth/authenticated` on `:authed`, `:auth/locked` on
  `:locked-out`. Views ask the *predicate* question — `@(rf/machine-has-tag?
  :auth.login/flow :auth/busy)` to disable inputs while a request is in
  flight — instead of enumerating exact state names. *Ask, don't tell*: add a
  fourth busy state later and the views don't change.

- **Open maps everywhere.** Every shape on the wire is an open map.

One nice detail worth noticing: the password never touches durable app-db or
the machine's `:data` slot — its only off-box path is the HTTP request body,
which is scrubbed from every trace by the per-request `:sensitive? true` flag
on the managed-HTTP call. The secret stays off the observability wire without
any app-db classification machinery, because it never lives in app-db to
begin with.

## Why this shape

This is the **canonical cross-substrate base**. The exact same login feature
— same machine, same schemas, same HTTP stub — is mirrored 1:1 as
[`examples/uix/login_uix/`](../../uix/login_uix/) and
[`examples/helix/login_helix/`](../../helix/login_helix/); only the view layer
differs. That's deliberate: it makes the three substrate variants a clean
apples-to-apples comparison, where the only moving part is the rendering
library. (Per Spec 006 §Adapter shipping convention Decision 7.)

The substrate here is **stock Reagent** (not reagent-slim) — this is the
reference example, so it sits on the reference substrate. If it's the
stock-vs-slim *contrast* you're after, that's a different pair:
`counter` / `counter_slim_and_fast`.

**A note on layout (this is example layout, not production layout).** In a
real codebase this single file would split along the seams the `:auth.login/*`
slice already implies — `login/schema.cljc | events.cljs | subs.cljs |
views.cljs | machines.cljs | events_test.cljs` — and that `events_test.cljs`
is where a real app's login tests would live. The example tree here is
deliberately different: it's **test-free**, so this folder ships no inline
test fn and no sibling `test/` tree; the flow's coverage lives in the
framework test tree (see [How to run](#how-to-run)). One file, for the sake of
reading it whole.

## Files

```
login/
  core.cljs            — schema + events + subs + views + machine + mount.
  index.html           — minimal host page (the live app).
  stories.cljs         — Story showcase: one variant per reachable
                         :auth.login/flow state (auxiliary; see below).
  stories_host.cljs    — Story-showcase entry point (live-app ↔ shell hash router).
  stories.index.html   — host page for the Story-showcase build.
```

The three `stories*` files are an **intentionally auxiliary Story showcase**
layered over this example (build `:examples/login-with-stories`) — not a
second example and not tool-owned. The login form's view-states are a natural
variant set, so the showcase sources `login.core`'s real machine, schemas,
and views and enumerates every reachable login state as a Story variant, with
the Xray preload wired so the auth-submit cascade is inspectable. They live
here (rather than under `tools/story/testbeds/`) because they showcase *this*
worked example end-to-end; the tool-owned Story testbeds at
[`tools/story/testbeds/`](../../../tools/story/testbeds/) stay catalogued with
the tool. See [How to run](#how-to-run) for the showcase command.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/login
```

The watch build emits `main.js` into `out/examples/login/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/login/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)

To run the Story showcase instead, watch its build and open the Story
shell:

```bash
# From implementation/:
shadow-cljs watch :examples/login-with-stories   # then open http://localhost:8041/#/stories
```

`#/` renders the live login app; `#/stories` mounts the Story shell with
every reachable state as a variant. Press <kbd>Ctrl+Shift+C</kbd> on
either surface to open Xray over the auth-submit cascade.

## Coverage (examples are test-free)

This folder ships no tests. The login flow this example wires is a
near-twin of the `:auth.login/flow` machine the sibling
[`state_machine_walkthrough`](../state_machine_walkthrough/) example
exercises headlessly: the `state-machine-walkthrough-runs-headless`
deftest in
[`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
drives the happy-path / retry-then-lockout / machine-transition
scenarios. Login-specific machine-data schema coverage lives in
[`implementation/adapters/reagent/test/re_frame/login_cljs_test.cljs`](../../../implementation/adapters/reagent/test/re_frame/login_cljs_test.cljs).
Broader contract coverage runs in the substrate contract suite
(`npm run test:cljs`) and the framework gates; the full split is in
[`examples/README.md`](../../README.md).

## Cross-references

- [Construction Prompts CP-1 / CP-2 / CP-3 / CP-4 / CP-5 / CP-6 / CP-8](../../../spec/Construction-Prompts.md) — the construction prompts this example instantiates.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the machine substrate.
- [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — schema attachment.
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — `:rf.http/managed`.
- [`examples/uix/login_uix/`](../../uix/login_uix/) + [`examples/helix/login_helix/`](../../helix/login_helix/) — the substrate variants.
