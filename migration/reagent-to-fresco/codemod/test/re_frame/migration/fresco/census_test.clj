(ns re-frame.migration.fresco.census-test
  "Census findings distinguish live calls, recognised files and honest zeros."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.census :as rf.migration.fresco.census]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]))

(defn- scan [src file] (rf.migration.fresco.census/scan src file))

(defn- classes [src file] (mapv :class (:entries (scan src file))))

(defn- summary [& scans] (:summary (rf.migration.fresco.census/build (vec scans))))

(def ^:private form-2
  (str "(ns app.counter\n"
       "  (:require [reagent.core :as r]))\n"
       "\n"
       "(defn counter []\n"
       "  (let [n (r/atom 0)]\n"
       "    (fn [] [:div {:on-click #(swap! n inc)} @n])))\n"))

(deftest the-census-answers-non-empty
  (let [{:keys [entries reagent? unresolved?]} (scan form-2 "app/counter.cljs")]
    (testing "coordinates and API"
      (is (= [true false [{:class :local-reactive-cell :verdict :runtime-blocker
                           :line 5 :col 11 :detail {:api "atom"}}]]
             [reagent? unresolved? (mapv #(select-keys % [:class :verdict :line :col :detail]) entries)])))
    (testing "recovery guidance"
      (is (str/includes? (:note (first entries)) "NO view-local state tier")))))

(deftest every-roster-class-has-a-recovery-sentence
  (is (= []
         (for [[label roster require-spec alias]
               [["reagent"   rf.migration.fresco.census/surface           "[reagent.core :as r]"          "r"]
                ["substrate" rf.migration.fresco.census/substrate-surface "[re-frame.adapter.uix :as ad]" "ad"]]
               [api {:keys [verdict]}] roster
               :let  [note (:note (first (:entries (scan (str "(ns a (:require " require-spec "))\n"
                                                               "(" alias "/" api " x)\n")
                                                          "a.cljs"))))]
               :when (not (and (contains? (set rf.migration.fresco.census/verdicts) verdict) (string? note)))]
           (str label " " api)))
      "a class the roster can produce but the verdicts or the notes cannot describe"))

(def ^:private vendored-require
  (str "(ns app.panel\n"
       "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core :as r]))\n"
       "\n"
       "(defonce state (atom nil))\n"
       "\n"
       "(defn panel [] (r/atom {}))\n"))

(deftest an-unbindable-require-is-reported
  (let [{:keys [entries unresolved?]} (scan vendored-require "app/panel.cljs")]
    (is (true? unresolved?))
    (is (= [[:unresolved-reagent-require 1 nil]
            [:unresolved-alias 6 {:api "atom" :symbol "r/atom"}]]
           (mapv (juxt :class :line :detail) entries))
        "exactly two: the require at the `ns` form, where the fix goes, and the call
         naming the symbol it could not bind. `(atom nil)` on line 4 is
         `clojure.core`'s: an alias is what could not be bound, so a call with no
         alias is not evidence of it")
    (testing "warn that the whole tool family is blind"
      (is (str/includes? (:note (first entries)) "PARTIALLY BLIND")))))

(deftest a-reader-conditional-require-binds
  (testing "reader conditional around the require clause"
    (is (= [:local-reactive-cell]
           (classes (str "(ns app.ssr\n"
                         "  #?(:cljs (:require [reagent.core :as r])))\n"
                         "\n"
                         "(defn v [] (r/atom 1))\n")
                    "app/ssr.cljc")))))

(deftest an-ns-form-under-metadata-is-still-the-ns-form
  (testing "metadata around the namespace"
    (let [{:keys [entries reagent? unresolved?]}
          (scan (str "^:cljstyle/ignore\n"
                     "(ns ^{:doc \"Graph and controls.\"}\n"
                     " app.graph\n"
                     "  (:require\n"
                     "   [re-frame.core :as rf]\n"
                     "   [reagent.core :as r]))\n"
                     "\n"
                     "(def graph-ref-map (r/atom {}))\n")
                "app/graph.cljs")]
      (is (= [true false [[:local-reactive-cell 8]]]
             [reagent? unresolved? (mapv (juxt :class :line) entries)]))))
  (testing "report an unresolved namespace once"
    (is (= [:unresolved-reagent-require]
           (classes (str "^:cljstyle/ignore\n"
                         "(ns app.graph\n"
                         "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core\n"
                         "             :as r]))\n")
                    "app/graph.cljc"))))
  (testing "metadata and reader conditionals compose"
    (is (= [:local-reactive-cell]
           (classes (str "^:cljstyle/ignore\n"
                         "(ns app.graph\n"
                         "  (:require #?(:cljs [reagent.core :as r])))\n"
                         "\n"
                         "(def graph-ref-map (r/atom {}))\n")
                    "app/graph.cljc")))))

(deftest prose-about-reagent-is-not-a-finding
  (testing "namespace prose is not a require"
    (let [{:keys [entries reagent?]}
          (scan (str "(ns app.editor\n"
                     "  \"Settle is driven by causal events, not Form-3 `reagent.ratom/run!`\n"
                     "   reactions watching a settle, and there is no `reagent.core/atom` here.\"\n"
                     "  (:require [clojure.string :as str]))\n"
                     "\n"
                     "(defn page [] [:div (str/upper-case \"x\")])\n")
                "app/editor.cljs")]
      (is (= [[] false] [entries reagent?])))))

(deftest a-core-name-is-not-reagents-without-a-binding
  (testing "unbound core names stay clean"
    (is (= [] (classes (str "(ns app.util\n"
                            "  (:require [clojure.string :as str]))\n"
                            "\n"
                            "(def cache (atom {}))\n"
                            "(defn go [f xs] (run! f xs))\n"
                            "(defn f [g] (partial g 1))\n"
                            "(defn h [m] (flush))\n")
                       "app/util.cljs"))))
  (testing "explicit referrals bind"
    (is (= [:local-reactive-cell]
           (classes (str "(ns app.util\n"
                         "  (:require [reagent.core :refer [atom]]))\n"
                         "\n"
                         "(def cache (atom {}))\n")
                    "app/util.cljs")))))

(deftest inert-source-is-not-a-call-site
  (testing "inert forms and a live control"
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [[:local-reactive-cell] [] [] []]
             (mapv #(classes (str hdr %) "app/p.cljs")
                   ["(def a (r/atom 0))\n"
                    "(def a '(r/atom 0))\n"
                    "(def a 1)\n#_(r/atom 0)\n"
                    "(comment (r/atom 0))\n"])))))

  (testing "resume after inert subtrees"
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [:with-let]
             (classes (str hdr "(defn f []\n  (comment (r/atom 0))\n"
                           "  (r/with-let [_ 1] [:div]))\n")
                      "app/p.cljs"))
          "inert form NESTED inside a live one")
      (is (= [:local-reactive-cell]
             (classes (str hdr "(def z (r/atom 3))\n(comment (r/atom 0))\n") "app/p.cljs"))
          "the inert form ends the file"))))

