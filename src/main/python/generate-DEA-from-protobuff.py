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
            if prev_l_val != exp_result.per_task_think_time and prev_l_val != -1.0:
                opt_extra_newline = "\n"
            else:
                opt_extra_newline = ""
            prev_l_val = exp_result.per_task_think_time

            scheduler_stats_key = (env.cell_name, sched_stat.scheduler_name, "DEA")

            for workload_stat in exp_result.workload_stats:
                #if workload_stat.workload_name == exp_result.sweep_workload:
                for per_workload_busy_time in sched_stat.per_workload_busy_time:
                    if per_workload_busy_time.workload_name == workload_stat.workload_name:
                        output_strings[scheduler_stats_key] += \
                            "%s%s %s %i %i %.2f %.2f %.4f\n" % (opt_extra_newline,
                                                             exp_result.efficiency_stats.power_off_policy.name,
                                                             workload_stat.workload_name,
                                                             10000,
                                                             exp_result.efficiency_stats.total_power_off_number,
                                                             env.run_time * exp_result.cell_state_avg_cpu_utilization,
                                                             exp_result.efficiency_stats.total_energy_consumed/3600000,
                                                             workload_stat.avg_job_queue_times_till_fully_scheduled)


# Create output files.
# One output file for each unique (cell_name, scheduler_name, metric) tuple.
printed_headers = []
for key_tuple, out_str in output_strings.iteritems():
    outfile_name = (outfile_name_base +
                    "." + "_".join([str(i) for i in key_tuple]) + ".txt")
    logging.info("Creating output file: %s" % outfile_name)
    outfile = open(outfile_name, "w")
    if "DEA" in outfile_name and outfile_name not in printed_headers:
        outfile.write("%s%s %s %s %s %s %s %s\n" % (opt_extra_newline,
                                           "Politica-Off",
                                           "Workload",
                                           "#Recursos",
                                           "#Apagados",
                                           "Computacion(s)",
                                           "Consumo(kWh)",
                                           "Tiempo-en-cola(s)"))
        printed_headers.append(outfile_name)
    outfile.write(out_str)
    outfile.close()
