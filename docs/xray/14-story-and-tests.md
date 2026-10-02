# Turn a diagnosis into a regression test

Use **Story** to reproduce a state or interaction with controlled inputs,
**tests** to decide whether its assertions pass, and **Xray** to explain the
events behind the result. Keep the reproduction and assertions together so
fixing a bug leaves a scenario you can run again.

Append this regression to the login testbed's
`tools/story/testbeds/login_form/stories.cljc`, outside `register-all!`:

```clojure
;; The testbed namespace already requires [re-frame.story :as rf.story].
(rf.story/reg-variant :story.login-form/error-regression
  {:extends :story.login-form/error
   :script [[:assert [:rf.assert/state-is :login/flow :error]]
            [:assert [:rf.assert/sub-equals
                      [:login/error] "Invalid credentials."]]]
   :tags #{:dev :test}})
```

The parent testbed variant supplies the login setup and stubbed failure.
This variant checks the machine state and the message derived from it.
In your app, extend the corresponding registered scenario and assert the
behaviour the fix must preserve.

## Follow a failing assertion into Xray

In Story's **Tests** tab:

1. Open **show detail** on the failed assertion. Compare expected and actual.
2. Choose **open in Evidence →**. Story selects the script step that failed
   in its Evidence panel.
3. Follow **Xray: Epoch**, **Xray: App-db** or **Xray: Trace** on the event
   belonging to that step.
4. Read the responsible handler, state write or machine transition and follow
   its source link. Fix the cause, then rerun the same variant.

[![A failed Story run: 1 shows the verdict, 2–3 offer detail and Evidence, 4 selects the failing beat, and 5 links the setup failure event into Xray.](../images/story/story-tutorial-09-failing-run.png)](../images/story/story-tutorial-09-failing-run.png)

The [Story failure walkthrough](../story/06-xray-earned-at-failure.md) follows
an intentionally wrong expectation through these controls. That example is a
diagnostic exercise; the regression assertion above states the correct result.

## Xray in Story's right rail

[![Story's embedded Xray: callout 5 highlights the login machine moving from :submitting to :error.](../images/story/story-tutorial-07-xray-embed.png)](../images/story/story-tutorial-07-xray-embed.png)

Story gives each variant its own frame. The rail observes the selected
variant's frame and offers **Epoch**, **App-db**, **Views**, **Trace**,
**Machines** and **Routing**. Selecting a different variant changes the
observed frame; selecting an event pins that epoch. **Pop out** opens the full
Xray shell with its other panels.

Story disables Xray's global keyboard shortcuts to keep its own keys
available. Use the mouse inside this embedded inspector and its pop-out.
A custom host can make the same frame/epoch/panel handoff with
[`focus!`](api/mount-control.md#focusing-a-panel-from-a-host).

## Run the same assertion in CI

The shell makes a scenario visible; `story/is` reports the same scenario's
assertions through your test runner. Here is a JVM test using the shipped
portable login-form registrations:

```clojure
(ns my-app.login-regression-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [re-frame.epoch]
            [re-frame.story :as story]
            [re-frame.substrate.plain-atom :as plain]
            [re-frame.test-support :as ts]
            [login-form.stories]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain/adapter}))

(deftest invalid-credentials-remain-visible
  (story/is :story.login-form/error-regression))
```

The test classpath needs the Story and epoch artefacts and the testbed's source
path. In your app, require your own `.cljc` stories namespace instead of
`login-form.stories`. A headless runner checks events and state; a scenario
with DOM steps needs a runner with the required DOM capabilities. The
[Story test guide](../story/04-the-variant-is-a-test.md#using-story-from-tests)
includes ClojureScript's asynchronous test form and runner choices.

## When the bug came from a live app

From Xray, collect the initial state, the ordered event vectors and each
event's recordable coeffects. Preserve external replies as stubs. Replaying
the event vectors without their clock, generated ids or reply inputs may
produce a different run.

For a state-only regression, dispatch those events in an isolated test frame.
For a state worth inspecting visually, make a Story variant and keep the
assertions in it. The [pipeline testing guide](../core/testing/pipeline-runs.md#replay-a-bug-as-a-regression-test)
shows the isolated-frame replay.

## Troubleshooting

| Symptom | Meaning | Action |
| --- | --- | --- |
| `:fail` despite correct final state | A check failed or unconsumed failure evidence remains | Read the failed assertion and schema/error evidence |
| `:cannot-run` | The runner lacks required capabilities | Choose a capable runner or keep this regression at a state-only level |
| `:error` | Running the scenario itself failed | Read the exception and execution details; this is not an assertion pass |
| No epochs in a headless run | The epoch artefact was not loaded or evidence was not retained | Require `re-frame.epoch` and rerun with enough retention |
| A variant created in the shell disappears on reload | Its registration was only in memory | Copy the generated form into the stories namespace |

Xray never changes these verdicts. It supplies evidence to help you fix the
behaviour that the assertion checks.
