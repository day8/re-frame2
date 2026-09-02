;;;; tests/first_counter_derivation.clj — derives the setup skill's default
;;;; scaffold from the generator template.
;;;;
;;;; `references/first-counter.md` ships the twelve files the skill writes on
;;;; its default route. Those files are NOT hand-maintained: they are the
;;;; generator template's own emission for its reference project
;;;; (`:name acme/my-app`, the default `:reagent` substrate), rendered into
;;;; the leaf by this script. `references/entry-namespace.md` §UIx greenfield
;;;; carries the three files the `:uix` substrate swaps, rendered the same
;;;; way from the template's `_uix/` tree. One source of truth
;;;; (`tools/template/`), two derived views of it.
;;;;
;;;; How the render works. deps-new's emission is three hooks plus a flat
;;;; `{{key}}` substitution: `data-fn` derives the substitution values (the
;;;; namespace forms and the reviewed dependency pins), `template-fn` names
;;;; which resource files land where, and deps-new copies `root/` plus those
;;;; files with every `{{key}}` replaced. This script loads the template's
;;;; REAL `hooks.clj` for the first two, and re-does the copy + substitution —
;;;; the one deps-new-owned step — in a dozen lines. It never carries a pin
;;;; or a file body of its own.
;;;;
;;;; Two locks keep the derived leaves honest, from different instruments:
;;;;
;;;;   * `tests/setup_drift_test.clj` loads this file and asserts the leaves'
;;;;     fenced blocks equal this render (cheap; Babashka; the
;;;;     `skills-structural` CI job).
;;;;   * `tools/template/test/.../emitted_test_run_test.clj` asserts the same
;;;;     blocks equal a REAL deps-new emission byte-for-byte (the
;;;;     `jvm-tools-template` CI job), so a divergence between this renderer
;;;;     and deps-new itself cannot go unseen either.
;;;;
;;;; Regenerate (from skills/re-frame2-setup/):
;;;;
;;;;     bb tests/first_counter_derivation.clj
;;;;
;;;; Only the region between the BEGIN/END markers in each leaf is rewritten;
;;;; the prose around it is hand-maintained.
;;;;
;;;; NOT published — `package.json` :files excludes `tests/`.

(ns first-counter-derivation
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Paths
;; ---------------------------------------------------------------------------

(def setup-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)    ;; tests/
      (.getParentFile)))  ;; skills/re-frame2-setup/

(def repo-root
  (-> setup-root
      (.getParentFile)    ;; skills/
      (.getParentFile)))  ;; repo root

(def template-root (io/file repo-root "tools/template"))

(def hooks-file
  (io/file template-root "src/day8/re_frame2_template/hooks.clj"))

(def resource-root
  (io/file template-root "resources/day8/re_frame2_template"))

(def first-counter-md (io/file setup-root "references/first-counter.md"))
(def entry-namespace-md (io/file setup-root "references/entry-namespace.md"))

;; The generator's reference project — the identity every template test and
;; README example uses. The default route writes exactly this project; an
;; author-supplied name is a rename.
(def reference-identity
  {:name "acme/my-app" :top "acme" :main "my-app"})

;; ---------------------------------------------------------------------------
;; The template's own hooks
;; ---------------------------------------------------------------------------

(load-file (.getPath hooks-file))

