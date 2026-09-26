# 5. The recorder, and cannot-run

The recorder writes a `:script` from what you do on the canvas. This chapter
covers the recorder, the script grammar, and `:cannot-run`: the status a run
reports when its runner cannot perform a step, such as a click on a runner
with no DOM.

## Recording a script

The recorder watches the selected variant's frame while you work the canvas,
and turns what you did into a whole `rf.story/reg-variant` form. It pastes and
runs as-is in a stories namespace that requires `[re-frame.story :as
rf.story]`.

1. Select a variant.
2. Press **REC** in the toolbar. It turns red, and an overlay names the variant
   and counts the events captured so far.
3. Click and type through the interaction on the canvas.
4. Press **REC** again, or **stop** in the overlay.

The overlay has two more controls. **DOM on** records your clicks and typing as
DOM steps; switch it to **DOM off** to record only the events they dispatch.
**+ assert** opens a picker of the seven `:rf.assert/*` assertions, asks for
the payload, and inserts the assertion at that point in the recording, while
recording carries on underneath.

When you stop, the **Test Codegen** dialog shows the form under an id you can
edit. Typing an email and a password into the login testbed's idle variant and
pressing Sign in records:

```clojure
(rf.story/reg-variant :story.login-form/recorded-883367
  {:extends :story.login-form/idle
   :script  {:auto-run? true
             :script    [[:type "[data-test=\"login-email\"]" "[:rf/redacted]"]
                         [:type "[data-test=\"login-password\"]" "[:rf/redacted]"]
                         [:click "[data-test=\"login-submit\"]"]]}})
```

The new variant extends the one you recorded on, so it starts from the same
setup. Each DOM gesture becomes a `:click` or `:type` step addressed by a
selector. The events a gesture dispatches are not recorded as steps of their
own, because replaying the gesture dispatches them again: here Sign in's
`:login/submit` is dispatched by the `:click`. Any other event the frame saw,
or every event when **DOM off** is set, becomes a `:dispatch` step, whose third
element carries the coeffects to replay, such as the clock reading the event
was stamped with. A pause of 50 ms or more between two steps becomes a
`[:wait ms]` step. Typed values are redacted in inputs of type
password, email or tel, and in inputs whose `autocomplete` names a credential
or payment field.

The dialog has four buttons: **copy to clipboard** copies the form,
**discard** clears the recording and closes the dialog, **close** just closes
it, and **export as :script** opens a second dialog. There you name the script, which becomes its
`:name`, and can tick **Auto-assert app-db at end**, which appends up to five
`[:assert-db path value]` steps checking the app-db paths the recording
changed. **replay in this story** runs the exported script against a fresh
copy of the variant, so you can check it before you paste it.

A recording is data, so you can diff it, copy it, send it over MCP, and edit
it like any hand-written script. Treat it as a first draft: delete the steps
you do not mean, and turn the final state you care about into an assertion.

## The shapes of `:script`

A `:script` is either a vector of steps or a map around one:

```clojure
:script {:name      "happy path"
         :auto-run? true
         :script    [[:dispatch [:counter/inc]]
                     [:assert-db [:count] 1]]}
```

The bare vector means the same as the map with `:auto-run? true`: the canvas
runs the script as soon as the variant mounts, and `rf.story/run`, `rf.story/is`
and the Tests tab run it too. `:auto-run? false` leaves it until you press
**Re-run** in the toolbar, and the test verbs skip it. The map is closed, so a
misspelling such as `:autorun?` is rejected at registration instead of quietly
running the script.

A variant that needs several scripts declares `:plays` instead, a vector of
maps whose `:name` is required and unique:

```clojure
:plays [{:name "happy-path"
         :script [[:dispatch-sync [:counter/initialise 5]]
                  [:assert-db [:count] 5]]}
        {:name "edge-case-zero"
         :script [[:dispatch-sync [:counter/initialise 0]]
                  [:assert-db [:count] 0]]}]
```

The first play runs on mount, and so does any other play that sets
`:auto-run? true`; the test verbs run the same plays. The toolbar's play status
turns into a dropdown that lists each play with its last result, runs any one
of them from the variant's setup, and offers **Run all**. A variant declares
`:script` or `:plays`, not both.

## The steps

| Step | What it does |
|---|---|
| `[:dispatch event]` | Dispatches the event and waits for it to settle. An optional third element, `{:rf.cofx {...}}`, supplies coeffects. |
| `[:dispatch-sync event]` | Dispatches the event synchronously. |
| `[:assert assertion]` | Runs an `:rf.assert/*` assertion at this point in the script. |
| `[:assert-db path value]` | Checks that the app-db value at `path` equals `value`. `[:assert-db path :pred f]` checks it against a predicate instead. |
| `[:assert-dom selector :visible]` | Checks that an element is present. `:hidden` checks that it is absent, and `:text "..."` checks its text. |
| `[:click selector]`, `[:type selector text]`, `[:focus selector]` | Drive the DOM. |
| `[:wait-until condition]` | Waits until `[:db path value]`, `[:db path :pred f]` or `[:queue-empty]` holds. |
| `[:wait ms]` | Sleeps. See below for why you rarely want it. |
| `[:flush-presence]`, `[:flush-presence ms]` | Advances the presence clock your host installs for enter and exit transitions. With none installed, the step cannot run. |

A bare event vector in a `:script` or `:setup` is read as `[:dispatch event]`.
That shorthand has a catch: a misspelt step tag, such as `[:asert …]`, is
dispatched as an event, and the run errors because no handler is registered
for it. A known tag with the wrong arguments, such as `[:assert-dom sel]`,
errors the run with `:rf.error/story-bad-step` before any step runs. `:setup`
accepts only dispatches: an `[:assert …]` there errors with
`:rf.error/story-assert-in-setup`, and a DOM or wait step there errors the run
too, so move it to `:script`.

