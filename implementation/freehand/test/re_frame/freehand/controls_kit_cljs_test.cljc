(ns re-frame.freehand.controls-kit-cljs-test
  "FH-CTRL-016 and FH-CTRL-017 — the first-party control kit.

  `form-cljs-test` proves the transitions as functions. These two rows
  prove the CONTROLS: what they render, what a caller can and cannot say
  to them, and what the causal owner's release actually removes.

  ## Why there is still no runtime fixture

  Neither control calls `v/sub`. The caller reads the leaf and passes the
  projection in, which is what makes the narrow read the CALLER's visible
  decision rather than a component's private one — so a structural render
  needs no candidate, no frame and no capture, and every assertion below
  is over the tree `t/render` returns.

  That is worth stating as evidence rather than as convenience: a control
  that subscribed internally would have had to be given a frame here, and
  the projection it read would have been unavailable to the application
  that mounts it.

  ## The narrowness assertion, and its counter-case

  FH-CTRL-016 asserts that a keystroke in one leaf leaves the OTHER
  leaf's projection byte-identical. On its own that is a claim about
  nothing — a projection that never changed would satisfy it. So the same
  test asserts the CONTAINER read on the same keystroke, and that it
  DOES change. The pair is the cost model: the wide read publishes and
  the narrow one does not."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [re-frame.freehand.compiler.check :as check])
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.controls :as c]
            [re-frame.freehand.form :as form]
            [re-frame.freehand.test :as t]))

(defn- input-attrs
  "The attributes of the single `:input` the control rendered."
  [tree]
  (t/attrs (t/find tree #(= :input (:tag %)))))

;; ===========================================================================
;; FH-CTRL-016 — one leaf in, one intent out, and no way to say `value`
;; ===========================================================================

(def ctrl-016 (conf/fixture :FH-CTRL-016))

(deftest fh-ctrl-016-the-field-renders-a-door-shaped-controlled-input
  (testing "Per FH-CTRL-016: the call site is one leaf projection and one
            intent. What it renders is a native `<input>` carrying
            `value`, with a LITERAL event vector at `on-input` completed
            by the scalar projection the door fills — which is exactly
            the shape D009's door recognises, so the round trip is
            synchronous and the caret survives it."
    (let [{:keys [baseline email-path edit-intent visit-intent tag rendered-value
                  rendered-on-input rendered-on-blur forwarded]} ctrl-016
          f     (form/init baseline)
          tree  (t/render [c/field {:field    (form/field f email-path)
                                    :on-edit  edit-intent
                                    :on-visit visit-intent}])
          node  (t/find tree #(= tag (:tag %)))
          attrs (t/attrs node)]
      (is (some? node) "non-vacuous: the control really rendered that element")
      (is (= rendered-value (:value attrs)) "the value comes from the LEAF")
      (is (= rendered-on-input (:on-input attrs))
          "and the intent is the caller's, completed with the projection")
      (is (= rendered-on-blur (:on-blur attrs)))
      (is (not= rendered-on-input edit-intent)
          "non-vacuous: the control really did complete the caller's vector")

      (testing "the caller's ordinary attributes reach the element"
        (let [with-attrs (input-attrs
                           (t/render [c/field (merge {:field   (form/field f email-path)
                                                      :on-edit edit-intent}
                                                     forwarded)]))]
          (doseq [[k expected] forwarded]
            (is (= expected (get with-attrs k))
                (str "the caller's " (pr-str k) " reached the input")))
          (is (= rendered-value (:value with-attrs))
              "and the owned value still wins")))

      (testing "the blur is OPTIONAL — a form with no reveal-on-blur policy
                does not have to invent an event to satisfy a component"
        (let [{:keys [on-blur-when-absent]} ctrl-016
              bare (input-attrs (t/render [c/field {:field   (form/field f email-path)
                                                    :on-edit edit-intent}]))]
          (is (= on-blur-when-absent (:on-blur bare)))
          (is (= rendered-on-input (:on-input bare))
              "non-vacuous: the same render DID produce the edit site"))))))

(deftest fh-ctrl-016-a-caller-cannot-spell-the-container-read
  (testing "Per FH-CTRL-016: neither control has a `:value` prop, and a
            caller who reaches for one is refused LOUDLY by the one deny
            law every component library forwards through — in every
            build, and by normalized slot, so an alternate spelling does
            not route around it. Reading the whole draft into a control
            is not discouraged here; it is unspellable."
    (let [{:keys [baseline email-path edit-intent denied-caller-prop deny-error-id]}
          ctrl-016
          f     (form/init baseline)
          thunk #(t/render [c/field {:field              (form/field f email-path)
                                     :on-edit            edit-intent
                                     denied-caller-prop  "the whole draft map"}])]
      (is (= deny-error-id (conf/caught-id thunk))
          "the caller's value is refused, with the catalogued id")
      (is (str/includes? (conf/caught-message thunk) (name denied-caller-prop))
          "and the message names the prop")

      (testing "the CONTROL for the refusal: the same view without it renders,
                so the rejection above cannot be green because nothing renders"
        (is (= conf/no-throw
               (conf/caught-id #(t/render [c/field {:field   (form/field f email-path)
                                                    :on-edit edit-intent}]))))))))

