/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.xml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.id.NodeId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.security.authorization.AccessControlConstants;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.commons.conversion.NamePathResolver;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.JackrabbitSession;

import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.PropertyType;
import javax.jcr.Value;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import javax.jcr.security.AccessControlPolicy;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Stack;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.security.Principal;

/**
 * <code>AccessControlImporter</code> implements a
 * <code>ProtectedNodeImporter</code> that is able to deal with access control
 * content as defined by the default ac related node types present with
 * jackrabbit-core.
 */
public class AccessControlImporter extends DefaultProtectedNodeImporter {

    /**
     * logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(AccessControlImporter.class);

    private static final int STATUS_UNDEFINED = 0;
    private static final int STATUS_AC_FOLDER = 1;
    private static final int STATUS_PRINCIPAL_AC = 2;
    private static final int STATUS_ACL = 3;
    private static final int STATUS_ACE = 4;

    private static final Set<Name> ACE_NODETYPES = new HashSet<Name>(2);
    static {
        ACE_NODETYPES.add(AccessControlConstants.NT_REP_DENY_ACE);
        ACE_NODETYPES.add(AccessControlConstants.NT_REP_GRANT_ACE);
    }

    private final AccessControlManager acMgr;
    private final Stack<Integer> prevStatus = new Stack<Integer>();

    private int status = STATUS_UNDEFINED;
    private NodeImpl parent = null;

    private boolean principalbased = false;

    public AccessControlImporter(JackrabbitSession session, NamePathResolver resolver,
                                 boolean isWorkspaceImport, int uuidBehavior) throws RepositoryException {
        super(session, resolver, isWorkspaceImport, uuidBehavior);

        acMgr = session.getAccessControlManager();
    }

    public boolean start(NodeImpl protectedParent) throws RepositoryException {
        if (isStarted()) {
            // only ok if same parent
            if (!protectedParent.isSame(parent)) {
                throw new IllegalStateException();
            }
            return true;
        }
        if (isWorkspaceImport) {
            log.debug("AccessControlImporter may not be used with the WorkspaceImporter");
            return false;
        }
        if (!protectedParent.getDefinition().isProtected()) {
            log.debug("AccessControlImporter may not be started with a non-protected parent.");
            return false;
        }

        if (AccessControlConstants.N_POLICY.equals(protectedParent.getQName())
                && protectedParent.isNodeType(AccessControlConstants.NT_REP_ACL)) {
            status = STATUS_ACL;
        } else if (protectedParent.isNodeType(AccessControlConstants.NT_REP_ACCESS_CONTROL)) {
            status = STATUS_AC_FOLDER;
            principalbased = true;
        } // else: nothing this importer can deal with.

        if (isStarted()) {
            parent = protectedParent;
            return true;
        } else {
            return false;
        }
    }

    public boolean start(NodeState protectedParent) throws IllegalStateException, RepositoryException {
        if (isStarted()) {
            throw new IllegalStateException();
        }
        if (isWorkspaceImport) {
            log.debug("AccessControlImporter may not be used with the WorkspaceImporter");
            return false;
        }
        return false;
    }

    public void end(NodeImpl protectedParent) throws RepositoryException {
        if (!isStarted()) {
            return;
        }

        if (!principalbased) {
            checkStatus(STATUS_ACL, "");
        } else {
            checkStatus(STATUS_AC_FOLDER, "");
            if (!prevStatus.isEmpty()) {
                throw new ConstraintViolationException("Incomplete protected item tree: "+ prevStatus.size()+ " calls to 'endChildInfo' missing.");
            }
        }
        reset();
    }

    public void end(NodeState protectedParent) throws IllegalStateException, ConstraintViolationException, RepositoryException {
        // nothing to do. will never get here.
    }

    public void startChildInfo(NodeInfo childInfo, List<PropInfo> propInfos) throws RepositoryException {
        if (!isStarted()) {
            return;
        }
        
        Name ntName = childInfo.getNodeTypeName();
        int previousStatus = status;

        if (!principalbased) {
            checkStatus(STATUS_ACL, "Cannot handle childInfo " + childInfo + "; rep:ACL may only contain a single level of child nodes representing the ACEs");
            addACE(childInfo, propInfos);
            status = STATUS_ACE;
        } else {
            switch (status) {
                case STATUS_AC_FOLDER:
                    if (AccessControlConstants.NT_REP_ACCESS_CONTROL.equals(ntName)) {
                        // yet another intermediate node -> keep status
                    } else if (AccessControlConstants.NT_REP_PRINCIPAL_ACCESS_CONTROL.equals(ntName)) {
                        // the start of a principal-based acl
                        status = STATUS_PRINCIPAL_AC;
                    } else {
                        // illegal node type.
                        throw new ConstraintViolationException("Unexpected node type " + ntName + ". Should be rep:AccessControl or rep:PrincipalAccessControl.");
                    }
                    checkIdMixins(childInfo);
                    break;
                case STATUS_PRINCIPAL_AC:
                    if (AccessControlConstants.NT_REP_ACCESS_CONTROL.equals(ntName)) {
                        // some intermediate path between principal paths.
                        status = STATUS_AC_FOLDER;
                    } else if (AccessControlConstants.NT_REP_PRINCIPAL_ACCESS_CONTROL.equals(ntName)) {
                        // principal-based ac node underneath another one
                        // keep status
                    } else {
                        // the start the acl definition itself
                        checkDefinition(childInfo, AccessControlConstants.N_POLICY, AccessControlConstants.NT_REP_ACL);
                        status = STATUS_ACL;
                    }
                    checkIdMixins(childInfo);
                    break;
                case STATUS_ACL:
                    // nodeinfo must define an ACE
                    addACE(childInfo, propInfos);
                    status = STATUS_ACE;
                    break;
                default:
                    throw new ConstraintViolationException("Cannot handle childInfo " + childInfo + "; inexpected status " + status + " .");

            }
        }
        prevStatus.push(previousStatus);        
    }

    /**
     * @throws javax.jcr.RepositoryException
     */
    public void endChildInfo() throws RepositoryException {
        if (!isStarted()) {
            return;
        }

        // if the protected imported is started at an existing protected node
        // SessionImporter does not remember it on the stack of parents node.
        if (!principalbased) {
            // childInfo + props have already been handled
            // -> assert valid status
            // -> no further actions required.
            checkStatus(STATUS_ACE, "Upon completion of a NodeInfo the status must be STATUS_ACE.");
        }

        // reset the status
        status = prevStatus.pop();
    }


