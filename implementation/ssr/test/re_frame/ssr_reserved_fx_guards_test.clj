(ns re-frame.ssr-reserved-fx-guards-test
  "rf2-dtpfv ACCEPTANCE — the reserved `:rf.server/*` fx guard their own args
  in EVERY build, so a malformed framework effect never reaches the response
  accumulator that `ssr/get-response` publishes.

  THE DEFECT. Spec 010 §Validation order step 5 validates an fx's args
  against the `:schema` on its registration meta, and §Per-step recovery row
  5 says the offending fx is `:skipped`. That gate is
  `re-frame.schemas.validate/validate-fx!`, whose body is
  `(if interop/debug-enabled? (run-validation …) true)` — and
  `interop/debug-enabled?` is read ONCE at namespace-load time. Under
  `-Dre-frame.debug=false` the boundary therefore does not run AT ALL and
  nothing is skipped. Measured at `ssr/get-response` before the fix:

    [:rf.server/set-status \"not-an-int\"]            → :status \"not-an-int\"
    [:rf.server/set-header {:name \"X-Foo\"}]         → [\"X-Foo\" nil]
    [:rf.server/set-cookie {:name \"session\"}]       → {:name \"session\"}
    [:rf.server/safe-redirect {:location \"/ok\"
                               :status \"not-int\"}]  → the malformed redirect

  A Ring host survived that because `ssr-ring`'s `fail-closed-status`
  coerces — host-specific belt-and-braces whose only signal is a dev-bus
  warning. `get-response` is a PUBLIC host-adapter surface; its shape must
  not depend on which build you are running.

  THE RULING (option (b)). The general step-5 gate for USER fx stays
  dev-only — trust-the-programmer covers user declarations, and validating
  every fx in every app to protect seven closed framework effects is
  posture-hostile. The reserved family guards its OWN args unconditionally,
  which is the ownership pattern already in `re-frame.ssr.response` for the
  wire-grammar surface (`validate-header-name!` / `-value!`,
  `validate-cookie-name!` / `-attr!`, `validate-redirect-location!` all
  `throw-error!` unconditionally before `swap-response!`).

  ## Why this namespace is POSTURE-INDEPENDENT, and why that matters

  Every assertion in §1–§3 below is true under BOTH postures and mentions
  the dev trace bus NOWHERE, so this namespace joins
  `scripts/test-ssr-prod-gate.sh` by default (that roster is an EXCLUSION
  list) and executes under `-Dre-frame.debug=false` for real. §4 is the ONE
  posture-split section, and it asserts only the DIAGNOSTIC — which differs
  by design and is documented rather than hidden:

    dev + schemas : Malli step-5 rejects, the fx is `:skipped`, the
                    programmer gets `:rf.error/schema-validation-failure`
                    `:where :fx-args`, and the page still renders.
    prod (or a schemas-less dev build)
                  : the guard throws → registered-fx containment → an
                    always-on `:rf.error/fx-handler-exception` record →
                    SSR's error projection serves a sanitised 500.

  A `with-redefs [interop/debug-enabled? false]` rebind CANNOT reach a
  load-time gate and is not evidence for this bead — which is exactly how
  the dev-posture cluster in `ssr-end-to-end-test` stayed green while a
  release build put a string status on the accumulator.

  ## The acceptance corpus

  `acceptance-corpus` below is ONE literal driven twice: through
  `malli.core/validate` against the registered `:rf.fx.server/*-args`
  schemas (the dev boundary), and through the live fx (the always-on
  guards). A schema widened on one side only fails §3. `m/validate` is
  called DIRECTLY rather than through the late-bind validator, so the
  schema half adjudicates under the production gate too."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.response :as response]
            [re-frame.ssr.server-fx-schemas :as fx-schemas]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- server-frame []
  (frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}}))

;; The well-formed fx queued AFTER every malformed one. Its presence on the
;; accumulator is the containment proof: whichever posture rejected the bad
;; fx, it skipped only that fx and let the cascade continue.
(def ^:private sibling-fx
  [:rf.server/append-header {:name "X-Sibling" :value "ran"}])

(defn- sibling-ran? [raw]
  (boolean (some (fn [[k v]] (and (= "X-Sibling" k) (= "ran" v)))
                 (:headers raw))))

(defn- drive!
  "Dispatch `fx-vec` followed by `sibling-fx` on a fresh server frame.
  Returns `{:frame :raw :bookkept :response :records}` — the PURE accumulator
  read (`ssr/peek-response`, no projector drain), the same read WITHOUT the
  internal `_status-writes` / `_redirect-writes` strip (`response/response-of`
  — the only way to see that a `:rf.server/set-status 200` really ran rather
  than the default holding), the PUBLIC host-adapter read
  (`ssr/get-response`, which drains), and every record the always-on
  `:errors` axis saw. The pure reads are taken BEFORE `get-response`: the
  drain is a side effect, so the untouched-accumulator claim must come first."
  [fx-vec]
  (let [f    (server-frame)
        id   (keyword "rf2-dtpfv" (str "cap-" (name (gensym "g"))))
        seen (atom [])]
    (rf/register-listener! :errors id (fn [r] (swap! seen conj r)))
    (rf/reg-event ::attempt (fn [_ [_ fx]] {:fx [fx sibling-fx]}))
    (try
      (rf/dispatch-sync [::attempt fx-vec] {:frame f})
      (let [raw      (ssr/peek-response f)
            bookkept (response/response-of f)]
        {:frame    f
         :raw      raw
         :bookkept bookkept
         :response (ssr/get-response f)
         :records  @seen})
      (finally
        (rf/unregister-listener! :errors id)))))

;; ---------------------------------------------------------------------------
;; The shared acceptance corpus — ONE literal, driven through BOTH halves
;;
;; Each row: [label fx-id schema args accept?]
;;
;; `schema` is the registered `:rf.fx.server/*-args` EDN; `args` is the fx's
;; args value; `accept?` is the single answer both halves must give. Rows are
;; the CANONICAL cases — the shapes Spec 011 §Standard fx publishes and the
;; four malformations rf2-dtpfv measured on the wire.
;;
;; The AUDIT rows at the end are the two disagreements the audit of the first
;; fix found — where "both halves must give one answer" was the assertion and
;; the corpus was the thing not asking. Neither was reachable through a
;; `:value`-shaped malformation, so §1's witnesses stayed green while the
;; accumulator stayed posture-dependent one key deeper:
;;
;;   - EVERY OPTIONAL KEY, EXPLICITLY nil. The guards read `{:path nil}` as
;;     absent; a bare `[:path {:optional true} :string]` read it as a
;;     violation. Dev skipped the cookie, production persisted it. Settled as
;;     absent on both sides (`[:maybe …]`), so every one of these rows is an
;;     ACCEPT — including the combined rows where they are all nil at once.
;;   - A KEYWORD / SYMBOL COOKIE `:name`. The fx boundary admitted it, mirroring
;;     the Ring materialiser's `clojure.lang.Named` check, while the schemas,
;;     Spec 011 §Cookie shape and Spec-Schemas all published `:string` — so it
;;     was skipped in dev, landed in production, and reached host adapters as a
;;     keyword. Settled as the published `:string`, so these rows are REJECTS.
;; ---------------------------------------------------------------------------

(def ^:private acceptance-corpus
  [;; ---- :rf.server/set-status ------------------------------------------
   ["set-status 200"           :rf.server/set-status
    fx-schemas/set-status-args 200                                  true]
   ["set-status 404"           :rf.server/set-status
    fx-schemas/set-status-args 404                                  true]
   ["set-status 599 (top)"     :rf.server/set-status
    fx-schemas/set-status-args 599                                  true]
   ["set-status 100 (bottom)"  :rf.server/set-status
    fx-schemas/set-status-args 100                                  true]
   ["set-status string"        :rf.server/set-status
    fx-schemas/set-status-args "not-an-int"                         false]
   ["set-status below range"   :rf.server/set-status
    fx-schemas/set-status-args 99                                   false]
   ["set-status above range"   :rf.server/set-status
    fx-schemas/set-status-args 600                                  false]
   ["set-status nil"           :rf.server/set-status
    fx-schemas/set-status-args nil                                  false]

   ;; ---- :rf.server/set-header -------------------------------------------
   ["set-header well-formed"   :rf.server/set-header
    fx-schemas/set-header-args {:name "X-Foo" :value "bar"}          true]
   ["set-header no :value"     :rf.server/set-header
    fx-schemas/set-header-args {:name "X-Foo"}                       false]
   ["set-header int :value"    :rf.server/set-header
    fx-schemas/set-header-args {:name "X-Foo" :value 42}             false]
   ["set-header symbol :name"  :rf.server/set-header
    fx-schemas/set-header-args {:name 'x-foo :value "bar"}           false]

   ;; ---- :rf.server/append-header ----------------------------------------
   ["append-header well-formed" :rf.server/append-header
    fx-schemas/append-header-args {:name "Vary" :value "Accept"}     true]
   ["append-header int :value"  :rf.server/append-header
    fx-schemas/append-header-args {:name "Vary" :value 42}           false]

   ;; ---- :rf.server/set-cookie -------------------------------------------
   ["set-cookie minimal"       :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "session" :value "abc"}        true]
   ["set-cookie canonical"     :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "session" :value "abc"
                                :max-age 3600 :same-site :lax
                                :secure true :http-only true
                                :path "/" :domain "example.com"}     true]
   ["set-cookie string attrs (documented ingress tolerance)"
    :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "session" :value "abc"
                                :max-age "3600" :expires "1700000000000"
                                :same-site "Strict"}                 true]
   ["set-cookie no :value"     :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "session"}                     false]
   ["set-cookie int :value"    :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "session" :value 42}           false]
   ["set-cookie bogus :same-site keyword" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :same-site :bogus} false]
   ["set-cookie non-boolean :secure" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :secure "yes"}  false]

   ;; ---- :rf.server/delete-cookie ----------------------------------------
   ["delete-cookie well-formed" :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:name "stale" :path "/"}          true]
   ["delete-cookie no :name"    :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:path "/"}                        false]
   ["delete-cookie int :path"   :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:name "stale" :path 42}           false]

   ;; ---- :rf.server/redirect ---------------------------------------------
   ["redirect well-formed"     :rf.server/redirect
    fx-schemas/redirect-args {:location "/dashboard" :status 302}    true]
   ;; The documented no-target graceful path: NOT a structural error, so both
   ;; halves must ACCEPT it and let the adapter's warn→3xx take over.
   ["redirect no target"       :rf.server/redirect
    fx-schemas/redirect-args {:status 302}                           true]
   ["redirect non-int :status" :rf.server/redirect
    fx-schemas/redirect-args {:location "/x" :status "oops"}         false]
   ["redirect keyword :location" :rf.server/redirect
    fx-schemas/redirect-args {:location :dashboard}                  false]

   ;; ---- :rf.server/safe-redirect ----------------------------------------
   ["safe-redirect well-formed" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/dashboard"
                                   :status 302
                                   :relative-only? true
                                   :allow ["app.example.com"]}       true]
   ["safe-redirect no :location" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:status 302}                      false]
   ["safe-redirect non-int :status" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :status "not-int"} false]
   ["safe-redirect scalar :allow" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok"
                                   :allow "app.example.com"}         false]
   ["safe-redirect non-boolean :relative-only?" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :relative-only? "yes"} false]

   ;; ---- AUDIT (a): every optional key, explicitly nil — ACCEPTED by both --
   ;; `{:path nil}` and `{}` say the same thing in Clojure, and code that
   ;; builds a cookie from an options map writes the first meaning the second.
   ["set-cookie nil :path"      :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :path nil}      true]
   ["set-cookie nil :domain"    :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :domain nil}    true]
   ["set-cookie nil :max-age"   :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :max-age nil}   true]
   ["set-cookie nil :expires"   :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :expires nil}   true]
   ["set-cookie nil :same-site" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :same-site nil} true]
   ["set-cookie nil :secure"    :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :secure nil}    true]
   ["set-cookie nil :http-only" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v" :http-only nil} true]
   ["set-cookie every optional nil at once" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value "v"
                                :path nil :domain nil :max-age nil
                                :expires nil :same-site nil
                                :secure nil :http-only nil}          true]
   ["delete-cookie nil :path"   :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:name "stale" :path nil}          true]
   ["delete-cookie nil :domain" :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:name "stale" :domain nil}        true]
   ["redirect nil :status"      :rf.server/redirect
    fx-schemas/redirect-args {:location "/x" :status nil}            true]
   ;; The no-target path spelled with an explicit nil rather than by omission.
   ["redirect nil :location"    :rf.server/redirect
    fx-schemas/redirect-args {:location nil :status 302}             true]
   ["safe-redirect nil :status" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :status nil}      true]
   ["safe-redirect nil :relative-only?" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :relative-only? nil} true]
   ["safe-redirect nil :allow"  :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :allow nil}       true]
   ["safe-redirect every optional nil at once" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location "/ok" :status nil
                                   :relative-only? nil :allow nil}   true]

   ;; A REQUIRED key is not an optional one: nil is the violation it looks
   ;; like. These are the control rows for AUDIT (a) — without them `[:maybe …]`
   ;; on the optional slots could have been read as nil-tolerance at large.
   ["set-cookie nil :value (required)" :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name "s" :value nil}                false]
   ["set-cookie nil :name (required)"  :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name nil :value "v"}                false]
   ["set-header nil :value (required)" :rf.server/set-header
    fx-schemas/set-header-args {:name "X-Foo" :value nil}            false]
   ["safe-redirect nil :location (required)" :rf.server/safe-redirect
    fx-schemas/safe-redirect-args {:location nil}                    false]

   ;; ---- AUDIT (b): a cookie `:name` is the published `:string` ---------
   ["set-cookie keyword :name"  :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name :csrf :value "v"}              false]
   ["set-cookie symbol :name"   :rf.server/set-cookie
    fx-schemas/set-cookie-args {:name 'tracker :value "v"}           false]
   ["delete-cookie keyword :name" :rf.server/delete-cookie
    fx-schemas/delete-cookie-args {:name :stale :path "/"}           false]])

