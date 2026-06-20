(ns re-frame2-pair-mcp.eval-cljs-test
  "Unit tests for the eval-cljs tool.

  ## Fail-loud on a runtime-absent build (`no-runtime-for-build` cluster)

  `eval-cljs` against a build with no live re-frame2-pair runtime must
  NOT collapse to `{:ok? true :value nil}` for forms like `(count ...)`
  that can never be nil — shadow's `cljs-eval` against a non-running
  build yields a blank value that would otherwise read as a genuine
  nil. The tool guards against that with two behaviours (shared with
  the bash shim via the same `probe` logic):
    1. Fail loud — preflight the runtime sentinel; a runtime-absent
       build returns `{:ok? false :reason :no-runtime-for-build ...}`
       enumerating the running builds, NEVER `:ok? true :value nil`.
    2. Auto-detect — when no `:build` arg is passed, detect the single
       running shadow build instead of blindly defaulting to `:app`.
       Exactly one running ⇒ use it; zero/many ⇒ fail loud listing them.

  The runtime calls these tests intercept:
    - `nrepl/jvm-eval`         — the `active-builds` enumeration (JVM-side).
    - `nrepl/cljs-eval-value`  — the sentinel probe + the actual eval.

  ## Await mode (`await-*` cluster)

  Opt-in `:await true` arg awaits Promise-returning forms server-side.
  The browser-side wrapper either short-circuits with
  `{:rf.mcp/await-direct v}` (non-thenable) or installs a mailbox slot
  and returns `{:rf.mcp/await-mailbox <id>}`; the server polls the
  mailbox on a sentinel of the latter shape. Tests stub the
  cljs-eval-value entry-point with a tiny in-test mailbox so the
  polling loop sees the same `:pending` → `:resolved` / `:rejected`
  transitions a real browser would emit, without spinning a runtime."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]))

;; eval-cljs defaults ON (the operator opts OUT via --no-eval). The
;; suite leaves the gate at its default so we test the resolution path,
;; not the gate (the gate's disabled-state coverage lives in
;; conformance_test + `gate-closed-rejects-before-touching-nrepl`
;; below).
(use-fixtures :each
  {:before (fn [] (eval-cljs/set-eval-allowed! true))
   :after  (fn [] (eval-cljs/set-eval-allowed! true))})

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

(defn- fresh-conn []
  (nrepl/make-conn 0 "127.0.0.1"))

(defn- sentinel-probe? [form-str]
  (and (string? form-str)
       (re-find #"__re_frame2_pair_runtime" form-str)))

(defn- with-stubbed-runtime!
  "Install stubs over the two nREPL entry points eval-cljs touches:

    - `nrepl/jvm-eval`         resolves `{:value <running-builds-edn>}`
      where `<running-builds-edn>` is the pr-str of `running-vec`
      (mimics shadow's `active-builds` JVM result).
    - `nrepl/cljs-eval-value`  resolves `runtime?` for the sentinel
      probe form, and `eval-value` for any other (the actual eval).

  Restores both in `.finally` so cleanup outlives async resolution."
  [{:keys [running-vec runtime? eval-value]} body-fn]
  (let [orig-jvm  nrepl/jvm-eval
        orig-cljs nrepl/cljs-eval-value
        ;; jvm-eval returns a CLJS map {:value "..."} in production; the
        ;; stub mirrors that shape so `running-builds`' `(:value resp)`
        ;; read works.
        jvm-stub  (fn
                    ([_conn _form] (js/Promise.resolve {:value (pr-str running-vec)}))
                    ([_conn _form _opts] (js/Promise.resolve {:value (pr-str running-vec)})))
        cljs-stub (fn
                    ([_conn _build form-str]
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value)))
                    ([_conn _build form-str _opts]
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value))))]
    (set! nrepl/jvm-eval jvm-stub)
    (set! nrepl/cljs-eval-value cljs-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-jvm-eval! jvm-stub orig-jvm)
                    (tu/restore-eval! cljs-stub orig-cljs))))))

