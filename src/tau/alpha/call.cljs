(ns tau.alpha.call
  (:require-macros [tau.alpha.macros :refer [log on]])
  (:require [clojure.string :refer [replace]]
            [tau.alpha.state :refer [serve-handlers add-handler add-listeners]]
            [tau.alpha.util :refer [read write typed-array?]]
            [tau.alpha.io]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(defn get-raw-fn
  [y]
  (let [s? (string? y)
        raw? (if s?
               (= "function" (apply str (take 8 y)))
               (= "function" (apply str (take 8 (str y)))))

        x (apply str (butlast (butlast (pr-str y)))) ;(pr-str y))))
        last-quote? (= \" (last x))
        x (if last-quote? (apply str (butlast (butlast x))) x)
        x (apply str (drop 18 x))]
    (if raw?
      (str y)
      (apply str (rest (drop-while #(not (#{\"} %)) x))))))

(defn obj? [x] (= "#object[" (apply str (take 8 (str x)))))

(defn strip-obj [x]
  (let [drop-front (rest (drop-while #(not (#{\"} %)) x))
        drop-back (apply str (-> drop-front butlast butlast))]
    (if (obj? x)
      drop-back
      x)))

(defn get-name [obj-js]
  (let [s (if (string? obj-js) obj-js (pr-str obj-js))
        new-s (strip-obj s)
        anon? (= "function (" (apply str (take 10 new-s)))
        n1 (if anon?
             "Function"
             (apply str
               (take-while #(not (#{\(} %))
                 (drop 9 new-s))))]
    n1))

(defn name-fn-proto [s]
  (let [old-name (get-name s)
        f? (= "Function" old-name)
        n (str "f_" (gensym))]
    (if f?
      (str "function " n (subs (get-raw-fn s) 9) ";" n)
      (str (strip-obj s) ";" old-name))))

(defn name-fn [s]
  (let [f (name-fn-proto s)
        es? (filter #{\\} f)]
    (if (not (empty? es?)) (read (str "\"" f "\""))
      f)))

(defn respile [fn-str]
  (js/eval (name-fn fn-str)))

(defn call [afn & args]
  (if afn
    (if (empty? args)
      ((respile afn))
      (apply (respile afn) args))
    (log "call - afn undefined:" afn)))

(defn str-fn? [s]
  (and (string? s)
    (= "function"
      (apply str
        (take 8
          (apply str
            (rest
              (drop-while #(not (#{\"} %))
                s))))))))

(defn respile-args [args]
  (map #(if (str-fn? %) (respile %) %) args))

(defn call-handler
  [{:keys [afn ab args from to opts]}]
  (if (typed-array? ab)
    (apply (respile afn) (cons ab (respile-args args)))
    (try
      (apply (respile afn) (respile-args args))
      (catch :default e
        (if (:error-fn opts)
          (let [error-fn (read (pr-str (:error-fn opts)))]
            (if-not (= "nil" error-fn)
              ((respile error-fn) {:from from :to to :afn afn :args args :error e})))
          (log "ERROR:" e "- no :error-fn defined.\n afn:" afn "\n args:" args
            "\nfrom:" from "\nto:" to "\nopts:" opts))))))

(add-handler {:typed-call-here call-handler})
