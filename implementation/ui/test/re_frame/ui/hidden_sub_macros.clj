(ns re-frame.ui.hidden-sub-macros)

(defmacro hidden-public-sub []
  '(re-frame.ui/sub [:hidden/macro-helper]))
