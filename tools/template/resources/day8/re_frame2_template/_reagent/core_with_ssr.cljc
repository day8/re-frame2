(ns {{namespace}}.core
  "Shared server-render and client-hydration entry point.

   Events, subscriptions, schema, and view run on both platforms. The Ring
   host creates a short-lived frame per request; the browser creates a
   client frame, applies the embedded payload with `ssr/hydrate!`, verifies
   the render hash, and then ADOPTS the server-painted DOM with `hydrate-root`
   (a plain client load with no payload mounts a fresh root instead)."
  (:require [re-frame.core :as rf]
            ;; Installs the default Malli validator before schema attachment.
            [re-frame.schemas]
            [re-frame.ssr :as ssr]
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; Keep the schema as data and attach it after each platform creates a frame.

(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; ============================================================================
;; EVENTS
;; ============================================================================

;; The client hydrates this server-seeded state instead of running this event.
(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side boot — seeds the counter."
   :platforms #{:server}}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; Used only for a plain client load with no hydration payload.
(rf/reg-event :counter/initialise
  {:doc "Client-side seed for a plain (non-server-rendered) first load."}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :counter/value
  (fn [db _query]
    (:counter/value db)))

;; ============================================================================
;; VIEW
;; ============================================================================
;;
;; Pin the view id so the server and browser resolve the same registration.

(rf/reg-view ^{:rf/id :app/root} counter-app []
  [:div
   [:h1 "{{name}}"]
   [:button {:data-testid "increment"
             :on-click     #(dispatch [:counter/increment])}
    "+1"]
   [:span {:data-testid "counter-value"
           :style        {:margin "0 1em"}}
    @(subscribe [:counter/value])]])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; ssr-handler owns the per-request frame id. Its initial events run inside
;; that frame, so the zero-arity helper attaches to the ambient request frame.
;; The browser knows its frame id and uses the explicit arity.
(defn register-schema!
  ([] (rf/reg-app-schema [] CounterDb))
  ([frame-id] (rf/reg-app-schema [] {:frame frame-id} CounterDb)))

;; Attach the schema before the following seed event returns its :db effect.
(rf/reg-event :ssr/register-schema
  {:doc       "Per-request schema attach — registers the whole-app-db
                schema against the frame ssr-handler is constructing for
                this request (the ambient `:initial-events` scope)."
   :platforms #{:server}}
  (fn [_cofx _event]
    (register-schema!)
    {}))

(def server-init-events
  "The `:initial-events` vector the Ring SSR handler dispatches into each
   request frame. Schema attachment must precede the seed commit."
  [[:ssr/register-schema]
   [:rf/server-init]])

(defn root-view
  "The root render tree the SSR handler renders after the drain settles.

  A 0-arity fn rather than a literal vector, because `(rf/view :app/root)`
  must resolve AFTER the view is registered — a top-level `def` would
  capture the lookup at namespace-load time.

  NOTE THE OUTER CALL: this INVOKES the view, returning the DOM-rooted
  tree, where the tempting `[(rf/view :app/root)]` would return a
  *reference* to it. Both spellings emit byte-identical HTML, and only
  this one is hashable. `render-tree-hash` is a pure structural walk that
  never expands a callable head, and every fn canonicalises to the same
  identity-free `#fn[]` token, so a callable-headed root hashes to one
  constant for every application on earth — ssr-ring therefore omits the
  render hash entirely for that shape (rf2-q1b96), and the page ships with
  no hydration tripwire. The outer call is also what makes this symmetric
  with the client's `:render-tree-fn` below, which calls the view the same
  way: the two ends must hash the SAME tree or every page reports a
  hydration mismatch.

  The React mount tree further down keeps the `[(rf/view :app/root)]`
  reference form — that one is a component boundary React owns, not a tree
  anyone hashes.

  A keyword head would be worse still: it is an HTML element on every host,
  never a view (Conventions §Render-tree shape vs runtime lookup), so
  `[:app/root]` paints an empty `<root>` element."
  []
  ((rf/view :app/root)))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; hydrate! reads and applies the embedded payload before the first render,
;; then compares the client render-tree hash with the server marker.

;; The retained React root. `defonce` so it survives hot reload — one root
;; owns `#app` for the life of the page, created once on first boot below.
#?(:cljs (defonce ^:private react-root (atom nil)))

;; Hydration and the view tree must target the same client frame.
(def app-frame :rf/default)

#?(:cljs
   (defn ^:export init
     "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
      shadow's `:browser` target re-invokes it on every hot reload, so the
      boot ceremony (frame -> schema -> hydrate -> root) runs exactly ONCE and
      later reruns only re-render the edited views into the one retained root."
     []
     (rf/init! reagent-adapter/adapter)
     (if @react-root
       ;; HOT RELOAD — the boot already ran (the frame exists). Re-render the
       ;; edited views into the SAME retained root. Never re-hydrate (that would
       ;; re-seed the server slice over live interactive state) and never create
       ;; a second root over the container.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]])
       ;; FIRST BOOT — create the frame before attaching its schema or hydrating
       ;; state. Programmatic `rf/make-frame` (not the view-owned
       ;; `rf/frame-root`): `ssr/hydrate!` must run against the live frame BEFORE
       ;; React touches the DOM.
       (do
         (rf/make-frame {:id       app-frame
                         :doc      "{{name}} SSR client app-frame"
                         :platform :client})
         (register-schema! app-frame)
         ;; hydrate! seeds STATE and verifies the render-tree hash against the
         ;; server marker BEFORE the mount; it never touches the DOM. It hands
         ;; back the payload it applied (nil on a plain client load), which is
         ;; what lets the mount below adopt-or-create correctly.
         (let [el      (and (exists? js/document) (js/document.getElementById "app"))
               payload (ssr/hydrate!
                         {:frame          app-frame
                          ;; The verify step renders the view to hash it. The
                          ;; view's injected `subscribe` is frame-scoped, and
                          ;; hydrate! calls this fn OUTSIDE any frame scope — so
                          ;; pin the ambient frame here, or the render raises
                          ;; :rf.error/no-frame-context.
                          :render-tree-fn (fn []
                                            (rf/with-frame app-frame
                                              ((rf/view :app/root))))})
               tree    [rf/frame-provider {:frame app-frame}
                        [(rf/view :app/root)]]]
           (when el
             (if payload
               ;; SERVER-RENDERED — ADOPT the server-painted DOM. `hydrate-root`
               ;; reconciles React against the existing markup: the same nodes,
               ;; handlers wired live, no re-paint and no flash. `create-root` +
               ;; `render` here would throw the server HTML away and mount a
               ;; fresh tree — the bug this branch exists to avoid.
               (reset! react-root (rdc/hydrate-root el tree))
               ;; PLAIN CLIENT LOAD — no server payload to adopt. Seed the
               ;; app-db, then mount a fresh root.
               (do
                 (rf/dispatch-sync [:counter/initialise] {:frame app-frame})
                 (reset! react-root (rdc/create-root el))
                 (rdc/render @react-root tree)))))))))
