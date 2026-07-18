# GitHub Slug Anchor (Negative Control)

See [the rule](#m-38-cljs-namespace-rename--re-framesubstratename--re-frameadaptername)
below — the anchor uses the **GitHub** slug rule, which keeps the `<name>`
placeholder text.  The gate's shared base slugifier strips it, so this id is
not in the computed set and the link is flagged.

### M-38. CLJS namespace rename — re-frame.substrate.<name> -> re-frame.adapter.<name>

The anchor this gate accepts is
`m-38-cljs-namespace-rename--re-framesubstrate---re-frameadapter`.

Note the honest tension: on GitHub the link above is the one that would really
resolve.  Tag-shaped heading text is the single measured base-slug divergence
between the shared slugifier and GitHub's, and nothing in the live README
corpus hits it (0 over 545 headings), so the gap is pinned here rather than
closed (rf2-zzt2r).  Its sibling `mkdocs_slug_anchor_ok` records the other
half.
