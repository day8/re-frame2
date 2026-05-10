(ns ssr.core
  "Worked example for [Construction Prompt CP-9](../../Construction-Prompts.md)
   and [EP 011 SSR & Hydration](../../011-SSR.md). A small server+client app:
   server renders a 'recent articles' page; client hydrates and remains
   interactive.

   Per [011-SSR.md](../../spec/011-SSR.md): SSR is part of the target
   architecture, not a future concession. View pure-fn requirement,
   id-valued override seam, hydration via :rf/hydrate.

   This is .cljc so the same code can be evaluated server-side (JVM, with
   :clj branches) and client-side (browser, with :cljs branches).

   Demonstrates:
   - Per-request frame on the server                 (with-frame)
   - :rf/server-init dispatched at frame creation
   - Server-only fx via :platforms #{:server}        (and skipping on the client)
   - Pure hiccup → HTML emitter                       (rf/render-to-string)
   - Hydration payload format                         (:rf/hydration-payload schema)
   - :rf/hydrate replaces (not merges) the client app-db, per the locked
     :replace-app-db policy
   - data-rf-render-hash structural marker on the root element; the runtime
     diffs server vs. client hashes after first render and emits
     :rf.ssr/hydration-mismatch on disagreement

   Runnable form (rf2-vq2s): the hand-written `index.html` next to this
   file ships with pre-rendered HTML inside `<div id='app'>` and a pre-
   baked `<script id='__rf_payload'>` — exactly the shape `handle-request`
   below would emit if a real Clojure server were sitting in front. The
   browser-side `run` reads the payload, dispatches `:rf/hydrate`, and
   renders against the now-seeded app-db."
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame-2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves at
            ;; the call sites below.
            [re-frame.schemas]
            ;; Per rf2-5kpd, managed-HTTP ships in day8/re-frame-2-http.
            ;; Loading the ns here registers the `:rf.http/managed` fx
            ;; family — the SSR worked example dispatches
            ;; `:rf.http/managed` for the article-list fetch and uses
            ;; per-frame `:fx-overrides` to redirect to a canned-success
            ;; stub during render. Without the require, the override
            ;; would target an unregistered fx-id.
            [re-frame.http-managed]
            ;; Per rf2-uo7v, SSR ships in day8/re-frame-2-ssr. Loading
            ;; the ns here registers the six `:rf.server/*` server-only
            ;; fxs, the `:rf/hydrate` event, and the
            ;; `:rf.ssr/default-error-projector`, and publishes the
            ;; late-bind hooks (`:ssr/render-tree-hash`,
            ;; `:ssr/render-to-string`, `:ssr/reg-error-projector`,
            ;; `:ssr/project-error`). Without the require the four core
            ;; re-exports raise `:rf.error/ssr-artefact-missing`.
            [re-frame.ssr :as ssr]
            #?(:cljs [cljs.reader])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(rf/reg-app-schema [:articles]
  [:vector [:map
            [:id    :string]
            [:title :string]
            [:body  :string]]])

;; ============================================================================
;; FX
;; ============================================================================
;;
;; HTTP requests go via the framework-shipped `:rf.http/managed` (Spec 014).
;; The example deliberately doesn't register an HTTP-side fx of its own —
;; the SSR test below uses the `:fx-overrides` seam to redirect
;; `:rf.http/managed` to a per-frame canned-success stub so the JVM-side
;; render exercises the full domino loop without real network traffic.

(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; client-only — server SSR skips this
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (.setItem ls "auth/token" token)))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-fx :rf/server-init
  {:doc       "Per-request server-side initialisation. Reads the request
               cofx, dispatches setup events. Server only."
   :platforms #{:server}}
  (fn handler-rf-server-init [{:keys [db]} [_ request]]
    {:db (assoc db :rf/route {:id :route/articles :params {}})
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles"}
            :decode     :json
            :on-success [:articles/loaded]}]]}))

(rf/reg-event-db :articles/loaded
  (fn handler-articles-loaded [db [_ {:keys [value]}]]
    (assoc db :articles value)))

