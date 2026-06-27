(ns boot.core
  "Entry point for the boot example.

   `run` installs the Reagent adapter and renders the app under a
   frame-provider (docs/guide/glossary.md#frame-provider). The provider
   redirects HTTP to a per-URL canned stub (so the example runs
   standalone — no backend) and seeds the boot machine on first mount.
   The root view stays on the boot-progress screen until the boot
   machine reaches `:ready`.

   The four mocked endpoints:
     /api/config.json   → static app config (api-base, env, build)
     /api/routes.json   → route table (id + path tuples)
     /api/flags.json    → feature flags
     /api/user.json     → initial user record

   The stub routes by URL substring and delegates to the framework's
   `:rf.http/managed-canned-success`, so the reply shape matches what a
   live server would produce (docs/resources/glossary.md#reply-map)."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Required for `rf/init!`.
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Managed HTTP ships in a separate artefact. The require
            ;; registers `:rf.http/managed` and family; without it the
            ;; child loaders' dispatches would raise :rf.error/no-such-fx.
            [re-frame.http.managed]
            ;; This example overrides :rf.http/managed with a per-URL stub
            ;; that delegates to :rf.http/managed-canned-success (the same
            ;; reply shape a live server produces). The canned-stub fx ids
            ;; register from re-frame.http.test-support, so require it here
            ;; to opt into the canned stubs.
            [re-frame.http.test-support]
            [boot.schema]
            [boot.boot]
            [boot.views]))

;; ============================================================================
;; DEMO STUBS — per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; The boot fetches /api/config.json and three more endpoints, but no backend
;; ships with this example. This section defines the canned payloads and the
;; `:boot.demo/http-stub` fx that returns one per URL substring. The provider
;; in `run` wires this stub in as the frame's `:rf.http/managed` via
;; `:fx-overrides`. Each reply delegates to `:rf.http/managed-canned-success`,
;; the same reply shape a live server produces.

(def ^:private demo-config
  {:api-base "/api"
   :env      :prod
   :build    "boot-example-1.0.0"
   :title    "Pattern-Boot example app"})

(def ^:private demo-routes
  [{:id :boot.demo/home     :path "/"}
   {:id :boot.demo/about    :path "/about"}
   {:id :boot.demo/settings :path "/settings"}])

(def ^:private demo-flags
  {:dark-mode?       false
   :beta-channel?    true
   :onboarding-skip? false})

(def ^:private demo-user
  {:id       "user-1"
   :username "stub-bot"
   :email    "stub-bot@example.com"})

(defn- demo-payload-for-url [url]
  (let [u (str url)]
    (cond
      (str/includes? u "/config.json") demo-config
      (str/includes? u "/routes.json") demo-routes
      (str/includes? u "/flags.json")  demo-flags
      (str/includes? u "/user.json")   demo-user
      :else                            {})))

(rf/reg-event :boot.demo/schedule-reply
  {:doc "Private — dispatched from :boot.demo/http-stub. Uses
         `:dispatch-later` so framework time controls (time-travel,
         the `:dispatch-later` override seam) apply to the demo
         latency. No user dispatches this directly."}
  (fn handler-boot-demo-schedule-reply [_ [_ args-map payload]]
    {:fx [[:dispatch-later
           {:ms    60
            :event [:boot.demo/deliver-reply args-map payload]}]]}))

(rf/reg-event :boot.demo/deliver-reply
  {:doc "Private — fired by the :dispatch-later scheduled in
         :boot.demo/schedule-reply. Delegates to the framework's
         `:rf.http/managed-canned-success` with the per-URL canned
         payload."}
  (fn handler-boot-demo-deliver-reply [_ [_ args-map payload]]
    {:fx [[:rf.http/managed-canned-success (assoc args-map :value payload)]]}))

(rf/reg-fx :boot.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL
               substring to canned boot responses so the example runs
               standalone without a backend.

               Dispatches the private :boot.demo/schedule-reply event so
               the deferred reply rides `:dispatch-later` (60 ms) rather
               than raw `js/setTimeout`. The delay lets the boot-progress
               view render the per-phase loading state before the replies
               land — without it the boot resolves in one drain and the
               user only ever sees the `:ready` screen. The reply lands
               via the framework's `:rf.http/managed-canned-success`.

               Framework time controls (time-travel, the
               `:dispatch-later` override seam) apply automatically."
   :platforms #{:server :client}}
  (fn fx-managed-boot-demo [frame-ctx args-map]
    (let [url     (-> args-map :request :url)
          payload (demo-payload-for-url url)
          frame   (:frame frame-ctx)]
      (rf/dispatch [:boot.demo/schedule-reply args-map payload]
                   {:frame frame}))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root, held in an atom and created lazily inside `run`. Doing it
;; in `run` (not at ns-load) lets several example namespaces share the
;; browser-test bundle without racing `create-root` onto the same `#app`
;; element.
(defonce react-root (atom nil))

;; The app frame id. Every in-tree `dispatch`/`subscribe` resolves to this
;; frame; the frame-provider below establishes it (see `run`). An app must
;; name its frame explicitly — the runtime never invents one.
(def app-frame :rf/default)

(defn run []
  ;; Install the adapter by passing its spec map directly.
  (rf/init! reagent-adapter/adapter)

  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The frame-provider creates, configures, and seeds the app frame in
    ;; one place:
    ;;
    ;; - `:fx-overrides` redirects `:rf.http/managed` on this frame to the
    ;;   per-URL canned stub above, so every child loader's GET hits a
    ;;   canned reply. Frame-wide is the right grain — the boot example
    ;;   issues no other requests.
    ;; - `:initial-events` fires `:boot/initialise` once on first mount,
    ;;   which kicks off the boot machine. On hot reload the frame is
    ;;   reused and the boot is not re-run.
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :doc            "Boot example demo frame."
                                    :fx-overrides   {:rf.http/managed :boot.demo/http-stub}
                                    :initial-events [[:boot/initialise]]}
                 [boot.views/root-view]])))
