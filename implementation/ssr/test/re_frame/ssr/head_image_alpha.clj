(ns re-frame.ssr.head-image-alpha
  "Test-support namespace ALPHA for the frame-targeted head/projector
  resolution tests (rf2-blpg).

  Its whole job is to be a distinct `:rf.provenance/ns` — the provenance
  source store keys descriptors `[kind id provenance-ns]`, so the SAME
  `[kind id]` registered from here and from `re-frame.ssr.head-image-beta`
  produces TWO retained descriptors that two images can select apart. The
  registrar ATOM keeps only the later of the two; that divergence between
  the atom and a frame's sealed generation is exactly what the
  frame-targeted resolution tests measure.

  Provenance is stamped at MACROEXPANSION time by the public `reg-*`
  macros, so it is a property of the FILE the form is written in, not of
  the runtime `*ns*` at call time. That is why these are real files rather
  than `create-ns` calls in a test body, and why the registration lives
  behind a fn the test calls (the test fixture's `registrar/clear-all!`
  would otherwise wipe an ns-load-time registration before the test body
  ran).

  The `-alpha` and `-beta` bodies are deliberately distinguishable by
  their RETURN VALUE, not by a side effect: a test that cannot tell which
  body ran cannot detect the wrong-generation defect at all."
  (:require [re-frame.core :as rf]))

(def head-id
  "The head id both support namespaces register — the collision the
  provenance store retains and the two images select apart."
  :rf.ssr.head-image/shared)

(def projector-id
  "The error-projector id both support namespaces register."
  :rf.ssr.head-image/shared-projector)

(defn register!
  "Register ALPHA's `head-id` head and `projector-id` projector.

  Called from a test body rather than at ns load: the shared `:each`
  fixture runs `registrar/clear-all!`, which would wipe an ns-load-time
  registration before the test that needs it."
  []
  (rf/reg-head head-id
    {:doc "ALPHA's body for the shared head id."}
    (fn [db _route]
      {:title (str "alpha:" (:marker db))}))
  (rf/reg-error-projector projector-id
    {:doc "ALPHA's body for the shared projector id."}
    (fn [_trace-event]
      {:status 418 :code :alpha :message "alpha" :retryable? false})))
