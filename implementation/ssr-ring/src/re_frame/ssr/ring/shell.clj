(ns re-frame.ssr.ring.shell
  "Default HTML envelope for the Ring host adapter.

  A small, sane default. Users with their own envelope (custom
  `<head>`, asset-hash-pinned `<script>` tags, JSON-LD blocks, ...)
  pass an `:html-shell` option to override. The default exists so a
  first-time user gets a working SSR endpoint without writing string
  concatenation glue.

  The envelope, the payload `<script>` tag and the `<html>` attribute bag
  are the SSR artefact's (`re-frame.ssr.html-helpers/html-document`,
  `payload-script-tag`, `html-attr-bag`), shared with Fresco's server
  `document`; this namespace owns Ring's defaults.

  The hydration EDN is escaped with the EDN-aware script-body encoder:
  string content cannot close the script element, token content still
  round-trips through the EDN reader, and unsafe token-position breakout
  sequences fail loudly."
  (:require [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]))

(set! *warn-on-reflection* true)

(defn default-html-shell
  "The default HTML envelope. Returns a string wrapping the rendered
  body in a minimal-but-runnable document. Override via `:html-shell`
  in `ssr-handler` opts when you need custom <head> / scripts / styles.

  The hydration-payload `<script>` is stamped with the id pinned in
  `re-frame.ssr.constants/payload-script-id` (`\"__rf_payload\"`). The
  CLJS bootstrap reads the payload via
  `document.getElementById(\"__rf_payload\")` — same constant, both
  sides (Spec 011 §Hydration payload script id).
  Custom `:html-shell` overrides MUST emit the payload `<script>` under
  this id (or substitute their own bootstrap that reads a custom id).

  `<title>` is NOT emitted by the shell — the head fragment is the
  canonical source per Spec 011 §Head/meta contract, and emitting one here
  would produce two `<title>` tags per document — malformed HTML. The
  document has a `<title>` only when the active route's `:head` supplies
  one (via `reg-head`), or when the handler's `:head` string opt — which
  REPLACES the whole resolved head — carries one; with neither, the page
  ships no `<title>`.

  `<html>` / `<body>` attributes — the active head model's
  `:html-attrs` / `:body-attrs` bags (Spec 011 §Head/meta)
  are threaded in through opts by the pipeline and stamped onto the
  opening tags via the shared `re-frame.ssr.html-helpers/attr-string`
  serialiser (boolean `true` → bare attribute name; `false` / `nil` →
  omitted; all other values are `escape-attr`-escaped). When
  `:html-attrs` is absent OR omits `:lang`, the `:lang` opt is the
  fallback. When `:html-attrs`
  supplies `:lang`, it wins.

  Args:
    body-html — the string returned by re-frame.ssr/render-to-string
    payload-edn — the hydration payload, pre-serialised with pr-str
    opts — the caller's adapter opts (merged with any per-request
           overrides); standard keys :head / :html-attrs / :body-attrs
           / :body-end / :script-src / :app-element-id / :lang
           influence the envelope.

  Trusted-string contract: `:head` and `:body-end` are raw content hooks;
  `:script-src` and `:app-element-id` are escaped attribute values. A nil
  value means \"use the default\" for each; `:script-src false` emits no
  bootstrap `<script src>` at all. Raw hooks
  must never be populated from untrusted input. Construction validates shape,
  not content trust; prefer structured views and head registrations for
  untrusted content."
  [body-html payload-edn
   {:keys [head html-attrs body-attrs lang app-element-id body-end script-src]}]
  ;; `or`, not `:or`: `:or` fires only for an ABSENT key, and an explicit nil
  ;; means "use the default" too (Spec 011 §Trusted shell hook contract).
  ;; Attribute values are escaped by the envelope; raw trust applies only to
  ;; the `:head` and `:body-end` content hooks.
  (rf.ssr.html-helpers/html-document
    {:html-attrs     (rf.ssr.html-helpers/html-attr-bag html-attrs (or lang "en"))
     :head           head
     :body-attrs     body-attrs
     :app-element-id (or app-element-id "app")
     :body           body-html
     :payload-script (rf.ssr.html-helpers/payload-script-tag payload-edn)
     ;; `false` writes no bootstrap script; nil takes the default.
     :script-src     (when-not (false? script-src) (or script-src "/main.js"))
     :body-end       body-end}))
