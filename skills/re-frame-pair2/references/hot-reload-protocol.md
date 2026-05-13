# Hot-reload protocol

Editing source is legitimate and often correct. The protocol is strict:

1. Make the edit with `Edit` / `Write`.
2. Call `mcp__re-frame-pair2__tail-build` with a `probe` that verifies the browser has the new code (legacy fallback: `scripts/tail-build.sh --probe '...'`):
   - If you edited a `reg-*` handler, the probe is `(re-frame-pair2.runtime/registrar-handler-ref <kind> <id>)` — compares a hash over `handler-meta`.
   - If you edited a `reg-machine`, the probe is the same shape against `:event` (machines register under `:event` per Spec 005).
   - If you edited a view or helper, the probe is a short form that derefs a value depending on the edited code.
   - If no good probe is available, omit `probe` and accept the soft/timer-based confirmation.
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

For the underlying `tail-build` invocation and probe choices, see the [Hot-reload coordination](ops.md#hot-reload-coordination) section in `ops.md` (the bash-shim form is catalogued there too, in the back-compat appendix).
