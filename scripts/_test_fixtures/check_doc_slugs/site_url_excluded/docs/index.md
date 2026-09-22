# Index

A page can sit in `docs/` and still publish nowhere, because `exclude_docs`
keeps it out of the build. Existence on disk is therefore not the question —
which is why this is checked BEFORE the file lookup.

- excluded, so no page is published (finding):
  [charter](https://day8.github.io/re-frame2/design/fresco/charter/)
- the prefix is scoped, so its sibling still resolves (control):
  [other](https://day8.github.io/re-frame2/design/other/)
