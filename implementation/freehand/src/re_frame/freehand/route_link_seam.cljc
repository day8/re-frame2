(ns re-frame.freehand.route-link-seam
  "The anchor `v/route-link` renders, and the two late-bound hooks it
  reads to get it. (The `-seam` suffix avoids a ClojureScript ns/var
  clash with the `re-frame.freehand/route-link` view var.)

  `route-link` is ORDINARY framework view code — a `v/defview` like any
  application view, not a compiler form and not a second routing
  contract. [`spec/012-Routing.md`](../../../../../spec/012-Routing.md)
  continues to own href and click semantics; what lives here is the
  descriptor's body: assemble a real `<a>`, put the routing-owned href on
  it, and hand the click back to routing.

  All routing knowledge stays in the OPTIONAL `re-frame.routing`
  artefact, behind two substrate-neutral late-bound hooks routing
  publishes and this namespace consumes:

    `:routing/link-model`     — PURE, both hosts; the whole link
                                calculation (strategy-encoded href,
                                dispatch payload, native?).
    `:routing/activate-link!` — ClojureScript only; THE router-attributed
                                click decision.

  Consuming them through `re-frame.late-bind` (core) keeps the packaging
  graph `freehand -> core late-bind <- routing`: neither optional
  artefact statically requires the other (Conventions §Packaging). When
  routing is absent both hooks are unbound and rendering a `v/route-link`
  fails loud with `:rf.error/routing-artefact-missing`, naming the
  artefact, its Maven coordinate, and the link site — a plain `[:a]`
  remains available for intentional browser-native navigation.

  Host split: the ClojureScript render attaches the activation closure;
  the JVM render emits the handler-free path-form shell SSR serves, so no
  host event ever enters the server tree."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private routing-artefact
  ;; The optional-artefact identity the fail-loud missing-hook error
  ;; reports. Freehand carries its own copy so the diagnostic names the
  ;; missing artefact AT THE LINK SITE without reaching into core
  ;; internals for the `rf/*` wrappers' equivalent.
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

(defn link-model
  "Resolve the `:routing/link-model` hook and compute the link model for
  one `v/route-link` render — `{:href :payload :native?}`. `target` is
  the `:to` / `:params` / `:query` / `:fragment` control keys plus the
  native-handling attrs (`:target` / `:download`); `render-frame` is the
  frame captured at render.

  Fails loud with `:rf.error/routing-artefact-missing` (naming
  `v/route-link` and the link's `:to`) when routing is not loaded. This
  is the render-time artefact-missing gate: a `v/route-link` never
  renders a half-formed anchor, and never renders a dead one."
  [target render-frame]
  ((late-bind/require-fn! :routing/link-model
                          'v/route-link
                          routing-artefact
                          {:to (:to target)})
   target render-frame))

#?(:cljs
   (defn activate!
     "Resolve the `:routing/activate-link!` hook and run the
     router-attributed click decision for a `v/route-link` anchor.

     Reached only from the rendered anchor's `:on-click`, i.e. AFTER
     [[link-model]] already proved routing present at render; a hook that
     vanished between render and click (dev hot-reload of the routing
     artefact) degrades to native navigation — the browser follows the
     real `href` — rather than throwing at click time."
     [e on-click render-frame payload native?]
     (when-let [f (late-bind/get-fn :routing/activate-link!)]
       (f e on-click render-frame payload native?))))

;; ---------------------------------------------------------------------------
;; The anchor
;; ---------------------------------------------------------------------------

(def ^:private control-keys
  "The keys `v/route-link` owns. Everything else on the props map is a
  passthrough HTML attribute and reaches the `<a>` untouched — that is
  what makes `:class`, `:title`, `:aria-label`, `:target` and `:download`
  work without the view enumerating them."
  [:to :params :query :fragment :on-click :children])

(defn anchor
  "The body of the `v/route-link` descriptor: one real `<a>`.

  Captures the render-time frame FIRST — a browser click fires long after
  render, by which point the render-time frame scope has unwound, so
  resolving the frame at click time would raise `:rf.error/no-frame-context`
  or route to the wrong frame. Capturing here pins the navigation to the
  frame that RENDERED the link, and fails at the render site (with the
  render stack) rather than at a detached click.

  Then asks routing for the link model, spreads the caller's passthrough
  attributes onto the anchor, and lets the framework-owned `:href` win.
  On ClojureScript the anchor carries the activation closure; on the JVM
  it does not — an SSR shell has no click to intercept."
  [{:keys [on-click children] :as props}]
  (let [render-frame (frame/require-current-frame!
                       :route-link
                       {:where 'v/route-link})
        {:keys [href payload native?]} (link-model (dissoc props :on-click :children)
                                                   render-frame)
        attrs (assoc (apply dissoc props control-keys) :href href)
        attrs #?(:cljs (assoc attrs
                              :on-click
                              (fn [e]
                                (activate! e on-click render-frame payload native?)))
                 :clj  attrs)]
    (into [:a attrs] children)))
