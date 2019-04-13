package efficiency.power_off_policies.decision.probabilistic

import ClusterSchedulingSimulation.{CellState, ExpExpExpWorkloadGenerator}
import ClusterSchedulingSimulation.Workloads.tasksHeterogeneity
import efficiency.DistributionUtils
import efficiency.power_off_policies.decision.PowerOffDecision

/**
 * Created by dfernandez on 22/1/16.
 */
class JavierOrtegaPowerOffDecision(threshold : Double, windowSize: Int, ts : Double = 30.0, numSimulations : Int) extends PowerOffDecision with DistributionUtils{

  var llamado1 = false
  var llamado2 = false

  override def shouldPowerOff(cellState: CellState, machineID: Int): Boolean = {
    //println(("On : %f y ocupadas: %f").format(cellState.numberOfMachinesOn.toDouble/cellState.numMachines, cellState.numMachinesOccupied.toDouble/cellState.numMachines))
    //FIXME: Esto no calcula bien
    //TODO: Calculate Ts
    val allPastTuples = getPastTuples(cellState, windowSize)
    var should = false
    val jobAttributes = getJobAttributes(allPastTuples)
    var results = Array.fill(numSimulations){false}

/*
    if(!llamado1 && cellState.simulator.currentTime >= 21600.0 && cellState.simulator.jobCache.count(p => p._2.submitted >= 21600.0) >= 10){
      cellState.simulator.jobCache.filter(p => p._2.submitted >= 21600.0).foreach(p => println(p._1 + " " + p._2))
      llamado1=true
    }
    else if(!llamado2 && cellState.simulator.currentTime >= 136800.0 && cellState.simulator.jobCache.count(p => p._2.submitted >= 136800.0) >= 10){
      cellState.simulator.jobCache.filter(p => p._2.submitted >= 136800.0).foreach(p => println(p._1 + " " +p._2))
      llamado2=true
    }*/
    //for(i <- 0 to numSimulations-1){
      if(jobAttributes._1 > 0.0 && jobAttributes._3 > 0.0 && jobAttributes._5 > 0.0 && jobAttributes._7 > 0.0 && jobAttributes._9 > 0.0){
        val workloadGenerator = new ExpExpExpWorkloadGenerator(workloadName = "Batch".intern(),
          initAvgJobInterarrivalTime = jobAttributes._1,
          avgTasksPerJob = jobAttributes._7,
          avgJobDuration = jobAttributes._9,
          avgCpusPerTask = jobAttributes._5,
          avgMemPerTask = jobAttributes._3,
          heterogeneousTasks = false)

        val newWorkload = workloadGenerator.newWorkload(timeWindow = ts)
        val jobs = newWorkload.getJobs
        should = jobs.map(job => job.cpusPerTask * job.numTasks).sum < cellState.availableCpus
        //val shouldIteration = jobs.map(job => job.cpusPerTask * job.numTasks).sum < cellState.availableCpus
        //results(i) = shouldIteration;
      }
    /*}
    should = results.count(_ == true) / numSimulations > threshold
    */
    should

  }




  override val name: String = ("javier-ortega-off-threshold:%f-window:%d-ts:%f-numSim:%d").format(threshold,windowSize,ts,numSimulations)
}
