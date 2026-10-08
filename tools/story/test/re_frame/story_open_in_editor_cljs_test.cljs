(ns re-frame.story-open-in-editor-cljs-test
  "CLJS tests for Story's 'Open in editor' glue; the URI grammar itself is
  matrix-tested on the JVM in `re-frame.source-coords.editor-uri`. Covered
  here: the chip and its editor / project-root config, the click-time
  navigator seam, the `javascript:` / `data:` / `vbscript:` denylist (there
  is no allowlist), the `:rf.story/open-in-editor` event and
  `:rf.story.fx/open-in-editor` effect, the dev-server endpoint preference,
  and a real macro-stamped Story coordinate surviving a 422 endpoint decline."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]
            [re-frame.source-store :as rf.source-store]
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.ui.open-in-editor :as rf.story.ui.open-in-editor]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; The click launcher `open-coord!` prefers the dev-server endpoint and falls
;; back to the `editor://` URI. The fixture stubs the endpoint to always fall
;; back, so the URI and navigator assertions are deterministic;
;; `open-coord-prefers-endpoint-when-it-succeeds` covers the other half.

(defn- always-fall-back!
  "Endpoint launcher stub: no dev server, so invoke the fallback synchronously."
  [_url fallback!]
  (fallback!))

;; ---- fixtures ------------------------------------------------------------

(defn reset-editor! []
  (rf.story.config/set-editor! :vscode)
  (rf.story.config/set-project-root! nil)
  (rf.source-coords.open-endpoint/set-launcher! always-fall-back!))

(use-fixtures :each {:before reset-editor!
                     :after  reset-editor!})

;; Navigation goes through the swappable `set-navigator!` seam, so tests
;; capture it without mutating `js/window.location`.

(defn- with-stub-navigator
  "Swap the navigator seam for `stub-fn` around `body-fn`, restoring it even on throw."
  [stub-fn body-fn]
  (let [prev (rf.story.ui.open-in-editor/set-navigator! stub-fn)]
    (try
      (body-fn)
      (finally
        (rf.story.ui.open-in-editor/set-navigator! prev)))))

(defn- capturing-navigator
  "Build a navigator fn that pushes its URI argument onto the shared
  `calls` atom. Returns `[navigator-fn, calls-atom]`."
  []
  (let [calls (atom [])
        nav   (fn [uri] (swap! calls conj uri))]
    [nav calls]))

;; ---- chip rendering ------------------------------------------------------

(deftest open-chip-renders-anchor-with-href
  (testing "open-chip returns an <a> hiccup vector when source has :file;
            its :title surfaces file:line for hover"
    (let [coord  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7}
          hiccup (rf.story.ui.open-in-editor/open-chip coord)
          props  (second hiccup)]
      (is (= :a (first hiccup)))
      (is (= {:href        "vscode://file/src/app/views.cljs:42:7"
              :title       "Open in editor — src/app/views.cljs:42"
              :data-test   "story-open-in-editor"
              :data-editor "vscode"}
             (dissoc props :style :on-click)))
      (is (fn? (:on-click props))))))

(deftest open-chip-respects-editor-preference
  (testing "switching editor flips the URI scheme on subsequent renders"
    (let [coord {:file "src/x.cljs" :line 10 :column 1}]
      (rf.story.config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:10:1"
             (:href (second (rf.story.ui.open-in-editor/open-chip coord)))))
      (rf.story.config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second (rf.story.ui.open-in-editor/open-chip coord))))))))

(deftest open-chip-nil-when-source-missing
  (testing "open-chip returns nil when source-coord lacks :file"
    (is (nil? (rf.story.ui.open-in-editor/open-chip nil)))
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:line 10})))
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file ""})))))

