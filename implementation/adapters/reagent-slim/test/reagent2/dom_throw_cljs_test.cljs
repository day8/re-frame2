(ns reagent2.dom-throw-cljs-test
  "Tests for the Class B throw-on-call shims (Stage 4-F, rf2-6hyy).

  Per IMPL-SPEC §10.1 + §12.1: five React-19-removed surfaces ship as
  throw-on-call shims; each throws an `ex-info` whose canonical
  `:rf.error/id` discriminator (per Spec 009) is
  `:rf.error/react-19-removed-surface`, carrying the offending symbol on
  `:surface`, the human-readable explanation on `:reason`, and the
  migration-guide URL on `:migration`. The shared `:rf.error/id` lets a
  single try/catch in a migration helper match all five; the message
  string is the stringified discriminator kw so `.getMessage` pivots to
  the same category without ex-data.

    reagent2.dom/render
    reagent2.dom/unmount-component-at-node
    reagent2.dom/force-update-all
    reagent2.core/render
    reagent2.core/dom-node

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom :as rdom]
            [reagent2.core :as r]))

;; ---------------------------------------------------------------------------
;; Shared `:rf.error/id` invariant — one try/catch matches all five
;; ---------------------------------------------------------------------------

(def ^:private expected-id :rf.error/react-19-removed-surface)

(defn- caught-ex-data
  "Invoke `f`, expect it to throw, return the `ex-data` of the
  exception or nil if no throw occurred."
  [f]
  (try
    (f)
    nil
    (catch :default e
      (ex-data e))))

(defn- caught-message
  [f]
  (try
    (f)
    nil
    (catch :default e
      (.-message e))))

;; ---------------------------------------------------------------------------
;; reagent2.dom shims
;; ---------------------------------------------------------------------------

(deftest reagent2-dom-render-throws
  (testing "reagent2.dom/render throws :rf.error/react-19-removed-surface"
    (let [data (caught-ex-data #(rdom/render [:div "hi"] (js-obj)))]
      (is (some? data)
          "call threw")
      (is (= expected-id (:rf.error/id data))
          ":rf.error/id identifies the React-19-removed-surface class")
      (is (= 'reagent2.dom/render (:surface data))
          ":surface names the offending symbol")
      (is (= :no-recovery (:recovery data))
          ":recovery is :no-recovery — there is no fallback path")
      (is (re-find #"reagent\.dom/render" (:reason data))
          ":reason names the legacy stock-Reagent symbol")
      (is (re-find #"reagent2\.dom\.client/" (:reason data))
          ":reason points at the reagent2 migration target")
      (is (re-find #"migration/from-re-frame-v1/README\.md#legacy-mount-path" (:migration data))
          ":migration links to the migration guide anchor"))
    (let [msg (caught-message #(rdom/render [:div "hi"] (js-obj)))]
      (is (= ":rf.error/react-19-removed-surface" msg)
          "message is the stringified discriminator kw"))))

(deftest reagent2-dom-unmount-throws
  (testing "reagent2.dom/unmount-component-at-node throws"
    (let [data (caught-ex-data #(rdom/unmount-component-at-node (js-obj)))]
      (is (= expected-id (:rf.error/id data)))
      (is (= 'reagent2.dom/unmount-component-at-node (:surface data)))
      (is (re-find #"unmount-component-at-node" (:reason data)))
      (is (re-find #"reagent2\.dom\.client/unmount" (:reason data))
          ":reason points at the unmount migration target"))))

(deftest reagent2-dom-force-update-all-throws
  (testing "reagent2.dom/force-update-all throws"
    (let [data (caught-ex-data #(rdom/force-update-all))]
      (is (= expected-id (:rf.error/id data)))
      (is (= 'reagent2.dom/force-update-all (:surface data)))
      (is (re-find #"force-update-all" (:reason data)))
      (is (re-find #"file an issue" (:reason data))
          ":reason points at the issue tracker — there is no replacement"))))

;; ---------------------------------------------------------------------------
;; reagent2.core shims
;; ---------------------------------------------------------------------------

(deftest reagent2-core-render-throws
  (testing "reagent2.core/render throws"
    (let [data (caught-ex-data #(r/render [:div "hi"] (js-obj)))]
      (is (= expected-id (:rf.error/id data)))
      (is (= 'reagent2.core/render (:surface data)))
      (is (re-find #"reagent\.core/render" (:reason data)))
      (is (re-find #"reagent2\.dom\.client/" (:reason data))))))

(deftest reagent2-core-dom-node-throws
  (testing "reagent2.core/dom-node throws"
    (let [data (caught-ex-data #(r/dom-node (js-obj)))]
      (is (= expected-id (:rf.error/id data)))
      (is (= 'reagent2.core/dom-node (:surface data)))
      (is (re-find #"findDOMNode" (:reason data))
          ":reason names the underlying React 17 API that was removed")
      (is (re-find #"ref" (:reason data))
          ":reason points at the :ref migration target")
      (is (re-find #"migration/from-re-frame-v1/README\.md#dom-node-removal" (:migration data))
          ":migration links to the migration guide anchor"))))

;; ---------------------------------------------------------------------------
;; Cross-shim contract — one try/catch matches all five
;; ---------------------------------------------------------------------------

(deftest all-five-shims-share-the-error-id
  (testing "a single try/catch keyed on :rf.error/id matches every Class B shim"
    (let [shims [#(rdom/render [:div] (js-obj))
                 #(rdom/unmount-component-at-node (js-obj))
                 #(rdom/force-update-all)
                 #(r/render [:div] (js-obj))
                 #(r/dom-node (js-obj))]
          ids (mapv (fn [f] (:rf.error/id (caught-ex-data f))) shims)]
      (is (every? #(= expected-id %) ids)
          (str "all five shims must use :rf.error/id " expected-id
               "; observed: " (pr-str ids))))))
