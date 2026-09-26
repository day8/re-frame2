# 4. The variant is a test

A variant with setup and assertions is already a test. This chapter runs one
in the shell's Test mode, then from your own test suite with `rf.story/run`,
`rf.story/is` and `rf.story/explain`, without writing the scenario a second
time.

## The reveal

Look again at the error variant:

```clojure
(rf.story/reg-variant :story.login/error
  {:setup  [...]
   :script [[:assert [:rf.assert/state-is :login/flow :error]]
            [:assert [:rf.assert/sub-equals [:login/error] "Invalid credentials."]]]
   :tags   #{:dev :docs :test}})
```

It has a precondition in `:setup`, checks in `:script`, and the `:test` tag.
Test mode runs it in the shell, and `rf.story/is` runs the same registration
in CI.

![Test mode showing the login error variant run, its runner, status summary, assertions, and promotion action.](../images/story/story-tutorial-04-test-mode.png)

## Test mode

Test mode is the **Tests** tab above the canvas. It shows the selected
variant's canvas at its top, and the canvas runs the variant with its view
mounted, so a `:click` or `:assert-dom` step runs against the rendered view.
The tab runs the variant each time you open it and shows the latest run; a
Controls edit, a mode or substrate change, a hot reload and **Re-run** each
run it again. From the top, it shows:

- the variant's canvas, as the Canvas tab renders it;
- the variant, its parent story, **Re-run**, when the run happened and how long
  it took, and the runner that ran it beside the runner the variant requires;
- a summary: the verdict, and the passed, failed and cannot-run counts;
- each check the variant carries, with the assertions inside it;
- the **Step-debugger**, whose **Start** runs the `:script` one step at a time
  under **Step →**, **← Back**, **▶ Play**, **Pause** and **↺ Rewind**, with a
  breakpoint toggle on every step;
- **Step-through**, one tick per step of the last run: click a tick, or drag
  the slider, and the canvas shows the state after that step;
- one row per assertion, with a **failed only** filter;
- the run's schema violations, each marked **consumed** when an
  `:rf.assert/schema-error` declared it and **unconsumed** when nothing did;
- **Cannot run**, one row per step or assertion the runner refused, naming the
  capabilities it required, the ones the runner had and the ones missing;
- a link that opens the Evidence panel on this run, and **promote run →
  regression variant…**.

A variant with nothing to run shows "No tests registered for this variant"
instead. A variant has something to run when it declares a `:script`,
`:assertions` or `:checks`, directly or through `:extends` or `:compose`.

### When a run fails

The summary reads "1 failed of 3", and the failed row carries two links.
**show detail** opens the reason, the expected and actual values and the
source location of the assertion. **open in Evidence →** opens the Evidence
panel at the beat where it failed, and chapter 6 follows that link into Xray.

A failing `:script` also marks the canvas. A **PLAY FAIL** banner names the
first failing step, with **Re-run**, and with **Highlight** when that step
targets an element. The toolbar's play status reads `Play: FAIL (2/3 steps)`,
and the variant's chip in the sidebar turns to Fail.

A **Cannot run** row is not a failure of your app. It says the runner could
not attempt a step or assertion, for example a DOM step under a runner without
a DOM, and the run's verdict is `:cannot-run` rather than `:pass`.

### Promoting a run

Promotion turns the run in front of you into a named regression variant that
fails for the reason this one failed. It captures the program the run
executed: its dispatches or, when the script dispatches nothing, the
variant's own stepped script. Every variant of the login-form testbed is that
second shape (a `:setup` precondition plus `[:assert …]` checkpoints), and
each one promotes from Test mode. By default the promoted variant extends the
one you ran, so it inherits that variant's setup, and it carries that
variant's own `:assertions` and `:checks` and its full script, checkpoints
included.

Press **promote run → regression variant…** and the dialog shows the
`reg-variant` form under an id such as `:story.login/regression-123456`. Edit
the id, add a doc string, adjust the tags (`:test` is already there), and set
how many of the leading steps are preconditions, which moves them into
`:setup`. **promote to variant** registers it in the running shell, where it
appears in the sidebar at once; **copy** puts the form on the clipboard, and
the variant outlives a reload only once you paste it into your stories
namespace. The captured run stays in the sidebar under **Captured artifacts**.

