(ns fixtures.ns-form-bare-alias
  "POSITIVE fixture: the plainest violation — bare leaf aliases in an `ns`
  form's `:require` clause. `spec/Conventions.md` §Require-alias dialect
  reserves the bare leaf form for APPLICATION namespaces, so `machines` and
  `routing` must be `rf.machines` and `rf.routing` (2 findings).

  `re-frame.core :as rf` on the first line is the one namespace exempt by
  construction and must stay green, which is what makes this fixture a control
  as well as a witness."
  (:require [re-frame.core     :as rf]
            [re-frame.machines :as machines]
            [re-frame.routing  :as routing]))

(defn boot [frame]
  (rf/dispatch [::start])
  (machines/start! frame :door)
  (routing/match-url "/"))