(def ^:private anon-hdr "(ns app.t\n  (:require [reagent.core :as r]))\n")

(defn- anon-classes [body] (classes (str anon-hdr body) "app/t.cljs"))

(deftest an-anonymous-fn-body-is-a-call-site
  (testing "anonymous-function heads count once"
    (is (= [:render-control] (anon-classes "(def f #(r/flush))\n")))
    (is (= [:render-control] (anon-classes "(def f #(do (r/flush)))\n"))
        "a list inside the literal is counted at the list, and the literal's
         own head `do` is not rostered, so once"))

  (testing "syntax quotes remain live"
    (is (= [:render-control] (anon-classes "(defmacro m [] `#(r/flush))\n"))))

  (testing "mixed calls retain order and coordinates"
    (is (= [[3 12 "(r/flush)"] [4 33 "#(r/flush)"] [5 12 "(r/flush)"]]
           (mapv (juxt :line :col :form)
                 (:entries (scan (str anon-hdr
                                      "(defn a [] (r/flush))\n"
                                      "(defn b [] (react-dom/flushSync #(r/flush)))\n"
                                      "(defn c [] (r/flush))\n")
                                 "app/t.cljs")))))))

(deftest a-symbol-passed-as-a-value-is-not-a-call
  (testing "only a call head is a call"
    (is (= [[] []] (mapv anon-classes ["(def f (run-later r/flush))\n"
                                       "(def f #(run-later r/flush))\n"])))))

(deftest refer-all-binds-the-whole-roster
  (testing "refer all binds Reagent names"
    (is (= [:local-reactive-cell :with-let]
           (classes (str "(ns app.p\n"
                         "  (:require [reagent.core :refer :all]))\n"
                         "\n"
                         "(def a (atom 0))\n"
                         "(defn f [] (with-let [_ 1] [:div]))\n")
                    "app/p.clj"))))

  (testing "refer all stays scoped to its namespace"
    (is (= [] (classes (str "(ns app.p\n"
                            "  (:require [clojure.string :refer :all]))\n"
                            "\n"
                            "(def a (atom 0))\n")
                       "app/p.clj")))))

(deftest a-rename-binds-the-new-spelling-and-releases-the-old
  (testing "renames replace the local spelling"
    (is (= [[:local-reactive-cell 4 {:api "atom"}]]
           (mapv (juxt :class :line :detail)
                 (:entries (scan (str "(ns app.p\n"
                                      "  (:require [reagent.core :refer [atom] :rename {atom ratom}]))\n"
                                      "\n"
                                      "(def a (ratom 0))\n"
                                      "(def b (atom 0))\n")
                                 "app/p.clj"))))))

  (testing "refer all and rename compose"
    (is (= [4] (mapv :line (:entries (scan (str "(ns app.p\n"
                                                 "  (:require [reagent.core :refer :all :rename {atom ratom}]))\n"
                                                 "\n"
                                                 "(def a (ratom 0))\n"
                                                 "(def b (atom 0))\n")
                                            "app/p.clj"))))))

  (testing "rename without refer binds nothing"
    (is (= [] (classes (str "(ns app.p\n"
                            "  (:require [reagent.core :as r :rename {atom ratom}]))\n"
                            "\n"
                            "(def a (ratom 0))\n")
                       "app/p.clj")))))

(deftest the-summary-names-every-bucket-including-the-empty-ones
  (let [built (rf.migration.fresco.census/build [(scan form-2 "app/counter.cljs")
                                                 (scan vendored-require "app/panel.cljs")
                                                 (scan "(ns a)\n(defn f [] [:div])\n" "app/plain.cljs")])]
    (is (= {:by-verdict {:mechanical 0 :human-decision 0 :runtime-blocker 3}
            :files-scanned 3 :files-with-reagent 2 :files-unresolved 1 :entries 3}
           (select-keys (:summary built)
                        [:by-verdict :files-scanned :files-with-reagent :files-unresolved :entries]))
        "every bucket is present, a zero included: an absent key is a count nobody can tell from a bug")
    (testing "sort by file, line and column"
      (is (= (:entries built) (vec (sort-by (juxt :file :line :col) (:entries built))))))))

(def ^:private rf2-native-boot
  (str "(ns app.core\n"
       "  (:require [re-frame.core :as rf]\n"
       "            [re-frame.adapter.reagent :as reagent-adapter]))\n"
       "\n"
       "(defonce app-root (reagent-adapter/client-root))\n"
       "\n"
       "(defn run []\n"
       "  (rf/init! reagent-adapter/adapter)\n"
       "  (reagent-adapter/render! app-root [root-view] el))\n"))

(def ^:private rf2-native-view
  (str "(ns app.articles\n"
       "  (:require [re-frame.core :as rf]))\n"
       "\n"
       "(rf/reg-view ::card [id]\n"
       "  [:div {:on-click #(rf/dispatch [:open id])} @(rf/subscribe [:title id])])\n"))

(deftest the-substrate-adapter-is-a-population
  (testing "substrate calls form a separate population"
    (let [{:keys [entries reagent? substrate? recognised?]} (scan rf2-native-boot "app/core.cljs")]
      (is (= [false true true [[:root-mount 5 {:api "client-root"}] [:root-mount 9 {:api "render!"}]]]
             [reagent? substrate? recognised? (mapv (juxt :class :line :detail) entries)]))
      (testing "root-mount recovery guidance"
        (is (str/includes? (:note (first entries)) "Fresco mounts its own root"))))))

(deftest a-shared-roster-name-resolves-by-require
  (testing "shared names resolve through separate contexts"
    (doseq [[require-spec flags] [["[reagent2.dom.client :as x]"   [true false]]
                                  ["[re-frame.adapter.uix :as x]" [false true]]]]
      (let [{:keys [entries reagent? substrate?]}
            (scan (str "(ns app.t\n  (:require " require-spec "))\n\n(defn settle [] (x/flush-views!))\n")
                  "app/t.cljs")]
        (is (= [flags [:substrate-test-seam]] [[reagent? substrate?] (mapv :class entries)])
            require-spec)))))

(deftest the-prefix-rule-is-anchored-at-the-start
  (testing "anchor the substrate namespace prefix"
    (let [{:keys [entries substrate? recognised?]}
          (scan (str "(ns app.p\n"
                     "  (:require [my.vendored.re-frame.adapter.reagent :as ad]))\n"
                     "\n"
                     "(defn f [] (ad/render! nil nil nil))\n")
                "app/p.cljs")]
      (is (= [[] false false] [entries substrate? recognised?])))))

(def ^:private recognised-namespace-calls
  '{reagent.core        "(x/atom 0)"
    reagent.dom         "(x/render [:div] el)"
    reagent.dom.client  "(x/unmount root)"
    reagent.ratom       "(x/reactive?)"
    reagent.dom.server  "(x/render-to-string [:div])"
    reagent2.core       "(x/as-element h)"
    reagent2.ratom      "(x/activate! rx)"
    reagent2.dom.client "(x/flush-views!)"
    reagent2.dom.server "(x/render-to-static-markup [:div])"})

(def ^:private substrate-namespace-calls
  '{re-frame.adapter.reagent      "(x/render! root view opts)"
    re-frame.adapter.reagent-slim "(x/client-root el)"
    re-frame.adapter.uix          "(x/use-sub [:q])"
    re-frame.adapter.test-react   "(x/mount! [:div])"})

