(ns fixtures.every-ambient-read-form
  "POSITIVE fixture: every EXERCISABLE entry in `_AMBIENT_READ_FORMS`, one per
  line, each written into the same durable `:instance-id` key so the only thing
  that varies down the file is the read form itself. Fourteen findings, and the
  self-test asserts the read forms BY NAME (rf2-g1xpb).

  Two of the sixteen roster entries are absent, and deliberately — see
  `_UNEXERCISABLE_READ_FORMS`: `js/crypto.getRandomValues` and
  `(.getRandomValues js/crypto …)` carry the very text the allowlist window
  searches for, so any line matching them exempts itself. The self-test holds
  that pair to being uncoverable rather than merely uncovered.

  The host-fact entries are written as BARE symbol values because that is the
  shape the roster describes: the gate matches a read sitting immediately in a
  durable key's value position, so `js/localStorage` fires there while a call
  WRAPPING it does not. Idiomatic these lines are not; the roster entry they
  witness is exactly this spelling."
  (:require [re-frame.interop :as interop]))

(def ^:private id-pool ["a" "b" "c"])

(defn derive-instance-id-from-every-ambient-read
  [rows]
  [;; clock
   (assoc (nth rows 0)  :instance-id (interop/now-ms))
   (assoc (nth rows 1)  :instance-id (js/Date.now))
   (assoc (nth rows 2)  :instance-id (.now js/Date))
   ;; random — `(rand …)` also matches the two longer spellings below, so those
   ;; two lines each witness a PAIR of roster entries, not one.
   (assoc (nth rows 3)  :instance-id (rand 1000))
   (assoc (nth rows 4)  :instance-id (rand-int 1000))
   (assoc (nth rows 5)  :instance-id (rand-nth id-pool))
   (assoc (nth rows 6)  :instance-id (random-uuid))
   ;; browser / host facts
   (assoc (nth rows 7)  :instance-id js/location)
   (assoc (nth rows 8)  :instance-id js/navigator)
   (assoc (nth rows 9)  :instance-id navigator.language)
   (assoc (nth rows 10) :instance-id js/localStorage)
   (assoc (nth rows 11) :instance-id js/sessionStorage)
   (assoc (nth rows 12) :instance-id (.matchMedia js/window "(min-width: 40em)"))
   (assoc (nth rows 13) :instance-id js/matchMedia)])
