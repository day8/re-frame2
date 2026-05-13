(ns re-frame.ssr-teardown-load-test
  "Per-request SSR frame teardown — load + memory-hygiene audit (rf2-fcj33).

  Background. A long-running server process serving SSR requests at high
  rate is the canonical re-frame2 SSR shape: every HTTP request creates a
  per-request server frame, drains, renders, and destroys the frame. The
  per-request lifecycle MUST return the runtime to the same state it was
  in before the request — any accumulation, however slow, compounds at
  request-rate and ships an SSR-broken app.

  Per Spec 011 §Per-request frame teardown contract the framework owns a
  small set of per-frame allocation sites:

    1. The frame record (app-db, router queue, drain-lock, sub-cache,
       lifecycle, config) — owned by `re-frame.frame/frames`.
    2. HTTP response accumulator — owned by
       `re-frame.ssr/response-slots`, a defonce side-channel atom keyed
       by frame-id (rf2-jbcmt moved this off app-db to plug a hydration-
       payload leak + per-fx full-app-db swap).
    3. Per-frame pending-error-traces buffer — owned by
       `re-frame.ssr/pending-error-traces`, a defonce side-channel atom
       keyed by frame-id.
    4. Per-frame HTTP request slot — owned by
       `re-frame.ssr/request-slots`, a defonce side-channel atom keyed
       by frame-id.
    5. Per-frame epoch ring buffer — owned by `re-frame.epoch`, a
       defonce side-channel atom keyed by frame-id (cleaned by the
       `:epoch/on-frame-destroyed` late-bind hook).

  This test drives the documented per-request SSR flow N times against
  the same process and asserts:

    a. After every iteration, `frame/frames` is back to baseline (zero
       leftover per-request frames). Verifies the frame record itself
       releases.
    b. After every iteration, the SSR side-channel atoms
       (`pending-error-traces`, `request-slots`, `response-slots`) are
       back to baseline. Verifies the `:ssr/on-frame-destroyed` hook
       (rf2-fcj33 / rf2-jbcmt) fires and clears every slot.
    c. After a triggered GC, the JVM heap delta is small relative to
       the total bytes the test churned — proves no large object graph
       is retained past frame destruction.

  Marked `^:slow` so the default test gate skips it; CI runs it via the
  `:slow-test` alias (see `:test` alias filter in deps.edn).

  Iteration counts:
    - quick smoke (in this file): 2_000 requests.
    - manual stress (REPL):        increase via the `(load-test N)`
                                   helper. The harness scales linearly;
                                   10_000 finishes in ~5s on a recent
                                   laptop."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.ssr              :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

;; ---- runtime reset --------------------------------------------------------
;;
;; The canonical reset-runtime fixture lives in `re-frame.ssr.test-fixture`
;; (rf2-i3qc0). One source of truth for the registrar/side-channel/ns-reload
;; cycle every ssr-artefact JVM test runs between :each.

(use-fixtures :each tf/reset-runtime)

;; ---- side-channel probes --------------------------------------------------

(defn- pending-error-traces-atom
  "Return the `re-frame.ssr/pending-error-traces` atom. It's `^:private`
  so we resolve the Var reflectively; its contents are an observable
  property of the teardown contract (the per-frame buffer the
  projector drains)."
  []
  (deref (or (resolve 're-frame.ssr/pending-error-traces)
             (throw (ex-info "Cannot resolve re-frame.ssr/pending-error-traces — ns layout changed?"
                             {})))))

(defn- pending-error-traces-snapshot
  "Snapshot the current value of the per-frame error-trace buffer atom."
  []
  @(pending-error-traces-atom))

(defn- request-slots-atom
  "Return the `re-frame.ssr/request-slots` atom. Per rf2-i3qc0 this is
  `^:private` at the façade (symmetric with `pending-error-traces` and
  `response-slots`); resolve via the producing sub-namespace so the
  load-test still observes the teardown contract."
  []
  (deref (or (resolve 're-frame.ssr.request/request-slots)
             (throw (ex-info "Cannot resolve re-frame.ssr.request/request-slots — ns layout changed?"
                             {})))))

(defn- request-slots-snapshot
  "Snapshot the current value of the per-frame request-slot atom."
  []
  @(request-slots-atom))

(defn- response-slots-atom
  "Return the `re-frame.ssr/response-slots` atom. It's `^:private` at the
  façade (per Spec 011 §Response storage substrate, rf2-jbcmt — the
  accumulator's framework-private side-channel slot) so we resolve the
  Var reflectively, like `pending-error-traces`."
  []
  (deref (or (resolve 're-frame.ssr/response-slots)
             (throw (ex-info "Cannot resolve re-frame.ssr/response-slots — ns layout changed?"
                             {})))))

(defn- response-slots-snapshot
  "Snapshot the current value of the per-frame response-accumulator atom."
  []
  @(response-slots-atom))

(defn- non-default-frame-ids
  "Every frame-id currently registered, minus `:rf/default`. Per-request
  frames are gensym'd under `:rf.frame/*`; if any survive past their
  request's `destroy-frame!`, the count grows here."
  []
  (disj (frame/frame-ids) :rf/default))

;; ---- the per-request SSR flow under test ---------------------------------

(defn- one-request!
  "Drive one request through the SSR runtime end-to-end:

    1. make-frame with :platform :server and a synthetic :on-create.
    2. set-request! populates the per-frame request slot (Ring-adapter
       parity — exercises the side-channel that needs cleanup).
    3. Drain settles synchronously via dispatch-sync.
    4. ssr/get-response flushes the response accumulator.
    5. render-to-string against a registered view emits HTML.
    6. ssr/clear-request! is INTENTIONALLY omitted here so the
       `:ssr/on-frame-destroyed` hook is the only path that clears the
       slot — the contract is that destroy-frame is sufficient even
       when the host adapter forgets to clear inline.
    7. destroy-frame!

  Returns the rendered HTML for sanity-check assertions."
  [i]
  (let [server-frame
        (rf/make-frame
          {:doc       (str "load-test request " i)
           :platform  :server
           :on-create [:load-test/server-init {:i i}]})]
    ;; Step 2 — populate the per-frame request slot. Exercises the SSR
    ;; side-channel atom we just wired the destroy hook for.
    (ssr/set-request! server-frame
                      {:uri            (str "/load/" i)
                       :request-method :get
                       :headers        {"user-agent" "load-test"}})
    (let [html (rf/with-frame server-frame
                 (rf/render-to-string [:load-test/page] {:emit-hash? true}))]
      ;; Step 4 — flush the response accumulator (also triggers any
      ;; pending-error-trace drain).
      (ssr/get-response server-frame)
      ;; Step 7 — destroy. Intentionally NO clear-request! call.
      (rf/destroy-frame server-frame)
      html)))

(defn- install-registry!
  "Register the events + view the load test uses. Called once per test;
  the runtime reset between tests wipes everything.

  `:load-test/server-init` fires `:rf.server/set-header` so each request
  writes to `response-slots` — exercises the side-channel that needs
  cleanup on frame destroy (per Spec 011 §Response storage substrate,
  rf2-jbcmt)."
  []
  (rf/reg-event-fx :load-test/server-init
    {:platforms #{:server}}
    (fn [{:keys [db]} [_ {:keys [i]}]]
      {:db (assoc db :i i :rf/route {:id :route/load})
       :fx [[:rf.server/set-header {:name "X-Load-Test" :value (str i)}]]}))
  (rf/reg-view* :load-test/page
    (fn []
      [:div.page
       [:h1 "Load test request"]
       [:p "Some content"]])))

;; ---- baseline + heap helpers ---------------------------------------------

(defn- heap-bytes
  "Used-heap after a System/gc. Not authoritative — the JVM may
  schedule the GC asynchronously and may not honour it at all under
  some VMs — but combined with the synchronous Runtime/freeMemory read,
  it gives a stable-enough delta for a same-process comparison."
  []
  (let [_  (System/gc)
        rt (Runtime/getRuntime)]
    (- (.totalMemory rt) (.freeMemory rt))))

(defn load-test
  "REPL harness — drives `n` SSR requests and returns a result map.
  The `^:slow` deftest below calls this with n=2000. Stress-runs from
  the REPL pass larger n (10_000+) to characterise the per-request
  heap-delta tail."
  [n]
  (install-registry!)
  (let [;; Warm-up — JIT, class-loading, registry first-time allocations.
        _              (dotimes [_ 100] (one-request! -1))
        baseline-frames        (count (non-default-frame-ids))
        baseline-pending       (count (pending-error-traces-snapshot))
        baseline-requests      (count (request-slots-snapshot))
        baseline-responses     (count (response-slots-snapshot))
        baseline-heap          (heap-bytes)
        start-ns               (System/nanoTime)]
    (dotimes [i n] (one-request! i))
    (let [end-ns         (System/nanoTime)
          end-heap       (heap-bytes)
          end-frames     (count (non-default-frame-ids))
          end-pending    (count (pending-error-traces-snapshot))
          end-requests   (count (request-slots-snapshot))
          end-responses  (count (response-slots-snapshot))
          duration-ms    (/ (- end-ns start-ns) 1e6)]
      {:n                  n
       :duration-ms        duration-ms
       :req-per-sec        (long (/ n (/ duration-ms 1e3)))
       :baseline-frames    baseline-frames
       :end-frames         end-frames
       :baseline-pending   baseline-pending
       :end-pending        end-pending
       :baseline-requests  baseline-requests
       :end-requests       end-requests
       :baseline-responses baseline-responses
       :end-responses      end-responses
       :baseline-heap-mb   (/ baseline-heap 1024.0 1024.0)
       :end-heap-mb        (/ end-heap 1024.0 1024.0)
       :heap-delta-mb      (/ (- end-heap baseline-heap) 1024.0 1024.0)
       :bytes-per-req      (long (/ (- end-heap baseline-heap) (max 1 n)))})))

;; ---- the test -------------------------------------------------------------

(deftest ^:slow per-request-teardown-load-test
  (testing "2000 SSR requests — frame registry returns to baseline, side-
            channel atoms return to baseline, heap delta is bounded"
    (let [result (load-test 2000)]
      (println "load-test result:" result)

      ;; (a) Frame registry — every per-request frame destroyed.
      (is (= 0 (:end-frames result))
          (str "per-request frames leaked across destroy-frame! — "
               "end-count " (:end-frames result) " > 0; the frame "
               "record stayed in `re-frame.frame/frames` after destroy-frame!"))
      (is (= (:baseline-frames result) (:end-frames result))
          "frame registry returned to baseline")

      ;; (b) SSR side-channel atoms — every per-request entry released.
      (is (= 0 (:end-pending result))
          (str "pending-error-traces leaked across destroy-frame! — "
               "end-count " (:end-pending result) " > 0; the "
               ":ssr/on-frame-destroyed hook didn't clear the slot, or "
               "the hook isn't wired into frame/destroy-frame!"))
      (is (= 0 (:end-requests result))
          (str "request-slots leaked across destroy-frame! — "
               "end-count " (:end-requests result) " > 0; the "
               ":ssr/on-frame-destroyed hook didn't clear the slot, or "
               "the hook isn't wired into frame/destroy-frame! — and "
               "this test intentionally omits clear-request! so the "
               "destroy hook is the ONLY release path."))
      (is (= 0 (:end-responses result))
          (str "response-slots leaked across destroy-frame! — "
               "end-count " (:end-responses result) " > 0; the "
               ":ssr/on-frame-destroyed hook didn't clear the slot. "
               "Per Spec 011 §Response storage substrate (rf2-jbcmt) "
               "the accumulator is a per-frame side-channel atom whose "
               "release path is the destroy-frame! teardown hook; each "
               "request fires :rf.server/set-header so the slot is "
               "populated then must be cleared."))

      ;; (c) Heap delta. Each request allocates ~a few KB of transient
      ;; state — frame record, drain ctx, render-tree, response map,
      ;; trace events, etc. After GC, the retained delta should be at
      ;; least an order of magnitude below the transient churn. We
      ;; assert <= 10 MB total delta for 2_000 requests (5 KB/req
      ;; retained = 10 MB total, which would already be a serious
      ;; leak; real bound is well under 1 MB).
      (is (< (:heap-delta-mb result) 10.0)
          (str "heap delta > 10 MB after 2_000 requests (delta = "
               (format "%.2f" (:heap-delta-mb result)) " MB; "
               (:bytes-per-req result) " bytes/request retained); "
               "indicates a memory leak in the per-request teardown path."))

      ;; Sanity: throughput should be reasonable (catches catastrophic
      ;; pathologies where the test takes minutes to run). Bound is
      ;; loose — we just want the test to fail loud if it hangs.
      (is (> (:req-per-sec result) 100)
          (str "throughput < 100 req/s — got "
               (:req-per-sec result)
               " req/s; investigate per-request overhead.")))))

(deftest ^:slow per-request-error-buffer-cleanup
  (testing "destroy-frame! clears the pending-error-traces entry even
            when the projector hasn't drained the buffer"
    (install-registry!)
    ;; Drive a few requests that EACH leave an entry in
    ;; pending-error-traces (we plant it directly via a synthetic
    ;; trace-emit so we don't have to engineer a real handler failure
    ;; — the cleanup contract is the same).
    (dotimes [i 50]
      (let [fid (rf/make-frame
                  {:doc       (str "error-buffer test " i)
                   :platform  :server
                   :on-create [:load-test/server-init {:i i}]})]
        ;; Plant a fake pending error trace under this frame's slot.
        (swap! (pending-error-traces-atom)
               update fid (fnil conj [])
               {:op-type :error :operation :rf.error/handler-exception})
        (rf/destroy-frame fid)))
    ;; Every planted slot should be gone.
    (is (= 0 (count (pending-error-traces-snapshot)))
        "destroy-frame! cleared the pending-error-traces entries even
         though the projector never drained them via get-response.")))
