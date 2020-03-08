/**
  * Copyright (c) 2013, Regents of the University of California
  * All rights reserved.
  *
  * Redistribution and use in source and binary forms, with or without
  * modification, are permitted provided that the following conditions are met:
  *
  * Redistributions of source code must retain the above copyright notice, this
  * list of conditions and the following disclaimer.  Redistributions in binary
  * form must reproduce the above copyright notice, this list of conditions and the
  * following disclaimer in the documentation and/or other materials provided with
  * the distribution.  Neither the name of the University of California, Berkeley
  * nor the names of its contributors may be used to endorse or promote products
  * derived from this software without specific prior written permission.  THIS
  * SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
  * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
  * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
  * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
  * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
  * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
  * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */

package ClusterSchedulingSimulation

import java.io.File

import scala.collection.mutable.HashMap
import scala.util.Random

/**
  * Set up workloads based on measurements from a real cluster.
  * In the Eurosys paper, we used measurements from Google clusters here.
  */

/**
  * Copyright (c) 2013, Regents of the University of California
  * All rights reserved.
  *
  * Redistribution and use in source and binary forms, with or without
  * modification, are permitted provided that the following conditions are met:
  *
  * Redistributions of source code must retain the above copyright notice, this
  * list of conditions and the following disclaimer.  Redistributions in binary
  * form must reproduce the above copyright notice, this list of conditions and the
  * following disclaimer in the documentation and/or other materials provided with
  * the distribution.  Neither the name of the University of California, Berkeley
  * nor the names of its contributors may be used to endorse or promote products
  * derived from this software without specific prior written permission.  THIS
  * SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
  * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
  * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
  * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
  * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
  * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
  * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
  * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  */

/**
  * Set up workloads based on measurements from a real cluster.
  * In the Eurosys paper, we used measurements from Google clusters here.
  */
object Workloads {
  /**
    * Set up CellStateDescs that will go into WorkloadDescs. Fabricated
    * numbers are provided as an example. Enter numbers based on your
    * own clusters instead.
    */

  //Cloud
  val numMach = 1000
  val min = 1.0
  val max = 1.3
  //val machinesPerformance = Array.fill[Double](numMach)(Random.nextDouble() * (1.5) + 0.5)
  /*val machinesPerformance = Array.fill[Double](numMach)({
    var penalty = 1.0
    if(Random.nextDouble() > 0.5)
      penalty = min + (max-min) * Random.nextDouble()
    penalty
  })*/
  val machinesPerformance = Array.fill[Double](numMach)(1.0)
  //val machinesSecurity = Array.fill[Int](numMach)(Random.nextInt(4))
  val machinesSecurity = Array.fill[Int](numMach)(4) //Every machine has the highest level so it can execute every task. We need to disable the performance penalty of the security
  //val machinesEnergy = Array.fill[Double](numMach)(Random.nextDouble() * (1.5) + 0.5)
  val machinesEnergy = Array.fill[Double](numMach)(1.0)
  val machineHeterogeneity = false


  val cloudCellStateDesc = new CellStateDesc(numMachines = numMach,
    cpusPerMachine = 4,
    memPerMachine = 8,
    machinesHet = machineHeterogeneity,
    machEn = machinesEnergy,
    machPerf = machinesPerformance,
    machSec = machinesSecurity)



  //Edge
  val numEdgeClusters=4


  //SBC

  val numMachEdge = 500
  val maxRangePerformanceEdge = 5.0
  val minRangePerformanceEdge = 4.5
  //val machinesPerformanceEdge = Array.fill[Double](numMachEdge)(minRangePerformanceEdge + (maxRangePerformanceEdge - minRangePerformanceEdge) * Random.nextDouble())
  val machinesPerformanceEdge = Array.fill[Double](numMach)({
    var penalty = 1.0
    if(Random.nextDouble() > 0.5)
      penalty = minRangePerformanceEdge + (maxRangePerformanceEdge-minRangePerformanceEdge) * Random.nextDouble()
    penalty
  })
  //val machinesPerformanceEdge = Array.fill[Double](numMachEdge)(5)
  //val machinesSecurity = Array.fill[Int](numMach)(Random.nextInt(4))
  val machinesSecurityEdge = Array.fill[Int](numMachEdge)(4) //Every machine has the highest level so it can execute every task. We need to disable the performance penalty of the security
  //val machinesEnergyEdge = Array.fill[Double](numMach)(minRangePerformanceEdge + (maxRangePerformanceEdge - minRangePerformanceEdge) * Random.nextDouble())
  val machinesEnergyEdge = Array.fill[Double](numMachEdge)(0.1)
  val machineHeterogeneityEdge = true


