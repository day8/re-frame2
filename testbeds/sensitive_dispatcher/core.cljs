(ns sensitive-dispatcher.core
  "Shared framework-behavior testbed — handlers carrying `:sensitive? true`
  in their registration metadata. A consumer (Causa, Story, pair2-mcp)
  verifies that the always-on event-emit substrate, the dev-time trace
  surface, the recorder, and the MCP wire each honour the privacy
  contract (per [spec/009 §Privacy / sensitive data in traces] and
  [spec/Security.md §Privacy / secret handling]).

  Three buttons drive three distinct privacy shapes:

    Button A · Sign-in (`:sensitive? true` on registration)
      — handler declares `:sensitive? true` on registration meta. The
        event-emit listener substrate DROPS the record entirely (per
        rf2-6hklf); the dev trace surface stamps `:sensitive? true` on
        the trace event but rides the payload through (the dev surface
        treats `:sensitive?` as declarative — listeners filter).

    Button B · Sign-in with redaction (`:sensitive?` + `with-redacted`)
      — handler declares both flags. The recorder + the MCP wire +
        the trace surface all see redacted payload (the `:password`
        slot in the event vector becomes `:rf/redacted`); the
        event-emit substrate still drops the record entirely.

    Button C · Sign-in that throws
      — handler declares `:sensitive? true` and deliberately throws.
        The always-on error-emit substrate REDACTS the event payload
        to `:rf/redacted` (per rf2-vnjfg) so error monitors see the
        exception class + frame + elapsed but NOT the secret payload.

  This is NOT a tutorial. The bodies are minimal. The point is to
  produce three deterministic privacy outcomes a consumer can assert
  against — payload dropped vs payload redacted vs error-payload
  redacted — all from one surface."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; App-db
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::initialise
  (fn [_db _ev]
    {;; Counters per button — flipped when the handler runs to
     ;; completion. A spec asserts that the counters DO advance
     ;; (the handlers themselves see the unredacted payload via the
     ;; cofx slot per Spec 009 §with-redacted) even when the trace
     ;; / wire surfaces see redaction.
     :click-count {:plain 0 :redacted 0 :throw 0}
     ;; A consumer that asks "did the handler's :sensitive? meta
     ;; flow into the registration?" reads the registrar via
     ;; (rf/handler-meta :event ::sign-in) and sees :sensitive? true.
     ;; The mirror below echoes that into the DOM so a Playwright
     ;; spec can read it directly.
     :handler-meta-mirror
     {::sign-in-plain    nil
      ::sign-in-redacted nil
      ::sign-in-throw    nil}}))

;; ----------------------------------------------------------------------------
;; Button A — :sensitive? true on registration, no with-redacted
;; ----------------------------------------------------------------------------
;;
;; The registration-meta map carries `:sensitive? true`. Per [spec/009
;; §Substrate-level enforcement on the always-on surfaces]:
;;
;;   - The event-emit substrate (per `register-event-emit-listener!`)
;;     drops the record for this dispatch entirely. Listeners are
;;     NOT invoked. Sensitive cascades produce no per-event
;;     observability record at all (rf2-6hklf).
;;
;;   - The dev-time trace surface stamps `:sensitive? true` at the
;;     top level of every trace event emitted within this handler's
;;     scope; the payload itself rides through unchanged. Tools
;;     downstream of the trace surface (recorders, off-box pair2
;;     server, Causa-MCP) filter / redact per their own policy.

(rf/reg-event-db ::sign-in-plain
  {:doc        "Verify credentials (plain :sensitive? — no redaction)."
   :sensitive? true}
  (fn [db [_ _credentials]]
    ;; HOT PATH — handler sees unredacted credentials map via the
    ;; regular :event cofx slot. The :sensitive? declarative axis
    ;; is independent of what the handler body sees; only the trace
    ;; and the always-on substrates' wire shapes change.
    (update-in db [:click-count :plain] inc)))

;; ----------------------------------------------------------------------------
;; Button B — :sensitive? true + with-redacted interceptor
;; ----------------------------------------------------------------------------
;;
;; This is the conservative recommended pattern for sensitive
;; handlers (per [spec/009 §The `with-redacted` interceptor] and
;; [spec/Security.md §Privacy / secret handling]). The interceptor's
;; :before stage redacts the named paths in the event vector, in the
;; downstream :event cofx slot, and in the :event/dispatched +
;; :event/db-changed trace events.

(rf/reg-event-db ::sign-in-redacted
  {:doc        "Verify credentials (redacted via with-redacted)."
   :sensitive? true}
  [(rf/with-redacted [[:password] [:totp-code]])]
  (fn [db [_ _credentials]]
    (update-in db [:click-count :redacted] inc)))

;; ----------------------------------------------------------------------------
;; Button C — :sensitive? true + throws (exercise the error-emit substrate)
;; ----------------------------------------------------------------------------
;;
;; The handler's throw drives the always-on error-emit substrate
;; (per [spec/009 §Substrate-level enforcement] §error-emit). When
;; the failing handler's meta carries `:sensitive? true` the
;; substrate REDACTS the :event slot of the error-record to
;; `:rf/redacted` before fan-out. Operators see the exception class,
;; the frame id, the failing event-id, and `:elapsed-ms` but NOT
;; the secret payload (per rf2-vnjfg).

(rf/reg-event-db ::sign-in-throw
  {:doc        "Sign-in that throws — exercises the error-emit redaction."
   :sensitive? true}
  (fn [_db [_ _credentials]]
    ;; HOT PATH — the throw site. The runtime's outer catch fires
    ;; the always-on error-emit listener with :event redacted to
    ;; :rf/redacted because of the :sensitive? meta.
    (throw (ex-info "sensitive-dispatcher / sign-in-throw"
                    {:where :handler}))))

