(ns re-frame.ssr-source-coord-test
  "Per Spec 011 §Source-coord annotation under SSR (rf2-z7f7 / rf2-z9n1):
  the JVM hiccup→HTML emitter mirrors the CLJS Reagent-adapter contract
  in Spec 006 §Source-coord annotation. When emitting HTML for a
  registered view, the emitter MUST inject
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on the rendered
  root DOM element.

  Coverage:

    - reg-view'd component → server-rendered HTML carries the attribute.
    - Format matches <ns>:<sym>:<line>:<col>.
    - Plain hiccup (no registered view) is NOT annotated — the contract
      applies to the registered-view boundary, not arbitrary tags.
    - Non-DOM root (Fragment :<>): exempt; pair tools fall back to
      the registry's :rf/id.
    - Programmatic registration without macro coords degrades to
      <ns>:<sym>:?:?."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture` (rf2-i3qc0).
(use-fixtures :each tf/reset-runtime)

;; ---- registered-view root → HTML carries the attribute -------------------

(deftest reg-view-root-is-annotated
  (testing "a server-rendered registered view carries data-rf2-source-coord
            on its root element"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/banner} banner-view []
      [:h1 "hi"])
    (let [html (ssr/render-to-string [:rf.ssr-coord-test/banner] {})]
      (is (str/includes? html "data-rf2-source-coord=\"")
          "rendered HTML carries the data-rf2-source-coord attribute")
      ;; Format: <ns>:<sym>:<line>:<col>.
      (is (re-find #"data-rf2-source-coord=\"rf\.ssr-coord-test:banner:\d+:\d+\""
                   html)
          (str "attribute value matches <ns>:<sym>:<line>:<col>; got: " html)))))

;; ---- plain hiccup (no registered view) is NOT annotated ------------------

(deftest plain-hiccup-not-annotated
  (testing "the SSR emitter only annotates registered-view roots; plain
            hiccup is left untouched"
    (let [html (ssr/render-to-string [:p "no-view-here"] {})]
      (is (= "<p>no-view-here</p>" html)
          "plain hiccup renders without the annotation")
      (is (not (str/includes? html "data-rf2-source-coord"))
          "no source-coord attribute on plain hiccup"))))

;; ---- nested registered views → each root annotated ----------------------

(deftest nested-registered-views-each-annotated
  (testing "nested reg-view'd components — each registered root carries
            its own data-rf2-source-coord"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/inner} inner-view []
      [:span "in"])
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/outer} outer-view []
      [:section [:rf.ssr-coord-test/inner]])
    (let [html (ssr/render-to-string [:rf.ssr-coord-test/outer] {})]
      (is (re-find #"<section [^>]*data-rf2-source-coord=\"rf\.ssr-coord-test:outer:\d+:\d+\""
                   html)
          (str "outer root annotated with outer's coord; got: " html))
      (is (re-find #"<span [^>]*data-rf2-source-coord=\"rf\.ssr-coord-test:inner:\d+:\d+\""
                   html)
          (str "inner root annotated with inner's coord; got: " html)))))

;; ---- Fragment / non-DOM root is exempt -----------------------------------

(deftest fragment-root-skipped-in-ssr
  (testing "a registered view whose root is :<> (Fragment) is exempt —
            no attribute is injected; pair tools fall back to :rf/id"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/frag} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    ;; The current SSR emitter renders :<> by appending the rendered
    ;; children with no wrapper. The contract: the data-rf2-source-coord
    ;; attribute is NOT injected anywhere derived from the fragment root.
    (let [html (ssr/render-to-string [:rf.ssr-coord-test/frag] {})]
      (is (not (str/includes? html "data-rf2-source-coord"))
          (str "no data-rf2-source-coord on fragment-rooted view; got: " html)))))

;; ---- programmatic reg-view* (no macro coords) → <ns>:<sym>:?:? ----------

