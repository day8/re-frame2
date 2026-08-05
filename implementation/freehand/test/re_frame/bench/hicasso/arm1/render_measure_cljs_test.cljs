(ns re-frame.bench.hicasso.arm1.render-measure-cljs-test
  "SPEC 009's `:render` BUCKET — THE OFF HALF, AND THE ID RULE
  (rf2-2rtt6.125).

  `defview` boundaries now report through Spec 009's Performance
  channel: [[re-frame.bench.hicasso.arm1.runtime/mint-view!]] wraps the
  component fn React calls in `(performance/mark-and-measure :render
  view-name …)`, so a build that flips
  `re-frame.performance/enabled?` gets one `rf:render:<view-id>`
  User-Timing measure per boundary render.

  This file is the half that runs in the ORDINARY `:node-test` build,
  where the flag is at its `goog-define` default of `false`. It asserts
  two things a flag-off build is the only place to assert:

  1. **The ordinary render path is unchanged.** A page of boundaries
     renders, its markup is what it always was, its bodies ran — and the
     User-Timing stream is empty. That is the runtime expression of the
     elision contract (the `:advanced` bundle grep is
     `scripts/check-perf-bundle.cjs`'s job; a broken DCE would leave the
     body live and THIS row is the runtime signal).
  2. **The id rule**, which is a property of the mint and needs no flag:
     the measure name is `rf:render:` + the head's `displayName`, and
     `defview` makes that `\"<ns>/<sym>\"`. One identifier serves the
     measure stream and React DevTools.

  ## The row that stops row 1 being vacuous

  \"No `rf:` measures landed\" is trivially true of a page that never
  rendered, so every off-path assertion below is paired with a
  [[re-frame.bench.hicasso.arm1.runtime/body-runs]] delta and a markup
  assertion. A regression that broke the render would otherwise read as
  a pass.

  ## Where the ON half lives

  `re-frame.bench.hicasso.arm1.render-measure-emit-nightly-test`, which
  runs under the `:node-test-perf-nightly` shadow-cljs build (both perf
  goog-defines flipped true) — the same runner and the same naming
  convention core's `re-frame.performance-emit-nightly-test` uses. Perf
  assertions do not ride the per-PR runner; the emission is witnessed
  there and the ABSENCE is witnessed here.

  ## Why `renderToString` and not a DOM

  The claim is about the component fn React invokes, and React's server
  renderer invokes it exactly as the DOM renderer does — the shell's
  `useSyncExternalStore` answers from its server snapshot
  ([[re-frame.bench.hicasso.ssr.entry]]). That keeps the row in the
  headless `:node-test` build rather than behind a browser, and it
  doubles as the bead's SSR clause: a server pass leaves no durable
  registration behind a bracket."
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

(def ^:private frame-id ::render-measure-off)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :rm/title (fn [db _] (:title db)))

(rf/reg-event :rm/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!) (rt/reset-body-runs!))}))

;; ---------------------------------------------------------------------------
;; The page — two boundaries, so "one measure per boundary" has something
;; to be one of
;; ---------------------------------------------------------------------------

(defview title-row
  "A boundary that reads, so its body is a real body and not a constant."
  [_]
  [:h1.title (rt/sub [:rm/title])])

