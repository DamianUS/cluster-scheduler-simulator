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

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.channels.FileChannel
import java.util.Locale

import ClusterSchedulingSimulation.Workloads._
import ClusterSchedulingSimulation._
import ca.zmatrix.utils._
import dynamic.{DynamicSchedulerDesc, DynamicSimulatorDesc}
import efficiency.ordering_cellstate_resources_policies.{BasicLoadSorter, CellStateResourcesSorter, NoSorter, PowerStateLoadSorter}
import efficiency.pick_cellstate_resources._
import efficiency.power_off_policies.action.DefaultPowerOffAction
import efficiency.power_off_policies.decision.deterministic.load.{LoadMaxPowerOffDecision, LoadMeanPowerOffDecision}
import efficiency.power_off_policies.decision.deterministic.security_margin.{ConstantMarginPowerOffDecision, FreeCapacityMeanMarginPowerOffDecision, FreeCapacityMinMarginPowerOffDecision, WeightedFreeCapacityMarginPowerOffDecision}
import efficiency.power_off_policies.decision.deterministic.{AlwzPowerOffDecision, NoPowerOffDecision}
import efficiency.power_off_policies.decision.probabilistic._
import efficiency.power_off_policies.{ComposedPowerOffPolicy, PowerOffPolicy}
import efficiency.power_on_policies.action.CombinedPowerOnAction
import efficiency.power_on_policies.action.margin.PowerOnMarginPercAvailableAction
import efficiency.power_on_policies.action.probabilistic.{GammaNormalPowerOnAction, GammaPowerOnAction}
import efficiency.power_on_policies.action.unsatisfied.DefaultPowerOnAction
import efficiency.power_on_policies.decision.probabilistic.{ExponentialPowerOnDecision, GammaNormalPowerOnDecision}
import efficiency.power_on_policies.decision._
import efficiency.power_on_policies.{ComposedPowerOnPolicy, PowerOnPolicy}
import stackelberg.{NoStackelberg, StackelbergAgent, SwitchBetweenCurrentAndSpecified}

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

object Simulation {
  def main(args: Array[String]) {
    Locale.setDefault(Locale.US);

    val helpString = "Usage: bin/sbt run [--thread-pool-size INT_NUM_THREADS] [--random-seed INT_SEED_VALUE]"
    if (args.length > 0) {
      if (args.head.equals("--help") || args.head.equals("-h")) {
        println(helpString)
        System.exit(0)
      }
    }
    val pp = new ParseParms(helpString)
    pp.parm("--thread-pool-size", "4").rex("^\\d*") // optional_arg
    pp.parm("--random-seed").rex("^\\d*") // optional_arg

    var inputArgs = Map[String, String]()
    val result = pp.validate(args.toList)
    if (result._1 == false) {
      println(result._2)
      sys.error("Exiting due to invalid input.")
    } else {
      inputArgs = result._3
    }

    println("\nRUNNING CLUSTER SIMULATOR EXPERIMENTS")
    println("------------------------\n")

    /**
     * Set up SchedulerDesc-s.
     */
    // Monolithic
    var monolithicSchedulerDesc = new SchedulerDesc(
      name = "Monolithic".intern(),
      constantThinkTimes = Map("Batch" -> 0.01, "Service" -> 0.01),
      perTaskThinkTimes = Map("Batch" -> 0.005, "Service" -> 0.01))

    // Mesos
    var mesosBatchSchedulerDesc = new MesosSchedulerDesc(
      name = "MesosBatch".intern(),
      constantThinkTimes = Map("Batch" -> 0.01),
      perTaskThinkTimes = Map("Batch" -> 0.005),
      schedulePartialJobs = true)

    var mesosServiceSchedulerDesc = new MesosSchedulerDesc(
      name = "MesosService".intern(),
      constantThinkTimes = Map("Service" -> 0.01),
      perTaskThinkTimes = Map("Service" -> 0.01),
      schedulePartialJobs = true)

    val mesosSchedulerDescs = Array(mesosBatchSchedulerDesc,
      mesosServiceSchedulerDesc)

    var mesosBatchScheduler2Desc = new MesosSchedulerDesc(
      name = "MesosBatch-2".intern(),
      constantThinkTimes = Map("Batch" -> 0.01),
      perTaskThinkTimes = Map("Batch" -> 0.005),
      schedulePartialJobs = true)

    var mesosBatchScheduler3Desc = new MesosSchedulerDesc(
      name = "MesosBatch-3".intern(),
      constantThinkTimes = Map("Batch" -> 0.01),
      perTaskThinkTimes = Map("Batch" -> 0.005),
      schedulePartialJobs = true)

    var mesosBatchScheduler4Desc = new MesosSchedulerDesc(
      name = "MesosBatch-4".intern(),
      constantThinkTimes = Map("Batch" -> 0.01),
      perTaskThinkTimes = Map("Batch" -> 0.005),
      schedulePartialJobs = true)

    val mesos4BatchSchedulerDescs = Array(mesosBatchSchedulerDesc,
      mesosBatchScheduler2Desc,
      mesosBatchScheduler3Desc,
      mesosBatchScheduler4Desc,
      mesosServiceSchedulerDesc)


    // Dynamic
    def generateDynamicSchedulerDescs(numServiceScheds: Int,
                                    numBatchScheds: Int)
    : Array[DynamicSchedulerDesc] = {
      val schedDescs = ArrayBuffer[DynamicSchedulerDesc]()
      (1 to numBatchScheds).foreach(i => {
        schedDescs +=
          new DynamicSchedulerDesc(name = "DynamicBatch-%d".format(i).intern(),
            constantThinkTimes = Map("Batch" -> 0.01),
            perTaskThinkTimes = Map("Batch" -> 0.01))
      })
      (1 to numServiceScheds).foreach(i => {
        schedDescs +=
          new DynamicSchedulerDesc(name = "DynamicService-%d".format(i).intern(),
            constantThinkTimes = Map("Service" -> 0.01),
            perTaskThinkTimes = Map("Service" -> 0.01))
      })
      println("Generated schedulerDescs: " + schedDescs)
      schedDescs.toArray
    }


    // Omega
    def generateOmegaSchedulerDescs(numServiceScheds: Int,
                                    numBatchScheds: Int)
    : Array[OmegaSchedulerDesc] = {
      val schedDescs = ArrayBuffer[OmegaSchedulerDesc]()
      (1 to numBatchScheds).foreach(i => {
        schedDescs +=
          new OmegaSchedulerDesc(name = "OmegaBatch-%d".format(i).intern(),
            constantThinkTimes = Map("Batch" -> 0.01),
            perTaskThinkTimes = Map("Batch" -> 0.01))
      })
      (1 to numServiceScheds).foreach(i => {
        schedDescs +=
          new OmegaSchedulerDesc(name = "OmegaService-%d".format(i).intern(),
            constantThinkTimes = Map("Service" -> 0.01),
            perTaskThinkTimes = Map("Service" -> 0.01))
      })
      println("Generated schedulerDescs: " + schedDescs)
      schedDescs.toArray
    }

    /**
     * Set up workload-to-scheduler mappings.
     */
    var monolithicSchedulerWorkloadMap =
      Map[String, Seq[String]]("Batch" -> Seq("Monolithic"),
                             "Service" -> Seq("Monolithic"))

    /*var mesos1BatchSchedulerWorkloadMap =
      Map[String, Seq[String]]("Batch" -> Seq("MesosBatch"),
                             "Service" -> Seq("MesosService"))*/

    var mesos4BatchSchedulerWorkloadMap =
      Map[String, Seq[String]]("Batch" -> Seq("MesosBatch",
                                                 "MesosBatch-2",
                                                 "MesosBatch-3",
                                                 "MesosBatch-4"),
                                  "Service" -> Seq("MesosService"))

    /**
     * Returns a Map with mappings from workload to an arbitrary
     * number of schedulers. These mappings are used by the simulator
     * to decide which scheduler to send a job to when it arrives.
     * If more than one scheduler is specified for a single workload
     * name, then the jobs will be scheduled round-robin across all
     * of those schedulers.
     */
    type SchedulerWorkloadMap = Map[String, Seq[String]]
    def generateSchedulerWorkloadMap(schedulerNamePrefix: String,
                                     numServiceScheds: Int,
                                     numBatchScheds: Int)
    : SchedulerWorkloadMap = {
      println("Generating workload map with %d serv scheds & %d batch scheds"
        .format(numServiceScheds, numBatchScheds))
      val schedWorkloadMap = collection.mutable.Map[String, Seq[String]]()
      schedWorkloadMap("Service") =
        (1 to numServiceScheds).map(schedulerNamePrefix + "Service-" + _)
      schedWorkloadMap("Batch") =
        (1 to numBatchScheds).map(schedulerNamePrefix + "Batch-" + _)
      println("Generated schedulerWorkloadMap: " + schedWorkloadMap)
      schedWorkloadMap.toMap
    }

    /**
     * Returns a Map whose entries represent which scheduler/workload pairs
     * to apply the L/C parameter sweep to.
     */
    type SchedulerWorkloadsToSweep = Map[String, Seq[String]]
    def generateSchedulerWorkloadsToSweep(schedulerNamePrefix: String,
                                          numServiceScheds: Int,
                                          numBatchScheds: Int)
    : SchedulerWorkloadsToSweep = {
      println("Generating workload map with %d serv scheds & %d batch scheds"
        .format(numServiceScheds, numBatchScheds))
      val schedWorkloadsToSweep = collection.mutable.Map[String, Seq[String]]()
      (1 to numServiceScheds).foreach { i: Int => {
        schedWorkloadsToSweep(schedulerNamePrefix + "Service-" + i) = Seq("Service")
      }
      }
      (1 to numBatchScheds).foreach { i: Int => {
        schedWorkloadsToSweep(schedulerNamePrefix + "Batch-" + i) = Seq("Batch")
      }
      }
      println("Generated schedulerWorkloadsToSweepMap: " + schedWorkloadsToSweep)
      schedWorkloadsToSweep.toMap
    }

    /**
     * Set up a simulatorDesc-s.
     */
    val globalRunTime = 86400.0 * 7
    //val globalRunTime = 86400.0 * 30 // 1 Day
    val monolithicSimulatorDesc =
      new MonolithicSimulatorDesc(Array(monolithicSchedulerDesc),
        globalRunTime)

    val mesosSimulator1BatchDesc =
      new MesosSimulatorDesc(mesosSchedulerDescs,
        runTime = globalRunTime,
        allocatorConstantThinkTime = 0.001)
    // Mesos simulator with 4 batch schedulers
    val mesosSimulator4BatchDesc =
      new MesosSimulatorDesc(mesos4BatchSchedulerDescs,
        runTime = globalRunTime,
        allocatorConstantThinkTime = 0.001)

/*
    /**
     * Synthetic workloads for testing.
     * These can probably be deleted eventually.
     */
    val synthWorkloadGeneratorService =
      new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
        initAvgJobInterarrivalTime = 5.0,
        avgTasksPerJob = 100,
        avgJobDuration = 10.0,
        avgCpusPerTask = 1.0,
        avgMemPerTask = 1.5)
    val synthWorkloadGeneratorBatch =
      new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
        initAvgJobInterarrivalTime = 3.0,
        avgTasksPerJob = 100,
        avgJobDuration = 10.0,
        avgCpusPerTask = 1.0,
        avgMemPerTask = 1.5)
    val synthWorkloadDesc =
      WorkloadDesc(cell = "synth",
        assignmentPolicy = "none",
        workloadGenerators =
          synthWorkloadGeneratorService ::
            synthWorkloadGeneratorBatch :: Nil,
        cellStateDesc = exampleCellStateDesc)
