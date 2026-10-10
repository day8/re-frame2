;; NEGATIVE FIXTURE — a `#_` DISCARDED RUNTIME REQUIRE IS NOT A REQUIRE EDGE.
;;
;; Trap (5) puts runtime `(require ...)` forms into the scan surface, and trap
;; (6) has to reach them too: a discard in front of a whole require form, and a
;; discard of one libspec inside a LIVE one, are different spans and only the
;; second leaves the enclosing form standing.
(ns re-frame.fixtures.discard-runtime-require
  (:require [re-frame.core :as rf]))

;; The whole form is discarded, so the require never happens.
#_(require '[re-frame.late-bind.directory :as directory])

;; ... and here the require IS live: only its second libspec is discarded, and
;; the quote in front of it is part of the form the discard consumes.
(require '[re-frame.machines :as rf.machines]
         #_'[re-frame.routing :as routing])

(defn boot []
  (rf/dispatch [::rf.machines/start]))
