package dynamic.neuralnetwork

import ClusterSchedulingSimulation.CellState
import breeze.linalg.DenseMatrix

import scala.collection.mutable.ArrayBuffer

object JaviNN {

  def classify(cellState: CellState): String = {
    val jobCacheService = cellState.simulator.jobCache.filter(tuple => tuple._2.workloadName == "Service")
    val jobCacheBatch = cellState.simulator.jobCache.filter(tuple => tuple._2.workloadName == "Batch")

    if (jobCacheService.length > 1 && jobCacheBatch.length > 1) {
      val jobCacheLength = cellState.simulator.jobCache.length
      val windowSize = 10
      //Service
      val pastTuplesService = if (jobCacheService.length > windowSize + 1) jobCacheService.slice(jobCacheService.length - (windowSize + 1), jobCacheService.length) else jobCacheService
      val arraySizeService = if (pastTuplesService.length > 0) pastTuplesService.length - 1 else 0
      val interArrivalService = new Array[Double](arraySizeService)
      var durationService = 0.0
      var queueFirstService = 0.0
      var queueFullService = 0.0
      var cpuService = 0.0
      var memService = 0.0
      var numTasksService = 0
      for (i <- 1 to pastTuplesService.length - 1) {
        interArrivalService(i - 1) = (pastTuplesService(i)._1 - pastTuplesService(i - 1)._1)
        durationService += pastTuplesService(i)._2.taskDuration
        queueFirstService += pastTuplesService(i)._2.timeInQueueTillFirstScheduled
        queueFullService += pastTuplesService(i)._2.timeInQueueTillFullyScheduled
        cpuService += pastTuplesService(i)._2.cpusPerTask
        memService += pastTuplesService(i)._2.memPerTask
        numTasksService += pastTuplesService(i)._2.numTasks
      }
      var interArrivalMeanService = interArrivalService.sum / interArrivalService.size.toDouble
      durationService = durationService / pastTuplesService.length
      queueFirstService = queueFirstService / pastTuplesService.length
      queueFullService = queueFullService / pastTuplesService.length
      cpuService = cpuService / pastTuplesService.length
      memService = memService / pastTuplesService.length
      numTasksService = numTasksService / pastTuplesService.length
      //Batch
      val pastTuplesBatch = if (jobCacheBatch.length > windowSize + 1) jobCacheBatch.slice(jobCacheBatch.length - (windowSize + 1), jobCacheBatch.length) else jobCacheBatch
      val arraySizeBatch = if (pastTuplesBatch.length > 0) pastTuplesBatch.length - 1 else 0
      val interArrivalBatch = new Array[Double](arraySizeBatch)
      var durationBatch = 0.0
      var queueFirstBatch = 0.0
      var queueFullBatch = 0.0
      var cpuBatch = 0.0
      var memBatch = 0.0
      var numTasksBatch = 0
      for (i <- 1 to pastTuplesBatch.length - 1) {
        interArrivalBatch(i - 1) = (pastTuplesBatch(i)._1 - pastTuplesBatch(i - 1)._1)
        durationBatch += pastTuplesBatch(i)._2.taskDuration
        queueFirstBatch += pastTuplesBatch(i)._2.timeInQueueTillFirstScheduled
        queueFullBatch += pastTuplesBatch(i)._2.timeInQueueTillFullyScheduled
        cpuBatch += pastTuplesBatch(i)._2.cpusPerTask
        memBatch += pastTuplesBatch(i)._2.memPerTask
        numTasksBatch += pastTuplesBatch(i)._2.numTasks
      }
      var interArrivalMeanBatch = interArrivalBatch.sum / interArrivalBatch.size.toDouble
      durationBatch = durationBatch / pastTuplesBatch.length
      queueFirstBatch = queueFirstBatch / pastTuplesBatch.length
      queueFullBatch = queueFullBatch / pastTuplesBatch.length
      cpuBatch = cpuBatch / pastTuplesBatch.length
      memBatch = memBatch / pastTuplesBatch.length
      numTasksBatch = numTasksBatch / pastTuplesBatch.length

      //All
      val pastTuples = if (jobCacheLength > windowSize + 1) cellState.simulator.jobCache.slice(jobCacheLength - (windowSize + 1), jobCacheLength) else cellState.simulator.jobCache
      val arraySize = if (pastTuples.length > 0) pastTuples.length - 1 else 0
      val interArrival = new Array[Double](arraySize)
      for (i <- 1 to pastTuples.length - 1) {
        interArrival(i - 1) = (pastTuples(i)._1 - pastTuples(i - 1)._1)
      }
      val interArrivalMean = interArrival.sum / interArrival.size.toDouble

      //Evaluacion
      var array = Array(pastTuplesService.length.toDouble, pastTuplesBatch.length.toDouble, interArrivalMeanService, interArrivalMeanBatch, interArrivalMean, durationService, durationBatch, queueFirstService, queueFirstBatch, queueFullService, queueFullBatch, cpuService, cpuBatch, memService, memBatch, numTasksService, numTasksBatch, cellState.totalOccupiedCpus / cellState.totalCpus)
      val url = "http://127.0.0.1:5000/predict?" + array.mkString(",")
      val result = scala.io.Source.fromURL(url).mkString
      //println("inter-arrival: "+ interArrivalMean + " chosen: " +result)
      result
    }
    ""
  }

}