  val sbcCellStateDesc = new CellStateDesc(numMachines = numMachEdge,
    cpusPerMachine = 1,
    memPerMachine = 1,
    machinesHet = machineHeterogeneityEdge,
    machEn = machinesEnergyEdge,
    machPerf = machinesPerformanceEdge,
    machSec = machinesSecurityEdge)



  //Cloudlet Cloud
  val numMachEdgeCloud = 50
  //val machinesPerformanceEdge = Array.fill[Double](numMachEdge)(minRangePerformanceEdge + (maxRangePerformanceEdge - minRangePerformanceEdge) * Random.nextDouble())
  val machinesPerformanceEdgeCloud = Array.fill[Double](numMachEdgeCloud)(Random.nextDouble() * (1.5) + 0.5)
  //val machinesSecurity = Array.fill[Int](numMach)(Random.nextInt(4))
  val machinesSecurityEdgeCloud = Array.fill[Int](numMachEdgeCloud)(4) //Every machine has the highest level so it can execute every task. We need to disable the performance penalty of the security
  //val machinesEnergyEdge = Array.fill[Double](numMach)(minRangePerformanceEdge + (maxRangePerformanceEdge - minRangePerformanceEdge) * Random.nextDouble())
  val machinesEnergyEdgeCloud = Array.fill[Double](numMachEdgeCloud)(1.0)
  val machineHeterogeneityEdgeCloud = false


  val heCloudletCellStateDesc = new CellStateDesc(numMachines = numMachEdgeCloud,
    cpusPerMachine = 8,
    memPerMachine = 16,
    machinesHet = machineHeterogeneityEdgeCloud,
    machEn = machinesEnergyEdgeCloud,
    machPerf = machinesPerformanceEdgeCloud,
    machSec = machinesSecurityEdgeCloud)


  //Primer elemento de la tupla Batch, segundo Service
  //val interArrival = ((90, 900) :: (100, 1000) :: Nil)
  //90/10%
  /*val interArrival = ((100.0, 1000.0) :: Nil)
  /*val interArrival0 = for (elem <- (1 to 10 by 5).toList) yield (elem, elem*10)
  val interArrival1 = for (elem <- (10 to 25 by 2).toList) yield (elem, elem*10)
  val interArrival2 = for (elem <- (25 to 55 by 1).toList) yield (elem, elem*10)
  val interArrival3 = for (elem <- (55 to 80 by 2).toList) yield (elem, elem*10)
  val interArrival4 = for (elem <- (80 to 120 by 5).toList) yield (elem, elem*10)*/
  //val interArrival5 = for (elem <- (80 to 150 by 10).toList) yield (elem, elem*10)

  //val interArrival = interArrival0 ::: interArrival1 ::: interArrival2 ::: interArrival3 ::: interArrival4 ::: interArrival5*/
  //val interArrival = for (elem <- (80 to 95 by 5).toList) yield (elem, elem*10)
  val interArrival = for (elem <- (40 to 180 by 20).toList) yield (elem, elem*10)
  //val interArrival = for (elem <- (1 to 10 by 2).toList) yield (elem, elem*10)
  val tasksPerJob = ((180.0, 30.0)  :: Nil)
  val jobDuration = ((90.0, 2000.0) :: Nil)
  val cpusTask = ((0.3, 0.5) :: Nil)
  //val memTask = ((0.5, 1.2) :: Nil)
  val memTask = ((0.5, 1.2) :: Nil)
  val tasksHeterogeneity = false
  val runFlatPattern = false
  val runDayNightPattern = true
  val runWeekPattern = false
  val alphas = (/*(0.2, 120.0)  ::*/(0.3, 9.26053) /*:: (0.4, 3.32335) :: (0.5, 2.0) :: (0.6, 1.50458) :: (0.7, 1.26582) :: (0.8, 1.133)*/:: Nil)

