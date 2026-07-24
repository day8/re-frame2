(ns re-frame.ui.route-link-seam
  "Runtime seam-consumption helpers for the compiled `re-frame.ui/route-link`
  defview (rf2-vxgfnd.95.5). (The `-seam` suffix avoids a CLJS ns/var clash with
  the `re-frame.ui/route-link` view var.)

  `route-link` ships as an ORDINARY compiled `defview` in the `re-frame.ui`
  facade — it gets JSX emission, the generated prop comparator, JVM-tree parity,
  and dev identity for free, and is NOT a compiler intrinsic. All routing
  knowledge lives in the OPTIONAL `re-frame.routing` artefact behind two
  substrate-neutral late-bound hooks routing publishes and the defview consumes
  through this namespace — two for the click, two for the `:prefetch :intent`
  warm-up, each pair split the same way:

    `:routing/link-model`     — PURE, both hosts; the whole link calculation
                                (strategy-encoded href, dispatch payload, native?).
    `:routing/activate-link!` — CLJS only; THE router-attributed click decision.
    `:routing/prefetch-payload`    — PURE, both hosts; the
                                `[:rf.route/prefetch {address}]` vector for a link
                                that asked for it, or nil.
    `:routing/prefetch-on-intent!` — CLJS only; the intent dispatch that warms
                                the destination.

  Consuming the seam through `re-frame.late-bind` (core) keeps the packaging
  graph `ui -> core late-bind <- routing`: neither optional artefact statically
  requires the other (Conventions §Packaging), so there is no `re-frame.ui →
  routing` dependency. When the routing artefact is absent both hooks are
  unbound and rendering a `ui/route-link` fails loud with the standard
  `:rf.error/routing-artefact-missing`, naming the artefact + Maven coord + the
  link site — a plain `[:a]` remains available for intentional browser-native
  navigation."
  (:require [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private routing-artefact
  ;; The optional-artefact identity the fail-loud missing-hook error reports.
  ;; Mirrors `re-frame.core-routing/routing-artefact` (core owns its private
  ;; copy for the `rf/*` wrappers); the ui artefact carries its own so it names
  ;; the missing routing artefact at the link site without reaching into core
  ;; internals.
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

(defn link-model
  "Resolve the `:routing/link-model` hook and compute the link model for one
  `ui/route-link` render — `{:href :payload :native?}`. `target` is the `:to` /
  `:params` / `:query` / `:fragment` control keys plus the native-handling attrs
  (`:target` / `:download`); `render-frame` is the view's captured render-frame
  id. Fails loud with `:rf.error/routing-artefact-missing` (naming the link's
  `:to`) when routing is not loaded — the render-time artefact-missing gate, so a
  `ui/route-link` never renders a half-formed anchor."
  [target render-frame]
  ((late-bind/require-fn! :routing/link-model
                          're-frame.ui/route-link
                          routing-artefact
                          {:to (:to target)})
   target render-frame))

(defn prefetch-payload
  "Resolve the `:routing/prefetch-payload` hook and compute the
  `[:rf.route/prefetch {address}]` dispatch vector for one `ui/route-link`
  render — nil when the link did not ask for a prefetch. PURE, both hosts.
  The view never reads `:prefetch` itself: which value opts a link in, and
  which address it warms, is routing's law and stays behind the hook."
  [target]
  ((late-bind/require-fn! :routing/prefetch-payload
                          're-frame.ui/route-link
                          routing-artefact
                          {:to (:to target)})
   target))

#?(:cljs
   (defn prefetch-on-intent!
     "Resolve the `:routing/prefetch-on-intent!` hook and warm the link's
     destination on one credible-intent event (hover / focus / touch-start).
     `caller-handler` is the caller's handler at that position, which routing
     runs FIRST — the prefetch composes with the author's hover work rather
     than replacing it. Reached only from a rendered anchor, i.e. AFTER
     `prefetch-payload` already proved routing present at render; a hook that
     vanished between render and hover (dev hot-reload of the routing artefact)
     simply warms nothing, exactly as `activate!` degrades to native
     navigation rather than throwing at event time."
     [e caller-handler render-frame payload]
     (when-let [f (late-bind/get-fn :routing/prefetch-on-intent!)]
       (f e caller-handler render-frame payload))))

#?(:cljs
   (defn activate!
     "Resolve the `:routing/activate-link!` hook and run the router-attributed
     click decision for a `ui/route-link` anchor. Reached only from the rendered
     anchor's `:on-click`, i.e. AFTER `link-model` already proved routing present
     at render; a hook that vanished between render and click (dev hot-reload of
     the routing artefact) degrades to native navigation (the browser follows the
     real `href`) rather than throwing at click time."
     [e on-click render-frame payload native?]
     (when-let [f (late-bind/get-fn :routing/activate-link!)]
       (f e on-click render-frame payload native?))))
