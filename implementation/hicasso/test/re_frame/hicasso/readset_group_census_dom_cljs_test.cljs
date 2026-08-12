(ns re-frame.hicasso.readset-group-census-dom-cljs-test
  "THE POPULATION — the shared read-set census taken on real mounted
  applications (rf2-hic-083).

  [[re-frame.hicasso.readset-group-census-cljs-test]] decides the census's
  ARITHMETIC on the Node lane, where the published `render-body` +
  `commit-boundary!` seam makes a browser unnecessary. It cannot decide
  the POPULATION: how many instances of each body a real application
  mounts is React's answer, not a harness's, and a population transcribed
  by hand is the exact defect the ceremony census beside
  `examples.typeahead` warns about in its own opening.

  So every row here mounts a whole witness application on a real React
  root and reads the runtime's own tables while it is mounted. React
  decides how many boundaries exist; this file only counts them.

  ## The seven

  `rf2-hic-083` names *the slice + editor/grid apps*. All seven witness
  applications in the tree are censused instead, because a verdict that
  three applications support and four contradict is a verdict nobody
  should take, and adding the other four costs four mounts.

  ## What each row asserts, and what the last one decides

  Per application: the census answers a POSITIVE membership count (a
  reporter that cannot answer non-empty reports \"clean\" and \"nothing
  ran\" identically), and its entry-side walk reproduces the cell-side
  landmark exactly. Then [[the-pooled-population-decides-c1]] applies the
  pre-registered trigger to the pooled figure.

  The pooled row runs LAST because `cljs.test` runs a namespace's rows in
  definition order and waits for each `async` one — so the accumulator is
  complete when it reads it, and it says so by asserting that all seven
  applications reached it rather than trusting the order.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`) and
  each row degrades there to a STATED skip rather than to a false green."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.examples.editor.app :as editor-app]
            [re-frame.hicasso.examples.editor.views :as editor-views]
            [re-frame.hicasso.examples.forms.events :as forms-events]
            [re-frame.hicasso.examples.forms.views :as forms-views]
            [re-frame.hicasso.examples.grid.app :as grid-app]
            [re-frame.hicasso.examples.grid.views :as grid-views]
            [re-frame.hicasso.examples.navigation.events :as nav-events]
            [re-frame.hicasso.examples.navigation.routes :as nav-routes]
            [re-frame.hicasso.examples.navigation.views :as nav-views]
            [re-frame.hicasso.examples.slice.events :as slice-events]
            [re-frame.hicasso.examples.slice.routes :as slice-routes]
            [re-frame.hicasso.examples.slice.views :as slice-views]
            [re-frame.hicasso.examples.todo.app :as todo-app]
            [re-frame.hicasso.examples.todo.events :as todo-events]
            [re-frame.hicasso.examples.todo.routes :as todo-routes]
            [re-frame.hicasso.examples.todo.views :as todo-views]
            [re-frame.hicasso.examples.typeahead.events :as ta-events]
            [re-frame.hicasso.examples.typeahead.views :as ta-views]
            [re-frame.hicasso.readset-group-census :as census]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted population needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because every mounting row is `async`.
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; Each routed application's own routes, restored the
                      ;; way its own suite restores them: the registrar
                      ;; baseline is captured when this fixture form is
                      ;; EVALUATED, which is before these namespaces
                      ;; finished loading.
                      (slice-routes/register!)
                      (todo-routes/register!)
                      (nav-routes/register!))}))

;; ---------------------------------------------------------------------------
;; The accumulator
;; ---------------------------------------------------------------------------

(defonce ^:private !pool (atom nil))
(defonce ^:private !per-app (atom {}))

(defn- census-app!
  "Mount one application, census the live tables WHILE it is mounted, pool
  the report, then tear down and assert the mount left nothing behind."
  [label form opts done]
  (let [m   (hm/mount! form opts)
        rpt (census/report)]
    (swap! !pool census/pool rpt)
    (swap! !per-app assoc label rpt)
    (is (pos? (:entries rpt))
        (str label " — the census reached read-set entries"))
    (is (pos? (:memberships rpt))
        (str label " — and answered a POSITIVE membership count: " (pr-str rpt)))
    (is (census/calibrated? rpt)
        (str label " — entry-side reproduced the cell-side landmark: "
             (pr-str (:landmark rpt))))
    (-> (hm/unmount! m) (hm/assert-clean!) (.then done))))

;; ---------------------------------------------------------------------------
;; The seven populations
;; ---------------------------------------------------------------------------

(deftest slice-population
  (async done
    (if-not (browser?)
      (do (skip! "the slice") (done))
      (census-app! "slice" [slice-views/app {}]
                   {:initial-events [[::slice-events/seed]
                                     [:rf.route/navigate {:to slice-routes/feed}]]}
                   done))))

(deftest editor-population
  (async done
    (if-not (browser?)
      (do (skip! "the four-field editor") (done))
      (census-app! "editor" [editor-views/editor {}]
                   {:initial-events editor-app/initial-events}
                   done))))

(deftest grid-population
  (async done
    (if-not (browser?)
      (do (skip! "the 100-cell grid") (done))
      (census-app! "grid" [grid-views/grid {}]
                   {:initial-events (grid-app/initial-events)}
                   done))))

(deftest todo-population
  (async done
    (if-not (browser?)
      (do (skip! "the todo application") (done))
      (census-app! "todo" [todo-views/app {}]
                   {:initial-events [[::todo-events/seed todo-app/sample-todos]
                                     [:rf.route/navigate {:to todo-routes/all}]]}
                   done))))

(deftest forms-population
  (async done
    (if-not (browser?)
      (do (skip! "the forms recipes") (done))
      (census-app! "forms" [forms-views/screen {:ikey 7}]
                   {:initial-events [[::forms-events/seed]]}
                   done))))

(deftest typeahead-population
  (async done
    (if-not (browser?)
      (do (skip! "the typeahead") (done))
      (census-app! "typeahead" [ta-views/screen {}]
                   {:initial-events [[::ta-events/seed]]}
                   done))))

(deftest navigation-population
  (async done
    (if-not (browser?)
      (do (skip! "the navigation recipes") (done))
      (census-app! "navigation" [nav-views/app {}]
                   {:initial-events [[::nav-events/seed]
                                     [:rf.route/navigate {:to nav-routes/feed}]]}
                   done))))

;; ---------------------------------------------------------------------------
;; The verdict
;; ---------------------------------------------------------------------------

(deftest the-pooled-population-decides-c1
  (if-not (browser?)
    (skip! "the pooled verdict is taken over seven real mounts")
    (let [p (census/pooled @!pool)]
      (println "rf2-hic-083 pooled read-set census:" (pr-str p)
               "per application:" (pr-str @!per-app))

      (is (= 7 (:apps p))
          "all seven applications reached the accumulator — asserted
           rather than inferred from row order")
      (is (pos? (:memberships p))
          (str "NON-EMPTY over the pooled population: " (pr-str p)))
      (is (zero? (:divergence p))
          (str "and every entry-side walk reproduced its cell-side
                landmark, so the pooled figure is the runtime's own
                membership count: " (pr-str p)))

      (is (= {:claimed 150 :memberships 196 :grouped 348 :saved -152
              :shared-entries 3 :shareable 11}
             (select-keys p [:claimed :memberships :grouped :saved
                             :shared-entries :shareable]))
          (str "The pooled census, PINNED. A red here means the witness
                corpus moved, which is exactly when the verdict owes a
                re-read — repair it by re-running and updating
                `docs/design/hicasso/product/readset-group-census.md`,
                never by loosening the row. Measured: " (pr-str p)))

      (is (zero? (:paying-entries p))
          "NOT ONE read-set entry in seven real applications would save a
           membership under grouping. The saving identity is
           (B−1)(R−1) − 1, so an entry pays only when several boundaries
           read an identical set of several keys — and the same absence
           of a per-instance parameter that lets a set be shared is what
           keeps that set small.")
      (is (zero? (:non-losing-entries p))
          "and not one would even BREAK EVEN, which is the sharper
           statement: a `B = R = 2` entry saves nothing and is still the
           shape a scheme would be built for, so `:paying-entries` alone
           would have tolerated it. There is no such entry either.")
      (is (neg? (:saved p))
          "grouping is a net COST on the real population, not a small
           gain — reported with a sign so it cannot be read as neutral")
      (is (< (:coalesced p) 0.10)
          "C1, the pre-registered trigger: proceed only if roughly 10% of
           real memberships coalesce. This row is the trigger made
           executable — a witness application that introduced material
           identical-set fan-out reds it, and the question is re-opened
           on the new population rather than on this one.")
      (is (< (:shareable-fraction p) 0.10)
          "and C1 fails on the generous denominator too: the memberships
           living in entries more than one boundary holds — an upper
           bound on what ANY sharing scheme could touch, whatever its
           arithmetic — are themselves under the trigger. The verdict
           does not turn on a choice of denominator."))))
