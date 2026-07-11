(ns re-frame.ssr.http-validation
  "Shared HTTP header / cookie validation primitives — the wire-grammar
  predicates the SSR fx-boundary (`re-frame.ssr.response`) and the Ring
  host adapter's Set-Cookie materialiser (`re-frame.ssr.ring.cookie`)
  both enforce.

  The same grammars are enforced at two boundaries: the SSR fx boundary,
  where the dispatching event is still in scope, and the Ring materialiser,
  immediately before bytes reach the host. Keeping the predicates here makes
  that defence-in-depth policy portable without duplicating the grammars.

  Three grammars:

    - `injection-char-re`  — the three control chars RFC 7230 §3.2.4
                             forbids inside any header value (`\\r` / `\\n`
                             / `\\x00`). A value carrying one would split
                             the header on the wire (header-splitting
                             injection).
    - `cookie-attr-injection-re` — the header-splitting chars PLUS the raw
                             `;` cookie-attribute delimiter (RFC 6265
                             §4.1.1). A Set-Cookie attribute (`:domain` /
                             `:path` / `:max-age` / `:same-site` /
                             `:expires`) is concatenated verbatim after a
                             `; ` separator, so a `;` inside its value
                             escapes the assigned attribute and fabricates
                             additional attributes (`SameSite=None`,
                             `Secure`, …). Cookie `:value` is exempt — the
                             host serialiser percent-encodes it, so a `;`
                             there stays data (`%3B`).
    - `token-name-re`      — RFC 7230 §3.2.6 `token` (= RFC 6265 §4.1.1
                             cookie-name): `1*tchar` where `tchar` is the
                             visible US-ASCII set MINUS the separators
                             `( ) < > @ , ; : \\ \" / [ ] ? = { }` and
                             whitespace. The set is encoded explicitly so
                             the grammar is auditable in place; the `+`
                             quantifier rejects empty names.

  Three predicates (`contains-injection-char?`,
  `contains-cookie-attr-injection-char?`, `valid-token-name?`) carry
  the accept/reject decision; the THROW shape stays at each callsite
  because the two boundaries surface distinct `:where` tags that are part
  of each layer's documented contract — the fx boundary throws
  `:rf.error/header-invalid-name` /
  `:rf.error/cookie-invalid-attribute` with `:where 'rf.ssr/response`, the
  materialiser throws `:rf.error/cookie-invalid-name` /
  `:rf.error/cookie-invalid-attribute` with
  `:where 'rf.ssr/cookie->set-cookie-header`. Both boundaries emit the SAME
  catalogued `:rf.error/cookie-invalid-attribute` for the injection class
  with the offending attribute in the `:attribute` payload slot. The decision
  is shared; the layer-specific diagnostics remain local."
  (:require [clojure.string]))

#?(:clj (set! *warn-on-reflection* true))

(def injection-char-re
  "RFC 7230 §3.2.4 header-splitting injection chars: CR / LF / NUL. A
  header value (or any string concatenated verbatim into the wire header
  shape — cookie `:domain` / `:path` / `:max-age` / `:same-site`, a
  redirect `:location`) carrying one of these would split the header on
  the wire and let an attacker forge second-and-later headers."
  #"[\r\n\x00]")

(def cookie-attr-injection-re
  "The RFC 7230 §3.2.4 header-splitting chars (CR / LF / NUL) PLUS the raw
  `;` RFC 6265 §4.1.1 cookie-attribute delimiter. A Set-Cookie attribute
  value (`:domain` / `:path` / `:max-age` / `:same-site` / `:expires`) is
  concatenated verbatim after a `; ` separator, so a `;` inside the value
  is itself a delimiter — it escapes the assigned attribute and fabricates
  additional attributes (`SameSite=None`, `Secure`, `Max-Age=0`, …),
  defeating the structured-cookie boundary. Cookie `:value` does NOT use
  this grammar: the host serialiser percent-encodes it (`;` → `%3B`), so
  data stays data."
  #"[\r\n\x00;]")

(def token-name-re
  "RFC 7230 §3.2.6 `token` grammar (= RFC 6265 §4.1.1 cookie-name):
  `1*tchar`, where `tchar` is the visible US-ASCII set MINUS the
  separators `( ) < > @ , ; : \\ \" / [ ] ? = { }` and whitespace. The
  allowed set is encoded explicitly so the grammar is auditable in place;
  the `+` quantifier rejects empty names. Shared by the header-name gate
  and the cookie-name gate."
  #"[!#$%&'*+\-.0-9A-Z\^_`a-z|~]+")

(defn contains-injection-char?
  "True when string `s` carries a CR / LF / NUL — the RFC 7230 §3.2.4
  header-splitting injection chars. Callers `(str v)`-coerce before the
  check; this fn does NOT coerce so the caller controls how a non-string
  value is rendered for both the check and the error payload."
  [s]
  (boolean (re-find injection-char-re s)))

(defn contains-cookie-attr-injection-char?
  "True when string `s` carries a CR / LF / NUL (RFC 7230 §3.2.4
  header-splitting) OR a `;` (the RFC 6265 §4.1.1 cookie-attribute
  delimiter). The wire-grammar gate for Set-Cookie attributes concatenated
  verbatim into the header line — `:domain` / `:path` / `:max-age` /
  `:same-site` / `:expires`. NOT for cookie `:value`, which the host
  serialiser percent-encodes; use `contains-injection-char?` there. Callers
  `(str v)`-coerce before the check; this fn does NOT coerce so the caller
  controls how a non-string value is rendered for both the check and the
  error payload."
  [s]
  (boolean (re-find cookie-attr-injection-re s)))

(defn valid-token-name?
  "True when string `s` is a non-empty RFC 7230 §3.2.6 `token` (the
  shared header-name / cookie-name grammar — no CTLs, whitespace, or
  separators). `re-matches` anchors the whole string, so a name with any
  illegal char anywhere is rejected."
  [s]
  (boolean (re-matches token-name-re s)))