;; ---------------------------------------------------------------------------
;; Fail-loud — a runtime-absent build never reports a silent nil.
;; ---------------------------------------------------------------------------

(deftest no-runtime-for-build-fails-loud
  ;; A build IS running but has no live runtime sentinel. The eval MUST
  ;; NOT return `:ok? true :value nil` — it must fail loud with
  ;; `:no-runtime-for-build` enumerating the running builds.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:app] :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)) "must NOT report success")
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [:app] (:running-builds edn)) "enumerates running builds")
                   (is (string? (:hint edn)))
                   (is (not (contains? edn :value))
                       "no :value slot — the eval did not run"))
                 (done))))))

(deftest no-running-build-fails-loud
  ;; Zero shadow builds running, no :build passed → can't auto-detect.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [] :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [] (:running-builds edn))))
                 (done))))))

(deftest multiple-builds-ambiguous-fails-loud
  ;; Two builds running, no :build passed → can't pick one. Error lists
  ;; both so the operator sees the right --build.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:app :examples/step-deck]
                                :runtime? true :eval-value 42}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [:app :examples/step-deck] (:running-builds edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Auto-detect — the single running build is used without a :build arg.
;; ---------------------------------------------------------------------------

(deftest auto-detects-single-running-build
  ;; No :build arg, exactly one running build with a live runtime →
  ;; eval runs against it; the resolved build is echoed back.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:examples/step-deck]
                                :runtime? true :eval-value 3}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= 3 (:value edn)))
                   (is (= :examples/step-deck (:build edn))
                       "auto-detected build echoed back"))
                 (done))))))

(deftest explicit-build-honoured-when-runtime-present
  ;; An explicit :build with a live runtime → used verbatim; no
  ;; auto-detect (running-vec deliberately differs from the requested
  ;; build to prove it isn't consulted on the happy path).
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:other]
                                :runtime? true :eval-value 99}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(+ 90 9)" :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= 99 (:value edn)))
                   (is (= :app (:build edn)) "explicit build used verbatim"))
                 (done))))))

(deftest explicit-build-with-no-runtime-fails-loud
  ;; An explicit :build that has no runtime sentinel → fail loud,
  ;; enumerating the builds that ARE running so the operator can switch.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:examples/step-deck]
                                :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(count [1 2 3])" :build "app"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= :app (:build edn)) "echoes the requested build")
                   (is (= [:examples/step-deck] (:running-builds edn))
                       "lists the builds that ARE running"))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Gate + arg validation still hold.
;; ---------------------------------------------------------------------------

(deftest gate-closed-rejects-before-touching-nrepl
  ;; The gate defaults ON. To exercise the disabled envelope we
  ;; flip the gate OFF (mimics `--no-eval` at launch), then restore the
  ;; default ON for downstream tests.
  (async done
    (eval-cljs/set-eval-allowed! false)
    (-> (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (= :rf.error/eval-cljs-disabled (:reason edn))))
                 (eval-cljs/set-eval-allowed! true)
                 (done))))))