### The Tests widget

The Tests widget at the foot of the sidebar counts the variants tagged `:test`
that have something to run: passed, failed, running and not yet run. **Run
all** runs each of them in turn. **watch** keeps them running: while it is on,
the shell notices when a variant's registration, or anything it refers to,
changes under hot reload, and runs that variant again. Variants that did not
change keep their last result.

The sidebar's status chips show the same results per variant, so a state
matrix can tell you which variants have passed without opening each one.

## The three verbs

Outside the shell, three functions run variants:

```clojure
(rf.story/run     target opts) ; returns a promise of the run result
(rf.story/is      target opts) ; reports through clojure.test / cljs.test
(rf.story/explain target opts) ; returns how the plan was assembled, without running it
```

`target` can be a registered variant id:

```clojure
(rf.story/run :story.login/error)
```

or an inline plan:

```clojure
(rf.story/run
  {:setup  [[:login/flow [:login/dismiss]]]
   :script [[:assert [:rf.assert/state-is :login/flow :idle]]]})
```

Use an inline plan for a one-off test that does not need a place in the
sidebar. Register a variant when the state should also be seen, documented,
reviewed or shared.

## Using Story from tests

Variants do not replace unit tests of pure functions. They let the examples you
already maintain run in the same suite, under whatever runner runs it now.

A test namespace needs three things beside your stories: a substrate adapter
installed, which the reset fixture from `re-frame.test-support` does for you;
`re-frame.epoch` loaded, because the runner reads each run's evidence from the
epoch tape; and the stories namespace required, so its variants are
registered.

On the JVM, `rf.story/is` blocks until the run resolves and reports per
assertion:

```clojure
(ns my-app.login-stories-test
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [re-frame.epoch]
            [re-frame.story :as rf.story]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [my-app.stories]))

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest login-error-story-passes
  (rf.story/is :story.login/error))
```

To put every test variant into CI, loop over the `:test` tag. Each variant
becomes its own `testing` context, so a failure names the variant:

```clojure
(deftest every-test-variant-passes
  (doseq [id (sort (rf.story/variants-with-tags #{:test}))]
    (testing (str id)
      (rf.story/is id))))
```

A new `:test` variant joins the suite the moment it is registered, with no
test file to edit.

`is` reports one pass or failure per assertion. A run that ends `:cannot-run`
or `:error` fails the test, as does a run the tape floor turned to `:fail`
(below). A run that passes with no assertions at all reports a single pass.
The JVM wait is bounded by `:timeout-ms`, 30 seconds by default, as in
`(rf.story/is :story.login/error {:timeout-ms 5000})`; a run that takes longer
throws rather than hanging the build.

In CLJS, runs are async. `is` returns a promise that resolves after it has
reported, so use the async fixture and `cljs.test/async`:

```clojure
(ns my-app.login-stories-test
  (:require [cljs.test :refer-macros [deftest async use-fixtures]]
            [re-frame.epoch]
            [re-frame.story :as rf.story]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [my-app.stories]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :async? true}))

(deftest login-error-story-passes
  (async done
    (-> (rf.story/is :story.login/error)
        (.then (fn [_result] (done))))))
```

If you want the raw result instead of test reports, call `rf.story/run` and
inspect the resolved map. On the JVM it returns a `CompletableFuture`, so
`@(rf.story/run :story.login/error)` works; in CLJS it returns a promise.
Neither ever rejects: a run that goes wrong resolves with `:status :error`.

Both verbs run under the `:headless` runner unless you say otherwise. Pass
`{:runner :auto}` to let Story pick the cheapest runner that can prove the
variant; chapter 5 lists the runners.

## The run result

Every runner returns one result shape. The important keys are:

