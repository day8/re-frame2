(ns re-frame.egress-closed-opts-test
  "ONE policy vocabulary, CLOSED at both egress doors.

  On a privacy surface a recognised-looking policy key must never vanish
  without a signal: a `:rf.egress/profile` handed to the walker (profiles
  resolve a layer up, in `project-egress`), or a bare / `:rf.size/*` spelling
  of an `:rf.egress/*` axis, would read as an applied policy while the walk ran
  under the default one. So both opts maps are closed and an unrecognised key
  is a loud `:rf.error/bad-egress-opts` naming it. Closing cannot widen egress:
  an open map would ignore the key, never use it to let a value out."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.elision :as rf.elision]
            [re-frame.projection :as rf.projection]))

(defn- bad-opts-ex-data
  "Run `f`; return the `ex-data` of a `:rf.error/bad-egress-opts` throw, nil
  when nothing throws. Any other error is re-thrown so it reports itself."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (if (= :rf.error/bad-egress-opts (:rf.error/id d))
          d
          (throw e))))))

(def ^:private stray-keys
  "Outside both closed sets: the bare inclusion axes, the retired `:rf.size/*`
  policy spellings, `:as-of-epoch` (its four-element marker handle is one
  Spec-Schemas `:rf/elision-marker` rejects) and an arbitrary key."
  {:include-sensitive?         true
   :include-large?             true
   :include-digests?           true
   :include-fx-args?           true
   :include-runtime-db?        true
   :include-event-args?        true
   :rf.size/include-sensitive? true
   :rf.size/include-large?     true
   :rf.size/include-digests?   true
   :rf.size/threshold-bytes    true
   :as-of-epoch                7
   :totally-made-up            true})

(def ^:private epoch-only-axes
  {:rf.egress/include-fx-args?    true
   :rf.egress/include-runtime-db? true
   :rf.egress/include-event-args? true})

(def ^:private ex-slots [:unknown-keys :recovery :where :accepted])

(deftest walker-rejects-a-profile
  (testing "a profile names a BOUNDARY, which `project-egress` resolves; the
            walker refuses it with a recovery that says so"
    (is (= {:unknown-keys [:rf.egress/profile]
            :recovery     :pass-the-profile-to-project-egress
            :where        're-frame.elision/elide-wire-value}
           (select-keys (bad-opts-ex-data
                          #(rf.elision/elide-wire-value
                             {:a 1}
                             {:frame :app/main
                              :rf.egress/profile :rf.egress/off-box-tool}))
                        [:unknown-keys :recovery :where])))))

(deftest walker-rejects-an-arbitrary-unknown-key
  (testing "every key outside the walker's seven is named, sorted, beside the
            accepted set — the epoch-only axes included, since they are
            stripped at `project-egress` and never reach the walker"
    (is (= {:unknown-keys [:as-of-epoch :include-digests? :include-event-args?
                           :include-fx-args? :include-large? :include-runtime-db?
                           :include-sensitive? :totally-made-up
                           :rf.egress/include-event-args? :rf.egress/include-fx-args?
                           :rf.egress/include-runtime-db?
                           :rf.size/include-digests? :rf.size/include-large?
                           :rf.size/include-sensitive? :rf.size/threshold-bytes]
            :recovery     :use-a-recognised-egress-opts-key
            :where        're-frame.elision/elide-wire-value
            :accepted     [:frame :path :query-v
                           :rf.egress/include-digests? :rf.egress/include-large?
                           :rf.egress/include-sensitive? :rf.egress/threshold-bytes]}
           (select-keys (bad-opts-ex-data
                          #(rf.elision/elide-wire-value
                             {:a 1}
                             (merge stray-keys epoch-only-axes {:frame :app/main})))
                        ex-slots)))))

(def ^:private record-kind-samples
  "One input per arm of `project-record-by-kind`, kindless included. An
  event-shaped record never reaches the walker, so only a guard at the door
  catches a stray key on it."
  {:kindless      {:some "value"}
   :handled-event {:kind  :rf.observe/handled-event
                   :frame :app/main
                   :event [:auth/login {:password "secret"}]}
   :error         {:kind  :rf.observe/error
                   :frame :app/main
                   :event [:auth/login {:password "secret"}]}
   :derived-tree  {:kind  :rf.observe/derived-tree
                   :frame :app/main
                   :tree  {:a 1}}})

(deftest project-egress-rejects-an-unknown-key-on-every-kind
  (testing "graded at the door, so every `:rf.observe/*` kind and the kindless
            value path refuse the same keys against the same eleven"
    (doseq [[label record] record-kind-samples]
      (is (= {:unknown-keys [:as-of-epoch :include-digests? :include-event-args?
                             :include-fx-args? :include-large? :include-runtime-db?
                             :include-sensitive? :totally-made-up
                             :rf.size/include-digests? :rf.size/include-large?
                             :rf.size/include-sensitive? :rf.size/threshold-bytes]
              :recovery     :use-a-recognised-egress-opts-key
              :where        'rf/project-egress
              :accepted     [:frame :path :query-v
                             :rf.egress/include-digests? :rf.egress/include-event-args?
                             :rf.egress/include-fx-args? :rf.egress/include-large?
                             :rf.egress/include-runtime-db? :rf.egress/include-sensitive?
                             :rf.egress/profile :rf.egress/threshold-bytes]}
             (select-keys (bad-opts-ex-data
                            #(rf.projection/project-egress
                               record
                               (assoc stray-keys :rf.egress/profile :rf.egress/off-box-tool)))
                          ex-slots))
          (str label))))
  (testing "CONTROL — the profile and the three epoch-only axes pass the door
            and are stripped before the walker's closed map"
    (is (nil? (bad-opts-ex-data
                #(rf.projection/project-egress
                   {:a 1}
                   (assoc epoch-only-axes :rf.egress/profile :rf.egress/off-box-tool)))))))
