(ns re-frame.testbed.config-cljs-test
  "Focused unit tests for `re-frame.testbed.config` (rf2-ynjts.23).

  ## What this pins

  `config.cljs` is a tiny but cross-platform-load-bearing helper: it
  turns a build-env-derived repo root + a tool-relative testbed subdir
  into the on-disk project-root the Xray / Story testbeds hand to their
  `configure!` open-in-editor resolvers. The whole point of the file is
  correct *path joining* across Windows / macOS / Linux clones, so the
  branches that matter are:

    1. The build-time `repo-root` goog-define joined with `subdir`
       (the normal `npm run dev ...` launch).
    2. Root trailing-slash normalisation (so `/repo/` + `sub` never
       yields `/repo//sub`).
    3. Subdir leading-slash normalisation (so callers may pass the
       subdir with or without a leading slash).
    4. Blank / nil subdir → the root verbatim.
    5. Blank `repo-root` → `nil` (the graceful-no-op contract that
       `set-project-root!` already encodes for blank input).

  Before this file the only coverage was indirect: the consuming
  testbeds compile through `config.cljs` under `npm run
  test:xray-feature-gate`, but each calls `resolve-project-root` with
  ONE fixed subdir and no query override, so the normalisation branches
  above were never asserted. A behaviour-preserving refactor of the
  join/strip logic could have silently regressed cross-platform path
  construction.

  ## Why these live under `src/` (not a sibling `test/` dir)

  Every other tool wires a dedicated `tools/<tool>/test` source-path
  into the top-level `implementation/shadow-cljs.edn` so `:node-test`
  (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) discovers it. That
  shadow-cljs.edn is a hot-zone file outside this slice's edit surface
  (rf2-ynjts.23 is scoped to `tools/testbed-support/` only), and an
  un-wired `test/` dir would be an orphaned never-run suite. The slice's
  one already-wired source root is `tools/testbed-support/src`, so this
  suite lives there. Nothing under `src/` `:require`s it, so it is dead
  code for every build except `:node-test` (which finds it by the
  `cljs-test$` ns regexp) and is tree-shaken out of any release bundle.

  ## How the `?project-root=` override (tier 1) is pinned here

  Tier 1 of the resolution order — the `?project-root=<path>` query
  string winning over everything — used to have NO executable coverage:
  the override read `js/window.location.search` directly, and there is no
  `js/window` under `:node-test`, so the branch silently degraded to nil
  and the cross-machine escape hatch could regress unnoticed (rf2-6bq0o).

  `config.cljs` now isolates the single genuinely browser-bound step —
  reading `location.search` off the live document — behind the thin
  `query-param` adapter, and delegates the actual parse/decode/trim/
  non-blank contract to the PURE `query-param-from-search`, which takes a
  query string verbatim. `js/URLSearchParams` is a host global present in
  Node too, so that pure parser runs identically off-browser. That lets us
  pin, with no `js/window` faking:

    a. the override-parsing contract — param selection, URL-decoding
       (`%2F` → `/`), trimming, and the blank-falls-through rule — by
       driving `query-param-from-search` with literal search strings; and
    b. tier-1 PRECEDENCE — the override winning over a present `repo-root`,
       a blank override falling through to the `repo-root` tier — by
       `with-redefs`-ing the private `query-param` (the same technique the
       join/normalisation tests use for `repo-root`).

  The node degradation (no window → adapter returns nil → repo-root tier)
  is still asserted in `resolve-project-root-nil-when-root-blank` below."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.testbed.config :as config]))

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

;; ---- resolve-project-root: blank-root tier (default goog-define) -------

(deftest resolve-project-root-nil-when-root-blank
  (testing "with the default (blank) repo-root goog-define and no
            browser query override (no js/window under node), every
            tier is absent → nil, so the testbed configures no root and
            open-in-editor degrades to a graceful no-op"
    ;; `config/repo-root` defaults to "" under :node-test (no #shadow/env
    ;; seed in this build), and there is no js/window for the query tier.
    (is (= "" config/repo-root)
        "node-test build carries the unseeded default define")
    (is (nil? (config/resolve-project-root "tools/xray/testbeds")))
    (is (nil? (config/resolve-project-root ""))
        "blank subdir with a blank root is still nil")
    (is (nil? (config/resolve-project-root nil))
        "nil subdir with a blank root is still nil")))

;; ---- resolve-project-root: repo-root tier (join + normalisation) -------
;;
;; goog-define expands to a plain `def`, so `with-redefs` can stand in a
;; concrete root to exercise the join/normalisation branches that the
;; consuming testbeds never reach (they always pass a fixed subdir with a
;; root present and no trailing slash).

(deftest resolve-project-root-joins-root-and-subdir
  (testing "the build-time root is joined to the tool-relative subdir
            with a single \"/\""
    (with-redefs [config/repo-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "tools/xray/testbeds")))
      (is (= "/home/dev/re-frame2/tools/story/testbeds"
             (config/resolve-project-root "tools/story/testbeds"))))))

