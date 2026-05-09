# Helix examples (rf2-2qit)

Smoke trio for the Helix substrate adapter — counter + login (per
rf2-2qit Decision 7, transferred from rf2-3yij). Each example mirrors
the Reagent and UIx counterparts (`examples/reagent/counter`,
`examples/uix/counter_uix`) so the bundle-isolation grep can confirm
substrate-specific code is structurally absent from the wrong bundle.

The Helix substrate is the third canonical browser substrate alongside
Reagent and UIx. Per Spec 006 §CLJS reference: Helix as alternative
substrate, the adapter ships in `day8/re-frame-2-helix` and provides
`use-subscribe`, `flush-views!`, `wrap-view`, and `frame-provider`.
