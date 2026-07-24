(ns re-frame.freehand.bench.b5-reachability-control-app
  "The CONTROL entry of B5's reachability probe (`rf2-drpa3.52` acceptance 1).

  D021's B5 row names a DETERMINISTIC gate beside the byte and timing
  evidence: unused runtime modules are ABSENT from the production bundle.
  That is a claim about what `:advanced` dead-code elimination removed, and
  the only honest way to check it is a CONTROL BUILD — because the naive
  oracles have both been MEASURED WRONG in this repository. Counting a
  debug flag's occurrences reported 225 survivors in a bundle where the
  whole surface had been eliminated (they were prose, not code), and
  grepping a small function's name can never go red because Closure inlines
  it. A grep with no control is a grep that cannot fail.

  So this namespace is `re-frame.freehand.release-app` PLUS ONE THING: a
  view that calls `v/controller-key`, the public door onto the semantic
  controller infrastructure (`re-frame.freehand.control`). The production
  entry declares no controller anywhere, so that module is unreachable
  there and Closure removes it; here it is reached from the mount and
  Closure must keep it. A string that lives only behind that door is
  therefore a DISCRIMINATOR:

    ABSENT  in out/freehand-release          — the module did not ship
    PRESENT in out/freehand-release-reachability-control — the grep has teeth

  The control side is the half that makes the gate falsifiable. Without it,
  `absent` is indistinguishable from `misspelled`, `renamed`, `inlined` or
  `never in this bundle to begin with`, and the gate would pass forever
  while proving nothing.

  ## Rooted BY CALL, never by export

  `-main` CALLS `release-app/-main` and then mounts a view whose body calls
  the door. Exporting a var instead would keep an empty function alive and
  report a leak no consumer has — the other oracle mistake this repository
  has already made once. Nothing here is `^:export` except the build's own
  entry point.

  This bundle is COMPILED AND GREPPED, never served and never run. It lives
  under `freehand/test/` for the reason the sibling bundle probes do:
  `deps.edn` publishes `src` alone, so a fixture that exists to be measured
  stays out of the artefact a consumer resolves.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.release-app :as release-app]))

;; ---------------------------------------------------------------------------
;; The one thing the production entry does not do
;; ---------------------------------------------------------------------------

(v/defview controlled-field
  "A view that asks for a controller record key — the ONE call that makes
  `re-frame.freehand.control` reachable. Its refusal doors (`:control`
  absent, `:control` nil) carry the strings the gate discriminates on, and
  the runtime cannot prove at compile time that neither refusal fires, so
  `:advanced` keeps them."
  [props]
  [:output.control-probe (pr-str (v/controller-key ::field props))])

(v/defview probe-root
  "The probe's own root, mounted beside the production app's."
  [_]
  [:main#freehand-reachability-probe
   [controlled-field {:control [:probe 1]}]])

(defn ^:export -main
  "The control build's `:init-fn`: everything the release entry roots, plus
  the controller door.

  Calling `release-app/-main` rather than re-declaring its views is what
  keeps the two bundles comparable — the control is a strict SUPERSET of
  production, so a string present here and absent there is present BECAUSE
  of the controller and nothing else."
  []
  (release-app/-main)
  (let [el (js/document.createElement "div")]
    (.appendChild js/document.body el)
    (v/mount [probe-root {}] el {:frame {:id ::frame}})))
