(ns fixture.negative.fx-handler-ctx-frame
  "NEGATIVE fixture: the SANCTIONED binary fx-handler ctx :frame
  destructuring (Spec 002 §The binary fx-handler signature) + the
  HTTP-interceptor ctx :frame (Spec 014). This is an fx-CONTEXT read, not
  an event coeffect — the spec is normative; it is :frame. Must stay
  GREEN.")

;; Sanctioned: binary fx-handler reads :frame from its fx-context arg.
(defn scroll-fx [{:keys [frame]} args]
  (do-scroll frame args))

;; Sanctioned: HTTP-interceptor ctx carries :frame.
(defn http-middleware [{:keys [frame] :as ctx}]
  (assoc ctx :tagged frame))
