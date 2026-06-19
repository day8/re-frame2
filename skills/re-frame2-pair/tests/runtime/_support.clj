;;;; tests/runtime/_support.clj
;;;;
;;;; Shared babashka test scaffold for the `tests/runtime/*` structural
;;;; pins (rf2-yrpt90). Every structural-pin test needs to locate, slurp
;;;; and parse `preload/re_frame2_pair/runtime.cljs`, then walk the parsed
;;;; forms looking for a named defn / a predicate hit. That harness was
;;;; copy-pasted verbatim across ~12 tests; it lives here once.
;;;;
;;;; Per-test ASSERTIONS stay in each test file — only the mechanical
;;;; locate+parse+walk harness collapses here.
;;;;
;;;; Load it from a sibling test with:
;;;;
;;;;   (load-file (str (.getParent (io/file *file*)) "/_support.clj"))
;;;;
;;;; then `(:require [runtime-support :as rt])` or refer the vars. The
;;;; load-file path is resolved off the loading test's own `*file*`, so it
;;;; works regardless of the cwd `bb` was launched from.

(ns runtime-support
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]))

(def ^:private this-file
  ;; Absolute path to THIS support file, so we can resolve runtime.cljs
  ;; relative to the skill tree rather than the (unknown) launch cwd.
  (-> *file* io/file .getAbsoluteFile))

(def runtime-cljs-path
  "Absolute path to `preload/re_frame2_pair/runtime.cljs`. Resolved off
   this file's location: tests/runtime/_support.clj → skill root →
   preload/…. Exits 2 (matching the historical per-test guard) when the
   source can't be found, so a moved/renamed runtime fails loud."
  (let [skill-root (-> this-file
                       .getParentFile   ;; tests/runtime/
                       .getParentFile   ;; tests/
                       .getParentFile)  ;; skills/re-frame2-pair/
        f          (io/file skill-root "preload" "re_frame2_pair" "runtime.cljs")]
    (if (.exists f)
      (.getPath f)
      (do (binding [*out* *err*]
            (println "ERROR: cannot locate preload/re_frame2_pair/runtime.cljs from"
                     (.getPath this-file)))
          (System/exit 2)))))

(defn read-all-forms
  "Read every top-level form from `src`.

   `:read-cond :allow :features #{:cljs}` keeps reader conditionals on the
   `:cljs` branch. `*default-data-reader-fn*` swallows unknown tagged
   literals — notably the `#js {...}` / `#js [...]` the CLJS preload
   carries in its streaming section. Without it the bb reader (which has
   no `js` tag) HALTS on the first `#js`, silently dropping every form
   after it — including defns near the end of the file."
  [^String src]
  (binding [*default-data-reader-fn* (fn [_tag v] v)]
    (let [pbr (java.io.PushbackReader. (java.io.StringReader. src))]
      (loop [acc []]
        (let [form (try (read {:read-cond :allow :features #{:cljs}} pbr)
                        (catch Exception _ ::eof))]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(def all-forms
  "Every top-level form parsed from the live runtime source."
  (read-all-forms (slurp runtime-cljs-path)))

(defn form-contains?
  "True when any node within `form` satisfies `pred` (postwalk)."
  [pred form]
  (let [hit? (atom false)]
    (walk/postwalk (fn [node] (when (pred node) (reset! hit? true)) node) form)
    @hit?))

(defn defn-named
  "The top-level `(defn sym ...)` or `(defn- sym ...)` form for `sym`, or
   nil. Matches both public and private defns."
  [sym]
  (some (fn [form]
          (when (and (seq? form)
                     (#{'defn 'defn-} (first form))
                     (= sym (second form)))
            form))
        all-forms))