(deftest fh-ctrl-016-a-keystroke-in-one-leaf-does-not-move-another-leafs-read
  (testing "Per FH-CTRL-016: this is the cost model, and it is asserted as
            a pair. A character typed in one leaf leaves the OTHER leaf's
            projection byte-identical — so a subscription over it
            publishes nothing and that control does not re-render inside
            the keystroke — while the CONTAINER read every form is
            tempted to write changes on the very same keystroke."
    (let [{:keys [baseline email-path phone-path typed-in-email
                  container-read-changes? leaf-read-changes?]} ctrl-016
          before (form/init baseline)
          after  (form/edit before email-path typed-in-email)]

      (is (= leaf-read-changes?
             (not= (form/field before phone-path) (form/field after phone-path)))
          "the untouched leaf's projection did not move")
      (is (= container-read-changes?
             (not= (:draft before) (:draft after)))
          "and the whole-draft read did")
      (is (not= container-read-changes? leaf-read-changes?)
          "non-vacuous: the fixture's two answers really are opposites")

      (testing "and the leaf that WAS typed in did move — otherwise the
                narrowness above would be the absence of an edit"
        (is (not= (form/field before email-path) (form/field after email-path))))

      (testing "the two controls render identically before and after, for the
                untouched leaf — the structural statement of the same fact"
        (let [render-at (fn [f] (input-attrs
                                  (t/render [c/field {:field   (form/field f phone-path)
                                                      :on-edit [:editor/edited phone-path]}])))]
          (is (= (render-at before) (render-at after))))))))

#?(:clj
   (deftest fh-ctrl-016-both-controls-are-inside-the-compiled-grammar
     (testing "Per FH-CTRL-016: both declarations are checked, AS THEY
               STAND, by the same analyzer the build runs — pointed at the
               shipped source, so there is no copy to drift. Eligible with
               nothing to change on the way: promotion is a keyword, not a
               rewrite, and the kit therefore keeps interpreted/compiled
               structural parity by construction rather than by promise.

               JVM-only because the checker resolves heads against a
               loaded namespace, which only the JVM has."
       (let [{:keys [view-ids compile-eligible? findings current-lowering]} ctrl-016
             path    (.getPath (io/file (io/resource "re_frame/freehand/controls.cljc")))
             reports (check/check-file path)
             by-id   (into {} (map (juxt :view-id identity)) reports)]
         (is (= (count view-ids) (count reports))
             "non-vacuous: the checker read exactly this file's declarations")
         (doseq [id view-ids]
           (let [report (get by-id id)]
             (is (some? report) (str "the checker found " id))
             (is (= compile-eligible? (:compile-eligible? report))
                 (str id " is inside the compiled grammar"))
             (is (= findings (:findings report))
                 (str id " has nothing to change on the way"))
             (is (= current-lowering (:current-lowering report))
                 (str id " is checked as it stands, before any promotion"))))))))