;; The runtime ships a default :rf/hydrate handler that uses the locked
;; :replace-app-db policy (per Spec 011 §The :rf/hydrate event): server is
;; authoritative for the initial client app-db. We re-register here only to
;; document the contract for the example's readers — the body matches the
;; runtime default. If you wanted client-only transient state to survive
;; hydration, this is the place you'd switch to an explicit merge in the
;; order *you* want — but the default is replace, and that's the spec lock.
(rf/reg-event-fx :rf/hydrate
  {:doc       "Seed the client-side app-db from the server-supplied payload."
   :platforms #{:client}}
  (fn handler-rf-hydrate [_ [_ {:rf/keys [app-db version schema-digest]}]]
    {:db app-db                                     ;; replace, not merge
     :fx (cond-> [[:rf.ssr/check-version version]]
           schema-digest (conj [:rf.ssr/check-schema-digest schema-digest]))}))

;; ============================================================================
;; CLIENT-SIDE INTERACTIVITY EVENTS  (rf2-vq2s)
;; ============================================================================
;;
;; A small interactive surface so we can verify that hydration left the
;; client fully reactive — clicking "Hide bodies" must toggle the body
;; paragraphs in/out without a full re-render. The slice has no server
;; correspondent, so it lives outside the SSR payload's authoritative
;; slice and starts at its default value on the client.

(rf/reg-event-db :articles/toggle-bodies
  (fn [db _] (update db :articles/show-bodies? (fnil not true))))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :articles (fn [db _] (:articles db)))

(rf/reg-sub :articles/show-bodies?
  (fn [db _]
    ;; Default is true so the SSR pass renders bodies; the client can hide
    ;; them post-hydration.
    (let [v (:articles/show-bodies? db)]
      (if (nil? v) true v))))

;; reg-view (defn-shape per Spec 004 §reg-view) auto-defs the symbol and
;; registers under (keyword *ns* sym) — overridden here via
;; ^{:rf/id ...} so the legacy :pages/articles / :app/root ids stay
;; intact for the view callers below.
;;
;; Server-side (JVM) the auto-injected `subscribe` in `reg-view` is a
;; macro-time concept that resolves to (rf/subscriber) — a frame-bound
;; subscribe fn — at runtime. On the JVM render path, deref of a
;; subscription yields its current value; on the client, deref tracks
;; the reaction so re-renders fire on app-db changes. Same code, both
;; sides — that's the SSR/CLJS parity Spec 011 promises.
(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [arts         @(subscribe [:articles])
        show-bodies? @(subscribe [:articles/show-bodies?])]
    [:div.page
     [:h1 "Recent articles"]
     [:button.toggle-bodies
      {:on-click #(dispatch [:articles/toggle-bodies])}
      (if show-bodies? "Hide bodies" "Show bodies")]
     (if (seq arts)
       (into [:ul]
             (for [{:keys [id title body]} arts]
               ^{:key id}
               [:li [:h3 title]
                (when show-bodies? [:p.body body])]))
       [:p "No articles."])]))

(rf/reg-view ^{:rf/id :app/root} root-view []
  [(rf/view :pages/articles)])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; The server flow:
;;   1. Accept request.
;;   2. make-frame; :on-create dispatches :rf/server-init with the request.
;;   3. Drain settles (HTTP fetches resolve via :rf.http/managed; the JVM
;;      transport uses java.net.http.HttpClient under the hood).
;;   4. Render to string via the pure hiccup → HTML emitter.
;;   5. Serialise app-db; ship in the HTML.

#?(:clj
   (defn handle-request [request]
     (let [f (rf/make-frame {:on-create [:rf/server-init request]})]
       (rf/with-frame f
         (fn []
           (let [final-db (rf/get-frame-db f)
                 hiccup   ((rf/view :app/root))
                 ;; render-to-string with :emit-hash? embeds
                 ;; data-rf-render-hash="<hex>" on the root element. The
                 ;; client recomputes the hash after its first render and
                 ;; the runtime emits :rf.ssr/hydration-mismatch on
                 ;; disagreement.
                 html     (rf/render-to-string hiccup
                                               {:doctype?    true
                                                :emit-hash?  true})
                 ;; Same hash also lands on the payload so non-DOM
                 ;; environments (server logs, CDN cache keys) can read it
                 ;; without HTML parsing.
                 render-hash (rf/render-tree-hash hiccup)
                 payload  {:rf/version     1
                           :rf/frame-id    f
                           :rf/app-db      final-db
                           :rf/render-hash render-hash}]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head>"
                   "<meta charset='utf-8'/>"
                   "<title>SSR demo</title>"
                   "</head><body>"
                   "<div id='app'>" html "</div>"
                   "<script id='__rf_payload' type='application/edn'>"
                   (pr-str payload)
                   "</script>"
                   "<script src='/main.js'></script>"
                   "</body></html>")}))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; The client flow:
;;   1. Read the embedded :__rf_payload.
;;   2. Create the client frame.
;;   3. dispatch-sync :rf/hydrate before first render. The handler *replaces*
;;      app-db with the server slice (locked :replace-app-db policy).
;;   4. Render against the now-seeded state. The runtime hashes the client
;;      render-tree, compares to the data-rf-render-hash on the server's
;;      root element, and emits :rf.ssr/hydration-mismatch on disagreement.

#?(:cljs
   (defn read-server-payload []
     (when-let [el (.getElementById js/document "__rf_payload")]
       (cljs.reader/read-string (.-textContent el)))))

