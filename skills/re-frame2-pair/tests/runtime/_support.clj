;;;; tests/runtime/_support.clj — the locate+parse+walk harness the
;;;; `tests/runtime/*` structural pins share. Load it from a sibling with
;;;;
;;;;   (load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))
;;;;
;;;; The path resolves off the loading test's own `*file*`, so any cwd works.

(ns runtime-support
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]))

(def runtime-cljs-path
  "Absolute path to `preload/re_frame2_pair/runtime.cljs`. Exits 2 when it
   cannot be found, so a moved runtime fails loud."
  (let [f (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile .getParentFile)
                   "preload" "re_frame2_pair" "runtime.cljs")]
    (if (.exists f)
      (.getPath f)
      (do (binding [*out* *err*]
            (println "ERROR: cannot locate" (.getPath f)))
          (System/exit 2)))))

(defn read-all-forms
  "Every top-level form in `src`, read on the `:cljs` branch. The default
   data-reader swallows `#js` literals, which would otherwise halt the bb
   reader and silently drop every form after the first one."
  [^String src]
  (binding [*default-data-reader-fn* (fn [_tag v] v)]
    (let [pbr (java.io.PushbackReader. (java.io.StringReader. src))]
      (loop [acc []]
        (let [form (try (read {:read-cond :allow :features #{:cljs}} pbr)
                        (catch Exception _ ::eof))]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(def all-forms (read-all-forms (slurp runtime-cljs-path)))

(defn form-contains?
  "True when any node within `form` satisfies `pred`."
  [pred form]
  (let [hit? (atom false)]
    (walk/postwalk (fn [node] (when (pred node) (reset! hit? true)) node) form)
    @hit?))

(defn defn-named
  "The top-level `(defn sym …)` or `(defn- sym …)` form, or nil."
  [sym]
  (some (fn [form]
          (when (and (seq? form) (#{'defn 'defn-} (first form)) (= sym (second form)))
            form))
        all-forms))

(defn calls?
  "True when `form` invokes `sym` as the head of any list."
  [sym form]
  (form-contains? (fn [node] (and (seq? node) (= sym (first node)))) form))

(defn mentions?
  "True when `x` appears anywhere within `form`."
  [x form]
  (form-contains? #(= x %) form))
