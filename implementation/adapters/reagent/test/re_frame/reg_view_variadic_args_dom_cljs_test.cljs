(ns re-frame.reg-view-variadic-args-dom-cljs-test
  "A registered view forwards ANY number of positional arguments under stock
  Reagent, keyword-variadic ones included, with its frame context and its
  `displayName` intact.

  WHY THE COUNT MATTERS. Stock Reagent renders a component's argv by calling
  the registered head with `.call` for up to four arguments and with
  `(.apply f this argv-tail)` beyond that (`reagent.impl.component/wrap-render`).
  A head that is an IFn OBJECT rather than a JS function answers `.apply`
  through ClojureScript's IFn emulation, which dispatches on a fixed arity
  table: past twenty arguments it either folds the twenty-first argument into a
  rest seq or throws `Invalid arity: N`. A keyword-variadic view —
  `[& {:keys [label]}]` called with fifteen key/value pairs — is thirty
  scalars, so the count is an ordinary one for migrated v1 code, not an edge.

  What each row is for:

    - `kw-form-1-*` / `kw-form-2-*` — the `reg-view` macro's Form-1 and
      Form-2 shapes, mounted with thirty keyword/value scalars. The Form-2 row
      reads the label in BOTH the once-per-mount outer fn and the inner render
      fn, so a forwarding loss in either half shows.
    - `reg-view-star-form-3-*` — the `reg-view*` outer-callable route that
      the M-11 recipe prescribes for a `create-class` view, with the same
      thirty scalars reaching the class's `:reagent-render`.
    - `boundary-*` — 20, 21 and 22 positional arguments, each its own row,
      because the failure modes either side of twenty-one differ (a 21st
      argument is spread as a seq; 22 or more throws).
    - `head-*` — the head's own contract without a mount: it is a real JS
      function that `.apply` reaches with any argument count, and it still
      carries the `:contextType` metadata Reagent's `fn-to-class` reads.
      These run on both lanes.

  The mounted rows also assert the frame: the view and a registered child read
  the enclosing `frame-provider`'s frame through React context (the fixture's
  `:ambient-frame nil` leaves no dynamic scope to fall back on), and the
  mounted component keeps the view-id `displayName` React DevTools shows.

  The `-dom-cljs-test` suffix selects the `:browser-test` build; `:node-test`
  loads the namespace too, where the mounted rows take the no-DOM branch and
  the `head-*` rows run in full."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.react-test-support :as rf.adapter.react-test-support]
            [re-frame.core :as rf]
            [re-frame.performance :as rf.performance]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(def ^:private frame-id ::frame)

;; ---- the views under test ---------------------------------------------------
;;
;; Registered at namespace load, BEFORE the fixture is built, so they sit in the
;; registrar baseline the fixture restores before every test.

(reg-view ^{:rf/id ::frame-probe} frame-probe []
  [:span {:data-testid "frame-probe"} (pr-str (rf/current-frame-id))])

(reg-view ^{:rf/id ::kw-form-1} kw-form-1 [& {:keys [label p13]}]
  [:div {:data-testid "kw-form-1"}
   [:span {:data-testid "kw-form-1-label"} (str label "|" p13)]
   [:span {:data-testid "kw-form-1-frame"} (pr-str (rf/current-frame-id))]
   [frame-probe]])

(reg-view ^{:rf/id ::kw-form-2} kw-form-2 [& {outer-label :label}]
  (fn [& {:keys [label p13]}]
    [:div {:data-testid "kw-form-2"} (str outer-label "/" label "|" p13)]))

(rf/reg-view* ::reg-view-star-form-3
  (fn [& {outer-label :label}]
    (r/create-class
      {:display-name "variadic-form-3"
       :reagent-render
       (fn [& {:keys [label p13]}]
         [:div {:data-testid "reg-view-star-form-3"}
          (str outer-label "/" label "|" p13)])})))

(reg-view ^{:rf/id ::positional} positional [& args]
  [:div {:data-testid "positional"} (str (count args) "|" (last args))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :ambient-frame nil}))

;; ---- argument builders ------------------------------------------------------

(defn- kw-args
  "`pairs` keyword/value pairs — `:label \"visible\"` first, then `:p0 0` …
  — flattened to `(* 2 pairs)` scalars: the keyword-variadic ABI a v1 caller
  uses, not a one-map replacement."
  [pairs]
  (mapcat identity
          (cons [:label "visible"]
                (map (fn [i] [(keyword (str "p" i)) i]) (range (dec pairs))))))

