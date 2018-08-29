# coding=utf-8
#!/usr/bin/python

# Copyright (c) 2013, Regents of the University of California
# All rights reserved.

# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:

# Redistributions of source code must retain the above copyright notice, this
# list of conditions and the following disclaimer.  Redistributions in binary
# form must reproduce the above copyright notice, this list of conditions and the
# following disclaimer in the documentation and/or other materials provided with
# the distribution.  Neither the name of the University of California, Berkeley
# nor the names of its contributors may be used to endorse or promote products
# derived from this software without specific prior written permission.  THIS
# SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
# EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
# WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

# For each unique (cell_name, scheduler, metric) tuple, where metric
# is either busy_time_median or conflict_fraction median, print a
# different text file with rows that contain the following fields:
#   cell_name
#   sched_id
#   c
#   l
#   avg_job_interarrival_time
#   median_busy_time (or conflict_fraction)
#   err_bar_metric_for_busy_time (or conflict_fraction)

import sys, os, re
import logging
import numpy as np
from collections import defaultdict
import cluster_simulation_protos_pb2

logging.basicConfig(level=logging.DEBUG)

def usage():
    print "usage: generate-txt-from-protobuff.py <input_protobuff_name> <optional: base name for output files. (defaults to inputfilename)>"
    sys.exit(1)

logging.debug("len(sys.argv): " + str(len(sys.argv)))

if len(sys.argv) < 2:
    logging.error("Not enough arguments provided.")
    usage()

try:
    input_protobuff_name = sys.argv[1]
    # Start optional args.
    if len(sys.argv) == 3:
        outfile_name_base = str(sys.argv[2])
    else:
        #make the output files the same as the input but add .txt to end
        outfile_name_base = input_protobuff_name

except:
    usage()

logging.info("Input file: %s" % input_protobuff_name)

def get_mad(median, data):
    logging.info("in get_mad, with median %f, data: %s"
                 % (median, " ".join([str(i) for i in data])))
    devs = [abs(x - median) for x in data]
    mad = np.median(devs)
    print "returning mad = %f" % mad
    return mad

# Read in the ExperimentResultSet.
experiment_result_set = cluster_simulation_protos_pb2.ExperimentResultSet()
infile = open(input_protobuff_name, "rb")
experiment_result_set.ParseFromString(infile.read())
infile.close()

# This dictionary, indexed by 3tuples[String] of
# (cell_name, scheduler_name, metric_name), holds as values strings
# each holding all of the rows that will be written to to a text file
# uniquely identified by the dictionary key.
# This dictionary will be iterated over after being being filled
# to create text files holding its contents.
output_strings = defaultdict(str)
# Loop through each experiment environment.
logging.debug("Processing %d experiment envs."
              % len(experiment_result_set.experiment_env))
