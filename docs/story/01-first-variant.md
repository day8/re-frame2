# 1. Your first variant

This chapter registers one login-form story and one variant, renders the
variant in the Story shell, and adds an assertion that proves the variant is
in the state its name claims.

## The smallest useful Story file

Start with a `stories.cljs` namespace. Require the app namespaces first: their
`reg-event-*`, `reg-sub` and `reg-view` calls have to run before Story can
refer to their ids.

```clojure
(ns my-app.stories
  (:require [re-frame.story :as rf.story]
            [my-app.events]
            [my-app.subs]
            [my-app.views]))

(rf.story/reg-story :story.login
  {:doc        "The login form and its important states."
   :component  :my-app.views/login-card
   :args       {:heading "Sign in"}
   :tags       #{:dev :docs}
   :substrates #{:reagent}})

(rf.story/reg-variant :story.login/idle
  {:doc    "Fresh form, no input typed, no request in flight."
   :setup  [[:login/flow [:login/dismiss]]]
   :script [[:assert [:rf.assert/state-is :login/flow :idle]]]
   :tags   #{:dev :docs :test}})
```

Open your app's `#/stories` route, the one [Install Story](index.md#install-story)
mounts the shell on, select `/idle`, and the form appears on the canvas.

To follow along without an app of your own, run the shipped testbed: from
`implementation/`, `npx shadow-cljs watch :examples/login-form`, then open
`http://localhost:8043/index.html#/stories`. The browser console may log
`shadow-cljs watch for build :login-form not running!`; that is a harmless
shadow-cljs notice, because shadow-cljs names the build without its namespace,
and hot reload still works. The testbed registers these states as
`:story.login-form` in `tools/story/testbeds/login_form/stories.cljc`; this
tutorial uses the shorter `:story.login` your own app would, so
`:story.login/idle` here is `:story.login-form/idle` there.

![The first login variant selected in Story.](../images/story/story-tutorial-01-first-variant.png)

The story file does not reimplement the view. The variant names a registered
view id and supplies the state the view needs.

## The shell

The shell has four regions:

| Region | What it holds |
|---|---|
| Toolbar | The toolbar modes (chapter 7); **Dispatch**, which opens a console in the right rail for sending events to the selected variant; the play status of the variant's `:script`, with **Re-run**; the viewport and background pickers; **Inspect**, which lets you click an element on the canvas to open its view's source; **Share** (chapter 8); and **REC**, the recorder (chapter 5). |
| Sidebar | A search box, a tag filter, the story tree, the workspaces, and the **Tests** widget with its pass and fail counts, **Run all** and **watch** (chapter 4). |
| Canvas | The selected variant, under the **Canvas**, **Docs** and **Tests** tabs. The title row names the variant and its view, and **open** shows the variant's registration in your editor. |
| Right rail | Xray (chapter 6), Explain (chapter 4), Evidence, Controls, and the a11y, Chrome a11y, Layout-debug and Schema validation panels. |

Under each variant in the sidebar is a row of chips. The first is the variant's
test status: Pending until it runs, then Pass, Fail, Error or Can't run. The
others say how the state was reached (real setup, db seed or sub overrides,
chapter 3), which world inputs the variant declares (args, route, network, fx
overrides), the cheapest runner that can prove it (headless, hiccup,
cljs-reactive, DOM or browser, chapter 5), and whether it runs in a fresh
frame.

With the focus outside a text field, `f` toggles a full-screen canvas, `s` the
sidebar, `a` the right rail and `t` the toolbar; Escape leaves full-screen.
Ctrl-K (Cmd-K on macOS) opens a command palette that searches stories,
variants, workspaces, modes and decorators. The `?` button in the top-left
corner reopens the help overlay that the shell shows on your first visit.

## `reg-story` is the parent

The parent story groups variants that share a view and defaults.

```clojure
(rf.story/reg-story :story.login
  {:component :my-app.views/login-card
   :args      {:heading "Sign in"}})
```

The `:component` value is a view id keyword, not a function. The view stays in
your app's view registry, and the story body stays data.

The story id is also the navigation structure. `:story.login` is the parent;
`:story.login/idle` and `:story.login/error` are variants under it. There is no
separate `title: "Forms/Login/Error"` string to keep in sync with the id.

The id shapes are fixed. A story id is an unqualified keyword whose name starts
with `story.`, and a variant id takes its story's name as its namespace.
Workspace ids have a namespace starting with `Workspace.` and mode ids one
starting with `Mode.`, as in `:Workspace.login/all-states` and
`:Mode.app/dark`. Registering an id of the wrong shape throws
`:rf.error/story-id-shape`, `:rf.error/variant-id-shape` and so on, one id per
kind.

## `reg-variant` is the state

The three fields you will use most are `:setup`, `:script` and `:args`.

`:setup` establishes the precondition. Its event vectors are dispatched
through the app's real event pipeline. In the login testbed,
`[:login/flow [:login/dismiss]]` is the first event the login machine sees,
which starts it in its initial state, `:idle`.

`:script` is the behaviour or expectation Story runs after setup. This
variant's script is one assertion step:

```clojure
[:assert [:rf.assert/state-is :login/flow :idle]]
```

`:assert` is the checkpoint step: it runs the assertion at that point in the
script and records the result, pass or fail, before the runner continues. You
may also see it written `[:dispatch-sync [:rf.assert/…]]`, which dispatches
the same assertion event and records the same row.

`:args` supplies view inputs. A variant can override the parent story's args,
an active toolbar mode (chapter 7) sits between the two, and live Controls
edits override all of them:

