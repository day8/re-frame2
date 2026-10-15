(ns re-frame.adapter.uix-frame-context-read-dom-cljs-test
  "Both native boundaries provide the same frame context to use-frame and bare capture-frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(def ^:private observed (atom []))

(defui ProbeCurrentFrame []
  (let [f (:frame (rf.adapter.uix/use-frame))]
    (swap! observed conj f)
    ($ :div (str "f=" f))))

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
  "Mount under act, then unmount."
  [act-fn element]
  (let [mount-node (.createElement js/document "div")
        root       (react-dom-client/createRoot mount-node)]
    (try
      (act-fn (fn [] (.render root element)))
      (finally
        (try (.unmount root) (catch :default _ nil))))))

(deftest frame-context-read-resolves-under-both-boundaries
  (testing "UIx — a descendant hook resolves the shared frame-context under frame-provider (SCOPE) + frame-root (ENSURE) alike"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (binding [rf.frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

            (testing "under frame-provider (SCOPE) → the scoped frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-fcr/provider-frame]
                (rf/make-frame {:id frame-kw
                                :doc "frame-context-read SCOPE probe"})
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-provider {:frame frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (= [true false]
                       [(boolean (some #{frame-kw} @observed))
                        (boolean (some #{rf.adapter.context/no-provider-sentinel :rf/default} @observed))])
                    "the hook resolved the SCOPE-provided frame, never the sentinel or the :rf/default floor")))

            (testing "under frame-root (ENSURE) → the ENSUREd frame id"
              (reset! observed [])
              (let [frame-kw :rf.uix-fcr/root-frame]
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-root {:id frame-kw}
                     ($ ProbeCurrentFrame)))
                (is (= [true true false]
                       [(some? (rf.frame/frame frame-kw))
                        (boolean (some #{frame-kw} @observed))
                        (boolean (some #{rf.adapter.context/no-provider-sentinel :rf/default} @observed))])
                    "frame-root ENSUREd a live frame and installed its id in the SAME context, never the sentinel or the floor")))))))))

(deftest bare-capture-frame-resolves-the-provider-frame-rf2-fzbj28
  (testing "UIx — zero-arity (rf/capture-frame) inside a render resolves the
            context-provided frame through the imperative resolver's second
            tier, with the dynamic tier explicitly cleared"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (binding [rf.frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)

            (is (thrown-with-msg? :default #":rf.error/no-frame-context"
                  (rf/capture-frame))
                "outside any boundary, with the dynamic tier cleared, a bare
                 capture raises :rf.error/no-frame-context — no :rf/default floor")

            (testing "under frame-provider (SCOPE) → the scoped frame id"
              (reset! captured [])
              (let [frame-kw :rf.uix-fcr/bare-capture-provider-frame]
                (rf/make-frame {:id frame-kw
                                :doc "bare capture-frame SCOPE probe"})
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-provider {:frame frame-kw}
                     ($ ProbeBareCaptureFrame)))
                (is (= [true false]
                       [(boolean (some #{frame-kw} @captured))
                        (boolean (some #{rf.adapter.context/no-provider-sentinel :rf/default} @captured))])
                    "bare (rf/capture-frame) resolved the SCOPE-provided frame, never the sentinel or a synthesised floor")))

            (testing "under frame-root (ENSURE) → the ENSUREd frame id"
              (reset! captured [])
              (let [frame-kw :rf.uix-fcr/bare-capture-root-frame]
                (mount-and-render! act-fn
                  ($ rf.adapter.uix/frame-root {:id frame-kw}
                     ($ ProbeBareCaptureFrame)))
                (is (= [true false]
                       [(boolean (some #{frame-kw} @captured))
                        (boolean (some #{rf.adapter.context/no-provider-sentinel} @captured))])
                    "bare (rf/capture-frame) resolves beneath the ENSURE boundary too — the shape the shipped UIx testbed uses — and the sentinel never reached it")))))))))
