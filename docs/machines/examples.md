# Machines examples

Worked, runnable apps — the machine features you read about, running.

New to machines? Build one yourself first — the [Tutorial](tutorial.md) walks you through a login machine step by step — then skim [Concepts](concepts.md) for the whole model. After that, work through the apps below in order: each adds a little more machinery than the last.

- **state_machine_walkthrough** — your first machine, end to end. The Concepts login flow as runnable code: a self-contained transition table, with guards and actions living *with* the spec rather than in a global registry, driven headlessly to show the pure-transition + drain testing story. Read first: [Concepts](concepts.md). [→ source](../../examples/capabilities/machines/state_machine_walkthrough)
- **nine_states** — parallel regions and tags. One `:type :parallel` machine with three orthogonal regions (`:data` / `:form` / `:mode`) and `:tags`; a render-priority table over the tag union collapses all nine canonical UI states into a single `case` in the root view. Read first: [Parallel states](parallel-states.md) and [Tags](tags.md). [→ source](../../examples/patterns/nine_states)
- **long_running_work** — spawning workers, and cancelling them cleanly. Cancellable long-running work via `:spawn-all`: one parent coordinator spawns N parallel worker children, with per-step progress as an internal self-transition and a cooperative cancellation cascade that fires on every exit path — including unmount. Read first: [Actors](actors.md). [→ source](../../examples/patterns/long_running_work)
- **websocket** — the lot, in one machine. The canonical connection machine: a hierarchical compound `:active` state parenting connect/auth/connected, a `:spawn`'d socket actor bound to that state, `:after` exponential backoff, `:always` queue-flush on reconnect, and connection-epoch staleness against the live socket id. Read first: [Hierarchical states](hierarchical-states.md), [Actors](actors.md), and [Automatic transitions](automatic-transitions.md). [→ source](../../examples/patterns/websocket)

One more — not a machine, included only for substrate variety:

- **process_monitor_helix** — a Helix two-pane process monitor. It shows Helix consuming subs via `use-subscribe` and a live `:dispatch-later` tick loop, but has *no* state machine. Reach for it to see the ideas under another substrate, not as a machine example. [→ source](../../examples/substrates/helix/process_monitor)
