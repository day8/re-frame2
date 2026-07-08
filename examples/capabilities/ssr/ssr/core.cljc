(ns ssr.core
  "One app that runs twice. The server renders a 'recent articles' page to
   HTML so the first paint arrives ready-made; the client then hydrates that
   same HTML and takes over, fully interactive. The trick is that both runs
   are the *same code* — the SSR guide tells the longer story:
   ../../../docs/ssr/concepts.md.

   That's why this file is .cljc and not .cljs. The events, subscriptions,
   and views are written once and shared. The `#?(:clj …)` / `#?(:cljs …)`
   reader conditionals carve out the few spots that genuinely differ — the
   JVM render path on one side, the browser mount on the other. There is no
   second, server-flavoured copy of the app to keep in sync, which is the
   whole point.

   The tour, in the order you'll meet it below:
   - A fresh frame per request on the server (reg-frame + with-frame), torn
     down again when the request is done.
   - :rf/server-init — the per-request boot event, dispatched as the frame
     comes up.
   - A client-only effect, gated with :platforms #{:client}, that the server
     render simply skips.
   - The pure hiccup -> HTML emitter, rf/render-to-string. No DOM, just data
     in and a string out.
   - The hydration payload: the server's finished state, packed into the page
     for the client to adopt verbatim.
   - Hydration the app never has to write. The client boots through
     `ssr/hydrate!`, which dispatches the reserved `:rf/hydrate` event;
     re-frame2 owns the handler. `:rf/hydrate` *replaces* the client's
     frame-state rather than merging into it — on this question the server is
     the single source of truth.
   - A structural render hash that catches the classic SSR bug. The client
     hashes its own first render and compares it to the server's; if they
     disagree, the runtime emits :rf.ssr/hydration-mismatch. See
     ../../../docs/ssr/glossary.md#hydration-mismatch.

   Want to see it live? The hand-written `index.html` beside this file is a
   runnable stand-in for what `handle-request` below would emit if a real
   Clojure server sat in front — close in shape, but hand-authored, not
   byte-exact (it deliberately omits the render-hash attribute + payload key
   a genuine server stamps; see the comment in index.html). The pre-rendered
   markup sits in `<div id='app'>`, and the baked
   `<script id='__rf_payload'>` carries the state. The browser-side `run`
   reads that payload, hydrates, verifies, and renders on top of the seeded
   state — no flash, no re-fetch."
  (:require [re-frame.core :as rf]
            ;; A handful of these requires are here purely for their side
            ;; effect: loading the namespace registers something we lean on
            ;; later. They look unused; they aren't.

            ;; Wires up the schema hooks, so the rf/reg-app-schema calls below
            ;; have something to resolve to.
            [re-frame.schemas]
            ;; Brings in the `:rf.http/managed` effect we use for the article
            ;; fetch. During render we don't want a real network call, so we
            ;; redirect it through the per-frame `:fx-overrides` seam to a
            ;; canned-success stub. Skip this require and the override would be
            ;; pointing at an fx-id nobody registered.
            [re-frame.http.managed]
            ;; The canned stubs themselves (`:rf.http/managed-canned-success`
            ;; and `-canned-failure`). There's no backend behind this example,
            ;; so :fx-overrides drives these instead — and pulling in a test
            ;; affordance is the kind of thing you want to opt into out loud,
            ;; not by accident.
            [re-frame.http.test-support]
            ;; The SSR machinery: the `:rf.server/*` server-only effects, the
            ;; framework-owned `:rf/hydrate` event, the default error
            ;; projector, and the render/hash hooks the calls below ride on.
            [re-frame.ssr :as ssr]
            ;; Server-side only: a `<script>`-body escaper that understands
            ;; EDN. `handle-request` drops the payload, `pr-str`'d, inside a
            ;; `<script type="application/edn">`. If some article body smuggled
            ;; in the literal text `</script>`, it would slam the envelope shut
            ;; early. The escaper rewrites `<` to its reader escape, but only
            ;; inside EDN string literals — so the payload still reads back
            ;; byte-for-byte on the client.
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; We keep the schema as a plain value, deliberately. SSR juggles two frames —
;; the throwaway per-request server frame (a gensym, born in `handle-request`)
;; and the fixed client frame (`app-frame`, in `run`) — and both answer to the
;; exact same contract. Since `reg-app-schema` is frame-local, each entry point
;; registers this value against its own frame with the `{:frame …}` override.
;; Parking the schema in a `def` means namespace-load never has to reach for an
;; ambient frame that isn't there yet.
;;
;; Note the `[:maybe …]` wrapper. The articles slice is legitimately nil for a
;; beat: the server's `:rf/server-init` commits a db with no articles in it
;; (all it does is start the fetch), and a fresh client frame is empty until
;; hydration lands. A bare `[:vector …]` would call that intermediate nil a
;; violation and roll the commit back — so we tell the schema, up front, that
;; nil is a fine place to pass through. See
;; ../../../docs/core/glossary.md#schema.
(def ArticlesSchema
  [:maybe [:vector [:map
                    [:id    :string]
                    [:title :string]
                    [:body  :string]]]])

;; ============================================================================
;; FX
;; ============================================================================
;;
;; HTTP goes through the framework's `:rf.http/managed` effect. The headless
;; test swaps it, via the `:fx-overrides` seam, for a per-frame canned-success
;; stub — so the JVM render runs the whole event pipeline end to end without a
;; single packet leaving the building.

;; Some effects only make sense in a browser. localStorage is the textbook
;; case: there's no such thing on the server. `:platforms #{:client}` is how
;; you say so — the server render reaches this effect, shrugs, and moves on.
(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; client-only; the server render skips it
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (.setItem ls "auth/token" token)))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side boot. Reads the incoming request through
               the :rf.server/request coeffect and starts the work the page
               needs. Server only. See
               ../../../docs/ssr/concepts.md#reading-the-request."
   :platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn handler-rf-server-init [{:keys [db rf.server/request]} _]
    ;; Where's the request? Not in the event vector — it rides in on the
    ;; `:rf.server/request` coeffect we declared just above. That's `request`:
    ;; the host's HTTP request map (Ring-shaped under the bundled adapter), the
    ;; place you'd read URL, headers, and cookies from.
    ;;
    ;; This particular handler leaves db alone and just fires off the article
    ;; fetch. An app with routing would stash the matched route here; a page
    ;; that only ever shows articles has no route worth remembering.
    {:db db
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles"}
            :decode     :json
            :on-success [:articles/loaded]}]]}))

