# Story

You have a view with more states than you can comfortably reach by clicking
through the app. Story gives those states names, renders each one in its own
frame, and lets you edit their inputs. The same registration then works as
documentation, as a test, as a shareable reproduction and as something an
agent can run. If you know Storybook, the shape is familiar; Story builds it
on re-frame2's frames, event pipeline, schemas, trace bus and Xray.

![The Story shell with a login variant selected, the canvas in the middle, the Story tree on the left, and Xray embedded on the right.](../images/story/story-tutorial-00-shell.png)

## The short version

In Storybook, a story is usually a render function plus args. In Story, a
variant is data:

```clojure
(rf.story/reg-variant :story.login/error
  {:setup  [[:login/flow [:login/submit {:email "ada@example.com"
                                          :password "wrong"}]]
            [:login/flow [:login/failure
                          {:failure {:status 401
                                     :message "Invalid credentials."}}]]]
   :script [[:assert [:rf.assert/state-is :login/flow :error]]]
   :tags   #{:dev :docs :test}})
```

From that data, Story:

- creates a fresh frame for the variant;
- runs the setup events through your real handlers;
- renders your real view;
- runs the assertions;
- shows the evidence in Story and Xray;
- exports a URL, an EDN form, a screenshot or a static build;
- lets an agent run the same variant through Story-MCP.

A **variant** is a named application state. It is not a second
implementation of the view: the view and the handlers are your app's own.

## What makes Story different

**Every variant runs in its own frame.** The login error state and the
authenticated state do not share `app-db`, event queues, subscriptions or
trace records, so states rendered side by side cannot leak into each other.

**Variant bodies are data.** `:setup`, `:script`, `:args`, `:decorators` and
`:sub-overrides` are EDN, and functions live behind registered ids. That is
what lets a variant be copied, hashed, shared, recorded, tested and driven
over MCP.

**A variant is also a test.** Assertions record results instead of throwing
at the first failure. Test mode, `rf.story/run`, `rf.story/is` and the
sidebar's status chips all read the same run result.

**Story embeds Xray.** Story shows the variant, its script, its test result
and its docs page. Xray shows the runtime detail behind a run: epochs,
app-db, views, trace, machines and routing.

## When not to use Story

- To test a pure function or a single event handler, write an ordinary unit
  test. A variant earns its place when the state is worth looking at.
- Story's canvas has no iframe mode. Every variant renders into the shell's
  page and shares its stylesheets, so checking that a design system's CSS
  holds up without a host page around it needs a tool with an iframe
  boundary.
- Story does not compare pixels. It gives your visual-regression tool a
  stable key for each state, and that tool does the comparison.

## The running example

This tutorial uses the shipped `login_form` Story testbed: a login form with
five states.

- idle;
- submitting;
- server error;
- retry submitting;
- authenticated.

That is enough to teach real setup events, network stubbing, workspaces,
schema-derived args, Test mode, Docs mode, Xray, sharing and the MCP loop,
and small enough to keep in your head.

A bigger example, `nine_states`, appears when the tutorial puts a whole state
matrix on one screen.

## Install Story

A freshly generated app already has this wiring — the generator template emits the dev alias, the two npm packages, a `stories.cljs` with one story and a dev-only entry that mounts Story on `#/stories` — so the steps below are for an app the template did not generate: a dev alias, two npm packages, a require and a mount.

**This page assumes a `:local/root` install.** During alpha no re-frame2 artefact is published to Clojars, so every `day8/re-frame2*` coordinate resolves from a local re-frame2 checkout — your app's own `day8/re-frame2` and `day8/re-frame2-reagent` included — and all of them must come from the *same* checkout. `:local/root` is relative to *your* `deps.edn`, so the paths below assume that clone sits **beside** your project directory; adjust them if it does not.

```clojure
;; deps.edn — the re-frame2 lines, resolved from a checkout beside your project
{:deps
 {day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
  day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}}
 :aliases
 {:dev
  {:extra-deps {day8/re-frame2-story {:local/root "../re-frame2/tools/story"}}}}}
```

shadow-cljs puts an alias on its classpath only when `shadow-cljs.edn` names it, so the `:dev` alias does nothing until you add it there. In the generator template that means `{:deps {:aliases [:shadow]}}` becomes `{:deps {:aliases [:shadow :dev]}}`.

Story's shell embeds Xray, whose machine canvas requires two npm packages a plain re-frame2 app does not carry. Install them beside the `react` and `react-dom` your app already has, at the versions re-frame2's own build resolves:

```bash
npm install --save-dev @xyflow/react@12.4.2 elkjs@0.11.1
```

Then require your stories namespace from your dev entry point and mount the
shell on the Story route. `mount-shell!` takes the DOM node and nothing else:

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [my-app.stories]))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (when (= "#/stories" js/window.location.hash)
    (rf.story/mount-shell! (js/document.getElementById "app"))))
```

Loading `my-app.stories` runs its `reg-*` calls. The first registration
installs Story's built-in tags, assertion events, decorators and shell
vocabulary. A production build that sets the
`re-frame.story.config/enabled?` define to `false` compiles Story's
registrations out, and `mount-shell!` returns without touching the DOM.

## Vocabulary used in this tutorial

`reg-story` names the parent grouping and its view id. Args, tags and
decorators that every variant shares usually live here.

`reg-variant` names one state of that story. It has `:setup` events,
`:script` steps, args, expectations and metadata.

`reg-workspace` arranges variants together, usually as a grid.

`reg-mode` creates toolbar-wide sets of args, such as a light or dark theme or
a locale.

`reg-fragment` and `reg-check` package reusable setup and reusable
expectations for variants to `:compose`, and `reg-decorator` wraps or
prepares a variant.

`rf.story/run`, `rf.story/is` and `rf.story/explain` are the three
programmatic verbs. They run a registered variant or an inline plan, report
through the test framework, or show how the final plan was assembled.

Every body is a closed map: a misspelt key is rejected at registration, and
the recorder writes the same `:setup` and `:script` keys you do.

## Chapters

1. [Your first variant](01-first-variant.md) - register and render a login state.
2. [Every state, side by side](02-every-state-side-by-side.md) - build a grid of states and use Story as a review surface.
3. [The fidelity ladder](03-fidelity-ladder.md) - know whether a state was reached honestly or painted cheaply.
4. [The variant is a test](04-the-variant-is-a-test.md) - run variants from Test mode and unit tests.
5. [The recorder, and cannot-run](05-recorder-and-cannot-run.md) - record scripts and understand honest refusal.
6. [Xray, earned at failure](06-xray-earned-at-failure.md) - move from a failed expectation to runtime evidence.
7. [Workspaces, modes, composition](07-workspaces-modes-composition.md) - reuse context without hiding behaviour.
8. [Snapshot identity and sharing](08-snapshot-identity-and-sharing.md) - share reproducible states and stable visual keys.
9. [Multi-substrate and the agent loop](09-multi-substrate-and-agent-loop.md) - understand renderer choice and Story-MCP.

The [API reference](api/index.md) is the lookup track. Read the tutorial first
if Story is new to you; use the reference when you already know which surface
you need.
