# Static builds

A static build packages your registered Story catalogue into a directory of
plain files you can publish anywhere. Use it for design review, documentation
previews, or artifact hosting where the reviewer should not need your dev
server. It is the Story shell itself, with every variant's canvas, docs,
controls and status, not a set of screenshots.

It takes an entry namespace, a build, a host page, and one script. The names
below continue the `my-app` from [Install Story](index.md#install-story).

**1. An entry namespace that mounts only the shell.** Your dev entry point
mounts Story on the `#/stories` route beside your app. A published catalogue has
no app beside it, so it gets its own entry, `src/my_app/story_static.cljs`:

```clojure
(ns my-app.story-static
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [my-app.stories]))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf.story/mount-shell! (js/document.getElementById "app")))
```

**2. A build for it**, beside your app's build in `shadow-cljs.edn`:

```clojure
:story-static/my-app
{:target           :browser
 :output-dir       "out/story-static/my-app"
 :asset-path       "."
 :compiler-options {:closure-defines {re-frame.story.config/static-mode? true}}
 :modules          {:main {:init-fn my-app.story-static/run}}}
```

A `release` already drops shadow-cljs's dev-server connection. The
`static-mode?` define drops Story's own dev-time behaviour: the shell stops
polling for new registrations and does not pop the first-visit help overlay at
your readers. `:asset-path "."` keeps the bundle's references relative, so the
site works under any URL prefix.

**3. A host page**, `resources/story-static.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>my-app stories</title>
  <style>html, body, #app { height: 100%; margin: 0; }</style>
</head>
<body>
  <div id="app"></div>
  <script src="main.js"></script>
</body>
</html>
```

**4. A script** in `package.json` that releases the build and puts the host page
beside the bundle as `index.html`:

```json
"story:build": "shadow-cljs release story-static/my-app && node -e \"require('fs').copyFileSync('resources/story-static.html', 'out/story-static/my-app/index.html')\""
```

The copy is spelled in Node so the same line runs under Windows `cmd` and a
POSIX shell. `npm run story:build` is also the command the Share dialog's
**Static build** row copies.

Run `npm run story:build`. After the `:advanced` compile, which takes a minute
or so, `out/story-static/my-app/` holds `index.html`, `main.js`, and
shadow-cljs's `manifest.edn`. That directory is the site: open its `index.html`
straight from disk, serve it with any static file server, or publish the
directory as-is to GitHub Pages, Netlify, S3, or any other static host. The
generator template's `.gitignore` already ignores `out/`.

A variant selected in the published catalogue still writes itself into the
address bar, so a link copied from the static site opens on that variant. What
the site leaves out is everything that only makes sense beside a live compiler:
hot reload, the registration poll, the first-visit overlay, and open-in-editor,
because a published bundle must not carry a path from the machine that built
it. Subscription pins are compiled out too, so a variant that relies on
`:sub-overrides` renders its real subscription values there
([chapter 3](03-fidelity-ladder.md#rung-3-subscription-overrides)), and the
published site carries no Xray.

