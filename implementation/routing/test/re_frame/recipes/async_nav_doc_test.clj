(ns re-frame.recipes.async-nav-doc-test
  "Holds the `:optimistic` snippet of
  `docs/design/fresco/product/async-routing-recipes.md` to the application
  it reports. A code block is the one part of a page a reader copies, and
  both suites beside this one exercise the APPLICATION, so they stay green
  however wrong the snippet is.

  ## Why the shape matters

  `re-frame.recipes.async-nav` registers an `:optimistic` plan whose target
  is a MAP — `{:resource … :params … :scope …}`. The `[id params]` VECTOR
  spelling is not a near-miss: optimistic arms run BEFORE the request
  lowers, so a target that could write the cache under a wrong identity is
  refused outright (`:rf.error/mutation-invalid-target`, pinned by
  `vector-shaped-optimistic-target-refuses-readably` in
  `re-frame.resources-optimistic-validation-cljs-test`) and takes the
  request with it: no instance, no request, `:idle` afterwards.

  ## The rule

  **Every `:optimistic` target printed in a fenced `clojure` block of that
  page must be a target `async_nav.cljs` itself registers, and every target
  on either side must be the `{:resource :params :scope}` map the runtime
  accepts.** Both halves are load-bearing and neither implies the other:

  - **The match** catches DRIFT. The page and the application are read as
    data and compared as data, down to the resource symbol and the scope
    keyword.
  - **The shape** catches BORN-WRONG. A rule that only compared the two
    sides would be satisfied by making the application wrong too.

  The question is answerable outright — the target either is the one on
  the classpath or it is not — so it is asked directly rather than through
  a digest roster, which would certify a block that was wrong when it was
  pinned.

  ## The population

  The blocks that TEACH an optimistic plan, not every block on the page.
  Page-wide, `{:can-leave [::can-leave?] …}` would not read — a map literal
  with an odd number of forms, `…` meaning *and the rest of the route* —
  and elliptical fragments are how this corpus prints one. A block in the
  population that does not read throws out of [[read-forms]], so it reds
  both checks rather than contributing no targets, and a page that stops
  teaching a plan at all reds the shape check's non-empty read.

  ## Bounds

  One page, one class, one application. The plan is read in its LITERAL
  shape — `(fn [params] {target …})` — and a plan refactored behind a `let`
  is reported rather than followed, because a clever walker would
  eventually follow something into a false green. Nothing here evaluates a
  line of the page or of the application; this namespace reads text and
  compares data."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;; ---------------------------------------------------------------------------
;; Locating the two files
;; ---------------------------------------------------------------------------

(defn- repo-root
  "The repository root, found by walking up for the `AGENTS.md` marker
  rather than by a hardcoded relative path — this suite runs from
  `implementation/routing` locally and from the repo root in CI."
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private page-path
  "docs/design/fresco/product/async-routing-recipes.md")

(def ^:private app-path
  "implementation/routing/test/re_frame/recipes/async_nav.cljs")

(defn- slurp-repo-file
  "Read one repo-relative path, failing with the path if the file has
  moved: the page and the application are a pair, and half a pair is the
  staleness this gate exists to end."
  [rel]
  (let [f (io/file (repo-root) rel)]
    (when-not (.isFile f)
      (throw (ex-info (str "missing: " rel " — the page and the application are a pair; "
                           "if one moved, move this witness's path with it")
                      {:path rel})))
    (slurp f)))

;; ---------------------------------------------------------------------------
;; Splitting a page into fenced blocks, and reading without evaluating
;; ---------------------------------------------------------------------------

(defn- clojure-blocks
  "The body of every fenced `clojure` block in `text`, in document order. A
  fence is a line opening with three backticks and the block runs to the
  next such line; that is the whole parser."
  [text]
  (let [lines  (vec (str/split (str/replace text "\r\n" "\n") #"\n" -1))
        fence? #(str/starts-with? ^String % "```")]
    (loop [i 0, acc []]
      (cond
        (>= i (count lines)) acc

        (not (fence? (nth lines i))) (recur (inc i) acc)

        :else
        (let [lang  (str/trim (subs (nth lines i) 3))
              close (or (first (keep-indexed #(when (fence? %2) (+ i 1 %1))
                                             (subvec lines (inc i))))
                        (count lines))]
          (recur (inc close)
                 (cond-> acc
                   (= "clojure" lang)
                   (conj (str/join "\n" (subvec lines (inc i) close))))))))))

(defn- read-forms
  "Every top-level form in `s`, as data. Read under this namespace so `::foo`
  resolves (and prints qualified by THIS namespace in a failure), with the
  `:cljs` branch of any reader conditional taken and unknown tagged
  literals passed through. NOTHING is evaluated, and the application need
  not be loadable on the JVM — which it is not."
  [^String s]
  (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. s))]
    (binding [*ns*                     (the-ns 're-frame.recipes.async-nav-doc-test)
              *default-data-reader-fn* (fn [_tag value] value)]
      (into [] (take-while #(not= ::eof %))
            (repeatedly #(read {:eof ::eof :read-cond :allow :features #{:cljs}} rdr))))))

;; ---------------------------------------------------------------------------
;; Extracting the optimistic targets
;; ---------------------------------------------------------------------------

(def ^:private required-target-keys
  "The keys an optimistic target map carries. Exactly these: a target is
  an IDENTITY, and a partial identity would write the cache somewhere
  nobody asked for."
  #{:resource :params :scope})

(defn- plan-targets
  "The targets one `:optimistic` plan declares, each classified.

  `{:kind :map :target …}` is the accepted shape; `{:kind :rejected
  :target …}` is anything else, which is where the `[id params]` vector
  lands; the two `:not-a-literal-*` kinds report a plan this reader
  cannot see into rather than passing it."
  [plan]
  (let [ret (when (and (seq? plan) (contains? '#{fn fn*} (first plan)))
              (last plan))]
    (cond
      (nil? ret) [{:kind :not-a-literal-fn :form plan}]

      (map? ret) (mapv (fn [target]
                         {:kind   (if (and (map? target)
                                           (= required-target-keys (set (keys target))))
                                    :map
                                    :rejected)
                          :target target})
                       (keys ret))

      :else [{:kind :not-a-literal-map :form ret}])))

(defn- optimistic-targets
  "Every optimistic target declared anywhere in `forms`, classified.

  The rule is one line: in any sequential node, an element `:optimistic`
  is followed by its plan. That covers a map's entry (how the
  application spells it, inside `reg-mutation`) and a bare key/value
  fragment (how the page prints it) with no special case for either,
  because a map entry IS a sequential node."
  [forms]
  (into []
        (for [node   (tree-seq coll? seq forms)
              :when  (sequential? node)
              [k v]  (partition 2 1 node)
              :when  (= :optimistic k)
              target (plan-targets v)]
          target)))

(defn- describe
  [{:keys [kind target form]}]
  (case kind
    :map               (str "ok  " (pr-str target))
    :rejected          (str "REJECTED SHAPE — " (pr-str target)
                            " is not a " (pr-str (vec (sort required-target-keys)))
                            " map. The runtime refuses a wrong-identity target BEFORE"
                            " the request lowers, so this spelling deletes the whole"
                            " mutation: no instance, no request, :idle afterwards")
    :not-a-literal-fn  (str "UNREADABLE PLAN — " (pr-str form)
                            " is not a literal `(fn …)`; this witness reads the literal shape")
    :not-a-literal-map (str "UNREADABLE PLAN — the plan's last form is " (pr-str form)
                            ", not a literal target map")))

;; ---------------------------------------------------------------------------
;; The checks
;; ---------------------------------------------------------------------------

(def ^:private optimistic-token
  "The registration key, spelled so `:optimistic?` — a different keyword,
  the one the READ side uses — cannot be mistaken for it."
  #":optimistic(?![?\w-])")

(defn- page-forms []
  (into []
        (mapcat read-forms)
        (filter #(re-find optimistic-token %) (clojure-blocks (slurp-repo-file page-path)))))

(defn- app-forms [] (read-forms (slurp-repo-file app-path)))

(deftest every-optimistic-target-is-the-map-the-runtime-accepts
  ;; The BORN-WRONG half, on both sides. An empty side reds too: a reader
  ;; that sees no target is this witness grading nothing.
  (doseq [[what targets] [[page-path (optimistic-targets (page-forms))]
                          [app-path  (optimistic-targets (app-forms))]]]
    (is (= #{:map} (into #{} (map :kind) targets))
        (str "\n" what
             (if (seq targets)
               (str "\n" (str/join "\n" (map describe targets)))
               " declares no `:optimistic` target this reader can see")
             "\n"))))

(deftest the-page-publishes-only-targets-the-application-registers
  ;; The DRIFT half. A subset: the page may report fewer targets than the
  ;; application registers, never one the application does not have.
  (let [published  (into #{} (map :target) (optimistic-targets (page-forms)))
        registered (into #{} (map :target) (optimistic-targets (app-forms)))
        orphans    (set/difference published registered)]
    (is (= #{} orphans)
        (str "\n" page-path " publishes " (count orphans)
             " optimistic target(s) that " app-path " does not register.\n"
             "  published, unmatched: " (pr-str (vec orphans)) "\n"
             "  registered:           " (pr-str (vec registered)) "\n"
             "A reader copies the page, not the test. Make the snippet mirror the "
             "application, or change the application first.\n"))))
