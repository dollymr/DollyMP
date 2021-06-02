import os
import json
import csv
import numpy as np
import pprint

jobs = {}
jobdist = {}
mapdist = {}
mapinfo = {}
attemptKeys = ['attemptId', 'time', 'host', 'locality', 'ifclone']
list_of_imgs = []
list_of_elapsedTime = []
img_dir = "./"

for img in sorted(os.listdir(img_dir)):
    if img.endswith('.jhist'):
        list_of_imgs.append(img)

with open('out.csv', 'wb') as csvfile:
    writer = csv.writer(csvfile, delimiter=',')
    for index, filename in enumerate(list_of_imgs):
        jobdist[index] = []
        #If testing on trace, this needs to be modified
        key = index
        cash = key
        if key not in jobs:
            jobs[key] = []
        with open(filename) as infile:
            for i, line in enumerate(infile):
                if i == 2:
                    p = json.loads(line)
                    startTime = (
                        p['event']['org.apache.hadoop.mapreduce.jobhistory.AMStarted']['startTime'])
                elif i % 2 == 1 or i <= 1:
                    continue
                else:
                    p = json.loads(line)
                    if p['type'] == 'TASK_STARTED' and p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskStarted']['taskType'] == 'MAP':
                        info = p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskStarted']
                        mapinfo[info['taskid']] = [
                            info['startTime'], info['splitLocations']]
                        mapdist[info['taskid']] = []
                        # print info['taskid']
                    elif p['type'] == 'MAP_ATTEMPT_STARTED' and (p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskAttemptStarted']['taskid'] in mapinfo):
                        info = p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskAttemptStarted']
                        infolist = [info['attemptId'], info['startTime'], info['trackerName'],
                                    info['locality']['string'], info['avataar']['string']]
                        mapdist[info['taskid']].append(
                            dict(zip(attemptKeys, infolist)))
                    elif p['type'] == 'TASK_FINISHED' and (p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskFinished']['taskid'] in mapinfo):
                        info = p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskFinished']
                        mapinfo[info['taskid']][0] = (
                            info['finishTime'] - mapinfo[info['taskid']][0]) / 1000
                        writeinfo = info['counters']['groups'][0]['counts'][5]['value']
                        # pprint.pprint(writeinfo)
                        mapinfo[info['taskid']].append(writeinfo)
                    elif p['type'] == 'MAP_ATTEMPT_FINISHED' and (p['event']['org.apache.hadoop.mapreduce.jobhistory.MapAttemptFinished']['taskid'] in mapdist):
                        info = p['event']['org.apache.hadoop.mapreduce.jobhistory.MapAttemptFinished']
                        for mapattempt in mapdist[info['taskid']]:
                            if mapattempt['attemptId'] == info['attemptId']:
                                mapattempt['time'] = (
                                    info['finishTime'] - mapattempt['time']) / 1000
                                mapattempt['status'] = 'SUCCEEDED'
                    elif p['type'] == 'MAP_ATTEMPT_KILLED' and (p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskAttemptUnsuccessfulCompletion']['taskid'] in mapdist):
                        info = p['event']['org.apache.hadoop.mapreduce.jobhistory.TaskAttemptUnsuccessfulCompletion']
                        for mapattempt in mapdist[info['taskid']]:
                            if mapattempt['attemptId'] == info['attemptId']:
                                mapattempt['time'] = (
                                    info['finishTime'] - mapattempt['time']) / 1000
                                mapattempt['status'] = 'KILLED'
            p = json.loads(line)
            finishTime = (
                p['event']['org.apache.hadoop.mapreduce.jobhistory.JobFinished']['finishTime'])
            elapsedTime = (finishTime - startTime) / 1000
            jobs[key].append(elapsedTime)
            writer.writerow([(key)] + mapinfo.keys())
            maptimelist = ['']
            nodelist0 = ['']
            nodelist1 = ['']
            nodelist2 = ['']
            for val in mapinfo.values():
                maptimelist.append(val[0])
                nodelist0.append(val[1].split(',')[0].split('.')[0])
                nodelist1.append(val[1].split(',')[1].split('.')[0])
                nodelist2.append(val[1].split(',')[2].split('.')[0])
            writer.writerows([maptimelist, nodelist0, nodelist1, nodelist2])
            for count in range(len(mapdist[mapdist.keys()[0]])):
                hostlist = ['']
                clonelist = ['']
                localitylist = ['']
                statuslist = ['']
                timelist = ['']
                for key in mapdist.keys():
                    for num in range(len(mapdist[key])):
                        hostlist.append(mapdist[key][num]["host"].split('.')[0])
                        clonelist.append(mapdist[key][num]["ifclone"])
                        localitylist.append(mapdist[key][num]["locality"])
                        statuslist.append(mapdist[key][num]["status"])
                        timelist.append(mapdist[key][num]["time"])
                writer.writerow(["attempt " + str(count)])
                writer.writerows(
                    [hostlist, clonelist, localitylist, statuslist, timelist])
            print str(cash) + ' ' +str(len(nodelist0)) + ' ' + str(len(clonelist)) + ' ' + str(len(clonelist) - len(nodelist0))
            #pprint.pprint(mapinfo)
            #pprint.pprint(mapdist)
            mapdist = {}
            mapinfo = {}
