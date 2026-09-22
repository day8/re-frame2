(ns re-frame.adapter.uix-consumer-deps-recipe-test
  "The clean-consumer guard for the UIx dependency recipe (rf2-5x1xt).

   `day8/re-frame2-uix` ships `com.pitch/uix.core` and deliberately does NOT
   ship `com.pitch/uix.dom` — mounting a React root is the application's call,
   so the DOM half never arrives transitively. Inside this monorepo every UIx
   example compiles regardless of what its recipe names, because the aggregate
   `implementation/shadow-cljs.edn` build injects the UIx artefacts globally.
   That ambient dependency is precisely why the per-example compile gate cannot
   see the omission a standalone consumer hits: it is masked at exactly the
   layer the consumer does not have.

   So the guard is static rather than a compile. It reads the three example
   sources, collects every `uix.*` namespace they require, and insists each one
   has a named owner coordinate in every place the project publishes a UIx
   dependency recipe — the spec's consumer block, the how-to's coordinate
   table, and the three example READMEs — all pinned to the one version source,
   the generator template. Drop a coordinate the examples require from any of
   those and this goes red naming the file.

   The required set is DERIVED from the sources rather than listed here, so it
   narrows when a mount stops needing something. That is not the guard going
   quiet: what it protects is the gap between what the copyable source requires
   and what the published recipes name, and both halves move together.

   The generator template is the VERSION SOURCE, not a fourth recipe, and it
   alone does not name `uix.dom` (rf2-j908): the app it emits mounts through
   `rf.adapter.uix/client-root` + `render!`, so the Root is minted by the
   shared React spine and the DOM half is not a day-one dependency. That
   absence is asserted here positively rather than merely dropped, because a
   deleted assertion asserts nothing; `retired-coords` in the template suite
   is the sibling half, which refuses the coordinate's return anywhere in the
   emitted `deps.edn`.

   The three examples used to mint their own Root and so required `uix.dom`
   too, which is why the recipe pages named it. They now mount through the
   same `client-root` / `render!` door the template emits (rf2-fn0kx.8), so
   `uix.dom` is no longer in the derived owner set and the recipe assertions
   no longer demand it. A page may still name it — `docs/core/testing/views.md`
   has a component-test recipe that really does drive a Root by hand — and
   nothing here forbids that; the guard only insists that what the examples
   DO require is nameable and named.

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
          ;;
          ;; The signal namespace is `uix.core`, and it used to be `uix.dom`
          ;; (rf2-fn0kx.8). That was never weaker as a control, but it was
          ;; pinned to the wrong thing: these examples minted their own React
          ;; Root, so `uix.dom` was in their requires, and asserting it made
          ;; the control an assertion about the MOUNT IDIOM rather than about
          ;; the scan. When the examples moved onto the adapter's
          ;; `client-root` / `render!` — the shape the generator template
          ;; already emitted — the control went red for a change it was never
          ;; meant to be sensitive to. `uix.core` is where `$` and `defui`
          ;; come from, so a file cannot stop requiring it and still be a UIx
          ;; view file. It is the one require the scan can depend on finding.
          (testing "the scan has signal — the file's uix.core require is seen"
            (is (contains? required "uix.core")
                (str rel " does not appear to require uix.core. Either it "
                     "stopped being a UIx view file, or the require-scanning "
                     "regex in this test has gone blind — and a blind scan "
                     "passes the ownership check below in the same voice as "
                     "a clean file, which is what this assertion is here to "
                     "prevent.")))
          (testing "and every uix.* namespace it requires has a known owner"
            (is (empty? (remove ns->coordinate required))
                (str rel " requires " (pr-str (vec (remove ns->coordinate required)))
                     " — no consumer coordinate in this test owns it. Add the "
                     "owner to ns->coordinate AND to every recipe in "
                     (pr-str recipe-pages) "."))))))))

;; ---------------------------------------------------------------------------
;; Every published recipe names those owners, at the template's version.

;; Named for what it checks rather than for a count: the owner set is derived
;; from the example sources, so it was two coordinates while those examples
;; minted their own Root and is one now that they mount through the adapter
;; (rf2-fn0kx.8). A name carrying the count goes stale the moment the mount
;; does, which is exactly what happened to the non-vacuity pin above.
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
    (testing "and deliberately does NOT pin uix.dom (rf2-j908)"
      ;; The absence half, asserted rather than assumed. The emitted app
      ;; mounts through the adapter's `client-root` / `render!`, so uix.dom
      ;; is not on its classpath and there is no second version to keep in
      ;; lockstep. Restoring the pin would re-teach the superseded
      ;; `uix-dom/create-root` boot, so this must go red if it comes back.
      (is (nil? dom-ver)
          (str template-deps-path " pins com.pitch/uix.dom at "
               (pr-str dom-ver) ". rf2-j908 retired that coordinate from the "
               "scaffold: the emitted app mounts through "
               "`rf.adapter.uix/client-root` + `render!`, so the React Root "
               "is minted by the shared spine and uix.dom is not a day-one "
               "dependency. If the pin was restored deliberately, this guard "
               "and `retired-coords` in the template suite both need "
               "revisiting rather than relaxing.")))
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