;; ===========================================================================
;; (1) THE DEFECT — the four measured malformations, closed under BOTH postures
;;
;; These are the seven semantic assertions rf2-dtpfv measured red under
;; `clojure -M:test:prod-gate`, restated so they are true in every build. Each
;; reads the PURE accumulator, so it is a claim about the guard and not about
;; the error projection downstream of it.
;; ===========================================================================

(deftest a-string-status-never-reaches-the-accumulator
  (testing "rf2-dtpfv THE BEAD: `[:rf.server/set-status \"not-an-int\"]` left
            `:status \"not-an-int\"` on the accumulator in a release build —
            a STRING on the HTTP status line, saved only by one host
            adapter's coercion."
    (let [{:keys [raw response]} (drive! [:rf.server/set-status "not-an-int"])]
      (is (not= "not-an-int" (:status raw))
          "the malformed status never reached the accumulator")
      (is (integer? (:status raw))
          "the accumulator's :status is an integer")
      (is (integer? (:status response))
          "and so is the PUBLIC host-adapter surface's")
      (is (sibling-ran? raw)
          "containment: only the offending fx was skipped — its sibling ran"))))

(deftest a-value-less-header-never-reaches-the-accumulator
  (testing "rf2-dtpfv: a `:value`-less `:rf.server/set-header` left
            `[\"X-Foo\" nil]` in `:headers` — a nil header value every host
            adapter then has to invent a meaning for."
    (let [{:keys [raw response]} (drive! [:rf.server/set-header {:name "X-Foo"}])]
      (is (not-any? (fn [[k _]] (= "X-Foo" k)) (:headers raw))
          "nothing landed for the malformed header")
      (is (not-any? (fn [[_ v]] (nil? v)) (:headers raw))
          "no header on the accumulator carries a nil value")
      (is (not-any? (fn [[_ v]] (nil? v)) (:headers response))
          "nor does any on the PUBLIC host-adapter surface")
      (is (sibling-ran? raw) "containment: the sibling fx still ran"))))

