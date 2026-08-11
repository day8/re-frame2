(ns fixture.coord.negative.escaped-quote-in-prose-string
  "NEGATIVE fixture (d/string): a retired coordinate QUOTED inside a larger
  string literal, with escaped quotes around it.

  Rule (d)'s string arm shipped treating every `\"` as a literal boundary, so
  the escaped quotes below read as a whole literal whose entire content was the
  coordinate — and a sentence written to say the spelling is RETIRED was
  reported as a reintroduction of it. That is the mirror image of the miss the
  rule exists to close, and the more annoying of the two: it reds a correct
  edit, in a message about the retirement, which teaches the next author that
  the gate is noise.

  Both delimiters must now be real (a backslash-escaped `\"` is a character
  inside a literal, not a boundary of one), so every line below stays green.
  That the tightening did not blunt the rule is proven by the positives in the
  same phase — `coord/positive/assertion_string_{front,arm1}.cljc` still fire,
  and their delimiters are real.")

(def retirement-note
  (str "rf2-hic-007 moved the 42 coordinates; the assertion that used "
       "\"front.codec/\" as its prefix now reads \"re-frame.hicasso.impl.\"."))

(def one-line-note
  "the old assertion used \"arm1.mount/render!\" and had to be rewritten")

(defn explain []
  (println (str "a refusal must not name \"front.presence/fail!\" any more")))
