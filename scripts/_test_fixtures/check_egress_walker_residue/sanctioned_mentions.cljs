;;;; FIXTURE (check_egress_walker_residue) — shapes the gate must LEAVE ALONE.
;;;; Zero findings expected. Not compiled; not on any classpath.

(ns fixture.sanctioned-mentions
  (:require [re-frame.core :as rf]))

;; a longer symbol that merely starts with the needle — must not fire
(defn report [v]
  (elide-wire-values-report v))

;; The walker NAMED INSIDE a comment that the gate skips on its way to the
;; callee. A comment is skipped as ONE unit, up to and including its newline,
;; so the name is consumed with it and the callee is the symbol that actually
;; follows — here, the door.
(defn project-via-door [v frame-id]
  (
    ;; delegates to re-frame.elision/elide-wire-value once inside the door
    rf/project-egress v {:rf.egress/profile :rf.egress/off-box-tool
                         :frame             frame-id}))
