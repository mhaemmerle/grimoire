(ns grimoire.test.core
  (:use [grimoire.core])
  (:use [grimoire.session])
  (:use [clojure.test]))

(deftest replace-me
  (is true))

(defn add2 [x]
  (+ x 2))

(deftest test-adder
  (is (= 24  (add2 22))))

(deftest setup-test
  (let [r (setup 123)]
    (println r)
    (is (not (= nil r)))
    ))


;; (util/log-with-thread "pre pipeline")

;; (run-pipeline 1
;;               (fn [value]
;;                 (util/log-with-thread "start-pipeline")
;;                 value)
;;               #(task
;;                 (do
;;                   (util/log-with-thread "starting to sleep")
;;                   (Thread/sleep 5000)
;;                   %))
;;               #(task
;;                 (do
;;                   (util/log-with-thread "run-pipeline")
;;                   (inc %))))

;; (log/info "post pipeline on" (Thread/currentThread))
