(ns tau.alpha.macros
  (:refer-clojure :exclude [future])
  (:require [cljs.analyzer :as ana]))

(defmacro log [& x]
  (if (= :ret (first x))
    `(tau.alpha.io/plog ~(name cljs.analyzer/*cljs-ns*) ~(:line (meta &form)) ~(second x) ~@(rest (rest x)))
    `(tau.alpha.io/plog ~(name cljs.analyzer/*cljs-ns*) ~(:line (meta &form)) nil ~@x)))

(defmacro on [tname & x]
  (let [t (first x)
        s (second x)]
    (if (and (vector? t) (map? s))
      (list* `tau.alpha.io/do-on tname (list* `fn t (rest (rest x))) s t)
      (if (vector? t)
        (list* `tau.alpha.io/do-on tname (list* `fn t (rest x)) nil t)
        (if (map? t)
          (list* `tau.alpha.io/do-on tname (list* `fn (rest x)) t (first (rest x)))
          (list* `tau.alpha.io/do-on tname (list* `fn [] x) {} nil))))))

(defn yield-form? [form]
  (when (seq? form)
    (not
      (empty?
        (filter #(or (= % 'yield) (= % 'tau.alpha.future/yield)) form)))))

(defn yields? [expr]
  (when (coll? expr)
    (not
      (empty?
        (->> expr
             (tree-seq coll? seq)
             (filter yield-form?))))))

(defmacro future [& body]
  (if (yields? body)
    (if (vector? (first body))
      `(tau.alpha.future/future-call ~(first body) (^{:once true} fn* ~(first body) ~@(rest body)))
      `(tau.alpha.future/future-call [] (^{:once true} fn* [] ~@body)))
    (if (vector? (first body))
      `(tau.alpha.future/future-call [~@(first body)] (^{:once true} fn* [~@(first body)]
                                                      (tau.alpha.future/yield (do ~@(rest body)))))
      `(tau.alpha.future/future-call [] (^{:once true} fn* [] (tau.alpha.future/yield (do ~@body)))))))
