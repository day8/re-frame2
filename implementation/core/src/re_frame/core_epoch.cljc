(ns re-frame.core-epoch
  "Public-API wrappers for the optional epoch artefact (Tool-Pair
  §Time-travel). Implementation ships in `day8/re-frame2-epoch`
  (`re-frame.epoch`). See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention).

  The entire epoch surface is gated on `interop/debug-enabled?` (Tool-
  Pair §Time-travel §Production elision). Absent-artefact wrappers
  degrade silently (empty vector / no-op) so a release build that omits
  the artefact does not raise. The restore, replay and frame-state write
  surfaces have no wrapper here: they live at home in `re-frame.epoch`.

  Every wrapper that names a frame takes its id or the frame value
  `rf/make-frame` returns."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [artefact-info defwrapper]]
                                        :cljs [:refer        [artefact-info]
                                               :refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private epoch-artefact (artefact-info :epoch))

(defwrapper epoch-history
  "Return the vector of `:rf/epoch-record` values for the frame, oldest-
  first. Empty vector when the frame has no recorded epochs, when the
  ring buffer's depth is 0 (recording disabled), or when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/epoch-history`."
  {:hook :epoch/epoch-history :artefact epoch-artefact :on-absent :empty-vec}
  ([frame-id] :delegate))

(defwrapper register-epoch-listener!
  "Register a callback fired for each assembled
  `:rf/epoch-record`. Per Spec 009 §`register-epoch-listener!`. Same-id
  registrations replace; listener exceptions are isolated. Returns the
  id. No-op (returns nil) when the `day8/re-frame2-epoch` artefact is
  not on the classpath. Late-bound via `:epoch/register-epoch-listener!`."
  {:hook :epoch/register-epoch-listener! :artefact epoch-artefact :on-absent :nil}
  ([id f] :delegate))

(defwrapper unregister-epoch-listener!
  "Remove the listener registered under id. No-op when the
  `day8/re-frame2-epoch` artefact is not on the classpath. Late-bound
  via `:epoch/unregister-epoch-listener!`."
  {:hook :epoch/unregister-epoch-listener! :artefact epoch-artefact :on-absent :nil}
  ([id] :delegate))

(defwrapper clear-epoch-listeners!
  "Drop every registered epoch-settled callback. Test-isolation only —
  production code should never call this. Returns nil. No-op (returns
  nil) when the `day8/re-frame2-epoch` artefact is not on the classpath.
  Late-bound via `:epoch/clear-epoch-listeners!`."
  {:hook :epoch/clear-epoch-listeners! :artefact epoch-artefact :on-absent :nil}
  ([] :delegate))

