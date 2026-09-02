# {{name}}

A [re-frame2](https://github.com/day8/re-frame2) application on the
{{substrate-label}} substrate, scaffolded by `day8/re-frame2-template`.

## Run

```sh
npm install
npx shadow-cljs watch app
```

Open <http://localhost:8280>. Edit `src/{{nested-dirs}}/views.cljs` and
save: the page re-renders in place, keeping its state.

## Test

```sh
npm test
```

Runs `test/{{nested-dirs}}/events_test.cljs` under Node — the event
handlers and the subscription, no browser needed. Add more `*_test.cljs`
files beside it.

## Release

```sh
npm run release
```

The optimised bundle lands in `resources/public/js/main.js`; serve
`resources/public/` from any static host.

## What is here

- `src/{{nested-dirs}}/core.cljs` — installs the adapter and mounts the
  app. `init` runs once, when the bundle loads. `mount!` is the
  `^:dev/after-load` hook shadow-cljs re-runs after every save; it
  renders into the one React root, and `rf/frame-root` reuses the live
  frame without re-seeding, so a reload leaves app-db as you left it.
- `events.cljs` — `:counter/initialise` seeds app-db and
  `:counter/increment` bumps it. Pure functions of the coeffects map.
- `subs.cljs` — `:counter/value`, a pure extractor over app-db.
- `views.cljs` — the counter: one button that dispatches, one span that
  subscribes.

This is the same counter the guide walks through. Replace it with your
first feature.

## Next steps

- [The re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/core)
  — events, subscriptions, views, effects and frames.
- [Xray](https://github.com/day8/re-frame2/blob/main/docs/xray/01-installation.md)
  — the in-app devtools panel. Four edits attach it to your dev build.
- [Story](https://github.com/day8/re-frame2/blob/main/docs/story/index.md)
  — the component playground. A dev alias, a require and a mount.
- [Worked examples](https://github.com/day8/re-frame2/tree/main/examples).
