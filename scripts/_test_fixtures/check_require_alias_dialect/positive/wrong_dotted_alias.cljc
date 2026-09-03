(ns fixtures.wrong-dotted-alias
  "POSITIVE fixture: an alias can be WRONG while still looking canonical, and
  neither shape is a bare leaf. The dialect is one alias per namespace, not a
  prefix convention (2 findings).

  * `re-frame.core :as rf.core` — the root takes the BARE root alias `rf`,
    matching the bare `:rf/*` keyword root. `rf.core` is not it.
  * `re-frame.machines :as rf.mach` — the tail is the FULL dotted tail, not an
    abbreviation of it; `rf.machines` is the only spelling.

  A ratchet that merely required the alias to start `rf.` would read this file
  green, which is why the rule is equality against a derived canonical name."
  (:require [re-frame.core     :as rf.core]
            [re-frame.machines :as rf.mach]))

(defn boot [frame]
  (rf.core/dispatch [::start])
  (rf.mach/start! frame :door))