;; ===========================================================================
;; FH-CTRL-017 — commit, composition, and the causal owner's release
;; ===========================================================================

(def ctrl-017 (conf/fixture :FH-CTRL-017))

(deftest fh-ctrl-017-a-composing-enter-commits-nothing
  (testing "Per FH-CTRL-017: the keyboard branch is a pure function of two
            scalars, so the law is proven by CALLING it rather than by
            simulating a keyboard. The Enter that accepts an input-method
            candidate belongs to the IME — a control that reads it as a
            commit fires the domain event mid-word, on every phrase a
            Japanese, Chinese or Korean user types — and so does a
            composing Escape."
    (let [{:keys [key-intents]} ctrl-017]
      (is (seq key-intents) "non-vacuous: the table has rows")
      (doseq [{:keys [key composing? intent]} key-intents]
        (is (= intent (c/key-intent key composing?))
            (str (pr-str key) " while " (if composing? "composing" "not composing"))))

      (testing "the SAME key is two different answers, which is the whole
                point — a table that never disagreed with itself would
                prove nothing"
        (is (not= (c/key-intent c/commit-key false)
                  (c/key-intent c/commit-key true)))
        (is (not= (c/key-intent c/cancel-key false)
                  (c/key-intent c/cancel-key true)))
        (is (= :commit (c/key-intent c/commit-key false)))
        (is (= :cancel (c/key-intent c/cancel-key false)))))))

