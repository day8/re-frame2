(ns re-frame.bench.hicasso.arm1.render-measure-emit-nightly-test
  "SPEC 009's `:render` BUCKET — THE ON HALF (rf2-2rtt6.125).

  The measures actually landing. Every claim below is about entries a
  `PerformanceObserver` would have received and a
  `getEntriesByType('measure')` reader can filter by the `rf:render:`
  prefix, produced by rendering real `defview` boundaries through React.

  ## Run gate — the `:node-test-perf-nightly` build

  Same vehicle core's `re-frame.performance-emit-nightly-test` uses, and
  for the same reason: the brackets are a COMPILE-TIME decision, so the
  only honest way to assert emission is a build with
  `:closure-defines {re-frame.performance/enabled? true
                     re-frame.performance/retain-entries? true}`.
  `:node-test-perf-nightly`'s `:ns-regexp` is `\"-emit-nightly-test$\"`,
  which this ns matches and the per-PR `:node-test` build's
  `\"cljs-test$\"` does not — so no shadow-cljs.edn change was needed to
  wire this in, and the per-PR runner is not asked to time anything.

      cd implementation
      npx shadow-cljs compile node-test-perf-nightly && \\
        node out/node-test-perf-nightly.js

  `retain-entries? true` is what makes a synchronous read possible at
  all: the bracket clears each measure by name right after emit
  (Spec 009 §Observer-first contract), so with retention off a
  `getEntriesByType` snapshot finds an empty buffer — which is the leak
  fix working, and is asserted in `re-frame.performance-cljs-test`.

  ## THIS FILE FAILS RATHER THAN SKIPS IN A FLAG-OFF BUILD

  [[the-perf-flag-is-actually-on]] is first and is not a courtesy: a
  perf-emission suite whose every row is wrapped in `(when
  performance/enabled? …)` reads as a pass in a build where the flag is
  off, which is a gate nobody has watched fire. If this file is ever
  compiled into a runner without the goog-defines, it goes red and says
  which flag is missing.

  ## Why `renderToString`

  React's server renderer invokes the component fn exactly as the DOM
  renderer does, so the bracket — which sits ON that fn — is exercised
  identically, with no browser in the runner. The one thing it cannot
  witness is a `React.memo` bail-out (a server render has no previous
  render to compare against); the property that MATTERS there is
  \"a boundary React did not invoke produces no measure\", and
  [[a-head-react-never-invoked-produces-no-measure]] states exactly
  that, at the level a headless runner can state it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.core :as rf]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def ^:private frame-id ::render-measure-on)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :rm-on/title (fn [db _] (:title db)))

(rf/reg-event :rm-on/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!) (rt/reset-body-runs!))}))

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(defview title-row
  [_]
  [:h1.title (rt/sub [:rm-on/title])])

(defview note-row
  [_]
  [:p.note "below"])

(defview measured-page
  [_]
  [:div.page
   [title-row {}]
   [note-row {}]])

(defview never-rendered-row
  "Minted and never placed in a tree. React never invokes it, so the
  measure stream must never mention it."
  [_]
  [:span.never "unreachable"])

(defview throwing-row
  [_]
  (throw (ex-info "boom" {:rf.error/id ::deliberate})))

