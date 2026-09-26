# Examples

<a id="machines-examples"></a>

Runnable machines in the repo. Pair each app with the guide pages that
name the same machinery.

| Example | What it shows | Pair with |
|---|---|---|
| [state_machine_walkthrough](../../examples/capabilities/machines/state_machine_walkthrough) | A small transition table; guards and actions kept with the spec; pure `machine-transition` tests; the same machine value in the browser and on the JVM | [First machine](tutorial.md), [The table](concepts.md) |
| [login](../../examples/core/login) | The first machine's `:auth.login/flow` as a working app: `:schemas` on the machine's `:data` and its event, managed HTTP answered by an in-page stub, a controlled form, and a session token that never enters the machine | [First machine](tutorial.md) |
| [boot](../../examples/patterns/boot) | App start-up as a machine: one `:spawn`ed loader for config, a `:spawn-all` fan-out joined with `:join :all`, a failure state with retry, and a UI that mounts only at `:ready` | [Actors](actors.md), [Automatic transitions](automatic-transitions.md) |
| [nine_states](../../examples/patterns/nine_states) | Parallel regions for orthogonal UI axes; tags as render semantics; a `render-priority` table; one view branch instead of a cross-product | [Parallel regions](parallel-states.md), [Tags](tags.md) |
| [long_running_work](../../examples/patterns/long_running_work) | A parent `:spawn-all` of N workers; `:join :all`; progress as an internal self-transition; cooperative cancel and teardown on every exit | [Actors](actors.md), [Automatic transitions](automatic-transitions.md) |
| [websocket](../../examples/patterns/websocket) | Hierarchical connection states; a `:spawn`ed socket actor; `:after` exponential backoff; `:always` queue flush; tags for status; `:current-socket?` drops events from a replaced socket | [Hierarchical states](hierarchical-states.md), [Actors](actors.md), [Automatic transitions](automatic-transitions.md) |
