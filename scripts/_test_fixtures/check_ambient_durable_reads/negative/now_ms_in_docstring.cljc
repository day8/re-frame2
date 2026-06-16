(ns fixtures.now-ms-in-docstring
  "NEGATIVE fixture: the violating shape appears only in PROSE — a docstring
  and a `;;` comment. Masking blanks both, so the gate must NOT fire. The
  docstring even spells `:loaded-at (interop/now-ms)` as the anti-pattern it
  warns against:

      Do NOT write `:loaded-at (interop/now-ms)` — thread `:rf/time-ms` instead.

  Must stay GREEN (0 findings)."
  (:require [re-frame.interop :as interop]))

(defn build-entry
  [token]
  ;; The bad shape `:started-at (.now js/Date)` written here as a comment must
  ;; not fire — it is documentation of what NOT to do.
  {:loaded-at (get-in token [:rf.cofx :rf/time-ms])})
