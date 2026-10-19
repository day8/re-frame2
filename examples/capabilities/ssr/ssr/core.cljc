(ns ssr.core
  "One app that runs twice. The server renders a 'recent articles' page to
   HTML so the first paint arrives ready-made; the client then hydrates that
   same HTML and takes over, fully interactive. The trick is that both runs
   are the *same code* — the SSR guide tells the longer story:
   ../../../../docs/ssr/concepts.md.

   That's why this file is .cljc and not .cljs. The events, subscriptions,
   and views are written once and shared. The `#?(:clj …)` / `#?(:cljs …)`
   reader conditionals carve out the few spots that genuinely differ — the
   JVM render path on one side, the browser mount on the other. There is no
   second, server-flavoured copy of the app to keep in sync, which is the
   whole point.

   The tour, in the order you'll meet it below:
   - A fresh frame per request on the server (make-frame + with-frame), torn
     down again when the request is done.
   - :app/server-init — the per-request boot event, dispatched as the frame
     comes up.
   - A client-only effect, gated with :platforms #{:client}, that the server
     render simply skips.
   - The pure hiccup -> HTML emitter, rf.ssr/render-to-string. No DOM, just data
     in and a string out.
   - The hydration payload: the server's finished state, run through the
     framework's payload projection (rf.ssr/hydration-payload) and packed into
     the page for the client to adopt verbatim.
   - Hydration the app never has to write. The client boots through
     `rf.ssr/hydrate!`, which dispatches the reserved `:rf/hydrate` event;
     re-frame2 owns the handler. `:rf/hydrate` *replaces* the client's
     frame-state rather than merging into it — on this question the server is
     the single source of truth.
   - A report when the classic SSR bug strikes. If the client's first render
     disagrees with the server's markup and React has to re-render part of
     it, the hydrating root emits :rf.ssr/hydration-mismatch. See
     ../../../../docs/ssr/glossary.md#hydration-mismatch.

   Want to see it live? The hand-written `index.html` beside this file is a
   runnable stand-in for what `handle-request` below would emit if a real
   Clojure server sat in front — close in shape, but hand-authored, not
   byte-exact (see the comment in index.html). The pre-rendered
   markup sits in `<div id='app'>`, and the baked
   `<script id='__rf_payload'>` carries the state. The browser-side `run`
   reads that payload, hydrates the state, then adopts the server markup
   with `hydrate-root` — no flash, no re-fetch."
  (:require [re-frame.core :as rf]
            ;; A handful of these requires are here purely for their side
            ;; effect: loading the namespace registers something we lean on
            ;; later. They look unused; they aren't.

            ;; Wires up the schema hooks, so the rf/reg-app-schema call below
            ;; has something to resolve to.
            [re-frame.schemas]
            ;; Brings in the `:rf.http/managed` effect we use for the article
            ;; fetch. There's no backend behind this example: the headless
            ;; tests answer the fetch with a stub transport, which replaces
            ;; only the network hop beneath this same effect.
            [re-frame.http.managed]
            ;; The SSR machinery: the `:rf.server/*` server-only effects, the
            ;; framework-owned `:rf/hydrate` event, the default error
            ;; projector, the render hooks the calls below ride on, and the
            ;; hydration-payload builder.
            [re-frame.ssr :as rf.ssr]
            ;; Server-side only: a `<script>`-body escaper that understands
            ;; EDN. `handle-request` drops the payload, `pr-str`'d, inside a
            ;; `<script type="application/edn">`. If some article body smuggled
            ;; in the literal text `</script>`, it would slam the envelope shut
            ;; early. The escaper rewrites `<` to its reader escape, but only
            ;; inside EDN string literals — so the payload still reads back
            ;; byte-for-byte on the client.
            #?(:clj [re-frame.ssr.html-helpers :as rf.ssr.html-helpers])
            #?(:cljs [re-frame.adapter.reagent :as rf.adapter.reagent])
            ;; The client mount step — the payload-vs-client-only React-root
            ;; branch — factored into a registration-free helper so the browser
            ;; DOM-adoption regression can execute the EXACT branch this recipe
            ;; ships, not a copy of it. See the ns docstring there.
            #?(:cljs [ssr.mount :as mount])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; SSR juggles two frames — the throwaway per-request server frame (a gensym,
;; born in `handle-request`) and the fixed client frame (`app-frame`, in `run`)
;; — and both answer to the exact same contract. A schema is a program
;; registration, like an event: it is registered once, here at the top level,
;; and every frame that resolves this namespace's registrations validates
;; against it — each per-request frame and the client frame alike, with no
;; per-frame step in either entry point.
;;
;; The articles slice is absent for a beat: the server's `:app/server-init`
;; commits a db with no articles in it (all it does is start the fetch), and a
;; fresh client frame is empty until hydration lands. A path schema checks a
;; slice only where app-db has it, so a bare `[:vector …]` lets those commits
;; through and still checks every article list a handler writes. See
;; ../../../../docs/core/glossary.md#schema.
(def ArticlesSchema
  [:vector [:map
            [:id    :string]
            [:title :string]
            [:body  :string]]])

(rf/reg-app-schema [:articles] ArticlesSchema)

;; ============================================================================
;; FX
;; ============================================================================
;;
;; HTTP goes through the framework's `:rf.http/managed` effect. The headless
;; test answers it from a stub transport, which stands in for the server
;; beneath the effect — so the JVM render runs the whole event pipeline, the
;; real decode included, end to end without a
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

(rf/reg-event :app/server-init
  {:doc       "Per-request server-side boot. Reads the incoming request through
               the :rf.server/request coeffect and starts the work the page
               needs. Server only. See
               ../../../../docs/ssr/concepts.md#reading-the-request."
   :platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn handler-app-server-init [{:keys [db rf.server/request]} _]
    ;; Where's the request? Not in the event vector — it rides in on the
    ;; `:rf.server/request` coeffect we declared just above. That's `request`:
    ;; the host's HTTP request map (Ring-shaped under the bundled adapter), the
    ;; place you'd read URL, headers, and cookies from.
    ;;
    ;; This particular handler marks the page load pending and fires off the
    ;; article fetch. An app with routing would stash the matched route here; a
    ;; page that only ever shows articles has no route worth remembering.
    ;;
    ;; `:articles/load-state` is the page's OWN completion signal, and the
    ;; server render is what makes it load-bearing. On the JVM the managed
    ;; transport issues its request through `HttpClient/sendAsync`, so
    ;; `make-frame` returning is emphatically NOT the fetch having finished —
    ;; it only means the synchronous event work drained. Something has to say
    ;; "this page is done loading", and it has to say so for the FAILURE case
    ;; too, or a server that waits for articles to turn up waits for ever.
    ;; `handle-request` blocks on this key reaching a terminal value; the
    ;; client never reads it.
    {:db (assoc db :articles/load-state :pending)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles"}
            :decode     :json
            :on-success [:articles/loaded]
            :on-failure [:articles/load-failed]}]]}))

