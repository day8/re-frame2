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
  expose an XSS vector. The shell pre-escapes the EDN through the
  EDN-aware `html-helpers/escape-edn-script-body` — `<` inside EDN
  string literals becomes the `\\u003c` Unicode escape, while `<` inside
  keyword/symbol tokens (`:<`, `:a<b`) is left intact so the document
  round-trips through `clojure.edn/read-string` unchanged; a `</` / `<!`
  breakout precursor in a token position fails loud (rf2-rdxxa). Earlier
  drafts ran the whole-EDN `escape-script-body-string`, which corrupted
  such tokens into unreadable `\\u003c…` sequences. The streaming
  delta path applies the same encoder."
  (:require [re-frame.ssr.constants :as constants]
            [re-frame.ssr.html-helpers :as html]))

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

;; ---- shared hydration-payload <script> envelope (rf2-7ksyr) ---------------
;;
;; The id-pinned, `</script>`-escaped payload `<script>` is emitted in
;; two places — the non-streaming `default-html-shell` and the streaming
;; final chunk (`re-frame.ssr.ring.streaming`). The escape is security-
;; load-bearing (rf2-7ksyr); a fix or id change to one path MUST track
;; the other, so both call this single helper.

(defn payload-script-tag
  "Build the id-pinned `<script type=\"application/edn\">` carrying the
  already-`pr-str`'d hydration payload EDN. `payload-edn` is escaped
  through `html/escape-edn-script-body` so a payload containing
  `</script>` (sourced from any user-controlled string round-tripped
  through app-db) can't close the envelope — the XSS gate from
  security audit 2026-05-14 §P1 (rf2-7ksyr / rf2-rdxxa). The EDN-aware
  encoder escapes `<` to the `\\u003c` reader escape only inside EDN
  string literals (where the reader decodes it back), so the payload
  round-trips through `clojure.edn/read-string` on the client unchanged
  even when it carries keyword/symbol tokens containing `<` (`:<`,
  `:a<b`); a `</` / `<!` breakout in a token position fails loud rather
  than corrupting the document.

  The id is pinned in `re-frame.ssr.constants/payload-script-id`
  (`\"__rf_payload\"`) — the contract between this server-side emit and
  the client bootstrap's `document.getElementById(...)` read (Spec 011
  §Hydration payload script id, rf2-cegm7 CQ-3 / rf2-j54ee)."
  [payload-edn]
  (str "<script id=\"" constants/payload-script-id "\" type=\"application/edn\">"
       (html/escape-edn-script-body payload-edn)
       "</script>"))

;; ---- single-source document envelope (prefix + suffix) --------------------
;;
;; rf2-od9fet — the doctype / `<html>` / `<head>` / open-`<body>` /
;; open-`#app-div` PREFIX and the bootstrap-`<script>` / `:body-end` /
;; document-close SUFFIX are each assembled in EXACTLY ONE place here.
;; Both the non-streaming `default-html-shell` (which sandwiches
;; body-html + `</div>` + the shared `payload-script-tag` between them)
;; and the streaming delegates (`re-frame.ssr.ring.streaming`'s
;; `default-streaming-prefix` / `default-streaming-suffix`, which flush
;; the two halves around the resolved-continuation chunks) compose these
;; renderers, so the two response modes CANNOT drift on the doctype,
;; `<html>`/`<body>` attribute bags, charset, head fragment, head-hash
;; marker, `#app` id/escaping, bootstrap script, or closing tags. That
;; matters because the envelope carries security-load-bearing escaping
;; (the `escape-attr` attribute-value split, rf2-7x0qk) — a fix or shape
;; change made in one mode would previously have to be mirrored by hand
;; in the other.

