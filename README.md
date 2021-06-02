# Task Cloning Scheduler
## Overview
The hadoop cluster of our own algorithm is built under ``~/hadoop`` at all nodes, whose commands can be invoked by

```
hadoopc your_command
hdfsc your_command
```

## Testing
There are 3 types of jobs concerned now: QuasiMonteCarlo (calculating pi), teragen and wordcount, under ``Tests/pi``,``Tests/terasort``,``Tests/wc`` respectively. Under each test directory, we have 3 subdirectories containing the compiled programs with 0,1,2 copies of our DollyMP algorithm. Also, sample testing scripts are available under each test directory.

Basic testing commands:
```
hadoopc jar QuasiMonteCarlo.jar QuasiMonteCarlo #_of_mappers #_of_samples(used to control job size)
hadoopc jar TeraGen.jar TeraGen -D mapred.map.tasks=#_of_mappers filesize(in 10GB)
hadoopc jar WordCount.jar WordCount input_dir output_dir(both in HDFS)
```

**Trace Data and Job Submission Command**

To test on the trace, please refer to [Trace_shell_gen](Trace_shell_gen), then put the produced shell scripts under ``~/Tests`` and run.

## Result Retrieval and Analysis
To retrieve the information of completed jobs and further visualize them into csv files, please refer to [JobHistory_Analysis](JobHistory_Analysis).

Some previous results can be found at [result](result).
## Miscellaneous
**pdsh**

There is a powerful tool **pdsh** allowing us to run a command on multiple machines in the local area network with only one command at master node. Usage: under ``~/fqdn``, there is a file called ``all_hosts`` storing the alias of all machines in the cluster. Then for example,

```
pdsh -w ^all_hosts mkdir abc
```
will create a ``abc`` directory under the home directory of every node.

**scp to multiple dests**

Also under ``~/fqdn``, there is a script ``copy.sh``, the syntax can be followed to copy files from master node to several slave nodes. This is useful when we make changes to configuration files and want to distribute the new configuration to all nodes.