;;;; FIXTURE (check_egress_walker_residue) — every shape the gate MUST refuse.
;;;; Five findings expected. Not compiled; not on any classpath.

(ns fixture.residue-calls
  (:require [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]))

;; 1. the live home-namespace spelling — resolves, but not from tool source
(defn read-slice-internal [v frame-id]
  (rf.elision/elide-wire-value v {:frame frame-id}))

;; 2. bare, behind a `#` reader macro (a `:refer`-style reach)
(defn walk-all [vs frame-id]
  (mapv #(elide-wire-value % {:frame frame-id}) vs))

;; 3. inside a RENDERED EVAL FORM string — a real call at the far end
(def ^:private snapshot-script
  "(rf/elide-wire-value (rf/app-db-value :rf/default) {:frame :rf/default})")

;; 4. a NEWLINE between the paren and the callee — invisible to any
;;    line-at-a-time search, whatever the pattern
(defn broken-line [v frame-id]
  (
    rf.elision/elide-wire-value v {:frame frame-id}))

;; 5. a `;` COMMENT plus a newline between the paren and the callee
(defn behind-comment [v frame-id]
  ( ;; project the slot before it leaves the box
    rf.elision/elide-wire-value v {:frame frame-id}))
