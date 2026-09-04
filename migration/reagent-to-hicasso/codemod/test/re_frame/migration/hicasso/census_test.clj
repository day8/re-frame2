(ns re-frame.migration.hicasso.census-test
  "The census, gated on the two ways a census fails.

  A census fails by **answering nothing** — an empty population that
  cannot redden, so every run is green and no run means anything — and it
  fails by **answering too much** — a legal codebase reported as a wall of
  blockers, which trains its reader to stop looking. Both failures are
  confident and neither announces itself, so both get a control here, and
  the second one is not decoration: it caught two real over-reports in
  this repository's own example corpus while this namespace was being
  written.

  There is a **third** mode, and it is the one a blinded pilot found by
  migrating a real application (rf2-xoal): answering nothing over a corpus
  the census never RECOGNISED. That failure wears the first one's face
  exactly — every count zero, nothing red — and no roster is wide enough
  to rule it out, so it is gated from the other side, on the tool's
  inability to report the zero without saying which of the two it is.

  * `ns` DOCSTRING prose mentioning `reagent.ratom/run!` made five clean
    example files read as five migration blockers. `names-reagent?` now
    reads the `ns` form's CLAUSES.
  * `(atom nil)` — `clojure.core`'s — sat one line above the `rdc/render`
    that is the genuine finding in the SSR examples, and both were
    reported. `:unresolved-alias` now needs a qualified head, because the
    thing that could not be bound is an ALIAS.

  **What `unresolved` MEANS here changed with rf2-m4hm**, and these tests
  are where that is pinned. `#?(:cljs [reagent.core :as r])` used to be
  the archetypal unbindable require; `ns-context` now reads it
  structurally, so it RESOLVES and its call sites get their real classes.
  The class did not become decorative — it moved to the population that is
  genuinely unbindable, of which the vendored copy below is the honest
  example. A tool that started GUESSING that copy was `reagent.core`
  would be a worse tool than the blind one, so both directions are
  asserted."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.census :as rf.migration.hicasso.census]
            [re-frame.migration.hicasso.codemod :as rf.migration.hicasso.codemod]
            [re-frame.migration.hicasso.rewrite :as rf.migration.hicasso.rewrite]))

(defn- classes [src file] (mapv :class (:entries (rf.migration.hicasso.census/scan src file))))

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

(deftest the-fixer-is-silent-here
  (testing "the crossing population is genuinely empty in this file — the census is
            not being compared against a fixer that simply failed"
    (let [r (rf.migration.hicasso.codemod/scan-string form-2 "app/counter.cljs")]
      (is (= [] (:entries r)))
      (is (= 0 (:sites r))))))

(deftest the-census-answers-non-empty
  (let [{:keys [entries reagent? unresolved?]} (rf.migration.hicasso.census/scan form-2 "app/counter.cljs")]
    (is (true? reagent?))
    (is (false? unresolved?))
    (is (= [:local-reactive-cell] (mapv :class entries)))
    (is (= [:runtime-blocker] (mapv :verdict entries)))
    (testing "with the coordinate a person greps for, and the api that named it"
      (is (= {:line 5 :col 11 :api "atom"}
             (-> entries first (select-keys [:line :col]) (assoc :api (get-in (first entries) [:detail :api]))))))
    (testing "and a sentence to act on"
      (is (str/includes? (:note (first entries)) "NO view-local state tier")))))

(deftest every-roster-class-has-a-recovery-sentence
  (doseq [[api {:keys [class verdict]}] rf.migration.hicasso.census/surface]
    (testing (str api)
      (is (contains? (set rf.migration.hicasso.census/verdicts) verdict))
      (is (string? (:note (first (:entries (rf.migration.hicasso.census/scan
                                            (str "(ns a (:require [reagent.core :as r]))\n"
                                                 "(r/" api " x)\n")
                                            "a.cljs")))))
          "a class the roster can produce but the notes cannot describe"))))

;; ---------------------------------------------------------------------------
;; What it cannot resolve is REPORTED, never skipped
;; ---------------------------------------------------------------------------

(def ^:private conditional-require
  "The only legal way to require Reagent from a `.cljc` file.
  `examples/capabilities/ssr/` carries three real ones.

  This was the archetypal UNBINDABLE require until rf2-m4hm taught
  `ns-context` to read require clauses through reader-conditional nodes.
  It now resolves, and the assertions below say so."
  (str "(ns app.ssr\n"
       "  (:require [re-frame.core :as rf]\n"
       "            #?(:cljs [reagent.dom.client :as rdc])))\n"
       "\n"
       "#?(:cljs (defonce react-root (atom nil)))\n"
       "\n"
       "#?(:cljs (defn mount! [] (rdc/render @react-root [:div])))\n"))

