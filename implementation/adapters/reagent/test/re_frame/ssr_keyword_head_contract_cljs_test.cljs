(ns re-frame.ssr-keyword-head-contract-cljs-test
  "The CLIENT half of the one render-tree head grammar.

  Its JVM twin is `re-frame.ssr-keyword-head-contract-test`
  (`implementation/ssr/test/`), which pins the same rule on the two JVM
  SSR emitters. Neither file proves the contract alone: the two hosts can
  disagree while each looks correct in isolation. Read them as one
  statement.

  ## The divergence this pins shut

  Rendering `[:dashboard/card :revenue]` through stock Reagent +
  `react-dom/server` produces `<card>revenue</card>`. A JVM emitter that
  probed `(registrar/lookup :view head)` on a keyword head would produce a
  `<div class=\"card\">` subtree instead, and no client substrate does
  that. Reagent's `parse-tag` runs `(name tag)`: the namespace is
  discarded and the trailing argument becomes a text node. So such a
  SERVER would fail loud on a typo while the CLIENT fails silent on a
  correct-looking tree, and the mismatch would survive every server-side
  test.

  The rule is one rule corpus-wide — a keyword head is a DOM / custom
  element on EVERY host — and the JVM emitters match the client, NOT the
  reverse. Keyword heads have no client-side view semantics: those would
  cost a hot-path registry probe per keyword head and land in every
  adapter, while UIx is not hiccup at all so they could not land
  uniformly anyway.

  ## What these tests therefore assert

  That the client behaviour IS the single grammar. These assertions are
  the fixed point the server matches, so an edit that \"helpfully\"
  teaches a substrate to resolve keyword heads fails here.

  ## The child spelling

  The CHILD spelling must match too: a JVM that emitted
  `<card>:revenue</card>` where this substrate paints
  `<card>revenue</card>` would not hydrate cleanly, because React
  reconciles text nodes as well as element structure. So the cross-host
  test below compares WHOLE strings, never only the opening and closing
  tags.

  The rule is the same on both hosts: a keyword or symbol child is
  spelled by its `name`. Note WHICH spelling — Reagent runs `(name x)`,
  so `:a/b` paints `b` and the namespace is gone. Stripping the colon
  would produce `a/b` and leave the hosts apart; that case is pinned
  explicitly below.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [are deftest testing use-fixtures is]]
            [clojure.string :as str]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(def ^:private test-frame :ssr-keyword-head-contract-cljs-test/frame)

(defn- init! []
  (rf/make-frame {:id       test-frame
                  :doc      "keyword-head cross-host contract test frame"
                  :platform :client})
  (rf/reg-view* :dashboard/card {}
                (fn [card-id] [:div.card [:h3 (str card-id)]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn init!}))

(defn- render
  "Render `tree` to static markup under the test frame."
  [tree]
  (rf/with-frame test-frame
    (rds/render-to-static-markup
      [rf/frame-provider {:frame test-frame} tree])))

;; ===========================================================================
;; A keyword head is an element — even when it names a registered view
;; ===========================================================================

(deftest keyword-head-paints-an-element-not-a-view
  (testing "`:dashboard/card` IS registered here, and the head
            still paints `<card>`: the tag is the keyword's `name`, the
            namespace is dropped, and the argument lands as a text node
            spelled by ITS `name` too. This is the exact
            markup the JVM emitter produces for the same head."
    (let [html (render [:dashboard/card :revenue])]
      (is (= "<card>revenue</card>" html))
      (is (str/includes? html "<card>") (str "got: " html))
      (is (not (str/includes? html "class=\"card\""))
          (str "the registered view must NOT have been resolved — a keyword "
               "head has no view semantics. Got: " html))))

  (testing "the registration is genuinely live — without this the
            assertion above would pass against an unregistered id and
            prove nothing"
    (is (some? (rf/view :dashboard/card)))))

(deftest callable-heads-resolve-the-view
  (testing "the two supported spellings both resolve
            client-side, so the corpus migration away from keyword refs
            lands on something that works on BOTH hosts"
    (let [html (render [(rf/view :dashboard/card) :revenue])]
      (is (str/includes? html "class=\"card\"") (str "got: " html))
      (is (not (str/includes? html "<card>"))
          (str "no phantom element alongside the resolved view. Got: " html)))))

;; ===========================================================================
;; The cross-host statement
;; ===========================================================================

(deftest client-markup-matches-the-jvm-emitter
  (testing "the bytes this substrate paints for a
            keyword head are the bytes the JVM emitter emits for the same
            head. WHOLE STRING, not a prefix and a suffix: a check of only
            `starts-with? \"<card>\"` / `ends-with? \"</card>\"` would miss
            the TEXT nodes, and React hydration reconciles text nodes as
            well as element structure, so a server \":revenue\" could never
            hydrate cleanly against this substrate's \"revenue\".

            Every string below is duplicated verbatim in the JVM twin
            (`re-frame.ssr-keyword-head-contract-test`). A pair of
            literals is the mechanism: neither host can call the other's
            emitter, so the equality is proven by both sides pinning the
            same bytes and both suites having to stay green."
    (are [expected tree] (= expected (render tree))
      "<card>revenue</card>"      [:dashboard/card :revenue]
      "<div>revenue</div>"        [:div :revenue]
      "<div>b</div>"              [:div :a/b]
      "<div>leaf</div>"           [:div :ns.deep/leaf]
      "<div>sym</div>"            [:div 'sym]
      "<div>b</div>"              [:div 'a/b]
      "<div>revenue growth</div>" [:div :revenue " " :growth]
      "<div>1a</div>"             [:div 1 :a]))

  (testing "the namespace is DROPPED — Reagent routes a named child
            through `(name x)`, so `:a/b` paints `b`. A server emitter
            that merely stripped the leading colon would emit `a/b` and
            not match."
    (is (= "<div>b</div>" (render [:div :a/b])))
    (is (not= "<div>a/b</div>" (render [:div :a/b]))))

  (testing "an UNREGISTERED keyword head paints the identical element —
            registration state does not change a head's meaning on either
            host, which is the entire content of the one-grammar rule"
    (is (= (render [:dashboard/card :revenue])
           (render [:never-registered/card :revenue])))))