(deftest open-chip-for-variant-reads-source-slot
  (testing "open-chip-for-variant pulls :source off the variant body"
    (let [body {:setup []
                :source {:ns 'app.stories
                         :file "src/app/stories.cljs"
                         :line 17
                         :column 3}}
          hiccup (rf.story.ui.open-in-editor/open-chip-for-variant body)]
      (is (= "vscode://file/src/app/stories.cljs:17:3"
             (:href (second hiccup))))))
  (testing "open-chip-for-variant nil when variant body has no :source"
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant {:setup []})))
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant nil)))))

;; The element inspector resolves a coord through `open-source-coord!`: one
;; launcher, denylist and navigator seam for the chip and the inspector.
(deftest open-source-coord!-fires-navigator-with-resolved-uri
  (testing "open-source-coord! resolves through Story config and the navigator
            seam, and returns false without navigating when the coord lacks :file"
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          (rf.story.ui.open-in-editor/open-source-coord! {:file "src/app.cljs" :line 17 :column 3})
          (is (= [false false]
                 [(rf.story.ui.open-in-editor/open-source-coord! nil)
                  (rf.story.ui.open-in-editor/open-source-coord! {:line 10})]))))
      (is (= ["vscode://file/src/app.cljs:17:3"] @calls)))))

(deftest open!-denylist-gates-pre-resolved-uri
  (testing "`open!` re-applies the scheme denylist at the
            pre-resolved {:uri ...} handoff (the :rf.story.fx/open-in-editor reg-fx
            path that bypasses editor-uri's build-time gating). Forbidden
            schemes never reach the navigator; non-dangerous schemes
            (incl. unknown custom) navigate."
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          ;; forbidden script schemes are refused at the click-time seam,
          ;; case-insensitively + leading-whitespace tolerant
          (rf.story.ui.open-in-editor/open! "javascript:alert(1)")
          (rf.story.ui.open-in-editor/open! "JavaScript:alert(1)")
          (rf.story.ui.open-in-editor/open! " data:text/html,xxx")
          (rf.story.ui.open-in-editor/open! "vbscript:msgbox(1)")
          (is (= [] @calls)
              "no forbidden-scheme URI reaches the navigator")
          ;; an unknown, non-dangerous scheme passes through (no allowlist)
          (rf.story.ui.open-in-editor/open! "lapce://open?file=src/x.cljs&line=1")
          (is (= ["lapce://open?file=src/x.cljs&line=1"] @calls)
              "an unknown custom non-dangerous scheme navigates"))))))

;; `forbidden-scheme?` is matrix-tested in the editor-uri ns; these rows cover
;; the chip's wiring: it hides only for the three script schemes, and every
;; other scheme — http:/https: and unknown custom ones included — renders, as
;; in Xray.
(deftest open-chip-hides-when-custom-template-resolves-to-forbidden-scheme
  (testing "open-chip is nil only when a custom template resolves to one of
            the three forbidden script schemes"
    (doseq [template ["javascript:alert(1)" "data:text/html,xxx" "vbscript:msgbox(1)"]]
      (rf.story.config/set-editor! {:custom template})
      (is (nil? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})) template))))

(deftest open-chip-renders-for-non-forbidden-custom-scheme
  (testing "open-chip renders for any non-forbidden scheme — catalogued
            long-tail, http:/https: (the localhost footgun the spec accepts)
            and unknown custom schemes an allowlist would hide — reading the
            :custom template live from config"
    (doseq [[template coord href]
            [["subl://open?path={path}&line={line}" {:file "src/x.cljs" :line 5}
              "subl://open?path=src/x.cljs&line=5"]
             ["emacsclient://{path}" {:file "src/x.cljs"} "emacsclient://src/x.cljs"]
             ["http://localhost:3000/{path}" {:file "src/x.cljs"} "http://localhost:3000/src/x.cljs"]
             ["lapce://open?file={path}&line={line}" {:file "src/x.cljs" :line 8}
              "lapce://open?file=src/x.cljs&line=8"]
             ["zed://file/{path}:{line}" {:file "src/x.cljs" :line 5 :column 2}
              "zed://file/src/x.cljs:5"]]]
      (rf.story.config/set-editor! {:custom template})
      (is (= [href "custom"]
             ((juxt :href :data-editor) (second (rf.story.ui.open-in-editor/open-chip coord))))
          template))))

