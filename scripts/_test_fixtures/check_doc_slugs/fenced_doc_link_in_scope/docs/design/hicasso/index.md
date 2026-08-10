# Index — Linked Code Fence (rf2-mmyc)

This fixture reproduces the rf2-re0m shape exactly: a bulk link pass rewrote
lines *inside* Clojure samples, and every link it added RESOLVED — so the
link validator was satisfied while the samples became invalid to copy.

The link below therefore points at a real anchor on a real page.  The only
thing wrong with it is that it is inside a code fence.

- Mark the operand:

  ```clojure
  ([n/props](glossary.md#nprops) cell-props)

  ;; renders literally; copying this sample yields code that will not read
  ```
