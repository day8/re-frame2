(ns fixture.coord.negative.refusal-message-prose
  "NEGATIVE fixture: the two SHIPPED refusal MESSAGES that name the prototype
  in backticked prose. Reproduced verbatim from
  `implementation/hicasso/src/re_frame/hicasso/impl/collector.cljs` (the
  `:rf.error/no-frame-context` hint at :1738 and the `:rf.error/no-frame-prop`
  hint at :1790).

  These are the closest thing in the corpus to a false positive for the STRING
  half of rule (d): a retired coordinate, inside a string literal, in shipped
  code, unmasked by design. What keeps them green is that the rule requires the
  ENTIRE literal to be the coordinate — these are sentences, so they carry
  whitespace, and the literal cannot close before the first space.")

(defn- resolve-frame-context! [frame-kw where]
  (if (nil? frame-kw)
    (fail! :rf.error/no-frame-context
           where
           (str "A Hicasso boundary rendered with no frame in scope. Mount the "
                "tree under a frame boundary — `arm1.mount/root!` installs one.")
           :mount-under-a-frame
           {})
    frame-kw))

(defn- resolve-frame-prop! [frame-kw]
  (if (nil? frame-kw)
    (fail! :rf.error/no-frame-prop
           're-frame.hicasso.impl.collector/frame-prop-shell
           (str "A frame-fed Hicasso boundary rendered with no frame in its "
                "props. Every boundary element below the root is minted by an "
                "ancestor body, which carries the frame; the root and any "
                "outward React bridge mint theirs outside a body and must name "
                "it (`front.codec/root-element`, which `arm1.mount/render!` "
                "calls).")
           :mint-the-root-element-with-a-frame
           {})
    frame-kw))