(defview throwing-page
  [_]
  [:div.page [throwing-row {}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private render-prefix "rf:render:")

(defn- render-measure-names
  "Every retained measure in the `:render` bucket, in emit order — the
  read a `PerformanceObserver` consumer performs, minus the observer."
  []
  (->> (.getEntriesByType js/performance "measure")
       (map #(.-name %))
       (filterv #(.startsWith % render-prefix))))

(defn- clear-measures! []
  (.clearMeasures js/performance)
  (.clearMarks js/performance))

(defn- rf-mark-count
  "Marks whose name starts with `rf:`. The options-bag measure form
  allocates ZERO of them (Spec 009 §What gets bracketed), and a
  `:render` bracket is the newest call site that could break that."
  []
  (->> (.getEntriesByType js/performance "mark")
       (map #(.-name %))
       (filter #(.startsWith % "rf:"))
       count))

(defn- entry-named [nm]
  (->> (.getEntriesByType js/performance "measure")
       (filter #(= nm (.-name %)))
       first))

(defn- fresh! []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:rm-on/seed]))
  frame-id)

(defn- server-html [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(def ^:private page-name
  "re-frame.bench.hicasso.arm1.render-measure-emit-nightly-test/measured-page")
(def ^:private title-name
  "re-frame.bench.hicasso.arm1.render-measure-emit-nightly-test/title-row")
(def ^:private note-name
  "re-frame.bench.hicasso.arm1.render-measure-emit-nightly-test/note-row")

;; ---------------------------------------------------------------------------
;; 0 — the runner is the runner this file claims to need
;; ---------------------------------------------------------------------------

(deftest the-perf-flag-is-actually-on
  (testing "This suite asserts EMISSION, and emission is a compile-time
            decision. A build without the goog-defines would make every
            row below vacuous, so the flags are a row rather than a
            precondition nobody checks."
    (is performance/enabled?
        (str "re-frame.performance/enabled? is FALSE in this runner. This ns "
             "belongs to the :node-test-perf-nightly build "
             "(:closure-defines {re-frame.performance/enabled? true "
             "re-frame.performance/retain-entries? true}); run it there."))
    (is performance/retain-entries?
        (str "re-frame.performance/retain-entries? is FALSE, so each measure is "
             "cleared right after emit and a synchronous getEntriesByType read "
             "finds nothing. Flip it for this runner."))))

;; ---------------------------------------------------------------------------
;; 1 — one measure per boundary render, named by the head
;; ---------------------------------------------------------------------------

(deftest each-rendered-boundary-emits-one-prefixed-render-measure
  (testing "The bead's acceptance clause: a page of N distinct `defview`
            heads produces `rf:render:<view-id>` measures a consumer
            filters by the `rf:render:` prefix, with ids matching the
            pinned naming rule (the head's displayName)."
    (fresh!)
    (clear-measures!)
    (rt/reset-body-runs!)
    (let [html  (server-html [measured-page {}])
          names (render-measure-names)]
      (is (re-find #"quarterly" html) "the page really rendered")
      (is (= 3 (rt/body-runs)) "three boundary bodies ran")
      (is (= #{page-name title-name note-name}
             (set (map #(subs % (count render-prefix)) names)))
          "one entry per rendered head, each named by its displayName")
      (is (= 3 (count names)) "and exactly one entry each — no double-emit")
      (is (every? #(.startsWith % render-prefix) names)
          "every entry is prefix-filterable"))))

(deftest the-measure-carries-a-duration-and-allocates-no-marks
  (testing "The entry shape Spec 009 documents: a `measure` with a
            `startTime` and a `duration`, and — because the bracket uses
            the options-bag form — no `mark` entries at all."
    (fresh!)
    (clear-measures!)
    (server-html [measured-page {}])
    (let [e (entry-named (str render-prefix page-name))]
      (is (some? e) "the page's entry is present")
      (is (= "measure" (.-entryType e)))
      (is (number? (.-duration e)))
      (is (>= (.-duration e) 0) "a duration, not a sentinel"))
    (is (zero? (rf-mark-count))
        "the :render bracket allocates no rf: marks")))

;; ---------------------------------------------------------------------------
;; 2 — it is PER RENDER, not per mint
;; ---------------------------------------------------------------------------

(deftest a-second-render-pass-emits-a-second-measure-per-head
  (testing "A `:render` entry is a render, so the count moves with render
            passes and not with how many heads were minted. Three passes
            over three heads is nine entries — and the body-run counter,
            which is bumped somewhere else entirely, agrees."
    (fresh!)
    (clear-measures!)
    (rt/reset-body-runs!)
    (dotimes [_ 3]
      (server-html [measured-page {}]))
    (is (= 9 (rt/body-runs)))
    (is (= 9 (count (render-measure-names)))
        "one measure per body run — the fence never retried, so the two
         counters are the same number arrived at two ways")
    (is (= 3 (count (filter #(= % (str render-prefix title-name))
                            (render-measure-names))))
        "and the per-head count is three")))

;; ---------------------------------------------------------------------------
;; 3 — a boundary React never invoked emits nothing
;; ---------------------------------------------------------------------------

(deftest a-head-react-never-invoked-produces-no-measure
  (testing "HD-028's rider on the measure stream. The bracket sits on the
            component fn, BELOW React's memo comparator, so a boundary
            React skips is a boundary that emits nothing — the same law
            that makes `body-runs` a measurement of adoption rather than
            an inference from the memo. Stated here as the property a
            headless runner can state: a minted head that no tree
            contains is never mentioned."
    (fresh!)
    (clear-measures!)
    (server-html [measured-page {}])
    (let [names (render-measure-names)]
      (is (seq names) "the page did emit — this row is not vacuous")
      (is (not-any? #(.includes % "never-rendered-row") names)
          "and the head React never invoked is absent from the stream"))))

;; ---------------------------------------------------------------------------
;; 4 — the unhappy path still reports
;; ---------------------------------------------------------------------------

(deftest a-throwing-body-still-emits-its-measure
  (testing "Spec 009's try/finally clause: observability does not go
            silent on the unhappy path. The throw propagates — React's
            server renderer re-raises it — and the partial run's measure
            is on the stream, for the throwing boundary AND for the
            parent whose body had already returned when the child ran."
    (fresh!)
    (clear-measures!)
    (let [thrown (atom nil)]
      (try
        (server-html [throwing-page {}])
        (catch :default e (reset! thrown e)))
      (is (some? @thrown) "the exception propagated")
      (let [names (render-measure-names)]
        (is (some #(.endsWith % "/throwing-row") names)
            "the throwing boundary's measure was emitted from the finally")
        (is (some #(.endsWith % "/throwing-page") names)
            "and so was its parent's")))))

;; ---------------------------------------------------------------------------
;; 5 — the frame-prop twin is wired too
;; ---------------------------------------------------------------------------

(deftest the-frame-prop-boundary-reports-on-the-same-channel
  (testing "`mint-frame-prop-view!` is the arm's second view-substrate
            wrapper (rf2-2rtt6.39). A `:render` bucket wired to one mint
            and not the other would report half a page, so the twin gets
            its own row rather than an assumption."
    (fresh!)
    (clear-measures!)
    (rt/reset-body-runs!)
    (let [row  (rt/mint-frame-prop-view!
                 "frame-prop/measured-row"
                 (fn [_] [:li.fp (str (rt/sub [:rm-on/title]))]))
          html (server-html [row {}])]
      (is (re-find #"quarterly" html) "the frame-fed boundary rendered")
      (is (= 1 (rt/body-runs)))
      (is (= [(str render-prefix "frame-prop/measured-row")]
             (render-measure-names))
          "and it emitted exactly one entry, named the same way"))))