(deftest missing-form-rejected
  (async done
    (-> (eval-cljs/eval-cljs-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (= :missing-form (:reason edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; `:timeout-ms` is validated as a positive-millisecond integer BEFORE
;; the await-mailbox poll loop. A non-numeric value (`"bogus"`) would
;; make `await-promise/poll-mailbox!`'s `(>= elapsed timeout-ms)`
;; deadline `(>= n NaN)` — never true — so an `:await` over a pending
;; Promise would poll FOREVER. The tool short-circuits to an honest
;; validation error WITHOUT touching nREPL (validation is the first `cond`
;; branch, ahead of the eval).
;; ---------------------------------------------------------------------------

(deftest bogus-timeout-ms-rejected-before-touching-nrepl
  (async done
    ;; No runtime stub installed; if validation didn't short-circuit, the
    ;; tool would reach the nREPL preflight and fail differently. A
    ;; fresh-conn with no live socket would NOT surface :invalid-numeric-arg.
    (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                  #js {:form "(+ 1 2)" :await true :timeout-ms "bogus"})
        (.then (fn [r]
                 (is (err? r) "malformed :timeout-ms surfaces as :isError true")
                 (let [edn (read-edn r)]
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "timeout-ms" (:arg edn))))
                 (done))))))

(deftest negative-timeout-ms-rejected
  (async done
    (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                  #js {:form "(+ 1 2)" :await true :timeout-ms -1})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "timeout-ms" (:arg edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Typed result envelope — the default (non-await) path wraps the form
;; so a genuine nil, an eval-error, and an unserializable value are
;; DISTINCT outcomes instead of collapsing to a bare null. The stub
;; returns the TAGGED `:rf.mcp/result` envelope the runtime would emit;
;; the server projects it.
;; ---------------------------------------------------------------------------

(deftest typed-genuine-nil-is-ok-true-value-nil
  ;; A form that genuinely returns nil → :ok? true :value nil. Distinct
  ;; from the no-runtime fail-loud path (which never reaches the eval).
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:app] :runtime? true
                                :eval-value {:rf.mcp/result :nil}}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(get {} :missing)" :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)) "a genuine nil is a SUCCESS")
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (nil? (:value edn)) "genuine nil rides back as :value nil")
                   (is (= :app (:build edn))))
                 (done))))))

(deftest typed-eval-error-surfaces-structured
  ;; A form that throws (e.g. an unresolved symbol) → :ok? false
  ;; :reason :rf.error/eval-cljs-threw — NOT a silent nil.
  (async done
    (-> (with-stubbed-runtime!
          {:running-vec [:app] :runtime? true
           :eval-value {:rf.mcp/result :eval-error
                        :reason :rf.error/eval-cljs-threw
                        :ex "#error {:message \"x is not defined\"}"
                        :message "x is not defined"}}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(x)" :build "app"})))
        (.then (fn [r]
                 (is (err? r) "an eval-error is an :isError envelope")
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/eval-cljs-threw (:reason edn)))
                   (is (= "x is not defined" (:message edn)))
                   (is (= :app (:build edn)) "build echoed on the error too"))
                 (done))))))

(deftest typed-unserializable-surfaces-with-preview
  ;; A form returning a #object / #js / Function → :ok? false
  ;; :reason :rf.error/unserializable with a :preview — NOT a null.
  (async done
    (-> (with-stubbed-runtime!
          {:running-vec [:app] :runtime? true
           :eval-value {:rf.mcp/result :unserializable
                        :type "object"
                        :preview "#object[Object [object console]]"}}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "js/console" :build "app"})))
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/unserializable (:reason edn)))
                   (is (= "object" (:type edn)))
                   (is (str/includes? (:preview edn) "#object")
                       "the preview shows WHAT the value was")
                   (is (string? (:hint edn)) "a corrective hint is offered"))
                 (done))))))