(deftest open-chip-for-variant-hides-on-forbidden-scheme
  (testing "open-chip-for-variant inherits the denylist gate"
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant
                {:source {:file "src/x.cljs" :line 1}})))))

;; An OS-side editor cannot open a classpath-relative path, so the chip
;; prepends `:rf.story/project-root` (set once at boot via
;; `rf.story/configure!`) when one is configured.
(deftest open-chip-prefixes-with-project-root
  (testing "set-project-root! plumbs the on-disk root through the chip and
            round-trips through get-project-root; blank normalises to unset"
    (rf.story.config/set-project-root! "C:/Users/me/code/my-app")
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
           (:href (second (rf.story.ui.open-in-editor/open-chip
                            {:file "src/app/views.cljs" :line 42 :column 7})))))
    (is (= ["/abs/code" nil nil]
           (mapv (fn [root]
                   (rf.story.config/set-project-root! root)
                   (rf.story.config/get-project-root))
                 ["/abs/code" nil ""])))))

(deftest open-chip-project-root-survives-editor-change
  (testing "switching editor keeps project-root applied to the new scheme"
    (rf.story.config/set-project-root! "/abs/code")
    (rf.story.config/set-editor! :cursor)
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "cursor://file//abs/code/src/x.cljs:1:1"
             (:href (second hiccup)))))
    (rf.story.config/set-editor! :idea)
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "idea://open?file=/abs/code/src/x.cljs&line=1&column=1"
             (:href (second hiccup)))))))

;; The chip navigates with `Location.assign(uri)`, since some Chromium builds
;; silently no-op `window.location = uri` for custom schemes, and `open!` logs
;; the URI so a failed OS handoff is diagnosable from devtools.
(deftest click-handler-calls-navigator-with-uri
  (testing "clicking the chip invokes the navigator seam
            with the same URI carried in the :href"
    (let [hiccup       (rf.story.ui.open-in-editor/open-chip
                         {:file "src/x.cljs" :line 42 :column 7})
          props        (second hiccup)
          href         (:href props)
          on-click     (:on-click props)
          fake-evt     #js {:preventDefault (fn [])}
          [nav calls]  (capturing-navigator)]
      (with-stub-navigator nav
        #(on-click fake-evt))
      (is (= ["vscode://file/src/x.cljs:42:7"]
             @calls)
          "navigator called exactly once with the chip's href URI")
      (is (= href (first @calls))
          "the navigation URI is identical to the rendered href"))))

(deftest click-handler-prevents-default
  (testing "the click handler preventDefaults so the
            browser doesn't double-navigate (once via the <a href>
            native click, once via the JS Location.assign call)"
    (let [hiccup       (rf.story.ui.open-in-editor/open-chip
                         {:file "src/x.cljs" :line 1})
          on-click     (:on-click (second hiccup))
          prevented?   (atom false)
          fake-evt     #js {:preventDefault (fn [] (reset! prevented? true))}
          [nav _]      (capturing-navigator)]
      (with-stub-navigator nav
        #(on-click fake-evt))
      (is @prevented?
          "the click handler must call e.preventDefault()"))))

(deftest windows-backslash-path-uri-shape
  (testing "Windows project-root with trailing backslash
            still produces a valid URI (trailing separators stripped)"
    (rf.story.config/set-project-root! "C:\\Users\\me\\code\\myapp\\")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file/C:\\Users\\me\\code\\myapp/src/app/views.cljs:1:1"
             (:href (second hiccup)))
          "trailing separator stripped; backslashes inside the root
           preserved (VSCode accepts both on Windows)"))))

;; Hosts that do not render the chip (agents over MCP, custom panels) dispatch
;; `[:rf.story/open-in-editor coord]`, and the registered fx fires the URI
;; through the same denylist gate, as Xray's pairing does.
;;
;; The tests capture the fx args through a per-frame `:fx-overrides` fn value,
;; never a second `rf/reg-fx`: a test-namespace registration would leave the
;; fx id claimed by two namespaces in the source store, and the next
;; `rf/make-frame` anywhere would fail image assembly with
;; `:rf.error/image-duplicate-id`. A fn-value override runs without a registry
;; lookup of the id it shadows, so `assert-production-registration!` is the
;; positive control and `production-fx-navigates-without-any-override` drives
;; the real effect end to end.

(defonce ^:private captured-editor-fx (atom []))

(def ^:private capture-frame
  "Frame the dispatch-path tests land on. Private to this ns: it carries the
  `:fx-overrides` capture, and `:rf/default` must stay override-free for the
  suites that dispatch through it."
  :story.open-in-editor/capture)

(def ^:private production-fx-ns
  "The ONE namespace allowed to own `[:fx :rf.story.fx/open-in-editor]`."
  "re-frame.story.ui.open-in-editor")

(defn- ensure-adapter!
  "Install the plain-atom adapter unless one is installed: `rf/make-frame`
  needs a state-container factory."
  []
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil)))

