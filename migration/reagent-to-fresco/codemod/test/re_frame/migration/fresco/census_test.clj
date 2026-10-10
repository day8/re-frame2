(ns re-frame.migration.fresco.census-test
  "The census, gated on the two ways a census fails.

  A census fails by **answering nothing** — an empty population that
  cannot redden, so every run is green and no run means anything — and it
  fails by **answering too much** — a legal codebase reported as a wall of
  blockers, which trains its reader to stop looking. Both failures are
  confident and neither announces itself, so both get a control here.

  There is a **third** mode: answering nothing over a corpus the census
  never RECOGNISED. That failure wears the first one's face exactly — every
  count zero, nothing red — and no roster is wide enough to rule it out, so
  it is gated from the other side, on the tool's inability to report the
  zero without saying which of the two it is.

  `unresolved` means a require the reader genuinely cannot bind. A
  `#?(:cljs [reagent.core :as r])` require resolves structurally, so its
  call sites get their real classes; the vendored copy below is the honest
  unbindable example, and a tool that started GUESSING that copy was
  `reagent.core` would be a worse tool than the blind one."
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.census :as rf.migration.fresco.census]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]))

(defn- scan [src file] (rf.migration.fresco.census/scan src file))

(defn- classes [src file] (mapv :class (:entries (scan src file))))

(defn- summary [& scans] (:summary (rf.migration.fresco.census/build (vec scans))))

;; ---------------------------------------------------------------------------
;; It can answer NON-EMPTY, on exactly the source the fixer answers empty on
;; ---------------------------------------------------------------------------

(def ^:private form-2
  "The most common thing in a Reagent codebase, and the fixer's blind
  spot: local reactive state, no crossing anywhere."
  (str "(ns app.counter\n"
       "  (:require [reagent.core :as r]))\n"
       "\n"
       "(defn counter []\n"
       "  (let [n (r/atom 0)]\n"
       "    (fn [] [:div {:on-click #(swap! n inc)} @n])))\n"))

