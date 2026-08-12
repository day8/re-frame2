(ns re-frame.hicasso.examples.navigation.events
  "THE NAVIGATION WITNESS'S EVENTS — and the focus-on-route RECIPE
  (rf2-hic-042; specification §7's routing row).

  Ordinary re-frame2, like every other witness application's model tier:
  `re-frame.core` and pure functions, no view door, nothing here that
  could tell you a substrate existed.

  The one thing this file holds that no other witness does is the
  **focus-on-route recipe** — [[::pane-shown]] and its [[::focus-heading]]
  effect. It is a recipe rather than a framework facility because the
  framework does not have one and says so: `re-frame.routing.navigate`'s
  own commentary records that `pushState` / `replaceState` do not move
  focus, that `:rf.nav/scroll` is therefore required, and that no focus or
  `:target`-pseudo-class parity claim is made — *a separate a11y
  decision*. There is no `:rf.nav/focus` fx, no `:focus` route-metadata
  key and no `document.activeElement` write anywhere under
  `implementation/routing`. So an application that wants a keyboard or
  screen-reader user to arrive somewhere after a navigation writes the
  three forms below, and this is what they look like.

  ## Why it hangs off `:on-match` and not off a view

  `:on-match` is routing's own fire-and-forget dispatch, taken on a
  navigation that actually COMMITTED. That is the whole reason it is the
  right hook and a re-render is not:

  - A blocked navigation never reaches it. A dirty editor whose
    `:can-leave` guard refuses leaves the user exactly where they were
    typing — a recipe wired to *attempted* navigation would yank focus
    out of the field they are still editing, which is the same defect as
    having no recipe, pointed the other way.
  - A re-render is not a navigation. Typing a character re-renders the
    article pane; nothing about that should move focus, and nothing here
    can, because no keystroke fires `:on-match`.
  - Hicasso has no effect DSL and no local ref, deliberately (the
    completeness audit's explicit refusals). A view could not run this
    even if it wanted to.

  ## Why the effect names a root, which is the line most recipes omit

  An application shares its document. A shell header above the mount, a
  banner an embedder injected, a second application beside it: any of
  them may carry the same marker this recipe selects on, and
  `document.querySelector` answers the FIRST match in document order
  whoever it belongs to. A page that mounts exactly one application is
  the one page where that never shows — so the defect is legal, silent,
  and green under every single-root witness ever written for it.

  So [[pane-shown]] names this application's own root ([[root-selector]],
  the `<main>` the shell view renders) and [[focus-heading]] resolves the
  heading INSIDE it. The scope is the correction the merged-PR audits of
  #7970 and #8031 asked for, and it is load-bearing rather than
  decorative: the browser witness mounts a marked DECOY heading ahead of
  the application and asserts both halves — that a document-wide lookup
  answers the decoy, and that focus lands on this application's own
  heading anyway.

  ## Why the effect waits a frame, which is the other subtle line in it

  `:on-match` dispatches DURING the navigation cascade. The route slice
  has moved, but React has not re-rendered yet, so at the instant the
  effect runs the heading it wants to focus **does not exist** — the
  lookup would answer nil, and the recipe would be a silent no-op that
  looked exactly like a working one. One `requestAnimationFrame` is
  after the commit and after paint, which is the first moment the new
  pane is on the page.

  It is worth being plain that this ordering is not a Hicasso property:
  it is React's, and any substrate that commits asynchronously has it.
  The frame is also why the witness waits two frames before asserting
  that focus did NOT move — a negative that did not wait would be green
  for want of time."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(def seed
  "The starting app-db. Three articles, no drafts, nothing in flight.

  Titles rather than bodies because the accessible NAME of the article
  pane's heading is what the focus rows read back — a heading whose name
  is the article's title is how a user knows the navigation landed."
  {:articles [{:slug "deep-space"    :title "Deep space, and how to get there"}
              {:slug "shallow-water" :title "Shallow water"}
              {:slug "long-way-round" :title "The long way round"}]
   :drafts   {}})

(rf/reg-event ::seed
  {:doc "Install the starting app-db. The frame's `:initial-events` step."}
  (fn [_ _] {:db seed}))

;; ---------------------------------------------------------------------------
;; Editing — one controlled field, and it is what makes a leave dirty
;; ---------------------------------------------------------------------------

(rf/reg-event ::edit
  {:doc "Write the draft title for one article. POSITIONAL, because it
  carries `::h/value` and the marker substitutes at the intent vector's
  top level only."}
  (fn [{:keys [db]} [_ slug title]]
    {:db (assoc-in db [:drafts slug] title)}))

(rf/reg-event ::save
  {:doc "Commit the draft and drop it. A saved article is no longer dirty,
  so the `:can-leave` guard stops refusing — this is the event the
  blocked-navigation row uses to make the block END rather than merely
  exist."}
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    (let [title (get-in db [:drafts slug])]
      {:db (cond-> (update db :drafts dissoc slug)
             (some? title)
             (update :articles
                     (fn [as]
                       (mapv #(cond-> % (= slug (:slug %)) (assoc :title title))
                             as))))})))

;; ---------------------------------------------------------------------------
;; The focus-on-route recipe
;; ---------------------------------------------------------------------------

(def root-id
  "The id of this application's own root element — the `<main>` the shell
  view renders — and the SCOPE the focus recipe resolves its heading
  inside.

  The application declares its own root rather than borrowing whatever
  node it happened to be mounted into, because the recipe has to work
  wherever the application is embedded: a host page's shell, a portal, a
  second root beside it. See the namespace docstring §Why the effect
  names a root."
  "navigation-root")

(def root-selector
  "[[root-id]] as the selector the effect takes.

  Derived rather than written out a second time. A root the view names
  and a root the recipe looks for are the two halves of one fact, and two
  string literals that must agree is the same class of silent near-miss
  one level up from the one this scope exists to close."
  (str "#" root-id))

(def heading-selector
  "The one element per pane that a completed navigation focuses, resolved
  WITHIN [[root-selector]] and never against the document.

  A data attribute rather than an id: the marker says *this is the
  heading the route arrived at*, which is a claim about the pane's role
  in navigation, and an id would say only that it is called something.
  Each pane carries exactly one, and the views put `:tab-index -1` beside
  it so the engine will accept focus on a heading — a heading is not
  natively focusable, and without the tabindex `.focus()` is a no-op that
  leaves `<body>` holding focus and nothing on screen to say so.

  Being a marker rather than an id is also exactly why the root scope is
  needed: a marker is a vocabulary, so anything else on the page is free
  to use it, and one that only ever means *this* application's heading is
  a convention nothing enforces."
  "[data-route-heading]")

(defn pane-shown
  "The routes' `:on-match` handler, written as a plain fn so a structural
  witness can read what it returns without a browser.

  It names `:root` beside `:selector`, and that one key is the whole of
  the scoping: [[focus-heading]] resolves the heading inside this
  application's own root, so a marked heading belonging to anything else
  on the page cannot take the landing focus."
  [_ _]
  ;; `:fx`, not a top-level effect key: re-frame2's effect map is CLOSED at
  ;; `{:db … :fx …}`, so a classic `{::focus-heading …}` return is not an
  ;; effect at all.
  {:fx [[::focus-heading {:root     root-selector
                          :selector heading-selector}]]})

(rf/reg-event ::pane-shown
  {:doc "The routes' `:on-match`. A navigation committed, so ask for focus
  to move to whichever pane is now on screen."}
  pane-shown)

(rf/reg-fx ::focus-heading
  {:doc       "Move focus to the element `:selector` names WITHIN the element
  `:root` names, on the first frame after the commit that put it there, and
  WITHOUT scrolling. Either selector matching nothing is a no-op — a route
  whose pane has no heading is an application's choice, not a failure this
  effect can act on.

  Two lines carry the whole recipe.

  `:root` is the scope. Resolving `:selector` against the DOCUMENT takes
  the first match in document order, which is whichever marked heading the
  page renders first — a shell heading, a banner, another application's
  root. That version passes every witness that mounts one application and
  sends the user somewhere else the moment a second root exists.

  `:preventScroll` is what lets this recipe and scroll restoration COMPOSE.
  Focusing an element scrolls it into view, and this effect runs one frame
  AFTER `:rf.nav/scroll` has already restored the offset a Back button
  asked for — so a plain `.focus()` here silently undoes scroll
  restoration, on exactly the navigation restoration exists for, and both
  features look installed the whole time. The two recipes have to be
  written to compose; this is where they do."
   :platforms #{:client}}
  (fn [_ {:keys [root selector]}]
    ;; See the namespace docstring §Why the effect waits a frame. The
    ;; lookup happens INSIDE the callback, not outside it: resolving the
    ;; element now would resolve the OLD pane's heading, which is the
    ;; version of this bug that still focuses something and is therefore
    ;; the harder one to see.
    (js/requestAnimationFrame
      (fn []
        (some-> (.querySelector js/document root)
                (.querySelector selector)
                (.focus #js {:preventScroll true}))))))