(deftest a-value-less-cookie-never-reaches-the-accumulator
  (testing "rf2-dtpfv: a `:value`-less `:rf.server/set-cookie` left
            `{:name \"session\"}` in `:cookies`. A missing cookie `:value` is
            not inferable as `\"\"`, as omit, or as a bug — which is exactly
            why repairing downstream was rejected."
    (let [{:keys [raw response]} (drive! [:rf.server/set-cookie {:name "session"}])]
      (is (empty? (:cookies raw))
          "the malformed cookie never reached the accumulator")
      (is (empty? (:cookies response))
          "nor the PUBLIC host-adapter surface")
      (is (sibling-ran? raw) "containment: the sibling fx still ran"))))

(deftest a-malformed-safe-redirect-never-reaches-the-accumulator
  (testing "rf2-dtpfv: `{:location \"/ok\" :status \"not-int\"}` installed
            whole — a string status inside the `:redirect` slot, which Spec
            011 §Redirect precedence step 1 also flows onto `:status`."
    (let [{:keys [raw response]} (drive! [:rf.server/safe-redirect
                                          {:location "/ok" :status "not-int"}])]
      (is (nil? (:redirect raw))
          "no :redirect landed")
      (is (not= "not-int" (:status raw))
          "and the non-int status did not flow onto :status")
      (is (integer? (:status response))
          "the PUBLIC host-adapter surface's :status is an integer")
      (is (sibling-ran? raw) "containment: the sibling fx still ran"))))