```text
global < story < mode < variant < live control override
```

Most variants start with only `:setup` and `:script`; args matter once you
want to explore presentation inputs.

`:tags` classifies the variant. Story registers seven tags for you: `:dev`,
`:docs`, `:test`, `:screenshot`, `:experimental`, `:internal` and `:agent`. The
shell acts on one of them: a `:test` variant joins the sidebar's Tests widget.
Chapter 7 covers the rest of the tag vocabulary. A variant that declares no
`:tags` takes its story's; one that declares its own uses those instead.

A variant body is a closed map. A misspelt or unknown key throws
`:rf.error/variant-shape`, and the message names the key and the nearest valid
one. The [registration reference](api/registration.md#variant-body) lists every
key a variant accepts.

## A schema on the view gives you Controls

`:args` are view inputs, so the view is where a valid input is defined. Give the
view a Malli props schema under `:rf/props` on its registration:

```clojure
(ns my-app.views
  (:require [re-frame.core :as rf]))

(rf/reg-view ^{:rf/props [:map [:heading {:optional true} [:string {:min 1}]]]}
          login-card [{:keys [heading]}]
  [:section
   [:h3 (or heading "Sign in")]
   [login-form]])
```

The story file does not change. Select `/idle` and look at Controls in the
right-hand rail: Story reads the schema off the variant's `:component` and
derives a control for each arg, so `:heading` gets a text field. Clear the field
and the row shows an inline `schema:` error, with a banner saying the arg
violates the component's schema: an empty heading is not a valid render of this
view. Without a schema, Story can only guess a control from the value, and the
Schema validation panel below Controls reports "no schema registered for the
variant's :component".

Controls follow the schema's shape: `:string` gives a text field, `:int` and
`:double` a number field, `:boolean` a checkbox, `:keyword` a text field read
back as a keyword, `[:enum ...]` a select, and `[:maybe X]` the control for
`X`. `:map`, `:vector`, `:set` and `:tuple` nest their children, and the vector
and set editors add and remove rows. `:rf/props` is the canonical key; a
`:schema` key in the same place also works.

Where a derived control is not the one you want, name it in the story's or
variant's `:argtypes`, keyed by arg, as `{:heading {:control :textarea}}`. The
controls are `:text`, `:textarea`, `:number`, `:boolean`, `:select`, `:radio`,
`:date` and `:color`; `:select` and `:radio` take their choices from
`:options`. A variant's `:argtypes` beats its story's, and both beat the
schema.

## Every variant gets a frame

Each variant runs in its own frame, with its own `app-db`, event queue,
subscriptions, trace records, interceptors and lifecycle. Selecting `/idle`
does not touch `/error`, and a grid of five variants is five isolated
instances of your app: the same registered code, different state.

Because the view runs inside a real frame, it can subscribe, dispatch, read
machine state and emit effects exactly as it does in the app. That is why
Story can render application states, not only component states.

## Assertions record results

The `:rf.assert/*` assertions are ordinary events. They record assertion rows
in the variant's frame instead of throwing on the first failure.

| Assertion | Use it for |
|---|---|
| `:rf.assert/path-equals` | checking a path in `app-db`. |
| `:rf.assert/path-matches` | checking a path against a schema. |
| `:rf.assert/sub-equals` | checking a real subscription value. |
| `:rf.assert/dispatched?` | checking that an event was dispatched during the run, in setup or script. |
| `:rf.assert/state-is` | checking a registered machine's state. |
| `:rf.assert/no-warnings` | checking the run emitted no warnings. |
| `:rf.assert/effect-emitted` | checking that an effect id was emitted. |

An eighth id, `:rf.assert/schema-error`, works the other way round. It declares
a schema violation the run is expected to produce, such as
`[:rf.assert/schema-error {:where :event :event :login/flow}]`, and fails when
that violation does not happen. Without such a declaration, any schema
violation during the run fails the run (chapter 6).

Because assertions record instead of throwing, one run collects every failure,
the shell stays usable, and you see each failed check rather than the first
stack trace.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The sidebar is empty | The stories namespace was never required, so its `reg-*` calls did not run | Require it from the dev entry point |
| Registration throws `:rf.error/variant-shape` (or `:rf.error/story-shape`, `:rf.error/workspace-shape`, and so on) | The body carries a key that kind does not accept, often a typo | Use the key the message suggests; the [registration reference](api/registration.md#variant-body) lists every key |
| Registration throws `:rf.error/story-id-shape` or `:rf.error/variant-id-shape` | The id does not have the required shape | Name stories `:story.<path>` and variants `:story.<path>/<name>` |
| Registration throws `:rf.error/unknown-tag` | A tag in `:tags` is neither built in nor registered | Register it with `rf.story/reg-tag` before the variant that uses it |
| The canvas reads "variant has no :component registered" | Neither the variant nor its story names a view | Add `:component` to the story or the variant |
| The canvas reads ":component … is not registered as a view" | The view id is misspelt, or its namespace was not required | Require the views namespace and match the id `reg-view` registered |
| Schema validation reads "no schema registered for the variant's :component" | The view has no `:rf/props` schema | Add one to get derived Controls and arg checks |
| Test mode reads "No tests registered for this variant" | The variant has no `:script`, `:assertions` or `:checks` | Add a checkpoint such as `[:assert [:rf.assert/state-is :login/flow :idle]]` |
| The run errors with `:rf.error/story-assert-in-setup` | An `[:assert …]` step sits in `:setup` | Move it to `:script` |
