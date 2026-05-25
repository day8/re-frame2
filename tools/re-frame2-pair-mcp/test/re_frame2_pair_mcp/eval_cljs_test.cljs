(ns re-frame2-pair-mcp.eval-cljs-test
  "Unit tests for the eval-cljs tool.

  ## rf2-ivlb3 (`no-runtime-for-build-fails-loud` cluster)

  THE BUG: `eval-cljs` against a build with no live re-frame2-pair
  runtime returned `{:ok? true :value nil}` for EVERY form — including
  `(count ...)`, which can never be nil — because shadow's `cljs-eval`
  against a non-running build yields a blank value that
  `cljs-eval-value` reads as nil, indistinguishable from a genuine nil.
  ~30 min of dead-end debugging in the wild.

  THE FIX (shared with the bash shim via the same `probe` logic):
    1. Fail loud — preflight the runtime sentinel; a runtime-absent
       build returns `{:ok? false :reason :no-runtime-for-build ...}`
       enumerating the running builds, NEVER `:ok? true :value nil`.
    2. Auto-detect — when no `:build` arg is passed, detect the single
       running shadow build instead of blindly defaulting to `:app`.
       Exactly one running ⇒ use it; zero/many ⇒ fail loud listing them.

  The runtime calls these tests intercept:
    - `nrepl/jvm-eval`         — the `active-builds` enumeration (JVM-side).
    - `nrepl/cljs-eval-value`  — the sentinel probe + the actual eval.

  ## rf2-xn4f9 (`await-*` cluster)

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

;; eval-cljs defaults ON post-rf2-a0z0h (the operator opts OUT via
;; --no-eval). The suite leaves the gate at its default so we test the
;; resolution path, not the gate (the gate's disabled-state coverage
;; lives in conformance_test + `gate-closed-rejects-before-touching-nrepl`
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
                    (set! nrepl/jvm-eval orig-jvm)
                    (set! nrepl/cljs-eval-value orig-cljs))))))

;; ---------------------------------------------------------------------------
;; Fail-loud — the headline regression (rf2-ivlb3).
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
  ;; Default ON post-rf2-a0z0h. To exercise the disabled envelope we
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
;; Await mode (rf2-xn4f9) — the opt-in `:await true` arg awaits a
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
  mailbox state machine for the await wrapper + poll forms (rf2-xn4f9).

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
                    (set! nrepl/jvm-eval orig-jvm)
                    (set! nrepl/cljs-eval-value orig-cljs))))))

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
          orig-jvm   nrepl/jvm-eval]
      (set! nrepl/jvm-eval
            (fn
              ([_ _] (js/Promise.resolve {:value "[:app]"}))
              ([_ _ _] (js/Promise.resolve {:value "[:app]"}))))
      (set! nrepl/cljs-eval-value
            (fn
              ([_conn _build form-str]
               (swap! forms-seen conj form-str)
               (js/Promise.resolve (if (sentinel-probe? form-str) true 99)))
              ([_conn _build form-str _opts]
               (swap! forms-seen conj form-str)
               (js/Promise.resolve (if (sentinel-probe? form-str) true 99)))))
      (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                    #js {:form "(+ 90 9)" :build "app"})
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-edn r)]
                     (is (true? (:ok? edn)))
                     (is (= 99 (:value edn))))
                   (is (not-any? await-wrap-form? @forms-seen)
                       "default :await false MUST NOT emit the await wrapper")
                   (set! nrepl/cljs-eval-value orig-cljs)
                   (set! nrepl/jvm-eval orig-jvm)
                   (done)))))))
