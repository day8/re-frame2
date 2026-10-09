(ns re-frame.notebook-markdown-cljs-test
  "The Notebook example's hand-rolled markdown renderer (`notebook.core`), a
  Reagent-coupled `.cljs` namespace that loads only under the `:node-test`
  build. The preview renders user-typed markdown as hiccup data, and every link
  destination passes the private `safe-href` scheme allowlist: an allowlisted
  or scheme-less link becomes a hardened `:a`, anything else an inert
  `[:span.nb-unsafe-link]`. RealWorld's `realworld-shared.markdown` is a
  separate implementation with its own tests."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [notebook.core :as nb]))

;; `safe-href` is private to notebook.core; reached by var to pin the XSS
;; primitive directly, beside the end-to-end markdown->hiccup checks.
(def ^:private safe-href #'notebook.core/safe-href)

(deftest safe-href-passes-allowlisted-and-scheme-less-links
  (testing "the allowlist is case-insensitive, and a ':' after a '/' is a path, not a scheme"
    (doseq [href ["HTTPS://EXAMPLE.COM" "a/b:c"]]
      (is (= href (safe-href href)) href))))

(deftest safe-href-rejects-script-and-data-schemes
  (doseq [href ["javascript:alert(1)"
                "JavaScript:alert(1)"
                "  javascript:alert(1)"
                "vbscript:msgbox(1)"
                "data:text/html,<script>alert(1)</script>"
                "file:///etc/passwd"]]
    (is (nil? (safe-href href))
        (str (pr-str href) " is not allowlisted and must be rejected (nil)"))))

(deftest safe-href-rejects-control-char-obfuscated-schemes
  ;; Browsers drop these control chars before resolving a scheme, so
  ;; `java<TAB>script:alert(1)` fires as `javascript:` on click. safe-href
  ;; strips every ASCII control and space char before detecting the scheme.
  (doseq [href [(str "java" (char 9) "script:alert(1)")    ;; embedded TAB
                (str "java" (char 10) "script:alert(1)")   ;; embedded LF
                (str "java" (char 13) "script:alert(1)")   ;; embedded CR
                (str "java" (char 0) "script:alert(1)")    ;; embedded NUL
                (str (char 1) "javascript:alert(1)")]]     ;; leading SOH
    (is (nil? (safe-href href))
        (str "control-char-obfuscated scheme " (pr-str href) " must be rejected (nil)"))))

(deftest unsafe-link-yields-no-live-anchor-text-preserved
  (testing "an unsafe link, control-char-obfuscated or not, renders as the inert
            span with its text: no live :a, no :href. The link regex stops the
            destination at the first ')', so the payload's closing paren stays text."
    (doseq [href ["javascript:alert(1)"
                  (str "java" (char 9) "script:alert(1)")
                  (str (char 1) "javascript:alert(1)")]]
      (is (= [[:p "click " [:span.nb-unsafe-link "here"] ")"]]
             (nb/markdown->hiccup (str "click [here](" href ")")))
          (pr-str href)))))

(deftest safe-link-yields-a-hardened-anchor
  (doseq [href ["https://example.com"
                "http://example.com/x"
                "mailto:a@b.c"
                "/relative/path"
                "#anchor"]]
    (is (= [[:p "see " [:a {:href href :rel "noopener noreferrer" :target "_blank"} "link"]]]
           (nb/markdown->hiccup (str "see [link](" href ")")))
        (str "href preserved, rel and target hardened: " href))))

(deftest headings-render-to-their-level
  (doseq [[src expected] [["# Big **bold**" [:h1 "Big " [:strong "bold"]]]
                          ["## Medium"      [:h2 "Medium"]]
                          ["### Small *em*" [:h3 "Small " [:em "em"]]]]]
    (is (= [expected] (nb/markdown->hiccup src)) src)))

(deftest inline-runs-render-bold-italic-code
  (is (= [[:p "plain " [:strong "b"] " and " [:em "i"] " and " [:code "c"] " end"]]
         (nb/markdown->hiccup "plain **b** and *i* and `c` end"))))

(deftest lists-render-ul-and-ol
  (is (= [[:ul [:li "alpha"] [:li "beta"] [:li "gamma"]]]
         (nb/markdown->hiccup "- alpha\n- beta\n- gamma")))
  (is (= [[:ol [:li "one"] [:li "two"]]]
         (nb/markdown->hiccup "1. one\n2. two"))))

(deftest blank-source-renders-nothing
  (is (= [] (nb/markdown->hiccup "")))
  (is (= [] (nb/markdown->hiccup "   \n\n   "))))

(deftest multi-block-splits-on-blank-lines
  (is (= [[:p "first para"] [:p "second para"]]
         (nb/markdown->hiccup "first para\n\nsecond para"))))