The [Scripts reference](api/script.md) has the full grammar.

## What belongs in `:setup` and what belongs in `:script`

Setup establishes the state the viewer should land on. Script is the behaviour
you want to observe or test.

For the login error variant:

```clojure
:setup [[:login/flow [:login/submit {...}]]
        [:login/flow [:login/failure {...}]]]
```

is setup because it creates the error state the variant is about.

For a "submit the form" test, the same interaction would belong in `:script`:

```clojure
:script [[:dispatch-sync
          [:login/flow [:login/submit {:email "ada@example.com"
                                       :password "correct-horse"}]]]
         [:assert [:rf.assert/state-is :login/flow :authenticated]]]
```

The difference is intent, not mechanism: both run real events. Setup is the
precondition; the script is what the variant is about.

## cannot-run

A run can report:

- `:pass`;
- `:fail`;
- `:error`;
- `:cannot-run`.

`:cannot-run` means the selected runner could not observe the evidence a step
or assertion requires. It is neither a pass nor a skip: `rf.story/is` reports
it as a test failure, so CI cannot read it as green.

For example, this needs a DOM runner:

```clojure
[:click "[data-test=login-submit]"]
```

A headless runner can run app-db assertions and effect assertions, but it cannot
click a browser element. So the row is reported as `:cannot-run` with the
missing capability. A DOM or browser runner can execute it.

A run whose only problems are refusals is `:cannot-run` as a whole. A genuine
failure still wins: the verdict is `:error` if anything errored, otherwise
`:fail` if anything failed, otherwise `:cannot-run` if anything was refused,
and only then `:pass`.

## Runner capability ladder

Each runner provides a set of capabilities, and each richer runner provides
everything the cheaper ones do:

| Runner | Adds | Useful for |
|---|---|---|
| `:headless` | `:app-db` `:effects` `:schema` `:trace` `:pure-subs` | app-db, effects, trace, schema, pure subscriptions. |
| `:hiccup` | `:hiccup-structure` | structural view output without a browser. |
| `:cljs-reactive` | `:reactive-counts` | subscription and render recompute counts. |
| `:dom` | `:dom` | DOM events, focus, visibility, form entry. |
| `:browser` | `:pixels` `:a11y-engine` | pixels, screenshots, browser a11y engines. |

Each step and assertion needs a set of capabilities. Dispatches and
`:assert-db` need `:app-db`; `:click`, `:type`, `:focus` and `:assert-dom` need
`:dom`; the waits need nothing. Of the assertions, `dispatched?` also needs
`:trace`, `no-warnings` needs `:trace`, `effect-emitted` needs `:effects`,
`sub-equals` needs `:pure-subs`, and `schema-error` needs `:schema`. The
variant's required capabilities are the union. Test mode and the run result's
`:required-runner` list them, and the sidebar chip names the cheapest runner
that covers them.

`rf.story/run` and `rf.story/is` use `:headless` unless you pass `:runner`.
`{:runner :dom}` fixes the runner; `{:runner :auto}`, or `{:escalate true}`,
picks the cheapest runner whose capabilities cover the variant's, and the run
is `:cannot-run` when none does. A DOM step also needs a real document: on the
JVM there is none, so `{:runner :dom}` still refuses it there. The canvas runs
a variant with `{:runner :auto}`, and the Tests tab shows the canvas's run, so
a DOM step runs in the page against the rendered view.

Keep most Story tests headless. A claim about an app-db path, a machine state,
a subscription value or an emitted effect needs no browser; use a DOM runner
when the claim is about the DOM.

No Story runner proves pixels or an axe scan: a run reports
`:rf.assert/visual-snapshot` and `:rf.assert/a11y` as `:cannot-run`, because
nothing produces the pixel or axe evidence they need. Review pixels with a
runner you bring ([Local visual review](08-snapshot-identity-and-sharing.md#local-visual-review)).

## Waiting without flakiness

Avoid fixed sleeps:

```clojure
[:wait 300]
```

A sleep may pass on your laptop and fail on CI, because time passing is not the
same as the app being ready. `rf.story/run` and `rf.story/is` still honour it,
but `rf.story/assert-deterministic`, which replays a program in fresh frames to
check it runs the same way every time, refuses a program with a `[:wait ms]` as
`:cannot-run`.

Wait for a condition instead. A machine's state is not in app-db, so a
`[:wait-until [:db …]]` condition cannot see it. Wait for the event queue to
drain, then assert the state:

```clojure
[:wait-until [:queue-empty]]
[:assert [:rf.assert/state-is :login/flow :authenticated]]
```

The runner checks the condition once the preceding dispatch has settled. A
condition that never holds fails the step with a reason naming it, such as
`wait-until [:db [:form :ready?] true] never became true`, instead of hanging.

## Privacy at the recorder boundary

The recorder redacts at two points, and they cover different things.

Typing into a password, email or tel input, or into one whose `autocomplete`
names a credential or payment field, records the text as the string
`"[:rf/redacted]"`. The step stays, so the reproduction keeps its shape, but
the value is gone.

A dispatched event your app has classified as sensitive (see [Keep secrets
and large things out of traces](../core/how-to/keep-secrets-out-of-traces.md))
is recorded as `[:rf/redacted]` and dropped from the generated script. A value
typed into one of the inputs above is redacted from any dispatch the recorder
keeps, too: every string in its payload equal to a typed value becomes
`"[:rf/redacted]"`, the same text the `:type` step records. Anything else in
an event nobody classified is recorded as it was dispatched, so read a
recording before you commit it.