  // example pre-fill workload generators.
  val examplePrefillTraceFileName = "traces/initial-traces/example-init-cluster-state.log"
  assert((new File(examplePrefillTraceFileName)).exists())
  val exampleBatchPrefillTraceWLGenerator =
    new PrefillPbbTraceWorkloadGenerator("PrefillBatch",
      examplePrefillTraceFileName)
  val exampleServicePrefillTraceWLGenerator =
    new PrefillPbbTraceWorkloadGenerator("PrefillService",
      examplePrefillTraceFileName)
  val exampleBatchServicePrefillTraceWLGenerator =
    new PrefillPbbTraceWorkloadGenerator("PrefillBatchService",
      examplePrefillTraceFileName)

  var workloadGeneratorsCloud = List[WorkloadDesc]()
  val numRepetitionAnova = 1
  var workloadGeneratorsIsolatedCloudlet = List[WorkloadDesc]()
  val runExponential = true
  val runWeibull = false

  if(runExponential) {
    for (i <- 1 to numRepetitionAnova) {
      for (inter <- interArrival) {
        for (tasks <- tasksPerJob) {
          for (duration <- jobDuration) {
            for (cpu <- cpusTask) {
              for (mem <- memTask) {
                if (runWeekPattern) {
                  val workloadGeneratorBatch =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadGeneratorService =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloud",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = cloudCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsCloud ::= workloadDesc
                }
                if (runDayNightPattern) {
                  val workloadGeneratorBatch =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadGeneratorService =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloud",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = cloudCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsCloud ::= workloadDesc
                }
                if (runFlatPattern) {
                  val workloadGeneratorBatch =
                    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadGeneratorService =
                    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneity)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloud",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = cloudCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsCloud ::= workloadDesc
                }
              }
            }
          }
        }
      }
    }
  }


  if(runWeibull) {
    for (i <- 1 to numRepetitionAnova) {
      for (inter <- interArrival) {
        for (tasks <- tasksPerJob) {
          for (duration <- jobDuration) {
            for (cpu <- cpusTask) {
              for (mem <- memTask) {
                for (a <- alphas) {
                  if (runWeekPattern) {
                    val workloadGeneratorBatch =
                      new WeeklyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                        initAvgJobInterarrivalTime = inter._1,
                        avgTasksPerJob = tasks._1,
                        avgJobDuration = duration._1,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = mem._1,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadGeneratorService =
                      new WeeklyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                        initAvgJobInterarrivalTime = inter._2,
                        avgTasksPerJob = tasks._2,
                        avgJobDuration = duration._2,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = cpu._2,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadDesc =
                      WorkloadDesc(cell = "cloud",
                        assignmentPolicy = "CMB_PBB",
                        workloadGenerators =
                          workloadGeneratorBatch ::
                            workloadGeneratorService ::
                            Nil,
                        cellStateDesc = cloudCellStateDesc,
                        prefillWorkloadGenerators =
                          List(exampleBatchServicePrefillTraceWLGenerator))
                    workloadGeneratorsCloud ::= workloadDesc
                  }
                  if (runDayNightPattern) {
                    val workloadGeneratorBatch =
                      new DailyWeiWeiWeiWorkloadGenerator(workloadName = "Batch".intern(),
                        initAvgJobInterarrivalTime = inter._1,
                        avgTasksPerJob = tasks._1,
                        avgJobDuration = duration._1,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = mem._1,
                        alpha = a,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadGeneratorService =
                      new DailyWeiWeiWeiWorkloadGenerator(workloadName = "Service".intern(),
                        initAvgJobInterarrivalTime = inter._2,
                        avgTasksPerJob = tasks._2,
                        avgJobDuration = duration._2,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = cpu._2,
                        alpha = a,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadDesc =
                      WorkloadDesc(cell = "cloud",
                        assignmentPolicy = "CMB_PBB",
                        workloadGenerators =
                          workloadGeneratorBatch ::
                            workloadGeneratorService ::
                            Nil,
                        cellStateDesc = cloudCellStateDesc,
                        prefillWorkloadGenerators =
                          List(exampleBatchServicePrefillTraceWLGenerator))
                    workloadGeneratorsCloud ::= workloadDesc
                  }
                  if (runFlatPattern) {
                    val workloadGeneratorBatch =
                      new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                        initAvgJobInterarrivalTime = inter._1,
                        avgTasksPerJob = tasks._1,
                        avgJobDuration = duration._1,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = mem._1,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadGeneratorService =
                      new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                        initAvgJobInterarrivalTime = inter._2,
                        avgTasksPerJob = tasks._2,
                        avgJobDuration = duration._2,
                        avgCpusPerTask = cpu._1,
                        avgMemPerTask = cpu._2,
                        heterogeneousTasks = tasksHeterogeneity)
                    val workloadDesc =
                      WorkloadDesc(cell = "cloud",
                        assignmentPolicy = "CMB_PBB",
                        workloadGenerators =
                          workloadGeneratorBatch ::
                            workloadGeneratorService ::
                            Nil,
                        cellStateDesc = cloudCellStateDesc,
                        prefillWorkloadGenerators =
                          List(exampleBatchServicePrefillTraceWLGenerator))
                    workloadGeneratorsCloud ::= workloadDesc
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  //Para el caso Isolated cloudlets
  var interArrivalEdgeIsolated = ((110.0*numEdgeClusters, 1100.0*numEdgeClusters) :: Nil)
  val tasksPerJobEdgeIsolated = ((180.0, 30.0)  :: Nil)
  val jobDurationEdgeIsolated = ((90.0, 2000.0) :: Nil)
  val cpusTaskEdgeIsolated = ((0.3, 0.5) :: Nil)
  val memTaskEdgeIsolated = ((0.2, 0.7) :: Nil)
  val tasksHeterogeneityEdgeIsolated = false
  val runFlatPatternEdgeIsolated = true
  val runDayNightPatternEdgeIsolated = false
  val runWeekPatternEdgeIsolated = false


  var workloadGeneratorsEdgeIsolated = List[WorkloadDesc]()
  for (i <- 1 to numRepetitionAnova) {
    for (i <- 1 to numEdgeClusters) {
      for (inter <- interArrivalEdgeIsolated) {
        for (tasks <- tasksPerJobEdgeIsolated) {
          for (duration <- jobDurationEdgeIsolated) {
            for (cpu <- cpusTaskEdgeIsolated) {
              for (mem <- memTaskEdgeIsolated) {
                if (runWeekPatternEdgeIsolated) {
                  val workloadGeneratorBatch =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadGeneratorService =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadDescSBCOnly =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescCloudletOnly =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescSBCOnly
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescCloudletOnly
                }
                if (runDayNightPatternEdgeIsolated) {
                  val workloadGeneratorBatch =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadGeneratorService =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadDescSBCOnly =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescCloudletOnly =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescSBCOnly
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescCloudletOnly
                }
                if (runFlatPatternEdgeIsolated) {
                  val workloadGeneratorBatch =
                    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadGeneratorService =
                    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdgeIsolated)
                  val workloadDescSBCOnly =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescCloudletOnly =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescSBCOnly
                  workloadGeneratorsIsolatedCloudlet ::= workloadDescCloudletOnly
                }
              }
            }
          }
        }
      }
    }
  }



  //Collaborative
  //Primer elemento de la tupla Batch, segundo Service
  //val interArrival = ((90, 900) :: (100, 1000) :: Nil)
  //90/10%
  var interArrivalEdge = ((110.0*numEdgeClusters/0.4, 1100.0*numEdgeClusters/0.4) :: Nil)
  val tasksPerJobEdge = ((180.0, 30.0)  :: Nil)
  val jobDurationEdge = ((90.0, 2000.0) :: Nil)
  val cpusTaskEdge = ((0.3, 0.5) :: Nil)
  val memTaskEdge = ((0.2, 0.7) :: Nil)
  val tasksHeterogeneityEdge = false
  val runFlatPatternEdge = true
  val runDayNightPatternEdge = false
  val runWeekPatternEdge = false

  var workloadGeneratorsEdge = List[WorkloadDesc]()
  for (i <- 1 to numRepetitionAnova) {
    for (i <- 1 to numEdgeClusters) {
      for (inter <- interArrivalEdge) {
        for (tasks <- tasksPerJobEdge) {
          for (duration <- jobDurationEdge) {
            for (cpu <- cpusTaskEdge) {
              for (mem <- memTaskEdge) {
                if (runWeekPatternEdge) {
                  val workloadGeneratorBatch =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadGeneratorService =
                    new WeeklyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescEdgeCloud =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescSBCOnly =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescCloudletOnly =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsEdge ::= workloadDesc
                  workloadGeneratorsEdge ::= workloadDescEdgeCloud
                }
                if (runDayNightPatternEdge) {
                  val workloadGeneratorBatch =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadGeneratorService =
                    new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescEdgeCloud =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsEdge ::= workloadDesc
                  workloadGeneratorsEdge ::= workloadDescEdgeCloud
                }
                if (runFlatPatternEdge) {
                  val workloadGeneratorBatch =
                    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                      initAvgJobInterarrivalTime = inter._1,
                      avgTasksPerJob = tasks._1,
                      avgJobDuration = duration._1,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = mem._1,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadGeneratorService =
                    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                      initAvgJobInterarrivalTime = inter._2,
                      avgTasksPerJob = tasks._2,
                      avgJobDuration = duration._2,
                      avgCpusPerTask = cpu._1,
                      avgMemPerTask = cpu._2,
                      heterogeneousTasks = tasksHeterogeneityEdge)
                  val workloadDesc =
                    WorkloadDesc(cell = "cloudlet-sbc",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = sbcCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  val workloadDescEdgeCloud =
                    WorkloadDesc(cell = "cloudlet-highend",
                      assignmentPolicy = "CMB_PBB",
                      workloadGenerators =
                        workloadGeneratorBatch ::
                          workloadGeneratorService ::
                          Nil,
                      cellStateDesc = heCloudletCellStateDesc,
                      prefillWorkloadGenerators =
                        List(exampleBatchServicePrefillTraceWLGenerator))
                  workloadGeneratorsEdge ::= workloadDesc
                  workloadGeneratorsEdge ::= workloadDescEdgeCloud
                }
              }
            }
          }
        }
      }
    }
  }

  //Cloud centralizado en entornos colaborativos Edge
  //Primer elemento de la tupla Batch, segundo Service
  //val interArrival = ((90, 900) :: (100, 1000) :: Nil)
  //90/10%
  val interArrivalEdgeCloud = ((110.0/0.55, 1100.0/0.55) :: Nil)
  /*val interArrival0 = for (elem <- (1 to 10 by 5).toList) yield (elem, elem*10)
  val interArrival1 = for (elem <- (10 to 25 by 2).toList) yield (elem, elem*10)
  val interArrival2 = for (elem <- (25 to 55 by 1).toList) yield (elem, elem*10)
  val interArrival3 = for (elem <- (55 to 80 by 2).toList) yield (elem, elem*10)
  val interArrival4 = for (elem <- (80 to 120 by 5).toList) yield (elem, elem*10)
  val interArrival5 = for (elem <- (120 to 200 by 10).toList) yield (elem, elem*10)

  val interArrival = interArrival0 ::: interArrival1 ::: interArrival2 ::: interArrival3 ::: interArrival4 ::: interArrival5*/
  //val interArrival = for (elem <- (80 to 95 by 5).toList) yield (elem, elem*10)
  //val interArrival = for (elem <- (50 to 100 by 15).toList) yield (elem, elem*10)
  //val interArrival = for (elem <- (1 to 10 by 2).toList) yield (elem, elem*10)
  val tasksPerJobEdgeCloud = ((180.0, 30.0)  :: Nil)
  val jobDurationEdgeCloud = ((90.0, 2000.0) :: Nil)
  val cpusTaskEdgeCloud = ((0.3, 0.5) :: Nil)
  //val memTask = ((0.5, 1.2) :: Nil)
  val memTaskEdgeCloud = ((0.2, 0.7) :: Nil)
  val tasksHeterogeneityEdgeCloud = false
  val runFlatPatternEdgeCloud = false
  val runDayNightPatternEdgeCloud = true
  val runWeekPatternEdgeCloud = false


  //Cloud centralizado en entornos colaborativos Edge
  var workloadGeneratorsEdgeCloud = List[WorkloadDesc]()
  for (i <- 1 to numRepetitionAnova) {
    for (inter <- interArrivalEdgeCloud) {
      for (tasks <- tasksPerJobEdgeCloud) {
        for (duration <- jobDurationEdgeCloud) {
          for (cpu <- cpusTaskEdgeCloud) {
            for (mem <- memTaskEdgeCloud) {
              if (runWeekPatternEdgeCloud) {
                val workloadGeneratorBatch =
                  new WeeklyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                    initAvgJobInterarrivalTime = inter._1,
                    avgTasksPerJob = tasks._1,
                    avgJobDuration = duration._1,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = mem._1,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadGeneratorService =
                  new WeeklyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                    initAvgJobInterarrivalTime = inter._2,
                    avgTasksPerJob = tasks._2,
                    avgJobDuration = duration._2,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = cpu._2,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadDesc =
                  WorkloadDesc(cell = "cloud",
                    assignmentPolicy = "CMB_PBB",
                    workloadGenerators =
                      workloadGeneratorBatch ::
                        workloadGeneratorService ::
                        Nil,
                    cellStateDesc = cloudCellStateDesc,
                    prefillWorkloadGenerators =
                      List(exampleBatchServicePrefillTraceWLGenerator))
                workloadGeneratorsEdgeCloud ::= workloadDesc
              }
              if (runDayNightPatternEdgeCloud) {
                val workloadGeneratorBatch =
                  new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                    initAvgJobInterarrivalTime = inter._1,
                    avgTasksPerJob = tasks._1,
                    avgJobDuration = duration._1,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = mem._1,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadGeneratorService =
                  new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                    initAvgJobInterarrivalTime = inter._2,
                    avgTasksPerJob = tasks._2,
                    avgJobDuration = duration._2,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = cpu._2,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadDesc =
                  WorkloadDesc(cell = "cloud",
                    assignmentPolicy = "CMB_PBB",
                    workloadGenerators =
                      workloadGeneratorBatch ::
                        workloadGeneratorService ::
                        Nil,
                    cellStateDesc = cloudCellStateDesc,
                    prefillWorkloadGenerators =
                      List(exampleBatchServicePrefillTraceWLGenerator))
                workloadGeneratorsEdgeCloud ::= workloadDesc
              }
              if (runFlatPattern) {
                val workloadGeneratorBatch =
                  new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
                    initAvgJobInterarrivalTime = inter._1,
                    avgTasksPerJob = tasks._1,
                    avgJobDuration = duration._1,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = mem._1,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadGeneratorService =
                  new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
                    initAvgJobInterarrivalTime = inter._2,
                    avgTasksPerJob = tasks._2,
                    avgJobDuration = duration._2,
                    avgCpusPerTask = cpu._1,
                    avgMemPerTask = cpu._2,
                    heterogeneousTasks = tasksHeterogeneityEdgeCloud)
                val workloadDesc =
                  WorkloadDesc(cell = "cloud",
                    assignmentPolicy = "CMB_PBB",
                    workloadGenerators =
                      workloadGeneratorBatch ::
                        workloadGeneratorService ::
                        Nil,
                    cellStateDesc = cloudCellStateDesc,
                    prefillWorkloadGenerators =
                      List(exampleBatchServicePrefillTraceWLGenerator))
                workloadGeneratorsEdgeCloud ::= workloadDesc
              }
            }
          }
        }
      }
    }
  }

  //Tres workload generator: workloadGEneratorsCloud: Cloud only
  // workloadGeneratorsEdge: tanto para los cloudlet sbc como los high end
  // workloadGeneratorsEdgeCloud: para el cloud cuando colabora con cloudlets
  val workloadGenerators = workloadGeneratorsCloud
  /*
    /**
      * Reddit
      */


    /*val exampleWorkloadGeneratorBatch =
      new TraceReadWLGenerator(workloadName = "Batch".intern(),
        traceFileName = "/Users/damianfernandez/Downloads/GeneratedLoadReddit-10minutes-2009-02.csv",
        maxCpusPerTask = 3.9,
        maxMemPerTask = 7.9)*/
    val exampleWorkloadGeneratorService =
      new TraceReadWLGenerator(workloadName = "Service".intern(),
        traceFileName = "/Users/damianfernandez/Downloads/GeneratedLoadReddit-10minutes-2009-02.csv",
        maxCpusPerTask = 3.9,
        maxMemPerTask = 7.9)
    val TraceReadWLGenerator = WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
          exampleWorkloadGeneratorService :: Nil,
      cellStateDesc = exampleCellStateDesc)
  */

  /**
    * Patron dia noche generado
    */

  /*
    val exampleWorkloadGeneratorBatch =
      new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
        initAvgJobInterarrivalTime = 14,
        avgTasksPerJob = 180.0,
        avgJobDuration = (90.0),
        avgCpusPerTask = 0.3,
        avgMemPerTask = 0.5,
  heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadGeneratorService =
      new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
        initAvgJobInterarrivalTime = 140,
        avgTasksPerJob = 30.0,
        avgJobDuration = (2000.0),
        avgCpusPerTask = 0.5,
        avgMemPerTask = 1.2,
  heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadDesc = WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService :: Nil,
      cellStateDesc = exampleCellStateDesc)

    */
  //1000 maquinas

  /*  val exampleWorkloadGeneratorBatch =
    new DailyExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 90,
      avgTasksPerJob = 50.0,
      avgJobDuration = (90.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5,
      heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadGeneratorService =
      new DailyExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
        initAvgJobInterarrivalTime = 900,
        avgTasksPerJob = 9.0,
        avgJobDuration = (2000.0),
        avgCpusPerTask = 0.5,
        avgMemPerTask = 1.2,
        heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadDesc = WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService :: Nil,
      cellStateDesc = exampleCellStateDesc)*/


  /*
    /**
      * Este e sel bueno
      */

    /**
      * Set up WorkloadDescs, containing generators of workloads and
      * pre-fill workloads based on measurements of cells/workloads.
      */
    val exampleWorkloadGeneratorBatch =
      new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
        initAvgJobInterarrivalTime = 14,
        avgTasksPerJob = 180.0,
        avgJobDuration = (90.0),
        avgCpusPerTask = 0.3,
        avgMemPerTask = 0.5,
  heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadGeneratorService =
      new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
        initAvgJobInterarrivalTime = 140,
        avgTasksPerJob = 30.0,
        avgJobDuration = (2000.0),
        avgCpusPerTask = 0.5,
        avgMemPerTask = 1.2,
  heterogeneousTasks = tasksHeterogeneity)
    val exampleWorkloadDesc = WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService :: Nil,
      cellStateDesc = exampleCellStateDesc)
  */

  /*val exampleWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService ::
          Nil,
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))*/


  // Set up example workload with jobs that have interarrival times
  // from trace-based interarrival times.
  val exampleInterarrivalTraceFileName = "traces/job-distribution-traces/" +
    "example_interarrival_cmb.log"
  val exampleNumTasksTraceFileName = "traces/job-distribution-traces/" +
    "example_csizes_cmb.log"
  val exampleJobDurationTraceFileName = "traces/job-distribution-traces/" +
    "example_runtimes_cmb.log"
  assert((new File(exampleInterarrivalTraceFileName)).exists())
  assert((new File(exampleNumTasksTraceFileName)).exists())
  assert((new File(exampleJobDurationTraceFileName)).exists())

  // A workload based on traces of interarrival times, tasks-per-job,
  // and job duration. Task shapes now based on pre-fill traces.
  val exampleWorkloadGeneratorTraceAllBatch =
  new TraceAllWLGenerator(
    "Batch".intern(),
    exampleInterarrivalTraceFileName,
    exampleNumTasksTraceFileName,
    exampleJobDurationTraceFileName,
    examplePrefillTraceFileName,
    maxCpusPerTask = 3.9, // Machines in example cluster have 4 CPUs.
    maxMemPerTask = 7.9) // Machines in example cluster have 16GB mem.

  val exampleWorkloadGeneratorTraceAllService =
    new TraceAllWLGenerator(
      "Service".intern(),
      exampleInterarrivalTraceFileName,
      exampleNumTasksTraceFileName,
      exampleJobDurationTraceFileName,
      examplePrefillTraceFileName,
      maxCpusPerTask = 3.9,
      maxMemPerTask = 7.9)

  val exampleTraceAllWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorTraceAllBatch ::
          exampleWorkloadGeneratorTraceAllService ::
          Nil,
      cellStateDesc = cloudCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))

  //Este es el que usa el simulador, hay que convertirlo en lista

  /*val exampleGeneratedWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService ::
          Nil,
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))*/
  /*Solo service

    val exampleGeneratedWorkloadPrefillDesc =
      WorkloadDesc(cell = "example",
        assignmentPolicy = "CMB_PBB",
        workloadGenerators =
            exampleWorkloadGeneratorService ::
            Nil,
        cellStateDesc = exampleCellStateDesc,
        prefillWorkloadGenerators =
          List(exampleBatchServicePrefillTraceWLGenerator))*/

  /* Solo batch
  val exampleGeneratedWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          Nil,
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))

        */

}

