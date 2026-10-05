# Index — Linked Code Fence, Out Of Scope (rf2-mmyc)

A resolving doc link inside a fenced sample, under a tree the fenced-link
assertion does not cover.  The assertion is scoped, not corpus-wide: 109
in-repo links legitimately live inside fences elsewhere in this repo
(88 of them in `spec/Spec-Schemas.md`, as commentary inside schema samples).

- Mark the operand:

  ```clojure
  ([n/props](glossary.md#nprops) cell-props)

  ;; legitimate here — this tree is not covered
  ```
