(ns day8.re-frame2-xray.filters.pills-cljs-test
  "View + wiring tests for `filters/pills.cljs` (rf2-ak4ms).

  The pills view is a pure-hiccup function; tests walk its hiccup
  output rather than mounting to a DOM, matching the shell test's
  approach."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.filters.pills :as pills]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(defn- xray-init! []
  (xray-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- hiccup walker ------------------------------------------------------

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- text-nodes [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

;; -------------------------------------------------------------------------
;; (1) Empty filters — no committed pills in the cluster
;;
;; rf2-3f2di A5 — the add(+) affordance moved OUT of `pills-view` (which
;; now renders ONLY committed pills, on bar-2) up to the chrome ribbon
;; (bar-1). `pills-view` on empty buckets renders an empty cluster; the
;; add-pill is exercised standalone via `pills/add-pill`.
;; -------------------------------------------------------------------------

(deftest empty-filters-render-empty-cluster
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in [] :out []}})]
    (is (some? (find-by-testid tree "rf-xray-ribbon-filters"))
        "cluster element always present")
    (is (nil? (find-by-testid tree "rf-xray-filter-add"))
        "add-pill is NOT part of the committed-pills cluster (moved to bar-1)")
    (is (empty? (find-all-by-testid-prefix tree "rf-xray-filter-pill-"))
        "no pill rows when both buckets are empty")))

(deftest add-pill-renders-standalone
  (xray-setup!)
  (let [tree (pills/add-pill rf/dispatch)]
    (is (some? (find-by-testid tree "rf-xray-filter-add"))
        "add-pill renders the `[ + ]` affordance standalone (bar-1 mount)")))

;; -------------------------------------------------------------------------
;; (2) IN + OUT pills render with correct testids
;; -------------------------------------------------------------------------

(deftest in-and-out-pills-render-by-index
  (xray-setup!)
  (let [filters {:in  [{:pattern ":auth/*"} {:pattern ":order/*"}]
                 :out [{:pattern ":mouse-move"}]}
        tree    (pills/pills-view rf/dispatch {:filters filters})]
    (is (some? (find-by-testid tree "rf-xray-filter-pill-in-0")))
    (is (some? (find-by-testid tree "rf-xray-filter-pill-in-1")))
    (is (some? (find-by-testid tree "rf-xray-filter-pill-out-0")))
    (is (nil? (find-by-testid tree "rf-xray-filter-pill-out-1"))
        "no extra OUT pill")))

(deftest in-pill-shows-pattern-text
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in [{:pattern ":auth/*"}]
                                          :out []}})
        pill (find-by-testid tree "rf-xray-filter-pill-in-0")]
    (is (some? pill))
    (is (re-find #":auth/\*" (text-nodes pill))
        "pill renders the pattern")))