(deftest resolve-project-root-strips-trailing-slash-on-root
  (testing "a trailing slash on the root never doubles the separator"
    (with-redefs [config/repo-root "/home/dev/re-frame2/"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "tools/xray/testbeds"))))))

(deftest resolve-project-root-trims-and-strips-leading-slash-on-subdir
  (testing "the subdir is trimmed and any leading slash(es) removed so
            callers may pass it with or without a leading slash and the
            join stays single-separator"
    (with-redefs [config/repo-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "/tools/xray/testbeds"))
          "leading slash on subdir is normalised away")
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "///tools/xray/testbeds"))
          "multiple leading slashes are all stripped")
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "  tools/xray/testbeds  "))
          "surrounding whitespace on subdir is trimmed"))))

(deftest resolve-project-root-blank-subdir-yields-root-only
  (testing "a blank / nil / whitespace / slash-only subdir collapses to
            the normalised root verbatim (no dangling separator)"
    (with-redefs [config/repo-root "/home/dev/re-frame2/"]
      (is (= "/home/dev/re-frame2"
             (config/resolve-project-root ""))
          "empty subdir → root only, trailing slash still stripped")
      (is (= "/home/dev/re-frame2"
             (config/resolve-project-root nil))
          "nil subdir → root only")
      (is (= "/home/dev/re-frame2"
             (config/resolve-project-root "   "))
          "whitespace-only subdir → root only")
      (is (= "/home/dev/re-frame2"
             (config/resolve-project-root "/"))
          "slash-only subdir normalises to empty → root only"))))

(deftest resolve-project-root-blank-root-define-is-nil-even-with-subdir
  (testing "a blank/whitespace repo-root define is treated as absent —
            the repo-root tier yields nil even when a real subdir is
            passed (no browser override under node), matching the
            `non-blank` gate on the root"
    (with-redefs [config/repo-root "   "]
      (is (nil? (config/resolve-project-root "tools/xray/testbeds"))))))

;; ---- query-param-from-search: the pure override parser (tier 1) --------
;;
;; The override knob's parse/decode/trim/non-blank contract, exercised
;; off-browser by handing the pure parser literal `location.search`
;; strings. `js/URLSearchParams` is a Node global too, so this is the
;; same code path the browser adapter runs — no `js/window` fakery.

(deftest query-param-from-search-reads-the-named-param
  (testing "the named param's value is returned, trimmed + non-blank"
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "?project-root=/home/dev/re-frame2" "project-root")))
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "project-root=/home/dev/re-frame2" "project-root"))
        "a leading ? is optional — URLSearchParams tolerates either form")))

