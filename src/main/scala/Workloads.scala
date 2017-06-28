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
  val exampleCellStateDesc = new CellStateDesc(numMachines = 10000,
    cpusPerMachine = 4,
    memPerMachine = 8)
/*
    //20%
    val exampleWorkloadGeneratorBatch =
    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 50,
      avgTasksPerJob = 50.0,
      avgJobDuration = (60.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5)
  val exampleWorkloadGeneratorService =
    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
      initAvgJobInterarrivalTime = 400,
      avgTasksPerJob = 12.0,
      avgJobDuration = (1500.0),
      avgCpusPerTask = 0.5,
      avgMemPerTask = 1.2)
  val exampleWorkloadDesc = WorkloadDesc(cell = "example",
    assignmentPolicy = "CMB_PBB",
    workloadGenerators =
      exampleWorkloadGeneratorBatch ::
        exampleWorkloadGeneratorService :: Nil,
    cellStateDesc = exampleCellStateDesc)
  /**
    * Set up WorkloadDescs, containing generators of workloads and
    * pre-fill workloads based on measurements of cells/workloads.
    */
*/
/*
  // 35%
  val exampleWorkloadGeneratorBatch =
    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 30,
      avgTasksPerJob = 55.0,
      avgJobDuration = (65.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5)
  val exampleWorkloadGeneratorService =
    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
      initAvgJobInterarrivalTime = 250,
      avgTasksPerJob = 14.0,
      avgJobDuration = (1600.0),
      avgCpusPerTask = 0.5,
      avgMemPerTask = 1.2)
  val exampleWorkloadDesc = WorkloadDesc(cell = "example",
    assignmentPolicy = "CMB_PBB",
    workloadGenerators =
      exampleWorkloadGeneratorBatch ::
        exampleWorkloadGeneratorService :: Nil,
    cellStateDesc = exampleCellStateDesc)

*/

  /*
    // 50%
  val exampleWorkloadGeneratorBatch =
    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 20,
      avgTasksPerJob = 60.0,
      avgJobDuration = (70.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5)
  val exampleWorkloadGeneratorService =
    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
      initAvgJobInterarrivalTime = 170,
      avgTasksPerJob = 15.0,
      avgJobDuration = (1600.0),
      avgCpusPerTask = 0.5,
      avgMemPerTask = 1.2)
  val exampleWorkloadDesc = WorkloadDesc(cell = "example",
    assignmentPolicy = "CMB_PBB",
    workloadGenerators =
      exampleWorkloadGeneratorBatch ::
        exampleWorkloadGeneratorService :: Nil,
    cellStateDesc = exampleCellStateDesc)

*/
/*
    // 60%
  val exampleWorkloadGeneratorBatch =
    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 18,
      avgTasksPerJob = 65.0,
      avgJobDuration = (80.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5)
  val exampleWorkloadGeneratorService =
    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
      initAvgJobInterarrivalTime = 155,
      avgTasksPerJob = 16.0,
      avgJobDuration = (1600.0),
      avgCpusPerTask = 0.5,
      avgMemPerTask = 1.2)
  val exampleWorkloadDesc = WorkloadDesc(cell = "example",
    assignmentPolicy = "CMB_PBB",
    workloadGenerators =
      exampleWorkloadGeneratorBatch ::
        exampleWorkloadGeneratorService :: Nil,
    cellStateDesc = exampleCellStateDesc)
*/

    // 85%
    val exampleWorkloadGeneratorBatch =
      new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
        initAvgJobInterarrivalTime = 22,
        avgTasksPerJob = 180.0,
        avgJobDuration = (90.0),
        avgCpusPerTask = 0.3,
        avgMemPerTask = 0.5)
    val exampleWorkloadGeneratorService =
      new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
        initAvgJobInterarrivalTime = 220,
        avgTasksPerJob = 30.0,
        avgJobDuration = (2000.0),
        avgCpusPerTask = 0.5,
        avgMemPerTask = 1.2)
    val exampleWorkloadDesc = WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService :: Nil,
      cellStateDesc = exampleCellStateDesc)


/*
   // 50% mucho interarrival mucho consumo
 val exampleWorkloadGeneratorBatch =
   new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
     initAvgJobInterarrivalTime = 80,
     avgTasksPerJob = 100.0,
     avgJobDuration = (150.0),
     avgCpusPerTask = 0.3,
     avgMemPerTask = 0.5)
 val exampleWorkloadGeneratorService =
   new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
     initAvgJobInterarrivalTime = 300,
     avgTasksPerJob = 30.0,
     avgJobDuration = (1600.0),
     avgCpusPerTask = 0.5,
     avgMemPerTask = 1.2)
 val exampleWorkloadDesc = WorkloadDesc(cell = "example",
   assignmentPolicy = "CMB_PBB",
   workloadGenerators =
     exampleWorkloadGeneratorBatch ::
       exampleWorkloadGeneratorService :: Nil,
   cellStateDesc = exampleCellStateDesc)
*/
/*
    // 50% poco interarrival poco consumo
  val exampleWorkloadGeneratorBatch =
    new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
      initAvgJobInterarrivalTime = 10,
      avgTasksPerJob = 30.0,
      avgJobDuration = (60.0),
      avgCpusPerTask = 0.3,
      avgMemPerTask = 0.5)
  val exampleWorkloadGeneratorService =
    new ExpExpExpWorkloadGenerator(workloadName = "Service".intern(),
      initAvgJobInterarrivalTime = 120,
      avgTasksPerJob = 10.0,
      avgJobDuration = (1500.0),
      avgCpusPerTask = 0.5,
      avgMemPerTask = 1.2)
  val exampleWorkloadDesc = WorkloadDesc(cell = "example",
    assignmentPolicy = "CMB_PBB",
    workloadGenerators =
      exampleWorkloadGeneratorBatch ::
        exampleWorkloadGeneratorService :: Nil,
    cellStateDesc = exampleCellStateDesc)

*/


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

  val exampleWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService ::
          Nil,
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))


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
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))

  val exampleGeneratedWorkloadPrefillDesc =
    WorkloadDesc(cell = "example",
      assignmentPolicy = "CMB_PBB",
      workloadGenerators =
        exampleWorkloadGeneratorBatch ::
          exampleWorkloadGeneratorService ::
          Nil,
      cellStateDesc = exampleCellStateDesc,
      prefillWorkloadGenerators =
        List(exampleBatchServicePrefillTraceWLGenerator))
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