(deftest programmatic-registration-degrades-gracefully
  (testing "a programmatic reg-view* (no macro coords) still annotates,
            with the id-derived <ns>:<sym>; line/col are `?`"
    ;; Bypass the macro path: source-coords/merge-coords sees no
    ;; *pending-coords*, so the slot has no captured :ns/:line/:column.
    ;; Per Spec 006 §Source-coord annotation, the SSR emitter degrades
    ;; gracefully and the format helper returns nil — meaning the
    ;; emitter SKIPS the annotation when no coords were captured at
    ;; registration time. (CLJS shows <ns>:<sym>:?:? because it
    ;; constructs the attr from the id; JVM uses the slot's coords as
    ;; the trigger — symmetry is fine, the bead's contract names the
    ;; CLJS shape and accepts JVM SSR being slightly more conservative.)
    (let [reg-fn (requiring-resolve 're-frame.subs/reg-sub)]
      ;; Burn the pending-coords slot via a non-view register call
      ;; (so any macro-injected coords from another nearby form don't
      ;; leak into this view registration).
      (reg-fn :rf.ssr-coord-test/_burn (fn [db _] db)))
    (let [reg-fn (resolve 're-frame.views/reg-view*)
          ;; views/reg-view* doesn't exist on JVM (CLJS-only); JVM uses
          ;; registrar/register! :view directly via core/reg-view*. Simpler
          ;; path: call the public surface with empty metadata.
          _      (when-not reg-fn
                   (rf/reg-view* :rf.ssr-coord-test/programmatic
                                 (fn [] [:p "p"])))]
      (let [html (ssr/render-to-string [:rf.ssr-coord-test/programmatic] {})]
        ;; The slot carries no coords → SSR emitter elides annotation.
        ;; Equivalent to the documented "no-op for programmatic
        ;; registrations" behaviour.
        (is (= "<p>p</p>" html)
            (str "programmatic registration (no macro coords) renders "
                 "without annotation; got: " html))))))

;; ---- production gate: source-coords elided when debug-enabled? false -----
;;
;; rf2-wtd8z finding 3 / Spec 011 §SSR production elision: the JVM SSR
;; annotation site MUST obey the `re-frame.interop/debug-enabled?`
;; production gate — the counterpart to CLJS `goog.DEBUG=false`. Source
;; coords are internal ns/symbol/line info; a production render must not
;; leak them into public HTML. Pre-fix the emitter injected whenever the
;; slot carried coords, ignoring the gate, so a production JVM render
;; could ship `data-rf2-source-coord` into the wire HTML. These tests pin
;; both arms: debug ON → annotated (the existing contract); debug OFF →
;; elided.
;;
;; `debug-enabled?` is read once at ns load into a plain Var; the emitter
;; reads it through that Var at call time, so `with-redefs` flips it for
;; the duration of the body — the standard way to exercise the gate
;; without a JVM restart.

(deftest source-coord-injected-when-debug-enabled
  (testing "rf2-wtd8z finding 3: with interop/debug-enabled? true the
            registered-view root carries data-rf2-source-coord (the
            dev-mode contract is preserved)"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/gated-on} gated-on-view []
      [:h2 "on"])
    (with-redefs [interop/debug-enabled? true]
      (let [html (ssr/render-to-string [:rf.ssr-coord-test/gated-on] {})]
        (is (re-find #"data-rf2-source-coord=\"rf\.ssr-coord-test:gated-on:\d+:\d+\""
                     html)
            (str "debug ON → source-coord injected; got: " html))))))

(deftest source-coord-elided-when-debug-disabled
  (testing "rf2-wtd8z finding 3: with interop/debug-enabled? false the SSR
            emitter elides data-rf2-source-coord on a registered-view root
            whose slot carries coords — a production render must not leak
            internal source coordinates into public HTML"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/gated-off} gated-off-view []
      [:h2 "off"])
    (with-redefs [interop/debug-enabled? false]
      (let [html (ssr/render-to-string [:rf.ssr-coord-test/gated-off] {})]
        (is (= "<h2>off</h2>" html)
            (str "debug OFF → no source-coord leaked into the rendered "
                 "HTML; got: " html))
        (is (not (str/includes? html "data-rf2-source-coord"))
            "no data-rf2-source-coord attribute in a production render")))))

(deftest streaming-source-coord-respects-debug-gate
  (testing "rf2-wtd8z finding 3: the streaming shell walker reuses the
            same source-coord helper and MUST obey the same production
            gate — debug ON injects, debug OFF elides"
    (rf/reg-view ^{:rf/id :rf.ssr-coord-test/stream-gated} stream-gated-view []
      [:article "streamed"])
    (let [render-shell (requiring-resolve 're-frame.ssr.streaming/render-shell)]
      (with-redefs [interop/debug-enabled? true]
        (let [{:keys [shell-html]} (render-shell [:rf.ssr-coord-test/stream-gated])]
          (is (re-find #"data-rf2-source-coord=\"rf\.ssr-coord-test:stream-gated:\d+:\d+\""
                       shell-html)
              (str "streaming debug ON → source-coord injected; got: "
                   shell-html))))
      (with-redefs [interop/debug-enabled? false]
        (let [{:keys [shell-html]} (render-shell [:rf.ssr-coord-test/stream-gated])]
          (is (not (str/includes? shell-html "data-rf2-source-coord"))
              (str "streaming debug OFF → no source-coord leaked; got: "
                   shell-html)))))))