;; When the fetch comes back, this is where it lands — the `:on-success` target
;; from above. It takes the decoded reply and hands back the next app-db, which
;; the runtime commits. Notice there's nothing server- or client-specific in
;; here: the very same handler runs on the server's per-request frame and again
;; on the client after hydration. Write it once.
(rf/reg-event :articles/loaded
  (fn handler-articles-loaded [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :articles value :articles/load-state :loaded)}))

;; …and the other half of the outcome. A page load that can only ever record
;; SUCCESS is a page load the server waits on until its deadline, every time
;; the upstream is down. This handler is what turns a failed fetch into a
;; terminal answer `handle-request` can act on immediately.
(rf/reg-event :articles/load-failed
  (fn handler-articles-load-failed [{:keys [db]} _]
    {:db (assoc db :articles/load-state :failed)}))

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
;; ../../../../docs/ssr/concepts.md#the-client-side-hydrate-then-adopt.)
;;
;; It's also paranoid in the good way — it fails closed. A payload that isn't a
;; map, or a slice (`:rf/app-db` / `:rf/runtime-db`) that's present but not a
;; map, leaves the frame-state exactly as it was rather than installing garbage.
;; (A wholly-absent slice key isn't malformed — that's the no-server-slice
;; first-load shape, which falls back to the existing partition value.)
;;
;; This example has no machines and doesn't hydrate a route, so its runtime-db
;; is empty and the payload carries no `:rf/runtime-db` at all. A richer app's
;; route and machine state reaches the wire only through the framework's
;; projection in `handle-request`, which redacts what the app classified.
;; The client `run` at the bottom drives all of this through `rf.ssr/hydrate!`; the
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
;; the id both entry points below already expect (`:app/root`).
;;
;; The other gift is `subscribe`: `reg-view` injects it, and it resolves to the
;; frame-bound subscribe fn at *runtime*. That single detail is what lets one
;; view run twice. On the JVM render path, deref of a subscription just reads
;; the current value and returns. On the client, the very same deref registers
;; a reaction, so the view re-renders whenever app-db changes underneath it.
;; Same code, two behaviours, picked up from the context. See
;; ../../../../docs/core/glossary.md#view.
(rf/reg-view ^{:rf/id :app/root} root-view []
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

;; ============================================================================
;; WAITING FOR THE PAGE LOAD
;; ============================================================================
;;
;; The one step people leave out. `make-frame` drains the SYNCHRONOUS event
;; work its `:initial-events` set in motion — and that is all it promises. The
;; article fetch `:app/server-init` starts is not synchronous: the JVM managed
;; transport issues it through `HttpClient/sendAsync`. So the moment
;; `make-frame` returns, the request is very probably still in flight, app-db
;; still has no `:articles`, and a render taken right there emits "No
;; articles." over a 200 — a page that looks fine and is wrong.
;;
;; So wait, explicitly, on the `:articles/load-state` signal the events above
;; maintain. Two properties make this a wait rather than a hope:
;;
;;   - It is BOUNDED. `page-load-deadline-ms` caps it, the way the framework's
;;     own SSR blocking drain caps its route-resource wait. A server that
;;     blocks a request thread for ever on a silent upstream is a worse bug
;;     than the one we're fixing.
;;   - It settles on FAILURE too, not just success. Polling for non-nil
;;     `:articles` would look simpler and would hang for the full deadline on
;;     every failed fetch — which is exactly why `:app/server-init` declares an
;;     `:on-failure` target.
;;
;; (This page has no route table, so the framework's route-keyed
;; `rf.ssr/drain-blocking-resources!` has nothing to drain for it. The
;; `resources_ssr` sibling explains that split at length.)

#?(:clj
   (def ^:private page-load-deadline-ms
     "Wall-clock budget for `await-page-load!` — mirrors the framework's own
      SSR blocking-drain default (`re-frame.ssr`'s
      `default-ssr-blocking-timeout-ms`)."
     5000))

