(ns fixtures.reader-conditional-splice
  "POSITIVE fixture: a libspec reached only through a `#?@` reader-conditional
  SPLICE, so the libspec vector is nested one level inside another vector. The
  scan must descend into the inner vector rather than reading the outer one as
  a malformed libspec (1 finding: the bare `flows` alias).

  The `:clj` arm's `re-frame.flows.jvm :as rf.flows.jvm` is canonical and must
  stay green, so this fixture is also the control for descending correctly."
  (:require [re-frame.core :as rf]
            #?@(:clj  [[re-frame.flows.jvm :as rf.flows.jvm]]
                :cljs [[re-frame.flows :as flows]])))

(defn run [frame]
  (rf/console :log #?(:clj (rf.flows.jvm/status frame) :cljs (flows/status frame))))
