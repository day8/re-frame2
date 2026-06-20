(ns re-frame2-pair-mcp.read-ui-test
  "Unit tests for the typed ui/read op — read-ui.

  Two layers:

    1. Form composition — `read-ui-tool` builds a single
       `(re-frame2-pair.runtime/ui-read {...})` form. We stub
       `cljs-eval-value` and capture the emitted form, asserting the
       chosen entry point (view-id / point / selector) and the opts
       (max-text / frame) ride into the runtime call, and that the
       form is READ-ONLY (no DOM mutation host-form leaked into it).

    2. Tool wiring — preflight + envelope passthrough. We pin the wire
       shape the runtime returns: the producing :entity (view-id +
       source-coord + render-key + subs-read), the structured :content
       ({:tag :text :attrs}), the :rf.size/large-elided text passthrough
       (privacy / elision), the missing-entry-point gate, and the
       bad-selector error reason.

  The browser-side semantics (does the view<->DOM map resolve? does
  elide-wire-value redact?) run in a real tab — exercised by the live
  conformance corpus, out of scope for a node-runtime unit suite. This
  suite pins the tool's outer contract; the runtime ns owns the inner
  read."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-ui :as read-ui]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    ;; Pretend the preload is already confirmed so the probe resolves
    ;; synchronously and we exercise the form-building / forward path.
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; ---------------------------------------------------------------------------
;; Form composition — capture the emitted form via a recording stub.
;; ---------------------------------------------------------------------------

(defn- with-captured-form!
  "Stub `cljs-eval-value` to record the emitted form string into `seen`
  (an atom) and resolve to `canned`, then run `body-fn`, restoring the
  original in `.finally`."
  [seen canned body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build form] (reset! seen form) (js/Promise.resolve canned))
               ([_conn _build form _opts] (reset! seen form) (js/Promise.resolve canned)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest form-carries-view-id-entry-point
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok? true}
            (fn []
              (read-ui/read-ui-tool (fresh-conn)
                                    #js {:view-id ":my.app/counter"})))
          (.then (fn [_]
                   (let [form @seen]
                     (is (str/includes? form "re-frame2-pair.runtime/ui-read")
                         "calls the runtime ui-read fn")
                     (is (str/includes? form ":view-id") "view-id opt rides")
                     (is (str/includes? form ":my.app/counter")
                         "the keyword view-id is embedded")
                     ;; READ-ONLY by construction — no DOM mutation host-form.
                     (doseq [mutator [".setAttribute" ".dispatchEvent"
                                      "set! (.-" ".innerHTML" ".click"]]
                       (is (not (str/includes? form mutator))
                           (str "read-ui form must be read-only — found " mutator))))
                   (done)))))))

