# Choose a runner that can prove the claim

Start with headless tests for event behaviour, machine state, app-db and
subscription values. Use a browser document when the claim is about the
rendered DOM.

```clojure
;; Requires [re-frame.story :as rf.story] and the login registrations.
(rf.story/is :story.login-form/error)
```

The default `:headless` runner can prove this machine-state assertion.
The same test on the browser's Tests tab runs beside a mounted view.

## A claim that needs a DOM

The [recorded form scenario](05-recorder-and-cannot-run.md#recording-a-script)
uses `:type` and
`:click`. Run it in the browser shell, or call it in a browser host with:

```clojure
(rf.story/run :story.login-form/submit-from-form {:runner :auto})
```

`:auto` chooses the cheapest runner whose declared capabilities cover the
scenario. `:runner :dom` fixes that runner explicitly. On the JVM there
is no document, so the DOM step still cannot run even if that runner is
selected. `:auto` does not launch a browser or load the view for you.

Tests shows the actual runner beside the required capabilities. A
`:cannot-run` row identifies the capability or evidence that is missing.
Read that row before changing a working application.

## Capability and evidence

| Runner | Intended observation |
| --- | --- |
| `:headless` | Application state, effects, schemas, traces and pure subscriptions. |
| `:hiccup` | Adds structural view data without a DOM. |
| `:cljs-reactive` | Adds subscription and render recomputation counts. |
| `:dom` | Adds DOM interaction and DOM assertions. |
| `:browser` | Adds browser-only capabilities used by pixel and accessibility assertions. |

A runner can only prove an assertion when the relevant evidence exists.
Selecting `:browser` does not capture pixels or run axe-core:

- `:rf.assert/visual-snapshot` currently reports `:cannot-run`, because no
  Story runner captures and compares pixels. Use the
  [Playwright review recipe](08-local-visual-review.md).
- `:rf.assert/a11y` evaluates a stored axe scan for the variant in a browser.
  An unscanned variant reports `:cannot-run`. Run the a11y panel's scan first;
  incomplete checks still need human review.
- A structural accessibility assertion can check declared view structure,
  but cannot establish computed colour contrast.

The [script reference](api/script.md) lists assertion requirements;
the [runtime reference](api/runtime.md#runner-capabilities) enumerates the
capability sets.

## Keep the fast tests useful

Prefer the event-based login rejection test for transition and effect
behaviour. Keep the DOM version when you also need to prove that entering
text and pressing the form button dispatches the right event. These are
different claims, even if they end in the same machine state.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| DOM step cannot run on the JVM | The host has no browser document | Run that scenario in a browser test host. |
| `:auto` selected a runner but the assertion still cannot run | Required evidence was never produced | Read the refusal and supply the scan, render output or other evidence. |
| A pixel assertion cannot run in a browser | Story has no pixel capture/diff implementation | Use an external screenshot runner. |
| Reactive counts are absent | The host did not record reactive observations | Use the browser/reactive host and inspect its recorded evidence. |
| CI is green after an unawaited CLJS call | The test completed before the promise reported | Await `is` with `cljs.test/async`. |
