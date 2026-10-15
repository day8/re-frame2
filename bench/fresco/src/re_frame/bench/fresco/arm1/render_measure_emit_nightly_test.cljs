(ns re-frame.bench.fresco.arm1.render-measure-emit-nightly-test
  "Flag-on renders emit one correctly named measure per body, including failures and frame-prop views."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.mount :as rf.bench.fresco.arm1.mount]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.fresco.arm1.lang :refer [defview]]))

(def ^:private frame-id ::render-measure-on)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :rm-on/title (fn [db _] (:title db)))

(rf/reg-event :rm-on/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!) (rf.bench.fresco.arm1.runtime/reset-body-runs!))}))

;; The page

(defview title-row
  [_]
  [:h1.title (rf.bench.fresco.arm1.runtime/sub [:rm-on/title])])

(defview note-row
  [_]
  [:p.note "below"])

(defview measured-page
  [_]
  [:div.page
   [title-row {}]
   [note-row {}]])

(defview throwing-row
  [_]
  (throw (ex-info "boom" {:rf.error/id ::deliberate})))

(defview throwing-page
  [_]
  [:div.page [throwing-row {}]])

;; Helpers

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
  `:render` bracket is one more call site that could break that."
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
    (rf.bench.fresco.arm1.mount/provider frame-id (rf.bench.fresco.front.codec/root-element frame-id hiccup))))

(def ^:private page-name
  "re-frame.bench.fresco.arm1.render-measure-emit-nightly-test/measured-page")
(def ^:private title-name
  "re-frame.bench.fresco.arm1.render-measure-emit-nightly-test/title-row")
(def ^:private note-name
  "re-frame.bench.fresco.arm1.render-measure-emit-nightly-test/note-row")

;; 1 — one measure per boundary render, named by the head

(deftest each-rendered-boundary-emits-one-prefixed-render-measure
  (testing "The acceptance clause: a page of N distinct `defview`
            heads produces `rf:render:<view-id>` measures a consumer
            filters by the `rf:render:` prefix, with ids matching the
            pinned naming rule (the head's displayName)."
    (fresh!)
    (clear-measures!)
    (rf.bench.fresco.arm1.runtime/reset-body-runs!)
    (let [html  (server-html [measured-page {}])
          names (render-measure-names)]
      (is (= [true 3 {page-name 1 title-name 1 note-name 1}]
             [(some? (re-find #"quarterly" html))
              (rf.bench.fresco.arm1.runtime/body-runs)
              (frequencies (map #(subs % (count render-prefix)) names))])
          "the page really rendered, three boundary bodies ran, and there is
           exactly one entry per rendered head — no double-emit — each named
           by its displayName")
      (dotimes [_ 2] (server-html [measured-page {}]))
      (is (= [9 {page-name 3 title-name 3 note-name 3}]
           [(rf.bench.fresco.arm1.runtime/body-runs)
            (frequencies (map #(subs % (count render-prefix)) (render-measure-names)))])
        "one measure per body run, three per head — the fence never
         retried, so the two counters are the same number arrived at two
         ways"))))

(deftest the-measure-carries-a-duration-and-allocates-no-marks
  (testing "The entry shape Spec 009 documents: a `measure` with a
            `startTime` and a `duration`, and — because the bracket uses
            the options-bag form — no `mark` entries at all."
    (fresh!)
    (clear-measures!)
    (server-html [measured-page {}])
    (let [d (.-duration (entry-named (str render-prefix page-name)))]
      (is (= [true 0] [(and (number? d) (>= d 0)) (rf-mark-count)])
          "the page's entry carries a duration, not a sentinel, and the
           :render bracket allocates no rf: marks"))))
;; 3 — the unhappy path still reports

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
      (let [names (render-measure-names)]
        (is (= [true true true]
               [(some? @thrown)
                (boolean (some #(.endsWith % "/throwing-row") names))
                (boolean (some #(.endsWith % "/throwing-page") names))])
            "the exception propagated, the throwing boundary's measure was
             emitted from the finally, and so was its parent's")))))

;; 4 — the frame-prop twin is wired too

(deftest the-frame-prop-boundary-reports-on-the-same-channel
  (testing "`mint-frame-prop-view!` is the arm's second view-substrate
            wrapper. A `:render` bucket wired to one mint
            and not the other would report half a page, so the twin gets
            its own row rather than an assumption."
    (fresh!)
    (clear-measures!)
    (rf.bench.fresco.arm1.runtime/reset-body-runs!)
    (let [row  (rf.bench.fresco.arm1.runtime/mint-frame-prop-view!
                 "frame-prop/measured-row"
                 (fn [_] [:li.fp (str (rf.bench.fresco.arm1.runtime/sub [:rm-on/title]))]))
          html (server-html [row {}])]
      (is (= [true 1 [(str render-prefix "frame-prop/measured-row")]]
             [(some? (re-find #"quarterly" html))
              (rf.bench.fresco.arm1.runtime/body-runs)
              (render-measure-names)])
          "the frame-fed boundary rendered, and it emitted exactly one
           entry, named the same way"))))
