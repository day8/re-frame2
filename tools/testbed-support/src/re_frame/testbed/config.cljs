(ns re-frame.testbed.config
  "Shared testbed-config helper — derives the on-disk source-root the
  Xray / Story testbeds hand to their 'open in editor' resolvers.

  ## Two roots, named for what they are

  Two concepts ride this helper, and they are deliberately named apart:

  - a **checkout root** — the absolute path to a clone of this repo
    (`<checkout>`). This is the *input*: the build-env-seeded
    `checkout-root` goog-define and the `?checkout-root=` query override
    both name one.
  - a **source root** — the checkout root with the caller's tool-relative
    testbed subdir appended (`<checkout>/tools/xray/testbeds`). This is
    the *output* `resolve-source-root` returns — the root the editor-URI
    composer prepends to a classpath-relative `:file` slot.

  Keeping the two names distinct keeps input and output from both being
  spelled 'root', which would overload the reader.

  ## Why this exists

  Xray and Story turn a source-coord (`standard_epochs/core.cljs:42`) into an
  editor URI by prepending an on-disk *source root*. Shipped code reads
  that root from host config (`xray-config/configure!` /
  `story/configure!`); there is no baked default. The dev testbeds that
  drive Xray / Story have to PASS a root to `configure!`, and that root
  cannot be a hardcoded absolute checkout (e.g.
  `C:/Users/me/code/my-app/tools/xray/testbeds`): on any other clone, any
  other directory, and every Mac/Linux maintainer, such a literal resolves
  'open in editor' to a path that does not exist. This helper derives the
  root cross-platform from the build environment instead.

  ## How the root is derived (cross-platform, build-env derived)

  `checkout-root` is a `goog-define`d string, default `\"\"`. The dev launcher
  (`implementation/scripts/dev-testbed.cjs`, wired as the `dev` npm
  script) resolves the checkout root from its OWN location via node's
  `path` module — which works identically on Windows, macOS, and Linux —
  exports it as the `RF2_TESTBED_PROJECT_ROOT` env var, and spawns
  `shadow-cljs`. Every testbed build in `implementation/shadow-cljs.edn`
  (the Xray testbeds plus the Story testbeds + static build) seeds the
  define from that env var via `#shadow/env`, so the absolute root is the
  ACTUAL checkout root of whatever clone did the build — never a literal.

  `resolve-source-root` then joins that checkout root with the
  tool-relative testbed subdir (`\"tools/xray/testbeds\"` /  `\"tools/story/testbeds\"`)
  the caller passes. A `?checkout-root=<checkout>` query string wins
  as the per-session escape hatch (CI, a reader on a different machine, a
  testbed served from a copied bundle) — it names the same thing as the
  build-time root (a CHECKOUT root) and has the subdir appended the same
  way, so the composed editor URI reaches the tool source root
  (`<checkout>/tools/xray/testbeds`) the classpath-relative source coords
  resolve against. When neither the override nor the
  build-time root is present, this returns `nil` and the testbed simply
  configures no root — 'open in editor' degrades to a no-op rather than a
  broken link, exactly the graceful-absence behaviour
  `set-project-root!` already encodes for blank input.

  ## Why a goog-define and not a literal

  The checkout root cannot be known when the source is authored (it differs
  per clone), so it must be supplied at build time. A goog-define seeded
  from the build environment is the smallest cross-platform-correct
  mechanism: one define + one shared helper, no per-file string, no
  OS-specific path assumptions."
  (:require [clojure.string :as str]))

;; ---- the compile-time, build-env-derived checkout root -------------------
;;
;; @define {string}
;; Defaults to "" (no root known). Seeded per build from the
;; RF2_TESTBED_PROJECT_ROOT env var via `#shadow/env` in
;; implementation/shadow-cljs.edn :closure-defines. The `dev` npm script
;; (implementation/scripts/dev-testbed.cjs) exports that env var with the
;; node-resolved checkout root before spawning shadow-cljs.
(goog-define checkout-root "")

