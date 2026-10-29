(ns day8.re-frame2-xray.panels.shared.view-highlight-dom-cljs-test
  "The host-page view highlight against real DOM nodes: a hover leaves
  exactly one view painted, and a leave or a detaching container clears it
  whatever view id the row carries by then. The `:node-test` build loads
  this ns too and has no document, so each row reports the skip there."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.shared.view-highlight :as view-highlight]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- view-node!
  "Append a host node Spec 006 marks as rendering `view-id`."
  [view-id]
  (let [el (.createElement js/document "div")]
    (.setAttribute el "data-rf-view" (str view-id))
    (.appendChild (.-body js/document) el)
    el))

(defn- painted? [el]
  (.contains (.-classList el) view-highlight/highlight-class))

(deftest hover-paints-one-view-and-clears-by-class
  (if-not (browser?)
    (is true ":node — the :browser-test runner has the DOM")
    (let [a (view-node! :app/a)
          b (view-node! :app/b)]
      (try
        (testing "a hover paints the hovered view only"
          (view-highlight/apply! :app/a)
          (is (painted? a))
          (is (not (painted? b))))
        (testing "moving to another view leaves only that one painted"
          (view-highlight/apply! :app/b)
          (is (not (painted? a)))
          (is (painted? b)))
        (testing "a leave clears by class, so the painted view goes even when
                  the row under the pointer now names a different view"
          (view-highlight/clear!)
          (is (not (painted? a)))
          (is (not (painted? b))))
        (testing "the container's detaching ref clears; attaching does not"
          (view-highlight/apply! :app/a)
          (view-highlight/clear-on-detach! (.-body js/document))
          (is (painted? a))
          (view-highlight/clear-on-detach! nil)
          (is (not (painted? a))))
        (finally
          (.remove a)
          (.remove b))))))
