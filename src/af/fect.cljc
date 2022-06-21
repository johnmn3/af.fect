(ns af.fect
  (:require [clojure.pprint :as pp]))

(defn muff [s]
  (if-not s
    []
    (if (sequential? s)
      s
      [s])))

(defn merge-env [as afns]
  (->> afns
       (map-indexed
        #(if (-> %2 meta :as)
           %2
           (with-meta
             (fn [env]
               (merge env (%2 env)))
             {:as (-> as str rest (->> (apply str)) (str "-" %1) keyword)
              :index %1})))
       vec))

(defn apply-env [as afns]
  (->> afns
       (map-indexed
        #(if (-> %2 meta :as)
           %2
           (with-meta
             (fn [env]
               (%2 env))
             {:as (-> as str rest (->> (apply str)) (str "-" %1) keyword)
              :index %1})))
       vec))

(defn join [afn]
  (fn sub
    [{:keys [joined-env parent-env child-env]}]
    {:child-env child-env
     :parent-env parent-env
     :joined-env (merge (or joined-env {}) (afn parent-env child-env))}))

(def base
  {:is :base
   :joins [(with-meta (join (fn [& _] {})) {:as :base :index 0})]
   :affects [(with-meta identity {:as :base})]
   :effects [(with-meta identity {:as :base})]
   :op (fn [& args]
         (when (seq args)
           args))
   :finally [(with-meta identity {:as :base})]})

(defn wrap-joins [parent-env child-env]
  {:parent-env parent-env :child-env child-env})

(defn thread-joins [as afns]
  (->> afns
       (map-indexed
        #(if (-> %2 meta :as)
           %2
           (with-meta
             (join %2)
             {:as as :index %1})))
       vec))

(defn tag-fn [as afn]
  (if (-> afn meta :as)
    afn
    (with-meta afn {:as as})))

(defn concat-distinct [& coll]
  (vec (reverse (distinct (reverse (apply concat coll))))))

(defn combine [parent-env child-env]
  (let [{:keys [is joins affects effects finally]} parent-env
        {:keys [as join
                af af-env af-end
                ef ef-env ef-end
                fin fin-end]
         child-joins :joins
         child-affects :affects
         child-effects :effects
         child-finally :finally} child-env
        [afs af-envs af-ends] (map muff [af af-env af-end])
        [efs ef-envs ef-ends] (map muff [ef ef-env ef-end])
        [afs af-ends] (mapv (partial merge-env as) [afs af-ends])
        [efs ef-ends] (mapv (partial merge-env as) [efs ef-ends])
        [fins fin-ends] (mapv muff [fin fin-end])
        [fins fin-ends] [(mapv (partial tag-fn as) fins)
                         (mapv (partial tag-fn as) fin-ends)]
        threaded-joins (thread-joins as (muff join))
        static-joins (concat-distinct threaded-joins child-joins joins)
        static-affects (concat-distinct afs (apply-env as af-envs) child-affects affects af-ends)
        static-effects (concat-distinct efs (apply-env as ef-envs) child-effects effects ef-ends)
        static-finally (concat-distinct fins child-finally finally fin-ends)
        comp-joins (apply comp (reverse (concat [wrap-joins] static-joins [:joined-env])))
        comp-affects (apply comp (reverse static-affects))
        comp-effects (apply comp (reverse static-effects))
        comp-finally (apply comp (reverse static-finally))
        combined-env (merge
                      parent-env
                      (dissoc child-env :as :join :joined-env :child-env :parent-env :af :af-env :af-end :ef :ef-env :ef-end :fin :fin-end)
                      {:is           as
                       :was          is
                       :comp-joins   comp-joins
                       :comp-affects comp-affects
                       :comp-effects comp-effects
                       :comp-finally comp-finally
                       :joins        static-joins
                       :affects      static-affects
                       :effects      static-effects
                       :finally      static-finally})
        joined-env (if-let [joined-env (comp-joins parent-env child-env)]
                     (if (seq joined-env)
                       (merge combined-env joined-env)
                       combined-env)
                     combined-env)]
    joined-env))

