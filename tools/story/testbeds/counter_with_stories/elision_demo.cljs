(ns counter-with-stories.elision-demo
  "Privacy + Size elision demo.

  The README markets `:sensitive?` + `:large?` elision as one of
  re-frame2's headline novel-things; this namespace is the canonical
  worked demo of the arc end-to-end. Four pieces, each with a
  buttoned-up affordance so a visitor can drive the pipeline and
  watch the elision contract fire.

  ## The four pieces

  1. **`:sensitive?` handler metadata** — `:auth/sign-in` carries a
     password in its event-vector payload. The registration declares
     `:sensitive? true` in its `:rf/registration-metadata` map so the
     runtime stamps `:sensitive? true` on every TRACE event emitted
     inside the handler's scope (per Spec 009 §Privacy). Xray's
     trace collector reads that top-level stamp and drops the event
     from the buffer (default posture); the bottom rail surfaces a
     `[● REDACTED N]` hint.

  2. **Schema-installed redaction** — per-slot `{:sensitive? true}`
     metadata is the path-level privacy surface. This event payload is
     intentionally modeled with handler metadata because it is not an
     app-db path-scoped write.

  3. **`:large` classified app-db slot** — the `:user/avatar-pdf`
     slot is classified durable-large on the testbed's `:rf/default`
     frame by the `:counter/classify-avatar-large` event, which returns
     the EP-0025 commit-plane `:large [[:user/avatar-pdf]]` effect
     alongside `:db` (durable app-db egress classification rides the
     effects, not a schema prop, not a frame annotation). When
     `rf/elide-wire-value` walks an app-db payload that includes this
     slot at its classified path, the value is replaced with a
     `{:rf.size/large-elided {:bytes … :path … :reason :effect}}`
     marker. The marker's `:reason :effect` records the commit-plane
     classification provenance; there is no schema-attached `:hint` to
     propagate (the schema describes shape only).

  4. **Always-on `event-emit` listener** — the demo registers a
     console-logger via `register-listener!` (the `:events`
     channel). The
     listener receives one record per processed event and
     demonstrates the production-survivable substrate the chapter-22
     Datadog recipe pivots around: the listener fires under `:advanced`
     + `goog.DEBUG=false` where the trace surface is DCE'd.

  ## What to look for in the running app

  Open the browser console + (optionally) the Xray panel.

  - **Click 'Sign in (sensitive)'** — dispatches `:auth/sign-in`.
    In Xray the event is absent from the trace panel (filtered out
    by the `:sensitive?` top-level stamp); the bottom rail shows
    `[● REDACTED N]`. The console line from the always-on listener
    is honest about its scope: it shows the original event vector
    because the event-emit substrate consults handler-meta for
    `:sensitive?` before fan-out.

  - **Click 'Upload large avatar (inline)'** — dispatches
    `:user.avatar/upload` with a 20 kB string in the event payload.
    The console line from the listener shows the inline blob raw and
    intact: Path D removed runtime size auto-elision, so an
    unschema'd / unnominated inline blob is never substituted. Large
    egress is declared on app-db slots via the frame's `:large`
    classification, not auto-detected on arbitrary event-vector blobs.

  - **Click 'Walk app-db through elision'** — runs
    `rf/elide-wire-value` over a snapshot of the live frame's
    app-db. The console shows the `:user/avatar-pdf` slot replaced
    by the `:rf.size/large-elided` marker, carrying `:reason :effect`
    (the commit-plane classification provenance). This is the
    classification-driven branch — the same substitution Xray applies
    when it renders `app-db` in its inspector panel.

  Pre-alpha."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.schemas.malli])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMAS  (Spec 010 §`:large?` per-slot meta)
;; ============================================================================
;;
;; The `:user/avatar-pdf` slot is classified durable-large on the
;; testbed's `:rf/default` frame by the `:counter/classify-avatar-large`
;; event (the EP-0025 commit-plane `:large` effect, dispatched in
;; `core/run`), so the wire-walker substitutes its value with the
;; `:rf.size/large-elided` marker (carrying byte-count + path +
;; `:reason :effect`) whenever any wire consumer walks app-db through
;; `rf/elide-wire-value`. EP-0025: durable app-db egress classification
;; rides the commit-plane effects, not a schema prop — the schema below
;; describes SHAPE only.

;; Schema describes SHAPE only (EP-0015 §8): `:user/avatar-pdf` is an
;; optional string. Durable app-db egress classification rides the EP-0025
;; commit-plane effects — the `:counter/classify-avatar-large` event returns
;; `:large [[:user/avatar-pdf]]` alongside `:db` (see `core/run`) and that is
;; what makes this slot elide to the `:rf.size/large-elided` marker at wire
;; egress. Schemas no longer carry `:large?` / `:sensitive?` app-db egress
;; markers.
;;
;; The runtime ROLLS BACK on a post-commit app-db schema failure — the slot
;; may be `nil` (when the user hasn't uploaded yet), so the schema permits
;; nil via `:maybe`.
;;
;; EP-0002: `reg-app-schema` is context-required frame-local —
;; a bare ns-load call under no scope raises `:rf.error/no-frame-context`
;; (boot-time namespace loading is NOT a reason to synthesise `:rf/default`,
;; per Spec 002 §Frame target resolution). This demo's app runs in the
;; `:rf/default` frame (see `core/run`), so name it explicitly here via
;; `with-frame` — the registration carries a frame stamp even though it
;; lands at module-load before `init!`.
(with-frame :rf/default
  (rf/reg-app-schema [:user/avatar-pdf]
                     [:maybe :string]))

;; ============================================================================
;; EVENTS  (Spec 009 §`:sensitive?`)
;; ============================================================================
;;
;; Handler metadata is the cross-cutting escape hatch for event payloads
;; that cannot be represented as schema-sensitive app-db slots.

(rf/reg-event :auth/sign-in
  ;; Registration metadata — the registrar copies `:sensitive? true`
  ;; into the registry slot's meta; the runtime hoists it to the
  ;; TOP level of every trace event emitted within this handler's
  ;; scope. Xray filters on the top-level stamp.
  {:doc        "Demo sign-in handler — the password rides the event
                vector. `:sensitive? true` tells trace consumers and
                always-on substrates to suppress or redact these events."
   :sensitive? true}

  (fn handler-auth-sign-in [{:keys [db]} [_ {:keys [email password]}]]
    ;; In a real app this is where you'd dispatch the http request
    ;; carrying `email` + `password`. We just stash a redacted
    ;; placeholder in app-db so the demo card has something to
    ;; render — the `:auth/last-sign-in` slot is for UI feedback,
    ;; NOT for storing the password (passwords never live in
    ;; app-db). We reference `password` so the handler-body
    ;; semantic — "the handler sees the unredacted value via :event"
    ;; — is visible in the source.
    (assert (string? password) "handler sees the unredacted password")
    {:db (assoc db
                :auth/last-sign-in
                {:email      email
                 :submitted? true})}))

(rf/reg-event :user.avatar-pdf/set
  {:doc "Demo: write a synthetic large blob into the classified
         `:large` app-db slot. Walking app-db through `rf/elide-wire-value`
         substitutes the slot value with a `:rf.size/large-elided`
         marker carrying `:reason :effect` (the commit-plane classification
         provenance)."}
  (fn [{:keys [db]} [_ {:keys [bytes]}]]
    {:db (assoc db :user/avatar-pdf (str/join (repeat (or bytes 5000) "A")))}))

(rf/reg-event :user.avatar-pdf/clear
  {:doc "Drop the avatar slot. Uses `dissoc` rather than `(assoc ...
         nil)` so the slot is absent from app-db rather than carrying
         a typed `nil`, matching the schema's `:string` declaration
         (Spec 010 §Optional slots: absence is the soft-pass-friendly
         shape for an optional slot)."}
  (fn [{:keys [db]} _] {:db (dissoc db :user/avatar-pdf)}))

(rf/reg-event :user.avatar/upload
  {:doc "Demo: dispatch a 20 kB blob INLINE inside the event vector.
         The blob is NOT declared large anywhere — Path D removed
         runtime size auto-elision, so an unschema'd / unnominated
         inline payload rides through the event-emit listener UNCHANGED
         (no `:rf.size/large-elided` substitution). Large-value egress
         is declared, not auto-detected: classify app-db slots via the
         commit-plane `:large` effect (a reg-event returning `:large`
         alongside `:db`), the contrast to the classification-driven
         branch above."}
  (fn [{:keys [db]} [_ {:keys [_blob]}]]
    ;; We don't keep the blob; the point is the elision at the wire
    ;; boundary. Bump a counter so the UI has something to render.
    {:db (update db :user/uploads (fnil inc 0))}))

;; ============================================================================
;; SUBSCRIPTIONS  (UI plumbing only — none of these flow on the wire)
;; ============================================================================

(rf/reg-sub :auth/last-sign-in
  (fn [db _] (:auth/last-sign-in db)))

(rf/reg-sub :user/avatar-pdf-size
  ;; Layer-1 size sub — what the UI displays. We deliberately do NOT
  ;; expose the full blob through a sub; the UI only needs the size.
  (fn [db _]
    (when-let [s (:user/avatar-pdf db)]
      (count s))))

(rf/reg-sub :user/uploads
  (fn [db _] (or (:user/uploads db) 0)))

;; ============================================================================
;; ALWAYS-ON EVENT-EMIT LISTENER  (Spec 009 §Event-emit listener)
;; ============================================================================
;;
;; The chapter-22 production observability recipe is:
;;
;;   (when (and (= "production" (:env config))
;;              (not ^boolean re-frame.interop/debug-enabled?)
;;              (:api-key config))
;;     (rf/register-listener! :events
;;       :my-app/datadog
;;       (fn [record] (ship-to-datadog record))))
;;
;; This demo registers a console-logger flavour at boot — UNGATED
;; — so visitors can see the listener fire and observe the wire
;; walker's frame-driven substitution (declared-large app-db slots
;; become markers; unschema'd inline payloads ride through raw). The
;; substrate is the same one
;; Datadog / Honeycomb / Sentry attach to in production — but in
;; production you AND the listener with `goog.DEBUG=false` per the
;; recipe above so dev-laptop traffic never leaks.

(def listener-id ::elision-demo)

(defn- log-record! [record]
  ;; Format the record terse for browser-console readability. The
  ;; `:event` slot has already been passed through
  ;; `rf/elide-wire-value` with off-box defaults — declared-sensitive
  ;; paths are :rf/redacted; unschema'd / unnominated large leaves ride
  ;; through raw (Path D removed runtime size auto-elision).
  (js/console.log "[event-emit demo]" (pr-str record)))

(defn install-listener!
  "Register the demo console listener. Idempotent — re-registering
  under the same id replaces.

  EP-0025: no schema→elision population step — durable app-db
  classification rides the commit-plane `:large` effect, dispatched at boot
  (see `core/run`'s `[:counter/classify-avatar-large]` dispatch), so the
  `[:rf.runtime/elision :declarations]` registry is already live before any
  wire consumer asks for it."
  []
  (rf/register-listener! :events listener-id log-record!))

(defn uninstall-listener!
  "Drop the demo console listener. Used by tests to keep the suite
  from logging into the test runner's stdout."
  []
  (rf/unregister-listener! :events listener-id))

;; ============================================================================
;; APP-DB ELISION INSPECTOR  (the frame-driven branch surface)
;; ============================================================================
;;
;; The visitor clicks 'Walk app-db' and we run the whole live frame's
;; app-db through `rf/elide-wire-value` and `console.log` the
;; result. The `:user/avatar-pdf` slot shows up as the marker map;
;; everything else passes through. This is exactly the substitution
;; Xray applies when it renders its app-db inspector panel.

(defn- walk-app-db! []
  (let [db     (rf/app-db-value :rf/default)
        elided (rf/elide-wire-value db {:frame :rf/default})]
    (js/console.log "[app-db elision walk]" (pr-str elided))))

;; ============================================================================
;; VIEW  (the on-screen affordance)
;; ============================================================================

(reg-view elision-card []
  (let [form (r/atom {:email "demo@example.com" :password "hunter2"})]
    (fn []
      (let [last-sign-in @(subscribe [:auth/last-sign-in])
            avatar-size  @(subscribe [:user/avatar-pdf-size])
            uploads      @(subscribe [:user/uploads])]
        [:div {:style {:padding         "1em 1.5em"
                       :margin-top      "1.5em"
                       :border          "1px solid #ddd"
                       :border-radius   "6px"
                       :background      "#fafafa"
                       :font-family     "system-ui, sans-serif"
                       :max-width       "38em"}}
         [:h3 {:style {:margin "0 0 0.4em 0" :font-size "15px"}}
          "Privacy + Size elision demo"]
         [:p {:style {:font-size "12px" :color "#595959" :margin "0 0 1em 0"}}
          "Open the browser console (DevTools). The always-on event-emit "
          "listener prints every dispatched event's "
          [:em "elided"]
          " record. Each button drives a different branch of the elision "
          "contract."]

         ;; -- 1. :sensitive? handler (trace-surface scrub) ---------
         [:div {:style {:display "flex" :align-items "center" :gap "0.5em"
                        :margin-bottom "0.6em"}}
          [:button {:on-click   #(dispatch [:auth/sign-in @form])
                    :data-test  "sign-in"
                    :aria-label "Sign in (sensitive event)"}
           "Sign in (sensitive)"]
          [:span {:style {:font-size "12px" :color "#595959"}}
           (if last-sign-in
             (str "submitted for " (:email last-sign-in)
                  " — trace surface redacted, [● REDACTED N] in Xray")
             "dispatch :auth/sign-in — handler :sensitive? escape hatch")]]

         ;; -- 2. inline large payload (rides through raw — no auto-detect) ---
         [:div {:style {:display "flex" :align-items "center" :gap "0.5em"
                        :margin-bottom "0.6em"}}
          [:button {:on-click   #(dispatch
                                    [:user.avatar/upload
                                     {:blob (str/join (repeat 20000 "B"))}])
                    :data-test  "upload-inline"
                    :aria-label "Upload large avatar inline"}
           "Upload large avatar (inline)"]
          [:span {:style {:font-size "12px" :color "#595959"}}
           (if (pos? uploads)
             (str uploads " uploads — listener saw the blob raw (not elided)")
             "dispatch a 20 kB inline blob — rides through raw, no auto-detect")]]

         ;; -- 3. :large classified path (app-db walk) ------------
         [:div {:style {:display "flex" :align-items "center" :gap "0.5em"
                        :margin-bottom "0.6em"}}
          [:button {:on-click   #(dispatch [:user.avatar-pdf/set {:bytes 5000}])
                    :data-test  "set-avatar"
                    :aria-label "Set avatar PDF (large value)"}
           "Set avatar PDF (large)"]
          (when avatar-size
            [:button {:on-click   #(dispatch [:user.avatar-pdf/clear])
                      :data-test  "clear-avatar"
                      :aria-label "Clear avatar PDF"
                      :style      {:margin-left "0.4em"}}
             "Clear"])
          [:span {:style {:font-size "12px" :color "#595959"}}
           (if avatar-size
             (str avatar-size " bytes in app-db — classified :large")
             "write blob to a :large-classified app-db slot")]]

         ;; -- 4. inspect via elide-wire-value ----------------------
         [:div {:style {:display "flex" :align-items "center" :gap "0.5em"}}
          [:button {:on-click   walk-app-db!
                    :data-test  "walk-app-db"
                    :aria-label "Walk app-db through elide-wire-value"}
           "Walk app-db through elision"]
          [:span {:style {:font-size "12px" :color "#595959"}}
           "console.log app-db after rf/elide-wire-value — :user/avatar-pdf "
           "becomes the marker map carrying :reason :effect"]]]))))
