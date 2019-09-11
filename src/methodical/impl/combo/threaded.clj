(ns methodical.impl.combo.threaded
  (:refer-clojure :exclude [methods])
  (:require [methodical.impl.combo.common :as combo.common]
            [potemkin.types :as p.types]
            [pretty.core :refer [PrettyPrintable]])
  (:import methodical.interface.MethodCombination))

(defn reducer-fn
  "Reduces a series of before/combined-primary/after methods, threading the resulting values to the next method by
  calling the `invoke` function, which is generated by `threaded-invoker`."
  [before-primary-after-methods]
  (fn [[initial-value invoke]]
    (reduce
     (fn [last-result method]
       (invoke method last-result))
     initial-value
     before-primary-after-methods)))

(defn combine-with-threader
  "Combine primary and auxiliary methods using a threading invoker, i.e. something you'd get by calling
  `threading-invoker`. The way these methods are combined/reduced is the same, regarless of how args are threaded;
  thus, various strategies such as `:thread-first` and `:thread-last` can both share the same `reducer-fn`. "
  ([threader before-primary-afters]
   (comp (reducer-fn before-primary-afters) threader))

  ([threader primary-methods {:keys [before after around]}]
   (when-let [primary (combo.common/combine-primary-methods primary-methods)]
     (let [methods              (concat before [primary] (reverse after))
           threaded-fn          (combine-with-threader threader methods)
           optimized-one-arg-fn (apply comp (reverse methods))]
       (combo.common/apply-around-methods
        (fn
          ([]               (optimized-one-arg-fn))
          ([a]              (optimized-one-arg-fn a))
          ([a b]            (threaded-fn a b))
          ([a b c]          (threaded-fn a b c))
          ([a b c d]        (threaded-fn a b c d))
          ([a b c d & more] (apply threaded-fn a b c d more)))
        around)))))

(defmulti threading-invoker
  "Define a new 'threading invoker', which define how before/combined-primary/after methods should thread values to
  subsequent methods. These methods take the initial values used to invoke a multifn, then return a pair like
  `[initial-value threading-fn]`. The threading function is used to invoke any subsequent methods using only q single
  value, the result of the previous method; if effectively partially binds subsequent methods so that they are always
  invoked with the initial values of this invocation, excluding the threaded value."
  {:arglists '([threading-type])}
  keyword)

(defmethod threading-invoker :thread-first
  [_]
  (fn
    ([a b]            [a (fn [method a*] (method a* b))])
    ([a b c]          [a (fn [method a*] (method a* b c))])
    ([a b c d]        [a (fn [method a*] (method a* b c d))])
    ([a b c d & more] [a (fn [method a*] (apply method a* b c d more))])))

(defmethod threading-invoker :thread-last
  [_]
  (fn
    ([a b]     [b (fn [method b*] (method a b*))])
    ([a b c]   [c (fn [method c*] (method a b c*))])
    ([a b c d] [d (fn [method d*] (method a b c d*))])

    ([a b c d & more]
     (let [last-val (last more)
           butlast* (vec (concat [a b c d] (butlast more)))]
       [last-val
        (fn [method last*]
          (apply method (conj butlast* last*)))]))))


(p.types/deftype+ ThreadingMethodCombination [threading-type]
  PrettyPrintable
  (pretty [_]
    (list 'threading-method-combination threading-type))

  MethodCombination
  Object
  (equals [_ another]
    (and (instance? ThreadingMethodCombination another)
         (= threading-type (.threading-type ^ThreadingMethodCombination another))))

  MethodCombination
  (allowed-qualifiers [_]
    #{nil :before :after :around})

  (combine-methods [_ primary-methods aux-methods]
    (combine-with-threader (threading-invoker threading-type) primary-methods aux-methods))

  (transform-fn-tail [_ qualifier fn-tail]
    (combo.common/add-implicit-next-method-args qualifier fn-tail)))

(defn threading-method-combination
  "Create a new `ThreadingMethodCombination` using the keyword `threading-type` strategy, e.g. `:thread-first` or
  `:thread-last`."
  [threading-type]
  {:pre [(get-method threading-invoker threading-type)]}
  (ThreadingMethodCombination. threading-type))