(ns day8.re-frame2-xray.views.edn-inspector-mount-state-cljs-test
  "THE PER-MOUNT STORE — the three pieces of per-mount state, and the key
  they are held under.

  A form-2 component could keep its ResizeObserver, its width debounce and
  its Editscript projection cache in an outer body that runs once per
  mount and is collected with it. `edn-inspector-view` is a
  Fresco boundary — a real React function component — and has no such
  body: everything in it runs on every render. So the three live in one
  module-level store keyed by mount-id, with an EXPLICIT release.

  State held in a module-level map rather than a closure is exactly the
  shape that passes its happy path and leaks on unmount, so the rows here
  are written against the two ways it goes wrong rather than against the
  way it goes right:

    R5  the projection cache is held under the same key and goes with the
        rest, so a diff mount releases its Editscript result too. A cache
        that survives its mount is the leak that costs REAL memory.
    R6  releasing a mount that holds nothing is a no-op that dispatches
        nothing — the negative case, and the one that fires if React
        calls a ref with nil twice, which it is entitled to do.
    R7  a RETAINED callback re-attached after a nil call still dispatches
        — React StrictMode's setup → cleanup → setup cycle, which every
        other row here misses by construction.
    R8  and the store answers that retained callback again afterwards,
        so the re-attachment does not cost the memo — the `:ref`
        callback memoised per mount, without which a fresh closure per
        render would make React tear the observer down and stand it up
        again on every pass while every rendering assertion stayed green.
    R9  two live mounts of ONE logical surface — the case a stable
        `:mount-id` makes routine and which every row above misses by
        giving each mount an id of its own. Positive half and
        negative control, the control being the failure written out.

  ## No DOM here, deliberately

  `js/ResizeObserver` does not exist under Node, so the observer branch of
  the ref callback is skipped and the rows below are about the STORE's
  lifecycle rather than the observer's. The observer's own lifecycle is
  read off a real React commit in
  `edn_inspector_fresco_boundary_dom_cljs_test`, which is the browser
  lane. Splitting them this way is not a compromise: the store is where
  the release actually happens, and it is assertable here in milliseconds
  against a browser lane's seconds."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(defn- unmount!
  "What React does at unmount: call the container ref with nil."
  [ref-fn]
  (ref-fn nil))

(deftest r5-the-projection-cache-lives-and-dies-with-the-mount
  (testing "a diff render stores its Editscript projection
            under the mount's key, and unmount takes it with everything
            else. This is the piece with real bytes behind it: the
            projection of a large app-db diff is not small, and one that
            outlives its mount is a leak that grows with every epoch the
            operator steps through."
    (let [before (ei/mount-state-count)
          mid    "r5-mount"
          ref-fn (ei/container-ref-for mid identity)]
      ;; A diff render — `:before` is what puts the widget in diff mode
      ;; and so what makes it compute a projection at all.
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
          "and the store is back where it started"))))

