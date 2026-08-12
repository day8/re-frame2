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

  * `ns` DOCSTRING prose mentioning `reagent.ratom/run!` made five clean
    example files read as five migration blockers. `names-reagent?` now
    reads the `ns` form's CLAUSES.
  * `(atom nil)` — `clojure.core`'s — sat one line above the `rdc/render`
    that is the genuine finding in the SSR examples, and both were
    reported. `:unresolved-alias` now needs a qualified head, because the
    thing that could not be bound is an ALIAS."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.census :as census]
            [re-frame.migration.hicasso.codemod :as cm]))

(defn- classes [src file] (mapv :class (:entries (census/scan src file))))

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
    (let [r (cm/scan-string form-2 "app/counter.cljs")]
      (is (= [] (:entries r)))
      (is (= 0 (:sites r))))))

(deftest the-census-answers-non-empty
  (let [{:keys [entries reagent? unresolved?]} (census/scan form-2 "app/counter.cljs")]
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
  (doseq [[api {:keys [class verdict]}] census/surface]
    (testing (str api)
      (is (contains? (set census/verdicts) verdict))
      (is (string? (:note (first (:entries (census/scan
                                            (str "(ns a (:require [reagent.core :as r]))\n"
                                                 "(r/" api " x)\n")
                                            "a.cljs")))))
          "a class the roster can produce but the notes cannot describe"))))

;; ---------------------------------------------------------------------------
;; What it cannot resolve is REPORTED, never skipped
;; ---------------------------------------------------------------------------

(def ^:private conditional-require
  "The only legal way to require Reagent from a `.cljc` file — and the
  shape `ns-context` binds nothing for. `examples/capabilities/ssr/`
  carries three real ones."
  (str "(ns app.ssr\n"
       "  (:require [re-frame.core :as rf]\n"
       "            #?(:cljs [reagent.dom.client :as rdc])))\n"
       "\n"
       "#?(:cljs (defonce react-root (atom nil)))\n"
       "\n"
       "#?(:cljs (defn mount! [] (rdc/render @react-root [:div])))\n"))

(deftest an-unbindable-require-is-reported
  (let [{:keys [entries unresolved?]} (census/scan conditional-require "app/ssr.cljc")]
    (is (true? unresolved?))
    (is (= [:unresolved-reagent-require :unresolved-alias] (mapv :class entries)))
    (testing "the require is reported at the `ns` form, where the fix goes"
      (is (= 1 (:line (first entries)))))
    (testing "and the call that could not be bound names the symbol it could not bind"
      (is (= {:api "render" :symbol "rdc/render"} (:detail (second entries)))))
    (testing "the note says the whole tool family is blind here, not just the census"
      (is (str/includes? (:note (first entries)) "PARTIALLY BLIND")))))

(deftest the-unqualified-core-call-beside-it-is-not-reported
  (testing "`(atom nil)` on line 5 is `clojure.core`'s. An alias is what could not
            be bound, so a call with no alias is not evidence of it."
    (is (not-any? #(= 5 (:line %)) (:entries (census/scan conditional-require "app/ssr.cljc"))))))

;; ---------------------------------------------------------------------------
;; A legal population comes back CLEAN
;; ---------------------------------------------------------------------------

(deftest a-corpus-without-reagent-is-clean
  (let [src (str "(ns app.views\n"
                 "  (:require [re-frame.core :as rf]))\n"
                 "\n"
                 "(defn page []\n"
                 "  [:div (for [x @(rf/subscribe [:xs])] ^{:key x} [:span x])])\n")
        {:keys [entries reagent? unresolved?]} (census/scan src "app/views.cljs")]
    (is (= [] entries))
    (is (false? reagent?))
    (is (false? unresolved?))))

(deftest prose-about-reagent-is-not-a-finding
  (testing "the `ns` docstring is not a require. Five files in examples/ discuss
            `reagent.ratom/run!` in prose and are clean."
    (let [src (str "(ns app.editor\n"
                   "  \"Settle is driven by causal events, not Form-3 `reagent.ratom/run!`\n"
                   "   reactions watching a settle, and there is no `reagent.core/atom` here.\"\n"
                   "  (:require [clojure.string :as str]))\n"
                   "\n"
                   "(defn page [] [:div (str/upper-case \"x\")])\n")
          {:keys [entries reagent?]} (census/scan src "app/editor.cljs")]
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
      (is (= 7 (:line (first (:entries (census/scan src "app/bench.cljs")))))))))

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
;; Determinism, and a summary that cannot hide an empty bucket
;; ---------------------------------------------------------------------------

(deftest a-second-scan-reports-identically
  (is (= (census/scan form-2 "app/counter.cljs")
         (census/scan form-2 "app/counter.cljs")))
  (is (= (census/scan conditional-require "app/ssr.cljc")
         (census/scan conditional-require "app/ssr.cljc"))))

(deftest the-summary-names-every-bucket-including-the-empty-ones
  (let [built (census/build [(census/scan form-2 "app/counter.cljs")
                             (census/scan conditional-require "app/ssr.cljc")
                             (census/scan "(ns a)\n(defn f [] [:div])\n" "app/plain.cljs")])
        s     (:summary built)]
    (is (= (set census/verdicts) (set (keys (:by-verdict s))))
        "an absent key is a count nobody can tell from a bug")
    (is (= 0 (get-in s [:by-verdict :mechanical]))
        "nothing outside a crossing is mechanical, and the census says so with a zero")
    (is (= 3 (:files-scanned s)))
    (is (= 2 (:files-with-reagent s)))
    (is (= 1 (:files-unresolved s)))
    (is (= 3 (:entries s)))
    (testing "ordering is file, then line, then column"
      (is (= (:entries built) (vec (sort-by (juxt :file :line :col) (:entries built))))))))
