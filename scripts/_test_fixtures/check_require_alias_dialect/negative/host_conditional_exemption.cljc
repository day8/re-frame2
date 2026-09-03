(ns fixtures.host-conditional-exemption
  "NEGATIVE fixture: THE ONE SANCTIONED BARE ALIAS — a host-conditional
  binding, where ONE alias names a DIFFERENT namespace in each reader arm and
  the shared name IS the point. Per-arm dotted tails would give the single use
  site below two names, so the gate must stay silent (0 findings).

  The exemption is DERIVED, never listed: an alias bound to 2+ distinct
  namespaces within one file. A path allowlist would go stale on the first
  rename, and would have carried a wrong entry from the day it was written —
  rf2-j5or's notes recorded 'core one' and the core sweep measured ZERO.

  The self-test collapses the two arms onto ONE namespace and requires this
  same file to FIRE, which is what proves the exemption is the two-namespace
  binding rather than the file."
  (:require [re-frame.core :as rf]
            #?(:clj  [re-frame.substrate.plain-atom :as substrate]
               :cljs [re-frame.adapter.reagent :as substrate])))

(defn install! [frame]
  (rf/console :log (substrate/install! frame)))
