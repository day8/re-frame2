(ns boot.core
  "Entry point for the boot example — mount, trigger, and the fake backend.

   `run` installs the Reagent adapter and renders the app under a
   frame-root (docs/core/glossary.md#frame-root). Before that, `mount!`
   installs a stub transport on the frame, so the example runs on its own
   with no server behind it. The root itself seeds the boot machine on first
   mount. Until that machine reaches `:ready`, the root view stays on the
   boot-progress screen.

   The four mocked endpoints:
     /api/config.json   → static app config (api-base, env, build)
     /api/routes.json   → route table (id + path tuples)
     /api/flags.json    → feature flags
     /api/user.json     → initial user record

   The fake backend picks a payload by URL substring and answers it as JSON.
   It replaces only the network hop: the real `:rf.http/managed` decodes,
   classifies and replies exactly as it would against a server, so each reply
   has the shape a live request produces
   (docs/resources/glossary.md#reply-map)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The adapter `rf/init!` needs.
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            ;; Managed HTTP ships in its own artefact; this require
            ;; registers `:rf.http/managed` and family. Without it, every
            ;; child loader's fetch would raise :rf.error/no-such-fx.
            [re-frame.http.managed]
            ;; The fake backend below is a stub transport, installed on the
            ;; frame in `mount!`. A real deployment drops this require and that
            ;; call.
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [boot.schema]
            [boot.boot]
            [boot.views]))

;; The app frame id. Every in-tree `dispatch`/`subscribe` resolves to this
;; frame, which the frame-root in `mount!` stands up, and the fake backend
;; reads its demo toggle from this frame's app-db. An app always names its own
;; frame — the runtime never conjures one for you.
(def app-frame :rf/default)

;; ============================================================================
;; DEMO BACKEND — a stub transport answering the four endpoints
;; ============================================================================
;;
;; The boot wants to fetch four endpoints, but there's no server here to answer
;; them — so we fake one. This section holds the payloads and `demo-transport`,
;; which picks one by URL substring and answers it as JSON. `mount!` installs
;; it on the frame, below `:rf.http/managed`: the loaders' requests are real
;; managed requests, and only the network hop is replaced.
;;
;; The payloads are the JSON a server would send, so a value is a string where
;; JSON has no keyword — the environment and each route id.
;;
;; The failure seam. A boot's `:failed` branch is the half readers most want to
;; SEE, and a backend that can only succeed is one that hides it — so
;; `:fail-next-boot?` is read from app-db at request time and, when armed, the
;; next `/user.json` load answers a 503 instead. That URL is the interesting
;; one: it fails inside the `:spawn-all`, so you also watch `:on-any-failed`
;; cancel its two siblings on the way to `:failed` (while armed, the backend
;; answers their loads slowly so they are still in flight — see
;; `slow-url-fragments` below). The flag is a ONE-SHOT — the backend disarms it
;; as it fires — so the Retry button on the failure screen boots cleanly and
;; the whole arc is two clicks.

(def ^:private demo-config
  {:api-base "/api"
   :env      "prod"
   :build    "boot-example-1.0.0"
   :title    "Pattern-Boot example app"})

(def ^:private demo-routes
  [{:id "home"     :path "/"}
   {:id "about"    :path "/about"}
   {:id "settings" :path "/settings"}])

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

;; The fake backend's latency, in milliseconds. The pause is deliberate, not
;; laziness: it's what lets you actually *watch* the boot-progress screen step
;; through its phases. Without it the whole boot resolves in a single drain and
;; you'd blink straight to the `:ready` screen.
(def ^:private reply-delay-ms 60)

;; The one URL the "Fail the next boot" toggle rigs. Picked because it is
;; loaded inside the `:spawn-all` fan-out rather than by the lone
;; `:configuring` child, so failing it exercises the join's `:on-any-failed`
;; as well as the boot's own `:failed` screen.
(def ^:private fail-url-fragment "/user.json")

;; The rigged URL's two siblings in the same `:spawn-all`. While the seam is
;; armed the backend answers them only after `slow-delay-ms`, so both are
;; still in flight when /user.json fails and `:on-any-failed` has two
;; survivors to cancel. Left at the ordinary delay they would both finish
;; first, since /user.json is the last of the three children to ask. Cancelling
;; a loader aborts its request, and the abort reaches the stub transport, which
;; drops the pending answer — so a cancelled sibling's reply never arrives.
(def ^:private slow-url-fragments ["/routes.json" "/flags.json"])
(def ^:private slow-delay-ms (* 20 reply-delay-ms))

(defn- demo-answer
  "The fake backend: the HTTP response for one request, by URL. Reads the
  `:fail-next-boot?` app-db flag at request time: when armed, the
  `/user.json` load answers a 503, its two siblings answer slowly so
  `:on-any-failed` has them to cancel, and the flag is disarmed on the way
  past so the next boot is clean."
  [{:keys [url]}]
  (let [armed? (boolean (:fail-next-boot? (rf/app-db-value app-frame)))]
    (cond
      (and armed? (str/includes? (str url) fail-url-fragment))
      (do (rf/dispatch [:boot.demo/set-fail-next false] {:frame app-frame})
          {:status      503
           :status-text "Service Unavailable"
           :body        {:message "Simulated /user.json outage (the demo's failure seam)."}})

      (and armed? (some #(str/includes? (str url) %) slow-url-fragments))
      {:body (demo-payload-for-url url) :delay-ms slow-delay-ms}

      :else
      {:body (demo-payload-for-url url)})))

(def demo-transport
  "The stub transport `mount!` installs on the frame: `demo-answer` behind a
  `reply-delay-ms` pause."
  (rf.http.stub-transport/route-transport demo-answer {:delay-ms reply-delay-ms}))

;; The toggle itself: an ordinary app-db slice in the ordinary loop — an event
;; writes it, a sub reads it, the view reads the sub. It is demo chrome rather
;; than boot state, which is why it lives out here beside the fake backend it
;; rigs and not in the boot machine's `:data`.

(rf/reg-event :boot.demo/set-fail-next
  {:doc "Arm or disarm the one-shot '/user.json answers 503' seam. The fake
         backend disarms it as it fires, so a Retry after the failure screen
         boots cleanly without the reader having to untick anything."}
  (fn handler-set-fail-next [{:keys [db]} [_ on?]]
    {:db (assoc db :fail-next-boot? (boolean on?))}))

(rf/reg-sub :boot.demo/fail-next? (fn [db _] (boolean (:fail-next-boot? db))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is the adapter's: `client-root` returns an inert handle that
;; does no DOM work at ns-load, and the first `render!` through it, inside
;; `mount!`, creates the Root. Deferring the DOM work to that render means several example namespaces can share one
;; browser-test bundle without two of them racing `create-root` onto the same
;; `#app` element.
(defonce app-root (rf.adapter.reagent/client-root))

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    ;; `install-transport!` sends the frame's managed HTTP to the fake backend
    ;; above, so every loader's GET meets a demo reply. Frame-wide is the right
    ;; reach here — the boot makes no other requests.
    (rf.http.stub-transport/install-transport! app-frame demo-transport)
    ;; The frame-root creates the frame and seeds it: `:initial-events` fires
    ;; `:boot/initialise` once on first mount to kick the boot off. A hot
    ;; reload reuses the frame and leaves the boot alone, so you don't re-run
    ;; it on every save.
    (rf.adapter.reagent/render! app-root
      [rf/frame-root {:id             app-frame
                      :doc            "Boot example demo frame."
                      :initial-events [[:boot/initialise]]}
       [boot.views/root-view]]
      el)))

(defn run []
  ;; Hand the adapter's spec map straight to `init!`.
  (rf/init! rf.adapter.reagent/adapter)
  (mount!))
