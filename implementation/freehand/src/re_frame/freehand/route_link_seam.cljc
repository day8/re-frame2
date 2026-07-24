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
  artefact, behind four substrate-neutral late-bound hooks routing
  publishes and this namespace consumes — two for the click, two for the
  `:prefetch :intent` warm-up, and each pair split the same way:

    `:routing/link-model`        — PURE, both hosts; the whole link
                                   calculation (strategy-encoded href,
                                   dispatch payload, native?).
    `:routing/activate-link!`    — ClojureScript only; THE router-attributed
                                   click decision.
    `:routing/prefetch-payload`  — PURE, both hosts; the
                                   `[:rf.route/prefetch {address}]` vector
                                   for a link that asked for it, or nil.
    `:routing/prefetch-on-intent!` — ClojureScript only; the intent
                                   dispatch that warms the destination.

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

  The one place Freehand adds a contract of its own is the callback
  positions the framework OWNS. The routing seam's compatibility
  signatures take an optional user fn and call it, and Freehand's own
  grammar teaches `:on-click [:app/event …]` as ordinary intent data — so
  the vector an author reasonably writes would arrive at routing as
  something to invoke. [[veto]] and [[intent-callback]] are the
  translation at that boundary: they are where the accepted grammar is
  named, narrowed and enforced, before anything is handed across. The
  click position is always owned; the three [[intent-keys]] are owned
  only by a link that asked for `:prefetch :intent`, and stay ordinary
  open event positions on every other link."
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

(defn prefetch-payload
  "Resolve the `:routing/prefetch-payload` hook and compute the
  `[:rf.route/prefetch {address}]` dispatch vector for one `v/route-link`
  render — nil when the link did not ask for a prefetch.

  PURE, and asked on BOTH hosts, because a nil answer is what makes the
  three [[intent-keys]] ordinary open event positions again: the JVM
  render attaches no handler but still has to know whether it owns those
  keys, or the server shell would emit an attribute the browser render
  strips. The descriptor never reads `:prefetch` itself — which values
  opt a link in, and which address they warm, is routing's law and stays
  behind the hook."
  [target]
  ((late-bind/require-fn! :routing/prefetch-payload
                          'v/route-link
                          routing-artefact
                          {:to (:to target)})
   target))

#?(:cljs
   (defn prefetch-on-intent!
     "Resolve the `:routing/prefetch-on-intent!` hook and warm the link's
     destination on one credible-intent event.

     `caller-handler` is the caller's value at that position as
     [[intent-callback]] normalized it — a plain one-argument fn, or nil.
     Routing runs it FIRST and then dispatches, so the caller's hover
     work composes with the prefetch instead of being replaced by it.

     Reached only from the rendered anchor's intent handlers, i.e. AFTER
     [[prefetch-payload]] already proved routing present at render; a hook
     that vanished between render and hover (dev hot-reload of the routing
     artefact) simply warms nothing, exactly as [[activate!]] degrades to
     native navigation rather than throwing at event time."
     [e caller-handler render-frame payload]
     (when-let [f (late-bind/get-fn :routing/prefetch-on-intent!)]
       (f e caller-handler render-frame payload))))

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
;; The callback positions the framework OWNS — `:on-click`, and the three
;; intent positions a `:prefetch :intent` link binds
;; ---------------------------------------------------------------------------

(def legal-on-click-forms
  "The CLOSED roster `v/route-link` accepts at a callback position it owns:
  a plain function, a `v/handler`, or nothing.

  Deliberately NARROWER than the general Freehand event position, and the
  narrowing is the contract rather than an omission. A route click already
  produces the ONE routing intent — `:rf.route/url-requested`, and
  everything that follows from it — so a second intent site on the same
  click would mean one user action yielding two semantic events, which is
  the law Freehand's event plane exists to keep. What is genuinely needed
  here is the other thing: imperative work that runs BEFORE the navigation
  decision and can stop it (`.preventDefault`, a confirm dialog, an
  analytics ping). That is exactly `v/handler`'s declared role, and a bare
  function is its unceremonious spelling.

  The same roster governs the three [[intent-keys]] on a link that asked
  for `:prefetch :intent`, for the same mechanical reason: the framework
  runs the caller's handler itself, so it has to be something callable."
  [:bare-fn :v-handler :nil])

(def intent-keys
  "The three DOM positions a `:prefetch :intent` link binds — pointer
  hover, focus and touch-start, the credible-intent triggers Spec 012
  names. FRAMEWORK-OWNED on such a link and on no other: the anchor
  installs its own handler at each, running the caller's FIRST, so the
  prefetch composes with the author's hover work rather than replacing it.

  A link that did not opt in leaves all three exactly as it found them —
  ordinary open Freehand event positions like every other `:on-*`."
  [:on-mouse-enter :on-focus :on-touch-start])

(defn- value-description
  "A short HOST-NEUTRAL description of an offending callback value, for
  the human sentence of the diagnostic. Never the value itself
  (Spec 015 §Data-Classification)."
  [v]
  (cond
    (vector? v)          "an event vector"
    (map? v)             "an event options map"
    (events/callback? v) (str "a v/" (name (events/callback-role v)))
    :else                (str "a " (events/value-tag v))))

(defn- reject!
  "Refuse `v` at an owned callback position, with `sentence` explaining
  why THAT position is narrow. One error id, one recovery, one roster —
  the two owned positions differ in their reason, not in their shape."
  [prop v sentence]
  (error/throw-error!
    :rf.error/view-bad-event
    'v/route-link
    sentence
    {:recovery :use-a-plain-fn-or-v-handler
     :extra    {:legal-forms legal-on-click-forms
                :prop        prop
                :value       (error/diag-value-summary v)}}))

