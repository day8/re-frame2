(ns boot.core
  "Entry point for the boot example — Pattern-Boot demonstrated.

   `run` wires the Reagent adapter, installs the per-URL canned-HTTP
   stub override (so the example runs standalone — no backend), and
   triggers the boot machine via `:boot/initialise`. The render runs
   immediately; the root view stays on the boot-progress screen until
   the boot machine reaches `:ready`.

   The four mocked endpoints:
     /api/config.json   → static app config (api-base, env, build)
     /api/routes.json   → route table (id + path tuples)
     /api/flags.json    → feature flags
     /api/user.json     → initial user record

   The stub fx routes by URL substring and delegates to the
   framework-shipped `:rf.http/managed-canned-success` per Spec 014
   §Testing, so the canonical reply shape is preserved."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Required for `rf/init!`.
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Managed-HTTP ships in day8/re-frame2-http. The require
            ;; triggers its fx registrations (:rf.http/managed and
            ;; family) at app boot; without it, the child loaders'
            ;; managed-HTTP dispatches would raise :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; This example overrides :rf.http/managed with a per-URL
            ;; stub that delegates to :rf.http/managed-canned-success
            ;; (the same reply shape a live server would produce). The
            ;; canned-stub fx ids register from
            ;; re-frame.http-test-support, NOT from re-frame.http-managed.
            ;; This example IS a test/demo affordance (no real backend
            ;; ships with it), so requiring the test-support ns is
            ;; correct — it's the explicit opt-in for the canned stubs.
            [re-frame.http-test-support]
            [boot.schema]
            [boot.boot]
            [boot.views]))

;; ============================================================================
;; DEMO STUBS — per-URL canned :rf.http/managed override
;; ============================================================================
;;
;; The boot example would normally hit /api/config.json and three more
;; endpoints; we don't ship a backend, so we override `:rf.http/managed`
;; on the default frame to a wrapper fx that synthesises the canned
;; reply per URL substring. Each branch delegates to
;; `:rf.http/managed-canned-success` (Spec 014 §Testing) — the same
;; reply shape a live server would produce.

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
  {:doc "Private — entered via dispatch from :boot.demo/http-stub. Uses
         `:dispatch-later` so framework time controls (Tool-Pair
         time-travel, the documented `:dispatch-later` nil-override
         seam) apply to the demo latency. No user dispatches this
         directly."}
  (fn handler-boot-demo-schedule-reply [_ [_ args-map payload]]
    {:fx [[:dispatch-later
           {:ms    60
            :event [:boot.demo/deliver-reply args-map payload]}]]}))

(rf/reg-event :boot.demo/deliver-reply
  {:doc "Private — fired by the :dispatch-later scheduled in
         :boot.demo/schedule-reply. Delegates to the framework-shipped
         `:rf.http/managed-canned-success` with the per-URL canned
         payload."}
  (fn handler-boot-demo-deliver-reply [_ [_ args-map payload]]
    {:fx [[:rf.http/managed-canned-success (assoc args-map :value payload)]]}))

(rf/reg-fx :boot.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: routes by URL
               substring to canned boot responses so the example runs
               standalone without a backend.

               Dispatches into the private :boot.demo/schedule-reply
               event so the deferred reply rides framework
               `:dispatch-later` (60 ms) rather than raw
               `js/setTimeout`. The delay lets the boot-progress view
               render the per-phase loading state before the replies
               land — without it, the boot resolves in one drain and
               the user only ever sees the `:ready` screen. The reply
               itself lands via the framework-shipped
               `:rf.http/managed-canned-success` (Spec 014 §Testing).

               Framework time controls (Tool-Pair time-travel, the
               documented `:dispatch-later` nil-override seam) apply
               automatically."
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

;; React root named `react-root` (not `root`) so it does NOT collide
;; with any `root-view` registered above. Held in an atom and populated
;; lazily inside `run` rather than at ns-load so multiple example
;; namespaces co-required by the browser-test bundle don't race
;; `create-root` calls onto the same shared `#app` element.
(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. This example uses `:rf/default` as its app frame id:
;; `init!` installs the adapter (it does NOT create the frame), `reg-frame`
;; registers the app frame, the boot dispatch runs under `with-frame`, and
;; the render is wrapped in a `frame-provider` so every in-tree
;; `dispatch`/`subscribe` resolves to the app frame.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)

  ;; Override `:rf.http/managed` on the default frame so every child
  ;; loader's GET routes to the per-URL canned stub above. The
  ;; override applies frame-wide; the boot example doesn't issue any
  ;; non-mocked requests, so a blanket override is the right grain.
  (rf/reg-frame app-frame
    {:doc          "Boot example demo frame."
     :fx-overrides {:rf.http/managed :boot.demo/http-stub}})

  ;; Kick the boot under `with-frame` so the boot dispatch resolves to
  ;; the app frame (a bare dispatch-sync would raise
  ;; :rf.error/no-frame-context under the carried invariant). The
  ;; `:app/boot` machine's :initial state and :data seed
  ;; `[:rf.runtime/machines :snapshots :app/boot]` (runtime-db) on first
  ;; dispatch (per Spec 005 §Restore semantics); :boot/initialise fires
  ;; `[:app/boot [:rf.machine/start]]`, the creation marker that births
  ;; the machine directly into :configuring and runs its :spawn
  ;; entry-cascade, starting the boot sequence.
  (rf/with-frame app-frame
    (rf/dispatch-sync [:boot/initialise]))

  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [boot.views/root-view]])))
