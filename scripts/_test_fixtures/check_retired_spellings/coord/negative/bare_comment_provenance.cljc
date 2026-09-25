(ns fixture.coord.negative.bare-comment-provenance
  "NEGATIVE fixture: the THREE places the shipped package names a prototype
  coordinate BARE — no backticks — in a comment or a docstring. Reproduced
  verbatim from
  `implementation/fresco/src/re_frame/fresco/impl/{state.cljc,presence_react.cljs}`.

  Rule (d) masks comments rather than allowlisting any of these. Scanning
  the real surface with comments unmasked finds three: the section header
  in state.cljc, and two `[[wiki-link]]` doc references whose `[` grants
  token start exactly as the header's `(` does. Any allowlist would grow
  with the next provenance comment someone writes, so masking comments
  is the rule, and this fixture pins that rule to the corpus it
  describes.

  All three are prose provenance into a frozen prototype tree, kept verbatim by
  the freeze manifest, carried by NO refusal.")

;; ---------------------------------------------------------------------------
;; Errors — the lane's shape (front.presence/fail!)
;; ---------------------------------------------------------------------------

(defn step
  "The React half of the presence machine.

  that is what happens here — [[front.presence/step]] is idempotent, so"
  [state]
  state)

(defn settle [state]
  ;; The fix is the machine's own [[front.presence/settle]], the
  state)
