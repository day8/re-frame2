(ns fixtures.threaded-host-fact
  "NEGATIVE fixture: the CORRECT pattern for a BROWSER host fact, and the
  counterpart to the call-wrapped positives (rf2-vcjpx). The saved session and
  the viewport match are boundary facts, so they arrive on the token's flat
  `:rf.cofx` map as recordable coeffects and are threaded into the durable
  fields — the restore installer never reads storage, location or the media
  query itself.

  This fixture is green on its OWN merits, not via an allowlist wrapper: it
  names no `trace/emit!`, no `getRandomValues` and no `#_:rf.world/ambient-ok`
  escape. The suppliers below DO spell the ambient reads the widened roster now
  matches, which is the point — they sit in an ambient cofx supplier where they
  belong, not in a durable key's value position, so the gate's shape does not
  match them. Must stay GREEN (0 findings)."
  (:require [re-frame.interop :as interop]))

;; Ambient cofx suppliers. These ARE the host reads — that is their whole job,
;; and they are the sanctioned place for them. No durable field key is adjacent
;; to any of them, so the violating shape never forms.
(defn saved-session-supplier
  []
  (some-> (.-localStorage js/globalThis) (.getItem "session")))

(defn saved-draft-supplier
  []
  (.getItem js/sessionStorage "draft"))

(defn entry-url-supplier
  []
  (.-href js/location))

(defn locale-supplier
  []
  (.-language js/navigator))

(defn compact-viewport-supplier
  []
  (.-matches (js/matchMedia "(max-width: 40em)")))

;; The durable write. Every value is threaded off the token's recordable
;; coeffects; nothing here touches the host.
(defn restore-session
  [db token]
  (let [cofx    (:rf.cofx token)
        time-ms (:rf/time-ms cofx)]
    (assoc db
           :restored-at  time-ms
           :installed-at time-ms
           :instance-id  (:auth.session/token cofx)
           :correlation-id (:app.route/entry-url cofx)
           :resource-id  (:ui.viewport/compact? cofx))))

;; An unrelated elapsed-time helper: an ambient read with no durable key in
;; sight, the same near-miss `threaded_completed_at.cljc` carries for the clock.
(defn elapsed-since
  [start]
  (- (interop/now-ms) start))
