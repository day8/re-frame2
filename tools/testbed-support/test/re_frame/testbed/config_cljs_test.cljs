(ns re-frame.testbed.config-cljs-test
  "Focused unit tests for `re-frame.testbed.config`.

  ## What this pins

  `config.cljs` is a tiny but cross-platform-load-bearing helper: it
  turns a build-env-derived repo root + a tool-relative testbed subdir
  into the on-disk checkout-root the Xray / Story testbeds hand to their
  `configure!` open-in-editor resolvers. The whole point of the file is
  correct *path joining* across Windows / macOS / Linux clones, so the
  branches that matter are:

    1. The build-time `checkout-root` goog-define joined with `subdir`
       (the normal `npm run dev ...` launch).
    2. Root trailing-slash normalisation (so `/repo/` + `sub` never
       yields `/repo//sub`).
    3. Subdir leading-slash normalisation (so callers may pass the
       subdir with or without a leading slash).
    4. Blank / nil subdir → the root verbatim.
    5. Blank `checkout-root` → `nil` (the graceful-no-op contract that
       `set-project-root!` already encodes for blank input).

  The consuming testbeds compile through `config.cljs` under `npm run
  test:xray-feature-gate`, but each calls `resolve-source-root` with
  ONE fixed subdir and no query override, so those checks never assert the
  normalisation branches above. A behaviour-preserving refactor of the
  join/strip logic could silently regress cross-platform path
  construction, so this suite pins each branch directly.

  ## Where this lives

  Like every other tool, `testbed-support` wires a dedicated
  `tools/testbed-support/test` source-path into the top-level
  `implementation/shadow-cljs.edn`, so `:node-test` (`npm run test:cljs`,
  `:ns-regexp \"cljs-test$\"`) discovers this suite there. The runtime
  helper source path (`tools/testbed-support/src`) carries the helper
  namespaces only, keeping the same source/test separation the rest of
  the repo uses. Nothing under `src/` `:require`s this test,
  so it is on the classpath only for the `:node-test` build that finds it
  by the `cljs-test$` ns regexp and never reaches a release bundle.

  ## How the `?checkout-root=` override (tier 1) is pinned here

  Tier 1 of the resolution order — the `?checkout-root=<path>` query
  string winning over everything — is browser-bound: the override reads
  `js/window.location.search` directly, and there is no `js/window` under
  `:node-test`, so a naive test would silently degrade to nil and the
  cross-machine escape hatch could regress unnoticed.

  `config.cljs` isolates the single genuinely browser-bound step —
  reading `location.search` off the live document — behind the thin
  `query-param` adapter, and delegates the actual parse/decode/trim/
  non-blank contract to the PURE `query-param-from-search`, which takes a
  query string verbatim. That pure parser deliberately splits the query
  string manually and decodes each component with `js/decodeURIComponent`
  (NOT `js/URLSearchParams`, whose form-urlencoded semantics would corrupt a
  literal `+` → space — see `config.cljs` and the preserves-literal-plus
  test below); both `str/split` and `js/decodeURIComponent` are present in
  Node too, so that parser runs identically off-browser. That lets us
  pin, with no `js/window` faking:

    a. the override-parsing contract — param selection, URL-decoding
       (`%2F` → `/`), trimming, and the blank-falls-through rule — by
       driving `query-param-from-search` with literal search strings; and
    b. tier-1 PRECEDENCE — the override winning over a present `checkout-root`,
       a blank override falling through to the `checkout-root` tier — by
       `with-redefs`-ing the private `query-param` (the same technique the
       join/normalisation tests use for `checkout-root`).

  The node degradation (no window → adapter returns nil → checkout-root tier)
  is still asserted in `resolve-source-root-nil-when-root-blank` below."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.testbed.config :as config]))

;; ---- shared test helper: composed-editor-path --------------------------
;;
;; The whole point of the resolved root is that the open-in-editor resolver
;; prepends it to a CLASSPATH-RELATIVE source coord (the form-meta `:file`
;; slot, e.g. `standard_epochs/core.cljs`). The downstream prepend is
;; `(str root "/" file)`; this helper reproduces that single join so the
;; slice's unit suite can pin the end-to-end composed path (no `//`, the
;; tool source-root segment present) without reaching into the Xray
;; editor-uri resolver. Defined here at the top so every composition test
;; below — the override form, the Windows form, and the lone-`"/"`-root
;; edge — can use it.

(defn- composed-editor-path
  "Mirror the open-in-editor prepend: resolved-root + \"/\" + the
  classpath-relative source-coord `:file`."
  [root file]
  (str root "/" file))

;; ---- private helper: non-blank -----------------------------------------

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

;; ---- private helper: strip-trailing-slash ------------------------------

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

