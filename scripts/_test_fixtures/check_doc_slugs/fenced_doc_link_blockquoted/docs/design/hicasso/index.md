# Index — Linked Blockquoted Fence (rf2-1cpt)

The rf2-re0m shape again, this time inside a blockquote callout: a link that
RESOLVES, so link validation is satisfied, sitting inside a fence where it
renders literally and makes the sample invalid to copy.

`docs/design/hicasso/studio/` really does write samples this way — a quoted
block of prose that carries its evidence in a `> ```bash` fence — so the tree
the assertion guards is exactly the tree that has them.

> **Mark the operand.** The command below is the whole of it:
>
> ```clojure
> ([n/props](glossary.md#nprops) cell-props)
>
> ;; renders literally; copying this sample yields code that will not read
> ```

The link above is the only defect on this page.