(defn- normalize-callback
  "The shared normalization every owned callback position runs: nil stays
  nil, a plain fn passes, a `v/handler` is unwrapped to the function it
  carries, and everything else is refused through `reject`.

  The unwrapping is the whole reason `v/handler` is accepted: a roster
  callback is deliberately not `IFn`, so routing's compatibility signature
  cannot call one, and fencing without translating would have left the
  declared imperative form as the one thing that does not work here."
  [v reject]
  (cond
    (nil? v)             nil
    (events/callback? v) (if (= :handler (events/callback-role v))
                           (events/callback-fn v)
                           (reject v))
    (fn? v)              v
    :else                (reject v)))

(defn veto
  "Normalize a caller's `:on-click` into the pre-navigation callback
  routing invokes — a plain one-argument fn, or nil — or reject it.

  Total over [[legal-on-click-forms]] and loud outside it, on BOTH hosts
  and at RENDER: a value routing would call is decided here, at the site
  that wrote it and with the render stack, rather than at a detached click
  where the same mistake is an `IFn` collection lookup keyed by a
  MouseEvent, a `deftype` that is not callable, or a silently lost event."
  [on-click]
  (normalize-callback
    on-click
    (fn [v]
      (reject!
        :on-click v
        (str "v/route-link's :on-click is the imperative PRE-NAVIGATION seam — it runs before "
             "the routing decision and may veto it with .preventDefault — so it takes a plain "
             "function or a v/handler, and nothing else; this one carries " (value-description v) ". "
             "The click already produces the one routing intent (:rf.route/url-requested), so an "
             "application reaction belongs behind that routing event or its transition, not at a "
             "second intent site on the same click. Imperative work — preventDefault, a confirm "
             "dialog, an analytics ping — is what v/handler is for.")))))

(defn intent-callback
  "Normalize a caller's value at one of the [[intent-keys]] into the
  callback routing's prefetch runs first — a plain one-argument fn, or nil
  — or reject it. [[veto]]'s sibling for the three prefetch positions:
  same roster, same error, same both-hosts render-time check, and a
  different reason.

  The reason is that `:prefetch :intent` makes `k` a position the
  framework OWNS, and an owned position composes by CALLING what it found.
  A link that did not opt in never reaches here, so hover intent expressed
  as ordinary Freehand event data keeps working everywhere else."
  [k v]
  (normalize-callback
    v
    (fn [v]
      (reject!
        k v
        (str "v/route-link's :prefetch :intent makes " k " a position the framework owns: it "
             "installs its own handler there and runs yours FIRST, so the value has to be "
             "something it can call — a plain function or a v/handler; this one carries "
             (value-description v) ". A link that wants an application event on hover keeps "
             k " as the ordinary Freehand event position it is and dispatches "
             ":rf.route/prefetch itself — the :prefetch opt is sugar over that event.")))))

;; ---------------------------------------------------------------------------
;; The anchor
;; ---------------------------------------------------------------------------

(def ^:private control-keys
  "The keys `v/route-link` owns on every link. Everything else on the props
  map is a passthrough HTML attribute and reaches the `<a>` untouched —
  that is what makes `:class`, `:title`, `:aria-label`, `:target` and
  `:download` work without the view enumerating them.

  `:on-click` is owned rather than passed through: the caller's value is
  the pre-navigation veto ([[veto]]), and what reaches the element is the
  framework's activation closure with that veto inside it.

  `:prefetch` is owned because it is a routing control key, not markup:
  `prefetch=\"intent\"` on an `<a>` is not HTML. The [[intent-keys]] join
  it, but only on a link that opted in — they are conditional, so they are
  not on this list."
  [:to :params :query :fragment :on-click :prefetch :children])

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

  A link that asked for `:prefetch :intent` additionally owns the three
  [[intent-keys]]: each caller value is normalized by [[intent-callback]]
  (both hosts, same reason as the veto), stripped from the attrs, and on
  ClojureScript replaced by a handler that runs it FIRST and then warms
  the destination through [[prefetch-on-intent!]]. The prefetch and the
  click dispatch to the SAME render-time-captured frame, so a warm-up can
  never land in a sibling.

  The veto needs no commit machinery of its own: the anchor's `:on-click`
  is an ORDINARY Freehand event position, so the closure below is the
  site's committed body, and a re-render that changes the caller's
  callback changes what the site's stable proxy runs. Reading the veto
  out of that closure is therefore reading the committed one; the intent
  handlers are the same kind of position and read the same way."
  [{:keys [on-click children] :as props}]
  (let [render-frame (frame/require-current-frame!
                       :route-link
                       {:where 'v/route-link})
        veto-fn      (veto on-click)
        target       (dissoc props :on-click :children)
        {:keys [href payload native?]} (link-model target render-frame)
        prefetch     (prefetch-payload target)
        intent-fns   (when prefetch
                       (into {} (map (fn [k] [k (intent-callback k (get props k))])) intent-keys))
        owned        (cond-> control-keys prefetch (concat intent-keys))
        attrs        (assoc (apply dissoc props owned) :href href)
        attrs #?(:cljs (into (assoc attrs
                                    :on-click
                                    (fn [e]
                                      (activate! e veto-fn render-frame payload native?)))
                             (map (fn [[k caller-fn]]
                                    [k (fn [e]
                                         (prefetch-on-intent! e caller-fn render-frame prefetch))]))
                             intent-fns)
                 :clj  attrs)]
    (into [:a attrs] children)))
