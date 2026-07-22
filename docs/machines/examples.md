# Examples

<a id="machines-examples"></a>

Runnable apps that exercise the machine grammar. Build a machine yourself first
([tutorial](tutorial.md) — ends with a complete login table), then skim
[the model](concepts.md). Work these apps in order — each adds machinery.

| Example | What it shows | Read first |
|---|---|---|
| [state_machine_walkthrough](../../examples/capabilities/machines/state_machine_walkthrough) | Flat login-style table; guards and actions co-located; pure `machine-transition` tests — the tutorial's cousin in-repo | [The model](concepts.md) |
| [nine_states](../../examples/patterns/nine_states) | One parallel machine, three regions; tags collapse 3×3×3 UI into one view decision | [Tags](tags.md), [Parallel regions](parallel-states.md) |
| [long_running_work](../../examples/patterns/long_running_work) | Parent spawns N workers with `:spawn-all`; cooperative cancel on every exit path | [Actors](actors.md) |
| [websocket](../../examples/patterns/websocket) | Hierarchical connection machine; spawned socket actor; `:after` backoff; `:always` flush | [Hierarchical states](hierarchical-states.md), [Automatic transitions](automatic-transitions.md), [Actors](actors.md) |
