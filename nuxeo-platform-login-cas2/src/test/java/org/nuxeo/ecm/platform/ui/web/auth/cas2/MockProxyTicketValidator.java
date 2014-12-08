/*
 * (C) Copyright 2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 */

package org.nuxeo.ecm.platform.ui.web.auth.cas2;

import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import edu.yale.its.tp.cas.client.ProxyTicketValidator;

public class MockProxyTicketValidator extends ProxyTicketValidator {

    @Override
    public void validate() throws IOException, SAXException, ParserConfigurationException {

        if (callBackValidator == null) {
            return;
        }

        if (!MockProxyTicketValidator.callBackValidator.checkProxyTicketValidatorState(this)) {
            throw new RuntimeException("ProxyTicketValidator state not correct");
        }
    }

    public static CallBackCheckTicketValidatorState callBackValidator;

    public static void setCallBackValidator(CallBackCheckTicketValidatorState callBackValidator) {
        MockProxyTicketValidator.callBackValidator = callBackValidator;
    }

}
