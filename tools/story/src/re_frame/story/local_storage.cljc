(ns re-frame.story.local-storage
  "Shared defensive `js/window.localStorage` accessor.

  Browsers can disable localStorage (private-mode quirks, embedded
  contexts, `file://` in some configs) and merely *touching* the slot
  can throw, so every persistence helper across the Story shell guards
  the access. This namespace owns the one canonical guard so the
  `viewport` / `backgrounds` / `toolbar` / `help` / `keybindings` /
  `mode-tabs` / `dispatch-console` / shell-`rails` persistence helpers
  share a single implementation instead of copy-pasting it (rf2-jkake.21).

  CLJS-only by nature — `js/window` does not exist on the JVM. The
  `:clj` arm returns `nil` unconditionally, matching the existing
  contract where every JVM caller already short-circuits its
  load/save helpers to `nil` / no-op before reaching storage.")

(defn safe-local-storage
  "Return `js/window.localStorage` when it is present and reachable,
  otherwise `nil`. Never throws — callers degrade to in-memory-only /
  always-show behaviour on `nil`. Returns `nil` on the JVM."
  []
  #?(:clj  nil
     :cljs (when (and (exists? js/window) (.-localStorage js/window))
             (try (.-localStorage js/window)
                  (catch :default _ nil)))))
