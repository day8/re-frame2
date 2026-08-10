# Index — Indented Fence Recognition (rf2-mmyc)

A fenced code block is code wherever it sits.  The scanner used to anchor
its fence matcher at column 0, so a fence indented by its container was
never recognised and the sample inside it was scanned as ordinary prose.

Each sample below carries a blank line, which splits the inline block so
the inline-code-span mask cannot pair the fence's own backtick runs and
accidentally hide the link.  Without that blank line these fixtures would
pass for the wrong reason.

## A fence indented inside a list item

- Mark the operand, then read it back:

  ```clojure
  ([n/$](glossary.md#n-dollar) :td cell-props px)

  ;; the sample above is code, not a cross-reference
  ```

## A fence indented inside an admonition

!!! note

    ```clojure
    ([h/defhost](glossary.md#defhost) activity react/Activity)

    ;; likewise code
    ```

## A real link, to prove the file is still scanned

The fixture's own [target](target.md#hello-world) must still validate.
