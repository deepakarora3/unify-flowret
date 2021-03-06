/*
 * Copyright 2020 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.americanexpress.unify.flowret;

import com.americanexpress.unify.jdocs.Document;
import com.americanexpress.unify.jdocs.JDocument;
import com.americanexpress.unify.jdocs.UnifyException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/*
 * @author Deepak Arora
 */
public final class Rts {

  private static Logger logger = LogManager.getLogger(Rts.class);

  // variables are protected so that they can be accessed by classes in the same package
  protected FlowretDao dao = null;
  protected ProcessComponentFactory factory = null;
  protected EventHandler eventHandler = null;
  protected ProcessDefinition pd = null;
  protected Document slad = null;
  protected ProcessInfo pi = null;
  protected ISlaQueueManager slaQm = null;

  protected Rts(FlowretDao dao, ProcessComponentFactory factory, EventHandler eventHandler, ISlaQueueManager slaQm) {
    this.dao = dao;
    this.factory = factory;
    this.eventHandler = eventHandler;
    this.slaQm = slaQm;
  }

  protected void invokeEventHandler(EventType event, ProcessContext pc) {
    if (eventHandler == null) {
      return;
    }

    String wb = pc.getPendWorkBasket();
    wb = (wb == null) ? "" : wb;
    logger.info("Case id -> {}, raising event -> {}, comp name -> {}, work basket -> {}", pi.getCaseId(), event.name(), pc.getCompName(), wb);

    if ((event == EventType.ON_PERSIST) || (event == EventType.ON_TICKET_RAISED)) {
      try {
        pi.getLock().lock();
        eventHandler.invoke(event, pc);
      }
      finally {
        pi.getLock().unlock();
      }
    }
    else {
      try {
        eventHandler.invoke(event, pc);
        if ((slad != null) && (slaQm != null)) {
          raiseSlaEvent(event, pc);
        }
      }
      catch (Exception e) {
        // we log an error but we do not stop and the application has generated an error and we are not responsible for that
        logger.error("Error encountered while invoking event. Case id -> {}, event type -> {}, error message -> {}", pi.getCaseId(), event.name(), e.getMessage());
      }
    }
  }

  public ProcessContext startCase(String caseId, String journeyJson, ProcessVariables pvs, String journeySlaJson) {
    if (pvs == null) {
      pvs = new ProcessVariables();
    }

    String key = CONSTS_FLOWRET.DAO.JOURNEY + CONSTS_FLOWRET.DAO.SEP + caseId;

    // check if the document already exists
    Document d = dao.read(key);
    if (d != null) {
      throw new UnifyException("flowret_err_1", caseId);
    }

    // read the process definition and get process info
    d = new JDocument(journeyJson);
    dao.write(CONSTS_FLOWRET.DAO.JOURNEY + CONSTS_FLOWRET.DAO.SEP + caseId, d);
    pd = Utils.getProcessDefinition(d);
    pi = Utils.getProcessInfo(dao, caseId, pd);

    // write and get the sla configuration
    if (journeySlaJson != null) {
      slad = new JDocument(journeySlaJson);
      dao.write(CONSTS_FLOWRET.DAO.JOURNEY_SLA + CONSTS_FLOWRET.DAO.SEP + caseId, slad);
    }

    // update process variables
    List<ProcessVariable> list = pvs.getListOfProcessVariables();
    for (ProcessVariable pv : list) {
      pi.setProcessVariable(pv);
    }

    logger.info("Case id -> " + pi.getCaseId() + ", successfully created case");

    // invoke event handler
    boolean bContinue = true;
    ProcessContext pc = ProcessContext.forEvent(EventType.ON_PROCESS_START, this, ".");
    try {
      invokeEventHandler(EventType.ON_PROCESS_START, pc);
    }
    catch (Exception e) {
      bContinue = false;
      logger.info("Case id -> " + pi.getCaseId() + ", aborting as application exception encountered while raising event");
      logger.info("Case id -> " + pi.getCaseId() + ", exception details -> " + e.getMessage());
      logger.info("Case id -> " + pi.getCaseId() + ", exception stack -> " + e.getStackTrace());
    }

    // start case
    if (bContinue == true) {
      pc = resumeCase(caseId, false);
    }

    return pc;
  }

