(ns re-frame2-pair-mcp.read-sub-test
  "Unit tests for the read-sub tool — the validated one-shot
  subscription read with no-silent-swallow parity with dispatch.

  Two surfaces are pinned:

    1. The arg-parse gate (server-side, no socket): the `sub` arg MUST
       be an EDN vector — host-form source, a bare keyword, a map, and
       unreadable input each return a STRUCTURED error envelope, never
       reach the runtime.
    2. The structured-result contract: a runtime `:ok? false` envelope
       (unknown-id / ambiguous-frame / sub-error) rides back as an
       :isError envelope — NEVER a silent nil success. A hit rides back
       with the value + :elision flag.

  The runtime `read-sub!` validation + subscribe-and-deref is exercised
  by the bb structural pin (tests/runtime/read_sub_test.clj in the
  skill); here we stub `cljs-eval-value` and pin the wire-boundary
  behaviour."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-sub :as read-sub]))

;; Stub lifetime is fixture-scoped, not Promise-chain-scoped — see
;; orient_test for the rationale. Each test installs its stub via a
;; bare `set!`; the `:after` fixture restores the pristine original
;; captured at ns-load. Closes the cross-test `connect EADDRNOTAVAIL`
;; race a `.finally`-scoped restore otherwise opens.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    ;; Pretend the preload is confirmed so probe resolves synchronously
    ;; and we exercise the form-building / result-shaping path.
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; Arg-parse gate — the data-not-source boundary (mirrors dispatch).
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source
  ;; Host-form source must NOT reach the runtime — the parser reads it
  ;; as a list, the vector-check fails, the runtime is never contacted.
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "(rf/subscribe [:pwn])"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-a-sub-vector (:reason edn)))
                   (is (= :list (:parsed-type edn))))
                 (done))))))

(deftest rejects-bare-keyword
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub ":current-user"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-a-sub-vector (:reason edn)))
                   (is (= :keyword (:parsed-type edn))))
                 (done))))))

(deftest rejects-scalar
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "42"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-a-sub-vector (:reason edn)))
                   (is (= :scalar (:parsed-type edn))))
                 (done))))))

