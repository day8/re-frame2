# Project

A site URL's fragment is graded against the page MkDocs publishes, by the
MkDocs slug model — MkDocs renders that page, so this gate's own GitHub model
is the wrong authority for it. Two of these links are broken.

Sound — MkDocs' `_N` suffix for a repeated heading:

- [first setup](https://day8.github.io/re-frame2/spec/API/#setup)
- [second setup](https://day8.github.io/re-frame2/spec/API/#setup_1)
- [third setup](https://day8.github.io/re-frame2/spec/API/#setup_2)
- [a heading the page renders](https://day8.github.io/re-frame2/core/introduction/#new-heading)

Broken:

- [GitHub's dash suffix is not what MkDocs renders](https://day8.github.io/re-frame2/spec/API/#setup-1)
- [a heading the page does not render](https://day8.github.io/re-frame2/core/introduction/#old-heading)
