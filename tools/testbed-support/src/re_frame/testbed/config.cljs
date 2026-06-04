(ns re-frame.testbed.config
  "Shared testbed-config helper — derives the on-disk project-root the
  Xray / Story testbeds hand to their 'open in editor' resolvers
  (rf2-5dphw).

  ## Why this exists

  Xray and Story turn a source-coord (`standard_epochs/core.cljs:42`) into an
  editor URI by prepending an on-disk *project-root*. SHIPPED code reads
  that root from host config (`xray-config/configure!` /
  `story/configure!`); there is no baked default. But the dev testbeds
  that drive Xray / Story have to PASS a root to `configure!`, and they
  previously each hardcoded the author's absolute Windows checkout
  (`C:/Users/me/code/my-app/tools/xray/testbeds`) as that value —
  six copies of one machine-specific string. On any other clone, any
  other directory, and every Mac/Linux maintainer, 'open in editor'
  resolved to a path that does not exist.

  ## The fix (cross-platform, build-env derived)

  `repo-root` is a `goog-define`d string, default `\"\"`. The dev launcher
  (`implementation/scripts/dev-testbed.cjs`, wired as the `dev` npm
  script) resolves the repo root from its OWN location via node's
  `path` module — which works identically on Windows, macOS, and Linux —
  exports it as the `RF2_TESTBED_PROJECT_ROOT` env var, and spawns
  `shadow-cljs`. Every testbed build in `implementation/shadow-cljs.edn`
  (the Xray testbeds plus the Story testbeds + static build) seeds the
  define from that env var via `#shadow/env`, so the absolute root is the
  ACTUAL repo root of whatever checkout did the build — never a literal.

  `resolve-project-root` then joins that root with the tool-relative
  testbed subdir (`\"tools/xray/testbeds\"` /  `\"tools/story/testbeds\"`)
  the caller passes. A `?project-root=<path>` query string still wins as
  the per-session escape hatch (CI, a reader on a different machine, a
  testbed served from a copied bundle). When neither the override nor the
  build-time root is present, this returns `nil` and the testbed simply
  configures no root — 'open in editor' degrades to a no-op rather than a
  broken link, exactly the graceful-absence behaviour
  `set-project-root!` already encodes for blank input.

  ## Why a goog-define and not a literal

  The repo root cannot be known when the source is authored (it differs
  per clone), so it must be supplied at build time. A goog-define seeded
  from the build environment is the smallest cross-platform-correct
  mechanism: one define + one shared helper, no per-file string, no
  OS-specific path assumptions."
  (:require [clojure.string :as str]))

;; ---- the compile-time, build-env-derived repo root -----------------------
;;
;; @define {string}
;; Defaults to "" (no root known). Seeded per build from the
;; RF2_TESTBED_PROJECT_ROOT env var via `#shadow/env` in
;; implementation/shadow-cljs.edn :closure-defines. The `dev` npm script
;; (implementation/scripts/dev-testbed.cjs) exports that env var with the
;; node-resolved repo root before spawning shadow-cljs.
(goog-define repo-root "")

(defn- non-blank
  "Return `s` when it is a non-blank string, else nil."
  [s]
  (when (and (string? s) (seq (str/trim s))) s))

(defn- query-param-from-search
  "Pure parser for the per-session override knob: given a URL query string
  `search` (the `?a=b&c=d` form, as produced by `location.search`) and a
  `param-name`, return that param's value URL-decoded, trimmed, and
  non-blank — or nil when the param is absent, blank, or the search string
  itself is blank/nil.

  `js/URLSearchParams` is a host global (present in browsers AND in Node),
  so this fn carries no `js/window` dependency and is fully exercisable
  off-browser. It is the testable heart of the override contract: param
  selection, URL-decoding (`%2F` → `/`), trimming, and the blank-falls-
  through rule all live here. The only genuinely browser-bound step — reading
  `location.search` off the live document — is isolated in `query-param`."
  [search param-name]
  (when-let [s (non-blank search)]
    (let [params (js/URLSearchParams. s)]
      (when-let [raw (non-blank (.get params param-name))]
        (str/trim raw)))))

(defn- query-param
  "Return the named URL query param as a trimmed non-blank string, or nil
  when absent / blank / running outside a browser. The per-session
  override knob — not a Xray/Story-API surface, so kept private.

  Thin browser-only adapter: reads `location.search` off the live document
  when a `js/window` exists, then delegates the parse/decode/trim/non-blank
  contract to the pure `query-param-from-search`. Outside a browser (e.g.
  `:node-test`) there is no `js/window`, so this returns nil and the
  build-time `repo-root` tier governs (the intended non-browser degradation)."
  [param-name]
  (when (exists? js/window)
    (query-param-from-search (-> js/window .-location .-search) param-name)))

(defn- strip-trailing-slash
  "Return `s` without a single trailing slash, leaving a lone `\"/\"` intact."
  [s]
  (if (and (> (count s) 1) (str/ends-with? s "/"))
    (subs s 0 (dec (count s)))
    s))

(defn resolve-project-root
  "Resolve the on-disk project-root a testbed should hand to its
  open-in-editor resolver, for the given tool-relative testbed subdir
  (e.g. `\"tools/xray/testbeds\"` or `\"tools/story/testbeds\"`).

  Resolution order:

    1. `?project-root=<path>` query string — the per-session escape
       hatch. Wins over everything; used verbatim. (CI, a reader on a
       different machine, a copied bundle served elsewhere.)
    2. The build-time `repo-root` goog-define joined with `subdir` —
       the default for a normal `npm run dev ...` launch.
    3. `nil` — neither is available. The caller configures no root and
       open-in-editor is a graceful no-op (matching `set-project-root!`'s
       blank-input contract).

  `subdir` is normalised so callers may pass it with or without a
  leading slash."
  [subdir]
  (or (query-param "project-root")
      (when-let [root (non-blank repo-root)]
        (let [normalized-root   (strip-trailing-slash (str/trim root))
              normalized-subdir (-> (or subdir "")
                                    str/trim
                                    (str/replace #"^/+" ""))]
          (if (seq normalized-subdir)
            (str normalized-root "/" normalized-subdir)
            normalized-root)))))
