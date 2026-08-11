(ns fixture.coord.positive.where-symbol-front
  "POSITIVE fixture (d/symbol): a refusal raised with a `:where` naming the
  retired prototype namespace `front.codec`. This is the OBVIOUS half of the
  rule — the shape a symbol-aware grep would also have caught — and it is here
  so the two shapes are proven independently of each other.")

(defn- resolve-frame-prop! [frame-kw]
  (if (nil? frame-kw)
    (fail! :rf.error/no-frame-prop
           'front.codec/root-element
           "A frame-fed Hicasso boundary rendered with no frame in its props."
           :mint-the-root-element-with-a-frame
           {})
    frame-kw))
