(ns day8.re-frame2-xray.views.edn-inspector-mount-state-cljs-test
  "THE PER-MOUNT STORE — the three pieces of state that used to live in the
  form-2 closure (rf2-k97c.3).

  `edn-inspector` was a form-2 component, so its ResizeObserver, its width
  debounce and its Editscript projection cache lived in an outer body that
  ran once per mount and was collected with it. `edn-inspector-view` is a
  Fresco boundary — a real React function component — and has no such
  body: everything in it runs on every render. So the three moved into one
  module-level store keyed by mount-id, with an EXPLICIT release.

  Moving state out of a closure and into a module-level map is exactly the
  change that passes its happy path and leaks on unmount, so the rows here
  are written against the two ways it goes wrong rather than against the
  way it goes right:

    R1  the `:ref` callback is MEMOISED per mount — the adversarial case,
        because a fresh closure per render would be invisible to any
        rendering assertion and would make React tear the observer down
        and stand it up again on every pass.
    R2  a different mount gets a DIFFERENT ref, so the memo is keyed and
        not a singleton.
    R3  release drops the WHOLE entry, and the count returns to where it
        started — the aggregate leak claim.
    R4  release is per-mount: a sibling mount is untouched by it. The
        negative half of R3, and the one a `(reset! store {})` teardown
        would fail.
    R5  the projection cache is held under the same key and goes with the
        rest, so a diff mount releases its Editscript result too. A cache
        that survives its mount is the leak that costs REAL memory.
    R6  releasing a mount that holds nothing is a no-op that dispatches
        nothing — the negative case, and the one that fires if React
        calls a ref with nil twice, which it is entitled to do.

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

(deftest r1-the-ref-callback-is-memoised-per-mount
  (testing "rf2-k97c.3 — two asks for the same mount's ref answer the
            IDENTICAL function.

            This is the row that matters most and the one nothing else
            can catch. A Fresco body runs whole on every render, so a ref
            callback built in it would be a new closure each pass; React
            re-runs a callback ref whose identity moved, detaching the old
            one with nil and attaching the new one — which, for this
            widget, means disconnecting the ResizeObserver and building
            another one on every single render. Nothing about the
            rendered output would look wrong."
    (let [mid  "r1-mount"
          ref1 (ei/container-ref-for mid identity)
          ref2 (ei/container-ref-for mid identity)]
      (is (identical? ref1 ref2)
          "same mount-id ⇒ identical ref fn, so React sees one identity for the mount")
      ;; The control: the memo is real rather than an accident of two
      ;; calls in one tick. Ask again after the store has been written to.
      (is (identical? ref1 (ei/container-ref-for mid identity))
          "still identical on a third ask")
      (unmount! ref1))))

(deftest r2-a-different-mount-gets-a-different-ref
  (testing "rf2-k97c.3 — the memo is KEYED. Two mounts must not share a
            ref, or they would share an observer and a width slot."
    (let [ref-a (ei/container-ref-for "r2-a" identity)
          ref-b (ei/container-ref-for "r2-b" identity)]
      (is (not (identical? ref-a ref-b))
          "distinct mount-ids ⇒ distinct ref fns")
      (unmount! ref-a)
      (unmount! ref-b))))

(deftest r3-release-drops-the-whole-entry
  (testing "rf2-k97c.3 — calling the ref with nil, which is what React
            does at unmount, releases everything the mount held and the
            store returns to the size it was."
    (let [before (ei/mount-state-count)
          mid    "r3-mount"
          ref-fn (ei/container-ref-for mid identity)]
      (is (= (inc before) (ei/mount-state-count))
          "the mount is in the store while it is mounted")
      (is (contains? (ei/mount-state-held mid) :ref)
          "and it holds its ref")
      (unmount! ref-fn)
      (is (nil? (ei/mount-state-held mid))
          "after unmount the entry is GONE, not merely emptied")
      (is (= before (ei/mount-state-count))
          "and the store is back to the size it was")
      ;; The discriminating half: a store that had merely been emptied of
      ;; observers would still answer the SAME ref here.
      (let [ref-again (ei/container-ref-for mid identity)]
        (is (not (identical? ref-fn ref-again))
            "a remount mints a fresh ref, proving the entry really went")
        (unmount! ref-again)))))

(deftest r4-release-is-per-mount
  (testing "rf2-k97c.3 — releasing one mount leaves its siblings standing.
            The negative half of R3: a teardown that reset the whole store
            would pass R3 and fail here, and this widget is mounted dozens
            of times on one page (the epoch panel alone)."
    (let [keeper (ei/container-ref-for "r4-keeper" identity)
          goer   (ei/container-ref-for "r4-goer" identity)]
      (unmount! goer)
      (is (nil? (ei/mount-state-held "r4-goer"))
          "the released mount is gone")
      (is (contains? (ei/mount-state-held "r4-keeper") :ref)
          "the sibling is untouched")
      (is (identical? keeper (ei/container-ref-for "r4-keeper" identity))
          "and still answers its own ref")
      (unmount! keeper))))

(deftest r5-the-projection-cache-lives-and-dies-with-the-mount
  (testing "rf2-k97c.3 — a diff render stores its Editscript projection
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
  (testing "rf2-k97c.3 — a ref called with nil for a mount the store does
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
