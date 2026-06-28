(ns boot.core
  "Entry point for the boot example — mount, trigger, and the fake backend.

   `run` installs the Reagent adapter and renders the app under a
   frame-provider (docs/core/glossary.md#frame-provider). The provider
   does two jobs: it points HTTP at a per-URL canned stub (so the example
   runs on its own, with no server behind it) and it seeds the boot
   machine on first mount. Until that machine reaches `:ready`, the root
   view stays on the boot-progress screen.

   The four mocked endpoints:
     /api/config.json   → static app config (api-base, env, build)
     /api/routes.json   → route table (id + path tuples)
     /api/flags.json    → feature flags
     /api/user.json     → initial user record

   The stub picks a payload by URL substring and hands off to the
   framework's `:rf.http/managed-canned-success`, so each reply has
   exactly the shape a real server would send
   (docs/resources/glossary.md#reply-map)."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; The adapter `rf/init!` needs.
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Managed HTTP ships in its own artefact; this require
            ;; registers `:rf.http/managed` and family. Without it, every
            ;; child loader's fetch would raise :rf.error/no-such-fx.
            [re-frame.http.managed]
            ;; The canned-stub fx ids (`:rf.http/managed-canned-success`)
            ;; live in test-support. We require it because the demo stub
            ;; below leans on them to fake the backend — same reply shape a
            ;; live server produces.
            [re-frame.http.test-support]
            [boot.schema]
            [boot.boot]
            [boot.views]))

;; ============================================================================
;; DEMO STUBS — per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; The boot wants to fetch four endpoints, but there's no server here to answer
;; them — so we fake one. This section holds the canned payloads and the
;; `:boot.demo/http-stub` fx that picks one by URL substring. The provider in
;; `run` slots this stub in as the frame's `:rf.http/managed` via
;; `:fx-overrides`, and each reply rides `:rf.http/managed-canned-success` so
;; it looks exactly like the real thing.

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
  {:doc "Internal plumbing, fired by :boot.demo/http-stub. It schedules
         the reply with `:dispatch-later` rather than a raw timeout, so
         framework time controls (time-travel, the `:dispatch-later`
         override seam) cover the fake latency too. Not for direct use."}
  (fn handler-boot-demo-schedule-reply [_ [_ args-map payload]]
    {:fx [[:dispatch-later
           {:ms    60
            :event [:boot.demo/deliver-reply args-map payload]}]]}))

(rf/reg-event :boot.demo/deliver-reply
  {:doc "The other half of the fake latency — fired when the
         `:dispatch-later` from :boot.demo/schedule-reply comes due. It
         hands the per-URL payload to the framework's
         `:rf.http/managed-canned-success`, which completes the request."}
  (fn handler-boot-demo-deliver-reply [_ [_ args-map payload]]
    {:fx [[:rf.http/managed-canned-success (assoc args-map :value payload)]]}))

(rf/reg-fx :boot.demo/http-stub
  {:doc       "Our stand-in for `:rf.http/managed`: match the URL by
               substring, return the matching canned payload, and the
               example needs no backend at all.

               The 60 ms delay is deliberate, not laziness. It dispatches
               :boot.demo/schedule-reply so the reply rides `:dispatch-later`
               instead of a raw `js/setTimeout` — which means framework time
               controls (time-travel, the override seam) cover it for free.
               And the pause is what lets you actually *watch* the
               boot-progress screen step through its phases; without it the
               whole boot resolves in one drain and you'd blink straight to
               the `:ready` screen. The reply itself lands via the
               framework's `:rf.http/managed-canned-success`."
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

;; The React root, kept in an atom and created lazily inside `run`. We create
;; it there rather than at ns-load so several example namespaces can share one
;; browser-test bundle without two of them racing `create-root` onto the same
;; `#app` element.
(defonce react-root (atom nil))

;; The app frame id. Every in-tree `dispatch`/`subscribe` resolves to this
;; frame, which the frame-provider below stands up. An app always names its
;; own frame — the runtime never conjures one for you.
(def app-frame :rf/default)

(defn run []
  ;; Hand the adapter's spec map straight to `init!`.
  (rf/init! reagent-adapter/adapter)

  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; One frame-provider does three things — create the frame, configure it,
    ;; seed it:
    ;;
    ;; - `:fx-overrides` swaps `:rf.http/managed` on this frame for the canned
    ;;   stub above, so every loader's GET meets a canned reply. Frame-wide is
    ;;   the right reach here — the boot makes no other requests.
    ;; - `:initial-events` fires `:boot/initialise` once on first mount to
    ;;   kick the boot off. A hot reload reuses the frame and leaves the boot
    ;;   alone, so you don't re-run it on every save.
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :doc            "Boot example demo frame."
                                    :fx-overrides   {:rf.http/managed :boot.demo/http-stub}
                                    :initial-events [[:boot/initialise]]}
                 [boot.views/root-view]])))
