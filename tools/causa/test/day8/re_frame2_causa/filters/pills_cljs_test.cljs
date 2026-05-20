(ns day8.re-frame2-causa.filters.pills-cljs-test
  "View + wiring tests for `filters/pills.cljs` (rf2-ak4ms).

  The pills view is a pure-hiccup function; tests walk its hiccup
  output rather than mounting to a DOM, matching the shell test's
  approach."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.filters.pills :as pills]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

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
;; (1) Empty filters — no pills, just the add-pill
;; -------------------------------------------------------------------------

(deftest empty-filters-render-only-add-pill
  (causa-setup!)
  (let [tree (pills/pills-view {:filters {:in [] :out []}})]
    (is (some? (find-by-testid tree "rf-causa-ribbon-filters"))
        "cluster always present")
    (is (some? (find-by-testid tree "rf-causa-filter-add"))
        "add-pill always present")
    (is (empty? (find-all-by-testid-prefix tree "rf-causa-filter-pill-"))
        "no pill rows when both buckets are empty")))

;; -------------------------------------------------------------------------
;; (2) IN + OUT pills render with correct testids
;; -------------------------------------------------------------------------

(deftest in-and-out-pills-render-by-index
  (causa-setup!)
  (let [filters {:in  [{:pattern ":auth/*"} {:pattern ":order/*"}]
                 :out [{:pattern ":mouse-move"}]}
        tree    (pills/pills-view {:filters filters})]
    (is (some? (find-by-testid tree "rf-causa-filter-pill-in-0")))
    (is (some? (find-by-testid tree "rf-causa-filter-pill-in-1")))
    (is (some? (find-by-testid tree "rf-causa-filter-pill-out-0")))
    (is (nil? (find-by-testid tree "rf-causa-filter-pill-out-1"))
        "no extra OUT pill")))

(deftest in-pill-shows-pattern-text
  (causa-setup!)
  (let [tree (pills/pills-view {:filters {:in [{:pattern ":auth/*"}]
                                          :out []}})
        pill (find-by-testid tree "rf-causa-filter-pill-in-0")]
    (is (some? pill))
    (is (re-find #":auth/\*" (text-nodes pill))
        "pill renders the pattern")))

;; -------------------------------------------------------------------------
;; (3) Click pill body → dispatches :rf.causa/open-edit-popup
;; -------------------------------------------------------------------------

(deftest pill-body-click-opens-edit-popup
  (causa-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (let [tree (pills/pills-view {:filters {:in [{:pattern ":auth/*"}]
                                              :out []}})
            body (find-by-testid tree "rf-causa-filter-pill-in-0-body")
            handler (:on-click (second body))]
        (is (some? body) "pill body is addressable")
        (when handler (handler nil))))
    (is (some (fn [ev]
                (and (vector? ev)
                     (= :rf.causa/open-edit-popup (first ev))
                     (let [trig (second ev)]
                       (and (= :pill (:source trig))
                            (= :in   (:mode trig))
                            (= 0     (:idx trig))))))
              @dispatches)
        ":rf.causa/open-edit-popup fired with pill trigger payload")))

;; -------------------------------------------------------------------------
;; (4) Click pill `×` → dispatches :rf.causa/remove-filter
;; -------------------------------------------------------------------------

(deftest pill-remove-button-dispatches-remove-filter
  (causa-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (let [tree (pills/pills-view {:filters {:in  []
                                              :out [{:pattern ":mouse-move"}
                                                    {:pattern ":anim-frame"}]}})
            x (find-by-testid tree "rf-causa-filter-pill-out-1-remove")
            handler (:on-click (second x))]
        (is (some? x) "remove button addressable")
        (when handler (handler nil))))
    (is (some #(= [:rf.causa/remove-filter :out 1] %) @dispatches))))

;; -------------------------------------------------------------------------
;; (5) Add-pill click → dispatches open-edit-popup with :add source
;; -------------------------------------------------------------------------

(deftest add-pill-click-opens-empty-edit-popup
  (causa-setup!)
  (let [dispatches (atom [])]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]       (swap! dispatches conj ev) nil)
                                 ([ev _opts] (swap! dispatches conj ev) nil))]
      (let [tree (pills/pills-view {:filters {:in [] :out []}})
            add  (find-by-testid tree "rf-causa-filter-add")
            handler (:on-click (second add))]
        (is (some? add))
        (when handler (handler nil))))
    (is (some (fn [ev]
                (and (vector? ev)
                     (= :rf.causa/open-edit-popup (first ev))
                     (= :add (:source (second ev)))
                     (= :in  (:mode (second ev)))))
              @dispatches)
        ":rf.causa/open-edit-popup fired with :add source + :in default")))

;; -------------------------------------------------------------------------
;; (6) Counts tooltip
;; -------------------------------------------------------------------------

(deftest cluster-tooltip-shows-counts
  (causa-setup!)
  (let [tree (pills/pills-view {:filters {:in  [{:pattern ":a"}
                                                {:pattern ":b"}
                                                {:pattern ":c"}]
                                          :out [{:pattern ":d"}]}})
        cluster (find-by-testid tree "rf-causa-ribbon-filters")
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
    (causa-setup!)
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
        (let [tree (pills/pills-view {:filters {:in [] :out []}})
              add  (find-by-testid tree "rf-causa-filter-add")
              handler (:on-click (second add))]
          (when handler (handler nil)))
        (is (not @prompt-called?)
            "add-pill click must not call window.prompt")
        (finally
          (when (and (exists? js/window) original-prompt)
            (set! (.-prompt js/window) original-prompt)))))))
