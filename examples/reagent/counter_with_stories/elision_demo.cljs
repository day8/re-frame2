(ns counter-with-stories.elision-demo
  "Privacy + Size elision demo (rf2-vw0to).

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
     inside the handler's scope (per Spec 009 §Privacy). Causa's
     trace collector reads that top-level stamp and drops the event
     from the buffer (default posture); the bottom rail surfaces a
     `[● REDACTED N]` hint.

  2. **`with-redacted` interceptor** — paired with `:sensitive?` so
     the password is scrubbed to `:rf/redacted` in-place before any
     in-chain trace event copies the event vector. The handler body
     still sees the unredacted payload via the `:event` cofx slot
     (it needs the real value to do the work). Spec 009 §Composition
     calls this the conservative-recommended companion to
     `:sensitive?`; without it the registrar emits
     `:rf.warning/sensitive-without-redaction`.

  3. **`:large?` schema-meta slot** — the `:user/avatar-pdf` schema
     entry carries `{:large? true :hint \"Avatar PDF blob\"}`. When
     `rf/elide-wire-value` walks an app-db payload that includes
     this slot at its declared path, the value is replaced with a
     `{:rf.size/large-elided {:bytes … :path … :hint …}}` marker.
     The `:hint` propagates verbatim — AI consumers (pair2-mcp,
     Causa) see the orienting string without drilling into the
     blob.

  4. **Always-on `event-emit` listener** — the demo registers a
     console-logger via `register-event-emit-listener!`. The
     listener receives one record per processed event and
     demonstrates a different elision surface: the **runtime
     auto-detect** branch of the wire walker. When the
     `:user.avatar/upload` event vector carries an inline large
     string (≥ 16 kB, Spec 009 §Auto-detect threshold), the walker
     substitutes the string with `:rf.size/large-elided` BEFORE the
     listener sees the record — even though no schema slot declared
     the path. This is the production-survivable substrate the
     chapter-22 Datadog recipe pivots around: the listener fires
     under `:advanced` + `goog.DEBUG=false` where the trace surface
     is DCE'd.

  ## What to look for in the running app

  Open the browser console + (optionally) the Causa panel.

  - **Click 'Sign in (sensitive)'** — dispatches `:auth/sign-in`.
    In Causa the event is absent from the trace panel (filtered out
    by the `:sensitive?` top-level stamp); the bottom rail shows
    `[● REDACTED N]`. The console line from the always-on listener
    is honest about its scope: it shows the original event vector
    because the event-emit substrate does NOT consult handler-meta
    for `:sensitive?` — the listener sees what the walker would
    write to the wire, and the walker's sensitivity check consults
    the `:rf/elision` registry of app-db PATHS, not handler
    metadata. (Production deployments add a registration-time
    `goog.DEBUG=false` gate on the listener so dev-only PII never
    reaches the wire.)

  - **Click 'Upload large avatar (inline)'** — dispatches
    `:user.avatar/upload` with a 20 kB string in the event payload.
    The console line from the listener shows the inline blob
    REPLACED with the `:rf.size/large-elided` marker. The walker's
    runtime auto-detect fired at the wire boundary — the listener
    never saw the bytes.

  - **Click 'Walk app-db through elision'** — runs
    `rf/elide-wire-value` over a snapshot of the live frame's
    app-db. The console shows the `:user/avatar-pdf` slot replaced
    by the `:rf.size/large-elided` marker, with `:hint \"Avatar PDF
    blob\"` propagated verbatim from the schema declaration. This
    is the SCHEMA-driven branch — the same substitution Causa
    applies when it renders `app-db` in its inspector panel.

  Per rf2-vw0to. Pre-alpha."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.schemas.malli])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SCHEMAS  (Spec 010 §`:large?` per-slot meta)
;; ============================================================================
;;
;; The `:user/avatar-pdf` slot is declared `:large?` so the
;; wire-walker substitutes its value with the `:rf.size/large-elided`
;; marker (carrying byte-count + path + hint) whenever any wire
;; consumer walks app-db through `rf/elide-wire-value`. The `:hint`
;; propagates verbatim into the marker — Spec 010 §`:large?`
;; schema-driven size-elision nomination calls this the AI-consumer
;; orientation hook.

;; Container-level props form, per Spec 010 §`:large?` schema-driven
;; size-elision nomination: when the schema is registered at a path
;; directly (rather than as a nested slot inside a `:map`), the
;; `:large?` props live on the schema's own property map. The
;; framework's `populate-elision-from-schemas!` walker reads those
;; top-level props and writes a `{:large? true :source :schema}`
;; entry into `[:rf/elision :declarations]`.
;;
;; We deliberately do NOT wrap the schema in `:maybe` — a `:maybe`
;; wrapper would push `:large?` past the walker's top-level-props
;; check. The slot is allowed to be `nil` at runtime because the
;; default schema validator soft-passes (Spec 010 §Recommended
;; soft-pass); the optional-value semantic lives in the schema's
;; ABSENCE from app-db before the user clicks the upload button,
;; not in a `:maybe` wrapper.
(rf/reg-app-schema [:user/avatar-pdf]
                   [:string {:large? true
                             :hint   "Avatar PDF blob"}])

;; ============================================================================
;; EVENTS  (Spec 009 §`:sensitive?` + §`with-redacted`)
;; ============================================================================
;;
;; The conventional event-vector shape per Conventions §Unwrap
;; interceptor is `[event-id payload-map]`; `with-redacted`'s path
;; vector addresses keys inside that payload map. Declaring BOTH
;; `:sensitive? true` AND `with-redacted [[:password]]` is the
;; conservative-recommended pattern per Spec 009 §Composition —
;; the metadata stamp is the trace-surface filter signal; the
;; interceptor is the in-handler scrub.

(rf/reg-event-fx :auth/sign-in
  ;; Registration metadata — the registrar copies `:sensitive? true`
  ;; into the registry slot's meta; the runtime hoists it to the
  ;; TOP level of every trace event emitted within this handler's
  ;; scope. Causa filters on the top-level stamp.
  {:doc        "Demo sign-in handler — the password rides the event
                vector. `:sensitive? true` tells trace consumers to
                drop these events; `with-redacted [[:password]]`
                scrubs the field in-place before any downstream emit
                copies the event vector."
   :sensitive? true}

  ;; Positional interceptor chain — `with-redacted` runs in `:before`
  ;; so every in-chain emit that copies the event vector sees the
  ;; redacted form. The handler body itself sees the unredacted
  ;; payload via the `:event` cofx slot.
  [(rf/with-redacted [[:password]])]

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

(rf/reg-event-db :user.avatar-pdf/set
  {:doc "Demo: write a synthetic large blob into the `:large?`-flagged
         schema slot. Walking app-db through `rf/elide-wire-value`
         substitutes the slot value with a `:rf.size/large-elided`
         marker, with the schema's `:hint` propagated verbatim."}
  (fn [db [_ {:keys [bytes]}]]
    (assoc db :user/avatar-pdf (str/join (repeat (or bytes 5000) "A")))))

(rf/reg-event-db :user.avatar-pdf/clear
  {:doc "Drop the avatar slot. Uses `dissoc` rather than `(assoc ...
         nil)` so the slot is absent from app-db rather than carrying
         a typed `nil`, matching the schema's `:string` declaration
         (Spec 010 §Optional slots: absence is the soft-pass-friendly
         shape for an optional slot)."}
  (fn [db _] (dissoc db :user/avatar-pdf)))

(rf/reg-event-db :user.avatar/upload
  {:doc "Demo: dispatch a 20 kB blob INLINE inside the event vector.
         The event-emit listener's `elide-wire-value` pass auto-
         detects the leaf string (over the default 16 kB threshold)
         and substitutes it with `:rf.size/large-elided` BEFORE the
         listener receives the record. Demonstrates the runtime
         auto-detect branch of the wire walker — orthogonal to the
         schema-driven branch above."}
  (fn [db [_ {:keys [_blob]}]]
    ;; We don't keep the blob; the point is the elision at the wire
    ;; boundary. Bump a counter so the UI has something to render.
    (update db :user/uploads (fnil inc 0))))

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
;;     (rf/register-event-emit-listener!
;;       :my-app/datadog
;;       (fn [record] (ship-to-datadog record))))
;;
;; This demo registers a console-logger flavour at boot — UNGATED
;; — so visitors can see the listener fire and observe the wire
;; walker's auto-detect substitution. The substrate is the same one
;; Datadog / Honeycomb / Sentry attach to in production — but in
;; production you AND the listener with `goog.DEBUG=false` per the
;; recipe above so dev-laptop traffic never leaks.

(def listener-id ::elision-demo)

(defn- log-record! [record]
  ;; Format the record terse for browser-console readability. The
  ;; `:event` slot has already been passed through
  ;; `rf/elide-wire-value` with off-box defaults — large leaves are
  ;; markers; declared-sensitive paths are :rf/redacted.
  (js/console.log "[event-emit demo]" (pr-str record)))

(defn install-listener!
  "Register the demo console listener. Idempotent — re-registering
  under the same id replaces. Also calls
  `rf/populate-elision-from-schemas!` so the schema-driven `:large?`
  declarations land in the active frame's `[:rf/elision
  :declarations]` registry before any wire consumer asks for them.
  The populate step is normally run once at boot per Spec 009
  §Schema-driven boot population."
  []
  (rf/populate-elision-from-schemas!)
  (rf/register-event-emit-listener! listener-id log-record!))

(defn uninstall-listener!
  "Drop the demo console listener. Used by tests to keep the suite
  from logging into the test runner's stdout."
  []
  (rf/unregister-event-emit-listener! listener-id))

;; ============================================================================
;; APP-DB ELISION INSPECTOR  (the schema-driven branch surface)
;; ============================================================================
;;
;; The visitor clicks 'Walk app-db' and we run the whole live frame's
;; app-db through `rf/elide-wire-value` and `console.log` the
;; result. The `:user/avatar-pdf` slot shows up as the marker map;
;; everything else passes through. This is exactly the substitution
;; Causa applies when it renders its app-db inspector panel.

(defn- walk-app-db! []
  (let [db     (rf/get-frame-db :rf/default)
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
                  " — trace surface redacted, [● REDACTED N] in Causa")
             "dispatch :auth/sign-in — :sensitive? + with-redacted")]]

         ;; -- 2. inline large payload (wire-walker auto-detect) ---
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
             (str uploads " uploads — listener saw the blob as a marker")
             "dispatch a 20 kB inline blob — auto-detect fires")]]

         ;; -- 3. :large? schema-driven path (app-db walk) ---------
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
             (str avatar-size " bytes in app-db — schema-declared :large?")
             "write blob to :large? schema-flagged app-db slot")]]

         ;; -- 4. inspect via elide-wire-value ----------------------
         [:div {:style {:display "flex" :align-items "center" :gap "0.5em"}}
          [:button {:on-click   walk-app-db!
                    :data-test  "walk-app-db"
                    :aria-label "Walk app-db through elide-wire-value"}
           "Walk app-db through elision"]
          [:span {:style {:font-size "12px" :color "#595959"}}
           "console.log app-db after rf/elide-wire-value — :user/avatar-pdf "
           "becomes the marker map with the schema's :hint propagated"]]]))))
