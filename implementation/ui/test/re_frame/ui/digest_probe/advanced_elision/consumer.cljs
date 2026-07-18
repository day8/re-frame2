(ns re-frame.ui.digest-probe.advanced-elision.consumer
  "The ADVANCED-BROWSER digest/descriptor consumer (rf2-vxgfnd.195 arm 4).

  Arm 4's whole point is that production elision of the whole-build digest
  path was only ever grepped in the `:ui-bench` **`:node-script`** bundle, over
  a source that never CONSUMES the digest. An unconsumed path is trivially
  absent: Closure drops it for unreachability, and the grep proves nothing
  about `goog.DEBUG` elision. So this namespace deliberately ROOTS every
  residue class the bead names, from a real `:browser` entry point:

    - the CARRIER slot + its fixed-width SENTINEL — `digest-carrier/current`
      reads the compiled slot, and this fixture's builds configure NO build
      hook, so the slot keeps its raw `__RF2_UI_DIGEST_XX__` literal rather
      than a hook-patched digest. That is the adversarial case: a hookless or
      mis-hooked consumer build must STILL cost production zero bytes, and the
      sentinel grep has teeth only when nothing patched the sentinel away;
    - the `bd1-` LITERAL — `finalized-digest?`'s prefix test, reached through
      `current`, plus the namespace-load validation;
    - the DEBUG-MANIFEST / fail-loud diagnostic prose — the carrier's
      \"was not finalized\" configuration error;
    - REGISTRY TRAVERSAL — `client/descriptor-index` folds over the live-root
      registry stamping `:build-digest` onto every static core. A real
      live-root entry is registered below so the traversal has something to
      walk;
    - the RELOAD PRODUCERS — `before-load` / `stage!` / `after-load`, the
      activation transaction's hot-reload half.

  Every read is exported through `js/globalThis`, which Closure cannot prove
  dead, so under `goog.DEBUG=true` the whole graph is genuinely reachable and
  the control build carries the residue. Under `:advanced` + `goog.DEBUG=false`
  each body folds to nil and the entire graph must disappear.

  `live-probe` is the KNOWN-PRESENT control for the function-name oracle: it is
  rooted and carries no debug gate, so it must survive into the pseudo-named
  release. Without it, \"the carrier function names are absent\" could not be
  distinguished from \"the grep does not work on this artifact\"."
  (:require [re-frame.ui :as ui]
            [re-frame.ui.client :as client]
            [re-frame.ui.digest-carrier :as digest-carrier]))

(ui/defview probe-view []
  [:main {:data-probe "advanced-elision"} "advanced elision probe"])

(defn live-probe
  "The function-name oracle's KNOWN-PRESENT control.

  Rooted from `init` and NOT `goog.DEBUG`-gated, so its pseudo-name must appear
  in the `:advanced` release. The gate greps for this name in the very artifact
  in which it greps for the carrier names' ABSENCE — so a broken grep (wrong
  artifact, unreadable names, empty blob) fails loudly instead of passing
  vacuously."
  []
  (str "rf2-advanced-elision-live-probe:" (count (client/live-root-ids))))

(defn init []
  ;; A real live-root entry, so `descriptor-index`'s fold over the registry
  ;; walks a populated map rather than an empty one.
  (client/register-live-root!
   {:root-id ::probe :provenance :test
    :descriptor {:root-template ::probe}}
   (js/Object.)
   (client/->Root nil (js/Object.) ::probe))

  ;; The O(1) carrier read: the slot, the sentinel, and the `bd1-` prefix test.
  (set! (.-__rf2AdvElisionDigest js/globalThis)
        (fn [] (digest-carrier/current)))
  (set! (.-__rf2AdvElisionClientDigest js/globalThis)
        (fn [] (client/current-build-digest)))

  ;; The COMPLETE Root Descriptor read — the static core stamped with
  ;; `:build-digest` at read time.
  (set! (.-__rf2AdvElisionDescriptor js/globalThis)
        (fn [] (:build-digest (client/descriptor ::probe))))

  ;; REGISTRY TRAVERSAL: the whole live-root fold, the Xray/tool read surface.
  (set! (.-__rf2AdvElisionDescriptorIndex js/globalThis)
        (fn [] (clj->js (keys (client/descriptor-index)))))

  ;; The RELOAD PRODUCERS — the activation transaction's hot-reload half.
  ;;
  ;; Each is rooted by CALL, never by exporting the var itself. Exporting
  ;; `digest-carrier/before-load` directly would keep the (by then empty)
  ;; function object alive purely because this fixture holds a reference to it,
  ;; and the gate would report a leak no real consumer has. Rooting the call is
  ;; what a consumer actually does, and it is the shape under which the gated
  ;; body must fold away and take the whole function with it.
  (set! (.-__rf2AdvElisionBeforeLoad js/globalThis)
        (fn [] (digest-carrier/before-load)))
  (set! (.-__rf2AdvElisionStage js/globalThis)
        (fn [digest] (digest-carrier/stage! digest)))
  (set! (.-__rf2AdvElisionAfterLoad js/globalThis)
        (fn [] (digest-carrier/after-load)))

  ;; The known-present control, and a real compiled view mounted through the
  ;; genuine `ui/mount` path when a container exists.
  (set! (.-__rf2AdvElisionLiveProbe js/globalThis) live-probe)
  (when-let [container (.getElementById js/document "rf2-advanced-elision")]
    (ui/mount [probe-view] container {:root-id :advanced-elision/mounted})))