(deftest typed-value-rides-back-under-value
  ;; The happy path through the codec: a serializable value → :ok? true
  ;; :value v. Same shape callers always saw — additive, not a flip.
  (async done
    (-> (with-stubbed-runtime!
          {:running-vec [:app] :runtime? true
           :eval-value {:rf.mcp/result :value :value {:a 1 :b [2 3]}}}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "{:a 1 :b [2 3]}" :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= {:a 1 :b [2 3]} (:value edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; END-TO-END unresolved-symbol surfacing. Eval'ing a form with an
;; unresolved symbol (e.g. `re-frame.core/frame-db`, a fn that doesn't
;; exist) must NOT return `{:ok? true :value nil}` — that would silently
;; nil out the form and the agent would read the nil as a real value.
;;
;; Unlike the codec tests above (which stub `cljs-eval-value`, bypassing the
;; unwrap), this stubs ONE LAYER LOWER — `nrepl/cljs-eval` — so the REAL
;; `cljs-eval-value` unwrap runs end-to-end. We feed the exact shadow
;; response shape for an undeclared var (`{:results ["nil"] :err "<warning>"
;; ...}`) and assert the tool returns `:ok? false` carrying the compile
;; error, NOT `{:ok? true :value nil}`.
;; ---------------------------------------------------------------------------

(deftest unresolved-symbol-end-to-end-fails-loud
  (async done
    (let [orig-jvm  nrepl/jvm-eval
          orig-cljs nrepl/cljs-eval
          ;; active-builds enumeration (JVM-side) — one running build.
          jvm-stub  (fn
                      ([_ _]   (js/Promise.resolve {:value "[:app]"}))
                      ([_ _ _] (js/Promise.resolve {:value "[:app]"})))
          ;; The lower-level cljs-eval. The sentinel probe must report a
          ;; live runtime (so preflight passes); the wrapped user form
          ;; resolves to shadow's compile-warning shape (a "nil" result
          ;; sitting beside the undeclared-Var analyzer warning in :err).
          ;; Both arities spelled out — CLJS direct-arity dispatch calls
          ;; `...$arity$4`, which a rest-arg fn would not expose.
          cljs-resp (fn [form-str]
                      (if (sentinel-probe? form-str)
                        ;; runtime-preloaded? probe → true
                        {:value "{:results [\"true\"] :ns cljs.user}"}
                        ;; the actual eval → shadow undeclared-var shape
                        {:value (str "{:results [\"nil\"] "
                                     ":err \"WARNING: Use of undeclared Var "
                                     "re-frame.core/frame-db at line 1 <eval>\" "
                                     ":ns cljs.user}")}))
          cljs-stub (fn
                      ([_conn _build form-str]
                       (js/Promise.resolve (cljs-resp form-str)))
                      ([_conn _build form-str _opts]
                       (js/Promise.resolve (cljs-resp form-str))))]
      (set! nrepl/jvm-eval jvm-stub)
      (set! nrepl/cljs-eval cljs-stub)
      (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                    #js {:form "re-frame.core/frame-db" :build "app"})
          (.then (fn [r]
                   (is (err? r)
                       "an unresolved symbol is an isError envelope, not a silent success")
                   (let [edn (read-edn r)]
                     (is (false? (:ok? edn))
                         "MUST be :ok? false — never the silent {:ok? true :value nil}")
                     (is (not (contains? edn :value))
                         "no :value slot — the form did not produce a real value")
                     (is (= :rf.error/eval-cljs-compile-error (:reason edn))
                         "structured compile-error reason surfaces to the agent")
                     (is (re-find #"undeclared Var re-frame.core/frame-db" (:err edn))
                         "the analyzer warning text is carried through to the agent"))
                   (tu/restore-jvm-eval! jvm-stub orig-jvm)
                   (tu/restore-cljs-eval! cljs-stub orig-cljs)
                   (done))
                 (fn [e]
                   (tu/restore-jvm-eval! jvm-stub orig-jvm)
                   (tu/restore-cljs-eval! cljs-stub orig-cljs)
                   (is false (str "tool promise rejected: " e))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Await mode — the opt-in `:await true` arg awaits a
;; Promise-returning form's resolved value, surfaces rejections as
;; `:rf.error/eval-cljs-rejected`, surfaces unbounded waits as
;; `:rf.error/eval-cljs-timeout`.
;;
;; The eval form a real browser would receive is the `await-wrap-form`
;; — a `(let [v# <user-form>] ...)` wrapper that either:
;;   - synchronously returns `{:rf.mcp/await-direct v#}` on a
;;     non-thenable, OR
;;   - installs a mailbox slot and synchronously returns
;;     `{:rf.mcp/await-mailbox <id>}` on a thenable, then chains
;;     `.then`/`.catch` to write the resolution into the mailbox.
;;
;; The polling loop on the server then re-evaluates a `read-mailbox-form`
;; until the mailbox entry's :status flips off :pending.
;;
;; Our test stub plays both roles: it inspects the emitted form
;; (await-wrap vs. mailbox-read vs. mailbox-discard vs. sentinel-probe)
;; and resolves the appropriate canned shape, walking a small in-test
;; state machine to mimic resolve / reject / timeout.
;; ---------------------------------------------------------------------------

(defn- await-wrap-form?
  "True when the emitted form is the await wrapper. Discriminator:
  `:rf.mcp/await-mailbox` is emitted as part of the thenable-branch
  sentinel literal — present in the wrap form, NOT in the read or
  discard forms."
  [form-str]
  (and (string? form-str) (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str ":rf.mcp/await-mailbox")))

(defn- mailbox-read-form?
  "True when the emitted form is the mailbox-read poll form.
  Discriminator: `cljs.reader/read-string` is the EDN re-read of the
  mailbox value — present in the read form only."
  [form-str]
  (and (string? form-str) (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str "cljs.reader/read-string")))

(defn- mailbox-discard-form?
  "True when the emitted form is the post-timeout mailbox-discard
  form. Discriminator: contains `__rf2pair_await__` and `js-delete`
  but is NOT the read form (no `cljs.reader/read-string`)."
  [form-str]
  (and (string? form-str) (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str "js-delete")
       (not (str/includes? form-str "cljs.reader/read-string"))))

(defn- with-stubbed-await!
  "Like `with-stubbed-runtime!` but additionally walks an in-test
  mailbox state machine for the await wrapper + poll forms.

  `:wrap-result` is the canned synchronous return of the await wrapper —
  either `{:rf.mcp/await-direct v}` (fast-path passthrough) or
  `{:rf.mcp/await-mailbox <id>}` (kick the poll loop).

  `:poll-script` is a vector of canned mailbox-read returns, consumed
  in order — one per poll iteration. Use `[{:status :pending}
  {:status :pending} {:status :resolved :value 7}]` to assert the
  polling loop survives multiple `:pending` reads before resolving."
  [{:keys [wrap-result poll-script]} body-fn]
  (let [orig-jvm  nrepl/jvm-eval
        orig-cljs nrepl/cljs-eval-value
        poll-cur  (atom poll-script)
        jvm-stub  (fn
                    ([_conn _form] (js/Promise.resolve {:value "[:app]"}))
                    ([_conn _form _opts] (js/Promise.resolve {:value "[:app]"})))
        cljs-stub (fn
                    ([_conn _build form-str]
                     (js/Promise.resolve
                       (cond
                         (sentinel-probe? form-str)    true
                         (await-wrap-form? form-str)   wrap-result
                         (mailbox-read-form? form-str) (let [[head & tail] @poll-cur]
                                                         (when (seq tail) (reset! poll-cur tail))
                                                         head)
                         (mailbox-discard-form? form-str) nil
                         :else                         nil)))
                    ([_conn _build form-str _opts]
                     ;; The 4-arity passes through to the 3-arity logic.
                     (js/Promise.resolve
                       (cond
                         (sentinel-probe? form-str)    true
                         (await-wrap-form? form-str)   wrap-result
                         (mailbox-read-form? form-str) (let [[head & tail] @poll-cur]
                                                         (when (seq tail) (reset! poll-cur tail))
                                                         head)
                         (mailbox-discard-form? form-str) nil
                         :else                         nil))))]
    (set! nrepl/jvm-eval jvm-stub)
    (set! nrepl/cljs-eval-value cljs-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-jvm-eval! jvm-stub orig-jvm)
                    (tu/restore-eval! cljs-stub orig-cljs))))))

(deftest await-direct-passthrough
  ;; :await true on a form that returns a non-thenable: the wrapper's
  ;; synchronous arm fires and the server short-circuits with the
  ;; value, identical to :await false. No mailbox, no polling.
  (async done
    (-> (with-stubbed-await! {:wrap-result {:rf.mcp/await-direct 42}
                              :poll-script []}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form  "(+ 40 2)"
                                           :await true
                                           :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)) "direct passthrough is a success envelope")
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= 42 (:value edn)))
                   (is (= :app (:build edn))))
                 (done))))))

(deftest await-resolved-value
  ;; Thenable that resolves to a value after a single :pending read.
  ;; The wrapper returns the mailbox sentinel; the poll sees :pending
  ;; once, then :resolved with the EDN value.
  (async done
    (-> (with-stubbed-await! {:wrap-result {:rf.mcp/await-mailbox "await-test-1"}
                              :poll-script [{:status :pending}
                                            {:status :resolved :value {:hello "world"}}]}
          (fn []
            (eval-cljs/eval-cljs-tool
              (fresh-conn)
              #js {:form  "(-> (js/Promise.resolve {:hello \"world\"}) (.then identity))"
                   :await true
                   :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= {:hello "world"} (:value edn))
                       "resolved value surfaces under :value")
                   (is (= :app (:build edn))))
                 (done))))))

