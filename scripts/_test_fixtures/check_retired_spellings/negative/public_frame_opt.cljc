(ns fixture.negative.public-frame-opt
  "NEGATIVE fixture: the SANCTIONED public :frame dispatch/subscribe opt
  and the dispatch envelope :frame key. Must stay GREEN — only the
  COEFFECT read of :frame was retired, not the public opt.")

(defn dispatch-on [frame-id]
  ;; Sanctioned: :frame is the public dispatch opt (EP-0002 R3).
  (dispatch [:save] {:frame frame-id})
  (subscribe [:user] {:frame frame-id}))

(defn build-envelope [frame-id event]
  ;; Sanctioned: :frame is the dispatch-envelope key.
  {:frame frame-id :event event :source :ui})
