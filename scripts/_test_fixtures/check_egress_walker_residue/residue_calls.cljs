;;;; FIXTURE (check_egress_walker_residue) — every shape the gate MUST refuse.
;;;; Five findings expected. Not compiled; not on any classpath.

(ns fixture.residue-calls
  (:require [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]))

;; 1. the retired facade spelling
(defn read-slice [v frame-id]
  (rf/elide-wire-value v {:frame frame-id}))

;; 2. the retired fully-qualified facade spelling
(defn read-slice-qualified [v frame-id]
  (re-frame.core/elide-wire-value v {:frame frame-id}))

;; 3. the live home-namespace spelling — resolves, but not from tool source
(defn read-slice-internal [v frame-id]
  (rf.elision/elide-wire-value v {:frame frame-id}))

;; 4. bare, behind a `#` reader macro (a `:refer`-style reach)
(defn walk-all [vs frame-id]
  (mapv #(elide-wire-value % {:frame frame-id}) vs))

;; 5. inside a RENDERED EVAL FORM string — a real call at the far end
(def ^:private snapshot-script
  "(rf/elide-wire-value (rf/app-db-value :rf/default) {:frame :rf/default})")
