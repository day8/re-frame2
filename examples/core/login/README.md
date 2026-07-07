# A login form, wired end to end

This example is a login form. You type an email and a password, hit **Sign in**, and one of three things happens: you're signed in, you get an error and can try again, or — after four wrong tries — the account locks and the form is replaced by a locked-out panel. There's no real server to set up: a small stub answers the login request right in the page, so you just start it and click.

Most other examples in this tree show one idea on its own — a subscription, a flow, a single effect. This one is a whole feature, built the way a real feature is, with all the pieces wired together: a state machine, a couple of schemas, managed HTTP, and several registered views.

Read this when you've seen the smaller examples and you want to know how the pieces fit.

Everything in the feature shares one prefix: `:auth.login/*`. The machine, the events, the subscriptions — all of it. (The registered views are the one exception: their ids come from the file's namespace, e.g. `:login.core/root-view`.) That prefix marks the unit you'd lift into its own folder in a real codebase (more on that below). Here it's one file, kept compact so you — or an AI reading it — can take in the whole feature at once.

## What this demonstrates

The central idea is the **state machine**. A login isn't a value you read:

> **A login is a situation you're in, not a value you hold.**

Are we idle, mid-submit, showing an error, signed in, or locked out? That's five named **states**, with a fixed set of arrows between them:

```
:idle → :submitting → {:error-shown | :authed | :locked-out}
```

The whole flow is one transition table, registered with `reg-machine` under the id `:auth.login/flow`. Its live value is its **snapshot** — the current state plus a small `:data` map (`:attempts` and `:error`). The snapshot lives in runtime-db. You read it through the framework's `[:rf/machine :auth.login/flow]` subscription, like any other derived state. Everything else in the file hangs off the machine.

- **The machine is just an event handler.** Submitting the form dispatches a wrapped event into it — `[:auth.login/flow [:auth.login/submit creds]]`. The machine computes the transition, writes the new snapshot, and returns the transition's effects. No actor object, no second messaging system — the same dispatch and event pipeline as everything else. The `:locked-out` state is reached after the fourth failed submit, once the `:under-retry-limit` guard finally fails. It has no way out, so the view swaps the form for a non-interactive locked-account panel.

- **Two schemas, two boundaries.** A Malli schema (`AuthLoginData`) is attached to the machine's `[:schemas :data]` slot. It validates the snapshot's `:data` map — *just* that slot, not the whole `{:state … :data …}` snapshot — at the `:where :machine-data` boundary on every transition. A second schema (`Credentials`, riding `AuthLoginEvent`) validates the inbound event *vector* at the `:where :event` boundary. So a too-short password or a malformed email is rejected at the door, and the handler never runs. (Snapshots are runtime-db, not app-db — which is why `reg-app-schema`, the surface for *app-db* paths, isn't used here.)

- **Pure handlers, pure subscriptions.** The transition logic is pure functions of state and event. The two machine-facing subscriptions (`:auth.login/state`, `:auth.login/error`) are pure derivations off the machine snapshot; three more (`:auth.login/form-slice`, `:auth.login/draft`, `:auth.login/field-error`) project the form slice.

- **Managed HTTP, no backend required.** The `:issue-request` action fires a `:rf.http/managed` request to `/api/login`. The example ships no server, so a small demo stub stands in: `:rf.http/managed` is redirected to it on the default frame via `:fx-overrides`. The stub reads the request body and synthesises a success or failure reply through the framework's canned-response effects, keeping the real reply shape intact. The neat part: that reply lands *back inside the machine* as just another event — `[:auth.login/flow [:auth.login/success]]`, with the reply payload appended — and there's no glue code in between. That's the uniform reply at work.

- **Registered views.** `login-form`, `locked-panel`, `login-banner`, and `root-view` are all registered with `reg-view`. The form is a **controlled** [Pattern-Forms](../../../spec/Pattern-Forms.md) view. It holds no view-local state. The email/password **draft** lives in the app-db slice at `[:auth :login-form]`; each input's `:value` reads it through the `:auth.login/draft` subscription, and `:on-change` dispatches `:auth.login/edit-field`. (`reg-view` auto-injects `dispatch` and `subscribe`, bound to the render-time frame, so they survive async callbacks.)

- **The form's draft is application state, not view state.** This is the Pattern-Forms *machine + slice* split. The **slice** at `[:auth :login-form]` owns the **draft**, projected via subscriptions and changed via the standard form events (`initialise-form` / `edit-field` / `submit-form` / `reset-form`). The **machine** owns submit/auth status. On submit, the draft is read out of the slice and dispatched *into* the machine — the one point it is checked against `Credentials`. The slice, events, and subscriptions are identical across the Reagent, UIx, and Helix variants; only the view syntax differs.

- **Tags, not boolean subs.** Three states carry tags — `:auth/busy` on `:submitting`, `:auth/authenticated` on `:authed`, `:auth/locked` on `:locked-out`. Views ask the question by tag — `@(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])` to disable inputs while a request is in flight — instead of listing exact state names. *Ask, don't tell*: add a second busy state later, and the views don't change.

- **Open maps everywhere.** Every shape on the wire is an open map.

One detail worth noticing, about the password. As a controlled field it lives in the form draft while the user types. But `:submit-form` reads it out, hands it to the machine, and **clears `[:draft :password]` in the same commit** — secret-field hygiene for an auth form. So once the request is in flight, the password is gone from app-db. The machine never copies it into its `:data` slot, and the slice never writes it to `:submitted`. Its only path off-box is the HTTP request body, scrubbed from every trace by the per-request `:sensitive? true` flag on the call. The secret stays off the observability wire with no app-db classification machinery.

## Why this shape

This is the **canonical cross-substrate base**. The exact same login feature — same machine, same schemas, same HTTP stub — is mirrored 1:1 as [`examples/substrates/uix/login/`](../../substrates/uix/login/) and [`examples/substrates/helix/login/`](../../substrates/helix/login/). Only the view layer differs. That's deliberate: it makes the three substrate variants a clean apples-to-apples comparison, where the only moving part is the rendering library. (Per Decision 7 — the curated example set — in Spec 006's UIx and Helix substrate sections.)

The substrate here is **stock Reagent** (not reagent-slim). This is the reference example, so it sits on the reference substrate. If it's the stock-vs-slim *contrast* you want, that's a different pair: `counter` / `counter_slim_and_fast`.

**A note on layout (this is example layout, not production layout).** In a real codebase this single file would split along the seams the `:auth.login/*` slice already implies — `login/schema.cljc | events.cljs | subs.cljs | views.cljs | machines.cljs`. Here it's one file on purpose, so you can read it whole.

## Files

```
login/
  core.cljs            — schema + events + subs + views + machine + mount.
  index.html           — minimal host page (the live app).
  stories.cljs         — Story showcase: variants covering every reachable
                         :auth.login/flow state (auxiliary; see below).
  stories_host.cljs    — Story-showcase entry point (live-app ↔ shell hash router).
  stories.index.html   — host page for the Story-showcase build.
```

The three `stories*` files are an **intentionally auxiliary Story showcase** layered over this example (build `:examples/login-with-stories`). It's not a second example, and it isn't tool-owned. The login form's view-states make a natural variant set, so the showcase sources `login.core`'s real machine, schemas, and views and turns every reachable login state into a Story variant. The Xray preload is wired in, so the auth-submit cascade is inspectable. They live here, rather than under `tools/story/testbeds/`, because they showcase *this* worked example end to end; the tool-owned Story testbeds at [`tools/story/testbeds/`](../../../tools/story/testbeds/) stay catalogued with the tool. See [How to run](#how-to-run) for the showcase command.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/login
```

To run the Story showcase instead, watch its build and open the Story shell:

```bash
# From implementation/:
shadow-cljs watch :examples/login-with-stories   # then open http://localhost:8041/#/stories
```

`#/` renders the live login app; `#/stories` mounts the Story shell with every reachable state as a variant. Press <kbd>Ctrl+Shift+C</kbd> on either surface to open Xray over the auth-submit cascade.