(deftest a-target-less-safe-redirect-is-a-code-bug-not-a-security-signal
  (testing "rf2-dtpfv: `:location` is `:rf.server/safe-redirect`'s validation
            TARGET, so a call without one is a programmer error — and that is
            precisely what the five-step gate CANNOT say. Left to it, a missing
            or non-string `:location` falls through to
            `parse-url-safely` → nil → `:rf.error/safe-redirect-invalid-url`
            (\"URL did not parse\"), which since rf2-6jqa8 ships an ALWAYS-ON
            record on the security axis. So a forgotten key would page a
            security team as an open-redirect probe. The refusal was never in
            doubt (every non-string fails the gate closed); what the shape
            guard buys is that the code bug is not filed as an attack."
    (doseq [[label args] [["missing :location"   {:status 302}]
                          ["keyword :location"   {:location :dashboard}]]]
      (let [{:keys [raw records]} (drive! [:rf.server/safe-redirect args])
            security-records (filter #(clojure.string/starts-with?
                                        (name (:error %)) "safe-redirect-")
                                     records)]
        (is (nil? (:redirect raw))
            (str label " — nothing landed on the accumulator"))
        (is (empty? security-records)
            (str label " — and NO :rf.error/safe-redirect-* record reached the"
                 " always-on security axis; saw: "
                 (pr-str (mapv :error records))))))))

(deftest a-malformed-trusted-redirect-never-reaches-the-accumulator
  (testing "rf2-dtpfv, the caller-TRUSTED sibling: `:rf.server/redirect` took
            a non-int `:status` just as readily."
    (let [{:keys [raw]} (drive! [:rf.server/redirect {:location "/x" :status "oops"}])]
      (is (nil? (:redirect raw)) "no :redirect landed")
      (is (integer? (:status raw)) "the accumulator's :status is an integer")
      (is (sibling-ran? raw) "containment: the sibling fx still ran"))))

;; ===========================================================================
;; (2) NON-VACUITY — the guards admit the valid
;;
;; Without this, every assertion in §1 would be satisfied by a gate that
;; refused everything.
;; ===========================================================================

(deftest well-formed-args-still-land-under-both-postures
  (testing "rf2-dtpfv non-vacuity: the guard rejects the malformed and admits
            the valid — it is not a blanket gate."
    (rf/reg-event ::good
      (fn [_ _]
        {:fx [[:rf.server/set-status 201]
              [:rf.server/set-header    {:name "Cache-Control" :value "no-store"}]
              [:rf.server/append-header {:name "Vary" :value "Accept"}]
              [:rf.server/set-cookie    {:name "session" :value "abc"
                                         :max-age 3600 :same-site :lax
                                         :secure true :http-only true}]
              [:rf.server/delete-cookie {:name "stale" :path "/"}]]}))
    (let [f (server-frame)]
      (rf/dispatch-sync [::good] {:frame f})
      (let [raw (ssr/peek-response f)]
        (is (= 201 (:status raw)) "set-status landed")
        (is (some (fn [[k _]] (= "Cache-Control" k)) (:headers raw))
            "set-header landed")
        (is (some (fn [[k _]] (= "Vary" k)) (:headers raw))
            "append-header landed")
        (is (= 2 (count (:cookies raw)))
            "set-cookie + delete-cookie both landed")
        (is (= ["session" "stale"] (mapv :name (:cookies raw)))
            "in order, with their names intact")))))

