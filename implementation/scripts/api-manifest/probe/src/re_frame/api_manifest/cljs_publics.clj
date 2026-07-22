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
;; Host-arity enumeration (rf2-5bcdi — the CLJS companion to the JVM
;; api-md-check host-arity guard).
;;
;; The manifest carries name + :kind but NO arity, so a re-frame.ui.test
;; function can reshape a supported arity and stay green — and the ui.test
;; contract is host-specific (`flush!` is 0-arity on the JVM, 0/1-arity on
;; CLJS). This side reads each covered var's live ANALYZER arities so the
;; probe can reconcile them against the `:cljs` signature contract.
;; ---------------------------------------------------------------------------

(defn- strip-implicit-macro-params
  "Drop a MACRO's compiler-supplied `&form` / `&env` positional params so only
   PROGRAMMER-VISIBLE params are counted (bead AC: compiler-internal parameters
   must not leak). Applied ONLY to macros (rf2-d7sso) — for an ordinary function
   `&form`/`&env` are legal programmer parameter names and are counted. Mirrors
   the JVM lane's `re-frame.api-manifest.api-md-check/strip-implicit-macro-params`."
  [arglist]
  (remove (fn [p] (and (symbol? p) (contains? #{"&form" "&env"} (name p))))
          arglist))

(defn arglist->arity
  "Normalize one arglist to a PROGRAMMER-VISIBLE arity vector, KIND-AWARE
   (rf2-d7sso): `[n]` for a fixed n-arg call, `[n :&]` for a variadic call with
   n fixed args. For a `:macro` the compiler-supplied `&form` / `&env` slots are
   stripped; for a `:fn` they are ordinary parameters and COUNTED. A nested
   destructuring vector counts as ONE positional. Mirrors the JVM lane's
   `re-frame.api-manifest.api-md-check/arglist->arity` exactly so the two hosts
   normalize identically."
  [kind arglist]
  (let [al        (if (= kind :macro) (strip-implicit-macro-params arglist) arglist)
        fixed     (count (take-while #(not= '& %) al))
        variadic? (boolean (some #(= '& %) al))]
    (if variadic? [fixed :&] [fixed])))

(defn- unwrap-arglists
  "The analyzer stores `:arglists` either bare or wrapped in `(quote ...)`
   (metadata form). Return the raw seq-of-arglists, or nil."
  [raw]
  (cond
    (nil? raw)                              nil
    (and (seq? raw) (= 'quote (first raw))) (second raw)
    :else                                   raw))

(defn info->arities
  "The set of programmer-visible arity vectors for a CLJS analyzer var-info
   map, normalized for its live `kind-of` (rf2-d7sso — a function's `&form`/
   `&env` params are counted, a macro's are stripped), or nil when the analyzer
   surfaces no `:arglists` for it (a plain value `:var`, or a macro whose
   arglists the analyzer erases — the probe treats an unobserved FUNCTION as
   drift, but never a macro, whose grammar is host-invariant and pinned on the
   JVM lane)."
  [info]
  (when-let [arglists (or (unwrap-arglists (:arglists info))
                          (unwrap-arglists (:arglists (:meta info))))]
    (let [kind (kind-of info)]
      (into #{} (map #(arglist->arity kind %)) arglists))))

(defn ns-public-surface
  "Return `{var-name-string {:kind kw :arities (#{arity-vector ...} | nil)}}`
   for every public var of `ns-sym` in the analyzer compilation env `state`,
   minus the `^:no-doc` carve-outs — the live CLJS classification (`:kind`) and
   host arities in ONE map (rf2-d7sso). The driver behind `emit-ns-surface`;
   exposed as a plain fn so it can be unit-tested off a synthetic compiler-state
   atom.

   `:kind` is the AUTHORITATIVE live classification the sidecar's declared kind
   is reconciled against on the CLJS lane. For the ui.test surface the analyzer
   classifies its real `defn`s (`:fn`) and `defmacro`s (`:macro`) reliably —
   there are no fn-valued value-defs here (the analyzer limitation the
   `:cljs-only` kind carve-out documents does not reach this surface)."
  [state ns-sym]
  (->> (ana-api/ns-publics state ns-sym)
       (remove (fn [[_ info]] (:no-doc (:meta info))))
       (map (fn [[sym info]] [(name sym) {:kind    (kind-of info)
                                          :arities (info->arities info)}]))
       (into {})))

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

(defmacro emit-classification-rows
  "Expand to a literal vector of `{:namespace :var}` maps for the sidecar's
   `:classification` entries whose namespace is `ns-str` (read from
   `spec/api-manifest-metadata.edn` at macro-expansion time).

   The `:cljs-only` rows the probe normally reconciles are the surfaces the
   JVM generator CANNOT introspect. A `.cljc` namespace the generator DOES
   own on the JVM (`re-frame.ui.test` — rf2-vxgfnd.200: its Tier-1 surface
   runs headless on the JVM, so it lives in the generator's `jvm-namespaces`
   and its rows are curated under `:classification`, not `:cljs-only`) still
   needs its reader-conditional CLJS surface reconciled, so neither host can
   silently expose an extra public. This macro projects that namespace's
   classification rows into the same `{:namespace :var}` shape `reconcile`
   consumes; the probe treats it exactly like a fully-rowed surface. Only
   `:namespace`/`:var` are emitted — the probe checks EXISTENCE, not tier
   (the JVM generator owns tier/kind for these rows)."
  [ns-str]
  (->> (-> (find-sidecar) slurp edn/read-string :classification)
       (keep (fn [[[ns var] _]]
               (when (= ns ns-str) {:namespace ns :var var})))
       (sort-by :var)
       vec))

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

(defmacro emit-ns-surface
  "Expand to a literal `{var-name {:kind kw :arities #{arity-vector ...}|nil}}`
   map for the public vars of `ns-sym` (a quoted symbol), read from the CLJS
   analyzer's compilation environment at macro-expansion time (rf2-5bcdi;
   kind-aware rf2-d7sso). A var the analyzer surfaces no arglists for maps to
   `:arities nil`. The target namespace must already be analysed — `:require`
   it from the calling namespace first.

   The emitted map is self-evaluating data (strings → `{:kind <keyword>
   :arities <set of vectors of numbers / the :& keyword>}`), so — like
   `emit-cljs-only-rows` — the value is returned directly. It is the live CLJS
   classification + arity surface the probe reconciles the sidecar signature
   contract against."
  [ns-sym]
  (let [sym (if (and (seq? ns-sym) (= 'quote (first ns-sym)))
              (second ns-sym)
              ns-sym)]
    (ns-public-surface env/*compiler* sym)))

(defmacro emit-ui-test-signature-contract
  "Expand to the sidecar's `:ui-test-signatures` authority map
   (`{:namespace :vars {var {:kind :clj #{..} :cljs #{..}}}}`), read from
   `spec/api-manifest-metadata.edn` at macro-expansion time (rf2-5bcdi). The
   single machine-readable host-aware signature source, embedded so the probe
   checks the same committed contract the JVM lane joins against with no
   runtime filesystem dependency."
  []
  (-> (find-sidecar) slurp edn/read-string :ui-test-signatures))
