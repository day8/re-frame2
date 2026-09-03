(ns re-frame.adapter.uix-consumer-deps-recipe-test
  "The clean-consumer guard for the UIx dependency recipe (rf2-5x1xt).

   `day8/re-frame2-uix` ships `com.pitch/uix.core` and deliberately does NOT
   ship `com.pitch/uix.dom` — mounting a React root is the application's call,
   so the DOM half never arrives transitively. Every runnable UIx example
   nevertheless requires `uix.dom` for its mount, and inside this monorepo they
   all compile anyway, because the aggregate `implementation/shadow-cljs.edn`
   build injects both UIx artefacts globally. That ambient dependency is
   precisely why the per-example compile gate cannot see the omission a
   standalone consumer hits: it is masked at exactly the layer the consumer
   does not have.

   So the guard is static rather than a compile. It reads the three example
   sources, collects every `uix.*` namespace they require, and insists each one
   has a named owner coordinate in every place the project publishes a UIx
   dependency recipe — the spec's consumer block, the how-to's coordinate
   table, and the three example READMEs — all pinned to the one version source,
   the generator template. Drop `com.pitch/uix.dom` from any of those and this
   goes red naming the file.

   What this does NOT do is resolve a real classpath. A genuine clean-consumer
   compile would need its own fixture project, its own Maven resolution and its
   own build step — a new CI lane, which this bead is not worth. The invariant
   it can prove cheaply is the one that actually broke: a namespace the copyable
   mount requires with no consumer coordinate that owns it."
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
   `:require` vector, which is how every example spells it:
   `[uix.dom  :as uix-dom]`."
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
    (testing "the adapter's shipping :deps carry uix.core"
      (is (contains? deps 'com.pitch/uix.core)
          (str adapter-deps-path " no longer ships com.pitch/uix.core.")))
    (testing "and deliberately do NOT carry uix.dom"
      ;; If this ever flips, the DOM half became transitive and the recipe
      ;; assertions below stop being load-bearing — so the guard must be
      ;; re-thought rather than left standing as a vacuous pass.
      (is (not (contains? deps 'com.pitch/uix.dom))
          (str adapter-deps-path " now ships com.pitch/uix.dom. That is a "
               "deliberate non-goal (rf2-5x1xt); if it was intended, this "
               "whole guard needs revisiting.")))))

;; ---------------------------------------------------------------------------
;; Every namespace the copyable mount requires has an owner coordinate.

(deftest every-required-uix-namespace-has-an-owner-coordinate
  (let [root (repo-root)]
    (doseq [rel example-sources]
      (testing rel
        (let [required (required-uix-namespaces (slurp-at root rel))]
          ;; Non-vacuity: a scan that found nothing would satisfy the
          ;; ownership check below in the same voice as a clean file.
          (testing "the scan has signal — the mount's uix.dom require is seen"
            (is (contains? required "uix.dom")
                (str rel " does not appear to require uix.dom. Either the "
                     "example changed its mount, or the require-scanning "
                     "regex in this test has gone blind.")))
          (testing "and every uix.* namespace it requires has a known owner"
            (is (empty? (remove ns->coordinate required))
                (str rel " requires " (pr-str (vec (remove ns->coordinate required)))
                     " — no consumer coordinate in this test owns it. Add the "
                     "owner to ns->coordinate AND to every recipe in "
                     (pr-str recipe-pages) "."))))))))

;; ---------------------------------------------------------------------------
;; Every published recipe names those owners, at the template's version.

(deftest published-recipes-name-both-uix-coordinates-in-lockstep
  (let [root       (repo-root)
        template   (deps-map root template-deps-path)
        core-ver   (get-in template ['com.pitch/uix.core :mvn/version])
        dom-ver    (get-in template ['com.pitch/uix.dom :mvn/version])
        ;; The union of owners across all three examples: what a consumer
        ;; copying any of them must be able to resolve.
        owners     (->> example-sources
                        (mapcat #(required-uix-namespaces (slurp-at root %)))
                        set)]
    (testing "the template pins both UIx coordinates"
      (is (some? core-ver) (str template-deps-path " has no com.pitch/uix.core."))
      (is (some? dom-ver)  (str template-deps-path " has no com.pitch/uix.dom.")))
    (testing "at the same version — the pair moves in lockstep"
      (is (= core-ver dom-ver)
          (str template-deps-path " pins uix.core at " core-ver
               " and uix.dom at " dom-ver ".")))
    (doseq [rel recipe-pages]
      (testing rel
        (let [found (recipe-coordinates (slurp-at root rel))]
          (doseq [nm (sort owners)]
            (testing (str "names com.pitch/" nm)
              (is (contains? found nm)
                  (str rel " publishes a UIx dependency recipe that does not "
                       "name com.pitch/" nm ", but the examples require " nm
                       ". A consumer copying this recipe cannot resolve it "
                       "(rf2-5x1xt).")))
            (testing (str "pins com.pitch/" nm " at the template's version")
              (is (= core-ver (get found nm))
                  (str rel " pins com.pitch/" nm " at " (pr-str (get found nm))
                       " but " template-deps-path " pins " core-ver ".")))))))))
