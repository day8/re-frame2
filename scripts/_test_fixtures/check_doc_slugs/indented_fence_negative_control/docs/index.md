# Index — Indented Fence Negative Control (rf2-mmyc)

Widening the fence matcher must not make the gate quieter than it was.
An indented fence ends where its container ends, and prose after it is
still prose.

- Mark the operand:

  ```clojure
  ([n/$](glossary.md#n-dollar) :td cell-props px)

  ;; code, ignored
  ```

Back at column zero, this link [is real and broken](missing.md) and must
still be flagged as BROKEN TARGET.

- An unclosed fence must not swallow the document either:

  ```clojure
  (let [cell-props {}]

Back at column zero again — the list item ended, so the fence ended with
it, and nothing here is code.