(deftest await-rejected-surfaces-structured
  ;; Thenable that rejects: server returns
  ;; {:ok? false :reason :rf.error/eval-cljs-rejected :rejection <pr-str>}
  (async done
    (-> (with-stubbed-await!
          {:wrap-result {:rf.mcp/await-mailbox "await-test-2"}
           :poll-script [{:status :rejected :rejection "#error {:message \"nope\"}"}]}
          (fn []
            (eval-cljs/eval-cljs-tool
              (fresh-conn)
              #js {:form  "(js/Promise.reject (ex-info \"nope\" {}))"
                   :await true
                   :build "app"})))
        (.then (fn [r]
                 (is (not (err? r))
                     "rejection is :ok? false but not isError (not a transport-layer error)")
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/eval-cljs-rejected (:reason edn)))
                   (is (= "#error {:message \"nope\"}" (:rejection edn))
                       "rejection text round-trips verbatim")
                   (is (= :app (:build edn))))
                 (done))))))

(deftest await-timeout-surfaces-structured
  ;; Thenable that never settles: the poll runs the clock out and the
  ;; server returns {:ok? false :reason :rf.error/eval-cljs-timeout
  ;; :timeout-ms n}. We pick a small timeout (75ms) so the test stays
  ;; sub-second.
  (async done
    (-> (with-stubbed-await!
          ;; Poll-script returns :pending forever — each read repeats
          ;; the head since we only consume on `(seq tail)`.
          {:wrap-result {:rf.mcp/await-mailbox "await-test-3"}
           :poll-script [{:status :pending}]}
          (fn []
            (eval-cljs/eval-cljs-tool
              (fresh-conn)
              #js {:form       "(js/Promise. (fn [_ _]))"
                   :await      true
                   :timeout-ms 75
                   :build      "app"})))
        (.then (fn [r]
                 (is (not (err? r)) "timeout is :ok? false but not a transport error")
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/eval-cljs-timeout (:reason edn)))
                   (is (= 75 (:timeout-ms edn)) "caller's timeout echoed back")
                   (is (= :app (:build edn))))
                 (done))))))

