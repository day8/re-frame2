# 5. The recorder, and cannot-run

You do not want to hand-author every click in a script. This chapter explains
the recorder and the runner contract behind `:cannot-run`, the third status
that keeps a headless test from pretending it proved a DOM interaction. If a
tool cannot observe the evidence it needs, it should say so plainly.

## Recording a script

The recorder watches the selected variant frame while you interact with the
canvas. When you stop recording, Story produces EDN you can paste into a
variant.

The loop is:

1. Select a variant.
2. Press `REC`.
3. Click or type through the interaction on the canvas.
4. Stop recording.
5. Paste the generated script into the variant.

For a login form, a recording may look like:

```clojure
:script [[:type  "[data-test=login-email]" "ada@example.com"]
         [:type  "[data-test=login-password]" "correct-horse"]
         [:click "[data-test=login-submit]"]
         [:wait-until [:db [:rf/runtime :machines :snapshots
                            :login/flow :state]
                       :authenticated]]
         [:assert [:rf.assert/state-is :login/flow :authenticated]]]
```

The exact grammar depends on the events the recorder observes. The key point is
that the output is data. It is not a generated JavaScript function. That makes
it easy to diff, copy, send over MCP, and normalize through the same plan
compiler as hand-written scripts.

During the pre-alpha rename, some recorder output and internal tooltips still
say `:play-script`. The public authoring slot is `:script`; the registrar lowers
the older spelling while the tree finishes converging.

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

## Runner capability ladder

Think of runners by the evidence they can observe:

| Runner | Useful for |
|---|---|
| `:headless` | app-db, effects, trace, schema, pure subscriptions. |
| `:hiccup` | structural view output without a browser. |
| `:cljs-reactive` | subscription/render recompute evidence. |
| `:dom` | DOM events, focus, visibility, form entry. |
| `:browser` | pixels, screenshots, browser a11y engines. |

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

Prefer a condition:

```clojure
[:wait-until [:db [:rf/runtime :machines :snapshots
                   :login/flow :state]
              :authenticated]]
```

The runner waits for a meaningful state and times out with a readable reason.
That is much better than "maybe 300ms was enough today."

## Privacy at the recorder boundary

Recorded snippets should not casually bake secrets into source. Sensitive
values are redacted at the appropriate egress boundary, so a recorded password
or sensitive path becomes a marker such as `:rf/redacted` instead of raw text.

The step remains in order. The sensitive value is removed. That preserves the
shape of the reproduction without handing your repo a credential-shaped souvenir.

You now have authorable scripts and honest runner status. The next step is what
happens when an assertion actually goes red.
