(ns re-frame.ssr.ring.shell
  "Default HTML envelope for the Ring host adapter.

  A small, sane default. Users with their own envelope (custom
  `<head>`, asset-hash-pinned `<script>` tags, JSON-LD blocks, ...)
  pass an `:html-shell` option to override. The default exists so a
  first-time user gets a working SSR endpoint without writing string
  concatenation glue.

  The hydration EDN is escaped with the EDN-aware script-body encoder:
  string content cannot close the script element, token content still
  round-trips through the EDN reader, and unsafe token-position breakout
  sequences fail loudly. The streaming path uses the same encoder."
  (:require [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]))

(set! *warn-on-reflection* true)

;; ---- shared <html> attribute bag (lang fallback) --------------------------

(defn html-attr-bag
  "Resolve the `<html>` attribute bag from the `:html-attrs` opt with a
  `:lang` fallback. `:html-attrs` wins; when absent OR omitting `:lang`,
  the `lang` argument is the fallback (an explicit `:lang` inside
  `:html-attrs` takes precedence — the bag is used verbatim). Shared by
  `default-html-shell` and the streaming prefix so the two envelopes
  can't silently diverge on the lang-fallback rule."
  [html-attrs lang]
  (if (seq html-attrs)
    (cond-> html-attrs
      (not (contains? html-attrs :lang)) (assoc :lang lang))
    {:lang lang}))

;; ---- shared hydration-payload <script> envelope ---------------------------
;;
;; Non-streaming and streaming responses use this helper so the pinned id and
;; security-sensitive script-body escaping cannot diverge.

(defn payload-script-tag
  "Build the id-pinned `<script type=\"application/edn\">` carrying the
  already-`pr-str`'d hydration payload EDN. `payload-edn` is escaped
  through `html/escape-edn-script-body`: string content cannot close the
  element, EDN token content remains readable, and unsafe token-position
  breakout sequences fail loudly.

  The id is pinned in `re-frame.ssr.constants/payload-script-id`
  (`\"__rf_payload\"`) — the contract with the client bootstrap's
  `document.getElementById(...)` read."
  [payload-edn]
  (str "<script id=\"" rf.ssr.constants/payload-script-id "\" type=\"application/edn\">"
       (rf.ssr.html-helpers/escape-edn-script-body payload-edn)
       "</script>"))

;; ---- single-source document envelope (prefix + suffix) --------------------
;;
;; These renderers single-source the document envelope for both response
;; modes, including attribute escaping and hash-marker placement.

(defn document-prefix
  "Render the shared document prefix through the open app-root element.

  `render-hash` is explicit because the streaming prefix can stamp the
  body-only hash on its app root while `default-html-shell` passes nil.

  `head-html` is the resolved `<head>` fragment (a content position,
  injected raw). `opts` carries the `:html-attrs` / `:body-attrs` bags
  (stamped via the shared `attr-string` serialiser), the `:lang`
  fallback (resolved through `html-attr-bag`), the escaped `:app-element-id`,
  and the optional `:head-hash` (the SEPARATE client-reconstructible
  head-model hash stamped as `data-rf-head-hash` on `<head>`, omitted
  when nil)."
  [head-html render-hash
   {:keys [html-attrs body-attrs lang app-element-id head-hash]
    :or   {app-element-id "app"
           lang           "en"}}]
  (let [attr-bag (html-attr-bag html-attrs lang)]
    (str "<!DOCTYPE html>"
         "<html" (rf.ssr.html-helpers/attr-string attr-bag) ">"
         "<head"
         ;; The head-model hash is separate from the body's render hash.
         (when head-hash (str " data-rf-head-hash=\"" head-hash "\""))
         ">"
         "<meta charset=\"utf-8\">"
         (or head-html "")
         "</head>"
         "<body" (rf.ssr.html-helpers/attr-string body-attrs) ">"
         ;; Attribute values are escaped; raw trust applies only to the
         ;; `:head` and `:body-end` content hooks.
         "<div id=\"" (rf.ssr.html-helpers/escape-attr app-element-id) "\""
         ;; Only streaming supplies this body-only structural hash marker.
         (when render-hash
           (str " data-rf-render-hash=\"" render-hash "\""))
         ">")))

(defn document-suffix
  "Render the shared document suffix: the bootstrap `<script src=…>` (when a
  `:script-src` is in play), the raw `:body-end` content hook, and the
  `</body></html>` document close.

  The app-root `</div>` and the hydration-payload `<script>` are NOT
  emitted here — they sit BEFORE the suffix (the non-streaming shell
  writes `</div>` + `payload-script-tag` between the prefix and this
  suffix; the streaming writer closes `</div>` at the end of the shell
  chunk and streams the payload after the continuations. `:script-src` is
  escaped as an attribute value; `:body-end` stays raw content."
  [{:keys [body-end script-src]
    :or   {script-src "/main.js"}}]
  (str (when script-src
         ;; `:script-src` is an attribute value; `:body-end` is raw content.
         (str "<script src=\"" (rf.ssr.html-helpers/escape-attr script-src) "\"></script>"))
       (or body-end "")
       "</body>"
       "</html>"))

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
  canonical source per Spec 011 §Head/meta contract.
  `default-head` rolls the frame's `:doc` into `:title` when a route does
  not declare `:head`, so a sensible title is always present in the
  resolved head fragment threaded in as the `:head` opt. Emitting one
  here would produce two `<title>` tags per document — malformed HTML.

  `<html>` / `<body>` attributes — the active head model's
  `:html-attrs` / `:body-attrs` bags (Spec 011 §Head/meta)
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
           influence the envelope. Optional `:head-hash`
           stamps `data-rf-head-hash` on the `<head>` element — the
           SEPARATE client-reconstructible head-model hash channel
           (Spec 011 §Mismatch detection — head), distinct from the
           body's `data-rf-render-hash` on the `#app` root div. Omitted
           when nil (the explicit-`:head`-STRING / degraded-head shape).

  Trusted-string contract: `:head` and `:body-end` are raw content hooks;
  `:script-src` and `:app-element-id` are escaped attribute values. Raw hooks
  must never be populated from untrusted input. Construction validates shape,
  not content trust; prefer structured views and head registrations for
  untrusted content."
  [body-html payload-edn {:keys [head] :as opts}]
  ;; The non-streaming app root has no render-hash marker. Body content and
  ;; the shared payload tag sit between the common prefix and suffix.
  (str (document-prefix head nil opts)
       body-html
       "</div>"
       (payload-script-tag payload-edn)
       (document-suffix opts)))
