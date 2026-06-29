# Machines

Some features aren't a value — they're a *lifecycle*. A login flow runs `idle → submitting → authed | error`; a checkout is a chain of gates; a long-running job spawns workers, collects results, and tears itself down. Model that lifecycle with scattered boolean flags (`:loading?`, `:submitting?`, `:error?`) and it drifts out of sync and sprouts impossible states ("submitting *and* error"). A **machine** makes the lifecycle explicit: named states, named transitions, exactly one state at a time.

re-frame2 machines are statechart-capable — the XState v6 feature set (guards, actions, entry/exit, timeouts, parallel regions, spawned children) — and registered like everything else, with `reg-machine`. A machine's live value is a [snapshot](glossary.md#snapshot) held in the framework's [runtime-db](../core/glossary.md#runtime-db); you read it through an ordinary subscription and drive it with ordinary dispatched events.

```clojure
(rf/reg-machine :auth/login
  {:initial :idle
   :states  {:idle       {:on {:submit :submitting}}
             :submitting {:on {:ok   :authed
                               :fail :error}}
             :authed     {}
             :error      {:on {:submit :submitting}}}})

@(rf/subscribe [:rf/machine :auth/login])   ;; => {:state :idle :data {}}
```

You dispatch events to drive the transitions; the snapshot moves, and the views that read it follow. [Concepts](concepts.md) walks the whole model from here.

## In this section

**Start here**

- **[Concepts](concepts.md)** — the core model: states, transitions, guards, actions, the `{:data :fx}` effect map, the snapshot, and testing transitions as pure calls.

**Structuring states**

- **[Hierarchical states](hierarchical-states.md)** — nested compound states, initial cascading, deepest-wins resolution, entry/exit along the LCA, nested final states.
- **[Parallel states](parallel-states.md)** — `:type :parallel` regions: orthogonal concurrent state, transition broadcast, cross-region coordination via tags.

**Transitions and behaviour**

- **[Automatic transitions](automatic-transitions.md)** — `:always` (eventless), `:type :choice`, `:after` (delayed), and `:timeout`.
- **[Actors](actors.md)** — `:spawn` / `:spawn-all` child machines, fan-out + join, cooperative cancellation, cross-machine messaging.
- **[Tags](tags.md)** — semantic state labels, `:rf/machine-has-tag?`, and collapsing many states into one render decision.
- **[History](history.md)** — `:type :history` (shallow / deep): re-enter a compound state where you left it.

**Tooling and reference**

- **[Inspecting and testing](inspecting-machines.md)** — the Xray Machine Inspector, machine trace events, and pure-transition unit tests.
- **[Coming from XState](coming-from-xstate.md)** — the XState v6 → re-frame2 mapping, and the deliberate divergences.
- **[API](../api/re-frame.machines.md)** — `reg-machine`, the machine effects and the `:rf/machine` subscription, `machine-has-tag?`.
- **[Glossary](glossary.md)** — the machine vocabulary in one place.
- **[Examples](examples.md)** — runnable worked apps.