;; ---- private helper: to-forward-slashes --------------------------------
;;
;; The canonicalisation step: a Windows path with `\`
;; separators reduces to the same `/`-separated form a POSIX path already
;; has, matching the launcher's build-env normalisation and the
;; separator-agnostic editor-URI composer.

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

;; ---- resolve-source-root: blank-root tier (default goog-define) -------

(deftest resolve-source-root-nil-when-root-blank
  (testing "with the default (blank) checkout-root goog-define and no
            browser query override (no js/window under node), every
            tier is absent → nil, so the testbed configures no root and
            open-in-editor degrades to a graceful no-op"
    ;; `config/checkout-root` defaults to "" under :node-test (no #shadow/env
    ;; seed in this build), and there is no js/window for the query tier.
    (is (= "" config/checkout-root)
        "node-test build carries the unseeded default define")
    (is (nil? (config/resolve-source-root "tools/xray/testbeds")))
    (is (nil? (config/resolve-source-root ""))
        "blank subdir with a blank root is still nil")
    (is (nil? (config/resolve-source-root nil))
        "nil subdir with a blank root is still nil")))

;; ---- resolve-source-root: checkout-root tier (join + normalisation) -------
;;
;; goog-define expands to a plain `def`, so `with-redefs` can stand in a
;; concrete root to exercise the join/normalisation branches that the
;; consuming testbeds never reach (they always pass a fixed subdir with a
;; root present and no trailing slash).

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

;; ---- resolve-source-root: the lone POSIX filesystem root "/" ------------
;;
;; `strip-trailing-slash` DELIBERATELY preserves a lone `"/"` (the count > 1
;; guard) so a Unix filesystem root is not destroyed into a blank relative
;; path. That is the one normalised root that still ends in a separator — so
;; the join must NOT then prepend a SECOND `/`: an unconditional
;; `(str normalized-root "/" subdir)` would yield
;; `//tools/xray/testbeds` for `root = "/"`, breaking the docstring's
;; single-separator promise. These tests pin the edge through the FULL
;; `resolve-source-root` surface for BOTH root tiers (build-time define and
;; `?checkout-root=` override), and confirm the blank/slash-only-subdir
;; collapse still returns the lone root verbatim.

(deftest resolve-source-root-lone-root-slash-build-time-tier
  (testing "rf2-fzgcii: a build-time `checkout-root` of the lone POSIX
            filesystem root `\"/\"` joins a nonblank subdir with EXACTLY
            ONE separator — `/tools/xray/testbeds`, not the pre-fix
            `//tools/xray/testbeds`"
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
  (testing "rf2-fzgcii: the SAME lone-root edge through the
            `?checkout-root=` override tier — both tiers share one join, so
            an override of `\"/\"` must also yield a single-separator path"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root") "/"))
                  config/checkout-root ""]
      (let [resolved (config/resolve-source-root "tools/xray/testbeds")]
        (is (= "/tools/xray/testbeds" resolved)
            "override lone-root + subdir → single leading separator")
        (is (not (str/includes? resolved "//"))
            "no // boundary duplication via the override tier")))))

