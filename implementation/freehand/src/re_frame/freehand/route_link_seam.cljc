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
  host event ever enters the server tree.

  The one place Freehand adds a contract of its own is `:on-click`. The
  routing seam's compatibility signature takes an optional user fn and
  calls it, and Freehand's own grammar teaches `:on-click [:app/event …]`
  as ordinary intent data — so the vector an author reasonably writes
  would arrive at routing as something to invoke. [[veto]] is the
  translation at that boundary: it is where the accepted grammar is
  named, narrowed and enforced, before anything is handed across."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.freehand.events :as events]
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

     `veto` is the caller's pre-navigation callback as [[veto]] normalized
     it — a plain one-argument fn, or nil. Routing invokes it exactly
     once, before its own decision, and stands down when it prevented the
     default.

     Reached only from the rendered anchor's `:on-click`, i.e. AFTER
     [[link-model]] already proved routing present at render; a hook that
     vanished between render and click (dev hot-reload of the routing
     artefact) degrades to native navigation — the browser follows the
     real `href` — rather than throwing at click time."
     [e veto render-frame payload native?]
     (when-let [f (late-bind/get-fn :routing/activate-link!)]
       (f e veto render-frame payload native?))))

;; ---------------------------------------------------------------------------
;; `:on-click` — the imperative pre-navigation seam
;; ---------------------------------------------------------------------------

(def legal-on-click-forms
  "The CLOSED roster `v/route-link`'s `:on-click` accepts: a plain
  function, a `v/handler`, or nothing.

  Deliberately NARROWER than the general Freehand event position, and the
  narrowing is the contract rather than an omission. A route click already
  produces the ONE routing intent — `:rf.route/url-requested`, and
  everything that follows from it — so a second intent site on the same
  click would mean one user action yielding two semantic events, which is
  the law Freehand's event plane exists to keep. What is genuinely needed
  here is the other thing: imperative work that runs BEFORE the navigation
  decision and can stop it (`.preventDefault`, a confirm dialog, an
  analytics ping). That is exactly `v/handler`'s declared role, and a bare
  function is its unceremonious spelling."
  [:bare-fn :v-handler :nil])

(defn- on-click-tag
  "A short HOST-NEUTRAL description of the offending `:on-click` value,
  for the human sentence of the diagnostic. Never the value itself
  (Spec 015 §Data-Classification)."
  [v]
  (cond
    (vector? v)          "an event vector"
    (map? v)             "an event options map"
    (events/callback? v) (str "a v/" (name (events/callback-role v)))
    :else                (str "a " (events/value-tag v))))

(defn- reject-on-click!
  [v]
  (error/throw-error!
    :rf.error/view-bad-event
    'v/route-link
    (str "v/route-link's :on-click is the imperative PRE-NAVIGATION seam — it runs before "
         "the routing decision and may veto it with .preventDefault — so it takes a plain "
         "function or a v/handler, and nothing else; this one carries " (on-click-tag v) ". "
         "The click already produces the one routing intent (:rf.route/url-requested), so an "
         "application reaction belongs behind that routing event or its transition, not at a "
         "second intent site on the same click. Imperative work — preventDefault, a confirm "
         "dialog, an analytics ping — is what v/handler is for.")
    {:recovery :use-a-plain-fn-or-v-handler
     :extra    {:legal-forms legal-on-click-forms
                :value       (error/diag-value-summary v)}}))

(defn veto
  "Normalize a caller's `:on-click` into the pre-navigation callback
  routing invokes — a plain one-argument fn, or nil — or reject it.

  Total over [[legal-on-click-forms]] and loud outside it, on BOTH hosts
  and at RENDER: a value routing would call is decided here, at the site
  that wrote it and with the render stack, rather than at a detached click
  where the same mistake is an `IFn` collection lookup keyed by a
  MouseEvent, a `deftype` that is not callable, or a silently lost event.

  `v/handler` is unwrapped to the function it carries. That unwrapping is
  the whole reason the form is accepted: a roster callback is deliberately
  not `IFn`, so routing's compatibility signature cannot call one, and
  fencing without translating would have left the declared imperative form
  as the one thing that does not work here."
  [on-click]
  (cond
    (nil? on-click)      nil
    (events/callback? on-click)
    (if (= :handler (events/callback-role on-click))
      (events/callback-fn on-click)
      (reject-on-click! on-click))
    (fn? on-click)       on-click
    :else                (reject-on-click! on-click)))

;; ---------------------------------------------------------------------------
;; The anchor
;; ---------------------------------------------------------------------------

(def ^:private control-keys
  "The keys `v/route-link` owns. Everything else on the props map is a
  passthrough HTML attribute and reaches the `<a>` untouched — that is
  what makes `:class`, `:title`, `:aria-label`, `:target` and `:download`
  work without the view enumerating them.

  `:on-click` is owned rather than passed through: the caller's value is
  the pre-navigation veto ([[veto]]), and what reaches the element is the
  framework's activation closure with that veto inside it."
  [:to :params :query :fragment :on-click :children])

(defn anchor
  "The body of the `v/route-link` descriptor: one real `<a>`.

  Captures the render-time frame FIRST — a browser click fires long after
  render, by which point the render-time frame scope has unwound, so
  resolving the frame at click time would raise `:rf.error/no-frame-context`
  or route to the wrong frame. Capturing here pins the navigation to the
  frame that RENDERED the link, and fails at the render site (with the
  render stack) rather than at a detached click.

  Then validates the caller's `:on-click` against [[veto]] — on both
  hosts, so a form the browser would misuse is rejected by the server
  render too — asks routing for the link model, spreads the caller's
  passthrough attributes onto the anchor, and lets the framework-owned
  `:href` win. On ClojureScript the anchor carries the activation closure;
  on the JVM it does not — an SSR shell has no click to intercept.

  The veto needs no commit machinery of its own: the anchor's `:on-click`
  is an ORDINARY Freehand event position, so the closure below is the
  site's committed body, and a re-render that changes the caller's
  callback changes what the site's stable proxy runs. Reading the veto
  out of that closure is therefore reading the committed one."
  [{:keys [on-click children] :as props}]
  (let [render-frame (frame/require-current-frame!
                       :route-link
                       {:where 'v/route-link})
        veto-fn      (veto on-click)
        {:keys [href payload native?]} (link-model (dissoc props :on-click :children)
                                                   render-frame)
        attrs (assoc (apply dissoc props control-keys) :href href)
        attrs #?(:cljs (assoc attrs
                              :on-click
                              (fn [e]
                                (activate! e veto-fn render-frame payload native?)))
                 :clj  attrs)]
    (into [:a attrs] children)))
