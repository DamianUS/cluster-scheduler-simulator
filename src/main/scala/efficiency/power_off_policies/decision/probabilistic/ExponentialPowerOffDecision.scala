package efficiency.power_off_policies.decision.probabilistic

import ClusterSchedulingSimulation.{CellState, Job}
import efficiency.DistributionUtils
import efficiency.power_off_policies.decision.PowerOffDecision
import org.apache.commons.math.distribution.{ExponentialDistribution, ExponentialDistributionImpl}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Created by dfernandez on 22/1/16.
 */
class ExponentialPowerOffDecision(threshold : Double, windowSize: Int, lostFactor : Double, ts : Double = 30.0) extends PowerOffDecision with DistributionUtils{
  override def shouldPowerOff(cellState: CellState, machineID: Int): Boolean = {
    var should = false
    val allPastTuples = getPastTuples(cellState, windowSize)
    //val meanCpuPerTask = mean(allPastTuples.map(_._2.cpusPerTask))
    //val meanMemPerTask = mean(allPastTuples.map(_._2.memPerTask))
    //val meanFreeCpuPerOnMachine = (cellState.availableCpus - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor))/cellState.numberOfMachinesOn
    //val meanFreeMemPerOnMachine = (cellState.availableMem - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor))/ cellState.numberOfMachinesOn
    //val lostCpuFactor = getExponentialDistributionCummulativeProbability(meanCpuPerTask, meanFreeCpuPerOnMachine)
    //val lostMemFactor = getExponentialDistributionCummulativeProbability(meanMemPerTask, meanFreeMemPerOnMachine)
    val dangerousPastTuples = allPastTuples.filter(pastTuple => (pastTuple._2.numTasks * pastTuple._2.cpusPerTask > cellState.availableCpus - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor)) || (pastTuple._2.numTasks * pastTuple._2.memPerTask >  cellState.availableMem - (cellState.numberOfMachinesOn * cellState.memPerMachine * lostFactor)))
    //val dangerousPastTuples = allPastTuples.filter(pastTuple => (pastTuple._2.numTasks * pastTuple._2.cpusPerTask > (cellState.availableCpus - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor)) * lostCpuFactor) || (pastTuple._2.numTasks * pastTuple._2.memPerTask >  (cellState.availableMem - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor)) * lostMemFactor))
    //val jobAttributes = getJobAttributes(allPastTuples)
    if(dangerousPastTuples.size > 0){
      var interArrival= 0.0
      if(dangerousPastTuples.size == 1){
        interArrival = dangerousPastTuples(0)._1
      }
      else{
        interArrival = generateJobAtributes(dangerousPastTuples)._1
      }
      if(interArrival > 0.0){
        val lastDangerousTuple = dangerousPastTuples.maxBy(tuple => tuple._1)
        val prob = getExponentialDistributionCummulativeProbability(interArrival, Math.max(ts, cellState.simulator.currentTime - lastDangerousTuple._1))
        should = prob <= threshold
      }
    }
    else{
      should = true
    }

    should
  }

  override val name: String = ("exponential-off-threshold:%f-window:%d-lost:%f").format(threshold,windowSize, lostFactor)
}
