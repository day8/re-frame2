(ns re-frame.example-login-success-token-cljs-test
  "Framework-tree regression for the login feature's SUCCESS-TOKEN privacy — the
   `:auth.login/succeeded` reply event and the `:auth.session/store` persistence
   fx owned by examples/core/login/model.cljc, the substrate-free model shared
   across the Reagent/UIx login examples (rf2-ppbvav / rf2-j538f7.30).

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the login model owner
   (`login.model`) so its events / subs / machine / schemas register at ns-load,
   then drives the success continuation directly. Sibling of
   `re-frame.example-login-form-slice-cljs-test` (the PASSWORD-egress half); this
   ns pins the RETURN half — the session token the server hands back.

   THE SUCCESS TOKEN IS A CREDENTIAL, so — exactly like the password on the way
   in — it is classified at every TRANSIENT boundary it crosses on the way back
   (docs/core/how-to/keep-secrets-out-of-traces.md):

     1. THE REPLY EVENT. Managed HTTP appends its reply as the LAST positional
        arg of `:on-success`. Routed at a one-element `[:auth.login/succeeded]`,
        the reply lands in the addressable arg-map slot, and the registration's
        `:sensitive [[:value :token]]` redacts `[:value :token]` in the
        dispatched-event trace (and any error record carrying the event) while
        the handler still reads the real token. (Routing it at the two-element
        machine event would make the reply a THIRD positional arg — unaddressable
        — which is the leak this fix closes.)

     2. THE STORAGE FX. `:auth.session/store` declares `:sensitive [[:token]]`
        on its own registration, so the per-effect `:rf.fx/handled` trace redacts
        `[:token]`. fx args are a transient owner distinct from the event.

     3. THE MACHINE IS KEPT CREDENTIAL-FREE. The reply never reaches the machine
        — `:auth.login/succeeded` nudges it with a bare, credential-free
        `[:auth.login/flow [:auth.login/success]]` signal, so the flow reaches
        `:authed` without ever seeing the token.

   FRAMEWORK-GATED SLOTS — NOW CLOSED (rf2-6h3c02). Two trace slots used to carry
   the raw token, neither reachable by app-side classification — central
   classification-projector gaps where `project-trace-event` applied the fx
   registration's `:sensitive` only to the `:rf.fx/handled` slot. rf2-6h3c02
   taught the projector to apply the fx registration's `:sensitive` to EVERY
   fx-arg-bearing slot, so both now redact off the SAME `:auth.session/store`
   `:sensitive [[:token]]` this example already declares:

     - `:rf.event/fx` on `:rf.fx/do-fx` — the handler's WHOLE returned effect
       vector; each entry's args now walk through its fx registration (sibling of
       the `:rf.event/db` walk — Spec 009 §Canonical per-event trace sequence
       notes they share posture). Asserted clean by the whole-stream sweep below.
     - `:rf.fx/args` on the always-on (production-survivable)
       `:rf.error/fx-handler-exception` — fx-args redaction is now keyed on the
       slot shape, not op `:rf.fx/handled`. Asserted clean by the error-arm test.

   This ns asserts everything the example owns is redacted, pins the two
   registration declarations the projector consumes, and — post-rf2-6h3c02 — the
   two formerly-residual framework slots."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; login.model pulls these transitively; require here so the ns is
            ;; self-sufficient (mirrors the sibling login test namespaces).
            [re-frame.schemas]
            [re-frame.machines]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

;; A UNIQUE sentinel token — a string that appears nowhere else in the source or
;; the framework, so a recursive scan for it across the trace stream can only be
;; hitting THIS reply's token.
(def sentinel "SESSION-TOKEN-SENTINEL-8b71e0")

(def success-reply
  "The canonical managed-HTTP success envelope for the login request, carrying a
   uniquely-tagged session token — exactly what the demo stub conjures and what
   managed HTTP appends to `[:auth.login/succeeded]`."
  {:status :ok
   :value  {:user  {:id "u1" :email "alice@example.com"}
            :token sentinel}})

(defn- machine-state [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :state]))

(defn- contains-sentinel?
  "True when `x` contains the sentinel token string ANYWHERE in a nested data
   structure — the recursive scan an off-box shipper / dev tool would apply."
  [x]
  (cond
    (string? x) (not= -1 (.indexOf x sentinel))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

(defn- submit! [f]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit]] {:frame f}))

;; ---------------------------------------------------------------------------
;; (positive control) the real token IS usable end to end
;; ---------------------------------------------------------------------------

