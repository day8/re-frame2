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
  emit-cljs-only-rows`: this macro slurps the Xray spec markdown on the
  JVM side at macro-expansion time and emits the extracted set of
  `mount-<panel>!` names as a literal into the calling ClojureScript.
  The value the guard reconciles is therefore pinned to the same
  committed spec the human reads, with no runtime filesystem dependency
  and no fragile cross-classpath `require` of the CLJS spec surface.

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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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

(def ^:private spec-files
  "Repo-relative paths of the Xray spec files that enumerate the
  mountable panel set. Both are read at macro-expansion time."
  ["tools/xray/spec/007-UX-IA.md"
   "tools/xray/spec/008-Embedding-Contract.md"])

(defn- find-repo-root
  "Walk up from the build's `user.dir` until a directory containing
  `tools/xray/spec/007-UX-IA.md` resolves — robust whether shadow runs
  from `implementation/` (the consolidated `:node-test` build) or from
  the xray artefact dir. Throws an actionable error when not found."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when (nil? dir)
      (throw (ex-info (str "panel-enum spec-refs: could not locate "
                           "tools/xray/spec/007-UX-IA.md walking up from "
                           (System/getProperty "user.dir"))
                      {})))
    (if (.exists (io/file dir "tools" "xray" "spec" "007-UX-IA.md"))
      dir
      (recur (.getParentFile dir)))))

(defn- spec-mount-fn-names
  "Read every spec file and return the SET of distinct `mount-<panel>!`
  names referenced across them, minus the curated removed-name
  allowlist (deliberate removal-note mentions)."
  []
  (let [root (find-repo-root)]
    (->> spec-files
         (mapcat (fn [rel]
                   (let [f (apply io/file root (str/split rel #"/"))]
                     (re-seq mount-fn-re (slurp f)))))
         (remove known-removed-names)
         (into #{}))))

(defmacro emit-spec-mount-fn-names
  "Expand to a literal set of the `mount-<panel>!` names referenced in
  the Xray API spec (read at macro-expansion time). The set the
  panel-enum guard reconciles against the single-source enum + the live
  facade vars."
  []
  (spec-mount-fn-names))