(defn- assert-production-registration!
  "POSITIVE CONTROL: `:rf.story.fx/open-in-editor` is registered, and only by
  production — so no capture below stands in for nothing, and no test
  namespace shadows the id."
  []
  (is (= #{production-fx-ns}
         (set (keys (rf.source-store/descriptors-for
                      :fx :rf.story.fx/open-in-editor))))))

(defn- install-with-capture!
  "Install Story's open-in-editor handlers, check the production registration,
  and build `capture-frame` with an `:fx-overrides` fn value that records the
  fx args plus the coord resolved through the chip's own `resolve-uri`."
  []
  (reset! captured-editor-fx [])
  (ensure-adapter!)
  (rf.story.ui.open-in-editor/install!)
  (assert-production-registration!)
  ;; The event dispatches under a carried frame stamp (EP-0002), so the
  ;; with-frame dispatches need a live frame; re-making it is idempotent.
  (rf/make-frame
    {:id capture-frame
     :doc "open-in-editor dispatch-path test frame (carries the fx capture)"
     :fx-overrides
     {:rf.story.fx/open-in-editor
      (fn [_ctx args]
        (swap! captured-editor-fx conj
               (assoc args
                      :uri (when-let [coord (:source-coord args)]
                             (rf.story.ui.open-in-editor/resolve-uri coord)))))}}))

(deftest open-in-editor-event-resolves-every-payload-shape
  (testing "`:rf.story/open-in-editor` accepts a bare coord, the
            `{:source-coord coord}` wrapper some panels use, and a
            `\"file:line\"` display string bare or wrapped (panels that
            flatten coords at projection time dispatch them as strings).
            Each produces exactly one `:rf.story.fx/open-in-editor` fx whose
            `:source-coord` resolves to the vscode:// URI; a display string's
            missing column falls to the editor-uri default of 1."
    (doseq [[payload uri] [[{:file "src/app/events.cljs" :line 17 :column 3}
                            "vscode://file/src/app/events.cljs:17:3"]
                           [{:source-coord {:file "src/x.cljs" :line 5 :column 1}}
                            "vscode://file/src/x.cljs:5:1"]
                           [{:source-coord "src/app/events.cljs:42"}
                            "vscode://file/src/app/events.cljs:42:1"]
                           ["src/x.cljs:7"
                            "vscode://file/src/x.cljs:7:1"]]]
      (install-with-capture!)
      (with-frame capture-frame
        (rf/dispatch-sync [:rf.story/open-in-editor payload]))
      (is (= [uri] (map :uri @captured-editor-fx))
          (str "one fx resolving to " uri " for payload " (pr-str payload))))))

(deftest open-in-editor-event-rejects-forbidden-scheme
  (testing "a custom template that resolves to a forbidden script
            scheme still fires exactly one fx — the handler doesn't
            short-circuit — and its resolved URI is nil, which `open!`
            refuses to navigate"
    (install-with-capture!)
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= [nil] (map :uri @captured-editor-fx)))))

