# 9. Multi-substrate and the agent loop

> **What you'll build.** Run the same variant under Reagent, UIx, and Helix; then hand the whole catalogue to an agent over MCP and watch it drive the workshop the way you do. This chapter delivers the last two faces — *agent* and the full *multi-substrate* payoff — and then walks back through all six.

## The same artifact, three substrates, one model

The promise we made all the way back in [chapter 1](01-first-variant.md#the-pillar-every-variant-is-a-frame) — *variant bodies are data, never functions* — has been quietly accumulating interest this whole time, and here is where it pays out in full.

Because a variant body has *no substrate-specific code* in it — no JSX, no `useState`, no Reagent ratom, just EDN naming a view-id and some events — the *same* `:story.counter/empty` can render under Reagent, UIx, or Helix. You don't write three versions. You write the behaviour once and pick the renderer at the toolbar:

```clojure
(story/reg-story :story.counter
  {:component  :my-app.views/counter-card
   :args       {:label "Count"}
   :substrates [:reagent :uix :helix]})
```

"Write the behaviour once; pick the renderer at the toolbar." That sentence is only *possible* because the body never became a function. A Storybook story is a render fn bound to React; it cannot flip substrates because the substrate is baked into the function. A Story variant is data, so the substrate is just one more world input.

## Multi-substrate

<!-- SCREENSHOT S13: the substrate toolbar switch on a variant, and (login is the ch9 multi-substrate example) the same login variant rendered under Reagent vs UIx vs Helix. NOTE: v1 ships Reagent; the UIx/Helix toolbar switch is the contract the matrix is built on. -->

The substrate toolbar switch flips the active renderer for the selected variant. The per-substrate adapter smoke test — mount, dispatch, assert — is exactly one smoke per adapter (Reagent / UIx / Helix), which is the right shape: the smokes prove the adapter boundary works; the real behavioural regression coverage lives in the headless substrate contract tests ([chapter 4](04-the-variant-is-a-test.md#run-it-from-a-unit-test)), not in per-example browser tests.

The conceptual point that makes this honest: the substrate is a **`:world` input**, *not* part of the plan-hash's behavioural slice. So a `variant × substrate` matrix is genuine visual-regression coverage — same behaviour, three renderers, three keyed snapshots ([chapter 8](08-snapshot-identity-and-sharing.md)) — rather than three subtly-different things you have to keep in sync by hand.

> **Today:** the `:include-story?` scaffold and the shipped examples are Reagent
> at v1; UIx and Helix variants follow as Story's adapter coverage catches up. The
> `:substrates` slot and the toolbar switch are the contract the multi-substrate
> matrix is built on.

## One model, three entry points

Step back and notice the structural claim underneath everything. The same normalized plan ([chapter 4](04-the-variant-is-a-test.md#the-four-bucket-plan-a-peek-under-the-hood)) is reachable through **three doors**, and none is privileged:

- the **interactive shell** — a human at `#/stories`;
- **`cljs.test` / CI** — `story/run` headless;
- **MCP** — an agent.

All three consume the *same* unified run-result and the *same* epoch tape. There isn't a "real" path and a "test harness" path and an "agent" path that approximate each other. There is one plan, and three ways to ask it to run.

## The agent loop — MCP over the Tool-Pair contract

The newest face. Story is the *share* and *agent* faces of the six, and the agent face is exposed over MCP through re-frame2's Tool-Pair contract: an agent attaches to a running app and drives the same surface a human drives.

The tool inventory an agent has, drawn from the live MCP surface:

| Tool | What it does |
|---|---|
| `discover-app` | find the running app and its frames |
| `list-handlers` / `list-subscriptions` | enumerate the registered events and subs |
| `dispatch` | dispatch an event into a frame |
| `get-path` | read a path out of `app-db` |
| `snapshot` | capture the current state |
| `subscribe` / `unsubscribe` | watch a subscription's value |
| `watch-epochs` | observe the epoch stream as it commits |
| `restore-epoch` | time-travel to a prior epoch |
| `trace-window` | read a window of the trace stream |
| `reset-frame-db` | reset a frame's `app-db` |
| `eval-cljs` | evaluate ClojureScript in the running app |

This is the same surface from the human chapters, transposed into tools. And critically, **an agent reads through the same redacted boundary the share face uses.** Where a `:sensitive?` slot lived, an agent gets `:rf/redacted` — the AI/MCP boundary *is* the privacy boundary the whole tool has been consistent about ([chapter 5](05-recorder-and-cannot-run.md#redaction-at-the-recorder-boundary), [chapter 8](08-snapshot-identity-and-sharing.md#sharing-a-reproduction--honest-redaction)). An agent doesn't get a privileged peek at secrets; it sees exactly what a shared artifact would.

Watch an agent run the nine_states load cascade — and notice it's the *same scrub a human did in [chapter 6](06-xray-earned-at-failure.md)*, just driven by a model:

```
discover-app                                  ; find the running app
dispatch  [:nine-states.story/load {:n 4}]    ; kick off the fetch
watch-epochs                                  ; observe the cascade commit
get-path  [:ui/render]                        ; -> :some (the bucket the cascade chose)
restore-epoch <resolving-epoch-id>            ; time-travel into the :resolving microstep
```

Discover, dispatch, watch the cascade, assert the bucket, restore-epoch into a microstep to inspect it. The agent isn't using a special agent-only API; it's driving the human workshop through a mirror.

<!-- SCREENSHOT S10: an agent (Claude / Cursor) driving the Story workshop over MCP — the tool-call transcript (discover-app → dispatch → watch-epochs → get-path) beside the canvas it's manipulating, with a :rf/redacted slot visible where a :sensitive? value lived. NOTE: the agent face reads the same redacted egress boundary as share (018 §5.1, T3); the human-visible operations are the product source of truth. -->


## The six faces, revisited

We opened the [index](index.md#the-thing-thats-actually-different--one-artifact-many-faces) with a promise: one artifact, one plan, six faces. Every face has now been earned. Walk back through them and point at where:

| Face | Earned in | What it is |
|---|---|---|
| **canvas** | [ch 1](01-first-variant.md)–[2](02-every-state-side-by-side.md) | the rendered state, side-by-side in grids |
| **doc** | [ch 7](07-workspaces-modes-composition.md#mode-tabs--dev--docs--test) | Docs mode — variants as executable documentation |
| **test** | [ch 4](04-the-variant-is-a-test.md) | `run` / `is` — the variant runs as an assertion-bearing test |
| **replay** | [ch 6](06-xray-earned-at-failure.md#time-travel--the-scrub-backbone) | the evidence spine + time-travel scrub |
| **share** | [ch 8](08-snapshot-identity-and-sharing.md) | a content-keyed, honestly-redacted reproduction |
| **agent** | this chapter | the same workshop, driven by a model over MCP |

The win was never any *one* of these faces. Plenty of tools have a canvas; plenty have a test runner; a few have a recorder. The win is that they are the **same artifact**, lowered through the **same plan** — so they *cannot drift*. Your docs can't lie about your component, because they *are* your component. Your test can't pass while the canvas shows something else, because they're the same run. The agent can't see a state a human couldn't, because it reads the same boundary. One artifact is one truth, wearing six faces.

## A slim testing appendix

Three Story-specific things to keep in your pocket once you're authoring tests in anger:

- **Inline plans.** You don't have to register a variant to run one. `story/run`, `story/is`, and `story/explain` all accept a *map* target — an inline plan written on the spot, executed through the same compiler, never added to navigation. Use it for a one-off behavioural assertion that doesn't deserve a permanent variant.

  ```clojure
  (story/is {:setup      [[:counter/initialise 5]]
             :script     [[:dispatch [:counter/inc]]]
             :assertions [[:rf.assert/path-equals [:count] 6]]})
  ```

- **The variant *is* the test.** The same `reg-variant` body you render is the thing you run ([chapter 4](04-the-variant-is-a-test.md)). Tag it `:test`, and Story runs it headless in CI and lights its sidebar dot — no separate test file.

- **`:cannot-run` is not a pass.** When an assertion needs evidence the runner can't observe ([chapter 5](05-recorder-and-cannot-run.md)), it returns the distinct third state. Define your CI gate's `:cannot-run` policy deliberately; never let it silently green.

!!! info "Substrate-level testing recipes live in the Guide"

    This appendix is deliberately *Story-specific*. The broader recipes for testing
    the re-frame2 substrate itself — testing a handler, a subscription, a view, a
    machine, a route, a schema — are not Story's job and live in the framework
    **Guide's testing chapter**: [Guide — Testing](../guide/13-testing.md). Story
    builds *on* that substrate; it does not replace it. Reach for the Guide when the
    thing under test is a handler or a sub; reach for Story when the thing under test
    is a *variant*.

## Where to go from here

- The normative testing contract: [`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md).
- The UI north-star (and the honest status of what ships today): [`018-Story-UI-North-Star.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/018-Story-UI-North-Star.md).
- The reader's lookup sheet: the [API reference](api/index.md).
- The examples: the counter as the teaching backbone (`tools/story/testbeds/counter_with_stories`), nine_states and login as the showcases (`examples/reagent/`).
- The sibling tool Story hands diagnostics off to: the [Xray guide](../xray/index.md).