    private boolean isStarted() {
        return status > STATUS_UNDEFINED;
    }
    
    private void reset() {
        status = STATUS_UNDEFINED;
        parent = null;
    }

    private void checkStatus(int expectedStatus, String message) throws ConstraintViolationException {
        if (status != expectedStatus) {
            throw new ConstraintViolationException(message);
        }
    }

    private static void checkDefinition(NodeInfo nInfo, Name expName, Name expNodeTypeName) throws ConstraintViolationException {
        if (expName != null && !expName.equals(nInfo.getName())) {
            // illegal name
            throw new ConstraintViolationException("Unexpected Node name "+ nInfo.getName() +". Node name should be " + expName + ".");
        }
        if (expNodeTypeName != null && !expNodeTypeName.equals(nInfo.getNodeTypeName())) {
            // illegal name
            throw new ConstraintViolationException("Unexpected node type " + nInfo.getNodeTypeName() + ". Node type should be " + expNodeTypeName + ".");
        }
    }

    private static void checkIdMixins(NodeInfo nInfo) throws ConstraintViolationException {
        // neither explicit id NOR mixin types may be present.
        Name[] mixins = nInfo.getMixinNames();
        NodeId id = nInfo.getId();
        if (id != null || mixins != null) {
            throw new ConstraintViolationException("The node represented by NodeInfo " + nInfo + " may neither be referenceable nor have mixin types.");
        }
    }

