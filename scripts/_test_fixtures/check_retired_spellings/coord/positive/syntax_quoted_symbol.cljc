(ns fixture.coord.positive.syntax-quoted-symbol
  "POSITIVE fixture (d/symbol): a SYNTAX-QUOTED coordinate.

  Rule (d) shipped with one token-start boundary for both surfaces, and that
  boundary denied a preceding backtick everywhere. On Markdown that is the
  whole prose defence and it is right. In Clojure the backtick is not a prose
  device at all — it is the syntax-quote reader macro — so the denial protected
  no prose there (prose in a Clojure file is a line comment or a string, both
  masked before the symbol pattern runs). It only carved out a hole shaped
  exactly like a live coordinate. The audit of PR #7867 found the hole; this
  fixture is what keeps it shut.

  The form below is ordinary Clojure: syntax-quoting an already-qualified
  symbol yields that symbol, so this is a `:where` naming a namespace the
  shipped package does not contain, spelled the one way the rule could not
  see. Exactly one finding, from the symbol pattern's Clojure boundary.")

(def ^:private root-guard-where `front.codec/root-element)

(defn- resolve-frame-prop! [frame-kw]
  (if (nil? frame-kw)
    (fail! :rf.error/no-frame-prop
           root-guard-where
           "A frame-fed Hicasso boundary rendered with no frame in its props."
           :mint-the-root-element-with-a-frame
           {})
    frame-kw))
