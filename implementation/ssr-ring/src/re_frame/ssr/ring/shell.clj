(ns re-frame.ssr.ring.shell
  "Default HTML envelope for the Ring host adapter.

  A small, sane default. Users with their own envelope (custom
  `<head>`, asset-hash-pinned `<script>` tags, JSON-LD blocks, ...)
  pass an `:html-shell` option to override. The default exists so a
  first-time user gets a working SSR endpoint without writing string
  concatenation glue.")

(set! *warn-on-reflection* true)

(defn default-html-shell
  "The default HTML envelope. Returns a string wrapping the rendered
  body in a minimal-but-runnable document. Override via `:html-shell`
  in `ssr-handler` opts when you need custom <head> / scripts / styles.

  `<title>` is NOT emitted by the shell — the head fragment is the
  canonical source per Spec 011 §Head/meta contract (rf2-4dra9, rf2-3z841).
  `default-head` rolls the frame's `:doc` into `:title` when a route does
  not declare `:head`, so a sensible title is always present in the
  resolved head fragment threaded in as the `:head` opt. Emitting one
  here would produce two `<title>` tags per document — malformed HTML.

  Args:
    body-html — the string returned by re-frame.ssr/render-to-string
    payload-edn — the hydration payload, pre-serialised with pr-str
    opts — the caller's adapter opts (merged with any per-request
           overrides); standard keys :head / :body-end / :script-src /
           :app-element-id / :lang influence the envelope.

  Safety — `:body-end` is concatenated as RAW HTML; no escaping is
  applied. The shell is caller-controlled config (app boot decides what
  scripts/analytics tags to inject), so the trust-the-caller model is
  load-bearing here. Hosts that route user-controlled content through
  `:body-end` (e.g. config-driven analytics blocks sourced from a
  customer settings UI) MUST escape upstream — the shell will not.
  Audit rf2-asmj1 R12 / cluster rf2-sljs1."
  [body-html payload-edn
   {:keys [head body-end script-src app-element-id lang]
    :or   {app-element-id  "app"
           script-src      "/main.js"
           lang            "en"}}]
  (str "<!DOCTYPE html>"
       "<html lang=\"" lang "\">"
       "<head>"
       "<meta charset=\"utf-8\">"
       (or head "")
       "</head>"
       "<body>"
       "<div id=\"" app-element-id "\">" body-html "</div>"
       "<script id=\"__rf_payload\" type=\"application/edn\">"
       payload-edn
       "</script>"
       (when script-src
         (str "<script src=\"" script-src "\"></script>"))
       (or body-end "")
       "</body>"
       "</html>"))
