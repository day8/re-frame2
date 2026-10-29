(ns re-frame.substrate.spine-conditional-root-dom-cljs-test
  "An adapter that installs the spine's wrap-view and carries no
  `:componentize-view` — Fresco's shape — registers the bare frame-aware
  wrapper as the view's head, and React mounts it as a plain function
  component. A root that toggles between an element and nil keeps its unmount
  sentinel all the same: no `:rf.view/unmounted` while the view stays mounted,
  and exactly one at its real teardown (Spec 009 §`:rf.view/unmounted`). The
  UIx head's counterpart is `re-frame.adapter.uix-view-conditional-root-dom-cljs-test`."
  (:require ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [with-browser-act]]))

(def ^:private spine-adapter
  (rf.substrate.spine/make-react-adapter
    (rf.substrate.spine/make-react-spine
      {:gensym-prefix-sub     "conditional-root-sub-"
       :gensym-prefix-derived "conditional-root-derived-"
       :gensym-prefix-use-sub "conditional-root-use-sub-"
       :use-memo              React/useMemo
       :use-callback          React/useCallback
       :use-context           React/useContext})
    {:kind :rf.adapter/spine-conditional-root :frame-provider nil}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter spine-adapter}))

(def ^:private frame-id :rf.spine-conditional-root/frame)
(def ^:private view-id :rf.spine-conditional-root/banner)

(deftest a-bare-head-toggling-between-an-element-and-nil-keeps-its-sentinel
  (with-browser-act
    (fn [act]
      (let [unmounted (atom 0)
            listener  ::recorder]
        (rf/make-frame {:id frame-id})
        (rf/reg-view* view-id (fn [^js props]
                                (when (.-show props)
                                  (React/createElement "p" #js {:data-testid "banner"} "hi"))))
        (rf.trace.tooling/register-listener! listener
          (fn [ev]
            (when (and (= :rf.view/unmounted (:operation ev))
                       (= view-id (:rf.view/id (:tags ev))))
              (swap! unmounted inc))))
        (let [node    (.createElement js/document "div")
              root    (react-dom-client/createRoot node)
              render! (fn [show?]
                        (binding [rf.frame/*current-frame* nil]
                          (act (fn []
                                 (.render root
                                   (rf.adapter.context/provider-element
                                     frame-id
                                     (React/createElement (rf/view view-id) #js {:show show?})))))))
              shown?  (fn [] (some? (.querySelector node "[data-testid='banner']")))]
          (try
            (is (= [true false true false]
                   (mapv (fn [show?] (render! show?) (shown?)) [true false true false]))
                "the root rendered, vanished, came back and vanished again")
            (is (zero? @unmounted) "no :rf.view/unmounted while the view stays mounted")
            ;; The control: a real teardown, taken while the view renders nil.
            (act (fn [] (.unmount root)))
            (is (= 1 @unmounted) "the real teardown fires exactly one :rf.view/unmounted")
            (finally
              (rf.trace.tooling/unregister-listener! listener)
              (try (.unmount root) (catch :default _ nil))
              (rf/destroy-frame! frame-id))))))))
