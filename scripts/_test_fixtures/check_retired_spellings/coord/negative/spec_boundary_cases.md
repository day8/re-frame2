# NEGATIVE fixture (d, Markdown surface) — every boundary, unmasked

Markdown is the surface where rule (d) has NO comment or string masking to fall
back on, so it is the honest place to prove that each constraint in the pattern
is doing its own work rather than riding on the mask. Every line below names a
prototype coordinate and every one of them must stay green.

* **A backtick denies token start.** `front.codec/realize-deep` and
  `arm1.mount/render!` are provenance prose — the overwhelmingly common in-tree
  spelling, and the one the fix hint tells authors to use.
* **A preceding `.` denies token start.** The honest fully-qualified
  re-frame.bench.hicasso.front.slot-cljs-test names the prototype tree
  truthfully; it is not a coordinate anyone could mistake for a shipped one.
  Same for re-frame.bench.hicasso.arm1.hydrate-dom-cljs-test.
* **The namespace dot is mandatory.** arm1/host_hatch_dom_cljs_test is the
  pervasive shorthand for a FILE in the prototype's arm1 directory, not a
  namespace coordinate. Roughly twenty comments spell it that way.
* **The `/` is mandatory.** front.sub-index, front.dogfood and front.intent name
  retired MODULES in provenance prose and resolve to nothing executable.
* **The string shape needs the coordinate to open the literal.** A whole
  literal that is a *backticked* coordinate, "`front.codec/root-element`", is
  denied by both patterns at once.

The shipped spelling, for contrast:
`re-frame.hicasso.impl.collector/frame-prop-shell`.
