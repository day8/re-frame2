(ns re-frame2-pair-mcp.id-injection-test
  "rf2-3x7nj.32.2 — a caller-supplied id must never print into evaluated
  source as code.

  Tools mint caller ids into keywords and PRINT them into the forms they
  evaluate: the `:build` into the Clojure form `nrepl/cljs-eval` sends to
  the shadow-cljs JVM, a `:frame` / view / fx / interceptor id (and every
  key of a keywordized object argument) into the browser form. A keyword
  prints its name unescaped, so one minted from `\"app (do (evil)) #_\"`
  prints as live code. Before the fix any tool — read-only ones included,
  and past `--no-eval` — ran such a string on the JVM or in the page.

  Each witness below drives a hostile id through the real code path and
  captures what reaches the eval sink, asserting the payload never
  arrives there (and, at the `tools/call` chokepoint, that the call is
  refused with `:rf.mcp/invalid-arg`). The same capture sees a
  well-formed id arrive, which is the control that the instrument can
  see a build at all.

  A caller-supplied `cursor` is EDN data by the same argument: its frame
  must be a keyword with the id grammar, and its epoch id and predicate
  ride quoted."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]
            [re-frame2-pair-mcp.tools.read-ui :as read-ui]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.trace-window :as trace-window]
            [re-frame2-pair-mcp.tools.watch-epochs :as watch-epochs]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

;; ---------------------------------------------------------------------------
;; Sinks. Both are restored UNCONDITIONALLY in `:after` (the invoke-test
;; posture): cleanup is fixture-scoped, not Promise-chain-scoped.
;; ---------------------------------------------------------------------------

(def ^:private payload
  "The marker every hostile id carries. It must never reach an eval sink."
  "PWNED-3x7nj")

(def ^:private orig-jvm-eval nrepl/jvm-eval)
(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn []
             (set! nrepl/jvm-eval orig-jvm-eval)
             (set! nrepl/cljs-eval-value orig-cljs-eval-value)
             (cache/clear!))})

(defn- jvm-answer
  "Answer a JVM form as a live shadow-cljs would: no running builds for
  the `active-builds` enumeration, and `true` for any cljs-eval — so the
  runtime-preload probe passes and a tool proceeds to its own form."
  [code]
  (if (str/includes? code "active-builds")
    {:value "[]"}
    {:value "{:results [\"true\"]}"}))

(defn- capture-jvm!
  "Stub the LOWEST sink, `nrepl/jvm-eval`, recording every code string.
  Everything above it — `cljs-eval-value`, `cljs-eval` and the JVM-form
  splice — runs for real."
  [codes]
  (set! nrepl/jvm-eval
        (fn
          ([_conn code]
           (swap! codes conj code)
           (js/Promise.resolve (jvm-answer code)))
          ([_conn code _opts]
           (swap! codes conj code)
           (js/Promise.resolve (jvm-answer code))))))

(defn- capture-cljs!
  "Stub the browser sink, `nrepl/cljs-eval-value`, recording every form.
  The runtime-preload probe answers true; every other form nil."
  [forms]
  (let [answer (fn [form] (js/Promise.resolve
                            (boolean (str/includes? form "__re_frame2_pair_runtime"))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_conn _build form] (swap! forms conj form) (answer form))
            ([_conn _build form _opts] (swap! forms conj form) (answer form))))))