(def data-fn     (resolve 'day8.re-frame2-template.hooks/data-fn))
(def template-fn (resolve 'day8.re-frame2-template.hooks/template-fn))

;; ---------------------------------------------------------------------------
;; deps-new's substitution, restated
;; ---------------------------------------------------------------------------

(defn- ->ns   [v] (-> v str (str/replace "/" ".") (str/replace "_" "-")))
(defn- ->file [v] (-> v str (str/replace "." "/") (str/replace "-" "_")))

(defn- subst-map
  "deps-new's `->subst-map`: every key becomes `{{key}}`; unqualified keys
   with string / symbol / keyword values also get `{{key/ns}}` and
   `{{key/file}}`."
  [data]
  (reduce-kv (fn [m k v]
               (let [n (namespace k)
                     s (str (when n (str n "/")) (name k))
                     v (if (keyword? v) (name v) v)]
                 (cond-> (assoc m (str "{{" s "}}") (str v))
                   (and (nil? n) (string? v))
                   (assoc (str "{{" s "/ns}}")   (->ns v)
                          (str "{{" s "/file}}") (->file v)))))
             {}
             data))

(defn- substitute [s m]
  (reduce (fn [s [from to]] (str/replace s from to)) s m))

(defn- lf [s] (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n")))

(defn- slurp-lf [f] (lf (slurp f)))

;; ---------------------------------------------------------------------------
;; Render
;; ---------------------------------------------------------------------------

(defn render-project
  "The files the template emits for `reference-identity` on `substrate`
   (`:reagent` or `:uix`), as a sorted map of project-relative path →
   content (LF line endings). Mirrors deps-new's emission contract: copy
   `root/` verbatim-with-substitution, then apply each `[src target
   file-map :only]` transform `template-fn` returns."
  [substrate]
  (let [args   (cond-> reference-identity substrate (assoc :substrate substrate))
        data   (data-fn args)
        m      (subst-map (merge reference-identity data))
        edn    (template-fn {} data)
        root   (io/file resource-root "root")
        rooted (into (sorted-map)
                     (for [f (file-seq root) :when (.isFile f)]
                       (let [rel (-> (.relativize (.toPath root) (.toPath f))
                                     str (str/replace "\\" "/"))]
                         [(substitute rel m) (substitute (slurp-lf f) m)])))]
    (reduce (fn [acc [src target file-map _only]]
              (into acc
                    (for [[from to] file-map]
                      (let [prefix (if (= "." target) "" (str target "/"))]
                        [(substitute (str prefix to) m)
                         (substitute (slurp-lf (io/file resource-root src from)) m)]))))
            rooted
            (:transform edn))))

(defn substrate-swap-paths
  "The project-relative paths the `:uix` substrate swaps — read off
   `template-fn`'s per-substrate transform, so the leaf pins what the
   template actually varies."
  []
  (let [data (data-fn (assoc reference-identity :substrate :uix))
        m    (subst-map (merge reference-identity data))]
    (->> (:transform (template-fn {} data))
         (remove (fn [[src]] (= "_shared" src)))
         (mapcat (fn [[_ _ file-map]] (vals file-map)))
         (map #(substitute % m))
         sort
         vec)))

;; ---------------------------------------------------------------------------
;; Markdown
;; ---------------------------------------------------------------------------

(def begin-marker "<!-- BEGIN generated by tests/first_counter_derivation.clj -->")
(def end-marker   "<!-- END generated -->")

(def ^:private reading-order
  "The order the leaf presents the twelve files: build config first, then
   the page, then the source, then the starter test and the README."
  ["deps.edn" "package.json" "shadow-cljs.edn" ".gitignore"
   "resources/public/index.html" "resources/public/css/app.css"
   "src/acme/my_app/core.cljs" "src/acme/my_app/events.cljs"
   "src/acme/my_app/subs.cljs" "src/acme/my_app/views.cljs"
   "test/acme/my_app/events_test.cljs" "README.md"])

(defn- fence-lang [path]
  (cond
    (str/ends-with? path ".edn")   "clojure"
    (str/ends-with? path ".cljs")  "clojure"
    (str/ends-with? path ".json")  "json"
    (str/ends-with? path ".html")  "html"
    (str/ends-with? path ".css")   "css"
    (str/ends-with? path ".md")    "markdown"
    (= path ".gitignore")          "gitignore"
    :else                          "text"))

(defn- fence-for
  "Three backticks, or four when the body itself contains a fence."
  [body]
  (if (str/includes? body "```") "````" "```"))

(defn render-file-section [path body]
  (let [fence (fence-for body)]
    (str "### `" path "`\n\n"
         fence (fence-lang path) "\n"
         body
         (when-not (str/ends-with? body "\n") "\n")
         fence "\n")))

(defn render-region
  "The generated region: one `### \\`path\\`` heading + fenced block per
   file, in `paths` order."
  [files paths]
  (str begin-marker "\n\n"
       (str/join "\n" (map #(render-file-section % (get files %)) paths))
       "\n" end-marker "\n"))

(defn extract-files
  "Read the `### \\`path\\`` + fenced-block pairs back out of a leaf's
   generated region: path → body (LF). The inverse of `render-region`, and
   what both drift locks compare against."
  [md-text]
  (let [text   (lf md-text)
        start  (str/index-of text begin-marker)
        end    (str/index-of text end-marker)
        region (if (and start end) (subs text start end) "")]
    (into (sorted-map)
          (for [[_ path _ body] (re-seq #"(?s)### `([^`\n]+)`\n\n(`{3,4})[a-z]*\n(.*?)\n\2\n" region)]
            [path (str body "\n")]))))

(defn- replace-region [md-text new-region]
  (let [text  (lf md-text)
        start (str/index-of text begin-marker)
        end   (some-> (str/index-of text end-marker) (+ (count end-marker)))]
    (when-not (and start end)
      (throw (ex-info "leaf carries no BEGIN/END generated markers" {})))
    (str (subs text 0 start)
         (str/trim-newline new-region) "\n"
         (subs text end))))

(defn reagent-files [] (render-project :reagent))

(defn uix-swap-files []
  (select-keys (render-project :uix) (substrate-swap-paths)))

(defn expected-first-counter-region []
  (render-region (reagent-files) reading-order))

(defn expected-uix-region []
  (render-region (uix-swap-files) (substrate-swap-paths)))

(defn regenerate! []
  (doseq [[leaf region] [[first-counter-md (expected-first-counter-region)]
                         [entry-namespace-md (expected-uix-region)]]]
    (spit leaf (replace-region (slurp leaf) region))
    (println "wrote" (.getPath leaf))))

;; Script entry: `bb tests/first_counter_derivation.clj` rewrites both
;; generated regions. Loading the file from a test (`load-file`) does not.
(when (= *file* (System/getProperty "babashka.file"))
  (regenerate!))