#?(:clj
   (defn- await-page-load!
     "Block until this request's page load reaches a terminal
      `:articles/load-state` (`:loaded` / `:failed`), or the deadline
      elapses. Returns that terminal value, or `:timed-out`.

      Yields the JVM thread between checks (`Thread/sleep`) so the managed
      transport's async reply can land and its reply event dispatch — nothing
      settles inside the `:rf.http/managed` call itself."
     [f]
     (let [deadline (+ (System/currentTimeMillis) page-load-deadline-ms)]
       (loop []
         (let [state (:articles/load-state (rf/app-db-value f))]
           (cond
             (#{:loaded :failed} state)            state
             (>= (System/currentTimeMillis) deadline) :timed-out
             :else (do (Thread/sleep 5)
                       (recur))))))))

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; Here's the whole server-side dance, one request from end to end
;; (../../../../docs/ssr/concepts.md#a-request-start-to-finish):
;;   1. A request arrives.
;;   2. set-request! drops it into the per-frame slot that the
;;      :rf.server/request coeffect will read back out.
;;   3. make-frame stands up a fresh frame; its :initial-events step fires
;;      :app/server-init, which pulls the request via that coeffect.
;;   4. await-page-load! blocks until the article fetch reaches a terminal
;;      outcome — loaded, failed, or out of time. Only `:loaded` goes on to
;;      render; the other two answer 503 rather than dress a pending request
;;      up as a finished empty page.
;;   5. Render the settled state to a string with the pure hiccup -> HTML
;;      emitter.
;;   6. Build the payload from that same state with rf.ssr/hydration-payload,
;;      serialise it, and tuck it into the HTML.
;;   7. destroy-frame!, in a `finally`. This step isn't optional bookkeeping —
;;      on a server that runs for weeks, it's what stops you leaking a frame
;;      per request. It drops the frame record and fires
;;      `:ssr/on-frame-destroyed`, which hands back the request slot, the
;;      response accumulator, and the error-trace buffer. The `finally` covers
;;      the happy path, the 503 path and the throw path, so no request strands
;;      a half-built frame.

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (rf.ssr/set-request! fid request)
           ;; `make-frame` runs its `:initial-events` pipeline run
           ;; synchronously, and that run includes the `:articles` commit
           ;; `:app/server-init` sets in motion. The frame resolves the
           ;; top-level `ArticlesSchema` registration, so that commit is
           ;; validated with no per-request schema step.
           f   (rf/make-frame {:id fid :doc       "ssr-example per-request frame"
                               :platform  :server
                               :initial-events [[:app/server-init]]})]
       (try
         (rf/with-frame f
           ;; Step 4 — wait for the page load to finish before reading a thing
           ;; off the frame. Anything other than `:loaded` is a deliberate
           ;; terminal outcome, and it answers 503 rather than a 200 carrying
           ;; an empty page: "still loading" and "loaded, nothing to show" are
           ;; different answers and a cache must not be able to confuse them.
           ;; The `finally` below still tears the frame down on this path.
           (if-let [outcome (#{:failed :timed-out} (await-page-load! f))]
             {:status  503
              :headers {"Content-Type" "text/plain; charset=utf-8"
                        "Cache-Control" "no-store"}
              :body    (str "Articles unavailable (" (name outcome) ").")}
             (let [;; No `:doctype?` here — this handler wraps a *fragment*
                   ;; (`<div id='app'>…</div>`) inside its own hand-written
                   ;; document envelope below, which already opens with
                   ;; `<!DOCTYPE html>`. `:doctype?` is for a root view that
                   ;; renders the whole `[:html …]` document; asking for it here
                   ;; would prefix a *second* doctype onto the fragment and nest
                   ;; it inside `<div id='app'>`.
                   html     (rf.ssr/render-to-string [(rf/view :app/root)])
                   ;; You'll notice the payload carries no `:rf/frame-id`, and
                   ;; that's the intended shape. The server rendered under a
                   ;; throwaway per-request gensym (`f`); the client hydrates its
                   ;; own fixed `app-frame` (defined below). The two frames have
                   ;; nothing to say to each other by name. A `:rf/frame-id` in
                   ;; the payload isn't a "hydrate into this" instruction — it's
                   ;; evidence. `rf.ssr/hydrate!` checks any id it finds against the
                   ;; `:frame` you explicitly pass, and raises
                   ;; `:rf.error/hydration-frame-id-mismatch` if they disagree.
                   ;; Leaving it out is the no-argument-to-have-here shape, which
                   ;; is exactly what this output and the static `index.html`
                   ;; both use. A deployment that *does* want to carry one stamps
                   ;; a stable id both sides agree on ahead of time — never a
                   ;; per-request gensym. That is the `:client-frame-id` option
                   ;; this call leaves out.
                   ;;
                   ;; And the payload is PROJECTED, never the raw partitions.
                   ;; `hydration-payload` runs the same projection the Ring host
                   ;; (`re-frame.ssr.ring`) does, under this request's frame,
                   ;; which is still live:
                   ;;   - `:payload` is the fail-closed allowlist. The client
                   ;;     reads `:articles`; `:articles/load-state` is the
                   ;;     server's own wait signal, so it stays home.
                   ;;   - Any path the app classified `:sensitive` inside what
                   ;;     the allowlist let through is redacted.
                   ;;   - The runtime-db keeps only the durable route and
                   ;;     machine slices, with their classified values redacted
                   ;;     and the frame's classification registry left out.
                   ;;     Shipping the raw partition instead would put a route's
                   ;;     `:sensitive` query token, and that registry, into
                   ;;     cacheable HTML.
                   ;; This page's runtime-db is empty, so the payload has no
                   ;; `:rf/runtime-db` at all — a present nil would be a
                   ;; malformed slice that `:rf/hydrate` refuses.
                   payload  (rf.ssr/hydration-payload fid {:payload [:articles]})]
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
                     (rf.ssr.html-helpers/escape-edn-script-body (pr-str payload))
                     "</script>"
                     "<script src='/main.js'></script>"
                     "</body></html>")})))
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
;; On the client, the boot is two calls: `rf.ssr/hydrate!`, the mirror image of
;; the server render, then the adapter's hydrating render. `hydrate!` rolls two
;; steps into one, and the order is the point:
;;   1. READ    — pull the embedded `__rf_payload` `<script>` by its pinned id.
;;                A malformed payload fails closed (nothing replaces the frame
;;                state); a missing one just means "nobody server-rendered
;;                this" — a plain first load.
;;   2. HYDRATE — dispatch-sync `[:rf/hydrate payload]`, before the first
;;                render. The framework handler swaps in the server's
;;                serialisable frame state.
;; `hydrate!` hands back the payload it applied (or nil on a plain client load),
;; which lets `run` answer "was this server-rendered?" without going back to
;; sniff the DOM. The reason to route through the helper rather than wire these
;; up yourself: it keeps the fail-closed check and the seed locked together in
;; the one correct order. Then the first render through the client root, with
;; `{:hydrate? true}`, adopts the server's DOM; if React has to re-render part of
;; it, that root reports :rf.ssr/hydration-mismatch. See
;; ../../../../docs/ssr/concepts.md#the-client-side-hydrate-then-adopt.

;; A quick naming note. App events live under a namespace you pick; the reserved
;; `:rf/*` root is the framework's. `:rf/hydrate` is framework-owned. The
;; per-request boot event above is the app's own, `:app/server-init`, and so is
;; this little bootstrap-for-a-plain-load — so it gets the app's own `:ssr/`
;; namespace, not `:rf/`.
(rf/reg-event :ssr/client-bootstrap
  {:doc "Client-side init that runs even when the server never rendered this page."}
  (fn [{:keys [db]} _] {:db db}))

;; The retained React root lives in an atom and gets created lazily inside `run`,
;; never at namespace-load. The rule is that loading a namespace must not touch
;; the DOM — so if several example namespaces get required together, none of them
;; can race the others to slap a root onto the shared `#app`. One root per
;; container, reused across hot reloads.
#?(:cljs (defonce app-root (rf.adapter.reagent/client-root)))

;; The client's app-frame id. The app names its frame out loud and threads the
;; very same id through two places: `rf.ssr/hydrate!` (where the server's state
;; gets seeded) and the root `frame-provider` (where every `dispatch` and
;; `subscribe` in the tree goes looking for its frame). It has to be a
;; `:client`-platform frame, because the `:rf/hydrate` handler fires
;; compatibility-check effects that a `:server` frame would just skip. And since
;; the server payload carries no `:rf/frame-id`, this explicit `:frame` is the
;; hydration target, plain and simple (see the note over in `handle-request`).
(def app-frame :rf/default)

;; Re-render the current view through the adapter's client root. Tagged
;; `^:dev/after-load` so shadow-cljs re-runs it after each hot reload — edited
;; views re-render into the same root and the already-hydrated frame. The
;; handle makes the reload safe by construction: `run` performed the ONE
;; hydrating first render (or a fresh mount on a client-only load), and every
;; later `render!` through the same handle updates that root — never a second
;; root, never a re-hydration — so a reload can't re-seed over the interactive
;; state. HYDRATE happens once, in `run`.
#?(:cljs
   (defn ^:dev/after-load render! []
     (when-let [el (and (exists? js/document) (js/document.getElementById "app"))]
       ;; Render inside the app-frame's `frame-provider`, so every `dispatch` and
       ;; `subscribe` down in the view tree resolves to the frame we hydrated.
       (rf.adapter.reagent/render! app-root
         [rf/frame-provider {:frame app-frame}
          [(rf/view :app/root)]]
         el))))