(deftest await-default-off-preserves-passthrough
  ;; Without :await, today's semantics still hold — the eval form is
  ;; sent verbatim (no wrap), the value comes back unchanged. Asserts
  ;; the wrap form is NOT in the stub's matched-form set so the
  ;; default path can never silently shift to await semantics.
  (async done
    (let [forms-seen (atom [])
          orig-cljs  nrepl/cljs-eval-value
          orig-jvm   nrepl/jvm-eval
          jvm-stub   (fn
                       ([_ _] (js/Promise.resolve {:value "[:app]"}))
                       ([_ _ _] (js/Promise.resolve {:value "[:app]"})))
          cljs-stub  (fn
                       ([_conn _build form-str]
                        (swap! forms-seen conj form-str)
                        (js/Promise.resolve (if (sentinel-probe? form-str) true 99)))
                       ([_conn _build form-str _opts]
                        (swap! forms-seen conj form-str)
                        (js/Promise.resolve (if (sentinel-probe? form-str) true 99))))]
      (set! nrepl/jvm-eval jvm-stub)
      (set! nrepl/cljs-eval-value cljs-stub)
      (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                    #js {:form "(+ 90 9)" :build "app"})
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= 99 (:value edn))))
                   (is (not-any? await-wrap-form? @forms-seen)
                       "default :await false MUST NOT emit the await wrapper")
                   (tu/restore-eval! cljs-stub orig-cljs)
                   (tu/restore-jvm-eval! jvm-stub orig-jvm)
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Frame targeting — `:frame` arg wraps the supplied form
;; in `(re-frame.core/with-frame <frame> <user-form>)` so subscribe /
;; dispatch inside the form target the named frame instead of the MCP
;; server's ambient :rf/default context.
;; ---------------------------------------------------------------------------

(defn- with-form-recorder!
  "Stub harness that records every form sent over nREPL. Promise-
  resolves the eval to `eval-value` (or `runtime?` for the sentinel
  probe). Restores in .finally so cleanup outlives async resolution."
  [{:keys [runtime? eval-value forms-atom]} body-fn]
  (let [orig-jvm  nrepl/jvm-eval
        orig-cljs nrepl/cljs-eval-value
        jvm-stub  (fn
                    ([_ _] (js/Promise.resolve {:value "[:app]"}))
                    ([_ _ _] (js/Promise.resolve {:value "[:app]"})))
        cljs-stub (fn
                    ([_conn _build form-str]
                     (swap! forms-atom conj form-str)
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value)))
                    ([_conn _build form-str _opts]
                     (swap! forms-atom conj form-str)
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value))))]
    (set! nrepl/jvm-eval jvm-stub)
    (set! nrepl/cljs-eval-value cljs-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-jvm-eval! jvm-stub orig-jvm)
                    (tu/restore-eval! cljs-stub orig-cljs))))))

