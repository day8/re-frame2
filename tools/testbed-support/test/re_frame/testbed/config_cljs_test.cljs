(ns re-frame.testbed.config-cljs-test
  "Cross-platform tests for testbed source-root resolution.

  The pure query parser is exercised directly under Node; precedence tests
  redefine the browser adapter. This keeps URL decoding, literal-plus
  preservation, and Windows/POSIX joining covered without a fake DOM."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.testbed.config :as config]))

(defn- composed-editor-path
  "Compose a resolved root with a classpath-relative source file."
  [root file]
  (str root "/" file))

(deftest non-blank-only-passes-real-strings
  (testing "non-blank returns the string for non-blank strings and nil
            for blank / whitespace / non-string / nil input"
    (is (= "x" (#'config/non-blank "x")))
    (is (= "  x  " (#'config/non-blank "  x  "))
        "leading/trailing whitespace around real content still passes")
    (is (nil? (#'config/non-blank "")))
    (is (nil? (#'config/non-blank "   "))
        "all-whitespace is blank")
    (is (nil? (#'config/non-blank "\t\n"))
        "tabs/newlines are blank")
    (is (nil? (#'config/non-blank nil)))
    (is (nil? (#'config/non-blank 42))
        "non-string input is rejected")))

(deftest strip-trailing-slash-removes-single-trailing-slash
  (testing "a single trailing slash is removed"
    (is (= "/repo" (#'config/strip-trailing-slash "/repo/")))
    (is (= "C:/Users/me/code" (#'config/strip-trailing-slash "C:/Users/me/code/")))))

(deftest strip-trailing-slash-leaves-non-slash-tails-intact
  (testing "no trailing slash → unchanged"
    (is (= "/repo" (#'config/strip-trailing-slash "/repo")))
    (is (= "C:/Users/me/code" (#'config/strip-trailing-slash "C:/Users/me/code")))))

(deftest strip-trailing-slash-preserves-lone-root-slash
  (testing "a lone \"/\" is left intact (count > 1 guard) so a Unix
            filesystem root is not destroyed"
    (is (= "/" (#'config/strip-trailing-slash "/")))))

(deftest strip-trailing-slash-removes-only-one-slash
  (testing "only ONE trailing slash is stripped — a doubled tail keeps
            the inner slash (documents the single-strip contract;
            normal callers never pass `//`)"
    (is (= "/repo/" (#'config/strip-trailing-slash "/repo//")))))

(deftest to-forward-slashes-canonicalises-backslashes
  (testing "every backslash becomes a forward slash; a path that is
            already forward-slashed is unchanged; a literal + survives"
    (is (= "C:/Users/me/code/re-frame2"
           (#'config/to-forward-slashes "C:\\Users\\me\\code\\re-frame2"))
        "a raw Windows checkout root canonicalises to forward slashes")
    (is (= "C:/Users/me/code/re-frame2/"
           (#'config/to-forward-slashes "C:\\Users\\me\\code\\re-frame2\\"))
        "a trailing backslash canonicalises too (left for strip-trailing-slash)")
    (is (= "/home/dev/re-frame2"
           (#'config/to-forward-slashes "/home/dev/re-frame2"))
        "an already-forward-slashed POSIX root is unchanged")
    (is (= "C:/code/app+1"
           (#'config/to-forward-slashes "C:\\code\\app+1"))
        "a literal + is preserved through canonicalisation")))

(deftest resolve-source-root-nil-when-root-blank
  (testing "with the default (blank) checkout-root goog-define and no
            browser query override (no js/window under node), every
            tier is absent → nil, so the testbed configures no root and
            open-in-editor degrades to a graceful no-op"
    ;; Node has neither a seeded define nor a browser query override.
    (is (= "" config/checkout-root)
        "node-test build carries the unseeded default define")
    (is (nil? (config/resolve-source-root "tools/xray/testbeds")))
    (is (nil? (config/resolve-source-root ""))
        "blank subdir with a blank root is still nil")
    (is (nil? (config/resolve-source-root nil))
        "nil subdir with a blank root is still nil")))

;; goog-define expands to a def, so tests can supply checkout roots directly.

(deftest resolve-source-root-joins-root-and-subdir
  (testing "the build-time root is joined to the tool-relative subdir
            with a single \"/\""
    (with-redefs [config/checkout-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds")))
      (is (= "/home/dev/re-frame2/tools/story/testbeds"
             (config/resolve-source-root "tools/story/testbeds"))))))

(deftest resolve-source-root-strips-trailing-slash-on-root
  (testing "a trailing slash on the root never doubles the separator"
    (with-redefs [config/checkout-root "/home/dev/re-frame2/"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))))))

(deftest resolve-source-root-trims-and-strips-leading-slash-on-subdir
  (testing "the subdir is trimmed and any leading slash(es) removed so
            callers may pass it with or without a leading slash and the
            join stays single-separator"
    (with-redefs [config/checkout-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "/tools/xray/testbeds"))
          "leading slash on subdir is normalised away")
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "///tools/xray/testbeds"))
          "multiple leading slashes are all stripped")
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "  tools/xray/testbeds  "))
          "surrounding whitespace on subdir is trimmed"))))

(deftest resolve-source-root-blank-subdir-yields-root-only
  (testing "a blank / nil / whitespace / slash-only subdir collapses to
            the normalised root verbatim (no dangling separator)"
    (with-redefs [config/checkout-root "/home/dev/re-frame2/"]
      (is (= "/home/dev/re-frame2"
             (config/resolve-source-root ""))
          "empty subdir → root only, trailing slash still stripped")
      (is (= "/home/dev/re-frame2"
             (config/resolve-source-root nil))
          "nil subdir → root only")
      (is (= "/home/dev/re-frame2"
             (config/resolve-source-root "   "))
          "whitespace-only subdir → root only")
      (is (= "/home/dev/re-frame2"
             (config/resolve-source-root "/"))
          "slash-only subdir normalises to empty → root only"))))

(deftest resolve-source-root-blank-root-define-is-nil-even-with-subdir
  (testing "a blank/whitespace checkout-root define is treated as absent —
            the checkout-root tier yields nil even when a real subdir is
            passed (no browser override under node), matching the
            `non-blank` gate on the root"
    (with-redefs [config/checkout-root "   "]
      (is (nil? (config/resolve-source-root "tools/xray/testbeds"))))))

;; `/` is the only normalized root that already supplies a join separator.

(deftest resolve-source-root-lone-root-slash-build-time-tier
  (testing "a POSIX filesystem root joins a subdir with one separator"
    (with-redefs [config/checkout-root "/"]
      (let [resolved (config/resolve-source-root "tools/xray/testbeds")]
        (is (= "/tools/xray/testbeds" resolved)
            "lone-root + subdir → single leading separator")
        (is (not (str/includes? resolved "//"))
            "no // boundary duplication"))
      (is (= "/tools/story/testbeds"
             (config/resolve-source-root "tools/story/testbeds"))
          "the other tool subdir joins the same single-separator way")
      (is (= "/tools/xray/testbeds"
             (config/resolve-source-root "/tools/xray/testbeds"))
          "a leading slash on the subdir is still normalised away (no ///)"))))

(deftest resolve-source-root-lone-root-slash-override-tier
  (testing "the override tier handles a POSIX filesystem root identically"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root") "/"))
                  config/checkout-root ""]
      (let [resolved (config/resolve-source-root "tools/xray/testbeds")]
        (is (= "/tools/xray/testbeds" resolved)
            "override lone-root + subdir → single leading separator")
        (is (not (str/includes? resolved "//"))
            "no // boundary duplication via the override tier")))))

(deftest resolve-source-root-lone-root-slash-blank-subdir-yields-root
  (testing "blank subdirs preserve a POSIX filesystem root"
    (with-redefs [config/checkout-root "/"]
      (is (= "/" (config/resolve-source-root ""))
          "blank subdir → lone root preserved")
      (is (= "/" (config/resolve-source-root nil))
          "nil subdir → lone root preserved")
      (is (= "/" (config/resolve-source-root "   "))
          "whitespace subdir → lone root preserved")
      (is (= "/" (config/resolve-source-root "/"))
          "slash-only subdir normalises to empty → lone root preserved"))))

(deftest resolve-source-root-lone-root-composes-to-intended-on-disk-file
  (testing "the POSIX root composes with a classpath-relative coordinate"
    (with-redefs [config/checkout-root "/"]
      (let [root     (config/resolve-source-root "tools/xray/testbeds")
            composed (composed-editor-path root "standard_epochs/core.cljs")]
        (is (= "/tools/xray/testbeds/standard_epochs/core.cljs" composed)
            "the composed editor path reaches the real on-disk source file")
        (is (str/includes? composed "/tools/xray/testbeds/")
            "the tool source-root segment is present (not missing)")
        (is (not (str/includes? composed "//"))
            "no // boundary duplication in the composed path")))))

;; The parser intentionally has URI, not form-urlencoded, plus semantics.

(deftest query-param-from-search-reads-the-named-param
  (testing "the named param's value is returned, trimmed + non-blank"
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "?checkout-root=/home/dev/re-frame2" "checkout-root")))
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "checkout-root=/home/dev/re-frame2" "checkout-root"))
        "a leading ? is optional — the parser tolerates either form")))

(deftest query-param-from-search-url-decodes-the-value
  (testing "percent-encoded path separators (and spaces) are decoded, so a
            `?checkout-root=` value that travelled through a URL arrives as a
            real on-disk path"
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "?checkout-root=%2Fhome%2Fdev%2Fre-frame2" "checkout-root")))
    (is (= "C:/Users/me/my code"
           (#'config/query-param-from-search
            "?checkout-root=C%3A%2FUsers%2Fme%2Fmy%20code" "checkout-root"))
        "a Windows drive path with an encoded space round-trips")))

(deftest query-param-from-search-preserves-literal-plus
  (testing "literal plus characters are not form-urlencoded to spaces"
    (is (= "/home/dev/re-frame2+wip"
           (#'config/query-param-from-search
            "?checkout-root=/home/dev/re-frame2+wip" "checkout-root"))
        "literal + is preserved, not turned into a space")
    (is (= "C:/code/app+1"
           (#'config/query-param-from-search
            "?checkout-root=C:/code/app+1" "checkout-root"))
        "a Windows checkout with a + in the path round-trips")
    (is (= "/home/dev/re-frame2+wip"
           (#'config/query-param-from-search
            "?checkout-root=%2Fhome%2Fdev%2Fre-frame2%2Bwip" "checkout-root"))
        "the fully %-encoded form (%2B for +) decodes to the same path")
    (is (= "a+b"
           (#'config/query-param-from-search
            "?checkout-root=a+b" "checkout-root"))
        "no + → space remapping anywhere in the value")))

(deftest query-param-from-search-picks-the-right-param
  (testing "only the named param is read; other params are ignored"
    (is (= "/wanted"
           (#'config/query-param-from-search
            "?other=/nope&checkout-root=/wanted&more=x" "checkout-root")))
    (is (nil? (#'config/query-param-from-search
               "?other=/nope" "checkout-root"))
        "absent param → nil")))

(deftest query-param-from-search-blank-and-missing-fall-through-to-nil
  (testing "a blank/whitespace param value, an empty search, or a nil
            search all yield nil so the override falls through to the
            build-time checkout-root tier (never an empty override that would
            shadow a real root)"
    (is (nil? (#'config/query-param-from-search
               "?checkout-root=" "checkout-root"))
        "empty value → nil")
    (is (nil? (#'config/query-param-from-search
               "?checkout-root=%20%20%20" "checkout-root"))
        "whitespace-only value (encoded spaces) → nil")
    (is (nil? (#'config/query-param-from-search "" "checkout-root"))
        "empty search string → nil")
    (is (nil? (#'config/query-param-from-search nil "checkout-root"))
        "nil search string → nil")))

;; Redefine the browser adapter to cover override precedence under Node.

(deftest resolve-source-root-override-wins-over-checkout-root
  (testing "a present `?checkout-root=` override beats the build-time
            checkout-root tier and receives the caller's subdir"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "/override/checkout"))
                  config/checkout-root "/home/dev/re-frame2"]
      (is (= "/override/checkout/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "override checkout wins; the caller's subdir is still appended")
      (is (= "/override/checkout/tools/story/testbeds"
             (config/resolve-source-root "tools/story/testbeds"))
          "the appended subdir tracks the caller, the root tracks the override"))))

(deftest resolve-source-root-override-checkout-normalises-like-checkout-root
  (testing "the override uses the same join as the build-time tier:
            a trailing slash on the override checkout never doubles the
            separator, and the subdir's leading slash is normalised away"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "/override/checkout/"))
                  config/checkout-root ""]
      (is (= "/override/checkout/tools/xray/testbeds"
             (config/resolve-source-root "/tools/xray/testbeds"))
          "trailing slash on root + leading slash on subdir → single separator"))))

(deftest resolve-source-root-override-wins-even-when-checkout-root-blank
  (testing "the override governs regardless of the checkout-root tier's state —
            it is the highest-precedence root source"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "/override/checkout"))
                  config/checkout-root ""]
      (is (= "/override/checkout/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))))))

(deftest resolve-source-root-blank-override-falls-through-to-checkout-root
  (testing "when the override is absent (adapter returns nil) the
            build-time checkout-root tier governs — this is the path every
            non-browser host (e.g. :node-test) and every override-free
            browser session takes"
    (with-redefs [config/query-param (constantly nil)
                  config/checkout-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "no override → checkout-root + subdir join governs"))
    (with-redefs [config/query-param (constantly nil)
                  config/checkout-root ""]
      (is (nil? (config/resolve-source-root "tools/xray/testbeds"))
          "no override AND no checkout-root → nil (graceful no-op)"))))

;; Confirm that resolved roots compose with classpath-relative source coords.

(deftest documented-override-composes-to-intended-on-disk-file
  (testing "a checkout override composes to a file beneath the tool source root"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "/home/dev/re-frame2+wip"))
                  config/checkout-root ""]
      (let [root (config/resolve-source-root "tools/xray/testbeds")]
        (is (= "/home/dev/re-frame2+wip/tools/xray/testbeds" root)
            "the checkout override has the tool subdir appended")
        (is (= "/home/dev/re-frame2+wip/tools/xray/testbeds/standard_epochs/core.cljs"
               (composed-editor-path root "standard_epochs/core.cljs"))
            "the composed editor path reaches the actual on-disk source file")))))

(deftest documented-override-and-build-time-tier-compose-identically
  (testing "override and build-time tiers compose the same checkout path"
    (let [coord "standard_epochs/core.cljs"
          subdir "tools/xray/testbeds"
          via-override (with-redefs [config/query-param
                                     (fn [param]
                                       (when (= param "checkout-root")
                                         "/home/dev/re-frame2+wip"))
                                     config/checkout-root ""]
                         (composed-editor-path
                          (config/resolve-source-root subdir) coord))
          via-build-time (with-redefs [config/query-param (constantly nil)
                                       config/checkout-root "/home/dev/re-frame2+wip"]
                           (composed-editor-path
                            (config/resolve-source-root subdir) coord))]
      (is (= via-build-time via-override)
          "override and build-time tier compose to the identical on-disk path")
      (is (= "/home/dev/re-frame2+wip/tools/xray/testbeds/standard_epochs/core.cljs"
             via-override)))))

;; Raw and percent-encoded Windows roots share the same normalized join.

(deftest query-param-from-search-decodes-encoded-backslash
  (testing "a %5C-encoded backslash in the override transport form decodes
            to a literal `\\` (the join then canonicalises it to `/`)"
    (is (= "C:\\Users\\me\\code\\re-frame2"
           (#'config/query-param-from-search
            "?checkout-root=C%3A%5CUsers%5Cme%5Ccode%5Cre-frame2" "checkout-root"))
        "%5C → \\, %3A → : — the raw Windows root is reconstituted")))

(deftest resolve-source-root-normalises-raw-windows-override
  (testing "a raw Windows `?checkout-root=` value (with `\\` separators)
            resolves to a clean forward-slash path with exactly one
            separator at the root/subdir boundary — no `\\/` duplication"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "C:\\Users\\me\\code\\re-frame2"))
                  config/checkout-root ""]
      (is (= "C:/Users/me/code/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "backslashes canonicalised, subdir appended with one /"))))

(deftest resolve-source-root-normalises-windows-override-trailing-backslash
  (testing "a trailing `\\` on a raw Windows override never doubles the
            separator (the exact `C:\\...\\/tools/...` symptom in the bead)"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "C:\\Users\\me\\code\\re-frame2\\"))
                  config/checkout-root ""]
      (let [resolved (config/resolve-source-root "tools/xray/testbeds")]
        (is (= "C:/Users/me/code/re-frame2/tools/xray/testbeds" resolved)
            "trailing backslash canonicalised then stripped — single separator")
        (is (not (str/includes? resolved "\\/"))
            "no \\/ boundary duplication")
        (is (not (str/includes? resolved "\\"))
            "no backslash survives into the resolved path")
        (is (not (str/includes? resolved "//"))
            "no // boundary duplication")))))

(deftest resolve-source-root-normalises-encoded-windows-override
  (testing "the %5C-encoded transport form of a Windows override (with a
            trailing %5C) resolves to the SAME clean forward-slash path as
            the raw form — the override exists to survive URL transport"
    (with-redefs [config/query-param
                  (fn [param]
                    (when (= param "checkout-root")
                      ;; The decoded form of a percent-encoded Windows root.
                      (#'config/query-param-from-search
                       "?checkout-root=C%3A%5CUsers%5Cme%5Ccode%5Cre-frame2%5C"
                       "checkout-root")))
                  config/checkout-root ""]
      (is (= "C:/Users/me/code/re-frame2/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "encoded Windows root + trailing %5C → clean single-separator path"))))

(deftest resolve-source-root-normalises-windows-build-time-tier
  (testing "the build-time checkout-root tier is normalised the same way —
            a `\\`-separated define (e.g. an unnormalised launcher value)
            still resolves to a clean forward-slash path"
    (with-redefs [config/query-param (constantly nil)
                  config/checkout-root "C:\\Users\\me\\code\\re-frame2"]
      (is (= "C:/Users/me/code/re-frame2/tools/story/testbeds"
             (config/resolve-source-root "tools/story/testbeds"))))))

(deftest windows-override-composes-to-intended-on-disk-file
  (testing "a Windows override composes beneath the tool source root"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "C:\\Users\\me\\code\\re-frame2\\"))
                  config/checkout-root ""]
      (let [root (config/resolve-source-root "tools/xray/testbeds")
            composed (composed-editor-path root "standard_epochs/core.cljs")]
        (is (= "C:/Users/me/code/re-frame2/tools/xray/testbeds" root))
        (is (= "C:/Users/me/code/re-frame2/tools/xray/testbeds/standard_epochs/core.cljs"
               composed)
            "the composed editor path reaches the actual on-disk source file")
        (is (str/includes? composed "/tools/xray/testbeds/")
            "the tool source-root segment is present (not missing)")
        (is (not (str/includes? composed "\\"))
            "no backslash survives")
        (is (not (str/includes? composed "//"))
            "no // boundary duplication")))))

(deftest windows-override-preserves-literal-plus-in-checkout
  (testing "a Windows checkout with a `+` in the path (e.g.
            `C:\\code\\re-frame2+wip`) round-trips: `\\` → `/` but the
            literal `+` is preserved"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "C:\\code\\re-frame2+wip"))
                  config/checkout-root ""]
      (is (= "C:/code/re-frame2+wip/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "+ survives canonicalisation; \\ → /"))))

;; A malformed percent-escape must not be reused as a raw filesystem path.
;; An undecodable override is ignored so resolution falls through to the
;; build-time define, matching the blank/missing-override contract.

(deftest query-param-from-search-ignores-undecodable-value
  (testing "a value that cannot be percent-decoded yields nil (never the raw
            undecoded bytes), so the override falls through to a lower tier"
    (is (nil? (#'config/query-param-from-search
               "?checkout-root=C%ZZrepo" "checkout-root"))
        "an invalid %ZZ escape → nil, never the raw \"C%ZZrepo\"")
    (is (nil? (#'config/query-param-from-search
               "?checkout-root=%" "checkout-root"))
        "a lone trailing % → nil")
    (is (nil? (#'config/query-param-from-search
               "?checkout-root=abc%2" "checkout-root"))
        "an incomplete %2 escape → nil")))

(deftest query-param-from-search-skips-undecodable-key
  (testing "a pair whose KEY cannot be percent-decoded cannot masquerade as
            checkout-root and does not block a later valid pair"
    (is (= "/wanted"
           (#'config/query-param-from-search
            "?%ZZ=whatever&checkout-root=/wanted" "checkout-root"))
        "the undecodable key is skipped; the later valid checkout-root wins")
    (is (nil? (#'config/query-param-from-search
               "?%ZZ=/nope" "checkout-root"))
        "an undecodable key never matches the requested param")))

(deftest query-param-from-search-unrelated-malformed-param-does-not-block
  (testing "a malformed UNRELATED param value does not prevent a later valid
            checkout-root from resolving"
    (is (= "/wanted"
           (#'config/query-param-from-search
            "?other=C%ZZbad&checkout-root=/wanted" "checkout-root")))))

(deftest resolve-source-root-undecodable-override-falls-through-to-define
  (testing "acceptance #1: an undecodable ?checkout-root= override is ignored;
            resolution falls through to the build-time define, never beneath
            the malformed raw value"
    (with-redefs [config/query-param
                  (fn [param]
                    (#'config/query-param-from-search
                     "?checkout-root=C%ZZrepo" param))
                  config/checkout-root "/home/dev/re-frame2"]
      (let [resolved (config/resolve-source-root "tools/story/testbeds")]
        (is (= "/home/dev/re-frame2/tools/story/testbeds" resolved)
            "falls through to the build-time define")
        (is (not (str/includes? resolved "C%ZZrepo"))
            "the malformed raw value never appears in the resolved path")))))

(deftest resolve-source-root-undecodable-override-nil-without-define
  (testing "acceptance #2: with no build-time root, a malformed override
            yields nil so open-in-editor degrades to its no-root behaviour"
    (with-redefs [config/query-param
                  (fn [param]
                    (#'config/query-param-from-search
                     "?checkout-root=C%ZZrepo" param))
                  config/checkout-root ""]
      (is (nil? (config/resolve-source-root "tools/story/testbeds"))))))

(deftest decode-component-returns-sentinel-on-decode-failure
  (testing "the pure seam: a valid escape decodes to a string; an invalid one
            returns the distinct decode-failed sentinel, never the raw bytes"
    (is (= "/a b" (#'config/decode-component "%2Fa%20b"))
        "a valid escape decodes normally")
    (is (= "a+b" (#'config/decode-component "a+b"))
        "a literal + is preserved (not form-urlencoded)")
    (is (= @#'config/decode-failed (#'config/decode-component "C%ZZrepo"))
        "an invalid escape returns the sentinel")
    (is (= @#'config/decode-failed (#'config/decode-component "%"))
        "a lone % returns the sentinel")
    (is (not (string? @#'config/decode-failed))
        "the sentinel is not a string, so it can never equal a decoded value")))

(deftest public-config-surface-stable
  (testing "the namespace exposes the resolver and checkout-root define"
    (is (fn? config/resolve-source-root))
    (is (string? config/checkout-root))))
