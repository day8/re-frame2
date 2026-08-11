(ns fixture.coord.negative.bare-comment-provenance
  "NEGATIVE fixture: the THREE places the shipped package names a prototype
  coordinate BARE — no backticks — in a comment or a docstring. Reproduced
  verbatim from
  `implementation/hicasso/src/re_frame/hicasso/impl/{state.cljc,presence_react.cljs}`.

  rf2-r4jy's brief named ONE of these (the state.cljc section header) and asked
  whether to allowlist it or mask comments generally. Scanning the real surface
  with comments unmasked answers it: there are three, the other two are
  `[[wiki-link]]` doc references whose `[` grants token start exactly as the
  header's `(` does, and any allowlist would grow with the next provenance
  comment someone writes. Rule (d) masks comments; this fixture is what pins
  that decision to the corpus that forced it.

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
