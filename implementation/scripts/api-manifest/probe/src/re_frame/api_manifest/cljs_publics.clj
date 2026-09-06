(ns re-frame.api-manifest.cljs-publics
  "Compile-time enumeration of a ClojureScript namespace's public vars
  (rf2-2mtte — the CLJS-side companion to the JVM manifest generator).

  THE PROBLEM. The JVM manifest generator (`re-frame.api-manifest.gen`)
  introspects every JVM-loadable public namespace with `clojure.core/
  ns-publics`. The Reagent / UIx adapter namespaces and the Xray
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
            [re-frame.build.spec-resource :as rf.build.spec-resource]))

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
;; filesystem, so the sidecar is read from the JVM side of the macro and
;; the relevant rows are emitted as a literal into the ClojureScript.
;;
;; WHY THE READ GOES THROUGH THE SHARED BUILD-TIME READER (rf2-ze3ai).
;; Inlining at macro-expansion time by itself HIDES the sidecar from the
;; build: a compile that caches this probe's namespace has no dependency
;; edge back to the `.edn` whose bytes it froze, so a SIDECAR-ONLY edit —
;; the common "reconcile the drift report" edit — does not invalidate the
;; cache and the compiled probe keeps reconciling the PREVIOUS rows. A
;; warm-cache green over rows nobody checked is the worst answer a drift
;; guard can give.
;;
;; `re-frame.build.spec-resource` is the one reader for committed `spec/`
;; data. Under a ClojureScript compile it reads through shadow-cljs, which
;; records the sidecar's classpath path and last-modified against the
;; compiling namespace and re-checks both before reusing that namespace's
;; cache: edit the sidecar, the probe recompiles, no cache clearing.
;; `spec/` is a shadow-cljs `:source-path` (the one classpath root for
;; committed spec-side data) and the sidecar is the resource
;; `api-manifest-metadata.edn`.
;;
;; That reader is SHARED rather than reimplemented here because resolving
;; shadow's reader is a cold-load race, and two independent resolvers race
;; each other however carefully each one guards itself — see the reader's
;; namespace docstring. The JVM lanes were never affected: the manifest
;; generator re-reads the sidecar on every `clojure -M -m
;; re-frame.api-manifest.gen --check`, so it has no cache to invalidate,
;; and it does not go through this reader at all — it locates the same
;; file by walking up from its own deps.edn directory rather than
;; requiring `spec/` on that classpath. The reader itself has a
;; ClojureScript lane and no other: `slurp-resource` requires an `&env`
;; carrying `:ns` and rejects anything else.
;; ---------------------------------------------------------------------------

(def ^:private sidecar-resource
  "The sidecar as a classpath resource path, relative to the `spec/`
   `:source-path` root the ClojureScript lane resolves against."
  "api-manifest-metadata.edn")

(defn- read-sidecar
  "The parsed sidecar, read in the macro-expansion environment `env`."
  [env]
  (edn/read-string (rf.build.spec-resource/slurp-resource env sidecar-resource)))

(defmacro emit-cljs-only-rows
  "Expand to a literal vector of the sidecar's `:cljs-only` manifest rows
   (read from `spec/api-manifest-metadata.edn` at macro-expansion time).
   These are the curated rows the probe reconciles against the live CLJS
   public surface — so the value the probe checks is pinned to the same
   committed sidecar the JVM generator joins against, with no runtime
   filesystem dependency, and — in ClojureScript — a build dependency, so
   an edit to the sidecar recompiles this call site."
  []
  (-> (read-sidecar &env) :cljs-only vec))

(defmacro emit-classification-rows
  "Expand to a literal vector of `{:namespace :var}` maps for the sidecar's
   `:classification` entries whose namespace is `ns-str` (read from
   `spec/api-manifest-metadata.edn` at macro-expansion time).

   The `:cljs-only` rows the probe normally reconciles are the surfaces the
   JVM generator CANNOT introspect. A `.cljc` namespace the generator DOES
   own on the JVM (a test surface whose structural half
   runs headless on the JVM, so it lives in the generator's `jvm-namespaces`
   and its rows are curated under `:classification`, not `:cljs-only`) still
   needs its reader-conditional CLJS surface reconciled, so neither host can
   silently expose an extra public. This macro projects that namespace's
   classification rows into the same `{:namespace :var}` shape `reconcile`
   consumes; the probe treats it exactly like a fully-rowed surface. Only
   `:namespace`/`:var` are emitted — the probe checks EXISTENCE, not tier
   (the JVM generator owns tier/kind for these rows).

   The sidecar's `:jvm-only-classification` set is SUBTRACTED. A `.cljc`
   namespace can carry a var defined under `#?(:clj …)` with no ClojureScript
   arm at all — a verb that reads a source file, say, has nothing for a browser
   runtime to do and is honestly ABSENT rather
   than present-and-throwing. Its `:classification` row is real (the JVM
   generator introspects it), and reconciling that row against a CLJS surface
   that will never carry it would report permanent staleness for a var that
   is behaving exactly as designed. Subtracting it keeps DIRECTION 2 intact,
   which is the half that matters here: the var is still not rowed for CLJS,
   so if it ever DID acquire a ClojureScript arm the probe would see a live
   public with no row and go red. The escape hatch is one-directional by
   construction, and each entry carries its justification in the sidecar."
  [ns-str]
  (let [sidecar  (read-sidecar &env)
        jvm-only (set (:jvm-only-classification sidecar))]
    (->> (:classification sidecar)
         (keep (fn [[[ns var] _]]
                 (when (and (= ns ns-str) (not (contains? jvm-only [ns var])))
                   {:namespace ns :var var})))
         (sort-by :var)
         vec)))

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
