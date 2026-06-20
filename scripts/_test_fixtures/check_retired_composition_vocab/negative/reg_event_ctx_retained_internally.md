# reg-event-ctx "retained internally" note (negative fixture)

The EP-0018 `reg-event-ctx` demotion note says the `context -> context`
mechanism is "retained internally" — a DIFFERENT subject from the realm/app-value
substrate (no realm/app-value/module adjacency on the line). The retained-claim
family must stay GREEN here: mirrors spec/001-Registration.md and
spec/009-Instrumentation.md.

`reg-event-ctx` is demoted to a framework-internal primitive — the
`context -> context` mechanism it exposed is retained internally (it is what
`reg-event` lowers onto and what subsystem dispatchers use) but is no longer a
public application-authoring form.
