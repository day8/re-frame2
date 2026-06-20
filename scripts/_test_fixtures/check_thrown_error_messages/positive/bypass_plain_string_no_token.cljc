(ns fixture.positive.bypass-plain-string-no-token
  "POSITIVE fixture (rf2-krrv87 gate widen): a raw ex-info with a plain
  human-prose string literal as the message but NO trailing [:rf.<ns>/…]
  token, whose ex-data carries :rf.error/id. The message is human (so the
  four bare-keyword patterns do NOT fire) yet it still bypassed the builder
  and skipped the token — exactly what the widened rule must catch.")

(defn boom [x]
  (throw (ex-info "the widget could not be mounted; check the adapter"
                  {:rf.error/id :rf.error/widget-mount-failed
                   :where    'rf/mount-widget
                   :recovery :no-recovery
                   :bad      x})))
