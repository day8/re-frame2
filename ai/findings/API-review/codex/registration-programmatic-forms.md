# Registration And Programmatic Forms

Status: draft finding.

## Crowding Signal

Registration is intentionally broad: events, subs, fx, cofx, views, machines,
routes, resources, mutations, frames, schemas, and interceptors all enter the
program through registration. The crowding appears in the boundary between
author-facing macros, programmatic `*` functions, CLJS aliases, and removed
legacy names.

Current similar or confusing surfaces:

- `reg-event` plus removed `reg-event-db` / `reg-event-fx` stubs;
- `reg-view` and `reg-view*`;
- `reg-machine`, `reg-machine*`, and `defmachine`;
- `reg-interceptor`, `reg-interceptor*`, and `->interceptor`;
- CLJS fn aliases for registration macros in `re-frame.core`;
- app-value/image installation surfaces that also lower registrations.

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:213-295` defines CLJS fn aliases
  for registration macro names.
- `implementation/core/src/re_frame/core.cljc:296-328` keeps hard-error
  removed names such as `reg-event-db` and `reg-event-fx`.
- `implementation/core/src/re_frame/core.cljc:521-599` implements
  `reg-machine` and `defmachine`.
- `implementation/core/src/re_frame/core.cljc:2364-2403` exposes
  `reg-interceptor*` and `->interceptor`.
- `docs/api/README.md:19` states the `*` convention, but not all visible
  registration names follow the same user-facing need.
- `docs/EP/EP-0018-one-event-registration.md` already gives the right
  direction for events: one event registration surface.

## Observed Use Cases

1. Application authors register ordinary program pieces and want source
   metadata for tooling.

2. Reagent views need a macro because the symbol becomes both a Var and a
   registered view id, and because the body gets injected frame-bound locals.

3. Machines need source stamping inside nested transition tables; `defmachine`
   exists for the define-value-then-register shape.

4. Tools and libraries generate registrations programmatically and cannot use
   syntactic macros.

5. Migration code needs removed names to fail loudly with actionable messages.

6. Interceptors need a registered, inspectable form for reusable chains, while
   inline custom context transforms need an escape hatch.

7. Images aggregate registrations as data; they should not feel like a second
   authoring API for the same individual registration.

## Proposed Cleanup

Use one naming law:

- `reg-x` is the author-facing registration form;
- `reg-x*` exists only when there is a real programmatic need;
- a `*` function must be documented as the lower-level value API, not a second
  preferred spelling;
- removed legacy names stay as throwing migration stubs, outside the live API;
- image installation composes registrations but is not another way to author a
  single registration in ordinary code.

For interceptors, pick one public authoring story. The clean version is:

```clojure
(rf/reg-interceptor :audit/log
  {:before before-fn
   :after  after-fn})

(rf/reg-event :save
  {:interceptors [:audit/log]}
  handler)
```

If `->interceptor` remains, document it as an advanced inline escape hatch and
stop presenting it beside `reg-interceptor` as the normal authoring form.

For machines:

```clojure
(rf/defmachine login-machine ...)
(rf/reg-machine :auth/login login-machine)
```

`defmachine` defines a stamped value. `reg-machine` registers it. `reg-machine*`
is the programmatic lowerer. Avoid teaching all three as equivalent choices.

## Why This Is Better

Registration is where re-frame2 turns a program into addressable data. That
only works if the registration language itself is boring. The user should not
need to know which names are macros for source capture, which are CLJS aliases,
which are legacy stubs, and which are programmatic escape hatches until they
actually need that distinction.

The Clojure ethos is not "avoid macros"; it is "use macros where syntax is the
point". Where syntax is not the point, functions over maps are easier to
compose, generate, test, and inspect.
