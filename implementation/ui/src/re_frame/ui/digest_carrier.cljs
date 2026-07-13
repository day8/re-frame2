(ns ^{:dev/always true} re-frame.ui.digest-carrier
  "The dev-only client carrier for the compiler-owned whole-build digest.

  The Shadow build hook replaces the one fixed-width sentinel in this
  namespace's compiled `[:output resource-id :js]` at `:compile-finish`, then
  the candidate snapshot is carried in the returned functional build-state.
  Runtime readers are O(1): they read this single slot and never walk the
  registrar. Direct no-pass REPL evaluation never mutates this slot.

  Every operation is goog.DEBUG-gated. Closure removes the slot, sentinel,
  validation and accessors from advanced production output."
  (:require [clojure.string :as str]))

;; Exactly 20 bytes, matching a bd1- + 16-hex-digit digest. Keep this literal
;; unique and in ONE source location: the hook requires exactly one occurrence
;; in exactly one compiled carrier output, preserving source-map offsets by
;; replacing it with an equal-length digest.
(def ^:private state
  (when ^boolean js/goog.DEBUG
    #js {:digest "__RF2_UI_DIGEST_XX__"}))

(defn current
  "Return the compiler-published whole-build digest in dev, nil in production.
  O(1); no registry traversal or client-side digest computation."
  []
  (when ^boolean js/goog.DEBUG
    (.-digest state)))

;; A configured build that omitted the load-bearing hook must not limp along
;; with a plausible but false identity. The hook patches the sentinel before
;; this namespace executes. Prefix validation avoids a second copy of the
;; sentinel literal (the patch target must be unique).
(when ^boolean js/goog.DEBUG
  (when-not (str/starts-with? (current) "bd1-")
    (throw
     (js/Error.
      (str "re-frame.ui build digest was not finalized. Configure "
           "(re-frame.ui.compiler.build-hook/hook) in Shadow :build-hooks "
           "and keep re-frame.ui in :cache-blockers.")))))
