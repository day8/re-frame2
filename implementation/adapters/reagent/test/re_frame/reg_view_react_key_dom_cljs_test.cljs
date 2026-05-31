(ns re-frame.reg-view-react-key-dom-cljs-test
  "DOM-level companion to `reg-view-react-key-cljs-test`: mounting a
  reg-view that renders a seq of keyed reg-views (the `(for [n …]
  ^{:key n} [child n])` idiom) under a REAL React reconciliation commit
  must NOT emit React's 'Encountered two children with the same key'
  warning. Per rf2-1anbp.

  The element-level sibling pins that `.-key` survives
  `reagent.core/as-element`; this file pins the end-to-end consequence —
  React's reconciler, fed the rendered tree, sees distinct keys and
  stays quiet. The failing mode (rf2-1anbp) was a `console.error`
  collision warning on mount where every list child fell back to the
  same munged component name as its key.

  Browser-only — a real `react-dom` commit is required to drive
  reconciliation and the key-collision dev warning. The `-dom-cljs-test`
  suffix opts this file into the `:browser-test` build; `:node-test`
  loads it too (matches `cljs-test$`) and the mount branch gates on
  `(browser?)`, returning a trivially-true assertion under :node-test
  where `js/document` is absent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(reg-view ^{:rf/id :rf.keydom/row} keydom-row [n]
  [:div {:data-testid (str "keydom-row-" n)} "row-" n])

(reg-view ^{:rf/id :rf.keydom/heading} keydom-heading [label]
  [:div "h-" label])

;; The ladder shape from the bead's repro: a seq mixing two reg-view
;; kinds, each keyed at the call site.
(def ^:private ladder
  [[:heading "Events"] [:row 1] [:row 2] [:row 3]
   [:heading "Errors"] [:row 4] [:row 5]])

(reg-view ^{:rf/id :rf.keydom/root} keydom-root []
  [:div
   (for [[kind v] ladder]
     (if (= :heading kind)
       ^{:key (str "h-" v)} [keydom-heading v]
       ^{:key v} [keydom-row v]))])

(deftest mounting-seq-of-keyed-regviews-emits-no-collision-warning
  (testing "rendering a seq of reg-views each with ^{:key} under a real
            React commit emits NO 'same key' reconciliation warning, and
            every keyed child is present in the DOM (rf2-1anbp)"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (let [errs (atom [])
            orig (.-error js/console)]
        (set! (.-error js/console)
              (fn [& args]
                (swap! errs conj (apply str args))
                ;; Forward so other harness diagnostics are not swallowed.
                (.apply orig js/console (to-array args))))
        (let [render-fn (rf/view :rf.keydom/root)
              node      (.createElement js/document "div")
              root      (rdc/create-root node)]
          (try
            (react-dom/flushSync (fn [] (rdc/render root [render-fn])))
            ;; All five keyed rows committed (no child dropped by a
            ;; key collision).
            (is (= 5 (.-length (.querySelectorAll node "[data-testid^='keydom-row-']")))
                "every keyed reg-view child is present in the DOM")
            (is (not-any? #(re-find #"same key" %) @errs)
                (str "no React key-collision warning; console.error saw: "
                     (pr-str @errs)))
            (finally
              (set! (.-error js/console) orig)
              (try (rdc/unmount root) (catch :default _ nil)))))))))
