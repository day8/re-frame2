(ns fixtures.every-durable-timestamp-key
  "POSITIVE fixture: every key in `_DURABLE_TIMESTAMP_KEYS`, one per line, each
  taking an ambient clock read as its value. Eighteen findings, and the
  self-test asserts the eighteen KEYS by name — a count alone cannot tell a
  live key roster from one with a typo in it, which is how twelve of these went
  unexercised (rf2-g1xpb).

  The read form is held constant at `(interop/now-ms)` so the only thing that
  varies down the file is the durable key. The read-form roster is proven the
  other way round, in `every_ambient_read_form.cljc`."
  (:require [re-frame.interop :as interop]))

(defn stamp-every-durable-timestamp
  [row]
  (assoc row
         :started-at     (interop/now-ms)
         :deadline-at    (interop/now-ms)
         :loaded-at      (interop/now-ms)
         :stale-at       (interop/now-ms)
         :invalidated-at (interop/now-ms)
         :settled-at     (interop/now-ms)
         :created-at     (interop/now-ms)
         :completed-at   (interop/now-ms)
         :errored-at     (interop/now-ms)
         :restored-at    (interop/now-ms)
         :installed-at   (interop/now-ms)
         :registered-at  (interop/now-ms)
         :updated-at     (interop/now-ms)
         :detected-at    (interop/now-ms)
         :fetched-at     (interop/now-ms)
         :cached-at      (interop/now-ms)
         :expires-at     (interop/now-ms)
         :refreshed-at   (interop/now-ms)))
