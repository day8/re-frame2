# Install Story

Add Story to the development classpath, load your story registrations and
mount the shell on a DOM node. A generated re-frame2 app already includes
this wiring.

```clojure
;; deps.edn: these paths assume the re-frame2 checkout is beside your app
{:deps
 {day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
  day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}}
 :aliases
 {:dev
  {:extra-deps
   {day8/re-frame2-story {:local/root "../re-frame2/tools/story"}}}}}
```

The pre-alpha artifacts use local checkouts. Resolve all re-frame2 artifacts
from the same checkout; `:local/root` paths are relative to your `deps.edn`.
Keep Story in a development alias.

Include that alias in your existing `shadow-cljs.edn`:

```clojure
;; Keep any existing aliases, targets and module configuration.
{:deps {:aliases [:shadow :dev]}}
```

The shell embeds Xray. Install its chart dependencies beside your existing
`react` and `react-dom` packages:

```bash
npm install --save-dev @xyflow/react@12.4.2 elkjs@0.11.1
```

## Mount a development entry

This entry dedicates its host page to Story:

```clojure
;; src/my_app/stories_dev.cljs
(ns my-app.stories-dev
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [my-app.stories]))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf.story/mount-shell! (js/document.getElementById "app")))
```

`my-app.stories` is your registration namespace. It requires the app's events
and subscriptions, and its views in ClojureScript. Loading it executes its
`reg-story` and `reg-variant` forms. The first registration installs Story's
built-in vocabulary; there is no separate installation call.

Point your development browser module's `:init-fn` at
`my-app.stories-dev/run`. The host page needs the matching node:

```html
<div id="app"></div>
```

The shell takes a DOM node, with no options argument. URL query parameters
choose its initial variant, workspace and presentation. If Story shares a
page with your app, let your development entry's router mount it on
`#/stories` and mount the app elsewhere, as the generated app does.

## Keep it in the development build

Load this entry and the Story alias in development builds. Do not require
the entry from the production app. A build that sets
`re-frame.story.config/enabled?` to `false` also elides the registrations
and makes `mount-shell!` return without touching the DOM. A
[static Story catalogue](08-static-builds.md) is a separate build.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Cannot resolve `re-frame.story` | The development alias is absent from the compiler classpath | Include `:dev` in shadow-cljs's `:deps` aliases. |
| Missing `@xyflow/react` or `elkjs` | Xray's chart dependencies are absent | Install the two packages above in the app's npm project. |
| The shell has no stories | The registrations were never loaded | Require your stories namespace from this entry. |
| Mount returns `nil` | The DOM node is absent, or Story is disabled in this build | Check the node id and the build's `enabled?` define. |
| `:rf.error/no-adapter-installed` on a run | No substrate adapter was installed | Call `rf/init!` before mounting; tests can install the plain-atom adapter through a fixture. |
| Your view looks different from the app | It depends on CSS or a provider supplied by the app's outer shell | Load that CSS and supply the provider with a decorator. |
