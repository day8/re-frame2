(ns boot.core
  "Entry point for the boot example — mount, trigger, and the fake backend.

   `run` installs the Reagent adapter and renders the app under a
   frame-root (docs/core/glossary.md#frame-root). The root
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
            [re-frame.core :as rf]
            ;; `rf.registrar/handler` looks up the framework's canonical
            ;; canned-success fx handler so the demo stub can DELEGATE to it
            ;; (with an `:after-ms` delay) instead of hand-rolling the latency
            ;; plumbing.
            [re-frame.registrar :as rf.registrar]
            ;; The adapter `rf/init!` needs.
            [re-frame.adapter.reagent :as rf.adapter.reagent]
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
;;
;; The failure seam. A boot's `:failed` branch is the half readers most want to
;; SEE, and a stub that can only succeed is a stub that hides it — so
;; `:fail-next-boot?` is read from app-db at request time and, when armed, the
;; next `/user.json` load answers a 503 instead. That URL is the interesting
;; one: it fails inside the `:spawn-all`, so you also watch `:on-any-failed`
;; cancel its two siblings on the way to `:failed`. The flag is a ONE-SHOT —
;; the stub disarms it as it fires — so the Retry button on the failure screen
;; boots cleanly and the whole arc is two clicks.

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

(rf/reg-fx :boot.demo/http-stub
  {:doc       "Our stand-in for `:rf.http/managed`: match the URL by
               substring to pick a canned payload, then DELEGATE to the
               framework's own `:rf.http/managed-canned-success` — handing
               it the payload as `:value` and `reply-delay-ms` as
               `:after-ms`. With no server behind the example, that's all it
               takes to fake the backend.

               Reads the `:fail-next-boot?` app-db flag at request time:
               when armed, the `/user.json` load answers a 503 through
               `:rf.http/managed-canned-failure` instead, and the stub
               disarms the flag on its way past so the next boot is clean.

               The framework canned-success handler owns everything about
               the delayed reply: `:after-ms` defers it via the
               framework-native `:dispatch-later` (not a raw `js/setTimeout`),
               so the fake latency is observable in the tape and
               time-travel-safe — Tool-Pair time-travel and the
               `:dispatch-later` override seam apply for free — and the reply
               addresses the originating loader exactly as the immediate path
               would. The demo owns only the URL→payload routing; the
               transport plumbing is entirely the framework's."
   :platforms #{:server :client}}
  (fn fx-managed-boot-demo [frame-ctx args-map]
    (let [url   (-> args-map :request :url)
          ;; The fx context carries the envelope frame as `:frame`. The
          ;; `:fail-next-boot?` toggle is an ordinary app-db slice (written by
          ;; `:boot.demo/set-fail-next`), so read it off the frame's APP-db
          ;; partition (`:rf.db/app`) — app state lives there, not in the
          ;; `:rf.db/runtime` partition, which is framework runtime only.
          db    (:rf.db/app (rf/frame-state-value (:frame frame-ctx)))
          fail? (and (str/includes? (str url) fail-url-fragment)
                     (boolean (:fail-next-boot? db)))]
      (if fail?
        ;; Armed: answer this one load with a 503 so the boot takes its
        ;; `:failed` branch. Disarm first (it's a one-shot), then hand off to
        ;; the canned-failure fx — its reply rides `:after-ms` →
        ;; `:dispatch-later` exactly as the success path does. The
        ;; canned-failure args contract (Spec 014 §Testing) is top-level
        ;; `:kind` + `:tags`: the tags merge into the classified failure map
        ;; the reply carries under `:error`, which is what the loader's
        ;; `:failed` leaf lifts as its `:output-key` and what the boot's
        ;; `:record-failure` finally puts on the error screen.
        (let [canned-failure (rf.registrar/handler :fx :rf.http/managed-canned-failure)]
          (rf/dispatch [:boot.demo/set-fail-next false])
          (canned-failure frame-ctx
                          (assoc args-map
                                 :after-ms reply-delay-ms
                                 :kind     :rf.http/http-5xx
                                 :tags     {:status  503
                                            :message "Simulated /user.json outage (the demo's failure seam)."})))
        ;; Otherwise the ordinary happy path. Delegate to the framework
        ;; canned-success handler. Passing `frame-ctx` straight through
        ;; preserves the `:frame` stamp and the originating `:event`, so the
        ;; deferred reply is addressed to the loader that issued the GET — the
        ;; handler's own `:after-ms` plumbing carries that origin across the
        ;; `:dispatch-later` boundary.
        (let [canned-success (rf.registrar/handler :fx :rf.http/managed-canned-success)]
          (canned-success frame-ctx
                          (assoc args-map :value    (demo-payload-for-url url)
                                          :after-ms reply-delay-ms)))))))

;; The toggle itself: an ordinary app-db slice in the ordinary loop — an event
;; writes it, a sub reads it, the view reads the sub. It is demo chrome rather
;; than boot state, which is why it lives out here beside the fake backend it
;; rigs and not in the boot machine's `:data`.

(rf/reg-event :boot.demo/set-fail-next
  {:doc "Arm or disarm the one-shot '/user.json answers 503' seam. The stub
         disarms it as it fires, so a Retry after the failure screen boots
         cleanly without the reader having to untick anything."}
  (fn handler-set-fail-next [{:keys [db]} [_ on?]]
    {:db (assoc db :fail-next-boot? (boolean on?))}))

(rf/reg-sub :boot.demo/fail-next? (fn [db _] (boolean (:fail-next-boot? db))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root, kept in an atom and created lazily inside `run`. We create
;; it there rather than at ns-load so several example namespaces can share one
;; browser-test bundle without two of them racing `create-root` onto the same
;; `#app` element.
(defonce app-root (rf.adapter.reagent/client-root))

;; The app frame id. Every in-tree `dispatch`/`subscribe` resolves to this
;; frame, which the frame-root below stands up. An app always names its
;; own frame — the runtime never conjures one for you.
(def app-frame :rf/default)

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    ;; One frame-root does three things — create the frame, configure it,
    ;; seed it:
    ;;
    ;; - `:fx-overrides` swaps `:rf.http/managed` on this frame for the canned
    ;;   stub above, so every loader's GET meets a canned reply. Frame-wide is
    ;;   the right reach here — the boot makes no other requests.
    ;; - `:initial-events` fires `:boot/initialise` once on first mount to
    ;;   kick the boot off. A hot reload reuses the frame and leaves the boot
    ;;   alone, so you don't re-run it on every save.
    (rf.adapter.reagent/render! app-root
      [rf/frame-root {:id             app-frame
                      :doc            "Boot example demo frame."
                      :fx-overrides   {:rf.http/managed :boot.demo/http-stub}
                      :initial-events [[:boot/initialise]]}
       [boot.views/root-view]]
      el)))

(defn run []
  ;; Hand the adapter's spec map straight to `init!`.
  (rf/init! rf.adapter.reagent/adapter)
  (mount!))