(defn combine-and-run-affects [parent-env child-env]
  (let [grown-env (combine parent-env child-env)
        combined-env ((:comp-affects grown-env) grown-env)
        af-once (:af-one combined-env identity)
        one-time-env (af-once combined-env)
        final-af-env (merge (dissoc combined-env :af-one)
                            (dissoc one-time-env :af-one))]
    final-af-env))

(defn run-effects [env & args]
  (let [comp-effects (:comp-effects env)
        combined-args (concat (or (:args env) []) args)
        env-and-args (assoc env :args combined-args)
        effected-env (comp-effects env-and-args)
        ef-once (:ef-one effected-env identity)
        one-time-env (ef-once effected-env)
        final-ef-env (merge (dissoc effected-env :ef-one)
                            (dissoc one-time-env :ef-one))
        final-args (:args final-ef-env)
        op-env (:op-env final-ef-env)
        op (:op final-ef-env)
        operated-val (if op-env
                       (apply op-env final-ef-env final-args)
                       (apply op final-args))
        comp-finally (:comp-finally final-ef-env)
        finally-val (comp-finally operated-val)]
    finally-val))

(defn pp-env [{:as env :keys [with joins affects effects finally]}]
  (let [out-map (merge (dissoc env :comp-joins :comp-effects :comp-affects :comp-finally)
                       (when (and (not (fn? with)) (seq with))
                         {:with (mapv #(-> (% :af/env) :is) with)})
                       (when (fn? with)
                         {:with [(-> (with :af/env)  :is)]})
                       (when (seq joins)
                         {:joins (mapv (comp :as meta) joins)})
                       (when (seq affects)
                         {:affects (mapv (comp :as meta) affects)})
                       (when (seq effects)
                         {:effects (mapv (comp :as meta) effects)})
                       (when (seq finally)
                         {:finally (mapv (comp :as meta) finally)}))]
    (pp/pprint out-map)))

(defn proto-fect [parent-env child-env]
  (let [combined-env (combine-and-run-affects parent-env child-env)]
    (fn [& args]
      (if-let [env-or-args (first args)]
        (cond (or (= :af/env env-or-args) (= :affect/env env-or-args))
              combined-env
              (= :af/pp env-or-args)
              (pp-env combined-env)
              (:as env-or-args)
              (proto-fect (dissoc combined-env :ef-one)
                          (assoc env-or-args :args (rest args)))
              (:is env-or-args)
              (let [new-affect (proto-fect (dissoc combined-env :ef-one)
                                           (assoc env-or-args
                                                  :as (:is env-or-args)
                                                  :args (rest args)))]
                (new-affect))
              :else
              (let [return-value (apply run-effects combined-env args)]
                return-value))
        (run-effects combined-env)))))

(def fect*
  (proto-fect
   base
   {:is :affect/base :as :affect/base}))

(def void
  (fect*
   {:as :void
    :join (fn void-join [env1 env2]
            {:void (vec (set (concat (-> env2 :void muff)
                                     (-> env1 :void muff))))})
    :af-env (fn void-af-env [{:as env :keys [void]}]
              (apply dissoc env :with (muff void)))}))

(defn <-data [affect]
  (if (fn? affect)
    (affect :affect/env)
    affect))

(def with
  (void
   {:as :with
    :void :with
    :af-env (fn with-af-env [{:as env :keys [with]}]
              (if-not with
                env
                (let [without (-> env (assoc :as (:is env)) (dissoc :with))]
                  (->> with
                       muff
                       (#(concat [void] %))
                       (reduce #(combine (<-data %1) (<-data %2)))
                       (#(combine % without))))))}))

(def children
  (with
   {:as :children
    :void :children
    :ef (fn children-af [{:as env :keys [children args]}]
          (let [children (if-not children
                           []
                           (if-not (fn? children)
                             children
                             (children env)))]
            {:args (vec (concat args children))}))}))

(def fect
  (children
   {:as :fect}))
