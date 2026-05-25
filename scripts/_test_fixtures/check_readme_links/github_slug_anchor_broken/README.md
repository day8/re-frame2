# GitHub Slug Anchor (Negative Control)

See [the rule](#m-38-cljs-namespace-rename--re-framesubstratename--re-frameadaptername)
below — the anchor uses the **GitHub** slug rule, which would keep the
`<name>` placeholder text.  Under MkDocs this slug doesn't exist, so the
validator must flag this link.

### M-38. CLJS namespace rename — re-frame.substrate.<name> -> re-frame.adapter.<name>

The MkDocs-correct anchor would be
`m-38-cljs-namespace-rename--re-framesubstrate---re-frameadapter` —
the link above is the false-positive shape that rf2-69nh9 uncovered.
