(ns re-frame.ui.digest-probe.base
  (:require [re-frame.ui :as ui]
            [re-frame.ui.client :as client]
            ;; The runtime half of the activation transaction. The overlapping-
            ;; activation fixture (rf2-vxgfnd.243) drives its before-load /
            ;; stage! / after-load hooks directly to MODEL two reloads paused and
            ;; released newest-first — a deterministic interleaving real Shadow
            ;; under `:loader-mode :eval` runs synchronously and never overlaps.
            [re-frame.ui.digest-carrier :as digest-carrier]
            ;; A loaded application source in the base module: the
            ;; runtime-activation probe (counterexample 2) edits it to throw at
            ;; top-level during a compile-valid hot reload.
            [re-frame.ui.digest-probe.loaded :as loaded]
            [shadow.cljs.devtools.client.browser :as shadow-browser]))

(ui/defview base-view []
  [:main {:data-probe "base"} "digest probe"
   [loaded/loaded-view]])

(defn init []
  ;; The carrier is a read-time O(1) value. Keep the accessor stable across
  ;; lazy-only HMR updates; do not cache one sampled scalar and accidentally
  ;; require this unrelated base namespace's after-load hook to run.
  (set! (.-__rf2ReadDigest js/globalThis)
        (fn [] (client/current-build-digest)))
  ;; rf2-vxgfnd.205 — the explicit negative assertion over descriptor
  ;; PUBLICATION. Register a throwaway static-core live-root entry and read its
  ;; COMPLETE Root Descriptor: `:build-digest` is stamped at read time from
  ;; current-build-digest. A fail-closed carrier (hookless / unfinalized slot)
  ;; must make this nil, never the raw sentinel — so no complete descriptor can
  ;; publish an invalid build identity. Only the hookless fixture invokes this;
  ;; the hooked probe installs but never calls it.
  (set! (.-__rf2ReadDescriptorDigest js/globalThis)
        (fn []
          (client/register-live-root!
           {:root-id ::probe-descriptor :provenance :test
            :descriptor {:root-template ::probe-descriptor}}
           (js/Object.)
           (client/->Root nil (js/Object.) ::probe-descriptor))
          (:build-digest (client/descriptor ::probe-descriptor))))
  ;; Exact processed-ready witness from pinned Shadow 3.4.10. A WebSocket
  ;; packet arriving is earlier than the client applying :welcome; the runner
  ;; waits on this before its first edit so no HMR update can race registration.
  (set! (.-__rf2HmrReady js/globalThis)
        (fn [] @shadow-browser/ws-was-welcome-ref))
  ;; rf2-vxgfnd.242 — a REAL mounted root, so the runtime-activation fence can be
  ;; observed on a live rendered program AND its complete Root Descriptor, not
  ;; just the global carrier scalar. The base runtime lives in the base module;
  ;; the mounted root renders `loaded-view` (loaded.cljs), whose body the
  ;; runtime-activation counterexample edits. On a compile-valid reload that
  ;; throws before `loaded-view` re-registers, the fence holds: the mounted root
  ;; stays on the last accepted program (`loaded-probe-v1`) and its descriptor's
  ;; `:build-digest` fails closed (nil, NEVER the staged candidate); a successful
  ;; recovery advances the program exactly once to `loaded-probe-v2` and restamps
  ;; the descriptor with the exact accepted digest. The hookless fixture shares
  ;; this init but serves no mount container, so the mount is container-guarded.
  (when-let [container (.getElementById js/document "rf2-mounted")]
    (ui/mount [base-view] container {:root-id :digest-probe/mounted}))
  ;; The mounted root's COMPLETE Root Descriptor `:build-digest` (Spec 004C §2):
  ;; the client read-time projection stamped from `current-build-digest`. nil
  ;; while the fence holds; the exact accepted digest once activated. nil when no
  ;; root is mounted (the hookless share).
  (set! (.-__rf2MountedDescriptorDigest js/globalThis)
        (fn [] (:build-digest (client/descriptor :digest-probe/mounted))))
  ;; The last-accepted rendered program the mounted root shows — the DOM text of
  ;; `loaded-view`. It stays on v1 through the failed reload and advances to v2
  ;; exactly on recovery.
  (set! (.-__rf2MountedProgram js/globalThis)
        (fn []
          (when-let [el (.querySelector js/document "[data-probe=\"loaded\"]")]
            (.-textContent el))))
  ;; rf2-vxgfnd.243 — the raw activation-transaction hooks, so the runner can
  ;; DRIVE a modeled overlapping-reload interleaving (two reloads paused,
  ;; released newest-first) against the REAL carrier cell. Real Shadow under the
  ;; probe's default `:loader-mode :eval` runs before-load/stage!/after-load
  ;; synchronously per cycle, so it never produces the interleaving; the fixture
  ;; reproduces it deterministically to prove a STALE after-load neither
  ;; regresses the published digest nor clears a newer reload's fence. Driven
  ;; LAST in the proof, so mutating the live cell corrupts nothing downstream.
  (set! (.-__rf2CarrierBeforeLoad js/globalThis) digest-carrier/before-load)
  (set! (.-__rf2CarrierStage js/globalThis)
        (fn [digest] (digest-carrier/stage! digest)))
  (set! (.-__rf2CarrierAfterLoad js/globalThis) digest-carrier/after-load)
  (set! (.-__rf2BaseLoaded js/globalThis) true))