(deftest pill-body-has-no-leading-mode-glyph
  (testing "rf2-t2xba — the Figma authority pill is `[label] [trailing ×]`;
            the prior `+` (include) / `×` (exclude) LEADING glyph prefix on
            the pill BODY was a drift, retired here. The border colour
            carries the include/exclude signal. The body must NOT start
            with `+` or `×`; the trailing remove-button is its own element
            (`-remove` testid) and is unaffected."
    (xray-setup!)
    (let [tree    (pills/pills-view rf/dispatch
                    {:filters {:in  [{:pattern ":auth/*"}]
                               :out [{:pattern ":mouse-move"}]}})
          in-body (find-by-testid tree "rf-xray-filter-pill-in-0-body")
          out-body (find-by-testid tree "rf-xray-filter-pill-out-0-body")
          in-text  (text-nodes in-body)
          out-text (text-nodes out-body)]
      (is (some? in-body))
      (is (some? out-body))
      (is (not (re-find #"^\s*\+" in-text))
          "include pill body does NOT start with `+`")
      (is (not (re-find #"^\s*×" out-text))
          "exclude pill body does NOT start with `×`")
      (is (re-find #":auth/\*" in-text)
          "include pill still shows the pattern label")
      (is (re-find #":mouse-move" out-text)
          "exclude pill still shows the pattern label"))))

(deftest pills-use-green-include-red-exclude-borders
  (testing "rf2-3f2di A6 — include (`:in`) pills are GREEN-bordered
            (`:success` = reference --devtools-success) and exclude
            (`:out`) pills are RED-bordered (`:error` =
            --devtools-error), each with a transparent background and a
            tone-coloured border + a remove `×`, per the authority
            reference events-ribbon."
    (xray-setup!)
    (let [tree   (pills/pills-view rf/dispatch {:filters {:in  [{:pattern ":auth/*"}]
                                              :out [{:pattern ":mouse-move"}]}})
          in     (find-by-testid tree "rf-xray-filter-pill-in-0")
          out    (find-by-testid tree "rf-xray-filter-pill-out-0")
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
      (is (some? (find-by-testid tree "rf-xray-filter-pill-in-0-remove"))
          "include pill has a remove `×`")
      (is (some? (find-by-testid tree "rf-xray-filter-pill-out-0-remove"))
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
            body (find-by-testid tree "rf-xray-filter-pill-in-0-body")
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
            x (find-by-testid tree "rf-xray-filter-pill-out-1-remove")
            handler (:on-click (second x))]
        (is (some? x) "remove button addressable")
        (when handler (handler nil))))
    (is (some #(= [:rf.xray/remove-filter :out 1] %) @dispatches))))

;; -------------------------------------------------------------------------
;; (5) Add-pill click → dispatches open-edit-popup with :add source
;; -------------------------------------------------------------------------

(deftest add-pill-click-opens-empty-edit-popup
  (xray-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      ;; rf2-3f2di A5 — the add-pill is now a standalone bar-1 affordance.
      (let [tree (pills/add-pill rf/dispatch)
            add  (find-by-testid tree "rf-xray-filter-add")
            handler (:on-click (second add))]
        (is (some? add))
        (when handler (handler nil))))
    (is (some (fn [ev]
                (and (vector? ev)
                     (= :rf.xray/open-edit-popup (first ev))
                     (= :add (:source (second ev)))
                     (= :in  (:mode (second ev)))))
              @dispatches)
        ":rf.xray/open-edit-popup fired with :add source + :in default")))

;; -------------------------------------------------------------------------
;; (6) Counts tooltip
;; -------------------------------------------------------------------------

(deftest cluster-tooltip-shows-counts
  (xray-setup!)
  (let [tree (pills/pills-view rf/dispatch {:filters {:in  [{:pattern ":a"}
                                                {:pattern ":b"}
                                                {:pattern ":c"}]
                                          :out [{:pattern ":d"}]}})
        cluster (find-by-testid tree "rf-xray-ribbon-filters")
        title   (:title (second cluster))]
    (is (some? cluster))
    (is (re-find #"IN: 3 patterns" title))
    (is (re-find #"OUT: 1 pattern" title)
        "singular 'pattern' for count = 1")))

;; -------------------------------------------------------------------------
;; (7) window.prompt stub is gone (regression for rf2-ak4ms)
;; -------------------------------------------------------------------------

(deftest add-pill-handler-no-longer-calls-window-prompt
  (testing "rf2-ak4ms — replacing the #1397 stub means clicking [+]
            no longer triggers a `js/window.prompt` call. We assert
            this by stubbing prompt to throw; the click handler must
            complete without calling it (the click dispatches the
            open-edit-popup event instead)."
    (xray-setup!)
    (let [prompt-called? (atom false)
          original-prompt (when (and (exists? js/window)
                                     (.-prompt js/window))
                            (.-prompt js/window))]
      (when (exists? js/window)
        (set! (.-prompt js/window)
              (fn [& _]
                (reset! prompt-called? true)
                (throw (js/Error. "window.prompt called — stub regression")))))
      (try
        (let [tree (pills/pills-view rf/dispatch {:filters {:in [] :out []}})
              add  (find-by-testid tree "rf-xray-filter-add")
              handler (:on-click (second add))]
          (when handler (handler nil)))
        (is (not @prompt-called?)
            "add-pill click must not call window.prompt")
        (finally
          (when (and (exists? js/window) original-prompt)
            (set! (.-prompt js/window) original-prompt)))))))
