(ns re-frame.example-login-form-slice-cljs-test
  "Framework-tree regression for the login feature's FORM SLICE + password
   PRIVACY — the `:auth.login/*` events owned by examples/core/login/model.cljc,
   the substrate-free model shared across the Reagent/UIx login examples
   (rf2-ppbvav). rf2-t83ail + the password-classification fix rf2-3fc89f.33.

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the login feature's substrate-free
   model owner (`login.model`) so its events / subs / machine / schemas register
   at ns-load, then drives them directly. It runs under the consolidated
   `:node-test` CLJS build (`../examples/core` is on its source-paths) — the only
   runtime where `login.model`'s ns-load + durable handlers actually execute.
   Sibling of `re-frame.login-cljs-test` (which pins the MACHINE's
   `[:schemas :data]` boundary); this ns pins the SLICE + egress-privacy half.

   THE PASSWORD CROSSES THREE OBSERVATION BOUNDARIES, each classified by its own
   owner (docs/core/how-to/keep-secrets-out-of-traces.md). This ns is the
   regression that proves all three redact — the leak rf2-3fc89f.33 fixed was
   the password shipping RAW at each:

     1. DURABLE APP-DB. `:auth.login/initialise-form` returns a `:sensitive`
        classification effect for `[:auth :login-form :draft :password]` in the
        first durable write that creates the slice, so the draft password reads
        `:rf/redacted` at every app-db egress while the live value stays
        readable. (owner1-* tests)

     2. THE EDIT EVENT. Password keystrokes ride `:auth.login/edit-password`, a
        MAP-payload event whose registration declares `:sensitive [[:value]]`,
        so the dispatched-event trace redacts `:value`. The non-secret email
        keeps the plain positional `:auth.login/edit-field`, unredacted.
        (owner2-* test)

     3. THE MACHINE HAND-OFF + HTTP. `:auth.login/submit-form` (the classified
        owner of the draft) issues the credential-bearing `:rf.http/managed`
        request itself with `:sensitive? true`, and nudges the machine with a
        CREDENTIAL-FREE `[:auth.login/flow [:auth.login/submit]]` signal — so the
        machine never sees the password and there is no positional secret in the
        machine event. (clean-submit-* test)

   Both the machine hand-off and the HTTP request are captured via function-value
   `:fx-overrides` (spec/002 §`:fx-overrides`, rf2-nrpj1) — the overrides run in
   place of the reserved `:dispatch` / real `:rf.http/managed` bodies, so we
   observe exactly what `submit-form` emits WITHOUT the machine + managed-HTTP
   running. Egress redaction is asserted via `rf/project-egress` (app-db) and a
   trace listener (the edit event), the same surfaces a tool / shipper reads."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.privacy :as privacy]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; login.model pulls these transitively; require here so the ns is
            ;; self-sufficient (mirrors re-frame.login-cljs-test).
            [re-frame.schemas]
            [re-frame.machines]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- slice
  "The whole login-form slice at app-db [:auth :login-form] for frame `f`."
  [f]
  (get-in (rf/app-db-value f) [:auth :login-form]))

(defn- seed-draft!
  "Boot the slice (which classifies the draft-password path :sensitive) then
   type `email` / `password` into the draft via the real edit handlers — email
   through the positional `:auth.login/edit-field`, the secret through the
   map-shaped, classified `:auth.login/edit-password` (the controlled-input
   round trip)."
  [f email password]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email email] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-password {:value password}] {:frame f}))

