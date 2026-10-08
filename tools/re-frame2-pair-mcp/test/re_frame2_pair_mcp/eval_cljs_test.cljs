(ns re-frame2-pair-mcp.eval-cljs-test
  "Unit tests for the eval-cljs tool: build resolution and the fail-loud
  preflight, `:timeout-ms` validation, compile-error surfacing, the await
  failure envelopes, and `:frame` wrapping."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.await-promise :as await-promise]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]))

;; The gate's disabled state is the corpus's `:eval-cljs/disabled-via-no-eval` fixture.
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

(defn- with-runtime!
  "Stub the two nREPL entry points eval-cljs reaches. `nrepl/jvm-eval`
  answers the `active-builds` enumeration with `running`;
  `nrepl/cljs-eval-value` answers the runtime-sentinel probe with `runtime?`
  and every other form with `(respond form)`, recording those forms into the
  `forms` atom when one is given. Runs the Promise-returning `body-fn`, then
  restores both."
  [{:keys [running runtime? respond forms]
    :or   {running [:app] runtime? true respond (constantly nil)}}
   body-fn]
  (let [orig-jvm  nrepl/jvm-eval
        orig-cljs nrepl/cljs-eval-value
        jvm-stub  (fn
                    ([_conn _form] (js/Promise.resolve {:value (pr-str running)}))
                    ([_conn _form _opts] (js/Promise.resolve {:value (pr-str running)})))
        answer    (fn [form]
                    (js/Promise.resolve
                      (if (sentinel-probe? form)
                        runtime?
                        (do (when forms (swap! forms conj form))
                            (respond form)))))
        cljs-stub (fn
                    ([_conn _build form] (answer form))
                    ([_conn _build form _opts] (answer form)))]
    (set! nrepl/jvm-eval jvm-stub)
    (set! nrepl/cljs-eval-value cljs-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-jvm-eval! jvm-stub orig-jvm)
                    (tu/restore-eval! cljs-stub orig-cljs))))))

;; ---------------------------------------------------------------------------
;; Build resolution and preflight. Against a build with no live runtime,
;; shadow answers a blank value that would otherwise read as a genuine nil,
;; so the tool fails loud instead.
;; ---------------------------------------------------------------------------

