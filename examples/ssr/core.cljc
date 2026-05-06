(ns ssr.core
  "Worked example for [Construction Prompt CP-9](../../Construction-Prompts.md)
   and [EP 011 SSR & Hydration](../../011-SSR.md). A small server+client app:
   server renders a 'recent articles' page; client hydrates and remains
   interactive.

   Per [reorient.md](../../reorient.md): SSR is part of the target
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
   - :rf/hydrate event seeding the client app-db
   - First-render hash mismatch detection trace event"
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
  {:doc       "GET request. Returns to dispatch on success/error."
   :platforms #{:server :client}}
  (fn fx-http-get [m {:keys [url on-success on-error]}]
    #?(:cljs (-> (js/fetch url)
                 (.then  #(.json %))
                 (.then  #(when on-success
                            (rf/dispatch (conj on-success (js->clj % :keywordize-keys true))
                                         {:frame (:frame m)})))
                 (.catch #(when on-error
                            (rf/dispatch (conj on-error %) {:frame (:frame m)}))))
       :clj  (try
               (let [resp (slurp url)
                     data (clojure.edn/read-string resp)]
                 (when on-success
                   (rf/dispatch (conj on-success data) {:frame (:frame m)})))
               (catch Exception e
                 (when on-error
                   (rf/dispatch (conj on-error e) {:frame (:frame m)})))))))

(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; client-only — server SSR skips this
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (.setItem js/localStorage "auth/token" token))))

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

(rf/reg-event-db :rf/hydrate
  {:doc "Seed the client-side app-db from the server-supplied payload."}
  (fn handler-rf-hydrate [db [_ payload]]
    ;; Merge so client-bootstrap state (like :browser/window-size) survives.
    (merge db (:rf/app-db payload))))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :articles (fn [db _] (:articles db)))

(def articles-page
  (rf/reg-view :pages/articles
    (fn render-articles []
      (let [arts @(subscribe [:articles])]
        [:div.page
         [:h1 "Recent articles"]
         (if (seq arts)
           [:ul
            (for [{:keys [id title body]} arts]
              ^{:key id}
              [:li [:h3 title] [:p body]])]
           [:p "No articles."])]))))

(def root-view
  (rf/reg-view :app/root
    (fn render-root [] [articles-page])))

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
     (let [frame-id (gensym "ssr-")]
       (rf/with-frame [f (rf/make-frame {:id frame-id
                                         :on-create [:rf/server-init request]})]
         (let [final-db @(rf/get-frame-db f)
               hiccup   ((rf/get-view :app/root))
               html     (rf/render-to-string hiccup {:frame f :doctype? true})
               render-hash (hash hiccup)
               payload  {:rf/version    "1.0"
                         :rf/frame-id   frame-id
                         :rf/app-db     final-db
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
                 "</body></html>")})))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; The client flow:
;;   1. Read the embedded :__rf_payload.
;;   2. Create the client frame.
;;   3. dispatch-sync :rf/hydrate before first render.
;;   4. Render against the now-seeded state. First render should match the
;;      server's HTML byte-for-byte (hash check is automatic).

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
     (rdc/render root [root-view])))

;; ============================================================================
;; HEADLESS TESTS  (JVM-runnable; exercises the server flow)
;; ============================================================================

#?(:clj
   (defn ssr-tests []
     ;; Stub :http/get so the test doesn't make real network calls.
     (rf/reg-fx :http/get.canned-articles
       {:platforms #{:server :client}}
       (fn [_m {:keys [on-success]}]
         (when on-success
           (rf/dispatch (conj on-success
                              [{:id "a" :title "Article A" :body "Body A"}
                               {:id "b" :title "Article B" :body "Body B"}])))))

     (rf/with-frame [f (rf/make-frame {:on-create    [:rf/server-init {:uri "/articles"}]
                                       :fx-overrides {:http/get :http/get.canned-articles}})]
       (let [final-db @(rf/get-frame-db f)
             hiccup   ((rf/get-view :app/root))
             html     (rf/render-to-string hiccup {:frame f})]
         ;; State was loaded.
         (assert (= 2 (count (:articles final-db))))
         ;; HTML contains the article titles.
         (assert (clojure.string/includes? html "Article A"))
         (assert (clojure.string/includes? html "Article B"))
         ;; HTML round-trips via render-to-string without needing React/JSDOM.
         (assert (clojure.string/includes? html "<h1>"))))))