(deftest open-in-editor-fx-receives-source-coord-key-rf2-wn3bh
  (testing "`:rf.story/open-in-editor` emits the structured
            `{:source-coord {...}}` shape (NOT a pre-resolved `:uri`) so
            `:rf.story.fx/open-in-editor` can prefer the dev-server endpoint and fall
            back to the `editor://` URI"
    (install-with-capture!)
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= {:file "src/x.cljs" :line 1}
           (:source-coord (first @captured-editor-fx)))
        "the structured coord rides the fx verbatim — the endpoint
         resolves the relative :file at runtime on the server")))

(deftest production-fx-navigates-without-any-override
  (testing "the real registered :rf.story.fx/open-in-editor, on a frame with no
            overrides, reaches Story's navigator seam — if install! stopped
            registering the effect, only this test would go red"
    (ensure-adapter!)
    (rf.story.ui.open-in-editor/install!)
    (assert-production-registration!)
    (rf.story.config/set-editor! :vscode)
    (rf/make-frame {:id  :story.open-in-editor/production
                    :doc "no-override frame — drives the REAL effect"})
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          (with-frame :story.open-in-editor/production
            (rf/dispatch-sync [:rf.story/open-in-editor
                               {:file "src/app.cljs" :line 17 :column 3}]))))
      (is (= ["vscode://file/src/app.cljs:17:3"] @calls)))))