(deftest query-param-from-search-url-decodes-the-value
  (testing "percent-encoded path separators (and spaces) are decoded, so a
            `?project-root=` value that travelled through a URL arrives as a
            real on-disk path"
    (is (= "/home/dev/re-frame2"
           (#'config/query-param-from-search
            "?project-root=%2Fhome%2Fdev%2Fre-frame2" "project-root")))
    (is (= "C:/Users/me/my code"
           (#'config/query-param-from-search
            "?project-root=C%3A%2FUsers%2Fme%2Fmy%20code" "project-root"))
        "a Windows drive path with an encoded space round-trips")))

(deftest query-param-from-search-picks-the-right-param
  (testing "only the named param is read; other params are ignored"
    (is (= "/wanted"
           (#'config/query-param-from-search
            "?other=/nope&project-root=/wanted&more=x" "project-root")))
    (is (nil? (#'config/query-param-from-search
               "?other=/nope" "project-root"))
        "absent param → nil")))

(deftest query-param-from-search-blank-and-missing-fall-through-to-nil
  (testing "a blank/whitespace param value, an empty search, or a nil
            search all yield nil so the override falls through to the
            build-time repo-root tier (never an empty override that would
            shadow a real root)"
    (is (nil? (#'config/query-param-from-search
               "?project-root=" "project-root"))
        "empty value → nil")
    (is (nil? (#'config/query-param-from-search
               "?project-root=%20%20%20" "project-root"))
        "whitespace-only value (encoded spaces) → nil")
    (is (nil? (#'config/query-param-from-search "" "project-root"))
        "empty search string → nil")
    (is (nil? (#'config/query-param-from-search nil "project-root"))
        "nil search string → nil")))

;; ---- resolve-project-root: tier-1 override PRECEDENCE -------------------
;;
;; `query-param` is the private browser-only adapter (no window under
;; node → nil). Redefining it stands in a deterministic override value
;; so the precedence rule — tier 1 over tier 2 — is asserted without a
;; browser, mirroring how the join tests redefine `repo-root`.

(deftest resolve-project-root-override-wins-over-repo-root
  (testing "a present `?project-root=` override is used VERBATIM and beats
            the build-time repo-root tier entirely — the subdir is NOT
            appended (the override already names the final on-disk root)"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "project-root")
                                         "/override/path"))
                  config/repo-root "/home/dev/re-frame2"]
      (is (= "/override/path"
             (config/resolve-project-root "tools/xray/testbeds"))
          "override wins; repo-root + subdir is not joined")
      (is (= "/override/path"
             (config/resolve-project-root "tools/story/testbeds"))
          "the subdir the caller passes is irrelevant when the override is set"))))

(deftest resolve-project-root-override-wins-even-when-repo-root-blank
  (testing "the override governs regardless of the repo-root tier's state —
            it is the highest-precedence source"
    (with-redefs [config/query-param (fn [param]
                                       (when (= param "project-root")
                                         "/override/path"))
                  config/repo-root ""]
      (is (= "/override/path"
             (config/resolve-project-root "tools/xray/testbeds"))))))

(deftest resolve-project-root-blank-override-falls-through-to-repo-root
  (testing "when the override is absent (adapter returns nil) the
            build-time repo-root tier governs — this is the path every
            non-browser host (e.g. :node-test) and every override-free
            browser session takes"
    (with-redefs [config/query-param (constantly nil)
                  config/repo-root "/home/dev/re-frame2"]
      (is (= "/home/dev/re-frame2/tools/xray/testbeds"
             (config/resolve-project-root "tools/xray/testbeds"))
          "no override → repo-root + subdir join governs"))
    (with-redefs [config/query-param (constantly nil)
                  config/repo-root ""]
      (is (nil? (config/resolve-project-root "tools/xray/testbeds"))
          "no override AND no repo-root → nil (graceful no-op)"))))

;; ---- public-surface stability sentinel ---------------------------------

(deftest public-config-surface-stable
  (testing "the public surface rf2-ynjts.23 must keep stable is intact:
            the `re-frame.testbed.config` ns exposes `resolve-project-root`
            (a fn) and the `repo-root` goog-define (a string)"
    (is (fn? config/resolve-project-root))
    (is (string? config/repo-root))))