(defn- reached? [captured]
  (boolean (some #(str/includes? % payload) captured)))

(defn- invalid-arg [result]
  (:rf.mcp/invalid-arg (tu/extract-edn result)))

(defn- fail! [e] (is false (str "rejected: " (.-message e))) nil)

;; ---------------------------------------------------------------------------
;; The id grammar.
;; ---------------------------------------------------------------------------

(deftest id-keyword-mints-only-keyword-grammar
  (testing "real ids pass, colon-tolerant"
    (is (= :app (args/->id-keyword "app")))
    (is (= :app (args/->id-keyword ":app")))
    (is (= :examples/step-deck (args/->id-keyword ":examples/step-deck")))
    (is (= :rf/default (args/->id-keyword "rf/default")))
    (is (= :my.app/view-1? (args/->id-keyword ":my.app/view-1?")))
    (is (= :rf/default (args/->id-keyword :rf/default)) "a keyword passes through"))
  (testing "anything that would print as more than one token mints nothing"
    (is (nil? (args/->id-keyword (str "app (do (" payload ")) #_"))))
    (is (nil? (args/->id-keyword (str "rf/default (swap! a " payload ")"))))
    (is (nil? (args/->id-keyword "a\"b")))
    (is (nil? (args/->id-keyword "a;b")))
    (is (nil? (args/->id-keyword "a b")))
    (is (nil? (args/->id-keyword "::rf/default")) "a doubled colon is not a keyword id")
    (is (nil? (args/->id-keyword (keyword (str "x (" payload ")"))))
        "an already-minted keyword is shape-checked too"))
  (testing "absent / blank / non-string input is nil, as before"
    (is (nil? (args/->id-keyword nil)))
    (is (nil? (args/->id-keyword "")))
    (is (nil? (args/->id-keyword 42))))
  (testing "->frame-keyword is the same gate"
    (is (= :rf/xray (args/->frame-keyword ":rf/xray")))
    (is (nil? (args/->frame-keyword (str "rf/xray (" payload ")"))))))

(deftest invalid-id-keyword-finds-a-bad-key-at-any-depth
  (is (nil? (args/invalid-id-keyword {:event-id ":ev/x" :effects [:http]})))
  (let [bad (keyword (str "k (" payload ")"))]
    (is (= bad (args/invalid-id-keyword {:a {bad 1}})) "a nested map key")
    (is (= bad (args/invalid-id-keyword [{:ok 1} {bad 2}])) "inside a vector")
    (is (= bad (args/invalid-id-keyword {:a bad})) "a keyword value")))

;; ---------------------------------------------------------------------------
;; The `tools/call` chokepoint — `:build`, `:frame`, `:frames`.
;; ---------------------------------------------------------------------------

(deftest hostile-build-never-reaches-the-jvm
  (testing "a :build that prints as code is refused before it reaches a JVM form or the sticky default"
    (async done
      (let [codes   (atom [])
            conn    (nrepl/make-conn 0 "127.0.0.1")
            hostile (str "app (do (reset! probe/side-effect :" payload ") :app) #_")]
        (capture-jvm! codes)
        (-> (tools/invoke conn "get-path" (tu/args->js {:path "[:x]" :build hostile}) nil)
            (.then (fn [result]
                     (is (tu/error? result) "the refusal rides isError: true")
                     (let [body (invalid-arg result)]
                       (is (= :build (:arg body)))
                       (is (= hostile (:value body))))
                     (is (not (reached? @codes))
                         "the hostile build never reached a JVM form")
                     (is (nil? (:resolved-build-id @conn))
                         "a refused build is never stuck as the session default")))
            (.catch fail!)
            (.then (fn [_] (done))))))))

(deftest well-formed-build-reaches-the-jvm-form
  (testing "control: the same capture sees a real build id arrive in the JVM form"
    (async done
      (let [codes (atom [])]
        (capture-jvm! codes)
        (-> (tools/invoke nil "get-path"
                          (tu/args->js {:path "[:x]" :build ":examples/step-deck" :frame ":rf/default"})
                          nil)
            (.then (fn [result]
                     (is (nil? (invalid-arg result)) "a well-formed build and frame are not refused")
                     (is (some #(str/includes? % "(shadow.cljs.devtools.api/cljs-eval :examples/step-deck ")
                               @codes)
                         "the instrument sees the build spliced into the JVM form")))
            (.catch fail!)
            (.then (fn [_] (done))))))))

(deftest hostile-frame-never-reaches-the-page
  (testing "a :frame that prints as code is refused before any form is evaluated"
    (async done
      (let [codes   (atom [])
            hostile (str "rf/default (re-frame.core/dispatch-sync [:" payload "])")]
        (capture-jvm! codes)
        (-> (tools/invoke nil "get-path" (tu/args->js {:path "[:x]" :frame hostile}) nil)
            (.then (fn [result]
                     (is (tu/error? result))
                     (let [body (invalid-arg result)]
                       (is (= :frame (:arg body)))
                       (is (= hostile (:value body))))
                     (is (not (reached? @codes)) "the hostile frame never reached an eval form")))
            (.catch fail!)
            (.then (fn [_] (done))))))))

(deftest hostile-frames-entry-is-refused
  (testing "a :frames entry that prints as code is refused"
    (async done
      (let [codes (atom [])]
        (capture-jvm! codes)
        (-> (tools/invoke nil "snapshot"
                          (tu/args->js {:frames #js ["rf/default" (str "x (" payload ")")]})
                          nil)
            (.then (fn [result]
                     (is (tu/error? result))
                     (is (= :frames (:arg (invalid-arg result))))
                     (is (not (reached? @codes)))))
            (.catch fail!)
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; The JVM sink's own belt — whatever route a build id took.
;; ---------------------------------------------------------------------------

(deftest build-id-literal-reads-back-as-one-keyword
  (is (= ":examples/step-deck" (nrepl/build-id-literal :examples/step-deck)))
  (is (= ":app" (nrepl/build-id-literal nil)))
  (is (= ":app" (nrepl/build-id-literal "app")))
  (is (nil? (nrepl/build-id-literal (keyword (str "app (do (" payload ")) #_")))))
  (is (nil? (nrepl/build-id-literal (keyword ":x"))) "prints as ::x, which is no literal"))

(deftest cljs-eval-refuses-a-malformed-build-without-sending
  (async done
    (let [codes (atom [])]
      (capture-jvm! codes)
      (-> (nrepl/cljs-eval nil (keyword (str "app (do (" payload ")) #_")) "(+ 1 2)")
          (.then (fn [_] (is false "a malformed build id must reject")))
          (.catch (fn [e]
                    (is (= :rf.error/pair-mcp-malformed-build-id (:rf.error/id (ex-data e))))
                    (is (empty? @codes) "nothing was sent to the JVM")))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Tool-level ids and keywordized object keys.
;; ---------------------------------------------------------------------------

(defn- refused-before-eval
  "Run `call` (a thunk returning the tool's Promise) with the browser sink
  captured; assert the result is an isError refusal satisfying `check`
  and that the payload never reached a form."
  [call check done]
  (let [forms (atom [])]
    (capture-cljs! forms)
    (-> (call)
        (.then (fn [result]
                 (is (tu/error? result) "the refusal rides isError: true")
                 (check (tu/extract-edn result))
                 (is (not (reached? @forms)) "the hostile id never reached an eval form")))
        (.catch fail!)
        (.then (fn [_] (done))))))

(deftest read-ui-view-id-is-refused
  (async done
    (refused-before-eval
      #(read-ui/read-ui-tool nil (tu/args->js {:view-id (str ":my.app/header (" payload ") #_")}))
      (fn [edn] (is (= :view-id (get-in edn [:rf.mcp/invalid-arg :arg]))))
      done)))

(deftest read-ui-point-nested-key-is-refused
  (async done
    (refused-before-eval
      #(read-ui/read-ui-tool nil (tu/args->js {:point #js {:x (js-obj (str "k (" payload ")") 1) :y 2}}))
      (fn [edn] (is (= :point (get-in edn [:rf.mcp/invalid-arg :arg]))))
      done)))

(deftest watch-epochs-pred-key-is-refused
  (async done
    (refused-before-eval
      #(watch-epochs/watch-epochs-tool nil (tu/args->js {:pred (js-obj (str "event-id (" payload ")") ":ev/x")}))
      (fn [edn] (is (= :pred (get-in edn [:rf.mcp/invalid-arg :arg]))))
      done)))

(deftest watch-until-pred-nested-key-is-refused
  (async done
    (refused-before-eval
      #(watch-until/watch-until-tool
         nil (tu/args->js {:signals    "[{:app-db [:x]}]"
                           :pred       #js {:signal 0 :equals (js-obj (str "k (" payload ")") 1)}
                           :timeout-ms 200}))
      (fn [edn] (is (= :pred (get-in edn [:rf.mcp/invalid-arg :arg]))))
      done)))

(deftest record-signals-key-is-refused
  (async done
    (refused-before-eval
      #(record/record-tool nil (tu/args->js {:signals #js [(js-obj (str "app-db (" payload ")") #js [":x"])]}))
      (fn [edn] (is (= :signals (get-in edn [:rf.mcp/invalid-arg :arg]))))
      done)))

(deftest dispatch-fx-override-key-is-refused
  (async done
    (refused-before-eval
      #(dispatch/dispatch-tool nil (tu/args->js {:event        "[:ev/x]"
                                                 :fx-overrides (js-obj (str ":http (" payload ")") ":stub-http")}))
      (fn [edn] (is (= :rf.error/invalid-fx-overrides (:reason edn))))
      done)))

(deftest dispatch-fx-override-target-is-refused
  (async done
    (refused-before-eval
      #(dispatch/dispatch-tool nil (tu/args->js {:event        "[:ev/x]"
                                                 :fx-overrides #js {":http" (str ":stub (" payload ")")}}))
      (fn [edn] (is (= :rf.error/invalid-fx-overrides (:reason edn))))
      done)))

(deftest dispatch-interceptor-override-id-is-refused
  (async done
    (refused-before-eval
      #(dispatch/dispatch-tool nil (tu/args->js {:event                 "[:ev/x]"
                                                 :interceptor-overrides (js-obj (str ":auth/x (" payload ")") nil)}))
      (fn [edn] (is (= :rf.error/interceptor-override-invalid (:reason edn))))
      done)))

;; ---------------------------------------------------------------------------
;; The cursor is caller data too.
;; ---------------------------------------------------------------------------

(def ^:private forged-call
  "A call form a crafted cursor might carry. Evaluated, it would run;
  quoted, it is inert data."
  (list (symbol "js" "pwned3x7nj")))

(deftest cursor-with-a-non-keyword-frame-is-stale-not-evaluated
  (testing "both epoch tools treat a cursor whose :frame is code as malformed"
    (async done
      (let [forms (atom [])
            token (cursor/encode-cursor {:v 1 :after-id 1 :frame forged-call})]
        (capture-cljs! forms)
        (-> (watch-epochs/watch-epochs-tool nil (tu/args->js {:cursor token}))
            (.then (fn [result]
                     (is (= :rf.mcp/cursor-stale (:reason (tu/extract-edn result))))
                     (trace-window/trace-window-tool nil (tu/args->js {:cursor token}))))
            (.then (fn [result]
                     (is (= :rf.mcp/cursor-stale (:reason (tu/extract-edn result))))
                     (is (not-any? #(str/includes? % "pwned3x7nj") @forms)
                         "the forged frame never reached a form")))
            (.catch fail!)
            (.then (fn [_] (done))))))))

(deftest cursor-epoch-id-and-pred-ride-quoted
  (testing "a cursor's :after-id and :pred reach the form as quoted data, never source"
    (async done
      (let [forms (atom [])
            token (cursor/encode-cursor {:v 1 :after-id forged-call :frame :rf/default
                                         :pred {:event-id forged-call}})]
        (capture-cljs! forms)
        (-> (watch-epochs/watch-epochs-tool nil (tu/args->js {:cursor token}))
            (.then (fn [_]
                     (let [form (last @forms)]
                       (is (str/includes? form "epochs-since (quote (js/pwned3x7nj))")
                           "watch-epochs' epoch id is quoted")
                       (is (str/includes? form "epoch-matches? (quote {:event-id (js/pwned3x7nj)})")
                           "watch-epochs' predicate is quoted"))
                     (reset! forms [])
                     (trace-window/trace-window-tool nil (tu/args->js {:cursor token}))))
            (.then (fn [_]
                     (is (str/includes? (last @forms) "after-id (quote (js/pwned3x7nj))")
                         "trace-window's epoch id is quoted")))
            (.catch fail!)
            (.then (fn [_] (done))))))))
