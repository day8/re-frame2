(ns re-frame.ssr-keyword-head-contract-cljs-test
  "rf2-j81hs — the CLIENT half of the one render-tree head grammar.

  Its JVM twin is `re-frame.ssr-keyword-head-contract-test`
  (`implementation/ssr/test/`), which pins the same rule on the two JVM
  SSR emitters. Neither file proves the contract alone: the whole bug was
  that the two hosts disagreed while each looked correct in isolation.
  Read them as one statement.

  ## The divergence this pins shut

  Rendering `[:dashboard/card :revenue]` through stock Reagent +
  `react-dom/server` produced `<card>revenue</card>`, while the JVM
  streaming emitter produced a correct `<div class=\"card\">` subtree —
  because the JVM emitters probed `(registrar/lookup :view head)` on a
  keyword head and no client substrate does. Reagent's `parse-tag` runs
  `(name tag)`: the namespace is discarded and the trailing argument
  becomes a text node. So the SERVER failed loud on a typo and the CLIENT
  failed silent on a correct-looking tree, which is why the mistake
  shipped in the flagship streaming example and survived every
  server-side test (rf2-o4rbh measured exactly this).

  The ruling took option (b): one rule corpus-wide — a keyword head is a
  DOM / custom element on EVERY host — and the JVM emitters were changed
  to match the client, NOT the reverse. Giving keyword heads client-side
  view semantics was option (c) and was REJECTED (it would reverse
  rf2-n82bbu, cost a hot-path registry probe per keyword head, and land
  in every adapter, while UIx are not hiccup at all so it could not
  land uniformly anyway).

  ## What these tests therefore assert

  That the client behaviour is UNCHANGED and is now the single grammar.
  There is deliberately no client-side production change in either bead —
  nothing was added to this adapter. These assertions are the fixed point
  the server was moved onto, so a future edit that \"helpfully\" teaches
  a substrate to resolve keyword heads fails here.

  ## The child spelling (rf2-53lsj)

  rf2-j81hs moved the server onto this substrate's HEAD meaning and left
  the CHILD spelling diverging: the JVM emitted `<card>:revenue</card>`
  where this substrate paints `<card>revenue</card>`. The cross-host test
  below named itself `client-markup-matches-the-jvm-emitter` while
  checking only the opening and closing tags, and its comment argued the
  text difference away on the grounds that hydration reconciles element
  structure. It reconciles text nodes too — so the mismatch was real and
  the test was the thing hiding it.

  The rule is now the same on both hosts: a keyword or symbol child is
  spelled by its `name`. Note WHICH spelling — Reagent runs `(name x)`,
  so `:a/b` paints `b` and the namespace is gone. Stripping the colon
  would have produced `a/b` and left the hosts apart; that case is pinned
  explicitly below.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [are deftest testing use-fixtures is]]
            [clojure.string :as str]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(def ^:private test-frame :ssr-keyword-head-contract-cljs-test/frame)

(defn- init! []
  (rf/make-frame {:id       test-frame
                  :doc      "keyword-head cross-host contract test frame"
                  :platform :client})
  (rf/reg-view* :dashboard/card {}
                (fn [card-id] [:div.card [:h3 (str card-id)]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
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
  (testing "rf2-j81hs — `:dashboard/card` IS registered here, and the head
            still paints `<card>`: the tag is the keyword's `name`, the
            namespace is dropped, and the argument lands as a text node
            spelled by ITS `name` too (rf2-53lsj). This is the exact
            markup the JVM emitter now produces for the same head."
    (let [html (render [:dashboard/card :revenue])]
      (is (= "<card>revenue</card>" html))
      (is (str/includes? html "<card>") (str "got: " html))
      (is (not (str/includes? html "class=\"card\""))
          (str "the registered view must NOT have been resolved — that is "
               "rejected option (c). Got: " html))))

  (testing "the registration is genuinely live — without this the
            assertion above would pass against an unregistered id and
            prove nothing, which is precisely how the original bug hid"
    (is (some? (rf/view :dashboard/card)))))

(deftest callable-heads-resolve-the-view
  (testing "rf2-j81hs — the two supported spellings both resolve
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
  (testing "rf2-j81hs + rf2-53lsj — the bytes this substrate paints for a
            keyword head are the bytes the JVM emitter emits for the same
            head. WHOLE STRING, not a prefix and a suffix.

            #6378's version of this test asserted only `starts-with?
            \"<card>\"` / `ends-with? \"</card>\"` and explained in prose
            that the TEXT nodes differed but that this was fine because
            \"element structure is what hydration reconciles\". React
            hydration reconciles text nodes as well, so the server's
            \":revenue\" could never have hydrated cleanly against this
            substrate's \"revenue\" — the test's own name promised a byte
            match it deliberately did not check.

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
            through `(name x)`, so `:a/b` paints `b`. A server-side fix
            that merely stripped the leading colon would emit `a/b` and
            still not match."
    (is (= "<div>b</div>" (render [:div :a/b])))
    (is (not= "<div>a/b</div>" (render [:div :a/b]))))

  (testing "an UNREGISTERED keyword head paints the identical element —
            registration state does not change a head's meaning on either
            host, which is the entire content of the one-grammar rule"
    (is (= (render [:dashboard/card :revenue])
           (render [:never-registered/card :revenue])))))
