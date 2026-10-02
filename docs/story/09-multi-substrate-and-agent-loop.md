# Choose a rendering host

Use the rendering layer your view was written for. Story's variant bodies
name a view id and inputs, so the scenario can stay the same while the host
changes how that view is mounted.

```clojure
;; Requires [re-frame.story :as rf.story].
(rf.story/reg-story :story.login-form
  {:component :login-form.views/login-card
   :args {:heading "Sign in"}
   :substrates #{:reagent}})
```

## Substrates

The `:substrates` set accepts `:reagent`, `:uix` and `:fresco`.
Each names the render function Story uses to embed a view. It is separate
from the adapter installed by `rf/init!`.

Story installs its Reagent renderer. Register the renderer for another
view layer at browser boot, where your app has that layer's dependencies.
For UIx:

```clojure
;; In a portable stories namespace.
(ns my-app.stories
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            #?(:cljs [uix.core :refer [$]])))

#?(:cljs
   (rf.story/register-substrate! :uix
     (fn [_variant-id view-id args]
       ($ (rf/view view-id) args))))
```

For Fresco, the shipped `tools/story/testbeds/fresco_counter/core.cljs`
uses this form at boot:

```clojure
;; Browser code requiring re-frame.core, re-frame.story and re-frame.fresco.
(rf.story/register-substrate! :fresco
  (fn [_variant-id view-id args]
    (rf.fresco/as-element [(rf/view view-id) args])))
```

The shell mounts the view under the variant's frame context. The Fresco
testbed installs Reagent's adapter for the shell and embeds Fresco views
inside it; it does not need a second React root.

A variant inherits the story's substrates unless it declares its own.
Naming several renders a cell for each. Opt into multiple layers only when
the registered component can render through each selected function;
the set does not translate a Reagent component into UIx or Fresco.

## Two hosts

The browser shell and a JVM test are separate processes with separate
registries and frames. Requiring the same `.cljc` stories gives them the
same declarations; it does not share live state.

Run machine, subscription and effect tests on the JVM with the fixture in
[the testing recipe](04-the-variant-is-a-test.md#using-story-from-tests).
Use the browser for DOM interaction and for Xray's live view of a selected
variant. Keep the whole diagnosis in the host that produced the failure.

## Advanced

Story-MCP exposes registration and execution to external tools, including
an assistant. Its stdio server runs a JVM catalogue loaded from your portable
stories namespace; it is not a connection to the open browser shell.
Browser-only observations such as an axe scan are unavailable there.

The [MCP reference](api/mcp-surface.md#running-the-server) shows how to launch
the server. Its write tools are disabled unless explicitly enabled.
Changes registered in either live host still need to be saved in source
to survive a restart.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Red cell says a substrate is unavailable | Its render function was not registered | Call `register-substrate!` at browser boot. |
| The view cannot render under a selected layer | The component and renderer use different conventions | Select the matching layer or register the appropriate view/bridge. |
| A JVM tool cannot find a browser variant | The hosts have independent registries | Require its portable declarations in the JVM, or inspect it in the browser. |
| A JVM tool cannot read an axe result | The observation exists only in the browser | Scan and inspect the variant in its browser host. |
