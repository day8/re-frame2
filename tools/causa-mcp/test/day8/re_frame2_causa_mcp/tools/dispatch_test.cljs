(ns day8.re-frame2-causa-mcp.tools.dispatch-test
  "Unit tests for the T-Mut-1 tool `dispatch` (rf2-8xzoe.23).

  Pins:
    - Public surface — `dispatch-tool`, `build-form`, `shape-envelope`,
      `parse-event-arg`, `descriptor` resolvable.
    - Load-time registration via `register-tool!`.
    - B-3 origin stamp — `build-form` wraps the runtime call in a
      `(binding [*current-origin* :causa-mcp] ...)` block.
    - Pre-flight validation — missing / malformed / non-vector
      `:event` short-circuits without an nREPL hit.
    - Runtime envelope passthrough — `:ok? true` ack flows through
      unchanged; structured runtime failure surfaces verbatim."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.dispatch :as dispatch]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)

(defn- restore-stubs! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!))

(use-fixtures :each {:after (fn [] (restore-stubs!))})

(defn- stub-runtime! [runtime-env]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! nrepl/cljs-eval-value
        (fn
          ([_ _ _]   (js/Promise.resolve runtime-env))
          ([_ _ _ _] (js/Promise.resolve runtime-env)))))

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? dispatch/dispatch-tool))
  (is (fn? dispatch/build-form))
  (is (fn? dispatch/shape-envelope))
  (is (fn? dispatch/parse-event-arg))
  (is (= "dispatch" (:name dispatch/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "dispatch")))
  (is (= "dispatch" (:name (registry/descriptor-for "dispatch")))))

;; --- parse-event-arg --------------------------------------------------------

(deftest parse-event-arg-handles-nil-vector-string-malformed
  (is (nil? (dispatch/parse-event-arg nil)))
  (is (= [:foo 1] (dispatch/parse-event-arg [:foo 1])))
  (is (= [:cart/add 42] (dispatch/parse-event-arg "[:cart/add 42]")))
  (is (= ::dispatch/malformed (dispatch/parse-event-arg "not-edn-((")))
  (is (= ::dispatch/malformed (dispatch/parse-event-arg "{:a 1}"))
      "non-vector EDN parses to ::malformed"))

;; --- build-form -------------------------------------------------------------

(deftest build-form-wraps-origin-and-routes-runtime-ns
  (let [form (dispatch/build-form [:foo 1] {:frame :app :sync? false})]
    (is (string? form))
    (is (.includes form "day8.re-frame2-causa.runtime/dispatch!"))
    (is (.includes form ":causa-mcp")
        "B-3 origin binding wraps the call")
    (is (.includes form ":sync? false"))
    (is (.includes form ":frame :app"))))

;; --- shape-envelope ---------------------------------------------------------

(deftest shape-envelope-happy-path
  (let [shaped (dispatch/shape-envelope
                 {:ok? true :event-id :cart/add :frame :app
                  :origin :causa-mcp :mode :queued})]
    (is (true? (:ok? shaped)))
    (is (= :cart/add (:event-id shaped)))
    (is (= :causa-mcp (:origin shaped)))
    (is (= :queued (:mode shaped)))
    (is (nil? (:elided-large shaped))
        "zero-elide common path carries no counter")))

(deftest shape-envelope-passes-runtime-failure-through
  (let [env {:ok? false :reason :no-frame-resolved :hint "..."}]
    (is (= env (dispatch/shape-envelope env)))))

;; --- handler validation -----------------------------------------------------

(deftest missing-event-short-circuits
  (async done
    (-> (dispatch/dispatch-tool (atom {}) #js {})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (= :missing-event (:reason p)))
                   (is (false? (:ok? p))))
                 (done))))))

(deftest malformed-event-short-circuits
  (async done
    (-> (dispatch/dispatch-tool (atom {}) #js {"event" "((not-edn"})
        (.then (fn [r]
                 (is (= :event-malformed (:reason (payload-of r))))
                 (done))))))

(deftest non-vector-event-short-circuits
  (async done
    (-> (dispatch/dispatch-tool (atom {}) #js {"event" "{:a 1}"})
        (.then (fn [r]
                 (is (= :event-malformed (:reason (payload-of r)))
                     "non-vector EDN routes through parse-event-arg's
                      ::malformed path")
                 (done))))))

;; --- end-to-end via stubs ---------------------------------------------------

(deftest happy-path-via-stubs
  (async done
    (stub-runtime! {:ok? true :event-id :cart/add :frame :app
                    :origin :causa-mcp :mode :queued})
    (-> (dispatch/dispatch-tool
          (atom {})
          #js {"event" "[:cart/add 42]" "frame" "app"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= :cart/add (:event-id p)))
                   (is (= :causa-mcp (:origin p))))
                 (done))))))

(deftest sync-mode-flag-rides-through
  (async done
    (stub-runtime! {:ok? true :event-id :foo :frame :app
                    :origin :causa-mcp :mode :sync})
    (-> (dispatch/dispatch-tool
          (atom {})
          #js {"event" "[:foo]" "sync?" true})
        (.then (fn [r]
                 (is (= :sync (:mode (payload-of r))))
                 (done))))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (dispatch/dispatch-tool (atom {}) #js {"event" "[:foo]"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (= :runtime-not-preloaded (:reason p)))
                   (is (= "setup-hint" (:hint p))))
                 (done))))))
