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
            [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Required for `rf/init!`.
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            ;; Loads the registrar so we can resolve the canned-success
            ;; fx for the per-URL stub below.
            [re-frame.registrar :as registrar]
            ;; Managed-HTTP ships in day8/re-frame2-http. The require
            ;; triggers its fx registrations (:rf.http/managed and
            ;; family) at app boot; without it, the child loaders'
            ;; managed-HTTP dispatches would raise :rf.error/no-such-fx.
            [re-frame.http-managed]
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
  [{:id :route/home     :path "/"}
   {:id :route/about    :path "/about"}
   {:id :route/settings :path "/settings"}])

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

(rf/reg-fx :rf.http/managed.boot-demo
  {:doc       "Demo override for `:rf.http/managed`: routes by URL
               substring to canned boot responses so the example runs
               standalone without a backend. Delegates to the
               framework-shipped `:rf.http/managed-canned-success`
               per Spec 014 §Testing.

               A small artificial delay (60 ms) lets the boot-progress
               view render the per-phase loading state before the
               replies land — without it, the boot resolves in one
               drain and the user only ever sees the `:ready` screen.

               NOTE on the raw js/setTimeout below. The deferred work
               is an fx invocation, not a dispatch, so the framework's
               `:dispatch-later` path is not a 1:1 swap. The timer is
               purely demo-stub latency; production app code should
               never use raw `js/setTimeout` (use `:dispatch-later` or
               drive the fx via a private event whose dispatch is
               `:dispatch-later`'d, so framework time controls apply)."
   :platforms #{:server :client}}
  (fn fx-managed-boot-demo [frame-ctx args-map]
    (let [url     (-> args-map :request :url)
          payload (demo-payload-for-url url)
          stub-fn (registrar/handler :fx :rf.http/managed-canned-success)]
      (when stub-fn
        ;; Demo-only artificial latency — see the fx doc above.
        (js/setTimeout
          (fn [] (stub-fn frame-ctx (assoc args-map :value payload)))
          60)))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; React root named `react-root` (not `root`) so it does NOT collide
;; with any `root-view` registered above. Held in an atom and populated
;; lazily inside `run` rather than at ns-load (rf2-gkf9) so multiple
;; example namespaces co-required by the browser-test bundle don't
;; race `create-root` calls onto the same shared `#app` element.
(defonce react-root (atom nil))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)

  ;; Override `:rf.http/managed` on the default frame so every child
  ;; loader's GET routes to the per-URL canned stub above. The
  ;; override applies frame-wide; the boot example doesn't issue any
  ;; non-mocked requests, so a blanket override is the right grain.
  (rf/reg-frame :rf/default
    {:doc          "Boot example demo frame."
     :fx-overrides {:rf.http/managed :rf.http/managed.boot-demo}})

  ;; Kick the boot. The `:app/boot` machine's :initial state and
  ;; :data seed `[:rf/machines :app/boot]` on first dispatch (per
  ;; Spec 005 §Restore semantics); :boot/initialise fires
  ;; `[:app/boot [:rf/start]]`, which transitions :configuring's
  ;; :invoke spawn-fx and starts the boot sequence.
  (rf/dispatch-sync [:boot/initialise])

  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [boot.views/root-view])))
