(ns re-frame.splint-main
  "Cross-platform entry-point for Splint (noahtheduke/splint v1.24.0)
  with a kondo-shaped `--fail-on-errors` gate.

  ## Why a shim — the Windows config-loader bug

  Splint's config-loader builds two classpath-resource paths with
  `(str (io/file \"a\" \"b\" \"c\"))`. On Windows `io/file` joins segments
  with the platform separator (`\\`), and `io/resource` only accepts
  `/`-separated keys, so the lookup returns nil and Splint dies with
  `Cannot open <nil> as a Reader.` on every Windows dev machine.

  The two affected vars in `noahtheduke.splint.config` are:

    - `version`             — a delay that reads `noahtheduke/splint/SPLINT_VERSION`
    - `default-config-file` — a def that resolves `noahtheduke/splint/config/default.edn`
      (and `default-config`, the delay that slurps it)

  This shim re-binds those vars to forward-slash resource lookups
  before Splint forces the delays / reads the config. On Linux/macOS
  the forward-slash form is identical to what Splint already computes,
  so CI behaviour is unchanged — the shim is a pure cross-platform
  normalisation. Remove once Splint ships a fix (upstream patch:
  replace `(str (io/file ...))` in config.clj with a `/`-joined key).

  ## Why a custom gate — matching the clj-kondo job shape

  Splint's own exit code is `(if (pos? (count diagnostics)) 1 0)` — it
  exits non-zero on ANY finding, with no severity distinction. The
  clj-kondo job in `.github/workflows/lint.yml` runs `--fail-level
  error`: an introduced ERROR blocks the PR while warnings stay
  informational. To give Splint the same shape we add `--fail-on-errors`:
  Splint runs and prints everything normally, but the process exits
  non-zero only when an *error-class* diagnostic is present
  (`splint/error`, `splint/parsing-error`, `splint/unknown-error` —
  Splint's own operational failures, e.g. a file it could not parse).
  Style/lint warnings print to the log for triage but never fail the
  gate. Without the flag the shim is a pass-through to Splint's normal
  exit code.

  Invoked via the `:splint` alias in implementation/deps.edn."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   ;; Requiring `noahtheduke.splint` (the main ns) is load-bearing: it
   ;; pulls in every `noahtheduke.splint.rules.*` namespace, which is
   ;; how each rule registers itself into the global rule registry.
   ;; Without it `runner/run` finds no rules and reports zero findings.
   [noahtheduke.splint]
   [noahtheduke.splint.config :as config]
   [noahtheduke.splint.runner :as runner]))

;; Splint classifies these rule-names as operational errors (not style
;; warnings). Mirrors `noahtheduke.splint.printer/error-diagnostic`.
(def ^:private error-rule-names
  #{'splint/error 'splint/parsing-error 'splint/unknown-error})

(defn- patch-config!
  "Re-bind Splint's two backslash-broken resource lookups to
  forward-slash keys so the linter loads on Windows."
  []
  (alter-var-root
   #'config/version
   (constantly
    (delay
      (-> (io/resource "noahtheduke/splint/SPLINT_VERSION")
          (slurp)
          (str/trim)))))
  (let [default-config-file (io/resource "noahtheduke/splint/config/default.edn")]
    (alter-var-root #'config/default-config-file (constantly default-config-file))
    (alter-var-root
     #'config/default-config
     (constantly (delay (config/slurp-edn default-config-file))))))

(defn -main [& args]
  (patch-config!)
  (let [fail-on-errors? (some #(= "--fail-on-errors" %) args)
        ;; `--fail-on-errors` is our flag, not Splint's — strip it
        ;; before delegating so Splint's CLI parser does not reject it.
        splint-args (vec (remove #(= "--fail-on-errors" %) args))
        {:keys [exit diagnostics]} (runner/run splint-args)
        error-count (if (seq diagnostics)
                      (count (filter #(contains? error-rule-names (:rule-name %))
                                     diagnostics))
                      0)]
    (System/exit
     (cond
       ;; In error-gate mode, fail only on error-class diagnostics;
       ;; warnings stay informational (kondo `--fail-level error` shape).
       fail-on-errors? (if (pos? error-count) 1 0)
       :else (or exit 0)))))
