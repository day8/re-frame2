# 5. The recorder, and `:cannot-run`

> **What you'll build.** Record a canvas interaction into an EDN `:script` body and paste it into a variant. Then meet the third result state — `:cannot-run` — and understand why it's the most honest word in the tool.

## Record-and-replay, but the recording is data

Storybook 9's flagship feature is record-and-replay: you interact with a component in the browser, and it captures your gestures into a replayable interaction. It's a genuinely good feature and it deserved the headline.

Story has the same feature, and the gesture is identical. The divergence is in what comes *out* the end. Storybook records into a TypeScript `play` function — imperative `userEvent` calls and `await expect` lines. Story records into **EDN that pastes straight into a `reg-variant`**. It's not generating a function; it's generating data, the same data shape you've been hand-authoring since chapter 1. Try generating a clean, diffable, round-trippable TypeScript function from a recording sometime, and you'll feel exactly why the data shape matters: the EDN goes over MCP, into visual-regression services, and through agent pipelines unchanged, because it never stopped being data.

## The record loop

Five steps:

1. Open `#/stories` in your dev build and select the variant.
2. Click **record** in the canvas toolbar.
3. Tap through the interaction — click the increment button, type into a field, whatever the scenario is.
4. Click **stop**.
5. A modal shows the generated EDN `:script` body. Copy it; paste it into the variant.

Drive the increment button three times against the counter and you get back something like:

```clojure
:script [[:dispatch-sync [:counter/inc]]
         [:dispatch-sync [:counter/inc]]
         [:dispatch-sync [:counter/inc]]
         [:dispatch-sync [:rf.assert/dispatched? [:counter/inc]]]]
```

<!-- SCREENSHOT S5: the recorder toolbar (record/stop) on the canvas, and the export modal showing the emitted EDN :script body. -->

!!! note "One scoped spelling note"

    The recorder currently emits the older **`:play-script`** spelling for the slot,
    which the registrar lowers to `:script` on registration. This is a mechanical
    pre-alpha rename, not a compatibility layer you have to reason about — paste the
    body, and it runs as a `:script`. This is the *only* place in the tutorial that
    spelling appears; treat what you author as `:script` everywhere.

A subtlety worth catching: the recorder puts the dispatches in `:script`, not `:setup`. That's correct, and it matters for `:rf.assert/dispatched?` — that assertion's accumulator only observes dispatches during the script phase, not during setup. If the increments lived in `:setup`, the dispatch-trace listener wouldn't have seen them and the assertion would fail. The recorder gets this right because it captures from the user-interaction phase; the general rule for hand-authored variants is **assertions about dispatches only see the script, not the setup.**

## Redaction at the recorder boundary

If your interaction touches a sensitive payload — typing a password, submitting credentials — the recorder does not bake the secret into the generated EDN. An event whose handler is marked `:sensitive?` has its payload dropped, and path-marked sensitive slots become `:rf/redacted` in the snippet. Crucially, **the row position is preserved** — the step is still there, in order, so the script still reads correctly; only the secret value is gone.

```clojure
;; Recording a login flow — the password never lands in source.
:script [[:dispatch-sync [:auth/login [:rf/redacted]]]
         [:dispatch-sync [:rf.assert/path-equals [:auth :status] :authenticated]]]
```

You cannot accidentally commit a password by recording yourself signing in. The same privacy contract that governs what an agent or a shared artifact sees ([chapter 8](08-snapshot-identity-and-sharing.md)) governs the recorder.

## `:cannot-run` — the third result state

<!-- SCREENSHOT S7 (net-new thesis shot): a Test-mode result row showing the distinct :cannot-run status (neutral warning, not red) on a DOM step refused by the headless runner — visibly NOT a pass and NOT a fail. NOTE: per 018 §12.6 cannot-run must remain distinguishable from pass/fail/error in colour, icon, text, and shape; confirm the status chip renders before leaning this shot on it. -->

Now the honest part. A run resolves to one of three states for any given step or assertion — and the third one is the point of this chapter.

Not `:pass`. Not `:fail`. **`:cannot-run`** — meaning "the runner could not observe the evidence this step or assertion needs." It is a distinct status, never folded into pass or fail. A headless runner asked to perform a `[:click "[data-test=submit]"]` does not quietly skip it and report pass; it does not under-flush and pretend; it **refuses**, fail-closed, and records:

