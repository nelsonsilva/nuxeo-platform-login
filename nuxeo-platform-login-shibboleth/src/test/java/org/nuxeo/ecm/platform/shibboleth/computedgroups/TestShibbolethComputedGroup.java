/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and contributors.
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
 *     Arnaud Kervern
 */

package org.nuxeo.ecm.platform.shibboleth.computedgroups;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.jboss.el.ExpressionFactoryImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.computedgroups.GroupComputer;
import org.nuxeo.ecm.platform.el.ExpressionContext;
import org.nuxeo.ecm.platform.el.ExpressionEvaluator;
import org.nuxeo.ecm.platform.usermanager.NuxeoPrincipalImpl;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy({ "org.nuxeo.ecm.platform.content.template", "org.nuxeo.ecm.platform.dublincore",
        "org.nuxeo.ecm.platform.usermanager", "org.nuxeo.ecm.platform.el", "org.nuxeo.ecm.platform.usermanager.api",
        "org.nuxeo.ecm.directory.api", "org.nuxeo.ecm.directory.types.contrib", "org.nuxeo.ecm.directory",
        "org.nuxeo.ecm.directory.sql", "org.nuxeo.ecm.platform.login.shibboleth", "org.nuxeo.ecm.platform.web.common" })
@LocalDeploy("org.nuxeo.ecm.platform.login.shibboleth:OSGI-INF/test-sql-directory.xml")
public class TestShibbolethComputedGroup {

    @Before
    public void setUp() throws Exception {
        userDir = directoryService.open("userDirectory");
        groupDir = directoryService.open("shibbGroup");
        groupDir.deleteEntry("group1");
        groupDir.deleteEntry("group2");
        groupDir.deleteEntry("group3");
        groupDir.deleteEntry("group4");
    }

    @After
    public void setDown() throws Exception {
        if (userDir != null) {
            userDir.close();
        }

        if (groupDir != null) {
            groupDir.close();
        }
    }

    @Inject
    protected CoreSession session;

    @Inject
    protected DirectoryService directoryService;

    protected Session userDir;

    protected Session groupDir;

    protected static String[] sampleArray = new String[] { "hello", "world" };

    @Test
    public void testOnlyEL() {
        ExpressionEvaluator ee = new ExpressionEvaluator(new ExpressionFactoryImpl());
        ExpressionContext ec = new ExpressionContext();

        ee.bindValue(ec, "hello", sampleArray);
        assertSame("world", ee.evaluateExpression(ec, "${hello[1]}", String.class));
        assertNotSame("world", ee.evaluateExpression(ec, "${hello[0]}", String.class));
    }

    @Test
    public void testELOnDocumentModel() throws Exception {
        DocumentModel user = createUser("user1");
        user.setProperty("user", "company", "test");
        user.setProperty("user", "email", "mail");

        assertTrue(ELGroupComputerHelper.isUserInGroup(user, "currentUser.user.company == \"test\""));
        assertFalse(ELGroupComputerHelper.isUserInGroup(user, "currentUser.user.email == \"mail2\""));
    }

    @Test
    public void testComputedGroupGetAll() throws Exception {
        GroupComputer gc = new ShibbolethGroupComputer();

        assertSame(0, gc.getAllGroupIds().size());
        createShibbGroup("group1", "");
        createShibbGroup("group2", "");
        createShibbGroup("group3", "");
        createShibbGroup("group4", "");

        assertSame(4, gc.getAllGroupIds().size());
    }

    @Test
    public void testComputedGroupGetGroupForUser() throws Exception {
        DocumentModel user = createUser("John");
        user.setProperty("user", "firstName", "test");
        user.setProperty("user", "email", "test");

        NuxeoPrincipalImpl nxp = new NuxeoPrincipalImpl("JDoh");
        nxp.setModel(user);

        GroupComputer gc = new ShibbolethGroupComputer();
        assertSame(0, gc.getGroupsForUser(nxp).size());

        createShibbGroup("group1", "currentUser.user.firstName == \"test\"");
        createShibbGroup("group2", "currentUser.user.firstName != \"test\"");
        createShibbGroup("group3", "currentUser.user.email == \"test\"");
        createShibbGroup("group4", "currentUser.user.email != \"test\"");

        assertSame(2, gc.getGroupsForUser(nxp).size());
    }

    @Test
    public void testValidElMethod() {
        assertFalse(ELGroupComputerHelper.isValidEL(""));
        assertFalse(ELGroupComputerHelper.isValidEL(null));

        assertTrue(ELGroupComputerHelper.isValidEL("currentUser.user.email != \"test\""));
        assertFalse(ELGroupComputerHelper.isValidEL("fdsfds ! fdsf^6"));
        // changed to assertTrue when switching from juel-impl to jboss-el
        // implementation: can't see why this would not be a valid EL
        assertTrue(ELGroupComputerHelper.isValidEL("testMethodCall == hello"));
        assertTrue(ELGroupComputerHelper.isValidEL("empty currentUser"));
    }

    protected DocumentModel createUser(String username) throws Exception {
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("username", username);
        DocumentModel doc = userDir.createEntry(user);
        return doc;
    }

    protected DocumentModel createShibbGroup(String name, String el) throws Exception {
        Map<String, Object> group = new HashMap<String, Object>();
        group.put("groupName", name);
        group.put("expressionLanguage", el);
        DocumentModel doc = groupDir.createEntry(group);
        return doc;
    }
}