(defn- record-traces!
  [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

(defn- submit-capturing!
  "Dispatch `:auth.login/submit-form` on frame `f`, capturing BOTH the machine
   `:dispatch` signal into `dispatched` and the `:rf.http/managed` request into
   `http`, WITHOUT running either reserved body — so we observe exactly what
   `submit-form` emits (no machine + no real HTTP)."
  [f dispatched http]
  (rf/dispatch-sync [:auth.login/submit-form]
                    {:frame        f
                     :fx-overrides {:dispatch        (fn [_ ev]  (swap! dispatched conj ev))
                                    :rf.http/managed (fn [_ req] (swap! http conj req))}}))

;; ---------------------------------------------------------------------------
;; (owner 3 + 4) clean submit — credential-free machine signal + sensitive HTTP
;; ---------------------------------------------------------------------------

(deftest clean-submit-issues-sensitive-request-and-credential-free-machine-signal
  (testing "rf2-3fc89f.33 — a clean submit issues the (sensitive) managed-HTTP
            request carrying the REAL password, nudges the machine with a
            CREDENTIAL-FREE :submit signal (no password anywhere in the machine
            event), blanks [:draft :password], clears :errors, latches
            :submit-attempted?, and leaves the email"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" "hunter2pw")
      (let [dispatched (atom [])
            http       (atom [])]
        (submit-capturing! f dispatched http)
        (let [s (slice f)]
          ;; --- slice hygiene: only the secret is blanked ---
          (is (= "" (get-in s [:draft :password]))
              "the password is blanked in the app-db slice draft after submit")
          (is (= "alice@example.com" (get-in s [:draft :email]))
              "only the SECRET is cleared — the email draft is left intact")
          ;; rf2-fai6a8 — this machine-driven variant carries NO :status /
          ;; :submitted / :submit-error mirror: the :auth.login/flow machine and
          ;; its state tags own the submit/auth lifecycle, so the slice keeps no
          ;; parallel status a view could read stale. Assert the mirror is gone
          ;; on the clean branch (it used to advance :status to :submitting here).
          (is (not (contains? s :status))
              "no :status mirror — the machine + its state tags own the lifecycle")
          (is (not (contains? s :submitted))
              "the vestigial :submitted field is gone from the slice")
          (is (not (contains? s :submit-error))
              "the vestigial :submit-error field is gone from the slice")
          (is (= {} (:errors s))
              "stale field errors were cleared on the clean branch")
          (is (true? (:submit-attempted? s))
              ":submit-attempted? latched on submit")
          ;; --- owner 3: the machine signal is CREDENTIAL-FREE ---
          (is (= 1 (count @dispatched))
              "exactly one machine :dispatch signal was emitted")
          (is (= [:auth.login/flow [:auth.login/submit]] (first @dispatched))
              "the machine :submit signal carries NO password — the machine never
               sees the credential (no positional secret in a nested event)")
          ;; --- owner 4: the HTTP request carries the REAL password + :sensitive? ---
          (is (= 1 (count @http))
              "exactly one managed-HTTP login request was issued by submit-form")
          (let [req (:request (first @http))]
            (is (= "hunter2pw" (get-in req [:body :password]))
                "the live HTTP request body carries the REAL password — the
                 submit path still receives the value it needs to authenticate")
            (is (= "alice@example.com" (get-in req [:body :email]))
                "the email rides in the request body too")
            (is (true? (:sensitive? req))
                "the request is :sensitive? true — scrubbed at the HTTP boundary")))))))

;; ---------------------------------------------------------------------------
;; (owner 1) durable app-db — the draft password is redacted at egress
;; ---------------------------------------------------------------------------

(deftest owner1-password-redacted-in-app-db-egress
  (testing "rf2-3fc89f.33 (owner 1) — the draft password, classified :sensitive
            at slice-init, is redacted in app-db egress under both a
            local-redacted and an off-box-tool profile, while the LIVE value
            stays readable and the non-secret email rides through"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" "hunter2pw")
      ;; the classification is standing in the per-frame sensitive registry
      (is (contains? (elision/sensitive-declarations)
                     [:auth :login-form :draft :password])
          "the draft-password path is in the per-frame sensitive registry")
      ;; the live app-db still holds the real value — classification is egress-only
      (is (= "hunter2pw" (get-in (rf/app-db-value f)
                                 [:auth :login-form :draft :password]))
          "app-db still holds the REAL password — classification redacts only at egress")
      ;; egress projection redacts the password under BOTH profiles a tool /
      ;; shipper would use, and leaves the email visible
      (doseq [profile [:rf.egress/local-redacted :rf.egress/off-box-tool]]
        (let [wire (rf/project-egress (rf/app-db-value f)
                                      {:frame f :rf.egress/profile profile})]
          (is (= privacy/redacted-sentinel
                 (get-in wire [:auth :login-form :draft :password]))
              (str "the password reads :rf/redacted at egress under " profile))
          (is (= "alice@example.com"
                 (get-in wire [:auth :login-form :draft :email]))
              (str "the non-secret email rides through unredacted under " profile)))))))