(defn- positional-args
  "`n` distinct string arguments, `\"a0\"` … `\"a<n-1>\"`."
  [n]
  (map #(str "a" %) (range n)))

;; ---- mounting ---------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- mount
  "Mount `[(rf/view view-id) & args]` under a `frame-provider` for `frame-id`,
  hand `read` the container node while it is mounted, then unmount. Returns
  `{:value <read's result> :error <the uncaught render error, or nil>}`."
  [view-id args read]
  (let [error (atom nil)
        node  (.createElement js/document "div")
        root  (rdc/create-root node #js {:onUncaughtError (fn [e _info] (reset! error e))})]
    (.appendChild (.-body js/document) node)
    (try
      (react-dom/flushSync
        #(rdc/render root [rf/frame-provider {:frame frame-id}
                           (into [(rf/view view-id)] args)]))
      {:value (read node) :error @error}
      (finally
        (try (react-dom/flushSync #(rdc/unmount root)) (catch :default _ nil))
        (.remove node)))))

(defn- text-of [node testid]
  (some-> (.querySelector node (str "[data-testid='" testid "']")) (.-textContent)))

(defn- mounted-text
  "Mount and return `{:text <testid's text> :error <render error>}`."
  [view-id args testid]
  (let [{:keys [value error]} (mount view-id args #(text-of % testid))]
    {:text value :error error}))

(defn- error-message [e]
  (when e (or (ex-message e) (str e))))

(defn- assert-renders [view-id args testid expected]
  (let [{:keys [text error]} (mounted-text view-id args testid)]
    (is (nil? error)
        (str (count args) " arguments: the render raised " (pr-str (error-message error))))
    (is (= expected text)
        (str (count args) " arguments: the mounted view rendered " (pr-str text)))))

(defn- setup! []
  (rf/make-frame {:id frame-id :doc "variadic reg-view fixture"}))

;; ---- reg-view, keyword-variadic, 30 scalars ---------------------------------

(deftest kw-form-1-renders-its-label-with-thirty-scalars
  (testing "a Form-1 `reg-view` declared `[& {:keys [label]}]`, mounted with
            15 keyword/value pairs, receives every pair"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (do (setup!)
          (assert-renders ::kw-form-1 (kw-args 15) "kw-form-1-label" "visible|13")))))

(deftest kw-form-1-with-thirty-scalars-resolves-the-provider-frame
  (testing "the head and a registered child both read the enclosing
            frame-provider's frame through React context"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (do (setup!)
          (let [{:keys [value error]}
                (mount ::kw-form-1 (kw-args 15)
                       (fn [node] {:head  (text-of node "kw-form-1-frame")
                                   :child (text-of node "frame-probe")}))]
            (is (nil? error) (str "the render raised " (pr-str (error-message error))))
            (is (= (pr-str frame-id) (:head value))
                "the variadic head resolves the provider's frame")
            (is (= (pr-str frame-id) (:child value))
                "a frame-reading child of the variadic head resolves the provider's frame"))))))

(deftest kw-form-1-with-thirty-scalars-keeps-its-display-name
  (testing "the mounted component carries the view-id displayName DevTools shows"
    (is (= (rf.performance/entry-id ::kw-form-1) (.-displayName ^js (rf/view ::kw-form-1)))
        "the head is stamped with the view-id's display projection")
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the mount")
      (do (setup!)
          (let [expected (rf.performance/entry-id ::kw-form-1)
                {:keys [value error]}
                (mount ::kw-form-1 (kw-args 15)
                       (fn [node]
                         (rf.adapter.react-test-support/devtools-names-above
                           (.querySelector node "[data-testid='kw-form-1-label']"))))]
            (is (nil? error) (str "the render raised " (pr-str (error-message error))))
            (is (some #{expected} value)
                (str "the mounted component is named " (pr-str expected)
                     "; saw " (pr-str value))))))))

(deftest kw-form-2-forwards-thirty-scalars-to-outer-and-inner
  (testing "a Form-2 `reg-view` — outer and inner both keyword-variadic —
            receives all 15 pairs in the outer fn AND in the inner render fn"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (do (setup!)
          (assert-renders ::kw-form-2 (kw-args 15) "kw-form-2" "visible/visible|13")))))

;; ---- reg-view*, the M-11 Form-3 route ---------------------------------------

(deftest reg-view-star-form-3-forwards-thirty-scalars
  (testing "a `reg-view*` outer callable returning `create-class` receives all
            15 pairs, and so does the class's `:reagent-render`"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (do (setup!)
          (assert-renders ::reg-view-star-form-3 (kw-args 15)
                          "reg-view-star-form-3" "visible/visible|13")))))

;; ---- boundary counts --------------------------------------------------------

(deftest boundary-twenty-and-twenty-one-positional-arguments
  (testing "the fixed-arity limit and one past it"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (doseq [[n expected] [[20 "20|a19"] [21 "21|a20"]]]
        (setup!)
        (assert-renders ::positional (positional-args n) "positional" expected)))))

;; ---- the head's own contract, on both lanes ---------------------------------

(deftest head-is-a-js-function-that-apply-reaches-with-any-count
  (testing "`.apply` — the call stock Reagent makes past four arguments —
            reaches the registered view with every argument"
    (setup!)
    (let [head (rf/view ::positional)]
      (is (instance? js/Function head)
          "the head is a real JS function, so `.apply` is the platform's own")
      (doseq [n [20 21]]
        (let [out (with-frame frame-id
                    (.apply head nil (to-array (positional-args n))))]
          (is (some #{(str n "|a" (dec n))} out)
              (str n " arguments through `.apply`: the view returned " (pr-str out))))))))

(deftest head-keeps-the-context-type-metadata-fn-to-class-reads
  (testing "Reagent's `fn-to-class` builds the component class from the head's
            metadata, so the head's `:contextType` must be the frame context"
    (is (identical? rf.adapter.context/frame-context
                    (:contextType (meta (rf/view ::kw-form-1))))
        "the head's metadata carries the frame context as `:contextType`")))