*/
    /**
     * Set up parameter sweeps.
     */

    // 91 values.
    val fullConstantRange: List[Double] = (0.001 to 0.01 by 0.0005).toList :::
      (0.015 to 0.1 by 0.005).toList :::
      (0.15 to 1.0 by 0.05).toList :::
      (1.5 to 10.0 by 0.5).toList :::
      (15.0 to 100.0 by 5.0).toList // :::
    // (150.0 to 1000.0 by 50.0).toList

    // Full PerTaskRange is 55 values.
    val fullPerTaskRange: List[Double] = (0.001 to 0.01 by 0.0005).toList :::
      (0.015 to 0.1 by 0.005).toList :::
      (0.15 to 1.0 by 0.05).toList // :::
    // (1.5 to 10 by 0.5).toList

    // Full lambda is 20 values.
    val fullLambdaRange: List[Double] = (0.01 to 0.11 by 0.01).toList :::
      (0.15 to 1.0 by 0.1).toList // :::
    // (1.5 to 10.0 by 1.0).toList

    val fullPickinessRange: List[Double] = (0.00 to 0.75 by 0.05).toList


    val medConstantRange: List[Double] = 0.01 :: 0.05 :: 0.1 :: 0.5 ::
      1.0 :: 5.0 :: 10.0 :: 50.0 ::
      100.0 :: Nil
    val medPerTaskRange: List[Double] = 0.001 :: 0.005 :: 0.01 :: 0.05 ::
      0.1 :: 0.5 :: 1.0 :: Nil

    val medLambdaRange: List[Double] = 0.01 :: 0.05 :: 0.1 :: 0.5 :: Nil

    val smallConstantRange: List[Double] = (0.1 to 100.0 by 99.9).toList
    val smallPerTaskRange: List[Double] = (0.1 to 1.0 by 0.9).toList
    val smallLambdaRange: List[Double] = (0.001 to 10.0 by 9.999).toList

    /**
     * Choose which "experiment environments" (i.e. WorkloadDescs)
     * we want to use.
     */
    var allWorkloadDescs = List[WorkloadDesc]()
    //Este es el que estaba
    //allWorkloadDescs ::= exampleGeneratedWorkloadPrefillDesc
    //Nuevo
    allWorkloadDescs = workloadGenerators
    //allWorkloadDescs ::= exampleWorkloadPrefillDesc

    // Prefills jobs based on prefill trace, draws job and task stats from
    // exponential distributions.
    // allWorkloadDescs ::= exampleInterarrivalTimeTraceWorkloadPrefillDesc

    // Prefills jobs based on prefill trace. Loads Job stats (interarrival
    // time, num tasks, duration) from traces, and task stats from
    // exponential distributions.
    // allWorkloadDescs ::= exampleTraceWorkloadPrefillDesc

    // Prefills jobs based on prefill trace. Loads Job stats (interarrival
    // time, num tasks, duration) and task stats (cpusPerTask, memPerTask)
    // from traces.
    //allWorkloadDescs ::= exampleTraceAllWorkloadPrefillDesc

    /**
     * Set up a run of experiments.
     */
    var allExperiments: List[Experiment] = List()
    val wlDescs = allWorkloadDescs


    // -----------------Dynamic-----------------
    val numDynamicServiceSchedsRange = Seq(1)
    val numDynamicBatchSchedsRange = Seq(4)
    //val numOmegaBatchSchedsRange = Seq(1)
    //val strategiesToSwitch = ("Omega" :: "Mesos" :: Nil)
    val strategiesToSwitch = ("Mesos" :: "Omega" :: Nil)
    val dynamicSimulatorSetups =
      for (numDynamicServiceScheds <- numDynamicServiceSchedsRange;
           numDynamicBatchScheds <- numDynamicBatchSchedsRange) yield {
        // List of the different {{SimulatorDesc}}s to be run with the
        // SchedulerWorkloadMap and SchedulerWorkloadToSweep.
        val dynamicSimulatorDescs = for (
          //conflictMode <- Seq("sequence-numbers", "resource-fit");
          conflictMode <- Seq("resource-fit");
          //conflictMode <- Seq("sequence-numbers");
          transactionMode <- Seq("all-or-nothing")) yield {
          //transactionMode <- Seq("all-or-nothing", "incremental")) yield {
          //transactionMode <- Seq("incremental")) yield {
          new DynamicSimulatorDesc(
            generateDynamicSchedulerDescs(numDynamicServiceScheds,
              numDynamicBatchScheds),
            runTime = globalRunTime,
            conflictMode,
            transactionMode,
            strategies = strategiesToSwitch,
            allocatorConstantThinkTime = 0.001)
        }

        val dynamicSchedulerWorkloadMap =
          generateSchedulerWorkloadMap("Dynamic",
            numDynamicServiceScheds,
            numDynamicBatchScheds)

        val dynamicSchedulerWorkloadsToSweep =
          generateSchedulerWorkloadsToSweep("Dynamic",
            numServiceScheds = 1,
            numDynamicBatchScheds)
        (dynamicSimulatorDescs, dynamicSchedulerWorkloadMap, dynamicSchedulerWorkloadsToSweep)
      }


    // ------------------Omega------------------
    val numOmegaServiceSchedsRange = Seq(1)
    val numOmegaBatchSchedsRange = Seq(4)
    //val numOmegaBatchSchedsRange = Seq(1)

    val omegaSimulatorSetups =
      for (numOmegaServiceScheds <- numOmegaServiceSchedsRange;
           numOmegaBatchScheds <- numOmegaBatchSchedsRange) yield {
        // List of the different {{SimulatorDesc}}s to be run with the
        // SchedulerWorkloadMap and SchedulerWorkloadToSweep.
        val omegaSimulatorDescs = for (
          //conflictMode <- Seq("sequence-numbers", "resource-fit");
          conflictMode <- Seq("resource-fit");
          //conflictMode <- Seq("sequence-numbers");
          //transactionMode <- Seq("all-or-nothing")) yield {
          //transactionMode <- Seq("all-or-nothing", "incremental")) yield {
          transactionMode <- Seq("incremental")) yield {
            new OmegaSimulatorDesc(
              generateOmegaSchedulerDescs(numOmegaServiceScheds,
                numOmegaBatchScheds),
              runTime = globalRunTime,
              conflictMode,
              transactionMode)
          }

        val omegaSchedulerWorkloadMap =
          generateSchedulerWorkloadMap("Omega",
            numOmegaServiceScheds,
            numOmegaBatchScheds)

        val omegaSchedulerWorkloadsToSweep =
          generateSchedulerWorkloadsToSweep("Omega",
            numServiceScheds = 1,
            numOmegaBatchScheds)
        (omegaSimulatorDescs, omegaSchedulerWorkloadMap, omegaSchedulerWorkloadsToSweep)
      }

    // ------------------Mesos------------------
    val mesosSimulatorDesc = mesosSimulator4BatchDesc
    //val mesosSimulatorDesc = mesosSimulator1BatchDesc

    val mesosSchedulerWorkloadMap = mesos4BatchSchedulerWorkloadMap
    //val mesosSchedulerWorkloadMap = mesos1BatchSchedulerWorkloadMap

    // val mesosSchedWorkloadsToSweep = Map("MesosBatch" -> List("Batch"),
    //                                      "MesosBatch-2" -> List("Batch"),
    //                                      "MesosBatch-3" -> List("Batch"),
    //                                      "MesosBatch-4" -> List("Batch"))
    //val mesosSchedWorkloadsToSweep = Map("MesosService" -> List("Service"))
    //val mesosSchedWorkloadsToSweep = Map("MesosBatch" -> List("Batch"), "MesosService" -> List("Service"))
    val mesosSchedWorkloadsToSweep = Map("MesosBatch" -> List("Batch"),
                                          "MesosBatch-2" -> List("Batch"),
                                          "MesosBatch-3" -> List("Batch"),
                                          "MesosBatch-4" -> List("Batch"), "MesosService" -> List("Service"))

     val mesosWorkloadToSweep = "Batch"
    // val mesosWorkloadToSweep = "Service"

    val runMonolithic = true
    val runMesos = true
    val runOmega = true
    val runDynamic = false
    val runEdge = false


    val runStackelberg = false

    //All sorting and picking policies
    val sortingPolicies = List[CellStateResourcesSorter](NoSorter,BasicLoadSorter)
    //val pickingPolicies = List[CellStateResourcesPicker] (RandomPicker)
    //val pickingPolicies = List[CellStateResourcesPicker] (BasicReversePickerCandidatePower)
    //val pickingPolicies = List[CellStateResourcesPicker](RandomPicker, BasicReversePickerCandidatePower, new SpreadMarginReversePickerCandidatePower(spreadMargin = 0.05, marginPerc = 0.01))
    //Krakow
    //val pickingPolicies = List[CellStateResourcesPicker](RandomPicker, GASimplePickerCandidatePower, GreedyMakespanPickerCandidatePower)
    //val pickingPolicies = List[CellStateResourcesPicker](new GeneticStandardPickerCandidatePower(populationSize=20, mutationProbability=0.01, crossingSelector=Agnieszka, fitnessFunction = Makespan, epochNumber = 500))
    //val pickingPolicies = List[CellStateResourcesPicker](RandomPicker, GASimplePickerCandidatePower, GreedyMakespanPickerCandidatePower, new GeneticStandardPickerCandidatePower(populationSize=10, mutationProbability=0.01, crossingSelector=Agnieszka, fitnessFunction = Makespan, epochNumber = 300),new GeneticStandardPickerCandidatePower(populationSize=10, mutationProbability=0.01, crossingSelector=RouletteWheel, fitnessFunction = Makespan, epochNumber = 300),new GeneticStandardPickerCandidatePower(populationSize=10, mutationProbability=0.01, crossingSelector=TwoBest, fitnessFunction = Makespan, epochNumber = 300))
    //val pickingPolicies = List[CellStateResourcesPicker](new GeneticMutateWorstGenePicker(populationSize=20, mutationProbability=0.5, crossingSelector=TwoBest, fitnessFunction = Makespan, crossingFunction = CrossGenes, epochNumber = 200))
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaWithRandom)
    //val pickingPolicies = List[CellStateResourcesPicker](GeneticNoCrossingMutatingWorstPicker)
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaWithRandom) //This one is the best so far
    //val pickingPolicies = List[CellStateResourcesPicker](new NewGeneticStandardPicker(populationSize=10, mutationProbability=0.5, crossoverProbability = 0.7, crossingSelector=TwoBest, fitnessFunction = Makespan, epochNumber = 2000, crossingFunction = CrossGenes, mutatingFunction = WorstRandom))
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaSecurityWithRandom,AgnieszkaEnergySecurityWithRandom)
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaSecurityWithRandom, new SpreadMarginReversePickerCandidatePower(spreadMargin = 0.05, marginPerc = 0.07), RandomPicker, AgnieszkaEnergySecurityWithRandom)
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaEnergySecurityWithRandom)
    //val pickingPolicies = List[CellStateResourcesPicker](AgnieszkaSecurityWithRandom)
    val pickingPolicies = List[CellStateResourcesPicker](new SpreadMarginReversePickerCandidatePower(spreadMargin = 0.05, marginPerc = 0.07))
    //val pickingPolicies = List[CellStateResourcesPicker](new SpreadMarginReversePickerCandidatePower(spreadMargin = 0.05, marginPerc = 0.07), AgnieszkaSecurityWithRandom)
    //val pickingPolicies = List[CellStateResourcesPicker](BasicReversePickerCandidatePower)
    val powerOnPolicies = List[PowerOnPolicy](new ComposedPowerOnPolicy(DefaultPowerOnAction, NoPowerOnDecision))
    val powerOffPolicies = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, NoPowerOffDecision))


    //Default sorting and picking policies
    val defaultSortingPolicy = List[CellStateResourcesSorter](PowerStateLoadSorter)
    //val defaultPickingPolicy = List[CellStateResourcesPicker](BasicReversePickerCandidatePower)
    //val defaultPickingPolicy = List[CellStateResourcesPicker](new SpreadMarginReversePickerCandidatePower(spreadMargin = 0.05, marginPerc = 0.02))
    val defaultPickingPolicy = pickingPolicies

    //Stackelberg strategies
    val stackelbergCurrentAlwzOffMarginDecision = new SwitchBetweenCurrentAndSpecified(new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision), new FreeCapacityMinMarginPowerOffDecision(0.35))
    val stackelbergCurrentAlwzOffLessMarginDecision = new SwitchBetweenCurrentAndSpecified(new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision), new FreeCapacityMinMarginPowerOffDecision(0.25))
    val stackelbergCurrentAlwzOffRandomDecision = new SwitchBetweenCurrentAndSpecified(new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision), new RandomPowerOffDecision(0.5))
    val stackelbergCurrentAlwzOffGammaDecision = new SwitchBetweenCurrentAndSpecified(new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision), new GammaPowerOffDecision(0.8, 25, 0.4))
    val stackelbergCurrentAlwzOffExponentialDecision = new SwitchBetweenCurrentAndSpecified(new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision), new ExponentialPowerOffDecision(0.8, 25, 0.4))
    var stackelbergStrategies = List[StackelbergAgent](stackelbergCurrentAlwzOffMarginDecision,NoStackelberg,stackelbergCurrentAlwzOffLessMarginDecision,stackelbergCurrentAlwzOffRandomDecision,stackelbergCurrentAlwzOffGammaDecision,stackelbergCurrentAlwzOffExponentialDecision)
    if(!runStackelberg){
      stackelbergStrategies = List[StackelbergAgent](NoStackelberg)
    }


    val loadRange = (0.1 :: 0.5 :: 0.9 :: Nil)
    //val loadRange = (0.1 to 0.99 by 0.2).toList
    val defaultLoadRange = 0.5

    val freeCapacityRange = (0.35 :: 0.4 :: 0.45 ::  Nil)
    val freeCapacityOnRange = (0.05 :: 0.1 :: Nil)

    val constantCapacityRange = (0.1 :: 0.2 :: 0.3 ::  Nil)

    //val freeCapacityRange = (0.1 to 0.99 by 0.2).toList
    val defaultFreeCapacityRange = 0.45
    val defaultConstantCapacityRange = 0.15
    val defaultFreeCapacityOnRange = 0.1

    val randomRange = (0.1 to 0.99 by 0.2).toList
    val randomDefaultThreshold = 0.5

    //val normalThresholdRange = (0.05 to 0.99 by 0.1).toList
    val normalThresholdRange = (0.8 :: 0.85 :: 0.9 :: Nil)
   // val normalThresholdRange = (0.05 to 0.5 by 0.05).toList
    val defaultNormalThreshold = 0.85
