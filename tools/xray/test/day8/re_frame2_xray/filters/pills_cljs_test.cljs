(ns day8.re-frame2-xray.filters.pills-cljs-test
  "View + wiring tests for `filters/pills.cljs`.

  The pills view is a pure-hiccup function; tests walk its hiccup
  output rather than mounting to a DOM, matching the shell test's
  approach."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.filters.pills :as pills]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is core `make-reset-runtime-fixture` +
  ;; Xray `reset-all!` in one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker ------------------------------------------------------
;; Tests call `rf.test-helpers/find-by-testid`,
;; `rf.test-helpers/find-by-testid-prefix` and `rf.test-helpers/text-content`
;; directly; there is no Xray walker facade.

;; -------------------------------------------------------------------------
;; (1) Empty filters — no committed pills in the cluster
;;
;; The add buttons live outside `pills-view`, which renders ONLY
;; committed pills, on bar-2. `pills-view` on empty buckets renders an
;; empty cluster.
;; -------------------------------------------------------------------------

(deftest empty-filters-render-empty-cluster
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in [] :out []}})]
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-filters"))
        "cluster element always present")
    (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filter-add"))
        "the chrome add button is NOT part of the committed-pills cluster (it lives on bar-1)")
    (is (empty? (rf.test-helpers/find-by-testid-prefix tree "rf-xray-filter-pill-"))
        "no pill rows when both buckets are empty")))

;; -------------------------------------------------------------------------
;; (2) IN + OUT pills render with correct testids
;; -------------------------------------------------------------------------

(deftest in-and-out-pills-render-by-index
  (xray-setup!)
  (let [filters {:in  [{:pattern ":auth/*"} {:pattern ":order/*"}]
                 :out [{:pattern ":mouse-move"}]}
        tree    (pills/pills-view rf/dispatch {:filters filters})]
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0")))
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-1")))
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-0")))
    (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-1"))
        "no extra OUT pill")))

(deftest in-pill-shows-pattern-text
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in [{:pattern ":auth/*"}]
                                          :out []}})
        pill (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0")]
    (is (some? pill))
    (is (re-find #":auth/\*" (rf.test-helpers/text-content pill))
        "pill renders the pattern")))

