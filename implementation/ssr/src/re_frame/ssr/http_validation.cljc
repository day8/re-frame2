(ns re-frame.ssr.http-validation
  "Shared HTTP header / cookie validation primitives — the wire-grammar
  predicates the SSR fx-boundary (`re-frame.ssr.response`) and the Ring
  host adapter's Set-Cookie materialiser (`re-frame.ssr.ring.cookie`)
  both enforce.

  Per the security audit 2026-05-14 (rf2-hbty2 header-value gate,
  rf2-z7gor header-name + cookie gates at the fx boundary, rf2-rpedl
  cookie-attribute gate at the materialiser): the same two grammars are
  enforced at TWO boundaries — the SSR fx boundary (so non-Ring host
  adapters get the gate with the dispatching event in scope) AND the Ring
  materialiser (so the wire write is portable across servers whose own
  CR/LF defences differ). Both boundaries are deliberate (defence in
  depth); what is NOT deliberate is the regexes drifting apart. Before
  this ns the two regexes lived as byte-identical copies in
  `re-frame.ssr.response` and `re-frame.ssr.ring.cookie`, each carrying a
  comment that a fix to one MUST track the other — a copy-paste security
  surface. Single-sourcing them here removes that drift risk.

  Two grammars:

    - `injection-char-re`  — the three control chars RFC 7230 §3.2.4
                             forbids inside any header value (`\\r` / `\\n`
                             / `\\x00`). A value carrying one would split
                             the header on the wire (header-splitting
                             injection).
    - `token-name-re`      — RFC 7230 §3.2.6 `token` (= RFC 6265 §4.1.1
                             cookie-name): `1*tchar` where `tchar` is the
                             visible US-ASCII set MINUS the separators
                             `( ) < > @ , ; : \\ \" / [ ] ? = { }` and
                             whitespace. The set is encoded explicitly so
                             the grammar is auditable in place; the `+`
                             quantifier rejects empty names.

  Two predicates (`contains-injection-char?`, `valid-token-name?`) carry
  the accept/reject decision; the THROW shape stays at each callsite
  because the two boundaries surface distinct `:where` tags that are part
  of each layer's documented contract — the fx boundary throws
  `:rf.error/header-invalid-name` /
  `:rf.error/cookie-invalid-attribute` with `:where 'rf.ssr/response`, the
  materialiser throws `:rf.error/cookie-invalid-name` /
  `:rf.error/cookie-invalid-attribute` with
  `:where 'rf.ssr/cookie->set-cookie-header`. Both boundaries emit the SAME
  catalogued `:rf.error/cookie-invalid-attribute` for the injection class
  (rf2-xrk4w1 — the offending attribute rides the `:attribute` payload slot,
  not a per-attribute error id); the decision is shared, the diagnostics are
  local."
  (:require [clojure.string]))

#?(:clj (set! *warn-on-reflection* true))

(def injection-char-re
  "RFC 7230 §3.2.4 header-splitting injection chars: CR / LF / NUL. A
  header value (or any string concatenated verbatim into the wire header
  shape — cookie `:domain` / `:path` / `:max-age` / `:same-site`, a
  redirect `:location`) carrying one of these would split the header on
  the wire and let an attacker forge second-and-later headers."
  #"[\r\n\x00]")

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

(defn valid-token-name?
  "True when string `s` is a non-empty RFC 7230 §3.2.6 `token` (the
  shared header-name / cookie-name grammar — no CTLs, whitespace, or
  separators). `re-matches` anchors the whole string, so a name with any
  illegal char anywhere is rejected."
  [s]
  (boolean (re-matches token-name-re s)))
