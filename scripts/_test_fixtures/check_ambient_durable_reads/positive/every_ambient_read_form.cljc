(ns fixtures.every-ambient-read-form
  "POSITIVE fixture: every EXERCISABLE entry in `_AMBIENT_READ_FORMS`, one per
  line, each written into the same durable `:instance-id` key so the only thing
  that varies down the file is the read form itself. Twenty-one findings, and
  the self-test asserts the read forms BY NAME (rf2-g1xpb).

  Two of the twenty-three roster entries are absent, and deliberately — see
  `_UNEXERCISABLE_READ_FORMS`: `js/crypto.getRandomValues` and
  `(.getRandomValues js/crypto …)` carry the very text the allowlist window
  searches for, so any line matching them exempts itself. The self-test holds
  that pair to being uncoverable rather than merely uncovered.

  The browser host facts appear TWICE, because the roster spells them twice
  (rf2-vcjpx). First as BARE symbol values: the gate matches a read sitting
  immediately in a durable key's value position, so `js/localStorage` fires
  there. Idiomatic those lines are not; the roster entry they witness is
  exactly that spelling. Then again CALL-WRAPPED — `(.getItem js/localStorage
  …)`, `(.-href js/location)`, `(js/matchMedia …)` — which is how the fact is
  actually read, and which fired NOTHING before the roster carried it. Each
  call-wrapped line witnesses a PAIR: its own entry and the bare entry whose
  text it contains."
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
   ;; browser / host facts — bare symbol in the value position
   (assoc (nth rows 7)  :instance-id js/location)
   (assoc (nth rows 8)  :instance-id js/navigator)
   (assoc (nth rows 9)  :instance-id navigator.language)
   (assoc (nth rows 10) :instance-id js/localStorage)
   (assoc (nth rows 11) :instance-id js/sessionStorage)
   (assoc (nth rows 12) :instance-id (.matchMedia js/window "(min-width: 40em)"))
   (assoc (nth rows 13) :instance-id js/matchMedia)
   ;; browser / host facts — call-wrapped, the spelling people actually write
   (assoc (nth rows 14) :instance-id (.getItem js/localStorage "session"))
   (assoc (nth rows 15) :instance-id (.getItem js/sessionStorage "session"))
   (assoc (nth rows 16) :instance-id (.-href js/location))
   (assoc (nth rows 17) :instance-id (.-language js/navigator))
   (assoc (nth rows 18) :instance-id (js/matchMedia "(min-width: 40em)"))
   ;; ... and this repo's own storage idiom, both halves
   (assoc (nth rows 19) :instance-id (some-> (.-localStorage js/globalThis)
                                             (.getItem "session")))
   (assoc (nth rows 20) :instance-id (some-> (.-sessionStorage js/globalThis)
                                             (.getItem "session")))])
