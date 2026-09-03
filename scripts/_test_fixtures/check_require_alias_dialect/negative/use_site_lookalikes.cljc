(ns fixtures.use-site-lookalikes
  "NEGATIVE fixture: every USE-SITE shape that a textual ratchet mistakes for
  an alias edge. The gate reads LIBSPECS, never use sites, so all of this must
  be silent (0 findings) — and the machines sweep is the reason it is pinned:
  a first pass there treated the colon as a word boundary and silently rewrote
  42 late-bind keys into new keyword VALUES, with nothing failing, because a
  renamed late-bind key is only read back by another renamed site in the same
  file. The control that caught it counted `:rf.machines/` going 0 -> 42.

  Represented below: literal single-colon keywords whose first segment
  collides with a live alias; auto-resolved double-colon keywords; a
  fully-qualified call; a quoted symbol; a var-quote; and repo-relative doc
  paths that share a first segment with an alias."
  (:require [re-frame.core           :as rf]
            [re-frame.machines       :as rf.machines]
            [re-frame.machines.paths :as rf.machines.paths]))

;; Literal keywords — VALUES, not alias references. `machines`, `router`,
;; `cofx`, `frame` and `corner.timer` all collide with live alias text.
(def late-bind-keys
  [:machines/rearm-after-hydration! :router/dispatch! :cofx/eval-recordable-sub
   :frame/destroyed :corner.timer/scoped])

;; Auto-resolved keywords — these DO denote the aliased namespace and move
;; with the libspec, but they are not themselves libspecs.
(def resolved ::rf.machines/started)

(defn probe [frame]
  [(re-frame.machines.paths/snapshot-path frame)      ;; fully-qualified: stays
   're-frame.fx/dispatch-later-timers                 ;; quoted symbol: stays
   #'rf.machines/start!                               ;; var-quote: moves, not a libspec
   (rf/console :log
               "spec/conformance/fixtures/machines.edn"
               "docs/machines/how-to/tag-a-state.md"
               "../../spec/005-StateMachines.md")])
