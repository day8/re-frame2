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
  in every adapter, while UIx/Helix are not hiccup at all so it could not
  land uniformly anyway).

  ## What these tests therefore assert

  That the client behaviour is UNCHANGED and is now the single grammar.
  There is deliberately no client-side production change in this bead —
  nothing was added to this adapter. These assertions are the fixed point
  the server was moved onto, so a future edit that \"helpfully\" teaches
  a substrate to resolve keyword heads fails here.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
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
            namespace is dropped, and the argument lands as a text node.
            This is the exact markup the JVM emitter now produces for the
            same head."
    (let [html (render [:dashboard/card :revenue])]
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
  (testing "rf2-j81hs — the bytes this substrate paints for a keyword head
            are the bytes the JVM emitter now emits for the same head.

            The JVM side asserts `\"<card>:revenue</card>\"` exactly (see
            `re-frame.ssr-keyword-head-contract-test`). React serialises
            the text child without the keyword's leading colon, so the two
            are not byte-identical at the TEXT node — but the ELEMENT
            structure, which is what hydration reconciles and what the bug
            was about, now agrees. Pinning the element and asserting the
            absence of the resolved view is the honest form of that claim;
            asserting byte equality would be a claim this test cannot
            support."
    (let [html (render [:dashboard/card :revenue])]
      (is (str/starts-with? html "<card>") (str "got: " html))
      (is (str/ends-with? html "</card>") (str "got: " html))))

  (testing "an UNREGISTERED keyword head paints the identical element —
            registration state does not change a head's meaning on either
            host, which is the entire content of the one-grammar rule"
    (is (= (render [:dashboard/card :revenue])
           (render [:never-registered/card :revenue])))))
