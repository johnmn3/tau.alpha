(ns tau.alpha.util
  (:require-macros [tau.alpha.macros :refer [log on]])
  (:require [cljs.reader :refer [read-string register-tag-parser!]]
            [clojure.string :refer [replace]]
            [tau.alpha.state :refer [id serve-handlers conf on-screen?]]))

(enable-console-print!)
(set! *print-fn-bodies* true)

(register-tag-parser! 'object #(str "#object" %))
(register-tag-parser! 'error #(str "#error" %))
(register-tag-parser! 'Error #(str "#error" %))
(register-tag-parser! 'ExceptionInfo #(str "#exception-info" %))
(register-tag-parser! 'Nothing #(str "#nothing" %))

(defn- deserialize
  [data]
  (read-string data))

(defn- serialize
  [data]
  (pr-str data))

(defn- read
  [data]
  (read-string data))

(defn- write
  [data]
  (pr-str data))

(defn pr-fn [afn]
  (pr-str (pr-str afn)))

(defn time-since [x]
  (str (- (.getTime (js/Date.)) x) " milliseconds"))

(defn typed-array?
  "Tests whether a given `value` is a typed array."
  [value]
  (let [value-type (type value)]
    (or
     (= value-type js/ArrayBuffer)
     (= value-type js/SharedArrayBuffer)
     (= value-type js/Int8Array)
     (= value-type js/Uint8Array)
     (= value-type js/Uint8ClampedArray)
     (= value-type js/Int16Array)
     (= value-type js/Uint16Array)
     (= value-type js/Int32Array)
     (= value-type js/Uint32Array)
     (= value-type js/Float32Array)
     (= value-type js/Float64Array))))

(defn on? [fname] (= fname id))

(defn if-ojb->str [o]
  (if (string? o) o (pr-str o)))

(defn if-str->obj [s]
  (if (string? s) (cljs.reader/read-string s)
    (if (array? s) (cljs.reader/read-string s) s)))

(def id-counter (atom 0))

(defn new-id []
  (let [v @id-counter
        r (if (< v 10) (str "00" v))
        r (or r (if (< v 100) (str "0" v)))
        r (or r (str v))]
    (swap! id-counter inc)
    r))

(defn scripts-src []
  (let [scripts (.getElementsByTagName js/document "SCRIPT")]
    (->> (for [i (range (.-length scripts))] (aget scripts i))
         (remove #(empty? (.-src %)))
         (map #(.-src %)))))

(defn create-worker-body [require-ns afn value tid]
  (let [multi-loader
        (str "var taupreload = {};\n"
             "taupreload.fn = `" afn "`;\n"
             "taupreload.args = `" value "`;\n"
             "taupreload.tid = `" tid "`;\n"
             "CLOSURE_BASE_PATH = '" goog/basePath "';\n"
             "this.CLOSURE_IMPORT_SCRIPT = (function(global) {\n"
             "    return function(src) {\n"
             "        global['importScripts'](src);\n"
             "        return true;\n"
             "    };\n"
             "})(this);\n"
             "if(typeof goog == 'undefined') importScripts(CLOSURE_BASE_PATH + 'base.js');\n"
             "importScripts('" (str goog/basePath "../cljs_deps.js") "');\n"
             "goog.require('" require-ns "');\n")
        single-loader
        (str
          "var taupreload = {};\n"
          "taupreload.fn = `" afn "`;\n"
          "taupreload.args = `" value "`;\n"
          "taupreload.tid = `" tid "`;\n"
          "importScripts('" (last (scripts-src)) "');\n")]
    (if (empty? goog/basePath) single-loader multi-loader)))

(defn mk-body [nsname afn value tid]
  (create-worker-body nsname afn value tid))

(defn get-worker-script
  [fname nsname afn value tid]
  (.createObjectURL js/URL (js/Blob. (clj->js [(mk-body nsname afn (pr-str value) tid)]))))

(def htb64
  (js* "function hexToBase64(hexstring) {
    return btoa(hexstring.match(/\\w{2}/g).map(function(a) {
        return String.fromCharCode(parseInt(a, 16));
    }).join(\"\"));
}"))

(defn new-b64-uuid []
  (->> (str (random-uuid))
    (filter #(not (#{\-} %)))
    (apply str)
    htb64
    butlast
    butlast
    (apply str)))

(defn strip-onium-tag [s]
  (if (.startsWith s "#Onium")
    (apply str (drop-last (drop 13 s)))
    (if (.startsWith s "#Neutrino")
      (apply str (drop-last (drop 16 s)))
      (if (.startsWith (str s) ":tau.alpha.on/t-")
        (apply str (drop-last (drop 10 s)))
        (if (.startsWith (str s) ":tau/on")
          (apply str (drop-last (drop 10 s)))
          s)))))

(defn get-new-id []
  (str
    (-> (if (on-screen?)
          (str "t" (new-b64-uuid))
          (str (strip-onium-tag id) "_" (new-b64-uuid)))
      (replace #"/" "-"))))

(defn padnum [n]
  (let [s (str n)
        l (- 6 (count s))
        p (take l (cycle [","]))
        ps (apply str (concat p s))]
    ps))

(defn enc [ar d]
  (try
    (let [s (pr-str d)
          new-length (count s)
          _ (assert (< (* 2 new-length) (.-length ar)))
          old-index (js/Atomics.load ar 2)
          new-index (if (= 0 old-index) (int (/ (.-length ar) 2)) 0)
          write-point (if (= 0 new-index) 4 1)
          _ (doall (map-indexed #(aset ar (+ %1 write-point new-index) (.charCodeAt %2 0)) (seq s)))]
      (js/Atomics.store ar (+ (dec write-point) new-index) new-length)
      (js/Atomics.store ar 2 new-index)
      ar)
    (catch :default e (println "failed on enc"))))

(defn unc [a]
  (let [i (js/Atomics.load a 2)
        read-point (if (= 0 i) 4 1)
        l (js/Atomics.load a (+ (dec read-point) i))
        s (apply str
                 (map #(js/String.fromCharCode %)
                      (take l (drop (+ i read-point) (array-seq a)))))]
    (read s)))

(defn sleep [msec]
  (let [deadline (+ msec (.getTime (js/Date.)))]
    (while (> deadline (.getTime (js/Date.))))))