;; When the fetch comes back, this is where it lands — the `:on-success` target
;; from above. It takes the decoded reply and hands back the next app-db, which
;; the runtime commits. Notice there's nothing server- or client-specific in
;; here: the very same handler runs on the server's per-request frame and again
;; on the client after hydration. Write it once.
(rf/reg-event :articles/loaded
  (fn handler-articles-loaded [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :articles value)}))

;; You'll notice there's no `reg-event` for hydration here. That's on purpose:
;; `:rf/hydrate` is a reserved `:rf/*` event that `re-frame.ssr` owns, handler
;; and all. You dispatch it; the framework does the rest.
;;
;; What the framework handler does is install the server's frame-state in one
;; atomic move — and it *replaces*, it doesn't merge. The payload's app-db
;; becomes the app-db partition; its runtime-db slice (machine snapshots, route
;; slice) becomes the serializable runtime-db projection. Whatever the client
;; had pre-seeded is overwritten, no negotiation. On hydration the server is
;; the authority, full stop. (Why replace rather than merge is worth the read:
;; ../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify.)
;;
;; It's also paranoid in the good way — it fails closed. A payload that isn't a
;; map, or a slice (`:rf/app-db` / `:rf/runtime-db`) that's present but not a
;; map, leaves the frame-state exactly as it was rather than installing garbage.
;; (A wholly-absent slice key isn't malformed — that's the no-server-slice
;; first-load shape, which falls back to the existing partition value.) Along
;; the way it tucks the server's render hash aside for the verify step that
;; follows the first render.
;;
;; This example has no machines and doesn't hydrate a route, so its runtime-db
;; slice is empty here — but the shape is the same one a richer app fills in.
;; The client `run` at the bottom drives all of this through `ssr/hydrate!`; the
;; CLIENT ENTRY POINT section picks up the thread.

;; ============================================================================
;; CLIENT-SIDE INTERACTIVITY EVENTS
;; ============================================================================
;;
;; Proof that hydration handed you a *live* app and not a museum piece. Clicking
;; "Hide bodies" toggles the article paragraphs in and out — a normal reactive
;; round-trip, no full re-render in sight. This bit of state is purely a client
;; concern, so it never appears in the hydration payload; it just starts at its
;; default the moment the client wakes up.