(deftest every-recognised-namespace-has-a-rostered-call
  (testing "every recognised namespace needs a sample"
    (is (= (set (keys recognised-namespace-calls)) rf.migration.fresco.rewrite/reagent-namespaces)
        "a recognised namespace with no known-public-call sample, or a sample for
         a namespace the tool does not recognise"))

  (testing "recognised namespaces must produce findings"
    (is (= []
           (for [[ns* call] (merge recognised-namespace-calls substrate-namespace-calls)
                 :let  [s (summary (scan (str "(ns app.probe\n  (:require [" ns* " :as x]))\n\n(defn f [] " call ")\n")
                                         "app/probe.cljs"))]
                 :when (not (and (= :full (:recognition s)) (pos? (:entries s))))]
             (str ns* " " call))))))

(deftest a-full-zero-still-says-the-roster-bounds-it
  (testing "a full zero is still bounded by the roster"
    (let [s (summary (scan (str "(ns app.v\n"
                                "  (:require [reagent.core :as r]))\n"
                                "\n"
                                "(defn v [] [:div \"no rostered call in this file\"])\n")
                           "app/v.cljs"))]
      (is (= [:full 0] [(:recognition s) (:entries s)]))
      (is (every? #(str/includes? (:caveat s) %)
                  ["NOT ABOUT THE ROSTER" "fixed roster" "not that these files hold no migration work"])
          (:caveat s))
      (testing "mark zeros in the CLI summary"
        (is (str/includes? (rf.migration.fresco.census/describe {:summary s}) "ZERO ENTRIES"))))))

(deftest recognising-nothing-is-not-finding-nothing
  (testing "unrecognised files are not a clean bill of health"
    (let [s (summary (scan rf2-native-view "app/articles.cljs")
                     (scan rf2-native-view "app/profile.cljs"))]
      (is (= {:entries 0 :recognition :none :files-scanned 2 :files-recognised 0 :files-unrecognised 2}
             (select-keys s [:entries :recognition :files-scanned :files-recognised :files-unrecognised])))
      (is (str/includes? (:caveat s) "NOT A CLEAN BILL OF HEALTH"))
      (testing "carry recognition into the CLI summary"
        (is (str/includes? (rf.migration.fresco.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "the recognised control reports full"
    (let [s (summary (scan form-2 "app/counter.cljs"))]
      (is (= {:recognition :full :entries 1 :files-unrecognised 0}
             (select-keys s [:recognition :entries :files-unrecognised])))
      (is (str/includes? (:caveat s) "a population throughout"))
      (is (not (str/includes? (rf.migration.fresco.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "a mixed corpus reports partial"
    (let [s (summary (scan rf2-native-boot "app/core.cljs")
                     (scan rf2-native-view "app/articles.cljs"))]
      (is (= {:recognition :partial :entries 2 :files-with-substrate 1 :files-with-reagent 0}
             (select-keys s [:recognition :entries :files-with-substrate :files-with-reagent])))
      (is (str/includes? (:caveat s) "full of migration work"))))

  (testing "empty paths have their own verdict"
    (let [s (summary)]
      (is (= :no-files (:recognition s)))
      (is (str/includes? (:caveat s) "NO SOURCE FILE WAS SCANNED")))))

(deftest the-file-counts-partition-the-corpus
  (testing "file counts partition the corpus"
    (let [built (rf.migration.fresco.census/build [(scan form-2 "app/counter.cljs")
                                                   (scan vendored-require "app/panel.cljs")
                                                   (scan rf2-native-boot "app/core.cljs")
                                                   (scan rf2-native-view "app/articles.cljs")])
          s     (:summary built)]
      (is (= {:files-scanned 4 :files-recognised 3 :files-unrecognised 1
              :files-with-reagent 2 :files-with-substrate 1 :files-clean 0}
             (select-keys s [:files-scanned :files-recognised :files-unrecognised
                             :files-with-reagent :files-with-substrate :files-clean]))))))

(def ^:private kept-island
  (str "(ns app.summary\n"
       "  (:require [re-frame.fresco :as h]\n"
       "            [reagent.core :as r]\n"
       "            [re-com.core :as rc]\n"
       "            [app.search :as search]\n"
       "            [app.widget :as widget]))\n"
       "\n"
       "(h/defview summary-content-view [{:keys [opts grid-props]}]\n"
       "  (r/as-element\n"
       "    [rc/v-box :gap \"12px\"\n"
       "     :children [[search/describe-query opts]\n"
       "                [widget/nested-grid grid-props]]]))\n"))

(deftest as-element-is-triage-counted-at-its-call
  (testing "count a kept island as triage"
    (let [es (:entries (scan kept-island "app/summary.cljs"))]
      (is (= [{:class :as-element :verdict :human-decision :line 9 :col 3 :detail {:api "as-element"}}]
             (mapv #(select-keys % [:class :verdict :line :col :detail]) es)))
      (testing "explain both island outcomes"
        (is (every? #(str/includes? (:note (first es)) %)
                    ["stays as it is" "Fresco's lowerer" "a dynamic or unresolved target stays unsettled"
                     "UNSAFE bridge" "`h/as-element`" "`h/as-component`" "`:render` callback contract"])
            (:note (first es))))))

  (testing "summarise the triage bucket"
    (is (= {:entries 1 :by-class {:as-element 1}
            :by-verdict {:human-decision 1 :mechanical 0 :runtime-blocker 0}}
           (select-keys (summary (scan kept-island "app/summary.cljs")) [:entries :by-class :by-verdict]))))

  (testing "unresolved aliases remain blockers"
    (let [es (:entries (scan (str "(ns app.panel\n"
                                  "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core :as r]))\n"
                                  "(defn el [] (r/as-element [:div]))\n")
                             "app/panel.cljs"))]
      (is (= [[:unresolved-reagent-require :runtime-blocker nil]
              [:unresolved-alias :runtime-blocker {:api "as-element" :symbol "r/as-element"}]]
             (mapv (juxt :class :verdict :detail) es))))))

(defn- table-rows
  [path marker]
  (let [row? #(str/starts-with? (str/triml %) "|")]
    (->> (str/split-lines (slurp (io/file ".." ".." ".." path)))
         (drop-while #(not (str/includes? % marker)))
         (drop-while (complement row?))
         (take-while row?)
         (drop 2)
         (mapv #(mapv str/trim (rest (str/split (str/trim %) #"\|")))))))

(defn- cell-classes [cell] (set (map (comp keyword second) (re-seq #"`:([a-z][a-z0-9-]*)`" cell))))

(defn- class->verdicts [pairs] (reduce (fn [m [c v]] (update m c (fnil conj #{}) v)) {} pairs))

(deftest the-prose-tables-name-the-roster-and-its-verdicts
  (let [roster (class->verdicts (map (juxt :class :verdict)
                                     (concat (vals rf.migration.fresco.census/surface)
                                             (vals rf.migration.fresco.census/substrate-surface))))]
    (testing "guide verdicts match the roster"
      (is (= roster
             (class->verdicts
              (for [[label cell] (table-rows "docs/core/fresco/20-migration-from-reagent.md" "### The census")
                    c            (cell-classes cell)]
                [c ({"Human decision" :human-decision "Runtime blocker" :runtime-blocker
                     "Mechanical" :mechanical} label)])))))
    (testing "skill routes match the roster"
      (is (= (set (keys roster))
             (into #{} (mapcat (comp cell-classes first))
                   (table-rows "skills/reagent-fresco-migration/references/procedure.md" "Census `:class`")))))))
