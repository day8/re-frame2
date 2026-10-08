(ns re-frame.adapter.uix-consumer-deps-recipe-test
  "The clean-consumer guard for the UIx dependency recipe.

   `day8/re-frame2-uix` ships `com.pitch/uix.core` and not `com.pitch/uix.dom`,
   and inside this monorepo the aggregate shadow-cljs build injects the UIx
   artefacts globally, so no compile gate sees a recipe that omits a
   coordinate a standalone consumer needs. So the guard is static: every
   `uix.*` namespace the three example sources require (DERIVED, so it moves
   with the sources) must have an owner coordinate named, at the generator
   template's version, in every published recipe — the spec's consumer block,
   the how-to's table and the three example READMEs. The template, the version
   source, deliberately pins no `uix.dom`, because its app mounts through
   `rf.adapter.uix/client-root`; that absence is asserted rather than assumed
   (`retired-coords` in the template suite is the sibling half). A page may
   still name `uix.dom`. This resolves no real classpath — that would need its
   own fixture project and CI lane."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Paths, all repo-root-relative.

(def ^:private adapter-deps-path
  "implementation/adapters/uix/deps.edn")

(def ^:private template-deps-path
  "The single version source for the UIx pair — the generator template emits a
   real consumer project, so whatever it pins is what a consumer gets."
  "tools/template/resources/day8/re_frame2_template/_uix/deps.edn")

(def ^:private example-sources
  ["examples/substrates/uix/counter/core.cljs"
   "examples/substrates/uix/login/core.cljs"
   "examples/substrates/uix/dashboard/core.cljs"])

(def ^:private recipe-pages
  "Every document that publishes a copyable UIx dependency recipe."
  ["spec/Conventions.md"
   "docs/core/how-to/use-uix-or-slim.md"
   "examples/substrates/uix/counter/README.md"
   "examples/substrates/uix/login/README.md"
   "examples/substrates/uix/dashboard/README.md"])

(def ^:private ns->coordinate
  "Which consumer coordinate owns each `uix.*` namespace. A namespace absent
   from this map has no known owner, and the test says so rather than passing."
  {"uix.core" 'com.pitch/uix.core
   "uix.dom"  'com.pitch/uix.dom})

;; ---------------------------------------------------------------------------
;; Reading

(defn- repo-root
  "The nearest ancestor of the working directory carrying `mkdocs.yml`."
  []
  (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? dir)
      (throw (ex-info "repo root not found: no mkdocs.yml above user.dir"
                      {:user-dir (System/getProperty "user.dir")}))

      (.exists (io/file dir "mkdocs.yml")) dir
      :else (recur (.getParentFile dir)))))

(defn- slurp-at [root rel]
  (let [f (io/file root rel)]
    (when-not (.exists f)
      (throw (ex-info (str "missing file: " rel) {:path (str f)})))
    (slurp f)))

(defn- deps-map
  "The `:deps` of an EDN deps file — shipping dependencies only, no aliases."
  [root rel]
  (-> (slurp-at root rel) edn/read-string :deps))

(defn- required-uix-namespaces
  "The `uix.*` namespaces `src` requires, as strings. Matches the opening of a
   `:require` vector, which is how every example spells it —
   `[uix.core :refer [$ defui]]`, and `[uix.core :as uix :refer [$ defui]]` in
   the login example, so the trailing character class has to admit a space as
   well as a closing bracket."
  [src]
  (->> (re-seq #"\[(uix\.[a-zA-Z0-9._-]+)[\s\]]" src)
       (map second)
       set))

(defn- recipe-coordinates
  "Every `com.pitch/uix.<x> {:mvn/version \"v\"}` pair written anywhere in
   `md`, as `{\"uix.core\" \"1.4.4\", …}`. Deliberately a text scan: these live
   inside fenced code blocks and markdown tables, which no doc gate reads."
  [md]
  (into {}
        (for [[_ nm v] (re-seq #"com\.pitch/(uix\.[a-z]+)\s+\{:mvn/version\s+\"([^\"]+)\"\}" md)]
          [nm v])))

;; ---------------------------------------------------------------------------
;; The premise: uix.dom is not transitive.

(deftest adapter-ships-uix-core-but-not-uix-dom
  (let [deps (deps-map (repo-root) adapter-deps-path)]
    ;; Were uix.dom ever shipped, the DOM half would be transitive and the
    ;; recipe assertions below would stop being load-bearing.
    (is (= [true false] [(contains? deps 'com.pitch/uix.core)
                         (contains? deps 'com.pitch/uix.dom)])
        (str adapter-deps-path " must ship com.pitch/uix.core and must not ship"
             " com.pitch/uix.dom; if that changed deliberately, this whole guard"
             " needs revisiting."))))

;; ---------------------------------------------------------------------------
;; Every namespace the copyable mount requires has an owner coordinate.

(deftest every-required-uix-namespace-has-an-owner-coordinate
  (let [root (repo-root)]
    (doseq [rel example-sources]
      (testing rel
        (let [required (required-uix-namespaces (slurp-at root rel))]
          ;; Non-vacuity: a blind scan would pass the ownership check below.
          ;; `uix.core` is the signal because every UIx view file needs it for
          ;; `$` and `defui`, whatever its mount idiom.
          (testing "the scan has signal — the file's uix.core require is seen"
            (is (contains? required "uix.core")
                (str rel " does not appear to require uix.core: either it "
                     "stopped being a UIx view file, or the require-scanning "
                     "regex has gone blind.")))
          (testing "and every uix.* namespace it requires has a known owner"
            (is (empty? (remove ns->coordinate required))
                (str rel " requires " (pr-str (vec (remove ns->coordinate required)))
                     " — no consumer coordinate in this test owns it. Add the "
                     "owner to ns->coordinate AND to every recipe in "
                     (pr-str recipe-pages) "."))))))))

;; ---------------------------------------------------------------------------
;; Every published recipe names those owners, at the template's version.

;; Named for what it checks rather than for a count: the owner set is derived
;; from the example sources, so its size follows how those examples mount. A
;; name carrying the count would go stale the moment the mount changed.
(deftest published-recipes-name-every-required-uix-coordinate-in-lockstep
  (let [root       (repo-root)
        template   (deps-map root template-deps-path)
        core-ver   (get-in template ['com.pitch/uix.core :mvn/version])
        dom-ver    (get-in template ['com.pitch/uix.dom :mvn/version])
        ;; The union of owners across all three examples: what a consumer
        ;; copying any of them must be able to resolve.
        owners     (->> example-sources
                        (mapcat #(required-uix-namespaces (slurp-at root %)))
                        set)]
    (testing "the template pins uix.core — the one version source"
      (is (some? core-ver) (str template-deps-path " has no com.pitch/uix.core.")))
    (testing "and deliberately does NOT pin uix.dom"
      ;; A uix.dom pin would teach a hand-rolled `uix-dom/create-root` boot.
      (is (nil? dom-ver)
          (str template-deps-path " pins com.pitch/uix.dom at "
               (pr-str dom-ver) ", but the emitted app mounts through"
               " `rf.adapter.uix/client-root`. If the pin was deliberate, this"
               " guard and `retired-coords` in the template suite both need"
               " revisiting rather than relaxing.")))
    (doseq [rel recipe-pages]
      (testing rel
        (let [found (recipe-coordinates (slurp-at root rel))]
          (doseq [nm (sort owners)]
            (testing (str "names com.pitch/" nm " at the template's version")
              ;; A recipe that omits the coordinate reads nil here.
              (is (= core-ver (get found nm))
                  (str rel " pins com.pitch/" nm " at " (pr-str (get found nm))
                       " (nil: not named, so a consumer copying it cannot resolve"
                       " what the examples require) but " template-deps-path
                       " pins " core-ver ".")))))))))