(rf/reg-event :articles/toggle-bodies
  (fn [{:keys [db]} _] {:db (update db :articles/show-bodies? (fnil not true))}))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :articles/slice (fn [db _] (:articles db)))

(rf/reg-sub :articles/show-bodies?
  (fn [db _]
    ;; Default to true, so the server's render shows the bodies and the page
    ;; arrives fully fleshed out. Hiding them is a choice the reader makes
    ;; later, on the client.
    (let [v (:articles/show-bodies? db)]
      (if (nil? v) true v))))

;; A couple of things `reg-view` is doing for us here. It auto-`def`s the
;; symbol and registers the view under `(keyword *ns* sym)` — so by default the
;; id tracks the var name. The `^{:rf/id …}` metadata overrides that, pinning
;; ids the callers below already expect (`:pages/articles`, `:app/root`).
;;
;; The other gift is `subscribe`: `reg-view` injects it, and it resolves to the
;; frame-bound subscribe fn at *runtime*. That single detail is what lets one
;; view run twice. On the JVM render path, deref of a subscription just reads
;; the current value and returns. On the client, the very same deref registers
;; a reaction, so the view re-renders whenever app-db changes underneath it.
;; Same code, two behaviours, picked up from the context. See
;; ../../../docs/core/glossary.md#view.
(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [arts         @(subscribe [:articles/slice])
        show-bodies? @(subscribe [:articles/show-bodies?])]
    [:div.page
     [:h1 "Recent articles"]
     [:button.toggle-bodies
      {:data-testid "toggle-bodies"
       :on-click #(dispatch [:articles/toggle-bodies])}
      (if show-bodies? "Hide bodies" "Show bodies")]
     (if (seq arts)
       (into [:ul {:data-testid "articles-list"}]
             (for [{:keys [id title body]} arts]
               ^{:key id}
               [:li [:h3 title]
                (when show-bodies? [:p.body {:data-testid "article-body"} body])]))
       [:p "No articles."])]))

(rf/reg-view ^{:rf/id :app/root} root-view []
  [(rf/view :pages/articles)])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; Here's the whole server-side dance, one request from end to end