(deftest r6-releasing-nothing-is-a-no-op
  (testing "a ref called with nil for a mount the store does
            not hold does nothing and dispatches nothing.

            React is entitled to detach a ref it has already detached, and
            a hot reload can leave a ref fn alive over a store that has
            been rebuilt. Neither should reach the dispatcher: a stray
            `:clear-width` would be a write into a frame's app-db on
            behalf of a mount that no longer exists."
    (let [dispatched (atom [])
          mid        "r6-mount"
          ref-fn     (ei/container-ref-for mid #(swap! dispatched conj %))]
      (unmount! ref-fn)
      (is (= 1 (count @dispatched))
          "the real release cleared the width slot exactly once")
      (is (= :rf.xray.edn-inspector/clear-width (first (first @dispatched)))
          "and it was the clear-width event")
      (reset! dispatched [])
      (unmount! ref-fn)
      (unmount! ref-fn)
      (is (= [] @dispatched)
          "two further detaches of an already-released mount dispatch NOTHING")
      (is (nil? (ei/mount-state-held mid))
          "and the store is still empty for it"))))

;; ---------------------------------------------------------------------------
;; RETAINED-CALLBACK RE-ATTACHMENT.
;;
;; Every row above either never re-attaches at all (R5) or detaches an
;; already-released callback (R6). React does neither: StrictMode runs a
;; callback ref setup → cleanup → setup with the SAME function, and the
;; cleanup half calls `release-mount!`, which drops the WHOLE entry —
;; dispatcher included. A second setup that rebuilt measurement and
;; observer state around a store with no dispatcher in it would stop width
;; dispatch while everything else went on looking healthy.
;;
;; The two rows below are split so each failure names one thing: R7 is the
;; dispatcher, R8 is the memo.
;; ---------------------------------------------------------------------------

(defn- fake-el
  "A stand-in container element. `measure-and-dispatch!` reads `clientWidth`
  and nothing else, and `js/ResizeObserver` does not exist under Node, so
  this is the whole of the DOM these rows need. Mutable, so a width CHANGE
  is `set!` rather than a second element — the point is that ONE element is
  detached and re-attached."
  [w]
  #js {:clientWidth w})

(deftest r7-a-retained-ref-callback-reattaches-with-its-dispatcher
  (testing "element → nil → the SAME element still dispatches.

            This is the sequence React StrictMode performs on every
            callback ref, so it is routine rather than adversarial. An
            entry that came back carrying its width measurement and its
            observer, but not the dispatcher that went with the release,
            would dispatch NOTHING at the third step below, and nothing on
            screen or in the store would say so."
    (let [before     (ei/mount-state-count)
          dispatched (atom [])
          mid        "r7-mount"
          el         (fake-el 100)
          ref-fn     (ei/container-ref-for mid #(swap! dispatched conj %))]
      ;; setup
      (ref-fn el)
      (is (= [[:rf.xray.edn-inspector/set-width mid 100]] @dispatched)
          "first attach measured and dispatched")
      ;; cleanup — StrictMode's nil call
      (reset! dispatched [])
      (ref-fn nil)
      (is (= [[:rf.xray.edn-inspector/clear-width mid]] @dispatched)
          "the nil call released the mount and cleared the width slot")
      (is (nil? (ei/mount-state-held mid))
          "and the entry really went, dispatcher with it")
      ;; setup again, with the RETAINED callback — the path under test
      (reset! dispatched [])
      (set! (.-clientWidth el) 200)
      (ref-fn el)
      (is (= [[:rf.xray.edn-inspector/set-width mid 200]] @dispatched)
          "re-attaching the retained callback dispatches the new width")
      ;; and it keeps working, rather than dispatching once and dying
      (reset! dispatched [])
      (set! (.-clientWidth el) 300)
      (ref-fn el)
      (is (= [[:rf.xray.edn-inspector/set-width mid 300]] @dispatched)
          "and subsequent width changes still dispatch")
      ;; final detach returns to baseline, exactly as for a mount that
      ;; was never re-attached
      (reset! dispatched [])
      (unmount! ref-fn)
      (is (= [[:rf.xray.edn-inspector/clear-width mid]] @dispatched)
          "the final detach still clears the width slot")
      (is (nil? (ei/mount-state-held mid))
          "and removes the whole entry")
      (is (= before (ei/mount-state-count))
          "store back to the size it started at"))))

(deftest r8-re-attachment-restores-the-memo
  (testing "after a retained callback re-attaches, the store
            answers THAT callback again.

            The memo is what stops React tearing the ResizeObserver down
            on every render. A release drops `:ref` with the rest of the
            entry, so a re-attachment that rebuilt everything except the
            memo would leave the next render minting a fresh closure."
    (let [mid    "r8-mount"
          el     (fake-el 120)
          ref-fn (ei/container-ref-for mid identity)]
      (ref-fn el)
      (ref-fn nil)
      (ref-fn el)
      (is (identical? ref-fn (ei/container-ref-for mid identity))
          "the re-attached callback is the one the store hands back")
      (unmount! ref-fn)
      (is (nil? (ei/mount-state-held mid))
          "and the mount still releases cleanly afterwards"))))

;; ---------------------------------------------------------------------------
;; TWO LIVE MOUNTS OF ONE LOGICAL SURFACE.
;;
;; Every row above hands each mount an id of its own, so none of them can see
;; the case that actually reaches this store. A panel's `:mount-id` is a
;; LOGICAL name and is deliberately stable — it keys the width slot and the
;; testids, and a panel that leaves and returns wants the same one back — so
;; several live mounts routinely present the SAME id at once: the gallery
;; renders twelve variants of a panel side by side, and each embedded panel is
;; another.
;;
;; R9 is that case, in both directions. The positive half is two mounts under
;; two frames; the negative half is the same two keyed on the logical name
;; alone, which is the failure — and it is written out rather than
;; described because the symptom is not "the second mount is missing" but
;; "the second mount's width is dispatched into the FIRST one's frame", which
;; is the reading that makes a green browser lane look correct.
;; ---------------------------------------------------------------------------

(def ^:private shared-logical-id
  "The App-db panel's real top-section mount-id — a logical surface name, the
  same string from every panel that renders one."
  "app-db-state/top")

(deftest r9-two-live-mounts-of-one-logical-id-stay-independent
  (testing "two panels rendering the same stable `mount-id` under
            two different frames get two refs, two entries and two
            dispatchers, and releasing one leaves the other whole."
    (let [before (ei/mount-state-count)
          k-a    (ei/lifecycle-key :frame/a shared-logical-id)
          k-b    (ei/lifecycle-key :frame/b shared-logical-id)
          sink-a (atom [])
          sink-b (atom [])
          ref-a  (ei/container-ref-for k-a shared-logical-id
                                       #(swap! sink-a conj %))
          ref-b  (ei/container-ref-for k-b shared-logical-id
                                       #(swap! sink-b conj %))
          el-a   (fake-el 600)
          el-b   (fake-el 300)]
      (is (not (identical? ref-a ref-b))
          "two live mounts of one logical surface get two ref callbacks —
           sharing one is what leaves the second element unobserved")
      (is (= (+ before 2) (ei/mount-state-count))
          "and two entries, not one")

      (ref-a el-a)
      (ref-b el-b)
      (is (= [[:rf.xray.edn-inspector/set-width shared-logical-id 600]] @sink-a)
          "the first mount measured itself into its OWN frame's dispatcher")
      (is (= [[:rf.xray.edn-inspector/set-width shared-logical-id 300]] @sink-b)
          "and the second into its own, at its own width. The event payload
           is the LOGICAL id in both, because the width slot is per-frame and
           the frame is what separates them — the two identities doing two
           jobs at once")

      ;; ---- release one, and only one ---------------------------------
      (reset! sink-a [])
      (reset! sink-b [])
      (unmount! ref-b)
      (is (nil? (ei/mount-state-held k-b))
          "the released mount is gone")
      (is (contains? (ei/mount-state-held k-a) :ref)
          "and the mount that is still on screen is untouched — a shared
           key would release the survivor, disconnecting an observer of a
           node still in the document")
      (is (= [[:rf.xray.edn-inspector/clear-width shared-logical-id]] @sink-b)
          "the clear went to the leaving mount's own frame")
      (is (= [] @sink-a)
          "and NOTHING was written into the surviving mount's frame")

      (unmount! ref-a)
      (is (= before (ei/mount-state-count))
          "both released, store back where it started")))

  (testing "THE NEGATIVE CONTROL, and it is the failure verbatim:
            keyed on the logical name ALONE the same two mounts share one ref,
            the second one's width is dispatched into the FIRST one's frame,
            and detaching the second releases the first."
    (let [before (ei/mount-state-count)
          mid    "r9-control/top"
          sink-a (atom [])
          sink-b (atom [])
          ref-a  (ei/container-ref-for mid #(swap! sink-a conj %))
          ref-b  (ei/container-ref-for mid #(swap! sink-b conj %))]
      (is (identical? ref-a ref-b)
          "CONTROL BITES: one ref callback between two mounts")
      (is (= (inc before) (ei/mount-state-count))
          "and one entry between them")
      (ref-a (fake-el 600))
      (ref-b (fake-el 300))
      (is (= [[:rf.xray.edn-inspector/set-width mid 600]
              [:rf.xray.edn-inspector/set-width mid 300]]
             @sink-a)
          "BOTH widths landed in the first mount's frame — the second panel's
           measurement overwrites the first's in an app-db it does not belong
           to, which is a wrong number rather than a missing one")
      (is (= [] @sink-b)
          "and the second mount's own dispatcher was never called at all")
      (unmount! ref-b)
      (is (nil? (ei/mount-state-held mid))
          "detaching the SECOND released the shared entry, so the first mount
           — still on screen — has lost its state")
      (is (= before (ei/mount-state-count))
          "store back where it started"))))
