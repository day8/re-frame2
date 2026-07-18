# Duplicate Suffix Out Of Range (Negative Control)

Two headings slugify to `errors`, so GitHub mints exactly two ids: `errors`
and `errors-1`. A link to [a third errors section](#errors-2) names an id
nothing renders, and the validator must flag exactly one BROKEN ANCHOR —
the `-N` suffix is a real counter, not a wildcard.

## Errors

First occurrence — id `errors`.

## Errors

Second occurrence — id `errors-1`.
