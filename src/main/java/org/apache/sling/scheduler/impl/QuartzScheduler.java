/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.scheduler.impl;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.sling.scheduler.Scheduler;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The quartz based implementation of the scheduler.
 *
 * @scr.component
 * @scr.service interface="org.apache.sling.scheduler.Scheduler"
 * @scr.reference name="job" interface="org.quartz.Job" cardinality="0..n" policy="dynamic" bind="bindJob" unbind="unbindJob"
 * @scr.reference name="task" interface="java.lang.Runnable" cardinality="0..n" policy="dynamic" bind="bindJob" unbind="unbindJob"
 */
public class QuartzScheduler implements Scheduler {

    /** Default log. */
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected static final String DEFAULT_QUARTZ_JOB_GROUP = "Sling";

    /** Map key for the job object */
    static final String DATA_MAP_OBJECT = "QuartzJobScheduler.Object";

    /** Map key for the job name */
    static final String DATA_MAP_NAME = "QuartzJobScheduler.JobName";

    /** Map key for the concurrent run property */
    static final String DATA_MAP_RUN_CONCURRENT = "QuartzJobScheduler.RunConcurrently";

    /** Map key for the run status */
    static final String DATA_MAP_KEY_ISRUNNING = "QuartzJobExecutor.isRunning";

    protected org.quartz.Scheduler scheduler;

    protected final List<ServiceReference> registeredJobs = new ArrayList<ServiceReference>();

    protected ComponentContext context;

    protected void activate(ComponentContext ctx) throws Exception {
        this.context = ctx;
        synchronized ( this.registeredJobs ) {
            this.init();
            for( ServiceReference ref : this.registeredJobs ) {
                try {
                    this.register(ref);
                } catch (Exception e) {
                    // we don't want that one malicious service brings down the scheduler, so we just log
                    // the exception and continue
                    this.logger.error("Exception during registering job service {}.", ref, e);
                }
            }
            this.registeredJobs.clear();
        }
    }

    protected void deactivate(ComponentContext ctx) {
        synchronized (this.registeredJobs ) {
            this.dispose();
        }
        this.context = null;
    }

    protected void init() throws SchedulerException {
        final SchedulerFactory factory = new StdSchedulerFactory();
        this.scheduler = factory.getScheduler();
        this.scheduler.start();
        if ( this.logger.isDebugEnabled() ) {
            this.logger.debug("Scheduler started.");
        }
    }

    protected void dispose() {
        if ( this.scheduler != null ) {
            try {
                this.scheduler.shutdown();
            } catch (SchedulerException e) {
                this.logger.debug("Exception during shutdown of scheduler.", e);
            }
            if ( this.logger.isDebugEnabled() ) {
                this.logger.debug("Scheduler stopped.");
            }
            this.scheduler = null;
        }
    }

    /**
     * Add a job to the scheduler
     *
     * @param name The name of the job to add
     * @param Tje jopb
     * @param trigger a Trigger
     * @param canRunConcurrently whether this job can be run concurrently
     *
     * @throws Exception thrown in case of errors
     */
    protected void scheduleJob(final String name,
                               final Object job,
                               final Map<Object, Object>    config,
                               final Trigger trigger,
                               final boolean canRunConcurrently)
    throws Exception {
        // check if the supplied object is valid
        this.checkJob(job);

        // if there is already a job with the name, remove it first
        try {
            final JobDetail jobdetail = this.scheduler.getJobDetail(name, DEFAULT_QUARTZ_JOB_GROUP);
            if (jobdetail != null) {
                this.removeJob(name);
            }
        } catch (final SchedulerException ignored) {
        }

        // create the data map
        final JobDataMap jobDataMap = this.initDataMap(name, job, config, canRunConcurrently);

        final JobDetail detail = this.createJobDetail(name, jobDataMap);

        this.scheduler.scheduleJob(detail, trigger);
    }

    protected JobDataMap initDataMap(String  jobName,
                                     Object  job,
                                     Map<Object, Object>     config,
                                     boolean concurent) {
        final JobDataMap jobDataMap = new JobDataMap();
        // if config is supplied copy all entries first
        if ( config != null ) {
            jobDataMap.putAll(config);
        }
        jobDataMap.put(DATA_MAP_OBJECT, job);

        jobDataMap.put(DATA_MAP_NAME, jobName);
        jobDataMap.put(DATA_MAP_RUN_CONCURRENT, (concurent? Boolean.TRUE: Boolean.FALSE));

        return jobDataMap;
    }

    protected JobDetail createJobDetail(String name, JobDataMap jobDataMap) {
        final JobDetail detail = new JobDetail(name, DEFAULT_QUARTZ_JOB_GROUP, QuartzJobExecutor.class);
        detail.setJobDataMap(jobDataMap);
        return detail;
    }