(deftest the-composition-reader-answers-each-signal-alone
  (testing "`c/key-intent` takes the composition as a scalar; `c/composing?`
            is what reads that scalar off a host keyboard event, and it is
            public because the host adaptation is the part a kit member
            cannot re-derive safely (rf2-drpa3.182.16).

            FOUR shapes, each pressed ALONE, because an event carrying both
            signals cannot tell a reader of both from a reader of either —
            the same discipline FH-CTRL-018 and FH-CTRL-020 hold in the
            browser, held here without one."
    #?(:cljs
       (do
         (testing "i. the raw DOM flag, on the event itself"
           (is (true? (c/composing? #js {:isComposing true :keyCode 13}))))

         (testing "ii. React 19.2's synthetic keyboard event carries `key`
                   and the legacy `keyCode` but NOT `isComposing`, so the
                   flag has to be asked of `nativeEvent`"
           (is (true? (c/composing?
                        #js {:nativeEvent #js {:isComposing true :keyCode 13}
                             :keyCode     13}))))

         (testing "iii. `keyCode` 229 ALONE, with the flag false — the
                   pairing WebKit bug 165004 reported on the Enter that
                   accepts a candidate (fixed April 2026 via bug 311717,
                   kept here for engines already deployed without it). A
                   reader of only the standard flag fails exactly this row"
           (is (true? (c/composing? #js {:isComposing false :keyCode 229}))))

         (testing "iv. NEITHER signal: an ordinary Enter, which must not be
                   withheld — the positive barrier that keeps the three
                   rows above from being satisfied by a constant `true`"
           (is (false? (c/composing? #js {:isComposing false :keyCode 13})))))

       :clj
       (testing "the structural host asks the same question of a map, so a
                 test with no browser presses the same reader"
         (is (true?  (c/composing? {:composing? true})))
         (is (false? (c/composing? {:composing? false})))
         (is (false? (c/composing? {})))))))

(deftest fh-ctrl-017-the-commit-intent-carries-the-leafs-generation
  (testing "Per FH-CTRL-017: the commit intent carries the leaf's reset
            revision, so the receiving handler decides against COMMITTED
            state. Once the caller's rejection has rendered, the live
            intent speaks for the NEW generation while a commit captured
            before it still speaks for the old — which is what makes the
            superseded one inert, in the handler rather than by a guard
            captured at render."
    (let [{:keys [baseline amount-path edit-intent commit-intent cancel-intent
                  initial-reset-key rendered-on-blur next-reset-key
                  rendered-on-blur-after-reset stale-commit-current?
                  current-commit-current?]} ctrl-017
          f       (form/init baseline)
          render  (fn [f] (input-attrs
                            (t/render [c/buffered-field
                                       {:field     (form/field f amount-path)
                                        :on-edit   edit-intent
                                        :on-commit commit-intent
                                        :on-cancel cancel-intent}])))
          before  (render f)]
      (is (= rendered-on-blur (:on-blur before))
          "the blur commits, carrying the generation this render displayed")
      (is (= initial-reset-key (peek (:on-blur before))))

      (let [rejected (form/reset (form/edit f amount-path "bad") amount-path)
            after    (render rejected)]
        (is (= next-reset-key (form/reset-key rejected amount-path))
            "non-vacuous: the rejection really advanced the generation")
        (is (= rendered-on-blur-after-reset (:on-blur after))
            "and the re-rendered intent speaks for the new one")
        (is (not= (:on-blur before) (:on-blur after))
            "non-vacuous: the two intents really differ")

        (testing "the fence, asked of both — the captured commit and the live one"
          (is (= stale-commit-current?
                 (v/controller-current? (peek (:on-blur before))
                                        (form/reset-key rejected amount-path))))
          (is (= current-commit-current?
                 (v/controller-current? (peek (:on-blur after))
                                        (form/reset-key rejected amount-path)))))))))

(deftest fh-ctrl-017-the-causal-owner-releases-exactly-what-it-named
  (testing "Per FH-CTRL-017: cleanup follows the OWNER, never the render.
            An ordinary application transition releases the form slices
            the owner named — exactly those, atomically — and releasing
            twice changes nothing."
    (let [{:keys [db-before owned-path db-after db-after-second-release
                  both-paths db-after-both absent-path top-level-path
                  db-after-top-level]} ctrl-017]
      (is (= db-after (c/release db-before owned-path))
          "the owner's form is gone; the unrelated form and slice survive")
      (is (not= db-before db-after) "non-vacuous: something really was removed")

      (is (= db-after-second-release (c/release (c/release db-before owned-path)
                                                owned-path))
          "releasing twice is releasing once — a route leaving and a record
           closing legitimately both fire")

      (is (= db-after-both (apply c/release db-before both-paths))
          "several paths go in ONE value, so no intermediate state exists in
           which the owner is gone and its form is not")

      (is (= db-before (c/release db-before absent-path))
          "a path whose parent is absent removes nothing and CREATES nothing")
      (is (nil? (get-in (c/release db-before absent-path) [:nowhere]))
          "the failure an update-in reaches for by itself")

      (is (= db-after-top-level (c/release db-before top-level-path))
          "a top-level slice goes by its one-element path")

      (is (= db-before (c/release db-before []))
          "and the empty path releases nothing — the whole db is not a form"))))

(deftest fh-ctrl-017-no-published-var-of-the-kit-is-a-lifecycle-hook
  (testing "Per FH-CTRL-017: the absence is part of the contract, so it is
            asserted rather than assumed. Retention follows the owner
            because there is nowhere else to hang it — no unmount
            callback, no dispose registration, no per-occurrence teardown
            slot on the kit or on the door."
    (let [{:keys [cleanup-hook-spellings public-vars]} ctrl-017
          hookish?  (fn [s] (some #(str/includes? (str/lower-case (str s)) %)
                                  cleanup-hook-spellings))
          published #?(:clj  (into (mapv (comp name key) (ns-publics 're-frame.freehand.controls))
                                   (mapv (comp name key) (ns-publics 're-frame.freehand)))
                       :cljs (vec public-vars))]
      (is (seq published) "non-vacuous: there are published vars to examine")
      (is (empty? (filter hookish? published))
          "no published var is a lifecycle or teardown surface")
      (is (seq (filter hookish? (conj (vec published) "on-unmount")))
          "and the probe can SEE such a name when one is present")
      #?(:clj
         (is (= public-vars (set (mapv (comp name key)
                                       (ns-publics 're-frame.freehand.controls))))
             "the kit's published roster is exactly two controls, the release,
              the keyboard law with its reader and its two key names")))))
