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

;;;; ---------------------------------------------------------------------------
;;;; The three below discriminate the WIDENING (rf2-kuky.90, merged-PR audit
;;;; #9491). The gate now skips reader whitespace, newlines and `;` comments
;;;; between the paren and the callee, so these shapes newly reach the callee
;;;; position. They must still not fire.

;; A back-ticked mention behind a SPACE, and the same mention with its parens
;; SPANNING LINES. A backtick is not reader whitespace, so it breaks the call
;; head wherever it sits — tight against the paren, or behind a space, or
;; behind a newline:
;; ( `re-frame.elision/elide-wire-value` ) is SCHEMA-DRIVEN, and
;; ( `re-frame.elision/elide-wire-value`
;;   ) is that same mention, wrapped.

;; The walker NAMED INSIDE a comment that the gate skips on its way to the
;; callee. A comment is skipped as ONE unit, up to and including its newline,
;; so the name is consumed with it and the callee is the symbol that actually
;; follows — here, the door.
(defn project-via-door [v frame-id]
  (
    ;; delegates to re-frame.elision/elide-wire-value once inside the door
    rf/project-egress v {:rf.egress/profile :rf.egress/off-box-tool
                         :frame             frame-id}))
