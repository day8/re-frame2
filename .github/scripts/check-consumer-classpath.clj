;; check-consumer-classpath.clj — the structural half of
;; check-consumer-classpath.sh, which drives it. Every step here reads EDN or
;; Clojure source as DATA (clojure.edn / clojure.core/read), never as text, and
;; nothing here loads a namespace from the classpath it inspects. It runs on a
;; bare `clojure -Srepro -M` with no project deps.
;;
;; Subcommands, in the order the driver calls them:
;;
;;   plan     IMPL-ROOT REL...         publishable artefacts in build order
;;   localize M2 DEPS-EDN...           point each scratch build at the scratch repo
;;   sets     REPO-ROOT VERSION M2 OUT REL...
;;                                     one directory per documented coordinate set
;;   lookup   OUT                      the verdict over the resolved classpaths
;;
;; Exit 1 is a failed check: a documented require with no classpath file, or
;; with more than one classpath entry shipping it. Exit 2 is input this script
;; cannot classify, which fails closed rather than shrinking the set it checks.

(ns check-consumer-classpath
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File PushbackReader StringReader)
           (java.nio.file Paths)
           (java.util.zip ZipFile)))

(defn- fail!
  "Input this script cannot classify. Exit 2, naming what and where."
  [& parts]
  (println (str "::error::consumer-classpath: " (apply str parts)))
  (System/exit 2))

(defn- read-deps [file]
  (let [f (io/file file)]
    (when-not (.isFile f) (fail! "expected a deps.edn at " f))
    (try (edn/read-string (slurp f))
         (catch Exception e (fail! "cannot read " f " as EDN: " (.getMessage e))))))

(defn- slash-path [p] (str/replace (str p) "\\" "/"))

;; ── plan ────────────────────────────────────────────────────────────────────

(defn- artefact [impl-root rel]
  (let [deps (read-deps (io/file impl-root rel "deps.edn"))
        lib  (get-in deps [:aliases :clein/build :lib])]
    (when-not (qualified-symbol? lib)
      (fail! "implementation/" rel "/deps.edn: :clein/build carries no qualified :lib"))
    {:rel rel :lib lib :deps (:deps deps)}))

(defn- in-repo-edges
  "The published :deps entries that point at a sibling artefact by
  :local/root. `clein pom` skips a :local/root coordinate, so each one must be
  a publishable sibling the driver rewrites to :mvn/version — anything else
  would publish a POM with a hole in it."
  [{:keys [rel deps]} by-rel]
  (for [[lib coord] deps
        :let [root (:local/root coord)]
        :when root
        :let [target (slash-path (.normalize (.resolve (Paths/get rel (make-array String 0)) ^String root)))
              sibling (by-rel target)]]
    (cond
      (nil? sibling)
      (fail! "implementation/" rel "/deps.edn publishes " lib " {:local/root " (pr-str root)
             "}, which is not a publishable artefact (no :clein/build at implementation/" target ")")

      (not= lib (:lib sibling))
      (fail! "implementation/" rel "/deps.edn names " lib " at implementation/" target
             ", whose :clein/build :lib is " (:lib sibling))

      :else {:root root :rel target})))

