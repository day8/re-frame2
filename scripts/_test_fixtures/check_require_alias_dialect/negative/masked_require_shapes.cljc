(ns fixtures.masked-require-shapes
  "NEGATIVE fixture for the reader-level MASK. Every bare-alias require below
  is inside a string or a comment, so none of them is code and the gate must
  be silent (0 findings) — while the two CHARACTER LITERALS prove the mask
  stays in step with the reader rather than merely blanking on sight.

  `\\;` is a semicolon, not a comment opener, and `\\\"` is a quote, not a
  string opener. A mask that misses either desynchronises at the first one and
  blanks the rest of the file — which arrives as a clean run over a corpus it
  can no longer read, the exact failure this checker's usability floor exists
  to refuse. The self-test pins both directions on synthetic text: the same
  `(require '[re-frame.machines :as machines])` fires as code and is invisible
  in a string and in a comment."
  (:require [re-frame.core :as rf]))

;; A doc comment quoting the deferred-require idiom:
;;   (require '[re-frame.machines :as machines])

(def ^:private snippet
  "(require '[re-frame.routing :as routing])")

(def semicolon \;)
(def quote-char \")

(defn render []
  (rf/console :log snippet semicolon quote-char
              "(ns app (:require [re-frame.flows :as flows]))"))
