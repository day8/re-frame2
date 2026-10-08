(ns re-frame.adapter.reagent-slim-reg-view-variadic-args-dom-cljs-test
  "reagent-slim half of `re-frame.reg-view-variadic-args-dom-cljs-test`: a
  registered view forwards ANY number of positional arguments, keyword-variadic
  ones included, with its frame context intact.

  reagent-slim reaches the registered head through `cljs.core/apply`
  (`reagent2.impl.component/wrap-render`) rather than stock Reagent's
  `.apply`. On an IFn OBJECT, `apply` runs the fixed arity table up to twenty
  arguments and falls back to the object's `.apply` beyond it, so the same
  twenty-one-argument cliff applies — which is why this substrate needs its own
  rows rather than inheriting the stock-Reagent proof.

  The `-dom-cljs-test` suffix selects the `:browser-test` build; `:node-test`
  loads the namespace too, where every row takes the no-DOM branch."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def ^:private frame-id ::frame)

;; Registered at namespace load, BEFORE the fixture is built, so they sit in the
;; registrar baseline the fixture restores before every test.

(reg-view ^{:rf/id ::kw-form-1} kw-form-1 [& {:keys [label p13]}]
  [:div {:data-testid "slim-kw-form-1"}
   (str label "|" p13 "|" (pr-str (rf/current-frame-id)))])

(reg-view ^{:rf/id ::kw-form-2} kw-form-2 [& {outer-label :label}]
  (fn [& {:keys [label p13]}]
    [:div {:data-testid "slim-kw-form-2"} (str outer-label "/" label "|" p13)]))

(reg-view ^{:rf/id ::positional} positional [& args]
  [:div {:data-testid "slim-positional"} (str (count args) "|" (last args))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :ambient-frame nil}))

(defn- kw-args
  "`pairs` keyword/value pairs — `:label \"visible\"` first, then `:p0 0` …
  — flattened to `(* 2 pairs)` scalars."
  [pairs]
  (mapcat identity
          (cons [:label "visible"]
                (map (fn [i] [(keyword (str "p" i)) i]) (range (dec pairs))))))

(defn- positional-args [n]
  (map #(str "a" %) (range n)))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- mounted-text
  "Mount `[(rf/view view-id) & args]` under a `frame-provider` for `frame-id`
  on a reagent-slim root, read `testid`'s text, unmount. Returns
  `{:text … :error <the uncaught render error, or nil>}`."
  [view-id args testid]
  (let [error (atom nil)
        node  (.createElement js/document "div")
        root  (rdc/create-root node #js {:onUncaughtError (fn [e _info] (reset! error e))})]
    (.appendChild (.-body js/document) node)
    (try
      (react-dom/flushSync
        #(rdc/render root [rf/frame-provider {:frame frame-id}
                           (into [(rf/view view-id)] args)]))
      {:text  (some-> (.querySelector node (str "[data-testid='" testid "']"))
                      (.-textContent))
       :error @error}
      (finally
        (try (react-dom/flushSync #(rdc/unmount root)) (catch :default _ nil))
        (.remove node)))))

(defn- assert-renders [view-id args testid expected]
  (rf/make-frame {:id frame-id :doc "reagent-slim variadic reg-view fixture"})
  (let [{:keys [text error]} (mounted-text view-id args testid)]
    (is (nil? error)
        (str (count args) " arguments: the render raised "
             (pr-str (when error (or (ex-message error) (str error))))))
    (is (= expected text)
        (str (count args) " arguments: the mounted view rendered " (pr-str text)))))

(deftest slim-kw-form-1-renders-its-label-and-frame-with-thirty-scalars
  (testing "a Form-1 `reg-view` mounted with 15 keyword/value pairs receives
            every pair and reads the provider's frame through React context"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (assert-renders ::kw-form-1 (kw-args 15) "slim-kw-form-1"
                      (str "visible|13|" (pr-str frame-id))))))

(deftest slim-kw-form-2-forwards-thirty-scalars-to-outer-and-inner
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises this")
    (assert-renders ::kw-form-2 (kw-args 15) "slim-kw-form-2" "visible/visible|13")))

(deftest slim-boundary-twenty-and-twenty-one-positional-arguments
  (testing "the fixed-arity limit and one past it, where apply falls back to .apply"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (doseq [[n expected] [[20 "20|a19"] [21 "21|a20"]]]
        (assert-renders ::positional (positional-args n) "slim-positional" expected)))))