    /**
     * Check the job object, either runnable or job is allowed
     */
    protected void checkJob(Object job)
    throws Exception {
        if (!(job instanceof Runnable) && !(job instanceof Job)) {
            throw new Exception("Job object is neither an instance of " + Runnable.class.getName() + " nor " + Job.class.getName());
        }
    }

    /**
     * @see org.apache.sling.core.scheduler.Scheduler#addJob(java.lang.String, java.lang.Object, java.util.Map, java.lang.String, boolean)
     */
    public void addJob(String name,
                       Object job,
                       Map<Object, Object>    config,
                       String schedulingExpression,
                       boolean canRunConcurrently)
    throws Exception {
        final CronTrigger cronJobEntry = new CronTrigger(name, DEFAULT_QUARTZ_JOB_GROUP);

        try {
            cronJobEntry.setCronExpression(schedulingExpression);
        } catch (final ParseException pe) {
            throw new Exception(pe.getMessage(), pe);
        }
        this.scheduleJob(name, job, config, cronJobEntry, canRunConcurrently);
    }

    /**
     * @see org.apache.sling.core.scheduler.Scheduler#addPeriodicJob(java.lang.String, java.lang.Object, java.util.Map, long, boolean)
     */
    public void addPeriodicJob(String name, Object job, Map<Object, Object> config, long period, boolean canRunConcurrently)
    throws Exception {
        final long ms = period * 1000;
        final SimpleTrigger timeEntry =
            new SimpleTrigger(name, DEFAULT_QUARTZ_JOB_GROUP, new Date(System.currentTimeMillis() + ms), null,
                              SimpleTrigger.REPEAT_INDEFINITELY, ms);

        this.scheduleJob(name, job, config, timeEntry, canRunConcurrently);
    }

    /**
     * @see org.apache.sling.core.scheduler.Scheduler#fireJob(java.lang.Object, java.util.Map)
     */
    public void fireJob(Object job, Map<Object, Object> config)
    throws Exception {
        this.checkJob(job);
        final String name = job.getClass().getName();
        final JobDataMap dataMap = this.initDataMap(name, job, config, true);

        final JobDetail detail = this.createJobDetail(name, dataMap);

        final Trigger trigger = new SimpleTrigger(name, DEFAULT_QUARTZ_JOB_GROUP);
        this.scheduler.scheduleJob(detail, trigger);
    }

    /**
     * @see org.apache.sling.core.scheduler.Scheduler#fireJobAt(java.lang.String, java.lang.Object, java.util.Map, java.util.Date)
     */
    public void fireJobAt(String name, Object job, Map<Object, Object> config, Date date) throws Exception {
        final SimpleTrigger trigger = new SimpleTrigger(name, DEFAULT_QUARTZ_JOB_GROUP, date);
        this.scheduleJob(name, job, config, trigger, true);
    }

    /**
     * @see org.apache.sling.core.scheduler.Scheduler#removeJob(java.lang.String)
     */
    public void removeJob(String name) throws NoSuchElementException {
        try {
            this.scheduler.deleteJob(name, DEFAULT_QUARTZ_JOB_GROUP);
        } catch (final SchedulerException se) {
            throw new NoSuchElementException(se.getMessage());
        }
    }

    protected void register(ServiceReference ref)
    throws Exception {
        // get the job
        final Object job = this.context.getBundleContext().getService(ref);
        if ( ref != null ) {
            this.checkJob(job);
            String name = (String)ref.getProperty("scheduler.name");
            if ( name == null ) {
                name = (String)ref.getProperty(Constants.SERVICE_PID);
            }
            if ( name != null ) {
                final Boolean concurrent = (Boolean)ref.getProperty("scheduler.concurrent");
                final String expression = (String)ref.getProperty("scheduler.expression");
                if ( expression != null ) {
                    this.addJob(name, job, null, expression, (concurrent != null ? concurrent : true));
                } else {
                    final Long period = (Long)ref.getProperty("scheduler.period");
                    if ( period != null ) {
                        this.addPeriodicJob(name, job, null, period, (concurrent != null ? concurrent : true));
                    }
                }
            } else {
                throw new Exception("Job service must either have a PID or a configured property 'scheduler.name'.");
            }
        }
    }

    protected void unregister(ServiceReference ref) {
        String name = (String)ref.getProperty("scheduler.name");
        if ( name == null ) {
            name = (String)ref.getProperty(Constants.SERVICE_PID);
        }
        if ( name != null ) {
            this.removeJob(name);
        }
    }

    protected void bindJob(ServiceReference ref)
    throws Exception {
        synchronized ( this.registeredJobs ) {
            if ( this.scheduler != null ) {
                this.register(ref);
            } else {
                this.registeredJobs.add(ref);
            }
        }
    }

    protected void unbindJob(ServiceReference ref) {
        synchronized ( this.registeredJobs ) {
            if ( this.scheduler != null ) {
                this.unregister(ref);
            }
        }
    }
}
