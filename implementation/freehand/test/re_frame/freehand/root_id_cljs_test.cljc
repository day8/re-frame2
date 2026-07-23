(ns re-frame.freehand.root-id-cljs-test
  "The root-id grammar, on both hosts.

  Two tiers ask these questions of one id — the compiler at a mount site's
  expansion, and the live-root registry in a browser, of a root nobody
  compiled — so the answers have to be ONE answer. This file pins the
  grammar itself; the browser files pin what the registry does with it.

  The claim worth the most care is INJECTIVITY. The default
  `identifierPrefix` is built from the slug, so two distinct root-ids that
  slugged to one string would derive one prefix and collide React's
  `use-id` output — the exact collision the client-tier prefix check
  exists to catch, manufactured by the framework itself and invisible to
  it. The obvious lossy transform (normalise every disallowed character to
  `-`) has that defect, and the corpus below is chosen to catch it:
  `:a/b-c` and `:a-b/c` differ only in where the namespace boundary falls,
  and `[:x/y \"a--b\"]` and `[:x/y \"a\" \"b\"]` only in where the element
  boundary does."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand.compiler.root :as compiler-root]
            [re-frame.freehand.root-id :as root-id]))

;; ---------------------------------------------------------------------------
;; Shapes
;; ---------------------------------------------------------------------------

(deftest authored-root-id-grammar-is-closed
  (testing "a legal authored :root-id is a qualified keyword, or a vector of
            one plus scalar disambiguators — nothing else."
    (doseq [ok [:page/shop
                :a/b
                [:shop/panel :left]
                [:shop/panel "left"]
                [:shop/panel 2]
                [:shop/panel :a "b" 3]]]
      (is (root-id/authored-root-id? ok) (str (pr-str ok) " is a legal root-id")))
    (doseq [bad [:shop                       ; unqualified
                 "page/shop"                 ; a string is not a keyword
                 'page/shop                  ; nor a symbol
                 nil
                 []
                 [:shop/panel]               ; a one-element vector says nothing
                 [:shop :left]               ; unqualified head
                 [:shop/panel {:side :left}] ; a map is not a scalar
                 [:shop/panel [:left]]]]
      (is (not (root-id/authored-root-id? bad))
          (str (pr-str bad) " is not a legal root-id")))))

(deftest disambiguator-grammar-is-scalars-only
  (testing "identity a diagnostic cannot print is identity nobody can act on,
            so a disambiguator is a keyword, a string or an integer."
    (doseq [ok [:left "left" 0 7 -1]]
      (is (root-id/scalar-disambiguator? ok)))
    (doseq [bad [nil {} [] #{} 1.5 'left]]
      (is (not (root-id/scalar-disambiguator? bad))))))

(deftest derivation-appends-the-disambiguator-and-nothing-else
  (testing "no disambiguator is the single-root page's zero-ceremony default;
            a disambiguator makes the pair [view-id d]."
    (is (= :shop/app (root-id/derive-root-id :shop/app nil)))
    (is (= [:shop/app :left] (root-id/derive-root-id :shop/app :left)))
    (is (= [:shop/app 2] (root-id/derive-root-id :shop/app 2)))))

;; ---------------------------------------------------------------------------
;; The slug
;; ---------------------------------------------------------------------------

(deftest slug-is-the-pinned-canonical-form
  (testing "the two spellings the contract names verbatim."
    (is (= "page_Sshop" (root-id/root-id-slug :page/shop)))
    (is (= "_V_Kshop_Sapp_Kleft" (root-id/root-id-slug [:shop/app :left]))))
  (testing "every character outside [A-Za-z0-9-] is escaped rather than
            normalised, so nothing is lost on the way into the prefix."
    (is (= "fh_2e_root_Sright" (root-id/root-id-slug :fh.root/right)))
    (is (= "a_5f_b" (root-id/root-id-slug :a_b))
        "`_` is the metacharacter, so it escapes itself")))

(deftest slug-is-injective-over-the-cases-a-lossy-transform-aliases
  (testing "distinct root-ids ALWAYS yield distinct slugs. Each pair below
            collapses under the obvious lossy transform, so a regression to
            it reds here rather than in a use-id collision nobody can trace."
    (doseq [[a b] [[:a/b-c :a-b/c]
                   [[:x/y "a--b"] [:x/y "a" "b"]]
                   [[:x/y :a] [:x/y "a"]]
                   [[:x/y 1] [:x/y "1"]]
                   [:shop/app [:shop/app]]]]
      (is (not= (root-id/root-id-slug a) (root-id/root-id-slug b))
          (str (pr-str a) " and " (pr-str b) " must not share a slug"))))
  (testing "a keyword slug can never begin with the vector lead-in, so the two
            root-id shapes occupy disjoint slug space."
    (doseq [k [:page/shop :V/x :_V/x :a_b]]
      (is (not= "_V" (subs (root-id/root-id-slug k) 0 2))))))

(deftest slug-stays-inside-the-dom-safe-alphabet
  (testing "the slug seeds an identifierPrefix, so every character it can emit
            has to be legal in one."
    (doseq [rid [:page/shop
                 :a.b.c/d
                 (keyword "ünï" "märke")
                 [:x/y "a b" :c 3]
                 [:x/y "!@#$%^&*()"]]]
      (is (re-matches #"[A-Za-z0-9_-]+" (root-id/root-id-slug rid))
          (str (pr-str rid) " slugs into the DOM-safe alphabet")))))

;; ---------------------------------------------------------------------------
;; The prefix, and the one-implementation claim
;; ---------------------------------------------------------------------------

(deftest default-prefix-is-the-slug-wrapped
  (is (= "rf2-page_Sshop-" (root-id/default-identifier-prefix :page/shop)))
  (is (= "rf2-fh_2e_root_Sright-" (root-id/default-identifier-prefix :fh.root/right)))
  (is (= "rf2-_V_Kshop_Sapp_Kleft-"
         (root-id/default-identifier-prefix [:shop/app :left]))))

(deftest the-compiler-and-the-client-read-one-implementation
  (testing "the build-tier prefix and the runtime-tier prefix are the same
            function, not two that agree today. A compile-time prefix that
            disagreed with the runtime one would break use-id hydration in a
            way neither tier could see alone."
    (is (identical? root-id/root-id-slug compiler-root/root-id-slug))
    (is (identical? root-id/default-identifier-prefix
                    compiler-root/default-identifier-prefix))
    (is (identical? root-id/scalar-disambiguator? compiler-root/scalar-disambiguator?))))
