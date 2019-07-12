package dynamic.neuralnetwork

import java.util

import ClusterSchedulingSimulation.CellState
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport
import org.nd4j.linalg.factory.Nd4j
import org.apache.commons.io.IOUtils
import org.datavec.api.records.reader.RecordReader
import org.datavec.api.records.reader.impl.csv.CSVRecordReader
import org.datavec.api.split.FileSplit
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator
import org.deeplearning4j.nn.conf.MultiLayerConfiguration
import org.deeplearning4j.nn.conf.NeuralNetConfiguration
import org.deeplearning4j.nn.conf.layers.DenseLayer
import org.deeplearning4j.nn.conf.layers.OutputLayer
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.dataset.DataSet
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator
import org.nd4j.linalg.dataset.api.preprocessor.{DataNormalization, MultiNormalizerMinMaxScaler, NormalizerStandardize}
import org.nd4j.linalg.learning.config.Sgd
import org.nd4j.linalg.lossfunctions.LossFunctions
import org.nd4j.linalg.io.ClassPathResource
import org.nd4j.evaluation.classification.Evaluation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util

import org.datavec.api.transform.transform.doubletransform.MinMaxNormalizer


object JaviNN {

  /*val jsonModelPath = "/Users/damianfernandez/IdeaProjects/cluster-scheduler-simulator/modelos_rrnn/training_nomakespan_cnn_model.json"
  val weightsPath = "/Users/damianfernandez/IdeaProjects/cluster-scheduler-simulator/modelos_rrnn/training_nomakespan_cnn_model.h5"
  val model = KerasModelImport.importKerasSequentialModelAndWeights(jsonModelPath, weightsPath)*/

  //

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
      println("inter-arrival: "+ interArrivalMean + " chosen: " +result)
      result
    }
    ""
  }
//      val array1 = Array(11.0000,   11.0000,  802.2717,  123.7450,  123.7450,  364.6690,   27.9106,    0.0100,    0.0100,    0.0100,    0.0100,    0.4545,    0.4545,    1.5455,    0.4545,   11.0000,  126.0000,    0.1749)
//      val array2 = Array(11.0000,   11.0000,  113.0598,   20.2228,   19.7566,  513.9083,   32.6776,    0.0100,    0.0476,    0.0100,    0.0476,    0.4545,    0.4545,    1.5455,    0.4545,   16.0000,  147.0000,    0.3686)
 /*     val array1 = Array(0.6,   0.6,  0.5,  0.6,  0.72450,  0.4,   0.5,    0.0100,    0.0100,    0.0100,    0.0100,    0.4545,    0.7,    0.6,    0.7,   0.4,  0.50000,    0.1749)
      val array2 = Array(0.6,   0.6,  0.05,   0.09,   0.077,  0.7,   0.6,    0.0100,    0.0476,    0.0100,    0.0476,    0.4545,    0.7,   0.6,    0.7,   0.62,  0.6,    0.3686)
      //var aa = Nd4j.ones(1,18)
      var ndArray = Nd4j.create(array)
      //var reshaped = ndArray.reshape(1, 1, 18)
      var reshaped = ndArray.reshape(1, 1, 18)

      import org.nd4j.linalg.api.ndarray.INDArray
      import org.nd4j.linalg.factory.Nd4j
      val inputs = 18
      val features = Nd4j.zeros(inputs)
      val features1 = Nd4j.zeros(inputs)
      val features2 = Nd4j.zeros(inputs)
      var i = 0
      for (i <- 0 to inputs-1) {
        features.putScalar(Array[Int](i), array(i))
        features1.putScalar(Array[Int](i), array1(i))
        features2.putScalar(Array[Int](i), array2(i))

      }
      var reshaped2 = features.reshape(1, 1, 18)
      var hola = model.output(reshaped2);
      var reshaped3 = features1.reshape(1, 1, 18)
      var reshaped4 = features2.reshape(1, 1, 18)
      println(reshaped3)
      println(reshaped4)
      println(model.output(reshaped3))
      println(model.output(reshaped4))
      //println(model.output(reshaped4)(0))

     /* val trainingData = readCSVDataset(
        "/Users/damianfernandez/IdeaProjects/cluster-scheduler-simulator/modelos_rrnn/training_nomakespan.csv",
        30, 17, 2);
      val a =  new MinMaxNormalizer()
      a.
      val normalizer = new NormalizerStandardize()
      normalizer.fit(trainingData);           //Collect the statistics (mean/stdev) from the training data. This does not modify the input data
      normalizer.transform(trainingData);     //Apply normalization to the training data
      normalizer.transform(features1)*/
    }
    ""
  }

  def readCSVDataset(csvFileClasspath: String, batchSize:Int, labelIndex:Int, numClasses:Int):DataSet = {
    val rr = new CSVRecordReader();
    rr.initialize(new FileSplit(new ClassPathResource(csvFileClasspath).getFile()));
    val iterator = new RecordReaderDataSetIterator(rr,batchSize,labelIndex,numClasses);
    iterator.next();
  }*/

}