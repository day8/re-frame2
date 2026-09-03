(ns fixtures.canonical-aliases
  "NEGATIVE fixture: the dialect, spelled correctly, in every shape this
  checker reads — an `ns` form, a multi-line libspec, a `#?@` splice, an
  `:as-alias`, a `:refer` beside an `:as`, a libspec with NO alias at all, and
  a top-level runtime `(require ...)`. The gate must be silent (0 findings).

  `re-frame.core :as rf` keeps the bare root alias; every other namespace
  takes the full dotted tail."
  (:require [re-frame.core     :as rf :refer [dispatch]]
            [re-frame.machines :as rf.machines]
            [re-frame.routing  :as rf.routing]
            [re-frame.schemas  :as-alias rf.schemas]
            [re-frame.ssr]
            [re-frame.machines.result
             :as rf.machines.result
             :refer [depth-abort?]]
            #?@(:clj  [[re-frame.flows.jvm :as rf.flows.jvm]]
                :cljs [[re-frame.flows :as rf.flows]])
            [clojure.string :as str])
  (:import #?(:clj [java.util Date])))

(defn ensure-directory! []
  (require '[re-frame.late-bind.directory :as rf.late-bind.directory])
  (rf.late-bind.directory/entries))

(defn boot [frame]
  (dispatch [::start])
  (rf/console :log
              (str/join "/" ["a" "b"])
              (rf.machines/start! frame :door)
              (rf.routing/match-url "/")
              (rf.machines.result/summary {})
              (depth-abort? {})
              #?(:clj (rf.flows.jvm/status frame) :cljs (rf.flows/status frame)))
  (assoc {} ::rf.schemas/kind :ok))
