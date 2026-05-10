<!-- TODO rf2-z1f8: rewrite this README to parity with examples/uix/README.md
     (layout diagram, per-example demonstrations, running instructions, cross-references). -->
# Helix examples (rf2-2qit)

Smoke trio for the Helix adapter — counter + login (per rf2-2qit
Decision 7, transferred from rf2-3yij). Each example mirrors the
Reagent and UIx counterparts (`examples/reagent/counter`,
`examples/uix/counter_uix`) so the bundle-isolation grep can confirm
adapter-specific code is structurally absent from the wrong bundle.

Helix is the third canonical browser substrate alongside Reagent and
UIx. Per Spec 006 §CLJS reference: Helix as alternative substrate, the
adapter ships in `day8/re-frame-2-helix` and provides `use-subscribe`,
`flush-views!`, `wrap-view`, and `frame-provider`.
