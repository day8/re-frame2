(ns re-frame.api-manifest.cljs-publics
  "Compile-time enumeration of a ClojureScript namespace's public vars
  (rf2-2mtte — the CLJS-side companion to the JVM manifest generator).

  THE PROBLEM. The JVM manifest generator (`re-frame.api-manifest.gen`)
  introspects every JVM-loadable public namespace with `clojure.core/
  ns-publics`. The Reagent / UIx / Helix adapter namespaces and the Xray
  `mount` host-embed surface are ClojureScript-ONLY — they cannot be
  `require`d on the JVM, so the generator carries their rows verbatim in
  `spec/api-manifest-metadata.edn` under `:cljs-only` with
  `:runtime-verified? false`. The existence half of the drift-guard does
  not reach them.

  THE MECHANISM. CLJS has no *runtime* `ns-publics` (vars are erased to
  plain JS at `:advanced`). The portable, deterministic, headless way to
  enumerate a CLJS namespace's public vars is at COMPILE time, off the
  analyzer's compilation environment: `cljs.analyzer.api/ns-publics`
  returns the analysed var maps for a namespace, already minus the
  `:private` ones. We read that env from a CLJC/CLJS-targeting macro and
  emit a literal vector of `[var-name kind]` pairs into the calling
  ClojureScript — so the *value* the probe reconciles is fixed at the
  same compile the adapter/Xray sources are analysed in. No runtime
  reflection, no React/DOM load needed to read the surface, byte-stable
  output (the pairs are sorted).

  WHY THIS IS SOUND. A `(emit-ns-publics 'the.ns)` call only resolves to
  a real surface when `the.ns` has been analysed, which the probe
  guarantees by `:require`-ing each target namespace before the macro
  expands (shadow-cljs analyses a dependency before the namespace that
  requires it). The kind derivation mirrors the JVM generator's
  `kind-of` exactly (`:macro` / `:fn` / `:var`) so the two sides agree on
  the third manifest axis the existence-check compares.

  The `^:no-doc` carve-out mirrors `gen.clj`'s `public-vars-of`: a
  var flagged `^:no-doc` in its source is a documented internal that the
  manifest deliberately does not row, so the enumeration drops it too."
  (:require [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- kind-of
  "Derive the manifest `:kind` for a CLJS analyzer var-info map. Mirrors
   the JVM generator's `re-frame.api-manifest.gen/kind-of`:
   `:macro` — a defmacro; `:fn` — a fn (the analyzer flags `:fn-var` or
   carries `:arglists`); `:var` — a plain value (an adapter map, a
   canonical-vocabulary set, …)."
  [info]
  (let [m (:meta info)]
    (cond
      (or (:macro info) (:macro m))                    :macro
      (or (:fn-var info) (:arglists info) (:arglists m)) :fn
      :else                                            :var)))

(defn ns-public-pairs
  "Return a sorted vector of `[var-name-string kind-keyword]` for every
   public var of `ns-sym` in the analyzer compilation env `state`, minus
   the `^:no-doc` carve-outs. The driver behind the `emit-ns-publics`
   macro; exposed as a plain fn so it can be unit-tested off a synthetic
   compiler-state atom."
  [state ns-sym]
  (->> (ana-api/ns-publics state ns-sym)
       (remove (fn [[_ info]] (:no-doc (:meta info))))
       (map (fn [[sym info]] [(name sym) (kind-of info)]))
       (sort-by first)
       vec))

;; ---------------------------------------------------------------------------
;; Locating + reading the curated sidecar at compile time.
;;
;; The probe reconciles the live CLJS publics against the `:cljs-only`
;; rows in `spec/api-manifest-metadata.edn`. CLJS has no compile-time
;; filesystem, so we slurp the sidecar from the JVM side of the macro and
;; emit the relevant rows as a literal into the ClojureScript. The repo
;; root is found by walking up from the build's working directory until
;; the sidecar resolves — robust whether shadow runs from `implementation/`
;; (the consolidated `:node-test` build) or from a tool artefact dir.
;; ---------------------------------------------------------------------------

(defn- find-sidecar
  "Walk up from the build's `user.dir` looking for
   `spec/api-manifest-metadata.edn`. Throws an actionable error when the
   sidecar cannot be located (a misconfigured build classpath)."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when (nil? dir)
      (throw (ex-info (str "api-manifest CLJS probe: could not locate "
                           "spec/api-manifest-metadata.edn walking up from "
                           (System/getProperty "user.dir"))
                      {})))
    (let [candidate (io/file dir "spec" "api-manifest-metadata.edn")]
      (if (.exists candidate)
        candidate
        (recur (.getParentFile dir))))))

(defmacro emit-cljs-only-rows
  "Expand to a literal vector of the sidecar's `:cljs-only` manifest rows
   (read from `spec/api-manifest-metadata.edn` at macro-expansion time).
   These are the curated rows the probe reconciles against the live CLJS
   public surface — so the value the probe checks is pinned to the same
   committed sidecar the JVM generator joins against, with no runtime
   filesystem dependency."
  []
  (-> (find-sidecar) slurp edn/read-string :cljs-only vec))

(defmacro emit-ns-publics
  "Expand to a literal vector of `[var-name kind]` pairs for the public
   vars of `ns-sym` (a quoted symbol), read from the CLJS analyzer's
   compilation environment at macro-expansion time. The target namespace
   must already be analysed — `:require` it from the calling namespace
   before invoking this macro.

   The emitted form is a vector of `[\"var\" :kind]` literals; this is
   the live CLJS public surface the probe reconciles against the
   `:cljs-only` rows of `spec/api-manifest-metadata.edn`."
  [ns-sym]
  (let [sym   (if (and (seq? ns-sym) (= 'quote (first ns-sym)))
                (second ns-sym)
                ns-sym)
        pairs (ns-public-pairs env/*compiler* sym)]
    `[~@(map (fn [[v k]] [v k]) pairs)]))