(deftest open-coord-prefers-endpoint-when-it-succeeds
  (testing "when the endpoint launcher reports success, the URI fallback does not fire"
    (let [[nav calls] (capturing-navigator)
          prev        (rf.source-coords.open-endpoint/set-launcher! (fn [_url _fallback!] nil))]
      (try
        (with-stub-navigator nav
          #(rf.story.ui.open-in-editor/open-coord! {:file "src/x.cljs" :line 1}))
        (is (= [] @calls))
        (finally
          (rf.source-coords.open-endpoint/set-launcher! prev))))))

;; The URI rows above use hand-typed coords; this block reads back the
;; coordinate this very compile stamped. `coords-form` absolutises `:file` on
;; the JVM at macroexpansion, so when the endpoint declines with 422 and no
;; project root is configured (every repository dev testbed), the
;; `windsurf://` fallback must still name an absolute path. The real
;; `fetch-launcher!` runs over a stubbed `globalThis.fetch`; only its promise
;; is captured, so the async test can await the decision.

(def ^:private real-fetch
  "The platform `fetch`, captured at load: a stub left installed would answer
  for every later namespace in the shared `:node-test` build, so the test
  asserts the global is `identical?` to this again afterwards."
  (.-fetch js/globalThis))

(defn- strip-uri-position
  "`windsurf://file/<path>:<line>:<column>` → `<path>`."
  [uri]
  (-> uri
      (subs (count "windsurf://file/"))
      (str/replace #":\d+:\d+$" "")))

(deftest endpoint-422-falls-back-to-an-absolute-uri-for-a-real-story-coord
  (testing "a real `rf.story/reg-story` coordinate, stamped by this
            compile, reaches the editor through the 422 fallback as an
            ABSOLUTE path"
    (rf.story/reg-story :story.source-coords.cljs-pin
      {:doc "Fixture — this form's own coordinate is the subject."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.cljs-pin))]
      (is (some? coord) "the registration carries a :source coord at all")
      (is (some? (:file coord)) "and that coord carries a :file")
      (is (rf.source-coords.editor-uri/absolute-path? (:file coord))
          (str "the macro must bake an ABSOLUTE :file at expansion — got "
               (pr-str (:file coord))))
      (is (str/ends-with? (str/replace (:file coord) "\\" "/")
                          "re_frame/story_open_in_editor_cljs_test.cljs")
          "and it must still end in the classpath-relative tail it started as")
      (rf.story.config/set-editor! :windsurf)
      (rf.story.config/set-project-root! nil)
      (async done
        ;; The navigator stub is installed for the whole ASYNC duration, not
        ;; just the synchronous call: `fetch-launcher!` runs `fallback!` in a
        ;; promise callback, so a `with-stub-navigator` scope would already
        ;; have restored `default-navigator!` — which reaches for `js/window`
        ;; and blows up under node. Worse, `fetch-launcher!`'s own `.catch`
        ;; would then run `fallback!` a SECOND time on that throw.
        (let [[nav calls]  (capturing-navigator)
              pending      (atom nil)
              orig-fetch   (.-fetch js/globalThis)
              prev-nav     (rf.story.ui.open-in-editor/set-navigator! nav)
              prev-launch  (rf.source-coords.open-endpoint/set-launcher!
                             (fn [url fallback!]
                               ;; The REAL launcher; the atom only lets the
                               ;; async test await the decision.
                               (reset! pending
                                       (rf.source-coords.open-endpoint/fetch-launcher! url fallback!))))
              restore!     (fn []
                             (set! (.-fetch js/globalThis) orig-fetch)
                             (rf.source-coords.open-endpoint/set-launcher! prev-launch)
                             (rf.story.ui.open-in-editor/set-navigator! prev-nav)
                             (rf.story.config/set-editor! :vscode))]
          (set! (.-fetch js/globalThis)
                (fn [_url _opts]
                  (js/Promise.resolve #js {:ok false :status 422})))
          (rf.story.ui.open-in-editor/open-coord! coord)
          ;; Bind the launcher's promise to a LOCAL before threading. A
          ;; multi-step form in the `->` head (an `or`, say) is lowered by the
          ;; compiler to an awaited async IIFE, and the
          ;; await unwraps the promise to the `nil` `fetch-launcher!`'s own
          ;; `.then` returns, leaving `.then` to be called on null.
          (let [p @pending]
            (if (nil? p)
              (do (restore!)
                  (is false "the launcher seam was never reached — the endpoint
                             is supposed to be PREFERRED, so `fetch-launcher!`
                             must have run")
                  (done))
              (-> p
                  (.then (fn [_]
                           (restore!)
                           (is (= 1 (count @calls))
                               "the 422 decline ran the URI fallback exactly once")
                           (let [uri  (first @calls)
                                 path (strip-uri-position uri)]
                             (is (str/starts-with? uri "windsurf://file/")
                                 "the fallback is the editor:// URI path")
                             (is (rf.source-coords.editor-uri/absolute-path? path)
                                 (str "the URI the editor receives must name an "
                                      "ABSOLUTE path — got " (pr-str path)))
                             (is (not (str/starts-with? path "re_frame/"))
                                 "a bare classpath-relative path in the URI is
                                  what no editor can open"))
                           (is (identical? real-fetch (.-fetch js/globalThis))
                               "the fetch stub was not left installed")
                           (done)))
                  (.catch (fn [err]
                            (restore!)
                            (is false (str "the 422 fallback path threw: " err))
                            (done)))))))))))

(deftest project-root-knob-is-inert-over-an-absolutised-story-coord
  (testing "the public `:rf.story/project-root` option serves
            external / static / non-shadow hosts, and is inert over a
            coordinate the macro already absolutised: `compose-path` passes an
            absolute `:file` through rather than double-prefixing it"
    (rf.story/reg-story :story.source-coords.cljs-root-pin
      {:doc "Fixture — same coordinate, two project-root settings."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.cljs-root-pin))]
      (rf.story.config/set-editor! :windsurf)
      (rf.story.config/set-project-root! nil)
      (let [bare (rf.story.ui.open-in-editor/resolve-uri coord)]
        (rf.story.config/set-project-root! "/some/external/root")
        (is (= bare (rf.story.ui.open-in-editor/resolve-uri coord))
            "an absolute :file is not re-rooted by the knob")
        (rf.story.config/set-project-root! nil)
        (rf.story.config/set-editor! :vscode)))))