(defn- non-blank
  "Return `s` when it is a non-blank string, else nil."
  [s]
  (when (and (string? s) (seq (str/trim s))) s))

(defn- decode-component
  "`decodeURIComponent` the value, returning the raw value unchanged if it
  carries a malformed percent-escape (which would otherwise throw)."
  [v]
  (try
    (js/decodeURIComponent v)
    (catch :default _ v)))

(defn- query-param-from-search
  "Pure parser for the per-session override knob: given a URL query string
  `search` (the `?a=b&c=d` form, as produced by `location.search`) and a
  `param-name`, return that param's value URL-decoded, trimmed, and
  non-blank — or nil when the param is absent, blank, or the search string
  itself is blank/nil.

  Decoding semantics: a literal `+` in the value is preserved
  VERBATIM, NOT decoded to a space. The override names an on-disk checkout
  root, so a checkout at e.g. `/home/dev/re-frame2+wip` must round-trip
  through `?checkout-root=/home/dev/re-frame2+wip` with the `+` intact. We
  therefore split the query string manually and decode each component with
  `js/decodeURIComponent` (which does NOT map `+` → space) rather than
  `js/URLSearchParams` (form-urlencoded: `+` → space, silent path
  corruption). Percent-escapes still decode (`%2F` → `/`, `%20` → space,
  `%2B` → `+`). This fn carries no `js/window` dependency and is fully
  exercisable off-browser; the only genuinely browser-bound step — reading
  `location.search` off the live document — is isolated in `query-param`."
  [search param-name]
  (when-let [s (non-blank search)]
    (let [s (cond-> s (str/starts-with? s "?") (subs 1))]
      (some (fn [pair]
              (let [eq  (str/index-of pair "=")
                    k   (decode-component (if eq (subs pair 0 eq) pair))]
                (when (= k param-name)
                  (when-let [raw (non-blank (decode-component
                                             (if eq (subs pair (inc eq)) "")))]
                    (str/trim raw)))))
            (str/split s #"&")))))

(defn- query-param
  "Return the named URL query param as a trimmed non-blank string, or nil
  when absent / blank / running outside a browser. The per-session
  override knob — not a Xray/Story-API surface, so kept private.

  Thin browser-only adapter: reads `location.search` off the live document
  when a `js/window` exists, then delegates the parse/decode/trim/non-blank
  contract to the pure `query-param-from-search`. Outside a browser (e.g.
  `:node-test`) there is no `js/window`, so this returns nil and the
  build-time `checkout-root` tier governs (the intended non-browser degradation)."
  [param-name]
  (when (exists? js/window)
    (query-param-from-search (-> js/window .-location .-search) param-name)))

(defn- to-forward-slashes
  "Canonicalise every path separator in `s` to a forward slash. A Windows
  checkout root (`C:\\Users\\me\\code\\re-frame2`) and a POSIX one
  (`/home/dev/re-frame2`) thus reduce to the SAME `/`-separated form, the
  canonical shape this helper emits — matching the launcher's build-env
  normalisation (`implementation/scripts/dev-testbed.cjs`) and the
  separator-agnostic editor-URI composer (`source_coords/editor_uri.cljc`,
  which accepts both `/` and `\\` on input and joins with `/`)."
  [s]
  (str/replace s #"\\" "/"))

(defn- strip-trailing-slash
  "Return `s` without a single trailing forward slash, leaving a lone
  `\"/\"` intact. Callers canonicalise backslashes to `/` first
  (`to-forward-slashes`), so this only needs to consider forward slashes."
  [s]
  (if (and (> (count s) 1) (str/ends-with? s "/"))
    (subs s 0 (dec (count s)))
    s))

(defn- join-root+subdir
  "Join a checkout `root` with the tool-relative testbed `subdir`,
  normalising both sides so the result carries exactly one separator and
  uses the canonical forward-slash form on every platform.

  Both sides are first canonicalised so any `\\` separator (a raw or
  `%5C`-decoded Windows override root, e.g.
  `C:\\Users\\me\\code\\re-frame2\\`) becomes `/` — so a Windows
  `?checkout-root=` value joins exactly like a POSIX one rather than
  yielding a `\\/` boundary (`C:\\...\\/tools/xray/testbeds`) that would
  depend on downstream tolerant path normalisation. `root` then has any
  single trailing slash stripped (so `/repo/` + `sub` never yields `/repo//sub`);
  `subdir` is trimmed and any leading slash(es) removed (so callers may
  pass it with or without a leading slash). A blank / nil / slash-only
  `subdir` collapses to the normalised root verbatim.

  The one root that survives normalisation WITH a trailing slash is the
  lone POSIX filesystem root `\"/\"`: `strip-trailing-slash` preserves it
  (a lone separator is meaningful — destroying it would make the root a
  blank relative path), so it is the only case where `normalized-root`
  ends in `/`. We therefore add the boundary separator only when the
  root does not already supply one, so `\"/\"` + `\"tools/xray/testbeds\"`
  yields `/tools/xray/testbeds` (exactly one separator) rather than
  `//tools/xray/testbeds` — honouring the single-separator contract for
  every root, POSIX filesystem-root included."
  [root subdir]
  (let [normalized-root   (-> root str/trim to-forward-slashes strip-trailing-slash)
        normalized-subdir (-> (or subdir "")
                              str/trim
                              to-forward-slashes
                              (str/replace #"^/+" ""))]
    (if (seq normalized-subdir)
      ;; Add the boundary "/" only when the root does not already end in
      ;; one — the lone-"/" filesystem root is the sole normalised root
      ;; that keeps a trailing slash, so this is what stops `//tools/...`.
      (if (str/ends-with? normalized-root "/")
        (str normalized-root normalized-subdir)
        (str normalized-root "/" normalized-subdir))
      normalized-root)))

(defn resolve-source-root
  "Resolve the on-disk source root a testbed should hand to its
  open-in-editor resolver, for the given tool-relative testbed subdir
  (e.g. `\"tools/xray/testbeds\"` or `\"tools/story/testbeds\"`).

  Both input tiers name a *checkout root* and have the caller's `subdir`
  appended to yield the *source root*, because the editor URI is built by
  prepending the resolved source root to a *classpath-relative* source
  coord (the form-meta `:file` slot, e.g. `\"standard_epochs/core.cljs\"`,
  relative to the testbed source root `<checkout>/tools/xray/testbeds`).
  The result must therefore reach down to that source root, or the composed
  editor URI misses the tool source-root segment and points at a nonexistent
  file. The override and the build-time define mean the
  same thing — a checkout root — so they share one join.

  Resolution order:

    1. `?checkout-root=<checkout>` query string — the per-session escape
       hatch (CI, a reader on a different machine, a copied bundle served
       elsewhere). Wins over the build-time tier. Paste the checkout root
       unencoded, a literal `+` included (it is preserved, NOT decoded to
       a space; see `query-param-from-search`); a Windows root may use
       `\\` separators (raw or `%5C`-encoded) and is canonicalised to `/`
       (see `join-root+subdir`); `subdir` is appended just like tier 2.
    2. The build-time `checkout-root` goog-define — the default for a normal
       `npm run dev ...` launch; `subdir` is appended.
    3. `nil` — neither checkout root is available. The caller configures no
       root and open-in-editor is a graceful no-op (matching
       `set-project-root!`'s blank-input contract).

  Both input tiers and `subdir` are normalised to the canonical
  forward-slash form (`\\` → `/`, trim, trailing/leading-slash strip) so
  callers may pass either separator style with or without a leading
  slash, and the result carries exactly one separator at every boundary."
  [subdir]
  (when-let [root (non-blank (or (query-param "checkout-root") checkout-root))]
    (join-root+subdir root subdir)))