(deftest succeeded-persists-real-token-and-drives-machine-to-authed
  (testing "the success handler hands the REAL token to the persistence fx and
            drives the machine to :authed with a credential-free signal"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (is (= :submitting (machine-state f))
          "the submit signal put the flow in :submitting")
      (let [stored (atom nil)]
        ;; Capture the persistence fx's args (positive control) but let the
        ;; credential-free machine :dispatch run, so the flow reaches :authed.
        (rf/dispatch-sync [:auth.login/succeeded success-reply]
                          {:frame        f
                           :fx-overrides {:auth.session/store
                                          (fn [_ args] (reset! stored args))}})
        (is (= {:token sentinel} @stored)
            "the persistence fx received the REAL token — it is fully usable by
             the app logic (this is what gets written to localStorage)")
        (is (= :authed (machine-state f))
            "the bare, credential-free :success signal drove the flow to :authed
             — the machine reached the happy ending without seeing the token")))))

;; ---------------------------------------------------------------------------
;; the token is redacted across every EXAMPLE-OWNED trace slot
;; ---------------------------------------------------------------------------

(deftest success-token-redacted-across-trace-egress
  (testing "on a successful login, the session token does NOT appear raw in any
            reply-event or per-effect trace slot the example owns; the reply
            event redacts [:value :token] and the store fx redacts [:token]"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (let [traces (record-traces!)]
        (rf/dispatch-sync [:auth.login/succeeded success-reply] {:frame f})
        (rf/unregister-listener! :trace ::probe)

        (is (= :authed (machine-state f))
            "sanity: the drive reached :authed (so the full cascade was traced)")

        ;; --- the reply event trace redacts the token, shape retained ---
        (let [succeeded-vs (->> @traces
                                (keep #(get-in % [:tags :rf.event/v]))
                                (filter #(and (vector? %)
                                              (= :auth.login/succeeded (first %)))))]
          (is (seq succeeded-vs)
              "the :auth.login/succeeded event surfaced on the trace with its payload")
          (doseq [v succeeded-vs]
            (is (= rf.privacy/redacted-sentinel (get-in v [1 :value :token]))
                "the token reads :rf/redacted in the succeeded event trace")
            (is (= :ok (get-in v [1 :status]))
                "shape retained — the envelope :status is still visible")
            (is (contains? (get-in v [1 :value]) :user)
                "shape retained — the :user sibling is still present")))

        ;; --- the store fx's per-effect trace redacts its :token arg ---
        (let [handled (->> @traces
                           (filter #(= :auth.session/store (get-in % [:tags :rf.fx/id]))))]
          (is (seq handled)
              "the :auth.session/store fx emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
                "the store fx :token arg reads :rf/redacted in :rf.fx/handled")))

        ;; --- the whole-stream sweep: the sentinel appears in NO trace tag.
        ;;     Post-rf2-6h3c02 this includes :rf.event/fx (the :rf.fx/do-fx
        ;;     aggregate) — the projector now walks each fx entry's args through
        ;;     its registration, so the store fx's [:token] redacts there too.
        ;;     (The error-arm :rf.fx/args gap — also closed by rf2-6h3c02 — only
        ;;     fires when the fx THROWS, exercised in the error-arm test below.)
        (let [checked (atom 0)]
          (doseq [ev @traces
                  [k v] (:tags ev)]
            (swap! checked inc)
            (is (not (contains-sentinel? v))
                (str "the session token must not appear raw in " (:operation ev)
                     " / " k)))
          (is (pos? @checked)
              "the sweep actually inspected trace tags (guard against a no-op)"))))))

;; ---------------------------------------------------------------------------
;; the error arm — the origin event on the fx-exception trace is redacted
;; ---------------------------------------------------------------------------

(deftest error-arm-origin-event-and-fx-args-redacted-on-fx-exception
  (testing "when the localStorage fx throws, BOTH the origin event (:event slot,
            example-owned) AND the fx args (:rf.fx/args slot, framework-gated
            until rf2-6h3c02) on the always-on :rf.error/fx-handler-exception
            trace redact the token"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (submit! f)
      (let [traces (record-traces!)]
        (rf/dispatch-sync
          [:auth.login/succeeded success-reply]
          {:frame        f
           :fx-overrides {:auth.session/store
                          (fn [_ _] (throw (js/Error. "localStorage unavailable")))}})
        (rf/unregister-listener! :trace ::probe)
        (let [errs (->> @traces
                        (filter #(= :rf.error/fx-handler-exception (:operation %))))]
          (is (seq errs)
              "the throwing store fx emitted an :rf.error/fx-handler-exception trace")
          (doseq [ev errs]
            (is (not (contains-sentinel? (get-in ev [:tags :event])))
                "the origin event on the error trace redacts the token — the
                 :auth.login/succeeded registration classifies it")
            ;; rf2-6h3c02: the fx args on the production-survivable error trace
            ;; now redact off :auth.session/store's own :sensitive [[:token]].
            (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args :token]))
                "the store fx :token arg reads :rf/redacted in :rf.fx/args")
            (is (not (contains-sentinel? (:tags ev)))
                "the token appears nowhere raw across the error trace tags")))))))

;; ---------------------------------------------------------------------------
;; the two registrations own their classification (what the projector consumes)
;; ---------------------------------------------------------------------------

(deftest owners-declare-their-classification
  (testing "the reply event and the persistence fx each declare their own
            :sensitive path — the registration-owned classification the trace
            projector reads at egress"
    (is (= {:sensitive [[:value :token]]}
           (rf.classification/registration-classification :event :auth.login/succeeded))
        ":auth.login/succeeded owns [:value :token] — the reply's token slot")
    (is (= {:sensitive [[:token]]}
           (rf.classification/registration-classification :fx :auth.session/store))
        ":auth.session/store owns [:token] — its own transient fx arg")))
