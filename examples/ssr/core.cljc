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
     :rf.ssr/hydration-mismatch on disagreement"
  (:require [re-frame.core :as rf]
            #?(:cljs [reagent.dom.client :as rdc])))

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

(rf/reg-fx :http/get
  {:doc       "GET request. Returns to dispatch on success/error.
                Thread the active frame through to dispatch so the
                continuation lands in the right frame's app-db."
   :platforms #{:server :client}}
  (fn fx-http-get [{:keys [frame]} {:keys [url on-success on-error]}]
    #?(:cljs (-> (js/fetch url)
                 (.then  #(.json %))
                 (.then  #(when on-success
                            (rf/dispatch (conj on-success (js->clj % :keywordize-keys true))
                                         {:frame frame})))
                 (.catch #(when on-error
                            (rf/dispatch (conj on-error %) {:frame frame}))))
       :clj  (try
               (let [resp (slurp url)
                     data (clojure.edn/read-string resp)]
                 (when on-success
                   (rf/dispatch (conj on-success data) {:frame frame})))
               (catch Exception e
                 (when on-error
                   (rf/dispatch (conj on-error e) {:frame frame})))))))

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
    {:db (assoc db :route {:id :route/articles :params {}})
     :fx [[:http/get {:url "/api/articles" :on-success [:articles/loaded]}]]}))

(rf/reg-event-db :articles/loaded
  (fn handler-articles-loaded [db [_ articles]]
    (assoc db :articles articles)))

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
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :articles (fn [db _] (:articles db)))

;; reg-view (defn-shape per Spec 004 §reg-view) auto-defs the symbol and
;; registers under (keyword *ns* sym) — overridden here via
;; ^{:rf/id ...} so the legacy :pages/articles / :app/root ids stay
;; intact for the view callers below.
(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [arts (rf/subscribe-value [:articles])]
    [:div.page
     [:h1 "Recent articles"]
     (if (seq arts)
       (into [:ul]
             (for [{:keys [id title body]} arts]
               ^{:key id}
               [:li [:h3 title] [:p body]]))
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
;;   3. Drain settles (HTTP fetches resolve via the JVM-side :http/get).
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

#?(:cljs
   (defonce client-frame
     (rf/reg-frame :app/main {:on-create [:rf/client-bootstrap]})))

(rf/reg-event-db :rf/client-bootstrap
  {:doc "Client-side init that runs even if the server didn't render this page."}
  (fn [db _] db))

#?(:cljs
   (defonce root
     (rdc/create-root (js/document.getElementById "app"))))

#?(:cljs
   (defn ^:export run []
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload] {:frame :app/main}))
     (rdc/render root [(rf/view :app/root)])))

;; ============================================================================
;; HEADLESS TESTS  (JVM-runnable; exercises the server flow)
;; ============================================================================

#?(:clj
   (defn ssr-tests []
     ;; Boot the runtime (idempotent) — installs the plain-atom adapter
     ;; and the :rf/default frame.
     (rf/init!)
     ;; Stub :http/get so the test doesn't make real network calls. The
     ;; per-frame :fx-overrides redirect :http/get to this canned stub.
     ;; Note: fx handlers receive {:frame frame-id} as their first arg —
     ;; thread it through to dispatch so the :on-success continuation
     ;; lands in the right frame's app-db.
     (rf/reg-fx :http/get.canned-articles
       {:platforms #{:server :client}}
       (fn [{:keys [frame]} {:keys [on-success]}]
         (when on-success
           (rf/dispatch (conj on-success
                              [{:id "a" :title "Article A" :body "Body A"}
                               {:id "b" :title "Article B" :body "Body B"}])
                        {:frame frame}))))

     (let [f           (rf/make-frame {:on-create    [:rf/server-init {:uri "/articles"}]
                                       :fx-overrides {:http/get :http/get.canned-articles}})
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
