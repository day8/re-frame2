(ns causa-rhs-smoke.core
  "Entry point for the causa-rhs-smoke testbed (rf2-drprn).

  This testbed is a minimal Story shell with a single variant + the
  Causa preload, used by the companion `spec.cjs` to assert that the
  Story RHS's `[data-rf-causa-host]` slot (rf2-sgdd3) is mounted by
  the embedded Causa shell and that variant interactions surface in
  Causa's lenses (Trace tab counts, L2 event list, L1 nav focus
  stepping, mode pill LIVE/RETRO).

  No hash routing — page-load mounts the Story shell directly. No
  modes / tags / a11y / schema-validation / command-palette wiring —
  every feature beyond the four regression scenarios is deliberately
  omitted to keep the testbed small and the Story/Causa-collision
  surface absent (the broader counter-with-stories testbed has the
  full Story chrome and does NOT preload Causa for that reason)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [re-frame.story     :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-causa.config :as causa-config]
            [causa-rhs-smoke.stories]))

;; rf2-r1uod — Causa-as-RHS open-in-editor project-root for the live
;; testbed. Story testbeds register source-coords with classpath-
;; relative `:file` slots; OS-side editor URI handlers reject relative
;; paths. The Story testbeds source-path under shadow-cljs is
;; `../tools/story/testbeds` so the on-disk root that prepends to a
;; coord like `causa_rhs_smoke/stories.cljs:42` is the testbeds dir
;; below. Plumbed via `story/configure! :project-root` and bridged
;; into Causa's slot by `causa-preset/propagate-project-root!`.
;; Symmetric to shop's rf2-6jyf6.
(def ^:private default-project-root
  "C:/Users/miket/code/re-frame2/tools/story/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent /
  blank. Pure-data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Story-API surface)."
  [name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search
                     (js/URLSearchParams.))
          v      (.get params name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

(defn ^:export run []
  ;; Disable Causa's standalone auto-open — the Story shell drives
  ;; `mount/open!` itself via `causa-preset/ensure-causa-mounted!`
  ;; on the selection-watcher edge. Keep the preload's keybinding
  ;; + trace collectors live (they're needed for the embedded shell
  ;; to receive trace bus events).
  (causa-config/configure! {:launch/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  ;; rf2-r1uod — `:project-root` plumbed through Story; the
  ;; `causa-preset` bridge propagates it into Causa's slot so the
  ;; Event lens / Trace rows / Issues ribbon open-in-editor chips
  ;; resolve absolute on-disk paths.
  (story/configure! {:global-args  {}
                     :project-root (resolve-project-root)})
  (story/mount-shell! (js/document.getElementById "app")))
