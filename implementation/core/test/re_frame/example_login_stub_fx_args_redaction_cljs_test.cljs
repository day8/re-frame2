(ns re-frame.example-login-stub-fx-args-redaction-cljs-test
  "Framework-tree security regression for the login example's DEMO HTTP STUB —
   the `:auth.login.demo/managed-stub` reg-fx owned by
   examples/core/login/model.cljs (rf2-a6zmmu).

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the login model owner
   (`login.model`) so its events / subs / machine / schemas / demo-stub register
   at ns-load, then drives a real submit through the stub. Sibling of
   `re-frame.example-login-success-token-cljs-test` (the RETURN-token half) and
   `re-frame.example-login-form-slice-cljs-test` (the password-egress half); this
   ns pins the DEMO-STUB fx-args egress.

   THE LEAK THIS PINS. The three login examples run against a demo backend by
   REMAPPING `:rf.http/managed` → `:auth.login.demo/managed-stub` through the
   shared `frame-config` `:fx-overrides`. When the override fires, `handle-one-fx`
   stamps the always-emitted `:rf.fx/handled` trace with the RESOLVED stub id and
   the RAW fx args — and the classification projector redacts `:rf.fx/args` off
   the RESOLVED fx's OWN registration `:sensitive` (post-rf2-6h3c02). The real
   `:rf.http/managed` handler's `:sensitive? true` request-body scrub is
   BYPASSED by the override, so unless the stub declares its own `:sensitive`,
   the plaintext password in the request body rides RAW in the stub's
   `:rf.fx/handled` trace — a credential leaking onto the one wire every tool
   reads. The fix is the stub reg-fx declaring `:sensitive [[:request :body
   :password]]`, so the projector redacts it there too.

   Distinct from rf2-j538f7.30 (the reply/effect token leak) and rf2-6h3c02 (the
   projector walking every fx-arg slot): this is the demo stub missing its OWN
   `:sensitive` declaration — the registration the projector consumes."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.classification :as classification]
            [re-frame.privacy :as privacy]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; login.model pulls these transitively; require here so the ns is
            ;; self-sufficient (mirrors the sibling login test namespaces).
            [re-frame.schemas]
            [re-frame.machines]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; A UNIQUE sentinel password — a valid Credentials password (>= 8 chars) that
;; appears nowhere else in the source or the framework, so a recursive scan for
;; it across the trace stream can only be hitting THIS submit's credential.
(def sentinel "PW-STUB-ARGS-SENTINEL-4c1f9e")

(defn- contains-sentinel?
  "True when `x` contains the sentinel password ANYWHERE in a nested data
   structure — the recursive scan an off-box shipper / dev tool would apply."
  [x]
  (cond
    (string? x) (not= -1 (.indexOf x sentinel))
    (map? x)    (boolean (some contains-sentinel? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some contains-sentinel? x))
    :else       false))

(defn- record-traces! [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

(defn- seed+submit!
  "Boot the slice, type a valid draft carrying the sentinel password, then submit
   through the frame's demo-stub override — the SAME `:fx-overrides` remap the
   shared `frame-config` installs, so `:rf.http/managed` resolves to
   `:auth.login.demo/managed-stub` and the stub actually fires."
  [f]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email "alice@example.com"] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-password {:value sentinel}] {:frame f})
  (rf/dispatch-sync [:auth.login/submit-form]
                    {:frame        f
                     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}))

;; ---------------------------------------------------------------------------
;; the stub owns its classification (what the projector consumes)
;; ---------------------------------------------------------------------------

(deftest stub-declares-its-own-sensitive-request-body-password
  (testing "the demo stub reg-fx declares :sensitive [[:request :body :password]]
            — the registration-owned classification the trace projector reads to
            redact the RESOLVED fx's args when the override bypasses the real
            managed-HTTP handler's :sensitive? scrub"
    (is (= {:sensitive [[:request :body :password]]}
           (classification/registration-classification :fx :auth.login.demo/managed-stub))
        ":auth.login.demo/managed-stub owns [:request :body :password]")))

;; ---------------------------------------------------------------------------
;; the stub's :rf.fx/handled trace redacts the request-body password
;; ---------------------------------------------------------------------------

(deftest stub-fx-handled-trace-redacts-request-body-password
  (testing "on a real submit routed through the demo stub, the plaintext password
            in the request body is NOT raw in the stub's :rf.fx/handled trace —
            it reads :rf/redacted off the stub's own :sensitive declaration
            (rf2-a6zmmu)"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (let [traces (record-traces! ::probe)]
        (seed+submit! f)
        (rf/unregister-listener! :trace ::probe)
        (let [handled (->> @traces
                           (filter #(= :auth.login.demo/managed-stub
                                       (get-in % [:tags :rf.fx/id]))))]
          (is (seq handled)
              "the demo stub emitted a :rf.fx/handled trace (the override fired
               and the fx actually ran)")
          (doseq [ev handled]
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args :request :body :password]))
                "the request-body password reads :rf/redacted in the stub's
                 :rf.fx/handled :rf.fx/args slot")
            ;; shape retained — the non-secret sibling and the url are still there
            (is (= "alice@example.com"
                   (get-in ev [:tags :rf.fx/args :request :body :email]))
                "shape retained — the non-secret email rides through unredacted")
            (is (= "/api/login"
                   (get-in ev [:tags :rf.fx/args :request :url]))
                "shape retained — the request url is still visible")
            (is (not (contains-sentinel? (:tags ev)))
                "the plaintext password appears NOWHERE raw in the stub's
                 :rf.fx/handled trace tags")))))))