(defview measured-page
  "The outer boundary. Two heads render per pass, which is what makes the
  count in the ON half a count."
  [_]
  [:div.page
   [title-row {}]
   [:p.note "below"]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- rf-measure-names
  "Every retained User-Timing measure whose name starts with `rf:`."
  []
  (->> (.getEntriesByType js/performance "measure")
       (map #(.-name %))
       (filterv #(.startsWith % "rf:"))))

(defn- clear-measures! []
  (when (exists? js/performance.clearMeasures)
    (.clearMeasures js/performance))
  (when (exists? js/performance.clearMarks)
    (.clearMarks js/performance)))

(defn- fresh! []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:rm/seed]))
  frame-id)

(defn- server-html
  "The page under React's server renderer — the same component fn the DOM
  renderer calls, minus the browser."
  [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

;; ---------------------------------------------------------------------------
;; 1 — the id rule (no flag needed; it is a property of the mint)
;; ---------------------------------------------------------------------------

(deftest the-render-entry-id-is-the-heads-display-name
  (testing "`defview` stamps `\"<ns>/<sym>\"` as the head's displayName, and
            that same string is the `<view-id>` in `rf:render:<view-id>` —
            so the identifier a consumer filters the User-Timing stream on
            and the identifier React DevTools shows are one string, not two
            that happen to agree (Spec 009 §Naming convention)."
    (is (= "re-frame.bench.hicasso.arm1.render-measure-cljs-test/title-row"
           (.-displayName title-row)))
    (is (= "re-frame.bench.hicasso.arm1.render-measure-cljs-test/measured-page"
           (.-displayName measured-page)))
    (is (= "rf:render:re-frame.bench.hicasso.arm1.render-measure-cljs-test/title-row"
           (performance/build-name :render (.-displayName title-row))))
    (is (= "rf:render:re-frame.bench.hicasso.arm1.render-measure-cljs-test/measured-page"
           (performance/build-name :render (.-displayName measured-page))))))

(deftest the-id-rule-holds-for-a-mint-view-name-without-a-namespace
  (testing "`mint-view!` is also called directly (the frame-prop rows, the
            probes) with a bare name. `build-name`'s `:else` arm stringifies
            it verbatim, so the shape stays `rf:render:<id>` with no
            namespace invented — the same contract a non-namespaced
            `reg-view` keyword id gets."
    (let [head (rt/mint-view! "bare-row" (fn [_] [:span "x"]))]
      (is (= "bare-row" (.-displayName head)))
      (is (= "rf:render:bare-row"
             (performance/build-name :render (.-displayName head)))))))

;; ---------------------------------------------------------------------------
;; 2 — the off path: the render happens, and nothing is measured
;; ---------------------------------------------------------------------------

(deftest a-flag-off-build-renders-the-page-and-emits-no-render-measure
  (testing "With `re-frame.performance/enabled?` at its goog-define default
            the `:render` bracket is shape-equivalent to a bare call. The
            markup and the body-run count say the render REALLY HAPPENED —
            without them 'no rf: measures landed' is a statement about a
            page that never rendered — and the empty measure stream says
            the bracket added nothing to it."
    (when-not performance/enabled?
      (fresh!)
      (clear-measures!)
      (rt/reset-body-runs!)
      (let [html (server-html [measured-page {}])]
        (is (re-find #"quarterly" html)
            "the page rendered its subscription value — the render is real")
        (is (re-find #"class=\"page\"" html)
            "and its markup is the ordinary markup")
        (is (= 2 (rt/body-runs))
            "two boundary bodies ran — the page and the row")
        (is (= [] (rf-measure-names))
            "and NOTHING reached the User-Timing stream")))))

(deftest a-flag-off-build-emits-no-render-measure-across-repeated-renders
  (testing "The bracket is per-render, so a single-render row cannot
            distinguish 'elided' from 'emitted once and cleared'. Ten
            renders would leave ten retained entries in a flag-on build
            with retention on; here they leave none, and the body-run
            count proves ten renders were asked for."
    (when-not performance/enabled?
      (fresh!)
      (clear-measures!)
      (rt/reset-body-runs!)
      (dotimes [_ 5]
        (server-html [measured-page {}]))
      (is (= 10 (rt/body-runs)) "five passes over two boundaries")
      (is (= [] (rf-measure-names))
          "and the retained buffer is still empty"))))

(deftest a-server-pass-leaves-no-retained-registration-behind-the-bracket
  (testing "The bead's SSR clause. A `renderToString` pass runs bodies and
            mints read-set entries, but React never calls `subscribe`, so
            nothing is committed. The bracket must not change that — it
            writes a measure and (retention off) clears it, and holds no
            reference of its own. `stats` is the arm's own enumeration of
            what survived."
    (when-not performance/enabled?
      (fresh!)
      (clear-measures!)
      (server-html [measured-page {}])
      (is (zero? (:boundaries (rt/stats)))
          "a server pass committed nothing — no registration holds a reader slot")
      (is (= [] (rf-measure-names))
          "and left no User-Timing entry either"))))
