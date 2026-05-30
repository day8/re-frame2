# Writing interactive tutorials

This page is for people editing the guide. Readers learning re-frame2 should start with [01 - Introduction](01-introduction.md) and [03 - First app](03-first-app.md).

Interactive cells are useful when the reader learns by changing code and immediately seeing the result. They are not decorative screenshots with a slower loading path. Use them sparingly and make every cell earn its download.

## Cell kinds

| Fence | Behaviour | Use it for |
|---|---|---|
| ` ```cljs ` | Plain ClojureScript eval; prints the last value. | Teaching CLJS syntax or small pure expressions. |
| ` ```cljs-rf2 ` | Evaluates against re-frame2 and mounts the final hiccup value. | Teaching re-frame2 behaviour with a live app or component. |

A `cljs-rf2` cell must be self-contained: require aliases, register events and subscriptions, seed `app-db` if needed, define the view, and end with renderable hiccup such as `[counter]`.

## Authoring pattern

Write interactive sections as:

1. one sentence explaining the idea;
2. the live cell;
3. one concrete edit for the reader to try.

Example prompt: "Change `inc` to `(partial + 10)`, press `Ctrl-Enter`, and click `+`." The reader should know what to change and what result proves the idea.

## Gotchas

Do not wrap the whole cell in `(do ...)`; top-level `require` aliases need sibling top-level forms.

Do not end a `cljs-rf2` cell with a registration or bare value. The last form is mounted. Make it hiccup.

Use namespaced ids so two cells on the same page do not accidentally clobber each other's handlers. A page shares one live runtime.

Use `dispatch-sync` only for seed-before-render setup. The ordinary interaction path should use `dispatch`.