/*
    //val distributionThresholdRange = (0.05 to 0.99 by 0.1).toList
    //val distributionThresholdRange = (0.01 :: 0.1 :: 0.5 :: 0.9 :: 0.99 ::Nil)
    val distributionThresholdRange = (0.05 :: 0.1 :: 0.15 ::Nil)
    val defaultDistributionThreshold = 0.05
    val distributionOnThresholdRange = (0.01 :: 0.1 :: 0.9 :: 0.99 ::Nil)
    val defaultOnDistributionThreshold = 0.5
*/
    val distributionWindowRange = (25 :: Nil)
    val defaultWindowSize = 25

    //val exponentialOffDistributionThresholdRange = (0.1 :: 0.3 :: 0.5 :: 0.7 :: 0.9 :: Nil)
    val exponentialOffDistributionThresholdRange = (0.1 :: 0.9 ::Nil)
    val exponentialOnDistributionThresholdRange = (0.2 :: 0.5 :: 0.8 ::Nil)
    val defaultExponentialOffDistributionThreshold = 0.1
    val defaultExponentialOnDistributionThreshold = 0.5

    //val gammaOffDistributionThresholdRange = (0.1 :: 0.3 :: 0.5 :: 0.7 :: 0.9 ::Nil)
    val gammaOffDistributionThresholdRange = (0.1 :: 0.9 ::Nil)
    val gammaOnDistributionThresholdRange = (0.2 :: 0.5 :: 0.8 ::Nil)
    val defaultGammaOffDistributionThreshold = 0.1
    val defaultGammaOnDistributionThreshold = 0.5

    //val dataCenterLostFactorRange = (0.15 :: 0.2 :: 0.25 :: 0.3 :: Nil)
    //val dataCenterLostFactorRange = (0.15 :: 0.16 :: 0.17 :: 0.18 :: 0.19 :: 0.20 :: Nil)
    val dataCenterLostFactorRange = (0.25 :: 0.3 :: 0.35 :: Nil)
    val dataCenterLostFactorDefault = 0.35


    val javierOrtegaNumSimulationsRange = (1 :: 3 :: 5 :: Nil)
    val javierOrtegaNumSimulationsDefault = 5
    val javierOrtegaThresholdRange = (0.1 :: 0.5 :: 0.9 :: Nil)
    val javierOrtegaThresholdDefault = 0.5
    val javierOrtegaTsRange = (30.0 :: 60.0 :: Nil)
    val javierOrtegaTsDefault = 60.0
    val javierOrtegaTimeRange = (300.0 :: 600.0 :: Nil)
    //val javierOrtegaTimeRange = (600.0 :: Nil)


    val sweepMaxLoadOffRange = true
    val sweepMeanLoadOffRange = false
    val sweepMinFreeCapacityRange = false
    val sweepConstantFreeCapacityRange = true
    val sweepFreeCapacityRangeOn = false
    val sweepMeanFreeCapacityRange = false
    val sweepMinFreeCapacityPonderatedRange = false
    val sweepMinFreeCapacityPonderatedWindowSize = false
    val sweepRandomThreshold = false
    val sweepExponentialOffDistributionThreshold = false
    val sweepExponentialOnDistributionThreshold = false
    val sweepExponentialNormalDistributionThreshold = false
    val sweepExponentialNormalNormalThreshold = false
    val sweepdNormalThreshold = false
    val sweepdOnNormalThreshold = false
    val sweepDistributionThreshold = false // Gamma Off distribution threshold
    val sweepOnDistributionThreshold = false
    val sweepWindowSize = false
    val sweepOnWindowSize = false
    val sweepGammaLostFactor = false
    val sweepExponentialLostFactor = false
    val sweepGammaNormalLostFactor = false
    val sweepExponentialNormalLostFactor = false
    val sweepJavierOrtegaNumSimulatios = false
    val sweepJavierOrtegaThreshold = false
    val sweepJavierOrtegaTs = true
    val sweepJavierTimeWindow = true
    //Power Off
    val runMaxLoadOff = false
    val runMeanLoadOff = false
    val runMinFreeCapacity = true
    val runConstantFreeCapacity = false
    val runMeanFreeCapacity = false
    val runMinFreeCapacityPonderated = false
    val runNeverOff = true
    val runAlwzOff = true
    val runRandom = true
    val runGamma = true
    val runExp = true
    val runExpNormal = false
    val runGammaNormal = false
    val runJavierOrtega = true
    val runJavierOrtegaTime = true


    val availabilityFactorRange = (1.0 :: 1.5 :: 2.0 :: 3.0 :: Nil)
    val availabilityFactorDefault = 2.5
    val sweepAvailabilityFactorOn = false

    //PowerOn
    val runNoPowerOn = false
    val runDefault = false
    val runAvailableCapacityOn = true
    val runGammaNormalOn = false
    val runCombinedDefaultOrGammaNormal = false
    val runCombinedDefaultOrMargin = false
    val runCombinedDefaultOrExponential = false


    //val defaultPowerOnPolicy = List[PowerOnPolicy](new ComposedPowerOnPolicy(new PowerOnMarginPercAvailableAction(0.99), new MarginPowerOnDecision(0.99)))
    //val defaultPowerOnPolicy = List[PowerOnPolicy](new ComposedPowerOnPolicy(new GammaPowerOnAction(0.9, 0.7, 50), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(0.9, 0.7, 50)), "or") ))
    //val defaultPowerOnPolicy = List[PowerOnPolicy](new ComposedPowerOnPolicy(DefaultPowerOnAction, DefaultPowerOnDecision))
    //val defaultPowerOnPolicy = List[PowerOnPolicy](new ComposedPowerOnPolicy(DefaultPowerOnAction, NoPowerOnDecision))

    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, NoPowerOffDecision))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new LoadMaxPowerOffDecision(0.2)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new RandomPowerOffDecision(0.1)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(0.1, 50)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(0.9, 0.3, 50)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(0.6, 25)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaFreePowerOffDecision(0.00000001, 25)))
    //val defaultPowerOffPolicy = List[PowerOffPolicy](new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(0.00000000000000000000000001, 25)))

    var defaultPowerOnPolicy = List[PowerOnPolicy]()
    var defaultPowerOffPolicy = List[PowerOffPolicy]()

    if(runNeverOff){
      defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, NoPowerOffDecision)
    }


    if(runAlwzOff){
      defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, AlwzPowerOffDecision, doGlobalCheck = true)
    }

    if(runRandom){
      if(sweepRandomThreshold){
        for (randomThreshold <- randomRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new RandomPowerOffDecision(randomThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new RandomPowerOffDecision(randomDefaultThreshold), doGlobalCheck = true)
      }
    }

    if(runMaxLoadOff){
      if(sweepMaxLoadOffRange){
        for (loadThreshold <- loadRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new LoadMaxPowerOffDecision(loadThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new LoadMaxPowerOffDecision(defaultLoadRange), doGlobalCheck = true)
      }
    }

    if(runMeanLoadOff){
      if(sweepMeanLoadOffRange){
        for (loadThreshold <- loadRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new LoadMeanPowerOffDecision(loadThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new LoadMeanPowerOffDecision(defaultLoadRange), doGlobalCheck = true)
      }
    }

    if(runMinFreeCapacity){
      if(sweepMinFreeCapacityRange){
        for (freeThreshold <- freeCapacityRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new FreeCapacityMinMarginPowerOffDecision(freeThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new FreeCapacityMinMarginPowerOffDecision(defaultFreeCapacityRange), doGlobalCheck = true)
      }
    }

    if(runConstantFreeCapacity){
      if(sweepConstantFreeCapacityRange){
        for (freeThreshold <- constantCapacityRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ConstantMarginPowerOffDecision(freeThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ConstantMarginPowerOffDecision(defaultConstantCapacityRange), doGlobalCheck = true)
      }
    }

    if(runMinFreeCapacityPonderated){
      if(sweepMinFreeCapacityPonderatedRange && sweepMinFreeCapacityPonderatedWindowSize){
        for(freeThreshold <- freeCapacityRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new WeightedFreeCapacityMarginPowerOffDecision(freeThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepMinFreeCapacityPonderatedRange){
        for (freeThreshold <- freeCapacityRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new WeightedFreeCapacityMarginPowerOffDecision(freeThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new WeightedFreeCapacityMarginPowerOffDecision(defaultFreeCapacityRange, defaultWindowSize), doGlobalCheck = true)
      }
    }

    if(runMeanFreeCapacity){
      if(sweepMeanFreeCapacityRange){
        for (freeThreshold <- freeCapacityRange){
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new FreeCapacityMeanMarginPowerOffDecision(freeThreshold), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new FreeCapacityMeanMarginPowerOffDecision(defaultFreeCapacityRange), doGlobalCheck = true)
      }
    }


    if(runExpNormal){
      if(sweepExponentialNormalDistributionThreshold && sweepExponentialNormalNormalThreshold && sweepWindowSize){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(normalThreshold, distributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialNormalDistributionThreshold && sweepExponentialNormalNormalThreshold){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; normalThreshold <- normalThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(normalThreshold, distributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialNormalDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(defaultNormalThreshold, distributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialNormalNormalThreshold && sweepWindowSize){
        for(normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(normalThreshold, defaultExponentialOffDistributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialNormalNormalThreshold){
        for(normalThreshold <- normalThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(normalThreshold, defaultExponentialOffDistributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else if(sweepWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(defaultNormalThreshold, defaultExponentialOffDistributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialNormalDistributionThreshold){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(defaultNormalThreshold, distributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExpNormPowerOffDecision(defaultNormalThreshold, defaultExponentialOffDistributionThreshold, defaultWindowSize), doGlobalCheck = true)
      }
    }


    if(runExp){
      if(sweepExponentialOffDistributionThreshold && sweepWindowSize && sweepExponentialLostFactor){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; windowSize <-distributionWindowRange; lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(distributionThreshold, windowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialOffDistributionThreshold && sweepExponentialLostFactor){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(distributionThreshold, defaultWindowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepWindowSize && sweepExponentialLostFactor){
        for(windowSize <-distributionWindowRange; lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(defaultExponentialOffDistributionThreshold, windowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialOffDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(distributionThreshold, windowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialLostFactor){
        for(lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(defaultExponentialOffDistributionThreshold, defaultWindowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepExponentialOffDistributionThreshold){
        for(distributionThreshold <- exponentialOffDistributionThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(distributionThreshold, defaultWindowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else if(sweepWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(defaultExponentialOffDistributionThreshold, windowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new ExponentialPowerOffDecision(defaultExponentialOffDistributionThreshold, defaultWindowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
      }
    }

    if(runGamma){

      if(sweepGammaLostFactor && sweepDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; lostFactor <- dataCenterLostFactorRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(distributionThreshold, windowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepGammaLostFactor && sweepDistributionThreshold){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(distributionThreshold, defaultWindowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepGammaLostFactor && sweepWindowSize){
        for(windowSize <- distributionWindowRange; lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(defaultGammaOffDistributionThreshold, windowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(distributionThreshold, windowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else if(sweepGammaLostFactor){
        for(lostFactor <- dataCenterLostFactorRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(defaultGammaOffDistributionThreshold, defaultWindowSize, lostFactor), doGlobalCheck = true)
        }
      }
      else if(sweepDistributionThreshold){
        for(distributionThreshold <- gammaOffDistributionThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(distributionThreshold, defaultWindowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else if(sweepWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(defaultGammaOffDistributionThreshold, windowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaPowerOffDecision(defaultGammaOffDistributionThreshold, defaultWindowSize, dataCenterLostFactorDefault), doGlobalCheck = true)
      }
    }

    if(runGammaNormal){
      if(sweepDistributionThreshold && sweepdNormalThreshold && sweepWindowSize){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(normalThreshold, distributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepDistributionThreshold && sweepdNormalThreshold){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; normalThreshold <- normalThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(normalThreshold, distributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else if(sweepDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- gammaOffDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(defaultNormalThreshold, distributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepdNormalThreshold && sweepWindowSize){
        for(normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(normalThreshold, defaultGammaOffDistributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepdNormalThreshold){
        for(normalThreshold <- normalThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(normalThreshold, defaultGammaOffDistributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else if(sweepWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(defaultNormalThreshold, defaultGammaOffDistributionThreshold, windowSize), doGlobalCheck = true)
        }
      }
      else if(sweepDistributionThreshold){
        for(distributionThreshold <- gammaOffDistributionThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(defaultNormalThreshold, distributionThreshold, defaultWindowSize), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new GammaNormalPowerOffDecision(defaultNormalThreshold, defaultGammaOffDistributionThreshold, defaultWindowSize), doGlobalCheck = true)
      }
    }

    if(runJavierOrtega){

      if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaThreshold && sweepJavierOrtegaTs){
        for(numSim <- javierOrtegaNumSimulationsRange; thresh <- javierOrtegaThresholdRange; ts <-javierOrtegaTsRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = thresh, windowSize = defaultWindowSize, ts = ts, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaThreshold){
        for(numSim <- javierOrtegaNumSimulationsRange; thresh <- javierOrtegaThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = thresh, windowSize = defaultWindowSize, ts = javierOrtegaTsDefault, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaTs){
        for(numSim <- javierOrtegaNumSimulationsRange; ts <-javierOrtegaTsRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = javierOrtegaThresholdDefault, windowSize = defaultWindowSize, ts = ts, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaThreshold && sweepJavierOrtegaTs){
        for(thresh <- javierOrtegaThresholdRange; ts <-javierOrtegaTsRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = thresh, windowSize = defaultWindowSize, ts = ts, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios){
        for(numSim <- javierOrtegaNumSimulationsRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = javierOrtegaThresholdDefault, windowSize = defaultWindowSize, ts = javierOrtegaTsDefault, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaThreshold){
        for(thresh <- javierOrtegaThresholdRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = thresh, windowSize = defaultWindowSize, ts = javierOrtegaTsDefault, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaTs){
        for(ts <-javierOrtegaTsRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = javierOrtegaThresholdDefault, windowSize = defaultWindowSize, ts = ts, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else{
        defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaPowerOffDecision(threshold = javierOrtegaThresholdDefault, windowSize = defaultWindowSize, ts = javierOrtegaTsDefault, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
      }
    }


    if(runJavierOrtegaTime){

      if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaThreshold && sweepJavierOrtegaTs){
        for(numSim <- javierOrtegaNumSimulationsRange; thresh <- javierOrtegaThresholdRange; ts <-javierOrtegaTsRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = thresh, timeWindow = time, ts = ts, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaThreshold){
        for(numSim <- javierOrtegaNumSimulationsRange; thresh <- javierOrtegaThresholdRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = thresh, timeWindow = time, ts = javierOrtegaTsDefault, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios && sweepJavierOrtegaTs){
        for(numSim <- javierOrtegaNumSimulationsRange; ts <-javierOrtegaTsRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = javierOrtegaThresholdDefault, timeWindow = time, ts = ts, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaThreshold && sweepJavierOrtegaTs){
        for(thresh <- javierOrtegaThresholdRange; ts <-javierOrtegaTsRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = thresh, timeWindow = time, ts = ts, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaNumSimulatios){
        for(numSim <- javierOrtegaNumSimulationsRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = javierOrtegaThresholdDefault, timeWindow = time, ts = javierOrtegaTsDefault, numSimulations = numSim), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaThreshold){
        for(thresh <- javierOrtegaThresholdRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = thresh, timeWindow = time, ts = javierOrtegaTsDefault, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else if(sweepJavierOrtegaTs){
        for(ts <-javierOrtegaTsRange; time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = javierOrtegaThresholdDefault, timeWindow = time, ts = ts, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
      else{
        for(time <- javierOrtegaTimeRange) {
          defaultPowerOffPolicy = defaultPowerOffPolicy :+ new ComposedPowerOffPolicy(DefaultPowerOffAction, new JavierOrtegaTimePowerOffDecision(threshold = javierOrtegaThresholdDefault, timeWindow = time, ts = javierOrtegaTsDefault, numSimulations = javierOrtegaNumSimulationsDefault), doGlobalCheck = true)
        }
      }
    }

    //Power on

    if(runNoPowerOn){
      defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, NoPowerOnDecision)
    }

    if(runDefault){
      defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, DefaultPowerOnDecision)
    }

    if(runAvailableCapacityOn){
      if(sweepAvailabilityFactorOn){
        for(availabilityFactor <- availabilityFactorRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new AvailableCapacityPowerOnDecision(availabilityFactor))
        }
      }
      else{
        defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new AvailableCapacityPowerOnDecision(availabilityFactorDefault))
      }
    }

    if(runGammaNormalOn){
      if(sweepOnDistributionThreshold && sweepdOnNormalThreshold && sweepOnWindowSize){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(normalThreshold, distributionThreshold, windowSize))
        }
      }
      else if(sweepOnDistributionThreshold && sweepdOnNormalThreshold){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; normalThreshold <- normalThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(normalThreshold, distributionThreshold, defaultWindowSize))
        }
      }
      else if(sweepOnDistributionThreshold && sweepOnWindowSize){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(defaultNormalThreshold, distributionThreshold, windowSize))
        }
      }
      else if(sweepdOnNormalThreshold && sweepOnWindowSize){
        for(normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(normalThreshold, defaultGammaOnDistributionThreshold, windowSize))
        }
      }
      else if(sweepdOnNormalThreshold){
        for(normalThreshold <- normalThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(normalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize))
        }
      }
      else if(sweepOnWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(defaultNormalThreshold, defaultGammaOnDistributionThreshold, windowSize))
        }
      }
      else if(sweepOnDistributionThreshold){
        for(distributionThreshold <- gammaOnDistributionThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(defaultNormalThreshold, distributionThreshold, defaultWindowSize))
        }
      }
      else{
        defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(DefaultPowerOnAction, new GammaNormalPowerOnDecision(defaultNormalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize))
      }
    }

    if(runCombinedDefaultOrGammaNormal){
      if(sweepOnDistributionThreshold && sweepdOnNormalThreshold && sweepOnWindowSize){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(normalThreshold, distributionThreshold, windowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(normalThreshold, distributionThreshold, windowSize)), "or") )
        }
      }
      else if(sweepOnDistributionThreshold && sweepdOnNormalThreshold){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; normalThreshold <- normalThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(normalThreshold, distributionThreshold, defaultWindowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(normalThreshold, distributionThreshold, defaultWindowSize)), "or") )
        }
      }
      else if(sweepOnDistributionThreshold && sweepOnWindowSize){
        for(distributionThreshold <- gammaOnDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(defaultNormalThreshold, distributionThreshold, windowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(defaultNormalThreshold, distributionThreshold, windowSize)), "or") )
        }
      }
      else if(sweepdOnNormalThreshold && sweepOnWindowSize){
        for(normalThreshold <- normalThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(normalThreshold, defaultGammaOnDistributionThreshold, windowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(normalThreshold, defaultGammaOnDistributionThreshold, windowSize)), "or") )
        }
      }
      else if(sweepdOnNormalThreshold){
        for(normalThreshold <- normalThresholdRange) {
          defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(normalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(normalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize)), "or") )
        }
      }
      else if(sweepOnWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(defaultNormalThreshold, defaultGammaOnDistributionThreshold, windowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(defaultNormalThreshold, defaultGammaOnDistributionThreshold, windowSize)), "or") )
        }
      }
      else if(sweepOnDistributionThreshold){
        for(distributionThreshold <- gammaOnDistributionThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(defaultNormalThreshold, distributionThreshold, defaultWindowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(defaultNormalThreshold, distributionThreshold, defaultWindowSize)), "or") )
        }
      }
      else{
        defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new GammaNormalPowerOnAction(defaultNormalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new GammaNormalPowerOnDecision(defaultNormalThreshold, defaultGammaOnDistributionThreshold, defaultWindowSize)), "or") )
      }
    }

    if(runCombinedDefaultOrMargin){
      if(sweepFreeCapacityRangeOn){
        for (freeThreshold <- freeCapacityRange){
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new PowerOnMarginPercAvailableAction(freeThreshold)), "sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new MarginPowerOnDecision(freeThreshold)), "or") )
        }
      }
      else{
        new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new PowerOnMarginPercAvailableAction(defaultFreeCapacityRange)), "sum")
        defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new PowerOnMarginPercAvailableAction(defaultFreeCapacityOnRange)), "sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new MarginPowerOnDecision(defaultFreeCapacityOnRange)), "or") )
      }
    }

    if(runCombinedDefaultOrExponential){
      if(sweepExponentialOnDistributionThreshold && sweepWindowSize){
        for(distributionThreshold <- exponentialOnDistributionThresholdRange; windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new GammaPowerOnAction(distributionThreshold, windowSize)),"sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new ExponentialPowerOnDecision(distributionThreshold, windowSize)), "or"))
        }
      }
      else if(sweepExponentialOnDistributionThreshold){
        for(distributionThreshold <- exponentialOnDistributionThresholdRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new GammaPowerOnAction(distributionThreshold, defaultWindowSize)),"sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new ExponentialPowerOnDecision(distributionThreshold, defaultWindowSize)), "or"))
        }
      }
      else if(sweepWindowSize){
        for(windowSize <-distributionWindowRange) {
          defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new GammaPowerOnAction(defaultExponentialOnDistributionThreshold, windowSize)),"sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new ExponentialPowerOnDecision(defaultExponentialOnDistributionThreshold, windowSize)), "or"))
        }
      }
      else{
        defaultPowerOnPolicy = defaultPowerOnPolicy :+ new ComposedPowerOnPolicy(new CombinedPowerOnAction(Seq(DefaultPowerOnAction, new GammaPowerOnAction(defaultExponentialOnDistributionThreshold, defaultWindowSize)),"sum"), new CombinedPowerOnDecision(Seq(DefaultPowerOnDecision, new ExponentialPowerOnDecision(defaultExponentialOnDistributionThreshold, defaultWindowSize)), "or"))
      }
    }

    //var security1Range = (1.0 :: 10.0 :: 30.0 :: Nil) //seconds added to tasks of this security level
    var security1Range = (10.0 :: 30.0 :: Nil)
    security1Range = (0.0 :: Nil) //disable
    //var security2Range = (10.0 :: 60.0 :: 120.0 :: Nil) //seconds added to tasks of this security level
    var security2Range = (60.0 :: 180.0 :: Nil) //seconds added to tasks of this security level
    security2Range = (0.0 :: Nil) //disable
    //var security3Range = (60.0 :: 120.0 :: 240.0 :: Nil) //seconds added to tasks of this security level
    var security3Range = (360.0 :: 1080.0 :: Nil) //seconds added to tasks of this security level
    security3Range = (0.0 :: Nil) //disable
    //val constantRange = (0.1 :: 1.0 :: Nil)
    //TODO: Primera prueba resultados edge para no separar entre las dos generacione de experimentos, 1.0 es cloud, 0.7 edge, luego descartamos
    //val constantRange = (0.5 :: 1.0 :: 1.5 :: 2.0 :: 2.5 :: 3.0 :: 4.0 :: 4.5 :: 5.0 :: 5.5 :: 6.0 :: 6.5 :: 7.0 :: 7.5 :: 8.0 :: 8.5 :: 9.0 :: 9.5 :: 10.0 :: Nil)
    //val constantRange = (0.2 :: Nil)
    //  0.25 latencia =  0.25 --> latencia 50 ms, 0.3 --> latencia 100 ms, 0.35 --> lat. 150ms, 0.4 --> lat. 200ms, 0.45 --> lat 250ms, 0.5 --> lat. 300
    //val constantRange = (0.25 :: 0.3 :: 0.35 :: 0.4 :: 0.45 :: 0.5 :: Nil)
    val constantRange = (0.1 :: Nil)
    // 0.16--> latencia 10ms, 0.18 --> latencia 30ms, 0.2 --> latencia 50ms
    val constantRangeEdge = (0.16 :: 0.18 :: 0.2 :: Nil)

    //val constantRange = medConstantRange
    // val constantRange = fullConstantRange
    //val perTaskRange = (0.01 :: 0.1 :: 1.0 :: Nil)
    val perTaskRange = (0.01 :: Nil)
    //val perTaskRange = (0.1 :: Nil)
    //Para los test anova
    //val perTaskRange = (0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: 0.2 :: Nil)
    // val perTaskRange = medPerTaskRange
    // val perTaskRange = fullPerTaskRange
    val pickinessRange = fullPickinessRange
    // val lambdaRange = fullLambdaRange
    val interArrivalScaleRange = 0.009 :: 0.01 :: 0.02 :: 0.1 :: 0.2 :: 1.0 :: Nil
    // val interArrivalScaleRange = lambdaRange.map(1/_)
    //val prefillRange = (0.3 to 0.3 by 0.1).toList
    //val prefillRange = (0.2 to 0.8 by 0.05).toList
    //val prefillRange = (0.2 ::0.6 :: 0.8 :: Nil)
    val prefillRange = (0.2 :: Nil)
    var prefillCpuLim = List[Map[String, Double]]()
    for (prefillPerc <- prefillRange) {prefillCpuLim ::= Map("PrefillBatch" -> prefillPerc, "PrefillService" -> prefillPerc, "PrefillBatchService" -> prefillPerc)}
    //val prefillCpuLim = Map("PrefillBatch" -> 0.3, "PrefillService" -> 0.3, "PrefillBatchService" -> 0.3)
    val doLogging = false
    val timeout = 60.0 * 60.0 *10000.0 // In seconds.

    val sweepC = false
    val sweepL = false
    val sweepCL = true
    val sweepPickiness = false
    val sweepLambda = false
    val sweepSorting = false
    val sweepPicking = false
    val sweepPowerOn = false
    val sweepPowerOff = false
    val sweepPrefill = true

    var sweepDimensions = collection.mutable.ListBuffer[String]()
    if (sweepC)
      sweepDimensions += "C"
    if (sweepL)
      sweepDimensions += "L"
    if (sweepCL)
      sweepDimensions += "CL"
    if (sweepPickiness)
      sweepDimensions += "Pickiness"
    if (sweepLambda)
      sweepDimensions += "Lambda"
    if (sweepSorting)
      sweepDimensions += "Sorting"
    if (sweepPicking)
      sweepDimensions += "Picking"
    if (sweepPowerOn)
      sweepDimensions += "PowerOn"
    if (sweepPowerOff)
      sweepDimensions += "PowerOff"
    if (sweepPrefill)
      sweepDimensions += "Prefill"

    val formatter = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
    val dateTimeStamp = formatter.format(new java.util.Date)
    // Make the experiment_results dir if it doesn't exist
    val experDir = new java.io.File("experiment_results")
    if (!experDir.exists) {
      println("Creating the 'experiment_results' dir.")
      experDir.mkdir()
    }
    //Esta aproximación intenta aglutinar todas las cargas en una misma carpeta en vez de generar 15 carpetas para 15 cargas
      val outputDirName = "%s/%s-%s-%.0f"
      //val outputDirName = "%s/%s-%s-%s-%.0f"
      .format(
        experDir.toString,
        dateTimeStamp,
        "vary_" + sweepDimensions.mkString("_"),
        /*wlDescs.map(i => {
          i.cell + i.assignmentPolicy +
            (if (i.prefillWorkloadGenerators.length > 0) {
              "_prefilled"
            } else {
              ""
            })
        }).mkString("_"),*/
        /*wlDescs(0).cell + wlDescs(0).assignmentPolicy + (if (wlDescs(0).prefillWorkloadGenerators.length > 0) {
          "_prefilled"
        } else {
          ""
        }),*/
        globalRunTime)
    println("outputDirName is %s".format(outputDirName))





    if (runEdge) {
      // Loop over both a single and multi path Monolithic scheduler.
      // Emulate a single path scheduler by making the parameter sweep
      // apply to both the "Service" and "Batch" workload types for it.
      val multiPathSetup = ("multi", Map("Monolithic" -> List("Service")))
      val singlePathSetup =
        ("single", Map("Monolithic" -> List("Service", "Batch")))
      //List(singlePathSetup, multiPathSetup).foreach {
      //Now only the single path is run
      List(singlePathSetup).foreach {
        case (multiOrSingle, schedulerWorkloadsMap) => {
          //Tres workload generator: workloadGEneratorsCloud: Cloud only
          // workloadGeneratorsEdge: tanto para los cloudlet sbc como los high end
          // workloadGeneratorsEdgeCloud: para el cloud cuando colabora con cloudlets
/*
          //Isolated strategies: cloud only
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-cloud-only"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = workloadGeneratorsCloud,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              constantThinkTimeRange = constantRange,
              perTaskThinkTimeRange = perTaskRange,
              blackListPercentRange = (0.0 :: Nil),
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range, stackelbergStrategies = stackelbergStrategies)

          //Isolated strategies: cloudlet only
          allExperiments ::= new Experiment(
            name = "google-monolithic-%s_path-cloudlet-only"
              .format(multiOrSingle),
            workloadToSweepOver = "Service",
            workloadDescs = workloadGeneratorsIsolatedCloudlet,
            schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
            constantThinkTimeRange = constantRangeEdge,
            perTaskThinkTimeRange = perTaskRange,
            blackListPercentRange = (0.0 :: Nil),
            schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
            simulatorDesc = monolithicSimulatorDesc,
            logging = doLogging,
            outputDirectory = outputDirName,
            prefillCpuLimits = prefillCpuLim,
            simulationTimeout = timeout,
            cellStateResourcesSorterList = defaultSortingPolicy,
            cellStateResourcesPickerList = defaultPickingPolicy,
            powerOnPolicies = defaultPowerOnPolicy,
            powerOffPolicies = defaultPowerOffPolicy,
            level1SecurityTimes = security1Range,
            level2SecurityTimes = security2Range,
            level3SecurityTimes = security3Range, stackelbergStrategies = stackelbergStrategies)
*/
          //Collaborative experiments - Cloud side
          allExperiments ::= new Experiment(
            name = "google-monolithic-%s_path-collaborative-cloud"
              .format(multiOrSingle),
            workloadToSweepOver = "Service",
            workloadDescs = workloadGeneratorsEdgeCloud,
            schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
            constantThinkTimeRange = constantRange,
            perTaskThinkTimeRange = perTaskRange,
            blackListPercentRange = (0.0 :: Nil),
            schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
            simulatorDesc = monolithicSimulatorDesc,
            logging = doLogging,
            outputDirectory = outputDirName,
            prefillCpuLimits = prefillCpuLim,
            simulationTimeout = timeout,
            cellStateResourcesSorterList = defaultSortingPolicy,
            cellStateResourcesPickerList = defaultPickingPolicy,
            powerOnPolicies = defaultPowerOnPolicy,
            powerOffPolicies = defaultPowerOffPolicy,
            level1SecurityTimes = security1Range,
            level2SecurityTimes = security2Range,
            level3SecurityTimes = security3Range, stackelbergStrategies = stackelbergStrategies)

          //Collaborative experiments - Cloudlet side

          allExperiments ::= new Experiment(
            name = "google-monolithic-%s_path-collaborative-cloudlet"
              .format(multiOrSingle),
            workloadToSweepOver = "Service",
            workloadDescs = workloadGeneratorsEdge,
            schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
            constantThinkTimeRange = constantRangeEdge,
            perTaskThinkTimeRange = perTaskRange,
            blackListPercentRange = (0.0 :: Nil),
            schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
            simulatorDesc = monolithicSimulatorDesc,
            logging = doLogging,
            outputDirectory = outputDirName,
            prefillCpuLimits = prefillCpuLim,
            simulationTimeout = timeout,
            cellStateResourcesSorterList = defaultSortingPolicy,
            cellStateResourcesPickerList = defaultPickingPolicy,
            powerOnPolicies = defaultPowerOnPolicy,
            powerOffPolicies = defaultPowerOffPolicy,
            level1SecurityTimes = security1Range,
            level2SecurityTimes = security2Range,
            level3SecurityTimes = security3Range, stackelbergStrategies = stackelbergStrategies)


        }
      }
    }

    //----DYNAMIC----


    if (runDynamic) {
      // Set up Omega Experiments
      dynamicSimulatorSetups.foreach {
        case (simDescs, schedWLMap, schedWLToSweep) => {
          for (simDesc <- simDescs) {
            val numServiceScheds =
              simDesc.schedulerDescs.filter(_.name.contains("Service")).size
            val numBatchScheds =
              simDesc.schedulerDescs.filter(_.name.contains("Batch")).size
            if (sweepC) {
              allExperiments ::= new Experiment(
                name = "dynamic-%s-%s-%d_service-%d_batch-single_path-vary_c"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = constantRange,
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range
              )
            }

            if (sweepCL) {
              allExperiments ::= new Experiment(
                name = "dynamic-%s-%s-%d_service-%d_batch-single_path-vary_cl"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = constantRange,
                perTaskThinkTimeRange = perTaskRange,
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepL) {
              allExperiments ::= new Experiment(
                name = "dynamic-%s-%s-%d_service-%d_batch-single_path-vary_l"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = perTaskRange,
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepPickiness) {
              allExperiments ::= new Experiment(
                name = "dynamic-%s-%s-%d_service-%d_batch-single_path-vary_pickiness"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = pickinessRange,
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepLambda) {
              allExperiments ::= new Experiment(
                name = "dynamic-%s-%s-%d_service-%d_batch-single_path-vary_lambda"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                avgJobInterarrivalTimeRange = Some(interArrivalScaleRange),
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }
          }
        }
      }
    }



    //------OMEGA-----


    if (runOmega) {
      // Set up Omega Experiments
      omegaSimulatorSetups.foreach {
        case (simDescs, schedWLMap, schedWLToSweep) => {
          for (simDesc <- simDescs) {
            val numServiceScheds =
              simDesc.schedulerDescs.filter(_.name.contains("Service")).size
            val numBatchScheds =
              simDesc.schedulerDescs.filter(_.name.contains("Batch")).size
            if (sweepC) {
              allExperiments ::= new Experiment(
                name = "google-omega-%s-%s-%d_service-%d_batch-single_path-vary_c"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = constantRange,
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range
              )
            }

            if (sweepCL) {
              allExperiments ::= new Experiment(
                name = "google-omega-%s-%s-%d_service-%d_batch-single_path-vary_cl"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = constantRange,
                perTaskThinkTimeRange = perTaskRange,
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepL) {
              allExperiments ::= new Experiment(
                name = "google-omega-%s-%s-%d_service-%d_batch-single_path-vary_l"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = perTaskRange,
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepPickiness) {
              allExperiments ::= new Experiment(
                name = "google-omega-%s-%s-%d_service-%d_batch-single_path-vary_pickiness"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = pickinessRange,
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }

            if (sweepLambda) {
              allExperiments ::= new Experiment(
                name = "google-omega-%s-%s-%d_service-%d_batch-single_path-vary_lambda"
                  .format(simDesc.conflictMode,
                    simDesc.transactionMode,
                    numServiceScheds,
                    numBatchScheds),
                workloadToSweepOver = "Service",
                workloadDescs = wlDescs,
                schedulerWorkloadsToSweepOver = schedWLToSweep,
                avgJobInterarrivalTimeRange = Some(interArrivalScaleRange),
                constantThinkTimeRange = (0.1 :: Nil),
                perTaskThinkTimeRange = (0.005 :: Nil),
                blackListPercentRange = (0.0 :: Nil),
                schedulerWorkloadMap = schedWLMap,
                simulatorDesc = simDesc,
                logging = doLogging,
                outputDirectory = outputDirName,
                prefillCpuLimits = prefillCpuLim,
                simulationTimeout = timeout,
                cellStateResourcesSorterList = defaultSortingPolicy,
                cellStateResourcesPickerList = defaultPickingPolicy,
                powerOnPolicies = defaultPowerOnPolicy,
                powerOffPolicies = defaultPowerOffPolicy,
                level1SecurityTimes = security1Range,
                level2SecurityTimes = security2Range,
                level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
            }
          }
        }
      }
    }

    // Set up Mesos experiments, one each for a sweep over l, c, lambda.
    if (runMesos) {
      if (sweepC) {
        allExperiments ::= new Experiment(
          name = "google-mesos-single_path-vary_c",
          workloadToSweepOver = mesosWorkloadToSweep,
          workloadDescs = wlDescs,
          schedulerWorkloadsToSweepOver = mesosSchedWorkloadsToSweep,
          // constantThinkTimeRange = (0.1 :: Nil),
          constantThinkTimeRange = constantRange,
          perTaskThinkTimeRange = (0.005 :: Nil),
          blackListPercentRange = (0.0 :: Nil),
          schedulerWorkloadMap = mesosSchedulerWorkloadMap,
          simulatorDesc = mesosSimulatorDesc,
          logging = doLogging,
          outputDirectory = outputDirName,
          prefillCpuLimits = prefillCpuLim,
          simulationTimeout = timeout,
          cellStateResourcesSorterList = defaultSortingPolicy,
          cellStateResourcesPickerList = defaultPickingPolicy,
          powerOnPolicies = defaultPowerOnPolicy,
          powerOffPolicies = defaultPowerOffPolicy,
          level1SecurityTimes = security1Range,
          level2SecurityTimes = security2Range,
          level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
      }

      if (sweepCL) {
        allExperiments ::= new Experiment(
          name = "google-mesos-single_path-vary_cl",
          workloadToSweepOver = mesosWorkloadToSweep,
          workloadDescs = wlDescs,
          schedulerWorkloadsToSweepOver = mesosSchedWorkloadsToSweep,
          constantThinkTimeRange = constantRange,
          perTaskThinkTimeRange = perTaskRange,
          blackListPercentRange = (0.0 :: Nil),
          schedulerWorkloadMap = mesosSchedulerWorkloadMap,
          simulatorDesc = mesosSimulatorDesc,
          logging = doLogging,
          outputDirectory = outputDirName,
          prefillCpuLimits = prefillCpuLim,
          simulationTimeout = timeout,
          cellStateResourcesSorterList = defaultSortingPolicy,
          cellStateResourcesPickerList = defaultPickingPolicy,
          powerOnPolicies = defaultPowerOnPolicy,
          powerOffPolicies = defaultPowerOffPolicy,
          level1SecurityTimes = security1Range,
          level2SecurityTimes = security2Range,
          level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
      }

      if (sweepL) {
        allExperiments ::= new Experiment(
          name = "google-mesos-single_path-vary_l",
          workloadToSweepOver = mesosWorkloadToSweep,
          workloadDescs = wlDescs,
          schedulerWorkloadsToSweepOver = mesosSchedWorkloadsToSweep,
          constantThinkTimeRange = (0.1 :: Nil),
          perTaskThinkTimeRange = perTaskRange,
          blackListPercentRange = (0.0 :: Nil),
          schedulerWorkloadMap = mesosSchedulerWorkloadMap,
          simulatorDesc = mesosSimulatorDesc,
          logging = doLogging,
          outputDirectory = outputDirName,
          prefillCpuLimits = prefillCpuLim,
          simulationTimeout = timeout,
          cellStateResourcesSorterList = defaultSortingPolicy,
          cellStateResourcesPickerList = defaultPickingPolicy,
          powerOnPolicies = defaultPowerOnPolicy,
          powerOffPolicies = defaultPowerOffPolicy,
          level1SecurityTimes = security1Range,
          level2SecurityTimes = security2Range,
          level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
      }

      if (sweepPickiness) {
        allExperiments ::= new Experiment(
          name = "google-mesos-single_path-vary_pickiness",
          workloadToSweepOver = mesosWorkloadToSweep,
          workloadDescs = wlDescs,
          schedulerWorkloadsToSweepOver = mesosSchedWorkloadsToSweep,
          constantThinkTimeRange = (0.1 :: Nil),
          perTaskThinkTimeRange = (0.005 :: Nil),
          blackListPercentRange = pickinessRange,
          schedulerWorkloadMap = mesosSchedulerWorkloadMap,
          simulatorDesc = mesosSimulatorDesc,
          logging = doLogging,
          outputDirectory = outputDirName,
          prefillCpuLimits = prefillCpuLim,
          simulationTimeout = timeout,
          cellStateResourcesSorterList = defaultSortingPolicy,
          cellStateResourcesPickerList = defaultPickingPolicy,
          powerOnPolicies = defaultPowerOnPolicy,
          powerOffPolicies = defaultPowerOffPolicy,
          level1SecurityTimes = security1Range,
          level2SecurityTimes = security2Range,
          level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
      }

      if (sweepLambda) {
        allExperiments ::= new Experiment(
          name = "google-mesos-single_path-vary_lambda",
          workloadToSweepOver = "Service",
          workloadDescs = wlDescs,
          schedulerWorkloadsToSweepOver = Map("MesosService" -> List("Service")),
          avgJobInterarrivalTimeRange = Some(interArrivalScaleRange),
          constantThinkTimeRange = (0.1 :: Nil),
          perTaskThinkTimeRange = (0.005 :: Nil),
          blackListPercentRange = (0.0 :: Nil),
          schedulerWorkloadMap = mesosSchedulerWorkloadMap,
          simulatorDesc = mesosSimulatorDesc,
          logging = doLogging,
          outputDirectory = outputDirName,
          prefillCpuLimits = prefillCpuLim,
          simulationTimeout = timeout,
          cellStateResourcesSorterList = defaultSortingPolicy,
          cellStateResourcesPickerList = defaultPickingPolicy,
          powerOnPolicies = defaultPowerOnPolicy,
          powerOffPolicies = defaultPowerOffPolicy,
          level1SecurityTimes = security1Range,
          level2SecurityTimes = security2Range,
          level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
      }
    }

    if (runMonolithic) {
      // Loop over both a single and multi path Monolithic scheduler.
      // Emulate a single path scheduler by making the parameter sweep
      // apply to both the "Service" and "Batch" workload types for it.
      val multiPathSetup = ("multi", Map("Monolithic" -> List("Service")))
      val singlePathSetup =
        ("single", Map("Monolithic" -> List("Service", "Batch")))
      //List(singlePathSetup, multiPathSetup).foreach {
      //Now only the single path is run
      List(singlePathSetup).foreach {
        case (multiOrSingle, schedulerWorkloadsMap) => {
          if (sweepC) {
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-vary_c"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = wlDescs,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              constantThinkTimeRange = constantRange,
              perTaskThinkTimeRange = (0.005 :: Nil),
              blackListPercentRange = (0.0 :: Nil),
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
          }

          if (sweepCL) {
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-vary_cl"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = wlDescs,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              constantThinkTimeRange = constantRange,
              perTaskThinkTimeRange = perTaskRange,
              blackListPercentRange = (0.0 :: Nil),
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
          }

          if (sweepL) {
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-vary_l"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = wlDescs,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              constantThinkTimeRange = (0.1 :: Nil),
              perTaskThinkTimeRange = perTaskRange,
              blackListPercentRange = (0.0 :: Nil),
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
          }

          if (sweepPickiness) {
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-vary_pickiness"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = wlDescs,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              constantThinkTimeRange = (0.1 :: Nil),
              perTaskThinkTimeRange = (0.005 :: Nil),
              blackListPercentRange = pickinessRange,
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
          }

          if (sweepLambda) {
            allExperiments ::= new Experiment(
              name = "google-monolithic-%s_path-vary_lambda"
                .format(multiOrSingle),
              workloadToSweepOver = "Service",
              workloadDescs = wlDescs,
              schedulerWorkloadsToSweepOver = schedulerWorkloadsMap,
              avgJobInterarrivalTimeRange = Some(interArrivalScaleRange),
              constantThinkTimeRange = (0.1 :: Nil),
              perTaskThinkTimeRange = (0.005 :: Nil),
              blackListPercentRange = (0.0 :: Nil),
              schedulerWorkloadMap = monolithicSchedulerWorkloadMap,
              simulatorDesc = monolithicSimulatorDesc,
              logging = doLogging,
              outputDirectory = outputDirName,
              prefillCpuLimits = prefillCpuLim,
              simulationTimeout = timeout,
              cellStateResourcesSorterList = defaultSortingPolicy,
              cellStateResourcesPickerList = defaultPickingPolicy,
              powerOnPolicies = defaultPowerOnPolicy,
              powerOffPolicies = defaultPowerOffPolicy,
              level1SecurityTimes = security1Range,
              level2SecurityTimes = security2Range,
              level3SecurityTimes = security3Range,stackelbergStrategies = stackelbergStrategies)
          }
        }
      }
    }


    /* Make a snapshot of the source file that has our settings in it */
    println("Making a copy of Simulation.scala in %s"
      .format(outputDirName))
    val settingsFileNames = ("Simulation.scala" :: "Workloads.scala" :: Nil)
    for (settingsFileName <- settingsFileNames){
      val sourceFile = new File("src/main/scala/" + settingsFileName)
      val destFile = new File(outputDirName + "/" + settingsFileName +
        "-snapshot")
      // Create the output directory if it doesn't exist.
      (new File(outputDirName)).mkdirs()
      if (!destFile.exists()) {
        destFile.createNewFile();
      }
      var source: FileChannel = null
      var destination: FileChannel = null

      try {
        source = new FileInputStream(sourceFile).getChannel();
        destination = new FileOutputStream(destFile).getChannel();
        destination.transferFrom(source, 0, source.size());
      }
      finally {
        if (source != null) {
          source.close();
        }
        if (destination != null) {
          destination.close();
        }
      }
    }


    /**
     * Run the experiments we've set up.
     */
    if (!inputArgs("--random-seed").equals("")) {
      println("Using random seed %d.".format(inputArgs("--random-seed").toLong))
      Seed.set(inputArgs("--random-seed").toLong)
    } else {
      val randomSeed = util.Random.nextLong
      println("--random-seed not set. So using the default seed: %d."
        .format(0))
      Seed.set(0)
    }
    println("Using %d threads.".format(inputArgs("--thread-pool-size").toInt))
    val pool = java.util
      .concurrent
      .Executors
      .newFixedThreadPool(inputArgs("--thread-pool-size").toInt)
    val numTotalExps = allExperiments.length
    var numFinishedExps = 0
    var futures = allExperiments.map(pool.submit)
    // Let go of pointers to Experiments because each Experiment will use
    // quite a lot of memory.
    allExperiments = Nil
    pool.shutdown()
    while (!futures.isEmpty) {
      Thread.sleep(5 * 1000)
      val (completed, running) = futures.partition(_.isDone)
      if (completed.length > 0) {
        numFinishedExps += completed.length
        println("%d more experiments just finished running. In total, %d of %d have finished."
          .format(completed.length, numFinishedExps, numTotalExps))
      }
      completed.foreach(x => try x.get() catch {
        case e => e.printStackTrace()
      })
      futures = running
    }
    println("Done running all experiments. See output in %s."
      .format(outputDirName))
  }
}