;; (../../../docs/ssr/concepts.md#a-request-start-to-finish):
;;   1. A request arrives.
;;   2. set-request! drops it into the per-frame slot that the
;;      :rf.server/request coeffect will read back out.
;;   3. reg-frame stands up a fresh frame; its :initial-events step fires
;;      :rf/server-init, which pulls the request via that coeffect.
;;   4. The runtime drains — the article fetch resolves, app-db settles, and
;;      everything quiets down.
;;   5. Render the settled state to a string with the pure hiccup -> HTML
;;      emitter.
;;   6. Serialise that same state and tuck it into the HTML as the payload.
;;   7. destroy-frame!, in a `finally`. This step isn't optional bookkeeping —
;;      on a server that runs for weeks, it's what stops you leaking a frame
;;      per request. It drops the frame record and fires
;;      `:ssr/on-frame-destroyed`, which hands back the request slot, the
;;      response accumulator, and the error-trace buffer. The `finally` covers
;;      both the happy path and the throw path, so even a request that blows up
;;      halfway doesn't strand a half-built frame.

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (ssr/set-request! fid request)
           ;; Schema first, frame second — order matters. `reg-frame` runs its
           ;; `:initial-events` pipeline run synchronously, and that run includes
           ;; the `:articles` commit `:rf/server-init` sets in motion. If the
           ;; schema weren't already registered against this per-request frame
           ;; (the gensym `fid`), there'd be nothing to validate that commit
           ;; against by the time it fires.
           _   (rf/reg-app-schema [:articles] {:schema ArticlesSchema :frame fid})
           f   (rf/reg-frame fid
                 {:doc       "ssr-example per-request frame"
                  :platform  :server
                  :initial-events [[:rf/server-init]]})]
       (try
         (rf/with-frame f
           (let [final-db      (rf/app-db-value f)        ;; the app-db partition
                 final-runtime (:rf.db/runtime (rf/frame-state-value f))    ;; the runtime-db partition (serializable)
                 hiccup   ((rf/view :app/root))
                 ;; `:emit-hash?` stamps data-rf-render-hash="<hex>" onto the
                 ;; root element. That hex string is the tripwire: the client
                 ;; recomputes the hash after its first render, and if the two
                 ;; don't match, the runtime raises :rf.ssr/hydration-mismatch
                 ;; instead of quietly serving a subtly-broken page.
                 ;; No `:doctype?` here — this handler wraps a *fragment*
                 ;; (`<div id='app'>…</div>`) inside its own hand-written
                 ;; document envelope below, which already opens with
                 ;; `<!DOCTYPE html>`. `:doctype?` is for a root view that
                 ;; renders the whole `[:html …]` document; asking for it here
                 ;; would prefix a *second* doctype onto the fragment and nest
                 ;; it inside `<div id='app'>`.
                 html     (rf/render-to-string hiccup
                                               {:emit-hash? true})
                 ;; The same hash also rides in the payload, so something
                 ;; without a DOM to parse — a server log line, a CDN cache key
                 ;; — can read it straight.
                 render-hash (rf/render-tree-hash hiccup)
                 ;; You'll notice the payload carries no `:rf/frame-id`, and
                 ;; that's the intended shape. The server rendered under a
                 ;; throwaway per-request gensym (`f`); the client hydrates its
                 ;; own fixed `app-frame` (defined below). The two frames have
                 ;; nothing to say to each other by name. A `:rf/frame-id` in
                 ;; the payload isn't a "hydrate into this" instruction — it's
                 ;; evidence. `ssr/hydrate!` checks any id it finds against the
                 ;; `:frame` you explicitly pass, and raises
                 ;; `:rf.error/hydration-frame-id-mismatch` if they disagree.
                 ;; Leaving it out is the no-argument-to-have-here shape, which
                 ;; is exactly what this output and the static `index.html`
                 ;; both use. A deployment that *does* want to carry one stamps
                 ;; a stable id both sides agree on ahead of time — never a
                 ;; per-request gensym.
                 payload  {:rf/version     1
                           :rf/app-db      final-db        ;; app-db partition
                           :rf/runtime-db  final-runtime   ;; serializable runtime-db projection
                           :rf/render-hash render-hash}]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head>"
                   "<meta charset='utf-8'/>"
                   "<title>SSR demo</title>"
                   "</head><body>"
                   "<div id='app'>" html "</div>"
                   ;; Run the payload through the EDN-aware escaper before it
                   ;; goes in the `<script>`. If an article body happened to
                   ;; contain the literal text `</script>` and we wrote it raw,
                   ;; the browser would close the script element right there and
                   ;; eat the rest of our state. The escaper sidesteps that by
                   ;; rewriting `<` to its unicode reader escape — but *only*
                   ;; inside EDN string literals, so the client's
                   ;; `cljs.reader/read-string` still reads it back unchanged.
                   "<script id='__rf_payload' type='application/edn'>"
                   (html/escape-edn-script-body (pr-str payload))
                   "</script>"
                   "<script src='/main.js'></script>"
                   "</body></html>")}))
         ;; Whatever happened above — success or exception — the frame goes
         ;; away here. destroy-frame! fires `:ssr/on-frame-destroyed`, which
         ;; clears the request slot and the SSR side-channel atoms for us, so
         ;; there's no separate `clear-request!` to remember.
         (finally
           (rf/destroy-frame! fid))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; On the client, the whole boot is a single call: `ssr/hydrate!`, the mirror
;; image of the server render. It rolls three steps into one, and the order is
;; the point:
;;   1. READ    — pull the embedded `__rf_payload` `<script>` by its pinned id.
;;                A malformed payload fails closed (nothing replaces app-db); a
;;                missing one just means "nobody server-rendered this" — a plain
;;                first load.
;;   2. HYDRATE — dispatch-sync `[:rf/hydrate payload]`, before the first
;;                render. The framework handler swaps in the server's slice and
;;                sets the server's render hash aside for step 3.
;;   3. VERIFY  — once that first render is on screen, hash the `:render-tree-fn`
;;                result and hold it up against the server's hash. Disagree and
;;                you get :rf.ssr/hydration-mismatch.
;; `hydrate!` hands back the payload it applied (or nil on a plain client load),
;; which lets `run` answer "was this server-rendered?" without going back to
;; sniff the DOM. The reason to route through the helper rather than wire these
;; up yourself: it keeps the fail-closed check, the hash stash, and the verify
;; locked together in the one correct order. See
;; ../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify.

