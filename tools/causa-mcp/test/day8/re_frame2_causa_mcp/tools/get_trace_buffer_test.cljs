(ns day8.re-frame2-causa-mcp.tools.get-trace-buffer-test
  "Unit tests for the T-Insp-1 tool `get-trace-buffer` (rf2-8xzoe.14).

  These tests pin the three wire-pipeline mechanisms the tool layers
  on the runtime's raw response:

    1. **W-6 size elision** — the `:elided-large` envelope counter is
       stamped from the count of `{:rf.size/large-elided ...}` markers
       on the kept events.
    2. **B-1 privacy default-suppress** — `:sensitive? true` events are
       dropped from the response by default; `:include-sensitive? true`
       opts back in. The `:dropped-sensitive` counter rides on the
       envelope when non-zero.
    3. **W-1 token-cap overflow** — when the rendered envelope exceeds
       the per-call cap, the dispatcher in `tools.cljs` replaces it
       with the causa-spec-shaped `:rf.mcp/overflow` marker. (This
       test exercises the dispatcher seam by invoking through
       `tools/invoke` directly with a fixture runtime.)

  ## Test seam — fixture runtime via the registry handler

  We don't spin up a real nREPL socket. Each test constructs the tool's
  handler-arg shape (`conn`, `args`), and either:

    - calls `shape-envelope` directly (pure — no I/O) to pin the
      privacy / elision counter logic, or
    - rebinds `nrepl/cljs-eval-value` via `with-redefs` for the
      end-to-end tool-handler path."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.tools :as tools]
            [day8.re-frame2-causa-mcp.tools.get-trace-buffer :as gtb]))

;; ---------------------------------------------------------------------------
;; Stubs — use `set!` rather than `with-redefs` to avoid the rf2-wb06a
;; async-cleanup race (see `tools/pair2-mcp/test/.../invoke_test.cljs`
;; for the analysis). Fixtures restore pristine originals after each
;; test so cross-test leakage is impossible.
;; ---------------------------------------------------------------------------

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)

(defn- restore-stubs! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!))

(use-fixtures :each
  {:after (fn [] (restore-stubs!))})

(defn- stub-runtime!
  "Install fixture stubs that bypass the probe and resolve the eval to
  `runtime-env`. Returns nil; tests call this synchronously at the
  start of their `(async done ...)` block."
  [runtime-env]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! nrepl/cljs-eval-value
        (fn
          ([_ _ _]      (js/Promise.resolve runtime-env))
          ([_ _ _ _]    (js/Promise.resolve runtime-env)))))

;; ---------------------------------------------------------------------------
;; Public surface.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "tool ns lands the public surface the dispatcher consumes"
    (is (fn? gtb/get-trace-buffer-tool))
    (is (fn? gtb/build-form))
    (is (fn? gtb/shape-envelope))
    (is (map? gtb/descriptor))
    (is (= "get-trace-buffer" (:name gtb/descriptor)))))

(deftest registered-in-the-catalogue
  (testing "load-time register-tool! wires the handler + descriptor
            into the central registry so tools/invoke can dispatch"
    (is (some? (registry/handler-for "get-trace-buffer")))
    (is (= "get-trace-buffer" (:name (registry/descriptor-for "get-trace-buffer"))))))

;; ---------------------------------------------------------------------------
;; build-form — eval-form composition.
;; ---------------------------------------------------------------------------

(deftest build-form-calls-runtime-accessor
  (testing "build-form composes a (binding [origin :causa-mcp]
            (runtime/get-trace-buffer <opts>)) form — the runtime ns
            prefix, the opts map, and the binding wrapper are all
            present"
    (let [form (gtb/build-form {:op-type :event/dispatched
                                :include-sensitive? false
                                :include-large?     false})]
      (is (string? form))
      (is (.includes form "day8.re-frame2-causa.runtime/get-trace-buffer")
          "form addresses the runtime accessor by fully-qualified name")
      (is (.includes form ":causa-mcp")
          "form binds *current-origin* to :causa-mcp")
      (is (.includes form ":op-type :event/dispatched")
          "opts map rides through the form"))))

