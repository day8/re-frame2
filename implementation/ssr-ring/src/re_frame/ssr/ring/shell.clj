(ns re-frame.ssr.ring.shell
  "Default HTML envelope for the Ring host adapter.

  A small, sane default. Users with their own envelope (custom
  `<head>`, asset-hash-pinned `<script>` tags, JSON-LD blocks, ...)
  pass an `:html-shell` option to override. The default exists so a
  first-time user gets a working SSR endpoint without writing string
  concatenation glue.

  Script-body safety (rf2-7ksyr, security audit 2026-05-14 §P1) — the
  hydration payload's EDN string is dropped raw inside
  `<script id=\"__rf_payload\" type=\"application/edn\">…</script>`. A
  payload that contains `</script>` (from any user-controlled string
  that round-tripped through app-db) would close the envelope and
  expose an XSS vector. The shell pre-escapes the EDN through
  `html-helpers/escape-script-body-string` — every `<` becomes the
  Clojure / EDN `\\u003c` Unicode escape — before injection. The EDN
  reader on the client accepts `\\u003c` as the six-character escape
  for `<`, so the payload round-trips through `clojure.edn/read-string`
  unchanged. Same fix pattern as JSON-LD (rf2-m5u23) — single helper,
  two call sites.

  Dev-mode CSP-host warning for `:body-end` (rf2-2n5gg, security audit
  2026-05-14 §P3.4) — `:body-end` is the escape hatch for analytics /
  third-party scripts the shell injects RAW. When the caller supplies
  `:csp-script-src-allowlist` (set of allowed script-src hostnames)
  AND `:body-end` contains a `<script src=\"…\">` whose host is not
  in the allowlist, the shell emits `:rf.ssr/csp-allowlist-violation`
  via `re-frame.trace/emit-error!`. The check is debug-gated
  (`interop/debug-enabled?` short-circuits in production builds per
  Spec 009 §Production-elision verification) so prod requests pay no
  cost. Pure defence-in-depth: the script tag still reaches the wire
  — the warning is the signal, not a block. Callers who don't
  configure an allowlist see no behaviour change."
  (:require [clojure.string :as str]
            [re-frame.interop :as interop]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.html-helpers :as html]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

;; ---- :body-end CSP-allowlist check (rf2-2n5gg) ----------------------------
;;
;; Match: any `<script ... src="..."...></script>` (single OR double-quoted
;; src). Group 1 captures the src URL. Multi-script `:body-end` is common
;; (analytics + chat + error reporter); the scanner iterates every match.

(def ^:private script-src-pattern
  #"(?i)<script\b[^>]*\bsrc\s*=\s*(?:\"([^\"]+)\"|'([^']+)')[^>]*>")

(defn- ^:private script-src-host
  "Extract the host portion of an absolute script src. Returns nil for
  same-origin (relative) URLs — those can't violate a remote-host
  allowlist by definition."
  [src]
  (try
    (let [uri (java.net.URI. ^String src)
          h   (.getHost uri)]
      (when (and h (not (str/blank? h))) h))
    (catch Exception _ nil)))

(defn check-body-end-csp-hosts!
  "When `body-end` is non-empty AND `allowlist` is a non-empty coll,
  scan the raw HTML for `<script src=...>` references and emit
  `:rf.ssr/csp-allowlist-violation` (via `trace/emit-error!`) for each
  external host not in the allowlist. Pure defensive signalling — no
  exception, no shell rewrite. Production builds elide the whole
  check via `interop/debug-enabled?` (Spec 009 §Production builds)."
  [body-end allowlist]
  (when (and interop/debug-enabled?
             body-end
             (seq allowlist))
    (let [allowed (set allowlist)
          matcher (re-matcher script-src-pattern body-end)]
      (loop []
        (when (.find matcher)
          (let [src  (or (.group matcher 1) (.group matcher 2))
                host (script-src-host src)]
            (when (and host (not (contains? allowed host)))
              (trace/emit-error! :rf.ssr/csp-allowlist-violation
                {:where     :ssr-ring/default-html-shell
                 :src       src
                 :host      host
                 :allowlist allowed
                 :message   (str ":body-end carries <script src> whose host "
                                 (pr-str host)
                                 " is not in :csp-script-src-allowlist "
                                 (pr-str allowed)
                                 " — defence-in-depth warning per "
                                 "security audit 2026-05-14 §P3.4 / rf2-2n5gg")})))
          (recur))))))

(defn default-html-shell
  "The default HTML envelope. Returns a string wrapping the rendered
  body in a minimal-but-runnable document. Override via `:html-shell`
  in `ssr-handler` opts when you need custom <head> / scripts / styles.

  The hydration-payload `<script>` is stamped with the id pinned in
  `re-frame.ssr.constants/payload-script-id` (`\"__rf_payload\"`). The
  CLJS bootstrap reads the payload via
  `document.getElementById(\"__rf_payload\")` — same constant, both
  sides (Spec 011 §Hydration payload script id, rf2-cegm7 CQ-3 / rf2-j54ee).
  Custom `:html-shell` overrides MUST emit the payload `<script>` under
  this id (or substitute their own bootstrap that reads a custom id).

  `<title>` is NOT emitted by the shell — the head fragment is the
  canonical source per Spec 011 §Head/meta contract (rf2-4dra9, rf2-3z841).
  `default-head` rolls the frame's `:doc` into `:title` when a route does
  not declare `:head`, so a sensible title is always present in the
  resolved head fragment threaded in as the `:head` opt. Emitting one
  here would produce two `<title>` tags per document — malformed HTML.

  `<html>` / `<body>` attributes — the active head model's
  `:html-attrs` / `:body-attrs` bags (Spec 011 §Head/meta — line 478)
  are threaded in through opts by the pipeline and stamped onto the
  opening tags via the shared `re-frame.ssr.html-helpers/attr-string`
  serialiser (boolean `true` → bare attribute name; `false` / `nil` →
  omitted; all other values are `escape-attr`-escaped). When
  `:html-attrs` is absent OR omits `:lang`, the `:lang` opt is the
  fallback so existing callers keep working. When `:html-attrs`
  supplies `:lang`, it wins.

  Args:
    body-html — the string returned by re-frame.ssr/render-to-string
    payload-edn — the hydration payload, pre-serialised with pr-str
    opts — the caller's adapter opts (merged with any per-request
           overrides); standard keys :head / :html-attrs / :body-attrs
           / :body-end / :script-src / :app-element-id / :lang
           / :csp-script-src-allowlist influence the envelope.

  Trusted-string contract — per Spec 011 §Trusted shell hook contract
  (rf2-o6ndb), the four shell opts `:head`, `:body-end`, `:script-src`,
  and `:app-element-id` are TRUSTED STRINGS injected RAW into the
  rendered HTML envelope. No escaping, no validation, no sandbox.
  The shell is caller-controlled config (app boot decides what
  scripts/analytics tags / asset URLs / head fragments to inject), so
  the trust-the-caller model is load-bearing here. Hosts that route
  user-controlled content through any of the four (e.g. config-driven
  analytics blocks sourced from a customer settings UI, a tenant-
  admin-editable head fragment, an asset URL composed from a query-
  string parameter) MUST escape upstream — the shell will not. The
  structured alternative for untrusted-customization use cases is
  `reg-head` (for head fragments per Spec 011 §Head/meta) +
  `reg-view*` + `:rf.server/*` fx (for body content), both of which
  run through the standard SSR emitter and apply position-appropriate
  escaping at every leaf. Construction-time structural validation
  lives in `re-frame.ssr.ring.trust/validate-trusted-shell-opts!`
  (rejects non-string non-nil values with
  `:rf.error/ssr-trusted-shell-opt-invalid`); the content trust
  itself is the caller's. Audit rf2-asmj1 R12 / cluster rf2-sljs1 /
  rf2-o6ndb.

  Defence-in-depth — when `:csp-script-src-allowlist` is supplied (set
  of allowed script-src hostnames), the shell scans `:body-end` for
  `<script src=\"…\">` whose host is NOT in the allowlist and emits
  `:rf.ssr/csp-allowlist-violation` via `re-frame.trace/emit-error!`.
  The check is debug-gated (production builds elide it) and never
  blocks emission — the warning is the signal. Per rf2-2n5gg /
  security audit 2026-05-14 §P3.4."
  [body-html payload-edn
   {:keys [head html-attrs body-attrs body-end script-src app-element-id lang
           csp-script-src-allowlist]
    :or   {app-element-id  "app"
           script-src      "/main.js"
           lang            "en"}}]
  (check-body-end-csp-hosts! body-end csp-script-src-allowlist)
  (let [;; :html-attrs wins; otherwise fall back to {:lang lang}. An
        ;; explicit :lang inside :html-attrs takes precedence over the
        ;; :lang opt without us having to do anything special — the
        ;; bag is used verbatim.
        html-attr-bag (if (seq html-attrs)
                        (cond-> html-attrs
                          (not (contains? html-attrs :lang)) (assoc :lang lang))
                        {:lang lang})]
    (str "<!DOCTYPE html>"
         "<html" (html/attr-string html-attr-bag) ">"
         "<head>"
         "<meta charset=\"utf-8\">"
         (or head "")
         "</head>"
         "<body" (html/attr-string body-attrs) ">"
         "<div id=\"" app-element-id "\">" body-html "</div>"
         ;; The id is pinned in `re-frame.ssr.constants/payload-script-id`
         ;; — the contract between this server-side emit and the client-
         ;; side bootstrap's `document.getElementById(...)` read. Per
         ;; Spec 011 §Hydration payload script id (rf2-cegm7 CQ-3).
         "<script id=\"" constants/payload-script-id "\" type=\"application/edn\">"
         ;; rf2-7ksyr — escape `<` chars in the EDN string so a payload
         ;; containing `</script>` (sourced from any user-controlled
         ;; string round-tripped through app-db) can't close the
         ;; envelope. The EDN reader accepts `<` as the literal
         ;; escape for `<`, so this round-trips through
         ;; `clojure.edn/read-string` on the client unchanged.
         (html/escape-script-body-string payload-edn)
         "</script>"
         (when script-src
           (str "<script src=\"" script-src "\"></script>"))
         (or body-end "")
         "</body>"
         "</html>")))
