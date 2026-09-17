(ns day8.re-frame2-xray.panel-enum-spec-refs
  "Compile-time extractor for the `mount-<panel>!` references the Xray
  API spec enumerates (rf2-rapnr).

  THE PROBLEM. The panel-enum single-source guard
  (`panel_enum_guard_cljs_test.cljs`) is a ClojureScript test — it runs
  under the consolidated `:node-test` build so it can read the live
  `mount-*!` vars of `day8.re-frame2-xray.panels`. But it must ALSO
  reconcile the spec's projection of the panel set, and CLJS has no
  compile-time filesystem.

  THE MECHANISM. Mirrors `re-frame.api-manifest.cljs-publics/
  emit-cljs-only-rows`: this macro reads the Xray spec markdown on the
  JVM side at macro-expansion time and emits the extracted set of
  `mount-<panel>!` names as a literal into the calling ClojureScript.
  The value the guard reconciles is therefore pinned to the same
  committed spec the human reads, with no runtime filesystem dependency
  and no fragile cross-classpath `require` of the CLJS spec surface.

  THE READ IS RECORDED, WHICH IS THE WHOLE POINT OF THE READER IT GOES
  THROUGH (rf2-uttbj, repairing rf2-863tl). Inlining a file at
  macro-expansion time by itself HIDES it from the build: a `.md` is not
  an input the ClojureScript compiler tracks, so the cached consumer
  holds no edge back to the bytes this macro froze. Correct a drifted
  spec row and the incremental compile reports `1 compiled` while the
  guard goes on reconciling the PREVIOUS expansion — a verdict over a
  projection nobody re-read, measured failing in both directions (a
  stale RED that survived its own fix, and a stale GREEN over a spec
  the tree no longer contained). Reading through
  `re-frame.build.spec-resource/slurp-resource` instead records each
  file's classpath path and last-modified against the compiling
  namespace, and shadow-cljs re-checks both before reusing that
  namespace's cache: edit the spec, the guard recompiles, no cold
  rebuild and no ritual. `tools/xray/spec` is a shadow-cljs
  `:source-path` for exactly this.

  That reader is SHARED rather than reimplemented here: resolving
  shadow's own reader is a cold-load race that two independent resolvers
  lose however carefully each one guards itself — see the reader's
  namespace docstring. It has a ClojureScript lane and no other, so
  [[emit-spec-mount-fn-names]] hands its `&env` down and any other
  caller is refused rather than quietly reaching the tree by a second,
  unrecorded route.

  SCOPE. The spec is prose-heavy; a bare-token sweep would be all
  false-positive. We scope to exactly the `mount-<panel>!` lexical
  shape (`mount-` + kebab name + `!`) — the mountable-panel contract's
  canonical reference form — across the two spec files that enumerate
  the panel set:
    - `007-UX-IA.md` §Mountable surface inventory (the tier tables).
    - `008-Embedding-Contract.md` §Embeddable event spine + the
      mount-fn-contract prose.
  A `mount-<panel>!` named in either file MUST be in the single-source
  enum, and every enum mount fn MUST be named in the spec — the guard
  enforces both directions."
  (:require [clojure.string :as str]
            [re-frame.build.spec-resource :as rf.build.spec-resource]))

(def ^:private mount-fn-re
  "The `mount-<panel>!` reference shape (007-UX-IA §The mount-fn
  contract). Matches `mount-` + a kebab identifier + a trailing `!`."
  #"\bmount-[a-z][a-z0-9-]*!")

(def ^:private known-removed-names
  "Curated allowlist of `mount-<panel>!` names the spec DELIBERATELY
  mentions in removal notes (the panels were retired) but which are NOT
  in the single-source `panel-enum`. The keystone idiom (see
  `re-frame.api-manifest.projection` §WHY AN ALLOWLIST): a surface
  legitimately names a removed old name to document the change; the
  allowlist silences exactly those, by name, so the guard stays green
  on the history note yet still goes RED for any OTHER unknown
  `mount-*!` that drifts into the spec.

    - `mount-issues-ribbon!` — the Issues-tab mount fn, removed under
      rf2-gbz39 (Mike's Option (c)); 007-UX-IA names it in the
      removal note that explains where issues surface now."
  #{"mount-issues-ribbon!"})

(def ^:private spec-root
  "The shadow-cljs `:source-path` the spec files below are resolvable
  under, as a repo-relative prefix. The one place that root is spelled
  outside `implementation/shadow-cljs.edn`, and the only reason it is
  spelled at all is [[spec-files]]'s contract below."
  "tools/xray/spec/")

(def ^:private spec-files
  "The Xray spec files that enumerate the mountable panel set, as
  REPO-RELATIVE paths. Both are read at macro-expansion time, through
  [[spec-resource-name]], and both reads are recorded against the
  compiling namespace — see the namespace docstring.

  REPO-RELATIVE RATHER THAN THE RESOURCE NAMES THE READ ACTUALLY TAKES,
  AND THAT IS A CONTRACT RATHER THAN A HABIT: the surface classifier's
  Xray arm (rf2-6ng7) reads THIS vector to prove that each file it names
  arms `cljs_node_test`, which is what makes an Xray spec edit schedule
  the lane that grades it. Reduce these to bare resource names and the
  classifier mirror goes red — and the coverage it guards goes with it,
  since a spec-only edit would stop arming the node lane."
  ["tools/xray/spec/007-UX-IA.md"
   "tools/xray/spec/008-Embedding-Contract.md"])

(defn- spec-resource-name
  "`rel` as the classpath resource name the recording read takes —
  i.e. with the [[spec-root]] prefix stripped. Refuses a path outside
  that root rather than resolving it somewhere else on the classpath."
  [rel]
  (when-not (str/starts-with? rel spec-root)
    (throw (ex-info (str "panel-enum spec-refs: " rel " is not under the "
                         spec-root " classpath root, so the recording read "
                         "cannot address it.")
                    {:rel rel :spec-root spec-root})))
  (subs rel (count spec-root)))

(defn- spec-mount-fn-names
  "Read every spec file in the ClojureScript macro-expansion environment
  `env` and return the SET of distinct `mount-<panel>!` names referenced
  across them, minus the curated removed-name allowlist (deliberate
  removal-note mentions)."
  [env]
  (->> spec-files
       (mapcat (fn [rel]
                 (re-seq mount-fn-re
                         (rf.build.spec-resource/slurp-resource
                          env (spec-resource-name rel)))))
       (remove known-removed-names)
       (into #{})))

(defmacro emit-spec-mount-fn-names
  "Expand to a literal set of the `mount-<panel>!` names referenced in
  the Xray API spec (read at macro-expansion time). The set the
  panel-enum guard reconciles against the single-source enum + the live
  facade vars.

  Each file read is recorded against the calling namespace, so an edit
  to either spec file recompiles this call site."
  []
  (spec-mount-fn-names &env))
