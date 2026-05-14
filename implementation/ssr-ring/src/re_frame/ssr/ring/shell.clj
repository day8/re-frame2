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
  two call sites."
  (:require [re-frame.ssr.constants :as constants]
            [re-frame.ssr.html-helpers :as html]))

(set! *warn-on-reflection* true)

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
           influence the envelope.

  Safety — `:body-end` is concatenated as RAW HTML; no escaping is
  applied. The shell is caller-controlled config (app boot decides what
  scripts/analytics tags to inject), so the trust-the-caller model is
  load-bearing here. Hosts that route user-controlled content through
  `:body-end` (e.g. config-driven analytics blocks sourced from a
  customer settings UI) MUST escape upstream — the shell will not.
  Audit rf2-asmj1 R12 / cluster rf2-sljs1."
  [body-html payload-edn
   {:keys [head html-attrs body-attrs body-end script-src app-element-id lang]
    :or   {app-element-id  "app"
           script-src      "/main.js"
           lang            "en"}}]
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