(deftest pill-body-has-no-leading-mode-glyph
  (testing "the Figma authority pill is `[label] [trailing ×]`,
            with no `+` (include) / `×` (exclude) LEADING glyph prefix on
            the pill BODY. The border colour
            carries the include/exclude signal. The body must NOT start
            with `+` or `×`; the trailing remove-button is its own element
            (`-remove` testid) and is unaffected."
    (xray-setup!)
    (let [tree    (pills/pills-view rf/dispatch
                    {:filters {:in  [{:pattern ":auth/*"}]
                               :out [{:pattern ":mouse-move"}]}})
          in-body (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0-body")
          out-body (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-0-body")
          in-text  (rf.test-helpers/text-content in-body)
          out-text (rf.test-helpers/text-content out-body)]
      (is (some? in-body))
      (is (some? out-body))
      (is (not (re-find #"^\s*\+" in-text))
          "include pill body does NOT start with `+`")
      (is (not (re-find #"^\s*×" out-text))
          "exclude pill body does NOT start with `×`")
      (is (re-find #":auth/\*" in-text)
          "include pill shows the pattern label")
      (is (re-find #":mouse-move" out-text)
          "exclude pill shows the pattern label"))))

(deftest pills-use-green-include-red-exclude-borders
  (testing "include (`:in`) pills are GREEN-bordered
            (`:success` = reference --devtools-success) and exclude
            (`:out`) pills are RED-bordered (`:error` =
            --devtools-error), each with a transparent background and a
            tone-coloured border + a remove `×`, per the authority
            reference events-ribbon."
    (xray-setup!)
    (let [tree   (pills/pills-view rf/dispatch {:filters {:in  [{:pattern ":auth/*"}]
                                              :out [{:pattern ":mouse-move"}]}})
          in     (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0")
          out    (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-0")
          in-st  (:style (second in))
          out-st (:style (second out))]
      ;; include = green border + green ink + transparent bg.
      (is (= (str "1px solid " (:success tokens)) (:border in-st))
          "include pill is green-bordered (:success)")
      (is (= (:success tokens) (:color in-st))
          "include pill ink is green")
      (is (= "transparent" (:background in-st))
          "include pill background is transparent")
      ;; exclude = red border + red ink + transparent bg.
      (is (= (str "1px solid " (:error tokens)) (:border out-st))
          "exclude pill is red-bordered (:error)")
      (is (= (:error tokens) (:color out-st))
          "exclude pill ink is red")
      (is (= "transparent" (:background out-st))
          "exclude pill background is transparent")
      ;; each carries a remove `×` button.
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0-remove"))
          "include pill has a remove `×`")
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-0-remove"))
          "exclude pill has a remove `×`"))))

;; -------------------------------------------------------------------------
;; (3) Click pill body → dispatches :rf.xray/open-edit-popup
;; -------------------------------------------------------------------------

(deftest pill-body-click-opens-edit-popup
  (xray-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (let [tree (pills/pills-view rf/dispatch {:filters {:in [{:pattern ":auth/*"}]
                                              :out []}})
            body (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-in-0-body")
            handler (:on-click (second body))]
        (is (some? body) "pill body is addressable")
        (when handler (handler nil))))
    (is (some (fn [ev]
                (and (vector? ev)
                     (= :rf.xray/open-edit-popup (first ev))
                     (let [trig (second ev)]
                       (and (= :pill (:source trig))
                            (= :in   (:mode trig))
                            (= 0     (:idx trig))))))
              @dispatches)
        ":rf.xray/open-edit-popup fired with pill trigger payload")))

;; -------------------------------------------------------------------------
;; (4) Click pill `×` → dispatches :rf.xray/remove-filter
;; -------------------------------------------------------------------------

(deftest pill-remove-button-dispatches-remove-filter
  (xray-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (let [tree (pills/pills-view rf/dispatch {:filters {:in  []
                                              :out [{:pattern ":mouse-move"}
                                                    {:pattern ":anim-frame"}]}})
            x (rf.test-helpers/find-by-testid tree "rf-xray-filter-pill-out-1-remove")
            handler (:on-click (second x))]
        (is (some? x) "remove button addressable")
        (when handler (handler nil))))
    (is (some #(= [:rf.xray/remove-filter :out 1] %) @dispatches))))

;; -------------------------------------------------------------------------
;; (5) Counts tooltip
;; -------------------------------------------------------------------------

(deftest cluster-tooltip-shows-counts
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in  [{:pattern ":a"}
                                                {:pattern ":b"}
                                                {:pattern ":c"}]
                                          :out [{:pattern ":d"}]}})
        cluster (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-filters")
        title   (:title (second cluster))]
    (is (some? cluster))
    (is (re-find #"IN: 3 patterns" title))
    (is (re-find #"OUT: 1 pattern" title)
        "singular 'pattern' for count = 1")))

;; -------------------------------------------------------------------------
;; (6) The add buttons never call window.prompt
;;
;; The buttons are taken from the SHELL's own tree — the chrome ribbon's
;; `+ filter` and the events ribbon's `[+]` — so the test clicks whatever
;; the shell mounts, and a button it cannot find fails the test rather
;; than skipping the click.
;;
;; The node lane has no `window`, so the test installs one for the length
;; of the clicks: `window` is `globalThis`, as in a browser, and `prompt`
;; is a recording stub. A call through `window.prompt`, a bare `prompt`
;; or a `window`-guarded branch all land on the stub. The tree is walked
;; BEFORE the stub goes in, so no render path sees a `window` it would
;; take for a browser.
;; -------------------------------------------------------------------------

(def ^:private add-button-testids
  ["rf-xray-filter-add"          ; chrome ribbon `+ filter`
   "rf-xray-filter-add-events"]) ; events ribbon `[+]`

(deftest add-filter-buttons-never-call-window-prompt
  (xray-setup!)
  (let [handlers     (rf/with-frame :rf/xray
                       (let [tree (dynamic-shell-tree/shell-view-tree)]
                         (mapv #(:on-click (second (rf.test-helpers/find-by-testid tree %)))
                               add-button-testids)))
        prompt-calls (atom [])
        dispatches   (atom [])
        global       js/globalThis
        had-window?  (exists? js/window)
        had-prompt?  (some? (.-prompt global))
        original     (.-prompt global)]
    (doseq [[testid handler] (map vector add-button-testids handlers)]
      (is (fn? handler) (str testid " is in the shell tree with a click handler")))
    (when-not had-window? (set! (.-window global) global))
    (set! (.-prompt global) (fn [& args] (swap! prompt-calls conj (vec args)) nil))
    (try
      (with-redefs [rf/dispatch-impl (fn
                                       ([ev]       (swap! dispatches conj ev) nil)
                                       ([ev _opts] (swap! dispatches conj ev) nil))]
        (doseq [handler handlers :when (fn? handler)]
          (handler nil)))
      (finally
        (if had-prompt?
          (set! (.-prompt global) original)
          (js-delete global "prompt"))
        (when-not had-window? (js-delete global "window"))))
    (is (empty? @prompt-calls)
        "no add-filter click calls window.prompt")
    (is (= [[:rf.xray/open-edit-popup {:source :add :mode :in}]
            [:rf.xray/open-edit-popup {:source :add :mode :in}]]
           @dispatches)
        "each click opens the edit popup, empty and defaulted to IN")))
