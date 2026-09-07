(ns re-frame.adapter.uix-frame-context-read-dom-cljs-test
  "UIx DOM/browser coverage for a descendant hook reading the ONE shared
  frame-context that BOTH boundaries install — `frame-provider` (SCOPE) and
  `frame-root` (ENSURE) (rf2-kopcit; rf2-vxgfnd.222 AC#5).

  Both native boundary components write that SAME context: `frame-provider`
  scopes an already-live frame's id into it, `frame-root` ENSUREs a frame at
  commit and provides its id. So a descendant hook reads the wrapping frame's
  keyword under EITHER boundary. The earlier docs framing — the context is only
  populated under a `frame-provider` — was wrong: beneath a `frame-root` with
  no `frame-provider` the correct result is the frame id, and this test pins it.

  THE PROBE READS THROUGH `use-frame` (rf2-kuky.57). It used to call the
  adapter's `use-current-frame`, the narrow raw `useContext` read, which was
  retired as a public Var: it handed back the no-provider sentinel
  (`:rf.frame/no-provider`) as if it were an answer. `use-frame` is the
  hook-shaped `which frame am I in`, resolving from the React context the
  boundary above installed and nothing else (rf2-kuky.62), and it is what the
  two boundary cases below now assert. The third case this file used to carry — no boundary above
  — moved with the sentinel: absence is a LOUD `:rf.error/no-frame-context`,
  already pinned by
  `assert-use-sub-no-provider-no-dynamic-raises-no-frame-context` in the shared
  suite, so it is not duplicated here.

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
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

;; ---- side-channel atom + probe --------------------------------------------
;; The probe records every resolved frame into a side-channel atom the
;; assertions read. A top-level `defui` (uix `defui` defines a Var; it
;; cannot sit inside a `let`).

(def ^:private observed (atom []))

(defui ProbeCurrentFrame []
  (let [f (:frame (rf.adapter.uix/use-frame))]
    (swap! observed conj f)
    ($ :div (str "f=" f))))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- mount-and-render!
  "Mount `element` under a fresh root inside `act`, then unmount. Returns nil."
  [act-fn element]
  (let [mount-node (.createElement js/document "div")
        root       (react-dom-client/createRoot mount-node)]
    (try
      (act-fn (fn [] (.render root element)))
      (finally
        (try (.unmount root) (catch :default _ nil))))))

(deftest frame-context-read-resolves-under-both-boundaries
  (testing "UIx — a descendant hook resolves the shared frame-context under frame-provider (SCOPE) + frame-root (ENSURE) alike (rf2-kopcit / rf2-vxgfnd.222 AC#5)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          ;; `use-frame` reads React context ONLY (rf2-kuky.62), so the
          ;; fixture's ambient `:rf/default` dynamic scope can no longer mask
          ;; the boundary above the probe. Clearing it anyway keeps the rows
          ;; honest about what they measure and costs nothing.
          (binding [rf.frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

            (testing "under frame-provider (SCOPE) → the scoped frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-fcr/provider-frame]
                (rf/make-frame {:id frame-kw
                                :doc "rf2-kopcit frame-context-read SCOPE probe"})
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-provider {:frame frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (some #{frame-kw} @observed)
                    "the hook resolved to the SCOPE-provided frame id from the shared context")
                (is (not-any? #{rf.adapter.context/no-provider-sentinel} @observed)
                    "no sentinel leaked while a frame-provider sat above")))

            (testing "under frame-root (ENSURE) → the ENSUREd frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-fcr/root-frame]
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-root {:id frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (some? (rf.frame/frame frame-kw))
                    "frame-root ENSUREd a live frame at commit")
                (is (some #{frame-kw} @observed)
                    "the hook resolved to the ENSUREd frame id from the SAME shared context — proves frame-root installs it, not only frame-provider")
                (is (not-any? #{rf.adapter.context/no-provider-sentinel} @observed)
                    "no sentinel leaked while a frame-root sat above")))

            ;; The third case this file used to carry — no boundary above — is
            ;; the shared suite's
            ;; `assert-use-sub-no-provider-no-dynamic-raises-no-frame-context`.
            ;; Under `use-frame` absence is a THROW rather than a sentinel, and
            ;; the sentinel was the only reason to observe it from here: what
            ;; both cases above pin is that no boundary ever resolves to the
            ;; `:rf/default` floor.
            (is (not-any? #{:rf/default} @observed)
                "neither boundary resolved to the :rf/default floor")))))))
