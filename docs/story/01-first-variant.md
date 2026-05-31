# 1. Your first variant

> **What you'll build.** A working `:story.counter` with one variant, `:story.counter/empty`, that initialises a counter to zero, renders it on the canvas, and asserts that `app-db` landed where you expected. Two macro calls and a route. By the end you'll have the first face — *canvas* — on screen.

## The dopamine of the first paint

I remember the first time I ran `storybook init` back in 2017. You typed one command, a browser tab opened, and there was your button — *your button* — sitting in a clean little sandbox with controls down the side. It was a genuine dopamine hit, and I don't think that's incidental. Developer tools live or die on the first-render gesture. If the distance between "I have a component" and "I can see it isolated, poke it, and screenshot it" is more than about a minute, nobody adopts the tool, no matter how clever it is underneath.

Story aims for the same hit: roughly three lines of EDN, one variant, one canvas paint. So let's get there, and then we'll take the machine apart to see why it works.

And yes — it's another counter example. We are not above this. The counter is the smallest domain that still lets every abstraction in Story show its real shape, which is exactly what you want from a first example: nothing to distract you from the mechanism.

## The smallest interesting stories.cljs

Here is a complete, working story with one variant. This is drawn verbatim from the canonical [`counter_with_stories`](https://github.com/day8/re-frame2/tree/main/tools/story/testbeds/counter_with_stories) testbed — every snippet in this tutorial is valid against the shipped tool.

```clojure
(ns my-app.stories
  (:require [re-frame.story :as story]
            ;; Requiring these namespaces fires their reg-* calls, so the
            ;; event-ids and view-ids the variant references resolve.
            [my-app.events]
            [my-app.subs]
            [my-app.views]))

(story/reg-story :story.counter
  {:doc        "The counter — every variant of the canonical example."
   :component  :my-app.views/counter-card
   :args       {:label "Count"}
   :tags       #{:dev :docs}
   :substrates #{:reagent}})

(story/reg-variant :story.counter/empty
  {:doc    "Fresh counter at zero. The simplest possible variant."
   :setup  [[:counter/initialise 0]]
   :script [[:dispatch-sync [:rf.assert/path-equals [:count] 0]]]
   :tags   #{:dev :docs :test}})
```

Route `#/stories` to the shell (you did this in the [install step](index.md#adding-story-to-an-existing-app)), open the page, and `:story.counter/empty` is sitting in the sidebar. Click it. The canvas paints a counter at zero.

<!-- SCREENSHOT S1: the shell immediately after the variant loads — sidebar tree with /empty selected, canvas painting the counter, mode-tab strip above the canvas. -->

That's the first face. Now let's understand what each call did.

## What `reg-story` does, and doesn't

The parent story is a **logical grouping**, nothing more. It names the component, supplies default args, carries decorators and tags, and — importantly — **holds no state of its own**. Think of it as the README for a folder of scenarios: it says "all of these are counters, they all render `counter-card`, they all default `:label` to `"Count"`," and then it gets out of the way.

The id grammar is locked and load-bearing: `:story.<dotted-path>` for a story, `:story.<path>/<variant>` for a variant. **The keyword *is* the structure.** There is no `:title`, no `:folder`, no `:hierarchy` string. If you've ever stared at a Storybook `title: 'Components/Buttons/Primary/Default'` and wondered which slash was the meaningful one, you'll appreciate that a namespaced keyword carries the same hierarchy with none of the stringly-typed ambiguity.

The single most important field is `:component`:

```clojure
:component :my-app.views/counter-card
```

That is a **view-id keyword, not a function reference.** You are not handing Story a `counter-card` symbol; you are handing it the *name* of a registered view. This is most of why variant bodies stay pure data — and why everything downstream (round-tripping over MCP, keying visual-regression, letting an agent author a variant) is even possible. A function can't travel over a wire; a keyword can.

## What `reg-variant` does — the three meat slots

A variant is where the actual scenario lives. Three slots carry the weight:

- **`:setup`** — a vector of event vectors, dispatched in source order through the *real* router. These run before the canvas paints (lifecycle phase 2). They establish the precondition: by the time you see the canvas, the variant frame's `app-db` is exactly what those events left behind. In our example, `[[:counter/initialise 0]]` puts the counter at zero — the same handler your live app uses, no shortcut.

- **`:args`** — prop overrides, deep-merged onto whatever the parent story set. `:story.counter/empty` doesn't set `:args`, so it inherits `{:label "Count"}` from the parent.

- **`:script`** — the behaviour under test. It runs *after* setup settles and the canvas mounts. It's a vector of tagged step tuples: `[:dispatch-sync …]`, `[:dispatch …]`, `[:click …]`, `[:type …]`, `[:wait-until …]`, `[:assert …]`. The canonical `:rf.assert/*` assertions ride the `:dispatch-sync` rail — that's the `[:dispatch-sync [:rf.assert/path-equals [:count] 0]]` you see above.

!!! note "Note for Storybook refugees"

    Storybook's `play` is JavaScript — you write an `async` function that calls
    `userEvent.click(...)` and `await expect(...).toBeVisible()`. Story's `:script`
    is *data* — a vector of step tuples a runner interprets. Same role, different
    substance. And the data shape is exactly why the recorder
    ([chapter 5](05-recorder-and-cannot-run.md)) can *generate* a `:script` for you
    from your interactions. Try generating a TypeScript function from a recording
    and you'll appreciate the difference.

## The pillar: every variant *is* a frame

Here is the idea to internalise before anything else. In Storybook, the answer to "my story is stateful" is `useArgs()`, a render function, and the well-known hooks-inside-stories gotcha. The state has to live *somewhere*, and Storybook's somewhere is component closures and React state, which means every stateful story is a small negotiation with React's rules.

Story's answer is structural, not a feature: **each variant runs in its own frame.** Fresh `app-db`, fresh queue, fresh sub-cache, fresh interceptor chain. There is no render function to write, no hooks to break, no shared mutable state to leak. A stateful story isn't a *mode* you opt into — it's the default, because state in re-frame2 lives in `app-db` and every variant gets its own.

If you arrive from Storybook expecting to write a render fn, this is the sentence to tattoo on the inside of your eyelids: **frames, not hooks.** The view you wrote for your live app is *exactly* the view Story renders. No parallel `Counter.stories.tsx`; no second source of truth.

## The args chain (briefly)

Args resolve through a deep-merge precedence ladder:

```
global  <  mode  <  story  <  variant  <  cell-override
```

Later wins. Maps deep-merge; vectors replace. So a variant's `:args` override the story's, a toolbar mode's args sit under the story's, and a live edit in the controls panel (a cell-override) wins over everything. We'll keep this light here — the full treatment, including the live controls panel, is [chapter 2](02-every-state-side-by-side.md#controls-that-derived-themselves), and toolbar modes are [chapter 7](07-workspaces-modes-composition.md). For now: variant args beat story args, and that's the case you'll hit first.

## The seven canonical assertions

You've already used one (`:rf.assert/path-equals`). Here's the full canonical set. You don't register these — they auto-install at Story load — and they're ordinary re-frame2 events, dispatched through the real pipeline.

| Assertion | Payload | Checks |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` — the workhorse. |
| `:rf.assert/path-matches` | `[path schema]` | the value at `path` validates against a Malli schema. |
| `:rf.assert/sub-equals` | `[query-vec expected]` | a subscription's *computed* value equals `expected`. |
| `:rf.assert/dispatched?` | `[event-vec]` | this event was dispatched during the script. |
| `:rf.assert/state-is` | `[machine-id state]` | a `reg-machine` machine's active state. |
| `:rf.assert/no-warnings` | `[]` | no `:rf.warn/*` events fired — "did anything misbehave?" |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | this fx was emitted during the script. |

The behaviour worth dwelling on is **record-don't-throw**. Throwing on the first failed assertion would be a terrible fit for a visual playground — you'd lose every assertion after the broken one, exactly when you most want the full picture. So every `:rf.assert/*` records its result and the script keeps going; the runner reports the aggregate at the end. This mirrors what devcards did well and diverges from Storybook's throw-on-first-failure (which, to be fair to Storybook, is partly forced on it by JavaScript's async-throw mess — re-frame2's run-to-completion drain gives Story room to do better).

That this makes your variant *a test* is the reveal of [chapter 4](04-the-variant-is-a-test.md). For now, just notice that you wrote an assertion and nothing exploded.

## Decorators — the one place a closure lives

Sometimes a variant needs to be wrapped (a theme provider), have its frame patched at allocation, or have an effect stubbed. Decorators do this, and they come in three kinds:

- **`:hiccup`** — wrap the rendered tree. The wrapping fn (a closure) lives at the decorator's *registration site*, not in the variant body.
- **`:frame-setup`** — patch the frame at allocation time.
- **`:fx-override`** — stub an effect. Story ships one primitive, `force-fx-stub`, that can intercept *any* registered fx:

```clojure
(story/reg-variant :story.counter/save-stubbed
  {:doc        "The save flow with the network fx stubbed."
   :setup      [[:counter/initialise 5]]
   :decorators [[story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
   :script     [[:dispatch-sync [:counter/save]]
                [:dispatch-sync [:rf.assert/path-equals    [:saving?] true]]
                [:dispatch-sync [:rf.assert/effect-emitted :counter/sync-to-server]]]
   :tags       #{:dev :test}})
```

Storybook's instinct here is to ship an addon per concern (one for network mocking, one for routing, one for…). Story has the *one* primitive, because re-frame2 already names the seam: effects are data with ids, so stubbing one is `{fx-id replacement}`. We'll come back to decorators properly in [chapter 7](07-workspaces-modes-composition.md#composition--context-flows-down-verdict-is-local).

## What you should see now

A checklist for "it worked":

- `:story.counter/empty` shows up in the sidebar tree.
- Clicking it paints a counter at zero on the canvas.
- The mode-tab strip (*Dev / Docs / Test*) sits above the canvas.
- A controls row shows up (we'll make it interesting in chapter 2).
- The sidebar dot next to the variant goes green — the `:test`-tagged variant ran its script in the background and its one assertion passed.

## When it doesn't work

- **Empty sidebar.** You forgot to `:require` your stories namespace from somewhere on the live load path. Loading the namespace is what fires the `reg-*` calls; nothing requires it, nothing registers.
- **Blank canvas.** The `:component` view-id doesn't resolve. Check the keyword matches a registered `reg-view`.
- **"No assertions recorded" in Test mode.** Either you wrote the script step as a bare event vector — `[:rf.assert/path-equals [:count] 0]` — instead of wrapping it in a step — `[:dispatch-sync [:rf.assert/path-equals [:count] 0]]` — or you used the wrong slot. The public play slot is `:script`.
- **Missing controls.** The view has no Malli schema and no `:argtypes`, so Story can't derive widgets. ([Chapter 2](02-every-state-side-by-side.md#controls-that-derived-themselves) explains the schema → controls path.)
- **Nothing renders in production.** That's correct behaviour, not a bug — elision short-circuits Story in `:advanced` production builds.

## Where we go next

You've been staring at *one* state. The whole point of a workshop is to stop doing that — to see every state of a component on one screen at once. [Chapter 2](02-every-state-side-by-side.md): the wall of nine, and controls that built themselves out of a schema.
