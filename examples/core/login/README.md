# A login form, wired end to end

This example is a login form. You type an email and a password, hit **Sign in**, and one of 3 things happens: you're signed in, you get an error and can try again, or — after 4 wrong tries — the account locks and the form is replaced by a locked-out panel. There's no real server to set up: a small stub answers the login request right in the page, so you just start it and click.

Most other examples in this tree show one idea on its own — a subscription, a flow, a single effect. This one is a whole feature, built the way a real feature is, with all the pieces wired together: a state machine, a couple of schemas, managed HTTP, and several registered views.

Read this when you've seen the smaller examples and you want to know how the pieces fit.

Everything in the feature shares one prefix: `:auth.login/*`. The machine, the events, the subscriptions — all of it. (The registered views are the one exception: their ids come from the file's namespace, for example `:login.core/root-view`.) That prefix marks the unit you'd lift into its own folder in a real codebase (more on that below).

The whole substrate-free half of that feature — the machine, schemas, events, subs, demo HTTP stub, and frame config — lives in one place: [`model.cljc`](model.cljc), the namespace `login.model`. It is the single owner of the `auth.login` dataflow. This `core.cljs`, its [UIx twin](../../substrates/uix/login/) and its [Hicasso twin](../../substrates/hicasso/login/) each `:require` that model and add only their own views + mount. So the model you read below lives once, not three times — one owner, three view layers. [`examples/substrates/README.md`](../../substrates/README.md) lays the comparison out.

## What this demonstrates

The central idea is the state machine. A login isn't a value you read:

> A login is a situation you're in, not a value you hold.

Are we idle, mid-submit, showing an error, signed in, or locked out? That's 5 named states, with a fixed set of arrows between them:

```
:idle → :submitting → {:error-shown | :authed | :locked-out}
```

The whole flow is one transition table, registered with `reg-machine` under the id `:auth.login/flow`. Its live value is its snapshot — the current state plus a small `:data` map (`:attempts` and `:error`). The snapshot lives in runtime-db. You read it through the framework's `[:rf/machine :auth.login/flow]` subscription, like any other derived state. Everything else in the file hangs off the machine.

- The machine is just an event handler. Submitting the form dispatches a wrapped, credential-free event into it — `[:auth.login/flow [:auth.login/submit]]` (the form validates the draft and issues the request itself; the machine never sees the password). The machine computes the transition, writes the new snapshot, and returns the transition's effects. No actor object, no second messaging system — the same dispatch and event pipeline as everything else. The `:locked-out` state is reached after the fourth failed submit, once the `:under-retry-limit` guard finally fails. It has no way out, so the view swaps the form for a non-interactive locked-account panel.

- Two schemas guard the machine — and a third, plain function, guards the credential. A Malli schema (`AuthLoginData`) is attached to the machine's `[:schemas :data]` slot. It validates the snapshot's `:data` map — just that slot, not the whole `{:state … :data …}` snapshot — at the `:where :machine-data` boundary on every transition. A second schema (`AuthLoginEvent`) validates the inbound event vector at the `:where :event` boundary, so a malformed signal never reaches the transition table. Both are development-build assertions: each is a schema this app declares over its own registration, so both are compile-time eliminated from a release build and a release build runs neither ([Spec 010 §Production builds](../../../spec/010-Schemas.md#production-builds)). That is a fact about these two rather than about `:where` at large — the checks the framework makes on its own boundaries survive into production, `:rf.schema/at-boundary` among them, as the next paragraph shows. Read these two as tripwires that catch a wiring mistake while you work, not as gates that stand between bad input and your machine.

    What actually rejects a too-short password or a malformed email is `:auth.login/submit-form`. It runs the draft through `Credentials` with `m/explain` in its own handler body and branches — an ordinary pure function, present in every build. That is also the only shape that can answer: a rejection has to land field errors under the inputs and keep the password for the fixup, and a validation mechanism whose whole recovery is "skip the handler" would instead leave the form silently doing nothing. (Which is why the framework's production-side `:rf.schema/at-boundary` interceptor — right for a fire-and-forget ingress like an HTTP response — is deliberately not used here. The credential-free `AuthLoginEvent` never carries a password in the first place.)

    (Snapshots are runtime-db, not app-db — which is why `reg-app-schema`, the surface for app-db paths, isn't used here.)

- Pure handlers, pure subscriptions. The transition logic is pure functions of state and event. The 2 machine-facing subscriptions (`:auth.login/state`, `:auth.login/error`) are pure derivations off the machine snapshot; 3 more (`:auth.login/form-slice`, `:auth.login/draft`, `:auth.login/field-error`) project the form slice.

- Managed HTTP, no backend required. `:submit-form` fires a `:rf.http/managed` request to `/api/login` — it owns the validated draft, so it is the classified owner that actually sends the credential. The example ships no server, so a small demo stub stands in: `:rf.http/managed` is redirected to it on the default frame via `:fx-overrides`. The stub reads the request body and synthesises a success or failure reply through the framework's canned-response effects, keeping the real reply shape intact. The neat part: that reply lands back inside the machine as just another event — `[:auth.login/flow [:auth.login/success]]`, with the reply payload appended — and there's no glue code in between. That's the uniform reply at work.

- Registered views. `login-form`, `locked-panel`, `login-banner`, and `root-view` are all registered with `reg-view`. The form is a controlled [Pattern-Forms](../../../spec/Pattern-Forms.md) view. It holds no view-local state. The email/password draft lives in the app-db slice at `[:auth :login-form]`; each input's `:value` reads it through the `:auth.login/draft` subscription, and `:on-change` dispatches an edit event (`:auth.login/edit-field` for the email, `:auth.login/edit-password` for the secret). (`reg-view` auto-injects `dispatch` and `subscribe`, bound to the render-time frame, so they survive async callbacks.)

- The form's draft is application state, not view state. This is the Pattern-Forms machine + slice split. The slice at `[:auth :login-form]` owns the draft, projected via subscriptions and changed via the standard form events (`initialise-form` / `edit-field` / `edit-password` / `submit-form` / `reset-form`). The machine owns submit/auth status. On submit, the draft is validated against `Credentials`, the login request is issued, and the machine is nudged with a credential-free signal. The slice, events, and subscriptions live once in `login.model` and drive the Reagent, UIx and Hicasso views unchanged; only the view syntax differs.

- Tags, not boolean subs. Three states carry tags — `:auth/busy` on `:submitting`, `:auth/authenticated` on `:authed`, `:auth/locked` on `:locked-out`. Views ask the question by tag — `@(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])` (the frame-injected `subscribe`, no `rf/` prefix inside a `reg-view`) to disable inputs while a request is in flight — instead of listing exact state names. Ask, don't tell: add a second busy state later, and the views don't change.

- Open maps everywhere. Every shape on the wire is an open map.

One detail worth noticing, about the password. A password is a secret, and re-frame2 is unusually observable — events, app-db snapshots, and HTTP records all flow down one trace wire that every tool reads. So the secret has to be classified at every boundary it crosses, and here it crosses 3, each with its own owner ([keep secrets out of traces](../../../docs/core/how-to/keep-secrets-out-of-traces.md)):

- durable app-db — the draft at `[:auth :login-form :draft :password]`. `:auth.login/initialise-form` classifies it `:sensitive` in the very first write that creates the slice, so it reads `:rf/redacted` in every snapshot, epoch, and off-box record from the first keystroke on — while handlers still see the real value.
- the edit event — the password's keystrokes ride `:auth.login/edit-password`, a map-payload event whose registration declares `:sensitive [[:value]]`, so the dispatched-event trace redacts it. (A positional arg can't be classified — redaction is path-based — which is why the secret field gets its own map-shaped event while the non-secret email keeps the plain positional `:auth.login/edit-field`.)
- the HTTP request — `:submit-form` issues the managed-HTTP call with `:sensitive? true`, scrubbing the request body from every `:rf.http/*` trace event.

The password never enters the machine — `:submit-form` sends the request and hands the machine a credential-free signal. It is also blanked from the draft on submit, to keep its live lifetime short: classification covers the observable shadow, blanking retires the live value.

## Why this shape

This is the canonical cross-view-layer base. The exact same login feature — the same `login.model` machine, schemas, and HTTP stub — is imported by [`examples/substrates/uix/login/`](../../substrates/uix/login/) and [`examples/substrates/hicasso/login/`](../../substrates/hicasso/login/). Only the view layer differs. That's deliberate: it makes the three variants a clean apples-to-apples comparison, where the only moving part is the view notation — held against a model that is not merely equal across the trio but literally the same source. (Per Decision 7 — the curated example set — in Spec 006's UIx substrate section.)

The substrate here is stock Reagent (not reagent-slim). This is the reference example, so it sits on the reference substrate. If it's the stock-vs-slim contrast you want, that's a different pair: `counter` / `counter_slim_and_fast`.

A note on layout (this is example layout, not production layout). The substrate-free half already lives in its own file, `model.cljc` — the seam a real codebase draws first. In a larger app you'd split `model.cljc` further along the seams the `:auth.login/*` slice implies — `login/schema.cljc | events.cljs | subs.cljs | machines.cljs`. Here the model is one file so you can read it whole; each `core.cljs` keeps only the views + mount.

## Files

```
login/
  model.cljc           — THE substrate-free owner: schema + fx + machine +
                         events + subs + frame config. Shared, id for id, by
                         all three login examples (Reagent / UIx / Hicasso).
  core.cljs            — the Reagent HALF: reg-view views + adapter init + mount.
  index.html           — minimal host page (the live app).
  stories.cljs         — Story showcase: variants covering every reachable
                         :auth.login/flow state (auxiliary; see below).
  stories_host.cljs    — Story-showcase entry point (live-app ↔ shell hash router).
  stories.index.html   — host page for the Story-showcase build.
```

The 3 `stories*` files are an intentionally auxiliary Story showcase layered over this example (build `examples/login-with-stories`). It's not a second example, and it isn't tool-owned. The login form's view-states make a natural variant set, so the showcase sources the real login dataflow (the `login.model` machine and schemas) and `login.core`'s real views and turns every reachable login state into a Story variant. The Xray preload is wired in, so the auth-submit cascade is inspectable. They live here, rather than under `tools/story/testbeds/`, because they showcase this worked example end to end; the tool-owned Story testbeds at [`tools/story/testbeds/`](../../../tools/story/testbeds/) stay catalogued with the tool. See [How to run](#how-to-run) for the showcase command.

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/login
```

Then open the URL it prints. To run the Story showcase instead:

```bash
# From implementation/:
npm run dev:example -- examples/login-with-stories
```

Open the printed URL for the live login app, or append `#/stories` to it for the Story shell with every reachable state as a variant. Press <kbd>Ctrl+Shift+C</kbd> on either surface to open Xray over the auth-submit cascade.