;; A quick naming note. App events live under a namespace you pick; the reserved
;; `:rf/*` root is the framework's. `:rf/hydrate` is framework-owned, and
;; `:rf/server-init` is the documented per-request boot pattern you fill a body
;; into. This little bootstrap-for-a-plain-load, though, is your own brand-new
;; event — so it gets the app's own `:ssr/` namespace, not `:rf/`.
(rf/reg-event :ssr/client-bootstrap
  {:doc "Client-side init that runs even when the server never rendered this page."}
  (fn [{:keys [db]} _] {:db db}))

;; The React root lives in an atom and gets created lazily inside `run`, never
;; at namespace-load. The rule is that loading a namespace must not touch the
;; DOM — so if several example namespaces get required together, none of them
;; can race the others to slap a `create-root` onto the shared `#app`.
#?(:cljs (defonce react-root (atom nil)))

;; The client's app-frame id. The app names its frame out loud and threads the
;; very same id through two places: `ssr/hydrate!` (where the server's state
;; gets seeded) and the root `frame-provider` (where every `dispatch` and
;; `subscribe` in the tree goes looking for its frame). It has to be a
;; `:client`-platform frame, because the `:rf/hydrate` handler fires
;; compatibility-check effects that a `:server` frame would just skip. And since
;; the server payload carries no `:rf/frame-id`, this explicit `:frame` is the
;; hydration target, plain and simple (see the note over in `handle-request`).
(def app-frame :rf/default)

#?(:cljs
   (defn run []
     ;; First, point the runtime at the Reagent substrate. This is safe to call
     ;; over and over: the first call wires up the adapter, every hot reload
     ;; after that is a no-op. Note what `init!` does *not* do — it installs the
     ;; adapter and stops there. Creating a frame is the app's job, next line.
     ;; (Each adapter namespace exports an `adapter` var; you require it and
     ;; hand the spec map straight in.)
     (rf/init! reagent-adapter/adapter)
     ;; Stand up the client app-frame before we hydrate anything into it.
     ;; Re-registering an existing frame is a no-op, so hot-reload just shrugs
     ;; and carries on. `:platform :client` is what lets the hydrate
     ;; compatibility-check effects actually fire.
     (rf/reg-frame app-frame {:doc      "ssr-example client app-frame"
                              :platform :client})
     ;; Register the schema against this client frame too — the mirror of the
     ;; per-request registration back in `handle-request`. That way the hydrated
     ;; `:articles` commit, and every interactive commit the reader triggers
     ;; afterward, get validated on the client just as they were on the server.
     ;; (Also no-op-safe on hot-reload.)
     (rf/reg-app-schema [:articles] {:schema ArticlesSchema :frame app-frame})
     ;; One call, all three steps — READ, HYDRATE, VERIFY — against the same
     ;; `app-frame` the mount below will use. The one subtlety is `:render-tree-fn`:
     ;; VERIFY has to hash the *exact* tree the server hashed, or it'll cry
     ;; mismatch over a difference that was never real. The server hashed
     ;; `((rf/view :app/root))` — the view fn *called* — so we call it the same
     ;; way here, not the `[(rf/view :app/root)]` vector form Reagent mounts.
     ;; `hydrate!` returns the payload it applied, or nil on a plain client load.
     (let [payload (ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; No payload means no server render — a plain first load. Run the
         ;; app's own bootstrap against the frame; the page comes up showing the
         ;; empty-articles fallback.
         (rf/dispatch-sync [:ssr/client-bootstrap] {:frame app-frame})))
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Mount inside the app-frame's `frame-provider`, so every `dispatch` and
       ;; `subscribe` down in the view tree resolves to the frame we just
       ;; hydrated.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]]))))

;; No tests in this file — and that's deliberate. The example tree stays
;; test-free so the source reads as pure demonstration; the headless tests that
;; actually exercise all of the above live next door in re-frame.examples-test
;; (implementation/core/test/) and run on the JVM. If you're curious what's
;; covered:
;;   - `ssr-example-runs-end-to-end` — the whole server flow: per-request frame,
;;     :rf/server-init, the article fetch through a canned stub,
;;     render-to-string, render-hash.
;;   - `ssr-example-handle-request-tears-down-per-request-frame` and
;;     `…-tears-down-on-throw` — proof the frame is torn down on both the happy
;;     path and the throw path.
;;   - `ssr-example-client-hydration-*` — the client side: the server-hash
;;     stash, verify on both a matching and a divergent hash, and fail-closed
;;     behaviour on a malformed payload.