    private void addACE(NodeInfo childInfo, List<PropInfo> propInfos) throws RepositoryException, UnsupportedRepositoryOperationException {

        // node type may only be rep:GrantACE or rep:DenyACE
        Name ntName = childInfo.getNodeTypeName();
        if (!ACE_NODETYPES.contains(ntName)) {
            throw new ConstraintViolationException("Cannot handle childInfo " + childInfo + "; expected a valid, applicable rep:ACE node definition.");
        }

        checkIdMixins(childInfo);

        boolean isAllow = AccessControlConstants.NT_REP_GRANT_ACE.equals(ntName);
        Principal principal = null;
        Privilege[] privileges = null;
        Map<String, TextValue> restrictions = new HashMap<String, TextValue>();

        for (PropInfo pInfo : propInfos) {
            Name name = pInfo.getName();
            if (AccessControlConstants.P_PRINCIPAL_NAME.equals(name)) {
                Value[] values = pInfo.getValues(PropertyType.STRING, resolver);
                if (values == null || values.length != 1) {
                    throw new ConstraintViolationException("");
                }
                principal = session.getPrincipalManager().getPrincipal(values[0].getString());
            } else if (AccessControlConstants.P_PRIVILEGES.equals(name)) {
                Value[] values = pInfo.getValues(PropertyType.NAME, resolver);
                privileges = new Privilege[values.length];
                for (int i = 0; i < values.length; i++) {
                    privileges[i] = acMgr.privilegeFromName(values[i].getString());
                }
            } else {
                TextValue[] txtVls = pInfo.getTextValues();
                for (TextValue txtV : txtVls) {
                    restrictions.put(resolver.getJCRName(name), txtV);
                }
            }
        }


        // try to access policies
        List<AccessControlPolicy> policies = new ArrayList<AccessControlPolicy>();
        if (!principalbased) {
            // no need to retrieve the applicable policies as the policy node
            // itself is the start point of the protected import.
            policies.addAll(Arrays.asList(acMgr.getPolicies(parent.getParent().getPath())));
        } else {
            if (acMgr instanceof JackrabbitAccessControlManager) {
                JackrabbitAccessControlManager jacMgr = (JackrabbitAccessControlManager) acMgr;
                policies.addAll(Arrays.asList(jacMgr.getPolicies(principal)));
                policies.addAll(Arrays.asList(jacMgr.getApplicablePolicies(principal)));
            }
        }

        for (AccessControlPolicy policy : policies) {
            if (policy instanceof JackrabbitAccessControlList) {
                JackrabbitAccessControlList acl = (JackrabbitAccessControlList) policy;
                // test if this acl can be used to apply the ACE
                boolean matches;
                if (!principalbased) {
                    // resource-based the acl-path must correspond to the path
                    // of the start-point of the protected import that was the
                    // policy node itself.
                    matches = parent.getParent().getPath().equals(acl.getPath());
                } else {
                    // principal based acl: just try the first one (TODO: check again)
                    matches = true;
                }

                if (matches) {
                    Map<String, Value> restr = new HashMap<String, Value>();
                    for (String restName : acl.getRestrictionNames()) {
                        TextValue txtVal = restrictions.remove(restName);
                        if (txtVal != null) {
                            restr.put(restName, txtVal.getValue(acl.getRestrictionType(restName), resolver));
                        }
                    }
                    if (!restrictions.isEmpty()) {
                        throw new ConstraintViolationException("ACE childInfo contained restrictions that could not be applied.");
                    }
                    acl.addEntry(principal, privileges, isAllow, restr);
                    acMgr.setPolicy(acl.getPath(), acl);
                    return;
                }

            }
        }

        // could not apply the ACE. No suitable ACL found.
        throw new ConstraintViolationException("Cannot handle childInfo " + childInfo + "; No policy found to apply the ACE.");        
    }
}