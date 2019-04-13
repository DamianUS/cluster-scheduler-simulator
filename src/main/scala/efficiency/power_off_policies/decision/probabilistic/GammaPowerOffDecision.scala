package efficiency.power_off_policies.decision.probabilistic

import ClusterSchedulingSimulation.{Job, CellState}
import efficiency.{DistributionUtils, DistributionCache}
import efficiency.power_off_policies.decision.PowerOffDecision
import org.apache.commons.math.distribution.{GammaDistributionImpl, ExponentialDistributionImpl}

/**
 * Created by dfernandez on 22/1/16.
 */
class GammaPowerOffDecision(threshold : Double, windowSize: Int, lostFactor : Double, ts : Double = 30.0) extends PowerOffDecision with DistributionUtils{
  override def shouldPowerOff(cellState: CellState, machineID: Int): Boolean = {
    //println(("On : %f y ocupadas: %f").format(cellState.numberOfMachinesOn.toDouble/cellState.numMachines, cellState.numMachinesOccupied.toDouble/cellState.numMachines))
    //FIXME: Esto no calcula bien
    //TODO: Calculate Ts
    val allPastTuples = getPastTuples(cellState, windowSize)
    var should = false
    val jobAttributes = getJobAttributes(allPastTuples)

    if(jobAttributes._1 > 0.0 && jobAttributes._3 > 0.0 && jobAttributes._5 > 0.0){
      val alphaCpu = (cellState.availableCpus - (cellState.numberOfMachinesOn * cellState.cpusPerMachine * lostFactor)) / jobAttributes._5
      val alphaMem = (cellState.availableMem - (cellState.numberOfMachinesOn * cellState.memPerMachine * lostFactor)) / jobAttributes._3
      //FIXME: en la implementación anterior teníamos un floor de (alphacpu+alphamem) /2 y le sumábamos 1
      val prob = getGammaDistributionCummulativeProbability( Math.min(alphaCpu,alphaMem), jobAttributes._1, ts)
      should = prob <= threshold
    }
    should

    /*
    val allPastTuples = getPastTuples(cellState, windowSize)
    var should = false
    val jobAttributes = getJobAttributes(allPastTuples)
    val securityLevels = 6
    if(jobAttributes._1 > 0.0 && jobAttributes._3 > 0.0 && jobAttributes._5 > 0.0){
      val alphaCpu = ((cellState.availableCpus * ((securityLevels-cellState.machinesSecurity(machineID))/securityLevels)) - (cellState.numberOfMachinesOn * ((securityLevels-cellState.machinesSecurity(machineID))/securityLevels) * cellState.cpusPerMachine * lostFactor)) / jobAttributes._5
      val alphaMem = ((cellState.availableMem * ((securityLevels-cellState.machinesSecurity(machineID))/securityLevels)) - (cellState.numberOfMachinesOn * ((securityLevels-cellState.machinesSecurity(machineID))/securityLevels) * cellState.memPerMachine * lostFactor)) / jobAttributes._3
      //FIXME: en la implementación anterior teníamos un floor de (alphacpu+alphamem) /2 y le sumábamos 1
      val prob = getGammaDistributionCummulativeProbability( Math.min(alphaCpu,alphaMem), jobAttributes._1, ts)
      should = prob <= threshold
    }
    should
     */
  }


  override val name: String = ("gamma-off-threshold:%f-window:%d-lost:%f").format(threshold,windowSize,lostFactor)
}
