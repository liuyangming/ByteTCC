/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.springcloud.controller;

import java.beans.PropertyEditorSupport;

import javax.servlet.http.HttpServletResponse;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.xa.XidFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class CompensableCoordinatorController extends PropertyEditorSupport implements CompensableBeanFactoryAware {
	@Autowired
	private CompensableCoordinator compensableCoordinator;
	@Autowired
	private CompensableBeanFactory beanFactory;

	@RequestMapping(value = "/org/bytesoft/bytetcc/prepare/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public int prepare(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			return this.compensableCoordinator.prepare(xid);
		} catch (XAException ex) {
			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
			return -1;
		} catch (RuntimeException ex) {
			response.addHeader("failure", "true");
			response.setStatus(500);
			return -1;
		}
	}

	@RequestMapping(value = "/org/bytesoft/bytetcc/commit/{xid}/{opc}", method = RequestMethod.POST)
	@ResponseBody
	public void commit(@PathVariable("xid") String identifier, @PathVariable("opc") boolean onePhase,
			HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.commit(xid, onePhase);
		} catch (XAException ex) {
			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	@RequestMapping(value = "/org/bytesoft/bytetcc/rollback/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public void rollback(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.rollback(xid);
		} catch (XAException ex) {
			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	@RequestMapping(value = "/org/bytesoft/bytetcc/recover/{flag}", method = RequestMethod.GET)
	@ResponseBody
	public Xid[] recover(@PathVariable("flag") int flag, HttpServletResponse response) {
		try {
			return this.compensableCoordinator.recover(flag);
		} catch (XAException ex) {
			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
			return new Xid[0];
		} catch (RuntimeException ex) {
			response.addHeader("failure", "true");
			response.setStatus(500);
			return new Xid[0];
		}
	}

	@RequestMapping(value = "/org/bytesoft/bytetcc/forget/{xid}", method = RequestMethod.POST)
	@ResponseBody
	public void forget(@PathVariable("xid") String identifier, HttpServletResponse response) {
		try {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			byte[] byteArray = ByteUtils.stringToByteArray(identifier);
			Xid xid = xidFactory.createGlobalXid(byteArray);

			this.compensableCoordinator.forget(xid);
		} catch (XAException ex) {
			response.addHeader("failure", "true");
			response.addHeader("XA_XAER", String.valueOf(ex.errorCode));
			response.setStatus(500);
		} catch (RuntimeException ex) {
			response.addHeader("failure", "true");
			response.setStatus(500);
		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