(deftest the-census-answers-non-empty
  (let [{:keys [entries reagent? unresolved?]} (scan form-2 "app/counter.cljs")]
    (testing "with the coordinate a person greps for, and the api that named it"
      (is (= [true false [{:class :local-reactive-cell :verdict :runtime-blocker
                           :line 5 :col 11 :detail {:api "atom"}}]]
             [reagent? unresolved? (mapv #(select-keys % [:class :verdict :line :col :detail]) entries)])))
    (testing "and a sentence to act on"
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

;; ---------------------------------------------------------------------------
;; What it cannot resolve is REPORTED, never skipped
;; ---------------------------------------------------------------------------

(def ^:private vendored-require
  "A namespace that SPELLS a Reagent name without being Reagent's.

  re-frame-10x ships its dependencies inlined under a private prefix, so
  `names-reagent?` — which reads the `ns` form's text — says yes while
  `ns-context` correctly binds nothing, because only the exact roster
  binds. This is the population `:unresolved-reagent-require` exists for,
  and the tool must keep REPORTING it rather than guessing that `r` is
  `reagent.core`."
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
    (testing "the note says the whole tool family is blind here, not just the census"
      (is (str/includes? (:note (first entries)) "PARTIALLY BLIND")))))

;; A require behind a reader conditional resolves through the `ns-context` the
;; fixer shares, and the golden corpus's three reader-conditional cases pin the
;; per-spec shapes. The one shape only this reader meets is a conditional
;; wrapped round the whole `(:require …)` clause.

(deftest a-reader-conditional-require-binds
  (testing "a conditional wrapping the whole `(:require …)` clause, not one spec"
    (is (= [:local-reactive-cell]
           (classes (str "(ns app.ssr\n"
                         "  #?(:cljs (:require [reagent.core :as r])))\n"
                         "\n"
                         "(defn v [] (r/atom 1))\n")
                    "app/ssr.cljc")))))

(deftest an-ns-form-under-metadata-is-still-the-ns-form
  (testing "`^:cljstyle/ignore (ns …)` is ordinary. Reading it as `nil` binds
            NOTHING for the whole file — every Reagent API unnamed — and
            nothing looks wrong, because `:>` needs no alias and the fixer's
            report stays non-empty."
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
  (testing "and the ns is reported ONCE when it cannot be bound, though
            `ns-form?` matches at the meta node and at the list inside it"
    (is (= [:unresolved-reagent-require]
           (classes (str "^:cljstyle/ignore\n"
                         "(ns app.graph\n"
                         "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core\n"
                         "             :as r]))\n")
                    "app/graph.cljc"))))
  (testing "a conditional require under that same metadata resolves — the
            metadata path and the conditional path compose"
    (is (= [:local-reactive-cell]
           (classes (str "^:cljstyle/ignore\n"
                         "(ns app.graph\n"
                         "  (:require #?(:cljs [reagent.core :as r])))\n"
                         "\n"
                         "(def graph-ref-map (r/atom {}))\n")
                    "app/graph.cljc")))))

;; ---------------------------------------------------------------------------
;; A legal population comes back CLEAN
;; ---------------------------------------------------------------------------

(deftest prose-about-reagent-is-not-a-finding
  (testing "the `ns` docstring is not a require: a file that discusses
            `reagent.ratom/run!` in prose is clean"
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
  (testing "half the roster shares a name with `clojure.core`. Only a bound alias,
            or a `:refer` the `ns` form actually wrote, makes one Reagent's."
    (is (= [] (classes (str "(ns app.util\n"
                            "  (:require [clojure.string :as str]))\n"
                            "\n"
                            "(def cache (atom {}))\n"
                            "(defn go [f xs] (run! f xs))\n"
                            "(defn f [g] (partial g 1))\n"
                            "(defn h [m] (flush))\n")
                       "app/util.cljs"))))
  (testing "but a `:refer` from Reagent does"
    (is (= [:local-reactive-cell]
           (classes (str "(ns app.util\n"
                         "  (:require [reagent.core :refer [atom]]))\n"
                         "\n"
                         "(def cache (atom {}))\n")
                    "app/util.cljs")))))

;; ---------------------------------------------------------------------------
;; A call SITE is source that runs
;; ---------------------------------------------------------------------------

(deftest inert-source-is-not-a-call-site
  (testing "a discard, a quote and a `(comment …)` body each parse into the
            same nodes a live call does. None is a call site; the live call is
            the control they are measured against."
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [[:local-reactive-cell] [] [] []]
             (mapv #(classes (str hdr %) "app/p.cljs")
                   ["(def a (r/atom 0))\n"
                    "(def a '(r/atom 0))\n"
                    "(def a 1)\n#_(r/atom 0)\n"
                    "(comment (r/atom 0))\n"])))))

  (testing "pruning a subtree must not prune what FOLLOWS it — the walk resumes
            at the enclosing form's next sibling, and terminates when the inert
            form ends the file"
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [:with-let]
             (classes (str hdr "(defn f []\n  (comment (r/atom 0))\n"
                           "  (r/with-let [_ 1] [:div]))\n")
                      "app/p.cljs"))
          "inert form NESTED inside a live one")
      (is (= [:local-reactive-cell]
             (classes (str hdr "(def z (r/atom 3))\n(comment (r/atom 0))\n") "app/p.cljs"))
          "the inert form ends the file"))))

;; ---------------------------------------------------------------------------
;; An anonymous-fn literal's body is a call site
;; ---------------------------------------------------------------------------

(def ^:private anon-hdr "(ns app.t\n  (:require [reagent.core :as r]))\n")

(defn- anon-classes [body] (classes (str anon-hdr body) "app/t.cljs"))

(deftest an-anonymous-fn-body-is-a-call-site
  (testing "`#(r/flush)` reads as `(fn* [] (r/flush))`: the literal's own
            children ARE its body's call, head first, so the call sits at the
            literal, and is counted exactly once"
    (is (= [:render-control] (anon-classes "(def f #(r/flush))\n")))
    (is (= [:render-control] (anon-classes "(def f #(do (r/flush)))\n"))
        "a list inside the literal is counted at the list, and the literal's
         own head `do` is not rostered, so once"))

  (testing "a syntax-quoted literal is a template that emits the call at every
            expansion, so it counts exactly as a syntax-quoted list does"
    (is (= [:render-control] (anon-classes "(defmacro m [] `#(r/flush))\n"))))

  (testing "a mixed file keeps its ordinary calls, in order, at their own
            positions, and reports the literal at its `#` with the literal as
            the excerpt"
    (is (= [[3 12 "(r/flush)"] [4 33 "#(r/flush)"] [5 12 "(r/flush)"]]
           (mapv (juxt :line :col :form)
                 (:entries (scan (str anon-hdr
                                      "(defn a [] (r/flush))\n"
                                      "(defn b [] (react-dom/flushSync #(r/flush)))\n"
                                      "(defn c [] (r/flush))\n")
                                 "app/t.cljs")))))))

(deftest a-symbol-passed-as-a-value-is-not-a-call
  (testing "only a call's HEAD is a call. `r/flush` handed to another function,
            in a list or inside a literal, is a value"
    (is (= [[] []] (mapv anon-classes ["(def f (run-later r/flush))\n"
                                       "(def f #(run-later r/flush))\n"])))))

;; ---------------------------------------------------------------------------
;; Legal libspec options that bound nothing
;; ---------------------------------------------------------------------------

(deftest refer-all-binds-the-whole-roster
  (testing "`:refer :all` is legal: every bare Reagent call in such a file is
            Reagent's"
    (is (= [:local-reactive-cell :with-let]
           (classes (str "(ns app.p\n"
                         "  (:require [reagent.core :refer :all]))\n"
                         "\n"
                         "(def a (atom 0))\n"
                         "(defn f [] (with-let [_ 1] [:div]))\n")
                    "app/p.clj"))))

  (testing "and it binds only the namespace it is written on"
    (is (= [] (classes (str "(ns app.p\n"
                            "  (:require [clojure.string :refer :all]))\n"
                            "\n"
                            "(def a (atom 0))\n")
                       "app/p.clj")))))

(deftest a-rename-binds-the-new-spelling-and-releases-the-old
  (testing "`:refer [atom] :rename {atom ratom}` binds `ratom` to
            `reagent.core/atom` and leaves `atom` as `clojure.core`'s. The
            call is reported under the ROSTER name, which is the only one with
            a class and a recovery sentence."
    (is (= [[:local-reactive-cell 4 {:api "atom"}]]
           (mapv (juxt :class :line :detail)
                 (:entries (scan (str "(ns app.p\n"
                                      "  (:require [reagent.core :refer [atom] :rename {atom ratom}]))\n"
                                      "\n"
                                      "(def a (ratom 0))\n"
                                      "(def b (atom 0))\n")
                                 "app/p.clj"))))))

  (testing "the two compose: `:refer :all` binds everything the rename did not
            take away"
    (is (= [4] (mapv :line (:entries (scan (str "(ns app.p\n"
                                                 "  (:require [reagent.core :refer :all :rename {atom ratom}]))\n"
                                                 "\n"
                                                 "(def a (ratom 0))\n"
                                                 "(def b (atom 0))\n")
                                            "app/p.clj"))))))

  (testing "a `:rename` with no `:refer` binds nothing, which is the effect
            Clojure gives it"
    (is (= [] (classes (str "(ns app.p\n"
                            "  (:require [reagent.core :as r :rename {atom ratom}]))\n"
                            "\n"
                            "(def a (ratom 0))\n")
                       "app/p.clj")))))

;; ---------------------------------------------------------------------------
;; A summary that cannot hide an empty bucket
;; ---------------------------------------------------------------------------

(deftest the-summary-names-every-bucket-including-the-empty-ones
  (let [built (rf.migration.fresco.census/build [(scan form-2 "app/counter.cljs")
                                                 (scan vendored-require "app/panel.cljs")
                                                 (scan "(ns a)\n(defn f [] [:div])\n" "app/plain.cljs")])]
    (is (= {:by-verdict {:mechanical 0 :human-decision 0 :runtime-blocker 3}
            :files-scanned 3 :files-with-reagent 2 :files-unresolved 1 :entries 3}
           (select-keys (:summary built)
                        [:by-verdict :files-scanned :files-with-reagent :files-unresolved :entries]))
        "every bucket is present, a zero included: an absent key is a count nobody can tell from a bug")
    (testing "ordering is file, then line, then column"
      (is (= (:entries built) (vec (sort-by (juxt :file :line :col) (:entries built))))))))

;; ---------------------------------------------------------------------------
;; The SECOND roster: re-frame2's own substrate adapters
;; ---------------------------------------------------------------------------

(def ^:private rf2-native-boot
  "A re-frame2 application's boot file, on the Reagent adapter. It renders
  through Reagent and never names it: the substrate arrives as
  `re-frame.adapter.reagent`, and there is no `reagent.core` name in the
  whole application."
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
  "A re-frame2 view file. Every migration shape in it — the registration,
  the read, the dispatch closure — is re-frame2's own, so the census has
  no population here and must not pretend otherwise."
  (str "(ns app.articles\n"
       "  (:require [re-frame.core :as rf]))\n"
       "\n"
       "(rf/reg-view ::card [id]\n"
       "  [:div {:on-click #(rf/dispatch [:open id])} @(rf/subscribe [:title id])])\n"))

(deftest the-substrate-adapter-is-a-population
  (testing "`re-frame.adapter.reagent` is not `reagent.core`, so a census that
            classified by the Reagent roster alone had nothing to count in the
            file that actually mounts the application. Exactly the two call
            heads count: `reagent-adapter/adapter` is a value in argument
            position, which this walk does not read."
    (let [{:keys [entries reagent? substrate? recognised?]} (scan rf2-native-boot "app/core.cljs")]
      (is (= [false true true [[:root-mount 5 {:api "client-root"}] [:root-mount 9 {:api "render!"}]]]
             [reagent? substrate? recognised? (mapv (juxt :class :line :detail) entries)]))
      (testing "and each carries the recovery sentence for boot ceremony"
        (is (str/includes? (:note (first entries)) "Fresco mounts its own root"))))))

(deftest a-shared-roster-name-resolves-by-require
  (testing "`bound-call?` consults a SEPARATE `ns` context per roster, so the
            require decides the arm and the name order decides nothing. An
            UNINTENDED overlap still reds here — only the deliberate one is
            allowed through."
    (is (= '#{flush-views!}
           (clojure.set/intersection (set (keys rf.migration.fresco.census/surface))
                                     (set (keys rf.migration.fresco.census/substrate-surface))))))

  (testing "the shared name is the same seam at two addresses, so one class answers
            for both — and the flags say which: through `reagent2.dom.client` the
            file NAMES Reagent (the flag the migration skill reads to decide
            whether a Reagent coordinate may be dropped); through an adapter it
            is the substrate's"
    (doseq [[require-spec flags] [["[reagent2.dom.client :as x]"   [true false]]
                                  ["[re-frame.adapter.uix :as x]" [false true]]]]
      (let [{:keys [entries reagent? substrate?]}
            (scan (str "(ns app.t\n  (:require " require-spec "))\n\n(defn settle [] (x/flush-views!))\n")
                  "app/t.cljs")]
        (is (= [flags [:substrate-test-seam]] [[reagent? substrate?] (mapv :class entries)])
            require-spec)))))

(deftest the-prefix-rule-is-anchored-at-the-start
  (testing "the substrate roster binds by PREFIX so the adapter set can grow,
            and that is only safe anchored: a namespace that CONTAINS
            `re-frame.adapter.` is not one"
    (let [{:keys [entries substrate? recognised?]}
          (scan (str "(ns app.p\n"
                     "  (:require [my.vendored.re-frame.adapter.reagent :as ad]))\n"
                     "\n"
                     "(defn f [] (ad/render! nil nil nil))\n")
                "app/p.cljs")]
      (is (= [[] false false] [entries substrate? recognised?])))))

;; ---------------------------------------------------------------------------
;; RECOGNISED BUT UNCOUNTABLE — the confident zero
;; ---------------------------------------------------------------------------

(def ^:private recognised-namespace-calls
  "One known PUBLIC call per namespace `rewrite/reagent-namespaces`
  recognises, written as it would be called through the alias `x`.

  **This table is the ratchet, and the assertion below that it is
  COMPLETE is the half that matters.** Recognition is decided per FILE and
  entries are found per NAME, so widening the namespace set without
  widening `rf.migration.fresco.census/surface` converts an honest *I did
  not recognise this file* into `:recognition :full` with `entries 0` — a
  confident zero, and a strictly worse answer than the one it replaced.

  Every call here is a real public Var of the namespace it sits under, read
  off `implementation/adapters/reagent-slim/src/` for the `reagent2` half
  and Reagent's own published API for the stock half. A sample that named
  something no namespace defines would pass this test while proving
  nothing about the tool."
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
  "The same probe for the adapters shipping today. There is no
  completeness assertion to pair with it: `substrate-ns-prefix` binds an
  OPEN set on purpose, so no list here could be exhaustive, and the
  residual — an adapter recognised before this roster has rows for it — is
  what `caveat`'s weakened `:full` sentence admits to."
  '{re-frame.adapter.reagent      "(x/render! root view opts)"
    re-frame.adapter.reagent-slim "(x/client-root el)"
    re-frame.adapter.uix          "(x/use-sub [:q])"
    re-frame.adapter.test-react   "(x/mount! [:div])"})

(deftest every-recognised-namespace-has-a-rostered-call
  (testing "the ratchet: a namespace cannot join `reagent-namespaces` without a
            sample here, so the next widening cannot reach main half-done"
    (is (= (set (keys recognised-namespace-calls)) rf.migration.fresco.rewrite/reagent-namespaces)
        "a recognised namespace with no known-public-call sample, or a sample for
         a namespace the tool does not recognise"))

  (testing "each probe file is recognised AND counted: a recognised namespace whose
            public call scores `:full` with zero entries is the CONFIDENT ZERO"
    (is (= []
           (for [[ns* call] (merge recognised-namespace-calls substrate-namespace-calls)
                 :let  [s (summary (scan (str "(ns app.probe\n  (:require [" ns* " :as x]))\n\n(defn f [] " call ")\n")
                                         "app/probe.cljs"))]
                 :when (not (and (= :full (:recognition s)) (pos? (:entries s))))]
             (str ns* " " call))))))

(deftest a-full-zero-still-says-the-roster-bounds-it
  (testing "recognition can be `:full` and the count still zero — a recognised file
            whose only call is a shape no roster names. That is legal and honest;
            what it must not do is present the zero as a measurement of the corpus.
            This is the residual no roster closes, so the WORDING is the gate."
    (let [s (summary (scan (str "(ns app.v\n"
                                "  (:require [reagent.core :as r]))\n"
                                "\n"
                                "(defn v [] [:div \"no rostered call in this file\"])\n")
                           "app/v.cljs"))]
      (is (= [:full 0] [(:recognition s) (:entries s)]))
      (is (every? #(str/includes? (:caveat s) %)
                  ["NOT ABOUT THE ROSTER" "fixed roster" "not that these files hold no migration work"])
          (:caveat s))
      (testing "and the CLI tail — the last line of the run, read by whoever reads
                nothing else — marks the zero rather than printing it bare"
        (is (str/includes? (rf.migration.fresco.census/describe {:summary s}) "ZERO ENTRIES"))))))

;; ---------------------------------------------------------------------------
;; A zero the tool CANNOT report confidently
;; ---------------------------------------------------------------------------

(deftest recognising-nothing-is-not-finding-nothing
  (testing "a corpus of re-frame2 view files gives the census no population
            anywhere. Its zero must not read as a clean bill of health."
    (let [s (summary (scan rf2-native-view "app/articles.cljs")
                     (scan rf2-native-view "app/profile.cljs"))]
      (is (= {:entries 0 :recognition :none :files-scanned 2 :files-recognised 0 :files-unrecognised 2}
             (select-keys s [:entries :recognition :files-scanned :files-recognised :files-unrecognised])))
      (is (str/includes? (:caveat s) "NOT A CLEAN BILL OF HEALTH"))
      (testing "and the CLI tail says it too, because whoever reads only the last
                line of the run is exactly who this misleads"
        (is (str/includes? (rf.migration.fresco.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "THE CONTROL: the same machinery over a corpus the census DOES have a
            population in reports `:full`, and its caveat says so"
    (let [s (summary (scan form-2 "app/counter.cljs"))]
      (is (= {:recognition :full :entries 1 :files-unrecognised 0}
             (select-keys s [:recognition :entries :files-unrecognised])))
      (is (str/includes? (:caveat s) "a population throughout"))
      (is (not (str/includes? (rf.migration.fresco.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "the mixed corpus — one adapter file, one view file — is `:partial`,
            and its caveat says the view files are absent from this census AND
            full of migration work. `:files-with-reagent` keeps its meaning: no
            Reagent name is in this app"
    (let [s (summary (scan rf2-native-boot "app/core.cljs")
                     (scan rf2-native-view "app/articles.cljs"))]
      (is (= {:recognition :partial :entries 2 :files-with-substrate 1 :files-with-reagent 0}
             (select-keys s [:recognition :entries :files-with-substrate :files-with-reagent])))
      (is (str/includes? (:caveat s) "full of migration work"))))

  (testing "an empty path set is its own verdict rather than a zero"
    (let [s (summary)]
      (is (= :no-files (:recognition s)))
      (is (str/includes? (:caveat s) "NO SOURCE FILE WAS SCANNED")))))

(deftest the-file-counts-partition-the-corpus
  (testing "every scanned file lands in exactly one bucket, so the buckets close"
    (let [built (rf.migration.fresco.census/build [(scan form-2 "app/counter.cljs")
                                                   (scan vendored-require "app/panel.cljs")
                                                   (scan rf2-native-boot "app/core.cljs")
                                                   (scan rf2-native-view "app/articles.cljs")])
          s     (:summary built)]
      (is (= {:files-scanned 4 :files-recognised 3 :files-unrecognised 1
              :files-with-reagent 2 :files-with-substrate 1 :files-clean 0}
             (select-keys s [:files-scanned :files-recognised :files-unrecognised
                             :files-with-reagent :files-with-substrate :files-clean])))
      (is (= (:files-recognised s)
             (+ (:files-clean s) (count (into #{} (map :file) (:entries built)))))))))

;; ---------------------------------------------------------------------------
;; `r/as-element` is TRIAGE: whether it is right turns on whose heads it lowers
;; ---------------------------------------------------------------------------

(def ^:private kept-island
  "A converted view whose body is a Reagent island its author keeps. Every
  head `r/as-element` lowers here is a Reagent component, so Reagent is the
  right lowerer and the call stays."
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
  (testing "the island stays COUNTED, at the call's own coordinate, and is triage,
            not a confirmed runtime failure"
    (let [es (:entries (scan kept-island "app/summary.cljs"))]
      (is (= [{:class :as-element :verdict :human-decision :line 9 :col 3 :detail {:api "as-element"}}]
             (mapv #(select-keys % [:class :verdict :line :col :detail]) es)))
      (testing "and its recovery sentence names both cases the census cannot tell
                apart: a kept island stays, and a Fresco view lowered by Reagent is
                the unsafe bridge, with both of Fresco's supported doors"
        (is (every? #(str/includes? (:note (first es)) %)
                    ["stays as it is" "Fresco's lowerer" "a dynamic or unresolved target stays unsettled"
                     "UNSAFE bridge" "`h/as-element`" "`h/as-component`" "`:render` callback contract"])
            (:note (first es))))))

  (testing "the summary carries the call in the triage bucket, under its own class"
    (is (= {:entries 1 :by-class {:as-element 1}
            :by-verdict {:human-decision 1 :mechanical 0 :runtime-blocker 0}}
           (select-keys (summary (scan kept-island "app/summary.cljs")) [:entries :by-class :by-verdict]))))

  (testing "a call whose alias the reader could not bind stays the runtime blocker
            it is: the tool cannot tell whose `as-element` this is, so it does not
            lend it the roster row's triage verdict"
    (let [es (:entries (scan (str "(ns app.panel\n"
                                  "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core :as r]))\n"
                                  "(defn el [] (r/as-element [:div]))\n")
                             "app/panel.cljs"))]
      (is (= [[:unresolved-reagent-require :runtime-blocker nil]
              [:unresolved-alias :runtime-blocker {:api "as-element" :symbol "r/as-element"}]]
             (mapv (juxt :class :verdict :detail) es))))))

;; ---------------------------------------------------------------------------
;; The prose that restates the roster: the guide's verdicts, the skill's routes
;; ---------------------------------------------------------------------------

(defn- table-rows
  "The body rows of the first markdown table after the line containing
  `marker` in `path`, read from the repository root, each a vector of its
  trimmed cells."
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
    (testing "the human guide's verdict table gives every class the code's verdict"
      (is (= roster
             (class->verdicts
              (for [[label cell] (table-rows "docs/core/fresco/20-migration-from-reagent.md" "### The census")
                    c            (cell-classes cell)]
                [c ({"Human decision" :human-decision "Runtime blocker" :runtime-blocker
                     "Mechanical" :mechanical} label)])))))
    (testing "the skill's census route table routes every class, and only those"
      (is (= (set (keys roster))
             (into #{} (mapcat (comp cell-classes first))
                   (table-rows "skills/reagent-fresco-migration/references/procedure.md" "Census `:class`")))))))