(rf/reg-event-db :rf/client-bootstrap
  {:doc "Client-side init that runs even if the server didn't render this page."}
  (fn [db _] db))

#?(:cljs
   (defonce react-root
     (rdc/create-root (js/document.getElementById "app"))))

#?(:cljs
   (defn ^:export run []
     ;; Boot the runtime against the Reagent substrate. Idempotent — the
     ;; first call installs the adapter and creates :rf/default; subsequent
     ;; calls (e.g. shadow-cljs hot reloads) are no-ops.
     ;;
     ;; rf2-agql: pass the adapter spec map directly. There is no
     ;; default-adapter registry — each adapter ns exports an `adapter`
     ;; var the consumer requires and passes here.
     (rf/init! reagent-adapter/adapter)
     ;; If the page was server-rendered, `:rf/hydrate` replaces app-db with
     ;; the payload's :rf/app-db slice (locked :replace-app-db policy per
     ;; Spec 011 §The :rf/hydrate event). On a "client-only" load (no
     ;; payload script), :rf/client-bootstrap runs as a no-op and the page
     ;; renders the empty-articles fallback.
     (if-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload])
       (rf/dispatch-sync [:rf/client-bootstrap]))
     (rdc/render react-root [(rf/view :app/root)])))

;; ============================================================================
;; HEADLESS TESTS  (JVM-runnable; exercises the server flow)
;; ============================================================================

#?(:clj
   (defn ssr-tests []
     ;; Boot the runtime (idempotent) — installs the SSR adapter and the
     ;; :rf/default frame. Per rf2-agql `re-frame.ssr` exports its own
     ;; `adapter` var (the JVM-side counterpart of reagent/uix/helix
     ;; adapters); pass it explicitly.
     (rf/init! ssr/adapter)
     ;; Stub `:rf.http/managed` so the test doesn't make real network
     ;; calls. The per-frame `:fx-overrides` redirect `:rf.http/managed`
     ;; to a per-test stub that delegates to the framework-shipped
     ;; `:rf.http/managed-canned-success` (Spec 014 §Testing) with a
     ;; canned `:value` payload — the same reply shape a live request
     ;; would produce.
     (rf/reg-fx :ssr.http/canned-articles
       {:platforms #{:server :client}}
       (fn [frame-ctx args-map]
         (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
           (stub frame-ctx
                 (assoc args-map
                        :value [{:id "a" :title "Article A" :body "Body A"}
                                {:id "b" :title "Article B" :body "Body B"}])))))

     (let [f           (rf/make-frame {:on-create    [:rf/server-init {:uri "/articles"}]
                                       :fx-overrides {:rf.http/managed :ssr.http/canned-articles}})
           final-db    (rf/get-frame-db f)
           ;; The root view's body invokes the articles-page render fn,
           ;; which calls (rf/subscribe-value [:articles]). Both run
           ;; INSIDE render-to-string's tree walk; with-frame binds
           ;; *current-frame* across that walk so the sub reads from f
           ;; and not from :rf/default.
           hiccup      ((rf/view :app/root))
           html        (rf/with-frame f
                         (fn [] (rf/render-to-string hiccup {:emit-hash? true})))
           render-hash (rf/render-tree-hash hiccup)]
       ;; State was loaded.
       (assert (= 2 (count (:articles final-db))))
       ;; HTML contains the article titles.
       (assert (clojure.string/includes? html "Article A"))
       (assert (clojure.string/includes? html "Article B"))
       ;; HTML round-trips via render-to-string without needing React/JSDOM.
       (assert (clojure.string/includes? html "<h1>"))
       ;; render-hash is a structural marker (lowercase-hex FNV-1a per
       ;; Spec 011); the client recomputes it and the runtime emits a
       ;; :rf.ssr/hydration-mismatch trace event on disagreement.
       (assert (re-matches #"[0-9a-f]{8}" render-hash))
       (assert (clojure.string/includes? html "data-rf-render-hash"))
       :ok)))