  private ProcessContext resumeCase(String caseId, boolean raiseResumeEvent) {
    if (raiseResumeEvent == true) {
      // we are being called on our own
      // read process definition
      String key = CONSTS_FLOWRET.DAO.JOURNEY + CONSTS_FLOWRET.DAO.SEP + caseId;
      Document d = dao.read(key);
      if (d == null) {
        throw new UnifyException("flowret_err_2", caseId);
      }
      pd = Utils.getProcessDefinition(d);
      pi = Utils.getProcessInfo(dao, caseId, pd);
      pi.isPendAtSameStep = true;

      // read sla configuration
      key = CONSTS_FLOWRET.DAO.JOURNEY_SLA + CONSTS_FLOWRET.DAO.SEP + caseId;
      slad = dao.read(key);
    }

    // check if we have already completed
    if (pi.isCaseCompleted() == true) {
      throw new UnifyException("flowret_err_6", pi.getCaseId());
    }

    boolean bContinue = true;
    ProcessContext pc = null;
    try {
      if (raiseResumeEvent) {
        pc = ProcessContext.forEvent(EventType.ON_PROCESS_RESUME, this, pi.getPendExecPath());
        invokeEventHandler(EventType.ON_PROCESS_RESUME, pc);
      }
    }
    catch (Exception e) {
      bContinue = false;
      logger.info("Case id -> " + pi.getCaseId() + ", aborting as application exception encountered while raising event");
      logger.info("Case id -> " + pi.getCaseId() + ", exception details -> " + e.getMessage());
      logger.info("Case id -> " + pi.getCaseId() + ", exception stack -> " + e.getStackTrace());
    }

    if (bContinue == true) {
      // initiate on the current thread
      ExecThreadTask task = new ExecThreadTask(this);
      pc = task.execute();
    }

    return pc;
  }

  public ProcessContext resumeCase(String caseId) {
    return resumeCase(caseId, true);
  }

  private void raiseSlaEvent(EventType event, ProcessContext pc) {
    Document d = null;
    String caseId = pc.getCaseId();

    switch (event) {
      case ON_PROCESS_START: {
        Utils.enqueueCaseStartMilestones(pc, slad, slaQm);
        break;
      }

      case ON_PROCESS_PEND: {
        ExecPath ep = pi.getExecPath(pi.getPendExecPath());
        String prevPendWorkBasket = ep.getPrevPendWorkBasket();
        String pendWorkBasket = ep.getPendWorkBasket();
        String tbcWorkBasket = ep.getTbcSlaWorkBasket();

        if (pi.isPendAtSameStep == false) {
          if (prevPendWorkBasket.equals(tbcWorkBasket)) {
            Utils.dequeueWorkBasketMilestones(pc, prevPendWorkBasket, slaQm);
          }
          else {
            Utils.dequeueWorkBasketMilestones(pc, prevPendWorkBasket, slaQm);
            Utils.dequeueWorkBasketMilestones(pc, tbcWorkBasket, slaQm);
          }
          Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_exit, prevPendWorkBasket, slad, slaQm);
          Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_entry, pendWorkBasket, slad, slaQm);
          ep.setTbcSlaWorkBasket("");
          break;
        }

        // handling is_pend_at_same_step
        if (prevPendWorkBasket.equals(pendWorkBasket) == false) {
          // means that the first pend at this step was a pend_eor or error pend
          if (ep.getUnitResponseType() == UnitResponseType.ERROR_PEND) {
            if (prevPendWorkBasket.equals(tbcWorkBasket)) {
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_entry, pendWorkBasket, slad, slaQm);
            }
            else {
              Utils.dequeueWorkBasketMilestones(pc, prevPendWorkBasket, slaQm);
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_exit, prevPendWorkBasket, slad, slaQm);
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_entry, pendWorkBasket, slad, slaQm);
            }
          }
          else if (ep.getUnitResponseType() == UnitResponseType.OK_PEND_EOR) {
            if (prevPendWorkBasket.equals(tbcWorkBasket)) {
              Utils.dequeueWorkBasketMilestones(pc, prevPendWorkBasket, slaQm);
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_exit, prevPendWorkBasket, slad, slaQm);
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_entry, pendWorkBasket, slad, slaQm);
              ep.setTbcSlaWorkBasket(pendWorkBasket);
            }
            else {
              Utils.dequeueWorkBasketMilestones(pc, prevPendWorkBasket, slaQm);
              Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_exit, prevPendWorkBasket, slad, slaQm);

              if (pendWorkBasket.equals(tbcWorkBasket) == false) {
                Utils.dequeueWorkBasketMilestones(pc, tbcWorkBasket, slaQm);
                Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_exit, tbcWorkBasket, slad, slaQm);
                Utils.enqueueWorkBasketMilestones(pc, SlaMilestoneSetupOn.work_basket_entry, pendWorkBasket, slad, slaQm);
                ep.setTbcSlaWorkBasket(pendWorkBasket);
              }
              else {
                // nothing to do
              }
            }
          }
          else if (ep.getUnitResponseType() == UnitResponseType.OK_PEND) {
            // this situation cannot happen
          }
        }
        else {
          // nothing to do
        }

        break;
      }

      case ON_PROCESS_RESUME: {
        ExecPath ep = pi.getExecPath(pi.getPendExecPath());
        String pendWorkBasket = ep.getPendWorkBasket();
        UnitResponseType urt = ep.getUnitResponseType();
        if (urt == UnitResponseType.OK_PEND_EOR) {
          // set it to be used in the next pend or when the process moves ahead
          ep.setTbcSlaWorkBasket(pendWorkBasket);
        }
        break;
      }

      case ON_PROCESS_COMPLETE:
        slaQm.dequeueAll(pc);
        break;

    }
  }

}