;; ---------------------------------------------------------------------------
;; shape-envelope — happy path.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-happy-path
  (testing "a runtime envelope with no sensitive / no large items flows
            through unchanged plus :total / :limit / :offset slots"
    (let [runtime-env {:ok?    true
                       :events [{:operation :event/dispatched :event [:foo]}
                                {:operation :sub/updated      :result :bar}]
                       :count  2}
          shaped      (gtb/shape-envelope runtime-env false 50 0)]
      (is (true? (:ok? shaped)))
      (is (= 2 (:count shaped)))
      (is (= 2 (:total shaped)))
      (is (= 50 (:limit shaped)))
      (is (= 0  (:offset shaped)))
      (is (= 2 (count (:events shaped))))
      (is (nil? (:dropped-sensitive shaped))
          "zero-drop common path carries no counter")
      (is (nil? (:elided-large shaped))
          "zero-elide common path carries no counter"))))

;; ---------------------------------------------------------------------------
;; B-1 privacy default-suppress.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-drops-sensitive-by-default
  (testing "B-1: :sensitive? true events are dropped from the response
            and the :dropped-sensitive counter rides on the envelope"
    (let [runtime-env {:ok?    true
                       :events [{:operation :event/dispatched}
                                {:operation :auth/sign-in :sensitive? true}
                                {:operation :nav/route}
                                {:operation :auth/sign-out :sensitive? true}]
                       :count  4}
          shaped      (gtb/shape-envelope runtime-env false 50 0)]
      (is (= 2 (:count shaped))
          "only the two non-sensitive events survive the strip-step")
      (is (= 4 (:total shaped))
          ":total reflects the pre-strip count")
      (is (= 2 (:dropped-sensitive shaped)))
      (is (every? #(not (:sensitive? %)) (:events shaped))))))

(deftest shape-envelope-include-sensitive-passes-everything
  (testing "B-1: :include-sensitive? true bypasses the strip-step;
            the counter is absent on the envelope"
    (let [runtime-env {:ok?    true
                       :events [{:operation :auth/sign-in :sensitive? true}
                                {:operation :nav/route}]
                       :count  2}
          shaped      (gtb/shape-envelope runtime-env true 50 0)]
      (is (= 2 (:count shaped)))
      (is (nil? (:dropped-sensitive shaped))
          "opt-in suppresses the counter — zero drop is the common path"))))

;; ---------------------------------------------------------------------------
;; W-6 size elision marker count.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-counts-elided-markers
  (testing "W-6: every {:rf.size/large-elided ...} marker the runtime
            walker emitted is counted onto :elided-large"
    (let [runtime-env {:ok?    true
                       :events [{:operation :event/dispatched
                                 :event [:upload {:body {:rf.size/large-elided
                                                         {:bytes 102400}}}]}
                                {:operation :sub/updated
                                 :result {:rf.size/large-elided {:bytes 51200}}}
                                {:operation :nav/route :event [:nav/route :home]}]
                       :count  3}
          shaped      (gtb/shape-envelope runtime-env false 50 0)]
      (is (= 2 (:elided-large shaped))
          "two of the three events carry a marker — one nested, one top-level"))))

;; ---------------------------------------------------------------------------
;; Pagination.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-limit-and-offset
  (testing "pagination: :offset skips N events and :limit caps the
            returned page size"
    (let [events     (vec (for [i (range 10)]
                            {:operation :event/dispatched :id i}))
          runtime-env {:ok? true :events events :count 10}
          shaped     (gtb/shape-envelope runtime-env false 3 4)]
      (is (= 3 (:count shaped)))
      (is (= 10 (:total shaped)))
      (is (= [4 5 6] (mapv :id (:events shaped)))))))

;; ---------------------------------------------------------------------------
;; Runtime-side failure passthrough.
;; ---------------------------------------------------------------------------

(deftest shape-envelope-passes-runtime-failure-through
  (testing "a structured runtime failure (e.g. :no-frame-resolved)
            surfaces verbatim — the tool layer does not rewrite it"
    (let [runtime-env {:ok? false :reason :no-frame-resolved
                       :hint "Pass :frame :foo or register at least one frame."}
          shaped      (gtb/shape-envelope runtime-env false 50 0)]
      (is (= runtime-env shaped)))))

;; ---------------------------------------------------------------------------
;; W-1 token-cap overflow via the dispatcher.
;; ---------------------------------------------------------------------------

(deftest dispatcher-applies-cap-on-large-payload
  (testing "W-1: when the rendered envelope exceeds max-tokens, the
            dispatcher in tools/invoke replaces the result with the
            :rf.mcp/overflow marker"
    (async done
      (let [;; ~10K characters → well over the 500-token floor that
            ;; we'll pass as max-tokens to force the overflow path.
            big-event   {:operation :event/dispatched
                         :event     [:big-payload (apply str (repeat 10000 \x))]}
            runtime-env {:ok? true :events [big-event] :count 1}]
        (stub-runtime! runtime-env)
        (-> (tools/invoke (atom {})
                          "get-trace-buffer"
                          #js {"max-tokens" 500}
                          nil)
            (.then (fn [^js result]
                     (let [text    (j/get (aget (j/get result :content) 0) :text)
                           payload (edn/read-string text)]
                       (is (contains? payload :rf.mcp/overflow)
                           "over-cap payload is replaced with the overflow marker")
                       (let [marker (:rf.mcp/overflow payload)]
                         (is (= :reached (:limit marker)))
                         (is (= 500 (:cap marker)))
                         (is (contains? token-cap/hint-vocabulary (:hint marker)))))
                     (done)))
            (.catch (fn [err]
                      (is false (str "tool failed: " (.-message err)))
                      (done))))))))

;; ---------------------------------------------------------------------------
;; End-to-end happy path via the dispatcher.
;; ---------------------------------------------------------------------------

(deftest dispatcher-happy-path
  (testing "tools/invoke routes get-trace-buffer through the registry
            and returns a stamped envelope — under-cap payload passes
            through unchanged"
    (async done
      (let [runtime-env {:ok?    true
                         :events [{:operation :event/dispatched :event [:foo]}
                                  {:operation :auth/sign-in :sensitive? true}]
                         :count  2}]
        (stub-runtime! runtime-env)
        (-> (tools/invoke (atom {}) "get-trace-buffer" #js {} nil)
            (.then (fn [^js result]
                     (let [text    (j/get (aget (j/get result :content) 0) :text)
                           payload (edn/read-string text)]
                       (is (true? (:ok? payload)))
                       (is (= 1 (:count payload))
                           "B-1: sensitive event was dropped")
                       (is (= 1 (:dropped-sensitive payload))))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Runtime-side failure path — probe rejection surfaces verbatim.
;; ---------------------------------------------------------------------------

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (testing "when the runtime preload is absent, the tool surfaces
            :runtime-not-preloaded with the operator setup hint"
    (async done
      (set! probe/ensure-runtime!
        (fn [_ _]
          (js/Promise.reject
            (ex-info "causa runtime not preloaded"
                     {:reason :runtime-not-preloaded
                      :hint   "setup-hint"}))))
      (-> (gtb/get-trace-buffer-tool (atom {}) #js {})
          (.then (fn [^js result]
                   (let [text    (j/get (aget (j/get result :content) 0) :text)
                         payload (edn/read-string text)]
                     (is (false? (:ok? payload)))
                     (is (= :runtime-not-preloaded (:reason payload)))
                     (is (= "setup-hint" (:hint payload))))
                   (done)))))))
