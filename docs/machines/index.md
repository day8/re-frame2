# Machines

Some features aren't a value — they're a *lifecycle*. A login flow runs `idle → submitting → authed | error`; a checkout is a chain of gates; a long-running job spawns workers, collects results, and tears itself down. Model that lifecycle with scattered boolean flags (`:loading?`, `:submitting?`, `:error?`) and it drifts out of sync and sprouts impossible states ("submitting *and* error"). A **machine** makes the lifecycle explicit: named states, named transitions, exactly one state at a time.

re-frame2 machines are statechart-capable — the XState v6 feature set (guards, actions, entry/exit, timeouts, parallel regions, spawned children) — and registered like everything else, with `reg-machine`. A machine's live value is a [snapshot](glossary.md#snapshot) held in the framework's [runtime-db](../guide/glossary.md#runtime-db); you read it through an ordinary subscription and drive it with ordinary dispatched events.

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

- **[Concepts](concepts.md)** — states, transitions, guards, actions, snapshots, tags, spawning: how machines actually work.
- **[API](api.md)** — `reg-machine`, the machine effects and the `:rf/machine` subscription, `machine-has-tag?`.
- **[Glossary](glossary.md)** — the machine vocabulary in one place.
