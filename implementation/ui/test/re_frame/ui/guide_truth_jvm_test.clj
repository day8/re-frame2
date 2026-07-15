(ns re-frame.ui.guide-truth-jvm-test
  "Focused truth gates for the tracked pre-publication re-frame.ui guide.

  These assertions protect the ruled lifecycle recipe, guide-wide rendering
  boundary, Xray S3 surface, and S6 relocation plan until the guide moves to
  docs/ui."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def chapter-files
  ["01-getting-started.md"
   "02-views.md"
   "03-state.md"
   "04-events.md"
   "05-frames.md"
   "06-worked-app.md"
   "07-servers.md"
   "08-testing.md"
   "09-debugging.md"
   "10-performance.md"
   "11-ssr.md"
   "12-how-it-works.md"
   "13-from-other-worlds.md"
   "14-compile-time-limits.md"])

(defn- repo-root
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private root (delay (repo-root)))

(defn- root-file
  [relative]
  (io/file @root relative))

(defn- guide-file
  [name]
  (root-file (str "ai/findings/new-substrate-synthesis/guide/" name)))

(defn- slurp-guide
  [name]
  (slurp (guide-file name)))

(defn- markdown-targets
  [text]
  (map second (re-seq #"\]\(([^)]+)\)" text)))

(defn- target-path
  [target]
  (first (str/split target #"#" 2)))

(deftest lifecycle-recipe-is-installed-and-owned
  (let [getting-started (slurp-guide "01-getting-started.md")
        events          (slurp-guide "04-events.md")
        worked-app      (slurp-guide "06-worked-app.md")
        servers         (slurp-guide "07-servers.md")
        testing-guide   (slurp-guide "08-testing.md")]
    (testing "the one documented fixture installs an adapter for caller-owned frames"
      (is (str/includes? testing-guide
                         "test-support/make-reset-runtime-fixture"))
      (is (str/includes? testing-guide ":adapter plain-atom/adapter"))
      (is (str/includes? testing-guide ":ambient-frame nil")))
    (testing "every synchronous canonical test uses the eval-bind-run-destroy form"
      (doseq [[chapter text] [["01" getting-started]
                              ["04" events]
                              ["06" worked-app]
                              ["07" servers]
                              ["08" testing-guide]]]
        (is (str/includes? text "rf/with-new-frame")
            (str "Guide " chapter " must retain caller-owned frame cleanup"))))
    (testing "mounted examples keep root and frame ownership separate"
      (is (str/includes? testing-guide
                         "`with-root` owns its React root and DOM container"))
      (is (str/includes? testing-guide "[frame thunk]"))
      (is (str/includes? testing-guide "(js/Promise.resolve (thunk))"))
      (is (str/includes? testing-guide "rfUiTestCleanupError"))
      (is (not (str/includes? testing-guide "[frame promise]")))
      (is (re-find #"destroy-frame-after!\s+frame\s+#\(ui\.test/with-root"
                   testing-guide)
          "mounted call sites pass with-root as a thunk")
      (is (str/includes? testing-guide "(rf/destroy-frame! frame)"))
      (is (str/includes? testing-guide "reject-after-current!")))))

(deftest pipeline-and-stage-claims-stay-truthful
  (let [chapters (into {} (map (juxt identity slurp-guide) chapter-files))
        views    (get chapters "02-views.md")
        ssr      (get chapters "11-ssr.md")
        how      (get chapters "12-how-it-works.md")
        limits   (get chapters "14-compile-time-limits.md")
        retired-claim-patterns
        [["host outputs cannot drift"
          #"(?is)(?:(?:client|browser|server|ssr|jvm)[^\n]{0,120}(?:cannot|can't)\s+drift|(?:cannot|can't)\s+drift[^\n]{0,120}(?:client|browser|server|ssr|jvm))"]
         ["browser/JVM share one AST value"
          #"(?i)(?:same|shared)\s+(?:template\s+)?AST\b|\bshare(?:s|d)?\s+the\s+AST\b|\btwin\s+of\s+the\s+same\s+AST\b"]
         ["one emitter implements both hosts"
          #"(?i)(?:(?:one|same)\s+emitter[^\n]{0,120}(?:both\s+hosts|client[^\n]*server|browser[^\n]*jvm)|(?:both\s+hosts|client[^\n]*server|browser[^\n]*jvm)[^\n]{0,120}(?:one|same)\s+emitter)"]
         ["host output is identical"
          #"(?i)identically\s+on\s+(?:the\s+)?(?:client|browser)[^\n]{0,40}(?:server|jvm)"]
         ["no second serializer"
          #"(?i)no\s+second\s+seriali[sz]er"]
         ["JVM string emitter"
          #"(?i)(?:a\s+)?string\s+emitter\s+for\s+the\s+jvm"]
         ["equivalent by construction"
          #"(?i)structurally\s+equivalent\s+by\s+construction"]
         ["no implementation can drift"
          #"(?i)there\s+is\s+no\s+second\s+implementation\s+to\s+drift"]]]
    (testing "JVM emission is structural data and HTML serialization is S5"
      (doseq [required ["versioned `re-frame.ui.tree` structural data"
                        "`re-frame.ssr/emit-ui-tree`"
                        "frozen Reagent/hiccup"
                        "conversion contract and parity gates"]]
        (is (or (str/includes? ssr required)
                (str/includes? how required))
            (str "missing ruled pipeline phrase: " required))))
    (testing "chapters 02 and 14 state the same precise three-stage responsibility"
      (doseq [[chapter text] [["02" views] ["14" limits]]]
        (is (re-find #"(?is)browser.{0,80}direct.{0,40}React" text)
            (str "Guide " chapter " must name browser direct React output"))
        (doseq [[responsibility pattern]
                [["versioned re-frame.ui.tree structural data"
                  #"(?is)versioned\s+`re-frame\.ui\.tree`\s+structural\s+data"]
                 ["S5 HTML serializer"
                  #"`re-frame\.ssr/emit-ui-tree`"]
                 ["conversion contract"
                  #"(?i)conversion\s+contract"]
                 ["parity gates"
                  #"(?i)parity\s+gates"]]]
          (is (re-find pattern text)
              (str "Guide " chapter " missing ruled responsibility: "
                   responsibility)))))
    (testing "retired rendering overclaims stay absent from every numbered chapter"
      (doseq [[chapter text] chapters
              [claim pattern] retired-claim-patterns]
        (is (not (re-find pattern text))
            (str chapter " reintroduced retired claim: " claim))))
    (testing "controlled inputs state the exact S3 causal sequence"
      (is (re-find
            #"browser input event → synchronous drain/epoch commit → ViewCell snapshot advance →\s+React's discrete render observes the matching value"
            how))
      (is (str/includes? how "G-8 real-browser fixture")))))

(deftest xray-s3-stays-on-existing-views-surfaces
  (let [views       (slurp-guide "02-views.md")
        debugging   (slurp-guide "09-debugging.md")
        performance (slurp-guide "10-performance.md")
        combined    (str debugging "\n" performance)]
    (is (not (str/includes? views "dev heatmap")))
    (is (str/includes? combined "existing Views"))
    (is (str/includes? combined "post-S3 information-architecture review"))
    (is (str/includes? performance "`effect` deps are values *(lands S3)*"))
    (is (not (re-find #"(?i)heatmap[^\n]*lands S3" combined)))
    (is (not (str/includes? combined "render timeline")))
    (is (not (str/includes? combined "causes timeline")))))

(deftest readme-and-move-plan-cover-the-current-learning-order
  (let [readme       (slurp-guide "README.md")
        readme-order (map second
                          (re-seq #"\|\s+\d+\s+\|\s+\[[^]]+\]\((\d\d-[^)]+\.md)\)"
                                  readme))
        guide-names  (->> (.listFiles (guide-file "."))
                          (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".md"))
                          set)
        plan         (slurp (root-file
                              "ai/findings/new-substrate-synthesis/drafts/guide-docs-move-plan.md"))]
    (is (= chapter-files readme-order)
        "README numeric order is the single 14-chapter learning order")
    (is (= (conj (set chapter-files) "README.md") guide-names)
        "the source guide contains exactly index + 14 chapters")
    (doseq [chapter chapter-files]
      (is (str/includes? plan
                         (str "| `guide/" chapter "` | `docs/ui/" chapter "` |"))
          (str "missing source/destination map for " chapter))
      (is (str/includes? plan (str "\": ui/" chapter))
          (str "missing MkDocs nav entry for " chapter)))
    (is (str/includes? plan "15 guide files + the migration doc"))
    (is (str/includes? plan "16 files added (`docs/ui/index.md`, 14 chapters"))
    (is (= 7 (reduce + (map #(count (re-seq #"guide:no-fixture"
                                             (slurp-guide %)))
                            chapter-files)))
        "the move plan's seven-fence waiver census must match the guide")
    (is (not (str/includes? readme
                            "../../../../docs/core/frames.md"))
        "compatibility-only Frames teaching must not be a canonical continuation")))

(deftest simulated-docs-ui-relocation-keeps-every-guide-link-in-repo
  (let [source-prefix "../../../../docs/core/"
        expected-core-targets
        {"../../../../docs/core/introduction.md"     2
         "../../../../docs/core/app-db.md"           1
         "../../../../docs/core/subscriptions.md"    1
         "../../../../docs/core/effects.md"          2
         "../../../../docs/core/testing/index.md"    2
         "../../../../docs/core/where-state-lives.md" 2}
        plan      (slurp (root-file
                           "ai/findings/new-substrate-synthesis/drafts/guide-docs-move-plan.md"))
        all-pages (cons "README.md" chapter-files)
        targets   (for [page all-pages
                        target (markdown-targets (slurp-guide page))
                        :let [path (target-path target)]
                        :when (and (seq path)
                                   (not (str/starts-with? path "/"))
                                   (not (re-find #"^[a-z][a-z0-9+.-]*:" path)))]
                    [page path])
        core-targets (->> targets
                          (map second)
                          (filter #(str/starts-with? % source-prefix))
                          frequencies)]
    (is (= expected-core-targets core-targets)
        "core-link census changed without updating the S6 relocation plan")
    (doseq [source-target (keys expected-core-targets)]
      (let [moved-target (str "../core/" (subs source-target (count source-prefix)))]
        (is (str/includes? plan source-target)
            (str "move plan omits source target " source-target))
        (is (str/includes? plan moved-target)
            (str "move plan omits relocated target " moved-target))))
    (doseq [[page path] targets]
      (if (str/starts-with? path source-prefix)
        (let [moved-target (str "../core/" (subs path (count source-prefix)))
              destination  (.getCanonicalFile
                             (root-file (str "docs/ui/" moved-target)))]
          (is (.isFile destination)
              (str page " relocates inside the repo: " moved-target)))
        (is (.isFile (guide-file path))
            (str page " has a resolvable chapter link: " path))))))
