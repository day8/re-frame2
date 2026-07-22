(ns re-frame.adapter.uix-use-current-frame-dom-cljs-test
  "UIx DOM/browser coverage for `use-current-frame` reading the ONE shared
  frame-context that BOTH boundaries install — `frame-provider` (SCOPE) and
  `frame-root` (ENSURE) — and returning the no-provider sentinel when NEITHER
  sits above (rf2-kopcit; rf2-vxgfnd.222 AC#5).

  `use-current-frame` is the narrow raw `useContext` read of core's shared
  `frame-context`. Both native boundary components write that SAME context:
  `frame-provider` scopes an already-live frame's id into it, `frame-root`
  ENSUREs a frame at commit and provides its id. So a descendant hook reads
  the wrapping frame's keyword under EITHER boundary, and the context default
  (`:rf.frame/no-provider`, explicitly NOT `:rf/default`) under neither. The
  earlier docs framing — sentinel iff no `frame-provider` above — was wrong:
  beneath a `frame-root` with no `frame-provider` the correct result is the
  frame id, and this test pins it.

  The frame-root case needs a real client commit — its ENSURE runs in
  `useLayoutEffect` — so this is a react-dom/client + act DOM test, not an
  SSR/renderToString one (effects do not fire under renderToString).

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` (ns-regexp
  `-dom-cljs-test$`) discovers it for the real DOM assertions; `:node-test`'s
  `cljs-test$` regex also matches, where each test self-gates on `(browser?)`
  and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.context :as context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- side-channel atom + probe --------------------------------------------
;; The probe records every `use-current-frame` return into a side-channel atom
;; the assertions read. A top-level `defui` (uix `defui` defines a Var; it
;; cannot sit inside a `let`).

(def ^:private observed (atom []))

(defui ProbeCurrentFrame []
  (let [f (uix-adapter/use-current-frame)]
    (swap! observed conj f)
    ($ :div (str "f=" f))))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(defn- mount-and-render!
  "Mount `element` under a fresh root inside `act`, then unmount. Returns nil."
  [act-fn element]
  (let [mount-node (.createElement js/document "div")
        root       (react-dom-client/createRoot mount-node)]
    (try
      (act-fn (fn [] (.render root element)))
      (finally
        (try (.unmount root) (catch :default _ nil))))))

(deftest use-current-frame-reads-shared-context-under-both-boundaries
  (testing "UIx — use-current-frame reads the shared frame-context under frame-provider (SCOPE) + frame-root (ENSURE), and the sentinel under neither (rf2-kopcit / rf2-vxgfnd.222 AC#5)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          ;; `use-current-frame` is the RAW context read — it never consults
          ;; the dynamic-var tier — so clearing the fixture's ambient
          ;; `:rf/default` dynamic scope is not strictly required; we do it so
          ;; the three cases turn purely on the React-context boundary above
          ;; the probe.
          (binding [frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

            (testing "under frame-provider (SCOPE) → the scoped frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-ucf/provider-frame]
                (rf/make-frame {:id frame-kw
                                :doc "rf2-kopcit use-current-frame SCOPE probe"})
                (mount-and-render! act-fn
                  ($ uix-adapter/frame-provider {:frame frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (some #{frame-kw} @observed)
                    "use-current-frame read the SCOPE-provided frame id from the shared context")
                (is (not-any? #{context/no-provider-sentinel} @observed)
                    "no sentinel leaked while a frame-provider sat above")))

            (testing "under frame-root (ENSURE) → the ENSUREd frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-ucf/root-frame]
                (mount-and-render! act-fn
                  ($ uix-adapter/frame-root {:id frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (some? (frame/frame frame-kw))
                    "frame-root ENSUREd a live frame at commit")
                (is (some #{frame-kw} @observed)
                    "use-current-frame read the ENSUREd frame id from the SAME shared context — proves frame-root installs it, not only frame-provider")
                (is (not-any? #{context/no-provider-sentinel} @observed)
                    "no sentinel leaked while a frame-root sat above")))

            (testing "under neither boundary → the no-provider sentinel"
              (reset! observed [])
              (mount-and-render! act-fn ($ ProbeCurrentFrame))
              (is (some #{context/no-provider-sentinel} @observed)
                  "with no boundary above, use-current-frame returns the context default sentinel (:rf.frame/no-provider)")
              (is (not-any? #{:rf/default} @observed)
                  "the sentinel is emphatically NOT the :rf/default floor"))))))))