(defn plan [impl-root & rels]
  (when (empty? rels) (fail! "no publishable artefacts were listed"))
  (let [arts  (mapv #(artefact impl-root %) rels)
        by-rel (into {} (map (juxt :rel identity)) arts)
        edges (into {} (map (juxt :rel #(vec (in-repo-edges % by-rel)))) arts)]
    (loop [done [] left (sort (keys by-rel))]
      (if (empty? left)
        (doseq [rel done]
          (println (str/join "\t" ["PLAN" rel (:lib (by-rel rel))
                                   (str/join " " (map :root (edges rel)))])))
        (let [built (set done)
              ready (filter #(every? (comp built :rel) (edges %)) left)]
          (when (empty? ready)
            (fail! "the in-repo :local/root edges form a cycle among " (str/join ", " left)))
          (recur (into done ready) (remove (set ready) left)))))))

;; ── localize ────────────────────────────────────────────────────────────────

(defn localize [m2 & deps-files]
  (doseq [f deps-files]
    (let [deps (assoc (read-deps f) :mvn/local-repo m2)]
      (binding [*print-namespace-maps* false]
        (spit f (pr-str deps))))))

;; ── sets ────────────────────────────────────────────────────────────────────

(def ^:private template-dir "tools/template/resources/day8/re_frame2_template")
(def ^:private features-file "implementation/core/src/re_frame/features.cljc")
(def ^:private doc-pages ["docs/core/how-to/use-uix.md"
                          "docs/core/fresco/00-installation.md"])

;; Stands in for the template's `{{namespace}}` so its sources read as Clojure.
(def ^:private app-ns "rf2-template-app")

(defn- read-forms
  "Every top-level form in `text`, read as Clojure source without evaluating
  anything: reader conditionals take the :cljs branch, and tagged literals
  (`#js`) read as their value."
  [text where]
  (let [rdr (PushbackReader. (StringReader. text))]
    (binding [*read-eval* false
              *default-data-reader-fn* (fn [_tag v] v)
              *ns* (create-ns 'check-consumer-classpath.reading)]
      (try
        (loop [forms []]
          (let [form (read {:eof ::eof :read-cond :allow :features #{:cljs}} rdr)]
            (if (= ::eof form) forms (recur (conj forms form)))))
        (catch Exception e
          (fail! "cannot read " where " as Clojure: " (.getMessage e)))))))

(defn- ns-requires
  "The namespaces an `(ns …)` form's :require clauses name. A string libspec
  is an npm module, which no JVM classpath carries."
  [ns-form where]
  (vec (for [clause (drop 2 ns-form)
             :when (and (seq? clause) (= :require (first clause)))
             spec  (rest clause)
             :when (not (string? spec))
             :when (not (and (vector? spec) (string? (first spec))))]
         (cond (symbol? spec) (str spec)
               (and (vector? spec) (symbol? (first spec))) (str (first spec))
               :else (fail! where ": cannot classify the libspec " (pr-str spec))))))

(defn- ns-form? [form] (and (seq? form) (= 'ns (first form))))

(defn- deps-map?
  "A deps.edn map: :deps maps lib symbols to coordinate maps. shadow-cljs.edn
  also carries a :deps key, mapping keywords, and is not one."
  [form]
  (let [deps (and (map? form) (:deps form))]
    (and (map? deps) (seq deps)
         (every? symbol? (keys deps))
         (every? map? (vals deps)))))

(defn- template-sets
  "One set per substrate variant (`_<substrate>/deps.edn`). Its requires are
  what the release build reaches: the variant's `core.cljs` and every app
  namespace it requires, found in the variant or in `_shared/`. Story's
  namespace is not among them — it rides the `:dev` alias, not :deps."
  [repo-root]
  (let [dir      (io/file repo-root template-dir)
        variants (sort (for [^File d (.listFiles dir)
                             :when (and (.isDirectory d)
                                        (str/starts-with? (.getName d) "_")
                                        (.isFile (io/file d "deps.edn")))]
                         (.getName d)))
        app-file (fn [variant ns-name]
                   (let [leaf (str (subs ns-name (inc (count app-ns))) ".cljs")]
                     (or (first (filter #(.isFile ^File %)
                                        [(io/file dir variant leaf) (io/file dir "_shared" leaf)]))
                         (fail! template-dir "/" variant ": no source for the app namespace "
                                (str/replace ns-name app-ns "{{namespace}}")))))
        requires (fn [variant]
                   (loop [todo [(io/file dir variant "core.cljs")] seen #{} out []]
                     (if-let [^File f (first todo)]
                       (if (seen f)
                         (recur (rest todo) seen out)
                         (let [where  (str template-dir "/" variant "/" (.getName f))
                               forms  (read-forms (str/replace (slurp f) "{{namespace}}" app-ns) where)
                               nsf    (or (first (filter ns-form? forms)) (fail! where ": no ns form"))
                               reqs   (ns-requires nsf where)
                               own?   #(str/starts-with? % (str app-ns "."))]
                           (recur (concat (rest todo) (map #(app-file variant %) (filter own? reqs)))
                                  (conj seen f)
                                  (into out (remove own? reqs)))))
                       (vec (distinct out)))))]
    (when (empty? variants) (fail! template-dir ": no _<substrate>/deps.edn variant found"))
    (for [v variants]
      {:label    (str template-dir "/" v "/deps.edn")
       :deps     (:deps (read-deps (io/file dir v "deps.edn")))
       :requires (requires v)})))

(defn- feature-sets
  "One set per entry of the core artefact's static feature table: an app on
  core adds the entry's :maven coordinate and requires its :require."
  [repo-root core-lib]
  (let [forms (read-forms (slurp (io/file repo-root features-file)) features-file)
        table (some #(when (and (seq? %) (= 'def (first %)) (= 'feature-registry (second %)))
                       (last %))
                    forms)]
    (when-not (and (map? table) (seq table))
      (fail! features-file ": no (def feature-registry {...}) map found"))
    (for [[feature {:keys [maven require]}] (sort-by key table)]
      (do (when-not (and (string? maven) (string? require))
            (fail! features-file ": " feature " lacks a string :maven or :require"))
          {:label    (str features-file " " feature)
           :deps     {core-lib {} (symbol maven) {}}
           :requires [require]}))))

(defn- clojure-blocks
  "The page's fenced Clojure blocks, in page order, with their opening line."
  [text]
  (loop [lines (map-indexed vector (str/split-lines text)) open nil acc []]
    (if-let [[n line] (first lines)]
      (let [fence (re-matches #"\s*```\s*([A-Za-z]*).*" line)]
        (cond
          (and fence open) (recur (rest lines) nil (conj acc open))
          fence (recur (rest lines)
                       (when (#{"clojure" "clj" "cljs" "cljc" "edn"} (second fence))
                         {:line (inc n) :text []})
                       acc)
          open (recur (rest lines) (update open :text conj line) acc)
          :else (recur (rest lines) open acc)))
      (cond-> acc open (conj open)))))

(defn- doc-set
  "The page's installation block — the first fenced deps.edn map — and the
  requires of the first `ns` form after it, the page's first screen of code.
  Blocks are read one at a time and the walk stops there, so a block further
  down the page is never this check's business."
  [repo-root page]
  (loop [[block & more] (clojure-blocks (slurp (io/file repo-root page)))
         deps-block nil]
    (if-not block
      (fail! page (if deps-block
                    ": no ns form follows its deps.edn block"
                    ": no fenced deps.edn map with a :deps lib map"))
      (let [where (str page ":" (:line block))
            forms (read-forms (str/join "\n" (:text block)) where)]
        (cond
          (and (nil? deps-block) (some deps-map? forms))
          (recur more {:label where :deps (:deps (first (filter deps-map? forms)))})

          (and deps-block (some ns-form? forms))
          (assoc deps-block :requires (ns-requires (first (filter ns-form? forms)) where))

          :else (recur more deps-block))))))

(defn- consumer-coord
  "What a consumer resolves for a documented coordinate. A publishable lib
  resolves at the release VERSION, whatever the page writes for it before the
  release exists (`<latest>`, `{{rf2-version}}`, a :local/root checkout); any
  other lib must already be a concrete Maven version."
  [publishable version lib coord label]
  (let [v (:mvn/version coord)]
    (cond
      (publishable lib) {:mvn/version version}
      (and (string? v) (re-matches #"[0-9][0-9A-Za-z.+_-]*" v)) coord
      :else (fail! label ": cannot classify the coordinate " lib " " (pr-str coord)
                   ": not a publishable artefact here, and not a concrete :mvn/version"))))

(defn sets [repo-root version m2 out & rels]
  (let [impl-root (io/file repo-root "implementation")
        arts      (map #(artefact impl-root %) rels)
        core      (or (first (filter #(str/starts-with? features-file (str "implementation/" (:rel %) "/")) arts))
                      (fail! "no publishable artefact ships " features-file))
        publishable (set (map :lib arts))
        all-sets  (concat (template-sets repo-root)
                          (feature-sets repo-root (:lib core))
                          (map #(doc-set repo-root %) doc-pages))]
    (doseq [[i {:keys [label deps requires]}] (map-indexed vector all-sets)]
      (when (empty? requires) (fail! label ": documents no require to check"))
      (let [dir  (io/file out (format "%02d" i))
            deps (into (sorted-map)
                       (for [[lib coord] deps]
                         [lib (consumer-coord publishable version lib coord label)]))]
        (.mkdirs dir)
        (binding [*print-namespace-maps* false]
          (spit (io/file dir "deps.edn") (pr-str {:paths [] :deps deps :mvn/local-repo m2}))
          (spit (io/file dir "set.edn") (pr-str {:label label :deps deps :requires requires})))
        (println (str (.getName dir) "\t" label))))))

;; ── lookup ──────────────────────────────────────────────────────────────────

(def ^:private exts [".clj" ".cljc" ".cljs"])

(defn- provider
  "The files under `entry` (a jar or a directory) that would satisfy a
  require of `path` (the namespace's root-resource path), or nil."
  [^String entry path]
  (let [f (io/file entry)]
    (seq (cond
           (and (.isFile f) (str/ends-with? entry ".jar"))
           (with-open [z (ZipFile. f)]
             (vec (for [e exts :when (.getEntry z (str path e))] (str (.getName f) "!/" path e))))

           (.isDirectory f)
           (vec (for [e exts :when (.isFile (io/file f (str path e)))] (str entry "/" path e)))))))

(defn- root-resource [ns-name]
  (-> ns-name (str/replace "-" "_") (str/replace "." "/")))

(defn lookup [out]
  (let [dirs (sort (filter #(.isFile (io/file % "set.edn")) (.listFiles (io/file out))))
        failures (atom 0)
        failed! (fn [msg] (swap! failures inc) (println (str "::error::consumer-classpath: " msg)))]
    (when (empty? dirs) (fail! "no documented coordinate set was written under " out))
    (doseq [^File dir dirs]
      (let [{:keys [label deps requires]} (edn/read-string (slurp (io/file dir "set.edn")))
            exit (str/trim (slurp (io/file dir "classpath.exit")))
            cp   (last (remove str/blank? (str/split-lines (slurp (io/file dir "classpath.out")))))]
        (println (str "set " label))
        (println (str "  coordinates: " (str/join ", " (for [[lib c] deps] (str lib " " (:mvn/version c))))))
        (if (or (not= "0" exit) (nil? cp))
          (failed! (str label ": the set does not resolve (clojure -Srepro -Spath exit " exit "): "
                        (str/join " | " (take-last 5 (remove str/blank? (str/split-lines (slurp (io/file dir "classpath.err"))))))))
          (let [entries (str/split cp (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))]
            (doseq [req requires]
              (let [hits (keep #(provider % (root-resource req)) entries)]
                (case (count hits)
                  0 (failed! (str label ": require " req " resolves to NO classpath file (no "
                                  (root-resource req) ".{clj,cljc,cljs} on the set's classpath)"))
                  1 (println (str "  ok   " req " -> " (str/join ", " (first hits))))
                  (failed! (str label ": require " req " resolves to " (count hits)
                                " classpath entries: " (str/join "; " (map #(str/join ", " %) hits)))))))))))
    (if (pos? @failures)
      (do (println (str "consumer-classpath: FAILED: " @failures " finding(s) across " (count dirs) " documented set(s)"))
          (System/exit 1))
      (println (str "consumer-classpath: PASSED: " (count dirs)
                    " documented set(s), every documented require resolves to exactly one classpath entry")))))

;; ── entry ───────────────────────────────────────────────────────────────────

(let [[cmd & args] *command-line-args*]
  (case cmd
    "plan"     (apply plan args)
    "localize" (apply localize args)
    "sets"     (apply sets args)
    "lookup"   (apply lookup args)
    (fail! "unknown subcommand " (pr-str cmd) " (plan | localize | sets | lookup)")))