(deftest frame-arg-wraps-form-in-with-frame
  ;; With :frame :rf/xray the user form is sent inside
  ;; (re-frame.core/with-frame :rf/xray <form>), so subscribe /
  ;; dispatch inside resolve to :rf/xray.
  (async done
    (let [forms (atom [])]
      (-> (with-form-recorder! {:runtime? true :eval-value 42 :forms-atom forms}
            (fn []
              (eval-cljs/eval-cljs-tool
                (fresh-conn)
                #js {:form "@(re-frame.core/subscribe [:state])"
                     :frame ":rf/xray"
                     :build "app"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= 42 (:value edn)))
                     (is (= :rf/xray (:frame edn))
                         "frame echoed back on the envelope"))
                   (let [non-probe-forms (->> @forms
                                              (remove sentinel-probe?))]
                     (is (some #(re-find #"re-frame\.core/with-frame :rf/xray" %)
                               non-probe-forms)
                         "user form is wrapped in with-frame :rf/xray")
                     (is (some #(re-find #"re-frame\.core/subscribe \[:state\]" %)
                               non-probe-forms)
                         "original user form rides inside the wrap"))
                   (done)))))))

(deftest frame-arg-omitted-leaves-form-unwrapped
  ;; Without :frame the form crosses the wire verbatim — no with-
  ;; frame wrap. Pins the no-regression contract.
  (async done
    (let [forms (atom [])]
      (-> (with-form-recorder! {:runtime? true :eval-value 99 :forms-atom forms}
            (fn []
              (eval-cljs/eval-cljs-tool
                (fresh-conn)
                #js {:form "(+ 90 9)" :build "app"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= 99 (:value edn)))
                     (is (not (contains? edn :frame))
                         "no :frame slot in the envelope when arg omitted"))
                   (let [non-probe-forms (->> @forms
                                              (remove sentinel-probe?))]
                     (is (not-any? #(re-find #"re-frame\.core/with-frame" %)
                                   non-probe-forms)
                         "omitted :frame MUST NOT wrap the form"))
                   (done)))))))

