(ns re-frame.adapter-unsafe-html-ssr-cljs-test
  "One raw-HTML form for a Reagent-bridge app: `reagent.core/unsafe-html`.

  Stock Reagent 2 keeps a `:dangerouslySetInnerHTML` prop only when its
  value is that tagged value, and deletes every other value, React's own
  `{:__html s}` map included. `re-frame.ssr` writes the markup the prop's
  value answers for `:__html`. This adapter makes the tagged value answer
  it, so the one form renders the same body through `re-frame.ssr` and
  through Reagent, and hydration keeps the server's markup instead of
  regenerating the element empty.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.core :as r]
            [re-frame.adapter.reagent]
            [re-frame.ssr :as rf.ssr]))

(defn- react-inner-html
  "The `__html` stock Reagent hands React for `hiccup`'s root element."
  [hiccup]
  (some-> (.-props (r/as-element hiccup))
          (unchecked-get "dangerouslySetInnerHTML")
          (unchecked-get "__html")))

(deftest unsafe-html-renders-the-same-body-through-re-frame-ssr-and-reagent
  (let [tree [:div {:dangerouslySetInnerHTML (r/unsafe-html "<b>x</b>")}]]
    (testing "re-frame.ssr writes the tagged value's markup as the body"
      (is (= "<div><b>x</b></div>" (rf.ssr/render-to-string tree {}))))
    (testing "stock Reagent hands React the same markup"
      (is (= "<b>x</b>" (react-inner-html tree))))))

(deftest the-plain-map-and-empty-values-render-as-before
  (testing "the plain map still renders through re-frame.ssr"
    (is (= "<div><b>x</b></div>"
           (rf.ssr/render-to-string
             [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}] {}))))
  (testing "a tagged value carrying no markup gives an empty body"
    (is (= "<div></div>"
           (rf.ssr/render-to-string
             [:div {:dangerouslySetInnerHTML (r/unsafe-html nil)}] {}))))
  (testing "a value answering no :__html gives an empty body"
    (is (= "<div></div>"
           (rf.ssr/render-to-string
             [:div {:dangerouslySetInnerHTML "<b>x</b>"}] {})))))
