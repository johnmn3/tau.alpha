(ns tau.alpha.future
  (:require [tau.alpha.exec :refer [local-submit oid tau-tau tau-afn]]))

(defn yield [arg]
  (when (and @tau-tau (not (:yielded? @@tau-tau)))
    (swap! @tau-tau (fn [_] {:yielded? true :completed? true :result arg}))))

(defn future-call
  ([f]
   (future-call :default-executor [] f))
  ([conveyer f]
   (future-call :default-executor conveyer f))
  ([ex conveyer f]
   (local-submit (if (= :default-executor ex) ex (oid ex)) f conveyer)))