(def ^:private vendored-require
  "A namespace that SPELLS a Reagent name without being Reagent's.

  re-frame-10x ships its dependencies inlined under a private prefix, so
  `names-reagent?` — which reads the `ns` form's text — says yes while
  `ns-context` correctly binds nothing, because only the exact roster
  binds. This is the population `:unresolved-reagent-require` exists for
  now that the reader-conditional shape resolves, and the tool must keep
  REPORTING it rather than guessing that `r` is `reagent.core`."
  (str "(ns app.panel\n"
       "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core :as r]))\n"
       "\n"
       "(defonce state (atom nil))\n"
       "\n"
       "(defn panel [] (r/atom {}))\n"))

(deftest an-unbindable-require-is-reported
  (let [{:keys [entries unresolved?]} (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs")]
    (is (true? unresolved?))
    (is (= [:unresolved-reagent-require :unresolved-alias] (mapv :class entries)))
    (testing "the require is reported at the `ns` form, where the fix goes"
      (is (= 1 (:line (first entries)))))
    (testing "and the call that could not be bound names the symbol it could not bind"
      (is (= {:api "atom" :symbol "r/atom"} (:detail (second entries)))))
    (testing "the note says the whole tool family is blind here, not just the census"
      (is (str/includes? (:note (first entries)) "PARTIALLY BLIND")))))

(deftest the-unqualified-core-call-beside-it-is-not-reported
  (testing "`(atom nil)` on line 4 is `clojure.core`'s. An alias is what could not
            be bound, so a call with no alias is not evidence of it."
    (is (not-any? #(= 4 (:line %)) (:entries (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs"))))))

;; ---------------------------------------------------------------------------
;; A require behind a reader conditional RESOLVES (rf2-m4hm)
;; ---------------------------------------------------------------------------

(deftest a-reader-conditional-require-binds
  (testing "the non-splicing shape — `ns-context` used to sexpr the whole `ns`
            form, which throws on a reader-conditional node, so EVERY alias
            came back empty and the file's real findings were reported only as
            `:unresolved-alias`"
    (let [{:keys [entries reagent? unresolved?]} (rf.migration.hicasso.census/scan conditional-require "app/ssr.cljc")]
      (is (true? reagent?))
      (is (false? unresolved?) "resolved, not merely reported")
      (is (= [:root-mount] (mapv :class entries))
          "the class the call actually has, not the fallback")
      (is (= {:api "render"} (:detail (first entries))))))

  (testing "the splicing shape — `#?@` carries a COLLECTION of specs where `#?`
            carries one, so its branch value is lifted a level"
    (let [src (str "(ns app.ssr\n"
                   "  (:require #?@(:cljs [[reagent.core :as r]])))\n"
                   "\n"
                   "(defn v [] (r/atom 1))\n")]
      (is (= [:local-reactive-cell] (classes src "app/ssr.cljc")))))

  (testing "a conditional wrapping the whole `(:require …)` clause, not one spec"
    (let [src (str "(ns app.ssr\n"
                   "  #?(:cljs (:require [reagent.core :as r])))\n"
                   "\n"
                   "(defn v [] (r/atom 1))\n")]
      (is (= [:local-reactive-cell] (classes src "app/ssr.cljc")))))

  (testing "`:refer` through a conditional binds the referred names too"
    (let [src (str "(ns app.ssr\n"
                   "  (:require #?(:cljs [reagent.core :refer [partial]])))\n"
                   "\n"
                   "(defn v [] (partial f 1))\n")]
      (is (= [:reagent-partial] (classes src "app/ssr.cljc"))))))

(deftest one-resolving-require-does-not-vouch-for-another
  (testing "MERGED-PR AUDIT #7979's edge. An ordinary Reagent require beside a
            conditional one left the alias UNION non-empty, so `unresolved?` was
            false and the conditional half's call sites vanished with NO
            diagnostic — neither `:unresolved-reagent-require` nor
            `:unresolved-alias`. That is why the repair reads spec by spec
            rather than retrying the whole form when the alias set comes back
            empty: here it never was empty."
    (let [src (str "(ns app.mixed\n"
                   "  (:require [reagent.core :as r]\n"
                   "            #?(:cljs [reagent.dom.client :as rdc])))\n"
                   "\n"
                   "(defn v [] (r/atom 1))\n"
                   "(defn m [] (rdc/render nil nil))\n")
          {:keys [entries unresolved?]} (rf.migration.hicasso.census/scan src "app/mixed.cljc")]
      (is (false? unresolved?))
      (is (= [:local-reactive-cell :root-mount] (mapv :class entries))
          "BOTH requires' call sites are named, not just the ordinary one")
      (is (= [5 6] (mapv :line entries))))))

(deftest a-conditional-that-is-not-reagents-binds-nothing
  (testing "seeing THROUGH a reader conditional is only correct if it also
            declines to bind — a fix that read every branch as Reagent's would
            report a file with no Reagent in it"
    (let [src (str "(ns app.util\n"
                   "  (:require #?(:cljs [clojure.string :as str])))\n"
                   "\n"
                   "(defn v [] (str/atom 1))\n")
          {:keys [entries reagent? unresolved?]} (rf.migration.hicasso.census/scan src "app/util.cljc")]
      (is (false? reagent?))
      (is (false? unresolved?))
      (is (= [] entries)))))

(deftest an-ns-form-under-metadata-is-still-the-ns-form
  (testing "`^:cljstyle/ignore (ns …)` is ordinary. Reading it as `nil` binds
            NOTHING for the whole file — W4 and W5 dead, every Reagent API
            unnamed — and nothing looks wrong, because `:>` needs no alias and
            the fixer's report stays non-empty. Athens'
            `views/pages/graph.cljs` is that file: four `(r/atom …)` invisible
            under the ignore, in a namespace already carrying eleven entries.
            A `r/atom` cross-check against the corpus read 47 where the text
            said 51, and the four were all in it."
    (let [src (str "^:cljstyle/ignore\n"
                   "(ns ^{:doc \"Graph and controls.\"}\n"
                   " app.graph\n"
                   "  (:require\n"
                   "   [re-frame.core :as rf]\n"
                   "   [reagent.core :as r]))\n"
                   "\n"
                   "(def graph-ref-map (r/atom {}))\n")
          {:keys [entries reagent? unresolved?]} (rf.migration.hicasso.census/scan src "app/graph.cljs")]
      (is (true? reagent?))
      (is (false? unresolved?) "resolved, not merely reported")
      (is (= [:local-reactive-cell] (mapv :class entries)))
      (is (= 8 (:line (first entries))))))
  (testing "and the ns is reported ONCE when it cannot be bound, though
            `ns-form?` matches at the meta node and at the list inside it"
    (let [src (str "^:cljstyle/ignore\n"
                   "(ns app.graph\n"
                   "  (:require [day8.re-frame-10x.inlined-deps.reagent.v1v2v0.reagent.core\n"
                   "             :as r]))\n")]
      (is (= [:unresolved-reagent-require] (classes src "app/graph.cljc")))))
  (testing "a conditional require under that same metadata resolves — the
            metadata path and the conditional path compose"
    (let [src (str "^:cljstyle/ignore\n"
                   "(ns app.graph\n"
                   "  (:require #?(:cljs [reagent.core :as r])))\n"
                   "\n"
                   "(def graph-ref-map (r/atom {}))\n")]
      (is (= [:local-reactive-cell] (classes src "app/graph.cljc"))))))

;; ---------------------------------------------------------------------------
;; A legal population comes back CLEAN
;; ---------------------------------------------------------------------------

(deftest a-corpus-without-reagent-is-clean
  (let [src (str "(ns app.views\n"
                 "  (:require [re-frame.core :as rf]))\n"
                 "\n"
                 "(defn page []\n"
                 "  [:div (for [x @(rf/subscribe [:xs])] ^{:key x} [:span x])])\n")
        {:keys [entries reagent? unresolved? recognised?]} (rf.migration.hicasso.census/scan src "app/views.cljs")]
    (is (= [] entries))
    (is (false? reagent?))
    (is (false? unresolved?))
    (testing "and NOT recognised, which is a different fact from clean and is why
              this deftest's name is only half the story. This is the shape of
              every view file in the application that scored zero: legal,
              unmentioned, and full of migration work the census has no
              population for. `summarise` is where the two are told apart."
      (is (false? recognised?)))))

(deftest prose-about-reagent-is-not-a-finding
  (testing "the `ns` docstring is not a require. Five files in examples/ discuss
            `reagent.ratom/run!` in prose and are clean."
    (let [src (str "(ns app.editor\n"
                   "  \"Settle is driven by causal events, not Form-3 `reagent.ratom/run!`\n"
                   "   reactions watching a settle, and there is no `reagent.core/atom` here.\"\n"
                   "  (:require [clojure.string :as str]))\n"
                   "\n"
                   "(defn page [] [:div (str/upper-case \"x\")])\n")
          {:keys [entries reagent?]} (rf.migration.hicasso.census/scan src "app/editor.cljs")]
      (is (= [] entries))
      (is (false? reagent?)))))

(deftest a-word-in-a-comment-is-not-a-call
  (testing "`grep -c with-let` over examples/ answers 10; the corpus holds ONE.
            Nine of the ten are prose."
    (let [src (str "(ns app.bench\n"
                   "  \"The wrapper's `r/with-let` cleanup dispatches :cancel on unmount.\"\n"
                   "  (:require [reagent.core :as r]))\n"
                   "\n"
                   ";; riding on Reagent's own `reagent.core/with-let`\n"
                   "(defn bench []\n"
                   "  (r/with-let [_ nil]\n"
                   "    [:div]\n"
                   "    (finally (dispatch [:cancel]))))\n")]
      (is (= [:with-let] (classes src "app/bench.cljs")))
      (is (= 7 (:line (first (:entries (rf.migration.hicasso.census/scan src "app/bench.cljs")))))))))

(deftest a-core-name-is-not-reagents-without-a-binding
  (testing "half the roster shares a name with `clojure.core`. Only a bound alias,
            or a `:refer` the `ns` form actually wrote, makes one Reagent's."
    (let [src (str "(ns app.util\n"
                   "  (:require [clojure.string :as str]))\n"
                   "\n"
                   "(def cache (atom {}))\n"
                   "(defn go [f xs] (run! f xs))\n"
                   "(defn f [g] (partial g 1))\n"
                   "(defn h [m] (flush))\n")]
      (is (= [] (classes src "app/util.cljs")))))
  (testing "but a `:refer` from Reagent does"
    (let [src (str "(ns app.util\n"
                   "  (:require [reagent.core :refer [atom]]))\n"
                   "\n"
                   "(def cache (atom {}))\n")]
      (is (= [:local-reactive-cell] (classes src "app/util.cljs"))))))

;; ---------------------------------------------------------------------------
;; A call SITE is source that runs (merged-PR audit #8140)
;; ---------------------------------------------------------------------------

(deftest inert-source-is-not-a-call-site
  (testing "MERGED-PR AUDIT #8140's first finding. The walk advanced with an
            unconditional `z/next`, so a discard, a quote and a `(comment …)`
            body — each of which parses into the same nodes a live call does —
            reported IDENTICALLY to the live call. None is a call site, and a
            census of call sites that cannot tell them apart is a census whose
            every number is an upper bound nobody stated."
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [:local-reactive-cell] (classes (str hdr "(def a (r/atom 0))\n") "app/p.cljs"))
          "the live call is the control this is measured against")
      (is (= [] (classes (str hdr "(def a '(r/atom 0))\n") "app/p.cljs"))
          "reader quote")
      (is (= [] (classes (str hdr "(def a (quote (r/atom 0)))\n") "app/p.cljs"))
          "the `quote` special form spells the same thing")
      (is (= [] (classes (str hdr "(def a 1)\n#_(r/atom 0)\n") "app/p.cljs"))
          "`#_` discard")
      (is (= [] (classes (str hdr "(comment (r/atom 0))\n") "app/p.cljs"))
          "`(comment …)` body")))

  (testing "pruning a subtree must not prune what FOLLOWS it — the walk resumes
            at the next sibling, and at the enclosing form's next sibling when
            the inert form was last"
    (let [hdr "(ns app.p\n  (:require [reagent.core :as r]))\n"]
      (is (= [:local-reactive-cell]
             (classes (str hdr "#_(r/atom 0)\n(comment (r/atom 1))\n'(r/atom 2)\n"
                           "(def z (r/atom 3))\n")
                      "app/p.cljs"))
          "three inert forms in a row, then the live one")
      (is (= [:with-let]
             (classes (str hdr "(defn f []\n  (comment (r/atom 0))\n"
                           "  (r/with-let [_ 1] [:div]))\n")
                      "app/p.cljs"))
          "inert form NESTED inside a live one")
      (is (= [:local-reactive-cell]
             (classes (str hdr "(def z (r/atom 3))\n(comment (r/atom 0))\n") "app/p.cljs"))
          "the inert form ends the file: the walk terminates rather than looping"))))

(deftest a-syntax-quote-is-still-a-call-site
  (testing "the boundary, stated as a test so it is a decision and not an
            oversight. `#_`, `'` and `(comment …)` exist to NOT be code. A
            syntax-quote is a macro's template: the form it emits is a real
            call site at every expansion, and its `~unquote`s run outright.
            The tool prunes the three and stops."
    (let [src (str "(ns app.p\n  (:require [reagent.core :as r]))\n"
                   "(defmacro m [] `(r/atom 0))\n")]
      (is (= [:local-reactive-cell] (classes src "app/p.clj"))))))

;; ---------------------------------------------------------------------------
;; Legal libspec options that bound nothing (merged-PR audit #8140)
;; ---------------------------------------------------------------------------

(deftest refer-all-binds-the-whole-roster
  (testing "`:refer :all` is legal and this repository writes none, so nothing
            reached it: `ns-context` took `:refer` only when it was a
            collection, `:all` fell through, and every bare Reagent call in
            such a file went unreported with no diagnostic at all."
    (let [src (str "(ns app.p\n"
                   "  (:require [reagent.core :refer :all]))\n"
                   "\n"
                   "(def a (atom 0))\n"
                   "(defn f [] (with-let [_ 1] [:div]))\n")]
      (is (= [:local-reactive-cell :with-let] (classes src "app/p.clj")))))

  (testing "and it binds only the namespace it is written on"
    (let [src (str "(ns app.p\n"
                   "  (:require [clojure.string :refer :all]))\n"
                   "\n"
                   "(def a (atom 0))\n")]
      (is (= [] (classes src "app/p.clj"))))))

(deftest a-rename-binds-the-new-spelling-and-releases-the-old
  (testing "`:refer [atom] :rename {atom ratom}` binds `ratom` to
            `reagent.core/atom`. The tool bound `atom` — a name the file no
            longer uses for Reagent — so the finding was missed at the call
            AND invented at the one place `clojure.core/atom` still lives."
    (let [src (str "(ns app.p\n"
                   "  (:require [reagent.core :refer [atom] :rename {atom ratom}]))\n"
                   "\n"
                   "(def a (ratom 0))\n"
                   "(def b (atom 0))\n")
          {:keys [entries]} (rf.migration.hicasso.census/scan src "app/p.clj")]
      (is (= [:local-reactive-cell] (mapv :class entries)))
      (is (= [4] (mapv :line entries)) "the renamed call, not the core one")
      (testing "and it is reported under the ROSTER name, which is the only one
                with a class and a recovery sentence"
        (is (= {:api "atom"} (:detail (first entries)))))))

  (testing "the two compose: `:refer :all` binds everything the rename did not
            take away"
    (let [src (str "(ns app.p\n"
                   "  (:require [reagent.core :refer :all :rename {atom ratom}]))\n"
                   "\n"
                   "(def a (ratom 0))\n"
                   "(def b (atom 0))\n")]
      (is (= [4] (mapv :line (:entries (rf.migration.hicasso.census/scan src "app/p.clj")))))))

  (testing "a `:rename` with no `:refer` binds nothing, which is the effect
            Clojure gives it"
    (let [src (str "(ns app.p\n"
                   "  (:require [reagent.core :as r :rename {atom ratom}]))\n"
                   "\n"
                   "(def a (ratom 0))\n")]
      (is (= [] (classes src "app/p.clj"))))))

;; ---------------------------------------------------------------------------
;; Determinism, and a summary that cannot hide an empty bucket
;; ---------------------------------------------------------------------------

(deftest a-second-scan-reports-identically
  (is (= (rf.migration.hicasso.census/scan form-2 "app/counter.cljs")
         (rf.migration.hicasso.census/scan form-2 "app/counter.cljs")))
  (is (= (rf.migration.hicasso.census/scan conditional-require "app/ssr.cljc")
         (rf.migration.hicasso.census/scan conditional-require "app/ssr.cljc")))
  (is (= (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs")
         (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs"))))

(deftest the-summary-names-every-bucket-including-the-empty-ones
  (let [built (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan form-2 "app/counter.cljs")
                             (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs")
                             (rf.migration.hicasso.census/scan "(ns a)\n(defn f [] [:div])\n" "app/plain.cljs")])
        s     (:summary built)]
    (is (= (set rf.migration.hicasso.census/verdicts) (set (keys (:by-verdict s))))
        "an absent key is a count nobody can tell from a bug")
    (is (= 0 (get-in s [:by-verdict :mechanical]))
        "nothing outside a crossing is mechanical, and the census says so with a zero")
    (is (= 3 (:files-scanned s)))
    (is (= 2 (:files-with-reagent s)))
    (is (= 1 (:files-unresolved s)))
    (is (= 3 (:entries s)))
    (testing "ordering is file, then line, then column"
      (is (= (:entries built) (vec (sort-by (juxt :file :line :col) (:entries built))))))))

;; ---------------------------------------------------------------------------
;; The SECOND roster: re-frame2's own substrate adapters (rf2-xoal)
;; ---------------------------------------------------------------------------

(def ^:private rf2-native-boot
  "A re-frame2 application's boot file, on the Reagent adapter.

  This is the shape that scored ZERO. It renders through Reagent and never
  names it: the substrate arrives as `re-frame.adapter.reagent`, and there
  is no `reagent.core` name in the whole application."
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
  (testing "THE REPORTED DEFECT. `re-frame.adapter.reagent` is not `reagent.core`,
            so a census that classified by the Reagent roster alone had nothing
            to count in the file that actually mounts the application."
    (let [{:keys [entries reagent? substrate? recognised?]}
          (rf.migration.hicasso.census/scan rf2-native-boot "app/core.cljs")]
      (is (false? reagent?) "there is genuinely no Reagent name in this file")
      (is (true? substrate?))
      (is (true? recognised?))
      (is (= [:root-mount :root-mount] (mapv :class entries)))
      (is (= [5 9] (mapv :line entries)) "client-root, then render!")
      (is (= [{:api "client-root"} {:api "render!"}] (mapv :detail entries)))
      (testing "and each carries the recovery sentence for boot ceremony"
        (is (str/includes? (:note (first entries)) "Hicasso mounts its own root")))))

  (testing "`reagent-adapter/adapter` on the line between them is NOT counted:
            it is a value in argument position, and this walk reads call heads.
            The file is recognised through its `ns` form regardless, so nothing
            rides on catching it — stated here so the silence is a decision."
    (is (= 2 (count (:entries (rf.migration.hicasso.census/scan rf2-native-boot "app/core.cljs")))))))

(deftest every-substrate-class-has-a-recovery-sentence
  (doseq [[api {:keys [verdict]}] rf.migration.hicasso.census/substrate-surface]
    (testing (str api)
      (is (contains? (set rf.migration.hicasso.census/verdicts) verdict))
      (is (string? (:note (first (:entries (rf.migration.hicasso.census/scan
                                            (str "(ns a (:require [re-frame.adapter.uix :as ad]))\n"
                                                 "(ad/" api " x)\n")
                                            "a.cljs")))))
          "a class the roster can produce but the notes cannot describe"))))

(deftest a-shared-roster-name-resolves-by-require
  (testing "this used to assert the two rosters were DISJOINT, on the reasoning
            that `scan` tries Reagent first so an overlap would classify a
            substrate call under a Reagent class. `bound-call?` is what actually
            decides, and it consults a SEPARATE `ns` context per roster, so the
            require decides the arm and the name order decides nothing. The
            emptiness assertion was pinning a proxy; this pins the property
            (rf2-xoal). An UNINTENDED overlap still reds here — only the
            deliberate one is allowed through."
    (is (= '#{flush-views!}
           (clojure.set/intersection (set (keys rf.migration.hicasso.census/surface))
                                     (set (keys rf.migration.hicasso.census/substrate-surface))))))

  (testing "through `reagent2.dom.client` the shared name is Reagent's — and the
            file counts as one that NAMES Reagent, which is the flag the migration
            skill reads to decide whether a Reagent coordinate may be dropped"
    (let [src (str "(ns app.t\n"
                   "  (:require [reagent2.dom.client :as rdc]))\n"
                   "\n"
                   "(defn settle [] (rdc/flush-views!))\n")
          {:keys [entries reagent? substrate?]} (rf.migration.hicasso.census/scan src "app/t.cljs")]
      (is (true? reagent?))
      (is (false? substrate?))
      (is (= [:substrate-test-seam] (mapv :class entries)))))

  (testing "and through an adapter it is the substrate's, with the two flags the
            other way round — same class, because it is the same seam at two
            addresses, and one recovery sentence answers for both"
    (let [src (str "(ns app.t\n"
                   "  (:require [re-frame.adapter.uix :as ad]))\n"
                   "\n"
                   "(defn settle [] (ad/flush-views!))\n")
          {:keys [entries reagent? substrate?]} (rf.migration.hicasso.census/scan src "app/t.cljs")]
      (is (false? reagent?))
      (is (true? substrate?))
      (is (= [:substrate-test-seam] (mapv :class entries))))))

(deftest the-prefix-rule-is-anchored-at-the-start
  (testing "the substrate roster binds by PREFIX so the adapter set can grow,
            and that is only safe anchored: a namespace that CONTAINS
            `re-frame.adapter.` is not one, and a containment test would bind
            somebody else's API under re-frame2's roster. This is the vendored
            copy's lesson arriving in the second family."
    (let [src (str "(ns app.p\n"
                   "  (:require [my.vendored.re-frame.adapter.reagent :as ad]))\n"
                   "\n"
                   "(defn f [] (ad/render! nil nil nil))\n")
          {:keys [entries substrate? recognised?]} (rf.migration.hicasso.census/scan src "app/p.cljs")]
      (is (= [] entries))
      (is (false? substrate?))
      (is (false? recognised?)))))

;; ---------------------------------------------------------------------------
;; The slim adapter's `reagent2.*` IS Reagent (rf2-xoal)
;; ---------------------------------------------------------------------------

(deftest the-slim-reagent-is-reagent
  (testing "`implementation/adapters/reagent-slim/` ships Reagent's API a second
            time under `reagent2.*`, and `docs/core/how-to/use-uix-or-slim.md`
            teaches consumers to call it by the same names. An application on
            the slim substrate scored ZERO for want of four roster entries —
            the same defect the adapter surface had, one adopter later."
    (let [src (str "(ns app.chart\n"
                   "  (:require [reagent2.core :as r]))\n"
                   "\n"
                   "(def cache (r/atom {}))\n"
                   "(defn chart [] (r/create-class {:reagent-render (fn [] [:div])}))\n"
                   "(defn el [h] (r/as-element h))\n")
          {:keys [entries reagent? unresolved?]} (rf.migration.hicasso.census/scan src "app/chart.cljs")]
      (is (true? reagent?))
      (is (false? unresolved?) "bound, not merely named")
      (is (= [:local-reactive-cell :lifecycle-class :as-element] (mapv :class entries)))))

  (testing "and the slim ratom namespace binds too"
    (let [src (str "(ns app.d\n"
                   "  (:require [reagent2.ratom :as ratom]))\n"
                   "\n"
                   "(defn d [] (ratom/make-reaction #(inc 1)))\n")]
      (is (= [:derived-cell] (classes src "app/d.cljs")))))

  (testing "`reagent2` is not reached by SPELLING either: the roster is exact,
            so a vendored copy of the slim one binds nothing"
    (let [src (str "(ns app.p\n"
                   "  (:require [vendor.inlined.reagent2.core :as r]))\n"
                   "\n"
                   "(defn f [] (r/atom 0))\n")]
      (is (= [:unresolved-reagent-require :unresolved-alias] (classes src "app/p.cljs"))))))

;; ---------------------------------------------------------------------------
;; RECOGNISED BUT UNCOUNTABLE — the confident zero that came back through the
;; widening itself (rf2-xoal, merged-PR audit of #9132)
;; ---------------------------------------------------------------------------

(def ^:private recognised-namespace-calls
  "One known PUBLIC call per namespace `rewrite/reagent-namespaces`
  recognises, written as it would be called through the alias `x`.

  **This table is the ratchet, and the assertion below that it is
  COMPLETE is the half that matters.** Recognition is decided per FILE and
  entries are found per NAME, so widening the namespace set without
  widening `rf.migration.hicasso.census/surface` converts an honest *I did not recognise this
  file* into `:recognition :full` with `entries 0` — a confident zero, and
  a strictly worse answer than the one it replaced. PR #9132 did exactly
  that: it added the `reagent2.*` four and left `reagent2.dom.server`'s
  only public function off the roster.

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
  what `caveat`'s weakened `:full` sentence now admits to."
  '{re-frame.adapter.reagent      "(x/render! root view opts)"
    re-frame.adapter.reagent-slim "(x/client-root el)"
    re-frame.adapter.uix          "(x/use-subscribe [:q])"
    re-frame.adapter.test-react   "(x/mount! [:div])"})

(defn- probe
  "Scan a one-call file that requires `ns*` as `x`, and return the census
  summary over it."
  [ns* call]
  (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan (str "(ns app.probe\n"
                                             "  (:require [" ns* " :as x]))\n"
                                             "\n"
                                             "(defn f [] " call ")\n")
                                        "app/probe.cljs")])))

(deftest every-recognised-namespace-has-a-rostered-call
  (testing "the ratchet: a namespace cannot join `reagent-namespaces` without a
            sample here, so the next widening cannot reach main half-done the way
            #9132's did"
    (is (= (set (keys recognised-namespace-calls)) rf.migration.hicasso.rewrite/reagent-namespaces)
        "a recognised namespace with no known-public-call sample, or a sample for
         a namespace the tool does not recognise"))

  (doseq [[ns* call] recognised-namespace-calls]
    (testing (str ns* " " call)
      (let [s (probe ns* call)]
        (is (= :full (:recognition s))
            "the probe file must be recognised, or it is testing the wrong thing")
        (is (not (and (= :full (:recognition s)) (zero? (:entries s))))
            (str "CONFIDENT ZERO: " ns* " is recognised, " call " is one of its public "
                 "calls, and the census scored it :full with zero entries — which is the "
                 "sentence `a zero below is a measurement` over a roster that has no row "
                 "for it (rf2-xoal)"))
        (is (= 0 (:files-clean s))
            "a file whose only call is on the roster is not a clean file")
        (is (= 1 (:files-recognised s))))))

  (doseq [[ns* call] substrate-namespace-calls]
    (testing (str ns* " " call)
      (let [s (probe ns* call)]
        (is (= :full (:recognition s)))
        (is (not (and (= :full (:recognition s)) (zero? (:entries s))))
            (str "CONFIDENT ZERO through the prefix rule: " ns* " " call))
        (is (= 0 (:files-clean s)))))))

(deftest the-audits-reproduction
  (testing "the exact file the merged-PR audit of #9132 built, and the four numbers
            it reported. `reagent2.dom.server` was added to the namespace roster and
            `render-to-static-markup` — its ONLY public function — was not added to
            the call roster, so the census recognised the file, found nothing in it,
            counted it CLEAN, and told its reader the zero was a measurement."
    (let [src (str "(ns app.ssr\n"
                   "  (:require [reagent2.dom.server :as rds]))\n"
                   "\n"
                   "(defn page-html []\n"
                   "  (rds/render-to-static-markup [:div \"hello\"]))\n")
          s   (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan src "app/ssr.cljs")]))]
      (is (= 1 (:files-recognised s)))
      (is (= :full (:recognition s)))
      (is (= 1 (:entries s))         "was 0")
      (is (= 0 (:files-clean s))     "was 1")
      (is (= 1 (get-in s [:by-verdict :human-decision])))
      (is (= {:static-markup 1} (:by-class s)))
      (testing "and the sentence that made the zero confident is gone, replaced by one
                that says what recognition actually measures"
        (is (not (str/includes? (:caveat s) "A zero below is a measurement")))
        (is (str/includes? (:caveat s) "NOT ABOUT THE ROSTER"))))))

(deftest a-full-zero-still-says-the-roster-bounds-it
  (testing "recognition can be `:full` and the count still zero — a recognised file
            whose only call is a shape no roster names. That is legal and honest;
            what it must not do is present the zero as a measurement of the corpus.
            This is the residual no roster closes, so the WORDING is the gate."
    (let [src (str "(ns app.v\n"
                   "  (:require [reagent.core :as r]))\n"
                   "\n"
                   "(defn v [] [:div \"no rostered call in this file\"])\n")
          s   (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan src "app/v.cljs")]))]
      (is (= :full (:recognition s)))
      (is (= 0 (:entries s)))
      (is (str/includes? (:caveat s) "fixed roster"))
      (is (str/includes? (:caveat s) "not that these files hold no migration work"))
      (testing "and the CLI tail — the last line of the run, read by whoever reads
                nothing else — marks the zero rather than printing it bare"
        (is (str/includes? (rf.migration.hicasso.census/describe {:summary s}) "ZERO ENTRIES"))))))

;; ---------------------------------------------------------------------------
;; A zero the tool CANNOT report confidently (rf2-xoal)
;; ---------------------------------------------------------------------------

(deftest recognising-nothing-is-not-finding-nothing
  (testing "the corpus that started this: every file a re-frame2 view file, so
            the census has no population anywhere in it. Its zero used to be
            indistinguishable from a clean bill of health, which is the
            fail-open shape."
    (let [s (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan rf2-native-view "app/articles.cljs")
                                     (rf.migration.hicasso.census/scan rf2-native-view "app/profile.cljs")]))]
      (is (= 0 (:entries s)))
      (is (= :none (:recognition s)))
      (is (= 2 (:files-scanned s)))
      (is (= 0 (:files-recognised s)))
      (is (= 2 (:files-unrecognised s)))
      (is (str/includes? (:caveat s) "NOT A CLEAN BILL OF HEALTH"))
      (testing "and the CLI tail says it too, because whoever reads only the last
                line of the run is exactly who this misleads"
        (is (str/includes? (rf.migration.hicasso.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "THE CONTROL, and it has to fire or the assertion above means nothing:
            the same machinery over a corpus the census DOES have a population
            in reports `:full`, and its caveat says the zero would be a
            measurement rather than a shrug."
    (let [s (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan form-2 "app/counter.cljs")]))]
      (is (= :full (:recognition s)))
      (is (= 1 (:entries s)))
      (is (= 0 (:files-unrecognised s)))
      (is (str/includes? (:caveat s) "a population throughout"))
      (is (not (str/includes? (rf.migration.hicasso.census/describe {:summary s}) "NOTHING RECOGNISED")))))

  (testing "the mixed corpus — one adapter file, one view file — is `:partial`,
            and its caveat is the one a re-frame2 migrator needs: the view files
            are absent from this census AND full of migration work."
    (let [s (:summary (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan rf2-native-boot "app/core.cljs")
                                     (rf.migration.hicasso.census/scan rf2-native-view "app/articles.cljs")]))]
      (is (= :partial (:recognition s)))
      (is (= 2 (:entries s)) "the two mount calls, which used to be zero")
      (is (= 1 (:files-with-substrate s)))
      (is (= 0 (:files-with-reagent s))
          "`:files-with-reagent` keeps its meaning: no Reagent name is in this app")
      (is (str/includes? (:caveat s) "full of migration work"))))

  (testing "an empty path set is its own verdict rather than a zero"
    (let [s (:summary (rf.migration.hicasso.census/build []))]
      (is (= :no-files (:recognition s)))
      (is (str/includes? (:caveat s) "NO SOURCE FILE WAS SCANNED")))))

(deftest the-file-counts-partition-the-corpus
  (testing "the reported defect came with a bookkeeping tell beside it: 18 files
            scanned, and `files-with-reagent` + `files-unresolved` +
            `files-clean` summing to 0. The buckets close now, and the one that
            was missing is the one the zero was hiding."
    (let [built (rf.migration.hicasso.census/build [(rf.migration.hicasso.census/scan form-2 "app/counter.cljs")
                               (rf.migration.hicasso.census/scan vendored-require "app/panel.cljs")
                               (rf.migration.hicasso.census/scan rf2-native-boot "app/core.cljs")
                               (rf.migration.hicasso.census/scan rf2-native-view "app/articles.cljs")])
          s     (:summary built)
          with-entries (count (into #{} (map :file) (:entries built)))]
      (is (= (:files-scanned s) (+ (:files-recognised s) (:files-unrecognised s))))
      (is (= (:files-recognised s) (+ (:files-clean s) with-entries)))
      (is (= 4 (:files-scanned s)))
      (is (= 3 (:files-recognised s)))
      (is (= 1 (:files-unrecognised s)))
      (is (= 2 (:files-with-reagent s)))
      (is (= 1 (:files-with-substrate s)))
      (is (= 0 (:files-clean s))))))