(defn document-prefix
  "Render the document PREFIX shared by the non-streaming shell and the
  streaming prefix (rf2-od9fet): `<!DOCTYPE html>` through the OPEN
  `<div id=\"…\">` app-root element, inclusive.

  `render-hash` is an EXPLICIT argument (not read from `opts`) because it
  is the one piece the two modes disagree on: the non-streaming
  `default-html-shell` passes `nil` (its `#app` root carries no
  `data-rf-render-hash` — the marker is a streaming-only wire signal),
  while the streaming prefix passes its body-only structural hash when
  `:emit-hash?` is set, stamping `data-rf-render-hash` on the streamed
  root. When `render-hash` is nil the attribute is omitted entirely (no
  value needs no escaping — the hash is an 8-char hex FNV output).

  `head-html` is the resolved `<head>` fragment (a content position,
  injected raw). `opts` carries the `:html-attrs` / `:body-attrs` bags
  (stamped via the shared `attr-string` serialiser), the `:lang`
  fallback (resolved through `html-attr-bag`), the `:app-element-id`
  (an attribute-value position, `escape-attr`'d per the rf2-7x0qk split),
  and the optional `:head-hash` (the SEPARATE client-reconstructible
  head-model hash stamped as `data-rf-head-hash` on `<head>`, omitted
  when nil)."
  [head-html render-hash
   {:keys [html-attrs body-attrs lang app-element-id head-hash]
    :or   {app-element-id "app"
           lang           "en"}}]
  (let [attr-bag (html-attr-bag html-attrs lang)]
    (str "<!DOCTYPE html>"
         "<html" (html/attr-string attr-bag) ">"
         "<head"
         ;; rf2-1oxjxk — the SEPARATE client-reconstructible head-model
         ;; hash, distinct from the body's data-rf-render-hash on #app.
         ;; Omitted when nil (no value needs no escaping — an 8-char hex
         ;; FNV output, same shape as the render-hash marker).
         (when head-hash (str " data-rf-head-hash=\"" head-hash "\""))
         ">"
         "<meta charset=\"utf-8\">"
         (or head-html "")
         "</head>"
         "<body" (html/attr-string body-attrs) ">"
         ;; rf2-7x0qk — `:app-element-id` lands INSIDE a double-quoted
         ;; attribute value, so it is escaped through `html/escape-attr`
         ;; (escapes `&` + `"`, lossless + position-correct). The
         ;; trusted-string raw-injection contract applies only to the two
         ;; CONTENT-position opts (`:head` / `:body-end`); an
         ;; attribute-value position has a single correct escape, so even
         ;; a benign quote-bearing value can't break out of the attribute.
         ;; See `validate-trusted-shell-opts!` for the split.
         "<div id=\"" (html/escape-attr app-element-id) "\""
         ;; rf2-1oxjxk — the body-only structural `data-rf-render-hash`
         ;; marker. Non-streaming passes nil (no marker on #app);
         ;; streaming passes it when `:emit-hash?` is set. An 8-char hex
         ;; FNV output, so it needs no escaping.
         (when render-hash
           (str " data-rf-render-hash=\"" render-hash "\""))
         ">")))

(defn document-suffix
  "Render the document SUFFIX shared by the non-streaming shell and the
  streaming suffix (rf2-od9fet): the bootstrap `<script src=…>` (when a
  `:script-src` is in play), the raw `:body-end` content hook, and the
  `</body></html>` document close.

  The app-root `</div>` and the hydration-payload `<script>` are NOT
  emitted here — they sit BEFORE the suffix (the non-streaming shell
  writes `</div>` + `payload-script-tag` between the prefix and this
  suffix; the streaming writer closes `</div>` at the end of the shell
  chunk and streams the `__rf_payload` after the continuations,
  rf2-z9dduj). `:script-src` is an attribute-value position, escaped
  through `html/escape-attr` (the same rf2-7x0qk split as
  `:app-element-id` in the prefix); `:body-end` stays raw (content
  position)."
  [{:keys [body-end script-src]
    :or   {script-src "/main.js"}}]
  (str (when script-src
         ;; rf2-7x0qk — `:script-src` is an attribute-value position;
         ;; escape through `html/escape-attr` (same split as the prefix's
         ;; `:app-element-id`). `:body-end` stays raw (content position).
         (str "<script src=\"" (html/escape-attr script-src) "\"></script>"))
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
           influence the envelope. `:head-hash` (rf2-1oxjxk, optional)
           stamps `data-rf-head-hash` on the `<head>` element — the
           SEPARATE client-reconstructible head-model hash channel
           (Spec 011 §Mismatch detection — head), distinct from the
           body's `data-rf-render-hash` on the `#app` root div. Omitted
           when nil (the explicit-`:head`-STRING / degraded-head shape).

  Trusted-string contract — per Spec 011 §Trusted shell hook contract
  (rf2-o6ndb, attribute/content split rf2-7x0qk), the four shell opts
  split by injection position. `:head` and `:body-end` are TRUSTED
  STRINGS injected RAW into CONTENT positions (no escaping — free-form
  HTML content has no single-correct escape). `:script-src` and
  `:app-element-id` land in ATTRIBUTE-VALUE positions (`<script src>` /
  `<div id>`) and are `escape-attr`-escaped here (rf2-7x0qk): a
  double-quoted attribute value has a single correct escape, so a stray
  `\"` in an otherwise-benign id / URL can't break out of the attribute
  and emit malformed markup. Escaping `&` + `\"` is lossless. This is
  structural-correctness, not a sandbox — the content opts (`:head` /
  `:body-end`) remain raw. The shell is caller-controlled config (app
  boot decides what scripts/analytics tags / asset URLs / head
  fragments to inject), so the trust-the-caller model is load-bearing
  for the CONTENT opts. Hosts that route user-controlled content
  through `:head` / `:body-end` (e.g. config-driven analytics blocks
  sourced from a customer settings UI, a tenant-admin-editable head
  fragment) MUST escape upstream — the shell will not. The
  structured alternative for untrusted-customization use cases is
  `reg-head` (for head fragments per Spec 011 §Head/meta) +
  `reg-view*` + `:rf.server/*` fx (for body content), both of which
  run through the standard SSR emitter and apply position-appropriate
  escaping at every leaf. Construction-time structural validation
  lives in `re-frame.ssr.ring.trust/validate-trusted-shell-opts!`
  (rejects non-string non-nil values with
  `:rf.error/ssr-trusted-shell-opt-invalid`); the content trust
  itself is the caller's. Audit rf2-asmj1 R12 / cluster rf2-sljs1 /
  rf2-o6ndb."
  [body-html payload-edn {:keys [head] :as opts}]
  ;; rf2-od9fet — composed from the single-source `document-prefix` /
  ;; `document-suffix` renderers (shared verbatim with the streaming
  ;; delegates). The non-streaming `#app` root carries NO
  ;; `data-rf-render-hash` marker, so `render-hash` is nil here; the
  ;; body, its closing `</div>`, and the shared id-pinned,
  ;; `</script>`-escaped `payload-script-tag` (rf2-7ksyr — same helper the
  ;; streaming final chunk uses) sit between the prefix and the suffix.
  (str (document-prefix head nil opts)
       body-html
       "</div>"
       (payload-script-tag payload-edn)
       (document-suffix opts)))
