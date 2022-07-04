package coop.rchain.sdk.dag.merging

import cats.Order
import cats.syntax.all._

import scala.collection.compat.immutable.LazyList
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.math.Numeric.LongIsIntegral

object DagMergingLogic {

  /** All items in dependency chains. */
  def withDependencies[D](of: Set[D], dependencyMap: Map[D, Set[D]]): Set[D] = {
    def next(curOpt: Option[Set[D]]): Option[Set[D]] = curOpt.flatMap { c =>
      val n = c.flatMap(dependencyMap.getOrElse(_, Set()))
      n.nonEmpty.guard[Option].as(n)
    }
    LazyList.iterate(of.some)(next).takeWhile(_.nonEmpty).flatten.flatten.toSet
  }

  /** Deploys incompatible with finalized body. */
  def incompatibleWithFinal[D](
      acceptedFinally: Set[D],
      rejectedFinally: Set[D],
      conflictsMap: Map[D, Set[D]],
      dependencyMap: Map[D, Set[D]]
  ): Set[D] =
    acceptedFinally.flatMap(conflictsMap.getOrElse(_, Set())) ++
      rejectedFinally.flatMap(dependencyMap.getOrElse(_, Set()))

  /** Split the scope into non overlapping partitions, greedily allocating intersecting chunks to bigger view. */
  def partitionScope[D](views: Seq[Set[D]]): Seq[Set[D]] = {
    val r = LazyList.unfold(views) {
      case (head @ seen) +: tail => (head, tail.map(_ -- seen)).some
      case Seq()                 => none[(Set[D], List[Set[D]])]
    }
    r.toList
  }

  def computeRelationMap[D](directed: Boolean)(
      targetSet: Set[D],
      sourceSet: Set[D],
      relation: (D, D) => Boolean
  ): Map[D, Set[D]] =
    targetSet.iterator
      .flatMap(t => sourceSet.map((t, _)))
      .foldLeft(mutable.Map.empty[D, Set[D]]) {
        case (acc, (target, source)) =>
          if (relation(target, source) && target != source) {
            acc.update(source, acc.get(source).map(_ + target).getOrElse(Set(target)))
            if (!directed)
              acc.update(target, acc.get(target).map(_ + source).getOrElse(Set(source)))
          }
          acc
      }
      .toMap

  /** Build conflicts map. */
  def computeConflictsMap[D] = computeRelationMap[D](directed = false) _

  /** Build dependency map. */
  def computeDependencyMap[D] = computeRelationMap[D](directed = true) _

  /** Compute branches of depending items. */
  def computeBranches[D](target: Set[D], depends: (D, D) => Boolean): Map[D, Set[D]] = {
    val dependencyMap = computeDependencyMap(target, target, depends)
    dependencyMap.foldLeft(dependencyMap) {
      case (acc, (root, depending)) =>
        val rootDependencies = acc.collect { case (k, v) if v.contains(root) => k }
        if (rootDependencies.nonEmpty)
          acc - root |+| rootDependencies.map(_ -> (depending + root)).toMap
        else acc
    } ++ (target -- dependencyMap.flatMap { case (k, v) => v + k }).map(_ -> Set.empty[D])
  }

  /**
    * Compute branches of depending items that do not intersect.
    * Partitioning done via [[partitionScopeBiggestFirst]].
    */
  def computeGreedyNonIntersectingBranches[D: Ordering](
      target: Set[D],
      depends: (D, D) => Boolean
  ): Seq[Set[D]] = {
    val concurrentRoots = computeBranches(target, depends)
    val sorted =
      concurrentRoots.toList.sortBy { case (k, v) => (-v.size, k) }.map { case (k, v) => v + k }
    partitionScope(sorted)
  }

  /** Lowest fringe across number of fringes. */
  def lowestFringe[B: Ordering](fringes: Set[Set[B]], height: B => Long): Set[B] = {
    require(fringes.nonEmpty, "Cannot compute lowest fringe on empty set.")
    if (fringes.size > 1) fringes.minBy { f =>
      val minBlock = f.minBy(b => (height(b), b))
      (height(minBlock), minBlock)
    } else fringes.head
  }