(deftest the-documented-tolerances-are-not-tightened-by-the-guard
  (testing "rf2-dtpfv: the cookie schema DELIBERATELY tolerates string
            `:max-age` / `:expires` / `:same-site` (apps build cookie attrs
            from host data that arrives as strings, and the per-attribute
            CR/LF gate must SEE those strings). The guard mirrors that
            tolerance row for row — a string `:expires` is rejected LATER, by
            the Ring materialiser, exactly as before."
    (let [f (server-frame)]
      (rf/reg-event ::tolerant
        (fn [_ _]
          {:fx [[:rf.server/set-cookie {:name      "session"
                                        :value     "abc"
                                        :max-age   "3600"
                                        :expires   "1700000000000"
                                        :same-site "Strict"}]]}))
      (rf/dispatch-sync [::tolerant] {:frame f})
      (is (= 1 (count (:cookies (ssr/peek-response f))))
          "the string-attr cookie still lands at the fx boundary")))

  (testing "rf2-dtpfv: `:rf.server/redirect`'s documented NO-TARGET graceful
            path is untouched — `:location` stays optional, the redirect still
            installs, and the adapter's `:rf.ssr/ssr-redirect-no-target`
            warn→3xx still takes over downstream. A required-`:location` guard
            here would 500 the case Spec 011 says must degrade gracefully."
    (let [f (server-frame)]
      (rf/reg-event ::no-target
        (fn [_ _] {:fx [[:rf.server/redirect {:status 302}]]}))
      (rf/dispatch-sync [::no-target] {:frame f})
      (is (= {:status 302} (:redirect (ssr/peek-response f)))
          "the target-less redirect passed the guard and set :redirect")))

  (testing "rf2-dtpfv taxonomy: an untrusted-INPUT policy rejection stays the
            existing emit-and-no-op. A blank `:location` is a STRING — a
            well-formed call carrying a bad value — so it takes
            safe-redirect's parse-failure arm, not the guard's throw."
    (let [{:keys [raw response]} (drive! [:rf.server/safe-redirect {:location ""}])]
      (is (nil? (:redirect raw))
          "the policy gate still refuses it")
      (is (= 200 (:status response))
          "and it is NOT projected to a 500 — a rejected redirect must not
           become a denial of service"))))

;; ===========================================================================
;; (2b) THE AUDIT'S TWO SHAPES, WITNESSED ON THE RAW ACCUMULATOR
;;
;; The corpus below proves the two halves give one ANSWER. These prove what
;; that answer leaves behind, read off the pure accumulator — which is the
;; claim Spec 011 §Every adapter inherits a well-formed accumulator makes and
;; the one the audit found untrue one key deep. Both run under the real
;; production gate, where the schema half does not exist.
;; ===========================================================================

(deftest an-optional-key-present-with-nil-is-absent-not-a-violation
  (testing "rf2-dtpfv AUDIT: the guards read `{:path nil}` as absent while a
            bare `[:path {:optional true} :string]` read it as a violation — so
            a cookie assembled from an options map (`{:secure (:secure? opts)}`
            with no `:secure?` in it) was SKIPPED in dev and PERSISTED in
            production. Settled as absent on both sides. The cookie lands, in
            either build, and its nil attrs read as the absent ones they are."
    (let [{:keys [raw response records]}
          (drive! [:rf.server/set-cookie {:name "session" :value "abc"
                                          :path nil :domain nil :max-age nil
                                          :expires nil :same-site nil
                                          :secure nil :http-only nil}])
          cookie (first (:cookies raw))]
      (is (= 1 (count (:cookies raw)))
          "the nil-bearing cookie reached the accumulator")
      (is (= "session" (:name cookie)) "with its name intact")
      (is (every? nil? ((juxt :path :domain :max-age :expires
                              :same-site :secure :http-only) cookie))
          "and every optional attribute reads as absent, which is what an
           adapter appending `Path=` only `(when path)` already assumes")
      (is (empty? records)
          (str "no error record fired — a present nil is not a violation; saw: "
               (pr-str (mapv :error records))))
      (is (= 200 (:status response))
          "and the response is not projected to a 500")))

  (testing "rf2-dtpfv AUDIT control: a REQUIRED key is not an optional one. If
            `[:maybe …]` had been read as nil-tolerance at large, this would
            have started landing too."
    (let [{:keys [raw]} (drive! [:rf.server/set-cookie {:name "session" :value nil}])]
      (is (empty? (:cookies raw))
          "a nil :value is still a cookie with no value, and is still refused")
      (is (sibling-ran? raw) "containment: the sibling fx still ran"))))

(deftest a-cookie-name-on-the-accumulator-is-always-a-string
  (testing "rf2-dtpfv AUDIT: the fx boundary admitted a keyword / symbol
            `:name` — mirroring the Ring materialiser's `clojure.lang.Named`
            check — while the schemas, Spec 011 §Cookie shape and Spec-Schemas
            all published `:string`. So `{:name :csrf}` was skipped in dev,
            landed in production, and reached every host adapter as a keyword
            where the contract promised a string. An adapter comparing
            `(= \"csrf\" (:name c))` saw the two builds differently."
    (doseq [[label args] [["keyword :name" {:name :csrf    :value "v"}]
                          ["symbol :name"  {:name 'tracker :value "v"}]]]
      (let [{:keys [raw response]} (drive! [:rf.server/set-cookie args])]
        (is (empty? (:cookies raw))
            (str label " — nothing landed on the accumulator"))
        (is (empty? (:cookies response))
            (str label " — nor on the PUBLIC host-adapter surface"))
        (is (sibling-ran? raw)
            (str label " — containment: the sibling fx still ran")))))

  (testing "rf2-dtpfv AUDIT: `:rf.server/delete-cookie` is sugar over the same
            validator, so it inherits the same published `:name` type."
    (let [{:keys [raw]} (drive! [:rf.server/delete-cookie {:name :stale :path "/"}])]
      (is (empty? (:cookies raw))
          "the keyword-named delete marker never reached the accumulator")))

  (testing "rf2-dtpfv AUDIT non-vacuity: what DOES land carries the published
            type, which is the claim Spec 011 makes to host adapters."
    (let [f (server-frame)]
      (rf/reg-event ::named
        (fn [_ _]
          {:fx [[:rf.server/set-cookie    {:name "session" :value "abc"}]
                [:rf.server/delete-cookie {:name "stale" :path "/"}]]}))
      (rf/dispatch-sync [::named] {:frame f})
      (let [cookies (:cookies (ssr/peek-response f))]
        (is (= 2 (count cookies)) "both string-named cookies landed")
        (is (every? string? (map :name cookies))
            "and every :name on the accumulator is a string")))))

;; ===========================================================================
;; (3) THE ACCEPTANCE CORPUS — schema and guard cannot drift
;; ===========================================================================

(defn- landed?
  "Did `fx-id` actually WRITE to the accumulator? One probe per fx family,
  each reading the slot that fx owns — a truth-table over `:status` alone
  would read `[:rf.server/set-status 200]` as 'nothing happened', since 200
  is also the default. `:rf.server/set-status`'s write is therefore read off
  the `_status-writes` bookkeeping, which records EVERY write."
  [fx-id args {:keys [raw bookkept]}]
  (case fx-id
    :rf.server/set-status
    (boolean (seq (get bookkept response/status-writes-key)))

    (:rf.server/set-header :rf.server/append-header)
    (boolean (some (fn [[k _]] (= (:name args) k)) (:headers raw)))

    (:rf.server/set-cookie :rf.server/delete-cookie)
    (boolean (seq (:cookies raw)))

    (:rf.server/redirect :rf.server/safe-redirect)
    (some? (:redirect raw))))

(deftest schema-and-guard-agree-on-every-canonical-case
  (testing "rf2-dtpfv: the registered `:rf.fx.server/*-args` schemas (the DEV
            step-5 boundary) and the always-on guards in
            `re-frame.ssr.response` are two enforcements of ONE published
            contract, written in two languages in two files. This drives the
            same corpus through both so a widening on one side cannot land
            alone. `m/validate` is called directly, so this adjudicates under
            the production gate too."
    (doseq [[label _fx-id schema args accept?] acceptance-corpus]
      (is (= accept? (m/validate schema args))
          (str label " — the registered Malli schema must "
               (if accept? "ACCEPT" "REJECT") " " (pr-str args))))))

(deftest the-guards-accept-exactly-what-the-schemas-accept
  (testing "rf2-dtpfv: the same corpus, driven through the LIVE fx. A row the
            schema rejects must leave the accumulator untouched; a row it
            accepts must reach it. Posture-independent by construction: in dev
            the step-5 schema skips the rejected fx, in production the guard
            throws and containment skips it — either way nothing lands."
    (doseq [[label fx-id schema args accept?] acceptance-corpus]
      (is (= accept? (landed? fx-id args (drive! [fx-id args])))
          (str label " — the always-on guard must "
               (if accept? "ADMIT" "REFUSE") " " (pr-str args)
               " (schema says " (m/validate schema args) ")")))))

;; ===========================================================================
;; (4) THE DIAGNOSTIC — the ONE posture-split section
;;
;; §1–§3 pinned the SEMANTICS, which are now identical in both builds. The
;; recovery PATH still differs, by design. Split it explicitly rather than
;; letting a dev-bus read decide the namespace's posture.
;; ===========================================================================

(deftest the-production-diagnostic-is-always-on
  (when-not interop/debug-enabled?
    (testing "rf2-dtpfv: in a release build the guard's throw is contained by
              `re-frame.fx`, which fans an ALWAYS-ON
              `:rf.error/fx-handler-exception` — so the defect is visible to
              an off-box shipper, unlike the silent 200→500 rewrite the Ring
              adapter used to perform. And SSR's error projection turns it
              into a sanitised 500 at `get-response`."
      (let [{:keys [records response]} (drive! [:rf.server/set-status "not-an-int"])
            hits (filter #(= :rf.error/fx-handler-exception (:error %)) records)]
        (is (seq hits)
            (str "an always-on fx-handler-exception record reached the"
                 " :errors axis; saw: " (pr-str (mapv :error records))))
        (is (= :rf.server/set-status (:failing-id (first hits)))
            "the record names WHICH reserved fx was malformed")
        (is (= 500 (:status response))
            "and `get-response` serves a sanitised 500 rather than a string
             status")))))

(deftest the-dev-diagnostic-is-the-richer-schema-failure
  (when interop/debug-enabled?
    (testing "rf2-dtpfv: with schemas live in a dev build the Spec 010 step-5
              boundary rejects FIRST, so the guard never runs and the
              programmer gets the richer `:where :fx-args` diagnostic with the
              failing path — and, per Spec 010 §Per-step recovery row 5, the
              fx is skipped and the page still renders."
      (let [traces (atom [])
            tag    (keyword "rf2-dtpfv" (str "trace-" (name (gensym "t"))))
            f      (server-frame)]
        (rf/register-listener! :trace tag
          (fn [ev] (when (= :rf.error/schema-validation-failure (:operation ev))
                     (swap! traces conj ev))))
        (rf/reg-event ::bad-status
          (fn [_ _] {:fx [[:rf.server/set-status "not-an-int"]]}))
        (try
          (rf/dispatch-sync [::bad-status] {:frame f})
          (finally (rf/unregister-listener! :trace tag)))
        (is (seq (filter (fn [ev] (and (= :fx-args (-> ev :tags :where))
                                       (= :rf.server/set-status
                                          (-> ev :tags :failing-id))))
                         @traces))
            (str "a :where :fx-args schema failure named the fx; saw: "
                 (pr-str (mapv (comp :failing-id :tags) @traces))))
        (is (= 200 (:status (ssr/peek-response f)))
            "the fx was skipped and the app's status is untouched — the dev
             recovery is `:skipped`, not a 500")))))


(deftest a-user-fx-schema-stays-dev-posture-and-genuinely-elides
  (testing "rf2-dtpfv, the OTHER half of the ruling. Everything above is about
            seven framework-owned effects. A `:schema` an APPLICATION declares
            over its own effect is the case the ruling deliberately left alone:
            trust-the-programmer covers user declarations, and validating every
            effect in every app to protect a closed framework set is a cost the
            design refuses. So step 5 must still be exactly as dev-only as
            [Spec 010 §Per-step recovery row 5] now says it is — nothing here
            leaked onto the user-fx path, and nothing may.

            Written as one witness with two arms because the arms are the
            claim: in dev the effect is SKIPPED with a diagnostic; in a release
            build it RUNS with the malformed args and there is no trace to say
            so. Turn the general gate on and the production arm reds."
    (let [ran    (atom [])
          traces (atom [])
          tag    (keyword "rf2-dtpfv" (str "user-" (name (gensym "u"))))
          f      (server-frame)]
      (rf/reg-fx ::user-fx
        {:schema [:map [:n :int]]}
        (fn [_ctx args] (swap! ran conj args)))
      (rf/reg-event ::user-attempt (fn [_ _] {:fx [[::user-fx {:n "not-an-int"}]]}))
      (rf/register-listener! :trace tag
        (fn [ev] (when (and (= :rf.error/schema-validation-failure (:operation ev))
                            (= :fx-args (-> ev :tags :where)))
                   (swap! traces conj ev))))
      (try
        (rf/dispatch-sync [::user-attempt] {:frame f})
        (finally (rf/unregister-listener! :trace tag)))
      (if interop/debug-enabled?
        (do (is (empty? @ran)
                "dev: the user fx was SKIPPED — its handler never ran")
            (is (seq @traces)
                "dev: and the programmer got the :where :fx-args diagnostic"))
        (do (is (= [{:n "not-an-int"}] @ran)
                "production: step 5 ran no check at all, so nothing was skipped
                 and the malformed args reached the handler — the elision the
                 reserved family exists precisely because it cannot rely on")
            (is (empty? @traces)
                "production: and there is no trace to say so"))))))


;; ===========================================================================
;; (5) THE REJECTION DISCLOSES NOTHING IT WAS HANDED (rf2-210uq)
;;
;; This guard routes the offending value through
;; `re-frame.error/diag-value-summary` into BOTH the thrown message and the
;; ex-data, on the stated grounds that the summary carries shape and never
;; value — "a cookie `:value` is a session token". When the guard landed, the
;; summary did not meet that claim: its `:head` leg returned any string of 24
;; characters or fewer VERBATIM and a longer one's raw first 24 characters,
;; applied no bound at all to keywords/symbols, and printed numbers; its
;; `:keys` leg returned every top-level map key, uncapped and unsanitised.
;;
;; rf2-210uq made the summary content-free by construction. This section is
;; the PRODUCTION-FACING pin for that: a rejection reached through the real
;; public fx handlers must carry no fragment of what it rejected, into the
;; message OR the ex-data. The handlers are called directly rather than
;; dispatched, so the Spec 010 step-5 schema boundary cannot intercept and
;; these assertions hold under BOTH postures — the guard is always-on, and
;; so is its diagnostic.
;; ===========================================================================

(def ^:private sentinel
  "EXACTLY 24 characters — the historic `diag-head-limit`. A secret whose
  first 24 chars are this marker was reproduced WHOLE by the old
  `(subs s 0 24)` head, so it is the sharpest available witness."
  "SENTINELSENTINELSENTINEL")

(defn- rejection-of
  "Call `handler-fn` with a live server frame and `args`, and return the
  thrown `:rf.error/server-fx-args-invalid` — or nil if it did not throw."
  [handler-fn args]
  (try
    (handler-fn {:frame (server-frame)} args)
    nil
    (catch clojure.lang.ExceptionInfo e e)))

(def ^:private token-bearing-malformations
  "Malformed reserved-fx args whose OFFENDING VALUE carries the sentinel, one
  per path the old summary disclosed through. Each is a genuine type
  violation — the published contract wants a string — so each reaches
  `reject-arg!` with a value that is, in production, a session token."
  [;; A raw STRING reaching a slot whose published type is not a string —
   ;; the two cases the old `:head` leg disclosed most directly. A string
   ;; cookie `:value` is LEGAL and never reaches the guard, so the token is
   ;; routed through `:status` and the boolean `:secure` instead.
   ["status — a SHORT token (under the old 24-char head, so returned verbatim)"
    response/set-status-fx (subs sentinel 0 16)]
   ["status — a long token whose first 24 chars ARE the sentinel"
    response/set-status-fx (str sentinel "-tail-0123456789")]
   ["cookie :secure — a raw token where a boolean was published"
    response/set-cookie-fx {:name "session" :value "v" :secure (str sentinel "-x")}]
   ;; Non-string values in the session-token slot itself.
   ["cookie :value — a keyword, which the old head bounded not at all"
    response/set-cookie-fx {:name "session" :value (keyword sentinel)}]
   ["cookie :value — a number that IS the secret"
    response/set-cookie-fx {:name "session" :value 4111111111111111}]
   ["cookie :value — a MAP, whose keys the old `:keys` leg reproduced"
    response/set-cookie-fx {:name "session" :value {sentinel "v" (keyword sentinel) 2}}]
   ["header :value — a map with sentinel-bearing keys"
    response/set-header-fx {:name "X-Auth" :value {sentinel "v"}}]
   ["header :value — a vector carrying the token"
    response/set-header-fx {:name "X-Auth" :value [sentinel]}]
   ["header :value — an attacker-sized map (the old `:keys` leg grew unbounded)"
    response/set-header-fx {:name "X-Auth"
                            :value (into {} (map (fn [i] [(str sentinel "-" i) i]))
                                         (range 500))}]
   ;; The args value is not a map at all — `validate-args-map!`'s own leg.
   ["args map — a bare token string where a map was required"
    response/set-cookie-fx (str sentinel "-not-a-map")]
   ["args map — a token-bearing vector where a map was required"
    response/set-header-fx [sentinel]]])

(deftest a-rejection-carries-no-fragment-of-the-value-it-rejected
  (doseq [[label handler-fn args] token-bearing-malformations]
    (testing label
      (let [e (rejection-of handler-fn args)]
        (is (some? e) "the always-on guard rejected the malformed arg")
        (when e
          (is (= :rf.error/server-fx-args-invalid (:rf.error/id (ex-data e)))
              "the catalogued Spec 009 category")
          (is (not (clojure.string/includes? (ex-message e) "SENTINEL"))
              (str "the THROWN MESSAGE disclosed the value: " (ex-message e)))
          (is (not (clojure.string/includes? (pr-str (ex-data e)) "SENTINEL"))
              (str "the EX-DATA disclosed the value: " (pr-str (ex-data e)))))))))

(deftest a-rejection-still-names-the-fx-the-key-and-the-shape
  (testing "rf2-210uq removed disclosure, not diagnosis. WHICH fx and WHICH
            argument failed ride explicitly trusted ex-data slots the emit
            site controls — never a guess recovered from the value — and the
            shape summary still answers 'what did I actually get?'"
    (let [e    (rejection-of response/set-cookie-fx
                             {:name "session" :value {sentinel "v"}})
          data (ex-data e)]
      (is (= :rf.server/set-cookie (:fx-id data)) "the trusted fx-id slot")
      (is (= :value (:key data))                  "the trusted arg-key slot")
      (is (= {:type :map :count 1} (:value data))
          "the summary names the shape that arrived, and nothing else")
      (is (clojure.string/includes? (ex-message e) "must be a string")
          "and the published expectation is still spelled out"))))

(deftest a-rejection-message-is-a-fixed-size-whatever-arrives
  (testing "the old `:keys` leg grew the 'bounded' summary with the offending
            map's key set, so an attacker-sized value inflated a message
            headed for an SSR host log. The summary is now fixed-size, so the
            message length no longer tracks the input's"
    (let [small (ex-message (rejection-of response/set-header-fx
                                          {:name "X-Auth" :value {:a 1}}))
          huge  (ex-message (rejection-of response/set-header-fx
                                          {:name "X-Auth"
                                           :value (into {} (map (fn [i]
                                                                  [(str sentinel "-" i) i]))
                                                        (range 2000))}))]
      ;; The two messages differ ONLY in the rendered `:count` digits.
      (is (< (- (count huge) (count small)) 8)
          (str "message grew with the input: " (count small) " → " (count huge))))))
