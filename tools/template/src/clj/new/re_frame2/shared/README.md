# {{name}}

[![Built with re-frame2](https://img.shields.io/badge/built%20with-re--frame2-blue.svg)](https://github.com/day8/re-frame2)
[![Substrate]({{substrate-badge-url}})](https://github.com/day8/re-frame2/tree/main/implementation/adapters/{{substrate}})
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A new [re-frame2](https://github.com/day8/re-frame2) application,
scaffolded from `day8/clj-template.re-frame2`.

Substrate: **{{substrate}}**.

## Run the development build

```sh
npm install
npx shadow-cljs watch app
```

Then open <http://localhost:8280> in your browser.

The first build does take a moment — shadow-cljs is downloading
dependencies and compiling the ClojureScript. Subsequent rebuilds (on
file save) are fast.

## Hot reload

`shadow-cljs watch` rebuilds on every file save and re-invokes
`{{namespace}}.core/init` (the `:devtools/after-load` hook in
`shadow-cljs.edn`). The entry fn is **idempotent**: re-frame2's
`rf/init!` accepts being called repeatedly, the registrar
re-registers in place, and `dispatch-sync [:counter/initialise]`
re-seeds app-db.

Under the hood re-frame2 guarantees a **per-frame re-init contract**:
each call to `init!` on a frame snapshots the registrar, re-installs
the adapter, and resets the frame's app-db to a known state — your
handlers and subs come back wired to the new code without leaking
state from the previous build. Add new `reg-event-db` / `reg-sub` /
`reg-view` forms and they show up live; rename or remove a handler
and the next reload drops the old registration.

Stack traces in dev get click-to-source via the `:rf.trace/trigger-handler`
preload (auto-wired when the Causa preload — `day8.re-frame2-causa.preload`
— is on the classpath) — clicking a frame in the dev console jumps
straight to the offending form.

## Build for release

```sh
npx shadow-cljs release app
```

Production output lands in `resources/public/js/`. Serve `resources/public/`
from any static host.

## Production builds

`shadow-cljs release` already sets `:closure-defines {goog.DEBUG false}` —
asserts compile away, dev-only branches drop, the bundle shrinks. If
you want to verify or override it, the explicit form is:

```clojure
;; shadow-cljs.edn :builds :app
:release {:compiler-options {:closure-defines {goog.DEBUG false}}}
```

For production timing data, flip re-frame2's performance instrumentation
on at compile time. Add this block alongside `:closure-defines` (it's
off by default — turning it on costs a few cycles per dispatch):

```clojure
;; :compiler-options {:closure-defines {re-frame.performance/enabled? true}}
```

## REPL workflow

Once `npx shadow-cljs watch app` is running, connect your editor to
the shadow-cljs nREPL:

- **Calva (VS Code):** `Calva: Connect to a Running REPL` →
  `shadow-cljs` → pick the `:app` build.
- **CIDER (Emacs):** `M-x cider-jack-in-cljs` → `shadow-cljs` →
  `:app`.
- **Cursive (IntelliJ):** add a `Clojure REPL → Remote` config
  pointing at the nREPL port shadow-cljs prints on startup.

shadow-cljs prints the nREPL port on its first line of output
(default 7002 if you set `:nrepl {:port 7002}` in `shadow-cljs.edn`,
or a randomly-assigned port otherwise). Once connected, evaluate
forms in `dev/scratch.cljs` to drive the running app from the REPL.

## Run tests

```sh
npx shadow-cljs compile test
node out/node-test.js
```

The `:test` build in `shadow-cljs.edn` is a `:node-test` target that
picks up every `*_test.cljs` namespace under `test/` and runs it via
`cljs.test`. The scaffold ships one such file —
`test/{{nested-dirs}}/events_test.cljs` — exercising
`:counter/initialise` and `:counter/increment` against the plain-atom
reactive substrate (no DOM needed). Add more `*_test.cljs` files
alongside it as your app grows.

## Project layout

```
.
├── deps.edn                 ; Clojure deps — re-frame2 + the {{substrate}} adapter
├── shadow-cljs.edn          ; CLJS build config — watch / release / test targets
├── package.json             ; npm deps (react, react-dom, shadow-cljs runtime)
├── README.md                ; this file
├── .gitignore               ; CLJS standard
├── .editorconfig            ; 2-space indent, LF, trim trailing whitespace
├── .clj-kondo/
│   └── config.edn           ; linter config (empty by default)
├── .cljfmt.edn              ; cljfmt formatter config — `clojure -M:cljfmt check` / `fix`
├── .lefthook.yml            ; pre-commit format + lint hook (see lefthook.dev/installation)
├── resources/public/
│   ├── index.html           ; host page; loads shadow-cljs's compiled output
│   └── css/app.css          ; minimal plain CSS — body / button / h1
├── src/{{nested-dirs}}/
│   ├── core.cljs            ; entry point — mounts the root view
│   ├── events.cljs          ; `:counter/initialise`, `:counter/increment` handlers
│   ├── subs.cljs            ; `:counter/value` subscription
│   └── views.cljs           ; the counter view ({{substrate}})
├── test/{{nested-dirs}}/
│   └── events_test.cljs     ; substrate-agnostic event-handler tests
└── dev/
    ├── user.clj             ; JVM-side `(user/refresh)` entry
    └── scratch.cljs         ; REPL scratch namespace for `(rf/dispatch …)` experiments
```

## What's in the scaffold

A minimal **counter app** demonstrates the re-frame2 dataflow end-to-end:

- `:counter/value` is the slice of app-db (default `0`).
- `:counter/increment` is the event handler — pure `(update db ... inc)`.
- `:counter/value` is the subscription that derives the displayed number.
- `views.cljs` renders a `<button>` that dispatches the increment event
  plus a `<span>` that reads the subscription.

This is the same counter walked through in
[the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/guide) —
the shape is intentionally identical so the worked example matches what
you read.

## Next steps

- Read the [the re-frame2 guide](https://github.com/day8/re-frame2/tree/main/docs/guide).
- Browse the
  [Pattern Specification](https://github.com/day8/re-frame2/tree/main/spec)
  for the contract.
- Check out the [worked examples](https://github.com/day8/re-frame2/tree/main/examples).

## Migrating from re-frame v1?

If you are porting an existing re-frame app, see:

- [`spec/MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/spec/MIGRATION.md)
  — the v1→v2 migration contract.
- The [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration)
  skill — Claude Code workflow that walks the port mechanically and
  flags the cases that need a human decision.

v2 introduces a per-frame architecture, schema-typed app-db, managed
HTTP effects, and the `reg-view` macro on the Reagent adapter (which
both defines a view symbol and registers it under `(keyword *ns* name)`,
auto-injecting `dispatch` / `subscribe` as frame-scoped lexical
bindings). The migration skill walks you through each of those v1→v2
shape changes mechanically.

## Future: skill install

re-frame2 ships first-class Claude Code skills (under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills))
that pair-program against a running app, drive scaffolds, and walk
the v1→v2 migration. Once those publish to the Claude Code skills
marketplace this template will install them into your project on
scaffold; until then, clone the `re-frame2` repo and copy the
relevant skill directory into your `.claude/skills/`.

## License

Generated by `day8/clj-template.re-frame2` — see the template repo for
licensing of the scaffold. Your project's code is yours; pick whatever
license you like.