  /** All items in the conflict scope. */
  def conflictScope[B](latestMessages: Set[B], fringeMessages: Set[B], seen: B => Set[B]): Set[B] =
    latestMessages ++ latestMessages.flatMap(seen) -- fringeMessages -- fringeMessages.flatMap(seen)

  /** All items in the final scope. */
  def finalScope[B: Ordering](
      latestFringe: Set[B],
      lowestFringe: Set[B],
      seen: B => Set[B]
  ): Set[B] =
    latestFringe.flatMap(seen) -- lowestFringe.flatMap(seen) ++ latestFringe

  /** Relation map sufficient for merge set. */
  def computeRelationMapForMergeSet[D](
      conflictSet: Set[D],
      finalSet: Set[D],
      conflicts: (D, D) => Boolean,
      depends: (D, D) => Boolean
  ): (Map[D, Set[D]], Map[D, Set[D]]) = {
    val conflictsMap = computeConflictsMap(conflictSet, finalSet, conflicts) ++
      computeConflictsMap(conflictSet, conflictSet, conflicts)
    val dependencyMap = computeDependencyMap(conflictSet, finalSet, depends)
    (conflictsMap, dependencyMap)
  }

  // TODO this is o(2^n) algorithm (see [[MergingBenchmarkSpec]]), another that scales should be developed instead
  def computeRejectionOptions[D](conflictsMap: Map[D, Set[D]]): Set[Set[D]] = {
    def step(a: D, rjAcc: Set[D], acAcc: Set[D]): (Set[D], Set[D], Set[D]) = {
      val newRjAcc      = rjAcc ++ conflictsMap(a)
      val newAcAcc      = acAcc + a
      val nextAcOptions = conflictsMap.keySet -- newRjAcc -- newAcAcc
      (nextAcOptions, newRjAcc, newAcAcc)
    }
    val init = conflictsMap.keySet.map(k => (k, Set.empty[D], Set(k)))
    LazyList
      .unfold[(Set[Set[D]]), Set[(D, Set[D], Set[D])]](init) { x =>
        val (done, continue) = x
          .map((step _).tupled)
          .map {
            case (nextAccept, rjAcc, acAcc) =>
              val n    = nextAccept.map((_, rjAcc, acAcc))
              val done = n.isEmpty
              (n, done.guard[Option].as(rjAcc))
          }
          .partition { case (_, rjDone) => rjDone.isDefined }
        val end  = done.isEmpty && continue.isEmpty
        val out  = done.map(_._2.get)
        val next = continue.flatMap(_._1)
        (!end).guard[Option].as((out, next))
      }
      .flatten
      .toSet
  }

  /** Compute optimal rejection according to the target function. */
  def computeOptimalRejection[D: Ordering](
      options: Set[Set[D]],
      targetF: D => Long
  ): Set[D] = {
    implicit val ordD = Order.fromOrdering[D]
    options.toList
      .minimumByOption { rj =>
        (rj.map(targetF).sum, rj.size, rj.toList)
      }
      .getOrElse(Set.empty[D])
  }

