(ns re-frame.prod-gate-naming-drift-test
  "A test file whose NAME claims the JVM production/debug gate must reach that
  gate for real or say that it does not. `re-frame.interop/debug-enabled?` is
  read once at load, so a suite that rebinds it with `with-redefs` pins a
  rebindable Var, not the production posture, and its name must not hide that.

  DOMAIN: every `.clj` / `.cljc` file under any artefact's `test/` tree beneath
  `implementation/` whose file name contains `prod_gate`, `jvm_gate` or
  `debug_gate`. A file in the domain is HONEST when it does at least one of:

    a. carries `^:prod-gate` metadata (it runs in a real prod-gate lane);
    b. contains the literal `-Dre-frame.debug=false` (it relaunches a child
       JVM with the property on the command line);
    c. contains the disclaimer sentinel `NOT THE LOAD-TIME GATE`.

  Pure filesystem and string checks, so it holds in both postures."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private claim-tokens ["prod_gate" "jvm_gate" "debug_gate"])
(def ^:private disclaimer "NOT THE LOAD-TIME GATE")
(def ^:private prod-gate-tag "^:prod-gate")
(def ^:private jvm-property "-Dre-frame.debug=false")

(def ^:private required-artefacts
  "Artefacts the walk must reach — a floor on reach, not a census, so a new
  artefact with a claiming file is scanned without editing this set."
  #{"core" "epoch" "routing" "ssr"})

(defn- posix [^java.io.File f]
  (str/replace (.getPath f) "\\" "/"))

(defn- implementation-root
  "`implementation/`, found from a test-only classpath resource rather than the
  CWD, and accepted only if it carries `core/src`, so a recursive walk never
  starts from the wrong directory."
  ^java.io.File []
  (when-let [anchor (some-> (io/resource "re_frame/prod_gate_lane_pin_test.clj")
                            io/as-file
                            .getParentFile)]
    (let [root (some-> anchor .getParentFile .getParentFile .getParentFile)]
      (when (and root (.isDirectory (io/file root "core" "src")))
        root))))

(defn- domain-file? [^java.io.File f]
  (and (.isFile f)
       (some? (re-find #"\.cljc?$" (.getName f)))
       (str/includes? (posix f) "/test/")))

(defn- source-bearing-dir?
  "Prune build output and dependencies at descent: a developer checkout holds
  ~20x more entries under them than the source tree itself."
  [^java.io.File f]
  (let [n (.getName f)]
    (and (.isDirectory f)
         (not (str/starts-with? n "."))
         (not (contains? #{"node_modules" "out" "target"} n)))))

(defn- source-tree-seq [^java.io.File root]
  (tree-seq source-bearing-dir? #(seq (.listFiles ^java.io.File %)) root))

(defn- claiming-files []
  (->> (some-> (implementation-root) source-tree-seq)
       (filter domain-file?)
       (filter (fn [^java.io.File f]
                 (some #(str/includes? (.getName f) %) claim-tokens)))
       (sort-by posix)
       vec))

(defn- artefacts-of [^java.io.File root files]
  (let [rp (str (posix root) "/")]
    (into (sorted-set)
          (keep (fn [f]
                  (let [fp (posix f)]
                    (when (str/starts-with? fp rp)
                      (first (str/split (subs fp (count rp)) #"/"))))))
          files)))

(defn- rel [^java.io.File root ^java.io.File f]
  (subs (posix f) (inc (count (posix root)))))

(deftest the-domain-scan-still-finds-files
  ;; The guard on the guard: an empty or narrowed walk would let the honesty
  ;; check below pass vacuously. Reach is pinned by artefact NAME because a
  ;; count passes with any one small tree dropped.
  (let [root    (implementation-root)
        missing (if root
                  (vec (remove (artefacts-of root (claiming-files)) required-artefacts))
                  (vec (sort required-artefacts)))]
    (is (empty? missing)
        (str "the gate-naming walk no longer reaches: " (pr-str missing)))))

(deftest every-gate-claiming-namespace-is-honest-about-the-gate
  (let [root  (implementation-root)
        liars (remove (fn [f]
                        (let [content (slurp f)]
                          (some #(str/includes? content %)
                                [prod-gate-tag jvm-property disclaimer])))
                      (claiming-files))]
    (is (empty? liars)
        (str "these files NAME the production/debug gate but neither reach it "
             "nor disclaim it: " (mapv #(rel root %) liars)
             "\nFix by one of: tag the deftests `" prod-gate-tag "` and run them"
             " in your artefact's prod-gate lane; relaunch a child JVM with `"
             jvm-property "`; or state `" disclaimer "` in the ns docstring."))))