(deftest frame-arg-accepts-bare-name-and-edn-shape
  ;; `args/->frame-keyword` accepts both bare names ("rf/xray") and
  ;; EDN-shaped strings (":rf/xray"). Either should produce the same
  ;; with-frame wrap.
  (async done
    (let [forms (atom [])]
      (-> (with-form-recorder! {:runtime? true :eval-value 1 :forms-atom forms}
            (fn []
              (eval-cljs/eval-cljs-tool
                (fresh-conn)
                #js {:form "(+ 1 0)" :frame "rf/xray" :build "app"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (= :rf/xray (:frame edn))
                         "bare name coerced to keyword"))
                   (let [non-probe-forms (->> @forms (remove sentinel-probe?))]
                     (is (some #(re-find #"with-frame :rf/xray" %)
                               non-probe-forms)
                         "wrap uses the coerced keyword"))
                   (done)))))))

(deftest frame-arg-composes-with-await
  ;; :frame and :await compose orthogonally — the with-frame wrap is
  ;; the outer-most form, the await wrapper rides inside (the
  ;; mailbox sentinel survives the with-frame). Tests that BOTH the
  ;; mailbox sentinel keyword AND the with-frame wrap appear in the
  ;; first form sent over nREPL.
  (async done
    (let [forms     (atom [])
          orig-jvm  nrepl/jvm-eval
          orig-cljs nrepl/cljs-eval-value
          jvm-stub  (fn
                      ([_ _] (js/Promise.resolve {:value "[:app]"}))
                      ([_ _ _] (js/Promise.resolve {:value "[:app]"})))
          cljs-stub (fn
                      ([_conn _build form-str]
                       (swap! forms conj form-str)
                       (js/Promise.resolve
                         (cond
                           (sentinel-probe? form-str)    true
                           (await-wrap-form? form-str)   {:rf.mcp/await-direct 7}
                           :else                          nil)))
                      ([_conn _build form-str _opts]
                       (swap! forms conj form-str)
                       (js/Promise.resolve
                         (cond
                           (sentinel-probe? form-str)    true
                           (await-wrap-form? form-str)   {:rf.mcp/await-direct 7}
                           :else                          nil))))]
      (set! nrepl/jvm-eval jvm-stub)
      (set! nrepl/cljs-eval-value cljs-stub)
      (-> (eval-cljs/eval-cljs-tool
            (fresh-conn)
            #js {:form "(+ 3 4)" :await true :frame ":rf/xray" :build "app"})
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= 7 (:value edn))))
                   (let [wrap (first (filter await-wrap-form? @forms))]
                     (is (string? wrap) "await wrap form was emitted")
                     (is (re-find #"with-frame :rf/xray" wrap)
                         "with-frame wraps the await wrapper too — both
                          surfaces appear in the same emitted form"))
                   (tu/restore-eval! cljs-stub orig-cljs)
                   (tu/restore-jvm-eval! jvm-stub orig-jvm)
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Typed envelope — default path wraps the form. Lives down
;; here because it reuses `with-form-recorder!` (defined above in the
;; frame-targeting section).
;; ---------------------------------------------------------------------------

(deftest typed-default-path-wraps-the-form
  ;; The default (non-await) path MUST wrap the user form in the result
  ;; codec so the runtime classifies the outcome. Pin that the wrapped
  ;; form routes through read-string (the serializability probe) and
  ;; still carries the user form.
  (async done
    (let [forms (atom [])]
      (-> (with-form-recorder! {:runtime? true
                                :eval-value {:rf.mcp/result :value :value 5}
                                :forms-atom forms}
            (fn []
              (eval-cljs/eval-cljs-tool (fresh-conn)
                                        #js {:form "(+ 2 3)" :build "app"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [non-probe (->> @forms (remove sentinel-probe?))]
                     (is (some #(str/includes? % "cljs.reader/read-string") non-probe)
                         "the wrap round-trip-probes serializability")
                     (is (some #(str/includes? % "(+ 2 3)") non-probe)
                         "the user form rides inside the wrap"))
                   (done)))))))
