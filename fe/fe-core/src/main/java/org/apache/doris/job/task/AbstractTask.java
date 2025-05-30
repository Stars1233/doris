// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.job.task;

import org.apache.doris.catalog.Env;
import org.apache.doris.job.base.AbstractJob;
import org.apache.doris.job.base.Job;
import org.apache.doris.job.common.TaskStatus;
import org.apache.doris.job.common.TaskType;
import org.apache.doris.job.exception.JobException;
import org.apache.doris.thrift.TUniqueId;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.RandomUtils;

import java.util.UUID;

@Data
@Log4j2
public abstract class AbstractTask implements Task {

    @SerializedName(value = "jid")
    private Long jobId;
    @SerializedName(value = "tid")
    private Long taskId;

    @SerializedName(value = "st")
    private TaskStatus status;
    @SerializedName(value = "ctm")
    private Long createTimeMs;
    @SerializedName(value = "stm")
    private Long startTimeMs;
    @SerializedName(value = "ftm")
    private Long finishTimeMs;

    @SerializedName(value = "tt")
    private TaskType taskType;

    @SerializedName(value = "emg")
    private String errMsg;

    public AbstractTask() {
        taskId = getNextTaskId();
    }

    private static long getNextTaskId() {
        // do not use Env.getNextId(), just generate id without logging
        return System.nanoTime() + RandomUtils.nextInt();
    }

    @Override
    public boolean onFail() throws JobException {
        if (TaskStatus.CANCELED.equals(status)) {
            return false;
        }
        status = TaskStatus.FAILED;
        if (!isCallable()) {
            return false;
        }
        Env.getCurrentEnv().getJobManager().getJob(jobId).onTaskFail(this);
        return true;
    }

    @Override
    public void onFail(String errMsg) throws JobException {
        if (TaskStatus.CANCELED.equals(status)) {
            return;
        }
        status = TaskStatus.FAILED;
        setFinishTimeMs(System.currentTimeMillis());
        setErrMsg(errMsg);
        if (!isCallable()) {
            return;
        }
        Job job = Env.getCurrentEnv().getJobManager().getJob(getJobId());
        job.onTaskFail(this);
    }

    private boolean isCallable() {
        if (status.equals(TaskStatus.CANCELED)) {
            return false;
        }
        if (null != Env.getCurrentEnv().getJobManager().getJob(jobId)) {
            return true;
        }
        return false;
    }

    /**
     * Closes or releases all allocated resources such as database connections, file streams, or any other
     * external system handles that were utilized during the task execution. This method is invoked
     * unconditionally, ensuring that resources are properly managed whether the task completes
     * successfully, fails, or is canceled. It is crucial for preventing resource leaks and maintaining
     * the overall health and efficiency of the application.
     * <p>
     * Note: Implementations of this method should handle potential exceptions internally and log them
     * appropriately to avoid interrupting the normal flow of cleanup operations.
     */
    protected abstract void closeOrReleaseResources();

    @Override
    public boolean onSuccess() throws JobException {
        if (TaskStatus.CANCELED.equals(status)) {
            return false;
        }
        status = TaskStatus.SUCCESS;
        setFinishTimeMs(System.currentTimeMillis());
        if (!isCallable()) {
            return false;
        }
        Job job = Env.getCurrentEnv().getJobManager().getJob(getJobId());
        if (null == job) {
            log.info("job is null, job id is {}", jobId);
            return false;
        }
        job.onTaskSuccess(this);
        return true;
    }

    /**
     * Cancels the ongoing task, updating its status to {@link TaskStatus#CANCELED} and releasing associated resources.
     * This method encapsulates the core cancellation logic, calling the abstract method
     * {@link #executeCancelLogic(boolean)} for task-specific actions.
     *
     * @throws JobException If an error occurs during the cancellation process, a new JobException is thrown wrapping
     *                      the original exception.
     */
    @Override
    public boolean cancel(boolean needWaitCancelComplete) throws JobException {
        if (TaskStatus.SUCCESS.equals(status) || TaskStatus.FAILED.equals(status) || TaskStatus.CANCELED.equals(
                status)) {
            return false;
        }
        try {
            status = TaskStatus.CANCELED;
            executeCancelLogic(needWaitCancelComplete);
            return true;
        } catch (Exception e) {
            log.warn("cancel task failed, job id is {}, task id is {}", jobId, taskId, e);
            throw new JobException(e);
        } finally {
            closeOrReleaseResources();
        }
    }

    /**
     * Abstract method for implementing the task-specific cancellation logic.
     * Subclasses must override this method to provide their own implementation of how a task should be canceled.
     *
     * @throws Exception Any exception that might occur during the cancellation process in the subclass.
     */
    protected abstract void executeCancelLogic(boolean needWaitCancelComplete) throws Exception;

    public static TUniqueId generateQueryId() {
        UUID taskId = UUID.randomUUID();
        return new TUniqueId(taskId.getMostSignificantBits(), taskId.getLeastSignificantBits());
    }

    @Override
    public void before() throws JobException {
        status = TaskStatus.RUNNING;
        setStartTimeMs(System.currentTimeMillis());
    }

    public void runTask() throws JobException {
        try {
            before();
            run();
            onSuccess();
        } catch (Exception e) {
            if (TaskStatus.CANCELED.equals(status)) {
                return;
            }
            this.errMsg = e.getMessage();
            onFail();
            log.warn("execute task error, job id is {}, task id is {}", jobId, taskId, e);
        } finally {
            // The cancel logic will call the closeOrReleased Resources method by itself.
            // If it is also called here,
            // it may result in the inability to obtain relevant information when canceling the task
            if (!TaskStatus.CANCELED.equals(status)) {
                closeOrReleaseResources();
            }
        }
    }

    public boolean isCancelled() {
        return status.equals(TaskStatus.CANCELED);
    }

    public String getJobName() {
        AbstractJob job = Env.getCurrentEnv().getJobManager().getJob(jobId);
        return job == null ? "" : job.getJobName();
    }

    public Job getJobOrJobException() throws JobException {
        AbstractJob job = Env.getCurrentEnv().getJobManager().getJob(jobId);
        if (job == null) {
            throw new JobException("job not exist, jobId:" + jobId);
        }
        return job;
    }

    @Override
    public String toString() {
        return "AbstractTask{"
                + "jobId=" + jobId
                + ", taskId=" + taskId
                + ", status=" + status
                + ", createTimeMs=" + createTimeMs
                + ", startTimeMs=" + startTimeMs
                + ", finishTimeMs=" + finishTimeMs
                + ", taskType=" + taskType
                + ", errMsg='" + errMsg + '\''
                + '}';
    }
}