for env in experiment_result_set.experiment_env:
    logging.debug("Handling experiment env (%s %s)."
                  % (env.cell_name, env.workload_split_type))
    logging.debug("Processing %d experiment results."
                  % len(env.experiment_result))
    prev_l_val = -1.0
    for exp_result in env.experiment_result:
        logging.debug("Handling experiment result with C = %f and L = %f."
                      % (exp_result.constant_think_time,
                         exp_result.per_task_think_time))

        for sched_stat in exp_result.scheduler_stats:
            logging.debug("Handling scheduler stat for %s."
                          % sched_stat.scheduler_name)
            # Calculate per day busy time and conflict fractions.
            daily_busy_fractions = []
            daily_conflict_fractions = []
            for day_stats in sched_stat.per_day_stats:
                # Calculate the total busy time for each of the days and then
                # take median of all fo them.
                run_time_for_day = env.run_time - 86400 * day_stats.day_num
                logging.info("setting run_time_for_day = env.run_time - 86400 * "
                             "day_stats.day_num = %f - 86400 * %d = %f"
                             % (env.run_time, day_stats.day_num, run_time_for_day))
                if run_time_for_day > 0.0:
                    daily_busy_fractions.append(((day_stats.useful_busy_time +
                                                  day_stats.wasted_busy_time) /
                                                 min(86400.0, run_time_for_day)))
                    logging.info("%s appending daily_conflict_fraction %f."
                                 % (sched_stat.scheduler_name, daily_busy_fractions[-1]))

                    if day_stats.num_successful_transactions > 0:
                        conflict_fraction = (float(day_stats.num_failed_transactions) /
                                             float(day_stats.num_failed_transactions +
                                                   day_stats.num_successful_transactions))
                        daily_conflict_fractions.append(conflict_fraction)
                        logging.info("%s appending daily_conflict_fraction %f."
                                     % (sched_stat.scheduler_name, conflict_fraction))
                    else:
                        daily_conflict_fractions.append(0)
                        logging.info("appending 0 to daily_conflict_fraction")

            logging.info("Done building daily_busy_fractions: %s"
                         % " ".join([str(i) for i in daily_busy_fractions]))
            logging.info("Also done building daily_conflict_fractions: %s"
                         % " ".join([str(i) for i in daily_conflict_fractions]))

            if prev_l_val != exp_result.per_task_think_time and prev_l_val != -1.0:
                opt_extra_newline = "\n"
            else:
                opt_extra_newline = ""
            prev_l_val = exp_result.per_task_think_time

            # Compute the busy_time row and append it to the string
            # accumulating output rows for this schedulerName.
            daily_busy_fraction_median = np.median(daily_busy_fractions)
            busy_frac_key = (env.cell_name, sched_stat.scheduler_name, "busy_frac")
            output_strings[busy_frac_key] += \
                "%s%s %s %s %s %s %s %s\n" % (opt_extra_newline,
                                              env.cell_name,
                                              sched_stat.scheduler_name,
                                              exp_result.constant_think_time,
                                              exp_result.per_task_think_time,
                                              exp_result.avg_job_interarrival_time,
                                              daily_busy_fraction_median,
                                              get_mad(daily_busy_fraction_median,
                                                      daily_busy_fractions))

            conflict_fraction_median = np.median(daily_conflict_fractions)
            conf_frac_key = (env.cell_name, sched_stat.scheduler_name, "conf_frac")
            output_strings[conf_frac_key] += \
                "%s%s %s %s %s %s %s %s\n" % (opt_extra_newline,
                                              env.cell_name,
                                              sched_stat.scheduler_name,
                                              exp_result.constant_think_time,
                                              exp_result.per_task_think_time,
                                              exp_result.avg_job_interarrival_time,
                                              conflict_fraction_median,
                                              get_mad(conflict_fraction_median,
                                                      daily_conflict_fractions))

            scheduler_stats_key = (env.cell_name, sched_stat.scheduler_name, "scheduler_stats")
            #TODO: Cambiar esta guarrería de for anidados, pero como son pocos workloads la complejidad da igual
            #
            # #Measurements
            # for measurement in exp_result.measurements:
            #     measurement_key = (env.cell_name, sched_stat.scheduler_name, exp_result.efficiency_stats.power_off_policy.name, "measurement")
            #
            #     #measurements_outfile_name = ("meas-" + "-off:" + exp_result.efficiency_stats.power_off_policy.name + ".txt")
            #     #logging.info("Creating meas_output file: %s" % measurements_outfile_name)
            #     #measurements_outfile = open(measurements_outfile_name, "w")
            #
            #
            #     output_strings[measurement_key] += \
            #         "%s%.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %s\n" % (opt_extra_newline,
            #                                                                              measurement.cpuUtilization,
            #                                                                              measurement.cpuTotallyIdle,
            #                                                                              measurement.cpuPartiallyIdle,
            #                                                                              measurement.mpuLocked,
            #                                                                              measurement.memUtilization,
            #                                                                              measurement.memTotallyIdle,
            #                                                                              measurement.memPartiallyIdle,
            #                                                                              measurement.memLocked,
            #                                                                              measurement.machinesOff,
            #                                                                              measurement.machinesOn,
            #                                                                              measurement.machinesTurningOff,
            #                                                                              measurement.machinesTurningOn,
            #                                                                              measurement.strategy if measurement.strategy else "")

            for workload_stat in exp_result.workload_stats:
                #if workload_stat.workload_name == exp_result.sweep_workload:
                for per_workload_busy_time in sched_stat.per_workload_busy_time:
                    if per_workload_busy_time.workload_name == workload_stat.workload_name:
                        logging.info("%s workload_stat.avg_makespan_epoch_0: %f",workload_stat.workload_name ,workload_stat.avg_makespan_epoch_100)
                        output_strings[scheduler_stats_key] += \
                            "%s%s %s %d %s %s %s %s %s %s %s %.4f %.4f %.4f %.4f %.2f %.2f %.2f %.2f %.2f %s %i %i %i %i %.4f %.4f %i %i %i %i %i %i %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %s %i %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %.4f %i %i %i %i %i %i\n" % (opt_extra_newline,
                                                                                                                                                                                                                                                                                                                                                                                             env.cell_name,
                                                                                                                                                                                                                                                                                                                                                                                             env.is_prefilled,
                                                                                                                                                                                                                                                                                                                                                                                             env.run_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.power_on_policy.name,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.power_off_policy.name,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.picking_policy,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.stackelberg,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.scheduler_name,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.sweep_workload,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.workload_name,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.cell_state_avg_cpu_utilization,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.cell_state_avg_mem_utilization,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.cell_state_avg_cpu_locked,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.cell_state_avg_mem_locked,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.constant_think_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.per_task_think_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.security_1_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.security_2_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.security_3_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.avg_job_interarrival_time,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_scheduled,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_jobs_left_in_queue,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_jobs_timed_out_scheduling,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.useful_busy_time,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.wasted_busy_time,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_successful_transactions,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_failed_transactions,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_no_resources_found_scheduling_attempts,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_retried_transactions,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_successful_task_transactions,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.num_failed_task_transactions,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.job_think_times_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_job_queue_times_till_first_scheduled,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_job_queue_times_till_fully_scheduled,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.job_queue_time_till_first_scheduled_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.job_queue_time_till_fully_scheduled_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_scheduling_attempts_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_scheduling_attempts_99_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_task_scheduling_attempts_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_task_scheduling_attempts_99_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.is_multi_path,
                                                                                                                                                                                                                                                                                                                                                                                             sched_stat.failed_find_victim_attempts,
                                                                                                                                                                                                                                                                                                                                                                                             per_workload_busy_time.useful_busy_time,
                                                                                                                                                                                                                                                                                                                                                                                             per_workload_busy_time.wasted_busy_time,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.current_energy_consumed,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.total_energy_consumed,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.total_energy_saved,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.total_energy_consumed / exp_result.efficiency_stats.current_energy_consumed,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.total_power_off_number,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.kwh_saved_per_shutting,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_shuttings_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.max_shuttings_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.min_shuttings_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.shuttings_per_machine_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.shuttings_per_machine_99_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_time_shutted_down_per_cycle,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.max_time_shutted_down_per_cycle,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.min_time_shutted_down_per_cycle,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.time_shutted_down_per_cycle_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.time_shutted_down_per_cycle_99_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_time_shutted_down_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.max_time_shutted_down_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.min_time_shutted_down_per_machine,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.time_shutted_down_per_machine_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.time_shutted_down_per_machine_99_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_number_machines_on,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_number_machines_off,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_number_machines_turning_on,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_number_machines_turning_off,
                                                                                                                                                                                                                                                                                                                                                                                             exp_result.efficiency_stats.avg_number_machines_on/1000 - max(exp_result.cell_state_avg_cpu_utilization, exp_result.cell_state_avg_mem_utilization),
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.makespan_90_percentile,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan_epoch_0,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan_epoch_100,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan_epoch_500,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan_epoch_1000,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.avg_makespan_epoch_2000,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_0,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_1,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_2,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_3,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_4,
                                                                                                                                                                                                                                                                                                                                                                                             workload_stat.num_jobs_security_5)