(deftest form-carries-point-and-selector-and-knobs
  (async done
    (let [seen (atom nil)]
      (-> (with-captured-form! seen {:ok? true}
            (fn []
              (read-ui/read-ui-tool (fresh-conn)
                                    #js {:point #js {:x 12 :y 34}
                                         :max-text 100
                                         :frame ":stories"})))
          (.then (fn [_]
                   (let [form @seen]
                     (is (str/includes? form ":point") "point opt rides")
                     (is (str/includes? form "12") "point x embedded")
                     (is (str/includes? form "34") "point y embedded")
                     (is (str/includes? form ":max-text 100") "max-text knob rides")
                     (is (str/includes? form ":frame :stories") "frame coerced to keyword"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Tool wiring — preflight + envelope passthrough.
;; ---------------------------------------------------------------------------

(deftest happy-returns-entity-and-content
  (async done
    (let [canned {:ok? true :via :view-id
                  :entity {:view-id :my.app/counter
                           :source-coord {:ns "my.app" :handler-id "counter"
                                          :line 42 :col 3
                                          :file "/abs/my/app.cljs"}
                           :render-key 8123
                           :subs-read [[:count] [:user]]}
                  :content {:tag "div" :text "Count: 3"
                            :attrs {"class" "counter" "data-count" "3"}}}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-ui/read-ui-tool (fresh-conn) #js {:view-id ":my.app/counter"})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [edn     (tu/extract-edn r)
                         entity  (:entity edn)
                         content (:content edn)]
                     (is (true? (:ok? edn)))
                     (is (= :view-id (:via edn)) "entry point echoed")
                     ;; The producing ENTITY — the headline of ui/read.
                     (is (= :my.app/counter (:view-id entity)) "producing view-id")
                     (is (= "/abs/my/app.cljs" (get-in entity [:source-coord :file]))
                         "source-coord :file augmented via handler-meta")
                     (is (= 42 (get-in entity [:source-coord :line])) "source-coord :line")
                     (is (number? (:render-key entity)) "render-key present")
                     (is (= [[:count] [:user]] (:subs-read entity)) "subs-read query-vectors")
                     ;; The structured CONTENT.
                     (is (= "div" (:tag content)) "content :tag")
                     (is (= "Count: 3" (:text content)) "content :text")
                     (is (= "3" (get-in content [:attrs "data-count"])) "content data-* attr"))
                   (done)))))))

(deftest large-text-elision-passes-through
  ;; Privacy / elision: the runtime routes :text through
  ;; elide-wire-value; an over-cap blob rides as the :rf.size/large-elided
  ;; marker, never raw user DOM text.
  (async done
    (let [canned {:ok? true :via :view-id
                  :entity {:view-id :my.app/log :source-coord nil
                           :render-key 1 :subs-read []}
                  :content {:tag "pre"
                            :text {:rf.size/large-elided
                                   {:type :dom-text :chars 54000 :preview "lorem..."}}
                            :attrs {}}}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-ui/read-ui-tool (fresh-conn)
                                    #js {:view-id ":my.app/log" :max-text 100})))
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (let [text (-> (tu/extract-edn r) :content :text)
                         mark (:rf.size/large-elided text)]
                     (is (map? text) "elided text rides as a marker map")
                     (is (= :dom-text (:type mark)))
                     (is (= 54000 (:chars mark)) "elision marker reports char count"))
                   (done)))))))

(deftest no-tagged-view-root-still-returns-content
  ;; A portal / fragment leaf has no tagged view ancestor — the entity is
  ;; unresolvable for that node but the content still rides.
  (async done
    (let [canned {:ok? true :via :point
                  :entity {:view-id nil :reason :no-tagged-view-root}
                  :content {:tag "span" :text "x" :attrs {}}}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-ui/read-ui-tool (fresh-conn) #js {:point #js {:x 5 :y 5}})))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (true? (:ok? edn)))
                     (is (nil? (get-in edn [:entity :view-id])))
                     (is (= :no-tagged-view-root (get-in edn [:entity :reason])))
                     (is (= "span" (get-in edn [:content :tag])) "content still present"))
                   (done)))))))

(deftest missing-entry-point-short-circuits
  (async done
    (-> (read-ui/read-ui-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :no-target-arg (:reason (tu/extract-edn r))))
                 (done))))))

(deftest bad-selector-error-forwarded
  ;; A genuine `:ok? false` runtime failure (a thrown malformed-selector)
  ;; MUST ride as `:isError true`, per spec/003-Tool-Catalogue.md §381.
  ;; Routing every map through the shared `map-result-or-blank` /
  ;; `ok-text` path would ship this failure as `isError:false` and make
  ;; it cache-eligible.
  (async done
    (let [canned {:ok? false :reason :rf.error/ui-read-bad-selector
                  :message "bad selector"}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-ui/read-ui-tool (fresh-conn) #js {:selector "###"})))
          (.then (fn [r]
                   (is (tu/error? r)
                       "a thrown bad-selector failure is flagged isError")
                   (let [edn (tu/extract-edn r)]
                     (is (false? (:ok? edn)))
                     (is (= :rf.error/ui-read-bad-selector (:reason edn)))
                     (is (= :app (:build edn))
                         "the failure envelope is :build-stamped too"))
                   (done)))))))

(deftest blank-eval-result-becomes-structured-error-not-host-failure
  ;; A blank eval result (nil) must not produce a null structuredContent
  ;; that the SDK rejects at the transport layer.
  (async done
    (-> (tu/with-stubbed-eval! nil
          (fn []
            (read-ui/read-ui-tool (fresh-conn) #js {:selector "body"})))
        (.then (fn [r]
                 (is (tu/error? r))
                 (let [edn (tu/extract-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :rf.error/read-ui-blank-result (:reason edn))))
                 (is (some? (j/get r :structuredContent))
                     "structuredContent must NOT be null (rf2-r5erl)")
                 (is (object? (j/get r :structuredContent)))
                 (done))))))

(deftest happy-result-echoes-canonical-build
  ;; read-ui echoes the resolved :build.
  (async done
    (let [canned {:ok? true :via :selector
                  :entity {:view-id :my.app/x :source-coord nil :render-key 1 :subs-read []}
                  :content {:tag "div" :text "x" :attrs {}}}]
      (-> (tu/with-stubbed-eval! canned
            (fn []
              (read-ui/read-ui-tool (fresh-conn) #js {:selector "#x"})))
          (.then (fn [r]
                   (is (= :app (:build (tu/extract-edn r)))
                       "echoes the resolved :build keyword")
                   (done)))))))
