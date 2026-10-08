(ns day8.re-frame2-xray.views.edn-inspector-mount-state-cljs-test
  "THE PER-MOUNT STORE — the widget's ResizeObserver, width debounce and
  Editscript projection cache, held in one module-level store keyed by
  lifecycle key and released when React calls the container `:ref` with nil.
  `edn-inspector-view` is a Fresco boundary with no form-2 outer body to
  hold them, and state in a module-level map is exactly the shape that
  passes its happy path and leaks on unmount.

    R5  the projection cache goes with the rest of the entry on unmount.
    R7  a RETAINED callback re-attached after a nil call (React StrictMode's
        setup → cleanup → setup) gets its dispatcher and its memo back.
    R9  two live mounts of ONE logical `:mount-id` under two frames stay
        independent, and releasing one leaves the other whole.

  `js/ResizeObserver` does not exist under Node, so these rows are about the
  STORE; the observer's lifecycle is read off a real React commit in
  `edn_inspector_fresco_boundary_dom_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(defn- unmount!
  "What React does at unmount: call the container ref with nil."
  [ref-fn]
  (ref-fn nil))

(defn- fake-el
  "A stand-in container element. `measure-and-dispatch!` reads `clientWidth`
  and nothing else. Mutable, so a width CHANGE is `set!` on ONE element that
  is detached and re-attached."
  [w]
  #js {:clientWidth w})

(deftest r5-the-projection-cache-lives-and-dies-with-the-mount
  ;; The projection of a large app-db diff is not small, and one that
  ;; outlives its mount is a leak that grows with every epoch stepped through.
  (let [before (ei/mount-state-count)
        mid    "r5-mount"
        ref-fn (ei/container-ref-for mid identity)]
    ;; `:before` puts the widget in diff mode, which is what computes a projection.
    (ei/render-inspector
      {:value         {:a 1 :b 2}
       :opts          {:panel-id :r5 :before {:a 1}}
       :mount-id      mid
       :dispatch-fn   identity
       :container-ref ref-fn
       :expansion-map {}
       :zoom-map      nil
       :widths        {}})
    (is (contains? (ei/mount-state-held mid) :projection)
        "the diff render left its projection in the store")
    (unmount! ref-fn)
    (is (nil? (ei/mount-state-held mid))
        "unmount released the projection with the rest of the entry")
    (is (= before (ei/mount-state-count))
        "and the store is back where it started")))

(deftest r7-a-retained-ref-callback-reattaches-with-its-dispatcher-and-memo
  ;; The nil call releases the WHOLE entry, dispatcher and memo included, so
  ;; the re-attachment must put both back: without the dispatcher every later
  ;; width is swallowed, and without the memo the next render mints a fresh
  ;; ref and React tears the observer down on every pass.
  (let [dispatched (atom [])
        mid        "r7-mount"
        el         (fake-el 100)
        ref-fn     (ei/container-ref-for mid #(swap! dispatched conj %))]
    (ref-fn el)
    (ref-fn nil)
    (set! (.-clientWidth el) 200)
    (ref-fn el)
    (is (identical? ref-fn (ei/container-ref-for mid identity))
        "the store answers the re-attached callback again")
    (unmount! ref-fn)
    (is (= [[:rf.xray.edn-inspector/set-width mid 100]
            [:rf.xray.edn-inspector/clear-width mid]
            [:rf.xray.edn-inspector/set-width mid 200]
            [:rf.xray.edn-inspector/clear-width mid]]
           @dispatched)
        "attach, release, re-attach and final release each reached the dispatcher")))

(def ^:private shared-logical-id
  "The App-db panel's real top-section mount-id — a logical surface name, the
  same string from every panel that renders one."
  "app-db-state/top")

(deftest r9-two-live-mounts-of-one-logical-id-stay-independent
  ;; A panel's `:mount-id` is a stable LOGICAL name, so a gallery of panel
  ;; variants presents the same one from several frames at once. The event
  ;; payload is the logical id in both sinks: the width slot is per-frame,
  ;; and the frame is what separates them.
  (let [before (ei/mount-state-count)
        k-a    (ei/lifecycle-key :frame/a shared-logical-id)
        k-b    (ei/lifecycle-key :frame/b shared-logical-id)
        sink-a (atom [])
        sink-b (atom [])
        ref-a  (ei/container-ref-for k-a shared-logical-id
                                     #(swap! sink-a conj %))
        ref-b  (ei/container-ref-for k-b shared-logical-id
                                     #(swap! sink-b conj %))]
    (ref-a (fake-el 600))
    (ref-b (fake-el 300))
    (is (= [[[:rf.xray.edn-inspector/set-width shared-logical-id 600]]
            [[:rf.xray.edn-inspector/set-width shared-logical-id 300]]]
           [@sink-a @sink-b])
        "each mount measured itself into its OWN frame's dispatcher")

    (reset! sink-a [])
    (reset! sink-b [])
    (unmount! ref-b)
    (is (= [[] [[:rf.xray.edn-inspector/clear-width shared-logical-id]]]
           [@sink-a @sink-b])
        "the clear went to the leaving mount's frame and nothing to the survivor's")
    (is (contains? (ei/mount-state-held k-a) :ref)
        "the mount still on screen is untouched")

    (unmount! ref-a)
    (is (= before (ei/mount-state-count))
        "both released, store back where it started")))
