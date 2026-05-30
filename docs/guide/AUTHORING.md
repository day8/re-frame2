# Guide Authoring Notes

This file is for contributors editing `docs/guide`. It is not part of the reader path.

## Reader contract

Each chapter starts with two or three sentences framed around a reader problem or interest area. Avoid abstract topic announcements like "This chapter discusses subscriptions." Prefer "Your view needs state, but you do not want every component spelunking through app-db."

Do not add a ritual "What next" section to every page. Cross-link where the reader genuinely needs a neighboring concept, but let each page land cleanly.

## Voice

Write for humans. The guide should sound like an experienced engineer explaining a system over coffee: opinionated, concrete, occasionally funny, and technically accountable. Do not imitate a named writer. Do not turn the guide into stand-up comedy. The joke is allowed only when it helps the reader stay awake long enough to learn the hard bit.

## Shape

Use the same basic rhythm:

1. reader problem summary;
2. smallest useful mental model;
3. canonical code shape;
4. common trap;
5. rule of thumb.

Keep the API reference in `docs/api`. The guide may point to it, but should not become a giant function table.

## Live examples

Use `cljs-rf2` cells only when interaction teaches something static prose cannot. A live cell must run as written, be self-contained, and end with renderable hiccup.

## Naming

Reader-facing guide chapters use straight numbered titles: `NN - Title`. Do not create `23a`, `23b`, or other suffix chapters. If a topic grows too large, split it into the next real number or tighten the chapter.
