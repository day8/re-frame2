;;;; FIXTURE (check_egress_walker_residue) — every shape the gate must LEAVE ALONE.
;;;; Zero findings expected. Not compiled; not on any classpath.
;;;;
;;;; Each of these occurs live in `tools/*/src` today. A gate that fired on any
;;;; of them would be refusing correct content — naming the mechanism is not
;;;; calling it.

(ns fixture.sanctioned-mentions
  (:require [re-frame.core :as rf]
            ;; the namespace require itself is fine — tool code legitimately
            ;; reaches other `re-frame.elision` fns (classification effects)
            [re-frame.elision :as rf.elision]))

;; a back-ticked mention INSIDE a paren — the `tools/xray/src` shape
;; (`re-frame.elision/elide-wire-value`) is SCHEMA-DRIVEN: it substitutes only
;; where the frame's classification declares a path.

(defn project
  "Walks `v` for wire egress. The framework routes each tree-shaped slot
  through the shared `re-frame.elision/elide-wire-value` walker on the
  producer side, including inside a rendered eval form; this fn names the
  boundary and lets the door resolve the floor."
  [v frame-id]
  (rf/project-egress v {:rf.egress/profile :rf.egress/off-box-tool
                        :frame             frame-id}))

;; prose naming the walker with no paren at all: re-frame.elision/elide-wire-value
;; is the mechanism behind the door, and rf/elide-wire-value was its retired
;; facade spelling (rf2-kuky.90).

;; a different var out of the same namespace, in call position — must not fire
(defn classify [db effects]
  (rf.elision/apply-classification-effects db effects))

;; a longer symbol that merely starts with the needle — must not fire
(defn report [v]
  (elide-wire-values-report v))