(deftest resolve-source-root-lone-root-slash-blank-subdir-yields-root
  (testing "rf2-fzgcii: with the lone `\"/\"` root a blank / nil / slash-only
            subdir still collapses to the lone root verbatim (the
            graceful-no-subdir contract is unaffected by the join fix)"
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
  (testing "rf2-fzgcii: the lone-root case composes with a representative
            classpath-relative source coord to a clean on-disk path —
            `/tools/xray/testbeds/standard_epochs/core.cljs`, the tool
            source-root segment present and no `//` anywhere (the exact
            downstream prepend the open-in-editor resolver performs)"
    (with-redefs [config/checkout-root "/"]
      (let [root     (config/resolve-source-root "tools/xray/testbeds")
            composed (composed-editor-path root "standard_epochs/core.cljs")]
        (is (= "/tools/xray/testbeds/standard_epochs/core.cljs" composed)
            "the composed editor path reaches the real on-disk source file")
        (is (str/includes? composed "/tools/xray/testbeds/")
            "the tool source-root segment is present (not missing)")
        (is (not (str/includes? composed "//"))
            "no // boundary duplication in the composed path")))))

;; ---- query-param-from-search: the pure override parser (tier 1) --------
;;
;; The override knob's parse/decode/trim/non-blank contract, exercised
;; off-browser by handing the pure parser literal `location.search`
;; strings — the same code path the browser adapter runs, no `js/window`
;; fakery. The parser splits the query string manually and decodes with
;; `js/decodeURIComponent` so a LITERAL `+` survives verbatim
;; rather than being form-urlencoded-decoded to a space (the silent
;; path-corruption seam `js/URLSearchParams` would introduce).

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
  (testing "rf2-xdsat.1: a LITERAL `+` in the override path survives
            verbatim — it is NOT form-urlencoded-decoded to a space. A
            checkout at `/home/dev/re-frame2+wip` (or `C:/code/app+1`)
            must round-trip through `?checkout-root=` unencoded, or every
            open-in-editor link points at a nonexistent dir (the exact
            cross-machine-portability failure the override exists to fix)."
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

;; ---- resolve-source-root: tier-1 override PRECEDENCE -------------------
;;
;; `query-param` is the private browser-only adapter (no window under
;; node → nil). Redefining it stands in a deterministic override value
;; so the precedence rule — tier 1 over tier 2 — is asserted without a
;; browser, mirroring how the join tests redefine `checkout-root`.

(deftest resolve-source-root-override-wins-over-checkout-root
  (testing "a present `?checkout-root=` override beats the build-time
            checkout-root tier, AND — like that tier — names a CHECKOUT root
            to which the caller's subdir is appended (rf2-w4yw9q). Both
            tiers mean the same thing, so the composed editor URI reaches
            the tool source-path root the classpath-relative source coords
            resolve against."
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
  (testing "the override goes through the SAME join as the build-time tier:
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

;; ---- composed editor path: the documented override form ----------------
;;
;; The whole point of the resolved root is that the open-in-editor
;; resolver prepends it to a CLASSPATH-RELATIVE source coord (the
;; form-meta `:file` slot, e.g. `standard_epochs/core.cljs`, relative to
;; the testbed source-path root `<checkout>/tools/xray/testbeds`). These
;; tests pin that the documented override form — pasting a CHECKOUT root
;; into `?checkout-root=` — composes with a representative source coord to
;; the intended on-disk file, the exact cross-machine portability path the
;; override exists to provide. (The downstream prepend is `(str root "/"
;; file)`; we reproduce that single join here so the slice's unit suite
;; pins the end-to-end contract without reaching into the Xray editor-uri
;; resolver. (`composed-editor-path` itself is defined near the top of this
;; file so the lone-root and Windows composition tests can use it too.)

(deftest documented-override-composes-to-intended-on-disk-file
  (testing "rf2-w4yw9q: pasting a CHECKOUT root into `?checkout-root=` (the
            documented `?checkout-root=/home/dev/re-frame2+wip` form) and
            resolving for the xray testbed subdir composes with a
            representative source coord to the real on-disk file —
            `<checkout>/tools/xray/testbeds/standard_epochs/core.cljs` —
            NOT the tool-source-root-missing
            `<checkout>/standard_epochs/core.cljs` that the pre-fix
            verbatim semantics produced"
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
  (testing "rf2-w4yw9q: the override and the build-time `checkout-root` tier
            produce the SAME composed editor path for the same checkout —
            the contract is one meaning (a checkout root) across both
            tiers, so a reader's `?checkout-root=` override behaves exactly
            like a local `npm run dev` launch from that checkout"
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

;; ---- Windows override normalisation ------------------------------------
;;
;; A Windows reader pasting `?checkout-root=C:\Users\me\code\re-frame2\`
;; (raw `\` separators, with or without a trailing `\`) — or its
;; %5C-encoded transport form — must resolve to a CLEAN, single-separator,
;; forward-slash path, NOT the `C:\...\/tools/xray/testbeds` boundary
;; duplication that would rely on downstream tolerant path normalisation. Both
;; root tiers go through the SAME canonicalising join, so this holds for
;; the build-time `checkout-root` tier as well as the query override.

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
                      ;; mirrors the decode-component output for
                      ;; ?checkout-root=C%3A%5CUsers%5Cme%5Ccode%5Cre-frame2%5C
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
  (testing "rf2-d01s6s + rf2-w4yw9q: a Windows `?checkout-root=` override
            composes with a representative classpath-relative source coord
            to the intended on-disk file with the tool-source-root segment
            present and exactly one separator everywhere — proving no
            missing `tools/<tool>/testbeds` segment and no `\\/` / `//`
            duplication in the path Xray/Story actually prefix"
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
            literal `+` is preserved (the existing rf2-xdsat.1 semantics)"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "checkout-root")
                                         "C:\\code\\re-frame2+wip"))
                  config/checkout-root ""]
      (is (= "C:/code/re-frame2+wip/tools/xray/testbeds"
             (config/resolve-source-root "tools/xray/testbeds"))
          "+ survives canonicalisation; \\ → /"))))

;; ---- public-surface stability sentinel ---------------------------------

(deftest public-config-surface-stable
  (testing "the public surface rf2-ynjts.23 must keep stable is intact:
            the `re-frame.testbed.config` ns exposes `resolve-source-root`
            (a fn) and the `checkout-root` goog-define (a string)"
    (is (fn? config/resolve-source-root))
    (is (string? config/checkout-root))))
