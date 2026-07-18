# GitHub Duplicate Collision Bump

The third heading below slugifies naturally to `errors-1` — which the second
heading has already claimed as its generated id. GitHub's slugger loops until
the id is free, so the third heading becomes `errors-1-1`. Links to
[the first](#errors), [the second](#errors-1) and [the third](#errors-1-1)
must all resolve.

## Errors

First occurrence — id `errors`.

## Errors

Second occurrence — generated id `errors-1`.

## Errors-1

Natural slug `errors-1` is taken, so this renders as `errors-1-1`.