  /** Compute rejections options extended with rejections that have to be made due to mergeable value overflow. */
  def addMergeableOverflowRejections[D, CH](
      conflictSet: Set[D],
      rejectOptions: Set[Set[D]],
      initMergeableValues: Map[CH, Long],
      mergeableDiffs: Map[D, Map[CH, Long]]
  ): Set[Set[D]] = {

    def calMergedResult(
        deploy: D,
        initMergeableValues: Map[CH, Long]
    ): Option[Map[CH, Long]] = {
      val diff = mergeableDiffs.getOrElse(deploy, Map())
      diff.foldLeft(initMergeableValues.some) {
        case (accOpt, (channel, change)) =>
          accOpt.flatMap { acc =>
            try {
              val result = Math.addExact(acc.getOrElse(channel, 0L), change)
              if (result < 0) none else Some(acc.updated(channel, result))
            } catch {
              case _: ArithmeticException => none
            }
          }
      }
    }

    def foldRejection(baseBalance: Map[CH, Long], toMerge: Set[D]): Set[D] = {
      // Sort by sum of absolute diffs
      val sorted = toMerge.toList.sortBy { d =>
        mergeableDiffs.get(d).map(_.values.map(Math.abs).sum).getOrElse(Long.MinValue)
      }
      val (_, rejected) = sorted.foldLeft((baseBalance, Set.empty[D])) {
        case ((balances, rejected), deploy) =>
          // TODO come up with a better algorithm to solve below case
          // currently we are accumulating result from some order and reject the deploy once negative result happens
          // which doesn't seem perfect cases below
          //
          // base result 10 and folding the result from order like [-10, -1, 20]
          // which on the second case `-1`, the calculation currently would reject it because the result turns
          // into negative.However, if you look at the all the item view 10 - 10 -1 + 20 is not negative
          calMergedResult(deploy, balances).fold((balances, rejected + deploy))((_, rejected))
      }
      rejected
    }

    if (rejectOptions.isEmpty) {
      Set(foldRejection(initMergeableValues, conflictSet))
    } else {
      rejectOptions.map { normalRejectOptions =>
        normalRejectOptions ++ foldRejection(
          initMergeableValues,
          conflictSet diff normalRejectOptions
        )
      }
    }
  }

  /** Find deploys to reject from conflict set. */
  def resolveConflictSet[D: Ordering, CH](
      conflictSet: Set[D],
      dependencyMap: Map[D, Set[D]],
      conflictsMap: Map[D, Set[D]],
      cost: D => Long,
      mergeableDiffs: Map[D, Map[CH, Long]],
      initMergeableValues: Map[CH, Long]
  ): Set[D] = {
    // conflict map accounting for dependencies
    val fullConflictsMap = conflictsMap.mapValues(vs => vs ++ withDependencies(vs, dependencyMap))
    // find rejection combinations possible
    val rejectionOptions = computeRejectionOptions(fullConflictsMap)
    // add to rejection options rejections caused by mergeable channels overflow
    val mergeableOverflowRejectionOptions = addMergeableOverflowRejections(
      conflictSet,
      rejectionOptions,
      initMergeableValues,
      mergeableDiffs
    )
    // find optimal rejection
    computeOptimalRejection(mergeableOverflowRejectionOptions, cost)
  }

  /** Compute merge for the DAG. */
  def resolveDag[B: Ordering, D: Ordering, CH, S](
      // DAG
      latestMessages: Set[B],
      seen: B => Set[B],
      // finalization
      latestFringe: Set[B],
      acceptedFinally: Set[D],
      rejectedFinally: Set[D],
      // deploys
      deploysIndex: Map[B, Set[D]],
      cost: D => Long,
      // relations
      conflictsMap: Map[D, Set[D]],
      dependencyMap: Map[D, Set[D]],
      // support for mergeable
      mergeableDiffs: Map[D, Map[CH, Long]],
      initMergeableValues: Map[CH, Long]
  ): (Set[D], Set[D]) = {
    val enforceRejected = withDependencies(
      incompatibleWithFinal(acceptedFinally, rejectedFinally, conflictsMap, dependencyMap),
      dependencyMap
    )
    // conflict set without deploys conflicting with finalization
    val conflictSet           = conflictScope(latestMessages, latestFringe, seen).flatMap(deploysIndex)
    val conflictSetCompatible = conflictSet -- enforceRejected
    val resolved = resolveConflictSet(
      conflictSetCompatible,
      dependencyMap,
      conflictsMap,
      cost,
      mergeableDiffs,
      initMergeableValues
    )
    (conflictSetCompatible -- resolved, resolved ++ enforceRejected)
  }
}