# Create output files.
# One output file for each unique (cell_name, scheduler_name, metric) tuple.
printed_headers = []
for key_tuple, out_str in output_strings.iteritems():
    outfile_name = (outfile_name_base +
                    "." + "_".join([str(i) for i in key_tuple]) + ".txt")
    logging.info("Creating output file: %s" % outfile_name)
    outfile = open(outfile_name, "w")
    if "measurement" in outfile_name and outfile_name not in printed_headers:
        outfile.write("%s%s %s %s %s %s %s %s %s %s %s %s %s %s\n" % (opt_extra_newline,
                                                                   "used_cpu",
                                                                   "cpu_totally_idle",
                                                                   "cpu_partially_idle",
                                                                   "locked_cpu",
                                                                   "used_mem",
                                                                   "mem_totally_idle",
                                                                   "mem_partially_idle",
                                                                   "locked_mem",
                                                                   "off",
                                                                   "on",
                                                                   "turning_off",
                                                                   "turning_on",
                                                                   "strategy"))
    elif "scheduler_stats" in outfile_name and outfile_name not in printed_headers:
        outfile.write("%s%s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s\n" % (opt_extra_newline,
                                                                                                                                                                                                                                                                               "env.cell_name",
                                                                                                                                                                                                                                                                               "env.is_prefilled",
                                                                                                                                                                                                                                                                               "env.run_time(s)",
                                                                                                                                                                                                                                                                               "eff.power_on",
                                                                                                                                                                                                                                                                               "eff.power_off",
                                                                                                                                                                                                                                                                               "picker",
                                                                                                                                                                                                                                                                               "stackelberg",
                                                                                                                                                                                                                                                                               "sched_stat.scheduler_name",
                                                                                                                                                                                                                                                                               "exp_result.sweep_workload",
                                                                                                                                                                                                                                                                               "workload_stat.workload_name",
                                                                                                                                                                                                                                                                               "exp_result.cell_state_avg_cpu_utilization",
                                                                                                                                                                                                                                                                               "exp_result.cell_state_avg_mem_utilization",
                                                                                                                                                                                                                                                                               "exp_result.cell_state_avg_cpu_locked",
                                                                                                                                                                                                                                                                               "exp_result.cell_state_avg_mem_locked",
                                                                                                                                                                                                                                                                               "exp_result.constant_think_time(s)",
                                                                                                                                                                                                                                                                               "exp_result.per_task_think_time(s)",
                                                                                                                                                                                                                                                                               "exp_result.security_1_time(s)",
                                                                                                                                                                                                                                                                               "exp_result.security_2_time(s)",
                                                                                                                                                                                                                                                                               "exp_result.security_3_time(s)",
                                                                                                                                                                                                                                                                               "exp_result.avg_job_interarrival_time(s)",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs(in-workload)",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_scheduled(tried-in-workload)",
                                                                                                                                                                                                                                                                               "sched_stat.num_jobs_left_in_queue(left-in-scheduler-queue)",
                                                                                                                                                                                                                                                                               "sched_stat.num_jobs_timed_out_scheduling(100-job-tries-or-1000-with-same-tasks)",
                                                                                                                                                                                                                                                                               "sched_stat.useful_busy_time",
                                                                                                                                                                                                                                                                               "sched_stat.wasted_busy_time",
                                                                                                                                                                                                                                                                               "sched_stat.num_successful_transactions",
                                                                                                                                                                                                                                                                               "sched_stat.num_failed_transactions(el-commit-dio-conflicted-deltas)",
                                                                                                                                                                                                                                                                               "sched_stat.num_no_resources_found_scheduling_attempts(no-ha-colado-el-trabajo-en-el-cellstate-copia)",
                                                                                                                                                                                                                                                                               "sched_stat.num_retried_transactions",
                                                                                                                                                                                                                                                                               "sched_stat.num_successful_task_transactions",
                                                                                                                                                                                                                                                                               "sched_stat.num_failed_task_transactions",
                                                                                                                                                                                                                                                                               "workload_stat.job_think_times_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.avg_job_queue_times_till_first_scheduled",
                                                                                                                                                                                                                                                                               "workload_stat.avg_job_queue_times_till_fully_scheduled",
                                                                                                                                                                                                                                                                               "workload_stat.job_queue_time_till_first_scheduled_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.job_queue_time_till_fully_scheduled_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.num_scheduling_attempts_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.num_scheduling_attempts_99_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.num_task_scheduling_attempts_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.num_task_scheduling_attempts_99_percentile",
                                                                                                                                                                                                                                                                               "sched_stat.is_multi_path",
                                                                                                                                                                                                                                                                               "sched_stat.failed_find_victim_attempts",
                                                                                                                                                                                                                                                                               "per_workload_busy_time.useful_busy_time",
                                                                                                                                                                                                                                                                               "per_workload_busy_time.wasted_busy_time",
                                                                                                                                                                                                                                                                               "current_energy_consumed(kWh)",
                                                                                                                                                                                                                                                                               "total_energy_consumed(kWh)",
                                                                                                                                                                                                                                                                               "total_energy_saved(kWh)",
                                                                                                                                                                                                                                                                               "perc_energy_used_vs_current",
                                                                                                                                                                                                                                                                               "total_power_off_number",
                                                                                                                                                                                                                                                                               "kwh_saved_per_shutting",
                                                                                                                                                                                                                                                                               "avg_shuttings_per_machine",
                                                                                                                                                                                                                                                                               "max_shuttings_per_machine",
                                                                                                                                                                                                                                                                               "min_shuttings_per_machine",
                                                                                                                                                                                                                                                                               "shuttings_per_machine_90_percentile",
                                                                                                                                                                                                                                                                               "shuttings_per_machine_99_percentile",
                                                                                                                                                                                                                                                                               "avg_time_shutted_down_per_cycle",
                                                                                                                                                                                                                                                                               "max_time_shutted_down_per_cycle",
                                                                                                                                                                                                                                                                               "min_time_shutted_down_per_cycle",
                                                                                                                                                                                                                                                                               "time_shutted_down_per_cycle_90_percentile",
                                                                                                                                                                                                                                                                               "time_shutted_down_per_cycle_99_percentile",
                                                                                                                                                                                                                                                                               "avg_time_shutted_down_per_machine",
                                                                                                                                                                                                                                                                               "max_time_shutted_down_per_machine",
                                                                                                                                                                                                                                                                               "min_time_shutted_down_per_machine",
                                                                                                                                                                                                                                                                               "time_shutted_down_per_machine_90_percentile",
                                                                                                                                                                                                                                                                               "time_shutted_down_per_machine_99_percentile",
                                                                                                                                                                                                                                                                               "avg_number_machines_on",
                                                                                                                                                                                                                                                                               "avg_number_machines_off",
                                                                                                                                                                                                                                                                               "avg_number_machines_turning_on",
                                                                                                                                                                                                                                                                               "avg_number_machines_turning_off",
                                                                                                                                                                                                                                                                               "idle_resources_perc",
                                                                                                                                                                                                                                                                               "workload_stat.avg_makespan",
                                                                                                                                                                                                                                                                               "workload_stat.makespan_90_percentile",
                                                                                                                                                                                                                                                                               "workload_stat.avg_fitness_epoch_0",
                                                                                                                                                                                                                                                                               "workload_stat.avg_fitness_epoch_100",
                                                                                                                                                                                                                                                                               "workload_stat.avg_fitness_epoch_500",
                                                                                                                                                                                                                                                                               "workload_stat.avg_fitness_epoch_1000",
                                                                                                                                                                                                                                                                               "workload_stat.avg_fitness_epoch_2000",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_0",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_1",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_2",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_3",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_4",
                                                                                                                                                                                                                                                                               "workload_stat.num_jobs_security_5"))
        printed_headers.append(outfile_name)
    outfile.write(out_str)
    outfile.close()