```clojure
{:status           :cannot-run
 :required-runner  #{:dom}                  ; what the step needs
 :available-runner #{:app-db :effects ...}  ; what the runner has
 :missing          #{:dom}                  ; the gap
 :reason           :runner-lacks-capability
 :runner           :headless
 :unit             [:click "[data-test=submit]"]}
```

Sit with why this is the anti-false-green primitive. The worst possible behaviour for a workshop is to *silently green-light a DOM assertion it never actually ran.* That's the failure mode that erodes all trust in a test suite — the green that means nothing. `:cannot-run` is the structural refusal to produce that green. If the runner can't prove it, the runner says so, loudly, in a third colour.

## The runner ladder — why a step might not run

`:cannot-run` happens because runners have different capabilities, and a step might need one the chosen runner lacks. The runners are cost-ordered, cheapest to richest:

```
:headless  →  :hiccup  →  :cljs-reactive  →  :dom  →  :browser
```

Each advertises a *set* of capability tokens, not a single tier number (because the capabilities are genuinely orthogonal — hiccup structure and reactive counts don't subsume each other):

| Runner | Proves (cumulative) |
|---|---|
| `:headless` | `:app-db` `:effects` `:schema` `:trace` `:pure-subs` |
| `:hiccup` | + `:hiccup-structure` |
| `:cljs-reactive` | + `:reactive-counts` (sub recompute / render counts) |
| `:dom` | + `:dom` (clicks, focus, visibility) |
| `:browser` | + `:pixels` `:a11y-engine` (screenshots, a11y) |

Each step and assertion declares the tokens it needs. The plan's `:required-runner` is the *union* of all of them. A runner is **valid** if its token set is a superset of the requirement; the **cheapest** valid runner wins. So:

- The seven `:rf.assert/*` assertions need `:app-db` / `:trace` → satisfied by `:headless`.
- `:rf.assert/schema-error` needs `:schema` → still `:headless`.
- A `[:click …]` step needs `:dom` → `:cannot-run` under `:headless`, runs under `:dom`.
- A visual snapshot needs `:pixels` → only `:browser`.

And one assertion not dragging the rest along: under the default fixed-runner policy, a single `:visual-snapshot` that needs `:browser` does *not* escalate a 95%-headless variant to the browser. That one assertion records `:cannot-run`; the rest run headless and pass. You ask for escalation explicitly (`{:runner :auto}`) when you want the cheapest runner that can satisfy *everything*.

## The determinism gate

One more refusal, and it's a good one. A bare `[:wait 300]` — a wall-clock sleep — is the explicit opt-out from determinism, and the determinism gate **refuses** a script containing one with `:cannot-run` rather than running it flakily. The deterministic alternative is `[:wait-until predicate]`:

```clojure
[:wait-until [:db [:cart :status] :ready]]   ; deterministic — settle on a condition
;; not
[:wait 300]                                   ; refused — flaky wall-clock sleep
```

`[:wait-until …]` settles on a real condition (a db path reaching a value, the queue draining) and times out *readably* if the condition never holds — never a silent pass. Prefer it always; reach for `[:wait ms]` only when you've genuinely accepted the flakiness, and know the gate will flag it.

## The aggregation rule and CI policy

How does `:cannot-run` roll up? The rule is stated once and applied everywhere: **a variant whose only unmet assertions are `:cannot-run` is itself `:cannot-run`** — not a silent pass. The precedence is `:error` > `:fail` > `:cannot-run` > `:pass`, so a real failure is never masked by a refusal.

What CI *does* with a `:cannot-run` is a per-gate policy decision: a headless gate might treat the browser-only assertions as skip-and-route-to-the-browser-gate; a strict gate might fail on any `:cannot-run`. The tool's job is to report the third state honestly; the gate's job is to decide what it means for *that* gate. Either way, nothing green-lights a proof that wasn't produced.

## Where we go next

The recorder gave you the *what* — the sequence of things that happened. When a run goes red, you want the *why*: which step broke, against which committed state, and what cascade led there. That's the moment Xray earns its keep — and the moment it stops being optional. [Chapter 6](06-xray-earned-at-failure.md): evidence, earned at the moment of failure.