```clojure
{:variant/id        :story.login/error
 :status            :pass        ; :pass | :fail | :cannot-run | :error
 :runner            :headless    ; the runner that ran it
 :required-runner   #{:app-db}   ; the capabilities the variant needs
 :assertions        [...]
 :checks            [...]
 :schema-violations [...]
 :app-db            {...}
 :effects           [...]
 :warnings          [...]
 :epoch-tape        [...]        ; evidence source, when retained
 :narrative         [...]        ; scrubbable projection
 :elapsed-ms        86
 :plan-hash         "..."
 :run-hash          "..."}
```

Each assertion record says what was checked, how it came out and why:

```clojure
{:assertion :rf.assert/state-is
 :status    :pass                ; :pass | :fail | :cannot-run | :error
 :passed?   true
 :reason    "machine is in the expected state"
 :expected  :error
 :actual    :error}
```

A run the runner refused carries a `:cannot-run` vector as well, one row per
refused step or assertion, with the same required, available and missing
capabilities Test mode shows.

The top-level `:status` cannot be `:pass` while the run holds failure evidence
that no declaration consumed. Schema failures, assertion failures, errors and
cannot-run rows all count toward it, so a run never reports green while
evidence elsewhere in it is red.

## Explain

`rf.story/explain` does not run the variant. It shows what the variant becomes
after inheritance, composition, args, checks, setup and script are normalized.

Reach for it when a composed variant surprises you. Instead of guessing which
parent or fragment contributed a field, read the explanation: source chain,
merge decisions, final setup order, final script order, runner requirements,
tags, platforms and source coordinates.

The Explain panel in the right rail shows the same explanation for the
selected variant, one section per question: source chain, merge decisions,
args and their substitutions, network and sub-override lowering, fidelity,
setup and script order, checks, assertions, runner requirements, platforms,
tags and source coordinates. A toggle shows the raw EDN, and a button copies
it.

`explain` throws when the variant cannot compile, for example on a `:compose`
conflict, so the error arrives before anything runs. `rf.story/variant-plan`
returns the compiled plan itself, the same one `run` executes.

## Checks and assertions

Assertions in `:script` are checkpoints. They run at that point in the script.

Assertions in `:assertions` are terminal. They run after the script settles.

Checks are named assertion packs:

```clojure
(rf.story/reg-check :check/no-runtime-warnings
  {:assertions [[:rf.assert/no-warnings]]})

(rf.story/reg-variant :story.login/error
  {:checks [:check/no-runtime-warnings]
   :setup  [...]
   :script [...]})
```

Checks are the inheritable expectation form. Ordinary assertions are local to
the variant, so a parent variant cannot force its verdict onto every child.

To write expectations without typing them, press **add expectations…** under
Controls. Pick a kind (an app-db value or schema, a subscription value, the
rendered DOM, schema behaviour, or accessibility), fill in its operands, and
the dialog shows the runner each one needs, including any the default
`:headless` runner cannot run. It gives you a `reg-variant` form extending the
selected variant with those `:assertions`, to copy into your stories
namespace.

## Accessibility beside the example

The right-hand rail carries an a11y panel. It runs axe-core against the variant
on the canvas, and only the variant: Story's own chrome is excluded.

Press **run**. The first time, the panel asks before it loads anything, because
axe-core@4.10.0 comes from a public CDN. Press **enable axe-core + scan** to
approve once per browser. The choice is remembered, and no variant state leaves
the browser.

The panel then reports two numbers: how many violations axe found in the
variant, and how many checks it left **incomplete**. An incomplete check is one
axe could not decide, which a person has to look at. A login card can read no
violations while axe leaves its colour-contrast checks incomplete, because it
could not determine the background colour behind the text. The panel lists
those checks under their own heading, and reads "no violations, nothing
incomplete" only when both numbers are zero. An incomplete check is neither a
failure nor a pass, so report both numbers.

An agent reads the same stored result through `read-a11y-violations`. That tool
does not run a scan: it returns what the panel last stored, with the violations
under `:violations` and the incomplete checks beside them under `:incomplete`.
It also needs the browser host, so the story-mcp stdio server answers
capability-unavailable rather than an empty list.
[Chapter 9](09-multi-substrate-and-agent-loop.md#two-hosts) names the two hosts.