;; ----------------------------------------------------------------------------
;; Handler-meta mirror — populate at mount so the DOM can show the
;; registrar's view of the `:sensitive?` flag
;; ----------------------------------------------------------------------------
;;
;; The registrar copies `:sensitive?` from registration metadata into
;; the registry slot's stored meta. `(rf/handler-meta :event id)`
;; reads it back. The mirror below puts the value into app-db so
;; a Playwright spec can assert on the registrar's view directly.

(rf/reg-event-db ::populate-meta-mirror
  (fn [db _ev]
    (assoc db :handler-meta-mirror
              {::sign-in-plain    (boolean (:sensitive? (rf/handler-meta :event ::sign-in-plain)))
               ::sign-in-redacted (boolean (:sensitive? (rf/handler-meta :event ::sign-in-redacted)))
               ::sign-in-throw    (boolean (:sensitive? (rf/handler-meta :event ::sign-in-throw)))})))

;; ----------------------------------------------------------------------------
;; Reset
;; ----------------------------------------------------------------------------

(rf/reg-event-db ::reset
  (fn [_db _ev]
    {:click-count {:plain 0 :redacted 0 :throw 0}
     :handler-meta-mirror
     {::sign-in-plain    (boolean (:sensitive? (rf/handler-meta :event ::sign-in-plain)))
      ::sign-in-redacted (boolean (:sensitive? (rf/handler-meta :event ::sign-in-redacted)))
      ::sign-in-throw    (boolean (:sensitive? (rf/handler-meta :event ::sign-in-throw)))}}))

;; ----------------------------------------------------------------------------
;; Subs + view
;; ----------------------------------------------------------------------------

(rf/reg-sub :plain-count
  (fn [db _] (get-in db [:click-count :plain])))

(rf/reg-sub :redacted-count
  (fn [db _] (get-in db [:click-count :redacted])))

(rf/reg-sub :throw-count
  (fn [db _] (get-in db [:click-count :throw])))

(rf/reg-sub :meta-plain
  (fn [db _] (get-in db [:handler-meta-mirror ::sign-in-plain])))

(rf/reg-sub :meta-redacted
  (fn [db _] (get-in db [:handler-meta-mirror ::sign-in-redacted])))

(rf/reg-sub :meta-throw
  (fn [db _] (get-in db [:handler-meta-mirror ::sign-in-throw])))

;; A canonical secret payload reused by every button. The literal
;; secret strings ride the event-vector emit; consumers verify the
;; on-wire form replaces these with `:rf/redacted` according to
;; their policy.
(def example-credentials
  {:username  "ada"
   :password  "shhh-this-is-secret"
   :totp-code "123456"})

(reg-view buttons []
  (let [plain-count    @(subscribe [:plain-count])
        redacted-count @(subscribe [:redacted-count])
        throw-count    @(subscribe [:throw-count])
        meta-plain     @(subscribe [:meta-plain])
        meta-redacted  @(subscribe [:meta-redacted])
        meta-throw     @(subscribe [:meta-throw])]
    [:div {:data-testid "sensitive-dispatcher"
           :style       {:font-family "sans-serif" :padding "1em"}}
     [:h1 "sensitive-dispatcher testbed"]
     [:p "Three handlers all carrying " [:code ":sensitive? true"]
         ". Each click produces a distinct redaction shape on the
          trace stream, the event-emit substrate, the error-emit
          substrate, and the MCP wire."]

     [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap}}
      [:button {:data-testid "sign-in-plain"
                :on-click    #(dispatch [::sign-in-plain example-credentials])}
       "A · :sensitive? plain (event-emit drops record)"]
      [:button {:data-testid "sign-in-redacted"
                :on-click    #(dispatch [::sign-in-redacted example-credentials])}
       "B · :sensitive? + with-redacted (payload scrubbed)"]
      [:button {:data-testid "sign-in-throw"
                :on-click    #(dispatch [::sign-in-throw example-credentials])}
       "C · :sensitive? + throw (error-emit redacts :event)"]
      [:button {:data-testid "reset"
                :on-click    #(dispatch [::reset])}
       "Reset"]]

     [:p {:style {:margin-top "1em" :color "#666" :white-space :pre-wrap}}
      "plain-count="    [:span {:data-testid "plain-count"}    plain-count]
      "  (advances iff handler ran — proves substrate-drop ≠ skip-handler)"  "\n"
      "redacted-count=" [:span {:data-testid "redacted-count"} redacted-count]   "\n"
      "throw-count="    [:span {:data-testid "throw-count"}    throw-count]
      "  (always 0 — handler throws before commit)"                          "\n\n"
      "(handler-meta :event ::sign-in-plain    :sensitive?)="
      [:span {:data-testid "meta-plain"}    (str meta-plain)]    "\n"
      "(handler-meta :event ::sign-in-redacted :sensitive?)="
      [:span {:data-testid "meta-redacted"} (str meta-redacted)] "\n"
      "(handler-meta :event ::sign-in-throw    :sensitive?)="
      [:span {:data-testid "meta-throw"}    (str meta-throw)]]]))

(reg-view root []
  [buttons])

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [::initialise])
  ;; Populate the registrar-meta mirror once on boot; the mirror is
  ;; static — the registrations don't change at runtime.
  (rf/dispatch-sync [::populate-meta-mirror])
  (rdc/render react-root [root]))
