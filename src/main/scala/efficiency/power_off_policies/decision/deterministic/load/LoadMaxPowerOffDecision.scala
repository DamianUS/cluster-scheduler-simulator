package efficiency.power_off_policies.decision.deterministic.load

import ClusterSchedulingSimulation.CellState
import efficiency.power_off_policies.decision.PowerOffDecision

/**
   * Created by dfernandez on 15/1/16.
   */
class LoadMaxPowerOffDecision(threshold : Double) extends PowerOffDecision{
  //Intución: Cambio: Margen sobre los ocupados
    override def shouldPowerOff(cellState: CellState, machineID: Int): Boolean = {
      assert(threshold > 0 && threshold < 1, "Security margin percentage value must be between 0.001 and 0.999")
      cellState.availableCpus > (1+threshold)*cellState.totalOccupiedCpus && cellState.availableMem > (1+threshold)*cellState.totalOccupiedMem
    }

    override val name: String = ("load-power-off-decision-with-margin:%f").format(threshold)
  }
