# 5. The recorder, and cannot-run

You do not want to hand-author every click in a script. This chapter explains
the recorder and the runner contract behind `:cannot-run`, the third status
that keeps a headless test from pretending it proved a DOM interaction. If a
tool cannot observe the evidence it needs, it should say so plainly.

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
                         [:click "[data-test=\"login-submit\"]"]
                         [:dispatch [:login/flow [:login/submit {:email    "ada@example.com"
                                                                 :password "correct-horse"}]]
                          {:rf.cofx {:rf/time-ms 1790412881152}}]]}})
```

The new variant extends the one you recorded on, so it starts from the same
setup. Each DOM gesture becomes a `:click` or `:type` step addressed by a
selector, and each event the frame saw becomes a `:dispatch` step. The third
element of a `:dispatch` step carries the coeffects to replay, here the clock
reading the event was stamped with. A pause of 50 ms or more between two
steps becomes a `[:wait ms]` step. Typed values are redacted in inputs of type
password, email or tel, and in inputs whose `autocomplete` names a credential
or payment field.

The dialog has four buttons: **copy to clipboard** copies the form,
**discard** clears the recording and closes the dialog, **close** just closes
it, and **export as :script** opens a second dialog. There you name the script, which becomes its
`:name`, and can tick **Auto-assert app-db at end**, which appends up to five
`[:assert-db path value]` steps checking the app-db paths the recording
changed. **replay in this story** runs the exported script against a fresh
copy of the variant, so you can check it before you paste it.

The output is data, not a generated JavaScript function. That makes it easy to
diff, copy, send over MCP, and normalize through the same plan compiler as
hand-written scripts. Treat a recording as a first draft: delete the steps you
do not mean, and turn the final state you care about into an assertion.

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
That shorthand has a sharp edge: a misspelt step tag, such as `[:asert …]`, is
dispatched as an event, and the run errors because no handler is registered
for it. A step that is well-formed but has the wrong number of arguments fails
as a malformed step. `:setup` accepts only dispatches: an `[:assert …]` there
errors with `:rf.error/story-assert-in-setup`, and a DOM or wait step with
`:rf.error/story-setup-step-unrunnable`.

The [Scripts reference](api/script.md) has the full grammar.

## What belongs in `:setup` and what belongs in `:script`

Setup establishes the state the viewer should land on. Script is the behaviour
you want to observe or test.

For the login error variant:

```clojure
:setup [[:login/flow [:login/submit {...}]]
        [:login/flow [:login/failure {...}]]]
```

is setup because it creates the error state the chapter wants to show.

For a "submit the form" test, the same interaction would belong in `:script`:

```clojure
:script [[:dispatch-sync
          [:login/flow [:login/submit {:email "ada@example.com"
                                       :password "correct-horse"}]]]
         [:assert [:rf.assert/state-is :login/flow :authenticated]]]
```

The distinction is intent, not mechanism. Both are real events. Setup is
precondition. Script is what this variant is about.

## cannot-run

A run can report:

- `:pass`;
- `:fail`;
- `:error`;
- `:cannot-run`.

`:cannot-run` means the selected runner could not observe the evidence required
by a step or assertion. It is not a pass. It is not a skip that CI should ignore
by accident. It is an honest refusal.

For example, this needs a DOM runner:

```clojure
[:click "[data-test=login-submit]"]
```

A headless runner can run app-db assertions and effect assertions, but it cannot
click a browser element. So the row is reported as `:cannot-run` with the
missing capability. A richer DOM or browser runner can execute it.

A run whose only problems are refusals is `:cannot-run` as a whole. A genuine
failure still wins: the verdict is `:error` if anything errored, otherwise
`:fail` if anything failed, otherwise `:cannot-run` if anything was refused,
and only then `:pass`. `rf.story/is` reports a `:cannot-run` run as a test
failure, so CI cannot read it as green.

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
JVM there is none, so `{:runner :dom}` still refuses it there. The Tests tab
uses the same `:headless` default, so it lists a DOM step under **Cannot run**;
the canvas's own play, whose status shows in the toolbar, runs DOM steps in
the page.

Most Story tests should stay headless. If your assertion is about a db path, a
machine state, a subscription value, or an emitted effect, paying for a browser
is theatrical accounting. Use the cheap runner that can prove the claim.

If the claim really is about DOM behaviour or pixels, use the richer runner.
Story's job is to keep the boundary visible.

## Waiting without flakiness

A fixed sleep is usually a little bug farm:

```clojure
[:wait 300]
```

It may pass on your laptop and fail on CI, because time passed is not the same
thing as the app being ready.

Prefer a condition. Settle on the event queue draining, then assert the
machine state through `:rf.assert/state-is` (the machine snapshot lives in
runtime-db, so a `[:wait-until [:db …]]` app-db path can't see it — settle on
the drain and assert the state directly):

```clojure
[:wait-until [:queue-empty]]
[:assert [:rf.assert/state-is :login/flow :authenticated]]
```

The runner waits for the dispatch to settle to a fixed point and times out with
a readable reason. That is much better than "maybe 300ms was enough today."

## Privacy at the recorder boundary

Recorded snippets should not casually bake secrets into source. The recorder
redacts at two points, and they cover different things.

Typing into a password, email or tel input, or into one whose `autocomplete`
names a credential or payment field, records the text as the string
`"[:rf/redacted]"`. The step stays, so the reproduction keeps its shape, but
the value is gone.

A dispatched event is redacted only when your app has classified it as
sensitive (see [Keep secrets and large things out of
traces](../core/how-to/keep-secrets-out-of-traces.md)). It is recorded as
`[:rf/redacted]` and dropped from the generated script. An event nobody
classified is recorded as it was dispatched, payload and all: in the login
recording above, the typed password is redacted, and the `:login/submit`
dispatch that followed still carries it. Read a recording before you commit
it.

Where the typed value is redacted, the step remains in order and only the value
is removed. That preserves the shape of the reproduction without handing your
repo a credential-shaped souvenir.

You now have authorable scripts and honest runner status. The next step is what
happens when an assertion actually goes red.