#?(:cljs
   (defn run []
     ;; First, point the runtime at the Reagent substrate. This is safe to call
     ;; over and over: the first call wires up the adapter, every hot reload
     ;; after that is a no-op. Note what `init!` does *not* do — it installs the
     ;; adapter and stops there. Creating a frame is the app's job, next line.
     ;; (Each adapter namespace exports an `adapter` var; you require it and
     ;; hand the spec map straight in.)
     (rf/init! rf.adapter.reagent/adapter)
     ;; Stand up the client app-frame before we hydrate anything into it.
     ;; Re-registering an existing frame is a no-op, so hot-reload just shrugs
     ;; and carries on. `:platform :client` is what lets the hydrate
     ;; compatibility-check effects actually fire.
     (rf/make-frame {:id app-frame :doc      "ssr-example client app-frame"
                     :platform :client})
     ;; The client frame resolves the same top-level `ArticlesSchema`
     ;; registration the server frames do, so the hydrated `:articles` commit,
     ;; and every interactive commit the reader triggers afterward, get
     ;; validated on the client just as they were on the server.
     ;; READ, then HYDRATE, against the same `app-frame` the mount below uses.
     ;; `hydrate!` seeds STATE only — it never touches the DOM — and returns the
     ;; payload it applied, or nil on a plain client load.
     (let [el      (and (exists? js/document) (js/document.getElementById "app"))
           payload (rf.ssr/hydrate! {:frame app-frame})
           tree    [rf/frame-provider {:frame app-frame} [(rf/view :app/root)]]]
       (when el
         ;; No payload means no server render — a plain first load. Run the
         ;; app's own bootstrap against the frame before the fresh mount; a
         ;; hydrating load skips it, because the server already booted this
         ;; frame's state. (This is app logic, so it stays here in `run` rather
         ;; than inside the shared mount helper.)
         (when-not payload
           (rf/dispatch-sync [:ssr/client-bootstrap] {:frame app-frame}))
         ;; The adopt-vs-fresh decision lives in ONE place — `ssr.mount/mount!`
         ;; — so the DOM-adoption regression executes the exact call this recipe
         ;; ships instead of a copy: payload present ⇒ the first render through
         ;; the client root HYDRATES and ADOPTS the server DOM; absent ⇒ a fresh
         ;; mount. The handle keeps that root for `render!`'s hot-reload
         ;; re-renders.
         (mount/mount! app-root el tree payload)))))

;; No tests in this file — and that's deliberate. The example tree stays
;; test-free so the source reads as pure demonstration; the headless tests that
;; actually exercise all of the above live next door in re-frame.examples-test
;; (implementation/core/test/) and run on the JVM. If you're curious what's
;; covered:
;;   - `ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch` — the
;;     whole round trip: per-request frame, :app/server-init, the article fetch
;;     answered by a stub transport, render-to-string, and a payload the client frame
;;     installs when it hydrates.
;;   - `ssr-example-handle-request-tears-down-on-throw` — the per-request frame
;;     and its request slot are released even when the render throws; the
;;     delayed-reply and failure/deadline tests check that no frame outlives a
;;     200 or a 503.
;;   - `ssr-example-client-hydration-seeds-the-frame` — `hydrate!` installs
;;     the payload and records its version, and reports no mismatch itself.
;; Fail-closed hydration of a malformed payload is the framework's job rather
;; than the example's, so re-frame.ssr-hydration-test (implementation/ssr/test/)
;; covers it.