(deftest no-running-build-fails-loud
  (async done
    (-> (with-runtime! {:running [] :runtime? false}
          #(eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])"}))
        (.then (fn [r]
                 (is (= {:ok? false :reason :no-runtime-for-build :running-builds []}
                        (select-keys (read-edn r) [:ok? :reason :running-builds]))
                     "with no :build and nothing running, no build can be auto-detected")
                 (done))))))

(deftest auto-detects-single-running-build
  (async done
    (-> (with-runtime! {:running [:examples/step-deck] :respond (constantly 3)}
          #(eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"}))
        (.then (fn [r]
                 (is (= {:ok? true :value 3 :build :examples/step-deck} (read-edn r))
                     "the one running build is used, and echoed back")
                 (done))))))

(deftest explicit-build-with-no-runtime-fails-loud
  ;; The explicit build is honoured even while a different build is running.
  (async done
    (-> (with-runtime! {:running [:examples/step-deck] :runtime? false}
          #(eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])" :build "app"}))
        (.then (fn [r]
                 (is (= {:ok? false :reason :no-runtime-for-build :build :app
                         :running-builds [:examples/step-deck]}
                        (select-keys (read-edn r) [:ok? :reason :build :running-builds]))
                     "fails loud on the requested build, listing the builds that ARE running")
                 (done))))))

(deftest bogus-timeout-ms-rejected-before-touching-nrepl
  ;; A NaN deadline would let the await poll loop run forever. No stub is
  ;; installed, so reaching nREPL would fail some other way.
  (async done
    (-> (eval-cljs/eval-cljs-tool (fresh-conn)
                                  #js {:form "(+ 1 2)" :await true :timeout-ms "bogus"})
        (.then (fn [r]
                 (is (err? r))
                 (is (= {:reason :invalid-numeric-arg :arg "timeout-ms"}
                        (select-keys (read-edn r) [:reason :arg])))
                 (done))))))

(deftest unresolved-symbol-end-to-end-fails-loud
  ;; shadow answers an undeclared var with a "nil" result beside the analyzer
  ;; warning in :err. `nrepl/cljs-eval` is stubbed, one layer below
  ;; `cljs-eval-value`, so the real unwrap runs.
  (async done
    (let [orig-jvm  nrepl/jvm-eval
          orig-cljs nrepl/cljs-eval
          warning   "WARNING: Use of undeclared Var re-frame.core/frame-db at line 1 <eval>"
          jvm-stub  (fn
                      ([_ _]   (js/Promise.resolve {:value "[:app]"}))
                      ([_ _ _] (js/Promise.resolve {:value "[:app]"})))
          cljs-resp (fn [form-str]
                      (if (sentinel-probe? form-str)
                        {:value "{:results [\"true\"] :ns cljs.user}"}
                        {:value (str "{:results [\"nil\"] :err \"" warning "\" :ns cljs.user}")}))
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
                   (is (err? r) "an unresolved symbol is an isError envelope, never a silent nil")
                   (is (= {:ok? false :reason :rf.error/eval-cljs-compile-error :err warning}
                          (select-keys (read-edn r) [:ok? :reason :err]))
                       "the compile-error reason and the analyzer warning reach the agent")))
          (.catch (fn [e] (is false (str "tool promise rejected: " e))))
          (.then (fn [_]
                   (tu/restore-jvm-eval! jvm-stub orig-jvm)
                   (tu/restore-cljs-eval! cljs-stub orig-cljs)
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Await mode. For a thenable the wrapper returns
;; `{:rf.mcp/await-mailbox <id>}` and the server polls a read form until the
;; mailbox settles. The resolved, rejected and direct paths are corpus
;; fixtures; the two failure envelopes below are not.
;; ---------------------------------------------------------------------------

(defn- await-wrap-form? [form-str]
  (and (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str ":rf.mcp/await-mailbox")))

(defn- mailbox-read-form? [form-str]
  (and (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str "cljs.reader/read-string")))

(deftest await-timeout-surfaces-structured
  (async done
    (-> (with-runtime! {:respond (fn [form]
                                   (cond
                                     (await-wrap-form? form)    {:rf.mcp/await-mailbox "await-test-3"}
                                     (mailbox-read-form? form) {:status :pending}))}
          #(eval-cljs/eval-cljs-tool (fresh-conn)
                                     #js {:form       "(js/Promise. (fn [_ _]))"
                                          :await      true
                                          :timeout-ms 75
                                          :build      "app"}))
        (.then (fn [r]
                 (is (err? r) "a timed-out await is a known-tool failure, so isError")
                 (is (= {:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms 75 :build :app}
                        (read-edn r)))
                 (done))))))

(deftest await-bad-sentinel-surfaces-structured
  ;; An unrecognised sentinel means the await wrapper itself regressed.
  (async done
    (-> (with-runtime! {:respond (fn [form]
                                   (when (await-wrap-form? form) {:some/unexpected-key 42}))}
          #(eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)" :await true :build "app"}))
        (.then (fn [r]
                 (is (err? r) "an unrecognised await sentinel is isError")
                 (is (= {:ok? false :reason :rf.error/eval-cljs-await-wrap-failed :build :app}
                        (select-keys (read-edn r) [:ok? :reason :build])))
                 (done))))))

;; ---------------------------------------------------------------------------
;; `:frame` wraps the form in `(re-frame.core/with-frame <frame> <form>)`.
;; ---------------------------------------------------------------------------

(defn- readable? [src]
  (try (cljs.reader/read-string src) true
       (catch :default _ false)))

(deftest frame-arg-wraps-form-in-with-frame
  ;; The form ends in a `;` line comment: valid CLJS, which a wrapper that
  ;; appended its closing delimiters on the same line would swallow.
  (async done
    (let [forms (atom [])]
      (-> (with-runtime! {:respond (constantly {:rf.mcp/result :value :value 42}) :forms forms}
            #(eval-cljs/eval-cljs-tool (fresh-conn)
                                       #js {:form  "(+ 20 22) ; expected answer"
                                            :frame ":rf/xray"
                                            :build "app"}))
          (.then (fn [r]
                   (is (= {:ok? true :value 42 :build :app :frame :rf/xray} (read-edn r))
                       "the frame is echoed on the envelope")
                   (is (some #(and (str/includes? % "(re-frame.core/with-frame :rf/xray (+ 20 22) ; expected answer")
                                   (str/includes? % "cljs.reader/read-string"))
                             @forms)
                       "the form is wrapped in with-frame, inside the default path's result codec")
                   (is (every? readable? @forms)
                       "every emitted form reads: the comment swallowed no delimiter")
                   (done)))))))

(deftest await-wrapper-survives-a-trailing-line-comment
  ;; The await wrapper is a pure fn; the frame wrapper is pinned through the
  ;; tool by frame-arg-wraps-form-in-with-frame.
  (is (readable? (await-promise/wrap-form "(+ 20 22) ; expected answer" "mbox"))
      "the comment swallows no closing delimiter"))

(deftest frame-arg-omitted-leaves-form-unwrapped
  ;; With no :frame the form carries no frame stamp (EP-0002), so a
  ;; frame-scoped op inside it raises rather than silently targeting :rf/default.
  (async done
    (let [forms (atom [])]
      (-> (with-runtime! {:respond (constantly 99) :forms forms}
            #(eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 90 9)" :build "app"}))
          (.then (fn [r]
                   (is (= {:ok? true :value 99 :build :app} (read-edn r)) "no :frame slot on the envelope")
                   (is (not-any? #(str/includes? % "re-frame.core/with-frame") @forms)
                       "the form is not wrapped in with-frame")
                   (done)))))))

(deftest frame-arg-composes-with-await
  ;; The await wrapper wraps the with-frame form, so the mailbox sentinel survives.
  (async done
    (let [forms (atom [])]
      (-> (with-runtime! {:respond (fn [form]
                                     (when (await-wrap-form? form) {:rf.mcp/await-direct 7}))
                          :forms   forms}
            #(eval-cljs/eval-cljs-tool (fresh-conn)
                                       #js {:form "(+ 3 4)" :await true :frame ":rf/xray" :build "app"}))
          (.then (fn [r]
                   (is (= {:ok? true :value 7 :build :app} (read-edn r)))
                   (is (re-find #"with-frame :rf/xray" (first (filter await-wrap-form? @forms)))
                       "the await wrapper carries the with-frame wrap")
                   (done)))))))
