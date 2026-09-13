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

;; rf2-fzbj.28 — the IMPERATIVE resolver's SECOND tier, in a UIx render.
;; `use-frame` reads React context and nothing else (rf2-kuky.62), but bare
;; zero-arity `(rf/capture-frame)` funnels through
;; `rf.frame/require-current-frame!` → `resolve-current-frame`, whose order is
;; dynamic-var FIRST, React context SECOND, error last — and the UIx adapter
;; routes that reader's context tier to
;; `rf.adapter.context/function-component-current-frame` at install time. So a
;; bare capture inside a UIx render beneath a boundary resolves the BOUNDARY's
;; frame. The adapter README used to claim the opposite (that a bare capture
;; reads only the dynamic tier and necessarily raises
;; `:rf.error/no-frame-context` under a context-provided frame), contradicting
;; its own shipped testbed; this probe is what keeps the corrected prose true.
(def ^:private captured (atom []))

(defui ProbeBareCaptureFrame []
  (let [f (:frame (rf/capture-frame))]
    (swap! captured conj f)
    ($ :div (str "c=" f))))

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

(deftest bare-capture-frame-resolves-the-provider-frame-rf2-fzbj28
  (testing "UIx — zero-arity (rf/capture-frame) inside a render resolves the
            context-provided frame through the imperative resolver's second
            tier, with the dynamic tier explicitly cleared (rf2-fzbj.28)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          ;; The dynamic tier is cleared, so tier 1 cannot answer and the only
          ;; frame available to the capture is the one the boundary scoped.
          (binding [rf.frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

            ;; NEGATIVE CONTROL FIRST, and it runs OUTSIDE any render: with no
            ;; boundary the context slot holds the no-provider sentinel, so the
            ;; capture has nothing to resolve and fails CLOSED. This is what
            ;; makes the positive row below discriminating — it rules out a
            ;; capture that would have answered anyway.
            (is (thrown-with-msg? :default #":rf.error/no-frame-context"
                  (rf/capture-frame))
                "outside any boundary, with the dynamic tier cleared, a bare
                 capture raises :rf.error/no-frame-context — no :rf/default floor")

            ;; A deliberately NON-default frame id: were the capture answering
            ;; from a synthesised floor rather than from the provider, this row
            ;; would read :rf/default and fail.
            (testing "under frame-provider (SCOPE) → the scoped frame id"
              (reset! captured [])
              (let [frame-kw :rf.uix-fcr/bare-capture-provider-frame]
                (rf/make-frame {:id frame-kw
                                :doc "rf2-fzbj.28 bare capture-frame SCOPE probe"})
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-provider {:frame frame-kw}
                     ($ ProbeBareCaptureFrame)))
                (is (some #{frame-kw} @captured)
                    "bare (rf/capture-frame) resolved the SCOPE-provided frame —
                     the README's old 'dynamic tier only' claim is false")
                (is (not-any? #{:rf/default} @captured)
                    "and it was the provider's frame, not a synthesised floor")))

            (testing "under frame-root (ENSURE) → the ENSUREd frame id"
              (reset! captured [])
              (let [frame-kw :rf.uix-fcr/bare-capture-root-frame]
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-root {:id frame-kw}
                     ($ ProbeBareCaptureFrame)))
                (is (some #{frame-kw} @captured)
                    "bare (rf/capture-frame) resolves beneath the ENSURE boundary
                     too — the shape the shipped UIx testbed already uses")))

            (is (not-any? #{rf.adapter.context/no-provider-sentinel} @captured)
                "the no-provider sentinel never reached the capture as a frame id")))))))