;; ---------------------------------------------------------------------------
;; (owner 2) the edit event — :value redacted in the dispatched-event trace
;; ---------------------------------------------------------------------------

(deftest owner2-edit-password-event-redacted-in-trace
  (testing "rf2-3fc89f.33 (owner 2) — :auth.login/edit-password carries the
            keystroke in a :sensitive map payload, so its dispatched-event trace
            redacts :value to :rf/redacted, while the handler still writes the
            REAL value to the draft; the non-secret email edit stays visible"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
      (let [traces (record-traces! ::edit-probe)]
        (rf/dispatch-sync [:auth.login/edit-field :email "alice@example.com"] {:frame f})
        (rf/dispatch-sync [:auth.login/edit-password {:value "hunter2pw"}] {:frame f})
        (rf/unregister-listener! :trace ::edit-probe)
        ;; the handler received + wrote the REAL value (live, unredacted)
        (is (= "hunter2pw" (get-in (slice f) [:draft :password]))
            "the edit-password handler received the REAL keystroke and wrote it")
        ;; every trace event carrying the edit-password payload redacts :value
        (let [pw-payloads (->> @traces
                               (keep (fn [ev]
                                       (when (= :auth.login/edit-password
                                                (first (get-in ev [:tags :rf.event/v])))
                                         (get-in ev [:tags :rf.event/v 1]))))
                               (filter map?))]
          (is (seq pw-payloads)
              "the edit-password event surfaced on the trace with its payload map")
          (doseq [p pw-payloads]
            (is (= privacy/redacted-sentinel (:value p))
                "the edit-password :value is :rf/redacted in the event trace")))
        ;; the non-secret email edit is NOT redacted (positional, visible)
        (let [email-vals (->> @traces
                              (keep (fn [ev]
                                      (when (= :auth.login/edit-field
                                               (first (get-in ev [:tags :rf.event/v])))
                                        (get-in ev [:tags :rf.event/v 2])))))]
          (is (some #{"alice@example.com"} email-vals)
              "the non-secret email rides VISIBLY in the edit-field trace — only
               the password is redacted, proving selective classification"))))))

;; ---------------------------------------------------------------------------
;; (owners 3 + 4, trace side) the submit TRACE STREAM never leaks the password
;; ---------------------------------------------------------------------------
;;
;; The capture-based clean-submit test above observes what `submit-form` EMITS
;; via function-value `:fx-overrides` — which bypasses the trace/classification
;; pipeline for those assertions. This sibling drives the SAME submit with the
;; trace pipeline live (only the real transport is neutralised) and sweeps the
;; emitted stream: rf2-32ffq1 closed the two slots that used to carry the raw
;; password here — the `:rf.event/fx` aggregate on `:rf.fx/do-fx` and the
;; managed fx's `[:rf.fx/id :rf.fx/args]` slot — by honouring
;; `:rf.http/managed`'s per-call `:sensitive?` flag at the generic fx-arg
;; walk (the `:http/project-managed-fx-args` hook).

(def ^:private submit-pw-sentinel "PW-LOGIN-SENTINEL-32ffq1-c4d9")

(defn- leaks-submit-pw? [x] (str/includes? (pr-str x) submit-pw-sentinel))

(deftest clean-submit-trace-stream-never-leaks-password
  (testing "rf2-32ffq1 — a clean submit's WHOLE emitted trace stream ships no
            raw password: the :rf.event/fx aggregate and the :rf.http/managed
            :rf.fx/handled slot redact the request body (per-call :sensitive?),
            while the fx itself still receives the REAL credential"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" submit-pw-sentinel)
      (let [http   (atom [])
            traces (record-traces! ::submit-sweep)]
        ;; Neutralise ONLY the transport (fn-value override keeps the original
        ;; :rf.http/managed id on the aggregate + handled slots, so the trace
        ;; pipeline under test runs unchanged); the machine :dispatch runs.
        (rf/dispatch-sync [:auth.login/submit-form]
                          {:frame        f
                           :fx-overrides {:rf.http/managed
                                          (fn [_ req] (swap! http conj req))}})
        (rf/unregister-listener! :trace ::submit-sweep)

        ;; positive control — the fx received the REAL password for the wire.
        (is (= submit-pw-sentinel
               (get-in (first @http) [:request :body :password]))
            "the managed fx received the REAL password (egress-only redaction)")

        ;; the :rf.event/fx aggregate redacts the sensitive request body.
        (let [entries (for [ev    @traces
                            :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                            :when (vector? fx-vec)
                            [id args] fx-vec
                            :when (= :rf.http/managed id)]
                        args)]
          (is (seq entries) "the do-fx aggregate carried the managed entry")
          (doseq [args entries]
            (is (= privacy/redacted-sentinel (get-in args [:request :body]))
                "the sensitive request's whole :body reads :rf/redacted in
                 :rf.event/fx")))

        ;; the managed [:rf.fx/id :rf.fx/args] slot redacts too.
        (let [handled (->> @traces
                           (filter #(= :rf.http/managed
                                       (get-in % [:tags :rf.fx/id]))))]
          (is (seq handled) "the managed fx emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args :request :body]))
                "the managed :rf.fx/args request body reads :rf/redacted")))

        ;; the whole-stream sweep — the password appears in NO trace tag.
        (let [checked (atom 0)]
          (doseq [ev @traces
                  [k v] (:tags ev)]
            (swap! checked inc)
            (is (not (leaks-submit-pw? v))
                (str "the password must not appear raw in " (:operation ev)
                     " / " k)))
          (is (pos? @checked)
              "the sweep actually inspected trace tags (guard against a no-op)"))))))

;; ---------------------------------------------------------------------------
;; invalid submit — errors surface, NOTHING is issued, the password is retained
;; ---------------------------------------------------------------------------

(deftest invalid-submit-issues-nothing-and-retains-password
  (testing "rf2-t83ail — an invalid draft (bad email + short password) fails the
            pre-submit Credentials validation: :errors is populated per field,
            :submit-attempted? latches, and NOTHING is issued (no machine signal,
            no HTTP). The password is RETAINED for the fix-up — nothing left the
            box, so this is not the leak the fix guards."
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "not-an-email" "short")
      (let [dispatched (atom [])
            http       (atom [])]
        (submit-capturing! f dispatched http)
        (let [s (slice f)]
          (is (true? (:submit-attempted? s))
              ":submit-attempted? latched even on the invalid branch")
          (is (contains? (:errors s) :email)
              "the bad email produced a field error")
          (is (contains? (:errors s) :password)
              "the short password produced a field error")
          (is (not (contains? s :status))
              "rf2-fai6a8 — the slice carries no :status mirror on any branch;
               the machine owns the lifecycle, not a parallel slice field")
          (is (zero? (count @dispatched))
              "no machine signal — the invalid draft was not handed off")
          (is (zero? (count @http))
              "no HTTP request — nothing crossed the wire on the invalid branch")
          (is (= "short" (get-in s [:draft :password]))
              "the password is RETAINED on the invalid branch (kept for the
               fix-up; the clear is coupled to a clean hand-off, not the click)"))))))

(deftest partially-valid-submit-still-blocks-and-keeps-secret
  (testing "rf2-t83ail — a valid email but a too-short password still fails
            validation: only the :password error surfaces, nothing is issued,
            and the (short) password is retained"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" "tiny")
      (let [dispatched (atom [])
            http       (atom [])]
        (submit-capturing! f dispatched http)
        (let [s (slice f)]
          (is (not (contains? (:errors s) :email))
              "the valid email produced no error")
          (is (contains? (:errors s) :password)
              "the short password produced a field error")
          (is (zero? (count @dispatched))
              "a single invalid field is enough to block the machine hand-off")
          (is (zero? (count @http))
              "and to block the HTTP request")
          (is (= "tiny" (get-in s [:draft :password]))
              "the password is retained (never handed off, so never cleared)"))))))