(deftest rejects-unreadable-edn
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:foo"})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-sub-edn (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-missing-sub
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-sub (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-blank-sub
  (async done
    (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "   "})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-sub (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Acceptance — the EDN vector reaches the runtime read-sub! as data.
;; ---------------------------------------------------------------------------

(defn- stub-eval!
  "Install a `cljs-eval-value` stub via a bare `set!` (NO `.finally` —
  cleanup is the `:after` fixture's job). Records the READ-SUB form
  string into `captured*` and resolves it with `canned`.

  read-sub's prelude fires up to three evals: the preload-probe sentinel
  (`__re_frame2_pair_runtime`), the `configure-raw-state!` signal-runtime
  form, and the actual `read-sub!` form. The stub answers the probe with
  `true` and the signal with `nil` (so the preflight + raw-state gate
  pass regardless of conn-cache / module-atom state across interleaved
  tests), captures ONLY the `read-sub!` form, and hands every non-prelude
  form `canned` — so `@captured*` is never clobbered by a prelude eval.
  `captured*` may be nil when the form isn't asserted."
  [captured* canned]
  (let [respond (fn [form]
                  (cond
                    (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)

                    (and (string? form) (re-find #"configure-raw-state!" form))
                    (js/Promise.resolve nil)

                    :else
                    (do (when (and captured* (string? form) (re-find #"read-sub!" form))
                          (reset! captured* form))
                        (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(deftest accepts-edn-vector-and-calls-read-sub
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured {:ok? true :query-v [:current-user] :frame :rf/default :value {:id 42}})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:current-user]"})
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [form @captured]
                     (is (string? form))
                     (is (re-find #"read-sub!" form)
                         "routes through the runtime read-sub! fn")
                     (is (re-find #"\[:current-user\]" form)
                         "the query-v rides as an EDN literal — DATA")
                     (is (str/includes? form "elide-wire-value")
                         "the value is elided server-side by default"))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= [:current-user] (:query-v edn)))
                     (is (= {:id 42} (:value edn)))
                     (is (true? (:elision edn))))
                   (done)))))))

(deftest accepts-sub-with-args
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured {:ok? true :query-v [:user/by-id 42] :frame :rf/default :value {:name "Ada"}})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:user/by-id 42]"})
          (.then (fn [r]
                   (is (not (err? r)))
                   (is (re-find #"\[:user/by-id 42\]" @captured))
                   (is (= [:user/by-id 42] (:query-v (read-result-text r))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; No-silent-swallow — structured runtime failures surface as :isError,
;; never a silent nil success.
;; ---------------------------------------------------------------------------

(deftest unknown-sub-id-surfaces-as-error-with-nearest
  ;; A typo'd sub-id must NOT silently subscribe to a non-existent sub
  ;; and return nil. The runtime read-sub! validates first and returns
  ;; :unknown-id + :nearest; the tool surfaces it as an :isError
  ;; envelope.
  (async done
    (let [runtime-result {:ok? false :reason :unknown-id :kind :sub
                          :id :current-userr :query-v [:current-userr]
                          :nearest [:current-user] :subscribed? false
                          :hint "unknown :sub :current-userr; did you mean :current-user?"}]
      (stub-eval! nil runtime-result)
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:current-userr]"})
          (.then (fn [r]
                   (is (err? r) "unknown sub-id ⇒ :isError, never a silent nil")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :unknown-id (:reason edn)))
                     (is (= [:current-user] (:nearest edn))
                         "nearest matches carried for a corrective retry")
                     (is (= [:current-userr] (:query-v edn))
                         "the resolved query-v is echoed even on the miss")
                     (is (false? (:subscribed? edn))))
                   (done)))))))

(deftest ambiguous-frame-surfaces-as-error
  (async done
    ;; The runtime refuses with the enriched ambiguous-frame envelope
    ;; (operation, query, available frames, current pin, fix-hint). The
    ;; tool wrap must carry every slot back to the agent verbatim.
    (let [runtime-result {:ok?              false
                          :reason           :ambiguous-frame
                          :operation        :read-sub
                          :query            [:cart/total]
                          :query-v          [:cart/total]
                          :available-frames [:rf/default :stories]
                          :selected-frame   nil
                          :hint             "pass `frame` (one of [:rf/default :stories]) or pin one"}]
      (stub-eval! nil runtime-result)
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:cart/total]"})
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :ambiguous-frame (:reason edn)))
                     (is (= :read-sub (:operation edn)))
                     (is (= [:rf/default :stories] (:available-frames edn))
                         "the candidate frames ride back so the agent can pin one"))
                   (done)))))))

(deftest sub-error-surfaces-as-error-not-nil
  ;; A sub handler that throws while computing must return a structured
  ;; :sub-error — not a bare nil.
  (async done
    (let [runtime-result {:ok? false :reason :sub-error :query-v [:boom]
                          :frame :rf/default :message "boom"
                          :hint "the subscription handler threw..."}]
      (stub-eval! nil runtime-result)
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:boom]"})
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :sub-error (:reason edn)))
                     (is (= "boom" (:message edn))))
                   (done)))))))

(deftest frame-arg-routes-to-named-frame
  ;; The colon-prefixed frame arg coerces to the well-formed keyword and
  ;; rides into the runtime read-sub! call as a second arg.
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured {:ok? true :query-v [:state] :frame :rf/xray :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:state]" :frame ":rf/xray"})
          (.then (fn [_]
                   ;; The inner runtime form is (rt/read-sub! [:state] :rf/xray);
                   ;; elision wraps it in a let. Assert the frame keyword is
                   ;; well-formed (not the malformed ::rf/xray).
                   (is (re-find #"read-sub! \[:state\] :rf/xray" @captured)
                       "frame routes to the well-formed :rf/xray keyword")
                   (is (not (re-find #"::rf/xray" @captured))
                       "no malformed double-colon keyword in the form")
                   (done)))))))
